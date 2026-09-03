use anyhow::{Context, Result, bail};
use std::{
    collections::{BTreeMap, HashSet},
    sync::{Arc, RwLock},
};
use voxy_rust_server::{
    anvil::{AnvilWorld, discover_dimensions},
    config::Config,
    read_lock,
    regional::RegionalService,
    registry::Registry,
    safe_dimension_name,
    server::{self, ServerState},
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
    let catalog_id = read_lock(&registry)?.catalog_id();
    let discovered = discover_dimensions(&config.world, &config.dimension)?;
    let mut dimensions = BTreeMap::new();
    let mut paths = HashSet::new();
    for dimension in discovered {
        let safe = safe_dimension_name(&dimension.id);
        if !paths.insert(safe.clone()) {
            bail!("two dimension identifiers resolve to the same storage path: {safe}");
        }
        dimensions.insert(
            dimension.id.clone(),
            Arc::new(AnvilWorld::new(dimension.id, dimension.root)),
        );
    }
    let service = Arc::new(RegionalService::open(
        &config.data,
        &dimensions,
        registry.clone(),
    )?);
    if config.once {
        while service.refresh_all()? {}
        return Ok(());
    }
    service.start(config.poll_interval)?;
    let state = Arc::new(ServerState::new(&dimensions, catalog_id, service.clone()));

    let quic_identity = config.data.join("quic");
    server::serve(state, config.listen, &quic_identity, shutdown_signal()).await
}

async fn shutdown_signal() -> Result<()> {
    let mut terminate = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;
    tokio::select! {
        result = tokio::signal::ctrl_c() => result?,
        _ = terminate.recv() => {}
    }
    Ok(())
}
