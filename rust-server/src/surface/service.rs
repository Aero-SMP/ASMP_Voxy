//! Live publication and object service.
//!
//! Recovered roots are announced immediately, while each dimension polls its authoritative Anvil
//! source and transactionally publishes complete roots when source content changes.

use super::{
    gc::{GcMoment, GcPolicy, GcRunReport},
    memory::{MemoryClass, MemoryPermit, MemoryPressure, ServerMemoryBudget},
    object::{ObjectHash, ObjectKind},
    root::RootRecord,
    runtime::{DimensionSurface, LeasedDictionaryMismatch},
    visibility::DimensionVisibilityPolicy,
    wire::{
        C_CAMERA_DOMAIN, C_CREDIT, C_HELLO, C_OBJECT_REQUEST, C_PING, C_ROOT_READY,
        C_SUBTREE_REQUEST, CameraDomainState, Frame, HEADER_LEN, MAX_BUNDLE_ENTRIES,
        MAX_CANONICAL_OBJECT_BYTES, MAX_FRAME_PAYLOAD, MAX_MANIFEST_BYTES, Message, RootToken,
        S_OBJECT_BUNDLE, S_SUBTREE_DATA, WRITE_TIMEOUT, WireObject, error, hello,
        parse_control_u64, pong,
    },
};
use crate::{anvil::AnvilWorld, lock, read_lock, registry::Registry, write_lock};
use anyhow::{Context, Result, bail};
use std::{
    collections::{BTreeMap, HashMap, HashSet, VecDeque},
    fmt,
    path::Path,
    sync::{
        Arc, Mutex, RwLock, Weak,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncRead, AsyncWrite, AsyncWriteExt, BufWriter},
    sync::{broadcast, mpsc},
};

const READ_TIMEOUT: Duration = Duration::from_secs(90);
const MEMORY_RETRY_DELAY: Duration = Duration::from_millis(5);
const MAX_CREDIT_BYTES: u64 = 32 * 1024 * 1024;
const MAX_PENDING_BYTES: usize = 20 * 1024 * 1024;
const RESPONSE_LOW_WATER: usize = 4 * 1024 * 1024;
// One canonical decode, stored compressed input, encoded frame growth/copy, dictionary-set
// metadata, and bounded codec scratch. This permit lives until the final encoded frame has
// taken ownership, then shrinks to the bytes retained in the pending output queue.
const RESPONSE_WORKING_BYTES: usize =
    MAX_CANONICAL_OBJECT_BYTES + 4 * MAX_FRAME_PAYLOAD + 8 * 1024 * 1024;
const MAX_OUTSTANDING_HASHES: usize = 8 * 1024;
const MAX_CLIENT_FRAMES_PER_SECOND: u32 = 128;
const ANNOUNCEMENT_CAPACITY: usize = 1_024;
const COMMAND_CAPACITY: usize = 32;
// Generation + dimension hash + root hash + object count.
const BUNDLE_FIXED_BYTES: usize = 8 + 2 * 32 + 2;
const OBJECT_WIRE_HEADER_BYTES: usize = 32 + 17;

#[derive(Debug, Default)]
struct SurfaceState {
    current: Option<RootRecord>,
    previous: Option<RootRecord>,
    bad: HashSet<RootToken>,
}

impl SurfaceState {
    /// The newest verified root is preferred, but a known-bad current generation must not turn
    /// a recoverable restart into an empty world.  `previous` was fully verified when it was
    /// retained, and a later request failure will withhold it as well if it has since decayed.
    fn visible(&self) -> Option<RootRecord> {
        [self.current, self.previous]
            .into_iter()
            .flatten()
            .find(|root| !self.bad.contains(&RootToken::from(*root)))
    }

    /// Roots named by the announcement state are GC pins even when the runtime has already
    /// built several unannounced replacements.  This closes the rebuild/catch-up window where a
    /// newly connected client could otherwise be offered a root whose immutable objects had
    /// just been collected.
    fn retained_roots(&self) -> Vec<RootRecord> {
        let mut roots = [self.current, self.previous]
            .into_iter()
            .flatten()
            .collect::<Vec<_>>();
        roots.sort_unstable_by_key(|root| root.generation);
        roots.dedup();
        roots
    }

    fn is_bad(&self, token: RootToken) -> bool {
        self.bad.contains(&token)
    }
}

#[derive(Clone, Debug)]
struct Announcement {
    dimension: String,
    root: RootRecord,
}

struct SurfaceService {
    dimension: String,
    surface: Arc<DimensionSurface>,
    announced: RwLock<SurfaceState>,
    repair: mpsc::Sender<()>,
}

impl SurfaceService {
    fn publish(&self, root: RootRecord) -> Result<bool> {
        let expected = ObjectHash::dimension(&self.dimension)?;
        if root.dimension != expected {
            bail!("surface root belongs to a different dimension");
        }
        let mut state = write_lock(&self.announced)?;
        let token = RootToken::from(root);
        if state.current == Some(root) {
            return Ok(state.bad.remove(&token));
        }
        if state
            .current
            .is_some_and(|current| root.generation <= current.generation)
        {
            bail!("surface root generation did not advance monotonically");
        }
        state.previous = state.current;
        state.current = Some(root);
        let retained = [state.current, state.previous];
        state.bad.retain(|bad| {
            retained
                .into_iter()
                .flatten()
                .any(|record| RootToken::from(record) == *bad)
        });
        state.bad.remove(&token);
        Ok(true)
    }

    fn current(&self) -> Result<Option<RootRecord>> {
        let state = read_lock(&self.announced)?;
        Ok(state.visible())
    }

    fn retained_roots(&self) -> Result<Vec<RootRecord>> {
        Ok(read_lock(&self.announced)?.retained_roots())
    }

    fn is_bad(&self, token: RootToken) -> Result<bool> {
        Ok(read_lock(&self.announced)?.is_bad(token))
    }

    fn serviceable_announcement(&self, token: RootToken) -> Result<bool> {
        let state = read_lock(&self.announced)?;
        Ok(!state.bad.contains(&token)
            && [state.current, state.previous]
                .into_iter()
                .flatten()
                .any(|record| RootToken::from(record) == token))
    }

    fn request_repair(&self, token: RootToken) -> Result<()> {
        {
            let mut state = write_lock(&self.announced)?;
            if [state.current, state.previous]
                .into_iter()
                .flatten()
                .any(|record| RootToken::from(record) == token)
            {
                state.bad.insert(token);
            }
        }
        match self.repair.try_send(()) {
            Ok(()) | Err(mpsc::error::TrySendError::Full(())) => Ok(()),
            Err(mpsc::error::TrySendError::Closed(())) => {
                bail!("surface repair publisher is unavailable")
            }
        }
    }
}

struct PublisherWorker {
    surface: Arc<SurfaceService>,
    repairs: mpsc::Receiver<()>,
}

/// All dimension surfaces and their publication workers.
pub struct Service {
    registry: Arc<RwLock<Registry>>,
    surfaces: BTreeMap<String, Arc<SurfaceService>>,
    announcements: broadcast::Sender<Announcement>,
    workers: Mutex<Option<Vec<PublisherWorker>>>,
    poll_interval: Duration,
    sessions: Mutex<HashMap<u64, Vec<RootRecord>>>,
    next_session: AtomicU64,
    memory: Arc<ServerMemoryBudget>,
}

impl fmt::Debug for Service {
    fn fmt(&self, output: &mut fmt::Formatter<'_>) -> fmt::Result {
        output
            .debug_struct("Service")
            .field("dimensions", &self.surfaces.keys().collect::<Vec<_>>())
            .finish_non_exhaustive()
    }
}

impl Service {
    pub fn dimensions(&self) -> impl Iterator<Item = &str> {
        self.surfaces.keys().map(String::as_str)
    }

    /// Opens exactly one immutable surface for every discovered dimension. Update receivers are
    /// created synchronously here, before `start` can launch a potentially long initial build.
    pub fn open(
        data_root: impl AsRef<Path>,
        dimensions: &BTreeMap<String, Arc<AnvilWorld>>,
        registry: Arc<RwLock<Registry>>,
        poll_interval: Duration,
    ) -> Result<Self> {
        Self::open_with_budget(
            data_root,
            dimensions,
            registry,
            poll_interval,
            ServerMemoryBudget::default_budget(),
        )
    }

    pub fn open_with_budget(
        data_root: impl AsRef<Path>,
        dimensions: &BTreeMap<String, Arc<AnvilWorld>>,
        registry: Arc<RwLock<Registry>>,
        poll_interval: Duration,
        memory: Arc<ServerMemoryBudget>,
    ) -> Result<Self> {
        Self::open_with_budget_and_policies(
            data_root,
            dimensions,
            registry,
            poll_interval,
            memory,
            &BTreeMap::new(),
        )
    }

    pub fn open_with_budget_and_policies(
        data_root: impl AsRef<Path>,
        dimensions: &BTreeMap<String, Arc<AnvilWorld>>,
        registry: Arc<RwLock<Registry>>,
        poll_interval: Duration,
        memory: Arc<ServerMemoryBudget>,
        visibility_policies: &BTreeMap<String, DimensionVisibilityPolicy>,
    ) -> Result<Self> {
        let (announcements, _) = broadcast::channel(ANNOUNCEMENT_CAPACITY);
        let mut surfaces = BTreeMap::new();
        let mut workers = Vec::with_capacity(dimensions.len());
        for (dimension, source) in dimensions {
            let (repair, repairs) = mpsc::channel(1);
            let visibility_policy = DimensionVisibilityPolicy::configured(
                dimension,
                visibility_policies.get(dimension).copied(),
            )?;
            let runtime_surface = Arc::new(DimensionSurface::open_with_budget_and_policy(
                data_root.as_ref(),
                dimension.clone(),
                source.clone(),
                memory.clone(),
                visibility_policy,
            )?);
            let recovered_current = runtime_surface.current_root()?;
            let recovered_previous = runtime_surface.previous_root()?;
            let surface = Arc::new(SurfaceService {
                dimension: dimension.clone(),
                surface: runtime_surface,
                announced: RwLock::new(SurfaceState {
                    current: recovered_current,
                    previous: recovered_previous,
                    bad: HashSet::new(),
                }),
                repair,
            });
            workers.push(PublisherWorker {
                surface: surface.clone(),
                repairs,
            });
            surfaces.insert(dimension.clone(), surface);
        }
        Ok(Self {
            registry,
            surfaces,
            announcements,
            workers: Mutex::new(Some(workers)),
            poll_interval,
            sessions: Mutex::new(HashMap::new()),
            next_session: AtomicU64::new(1),
            memory,
        })
    }

    /// Starts each pre-subscribed publisher exactly once.
    pub fn start(self: &Arc<Self>) -> Result<()> {
        let workers = lock(&self.workers)?
            .take()
            .context("surface publication service was already started")?;
        for worker in workers {
            let service = self.clone();
            tokio::spawn(async move {
                if let Err(error) = service.publisher_loop(worker).await {
                    eprintln!("surface publication worker stopped: {error:#}");
                }
            });
        }
        Ok(())
    }

    /// Synchronously refreshes every dimension, used by the finite one-shot invocation before
    /// any network listener exists.
    pub fn refresh_all(&self) -> Result<()> {
        for surface in self.surfaces.values() {
            surface.surface.refresh(&self.registry)?;
        }
        Ok(())
    }

    async fn publisher_loop(self: Arc<Self>, mut worker: PublisherWorker) -> Result<()> {
        let mut repair_pending = false;
        loop {
            let surface = worker.surface.surface.clone();
            let registry = self.registry.clone();
            let repairing = repair_pending;
            match tokio::task::spawn_blocking(move || {
                if repairing {
                    surface.repair(&registry)
                } else {
                    surface.refresh(&registry)
                }
            })
            .await
            {
                Ok(Ok(report)) => {
                    repair_pending = false;
                    if let Some(root) = worker.surface.surface.current_root()? {
                        self.announce(&worker.surface, root, repairing)?;
                    }
                    if report.pending {
                        continue;
                    }
                }
                Ok(Err(error)) => eprintln!(
                    "{} publication failed safely; retaining current root: {error:#}",
                    worker.surface.dimension
                ),
                Err(error) => eprintln!(
                    "{} publication task failed: {error}",
                    worker.surface.dimension
                ),
            }
            tokio::select! {
                repair = worker.repairs.recv() => {
                    if repair.is_none() {
                        return Ok(());
                    }
                    repair_pending = true;
                }
                _ = tokio::time::sleep(self.poll_interval) => {}
            }
        }
    }

    fn announce(&self, surface: &Arc<SurfaceService>, root: RootRecord, force: bool) -> Result<()> {
        if surface.publish(root)? || force {
            let _ = self.announcements.send(Announcement {
                dimension: surface.dimension.clone(),
                root,
            });
        }
        Ok(())
    }

    fn root_snapshot_for(&self, dimension: &str) -> Result<Option<Announcement>> {
        let surface = self
            .surfaces
            .get(dimension)
            .with_context(|| format!("unknown surface dimension {dimension}"))?;
        Ok(surface.current()?.map(|root| Announcement {
            dimension: dimension.to_owned(),
            root,
        }))
    }

    fn register_session(self: &Arc<Self>) -> Result<SessionPinGuard> {
        let id = self
            .next_session
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |id| id.checked_add(1))
            .map_err(|_| anyhow::anyhow!("surface session identity exhausted"))?;
        lock(&self.sessions)?.insert(id, Vec::new());
        Ok(SessionPinGuard {
            service: Arc::downgrade(self),
            id,
        })
    }

    fn update_session_pins(&self, id: u64, roots: &SessionRoots) -> Result<()> {
        let mut sessions = lock(&self.sessions)?;
        let entry = sessions
            .get_mut(&id)
            .context("surface session pin record disappeared")?;
        *entry = roots.pinned();
        Ok(())
    }

    /// Runs one conservative GC cycle for a serviceable dimension. A withheld surface is
    /// skipped: repair must re-establish a verified root before any reachability decision.
    pub fn collect_garbage(
        &self,
        dimension: &str,
        moment: GcMoment,
        policy: GcPolicy,
    ) -> Result<Option<GcRunReport>> {
        let surface = self
            .surfaces
            .get(dimension)
            .with_context(|| format!("unknown surface dimension {dimension}"))?;
        if surface.current()?.is_none() {
            return Ok(None);
        }
        // `DimensionSurface` pins only its own current/previous records.  During a long
        // rebuild-and-catch-up it can advance past the last root advertised by this service, so
        // retain both announcement records explicitly in addition to live session leases.
        let mut retained = surface.retained_roots()?;
        let identity = ObjectHash::dimension(dimension)?;
        retained.extend(
            lock(&self.sessions)?
                .values()
                .flatten()
                .copied()
                .filter(|root| root.dimension == identity),
        );
        retained.sort_unstable_by_key(|root| root.generation);
        retained.dedup();
        surface
            .surface
            .collect_live(retained, moment, policy)
            .map(Some)
    }

    /// Runs one dimension-bound terrain connection. Roots from other dimensions are never
    /// announced or leased.
    pub async fn connection<S>(
        self: Arc<Self>,
        mut socket: S,
        selected_dimension: String,
        server_instance: u64,
    ) -> Result<()>
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        hello(server_instance).write(&mut socket).await?;

        // Subscribe first, then snapshot. A publication racing this boundary is either present
        // in the snapshot or retained by the receiver; duplicate announcements are harmless.
        let announcements = self.announcements.subscribe();
        let initial = match self.root_snapshot_for(&selected_dimension) {
            Ok(Some(root)) => vec![root],
            Ok(None) => {
                error(8, "selected surface dimension has no verified root")
                    .write(&mut socket)
                    .await?;
                flush(&mut socket).await?;
                return Ok(());
            }
            Err(failure) => {
                error(
                    8,
                    &format!("selected surface dimension is unavailable: {failure}"),
                )
                .write(&mut socket)
                .await?;
                flush(&mut socket).await?;
                return Ok(());
            }
        };
        let session_guard = self.register_session()?;
        let (mut reader, writer) = tokio::io::split(socket);
        let mut writer = BufWriter::with_capacity(256 * 1024, writer);
        let (commands_tx, commands_rx) = mpsc::channel(COMMAND_CAPACITY);
        let memory = self.memory.clone();
        let reader_task = tokio::spawn(async move {
            let mut frame_window = Instant::now();
            let mut frames = 0u32;
            loop {
                let Some(frame) = tokio::time::timeout(
                    READ_TIMEOUT,
                    Frame::read_client_budgeted(&mut reader, &memory),
                )
                .await
                .context("surface client read timeout")??
                else {
                    return Ok::<(), anyhow::Error>(());
                };
                if frame_window.elapsed() >= Duration::from_secs(1) {
                    frame_window = Instant::now();
                    frames = 0;
                }
                frames += 1;
                if frames > MAX_CLIENT_FRAMES_PER_SECOND {
                    bail!("surface client frame rate exceeded");
                }
                let (frame, memory) = frame.into_parts();
                let command = match frame.kind {
                    C_CREDIT => Command::Credit(parse_control_u64(&frame.payload)?),
                    C_PING => Command::Ping(parse_control_u64(&frame.payload)?),
                    C_SUBTREE_REQUEST | C_OBJECT_REQUEST | C_ROOT_READY | C_CAMERA_DOMAIN => {
                        Command::Message(Message::decode(frame.kind, &frame.payload)?)
                    }
                    C_HELLO => bail!("duplicate HELLO"),
                    other => bail!("unknown client frame type {other:#06x}"),
                };
                commands_tx
                    .send(BudgetedCommand {
                        command,
                        _memory: memory,
                    })
                    .await?;
            }
        });

        let result = self
            .writer_loop(
                &mut writer,
                commands_rx,
                announcements,
                initial,
                selected_dimension,
                session_guard.id,
            )
            .await;
        reader_task.abort();
        result
    }

    async fn writer_loop<W>(
        self: &Arc<Self>,
        writer: &mut BufWriter<W>,
        mut commands: mpsc::Receiver<BudgetedCommand>,
        mut announcements: broadcast::Receiver<Announcement>,
        initial: Vec<Announcement>,
        selected_dimension: String,
        session_id: u64,
    ) -> Result<()>
    where
        W: AsyncWrite + Unpin,
    {
        let mut session_roots = SessionRoots::default();
        for announcement in initial {
            self.send_announcement(writer, &mut session_roots, session_id, announcement)
                .await?;
        }
        flush(writer).await?;

        let mut jobs = VecDeque::<RequestJob>::new();
        let mut outstanding_hashes = 0usize;
        let mut pending = VecDeque::<PendingFrame>::new();
        let mut pending_bytes = 0usize;
        let mut credit = 0u64;
        let mut credit_stalled = None::<Instant>;
        let mut camera_sequences = HashMap::<RootToken, u64>::new();
        loop {
            let mut memory_stalled = false;
            let mut wrote = false;
            while let Some(item) = pending.front() {
                if !session_roots.is_requestable(item.root) {
                    let item = pending.pop_front().unwrap();
                    pending_bytes -= pending_frame_bytes(&item.frame);
                    continue;
                }
                let cost = (item.frame.payload.len() + HEADER_LEN) as u64;
                if cost > credit {
                    break;
                }
                let item = pending.pop_front().unwrap();
                pending_bytes -= pending_frame_bytes(&item.frame);
                credit -= cost;
                item.frame.write(writer).await?;
                wrote = true;
            }
            if wrote {
                credit_stalled = None;
                flush(writer).await?;
            }
            if let Some(item) = pending.front() {
                if (item.frame.payload.len() + HEADER_LEN) as u64 > credit {
                    let since = credit_stalled.get_or_insert_with(Instant::now);
                    if since.elapsed() > WRITE_TIMEOUT {
                        bail!("surface client object credit stalled");
                    }
                }
            } else {
                credit_stalled = None;
            }

            if pending_bytes < RESPONSE_LOW_WATER && !jobs.is_empty() {
                let response_memory = match self
                    .memory
                    .try_reserve(MemoryClass::Network, RESPONSE_WORKING_BYTES)
                {
                    Ok(memory) => Some(memory),
                    Err(_) => {
                        memory_stalled = true;
                        None
                    }
                };
                if let Some(response_memory) = response_memory {
                    let mut job = jobs.pop_front().expect("job queue was checked above");
                    if !session_roots.is_requestable(job.root) {
                        outstanding_hashes = outstanding_hashes
                            .checked_sub(job.remaining())
                            .context("surface canceled request accounting underflow")?;
                        continue;
                    }
                    let original_remaining = job.remaining();
                    let service = self.clone();
                    let (job, built) = tokio::task::spawn_blocking(move || {
                        let frame = service.build_response(&mut job, response_memory);
                        (job, frame)
                    })
                    .await?;
                    let frame = match built {
                        Ok(frame) => frame,
                        Err(failure) => {
                            if is_memory_pressure(&failure) {
                                // Pressure is transient scheduling state, not evidence that the
                                // immutable root is corrupt. Preserve the request and root lease;
                                // the bounded delay prevents a CPU spin if another priority owns the
                                // global budget for an extended operation.
                                jobs.push_front(job);
                                tokio::time::sleep(MEMORY_RETRY_DELAY).await;
                                continue;
                            }
                            if is_request_rejected(&failure) {
                                outstanding_hashes = outstanding_hashes
                                    .checked_sub(original_remaining)
                                    .context("surface rejected request accounting underflow")?;
                                error(4, &format!("surface object request rejected: {failure}"))
                                    .write(writer)
                                    .await?;
                                flush(writer).await?;
                                continue;
                            }
                            outstanding_hashes = outstanding_hashes
                                .checked_sub(original_remaining)
                                .context("surface failed request accounting underflow")?;
                            for surface in &job.surfaces {
                                surface.request_repair(job.root)?;
                            }
                            session_roots.fail(job.root);
                            self.update_session_pins(session_id, &session_roots)?;
                            error(
                                8,
                                &format!("surface root object unavailable; rebuilding: {failure}"),
                            )
                            .write(writer)
                            .await?;
                            flush(writer).await?;
                            continue;
                        }
                    };
                    let resolved = frame.resolved;
                    outstanding_hashes = outstanding_hashes
                        .checked_sub(resolved)
                        .context("surface outstanding request accounting underflow")?;
                    let bytes = pending_frame_bytes(&frame.frame);
                    pending_bytes = pending_bytes
                        .checked_add(bytes)
                        .context("surface pending byte count overflow")?;
                    if pending_bytes > MAX_PENDING_BYTES {
                        bail!("surface pending response limit exceeded");
                    }
                    pending.push_back(PendingFrame {
                        root: job.root,
                        frame: frame.frame,
                        _memory: frame.memory,
                    });
                    if !job.is_empty() {
                        jobs.push_front(job);
                    }
                    continue;
                }
            }

            tokio::select! {
                _ = tokio::time::sleep(MEMORY_RETRY_DELAY), if memory_stalled => {},
                command = commands.recv() => {
                    let Some(BudgetedCommand { command, _memory }) = command else {
                        return Ok(());
                    };
                    let mut command_memory = Some(_memory);
                    match command {
                    Command::Credit(amount) => {
                        if amount == 0 || amount > MAX_CREDIT_BYTES {
                            bail!("invalid surface client credit grant {amount}");
                        }
                        credit = credit.saturating_add(amount).min(MAX_CREDIT_BYTES);
                    }
                    Command::Ping(nonce) => {
                        pong(nonce).write(writer).await?;
                        flush(writer).await?;
                    }
                    Command::Message(Message::RootReady { dimension, root }) => {
                        let offer_latest = session_roots.mark_ready(&dimension, root)?;
                        self.update_session_pins(session_id, &session_roots)?;
                        if offer_latest
                            && let Some(latest) = self
                                .surfaces
                                .get(&dimension)
                                .context("ROOT_READY names an unavailable dimension")?
                                .current()?
                        {
                                self.send_announcement(
                                    writer,
                                    &mut session_roots,
                                    session_id,
                                    Announcement {
                                        dimension: dimension.clone(),
                                        root: latest,
                                    },
                                )
                                .await?;
                                flush(writer).await?;
                        }
                    }
                    Command::Message(Message::SubtreeRequest { root, hashes }) => {
                        let surfaces = self.request_surfaces(&session_roots, root)?;
                        let record = session_roots.record_for(root)?;
                        outstanding_hashes = outstanding_hashes
                            .checked_add(hashes.len())
                            .context("surface outstanding request count overflow")?;
                        if outstanding_hashes > MAX_OUTSTANDING_HASHES {
                            bail!("surface outstanding request limit exceeded");
                        }
                        // The budgeted frame reader reserved both the raw payload and its
                        // decoded ownership. The raw frame has already been dropped, so transfer
                        // that same permit to the queued hash vector without a second admission.
                        let memory = command_memory.take().expect("command permit is available");
                        jobs.push_back(RequestJob::new(record, true, surfaces, hashes, memory));
                    }
                    Command::Message(Message::ObjectRequest { root, hashes }) => {
                        let surfaces = self.request_surfaces(&session_roots, root)?;
                        let record = session_roots.record_for(root)?;
                        outstanding_hashes = outstanding_hashes
                            .checked_add(hashes.len())
                            .context("surface outstanding request count overflow")?;
                        if outstanding_hashes > MAX_OUTSTANDING_HASHES {
                            bail!("surface outstanding request limit exceeded");
                        }
                        let memory = command_memory.take().expect("command permit is available");
                        jobs.push_back(RequestJob::new(record, false, surfaces, hashes, memory));
                    }
                    Command::Message(Message::CameraDomainRequest {
                        root,
                        sequence,
                        block_x,
                        block_y,
                        block_z,
                    }) => {
                        if !session_roots.is_requestable(root) {
                            bail!("camera-domain request names a stale or failed root");
                        }
                        let previous = camera_sequences.entry(root).or_default();
                        if sequence <= *previous {
                            bail!("camera-domain sequence did not advance monotonically");
                        }
                        *previous = sequence;
                        let dimension = session_roots.dimension_for(root)?.to_owned();
                        let record = session_roots.record_for(root)?;
                        if dimension != selected_dimension {
                            bail!("camera-domain root belongs to a different session dimension");
                        }
                        let camera = self
                            .surfaces
                            .get(&dimension)
                            .context("camera-domain root belongs to an unavailable dimension")?
                            .surface
                            .camera_domain(record, block_x, block_y, block_z)?;
                        let (state, domain) = camera.domain.wire();
                        let message = Message::CameraDomain {
                            root,
                            sequence,
                            state: match state {
                                0 => CameraDomainState::Unknown,
                                1 => CameraDomainState::Exterior,
                                2 => CameraDomainState::Interior,
                                _ => unreachable!(),
                            },
                            domain,
                            min: camera.min,
                            max: camera.max,
                        };
                        Frame {
                            kind: message.kind(),
                            payload: message.encode()?,
                        }
                        .write(writer)
                        .await?;
                        flush(writer).await?;
                    }
                    Command::Message(_) => bail!("server-only surface message received from client"),
                    }
                    drop(command_memory);
                },
                announcement = announcements.recv() => match announcement {
                    Ok(announcement) => {
                        if announcement.dimension != selected_dimension {
                            continue;
                        }
                        self.send_announcement(
                            writer,
                            &mut session_roots,
                            session_id,
                            announcement,
                        )
                        .await?;
                        flush(writer).await?;
                    }
                    Err(broadcast::error::RecvError::Lagged(_)) => {
                        // Root state is tiny and authoritative; recover a connection-level lag
                        // by resending the full ready snapshot instead of requiring reconnect.
                        if let Some(announcement) =
                            self.root_snapshot_for(&selected_dimension)?
                        {
                            self.send_announcement(
                                    writer,
                                    &mut session_roots,
                                    session_id,
                                    announcement,
                                )
                                .await?;
                        }
                        flush(writer).await?;
                    }
                    Err(broadcast::error::RecvError::Closed) => return Ok(()),
                },
                _ = tokio::time::sleep(Duration::from_secs(1)) => {}
            }
        }
    }

    async fn send_announcement<W: AsyncWrite + Unpin>(
        &self,
        writer: &mut W,
        session: &mut SessionRoots,
        session_id: u64,
        announcement: Announcement,
    ) -> Result<()> {
        if !self
            .surfaces
            .get(&announcement.dimension)
            .context("surface announcement names an unavailable dimension")?
            .serviceable_announcement(RootToken::from(announcement.root))?
        {
            // A newer generation overtook this queued broadcast record. The later record (or a
            // lag snapshot) is authoritative; never advertise an already-unpinned root.
            return Ok(());
        }
        if !session.announce(announcement.dimension.clone(), announcement.root)? {
            return Ok(());
        }
        // Publish the GC lease before bytes can reach the peer. A failed socket write merely
        // retains an extra pin until SessionPinGuard removes the connection record.
        self.update_session_pins(session_id, session)?;
        let message = Message::RootAnnounce {
            dimension: announcement.dimension.clone(),
            root: RootToken::from(announcement.root),
            catalog: announcement.root.catalog,
            dictionary_set: announcement.root.dictionary_set,
            visibility: announcement.root.visibility,
        };
        let frame = Frame {
            kind: message.kind(),
            payload: message.encode()?,
        };
        frame.write(writer).await?;
        Ok(())
    }

    fn request_surfaces(
        &self,
        session: &SessionRoots,
        token: RootToken,
    ) -> Result<Vec<Arc<SurfaceService>>> {
        let mut surfaces = session
            .matching_dimensions(token)?
            .into_iter()
            .map(|dimension| {
                self.surfaces
                    .get(&dimension)
                    .cloned()
                    .context("session references an unavailable surface dimension")
            })
            .collect::<Result<Vec<_>>>()?;
        // A root can remain leased by an already-connected client after a corruption report.
        // It remains a GC pin for recovery, but must not keep consuming disk/CPU serving known
        // bad objects while the repair worker rebuilds it.  Do not require that it is one of the
        // service's current two roots here: old session leases are intentionally serviceable.
        // Keep a non-bad matching surface if one exists. This is defensive against the current
        // token's theoretical cross-dimension collision; the wire-level dimension binding
        // is tracked separately before cutover.
        let mut retained = Vec::with_capacity(surfaces.len());
        for surface in surfaces.drain(..) {
            if !surface.is_bad(token)? {
                retained.push(surface);
            }
        }
        if retained.is_empty() {
            bail!("surface request names a root withheld for repair");
        }
        Ok(retained)
    }

    fn build_response(&self, job: &mut RequestJob, mut memory: MemoryPermit) -> Result<BuiltFrame> {
        let mut objects = Vec::new();
        let mut canonical_bytes = 0usize;
        let mut encoded_bytes = BUNDLE_FIXED_BYTES;
        while objects.len() < MAX_BUNDLE_ENTRIES
            && (job.carried.is_some() || !job.hashes.is_empty())
        {
            let loaded = match job.carried.take() {
                Some(object) => object,
                None => {
                    let hash = job.hashes.pop_front().unwrap();
                    load_object(&job.surfaces, job.record, hash, &memory)?
                }
            };
            let object = loaded.wire;
            let is_manifest = matches!(
                object.kind,
                ObjectKind::ManifestSubtree
                    | ObjectKind::ManifestDescriptorPage
                    | ObjectKind::RootDirectory
            );
            if is_manifest != job.manifests {
                return Err(RequestRejected::new(
                    "surface object type does not match its request channel",
                )
                .into());
            }
            let next_canonical = canonical_bytes
                .checked_add(object.canonical_size as usize)
                .context("surface bundle canonical byte count overflow")?;
            let next_encoded = encoded_bytes
                .checked_add(OBJECT_WIRE_HEADER_BYTES + object.compressed.len())
                .context("surface bundle encoded byte count overflow")?;
            let canonical_limit = if job.manifests {
                MAX_MANIFEST_BYTES
            } else {
                MAX_CANONICAL_OBJECT_BYTES
            };
            if next_canonical > canonical_limit || next_encoded > MAX_FRAME_PAYLOAD {
                if objects.is_empty() {
                    bail!("one stored surface object cannot fit its legal response frame");
                }
                job.carried = Some(LoadedObject { wire: object });
                break;
            }
            canonical_bytes = next_canonical;
            encoded_bytes = next_encoded;
            objects.push(object);
        }
        if objects.is_empty() {
            bail!("surface request produced an empty response");
        }
        let resolved = objects.len();
        let message = if job.manifests {
            Message::SubtreeData {
                root: job.root,
                objects,
            }
        } else {
            Message::ObjectBundle {
                root: job.root,
                objects,
            }
        };
        debug_assert_eq!(
            message.kind(),
            if job.manifests {
                S_SUBTREE_DATA
            } else {
                S_OBJECT_BUNDLE
            }
        );
        let frame = Frame {
            kind: message.kind(),
            payload: message.encode()?,
        };
        let retained = pending_frame_bytes(&frame);
        memory.shrink_to(retained);
        Ok(BuiltFrame {
            resolved,
            frame,
            memory,
        })
    }
}

#[derive(Debug)]
enum Command {
    Credit(u64),
    Ping(u64),
    Message(Message),
}

struct BudgetedCommand {
    command: Command,
    _memory: MemoryPermit,
}

struct SessionPinGuard {
    service: Weak<Service>,
    id: u64,
}

impl Drop for SessionPinGuard {
    fn drop(&mut self) {
        if let Some(service) = self.service.upgrade()
            && let Ok(mut sessions) = service.sessions.lock()
        {
            sessions.remove(&self.id);
        }
    }
}

struct RequestJob {
    root: RootToken,
    record: RootRecord,
    manifests: bool,
    surfaces: Vec<Arc<SurfaceService>>,
    hashes: VecDeque<ObjectHash>,
    carried: Option<LoadedObject>,
    _memory: MemoryPermit,
}

impl RequestJob {
    fn new(
        record: RootRecord,
        manifests: bool,
        surfaces: Vec<Arc<SurfaceService>>,
        hashes: Vec<ObjectHash>,
        memory: MemoryPermit,
    ) -> Self {
        Self {
            root: RootToken::from(record),
            record,
            manifests,
            surfaces,
            hashes: hashes.into(),
            carried: None,
            _memory: memory,
        }
    }

    fn is_empty(&self) -> bool {
        self.hashes.is_empty() && self.carried.is_none()
    }

    fn remaining(&self) -> usize {
        self.hashes.len() + usize::from(self.carried.is_some())
    }
}

struct BuiltFrame {
    resolved: usize,
    frame: Frame,
    memory: MemoryPermit,
}

struct PendingFrame {
    root: RootToken,
    frame: Frame,
    _memory: MemoryPermit,
}

struct RootLease {
    record: RootRecord,
}

impl RootLease {
    fn new(record: RootRecord) -> Self {
        Self { record }
    }
}

#[derive(Default)]
struct DimensionLease {
    /// Renderable fallback retained until ROOT_READY atomically activates the incoming root.
    active: Option<RootLease>,
    /// One unactivated root frozen until ROOT_READY so continuous publication cannot starve it.
    incoming: Option<RootLease>,
    /// Newest publication observed while `incoming` is frozen for starvation-free completion.
    deferred: Option<RootRecord>,
}

#[derive(Default)]
struct SessionRoots {
    dimensions: BTreeMap<String, DimensionLease>,
    failed: HashSet<RootToken>,
}

impl SessionRoots {
    /// Returns whether this announcement should be put on the wire. An in-flight incoming root
    /// is frozen until ROOT_READY; newer generations collapse into one deferred latest record.
    fn announce(&mut self, dimension: String, root: RootRecord) -> Result<bool> {
        let token = RootToken::from(root);
        self.failed.remove(&token);
        let lease = self.dimensions.entry(dimension).or_default();
        if lease
            .active
            .as_ref()
            .is_some_and(|active| active.record == root)
        {
            return Ok(true);
        }
        if lease
            .incoming
            .as_ref()
            .is_some_and(|incoming| incoming.record == root)
        {
            return Ok(true);
        }
        let newest = lease
            .active
            .as_ref()
            .map_or(0, |active| active.record.generation)
            .max(
                lease
                    .incoming
                    .as_ref()
                    .map_or(0, |incoming| incoming.record.generation),
            )
            .max(lease.deferred.map_or(0, |deferred| deferred.generation));
        if root.generation <= newest {
            return Ok(false);
        }
        if lease.incoming.is_some() {
            lease.deferred = Some(root);
            return Ok(false);
        }
        lease.incoming = Some(RootLease::new(root));
        Ok(true)
    }

    /// Promotes one complete incoming root and reports whether newer publications were coalesced
    /// while it loaded. The caller then immediately offers the service's actual latest root.
    fn mark_ready(&mut self, dimension: &str, root: RootToken) -> Result<bool> {
        let lease = self
            .dimensions
            .get_mut(dimension)
            .context("ROOT_READY names an unannounced dimension")?;
        if lease
            .active
            .as_ref()
            .is_some_and(|active| RootToken::from(active.record) == root)
        {
            return Ok(false);
        }
        if !lease
            .incoming
            .as_ref()
            .is_some_and(|incoming| RootToken::from(incoming.record) == root)
        {
            bail!("ROOT_READY names an unannounced root");
        }
        lease.active = lease.incoming.take();
        self.failed.remove(&root);
        Ok(lease.deferred.take().is_some())
    }

    fn is_leased(&self, root: RootToken) -> bool {
        self.dimensions.values().any(|lease| {
            lease
                .active
                .as_ref()
                .into_iter()
                .chain(lease.incoming.as_ref())
                .any(|entry| RootToken::from(entry.record) == root)
        })
    }

    fn is_requestable(&self, root: RootToken) -> bool {
        self.is_leased(root) && !self.failed.contains(&root)
    }

    fn dimension_for(&self, root: RootToken) -> Result<&str> {
        let mut found = None;
        for (dimension, lease) in &self.dimensions {
            if lease
                .active
                .as_ref()
                .into_iter()
                .chain(lease.incoming.as_ref())
                .any(|entry| RootToken::from(entry.record) == root)
            {
                if found.is_some() {
                    bail!("one root token is leased for multiple dimensions");
                }
                found = Some(dimension.as_str());
            }
        }
        found.context("camera-domain request names an unannounced root")
    }

    fn record_for(&self, root: RootToken) -> Result<RootRecord> {
        let mut found = None;
        for lease in self.dimensions.values() {
            for entry in lease
                .active
                .as_ref()
                .into_iter()
                .chain(lease.incoming.as_ref())
            {
                if RootToken::from(entry.record) != root {
                    continue;
                }
                if found.replace(entry.record).is_some() {
                    bail!("one root token is leased more than once");
                }
            }
        }
        found.context("camera-domain request names an unannounced root")
    }

    fn matching_dimensions(&self, root: RootToken) -> Result<Vec<String>> {
        if self.failed.contains(&root) {
            bail!("surface request names a failed root awaiting repair");
        }
        let mut dimensions = Vec::new();
        for (dimension, lease) in &self.dimensions {
            for entry in lease
                .active
                .as_ref()
                .into_iter()
                .chain(lease.incoming.as_ref())
            {
                if RootToken::from(entry.record) != root {
                    continue;
                }
                dimensions.push(dimension.clone());
            }
        }
        if dimensions.is_empty() {
            bail!("surface request names a stale or unannounced root");
        }
        Ok(dimensions)
    }

    fn fail(&mut self, root: RootToken) {
        self.failed.insert(root);
        for lease in self.dimensions.values_mut() {
            if lease
                .incoming
                .as_ref()
                .is_some_and(|incoming| RootToken::from(incoming.record) == root)
            {
                lease.incoming = None;
            }
        }
    }

    fn pinned(&self) -> Vec<RootRecord> {
        let mut roots = self
            .dimensions
            .values()
            .flat_map(|lease| {
                lease
                    .active
                    .as_ref()
                    .into_iter()
                    .chain(lease.incoming.as_ref())
                    .map(|entry| entry.record)
            })
            .collect::<Vec<_>>();
        roots.sort_unstable_by_key(|root| (root.dimension, root.generation));
        roots.dedup();
        roots
    }
}

#[derive(Debug)]
struct LoadedObject {
    wire: WireObject,
}

#[derive(Debug)]
struct RequestRejected(String);

impl RequestRejected {
    fn new(message: impl Into<String>) -> Self {
        Self(message.into())
    }
}

impl fmt::Display for RequestRejected {
    fn fmt(&self, output: &mut fmt::Formatter<'_>) -> fmt::Result {
        output.write_str(&self.0)
    }
}

impl std::error::Error for RequestRejected {}

fn is_memory_pressure(error: &anyhow::Error) -> bool {
    error.chain().any(|cause| cause.is::<MemoryPressure>())
}

fn is_request_rejected(error: &anyhow::Error) -> bool {
    error.chain().any(|cause| cause.is::<RequestRejected>())
}

fn load_object(
    surfaces: &[Arc<SurfaceService>],
    root: RootRecord,
    hash: ObjectHash,
    memory: &MemoryPermit,
) -> Result<LoadedObject> {
    for surface in surfaces {
        let loaded = match surface.surface.read_wire_object(root, hash, memory) {
            Ok(loaded) => loaded,
            Err(error) if error.downcast_ref::<LeasedDictionaryMismatch>().is_some() => {
                return Err(RequestRejected::new(error.to_string()).into());
            }
            Err(error) => return Err(error),
        };
        if let Some((object, dictionary_id, _canonical)) = loaded {
            if !client_transferable_kind(object.kind) {
                return Err(RequestRejected::new(format!(
                    "surface object kind {:?} is server-internal",
                    object.kind
                ))
                .into());
            }
            return Ok(LoadedObject {
                wire: WireObject::from_stored_with_dictionary_id(object, dictionary_id)?,
            });
        }
    }
    Err(RequestRejected::new(format!(
        "requested surface object {hash} is absent from the leased root stores"
    ))
    .into())
}

fn client_transferable_kind(kind: ObjectKind) -> bool {
    !matches!(
        kind,
        ObjectKind::VisibilityDirectory
            | ObjectKind::VisibilityPage
            | ObjectKind::VisibilitySummaryPage
            | ObjectKind::SourceMicrotile
    )
}

fn pending_frame_bytes(frame: &Frame) -> usize {
    frame.payload.capacity().saturating_add(HEADER_LEN)
}
