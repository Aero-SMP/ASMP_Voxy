//! Live publication, QUIC root leases, and fair positional object service.

use super::{
    gc::{GcMoment, GcPolicy, GcRunReport},
    object::{ObjectHash, ObjectKind},
    pack::StoredObjectSource,
    root::RootRecord,
    runtime::{DimensionSurface, LeasedDictionaryMismatch},
    visibility::DimensionVisibilityPolicy,
    wire::{
        CameraDomainState, ControlMessage, MAX_STREAM_CANONICAL_BYTES, MAX_STREAM_COMPRESSED_BYTES,
        OBJECT_RESPONSE_HEADER_BYTES, ObjectRequest, ObjectResponseHeader, PriorityLane, RootToken,
        STREAM_OBJECT_REQUEST, encode_control_record, read_control, read_stream_role,
        write_object_error, write_object_success,
    },
};
use crate::{anvil::AnvilWorld, lock, read_lock, registry::Registry, write_lock};
use anyhow::{Context, Result, bail};
use crc32c::crc32c_append;
use quinn::{Connection, RecvStream, SendStream, VarInt};
use std::{
    array,
    collections::{BTreeMap, HashMap, HashSet, VecDeque},
    fmt,
    path::Path,
    sync::{
        Arc, Mutex, RwLock, Weak,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};
use tokio::sync::{RwLock as AsyncRwLock, broadcast, mpsc, oneshot, watch};

const ANNOUNCEMENT_CAPACITY: usize = 1_024;
const REQUEST_HEADER_TIMEOUT: Duration = Duration::from_secs(5);
const CONTROL_WRITE_TIMEOUT: Duration = Duration::from_secs(15);
const TERMINAL_CONTROL_DRAIN_TIMEOUT: Duration = Duration::from_millis(250);
const SCHEDULER_CAPACITY: usize = 4_096;
const MAX_ACTIVE_DISK_TURNS: usize = 16;
const MAX_ACTIVE_NETWORK_TURNS: usize = 16;
const MAX_ACTIVE_NETWORK_TURNS_PER_SESSION: usize = 2;
const NETWORK_WRITE_SLICE: Duration = Duration::from_millis(5);
const TRANSFER_CHUNK_BYTES: usize = 32 * 1024;
// Keep one small, coordinated stream budget on both peers. Coverage can use half of it,
// while current-view and speculative work cannot crowd out the correctness-critical lane.
const ACTIVE_STREAMS_PER_LANE: [usize; 3] = [4, 3, 1];
const MAX_ACTIVE_STREAMS_PER_CONNECTION: usize = 8;
const MAX_CONTROL_MESSAGES_PER_SECOND: u32 = 128;

#[derive(Debug, Default)]
struct SurfaceState {
    current: Option<RootRecord>,
    previous: Option<RootRecord>,
    bad: HashSet<RootToken>,
}

impl SurfaceState {
    fn visible(&self) -> Option<RootRecord> {
        [self.current, self.previous]
            .into_iter()
            .flatten()
            .find(|root| !self.bad.contains(&RootToken::from(*root)))
    }

    fn retained_roots(&self) -> Vec<RootRecord> {
        let mut roots = [self.current, self.previous]
            .into_iter()
            .flatten()
            .collect::<Vec<_>>();
        roots.sort_unstable_by_key(|root| root.generation);
        roots.dedup();
        roots
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
        if root.dimension != ObjectHash::dimension(&self.dimension)? {
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
        Ok(read_lock(&self.announced)?.visible())
    }

    fn retained_roots(&self) -> Result<Vec<RootRecord>> {
        Ok(read_lock(&self.announced)?.retained_roots())
    }

    fn serviceable(&self, token: RootToken) -> Result<bool> {
        let state = read_lock(&self.announced)?;
        Ok(!state.bad.contains(&token)
            && [state.current, state.previous]
                .into_iter()
                .flatten()
                .any(|record| RootToken::from(record) == token))
    }

    fn pin_if_serviceable(
        &self,
        token: RootToken,
        session: &Arc<SessionPinGuard>,
        record: RootRecord,
    ) -> Result<Option<RequestPinGuard>> {
        if RootToken::from(record) != token {
            bail!("announcement token and root record disagree");
        }
        let state = read_lock(&self.announced)?;
        let serviceable = !state.bad.contains(&token)
            && [state.current, state.previous]
                .into_iter()
                .flatten()
                .any(|candidate| RootToken::from(candidate) == token);
        if !serviceable {
            return Ok(None);
        }
        let pin = session.pin(record)?;
        drop(state);
        Ok(Some(pin))
    }

    fn is_bad(&self, token: RootToken) -> Result<bool> {
        Ok(read_lock(&self.announced)?.bad.contains(&token))
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

/// All dimension surfaces, publication workers, root leases, and cross-client I/O scheduling.
pub struct Service {
    registry: Arc<RwLock<Registry>>,
    surfaces: BTreeMap<String, Arc<SurfaceService>>,
    announcements: broadcast::Sender<Announcement>,
    shutdowns: broadcast::Sender<String>,
    workers: Mutex<Option<Vec<PublisherWorker>>>,
    poll_interval: Duration,
    sessions: Mutex<HashMap<u64, SessionPins>>,
    next_session: AtomicU64,
    scheduler: FairScheduler,
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

    pub fn open_with_policies(
        data_root: impl AsRef<Path>,
        dimensions: &BTreeMap<String, Arc<AnvilWorld>>,
        registry: Arc<RwLock<Registry>>,
        poll_interval: Duration,
        visibility_policies: &BTreeMap<String, DimensionVisibilityPolicy>,
    ) -> Result<Self> {
        let (announcements, _) = broadcast::channel(ANNOUNCEMENT_CAPACITY);
        let (shutdowns, _) = broadcast::channel(1);
        let mut surfaces = BTreeMap::new();
        let mut workers = Vec::with_capacity(dimensions.len());
        for (dimension, source) in dimensions {
            let (repair, repairs) = mpsc::channel(1);
            let visibility_policy = DimensionVisibilityPolicy::configured(
                dimension,
                visibility_policies.get(dimension).copied(),
            )?;
            let runtime_surface = Arc::new(DimensionSurface::open_with_policy(
                data_root.as_ref(),
                dimension.clone(),
                source.clone(),
                visibility_policy,
            )?);
            let surface = Arc::new(SurfaceService {
                dimension: dimension.clone(),
                announced: RwLock::new(SurfaceState {
                    current: runtime_surface.current_root()?,
                    previous: runtime_surface.previous_root()?,
                    bad: HashSet::new(),
                }),
                surface: runtime_surface,
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
            shutdowns,
            workers: Mutex::new(Some(workers)),
            poll_interval,
            sessions: Mutex::new(HashMap::new()),
            next_session: AtomicU64::new(1),
            scheduler: FairScheduler::start(),
        })
    }

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

    pub fn refresh_all(&self) -> Result<()> {
        loop {
            let mut pending = false;
            for surface in self.surfaces.values() {
                pending |= surface.surface.refresh(&self.registry)?;
            }
            if !pending {
                return Ok(());
            }
        }
    }

    pub fn shutdown(&self, reason: impl Into<String>) {
        let _ = self.shutdowns.send(reason.into());
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
                Ok(Ok(pending)) => {
                    repair_pending = false;
                    if let Some(root) = worker.surface.surface.current_root()? {
                        self.announce(&worker.surface, root, repairing)?;
                    }
                    if pending {
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

    fn register_session(self: &Arc<Self>) -> Result<Arc<SessionPinGuard>> {
        let id = self
            .next_session
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |id| id.checked_add(1))
            .map_err(|_| anyhow::anyhow!("surface session identity exhausted"))?;
        lock(&self.sessions)?.insert(id, SessionPins::default());
        Ok(Arc::new(SessionPinGuard {
            service: Arc::downgrade(self),
            id,
        }))
    }

    fn update_session_pins(&self, id: u64, roots: Vec<RootRecord>) -> Result<()> {
        let mut sessions = lock(&self.sessions)?;
        sessions
            .get_mut(&id)
            .context("surface session pin record disappeared")?
            .leased = roots;
        Ok(())
    }

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
        let mut retained = surface.retained_roots()?;
        let identity = ObjectHash::dimension(dimension)?;
        let session_roots = lock(&self.sessions)?
            .values()
            .flat_map(SessionPins::roots)
            .filter(|root| root.dimension == identity)
            .collect::<Vec<_>>();
        retained.extend(session_roots);
        retained.sort_unstable_by_key(|root| root.generation);
        retained.dedup();
        surface
            .surface
            .collect_live(retained, moment, policy)
            .map(Some)
    }

    /// Owns the permanent control stream and all object streams for one QUIC connection.
    pub async fn connection(
        self: Arc<Self>,
        connection: Connection,
        control_send: SendStream,
        control_recv: RecvStream,
        selected_dimension: String,
        server_instance: u64,
    ) -> Result<()> {
        let mut control_send = ControlOutput::new(control_send);
        let mut announcements = self.announcements.subscribe();
        let mut shutdowns = self.shutdowns.subscribe();
        let initial = match self.root_snapshot_for(&selected_dimension) {
            Ok(Some(root)) => root,
            Ok(None) => {
                self.control_write(
                    &mut control_send,
                    &ControlMessage::Error {
                        code: 8,
                        message: "selected dimension has no verified root".to_owned(),
                    },
                )
                .await?;
                drain_terminal_control(&mut control_send).await;
                return Ok(());
            }
            Err(error) => {
                self.control_write(
                    &mut control_send,
                    &ControlMessage::Error {
                        code: 8,
                        message: format!("selected dimension is unavailable: {error}"),
                    },
                )
                .await?;
                drain_terminal_control(&mut control_send).await;
                return Ok(());
            }
        };
        let guard = self.register_session()?;
        let roots = Arc::new(AsyncRwLock::new(SessionRoots::default()));
        self.control_write(
            &mut control_send,
            &ControlMessage::ServerHello { server_instance },
        )
        .await?;
        self.send_announcement(&mut control_send, &roots, &guard, initial)
            .await?;

        let (control_input_tx, mut control_input) = mpsc::channel(1);
        let control_reader_task = tokio::spawn(control_reader(control_recv, control_input_tx));

        let accept_service = self.clone();
        let accept_connection = connection.clone();
        let accept_roots = roots.clone();
        let accept_guard = guard.clone();
        let mut accept_task = tokio::spawn(async move {
            accept_service
                .accept_object_streams(accept_connection, accept_roots, accept_guard)
                .await
        });

        let mut camera_sequence = 0u64;
        let (camera_jobs, camera_job_rx) = watch::channel(None::<CameraJob>);
        let (camera_answers_tx, mut camera_answers) = mpsc::channel(1);
        let camera_task = tokio::spawn(camera_worker(
            camera_job_rx,
            camera_jobs.clone(),
            camera_answers_tx,
        ));
        let mut control_window = Instant::now();
        let mut control_message_count = 0u32;
        let result = async {
            loop {
                tokio::select! {
                message = control_input.recv() => {
                    let message = message.context("control reader stopped unexpectedly")??;
                    let Some(message) = message else {
                        break Ok(());
                    };
                    if control_window.elapsed() >= Duration::from_secs(1) {
                        control_window = Instant::now();
                        control_message_count = 0;
                    }
                    control_message_count += 1;
                    if control_message_count > MAX_CONTROL_MESSAGES_PER_SECOND {
                        bail!("control-message rate exceeded");
                    }
                    match message {
                        ControlMessage::RootReady { dimension, root } => {
                            let offer_latest = {
                                let mut roots = roots.write().await;
                                let offer = roots.mark_ready(&dimension, root)?;
                                self.update_session_pins(guard.id, roots.pinned())?;
                                offer
                            };
                            if offer_latest
                                && let Some(latest) = self
                                    .surfaces
                                    .get(&dimension)
                                    .context("ROOT_READY names an unavailable dimension")?
                                    .current()?
                            {
                                self.send_announcement(
                                    &mut control_send,
                                    &roots,
                                    &guard,
                                    Announcement { dimension, root: latest },
                                )
                                .await?;
                            }
                        }
                        ControlMessage::CameraDomainRequest {
                            root,
                            sequence,
                            block_x,
                            block_y,
                            block_z,
                        } => {
                            let (dimension, record, pin) = {
                                let roots = roots.read().await;
                                if !roots.is_requestable(root) {
                                    bail!("camera-domain request names a stale root");
                                }
                                if sequence <= camera_sequence {
                                    bail!("camera-domain sequence did not advance");
                                }
                                camera_sequence = sequence;
                                let dimension = roots.dimension_for(root)?.to_owned();
                                let record = roots.record_for(root)?;
                                let pin = Arc::new(guard.pin(record)?);
                                (dimension, record, pin)
                            };
                            if dimension != selected_dimension {
                                bail!("camera-domain root belongs to another dimension");
                            }
                            let runtime = self
                                .surfaces
                                .get(&dimension)
                                .context("camera-domain dimension is unavailable")?
                                .surface
                                .clone();
                            camera_jobs.send_replace(Some(CameraJob {
                                runtime,
                                root,
                                sequence,
                                record,
                                block_x,
                                block_y,
                                block_z,
                                _pin: pin,
                            }));
                        }
                        ControlMessage::Hello { .. } => bail!("duplicate control HELLO"),
                        _ => bail!("server-only control message received from client"),
                    }
                }
                answer = camera_answers.recv() => {
                    let Some((sequence, answer)) = answer else {
                        bail!("camera-domain worker stopped");
                    };
                    if sequence != camera_sequence {
                        continue;
                    }
                    let answer = answer?;
                    self.control_write(
                        &mut control_send,
                        &ControlMessage::CameraDomain {
                            root: answer.root,
                            sequence: answer.sequence,
                            state: match answer.state {
                                0 => CameraDomainState::Unknown,
                                1 => CameraDomainState::Exterior,
                                2 => CameraDomainState::Interior,
                                _ => unreachable!(),
                            },
                            domain: answer.domain,
                            min: answer.min,
                            max: answer.max,
                        },
                    )
                    .await?;
                }
                announcement = announcements.recv() => match announcement {
                    Ok(announcement) if announcement.dimension == selected_dimension => {
                        self.send_announcement(
                            &mut control_send,
                            &roots,
                            &guard,
                            announcement,
                        )
                        .await?;
                    }
                    Ok(_) => {}
                    Err(broadcast::error::RecvError::Lagged(_)) => {
                        if let Some(announcement) = self.root_snapshot_for(&selected_dimension)? {
                            self.send_announcement(
                                &mut control_send,
                                &roots,
                                &guard,
                                announcement,
                            )
                            .await?;
                        }
                    }
                    Err(broadcast::error::RecvError::Closed) => break Ok(()),
                },
                shutdown = shutdowns.recv() => {
                    let reason = shutdown
                        .unwrap_or_else(|_| "Voxy server shutting down".to_owned());
                    let _ = self.control_write(
                        &mut control_send,
                        &ControlMessage::Shutdown { message: reason },
                    ).await;
                    drain_terminal_control(&mut control_send).await;
                    break Ok(());
                },
                accepted = &mut accept_task => {
                    break match accepted {
                        Ok(result) => result,
                        Err(error) => Err(error.into()),
                    };
                }
                }
            }
        }
        .await;
        accept_task.abort();
        camera_task.abort();
        control_reader_task.abort();
        if let Err(error) = &result
            && control_send.healthy()
        {
            let _ = self
                .control_write(
                    &mut control_send,
                    &ControlMessage::Error {
                        code: 1,
                        message: error.to_string(),
                    },
                )
                .await;
            drain_terminal_control(&mut control_send).await;
        }
        connection.close(VarInt::from_u32(0), b"Voxy session closed");
        result
    }

    async fn control_write(
        &self,
        send: &mut ControlOutput,
        message: &ControlMessage,
    ) -> Result<()> {
        send.write(message).await
    }

    async fn fail_leased_root(
        &self,
        surface: &SurfaceService,
        roots: &AsyncRwLock<SessionRoots>,
        session_id: u64,
        root: RootToken,
    ) -> Result<()> {
        let repair = surface.request_repair(root);
        let mut roots = roots.write().await;
        roots.fail(root);
        let pins = self.update_session_pins(session_id, roots.pinned());
        repair?;
        pins
    }

    async fn send_announcement(
        &self,
        send: &mut ControlOutput,
        roots: &AsyncRwLock<SessionRoots>,
        session: &Arc<SessionPinGuard>,
        announcement: Announcement,
    ) -> Result<()> {
        let surface = self
            .surfaces
            .get(&announcement.dimension)
            .context("surface announcement names an unavailable dimension")?;
        let token = RootToken::from(announcement.root);
        let Some(temporary_pin) = surface.pin_if_serviceable(token, session, announcement.root)?
        else {
            return Ok(());
        };
        if !surface.serviceable(token)? {
            return Ok(());
        }
        let runtime = surface.surface.clone();
        let record = announcement.root;
        let dictionaries = match tokio::task::spawn_blocking(move || {
            runtime.leased_dictionaries(record)
        })
        .await
        {
            Ok(Ok(dictionaries)) => dictionaries,
            Ok(Err(error)) => {
                if let Err(repair) = self
                    .fail_leased_root(surface, roots, session.id, token)
                    .await
                {
                    return Err(error.context(format!(
                        "root dictionary-set repair could not be scheduled: {repair:#}"
                    )));
                }
                return Err(error.context("load the announced root dictionary set"));
            }
            Err(error) => {
                let error = anyhow::Error::from(error);
                if let Err(repair) = self
                    .fail_leased_root(surface, roots, session.id, token)
                    .await
                {
                    return Err(error.context(format!(
                        "root dictionary-set worker failed and repair could not be scheduled: {repair:#}"
                    )));
                }
                return Err(error.context("root dictionary-set worker failed"));
            }
        };
        let should_send = {
            let mut roots = roots.write().await;
            let should_send = roots.announce(
                announcement.dimension.clone(),
                announcement.root,
                dictionaries,
            )?;
            self.update_session_pins(session.id, roots.pinned())?;
            should_send
        };
        drop(temporary_pin);
        if should_send {
            self.control_write(
                send,
                &ControlMessage::RootAnnounce {
                    dimension: announcement.dimension,
                    root: token,
                    catalog: announcement.root.catalog,
                    dictionary_set: announcement.root.dictionary_set,
                    visibility: announcement.root.visibility,
                },
            )
            .await?;
        }
        Ok(())
    }

    async fn accept_object_streams(
        self: Arc<Self>,
        connection: Connection,
        roots: Arc<AsyncRwLock<SessionRoots>>,
        guard: Arc<SessionPinGuard>,
    ) -> Result<()> {
        let limits = Arc::new(ConnectionLimits::new());
        loop {
            let (send, recv) = connection.accept_bi().await?;
            let Ok(total) = limits.total.clone().try_acquire_owned() else {
                reject_unparsed_stream(send, recv, 2, "too many active object streams").await;
                continue;
            };
            let service = self.clone();
            let roots = roots.clone();
            let guard = guard.clone();
            let limits = limits.clone();
            tokio::spawn(async move {
                let _total = total;
                if let Err(error) = service
                    .object_stream(send, recv, roots, guard, limits)
                    .await
                    && !is_quic_cancellation(&error)
                {
                    eprintln!("Voxy object stream ended: {error:#}");
                }
            });
        }
    }

    async fn object_stream(
        self: Arc<Self>,
        mut send: SendStream,
        mut recv: RecvStream,
        roots: Arc<AsyncRwLock<SessionRoots>>,
        guard: Arc<SessionPinGuard>,
        limits: Arc<ConnectionLimits>,
    ) -> Result<()> {
        let request = tokio::time::timeout(REQUEST_HEADER_TIMEOUT, async {
            match read_stream_role(&mut recv).await? {
                Some(STREAM_OBJECT_REQUEST) => ObjectRequest::read(&mut recv).await,
                Some(other) => bail!("unexpected QUIC stream role {other}"),
                None => bail!("empty QUIC stream"),
            }
        })
        .await
        .context("object-request header timeout")?;
        let request = match request {
            Ok(request) => request,
            Err(error) => {
                write_object_error(&mut send, 1, &error.to_string()).await?;
                send.finish()?;
                return Ok(());
            }
        };
        send.set_priority(match request.lane {
            PriorityLane::Coverage => 2,
            PriorityLane::Current => 1,
            PriorityLane::Predicted => 0,
        })
        .context("set QUIC object-stream priority")?;
        let lane_permit = match limits.lanes[request.lane.index()]
            .clone()
            .try_acquire_owned()
        {
            Ok(permit) => permit,
            Err(_) => {
                write_object_error(&mut send, 2, "priority lane is at capacity").await?;
                send.finish()?;
                return Ok(());
            }
        };
        let _lane_permit = lane_permit;
        let roots_guard = roots.read().await;
        let authorization = match roots_guard.authorize(request.root) {
            Ok(authorization) => authorization,
            Err(error) => {
                drop(roots_guard);
                write_object_error(&mut send, 4, &error.to_string()).await?;
                send.finish()?;
                return Ok(());
            }
        };
        let request_pin = guard.pin(authorization.record)?;
        drop(roots_guard);
        let surface = self
            .surfaces
            .get(&authorization.dimension)
            .cloned()
            .context("leased surface dimension is unavailable")?;
        if surface.is_bad(request.root)? {
            drop(request_pin);
            write_object_error(&mut send, 4, "root is withheld for repair").await?;
            send.finish()?;
            return Ok(());
        }
        let response_surface = surface.clone();
        let response_record = authorization.record;
        let response_dictionaries = authorization.dictionaries.clone();
        let response_hashes = request.hashes.clone();
        let response = tokio::task::spawn_blocking(move || {
            prepare_response(
                &response_surface,
                response_record,
                &response_dictionaries,
                &response_hashes,
            )
        })
        .await
        .context("object-response preparation worker failed")?;
        // Every response now owns immutable file handles, so the root only needs to remain
        // explicitly pinned through source acquisition rather than through network backpressure.
        drop(request_pin);
        let response = match response {
            Ok(response) => response,
            Err(error) if is_request_rejected(&error) => {
                write_object_error(&mut send, 4, &error.to_string()).await?;
                send.finish()?;
                return Ok(());
            }
            Err(error) => {
                self.fail_leased_root(&surface, &roots, guard.id, request.root)
                    .await?;
                write_object_error(&mut send, 8, "root object failed integrity checks").await?;
                send.finish()?;
                return Err(error);
            }
        };
        write_object_success(&mut send, response.len()).await?;
        let mut buffer = vec![0u8; TRANSFER_CHUNK_BYTES];
        for object in response {
            send.write_all(&object.header).await?;
            let mut offset = 0u64;
            let mut compressed_crc = 0u32;
            while offset < object.source.compressed_size() {
                let length = usize::try_from(
                    (object.source.compressed_size() - offset).min(TRANSFER_CHUNK_BYTES as u64),
                )?;
                let disk_turn = tokio::select! {
                    turn = self.scheduler.disk_turn(guard.id, request.lane) => {
                        turn.context("object disk scheduler stopped")?
                    }
                    _ = send.stopped() => return Ok(()),
                };
                let source = object.source.clone();
                let (returned, read) = tokio::task::spawn_blocking(move || {
                    let read = source.read_exact_at(offset, &mut buffer[..length]);
                    (buffer, read)
                })
                .await?;
                buffer = returned;
                drop(disk_turn);
                if let Err(error) = read {
                    let _ = send.reset(VarInt::from_u32(8));
                    self.fail_leased_root(&surface, &roots, guard.id, request.root)
                        .await?;
                    return Err(error);
                }
                compressed_crc = crc32c_append(compressed_crc, &buffer[..length]);
                if offset + length as u64 == object.source.compressed_size()
                    && compressed_crc != object.source.compressed_crc()
                {
                    let _ = send.reset(VarInt::from_u32(8));
                    self.fail_leased_root(&surface, &roots, guard.id, request.root)
                        .await?;
                    bail!(
                        "stored compressed object {} failed CRC32C during streaming",
                        object.source.hash()
                    );
                }
                let mut written = 0usize;
                while written < length {
                    let network_turn = tokio::select! {
                        turn = self.scheduler.network_turn(guard.id, request.lane) => {
                            turn.context("object network scheduler stopped")?
                        }
                        _ = send.stopped() => return Ok(()),
                    };
                    let stopped = send.stopped();
                    let write = tokio::select! {
                        write = send.write(&buffer[written..length]) => Some(write),
                        _ = tokio::time::sleep(NETWORK_WRITE_SLICE) => None,
                        _ = stopped => return Ok(()),
                    };
                    drop(network_turn);
                    if let Some(write) = write {
                        let count = write?;
                        if count == 0 {
                            bail!("QUIC object stream made no write progress");
                        }
                        written += count;
                    }
                }
                offset += length as u64;
            }
        }
        send.finish()?;
        Ok(())
    }
}

struct ControlOutput {
    send: SendStream,
    healthy: bool,
}

impl ControlOutput {
    fn new(send: SendStream) -> Self {
        Self {
            send,
            healthy: true,
        }
    }

    fn healthy(&self) -> bool {
        self.healthy
    }

    async fn write(&mut self, message: &ControlMessage) -> Result<()> {
        if !self.healthy {
            bail!("control output is no longer writable");
        }
        let record = encode_control_record(message)?;
        match tokio::time::timeout(CONTROL_WRITE_TIMEOUT, self.send.write_all(&record)).await {
            Ok(Ok(())) => Ok(()),
            Ok(Err(error)) => {
                self.poison();
                Err(error.into())
            }
            Err(_) => {
                self.poison();
                bail!("control-stream write timeout")
            }
        }
    }

    fn poison(&mut self) {
        if self.healthy {
            self.healthy = false;
            let _ = self.send.reset(VarInt::from_u32(1));
        }
    }
}

async fn control_reader(
    mut recv: RecvStream,
    messages: mpsc::Sender<Result<Option<ControlMessage>>>,
) {
    loop {
        let message = read_control(&mut recv).await;
        let finished = !matches!(&message, Ok(Some(_)));
        if messages.send(message).await.is_err() || finished {
            return;
        }
    }
}

async fn drain_terminal_control(output: &mut ControlOutput) {
    if output.healthy {
        output.healthy = false;
        if output.send.finish().is_ok() {
            let _ =
                tokio::time::timeout(TERMINAL_CONTROL_DRAIN_TIMEOUT, output.send.stopped()).await;
        }
    }
}

#[derive(Clone)]
struct CameraJob {
    runtime: Arc<DimensionSurface>,
    root: RootToken,
    sequence: u64,
    record: RootRecord,
    block_x: i32,
    block_y: i32,
    block_z: i32,
    _pin: Arc<RequestPinGuard>,
}

struct CameraAnswer {
    root: RootToken,
    sequence: u64,
    state: u8,
    domain: u64,
    min: [i32; 3],
    max: [i32; 3],
}

async fn camera_worker(
    mut jobs: watch::Receiver<Option<CameraJob>>,
    pending: watch::Sender<Option<CameraJob>>,
    answers: mpsc::Sender<(u64, Result<CameraAnswer>)>,
) {
    while jobs.changed().await.is_ok() {
        let Some(job) = jobs.borrow_and_update().clone() else {
            continue;
        };
        let root = job.root;
        let sequence = job.sequence;
        pending.send_if_modified(|current| {
            if current
                .as_ref()
                .is_some_and(|pending| pending.sequence == sequence)
            {
                *current = None;
                true
            } else {
                false
            }
        });
        let result = tokio::task::spawn_blocking(move || {
            let camera =
                job.runtime
                    .camera_domain(job.record, job.block_x, job.block_y, job.block_z)?;
            let (state, domain) = camera.domain.wire();
            Ok(CameraAnswer {
                root,
                sequence,
                state,
                domain,
                min: camera.min,
                max: camera.max,
            })
        })
        .await
        .map_err(anyhow::Error::from)
        .and_then(|result| result);
        if answers.send((sequence, result)).await.is_err() {
            return;
        }
    }
}

struct ConnectionLimits {
    total: Arc<tokio::sync::Semaphore>,
    lanes: [Arc<tokio::sync::Semaphore>; 3],
}

impl ConnectionLimits {
    fn new() -> Self {
        Self {
            total: Arc::new(tokio::sync::Semaphore::new(
                MAX_ACTIVE_STREAMS_PER_CONNECTION,
            )),
            lanes: array::from_fn(|lane| {
                Arc::new(tokio::sync::Semaphore::new(ACTIVE_STREAMS_PER_LANE[lane]))
            }),
        }
    }
}

async fn reject_unparsed_stream(
    mut send: SendStream,
    mut recv: RecvStream,
    code: u16,
    message: &str,
) {
    let _ = recv.stop(VarInt::from_u32(code as u32));
    if write_object_error(&mut send, code, message).await.is_ok() {
        let _ = send.finish();
    } else {
        let _ = send.reset(VarInt::from_u32(code as u32));
    }
}

#[derive(Clone)]
struct FairScheduler {
    disk: FairTurnQueue,
    network: FairTurnQueue,
}

impl FairScheduler {
    fn start() -> Self {
        Self {
            disk: FairTurnQueue::start(MAX_ACTIVE_DISK_TURNS, MAX_ACTIVE_DISK_TURNS),
            network: FairTurnQueue::start(
                MAX_ACTIVE_NETWORK_TURNS,
                MAX_ACTIVE_NETWORK_TURNS_PER_SESSION,
            ),
        }
    }

    async fn disk_turn(&self, session: u64, lane: PriorityLane) -> Result<FairTurn> {
        self.disk.turn(session, lane).await
    }

    async fn network_turn(&self, session: u64, lane: PriorityLane) -> Result<FairTurn> {
        self.network.turn(session, lane).await
    }
}

#[derive(Clone)]
struct FairTurnQueue {
    requests: mpsc::Sender<TurnRequest>,
}

impl FairTurnQueue {
    fn start(maximum_active: usize, maximum_active_per_session: usize) -> Self {
        let (requests, request_rx) = mpsc::channel(SCHEDULER_CAPACITY);
        let (completions, completion_rx) = mpsc::unbounded_channel();
        tokio::spawn(run_scheduler(
            request_rx,
            completion_rx,
            completions.clone(),
            maximum_active,
            maximum_active_per_session,
        ));
        Self { requests }
    }

    async fn turn(&self, session: u64, lane: PriorityLane) -> Result<FairTurn> {
        let (ready, wait) = oneshot::channel();
        self.requests
            .send(TurnRequest {
                session,
                lane,
                ready,
            })
            .await
            .context("fair scheduler request queue closed")?;
        wait.await.context("fair scheduler stopped")
    }
}

struct TurnRequest {
    session: u64,
    lane: PriorityLane,
    ready: oneshot::Sender<FairTurn>,
}

struct FairTurn {
    session: u64,
    completions: mpsc::UnboundedSender<u64>,
}

impl Drop for FairTurn {
    fn drop(&mut self) {
        let _ = self.completions.send(self.session);
    }
}

struct SchedulerState {
    active: usize,
    maximum_active: usize,
    maximum_active_per_session: usize,
    active_by_session: HashMap<u64, usize>,
    sessions: VecDeque<u64>,
    waiters: HashMap<u64, [VecDeque<oneshot::Sender<FairTurn>>; 3]>,
    completions: mpsc::UnboundedSender<u64>,
}

impl SchedulerState {
    fn enqueue(&mut self, request: TurnRequest) {
        let queues = self
            .waiters
            .entry(request.session)
            .or_insert_with(|| array::from_fn(|_| VecDeque::new()));
        if queues.iter().all(VecDeque::is_empty) {
            self.sessions.push_back(request.session);
        }
        queues[request.lane.index()].push_back(request.ready);
    }

    fn grant(&mut self) {
        while self.active < self.maximum_active {
            let candidates = self.sessions.len();
            let mut selected = None;
            for _ in 0..candidates {
                let session = self
                    .sessions
                    .pop_front()
                    .expect("scheduler candidate count changed unexpectedly");
                if self.active_by_session.get(&session).copied().unwrap_or(0)
                    >= self.maximum_active_per_session
                {
                    self.sessions.push_back(session);
                } else {
                    selected = Some(session);
                    break;
                }
            }
            let Some(session) = selected else {
                break;
            };
            let queues = self
                .waiters
                .get_mut(&session)
                .expect("active scheduler session has waiters");
            let lane = PriorityLane::ALL
                .into_iter()
                .find(|lane| !queues[lane.index()].is_empty())
                .expect("active scheduler session has a nonempty lane")
                .index();
            let ready = queues[lane]
                .pop_front()
                .expect("selected scheduler lane is nonempty");
            if queues.iter().all(VecDeque::is_empty) {
                self.waiters.remove(&session);
            } else {
                self.sessions.push_back(session);
            }
            self.active += 1;
            *self.active_by_session.entry(session).or_default() += 1;
            let _ = ready.send(FairTurn {
                session,
                completions: self.completions.clone(),
            });
        }
    }

    fn complete(&mut self, session: u64) {
        self.active = self
            .active
            .checked_sub(1)
            .expect("scheduler completion without an active turn");
        let active = self
            .active_by_session
            .get_mut(&session)
            .expect("scheduler completion names an inactive session");
        *active -= 1;
        if *active == 0 {
            self.active_by_session.remove(&session);
        }
    }
}

async fn run_scheduler(
    mut requests: mpsc::Receiver<TurnRequest>,
    mut completions: mpsc::UnboundedReceiver<u64>,
    completion_sender: mpsc::UnboundedSender<u64>,
    maximum_active: usize,
    maximum_active_per_session: usize,
) {
    let mut state = SchedulerState {
        active: 0,
        maximum_active,
        maximum_active_per_session,
        active_by_session: HashMap::new(),
        sessions: VecDeque::new(),
        waiters: HashMap::new(),
        completions: completion_sender,
    };
    loop {
        state.grant();
        tokio::select! {
            request = requests.recv() => match request {
                Some(request) => state.enqueue(request),
                None => return,
            },
            completed = completions.recv(), if state.active != 0 => {
                let Some(session) = completed else {
                    return;
                };
                state.complete(session);
            }
        }
    }
}

#[derive(Default)]
struct SessionPins {
    leased: Vec<RootRecord>,
    requests: Vec<(RootRecord, usize)>,
}

impl SessionPins {
    fn roots(&self) -> impl Iterator<Item = RootRecord> + '_ {
        self.leased
            .iter()
            .copied()
            .chain(self.requests.iter().map(|&(root, _)| root))
    }
}

struct SessionPinGuard {
    service: Weak<Service>,
    id: u64,
}

impl SessionPinGuard {
    fn pin(self: &Arc<Self>, root: RootRecord) -> Result<RequestPinGuard> {
        let service = self
            .service
            .upgrade()
            .context("surface service stopped while pinning a request root")?;
        let mut sessions = lock(&service.sessions)?;
        let pins = sessions
            .get_mut(&self.id)
            .context("surface session pin record disappeared")?;
        match pins.requests.iter_mut().find(|(pinned, _)| *pinned == root) {
            Some((_, count)) => {
                *count = count
                    .checked_add(1)
                    .context("request-root pin count overflow")?;
            }
            None => pins.requests.push((root, 1)),
        }
        Ok(RequestPinGuard {
            session: self.clone(),
            root,
        })
    }
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

struct RequestPinGuard {
    session: Arc<SessionPinGuard>,
    root: RootRecord,
}

impl Drop for RequestPinGuard {
    fn drop(&mut self) {
        let Some(service) = self.session.service.upgrade() else {
            return;
        };
        let Ok(mut sessions) = service.sessions.lock() else {
            return;
        };
        let Some(pins) = sessions.get_mut(&self.session.id) else {
            return;
        };
        let Some(index) = pins
            .requests
            .iter()
            .position(|(pinned, _)| *pinned == self.root)
        else {
            return;
        };
        if pins.requests[index].1 == 1 {
            pins.requests.swap_remove(index);
        } else {
            pins.requests[index].1 -= 1;
        }
    }
}

#[derive(Clone)]
struct RootLease {
    record: RootRecord,
    dictionaries: Arc<[ObjectHash]>,
}

#[derive(Default)]
struct DimensionLease {
    active: Option<RootLease>,
    incoming: Option<RootLease>,
    deferred: Option<RootRecord>,
}

#[derive(Default)]
struct SessionRoots {
    dimensions: BTreeMap<String, DimensionLease>,
    failed: HashSet<RootToken>,
}

struct RequestAuthorization {
    dimension: String,
    record: RootRecord,
    dictionaries: Arc<[ObjectHash]>,
}

impl SessionRoots {
    fn announce(
        &mut self,
        dimension: String,
        record: RootRecord,
        dictionaries: Arc<[ObjectHash]>,
    ) -> Result<bool> {
        let token = RootToken::from(record);
        self.failed.remove(&token);
        let lease = self.dimensions.entry(dimension).or_default();
        if lease
            .active
            .as_ref()
            .is_some_and(|active| active.record == record)
            || lease
                .incoming
                .as_ref()
                .is_some_and(|incoming| incoming.record == record)
        {
            return Ok(true);
        }
        let newest = lease
            .active
            .as_ref()
            .map_or(0, |root| root.record.generation)
            .max(
                lease
                    .incoming
                    .as_ref()
                    .map_or(0, |root| root.record.generation),
            )
            .max(lease.deferred.map_or(0, |root| root.generation));
        if record.generation <= newest {
            return Ok(false);
        }
        if lease.incoming.is_some() {
            lease.deferred = Some(record);
            return Ok(false);
        }
        lease.incoming = Some(RootLease {
            record,
            dictionaries,
        });
        Ok(true)
    }

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

    fn is_requestable(&self, root: RootToken) -> bool {
        self.authorize(root).is_ok()
    }

    fn authorize(&self, root: RootToken) -> Result<RequestAuthorization> {
        if self.failed.contains(&root) {
            bail!("request names a failed root");
        }
        let mut found = None;
        for (dimension, lease) in &self.dimensions {
            for leased in lease
                .active
                .as_ref()
                .into_iter()
                .chain(lease.incoming.as_ref())
            {
                if RootToken::from(leased.record) != root {
                    continue;
                }
                if found.is_some() {
                    bail!("one root token is leased more than once");
                }
                found = Some(RequestAuthorization {
                    dimension: dimension.clone(),
                    record: leased.record,
                    dictionaries: leased.dictionaries.clone(),
                });
            }
        }
        found.context("request names a stale or unannounced root")
    }

    fn dimension_for(&self, root: RootToken) -> Result<&str> {
        let mut found = None;
        for (dimension, lease) in &self.dimensions {
            if lease
                .active
                .as_ref()
                .into_iter()
                .chain(lease.incoming.as_ref())
                .any(|leased| RootToken::from(leased.record) == root)
            {
                if found.is_some() {
                    bail!("one root token is leased for multiple dimensions");
                }
                found = Some(dimension.as_str());
            }
        }
        found.context("request names an unannounced root")
    }

    fn record_for(&self, root: RootToken) -> Result<RootRecord> {
        Ok(self.authorize(root)?.record)
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
                    .map(|root| root.record)
            })
            .collect::<Vec<_>>();
        roots.sort_unstable_by_key(|root| (root.dimension, root.generation));
        roots.dedup();
        roots
    }
}

struct ResponseObject {
    source: StoredObjectSource,
    header: [u8; OBJECT_RESPONSE_HEADER_BYTES],
}

fn prepare_response(
    surface: &SurfaceService,
    root: RootRecord,
    dictionaries: &[ObjectHash],
    hashes: &[ObjectHash],
) -> Result<Vec<ResponseObject>> {
    let mut response = Vec::with_capacity(hashes.len());
    let mut compressed = 0usize;
    let mut canonical = 0usize;
    for &hash in hashes {
        let loaded = match surface.surface.open_wire_object(root, dictionaries, hash) {
            Ok(value) => value,
            Err(error) if error.downcast_ref::<LeasedDictionaryMismatch>().is_some() => {
                return Err(RequestRejected(error.to_string()).into());
            }
            Err(error) => return Err(error),
        };
        let Some((source, dictionary_id)) = loaded else {
            return Err(RequestRejected(format!("requested object {hash} is unavailable")).into());
        };
        if !client_transferable_kind(source.kind()) {
            return Err(RequestRejected("requested object is server-internal".to_owned()).into());
        }
        let canonical_size = u32::try_from(source.canonical_size())
            .context("canonical object length does not fit the wire format")?;
        let compressed_size = u32::try_from(source.compressed_size())
            .context("compressed object length does not fit the wire format")?;
        canonical = canonical
            .checked_add(canonical_size as usize)
            .context("object-request canonical byte count overflow")?;
        compressed = compressed
            .checked_add(compressed_size as usize)
            .context("object-request compressed byte count overflow")?;
        if canonical > MAX_STREAM_CANONICAL_BYTES || compressed > MAX_STREAM_COMPRESSED_BYTES {
            return Err(RequestRejected(
                "object-request response exceeds stream bounds".to_owned(),
            )
            .into());
        }
        let header = ObjectResponseHeader {
            hash,
            kind: source.kind(),
            dictionary_id,
            canonical_size,
            compressed_size,
            compressed_crc: source.compressed_crc(),
        }
        .encode()?;
        response.push(ResponseObject { header, source });
    }
    Ok(response)
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

#[derive(Debug)]
struct RequestRejected(String);

impl fmt::Display for RequestRejected {
    fn fmt(&self, output: &mut fmt::Formatter<'_>) -> fmt::Result {
        output.write_str(&self.0)
    }
}

impl std::error::Error for RequestRejected {}

fn is_request_rejected(error: &anyhow::Error) -> bool {
    error.chain().any(|cause| cause.is::<RequestRejected>())
}

fn is_quic_cancellation(error: &anyhow::Error) -> bool {
    error.chain().any(|cause| {
        cause.is::<quinn::StoppedError>()
            || cause.is::<quinn::WriteError>()
            || cause.is::<quinn::ReadError>()
            || cause.is::<quinn::ConnectionError>()
    })
}
