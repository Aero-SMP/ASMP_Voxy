//! Direct and Minecraft-bridge transports.

use crate::{
    anvil::AnvilWorld,
    config::Transport,
    crc::crc32c,
    surface::{
        memory::ServerMemoryBudget,
        service::Service,
        wire::{C_HELLO, Frame, error},
    },
};
use anyhow::{Context, Result, bail};
use std::{
    collections::BTreeMap,
    os::unix::fs::{FileTypeExt, PermissionsExt},
    path::PathBuf,
    sync::Arc,
    time::Duration,
};
use tokio::{
    io::{AsyncRead, AsyncWrite},
    net::{TcpListener, UnixListener},
    sync::Semaphore,
};

const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);

trait AsyncSocket: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> AsyncSocket for T {}
type Socket = Box<dyn AsyncSocket>;

#[derive(Debug)]
pub struct ServerState {
    server_instance: u64,
    surface: Arc<Service>,
    memory: Arc<ServerMemoryBudget>,
    max_connections: usize,
}

impl ServerState {
    pub fn new(
        dimensions: &BTreeMap<String, Arc<AnvilWorld>>,
        catalog_id: u64,
        surface: Arc<Service>,
        memory: Arc<ServerMemoryBudget>,
        max_connections: usize,
    ) -> Self {
        let server_instance = dimensions
            .iter()
            .fold(catalog_id, |identity, (name, world)| {
                identity.rotate_left(11)
                    ^ u64::from(crc32c(name.as_bytes()))
                    ^ u64::from(crc32c(world.root.as_os_str().as_encoded_bytes()))
            });
        Self {
            server_instance,
            surface,
            memory,
            max_connections,
        }
    }
}

pub async fn serve(state: Arc<ServerState>, transport: Transport) -> Result<()> {
    match transport {
        Transport::Direct(listen) => serve_direct(state, listen).await,
        Transport::Minecraft(socket) => serve_minecraft(state, socket).await,
    }
}

async fn serve_direct(state: Arc<ServerState>, listen: std::net::SocketAddr) -> Result<()> {
    let listener = TcpListener::bind(listen)
        .await
        .with_context(|| format!("bind Voxy socket {listen}"))?;
    let slots = Arc::new(Semaphore::new(state.max_connections));
    eprintln!("VOXY_READY transport=direct");
    eprintln!("Voxy direct transport listening on {listen}");
    loop {
        let (socket, peer) = listener.accept().await?;
        let Ok(slot) = slots.clone().try_acquire_owned() else {
            continue;
        };
        if let Err(error) = socket.set_nodelay(true) {
            eprintln!("Voxy client {peer} socket setup failed: {error}");
            continue;
        }
        let state = state.clone();
        tokio::spawn(async move {
            let _slot = slot;
            if let Err(error) = connection(state, Box::new(socket)).await {
                eprintln!("Voxy client {peer} disconnected: {error:#}");
            }
        });
    }
}

async fn serve_minecraft(state: Arc<ServerState>, path: PathBuf) -> Result<()> {
    if let Ok(metadata) = std::fs::symlink_metadata(&path) {
        if !metadata.file_type().is_socket() {
            bail!("refusing to replace non-socket path {}", path.display());
        }
        std::fs::remove_file(&path)
            .with_context(|| format!("remove stale bridge socket {}", path.display()))?;
    }
    if let Some(parent) = path
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
    {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("create bridge socket directory {}", parent.display()))?;
    }
    let listener = UnixListener::bind(&path)
        .with_context(|| format!("bind Minecraft bridge socket {}", path.display()))?;
    std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600))
        .with_context(|| format!("secure Minecraft bridge socket {}", path.display()))?;
    let slots = Arc::new(Semaphore::new(state.max_connections));
    eprintln!("VOXY_READY transport=minecraft");
    eprintln!("Voxy Minecraft transport listening on {}", path.display());
    loop {
        let (socket, _) = listener.accept().await?;
        let Ok(slot) = slots.clone().try_acquire_owned() else {
            continue;
        };
        let state = state.clone();
        tokio::spawn(async move {
            let _slot = slot;
            if let Err(error) = connection(state, Box::new(socket)).await {
                eprintln!("Voxy Minecraft bridge session disconnected: {error:#}");
            }
        });
    }
}

async fn connection(state: Arc<ServerState>, mut socket: Socket) -> Result<()> {
    // Every transport enters the same wire handler with the same global budget. The small
    // session reservation bounds socket/task overhead before any client-controlled allocation.
    let _session_memory = state
        .memory
        .try_reserve(crate::surface::memory::MemoryClass::Network, 640 * 1024)?;
    let first = tokio::time::timeout(
        HANDSHAKE_TIMEOUT,
        Frame::read_client_budgeted(&mut socket, &state.memory),
    )
    .await
    .context("handshake timeout")??
    .context("connection closed before HELLO")?;
    let (first, hello_memory) = first.into_parts();
    if first.kind != C_HELLO {
        error(1, "HELLO must be the first frame")
            .write(&mut socket)
            .await?;
        bail!("first frame was not HELLO");
    }
    let dimension = crate::surface::wire::decode_client_hello(&first.payload)?;
    drop(first);
    drop(hello_memory);
    state
        .surface
        .clone()
        .connection(socket, dimension, state.server_instance)
        .await
}
