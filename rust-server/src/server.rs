//! One authenticated Minecraft-session route, paced before TLS, across all dimensions.
use crate::diagnostics::{self, Span, Stage};
use crate::{
    anvil::AnvilWorld,
    crc::crc32c,
    pacer::{ENVELOPE_BYTES, PacedSocket, RateLedger, UdpMux},
    quarantine,
    regional::{
        CatalogDefinition, PreparedSection, RegionalAnnouncement, RegionalResponder,
        RegionalRuntime, RegionalService,
        wire::{
            self, ALPN, ContentBinding, ControlMessage, Desire, DimensionAnchor, InventoryRecord,
            RecordStatus, STREAM_CONTROL, STREAM_DISCOVERY, STREAM_SECTION_LANE, ScopedDesire,
            ScopedKey, StreamingSettings, encode_control_record, read_control, read_lane,
            read_stream_role,
        },
    },
    replace_synced, sync_parent,
};
use anyhow::{Context, Result, bail};
use quinn::{Endpoint, IdleTimeout, Runtime, VarInt, crypto::rustls::QuicServerConfig};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, BTreeSet, HashMap, HashSet, VecDeque},
    fs,
    future::Future,
    io::Write,
    net::SocketAddr,
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
    pin::Pin,
    sync::{
        Arc, Mutex, OnceLock, Weak,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};
use tokio::{sync::Notify, time::Instant};

const IDLE_TIMEOUT: Duration = Duration::from_secs(60);
const KEEPALIVE_INTERVAL: Duration = Duration::from_secs(15);
const MAX_IDENTITY_BYTES: usize = 64 * 1024;
const CERTIFICATE_FILE: &str = "certificate.der";
const PRIVATE_KEY_FILE: &str = "private-key.der";
type Region = (u32, i32, i32);
type Subscribers = HashMap<Region, HashMap<usize, Weak<Session>>>;

#[derive(Debug)]
struct Network {
    mux: Arc<UdpMux>,
    config: quinn::ServerConfig,
}
#[derive(Debug)]
struct RouteOwner {
    token: [u8; 32],
    ledger: Arc<RateLedger>,
    endpoint: Endpoint,
    connection: Mutex<Option<quinn::Connection>>,
    closed: AtomicBool,
}
impl RouteOwner {
    fn close(&self) {
        self.closed.store(true, Ordering::Release);
        self.endpoint
            .close(VarInt::from_u32(0), b"Minecraft session ended");
    }
}
fn validate_bandwidth(kbps: u64, debug_uncapped: bool) -> Result<()> {
    if !(100..=20_000).contains(&kbps) && !(kbps == 0 && debug_uncapped) {
        bail!("invalid authenticated route policy")
    }
    Ok(())
}

#[cfg(test)]
mod bandwidth_tests {
    use super::validate_bandwidth;

    #[test]
    fn uncapped_policy_requires_debug_server_capability() {
        assert!(validate_bandwidth(0, false).is_err());
        assert!(validate_bandwidth(0, true).is_ok());
        for debug in [false, true] {
            for rate in [100, 5_000, 20_000] {
                assert!(validate_bandwidth(rate, debug).is_ok());
            }
            for rate in [1, 99, 20_001, u64::MAX] {
                assert!(validate_bandwidth(rate, debug).is_err());
            }
        }
    }
}

#[derive(Debug)]
pub struct ServerState {
    server_instance: u64,
    regional: Arc<RegionalService>,
    subscribers: Mutex<Subscribers>,
    sessions: Mutex<HashMap<usize, Weak<Session>>>,
    network: OnceLock<Network>,
    routes: Mutex<HashMap<[u8; 32], Arc<RouteOwner>>>,
    bridge_stopped: Notify,
    trace: bool,
    uncapped_bandwidth: bool,
    #[cfg(feature = "debug-diagnostics")]
    debug: DebugOwner,
}
#[cfg(feature = "debug-diagnostics")]
#[derive(Debug)]
struct DebugOwner {
    epoch: u64,
    native_identity: String,
    run: Mutex<String>,
    observed: Mutex<HashMap<String, BTreeSet<Region>>>,
}
impl ServerState {
    pub fn new(
        dimensions: &BTreeMap<String, Arc<AnvilWorld>>,
        catalog_id: u64,
        regional: Arc<RegionalService>,
    ) -> Self {
        let server_instance = dimensions
            .iter()
            .fold(catalog_id, |id, (name, world)| {
                id.rotate_left(11)
                    ^ u64::from(crc32c(name.as_bytes()))
                    ^ u64::from(crc32c(world.root.as_os_str().as_encoded_bytes()))
            })
            .max(1);
        Self {
            server_instance,
            regional,
            subscribers: Mutex::new(HashMap::new()),
            sessions: Mutex::new(HashMap::new()),
            network: OnceLock::new(),
            routes: Mutex::new(HashMap::new()),
            bridge_stopped: Notify::new(),
            trace: std::env::var("VOXY_NETWORK_TRACE").as_deref() == Ok("1"),
            uncapped_bandwidth: std::env::var("VOXY_DEBUG_UNCAPPED_BANDWIDTH").as_deref()
                == Ok("1"),
            #[cfg(feature = "debug-diagnostics")]
            debug: DebugOwner {
                epoch: (std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_nanos() as u64)
                    .min(i64::MAX as u64),
                native_identity: fs::read("/proc/self/exe")
                    .ok()
                    .map(|bytes| hex(&Sha256::digest(bytes)))
                    .unwrap_or_default(),
                run: Mutex::new(String::new()),
                observed: Mutex::new(HashMap::new()),
            },
        }
    }
    fn tracing(&self) -> bool {
        self.trace && !diagnostics::quiet()
    }
    fn register_route(self: &Arc<Self>, token: [u8; 32], kbps: u64) -> Result<()> {
        if token == [0; 32] {
            bail!("invalid authenticated route policy")
        }
        validate_bandwidth(kbps, self.uncapped_bandwidth)?;
        let mut routes = self.routes.lock().expect("Minecraft route owner poisoned");
        if let Some(route) = routes.get(&token) {
            route.ledger.update(kbps);
            return Ok(());
        }
        let network = self.network.get().context("UDP listener not ready")?;
        let ledger = RateLedger::new(kbps);
        let socket = PacedSocket::new(network.mux.route(&token)?, ledger.clone());
        let endpoint = Endpoint::new_with_abstract_socket(
            Default::default(),
            Some(network.config.clone()),
            socket,
            Arc::new(quinn::TokioRuntime),
        )?;
        let route = Arc::new(RouteOwner {
            token,
            ledger,
            endpoint,
            connection: Mutex::new(None),
            closed: AtomicBool::new(false),
        });
        routes.insert(token, route.clone());
        let state = self.clone();
        tokio::spawn(async move {
            while let Some(incoming) = route.endpoint.accept().await {
                let state = state.clone();
                let route = route.clone();
                tokio::spawn(async move {
                    if let Err(error) = serve_connection(state, route, incoming).await {
                        eprintln!("Voxy QUIC session ended: {error:#}");
                    }
                });
            }
            route.endpoint.wait_idle().await;
            if state.tracing() {
                let (bytes, packets) = route.ledger.counters();
                eprintln!(
                    "VOXY_ROUTE_STATS route={} ip_bytes={bytes} datagrams={packets}",
                    hex(&route.token[..16])
                );
            }
        });
        Ok(())
    }
    fn revoke_route(&self, token: [u8; 32]) {
        if let Some(route) = self
            .routes
            .lock()
            .expect("Minecraft route owner poisoned")
            .remove(&token)
        {
            route.close();
        }
    }
    #[cfg(feature = "debug-diagnostics")]
    fn debug_status(&self, run: &str, tokens: &[[u8; 32]]) -> serde_json::Value {
        let selected = tokens.iter().copied().collect::<HashSet<_>>();
        let routes = self
            .routes
            .lock()
            .expect("route owner poisoned")
            .keys()
            .filter(|t| selected.contains(*t))
            .count();
        let routed_sockets = self
            .network
            .get()
            .map_or(0, |network| network.mux.debug_live_routes(tokens));
        let sessions = self
            .sessions
            .lock()
            .expect("session owner poisoned")
            .values()
            .filter_map(Weak::upgrade)
            .filter(|s| selected.contains(&s.route.token))
            .collect::<Vec<_>>();
        let mut connections = Vec::with_capacity(sessions.len());
        for session in &sessions {
            let wants = session.wants.lock().expect("desire owner poisoned");
            let queued = wants
                .interests
                .values()
                .filter(|i| i.queued.is_some())
                .count();
            let waiting = wants
                .interests
                .values()
                .filter(|i| i.waiting_source)
                .count();
            let active = wants
                .interests
                .values()
                .filter(|i| i.active.is_some())
                .count();
            let oldest_queue = wants
                .interests
                .values()
                .filter_map(|i| i.debug_enqueued)
                .map(|i| i.elapsed().as_nanos() as u64)
                .max()
                .unwrap_or(0);
            let oldest_source = wants
                .interests
                .values()
                .filter_map(|i| i.debug_source)
                .map(|i| i.elapsed().as_nanos() as u64)
                .max()
                .unwrap_or(0);
            drop(wants);
            let (ip_bytes, datagrams) = session.route.ledger.counters();
            let mut record = serde_json::json!({"fingerprint":hex(&Sha256::digest(session.route.token)[..12]),"id":session.id,
                "queued":queued,"waiting_source":waiting,"active_requests":active,"queue_oldest_ns":oldest_queue,"source_oldest_ns":oldest_source,
                "route_ip_bytes":ip_bytes,"route_datagrams":datagrams,"pacer_blocked_attempts":session.route.ledger.debug_blocked(),"payload_bytes":session.debug_bytes.load(Ordering::Relaxed),
                "closed":session.closed.load(Ordering::Acquire),"records":session.debug_records.iter().map(|v|v.load(Ordering::Relaxed)).collect::<Vec<_>>(),
                "lanes":session.debug_phase.iter().enumerate().map(|(lane,v)|{
                    let start=v[1].load(Ordering::Relaxed);serde_json::json!({"lane":lane,"phase":v[0].load(Ordering::Relaxed),"age_ns":if start==0{0}else{diagnostics::now_ns().saturating_sub(start)},
                        "ticket":v[2].load(Ordering::Relaxed),"key":v[3].load(Ordering::Relaxed),"dimension":v[4].load(Ordering::Relaxed),"generation":v[5].load(Ordering::Relaxed),"revision":v[6].load(Ordering::Relaxed)})
                }).collect::<Vec<_>>()});
            if let Some(connection) = session
                .route
                .connection
                .lock()
                .expect("connection owner poisoned")
                .as_ref()
                && connection.stable_id() == session.id
            {
                let stats = connection.stats();
                record["quinn"] = serde_json::json!({"rtt_ns":stats.path.rtt.as_nanos() as u64,"cwnd":stats.path.cwnd,
                    "lost_packets":stats.path.lost_packets,"lost_bytes":stats.path.lost_bytes,"sent_packets":stats.path.sent_packets,
                    "congestion_events":stats.path.congestion_events,"udp_tx_bytes":stats.udp_tx.bytes,"udp_tx_datagrams":stats.udp_tx.datagrams,
                    "udp_rx_bytes":stats.udp_rx.bytes,"udp_rx_datagrams":stats.udp_rx.datagrams});
            }
            connections.push(record);
        }
        // Subscriber ownership remains visible during session cleanup, after the session map
        // entry has been removed but before runtime subscriptions have been released.
        let mut run_counts = HashMap::<Region, usize>::new();
        let mut subscriptions = 0;
        for (&region, owners) in self
            .subscribers
            .lock()
            .expect("subscriber owner poisoned")
            .iter()
        {
            for session in owners.values().filter_map(Weak::upgrade) {
                if selected.contains(&session.route.token) {
                    subscriptions += 1;
                    *run_counts.entry(region).or_default() += 1;
                }
            }
        }
        let coordinates = {
            let mut observed = self
                .debug
                .observed
                .lock()
                .expect("debug observations poisoned");
            let all = observed.entry(run.to_owned()).or_default();
            all.extend(run_counts.keys().copied());
            all.clone()
        };
        let (mut queued, mut inflight, mut outstanding, mut shared, mut exclusive, mut unknown) =
            (0, 0, 0, 0, 0, 0);
        for coordinate in &coordinates {
            let observation = self
                .regional
                .dimension_name(coordinate.0)
                .and_then(|name| self.regional.runtime(&name))
                .map(|runtime| {
                    runtime.debug_work(
                        coordinate.1,
                        coordinate.2,
                        run_counts.get(coordinate).copied().unwrap_or(0),
                    )
                });
            match observation {
                Ok((q, i, s, u)) => {
                    queued += usize::from(q);
                    inflight += usize::from(i);
                    unknown += usize::from(u);
                    if q || i {
                        outstanding += 1;
                        if s { shared += 1 } else { exclusive += 1 }
                    }
                }
                Err(_) => unknown += 1,
            }
        }
        serde_json::json!({"routes":routes,"routed_sockets":routed_sockets,"sessions":sessions.len(),"subscriptions":subscriptions,
            "queued_source_regions":queued,"inflight_source_regions":inflight,"outstanding_source_regions":outstanding,
            "shared_source_regions":shared,"exclusive_source_regions":exclusive,"unknown_source_regions":unknown,
            "cleanup_pending":routes!=0||routed_sockets!=0||!sessions.is_empty()||subscriptions!=0||exclusive!=0||unknown!=0,
            "observed_source_regions":coordinates.len(),"connections":connections,"timings_enabled":diagnostics::enabled()})
    }
    #[cfg(feature = "debug-diagnostics")]
    fn start_diagnostics(self: &Arc<Self>) {
        eprintln!("VOXY_DEBUG_EPOCH {}", self.debug.epoch);
        let state = Arc::downgrade(self);
        tokio::spawn(async move {
            let mut tick = tokio::time::interval(Duration::from_secs(1));
            tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                tick.tick().await;
                let Some(state) = state.upgrade() else { return };
                let run = state.debug.run.lock().expect("debug run poisoned").clone();
                if run.is_empty() {
                    continue;
                }
                // This allocation/logging is confined to the once-per-second reporting task.
                let mut snapshot = diagnostics::snapshot();
                snapshot["epoch"] = serde_json::json!(state.debug.epoch);
                snapshot["run_id"] = serde_json::json!(run);
                snapshot["pid"] = serde_json::json!(std::process::id());
                snapshot["native_identity"] = serde_json::json!(state.debug.native_identity);
                eprintln!("VOXY_DEBUG_TIMINGS {snapshot}");
            }
        });
    }
    fn register(&self, session: &Arc<Session>, coordinate: Region) {
        self.subscribers
            .lock()
            .expect("terrain subscriber owner poisoned")
            .entry(coordinate)
            .or_default()
            .insert(session.id, Arc::downgrade(session));
    }
    fn release(&self, session: &Session, coordinate: Region) {
        let mut subscribers = self
            .subscribers
            .lock()
            .expect("terrain subscriber owner poisoned");
        if let Some(region) = subscribers.get_mut(&coordinate) {
            region.remove(&session.id);
            if region.is_empty() {
                subscribers.remove(&coordinate);
            }
        }
    }
    fn publication_loop(self: &Arc<Self>) {
        let state = self.clone();
        let mut announcements = self.regional.subscribe();
        tokio::spawn(async move {
            loop {
                match announcements.recv().await {
                    Ok(RegionalAnnouncement::RuntimeReplaced {
                        dimension,
                        previous,
                    }) => {
                        let sessions = state
                            .sessions
                            .lock()
                            .expect("terrain session owner poisoned")
                            .values()
                            .filter_map(Weak::upgrade)
                            .collect::<Vec<_>>();
                        for session in sessions {
                            let affected = session
                                .scopes
                                .lock()
                                .expect("dimension scope owner poisoned")
                                .get(&dimension)
                                .is_some_and(|scope| scope.responder.uses_runtime(&previous));
                            let affected = affected
                                || session
                                    .discovered
                                    .lock()
                                    .expect("discovery scope owner poisoned")
                                    .get(&dimension)
                                    .and_then(Weak::upgrade)
                                    .is_some_and(|runtime| Arc::ptr_eq(&runtime, &previous));
                            if affected {
                                session.close();
                                if let Some(connection) = session
                                    .route
                                    .connection
                                    .lock()
                                    .expect("route connection owner poisoned")
                                    .as_ref()
                                    && connection.stable_id() == session.id
                                {
                                    connection
                                        .close(VarInt::from_u32(0), b"dimension source replaced");
                                }
                            }
                        }
                    }
                    Ok(RegionalAnnouncement::Ready {
                        dimension,
                        region_x,
                        region_z,
                    }) => {
                        if let Ok(id) = state.regional.dimension_id(&dimension) {
                            let recipients = state
                                .subscribers
                                .lock()
                                .expect("terrain subscriber owner poisoned")
                                .get(&(id, region_x, region_z))
                                .map(|region| {
                                    region
                                        .values()
                                        .filter_map(Weak::upgrade)
                                        .collect::<Vec<_>>()
                                })
                                .unwrap_or_default();
                            for session in recipients {
                                session.ready((id, region_x, region_z));
                            }
                        }
                    }
                    Ok(RegionalAnnouncement::Changed {
                        dimension,
                        region_x,
                        region_z,
                        changed_ordinals,
                        ..
                    }) => {
                        let Ok(id) = state.regional.dimension_id(&dimension) else {
                            continue;
                        };
                        let recipients = state
                            .subscribers
                            .lock()
                            .expect("terrain subscriber owner poisoned")
                            .get(&(id, region_x, region_z))
                            .map(|region| {
                                region
                                    .values()
                                    .filter_map(Weak::upgrade)
                                    .collect::<Vec<_>>()
                            })
                            .unwrap_or_default();
                        for session in recipients {
                            session.changed((id, region_x, region_z), changed_ordinals.as_deref());
                        }
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {
                        let recipients = state
                            .sessions
                            .lock()
                            .expect("terrain session owner poisoned")
                            .values()
                            .filter_map(Weak::upgrade)
                            .collect::<Vec<_>>();
                        for session in recipients {
                            session.reconcile();
                        }
                    }
                    Ok(RegionalAnnouncement::Shutdown(_))
                    | Err(tokio::sync::broadcast::error::RecvError::Closed) => return,
                    _ => {}
                }
            }
        });
    }
    /// Exactly one stdin reader consumes the Java-owned pipe. All writes are completed saves or
    /// authenticated registrations; EOF exits without keeping Tokio's runtime alive.
    fn start_bridge(self: &Arc<Self>) -> Result<()> {
        let state = self.clone();
        let runtime = tokio::runtime::Handle::current();
        std::thread::Builder::new()
            .name("Voxy server bridge".into())
            .spawn(move || {
                use std::io::Read;
                let stdin = std::io::stdin();
                let mut input = std::io::BufReader::new(stdin.lock());
                let result: Result<()> = (|| {
                    loop {
                        let mut opcode = [0; 1];
                        match input.read_exact(&mut opcode) {
                            Ok(()) => {}
                            Err(error) if error.kind() == std::io::ErrorKind::UnexpectedEof => {
                                return Ok(());
                            }
                            Err(error) => return Err(error.into()),
                        }
                        match opcode[0] {
                            1 => {
                                let name = read_string(&mut input)?;
                                let count = read_u32(&mut input)?;
                                for _ in 0..count {
                                    let x = read_u32(&mut input)? as i32;
                                    let z = read_u32(&mut input)? as i32;
                                    state.regional.saved_chunks(&name, &[(x, z)])?;
                                }
                                state.regional.wake();
                            }
                            2 => {
                                let mut token = [0; 32];
                                input.read_exact(&mut token)?;
                                let rate = read_u64(&mut input)?;
                                let _entered = runtime.enter();
                                state.register_route(token, rate)?;
                                eprintln!("VOXY_ROUTE_READY {} {}", hex(&token), rate);
                            }
                            3 => {
                                let mut token = [0; 32];
                                input.read_exact(&mut token)?;
                                state.revoke_route(token);
                            }
                            4 => {
                                let name = read_string(&mut input)?;
                                let root = PathBuf::from(read_string(&mut input)?);
                                let min_y = read_u32(&mut input)? as i32;
                                let count = read_u32(&mut input)?;
                                let mut flag = [0; 1];
                                input.read_exact(&mut flag)?;
                                let x = read_f64(&mut input)?;
                                let z = read_f64(&mut input)?;
                                let size = read_f64(&mut input)?;
                                state.regional.define_dimension(
                                    name,
                                    root,
                                    min_y,
                                    count,
                                    flag[0] != 0,
                                    x,
                                    z,
                                    size,
                                )?;
                            }
                            5 => state.regional.exclude_dimension(
                                read_string(&mut input)?,
                                read_string(&mut input)?,
                            )?,
                            #[cfg(feature = "debug-diagnostics")]
                            opcode @ (6 | 7 | 8) => {
                                let request = read_string(&mut input)?;
                                let run = read_string(&mut input)?;
                                let epoch = read_u64(&mut input)?;
                                let valid = epoch == state.debug.epoch;
                                let action = match opcode {
                                    6 => "status",
                                    7 => "revoke",
                                    _ => "timings",
                                };
                                let mut response = if opcode == 8 {
                                    let mut value = [0];
                                    input.read_exact(&mut value)?;
                                    if value[0] > 2 {bail!("invalid debug timing mode")}
                                    if valid {
                                        diagnostics::set_enabled(value[0] == 1);
                                        diagnostics::set_quiet(value[0] != 2);
                                        *state.debug.run.lock().expect("debug run poisoned") =
                                            if value[0]==2 {String::new()}else{run.clone()};
                                        if value[0] == 2 {
                                            state.debug.observed.lock().expect("debug observations poisoned").remove(&run);
                                        }
                                    }
                                    serde_json::json!({"timings_enabled":diagnostics::enabled(),"reporting":!state.debug.run.lock().expect("debug run poisoned").is_empty(),"affects_new_spans":true})
                                } else {
                                    let count = read_u32(&mut input)?;
                                    if count > 100 {
                                        bail!("debug route count exceeds the requested experiment")
                                    }
                                    let mut tokens = Vec::with_capacity(count as usize);
                                    for _ in 0..count {
                                        let mut token = [0; 32];
                                        input.read_exact(&mut token)?;
                                        tokens.push(token);
                                    }
                                    // Capture work ownership before closing routes; publication can outlive subscriptions.
                                    if valid && opcode == 7 {
                                        let _ = state.debug_status(&run, &tokens);
                                        for token in &tokens {
                                            state.revoke_route(*token);
                                        }
                                    }
                                    if valid {
                                        state.debug_status(&run, &tokens)
                                    } else {
                                        serde_json::json!({})
                                    }
                                };
                                response["request_id"] = serde_json::json!(request);
                                response["run_id"] = serde_json::json!(run);
                                response["epoch"] = serde_json::json!(state.debug.epoch);
                                response["native_pid"] = serde_json::json!(std::process::id());
                                response["native_identity"] =
                                    serde_json::json!(state.debug.native_identity);
                                response["action"] = serde_json::json!(action);
                                response["ok"] = serde_json::json!(valid);
                                if !valid {
                                    response["error"] = serde_json::json!("native epoch mismatch");
                                }
                                eprintln!("VOXY_DEBUG_RESPONSE {response}");
                            }
                            _ => bail!("unknown owned IPC opcode"),
                        }
                    }
                })();
                if let Err(error) = result {
                    eprintln!("Voxy owned bridge stopped: {error:#}");
                }
                // Pipe EOF ends Java's ownership, even when no read error occurred.
                state.bridge_stopped.notify_one();
            })?;
        Ok(())
    }
}
fn read_u32(input: &mut impl std::io::Read) -> Result<u32> {
    let mut bytes = [0; 4];
    input.read_exact(&mut bytes)?;
    Ok(u32::from_le_bytes(bytes))
}
fn read_u64(input: &mut impl std::io::Read) -> Result<u64> {
    let mut bytes = [0; 8];
    input.read_exact(&mut bytes)?;
    Ok(u64::from_le_bytes(bytes))
}
fn read_f64(input: &mut impl std::io::Read) -> Result<f64> {
    Ok(f64::from_bits(read_u64(input)?))
}
fn read_string(input: &mut impl std::io::Read) -> Result<String> {
    let mut bytes = [0; 2];
    input.read_exact(&mut bytes)?;
    let size = u16::from_le_bytes(bytes) as usize;
    if size == 0 {
        bail!("empty IPC string")
    };
    let mut bytes = vec![0; size];
    input.read_exact(&mut bytes)?;
    Ok(String::from_utf8(bytes)?)
}
fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write;
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        write!(&mut out, "{byte:02x}").unwrap();
    }
    out
}

#[derive(Clone, Debug)]
struct Scope {
    responder: RegionalResponder,
    world: [u8; 32],
}
#[derive(Debug)]
struct Interest {
    desire: Desire,
    known: Option<ContentBinding>,
    active: Option<(u64, u64)>,
    revision: u64,
    dirty: bool,
    waiting_source: bool,
    queued: Option<QueueEntry>,
    #[cfg(feature = "debug-diagnostics")]
    debug_enqueued: Option<Instant>,
    #[cfg(feature = "debug-diagnostics")]
    debug_source: Option<Instant>,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd)]
struct QueueEntry {
    class: u8,
    rank: u128,
    coarse: u8,
    dimension: u32,
    key: u64,
    revision: u64,
}
#[derive(Debug)]
struct Wants {
    interests: HashMap<ScopedKey, Interest>,
    regions: HashMap<Region, HashMap<u32, ScopedKey>>,
    visible: [BTreeSet<QueueEntry>; 2],
    prefetch: BTreeMap<u32, BTreeSet<QueueEntry>>,
    refresh: BTreeSet<QueueEntry>,
    changed: HashSet<ScopedKey>,
    last_refresh: Option<Instant>,
    refresh_started: bool,
    refresh_inflight: usize,
}
impl Default for Wants {
    fn default() -> Self {
        Self {
            interests: HashMap::new(),
            regions: HashMap::new(),
            visible: Default::default(),
            prefetch: BTreeMap::new(),
            refresh: BTreeSet::new(),
            changed: HashSet::new(),
            last_refresh: None,
            refresh_started: false,
            refresh_inflight: 0,
        }
    }
}
impl Wants {
    fn remove_queue(&mut self, key: ScopedKey) {
        let Some(entry) = self
            .interests
            .get_mut(&key)
            .and_then(|interest| interest.queued.take())
        else {
            return;
        };
        match entry.class {
            0 => {
                self.visible[0].remove(&entry);
            }
            1 | 2 => {
                self.visible[1].remove(&entry);
            }
            3 => {
                if let Some(queue) = self.prefetch.get_mut(&key.dimension) {
                    queue.remove(&entry);
                    if queue.is_empty() {
                        self.prefetch.remove(&key.dimension);
                    }
                }
            }
            4 => {
                self.refresh.remove(&entry);
            }
            _ => unreachable!(),
        }
    }
    fn enqueue(&mut self, key: ScopedKey) {
        self.remove_queue(key);
        let Some(interest) = self.interests.get(&key) else {
            return;
        };
        if interest.active.is_some() {
            return;
        }
        if (interest.known.is_some() && interest.dirty) || interest.desire.purpose == 2 {
            if interest.dirty {
                self.changed.insert(key);
            }
            return;
        }
        let class = match interest.desire.purpose {
            0 => 0,
            1 => 1,
            3 => 2,
            4 => 3,
            _ => return,
        };
        let entry = QueueEntry {
            class,
            rank: interest.desire.rank as u128,
            coarse: 4 - ((key.key >> 60) as u8),
            dimension: key.dimension,
            key: key.key,
            revision: interest.revision,
        };
        match class {
            0 => {
                self.visible[0].insert(entry);
            }
            1 | 2 => {
                self.visible[1].insert(entry);
            }
            3 => {
                self.prefetch
                    .entry(key.dimension)
                    .or_default()
                    .insert(entry);
            }
            _ => unreachable!(),
        }
        let interest = self.interests.get_mut(&key).unwrap();
        interest.queued = Some(entry);
        #[cfg(feature = "debug-diagnostics")]
        {
            if diagnostics::enabled() {
                interest.debug_enqueued.get_or_insert_with(Instant::now);
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
    state: Arc<ServerState>,
    route: Arc<RouteOwner>,
    scopes: Mutex<HashMap<u32, Scope>>,
    discovered: Mutex<HashMap<u32, Weak<RegionalRuntime>>>,
    wants: Mutex<Wants>,
    available: Notify,
    stopped: Notify,
    closed: AtomicBool,
    settings: Mutex<StreamingSettings>,
    active_dimension: Mutex<u32>,
    anchors: Mutex<HashMap<u32, (i32, i32)>>,
    metadata: tokio::sync::Mutex<PrimaryWriter>,
    catalogues: Mutex<HashSet<[u8; 32]>>,
    #[cfg(feature = "debug-diagnostics")]
    debug_bytes: std::sync::atomic::AtomicU64,
    #[cfg(feature = "debug-diagnostics")]
    debug_records: [std::sync::atomic::AtomicU64; 6],
    #[cfg(feature = "debug-diagnostics")]
    debug_phase: [[std::sync::atomic::AtomicU64; 7]; 2],
}
#[derive(Clone, Copy)]
struct Claim {
    scope: ScopedKey,
    desire: Desire,
    revision: u64,
    known: Option<ContentBinding>,
    refresh: bool,
}
enum ClaimOutcome {
    Sent(ContentBinding),
    NotReady,
    Cancelled,
}
impl Session {
    #[cfg(feature = "debug-diagnostics")]
    fn phase(&self, lane: usize, phase: u64) {
        let fields = &self.debug_phase[lane];
        fields[0].store(phase, Ordering::Relaxed);
        fields[1].store(
            if phase == 0 || !diagnostics::enabled() {
                0
            } else {
                diagnostics::now_ns()
            },
            Ordering::Relaxed,
        );
    }
    fn wake(&self) {
        self.available.notify_waiters();
    }
    fn close(&self) {
        self.closed.store(true, Ordering::Release);
        self.stopped.notify_waiters();
        self.wake();
    }
    async fn ended(&self) {
        loop {
            let wait = self.stopped.notified();
            if self.closed.load(Ordering::Acquire) {
                return;
            }
            wait.await;
        }
    }
    fn scope(&self, id: u32) -> Result<Scope> {
        let mut scopes = self.scopes.lock().expect("dimension scope owner poisoned");
        if let Some(scope) = scopes.get(&id) {
            return Ok(scope.clone());
        }
        let name = self.state.regional.dimension_name(id)?;
        let responder = self
            .state
            .regional
            .responder(&name, self.state.server_instance)?;
        let scope = Scope {
            world: responder.world_identity(),
            responder,
        };
        scopes.insert(id, scope.clone());
        Ok(scope)
    }
    fn coordinate(&self, scoped: ScopedKey) -> Result<(Region, u32)> {
        let key = crate::key::SectionKey::unpack(scoped.key)?;
        let side = 16i32 >> key.level;
        let region = (
            scoped.dimension,
            key.x.div_euclid(side),
            key.z.div_euclid(side),
        );
        let ordinal = self
            .scope(scoped.dimension)?
            .responder
            .layout()
            .index(region.1, region.2, key.into())
            .map(|n| n as u32)
            .unwrap_or(u32::MAX);
        Ok((region, ordinal))
    }
    fn apply(self: &Arc<Self>, desires: Vec<ScopedDesire>) -> Result<()> {
        crate::diagnostics::sync_result(crate::diagnostics::Stage::Apply, || {
            let settings = *self
                .settings
                .lock()
                .expect("streaming policy owner poisoned");
            let mut additions = Vec::new();
            let mut wants = self.wants.lock().expect("terrain desire owner poisoned");
            for mut scoped in desires {
                let scope = self.scope(scoped.dimension)?;
                if scoped.expected_world != [0; 32] && scoped.expected_world != scope.world {
                    bail!("request world identity changed")
                }
                let key = ScopedKey {
                    dimension: scoped.dimension,
                    key: scoped.desire.key,
                };
                if let Some(anchor) = self
                    .anchors
                    .lock()
                    .expect("spatial anchor owner poisoned")
                    .get(&scoped.dimension)
                    .copied()
                {
                    scoped.desire.rank = geometric_rank(key.key, anchor)?;
                }
                let (region, ordinal) = self.coordinate(key)?;
                if !wants.regions.contains_key(&region) {
                    additions.push(region);
                }
                wants
                    .regions
                    .entry(region)
                    .or_default()
                    .insert(ordinal, key);
                wants.remove_queue(key);
                let old = wants.interests.remove(&key);
                let revision = old.as_ref().map_or(0, |old| old.revision.wrapping_add(1));
                let known = scoped.desire.have;
                wants.interests.insert(
                    key,
                    Interest {
                        desire: scoped.desire,
                        known,
                        active: old.as_ref().and_then(|old| old.active),
                        revision,
                        dirty: old.as_ref().is_some_and(|old| old.dirty)
                            || (scoped.desire.purpose == 2 && known.is_some()),
                        waiting_source: false,
                        queued: None,
                        #[cfg(feature = "debug-diagnostics")]
                        debug_enqueued: old.as_ref().and_then(|old| old.debug_enqueued),
                        #[cfg(feature = "debug-diagnostics")]
                        debug_source: old.as_ref().and_then(|old| old.debug_source),
                    },
                );
                wants.enqueue(key);
            }
            drop(wants);
            for region in additions {
                self.scope(region.0)?
                    .responder
                    .subscribe_region(region.1, region.2)?;
                self.state.register(self, region);
            }
            self.state
                .regional
                .set_cadence(self.id, Some(settings.interval_millis))?;
            self.wake();
            Ok(())
        })
    }
    fn drop_keys(&self, keys: Vec<ScopedKey>) -> Result<()> {
        let mut releases = Vec::new();
        let mut wants = self.wants.lock().expect("terrain desire owner poisoned");
        for key in keys {
            wants.remove_queue(key);
            wants.changed.remove(&key);
            if wants.interests.remove(&key).is_none() {
                continue;
            }
            let (region, ordinal) = self.coordinate(key)?;
            if let Some(entries) = wants.regions.get_mut(&region) {
                entries.remove(&ordinal);
                if entries.is_empty() {
                    wants.regions.remove(&region);
                    releases.push(region);
                }
            }
        }
        let empty = wants.interests.is_empty();
        drop(wants);
        for region in releases {
            self.state.release(self, region);
            self.scope(region.0)?
                .responder
                .unsubscribe_region(region.1, region.2)?;
        }
        if empty {
            self.state.regional.set_cadence(self.id, None)?;
        }
        self.wake();
        Ok(())
    }
    fn changed(&self, region: Region, ordinals: Option<&[u32]>) {
        let mut wants = self.wants.lock().expect("terrain desire owner poisoned");
        let Some(entries) = wants.regions.get(&region) else {
            return;
        };
        let keys = match ordinals {
            Some(ordinals) => ordinals
                .iter()
                .filter_map(|ordinal| entries.get(ordinal).copied())
                .collect::<Vec<_>>(),
            None => entries.values().copied().collect(),
        };
        for key in keys {
            if let Some(interest) = wants.interests.get_mut(&key) {
                interest.revision = interest.revision.wrapping_add(1);
                interest.dirty = true;
                wants.enqueue(key);
            }
        }
        drop(wants);
        self.wake();
    }
    fn retire_replaced_scopes(&self) -> bool {
        let scopes = self
            .scopes
            .lock()
            .expect("dimension scope owner poisoned")
            .iter()
            .map(|(&id, scope)| (id, scope.clone()))
            .collect::<Vec<_>>();
        for (id, scope) in scopes {
            let current = self
                .state
                .regional
                .dimension_name(id)
                .and_then(|name| self.state.regional.runtime(&name));
            if current.is_err()
                || current.is_ok_and(|runtime| !scope.responder.uses_runtime(&runtime))
            {
                self.close();
                if let Some(connection) = self
                    .route
                    .connection
                    .lock()
                    .expect("route connection owner poisoned")
                    .as_ref()
                    && connection.stable_id() == self.id
                {
                    connection.close(VarInt::from_u32(0), b"dimension source replaced");
                }
                return true;
            }
        }
        let discovered = self
            .discovered
            .lock()
            .expect("discovery scope owner poisoned")
            .iter()
            .map(|(&id, runtime)| (id, runtime.clone()))
            .collect::<Vec<_>>();
        for (id, previous) in discovered {
            let current = self
                .state
                .regional
                .dimension_name(id)
                .and_then(|name| self.state.regional.runtime(&name));
            if previous.upgrade().is_none_or(|old| {
                current.is_err() || current.as_ref().is_ok_and(|new| !Arc::ptr_eq(&old, new))
            }) {
                self.close();
                if let Some(connection) = self
                    .route
                    .connection
                    .lock()
                    .expect("route connection owner poisoned")
                    .as_ref()
                    && connection.stable_id() == self.id
                {
                    connection.close(VarInt::from_u32(0), b"dimension source replaced");
                }
                return true;
            }
        }
        false
    }
    fn reconcile(&self) {
        if self.retire_replaced_scopes() {
            return;
        }
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
    fn ready(&self, region: Region) {
        let mut wants = self.wants.lock().expect("terrain desire owner poisoned");
        let keys = wants
            .regions
            .get(&region)
            .map(|keys| keys.values().copied().collect::<Vec<_>>())
            .unwrap_or_default();
        for key in keys {
            if let Some(interest) = wants.interests.get_mut(&key)
                && (interest.known.is_none()
                    || interest.waiting_source
                    || interest.active.is_some())
            {
                interest.revision += 1;
                interest.waiting_source = false;
                #[cfg(feature = "debug-diagnostics")]
                if let Some(start) = interest.debug_source.take() {
                    diagnostics::record(Stage::SourceWait, start.elapsed(), 0);
                }
                interest.dirty = interest.known.is_some() || interest.desire.purpose == 2;
                wants.enqueue(key);
            }
        }
        drop(wants);
        self.wake();
    }
    fn policy(
        &self,
        settings: StreamingSettings,
        active: u32,
        anchors: Vec<DimensionAnchor>,
    ) -> Result<()> {
        settings.validate()?;
        validate_bandwidth(settings.bandwidth_kbps, self.state.uncapped_bandwidth)?;
        self.scope(active)?;
        *self
            .settings
            .lock()
            .expect("streaming policy owner poisoned") = settings;
        *self
            .active_dimension
            .lock()
            .expect("active dimension owner poisoned") = active;
        self.route.ledger.update(settings.bandwidth_kbps);
        if self.state.tracing() {
            let (bytes, datagrams) = self.route.ledger.counters();
            eprintln!(
                "VOXY_TOTAL_POLICY session={} route={} total_kbps={} interval_ms={} refresh_allowed={} active_dimension={} ip_bytes={} datagrams={}",
                self.id,
                hex(&self.route.token[..16]),
                settings.bandwidth_kbps,
                settings.interval_millis,
                settings.refresh_allowed,
                active,
                bytes,
                datagrams
            );
        }
        {
            let mut current = self.anchors.lock().expect("spatial anchor owner poisoned");
            for anchor in anchors {
                current.insert(anchor.dimension, (anchor.x, anchor.z));
            }
        }
        let mut wants = self.wants.lock().expect("terrain desire owner poisoned");
        wants.visible = Default::default();
        wants.prefetch.clear();
        wants.refresh.clear();
        if wants.refresh_inflight == 0 {
            wants.refresh_started = false;
        }
        let anchors = self.anchors.lock().expect("spatial anchor owner poisoned");
        let keys = wants.interests.keys().copied().collect::<Vec<_>>();
        for key in keys {
            if let Some(interest) = wants.interests.get_mut(&key) {
                interest.queued = None;
                if let Some(anchor) = anchors.get(&key.dimension) {
                    interest.desire.rank = geometric_rank(key.key, *anchor)?;
                }
            }
            wants.enqueue(key);
        }
        let interested = !wants.interests.is_empty();
        drop(wants);
        self.state
            .regional
            .set_cadence(self.id, interested.then_some(settings.interval_millis))?;
        self.wake();
        Ok(())
    }
    fn claim(&self, lane: usize) -> (Option<Claim>, Option<Instant>) {
        let settings = *self
            .settings
            .lock()
            .expect("streaming policy owner poisoned");
        let active = *self
            .active_dimension
            .lock()
            .expect("active dimension owner poisoned");
        let mut wants = self.wants.lock().expect("terrain desire owner poisoned");
        let mut deadline = None;
        if lane != 0
            && settings.refresh_allowed
            && wants.refresh.is_empty()
            && wants.refresh_inflight == 0
            && !wants.changed.is_empty()
        {
            let next_refresh = wants
                .last_refresh
                .map(|start| start + Duration::from_millis(settings.interval_millis));
            if next_refresh.is_none_or(|deadline| Instant::now() >= deadline) {
                let changed = std::mem::take(&mut wants.changed);
                for key in changed {
                    let Some(interest) = wants.interests.get(&key) else {
                        continue;
                    };
                    if !interest.dirty || interest.active.is_some() {
                        wants.changed.insert(key);
                        continue;
                    }
                    let entry = QueueEntry {
                        class: 4,
                        rank: interest.desire.rank as u128 * 256,
                        coarse: 4 - ((key.key >> 60) as u8),
                        dimension: key.dimension,
                        key: key.key,
                        revision: interest.revision,
                    };
                    wants.refresh.insert(entry);
                    let interest = wants.interests.get_mut(&key).unwrap();
                    interest.queued = Some(entry);
                    #[cfg(feature = "debug-diagnostics")]
                    {
                        if diagnostics::enabled() {
                            interest.debug_enqueued.get_or_insert_with(Instant::now);
                        }
                    }
                }
                wants.refresh_started = false;
            } else {
                deadline = next_refresh;
            }
        }
        let entry = if let Some(entry) = wants.visible[lane].first().copied() {
            Some(entry)
        } else if lane == 0 {
            None
        } else {
            let pool = if wants.prefetch.contains_key(&active) {
                Some(active)
            } else {
                wants.prefetch.first_key_value().map(|(&id, _)| id)
            };
            let missing = pool.and_then(|id| wants.prefetch[&id].first().copied());
            let refresh = if settings.refresh_allowed {
                wants.refresh.first().copied()
            } else {
                None
            };
            match (missing, refresh) {
                (Some(missing), Some(refresh)) => Some(
                    if (missing.rank, missing.coarse, missing.dimension, missing.key)
                        <= (refresh.rank, refresh.coarse, refresh.dimension, refresh.key)
                    {
                        missing
                    } else {
                        refresh
                    },
                ),
                (missing, refresh) => missing.or(refresh),
            }
        };
        let Some(entry) = entry else {
            return (None, deadline);
        };
        let key = ScopedKey {
            dimension: entry.dimension,
            key: entry.key,
        };
        wants.remove_queue(key);
        let interest = wants
            .interests
            .get_mut(&key)
            .expect("queued terrain has an owner");
        interest.active = Some((interest.desire.ticket, interest.revision));
        #[cfg(feature = "debug-diagnostics")]
        if let Some(start) = interest.debug_enqueued.take() {
            diagnostics::record(
                match entry.class {
                    3 => Stage::QueuePrefetch,
                    4 => Stage::QueueRefresh,
                    _ => Stage::QueueVisible,
                },
                start.elapsed(),
                0,
            );
        }
        let claim = Claim {
            scope: key,
            desire: interest.desire,
            revision: interest.revision,
            known: interest.known,
            refresh: entry.class == 4,
        };
        if claim.refresh {
            wants.refresh_inflight += 1;
        }
        (Some(claim), deadline)
    }
    fn finish(&self, claim: Claim, outcome: ClaimOutcome) {
        let mut wants = self.wants.lock().expect("terrain desire owner poisoned");
        if claim.refresh {
            wants.refresh_inflight -= 1;
        }
        let Some(interest) = wants.interests.get_mut(&claim.scope) else {
            drop(wants);
            self.wake();
            return;
        };
        if interest.active != Some((claim.desire.ticket, claim.revision)) {
            return;
        }
        interest.active = None;
        if matches!(outcome, ClaimOutcome::Cancelled) {
            // A gate/ticket change is not a source-not-ready result. Preserve latest work;
            // reopening the gate or adopting the new ticket will make it eligible again.
            wants.enqueue(claim.scope);
            drop(wants);
            self.wake();
            return;
        }
        if interest.desire.ticket == claim.desire.ticket {
            interest.waiting_source = matches!(outcome, ClaimOutcome::NotReady);
            #[cfg(feature = "debug-diagnostics")]
            if interest.waiting_source {
                if diagnostics::enabled() {
                    interest.debug_source.get_or_insert_with(Instant::now);
                }
            }
            if let ClaimOutcome::Sent(binding) = outcome {
                interest.known = Some(binding);
            }
            // NotReady retains the subscription and waits for publication, without polling.
            if interest.revision == claim.revision {
                interest.dirty = false;
            }
        }
        if interest.desire.ticket != claim.desire.ticket || interest.revision != claim.revision {
            wants.enqueue(claim.scope);
        }
        drop(wants);
        self.wake();
    }
    fn claim_valid(&self, claim: Claim, settings: &StreamingSettings, wants: &Wants) -> bool {
        !self.closed.load(Ordering::Acquire)
            && (!claim.refresh || settings.refresh_allowed)
            && wants.interests.get(&claim.scope).is_some_and(|interest| {
                interest.desire.ticket == claim.desire.ticket
                    && interest.revision == claim.revision
                    && interest.active == Some((claim.desire.ticket, claim.revision))
            })
    }
    fn eligible(&self, claim: Claim) -> bool {
        let settings = self
            .settings
            .lock()
            .expect("streaming policy owner poisoned");
        let wants = self.wants.lock().expect("terrain desire owner poisoned");
        self.claim_valid(claim, &settings, &wants)
    }
    async fn begin_record(&self, send: &mut quinn::SendStream, claim: Claim) -> Result<bool> {
        let admission = Span::new(Stage::Admission);
        loop {
            let changed = self.available.notified();
            tokio::pin!(changed);
            changed.as_mut().enable();
            let admitted = std::future::poll_fn(|cx| {
                let settings = self
                    .settings
                    .lock()
                    .expect("streaming policy owner poisoned");
                let mut wants = self.wants.lock().expect("terrain desire owner poisoned");
                if !self.claim_valid(claim, &settings, &wants) {
                    return std::task::Poll::Ready(Ok(false));
                }
                // A one-byte poll is cancellation safe. Policy/ticket ownership cannot
                // change between this check and actual acceptance into Quinn's stream.
                match Pin::new(&mut *send).poll_write(cx, &[wire::S_RECORD]) {
                    std::task::Poll::Ready(Ok(1)) => {
                        if claim.refresh && !wants.refresh_started {
                            wants.last_refresh = Some(Instant::now());
                            wants.refresh_started = true;
                        }
                        std::task::Poll::Ready(Ok(true))
                    }
                    std::task::Poll::Ready(Ok(_)) => std::task::Poll::Ready(Err(anyhow::anyhow!(
                        "terrain lane accepted no record byte"
                    ))),
                    std::task::Poll::Ready(Err(error)) => std::task::Poll::Ready(Err(error.into())),
                    std::task::Poll::Pending => std::task::Poll::Pending,
                }
            });
            tokio::select! {
                biased;
                _ = &mut changed => {},
                result = admitted => { admission.finish(result.is_ok(),0); return result },
            }
        }
    }
    async fn metadata(&self, message: &ControlMessage) -> Result<()> {
        let lock = Span::new(Stage::MetadataLock);
        let mut writer = self.metadata.lock().await;
        lock.finish(true, 0);
        if writer.failed {
            bail!("metadata writer closed")
        }
        let write = Span::new(Stage::MetadataWrite);
        let result = writer
            .send
            .write_all(&encode_control_record(message)?)
            .await;
        write.finish(result.is_ok(), 0);
        if result.is_err() {
            writer.failed = true;
        }
        result.context("shared metadata frame failed")
    }
    async fn announce_catalogue(
        &self,
        dimension: u32,
        world: [u8; 32],
        catalogue: &CatalogDefinition,
    ) -> Result<()> {
        let lock = Span::new(Stage::MetadataLock);
        let mut writer = self.metadata.lock().await;
        lock.finish(true, 0);
        if writer.failed {
            bail!("metadata writer closed")
        }
        if self
            .catalogues
            .lock()
            .expect("catalogue owner poisoned")
            .contains(&catalogue.fingerprint)
        {
            return Ok(());
        }
        use tokio::io::AsyncWriteExt;
        let write = Span::new(Stage::MetadataWrite);
        let result = async {
            writer.send.write_u8(0x83).await?;
            writer
                .send
                .write_u32_le((36 + catalogue.payload.len()) as u32)
                .await?;
            writer.send.write_u32_le(dimension).await?;
            writer.send.write_all(&world).await?;
            writer.send.write_all(&catalogue.payload).await?;
            Ok::<(), anyhow::Error>(())
        }
        .await;
        write.finish(result.is_ok(), catalogue.payload.len() as u64);
        if let Err(error) = result {
            writer.failed = true;
            return Err(error);
        }
        self.catalogues
            .lock()
            .expect("catalogue owner poisoned")
            .insert(catalogue.fingerprint);
        if self.state.tracing() {
            eprintln!(
                "VOXY_CATALOG_SENT session={} dimension={} fingerprint={} canonical_bytes={} compressed_bytes={}",
                self.id,
                dimension,
                blake3::Hash::from(catalogue.fingerprint),
                catalogue.canonical_length,
                catalogue.compressed_length
            );
        }
        Ok(())
    }
}

pub async fn serve(
    state: Arc<ServerState>,
    listen: SocketAddr,
    identity_directory: &Path,
    shutdown: impl Future<Output = Result<()>>,
) -> Result<()> {
    let identity = load_or_create_identity(identity_directory)?;
    let config = make_server_config(&identity)?;
    let runtime = Arc::new(quinn::TokioRuntime);
    let socket = std::net::UdpSocket::bind(listen)
        .with_context(|| format!("bind Voxy QUIC UDP {listen}"))?;
    let actual = socket.local_addr()?;
    let mux = UdpMux::new(runtime.wrap_udp_socket(socket)?);
    state
        .network
        .set(Network { mux, config })
        .map_err(|_| anyhow::anyhow!("UDP listener already initialized"))?;
    state.start_bridge()?;
    #[cfg(feature = "debug-diagnostics")]
    state.start_diagnostics();
    state.publication_loop();
    eprintln!(
        "VOXY_READY udp_port={} alpn={} cert_sha256={}",
        actual.port(),
        std::str::from_utf8(ALPN)?,
        identity.fingerprint
    );
    tokio::select! { result = shutdown => result?, _ = state.bridge_stopped.notified() => {} }
    state.regional.shutdown("Voxy server shutting down");
    let routes = state
        .routes
        .lock()
        .expect("Minecraft route owner poisoned")
        .drain()
        .map(|(_, route)| route)
        .collect::<Vec<_>>();
    for route in &routes {
        route.close();
    }
    for route in routes {
        route.endpoint.wait_idle().await;
    }
    Ok(())
}
async fn serve_connection(
    state: Arc<ServerState>,
    route: Arc<RouteOwner>,
    incoming: quinn::Incoming,
) -> Result<()> {
    let tls = Span::new(Stage::Tls);
    let connection = incoming.await?;
    tls.finish(true, 0);
    if state.tracing() {
        eprintln!(
            "VOXY_BOOTSTRAP stage=TLS_ESTABLISHED session={} route={} peer={}",
            connection.stable_id(),
            hex(&route.token[..16]),
            connection.remote_address()
        );
    }
    if route.closed.load(Ordering::Acquire) {
        connection.close(VarInt::from_u32(0), b"Minecraft session revoked");
        return Ok(());
    }
    let result = serve_established(state.clone(), route.clone(), &connection).await;
    connection.close(VarInt::from_u32(0), b"Voxy session ended");
    if state.tracing() {
        let (bytes, packets) = route.ledger.counters();
        eprintln!(
            "VOXY_QUIC_ENDED session={} route={} route_ip_bytes={bytes} route_datagrams={packets} success={}",
            connection.stable_id(),
            hex(&route.token[..16]),
            result.is_ok()
        );
    }
    result
}
async fn serve_established(
    state: Arc<ServerState>,
    route: Arc<RouteOwner>,
    connection: &quinn::Connection,
) -> Result<()> {
    let bootstrap = Span::new(Stage::Bootstrap);
    let (send, mut recv) = connection.accept_bi().await?;
    send.set_priority(3)?;
    if read_stream_role(&mut recv).await? != Some(STREAM_CONTROL) {
        bail!("first stream must be control")
    }
    if state.tracing() {
        eprintln!(
            "VOXY_BOOTSTRAP stage=FIRST_CONTROL session={} route={}",
            connection.stable_id(),
            hex(&route.token[..16])
        );
    }
    let mut supplied = [0; 32];
    recv.read_exact(&mut supplied).await?;
    if supplied != route.token {
        bail!("authenticated route token mismatch")
    }
    if state.tracing() {
        eprintln!(
            "VOXY_BOOTSTRAP stage=TOKEN_VALID session={} route={}",
            connection.stable_id(),
            hex(&route.token[..16])
        );
    }
    // Routing carries only the public first half of the token. Do not let an unauthenticated
    // TLS handshake using that prefix displace the player's authenticated connection.
    {
        let mut current = route
            .connection
            .lock()
            .expect("QUIC connection owner poisoned");
        if let Some(old) = current.replace(connection.clone()) {
            old.close(VarInt::from_u32(0), b"QUIC reconnect");
        }
    }
    let open = if state.tracing() {
        wire::read_control_traced(
            &mut recv,
            connection.stable_id() as u64,
            &hex(&route.token[..16]),
        )
        .await?
    } else {
        read_control(&mut recv).await?
    };
    let (dimension, expected_world, held_catalog, settings, anchor_x, anchor_z, desires) =
        match open {
            Some(ControlMessage::Open {
                dimension,
                expected_world,
                held_catalog,
                settings,
                anchor_x,
                anchor_z,
                desires,
            }) => (
                dimension,
                expected_world,
                held_catalog,
                settings,
                anchor_x,
                anchor_z,
                desires,
            ),
            _ => bail!("OPEN must be first"),
        };
    if state.tracing() {
        eprintln!(
            "VOXY_BOOTSTRAP stage=OPEN_READ session={} route={} dimension={} total_kbps={} initial_desires={} expected_world={} held_catalog={}",
            connection.stable_id(),
            hex(&route.token[..16]),
            dimension,
            settings.bandwidth_kbps,
            desires.len(),
            blake3::Hash::from(expected_world),
            blake3::Hash::from(held_catalog)
        );
    }
    let active = state.regional.dimension_id(&dimension)?;
    let responder = state
        .regional
        .responder(&dimension, state.server_instance)?;
    let matched = expected_world == [0; 32] || expected_world == responder.world_identity();
    let mut catalogues = HashSet::new();
    if matched && held_catalog != [0; 32] {
        catalogues.insert(held_catalog);
    }
    let session = Arc::new(Session {
        id: connection.stable_id(),
        state: state.clone(),
        route,
        scopes: Mutex::new(HashMap::new()),
        discovered: Mutex::new(HashMap::new()),
        wants: Mutex::new(Wants::default()),
        available: Notify::new(),
        stopped: Notify::new(),
        closed: AtomicBool::new(false),
        settings: Mutex::new(settings),
        active_dimension: Mutex::new(active),
        anchors: Mutex::new(HashMap::from([(active, (anchor_x, anchor_z))])),
        metadata: tokio::sync::Mutex::new(PrimaryWriter {
            send,
            failed: false,
        }),
        catalogues: Mutex::new(catalogues),
        #[cfg(feature = "debug-diagnostics")]
        debug_bytes: std::sync::atomic::AtomicU64::new(0),
        #[cfg(feature = "debug-diagnostics")]
        debug_records: std::array::from_fn(|_| std::sync::atomic::AtomicU64::new(0)),
        #[cfg(feature = "debug-diagnostics")]
        debug_phase: std::array::from_fn(|_| {
            std::array::from_fn(|_| std::sync::atomic::AtomicU64::new(0))
        }),
    });
    state
        .sessions
        .lock()
        .expect("terrain session owner poisoned")
        .insert(session.id, Arc::downgrade(&session));
    let result = async {
        session.policy(settings, active, vec![DimensionAnchor { dimension:active, x:anchor_x, z:anchor_z }])?;
        session.metadata(&responder.hello(active)?).await?;
        if state.tracing() {
            eprintln!("VOXY_BOOTSTRAP stage=HELLO_SENT session={} route={}", session.id, hex(&session.route.token[..16]));
        }
        session.metadata(&state.regional.manifest_record()?).await?;
        if state.tracing() {
            eprintln!("VOXY_BOOTSTRAP stage=MANIFEST_SENT session={} route={}", session.id, hex(&session.route.token[..16]));
        }
        let definition = responder.catalogue()?;
        session.announce_catalogue(active, responder.world_identity(), &definition).await?;
        bootstrap.finish(true,0);
        if matched {
            session.apply(desires.into_iter().map(|desire| ScopedDesire { dimension:active, expected_world, desire }).collect())?;
        }
        if state.tracing() {
            eprintln!("VOXY_STREAM_SESSION session={} peer={} dimension={} total_kbps={} held_catalog={}", session.id, connection.remote_address(), active, settings.bandwidth_kbps, blake3::Hash::from(held_catalog));
        }
        let mut tasks = tokio::task::JoinSet::new();
        let owner = session.clone();
        tasks.spawn(async move {
            loop {
                match read_control(&mut recv).await? {
                    Some(ControlMessage::Desires(desires)) => owner.apply(desires)?,
                    Some(ControlMessage::Drop(keys)) => owner.drop_keys(keys)?,
                    Some(ControlMessage::Settings { settings, active_dimension, anchors }) => owner.policy(settings, active_dimension, anchors)?,
                    None => return Ok(()),
                    _ => bail!("unexpected client control record"),
                }
            }
        });
        let result = loop {
            tokio::select! {
                incoming = connection.accept_bi() => {
                    let (send,recv) = match incoming { Ok(streams)=>streams, Err(error)=>break Err(error.into()) };
                    let owner=session.clone();
                    tasks.spawn(async move { serve_lane(owner,send,recv).await });
                }
                result = tasks.join_next() => {
                    break match result { Some(Ok(result))=>result, Some(Err(error))=>Err(error.into()), None=>Ok(()) };
                }
                error = connection.closed() => break Err(error.into()),
            }
        };
        session.close();
        tasks.abort_all();
        while tasks.join_next().await.is_some() {}
        result
    }.await;
    session.close();
    state
        .sessions
        .lock()
        .expect("terrain session owner poisoned")
        .remove(&session.id);
    let regions = session
        .wants
        .lock()
        .expect("terrain desire owner poisoned")
        .regions
        .keys()
        .copied()
        .collect::<Vec<_>>();
    for region in regions {
        state.release(&session, region);
        session
            .scope(region.0)?
            .responder
            .unsubscribe_region(region.1, region.2)?;
    }
    state.regional.set_cadence(session.id, None)?;
    result
}
async fn serve_lane(
    session: Arc<Session>,
    mut send: quinn::SendStream,
    mut recv: quinn::RecvStream,
) -> Result<()> {
    match read_stream_role(&mut recv).await? {
        Some(STREAM_DISCOVERY) => {
            send.set_priority(1)?;
            return discovery_loop(session, &mut send).await;
        }
        Some(STREAM_SECTION_LANE) => {}
        _ => bail!("invalid section stream role"),
    }
    let lane = read_lane(&mut recv).await? as usize;
    send.set_priority(if lane == 0 { 2 } else { 1 })?;
    loop {
        let notified = session.available.notified();
        if session.closed.load(Ordering::Acquire) {
            return Ok(());
        }
        let (claim, deadline) = session.claim(lane);
        if let Some(claim) = claim {
            let outcome = tokio::select! {result=send_claim(&session,&mut send,claim,lane)=>result?,_=session.ended()=>return Ok(())};
            session.finish(claim, outcome);
        } else {
            tokio::select! {_=notified=>{},_=async{if let Some(deadline)=deadline{tokio::time::sleep_until(deadline).await}else{std::future::pending::<()>().await}}=>{},_=session.ended()=>return Ok(())}
        }
    }
}
fn geometric_rank(packed: u64, anchor: (i32, i32)) -> Result<u64> {
    let key = crate::key::SectionKey::unpack(packed)?;
    let side = 32i64 << key.level;
    let x = key.x as i64 * side;
    let z = key.z as i64 * side;
    let distance = |position: i32, origin: i64| {
        if (position as i64) < origin {
            origin - position as i64
        } else if position as i64 > origin + side {
            position as i64 - origin - side
        } else {
            0
        }
    };
    let dx = distance(anchor.0, x) as i128;
    let dz = distance(anchor.1, z) as i128;
    let rank = 512i128 * 512 + ((dx * dx + dz * dz) << (2 * (4 - key.level)));
    u64::try_from(rank).context("spatial rank overflow")
}
async fn send_claim(
    session: &Session,
    send: &mut quinn::SendStream,
    claim: Claim,
    lane: usize,
) -> Result<ClaimOutcome> {
    let request = Span::new(Stage::Request);
    #[cfg(feature = "debug-diagnostics")]
    let active = ActiveLane::new(session, lane, claim);
    #[cfg(not(feature = "debug-diagnostics"))]
    let _ = lane;
    let result = async {
        let scope = session.scope(claim.scope.dimension)?;
        let responder = scope.responder.clone();
        let desire = claim.desire;
        // Debug lifecycle proof retains the route while detached blocking work can still run.
        // A cancelled async waiter does not imply the executor closure has stopped.
        #[cfg(feature = "debug-diagnostics")]
        let work_owner = session.route.clone();
        let mut prepared: PreparedSection = diagnostics::blocking(
            Stage::PrepareQueue,
            Stage::PrepareWork,
            Stage::PrepareResume,
            move || {
                #[cfg(feature = "debug-diagnostics")]
                let _owner = work_owner;
                responder.prepare(desire.ticket, desire.key)
            },
        )
        .await??;
        let descriptor = prepared.descriptor;
        if !session.eligible(claim) {
            return Ok(ClaimOutcome::Cancelled);
        }
        if claim.refresh
            && claim.known == Some(descriptor.binding)
            && descriptor.status != RecordStatus::NotReady
        {
            return Ok(ClaimOutcome::Sent(descriptor.binding));
        }
        if descriptor.binding.catalog_fingerprint != [0; 32] {
            #[cfg(feature = "debug-diagnostics")]
            session.phase(lane, 2);
            session
                .announce_catalogue(claim.scope.dimension, scope.world, &prepared.catalog)
                .await?;
        }
        if !session.eligible(claim) {
            return Ok(ClaimOutcome::Cancelled);
        }
        if descriptor.status == RecordStatus::Data
            && claim
                .known
                .is_some_and(|known| known.has_body() && known.same_body(descriptor.binding))
        {
            prepared.descriptor.status = RecordStatus::Reuse;
        }
        let descriptor = prepared.descriptor;
        #[cfg(feature = "debug-diagnostics")]
        {
            session.phase(lane, 3);
            session.debug_phase[lane][5].store(descriptor.generation, Ordering::Relaxed);
        }
        #[cfg(feature = "debug-diagnostics")]
        let work_owner = session.route.clone();
        let body = diagnostics::blocking(
            Stage::BodyQueue,
            Stage::BodyWork,
            Stage::BodyResume,
            move || {
                #[cfg(feature = "debug-diagnostics")]
                let _owner = work_owner;
                prepared.body()
            },
        )
        .await??;
        #[cfg(feature = "debug-diagnostics")]
        session.phase(lane, 4);
        if !session.begin_record(send, claim).await? {
            return Ok(ClaimOutcome::Cancelled);
        }
        #[cfg(feature = "debug-diagnostics")]
        session.phase(lane, 5);
        let write = Span::new(Stage::RecordWrite);
        let written =
            wire::write_record_body(send, claim.scope.dimension, scope.world, descriptor, &body)
                .await;
        write.finish(written.is_ok(), body.len() as u64);
        written?;
        #[cfg(feature = "debug-diagnostics")]
        {
            session.debug_records[descriptor.status as usize].fetch_add(1, Ordering::Relaxed);
            session
                .debug_bytes
                .fetch_add(body.len() as u64, Ordering::Relaxed);
        }
        Ok(if descriptor.status == RecordStatus::NotReady {
            ClaimOutcome::NotReady
        } else {
            ClaimOutcome::Sent(descriptor.binding)
        })
    }
    .await;
    #[cfg(feature = "debug-diagnostics")]
    {
        active.completed.set(true);
        if matches!(result, Ok(ClaimOutcome::Cancelled)) {
            session.debug_records[5].fetch_add(1, Ordering::Relaxed);
        }
    }
    if matches!(result, Ok(ClaimOutcome::Cancelled)) {
        request.cancelled();
    } else {
        request.finish(result.is_ok(), 0);
    }
    result
}
#[cfg(feature = "debug-diagnostics")]
struct ActiveLane<'a> {
    session: &'a Session,
    lane: usize,
    completed: std::cell::Cell<bool>,
}
#[cfg(feature = "debug-diagnostics")]
impl<'a> ActiveLane<'a> {
    fn new(session: &'a Session, lane: usize, claim: Claim) -> Self {
        let fields = &session.debug_phase[lane];
        for (index, value) in [
            (2, claim.desire.ticket),
            (3, claim.scope.key),
            (4, claim.scope.dimension as u64),
            (5, 0),
            (6, claim.revision),
        ] {
            fields[index].store(value, Ordering::Relaxed);
        }
        session.phase(lane, 1);
        Self {
            session,
            lane,
            completed: std::cell::Cell::new(false),
        }
    }
}
#[cfg(feature = "debug-diagnostics")]
impl Drop for ActiveLane<'_> {
    fn drop(&mut self) {
        if !self.completed.get() {
            self.session.debug_records[5].fetch_add(1, Ordering::Relaxed);
        }
        self.session.phase(self.lane, 0);
    }
}
async fn write_discovery(send: &mut quinn::SendStream, message: &ControlMessage) -> Result<()> {
    send.write_all(&encode_control_record(message)?)
        .await
        .context("discovery stream frame failed")
}
fn inventory_state(availability: &crate::anvil::RegionAvailability, published: bool) -> u8 {
    if !availability.readable {
        2
    } else if published {
        1
    } else {
        6
    }
}
fn discovery_priority(session: &Session, send: &quinn::SendStream, dimension: u32) -> Result<()> {
    let active = *session
        .active_dimension
        .lock()
        .expect("active dimension owner poisoned");
    // Equal-priority streams share transport service. Keep inactive discovery at the
    // refinement priority so pending active COMPLETE bytes cannot be stranded behind
    // continuous refresh traffic when this shared stream moves to another dimension.
    send.set_priority(if dimension == active { 2 } else { 1 })?;
    Ok(())
}
async fn snapshot_inventory(
    session: &Session,
    send: &mut quinn::SendStream,
    dimension: u32,
) -> Result<Option<u64>> {
    discovery_priority(session, send, dimension)?;
    let name = session.state.regional.dimension_name(dimension)?;
    let runtime = session.state.regional.runtime(&name)?;
    session
        .discovered
        .lock()
        .expect("discovery scope owner poisoned")
        .insert(dimension, Arc::downgrade(&runtime));
    let revision = runtime.inventory_revision();
    let known = runtime.inventory_known()?;
    let record = |state, x, z, saved| {
        ControlMessage::Inventory(InventoryRecord {
            dimension,
            revision,
            state,
            x,
            z,
            saved,
        })
    };
    write_discovery(send, &record(if known { 0 } else { 5 }, 0, 0, [0; 16])).await?;
    if !known {
        if session.state.tracing() {
            eprintln!(
                "VOXY_INVENTORY_SNAPSHOT session={} dimension={dimension} revision={revision} known=false rows=0",
                session.id
            );
        }
        return Ok(None);
    }
    let mut after = None;
    let mut rows = 0u64;
    while let Some((coordinate, availability, published)) = runtime.inventory_after(after)? {
        write_discovery(
            send,
            &record(
                inventory_state(&availability, published),
                coordinate.0,
                coordinate.1,
                availability.saved,
            ),
        )
        .await?;
        after = Some(coordinate);
        rows += 1;
    }
    if session.state.tracing() {
        eprintln!(
            "VOXY_INVENTORY_SNAPSHOT session={} dimension={dimension} revision={revision} known=true rows={rows}",
            session.id
        );
    }
    // Complete only after queued changes since the cursor began have caught up. The map can
    // change while a capped stream writes; declaring this mixed cursor a finished snapshot
    // would incorrectly turn a just-saved or removed region into authoritative absence.
    Ok(Some(revision))
}
async fn discovery_loop(session: Arc<Session>, send: &mut quinn::SendStream) -> Result<()> {
    let mut events = session.state.regional.subscribe();
    let manifest = session.state.regional.manifest()?;
    let mut known_dimensions = manifest
        .iter()
        .map(|dimension| dimension.id)
        .collect::<HashSet<_>>();
    let mut revisions = HashMap::new();
    let mut pending_complete = HashSet::new();
    let mut initialized = HashSet::new();
    let mut pending_snapshots = manifest
        .iter()
        .map(|dimension| dimension.id)
        .collect::<VecDeque<_>>();
    loop {
        let event = match events.try_recv() {
            Ok(event) => Some(Ok(event)),
            Err(tokio::sync::broadcast::error::TryRecvError::Lagged(lost)) => {
                Some(Err(tokio::sync::broadcast::error::RecvError::Lagged(lost)))
            }
            Err(tokio::sync::broadcast::error::TryRecvError::Closed) => return Ok(()),
            Err(tokio::sync::broadcast::error::TryRecvError::Empty) => None,
        };
        let event = match event {
            Some(event) => event,
            None => {
                let pending = pending_complete.iter().copied().collect::<Vec<_>>();
                for dimension in pending {
                    let name = session.state.regional.dimension_name(dimension)?;
                    let runtime = session.state.regional.runtime(&name)?;
                    let revision = revisions[&dimension];
                    if runtime.inventory_known()? && runtime.inventory_revision() == revision {
                        discovery_priority(&session, send, dimension)?;
                        write_discovery(
                            send,
                            &ControlMessage::Inventory(InventoryRecord {
                                dimension,
                                revision,
                                state: 4,
                                x: 0,
                                z: 0,
                                saved: [0; 16],
                            }),
                        )
                        .await?;
                        pending_complete.remove(&dimension);
                        if session.state.tracing() {
                            eprintln!(
                                "VOXY_INVENTORY_COMPLETE session={} dimension={dimension} revision={revision}",
                                session.id
                            );
                        }
                    }
                }
                if pending_complete.is_empty() && !pending_snapshots.is_empty() {
                    let active = *session
                        .active_dimension
                        .lock()
                        .expect("active dimension owner poisoned");
                    let position = pending_snapshots
                        .iter()
                        .position(|id| *id == active)
                        .unwrap_or(0);
                    let dimension = pending_snapshots
                        .remove(position)
                        .expect("pending snapshot exists");
                    initialized.insert(dimension);
                    if let Some(revision) = snapshot_inventory(&session, send, dimension).await? {
                        revisions.insert(dimension, revision);
                        pending_complete.insert(dimension);
                    }
                    continue;
                }
                events.recv().await
            }
        };
        match event {
            Ok(RegionalAnnouncement::Manifest) => {
                if session.retire_replaced_scopes() {
                    return Ok(());
                }
                let manifest = session.state.regional.manifest()?;
                write_discovery(send, &session.state.regional.manifest_record()?).await?;
                for dimension in manifest {
                    if known_dimensions.insert(dimension.id) {
                        pending_snapshots.push_back(dimension.id);
                    }
                }
            }
            Ok(RegionalAnnouncement::Discovery {
                dimension,
                revision,
                updates,
                reset,
            }) => {
                let id = session.state.regional.dimension_id(&dimension)?;
                // A later full snapshot already includes changes before that dimension starts.
                if !initialized.contains(&id) {
                    continue;
                }
                if revisions
                    .get(&id)
                    .is_some_and(|previous| *previous > revision)
                {
                    continue;
                }
                let runtime = session.state.regional.runtime(&dimension)?;
                discovery_priority(&session, send, id)?;
                if reset {
                    if let Some(revision) = snapshot_inventory(&session, send, id).await? {
                        revisions.insert(id, revision);
                        pending_complete.insert(id);
                    }
                    continue;
                }
                revisions.insert(id, revision);
                if !runtime.inventory_known()? {
                    pending_complete.remove(&id);
                    write_discovery(
                        send,
                        &ControlMessage::Inventory(InventoryRecord {
                            dimension: id,
                            revision,
                            state: 5,
                            x: 0,
                            z: 0,
                            saved: [0; 16],
                        }),
                    )
                    .await?;
                    continue;
                }
                for (coordinate, availability, published) in updates.iter() {
                    write_discovery(
                        send,
                        &ControlMessage::Inventory(InventoryRecord {
                            dimension: id,
                            revision,
                            state: availability
                                .as_ref()
                                .map_or(3, |entry| inventory_state(entry, *published)),
                            x: coordinate.0,
                            z: coordinate.1,
                            saved: availability.as_ref().map_or([0; 16], |entry| entry.saved),
                        }),
                    )
                    .await?;
                }
            }
            Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {
                let manifest = session.state.regional.manifest()?;
                write_discovery(send, &session.state.regional.manifest_record()?).await?;
                known_dimensions = manifest.iter().map(|dimension| dimension.id).collect();
                pending_snapshots = manifest.iter().map(|dimension| dimension.id).collect();
                initialized.clear();
                revisions.clear();
                pending_complete.clear();
            }
            Ok(RegionalAnnouncement::Shutdown(message)) => {
                write_discovery(send, &ControlMessage::Shutdown { message }).await?;
                return Ok(());
            }
            Err(tokio::sync::broadcast::error::RecvError::Closed) => return Ok(()),
            _ => {}
        }
    }
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
    server.transport_config(make_transport_config()?);
    Ok(server)
}

fn make_transport_config() -> Result<Arc<quinn::TransportConfig>> {
    let mut transport = quinn::TransportConfig::default();
    // Control, eight section lanes, and one low-priority discovery lane share this connection.
    transport.max_concurrent_bidi_streams(VarInt::from_u32(10));
    transport.max_concurrent_uni_streams(VarInt::from_u32(0));
    transport.stream_receive_window(VarInt::from_u32(32 * 1024));
    transport.receive_window(VarInt::from_u32(1024 * 1024));
    transport.send_window(512 * 1024);
    transport.datagram_receive_buffer_size(None);
    transport.datagram_send_buffer_size(0);
    transport.max_idle_timeout(Some(IdleTimeout::try_from(IDLE_TIMEOUT)?));
    transport.keep_alive_interval(Some(KEEPALIVE_INTERVAL));
    let mut discovery = quinn::MtuDiscoveryConfig::default();
    discovery.upper_bound(1452 - ENVELOPE_BYTES as u16);
    transport.mtu_discovery_config(Some(discovery));
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
