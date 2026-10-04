use anyhow::{Context, Result, ensure};
use fastnbt::Value;
use quinn::{Connection, Endpoint};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::{collections::{HashMap, HashSet}, io::Read, path::PathBuf,
          sync::{Arc, Mutex}, time::{Duration, Instant}};
use tokio::{io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt}, sync::watch};

type Key = (u8, i32, i32, i32);
fn region(id: usize) -> (i32, i32) { ((id % 10) as i32 * 4 - 20, (id / 10) as i32 * 4 - 20) }
#[derive(Default, Clone, Serialize)]
struct Stats {
    connected: bool, attempts: u64, cached_before_connect: usize, cache_decode_ms: f64,
    cached_sections: usize, cached_at_pressure: usize,
    setup_requests: u64, setup_downloads: u64, setup_bytes: u64,
    requests: u64, downloads: u64, bytes: u64, unchanged: u64, unavailable: u64,
    changes: u64, cache_hits: u64, offline_hits: u64, complete_cycles: u64,
    incomplete_cycles: u64, first_coarse_ms: Option<f64>, first_detail_ms: Option<f64>,
    reconnects: u64, rtt_ms: f64, cycle_ms: Vec<f64>, failures: Vec<String>,
}
fn keys(id: usize, elapsed: f64, zoom: bool) -> Vec<Key> {
    let (rx, rz) = region(id); let mut wanted = Vec::new();
    for level in (1..=4).rev() {
        let side = 16 >> level;
        for z in 0..side { for x in 0..side { wanted.push((level, rx*side+x, 0, rz*side+z)); } }
    }
    if !zoom || (elapsed / 10.0) as u64 % 2 == 0 {
        let mut detail = Vec::new();
        for y in 0..2 { for z in 0..16 { for x in 0..16 { detail.push((0, rx*16+x, y, rz*16+z)); } } }
        let camera = ((elapsed*0.8).sin()*1.5+1.5, (elapsed*0.3).cos()*1.5+1.5);
        detail.sort_by_key(|key| (((key.1-rx*16) as f64-camera.0).powi(2)*100.0
            + ((key.3-rz*16) as f64-camera.1).powi(2)*100.0) as i32);
        wanted.extend(detail);
    }
    wanted
}
fn validate(bytes: &[u8]) -> Result<[u8;32]> {
    let mut raw = Vec::new(); flate2::read::ZlibDecoder::new(bytes).read_to_end(&mut raw)?;
    let tag: HashMap<String, Value> = fastnbt::from_bytes(&raw)?;
    let Value::List(palette) = tag.get("palette").context("palette")? else { anyhow::bail!("palette type"); };
    ensure!(!palette.is_empty() && palette.len() <= 39304, "palette size");
    for entry in palette {
        let Value::Compound(item) = entry else { anyhow::bail!("palette entry"); };
        let Some(Value::Compound(state)) = item.get("state") else { anyhow::bail!("state"); };
        ensure!(matches!(state.get("Name"), Some(Value::String(_))) && matches!(item.get("biome"), Some(Value::String(_))) && matches!(item.get("light"), Some(Value::Byte(_))), "palette fields");
    }
    let Some(Value::LongArray(data)) = tag.get("data") else { anyhow::bail!("data"); };
    let bits = (usize::BITS - (palette.len()-1).leading_zeros()).max(1);
    let per_word = 64 / bits as usize;
    ensure!(data.len() == 39304_usize.div_ceil(per_word), "packed length");
    for i in 0..39304 { ensure!(((data[i/per_word] as u64 >> ((i%per_word)*bits as usize)) & ((1_u64<<bits)-1)) < palette.len() as u64, "palette index"); }
    ensure!(matches!(tag.get("children"), Some(Value::Byte(_))) && matches!(tag.get("entities"), Some(Value::List(_))), "section fields");
    Ok(Sha256::digest(bytes).into())
}
fn filename(key: Key) -> String { format!("{}_{}_{}_{}.vxs", key.0, key.1, key.2, key.3) }
fn atomic(path: &std::path::Path, bytes: &[u8]) -> Result<()> {
    let temporary = path.with_extension("part"); std::fs::write(&temporary, bytes)?; std::fs::rename(temporary, path)?; Ok(())
}
async fn actor(id: usize, endpoint: Endpoint, address: std::net::SocketAddr, directory: PathBuf,
               seconds: f64, zoom: bool, start: watch::Receiver<Option<Instant>>, stats: Arc<Mutex<Stats>>,
               current: Arc<Mutex<Option<Connection>>>) -> Result<()> {
    std::fs::create_dir_all(&directory)?;
    let mut cache = HashMap::new(); let before = Instant::now();
    for entry in std::fs::read_dir(&directory)? {
        let path = entry?.path(); if path.extension().is_none_or(|ext| ext != "vxs") { continue; }
        let coordinates: Vec<_> = path.file_stem().unwrap().to_string_lossy().split('_').map(str::parse::<i32>).collect();
        if let [Ok(level), Ok(x), Ok(y), Ok(z)] = coordinates.as_slice() {
            match std::fs::read(&path).map_err(anyhow::Error::from).and_then(|data| validate(&data)) {
                Ok(hash) => { cache.insert((*level as u8, *x, *y, *z), hash); }
                Err(error) => stats.lock().unwrap().failures.push(format!("cache: {error:#}")),
            }
        }
    }
    let fullview = keys(id, 0., false); let root = fullview[0]; let fullkeys: HashSet<_> = fullview.into_iter().collect();
    let mut missing = fullkeys.iter().filter(|key| !cache.contains_key(key)).count();
    { let mut s = stats.lock().unwrap(); s.cached_before_connect = cache.len(); s.cached_sections = cache.len(); s.cache_decode_ms = before.elapsed().as_secs_f64()*1000.;
      if cache.contains_key(&root) { s.first_coarse_ms = Some(s.cache_decode_ms); }
      if missing == 0 { s.first_detail_ms = Some(s.cache_decode_ms); } }
    let startup = Instant::now();
    loop {
        if start.borrow().is_some_and(|time| time.elapsed().as_secs_f64() >= seconds) { break; }
        stats.lock().unwrap().attempts += 1;
        let opened = async {
            let connection = endpoint.connect(address, "voxy.local")?.await?;
            let (mut send, mut receive) = connection.open_bi().await?;
            send.write_u16_le(13).await?; send.write_all(b"voxy:pressure").await?;
            let mut world = [0;16]; receive.read_exact(&mut world).await?;
            Ok::<_, anyhow::Error>((connection, send, receive, world))
        }.await;
        let (connection, mut send, mut receive, world) = match opened {
            Ok(peer) => peer,
            Err(error) => { stats.lock().unwrap().failures.push(format!("connect: {error:#}")); tokio::time::sleep(Duration::from_secs(1)).await; continue; }
        };
        let identity = directory.join("world");
        if std::fs::read(&identity).is_ok_and(|old| old != world) {
            cache.clear(); missing = fullkeys.len();
            { let mut s = stats.lock().unwrap(); s.first_coarse_ms = None; s.first_detail_ms = None; s.cached_sections = 0; }
            for file in std::fs::read_dir(&directory)? { let path = file?.path(); if path.extension().is_some_and(|ext| ext == "vxs") { std::fs::remove_file(path)?; } }
        }
        atomic(&identity, &world)?;
        *current.lock().unwrap() = Some(connection.clone());
        stats.lock().unwrap().connected = true;
        let result = async {
            while start.borrow().is_none_or(|clock| clock.elapsed().as_secs_f64() < seconds) {
                let elapsed = start.borrow().map_or(0., |clock| clock.elapsed().as_secs_f64()); let wanted = keys(id, elapsed, zoom);
                stats.lock().unwrap().cache_hits += wanted.iter().filter(|key| cache.contains_key(key)).count() as u64;
                let cycle = Instant::now();
                for key in &wanted {
                    send.write_u8(0).await?; send.write_u8(key.0).await?;
                    send.write_i32_le(key.1).await?; send.write_i32_le(key.2).await?; send.write_i32_le(key.3).await?;
                    send.write_all(cache.get(key).unwrap_or(&[0;32])).await?;
                    stats.lock().unwrap().requests += 1;
                }
                for key in &wanted {
                    match receive.read_u8().await? {
                        0 => stats.lock().unwrap().unavailable += 1,
                        1 => stats.lock().unwrap().unchanged += 1,
                        2 => {
                            let length = receive.read_u32_le().await? as usize; let mut payload = vec![0;length]; receive.read_exact(&mut payload).await?;
                            let hash = validate(&payload)?; let previous = cache.insert(*key, hash);
                            if previous.is_none() && fullkeys.contains(key) { missing -= 1; }
                            atomic(&directory.join(filename(*key)), &payload)?;
                            let mut s = stats.lock().unwrap(); s.downloads += 1; s.bytes += length as u64; s.cached_sections = cache.len();
                            if previous.is_some_and(|old| old != hash) { s.changes += 1; }
                            let ms = startup.elapsed().as_secs_f64()*1000.;
                            if s.first_coarse_ms.is_none() && cache.contains_key(&root) { s.first_coarse_ms = Some(ms); }
                            if s.first_detail_ms.is_none() && missing == 0 { s.first_detail_ms = Some(ms); }
                        }
                        status => anyhow::bail!("terrain status {status}"),
                    }
                }
                { let mut s = stats.lock().unwrap(); s.rtt_ms = connection.rtt().as_secs_f64()*1000.; s.cycle_ms.push(cycle.elapsed().as_secs_f64()*1000.);
                  if wanted.iter().all(|key| cache.contains_key(key)) { s.complete_cycles += 1; } else { s.incomplete_cycles += 1; } }
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
            Ok::<_, anyhow::Error>(())
        }.await;
        stats.lock().unwrap().connected = false; current.lock().unwrap().take();
        if let Err(error) = result {
            let mut s = stats.lock().unwrap(); s.failures.push(format!("terrain: {error:#}"));
            s.offline_hits += keys(id, start.borrow().map_or(0., |clock| clock.elapsed().as_secs_f64()), zoom).iter().filter(|key| cache.contains_key(key)).count() as u64; s.reconnects += 1;
        }
        connection.close(0_u32.into(), b"cycle ended");
    }
    Ok(())
}
#[tokio::main]
async fn main() -> Result<()> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    ensure!(args.len() == 5 || args.len() == 6 && args[5] == "zoom", "seconds ports.json cert.der cache report.json [zoom]");
    let zoom = args.len() == 6;
    let seconds: f64 = args[0].parse()?; let ports: Vec<String> = serde_json::from_slice(&std::fs::read(&args[1])?)?;
    let mut roots = rustls::RootCertStore::empty(); roots.add(rustls::pki_types::CertificateDer::from(std::fs::read(&args[2])?))?;
    let mut tls = rustls::ClientConfig::builder().with_root_certificates(roots).with_no_client_auth(); tls.alpn_protocols = vec![b"voxy".to_vec()];
    let mut config = quinn::ClientConfig::new(Arc::new(quinn::crypto::rustls::QuicClientConfig::try_from(tls)?));
    let mut transport = quinn::TransportConfig::default(); transport.congestion_controller_factory(Arc::new(quinn::congestion::BbrConfig::default())); transport.keep_alive_interval(Some(Duration::from_secs(15))); transport.max_idle_timeout(Some(Duration::from_secs(300).try_into()?)); config.transport_config(Arc::new(transport));
    let mut endpoint = Endpoint::client("127.0.0.1:0".parse()?)?; endpoint.set_default_client_config(config);
    let (start, receiver) = watch::channel(None); let states: Vec<_> = ports.iter().map(|_| Arc::new(Mutex::new(Stats::default()))).collect(); let mut tasks = Vec::new();
    let connections: Vec<_> = ports.iter().map(|_| Arc::new(Mutex::new(None::<Connection>))).collect();
    let active = || connections.iter().filter(|connection| connection.lock().unwrap().as_ref().is_some_and(|peer| peer.close_reason().is_none())).count();
    for (id, port) in ports.iter().enumerate() { tasks.push(tokio::spawn(actor(id, endpoint.clone(), port.parse()?, PathBuf::from(&args[3]).join(id.to_string()), seconds, zoom, receiver.clone(), states[id].clone(), connections[id].clone()))); }
    let ready = async { loop {
        let count = active();
        println!("{}", serde_json::json!({"event":"setup", "active":count, "clients":ports.len()}));
        if count == ports.len() { break; } tokio::time::sleep(Duration::from_secs(1)).await;
    }};
    tokio::select! { _ = ready => {}, _ = tokio::signal::ctrl_c() => {
        endpoint.close(0_u32.into(), b"operator stopped");
        std::fs::write(&args[4], serde_json::to_vec_pretty(&states.iter().map(|state| state.lock().unwrap().clone()).collect::<Vec<_>>())?)?;
        anyhow::bail!("operator stopped before all connections were live");
    } }
    println!("{}", serde_json::json!({"event":"ready", "active":ports.len(), "view":"512 blocks", "full_view_keys":597, "zoom":zoom}));
    let mut command = String::new(); tokio::io::BufReader::new(tokio::io::stdin()).read_line(&mut command).await?;
    ensure!(command.trim() == "START" && active() == ports.len(), "all clients must be live when START is issued");
    for state in &states { let mut s = state.lock().unwrap(); s.cached_at_pressure = s.cached_sections;
        s.setup_requests = s.requests; s.setup_downloads = s.downloads; s.setup_bytes = s.bytes; }
    start.send(Some(Instant::now()))?;
    println!("{}", serde_json::json!({"event":"started", "active":active()}));
    let monitor = async { let clock = Instant::now(); let mut disconnected = false; while clock.elapsed().as_secs_f64() < seconds {
        if !disconnected && clock.elapsed().as_secs_f64() > seconds*0.6 {
            for connection in &connections { if let Some(peer) = connection.lock().unwrap().as_ref() { peer.close(0_u32.into(), b"live disconnect/reconnect"); } }
            disconnected = true;
        }
        println!("{}", serde_json::json!({"event":"pressure", "elapsed":clock.elapsed().as_secs_f64(), "active":active()})); tokio::time::sleep(Duration::from_secs(1)).await;
    }};
    tokio::select! { _ = monitor => {}, _ = tokio::signal::ctrl_c() => {} }
    endpoint.close(0_u32.into(), b"live interval finished");
    for task in tasks { task.abort(); }
    let report: Vec<_> = states.iter().map(|state| state.lock().unwrap().clone()).collect(); std::fs::write(&args[4], serde_json::to_vec_pretty(&report)?)?;
    Ok(())
}
