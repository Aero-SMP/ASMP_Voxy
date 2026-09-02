use anyhow::{Context, Result, bail};
use std::{
    collections::{BTreeMap, HashSet},
    sync::{Arc, RwLock},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use voxy_rust_server::{
    anvil::{AnvilWorld, discover_dimensions},
    config::Config,
    read_lock,
    registry::Registry,
    safe_dimension_name,
    server::{self, ServerState},
    surface::{
        gc::{GcMoment, GcPolicy},
        service::Service,
    },
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
    let registry = Arc::new(RwLock::new(Registry::open(
        config.data.join("surface").join("catalog"),
    )?));
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
            Arc::new(AnvilWorld::new(
                dimension.id,
                dimension.root,
                config.world.clone(),
            )),
        );
    }
    let service = Arc::new(Service::open_with_policies(
        &config.data,
        &dimensions,
        registry.clone(),
        config.poll_interval,
        &config.visibility_policies,
    )?);
    if config.once {
        service.refresh_all()?;
        return Ok(());
    }
    service.start()?;
    let state = Arc::new(ServerState::new(&dimensions, catalog_id, service.clone()));

    let maintenance = service.clone();
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(Duration::from_secs(600)).await;
            let service = maintenance.clone();
            let result = tokio::task::spawn_blocking(move || {
                let now = SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .context("system clock is before the Unix epoch")?
                    .as_secs();
                for dimension in service.dimensions() {
                    match service.collect_garbage(
                        dimension,
                        GcMoment { unix_seconds: now },
                        GcPolicy::default(),
                    ) {
                        Ok(Some(report)) if report.switched || report.reclaimed_objects != 0 => {
                            eprintln!(
                                "{dimension}: surface GC retained {} and reclaimed {} objects",
                                report.retained_objects, report.reclaimed_objects
                            );
                        }
                        Ok(Some(_)) | Ok(None) => {}
                        Err(error) => eprintln!("{dimension}: surface GC failed safely: {error:#}"),
                    }
                }
                Ok::<_, anyhow::Error>(())
            })
            .await;
            match result {
                Ok(Ok(())) => {}
                Ok(Err(error)) => eprintln!("surface maintenance failed safely: {error:#}"),
                Err(error) => eprintln!("surface maintenance worker failed: {error}"),
            }
        }
    });

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
