use anyhow::bail;
use anyhow::{Context, Result};
use std::{
    collections::{BTreeMap, HashSet},
    sync::{Arc, RwLock},
    time::Duration,
};
use tokio::sync::broadcast;
use voxy_rust_server::{
    anvil::{AnvilWorld, discover_dimensions},
    config::Config,
    registry::Registry,
    scanner::{DimensionRuntime, poll_dimension, safe_dimension_name},
    server::{self, ServerState},
    store::Store,
};

#[tokio::main(flavor = "multi_thread")]
async fn main() -> Result<()> {
    let config = match Config::load() {
        Ok(config) => config,
        Err(error) if std::env::args_os().any(|arg| arg == "--help" || arg == "-h") => {
            println!("{error}");
            return Ok(());
        }
        Err(error) => return Err(error),
    };
    if config.rayon_threads != 0 {
        rayon::ThreadPoolBuilder::new()
            .num_threads(config.rayon_threads)
            .build_global()
            .context("configure Rayon worker pool")?;
    }
    let registry = Arc::new(RwLock::new(Registry::open(config.data.join("catalog"))?));
    let catalog_id = registry
        .read()
        .map_err(|_| anyhow::anyhow!("registry lock poisoned"))?
        .catalog_id();
    let dimensions = discover_dimensions(&config.world, &config.dimension)?;
    let mut runtimes = BTreeMap::new();
    let mut dimension_paths = HashSet::new();
    for dimension in dimensions {
        let safe_name = safe_dimension_name(&dimension.id);
        if !dimension_paths.insert(safe_name.clone()) {
            bail!("two dimension identifiers resolve to the same storage path: {safe_name}");
        }
        let dimension_data = config.data.join("worlds").join(safe_name);
        let store = Arc::new(Store::open(&dimension_data, catalog_id)?);
        let runtime = Arc::new(DimensionRuntime::new(
            AnvilWorld::new(dimension.id.clone(), dimension.root),
            store,
            &config.data,
        ));
        runtimes.insert(dimension.id, runtime);
    }
    let (updates, _) = broadcast::channel(65_536);
    if config.once {
        for runtime in runtimes.values() {
            let report = runtime
                .scan_once(&registry, &updates)
                .with_context(|| format!("initial scan of {}", runtime.anvil.dimension))?;
            eprintln!(
                "{}: initial scan generated {} sections ({} failures)",
                runtime.anvil.dimension, report.generated_sections, report.failures
            );
            runtime.store.checkpoint_all()?;
        }
        eprintln!("index-only scan complete");
        return Ok(());
    }

    // Dirty journals and unowned store records are tombstoned before the socket is exposed.
    // The expensive Anvil scan starts below in background workers, so healthy cached regions
    // remain immediately available while missing regions self-heal on the fly.
    for runtime in runtimes.values() {
        runtime
            .recover_before_serve()
            .with_context(|| format!("metadata recovery of {}", runtime.anvil.dimension))?;
        runtime.begin_serving();
    }

    let state = Arc::new(ServerState {
        registry: registry.clone(),
        dimensions: runtimes.clone(),
        updates: updates.clone(),
        trust_client_opacity: config.trust_client_opacity,
        catalog_id,
    });
    for runtime in runtimes.values() {
        tokio::spawn(poll_dimension(
            runtime.clone(),
            registry.clone(),
            updates.clone(),
            config.poll_interval,
        ));
    }
    let maintenance = runtimes.clone();
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(Duration::from_secs(600)).await;
            for runtime in maintenance.values() {
                if let Err(error) = runtime
                    .store
                    .checkpoint_all()
                    .and_then(|_| runtime.store.compact_if_needed().map(|_| ()))
                {
                    eprintln!("{} maintenance failed: {error:#}", runtime.anvil.dimension);
                }
            }
        }
    });

    tokio::select! {
        result = server::serve(state, config.transport) => result?,
        result = shutdown_signal() => result?,
    }
    for runtime in runtimes.values() {
        runtime.store.checkpoint_all()?;
    }
    Ok(())
}

async fn shutdown_signal() -> Result<()> {
    let mut terminate = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;
    tokio::select! {
        result = tokio::signal::ctrl_c() => result?,
        _ = terminate.recv() => {}
    }
    Ok(())
}
