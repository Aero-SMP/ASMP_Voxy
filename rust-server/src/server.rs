//! Session-owned foreground desires and independently paced background terrain.
use crate::{
    anvil::AnvilWorld,
    crc::crc32c,
    pacer::{ENVELOPE_BYTES, PacedSocket, RateLedger, UdpMux},
    quarantine,
    regional::{
        CatalogDefinition, PreparedSection, RegionalAnnouncement, RegionalResponder,
        RegionalService,
        wire::{
            self, ALPN, ContentBinding, ControlMessage, Desire, PriorityLane, RecordStatus,
            STREAM_BACKGROUND, STREAM_CONTROL, STREAM_SECTION_LANE, StreamingSettings,
            encode_control_record, read_control, read_lane, read_stream_role,
        },
    },
    replace_synced, sync_parent,
};
use anyhow::{Context, Result, bail};
use quinn::{Endpoint, IdleTimeout, Runtime, VarInt, crypto::rustls::QuicServerConfig};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, HashMap, HashSet, VecDeque},
    fs,
    future::Future,
    io::Write,
    net::SocketAddr,
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex, Weak,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};
use tokio::{sync::Notify, time::Instant};

const SERVICE_SHUTDOWN_GRACE: Duration = Duration::from_millis(500);
const ENDPOINT_DRAIN_TIMEOUT: Duration = Duration::from_millis(250);
const CONTROL_STREAM_PRIORITY: i32 = 3;
const CONTROL_WRITE_PROGRESS_TIMEOUT: Duration = Duration::from_secs(15);
const MAX_PENDING_HANDSHAKES: usize = 128;
const MAX_LIVE_CONNECTIONS: usize = 1_024;
const IDLE_TIMEOUT: Duration = Duration::from_secs(60);
const KEEPALIVE_INTERVAL: Duration = Duration::from_secs(15);
const MAX_IDENTITY_BYTES: usize = 64 * 1024;
const CERTIFICATE_FILE: &str = "certificate.der";
const PRIVATE_KEY_FILE: &str = "private-key.der";

type Region = (i32, i32);
type Subscribers = HashMap<(String, i32, i32), HashMap<usize, Weak<Session>>>;
#[derive(Debug)]
pub struct ServerState {
    server_instance: u64,
    regional: Arc<RegionalService>,
    subscribers: Mutex<Subscribers>,
    started: Instant,
    trace: bool,
}
impl ServerState {
    pub fn new(
        dimensions: &BTreeMap<String, Arc<AnvilWorld>>,
        catalog_id: u64,
        regional: Arc<RegionalService>,
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
            regional,
            subscribers: Mutex::new(HashMap::new()),
            started: Instant::now(),
            trace: std::env::var("VOXY_BACKGROUND_TRACE").as_deref() == Ok("1"),
        }
    }

    fn register(&self, session: &Arc<Session>, coordinate: Region) {
        self.subscribers
            .lock()
            .expect("terrain subscriber owner poisoned")
            .entry((session.dimension.clone(), coordinate.0, coordinate.1))
            .or_default()
            .insert(session.id, Arc::downgrade(session));
    }
    fn release(&self, session: &Session, coordinate: Region) {
        let mut subscribers = self
            .subscribers
            .lock()
            .expect("terrain subscriber owner poisoned");
        let key = (session.dimension.clone(), coordinate.0, coordinate.1);
        if let Some(region) = subscribers.get_mut(&key) {
            region.remove(&session.id);
            if region.is_empty() {
                subscribers.remove(&key);
            }
        }
    }
    fn publication_loop(self: &Arc<Self>) {
        let state = self.clone();
        let mut announcements = self.regional.subscribe();
        tokio::spawn(async move {
            loop {
                match announcements.recv().await {
                    Ok(RegionalAnnouncement::Changed {
                        dimension,
                        region_x,
                        region_z,
                        changed_ordinals,
                        ..
                    }) => {
                        let recipients = state
                            .subscribers
                            .lock()
                            .expect("terrain subscriber owner poisoned")
                            .get(&(dimension, region_x, region_z))
                            .map(|entries| {
                                entries
                                    .values()
                                    .filter_map(Weak::upgrade)
                                    .collect::<Vec<_>>()
                            })
                            .unwrap_or_default();
                        for session in recipients {
                            session.changed((region_x, region_z), changed_ordinals.as_deref());
                        }
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {
                        // Recover current state; a slow receiver never needs lost event history.
                        let mut recipients = HashMap::new();
                        for region in state
                            .subscribers
                            .lock()
                            .expect("terrain subscriber owner poisoned")
                            .values()
                        {
                            for (&id, weak) in region {
                                if let Some(session) = weak.upgrade() {
                                    recipients.insert(id, session);
                                }
                            }
                        }
                        for session in recipients.into_values() {
                            session.reconcile();
                        }
                    }
                    Ok(RegionalAnnouncement::Shutdown(_))
                    | Err(tokio::sync::broadcast::error::RecvError::Closed) => return,
                }
            }
        });
    }
}

#[derive(Debug)]
struct Interest {
    desire: Desire,
    known: Option<ContentBinding>,
    primary_known: Option<ContentBinding>,
    active: Option<(u64, u64, bool)>,
    revision: u64,
}
#[derive(Debug, Default)]
struct Wants {
    interests: HashMap<u64, Interest>,
    regions: HashMap<Region, HashMap<u32, u64>>,
    foreground: [VecDeque<u64>; 2],
    queued: [HashSet<u64>; 2],
    background: HashSet<u64>,
}
impl Wants {
    fn queue(&mut self, key: u64) {
        let Some(interest) = self.interests.get(&key) else {
            return;
        };
        if interest.desire.purpose == 2 {
            self.background.insert(key);
        } else if interest.active.is_none() {
            let lane = interest.desire.purpose as usize;
            if self.queued[lane].insert(key) {
                self.foreground[lane].push_back(key);
            }
        }
    }
}
#[derive(Debug)]
struct PrimaryWriter {
    send: quinn::SendStream,
    failed: bool,
}

#[derive(Debug)]
struct Session {
    id: usize,
    peer: SocketAddr,
    dimension: String,
    state: Arc<ServerState>,
    responder: RegionalResponder,
    wants: Mutex<Wants>,
    foreground: [Notify; 2],
    background: Notify,
    stopped: Notify,
    closed: AtomicBool,
    settings: Mutex<StreamingSettings>,
    ledger: Arc<RateLedger>,
    metadata: tokio::sync::Mutex<PrimaryWriter>,
    catalogues: Mutex<HashSet<[u8; 32]>>,
}
#[derive(Clone, Copy)]
struct Claim {
    desire: Desire,
    revision: u64,
    known: Option<ContentBinding>,
}
impl Session {
    fn has_catalogue(&self, fingerprint: [u8; 32]) -> bool {
        self.catalogues
            .lock()
            .expect("catalogue announcement owner poisoned")
            .contains(&fingerprint)
    }
    async fn announce_catalogue(&self, catalogue: &CatalogDefinition) -> Result<()> {
        let mut writer = self.metadata.lock().await;
        if writer.failed || self.closed.load(Ordering::Acquire) {
            bail!("primary catalogue writer is closed");
        }
        if self.has_catalogue(catalogue.fingerprint) {
            return Ok(());
        }
        let started = Instant::now();
        self.trace_catalogue_start("foreground", catalogue);
        if let Err(error) = writer.send.write_all(&catalogue.frame).await {
            // A partial frame cannot be retried on this cursor. The lane error closes primary.
            writer.failed = true;
            return Err(error).context("primary catalogue frame failed");
        }
        self.catalogues
            .lock()
            .expect("catalogue announcement owner poisoned")
            .insert(catalogue.fingerprint);
        self.trace_catalogue("foreground", catalogue, started);
        Ok(())
    }
    fn trace_catalogue(&self, channel: &str, catalogue: &CatalogDefinition, started: Instant) {
        if self.state.trace {
            eprintln!(
                "VOXY_CATALOG_SENT session={} channel={channel} fingerprint={} canonical_bytes={} compressed_bytes={} frame_bytes={} write_ns={}",
                self.id,
                blake3::Hash::from(catalogue.fingerprint),
                catalogue.canonical_length,
                catalogue.compressed_length,
                catalogue.frame.len(),
                started.elapsed().as_nanos()
            );
        }
    }
    fn trace_catalogue_start(&self, channel: &str, catalogue: &CatalogDefinition) {
        if self.state.trace {
            eprintln!(
                "VOXY_CATALOG_WRITE session={} channel={channel} fingerprint={} frame_bytes={}",
                self.id,
                blake3::Hash::from(catalogue.fingerprint),
                catalogue.frame.len()
            );
        }
    }
    fn wake(&self) {
        for lane in &self.foreground {
            lane.notify_waiters();
        }
        self.background.notify_waiters();
    }
    fn close(&self) {
        self.closed.store(true, Ordering::Release);
        self.stopped.notify_waiters();
        self.wake();
    }
    async fn ended(&self) {
        loop {
            let notified = self.stopped.notified();
            if self.closed.load(Ordering::Acquire) {
                return;
            }
            notified.await;
        }
    }
    fn apply(self: &Arc<Self>, desires: Vec<Desire>) -> Result<()> {
        let mut additions = Vec::new();
        {
            let mut wants = self.wants.lock().expect("terrain desire owner poisoned");
            for desire in desires {
                let key = crate::key::SectionKey::unpack(desire.key)?;
                let side = 16i32 >> key.level;
                let coordinate = (key.x.div_euclid(side), key.z.div_euclid(side));
                let ordinal = self
                    .responder
                    .layout()
                    .index(coordinate.0, coordinate.1, key.into())
                    .map(|index| index as u32)
                    .unwrap_or(u32::MAX);
                let first = !wants.regions.contains_key(&coordinate);
                wants
                    .regions
                    .entry(coordinate)
                    .or_default()
                    .insert(ordinal, desire.key);
                if first {
                    additions.push(coordinate);
                }
                let (active, revision) = wants
                    .interests
                    .get(&desire.key)
                    .map_or((None, 0), |old| (old.active, old.revision.wrapping_add(1)));
                wants.interests.insert(
                    desire.key,
                    Interest {
                        desire,
                        known: desire.have,
                        primary_known: desire.have,
                        active,
                        revision,
                    },
                );
                if desire.purpose != 2 {
                    wants.queue(desire.key);
                } else if desire.have.is_some() {
                    wants.background.insert(desire.key);
                }
            }
        }
        for coordinate in additions {
            self.responder
                .subscribe_region(coordinate.0, coordinate.1)?;
            self.state.register(self, coordinate);
        }
        let interval = self
            .settings
            .lock()
            .expect("streaming settings owner poisoned")
            .interval_millis;
        if !self
            .wants
            .lock()
            .expect("terrain desire owner poisoned")
            .interests
            .is_empty()
        {
            self.state.regional.set_cadence(self.id, Some(interval))?;
        }
        self.wake();
        Ok(())
    }
    fn drop_keys(&self, keys: Vec<u64>) -> Result<()> {
        let mut releases = Vec::new();
        {
            let mut wants = self.wants.lock().expect("terrain desire owner poisoned");
            for key in keys {
                if wants.interests.remove(&key).is_none() {
                    continue;
                }
                let spatial = crate::key::SectionKey::unpack(key)?;
                let side = 16i32 >> spatial.level;
                let coordinate = (spatial.x.div_euclid(side), spatial.z.div_euclid(side));
                if let Some(region) = wants.regions.get_mut(&coordinate) {
                    let ordinal = self
                        .responder
                        .layout()
                        .index(coordinate.0, coordinate.1, spatial.into())
                        .map(|index| index as u32)
                        .unwrap_or(u32::MAX);
                    if region.get(&ordinal) == Some(&key) {
                        region.remove(&ordinal);
                    }
                    if region.is_empty() {
                        wants.regions.remove(&coordinate);
                        releases.push(coordinate);
                    }
                }
                wants.background.remove(&key);
                for queued in &mut wants.queued {
                    queued.remove(&key);
                }
            }
        }
        if self
            .wants
            .lock()
            .expect("terrain desire owner poisoned")
            .interests
            .is_empty()
        {
            self.state.regional.set_cadence(self.id, None)?;
        }
        for coordinate in releases {
            self.state.release(self, coordinate);
            self.responder
                .unsubscribe_region(coordinate.0, coordinate.1)?;
        }
        Ok(())
    }
    fn changed(&self, coordinate: Region, ordinals: Option<&[u32]>) {
        let mut wants = self.wants.lock().expect("terrain desire owner poisoned");
        let Some(region) = wants.regions.get(&coordinate) else {
            return;
        };
        let keys = match ordinals {
            Some(ordinals) => ordinals
                .iter()
                .filter_map(|ordinal| region.get(ordinal).copied())
                .collect::<Vec<_>>(),
            None => region.values().copied().collect(),
        };
        for key in keys {
            let Some(interest) = wants.interests.get_mut(&key) else {
                continue;
            };
            interest.revision = interest.revision.wrapping_add(1);
            if interest.known.is_none() && interest.desire.purpose != 2 {
                wants.queue(key);
            } else {
                wants.background.insert(key);
            }
        }
        drop(wants);
        self.wake();
    }
    fn reconcile(&self) {
        let coordinates = self
            .wants
            .lock()
            .expect("terrain desire owner poisoned")
            .regions
            .keys()
            .copied()
            .collect::<Vec<_>>();
        for coordinate in coordinates {
            self.changed(coordinate, None);
        }
    }
    fn reconcile_background(&self) {
        // Reliable writes are not durable receipt. An abandoned background connection may
        // have buffered records; only peer holdings and the still-live primary are a safe base.
        let mut wants = self.wants.lock().expect("terrain desire owner poisoned");
        for interest in wants.interests.values_mut() {
            interest.known = interest.primary_known;
            if interest.active.is_some_and(|(_, _, background)| background) {
                interest.active = None;
            }
        }
        drop(wants);
        self.reconcile();
    }

    fn claim(&self, lane: usize) -> Option<Claim> {
        let mut wants = self.wants.lock().expect("terrain desire owner poisoned");
        while let Some(key) = wants.foreground[lane].pop_front() {
            if !wants.queued[lane].remove(&key) {
                continue;
            }
            let Some(interest) = wants.interests.get_mut(&key) else {
                continue;
            };
            if interest
                .active
                .is_some_and(|(_, _, background)| !background)
                || interest.desire.purpose as usize != lane
            {
                continue;
            }
            // Urgent misses may supersede a slow background owner; its frame is not cancelled.
            interest.active = Some((interest.desire.ticket, interest.revision, false));
            return Some(Claim {
                desire: interest.desire,
                revision: interest.revision,
                known: interest.known,
            });
        }
        None
    }
    fn background_keys(&self) -> Vec<u64> {
        self.wants
            .lock()
            .expect("terrain desire owner poisoned")
            .background
            .iter()
            .copied()
            .collect()
    }
    fn claim_background(&self, key: u64) -> Option<Claim> {
        let mut wants = self.wants.lock().expect("terrain desire owner poisoned");
        let interest = wants.interests.get_mut(&key)?;
        if interest.active.is_some() {
            return None;
        }
        interest.active = Some((interest.desire.ticket, interest.revision, true));
        let claim = Claim {
            desire: interest.desire,
            revision: interest.revision,
            known: interest.known,
        };
        wants.background.remove(&key);
        Some(claim)
    }
    fn finish(&self, claim: Claim, binding: Option<ContentBinding>, background: bool) {
        let mut wants = self.wants.lock().expect("terrain desire owner poisoned");
        let Some(interest) = wants.interests.get_mut(&claim.desire.key) else {
            return;
        };
        if interest.active != Some((claim.desire.ticket, claim.revision, background)) {
            return;
        }
        interest.active = None;
        if interest.desire.ticket == claim.desire.ticket {
            if let Some(binding) = binding {
                interest.known = Some(binding);
                if !background {
                    interest.primary_known = Some(binding);
                }
            }
        }
        if interest.desire.ticket != claim.desire.ticket || interest.revision != claim.revision {
            if interest.desire.purpose != 2
                && (interest.desire.ticket != claim.desire.ticket || interest.known.is_none())
            {
                wants.queue(claim.desire.key);
            } else {
                wants.background.insert(claim.desire.key);
            }
        }
        drop(wants);
        self.wake();
    }
    fn policy(&self, settings: StreamingSettings) -> Result<()> {
        settings.validate()?;
        *self
            .settings
            .lock()
            .expect("streaming settings owner poisoned") = settings;
        self.ledger.update(settings.bandwidth_kbps);
        let interested = !self
            .wants
            .lock()
            .expect("terrain desire owner poisoned")
            .interests
            .is_empty();
        self.state
            .regional
            .set_cadence(self.id, interested.then_some(settings.interval_millis))?;
        self.wake();
        Ok(())
    }
    fn trace(&self, phase: &str, sequence: u64, keys: usize, port: u16) {
        if !self.state.trace {
            return;
        }
        let settings = *self
            .settings
            .lock()
            .expect("streaming settings owner poisoned");
        let (bytes, datagrams) = self.ledger.counters();
        eprintln!(
            "VOXY_BACKGROUND_BATCH phase={phase} session={} peer={} port={port} seq={sequence} monotonic_ns={} interval_ms={} keys={keys} ip_bytes={bytes} datagrams={datagrams}",
            self.id,
            self.peer,
            self.state.started.elapsed().as_nanos(),
            settings.interval_millis
        );
    }
}

pub async fn serve(
    state: Arc<ServerState>,
    listen: SocketAddr,
    identity_directory: &Path,
    shutdown: impl Future<Output = Result<()>>,
) -> Result<()> {
    let identity = load_or_create_identity(identity_directory)?;
    let server_config = make_server_config(&identity)?;
    let runtime = Arc::new(quinn::TokioRuntime);
    let socket = std::net::UdpSocket::bind(listen)
        .with_context(|| format!("bind Voxy QUIC UDP endpoint {listen}"))?;
    let multiplex = UdpMux::new(runtime.wrap_udp_socket(socket)?);
    let endpoint = Endpoint::new_with_abstract_socket(
        Default::default(),
        Some(server_config.clone()),
        multiplex.foreground(),
        runtime,
    )?;
    let mut background_config = server_config.clone();
    background_config.transport_config(make_transport_config(true)?);
    let actual = endpoint.local_addr()?;
    eprintln!(
        "VOXY_READY udp_port={} alpn={} cert_sha256={}",
        actual.port(),
        std::str::from_utf8(ALPN).expect("static ALPN"),
        identity.fingerprint
    );
    state.publication_loop();
    let pending = Arc::new(tokio::sync::Semaphore::new(MAX_PENDING_HANDSHAKES));
    let live = Arc::new(tokio::sync::Semaphore::new(MAX_LIVE_CONNECTIONS));
    tokio::pin!(shutdown);
    loop {
        tokio::select! {
            incoming=endpoint.accept()=> {
                let Some(incoming)=incoming else { return Ok(()); };
                let Ok(live_permit)=live.clone().try_acquire_owned() else {incoming.refuse();continue;};
                let Ok(handshake_permit)=pending.clone().try_acquire_owned() else {incoming.refuse();continue;};
                let state=state.clone();let config=background_config.clone();let multiplex=multiplex.clone();
                tokio::spawn(async move {
                    if let Err(error)=serve_connection(state,incoming,config,multiplex,handshake_permit,live_permit).await {
                        eprintln!("Voxy QUIC session ended: {error:#}");
                    }
                });
            }
            result=&mut shutdown=> {
                result?; state.regional.shutdown("Voxy server shutting down");
                tokio::time::sleep(SERVICE_SHUTDOWN_GRACE).await;
                endpoint.close(VarInt::from_u32(0),b"Voxy server shutting down");
                let _=tokio::time::timeout(ENDPOINT_DRAIN_TIMEOUT,endpoint.wait_idle()).await;
                return Ok(());
            }
        }
    }
}

async fn serve_connection(
    state: Arc<ServerState>,
    incoming: quinn::Incoming,
    config: quinn::ServerConfig,
    multiplex: Arc<UdpMux>,
    handshake_permit: tokio::sync::OwnedSemaphorePermit,
    _live_permit: tokio::sync::OwnedSemaphorePermit,
) -> Result<()> {
    // Quinn's idle timeout owns handshake loss recovery; a second five-second timer rejected impaired clients.
    let connection = incoming.await?;
    let (send, mut recv) = connection.accept_bi().await?;
    send.set_priority(CONTROL_STREAM_PRIORITY)?;
    if read_stream_role(&mut recv).await? != Some(STREAM_CONTROL) {
        bail!("first stream must be control");
    }
    let (dimension, expected_world, held_catalog, settings, desires) =
        match read_control(&mut recv).await? {
            Some(ControlMessage::Open {
                dimension,
                expected_world,
                held_catalog,
                settings,
                desires,
            }) => (dimension, expected_world, held_catalog, settings, desires),
            _ => bail!("OPEN must be the first control message"),
        };
    drop(handshake_permit);
    let responder = state
        .regional
        .responder(&dimension, state.server_instance)?;
    let mut token = [0; 32];
    connection
        .export_keying_material(&mut token, b"voxy-background-session", b"")
        .map_err(|_| anyhow::anyhow!("session token export failed"))?;
    let ledger = RateLedger::new(settings.bandwidth_kbps);
    let runtime = Arc::new(quinn::TokioRuntime);
    let socket = PacedSocket::new(multiplex.background(&token)?, ledger.clone());
    let background_endpoint =
        Endpoint::new_with_abstract_socket(Default::default(), Some(config), socket, runtime)?;
    let port = background_endpoint.local_addr()?.port();
    let matched = expected_world == [0; 32] || expected_world == responder.world_identity();
    let mut catalogues = HashSet::new();
    if matched && held_catalog != [0; 32] {
        catalogues.insert(held_catalog);
    }
    let session = Arc::new(Session {
        id: connection.stable_id(),
        peer: connection.remote_address(),
        dimension,
        state: state.clone(),
        responder,
        wants: Mutex::new(Wants::default()),
        foreground: [Notify::new(), Notify::new()],
        background: Notify::new(),
        stopped: Notify::new(),
        closed: AtomicBool::new(false),
        settings: Mutex::new(settings),
        ledger,
        metadata: tokio::sync::Mutex::new(PrimaryWriter {
            send,
            failed: false,
        }),
        catalogues: Mutex::new(catalogues),
    });
    session.policy(settings)?;
    if state.trace {
        let misses = desires.iter().filter(|desire| desire.purpose != 2).count();
        eprintln!(
            "VOXY_STREAM_SESSION session={} peer={} port={} interval_ms={} bandwidth_kbps={} initial_misses={} initial_interests={} held_catalog={}",
            session.id,
            session.peer,
            port,
            settings.interval_millis,
            settings.bandwidth_kbps,
            misses,
            desires.len(),
            blake3::Hash::from(held_catalog)
        );
    }
    if matched {
        session.apply(desires)?;
    }
    let result=async {
        {
            let mut writer=session.metadata.lock().await;
            write_control(&mut writer.send,&session.responder.hello(token)?,CONTROL_WRITE_PROGRESS_TIMEOUT).await?;
        }
        let background_session=session.clone();let background=background_endpoint.clone();
        let mut background_task=Some(tokio::spawn(async move {serve_background(background_session,background,token).await}));
        let mut lanes=tokio::task::JoinSet::new();
        // One reader owns the byte cursor. Cancelling read_control after accepting another lane
        // would discard an already-read frame prefix and corrupt the next control message.
        let control_session=session.clone();
        let mut controls=tokio::spawn(async move {
            loop {
                match read_control(&mut recv).await? {
                    Some(ControlMessage::Desires(desires))=>control_session.apply(desires)?,
                    Some(ControlMessage::Drop(keys))=>control_session.drop_keys(keys)?,
                    Some(ControlMessage::Settings(settings))=>control_session.policy(settings)?,
                    None=>return Ok::<(),anyhow::Error>(()),
                    Some(_)=>bail!("unexpected primary control record"),
                }
            }
        });
        let mut controls_finished=false;
        let result=loop {
            tokio::select! {
                incoming=connection.accept_bi()=> {
                    let (send,recv)=match incoming {Ok(pair)=>pair,Err(error)=>break Err(error.into())};
                    let owner=session.clone();lanes.spawn(async move {serve_lane(owner,send,recv).await});
                }
                message=&mut controls=> {
                    controls_finished=true;
                    break match message { Ok(result)=>result,Err(error)=>Err(error.into()) };
                }
                lane=lanes.join_next(),if !lanes.is_empty()=> {
                    match lane {Some(Ok(Ok(())))=>{},Some(Ok(Err(error)))=>break Err(error),Some(Err(error))=>break Err(error.into()),None=>{}}
                }
                background=async { background_task.as_mut().unwrap().await },if background_task.is_some()=> {
                    if let Ok(Err(error))=background {eprintln!("Voxy background connection failed: {error:#}; foreground remains available");}
                    background_task=None;
                }
            }
        };
        session.close();connection.close(VarInt::from_u32(0),b"session ended");
        while lanes.join_next().await.is_some() {}
        if !controls_finished { let _=controls.await; }
        if let Some(background_task)=background_task { let _=background_task.await; }
        result
    }.await;
    session.close();
    connection.close(VarInt::from_u32(0), b"primary session ended");
    background_endpoint.close(VarInt::from_u32(0), b"primary session ended");
    background_endpoint.wait_idle().await;
    let coordinates = session
        .wants
        .lock()
        .expect("terrain desire owner poisoned")
        .regions
        .keys()
        .copied()
        .collect::<Vec<_>>();
    for coordinate in coordinates {
        state.release(&session, coordinate);
        session
            .responder
            .unsubscribe_region(coordinate.0, coordinate.1)?;
    }
    state.regional.set_cadence(session.id, None)?;
    if state.trace {
        let (bytes, datagrams) = session.ledger.counters();
        eprintln!(
            "VOXY_BACKGROUND_STATS session={} peer={} port={} ip_bytes={} datagrams={}",
            session.id, session.peer, port, bytes, datagrams
        );
    }
    result
}

async fn serve_lane(
    session: Arc<Session>,
    mut send: quinn::SendStream,
    mut recv: quinn::RecvStream,
) -> Result<()> {
    if read_stream_role(&mut recv).await? != Some(STREAM_SECTION_LANE) {
        bail!("invalid foreground stream role");
    }
    let lane = read_lane(&mut recv).await?;
    send.set_priority(match lane {
        PriorityLane::Coverage => 2,
        PriorityLane::Refinement => 1,
    })?;
    let lane = lane as usize;
    loop {
        let notified = session.foreground[lane].notified();
        if session.closed.load(Ordering::Acquire) {
            return Ok(());
        }
        if let Some(claim) = session.claim(lane) {
            let sent = tokio::select! {result=send_claim(&session,&mut send,claim,None)=>result?,_=session.ended()=>return Ok(())};
            session.finish(claim, sent, false);
        } else {
            tokio::select! {_=notified=>{},_=session.ended()=>return Ok(())}
        }
    }
}

async fn send_claim(
    session: &Session,
    send: &mut quinn::SendStream,
    claim: Claim,
    background_catalogues: Option<&mut HashSet<[u8; 32]>>,
) -> Result<Option<ContentBinding>> {
    let background = background_catalogues.is_some();
    let responder = session.responder.clone();
    let desire = claim.desire;
    let mut prepared: PreparedSection =
        tokio::task::spawn_blocking(move || responder.prepare(desire.ticket, desire.key)).await??;
    let descriptor = prepared.descriptor;
    if background
        && claim.known == Some(descriptor.binding)
        && descriptor.status != RecordStatus::NotReady
    {
        return Ok(Some(descriptor.binding));
    }
    if descriptor.binding.catalog_fingerprint != [0; 32] {
        if let Some(announced) = background_catalogues {
            if !announced.contains(&descriptor.binding.catalog_fingerprint)
                && !session.has_catalogue(descriptor.binding.catalog_fingerprint)
            {
                let started = Instant::now();
                session.trace_catalogue_start("background", &prepared.catalog);
                send.write_all(&prepared.catalog.frame).await?;
                announced.insert(descriptor.binding.catalog_fingerprint);
                session.trace_catalogue("background", &prepared.catalog, started);
            }
        } else {
            session.announce_catalogue(&prepared.catalog).await?;
        }
    }
    if descriptor.status == RecordStatus::Data
        && claim
            .known
            .is_some_and(|known| known.has_body() && known.same_body(descriptor.binding))
    {
        prepared.descriptor.status = RecordStatus::Reuse;
    }
    let descriptor = prepared.descriptor;
    let body = tokio::task::spawn_blocking(move || prepared.body()).await??;
    wire::write_record(send, descriptor, &body).await?;
    Ok((descriptor.status != RecordStatus::NotReady).then_some(descriptor.binding))
}

async fn serve_background(
    session: Arc<Session>,
    endpoint: Endpoint,
    token: [u8; 32],
) -> Result<()> {
    let mut last_start = Instant::now();
    let mut sequence = 0u64;
    loop {
        let incoming = tokio::select! {
            incoming = endpoint.accept() => incoming,
            _ = session.ended() => return Ok(()),
        };
        let Some(incoming) = incoming else {
            return Ok(());
        };
        let connection = tokio::select! {
            result = incoming => match result {
                Ok(connection) => connection,
                Err(error) => {
                    eprintln!("background handshake failed: {error}");
                    continue;
                }
            },
            _ = session.ended() => return Ok(()),
        };
        let transfer = async {
            let (mut send, mut recv) = connection.accept_bi().await?;
            if read_stream_role(&mut recv).await? != Some(STREAM_BACKGROUND) {
                bail!("invalid background stream role");
            }
            let mut supplied = [0; 32];
            recv.read_exact(&mut supplied).await?;
            if supplied != token {
                bail!("background session token mismatch");
            }
            let mut held_catalog = [0; 32];
            recv.read_exact(&mut held_catalog).await?;
            if session.state.trace {
                let (bytes, datagrams) = session.ledger.counters();
                eprintln!(
                    "VOXY_BACKGROUND_CONNECTED session={} peer={} port={} ip_bytes={} datagrams={}",
                    session.id,
                    connection.remote_address(),
                    endpoint.local_addr()?.port(),
                    bytes,
                    datagrams
                );
            }
            session.reconcile_background();
            let mut catalogues = HashSet::new();
            if held_catalog != [0; 32] {
                catalogues.insert(held_catalog);
            }
            loop {
                let settings = *session
                    .settings
                    .lock()
                    .expect("streaming settings owner poisoned");
                let deadline = last_start + Duration::from_millis(settings.interval_millis);
                if Instant::now() < deadline {
                    tokio::select! {
                        _ = tokio::time::sleep_until(deadline) => {},
                        _ = session.background.notified() => continue,
                        _ = session.ended() => return Ok::<(), anyhow::Error>(()),
                    }
                }
                // Observe wakeups before the predicate: notify_waiters retains changes
                // for an existing future, but not for one created after the last key arrives.
                let notified = session.background.notified();
                let keys = session.background_keys();
                if keys.is_empty() {
                    tokio::select! {
                        _ = notified => {},
                        _ = session.ended() => return Ok(()),
                    }
                    continue;
                }
                last_start = Instant::now();
                sequence = sequence
                    .checked_add(1)
                    .context("background batch counter exhausted")?;
                session.trace("start", sequence, keys.len(), endpoint.local_addr()?.port());
                for &key in &keys {
                    let Some(claim) = session.claim_background(key) else {
                        continue;
                    };
                    let sent = tokio::select! {
                        result = send_claim(&session, &mut send, claim, Some(&mut catalogues)) => match result {
                            Ok(binding) => binding,
                            Err(error) => {
                                session.finish(claim, None, true);
                                return Err(error);
                            }
                        },
                        _ = session.ended() => return Ok(()),
                    };
                    session.finish(claim, sent, true);
                }
                session.trace(
                    "finish",
                    sequence,
                    keys.len(),
                    endpoint.local_addr()?.port(),
                );
            }
        };
        let result = tokio::select! {
            result = transfer => result,
            error = connection.closed() => Err(error.into()),
            _ = session.ended() => Ok(()),
        };
        connection.close(VarInt::from_u32(0), b"background stream ended");
        if let Err(error) = result {
            eprintln!("Voxy background reconnect required: {error:#}");
        }
        if session.closed.load(Ordering::Acquire) {
            return Ok(());
        }
    }
}

async fn write_control(
    send: &mut quinn::SendStream,
    message: &ControlMessage,
    progress_timeout: Duration,
) -> Result<()> {
    let record = encode_control_record(message)?;
    let mut remaining = record.as_slice();
    while !remaining.is_empty() {
        // Quinn's inherent write is cancellation-safe and reports the accepted prefix.
        // The next deadline starts only after bytes were accepted, not after a wakeup.
        let written = tokio::time::timeout(progress_timeout, send.write(remaining))
            .await
            .context("regional control write made no progress")??;
        if written == 0 {
            bail!("regional control write accepted zero bytes");
        }
        remaining = &remaining[written..];
    }
    Ok(())
}

struct PersistentIdentity {
    certificate: Vec<u8>,
    private_key: Vec<u8>,
    fingerprint: String,
}

fn load_or_create_identity(directory: &Path) -> Result<PersistentIdentity> {
    fs::create_dir_all(directory)
        .with_context(|| format!("create QUIC identity directory {}", directory.display()))?;
    let certificate_path = directory.join(CERTIFICATE_FILE);
    let private_key_path = directory.join(PRIVATE_KEY_FILE);
    if certificate_path.exists() && private_key_path.exists() {
        match PersistentIdentity::read(&certificate_path, &private_key_path) {
            Ok(identity) if make_server_config(&identity).is_ok() => return Ok(identity),
            Ok(_) | Err(_) => {
                quarantine(&certificate_path);
                quarantine(&private_key_path);
            }
        }
    } else {
        if certificate_path.exists() {
            quarantine(&certificate_path);
        }
        if private_key_path.exists() {
            quarantine(&private_key_path);
        }
    }
    let generated = rcgen::generate_simple_self_signed(vec!["voxy.local".to_owned()])?;
    let certificate = generated.cert.der().to_vec();
    let private_key = generated.key_pair.serialize_der();
    let certificate_temp = temporary_path(&certificate_path);
    let key_temp = temporary_path(&private_key_path);
    replace_synced(&certificate_path, &certificate_temp, &certificate)?;
    replace_private_key(&private_key_path, &key_temp, &private_key)?;
    PersistentIdentity::new(certificate, private_key)
}

impl PersistentIdentity {
    fn read(certificate: &Path, private_key: &Path) -> Result<Self> {
        let certificate = read_bounded(certificate)?;
        let private_key = read_bounded(private_key)?;
        Self::new(certificate, private_key)
    }

    fn new(certificate: Vec<u8>, private_key: Vec<u8>) -> Result<Self> {
        if certificate.is_empty() || private_key.is_empty() {
            bail!("QUIC certificate or private key is empty");
        }
        let digest = Sha256::digest(&certificate);
        let mut fingerprint = String::with_capacity(64);
        for byte in digest {
            use std::fmt::Write;
            write!(&mut fingerprint, "{byte:02x}").unwrap();
        }
        Ok(Self {
            certificate,
            private_key,
            fingerprint,
        })
    }
}

fn make_server_config(identity: &PersistentIdentity) -> Result<quinn::ServerConfig> {
    let certificate = CertificateDer::from(identity.certificate.clone());
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(identity.private_key.clone()));
    let mut tls = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![certificate], key)
        .context("load persistent QUIC certificate and private key")?;
    tls.alpn_protocols = vec![ALPN.to_vec()];
    tls.max_early_data_size = 0;
    let crypto = QuicServerConfig::try_from(tls).context("configure QUIC TLS")?;
    let mut server = quinn::ServerConfig::with_crypto(Arc::new(crypto));
    server.transport_config(make_transport_config(false)?);
    Ok(server)
}

fn make_transport_config(background: bool) -> Result<Arc<quinn::TransportConfig>> {
    let mut transport = quinn::TransportConfig::default();
    // One permanent control stream plus eight persistent client-opened section lanes.
    transport.max_concurrent_bidi_streams(VarInt::from_u32(9));
    transport.max_concurrent_uni_streams(VarInt::from_u32(0));
    transport.stream_receive_window(VarInt::from_u32(32 * 1024));
    transport.receive_window(VarInt::from_u32(1024 * 1024));
    transport.send_window(512 * 1024);
    transport.datagram_receive_buffer_size(None);
    transport.datagram_send_buffer_size(0);
    transport.max_idle_timeout(Some(IdleTimeout::try_from(IDLE_TIMEOUT)?));
    transport.keep_alive_interval(Some(KEEPALIVE_INTERVAL));
    if background {
        // Reserve the framing bytes within Quinn's existing IPv6-safe 1452-byte ceiling.
        let mut discovery = quinn::MtuDiscoveryConfig::default();
        discovery.upper_bound(1452 - ENVELOPE_BYTES as u16);
        transport.mtu_discovery_config(Some(discovery));
    }
    Ok(Arc::new(transport))
}

fn read_bounded(path: &Path) -> Result<Vec<u8>> {
    let metadata = fs::metadata(path)?;
    if metadata.len() == 0 || metadata.len() > MAX_IDENTITY_BYTES as u64 {
        bail!(
            "QUIC identity file {} is empty or oversized",
            path.display()
        );
    }
    let bytes = fs::read(path)?;
    if bytes.len() > MAX_IDENTITY_BYTES {
        bail!("QUIC identity file {} grew while reading", path.display());
    }
    Ok(bytes)
}

fn temporary_path(path: &Path) -> PathBuf {
    path.with_extension("tmp")
}

fn replace_private_key(path: &Path, temporary: &Path, bytes: &[u8]) -> Result<()> {
    let mut file = fs::OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .mode(0o600)
        .open(temporary)?;
    fs::set_permissions(temporary, fs::Permissions::from_mode(0o600))?;
    file.write_all(bytes)?;
    file.sync_all()?;
    drop(file);
    fs::rename(temporary, path)?;
    sync_parent(path)
}
