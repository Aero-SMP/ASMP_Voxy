use anyhow::{Context, Result};
use std::{
    collections::BTreeMap,
    path::Path,
    sync::{Arc, RwLock},
    time::Duration,
};
use voxy_rust_server::{
    config::Config,
    read_lock,
    regional::RegionalService,
    registry::Registry,
    server::{self, ServerState},
};

#[tokio::main(flavor = "multi_thread")]
async fn main() -> Result<()> {
    #[cfg(feature = "debug-diagnostics")]
    if std::env::args_os().any(|arg| arg == "--debug-diagnostics-self-check") {
        println!("{}", voxy_rust_server::diagnostics::self_check()?);
        return Ok(());
    }
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
    let data = Path::new("voxy-data");
    let registry = Arc::new(RwLock::new(Registry::open(data.join("catalog"))?));
    let catalog_id = read_lock(&registry)?.catalog_id();
    let dimensions = BTreeMap::new();
    let service = Arc::new(RegionalService::open(data, &dimensions, registry.clone())?);
    service.start(Duration::from_secs(1))?;
    let state = Arc::new(ServerState::new(&dimensions, catalog_id, service.clone()));

    let quic_identity = data.join("quic");
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
