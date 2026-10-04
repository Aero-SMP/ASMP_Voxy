use anyhow::{Context, Result, ensure};
use fastnbt::Value;
use quinn::{Connection, Endpoint};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet},
    io::Read,
    path::PathBuf,
    sync::{Arc, Mutex},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::{
    io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt},
    sync::watch,
};

type Key = (u8, i32, i32, i32);
fn region(id: usize) -> (i32, i32) {
    ((id % 10) as i32 * 4 - 20, (id / 10) as i32 * 4 - 20)
}
#[derive(Default, Clone, Serialize)]
struct Stats {
    connected: bool,
    attempts: u64,
    cached_before_connect: usize,
    cache_decode_ms: f64,
    phase: &'static str,
    phase_started_ms: u64,
    last_progress_ms: u64,
    last_failed_phase: &'static str,
    handshakes: u64,
    world_acks: u64,
    request_index: usize,
    response_index: usize,
    batch_size: usize,
    pending_payload_bytes: usize,
    completed_payload_bytes: u64,
    transport_stats: Option<serde_json::Value>,
    #[serde(skip)]
    transport: Option<Connection>,
    cached_sections: usize,
    cached_at_pressure: usize,
    setup_requests: u64,
    setup_downloads: u64,
    setup_bytes: u64,
    requests: u64,
    downloads: u64,
    bytes: u64,
    unchanged: u64,
    unavailable: u64,
    changes: u64,
    cache_hits: u64,
    offline_hits: u64,
    complete_cycles: u64,
    incomplete_cycles: u64,
    first_coarse_ms: Option<f64>,
    first_detail_ms: Option<f64>,
    reconnects: u64,
    rtt_ms: f64,
    cycle_ms: Vec<f64>,
    failures: Vec<String>,
}
fn unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
impl Stats {
    fn phase(&mut self, phase: &'static str) {
        self.phase = phase;
        self.phase_started_ms = unix_ms();
        self.last_progress_ms = self.phase_started_ms;
    }
}
fn transport_stats(connection: &Connection) -> serde_json::Value {
    let s = connection.stats();
    serde_json::json!({"live":connection.close_reason().is_none(), "rtt_ms":s.path.rtt.as_secs_f64()*1000.,
        "cwnd":s.path.cwnd, "lost_packets":s.path.lost_packets, "lost_bytes":s.path.lost_bytes,
        "congestion_events":s.path.congestion_events,
        "udp_tx":{"packets":s.udp_tx.datagrams,"bytes":s.udp_tx.bytes},
        "udp_rx":{"packets":s.udp_rx.datagrams,"bytes":s.udp_rx.bytes},
        "tx":{"ack":s.frame_tx.acks,"crypto":s.frame_tx.crypto,"ping":s.frame_tx.ping,
              "stream":s.frame_tx.stream,"data_blocked":s.frame_tx.data_blocked,"stream_data_blocked":s.frame_tx.stream_data_blocked},
        "rx":{"ack":s.frame_rx.acks,"crypto":s.frame_rx.crypto,"ping":s.frame_rx.ping,
              "stream":s.frame_rx.stream,"data_blocked":s.frame_rx.data_blocked,"stream_data_blocked":s.frame_rx.stream_data_blocked}})
}
struct Observation(Arc<Mutex<Stats>>);
impl Drop for Observation {
    fn drop(&mut self) {
        if let Ok(mut s) = self.0.lock() {
            if let Some(connection) = s.transport.take() {
                s.transport_stats = Some(transport_stats(&connection));
            }
        }
    }
}
fn observations(
    states: &[Arc<Mutex<Stats>>],
    previous: &mut [Option<serde_json::Value>],
) -> Vec<serde_json::Value> {
    states.iter().enumerate().filter_map(|(id, state)| {
        let mut s = state.lock().unwrap();
        if let Some(connection) = &s.transport { s.transport_stats = Some(transport_stats(connection)); }
        let value = serde_json::json!({"id":id,"attempt":s.attempts,"phase":s.phase,
            "phase_started_ms":s.phase_started_ms,"last_progress_ms":s.last_progress_ms,
            "last_failed_phase":s.last_failed_phase,"handshakes":s.handshakes,"world_acks":s.world_acks,
            "world_ready":s.connected && s.transport.as_ref().is_some_and(|connection| connection.close_reason().is_none()),
            "request_index":s.request_index,"response_index":s.response_index,"batch_size":s.batch_size,
            "pending_payload_bytes":s.pending_payload_bytes,"completed_payload_bytes":s.completed_payload_bytes,
            "cached_sections":s.cached_sections,"transport":s.transport_stats});
        if previous[id].as_ref() == Some(&value) { None } else { previous[id] = Some(value.clone()); Some(value) }
    }).collect()
}
fn keys(id: usize, elapsed: f64, zoom: bool) -> Vec<Key> {
    let (rx, rz) = region(id);
    let mut wanted = Vec::new();
    for level in (1..=4).rev() {
        let side = 16 >> level;
        for z in 0..side {
            for x in 0..side {
                wanted.push((level, rx * side + x, 0, rz * side + z));
            }
        }
    }
    if !zoom || (elapsed / 10.0) as u64 % 2 == 0 {
        let mut detail = Vec::new();
        for y in 0..2 {
            for z in 0..16 {
                for x in 0..16 {
                    detail.push((0, rx * 16 + x, y, rz * 16 + z));
                }
            }
        }
        let camera = (
            (elapsed * 0.8).sin() * 1.5 + 1.5,
            (elapsed * 0.3).cos() * 1.5 + 1.5,
        );
        detail.sort_by_key(|key| {
            (((key.1 - rx * 16) as f64 - camera.0).powi(2) * 100.0
                + ((key.3 - rz * 16) as f64 - camera.1).powi(2) * 100.0) as i32
        });
        wanted.extend(detail);
    }
    wanted
}
fn validate(bytes: &[u8]) -> Result<[u8; 32]> {
    let mut raw = Vec::new();
    flate2::read::ZlibDecoder::new(bytes).read_to_end(&mut raw)?;
    let tag: HashMap<String, Value> = fastnbt::from_bytes(&raw)?;
    let Value::List(palette) = tag.get("palette").context("palette")? else {
        anyhow::bail!("palette type");
    };
    ensure!(
        !palette.is_empty() && palette.len() <= 39304,
        "palette size"
    );
    for entry in palette {
        let Value::Compound(item) = entry else {
            anyhow::bail!("palette entry");
        };
        let Some(Value::Compound(state)) = item.get("state") else {
            anyhow::bail!("state");
        };
        ensure!(
            matches!(state.get("Name"), Some(Value::String(_)))
                && matches!(item.get("biome"), Some(Value::String(_)))
                && matches!(item.get("light"), Some(Value::Byte(_))),
            "palette fields"
        );
    }
    let Some(Value::LongArray(data)) = tag.get("data") else {
        anyhow::bail!("data");
    };
    let bits = (usize::BITS - (palette.len() - 1).leading_zeros()).max(1);
    let per_word = 64 / bits as usize;
    ensure!(
        data.len() == 39304_usize.div_ceil(per_word),
        "packed length"
    );
    for i in 0..39304 {
        ensure!(
            ((data[i / per_word] as u64 >> ((i % per_word) * bits as usize))
                & ((1_u64 << bits) - 1))
                < palette.len() as u64,
            "palette index"
        );
    }
    ensure!(
        matches!(tag.get("children"), Some(Value::Byte(_)))
            && matches!(tag.get("entities"), Some(Value::List(_))),
        "section fields"
    );
    Ok(Sha256::digest(bytes).into())
}
fn filename(key: Key) -> String {
    format!("{}_{}_{}_{}.vxs", key.0, key.1, key.2, key.3)
}
fn atomic(path: &std::path::Path, bytes: &[u8]) -> Result<()> {
    let temporary = path.with_extension("part");
    std::fs::write(&temporary, bytes)?;
    std::fs::rename(temporary, path)?;
    Ok(())
}
async fn requests(
    send: &mut quinn::SendStream,
    keys: &[Key],
    cache: &HashMap<Key, [u8; 32]>,
    stats: &Arc<Mutex<Stats>>,
) -> Result<()> {
    for key in keys {
        send.write_u8(0).await?;
        send.write_u8(key.0).await?;
        send.write_i32_le(key.1).await?;
        send.write_i32_le(key.2).await?;
        send.write_i32_le(key.3).await?;
        send.write_all(cache.get(key).unwrap_or(&[0; 32])).await?;
        let mut s = stats.lock().unwrap();
        s.requests += 1;
        s.request_index += 1;
        s.last_progress_ms = unix_ms();
    }
    Ok(())
}
async fn actor(
    id: usize,
    endpoint: Endpoint,
    config: quinn::ClientConfig,
    address: std::net::SocketAddr,
    directory: PathBuf,
    seconds: f64,
    zoom: bool,
    start: watch::Receiver<Option<Instant>>,
    stats: Arc<Mutex<Stats>>,
    current: Arc<Mutex<Option<Connection>>>,
) -> Result<()> {
    stats.lock().unwrap().phase("cache_load");
    std::fs::create_dir_all(&directory)?;
    let mut cache = HashMap::new();
    let before = Instant::now();
    for entry in std::fs::read_dir(&directory)? {
        let path = entry?.path();
        if path.extension().is_none_or(|ext| ext != "vxs") {
            continue;
        }
        let coordinates: Vec<_> = path
            .file_stem()
            .unwrap()
            .to_string_lossy()
            .split('_')
            .map(str::parse::<i32>)
            .collect();
        if let [Ok(level), Ok(x), Ok(y), Ok(z)] = coordinates.as_slice() {
            match std::fs::read(&path)
                .map_err(anyhow::Error::from)
                .and_then(|data| validate(&data))
            {
                Ok(hash) => {
                    cache.insert((*level as u8, *x, *y, *z), hash);
                }
                Err(error) => stats
                    .lock()
                    .unwrap()
                    .failures
                    .push(format!("cache: {error:#}")),
            }
        }
    }
    let fullview = keys(id, 0., false);
    let root = fullview[0];
    let fullkeys: HashSet<_> = fullview.into_iter().collect();
    let mut missing = fullkeys
        .iter()
        .filter(|key| !cache.contains_key(key))
        .count();
    {
        let mut s = stats.lock().unwrap();
        s.cached_before_connect = cache.len();
        s.cached_sections = cache.len();
        s.cache_decode_ms = before.elapsed().as_secs_f64() * 1000.;
        if cache.contains_key(&root) {
            s.first_coarse_ms = Some(s.cache_decode_ms);
        }
        if missing == 0 {
            s.first_detail_ms = Some(s.cache_decode_ms);
        }
    }
    let startup = Instant::now();
    loop {
        if start
            .borrow()
            .is_some_and(|time| time.elapsed().as_secs_f64() >= seconds)
        {
            break;
        }
        {
            let mut s = stats.lock().unwrap();
            s.attempts += 1;
            s.phase("handshake");
            s.request_index = 0;
            s.response_index = 0;
            s.batch_size = 0;
            s.pending_payload_bytes = 0;
            s.transport_stats = None;
        }
        let _observation = Observation(stats.clone());
        let opened = async {
            let connection = endpoint
                .connect_with(config.clone(), address, "voxy.local")?
                .await?;
            {
                let mut s = stats.lock().unwrap();
                s.transport = Some(connection.clone());
                s.handshakes += 1;
                s.phase("open_stream");
            }
            let (mut send, mut receive) = connection.open_bi().await?;
            stats.lock().unwrap().phase("write_dimension");
            send.write_u16_le(13).await?;
            send.write_all(b"voxy:pressure").await?;
            let elapsed = start
                .borrow()
                .map_or(0., |clock| clock.elapsed().as_secs_f64());
            let initial: Vec<_> = keys(id, elapsed, zoom)
                .into_iter()
                .filter(|key| !cache.contains_key(key))
                .collect();
            {
                let mut s = stats.lock().unwrap();
                s.batch_size = initial.len();
                s.phase("write_initial_missing");
            }
            // These real misses carry zero hashes; cached data waits for world association.
            requests(&mut send, &initial, &cache, &stats).await?;
            stats.lock().unwrap().phase("world_ack");
            let mut world = [0; 16];
            receive.read_exact(&mut world).await?;
            {
                let mut s = stats.lock().unwrap();
                s.world_acks += 1;
                s.phase("cache_identity");
            }
            Ok::<_, anyhow::Error>((connection, send, receive, world, initial))
        }
        .await;
        let (connection, mut send, mut receive, world, initial) = match opened {
            Ok(peer) => peer,
            Err(error) => {
                {
                    let mut s = stats.lock().unwrap();
                    s.last_failed_phase = s.phase;
                    s.phase("retry_wait");
                    s.failures.push(format!("connect: {error:#}"));
                }
                drop(_observation);
                tokio::time::sleep(Duration::from_secs(1)).await;
                continue;
            }
        };
        let identity = directory.join("world");
        if std::fs::read(&identity).is_ok_and(|old| old != world) {
            cache.clear();
            missing = fullkeys.len();
            {
                let mut s = stats.lock().unwrap();
                s.first_coarse_ms = None;
                s.first_detail_ms = None;
                s.cached_sections = 0;
            }
            for file in std::fs::read_dir(&directory)? {
                let path = file?.path();
                if path.extension().is_some_and(|ext| ext == "vxs") {
                    std::fs::remove_file(path)?;
                }
            }
        }
        atomic(&identity, &world)?;
        *current.lock().unwrap() = Some(connection.clone());
        stats.lock().unwrap().connected = true;
        let mut initial = Some(initial);
        let result = async {
            while start
                .borrow()
                .is_none_or(|clock| clock.elapsed().as_secs_f64() < seconds)
            {
                let elapsed = start
                    .borrow()
                    .map_or(0., |clock| clock.elapsed().as_secs_f64());
                let mut wanted = keys(id, elapsed, zoom);
                let queued = if let Some(mut prefix) = initial.take() {
                    let sent: HashSet<_> = prefix.iter().copied().collect();
                    let count = prefix.len();
                    prefix.extend(wanted.into_iter().filter(|key| !sent.contains(key)));
                    wanted = prefix;
                    count
                } else {
                    0
                };
                stats.lock().unwrap().cache_hits +=
                    wanted.iter().filter(|key| cache.contains_key(key)).count() as u64;
                let cycle = Instant::now();
                {
                    let mut s = stats.lock().unwrap();
                    s.phase("write_requests");
                    s.batch_size = wanted.len();
                    s.request_index = queued;
                    s.response_index = 0;
                }
                requests(&mut send, &wanted[queued..], &cache, &stats).await?;
                stats.lock().unwrap().phase("response_header");
                for key in &wanted {
                    match receive.read_u8().await? {
                        0 => stats.lock().unwrap().unavailable += 1,
                        1 => stats.lock().unwrap().unchanged += 1,
                        2 => {
                            stats.lock().unwrap().phase("response_length");
                            let length = receive.read_u32_le().await? as usize;
                            {
                                let mut s = stats.lock().unwrap();
                                s.pending_payload_bytes = length;
                                s.phase("response_payload");
                            }
                            let mut payload = vec![0; length];
                            receive.read_exact(&mut payload).await?;
                            {
                                let mut s = stats.lock().unwrap();
                                s.completed_payload_bytes += length as u64;
                                s.pending_payload_bytes = 0;
                                s.phase("validate_cache");
                            }
                            let hash = validate(&payload)?;
                            let previous = cache.insert(*key, hash);
                            if previous.is_none() && fullkeys.contains(key) {
                                missing -= 1;
                            }
                            atomic(&directory.join(filename(*key)), &payload)?;
                            let mut s = stats.lock().unwrap();
                            s.downloads += 1;
                            s.bytes += length as u64;
                            s.cached_sections = cache.len();
                            if previous.is_some_and(|old| old != hash) {
                                s.changes += 1;
                            }
                            let ms = startup.elapsed().as_secs_f64() * 1000.;
                            if s.first_coarse_ms.is_none() && cache.contains_key(&root) {
                                s.first_coarse_ms = Some(ms);
                            }
                            if s.first_detail_ms.is_none() && missing == 0 {
                                s.first_detail_ms = Some(ms);
                            }
                        }
                        status => anyhow::bail!("terrain status {status}"),
                    }
                    {
                        let mut s = stats.lock().unwrap();
                        s.response_index += 1;
                        s.phase("response_header");
                    }
                }
                {
                    let mut s = stats.lock().unwrap();
                    s.rtt_ms = connection.rtt().as_secs_f64() * 1000.;
                    s.cycle_ms.push(cycle.elapsed().as_secs_f64() * 1000.);
                    if wanted.iter().all(|key| cache.contains_key(key)) {
                        s.complete_cycles += 1;
                    } else {
                        s.incomplete_cycles += 1;
                    }
                }
                stats.lock().unwrap().phase("cycle_wait");
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
            Ok::<_, anyhow::Error>(())
        }
        .await;
        stats.lock().unwrap().connected = false;
        current.lock().unwrap().take();
        if let Err(error) = result {
            let mut s = stats.lock().unwrap();
            s.last_failed_phase = s.phase;
            s.failures.push(format!("terrain: {error:#}"));
            s.offline_hits += keys(
                id,
                start
                    .borrow()
                    .map_or(0., |clock| clock.elapsed().as_secs_f64()),
                zoom,
            )
            .iter()
            .filter(|key| cache.contains_key(key))
            .count() as u64;
            s.reconnects += 1;
        }
        connection.close(0_u32.into(), b"cycle ended");
        stats.lock().unwrap().phase("cycle_closed");
    }
    Ok(())
}
#[tokio::main]
async fn main() -> Result<()> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    ensure!(
        args.len() == 5 || args.len() == 6 && args[5] == "zoom",
        "seconds ports.json cert.der cache report.json [zoom]"
    );
    let zoom = args.len() == 6;
    let seconds: f64 = args[0].parse()?;
    let ports: Vec<String> = serde_json::from_slice(&std::fs::read(&args[1])?)?;
    let mut roots = rustls::RootCertStore::empty();
    roots.add(rustls::pki_types::CertificateDer::from(std::fs::read(
        &args[2],
    )?))?;
    let mut tls = rustls::ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth();
    tls.alpn_protocols = vec![b"voxy".to_vec()];
    let mut transport = quinn::TransportConfig::default();
    transport.congestion_controller_factory(Arc::new(quinn::congestion::BbrConfig::default()));
    transport.max_idle_timeout(Some(Duration::from_secs(300).try_into()?));
    let transport = Arc::new(transport);
    let endpoint = Endpoint::client("127.0.0.1:0".parse()?)?;
    let (start, receiver) = watch::channel(None);
    let states: Vec<_> = ports
        .iter()
        .map(|_| Arc::new(Mutex::new(Stats::default())))
        .collect();
    let mut tasks = Vec::new();
    let connections: Vec<_> = ports
        .iter()
        .map(|_| Arc::new(Mutex::new(None::<Connection>)))
        .collect();
    let mut previous = vec![None; ports.len()];
    let active = || {
        connections
            .iter()
            .filter(|connection| {
                connection
                    .lock()
                    .unwrap()
                    .as_ref()
                    .is_some_and(|peer| peer.close_reason().is_none())
            })
            .count()
    };
    for (id, port) in ports.iter().enumerate() {
        let mut actor_tls = tls.clone();
        actor_tls.resumption = rustls::client::Resumption::default();
        let mut config = quinn::ClientConfig::new(Arc::new(
            quinn::crypto::rustls::QuicClientConfig::try_from(actor_tls)?,
        ));
        config.transport_config(transport.clone());
        tasks.push(tokio::spawn(actor(
            id,
            endpoint.clone(),
            config,
            port.parse()?,
            PathBuf::from(&args[3]).join(id.to_string()),
            seconds,
            zoom,
            receiver.clone(),
            states[id].clone(),
            connections[id].clone(),
        )));
    }
    let ready = async {
        loop {
            let count = active();
            println!(
                "{}",
                serde_json::json!({"event":"setup", "active":count, "clients":ports.len(),
            "observed_ms":unix_ms(),"peer_changes":observations(&states, &mut previous)})
            );
            if count == ports.len() {
                break;
            }
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
    };
    tokio::select! { _ = ready => {}, _ = tokio::signal::ctrl_c() => {
        endpoint.close(0_u32.into(), b"operator stopped");
        std::fs::write(&args[4], serde_json::to_vec_pretty(&states.iter().map(|state| state.lock().unwrap().clone()).collect::<Vec<_>>())?)?;
        anyhow::bail!("operator stopped before all connections were live");
    } }
    println!(
        "{}",
        serde_json::json!({"event":"ready", "active":ports.len(), "view":"512 blocks", "full_view_keys":597, "zoom":zoom})
    );
    let mut command = String::new();
    tokio::io::BufReader::new(tokio::io::stdin())
        .read_line(&mut command)
        .await?;
    ensure!(
        command.trim() == "START" && active() == ports.len(),
        "all clients must be live when START is issued"
    );
    for state in &states {
        let mut s = state.lock().unwrap();
        s.cached_at_pressure = s.cached_sections;
        s.setup_requests = s.requests;
        s.setup_downloads = s.downloads;
        s.setup_bytes = s.bytes;
    }
    start.send(Some(Instant::now()))?;
    println!(
        "{}",
        serde_json::json!({"event":"started", "active":active()})
    );
    let monitor = async {
        let clock = Instant::now();
        let mut disconnected = false;
        while clock.elapsed().as_secs_f64() < seconds {
            if !disconnected && clock.elapsed().as_secs_f64() > seconds * 0.6 {
                for connection in &connections {
                    if let Some(peer) = connection.lock().unwrap().as_ref() {
                        peer.close(0_u32.into(), b"live disconnect/reconnect");
                    }
                }
                disconnected = true;
            }
            println!(
                "{}",
                serde_json::json!({"event":"pressure", "elapsed":clock.elapsed().as_secs_f64(), "active":active(),
            "observed_ms":unix_ms(),"peer_changes":observations(&states, &mut previous)})
            );
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
    };
    tokio::select! { _ = monitor => {}, _ = tokio::signal::ctrl_c() => {} }
    endpoint.close(0_u32.into(), b"live interval finished");
    for task in tasks {
        task.abort();
    }
    let report: Vec<_> = states
        .iter()
        .map(|state| state.lock().unwrap().clone())
        .collect();
    std::fs::write(&args[4], serde_json::to_vec_pretty(&report)?)?;
    Ok(())
}
