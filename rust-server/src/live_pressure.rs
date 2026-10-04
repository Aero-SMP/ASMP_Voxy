//! Live protocol consumers with local cache, packet-impaired QUIC and evidence.
use anyhow::{Context, Result, bail, ensure};
use quinn::{AsyncUdpSocket, Connection, Endpoint, RecvStream, Runtime, SendStream, UdpPoller};
use std::{
    collections::{HashMap, HashSet},
    io::{self, IoSliceMut, Write},
    net::SocketAddr,
    path::{Path, PathBuf},
    pin::Pin,
    sync::{Arc, Mutex},
    task::{Context as PollContext, Poll},
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt},
    sync::{Notify, mpsc, watch},
};
use voxy_rust_server::{
    catalog::Catalog,
    crc::crc32c,
    key::SectionKey,
    lod::SECTION_VOLUME,
    regional::{
        SectionFrame,
        wire::{self, ControlMessage, Desire, RecordDescriptor, RecordStatus, StreamingSettings},
    },
};

const CLIENTS: usize = 100;
type Catalogs = Arc<Mutex<HashMap<[u8; 32], Arc<Catalog>>>>;

/// Match the real client's authenticated background envelope on the primary UDP port.
#[derive(Debug)]
struct BackgroundSocket {
    inner: Arc<dyn AsyncUdpSocket>,
    prefix: [u8; 17],
    send: Mutex<Vec<u8>>,
    receive: Mutex<BackgroundReceive>,
}
#[derive(Debug)]
struct BackgroundReceive {
    bytes: Vec<u8>,
    packet: quinn::udp::RecvMeta,
    offset: usize,
}
impl BackgroundSocket {
    fn endpoint(token: [u8; 32]) -> Result<Endpoint> {
        let runtime = Arc::new(quinn::TokioRuntime);
        let socket = std::net::UdpSocket::bind("127.0.0.1:0")?;
        socket.set_nonblocking(true)?;
        let mut prefix = [0; 17];
        prefix[1..].copy_from_slice(&token[..16]);
        let config = quinn::EndpointConfig::default();
        let inner = runtime.wrap_udp_socket(socket)?;
        let datagram = config.get_max_udp_payload_size() as usize + prefix.len();
        let capacity = datagram * inner.max_receive_segments();
        let socket = Arc::new(Self {
            inner,
            prefix,
            send: Mutex::new(Vec::with_capacity(datagram)),
            receive: Mutex::new(BackgroundReceive {
                bytes: vec![0; capacity],
                packet: quinn::udp::RecvMeta::default(),
                offset: 0,
            }),
        });
        Ok(Endpoint::new_with_abstract_socket(
            config, None, socket, runtime,
        )?)
    }
}
impl AsyncUdpSocket for BackgroundSocket {
    fn create_io_poller(self: Arc<Self>) -> Pin<Box<dyn UdpPoller>> {
        self.inner.clone().create_io_poller()
    }
    fn try_send(&self, transmit: &quinn::udp::Transmit) -> io::Result<()> {
        if transmit.segment_size.is_some() {
            return Err(io::ErrorKind::InvalidInput.into());
        }
        let mut bytes = self.send.lock().unwrap();
        bytes.clear();
        bytes.extend_from_slice(&self.prefix);
        bytes.extend_from_slice(transmit.contents);
        self.inner.try_send(&quinn::udp::Transmit {
            contents: &bytes,
            ..transmit.clone()
        })
    }
    fn poll_recv(
        &self,
        cx: &mut PollContext<'_>,
        bufs: &mut [IoSliceMut<'_>],
        meta: &mut [quinn::udp::RecvMeta],
    ) -> Poll<io::Result<usize>> {
        let mut receive = self.receive.lock().unwrap();
        let mut count = 0;
        while count < bufs.len().min(meta.len()) {
            if receive.offset == receive.packet.len {
                let mut received = [quinn::udp::RecvMeta::default()];
                let mut input = [IoSliceMut::new(&mut receive.bytes)];
                match self.inner.poll_recv(cx, &mut input, &mut received) {
                    Poll::Ready(Ok(0)) => continue,
                    Poll::Ready(Ok(_)) => {}
                    Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                    Poll::Pending if count == 0 => return Poll::Pending,
                    Poll::Pending => break,
                }
                receive.packet = received[0];
                receive.offset = 0;
            }
            let start = receive.offset;
            let end = (start + receive.packet.stride).min(receive.packet.len);
            if end <= start {
                receive.offset = receive.packet.len;
                continue;
            }
            receive.offset = end;
            let segment = &receive.bytes[start..end];
            if segment.len() <= self.prefix.len() || !segment.starts_with(&self.prefix) {
                continue;
            }
            let payload = &segment[self.prefix.len()..];
            if payload.len() > bufs[count].len() {
                continue;
            }
            bufs[count][..payload.len()].copy_from_slice(payload);
            meta[count] = quinn::udp::RecvMeta {
                len: payload.len(),
                stride: payload.len(),
                ..receive.packet
            };
            count += 1;
        }
        Poll::Ready(Ok(count))
    }
    fn local_addr(&self) -> io::Result<SocketAddr> {
        self.inner.local_addr()
    }
    fn may_fragment(&self) -> bool {
        self.inner.may_fragment()
    }
}

fn quote(text: &str) -> String {
    let mut out = String::from("\"");
    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c < ' ' => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}
fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}
fn emit(value: &str) {
    println!("{value}");
    let _ = std::io::stdout().flush();
}

#[derive(Default)]
struct Stats {
    primary: Option<Connection>,
    background: Option<Connection>,
    background_endpoint: Option<Endpoint>,
    hello: bool,
    served: bool,
    mutation_ready: bool,
    background_ready_once: bool,
    background_attempts: u64,
    mutation_changes: u64,
    attempts: u64,
    cached_before_connect: usize,
    cache_load_us: u128,
    desired: usize,
    cached: usize,
    usable_desired: usize,
    desired_nonempty: usize,
    foreground_misses: u64,
    cache_hits: u64,
    offline_hits: u64,
    zoom_cache_ready_us: u128,
    foreground_records: u64,
    background_records: u64,
    compressed_bytes: u64,
    changes: u64,
    unchanged: u64,
    absent: u64,
    not_ready: u64,
    integrity_failures: u64,
    first_coarse_ms: Option<f64>,
    first_detail_ms: Option<f64>,
    last_generation: u64,
    cache_at_pressure: usize,
    last_error: String,
}
impl Stats {
    fn active(&self) -> bool {
        self.hello
            && self
                .primary
                .as_ref()
                .is_some_and(|peer| peer.close_reason().is_none())
    }
    fn json(&self, id: usize) -> String {
        let transport = |peer: &Option<Connection>| -> String {
            let Some(peer) = peer else {
                return "null".into();
            };
            let s = peer.stats();
            format!(
                "{{\"live\":{},\"rtt_ms\":{},\"lost_packets\":{},\"lost_bytes\":{},\"udp_tx_bytes\":{},\"udp_rx_bytes\":{},\"udp_tx_datagrams\":{},\"udp_rx_datagrams\":{}}}",
                peer.close_reason().is_none(),
                s.path.rtt.as_secs_f64() * 1000.,
                s.path.lost_packets,
                s.path.lost_bytes,
                s.udp_tx.bytes,
                s.udp_rx.bytes,
                s.udp_tx.datagrams,
                s.udp_rx.datagrams
            )
        };
        format!(
            concat!(
                "{{\"id\":{},\"active\":{},\"served\":{},\"mutation_ready\":{},\"background_ready_once\":{},\"background_attempts\":{},\"mutation_changes\":{},\"attempts\":{},",
                "\"cached_before_connect\":{},\"cache_load_us\":{},\"desired\":{},\"cached\":{},",
                "\"usable_desired\":{},\"desired_nonempty\":{},\"foreground_misses\":{},",
                "\"cache_hits\":{},\"offline_hits\":{},\"zoom_cache_ready_us\":{},",
                "\"foreground_records\":{},\"background_records\":{},\"compressed_bytes\":{},",
                "\"changes\":{},\"unchanged\":{},\"absent\":{},\"not_ready\":{},",
                "\"integrity_failures\":{},\"first_coarse_ms\":{},\"first_detail_ms\":{},",
                "\"last_generation\":{},\"cache_at_pressure\":{},\"last_error\":{},",
                "\"foreground_transport\":{},\"background_transport\":{}}}"
            ),
            id,
            self.active(),
            self.served,
            self.mutation_ready,
            self.background_ready_once,
            self.background_attempts,
            self.mutation_changes,
            self.attempts,
            self.cached_before_connect,
            self.cache_load_us,
            self.desired,
            self.cached,
            self.usable_desired,
            self.desired_nonempty,
            self.foreground_misses,
            self.cache_hits,
            self.offline_hits,
            self.zoom_cache_ready_us,
            self.foreground_records,
            self.background_records,
            self.compressed_bytes,
            self.changes,
            self.unchanged,
            self.absent,
            self.not_ready,
            self.integrity_failures,
            self.first_coarse_ms
                .map_or("null".into(), |v| v.to_string()),
            self.first_detail_ms
                .map_or("null".into(), |v| v.to_string()),
            self.last_generation,
            self.cache_at_pressure,
            quote(&self.last_error),
            transport(&self.primary),
            transport(&self.background)
        )
    }
}

#[derive(Clone)]
struct Options {
    directory: PathBuf,
    catalog_directory: PathBuf,
    dimension: String,
    settings: StreamingSettings,
    base_y: i32,
    height: i32,
    zoom_seconds: u64,
    mutation_key: SectionKey,
}
#[derive(Clone, Copy)]
struct Location {
    id: usize,
    foreground: SocketAddr,
    background: SocketAddr,
    x: i32,
    z: i32,
}
#[derive(Clone, Copy)]
struct Cached {
    descriptor: RecordDescriptor,
    nonempty: bool,
}
struct Local {
    world: [u8; 32],
    cache: HashMap<u64, Cached>,
    wanted: HashMap<u64, Desire>,
    next_ticket: u64,
    directory: PathBuf,
    catalogs: Catalogs,
    catalog_directory: PathBuf,
    first_attempt: Instant,
    mutation_key: Option<u64>,
    held_catalog: [u8; 32],
}

fn atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    let temporary = path.with_extension("pending");
    std::fs::write(&temporary, bytes)?;
    std::fs::rename(&temporary, path)?;
    Ok(())
}
fn frame(descriptor: RecordDescriptor, bytes: &[u8], catalogs: &Catalogs) -> Result<bool> {
    ensure!(
        descriptor.binding.flags & !0x8001 == 0,
        "unexpected section flags"
    );
    if descriptor.status == RecordStatus::Empty {
        ensure!(
            descriptor.binding.flags == 0x8001 && bytes.is_empty(),
            "invalid empty section"
        );
        return Ok(false);
    }
    ensure!(
        descriptor.status == RecordStatus::Data && descriptor.generation != 0,
        "cache record is neither data nor empty"
    );
    let b = descriptor.binding;
    ensure!(
        b.flags == 0x8000 && b.compressed_length as usize == bytes.len(),
        "section shape mismatch"
    );
    ensure!(
        b.canonical_length > 0 && b.canonical_length as usize <= 2 + SECTION_VOLUME * 11,
        "canonical section length exceeds exact cell representation"
    );
    ensure!(
        crc32c(bytes) == b.compressed_crc,
        "compressed section CRC mismatch"
    );
    let raw = zstd::bulk::decompress(bytes, b.canonical_length as usize)?;
    ensure!(
        raw.len() == b.canonical_length as usize
            && blake3::hash(&raw).as_bytes()[..16] == b.fingerprint,
        "canonical section length/fingerprint mismatch"
    );
    let section = SectionFrame::decode(&raw)?;
    let catalog = catalogs
        .lock()
        .unwrap()
        .get(&b.catalog_fingerprint)
        .cloned()
        .context("section arrived before its referenced catalog")?;
    ensure!(
        section
            .cells
            .iter()
            .all(|cell| (cell.block as usize) < catalog.blocks.len()
                && (cell.biome as usize) < catalog.biomes.len()),
        "section references unknown block/biome"
    );
    Ok(!section.is_empty())
}
fn accept_catalog(
    catalogs: &Catalogs,
    directory: &Path,
    fingerprint: [u8; 32],
    bytes: &[u8],
) -> Result<()> {
    ensure!(
        *blake3::hash(bytes).as_bytes() == fingerprint,
        "catalog fingerprint mismatch"
    );
    let mut held = catalogs.lock().unwrap();
    if !held.contains_key(&fingerprint) {
        let catalog = Arc::new(Catalog::decode(bytes)?);
        let path = directory.join(format!("{}.catalog", hex(&fingerprint)));
        if !path.exists() {
            atomic(&path, bytes)?;
        }
        held.insert(fingerprint, catalog);
    }
    Ok(())
}
impl Local {
    fn path(&self, key: u64) -> PathBuf {
        self.directory.join(format!("{key:016x}.section"))
    }
    fn load(options: &Options, id: usize, catalogs: Catalogs) -> Result<Self> {
        let directory = options.directory.join(id.to_string());
        std::fs::create_dir_all(&directory)?;
        let world = match std::fs::read(directory.join("world")) {
            Ok(bytes) => bytes
                .try_into()
                .map_err(|_| anyhow::anyhow!("invalid cached world identity"))?,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => [0; 32],
            Err(error) => return Err(error.into()),
        };
        let mut local = Self {
            world,
            cache: HashMap::new(),
            wanted: HashMap::new(),
            next_ticket: (id as u64 + 1) << 48,
            catalogs,
            catalog_directory: options.catalog_directory.clone(),
            first_attempt: Instant::now(),
            mutation_key: None,
            held_catalog: match std::fs::read(directory.join("catalogue")) {
                Ok(bytes) => bytes
                    .try_into()
                    .map_err(|_| anyhow::anyhow!("invalid held catalogue hint"))?,
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => [0; 32],
                Err(error) => return Err(error.into()),
            },
            directory,
        };
        for entry in std::fs::read_dir(&local.directory)? {
            let path = entry?.path();
            if path.extension().is_none_or(|ext| ext != "section") {
                continue;
            }
            let bytes = std::fs::read(&path)?;
            ensure!(
                bytes.len() >= wire::RECORD_DESCRIPTOR_BYTES,
                "truncated local cache entry"
            );
            let descriptor = RecordDescriptor::decode(&bytes[..wire::RECORD_DESCRIPTOR_BYTES])?;
            ensure!(
                local.path(descriptor.key) == path,
                "cache key/path mismatch"
            );
            let nonempty = frame(
                descriptor,
                &bytes[wire::RECORD_DESCRIPTOR_BYTES..],
                &local.catalogs,
            )?;
            local.cache.insert(
                descriptor.key,
                Cached {
                    descriptor,
                    nonempty,
                },
            );
        }
        ensure!(
            local.cache.is_empty() || local.world != [0; 32],
            "cache has no world identity"
        );
        Ok(local)
    }
    fn desired(
        &mut self,
        location: Location,
        options: &Options,
        detail: bool,
        stats: &mut Stats,
    ) -> Result<(Vec<Desire>, Vec<u64>)> {
        let before = Instant::now();
        let mut keys = Vec::new();
        for level in (0..=4).rev() {
            if level == 0 && !detail {
                continue;
            }
            let side = 16 >> level;
            let min_y = options.base_y.div_euclid(1 << level);
            let max_y = (options.base_y + options.height - 1).div_euclid(1 << level);
            for y in min_y..=max_y {
                for z in 0..side {
                    for x in 0..side {
                        keys.push(
                            SectionKey::new(
                                level,
                                location.x * side + x,
                                y,
                                location.z * side + z,
                            )?
                            .packed(),
                        );
                    }
                }
            }
        }
        let mut selected: HashSet<_> = keys.iter().copied().collect();
        if (location.x, location.z)
            == (
                options.mutation_key.x.div_euclid(16),
                options.mutation_key.z.div_euclid(16),
            )
        {
            self.mutation_key = Some(options.mutation_key.packed());
            let mut key = Some(options.mutation_key);
            while let Some(value) = key {
                if selected.insert(value.packed()) {
                    keys.push(value.packed());
                }
                key = value.parent();
            }
        }
        stats.mutation_ready = self
            .mutation_key
            .is_none_or(|key| self.cache.contains_key(&key));
        let dropped = self
            .wanted
            .keys()
            .copied()
            .filter(|key| !selected.contains(key))
            .collect::<Vec<_>>();
        for key in &dropped {
            self.wanted.remove(key);
        }
        let mut desires = Vec::new();
        stats.usable_desired = 0;
        stats.desired_nonempty = 0;
        for key in keys {
            let cached = self.cache.get(&key).copied();
            if let Some(value) = cached {
                stats.cache_hits += 1;
                stats.usable_desired += 1;
                stats.desired_nonempty += usize::from(value.nonempty);
            }
            if self.wanted.contains_key(&key) {
                continue;
            }
            self.next_ticket += 1;
            let desire = Desire {
                ticket: self.next_ticket,
                key,
                purpose: if cached.is_some() {
                    2
                } else if SectionKey::unpack(key)?.level == 0 {
                    1
                } else {
                    0
                },
                have: cached.map(|value| value.descriptor.binding),
            };
            stats.foreground_misses += u64::from(cached.is_none());
            self.wanted.insert(key, desire);
            desires.push(desire);
        }
        stats.desired = self.wanted.len();
        stats.zoom_cache_ready_us = before.elapsed().as_micros();
        Ok((desires, dropped))
    }
    fn response(
        &mut self,
        message: ControlMessage,
        background: bool,
        stats: &mut Stats,
    ) -> Result<Option<[u8; 32]>> {
        match message {
            ControlMessage::ServerHello {
                world_identity,
                background_token,
                ..
            } => {
                ensure!(
                    self.world == [0; 32] || self.world == world_identity,
                    "world identity differs from local cache"
                );
                ensure!(
                    world_identity != [0; 32] && background_token != [0; 32],
                    "invalid world/background identity"
                );
                self.world = world_identity;
                atomic(&self.directory.join("world"), &world_identity)?;
                stats.hello = true;
                return Ok(Some(background_token));
            }
            ControlMessage::Record {
                descriptor,
                compressed,
            } => {
                if background {
                    stats.background_records += 1;
                } else {
                    stats.foreground_records += 1;
                }
                let Some(wanted) = self.wanted.get(&descriptor.key).copied() else {
                    return Ok(None);
                };
                if wanted.ticket != descriptor.ticket {
                    return Ok(None);
                }
                let previous = self.cache.get(&descriptor.key).copied();
                if descriptor.binding.catalog_fingerprint != self.held_catalog
                    && self
                        .catalogs
                        .lock()
                        .unwrap()
                        .contains_key(&descriptor.binding.catalog_fingerprint)
                {
                    self.held_catalog = descriptor.binding.catalog_fingerprint;
                    atomic(&self.directory.join("catalogue"), &self.held_catalog)?;
                }
                if previous.is_some_and(|held| held.descriptor.generation > descriptor.generation) {
                    return Ok(None);
                }
                let mut stored = descriptor;
                let (body, nonempty) = match descriptor.status {
                    RecordStatus::NotReady => {
                        stats.not_ready += 1;
                        return Ok(None);
                    }
                    RecordStatus::Absent => {
                        stats.absent += 1;
                        return Ok(None);
                    }
                    RecordStatus::Empty => {
                        (Vec::new(), frame(descriptor, &compressed, &self.catalogs)?)
                    }
                    RecordStatus::Data => (compressed, false),
                    RecordStatus::Reuse => {
                        let held =
                            previous.context("server reused terrain absent from local cache")?;
                        ensure!(
                            held.descriptor.binding.same_body(descriptor.binding),
                            "reused terrain fingerprint differs"
                        );
                        let bytes = std::fs::read(self.path(descriptor.key))?;
                        stored.status = held.descriptor.status;
                        (
                            bytes[wire::RECORD_DESCRIPTOR_BYTES..].to_vec(),
                            held.nonempty,
                        )
                    }
                };
                let nonempty = if stored.status == RecordStatus::Data {
                    frame(stored, &body, &self.catalogs)?
                } else {
                    nonempty
                };
                stats.compressed_bytes +=
                    body.len() as u64 * u64::from(descriptor.status == RecordStatus::Data);
                let changed =
                    previous.is_some_and(|held| held.descriptor.binding != descriptor.binding);
                stats.changes += u64::from(changed);
                if background && changed && self.mutation_key == Some(descriptor.key) {
                    stats.mutation_changes += 1;
                }
                stats.unchanged += u64::from(descriptor.status == RecordStatus::Reuse);
                let mut bytes = Vec::with_capacity(wire::RECORD_DESCRIPTOR_BYTES + body.len());
                bytes.extend_from_slice(&stored.encode()?);
                bytes.extend_from_slice(&body);
                atomic(&self.path(descriptor.key), &bytes)?;
                self.cache.insert(
                    descriptor.key,
                    Cached {
                        descriptor: stored,
                        nonempty,
                    },
                );
                if self.mutation_key == Some(descriptor.key) {
                    stats.mutation_ready = true;
                }
                let active = self.wanted.get_mut(&descriptor.key).unwrap();
                active.have = Some(descriptor.binding);
                active.purpose = 2;
                stats.cached = self.cache.len();
                stats.last_generation = descriptor.generation;
                let key = SectionKey::unpack(descriptor.key)?;
                if key.level > 0 && nonempty {
                    stats.served = true;
                    if stats.first_coarse_ms.is_none() {
                        stats.first_coarse_ms =
                            Some(self.first_attempt.elapsed().as_secs_f64() * 1000.);
                    }
                }
                if key.level == 0 && nonempty && stats.first_detail_ms.is_none() {
                    stats.first_detail_ms =
                        Some(self.first_attempt.elapsed().as_secs_f64() * 1000.);
                }
                stats.usable_desired += usize::from(previous.is_none());
                if nonempty && previous.is_none_or(|held| !held.nonempty) {
                    stats.desired_nonempty += 1;
                }
                if !nonempty && previous.is_some_and(|held| held.nonempty) {
                    stats.desired_nonempty -= 1;
                }
            }
            ControlMessage::Error { code, message } => bail!("server error {code}: {message}"),
            ControlMessage::Shutdown { message } => bail!("server shutdown: {message}"),
            _ => bail!("unexpected client-bound message"),
        }
        Ok(None)
    }
    fn held_catalog(&self) -> [u8; 32] {
        if self
            .catalogs
            .lock()
            .unwrap()
            .contains_key(&self.held_catalog)
        {
            self.held_catalog
        } else {
            [0; 32]
        }
    }
}

enum Incoming {
    Frame(ControlMessage, bool),
    Failed(String, bool),
    IntegrityFailure(String),
}
fn reader(
    mut input: RecvStream,
    background: bool,
    sink: mpsc::Sender<Incoming>,
    catalogs: Catalogs,
    directory: PathBuf,
    ready: Arc<Notify>,
    connection: Connection,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let result: Result<()> = async {
            while let Some(message) = wire::read_control(&mut input).await? {
                if let ControlMessage::Catalog { fingerprint, canonical_length, compressed } = message {
                    let accepted: Result<()> = (|| {
                        ensure!(zstd::zstd_safe::find_frame_compressed_size(&compressed).map_err(|error| anyhow::anyhow!("invalid catalogue Zstd frame: {error:?}"))? == compressed.len(),
                            "catalogue is not one complete Zstd frame");
                        let canonical = zstd::bulk::decompress(&compressed, canonical_length as usize)?;
                        ensure!(canonical.len() == canonical_length as usize, "catalogue canonical length mismatch");
                        accept_catalog(&catalogs, &directory, fingerprint, &canonical)
                    })();
                    if let Err(error) = accepted {
                        let _ = sink.send(Incoming::IntegrityFailure(format!("{error:#}"))).await;
                        return Ok(());
                    }
                    ready.notify_waiters();
                    continue;
                }
                if let ControlMessage::Record { descriptor, .. } = &message {
                    if matches!(descriptor.status, RecordStatus::Data | RecordStatus::Reuse) {
                        loop {
                            // Keep one complete owned record; draining it frees shared receive credit.
                            let notified = ready.notified();
                            if catalogs.lock().unwrap().contains_key(&descriptor.binding.catalog_fingerprint) { break; }
                            tokio::select! { _ = notified => {}, _ = connection.closed() => bail!("catalogue wait connection closed") }
                        }
                    }
                }
                if sink
                    .send(Incoming::Frame(message, background))
                    .await
                    .is_err()
                {
                    return Ok(());
                }
            }
            bail!("reliable terrain stream ended")
        }
        .await;
        if let Err(error) = result {
            let _ = sink
                .send(Incoming::Failed(format!("{error:#}"), background))
                .await;
        }
    })
}
async fn write(send: &mut SendStream, message: ControlMessage) -> Result<()> {
    send.write_all(&wire::encode_control_record(&message)?)
        .await?;
    Ok(())
}
type BackgroundConnect = tokio::task::JoinHandle<Result<(Connection, SendStream, RecvStream)>>;
fn background_connect(
    endpoint: Endpoint,
    config: quinn::ClientConfig,
    address: SocketAddr,
    token: [u8; 32],
    held_catalog: [u8; 32],
    gate: Option<Arc<Notify>>,
) -> BackgroundConnect {
    tokio::spawn(async move {
        if let Some(gate) = gate {
            gate.notified().await;
        }
        let peer = endpoint
            .connect_with(config, address, "voxy.local")?
            .await?;
        let (mut output, input) = peer.open_bi().await?;
        output.write_u8(wire::STREAM_BACKGROUND).await?;
        output.write_all(&token).await?;
        output.write_all(&held_catalog).await?;
        Ok((peer, output, input))
    })
}
async fn session(
    location: Location,
    options: &Options,
    endpoint: &Endpoint,
    config: &quinn::ClientConfig,
    local: &mut Local,
    stats: &Arc<Mutex<Stats>>,
    start: &watch::Receiver<Option<Instant>>,
    bg_gate: &Arc<Notify>,
) -> Result<()> {
    let primary = endpoint
        .connect_with(config.clone(), location.foreground, "voxy.local")?
        .await?;
    stats.lock().unwrap().primary = Some(primary.clone());
    let (mut send, recv) = primary.open_bi().await?;
    send.write_u8(wire::STREAM_CONTROL).await?;
    local.wanted.clear();
    let (desires, _) = local.desired(location, options, true, &mut stats.lock().unwrap())?;
    write(
        &mut send,
        ControlMessage::Open {
            dimension: options.dimension.clone(),
            expected_world: local.world,
            held_catalog: local.held_catalog(),
            settings: options.settings,
            desires,
        },
    )
    .await?;
    // Each reader hands one complete record to its owner. No partially-read future is cancelled.
    let (sink, mut incoming) = mpsc::channel(1);
    let catalogs = local.catalogs.clone();
    let catalog_directory = local.catalog_directory.clone();
    let catalog_ready = Arc::new(Notify::new());
    let read_lane = |input, background, peer| {
        reader(
            input,
            background,
            sink.clone(),
            catalogs.clone(),
            catalog_directory.clone(),
            catalog_ready.clone(),
            peer,
        )
    };
    let mut readers = vec![read_lane(recv, false, primary.clone())];
    let mut lane_sends = Vec::new();
    // Match the maintained client: two coverage and six refinement streams.
    for lane in [0, 0, 1, 1, 1, 1, 1, 1] {
        let (mut output, input) = primary.open_bi().await?;
        output.write_u8(wire::STREAM_SECTION_LANE).await?;
        output.write_u8(lane).await?;
        lane_sends.push(output);
        readers.push(read_lane(input, false, primary.clone()));
    }
    let mut background: Option<Connection> = None;
    let mut connecting: Option<BackgroundConnect> = None;
    let mut background_token = None;
    let mut background_endpoint = None;
    let mut background_reader: Option<tokio::task::JoinHandle<()>> = None;
    let mut background_send = None;
    let mut zoom_phase = 0;
    let mut tick = tokio::time::interval(Duration::from_secs(1));
    let result:Result<()> = async {
        loop {
            tokio::select! {
                _ = primary.closed() => bail!("foreground connection closed"),
                event = incoming.recv() => {
                    match event.context("terrain readers stopped")? {
                        Incoming::IntegrityFailure(error) => {
                            stats.lock().unwrap().integrity_failures += 1;
                            bail!("{error}");
                        }
                        Incoming::Failed(error, false) => bail!("{error}"),
                        Incoming::Failed(error, true) => {
                            stats.lock().unwrap().last_error = format!("background: {error}");
                            if let Some(peer) = background.take() { peer.close(0u32.into(), b"background reconnect"); }
                            stats.lock().unwrap().background = None;
                            if let Some(task) = background_reader.take() { task.abort(); }
                            background_send = None;
                        }
                        Incoming::Frame(message, is_background) => {
                            let result = local.response(message, is_background, &mut stats.lock().unwrap());
                            let hello = match result {
                                Ok(value) => value,
                                Err(error) => { stats.lock().unwrap().integrity_failures += 1; return Err(error); }
                            };
                            if let Some(token) = hello {
                                emit(&format!("{{\"event\":\"background_endpoint\",\"id\":{},\"envelope\":{}}}", location.id, quote(&hex(&token[..16]))));
                                ensure!(connecting.is_none() && background.is_none(), "duplicate background session announcement");
                                background_token = Some(token);
                                let socket = BackgroundSocket::endpoint(token)?;
                                stats.lock().unwrap().background_endpoint = Some(socket.clone());
                                stats.lock().unwrap().background_attempts += 1;
                                connecting = Some(background_connect(socket.clone(),config.clone(),location.background,token,local.held_catalog(),Some(bg_gate.clone())));
                                background_endpoint = Some(socket);
                            }
                        }
                    }
                }
                value = async { connecting.as_mut().unwrap().await }, if connecting.is_some() => {
                    connecting = None;
                    match value? {
                        Ok((peer,output,input)) => {
                            background_send = Some(output); background_reader = Some(read_lane(input,true,peer.clone()));
                            let mut s=stats.lock().unwrap();s.background=Some(peer.clone());s.background_ready_once=true;
                            background=Some(peer);
                        }
                        Err(error) => stats.lock().unwrap().last_error=format!("background connect: {error:#}"),
                    }
                }
                _ = tick.tick() => {
                    if background.as_ref().is_some_and(|peer| peer.close_reason().is_some()) {
                        background = None; stats.lock().unwrap().background = None;
                        if let Some(task) = background_reader.take() { task.abort(); } background_send = None;
                    }
                    if background.is_none() && connecting.is_none() {
                        if let Some(token)=background_token {
                            stats.lock().unwrap().background_attempts+=1;
                            connecting=Some(background_connect(background_endpoint.as_ref().unwrap().clone(),config.clone(),location.background,token,local.held_catalog(),None));
                        }
                    }
                    let begun = *start.borrow();
                    if let Some(clock) = begun {
                        let phase = clock.elapsed().as_secs() / options.zoom_seconds;
                        if phase != zoom_phase {
                            zoom_phase = phase;
                            let (desires,dropped) = local.desired(location,options,phase%2==0,&mut stats.lock().unwrap())?;
                            if !dropped.is_empty() { write(&mut send,ControlMessage::Drop(dropped)).await?; }
                            if !desires.is_empty() { write(&mut send,ControlMessage::Desires(desires)).await?; }
                        }
                    }
                }
            }
        }
    }.await;
    for reader in readers {
        reader.abort();
    }
    if let Some(task) = connecting {
        task.abort();
    }
    if let Some(task) = background_reader {
        task.abort();
    }
    drop(background_send);
    primary.close(0u32.into(), b"live session finished");
    if let Some(peer) = background {
        peer.close(0u32.into(), b"live session finished");
    }
    if let Some(endpoint) = background_endpoint {
        endpoint.close(0u32.into(), b"live session finished");
        endpoint.wait_idle().await;
        stats.lock().unwrap().background_endpoint = None;
    }
    result
}

async fn actor(
    location: Location,
    options: Options,
    endpoint: Endpoint,
    config: quinn::ClientConfig,
    catalogs: Catalogs,
    stats: Arc<Mutex<Stats>>,
    start: watch::Receiver<Option<Instant>>,
    bg_gate: Arc<Notify>,
) {
    let clock = Instant::now();
    let mut local = match Local::load(&options, location.id, catalogs) {
        Ok(local) => local,
        Err(error) => {
            let mut s = stats.lock().unwrap();
            s.integrity_failures += 1;
            s.last_error = format!("cache: {error:#}");
            return;
        }
    };
    {
        let mut s = stats.lock().unwrap();
        s.cached_before_connect = local.cache.len();
        s.cached = local.cache.len();
        s.cache_load_us = clock.elapsed().as_micros();
        s.offline_hits = local.cache.len() as u64;
        s.served = local.cache.iter().any(|(key, value)| {
            value.nonempty && SectionKey::unpack(*key).is_ok_and(|key| key.level > 0)
        });
    }
    emit(&format!(
        "{{\"event\":\"local_ready\",\"id\":{},\"cached\":{},\"elapsed_us\":{}}}",
        location.id,
        local.cache.len(),
        clock.elapsed().as_micros()
    ));
    local.first_attempt = Instant::now();
    loop {
        {
            let mut s = stats.lock().unwrap();
            s.attempts += 1;
            s.hello = false;
            s.primary = None;
            s.background = None;
            s.background_ready_once = false;
        }
        if let Err(error) = session(
            location, &options, &endpoint, &config, &mut local, &stats, &start, &bg_gate,
        )
        .await
        {
            let mut s = stats.lock().unwrap();
            s.last_error = format!("{error:#}");
            s.hello = false;
            s.offline_hits += local.cache.len() as u64;
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
}

fn argument<'a>(args: &'a HashMap<String, String>, key: &str) -> Result<&'a str> {
    args.get(key)
        .map(String::as_str)
        .with_context(|| format!("missing --{key}"))
}
#[tokio::main]
async fn main() -> Result<()> {
    let mut args = HashMap::new();
    let mut input = std::env::args().skip(1);
    while let Some(key) = input.next() {
        ensure!(key.starts_with("--"), "expected named option");
        args.insert(
            key[2..].into(),
            input.next().context("missing option value")?,
        );
    }
    let links = std::fs::read_to_string(argument(&args, "links")?)?;
    let mut locations = Vec::new();
    for line in links.lines().filter(|line| !line.is_empty()) {
        let fields = line.split_whitespace().collect::<Vec<_>>();
        ensure!(fields.len() == 5, "invalid links.tsv");
        let location = Location {
            id: fields[0].parse()?,
            foreground: fields[1].parse()?,
            background: fields[2].parse()?,
            x: fields[3].parse()?,
            z: fields[4].parse()?,
        };
        ensure!(
            location.id == locations.len(),
            "client ids must be contiguous"
        );
        locations.push(location);
    }
    ensure!(
        locations.len() == CLIENTS,
        "pressure requires exactly 100 virtual clients"
    );
    let options = Options {
        directory: PathBuf::from(argument(&args, "cache")?),
        catalog_directory: PathBuf::from(argument(&args, "cache")?).join("catalogs"),
        dimension: argument(&args, "dimension")?.into(),
        settings: StreamingSettings {
            interval_millis: argument(&args, "interval-ms")?.parse()?,
            bandwidth_kbps: argument(&args, "cap-kbps")?.parse()?,
        },
        base_y: argument(&args, "base-y")?.parse()?,
        height: argument(&args, "height-sections")?.parse()?,
        zoom_seconds: argument(&args, "zoom-seconds")?.parse()?,
        mutation_key: SectionKey::new(
            0,
            argument(&args, "mutation-block-x")?
                .parse::<i32>()?
                .div_euclid(32),
            argument(&args, "mutation-block-y")?
                .parse::<i32>()?
                .div_euclid(32),
            argument(&args, "mutation-block-z")?
                .parse::<i32>()?
                .div_euclid(32),
        )?,
    };
    options.settings.validate()?;
    ensure!(
        options.height > 0 && options.zoom_seconds > 0,
        "invalid view height/zoom period"
    );
    std::fs::create_dir_all(&options.catalog_directory)?;
    let catalogs: Catalogs = Arc::new(Mutex::new(HashMap::new()));
    for entry in std::fs::read_dir(&options.catalog_directory)? {
        let path = entry?.path();
        if path.extension().is_none_or(|ext| ext != "catalog") {
            continue;
        }
        let bytes = std::fs::read(&path)?;
        let fingerprint = *blake3::hash(&bytes).as_bytes();
        ensure!(
            path.file_stem().unwrap() == hex(&fingerprint).as_str(),
            "catalog cache filename mismatch"
        );
        accept_catalog(&catalogs, &options.catalog_directory, fingerprint, &bytes)?;
    }
    let mut roots = rustls::RootCertStore::empty();
    roots.add(rustls::pki_types::CertificateDer::from(std::fs::read(
        argument(&args, "cert")?,
    )?))?;
    let mut tls = rustls::ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth();
    tls.alpn_protocols = vec![wire::ALPN.to_vec()];
    let mut transport = quinn::TransportConfig::default();
    transport.congestion_controller_factory(Arc::new(quinn::congestion::BbrConfig::default()));
    transport.max_idle_timeout(Some(Duration::from_secs(600).try_into()?));
    transport.keep_alive_interval(Some(Duration::from_secs(15)));
    let mut config = quinn::ClientConfig::new(Arc::new(
        quinn::crypto::rustls::QuicClientConfig::try_from(tls)?,
    ));
    config.transport_config(Arc::new(transport));
    let endpoint = Endpoint::client("127.0.0.1:0".parse()?)?;
    let (start, start_rx) = watch::channel(None::<Instant>);
    let states = (0..CLIENTS)
        .map(|_| Arc::new(Mutex::new(Stats::default())))
        .collect::<Vec<_>>();
    let bg_gates = (0..CLIENTS)
        .map(|_| Arc::new(Notify::new()))
        .collect::<Vec<_>>();
    let start_gate = Arc::new(Notify::new());
    let command_task = {
        let bg_gates = bg_gates.clone();
        let start_gate = start_gate.clone();
        tokio::spawn(async move {
            let mut lines = tokio::io::BufReader::new(tokio::io::stdin()).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                if line == "START" {
                    start_gate.notify_one();
                } else if let Some(id) = line
                    .strip_prefix("BG ")
                    .and_then(|id| id.parse::<usize>().ok())
                    .filter(|id| *id < CLIENTS)
                {
                    bg_gates[id].notify_one();
                }
            }
        })
    };
    let tasks = locations
        .into_iter()
        .map(|location| {
            tokio::spawn(actor(
                location,
                options.clone(),
                endpoint.clone(),
                config.clone(),
                catalogs.clone(),
                states[location.id].clone(),
                start_rx.clone(),
                bg_gates[location.id].clone(),
            ))
        })
        .collect::<Vec<_>>();
    let counts = || {
        let mut active = 0;
        let mut served = 0;
        let mut failures = 0;
        for state in &states {
            let s = state.lock().unwrap();
            active += usize::from(s.active());
            served +=
                usize::from(s.active() && s.served && s.mutation_ready && s.background_ready_once);
            failures += s.integrity_failures;
        }
        (active, served, failures)
    };
    let rows = || {
        states
            .iter()
            .enumerate()
            .map(|(id, state)| state.lock().unwrap().json(id))
            .collect::<Vec<_>>()
            .join(",")
    };
    let background_live = || {
        states
            .iter()
            .filter(|state| {
                state
                    .lock()
                    .unwrap()
                    .background
                    .as_ref()
                    .is_some_and(|peer| peer.close_reason().is_none())
            })
            .count()
    };
    let mutation_changes = || {
        states
            .iter()
            .map(|state| state.lock().unwrap().mutation_changes)
            .sum::<u64>()
    };
    let setup_timeout: u64 = argument(&args, "setup-timeout")?.parse()?;
    let duration: u64 = argument(&args, "duration")?.parse()?;
    let mut finished = None;
    let result:Result<()>=async {
        let ready=async {loop {
            let (active,served,failures)=counts();
            emit(&format!("{{\"event\":\"setup\",\"active\":{active},\"served\":{served},\"background_live\":{},\"clients\":[{}]}}",background_live(),rows()));
            ensure!(failures==0,"protocol/cache integrity validation failed");
            if active==CLIENTS&&served==CLIENTS {return Ok::<_,anyhow::Error>(());}
            tokio::time::sleep(Duration::from_secs(1)).await;
        }};
        tokio::select! {value=tokio::time::timeout(Duration::from_secs(setup_timeout),ready)=>value.context("100-client setup deadline exceeded")??,
            _=tokio::signal::ctrl_c()=>bail!("operator interrupted setup")};
        emit("{\"event\":\"ready\",\"active\":100,\"served\":100}");
        tokio::select! {_=start_gate.notified()=>{},_=tokio::signal::ctrl_c()=>bail!("operator interrupted start")};
        let (active,served,_)=counts();ensure!(active==CLIENTS&&served==CLIENTS,"100-client barrier changed before start");
        for state in &states {let mut s=state.lock().unwrap();s.cache_at_pressure=s.cached;}
        let clock=Instant::now();start.send(Some(clock))?;
        emit("{\"event\":\"started\",\"active\":100,\"served\":100}");
        let mut active_min=CLIENTS;let mut served_min=CLIENTS;
        loop {
            let (active,served,failures)=counts();active_min=active_min.min(active);served_min=served_min.min(served);
            emit(&format!("{{\"event\":\"pressure\",\"elapsed_seconds\":{},\"active\":{active},\"served\":{served},\"background_live\":{},\"mutation_changes\":{},\"clients\":[{}]}}",clock.elapsed().as_secs_f64(),background_live(),mutation_changes(),rows()));
            ensure!(failures==0,"terrain integrity validation failed during pressure");
            if clock.elapsed().as_secs()>=duration {
                finished = Some(format!("{{\"event\":\"finished\",\"active\":{active},\"served\":{served},\"active_min\":{active_min},\"served_min\":{served_min},\"background_live\":{},\"mutation_changes\":{},\"integrity_failures\":{failures}}}",background_live(),mutation_changes()));
                ensure!(active_min==CLIENTS&&served_min==CLIENTS,"not all 100 clients stayed active/served for the pressure interval");break;
            }
            tokio::select! {_=tokio::time::sleep(Duration::from_secs(1))=>{},_=tokio::signal::ctrl_c()=>bail!("operator interrupted pressure")};
        }
        Ok(())
    }.await;
    // Freeze actor ownership before taking endpoint handles. Aborting an actor alone
    // does not close its background transport, and dropping Endpoint is not close().
    for task in &tasks {
        task.abort();
    }
    for task in tasks {
        let _ = task.await;
    }
    let background_endpoints = states
        .iter()
        .filter_map(|state| state.lock().unwrap().background_endpoint.take())
        .collect::<Vec<_>>();
    endpoint.close(0u32.into(), b"live pressure finished");
    for background in &background_endpoints {
        background.close(0u32.into(), b"live pressure finished");
    }
    let mut drains = tokio::task::JoinSet::new();
    for background in background_endpoints {
        drains.spawn(async move { background.wait_idle().await });
    }
    let background_count = drains.len();
    endpoint.wait_idle().await;
    while drains.join_next().await.is_some() {}
    emit(&format!(
        "{{\"event\":\"endpoints_drained\",\"foreground_endpoints\":1,\"background_endpoints\":{background_count}}}"
    ));
    command_task.abort();
    if result.is_ok() {
        if let Some(finished) = finished {
            emit(&finished);
        }
    }
    if let Err(error) = &result {
        emit(&format!(
            "{{\"event\":\"failure\",\"error\":{}}}",
            quote(&format!("{error:#}"))
        ));
    }
    result
}
