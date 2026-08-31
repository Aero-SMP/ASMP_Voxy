use crate::{
    config::Transport,
    crc::crc32c,
    key::SectionKey,
    protocol::{
        C_BLOCK_PROPERTIES, C_CREDIT, C_HELLO, C_PING, C_SUBSCRIBE, Frame, HEADER_LEN, S_SECTION,
        SubscriptionBatch, WRITE_TIMEOUT, error, hello, invalidate, mapping_deltas,
        parse_block_properties, parse_credit, parse_hello, parse_nonce, parse_subscriptions, pong,
        resolution,
    },
    registry::Registry,
    scanner::{DimensionRuntime, Update, UpdateEvent},
    store::NetworkItem,
};
use anyhow::{Context, Result, bail};
use std::{
    collections::{BTreeMap, HashMap, HashSet, VecDeque},
    os::unix::fs::{FileTypeExt, PermissionsExt},
    path::PathBuf,
    sync::{Arc, RwLock},
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncRead, AsyncWrite, AsyncWriteExt, BufWriter, WriteHalf},
    net::{TcpListener, UnixListener},
    sync::{Semaphore, broadcast, mpsc},
};

const MAX_CONNECTIONS: usize = 256;
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
const READ_TIMEOUT: Duration = Duration::from_secs(90);
const MAX_CREDIT_BYTES: u64 = 128 * 1024 * 1024;
const MAX_SUBSCRIPTIONS: usize = 2_200_000;
// One blocking lookup job now matches the protocol's maximum resolution frame. Most warm-cache
// requests resolve without a payload, so smaller jobs only multiply scheduling and frame costs.
const LOOKUP_BATCH: usize = 256;
const LOOKUP_LOW_WATER: usize = 2 * 1024 * 1024;
const MAX_PENDING_BYTES: usize = 64 * 1024 * 1024;
trait AsyncSocket: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> AsyncSocket for T {}
type Socket = Box<dyn AsyncSocket>;
type Writer = BufWriter<WriteHalf<Socket>>;

#[derive(Debug)]
pub struct ServerState {
    pub registry: Arc<RwLock<Registry>>,
    pub dimensions: BTreeMap<String, Arc<DimensionRuntime>>,
    pub updates: broadcast::Sender<UpdateEvent>,
    pub trust_client_opacity: bool,
    pub catalog_id: u64,
}

impl ServerState {
    fn current_instance(&self) -> u64 {
        self.dimensions
            .iter()
            .fold(self.catalog_id, |identity, (name, runtime)| {
                identity.rotate_left(11)
                    ^ runtime.store.epoch().rotate_right(7)
                    ^ u64::from(crc32c(name.as_bytes()))
            })
    }
}

#[derive(Debug)]
enum Command {
    Subscriptions(SubscriptionBatch),
    Credit(u64),
    Ping(u64),
}

struct Pending {
    key: Option<u64>,
    frame: Frame,
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
        .with_context(|| format!("bind Voxy protocol socket {listen}"))?;
    let slots = Arc::new(Semaphore::new(MAX_CONNECTIONS));
    eprintln!("Voxy Rust direct transport listening on {listen}");
    loop {
        let (socket, peer) = listener.accept().await?;
        let Ok(slot) = slots.clone().try_acquire_owned() else {
            continue;
        };
        socket.set_nodelay(true)?;
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
    let slots = Arc::new(Semaphore::new(MAX_CONNECTIONS));
    eprintln!(
        "Voxy Rust Minecraft transport listening on {}",
        path.display()
    );
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
    let first = tokio::time::timeout(HANDSHAKE_TIMEOUT, Frame::read_client(&mut socket))
        .await
        .context("handshake timeout")??
        .context("connection closed before HELLO")?;
    if first.kind != C_HELLO {
        error(1, "HELLO must be the first frame")
            .write(&mut socket)
            .await?;
        bail!("first frame was not HELLO");
    }
    let capabilities = parse_hello(&first.payload)?;
    let epoch = state
        .registry
        .read()
        .map_err(|_| anyhow::anyhow!("registry lock poisoned"))?
        .generation() as u32;
    let server_instance = state.current_instance();
    hello(server_instance, 0, epoch, epoch)
        .write(&mut socket)
        .await?;

    let (mut reader, writer) = tokio::io::split(socket);
    let writer = BufWriter::with_capacity(256 * 1024, writer);
    let (commands_tx, commands_rx) = mpsc::channel(32);
    let reader_state = state.clone();
    let reader_task = tokio::spawn(async move {
        let mut frame_window = Instant::now();
        let mut frames = 0u32;
        loop {
            let Some(frame) = tokio::time::timeout(READ_TIMEOUT, Frame::read_client(&mut reader))
                .await
                .context("client read timeout")??
            else {
                return Ok::<(), anyhow::Error>(());
            };
            if frame_window.elapsed() >= Duration::from_secs(1) {
                frame_window = Instant::now();
                frames = 0;
            }
            frames += 1;
            if frames > 128 {
                bail!("client frame rate exceeded");
            }
            match frame.kind {
                C_SUBSCRIBE => {
                    commands_tx
                        .send(Command::Subscriptions(parse_subscriptions(&frame.payload)?))
                        .await?;
                }
                C_CREDIT => {
                    commands_tx
                        .send(Command::Credit(parse_credit(&frame.payload)?))
                        .await?
                }
                C_PING => {
                    commands_tx
                        .send(Command::Ping(parse_nonce(&frame.payload)?))
                        .await?
                }
                C_BLOCK_PROPERTIES => {
                    if !reader_state.trust_client_opacity {
                        continue;
                    }
                    let values = parse_block_properties(&frame.payload)?;
                    let registry = reader_state.registry.clone();
                    tokio::task::spawn_blocking(move || -> Result<()> {
                        let mut registry = registry
                            .write()
                            .map_err(|_| anyhow::anyhow!("registry lock poisoned"))?;
                        registry.apply_opacity_batch(&values)?;
                        Ok(())
                    })
                    .await??;
                }
                C_HELLO => bail!("duplicate HELLO"),
                other => bail!("unknown client frame type {other:#06x}"),
            }
        }
    });

    let writer_result =
        writer_loop(state, writer, commands_rx, capabilities, server_instance).await;
    reader_task.abort();
    writer_result
}

async fn writer_loop(
    state: Arc<ServerState>,
    mut writer: Writer,
    mut commands: mpsc::Receiver<Command>,
    _capabilities: u32,
    server_instance: u64,
) -> Result<()> {
    let mut updates = state.updates.subscribe();
    let mut dimension = None::<String>;
    let mut subscriptions = HashSet::<u64>::new();
    let mut lookups = VecDeque::<(u64, u64)>::new();
    let mut deferred_missing = HashMap::<u64, u64>::new();
    let mut pending = VecDeque::<Pending>::new();
    let mut pending_bytes = 0usize;
    let mut credit_stalled = None::<Instant>;
    let mut credit = 0u64;
    let mut sent_blocks = 0usize;
    let mut sent_biomes = 0usize;
    loop {
        ensure_instance(&state, server_instance)?;

        if !deferred_missing.is_empty()
            && dimension
                .as_ref()
                .and_then(|name| state.dimensions.get(name))
                .is_some_and(|runtime| !runtime.is_reconciling())
        {
            lookups.extend(deferred_missing.drain());
        }

        let mut wrote = false;
        while let Some(item) = pending.front() {
            if item.key.is_some_and(|key| !subscriptions.contains(&key)) {
                pending_bytes -= item.frame.payload.len() + HEADER_LEN;
                pending.pop_front();
                continue;
            }
            let cost = (item.frame.payload.len() + HEADER_LEN) as u64;
            if cost > credit {
                break;
            }
            let item = pending.pop_front().unwrap();
            pending_bytes -= cost as usize;
            credit -= cost;
            item.frame.write(&mut writer).await?;
            wrote = true;
        }
        if wrote {
            credit_stalled = None;
            flush(&mut writer).await?;
        }
        if let Some(item) = pending.front() {
            if (item.frame.payload.len() + HEADER_LEN) as u64 > credit {
                let since = credit_stalled.get_or_insert_with(Instant::now);
                if since.elapsed() > WRITE_TIMEOUT {
                    bail!("client section credit stalled");
                }
            }
        } else {
            credit_stalled = None;
        }

        if pending_bytes < LOOKUP_LOW_WATER && !lookups.is_empty() {
            let mut batch = Vec::with_capacity(LOOKUP_BATCH);
            while batch.len() < LOOKUP_BATCH {
                let Some(request) = lookups.pop_front() else {
                    break;
                };
                if subscriptions.contains(&request.0) {
                    batch.push(request);
                }
            }
            if !batch.is_empty() {
                let Some(name) = dimension.as_ref() else {
                    continue;
                };
                let runtime = state
                    .dimensions
                    .get(name)
                    .with_context(|| format!("unknown or unavailable dimension {name}"))?;
                send_mappings(&state, &mut writer, &mut sent_blocks, &mut sent_biomes).await?;
                let store = runtime.store.clone();
                let items =
                    tokio::task::spawn_blocking(move || store.requested_items(&batch)).await??;
                ensure_instance(&state, server_instance)?;
                send_mappings(&state, &mut writer, &mut sent_blocks, &mut sent_biomes).await?;
                let rebuilding = runtime.is_reconciling();
                let mut ready = Vec::with_capacity(items.len());
                for item in items {
                    match item {
                        NetworkItem::Missing(requests) if rebuilding => {
                            deferred_missing.extend(
                                requests
                                    .into_iter()
                                    .filter(|request| subscriptions.contains(&request.0)),
                            );
                        }
                        NetworkItem::Missing(requests) => ready.push(NetworkItem::Resolved(
                            requests.into_iter().map(|request| request.0).collect(),
                        )),
                        NetworkItem::Section(payload) => {
                            deferred_missing.remove(&payload_key(&payload));
                            ready.push(NetworkItem::Section(payload));
                        }
                        NetworkItem::Invalidate(value) => {
                            deferred_missing.remove(&value.key);
                            ready.push(NetworkItem::Invalidate(value));
                        }
                        item => ready.push(item),
                    }
                }
                enqueue_items(&mut pending, &mut pending_bytes, ready)?;
                continue;
            }
        }

        tokio::select! {
            command = commands.recv() => match command {
                Some(Command::Ping(nonce)) => {
                    pong(nonce).write(&mut writer).await?;
                    flush(&mut writer).await?;
                }
                Some(Command::Credit(amount)) => {
                    if amount == 0 || amount > MAX_CREDIT_BYTES {
                        bail!("invalid client credit grant {amount}");
                    }
                    credit = credit.saturating_add(amount).min(MAX_CREDIT_BYTES);
                }
                Some(Command::Subscriptions(batch)) => {
                    if !state.dimensions.contains_key(&batch.dimension) {
                        error(6, "unknown or unavailable dimension").write(&mut writer).await?;
                        flush(&mut writer).await?;
                        continue;
                    }
                    if dimension.as_ref() != Some(&batch.dimension) {
                        dimension = Some(batch.dimension.clone());
                        subscriptions.clear();
                        lookups.clear();
                        deferred_missing.clear();
                        pending.clear();
                        pending_bytes = 0;
                    }
                    for key in batch.removals {
                        SectionKey::unpack(key)?;
                        subscriptions.remove(&key);
                        deferred_missing.remove(&key);
                    }
                    for (key, known_revision) in batch.additions {
                        SectionKey::unpack(key)?;
                        if subscriptions.insert(key) {
                            if subscriptions.len() > MAX_SUBSCRIPTIONS {
                                bail!("client subscription limit exceeded");
                            }
                            lookups.push_back((key, known_revision));
                        }
                    }
                }
                None => return Ok(()),
            },
            update = updates.recv() => match update {
                Ok(update) => {
                    ensure_instance(&state, server_instance)?;
                    let key = update_key(&update);
                    if dimension.as_deref() == Some(&update.dimension) && subscriptions.contains(&key) {
                        match update.change {
                            Update::Invalidate(value) => {
                                deferred_missing.remove(&key);
                                enqueue_items(
                                    &mut pending,
                                    &mut pending_bytes,
                                    vec![NetworkItem::Invalidate(value)],
                                )?;
                            }
                            Update::Section(_) => {
                                deferred_missing.remove(&key);
                                lookups.push_back((key, u64::MAX));
                            }
                        }
                    }
                }
                Err(broadcast::error::RecvError::Lagged(_)) => {
                    error(7, "update stream lagged; reconnect to reconcile subscriptions")
                        .write(&mut writer)
                        .await?;
                    flush(&mut writer).await?;
                    bail!("client update stream lagged");
                }
                Err(broadcast::error::RecvError::Closed) => return Ok(()),
            },
            _ = tokio::time::sleep(Duration::from_secs(1)) => {}
        }
    }
}

async fn flush(writer: &mut Writer) -> Result<()> {
    tokio::time::timeout(WRITE_TIMEOUT, writer.flush())
        .await
        .context("client buffer flush timed out")??;
    Ok(())
}

async fn send_mappings(
    state: &ServerState,
    writer: &mut Writer,
    sent_blocks: &mut usize,
    sent_biomes: &mut usize,
) -> Result<()> {
    let snapshot = {
        let mut registry = state
            .registry
            .write()
            .map_err(|_| anyhow::anyhow!("registry lock poisoned"))?;
        // Never expose an ID that can be reused after a crash. Anvil parsing appends mappings
        // before section publication; make that catalog generation durable before snapshotting.
        registry.save()?;
        if registry.counts() == (*sent_blocks, *sent_biomes) {
            None
        } else {
            Some(registry.snapshot())
        }
    };
    let Some(snapshot) = snapshot else {
        return Ok(());
    };
    for frame in mapping_deltas(&snapshot, *sent_blocks, *sent_biomes)? {
        frame.write(writer).await?;
    }
    *sent_blocks = snapshot.blocks.len();
    *sent_biomes = snapshot.biomes.len();
    Ok(())
}

fn ensure_instance(state: &ServerState, expected: u64) -> Result<()> {
    if state.current_instance() != expected {
        bail!("durable store identity changed; reconnect for a clean cache epoch");
    }
    Ok(())
}

fn update_key(update: &UpdateEvent) -> u64 {
    match update.change {
        Update::Invalidate(value) => value.key,
        Update::Section(value) => value.key,
    }
}

fn enqueue_items(
    pending: &mut VecDeque<Pending>,
    bytes: &mut usize,
    items: Vec<NetworkItem>,
) -> Result<()> {
    for item in items {
        let (key, frame) = match item {
            NetworkItem::Section(payload) => (
                Some(payload_key(&payload)),
                Frame {
                    kind: S_SECTION,
                    payload,
                },
            ),
            NetworkItem::Invalidate(value) => (
                Some(value.key),
                invalidate(value.key, value.revision, value.reason),
            ),
            NetworkItem::Resolved(keys) => (None, resolution(&keys)?),
            NetworkItem::Missing(_) => bail!("unresolved store miss reached the network queue"),
        };
        *bytes = bytes
            .checked_add(frame.payload.len() + HEADER_LEN)
            .context("pending response byte count overflow")?;
        if *bytes > MAX_PENDING_BYTES {
            bail!("client pending response limit exceeded");
        }
        pending.push_back(Pending { key, frame });
    }
    Ok(())
}

fn payload_key(payload: &[u8]) -> u64 {
    u64::from_le_bytes(payload[..8].try_into().unwrap())
}
