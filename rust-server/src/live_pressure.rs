//! Independent current-protocol Rust clients with run-owned opaque UDP impairment.
//! Registration and the external hard deadline belong to the debug Java/operator owners.
#[path = "pressure_relay.rs"]
mod relay;
use anyhow::{Context, Result, bail, ensure};
use quinn::{AsyncUdpSocket, Connection, Endpoint, RecvStream, Runtime, SendStream, UdpPoller};
use std::{
    cmp::Reverse,
    collections::{BinaryHeap, HashMap, HashSet},
    io::{self, IoSliceMut, Write},
    net::SocketAddr,
    path::{Path, PathBuf},
    pin::Pin,
    sync::{Arc, Mutex},
    task::{Context as PollContext, Poll},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::{
    io::AsyncWriteExt,
    sync::{Notify, mpsc, watch},
};
use voxy_rust_server::{
    catalog::Catalog,
    crc::crc32c,
    key::SectionKey,
    lod::SECTION_VOLUME,
    regional::{
        SectionFrame,
        wire::{
            self, ControlMessage, Desire, RecordDescriptor, RecordStatus, ScopedDesire, ScopedKey,
            StreamingSettings,
        },
    },
};
type Catalogs = Arc<Mutex<HashMap<[u8; 32], Arc<Catalog>>>>;

/// Use the authenticated Minecraft-session envelope from the first QUIC handshake.
#[derive(Debug)]
struct RoutedSocket {
    inner: Arc<dyn AsyncUdpSocket>,
    prefix: [u8; 17],
    send: Mutex<Vec<u8>>,
    receive: Mutex<RoutedReceive>,
}
#[derive(Debug)]
struct RoutedReceive {
    bytes: Vec<u8>,
    packet: quinn::udp::RecvMeta,
    offset: usize,
}
impl RoutedSocket {
    fn endpoint(token: [u8; 32], server: SocketAddr) -> Result<Endpoint> {
        let runtime = Arc::new(quinn::TokioRuntime);
        let socket = std::net::UdpSocket::bind(if server.is_ipv6() {
            "[::]:0"
        } else {
            "0.0.0.0:0"
        })?;
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
            receive: Mutex::new(RoutedReceive {
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
impl AsyncUdpSocket for RoutedSocket {
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

fn argument<'a>(args: &'a HashMap<String, String>, key: &str) -> Result<&'a str> {
    args.get(key)
        .map(String::as_str)
        .with_context(|| format!("missing --{key}"))
}
fn number<T: std::str::FromStr>(
    args: &HashMap<String, String>,
    key: &str,
    default: &str,
) -> Result<T>
where
    T::Err: std::fmt::Display,
{
    args.get(key)
        .map(String::as_str)
        .unwrap_or(default)
        .parse()
        .map_err(|error| anyhow::anyhow!("invalid --{key}: {error}"))
}
fn token(value: &str) -> Result<[u8; 32]> {
    ensure!(
        value.len() == 64 && value.is_ascii(),
        "route token must contain exactly 64 hex characters"
    );
    let mut bytes = [0; 32];
    for (index, byte) in bytes.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&value[index * 2..index * 2 + 2], 16)?;
    }
    ensure!(bytes != [0; 32], "route token is zero");
    Ok(bytes)
}
fn atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    std::fs::create_dir_all(path.parent().context("cache path has no parent")?)?;
    let temporary = path.with_extension("pending");
    std::fs::write(&temporary, bytes)?;
    std::fs::rename(&temporary, path)?;
    Ok(())
}
fn validate(
    descriptor: RecordDescriptor,
    bytes: &[u8],
    catalogs: &Catalogs,
    scratch: &mut Scratch,
) -> Result<()> {
    let binding = descriptor.binding;
    match descriptor.status {
        RecordStatus::Absent => ensure!(
            binding.flags == 0 && bytes.is_empty(),
            "invalid absent record"
        ),
        RecordStatus::Empty => ensure!(
            binding.flags == 0x8001 && bytes.is_empty() && descriptor.generation != 0,
            "invalid empty record"
        ),
        RecordStatus::Data => {
            ensure!(
                descriptor.generation != 0
                    && binding.flags == 0x8000
                    && binding.compressed_length as usize == bytes.len(),
                "invalid data record shape"
            );
            ensure!(
                binding.canonical_length > 0
                    && binding.canonical_length as usize <= 2 + SECTION_VOLUME * 11,
                "invalid canonical extent"
            );
            ensure!(
                crc32c(bytes) == binding.compressed_crc,
                "compressed CRC mismatch"
            );
            ensure!(
                zstd::zstd_safe::find_frame_compressed_size(bytes)
                    .map_err(|error| anyhow::anyhow!("invalid Zstd frame: {error:?}"))?
                    == bytes.len(),
                "trailing compressed data"
            );
            scratch.raw.resize(binding.canonical_length as usize, 0);
            let length = scratch
                .decoder
                .decompress_to_buffer(bytes, &mut scratch.raw[..])?;
            let raw = &scratch.raw[..length];
            ensure!(
                raw.len() == binding.canonical_length as usize
                    && blake3::hash(raw).as_bytes()[..16] == binding.fingerprint,
                "canonical integrity mismatch"
            );
            let section = SectionFrame::decode(raw)?;
            let catalog = catalogs
                .lock()
                .unwrap()
                .get(&binding.catalog_fingerprint)
                .cloned()
                .context("section catalogue is unavailable")?;
            ensure!(
                section
                    .cells
                    .iter()
                    .all(|cell| (cell.block as usize) < catalog.blocks.len()
                        && (cell.biome as usize) < catalog.biomes.len()),
                "unknown block or biome"
            );
        }
        _ => bail!("uncommitted status cannot enter cache"),
    }
    Ok(())
}
fn accept_catalog(
    catalogs: &Catalogs,
    directory: &Path,
    fingerprint: [u8; 32],
    raw: &[u8],
) -> Result<()> {
    ensure!(
        *blake3::hash(raw).as_bytes() == fingerprint,
        "catalogue integrity mismatch"
    );
    if catalogs.lock().unwrap().contains_key(&fingerprint) {
        return Ok(());
    }
    let catalog = Arc::new(Catalog::decode(raw)?);
    atomic(
        &directory.join(format!("{}.catalog", hex(&fingerprint))),
        raw,
    )?;
    catalogs
        .lock()
        .unwrap()
        .entry(fingerprint)
        .or_insert(catalog);
    Ok(())
}
fn rank(key: SectionKey, x: i32, z: i32) -> Result<u64> {
    let side = 32_i64 << key.level;
    let min_x = i64::from(key.x) * side;
    let min_z = i64::from(key.z) * side;
    let dx = (min_x - i64::from(x))
        .max(i64::from(x) - min_x - side)
        .max(0);
    let dz = (min_z - i64::from(z))
        .max(i64::from(z) - min_z - side)
        .max(0);
    let distance = dx
        .checked_mul(dx)
        .and_then(|value| {
            dz.checked_mul(dz)
                .and_then(|other| value.checked_add(other))
        })
        .context("distance overflow")?;
    let normalized = u64::try_from(distance)?
        .checked_mul(1 << (2 * (4 - key.level)))
        .context("rank shift overflow")?;
    normalized.checked_add(512 * 512).context("rank overflow")
}
async fn write(send: &mut SendStream, message: ControlMessage) -> Result<()> {
    send.write_all(&wire::encode_control_record(&message)?)
        .await?;
    Ok(())
}
struct Scratch {
    decoder: zstd::bulk::Decompressor<'static>,
    raw: Vec<u8>,
}
impl Scratch {
    fn new() -> Result<Self> {
        Ok(Self {
            decoder: zstd::bulk::Decompressor::new()?,
            raw: Vec::new(),
        })
    }
}

fn reader(
    mut input: RecvStream,
    connection: Connection,
    sink: mpsc::Sender<Result<ControlMessage>>,
    catalogs: Catalogs,
    ready: Arc<Notify>,
    gate: Option<watch::Receiver<bool>>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let result: Result<()> = async {
            if let Some(mut authority) = gate { authority.wait_for(|value| *value).await?; }
            while let Some(message) = wire::read_control(&mut input).await? {
                if let ControlMessage::Record { descriptor, .. } = &message {
                    if matches!(descriptor.status, RecordStatus::Data | RecordStatus::Reuse) && descriptor.binding.has_body() {
                        loop {
                            let notified = ready.notified();
                            if catalogs.lock().unwrap().contains_key(&descriptor.binding.catalog_fingerprint) { break; }
                            tokio::select! { _ = notified => {}, _ = connection.closed() => bail!("catalogue wait interrupted") }
                        }
                    }
                }
                if sink.send(Ok(message)).await.is_err() { return Ok(()); }
            }
            bail!("reliable stream ended")
        }.await;
        if let Err(error) = result {
            let _ = sink.send(Err(error)).await;
        }
    })
}

#[derive(Clone)]
struct Anchor {
    index: usize,
    dimension: String,
    x: i32,
    y: i32,
    z: i32,
}
#[derive(Default)]
struct ClientStats {
    authenticated: bool,
    closed: bool,
    records: u64,
    data: u64,
    empty: u64,
    absent: u64,
    reuse: u64,
    not_ready: u64,
    payload_bytes: u64,
    cache_hits: u64,
    inventory: u64,
    pending: usize,
    requested: u64,
    decode_ns: u64,
    persist_ns: u64,
    worker_queue_ns: u64,
    resume_ns: u64,
    validation_ns: u64,
    max_completion_ns: u64,
    completion_ns: u64,
    completion_histogram: [u64; 32],
    first_coarse_ns: u64,
    first_detail_ns: u64,
    last_progress_ns: u64,
    event_late_ns: u64,
    max_pending_ns: u64,
    phase_records: [u64; 3],
    phase_missing: [u64; 3],
    phase_coarse_records: [u64; 3],
    phase_detail_records: [u64; 3],
    phase_payload_bytes: [u64; 3],
    missing_pending: usize,
    plateau_missing_samples: u64,
    plateau_idle_samples: u64,
    trailing_records: u64,
    frontier_nodes: usize,
    validated_lanes: u8,
    failures: u64,
    failure: Option<String>,
    setup_rtt_ns: u64,
    rtt_ns: u64,
    cwnd: u64,
    lost_packets: u64,
    lost_bytes: u64,
    sent_packets: u64,
    udp_tx: u64,
    udp_rx: u64,
}
struct ClientState {
    stats: Mutex<ClientStats>,
    relay: Mutex<Option<Arc<Mutex<relay::Stats>>>>,
    connection: Mutex<Option<Connection>>,
}
#[derive(Clone)]
struct Run {
    id: Arc<String>,
    server: SocketAddr,
    tls: quinn::ClientConfig,
    directory: Arc<PathBuf>,
    settings: StreamingSettings,
    profile: relay::Profile,
    phase: watch::Receiver<Option<Instant>>,
    stop: watch::Receiver<bool>,
    started: Instant,
    saved_regions: Option<Arc<HashSet<(i32, i32)>>>,
    cleanup_deadline: Instant,
    plateau_seconds: u64,
}
struct Pending {
    ticket: u64,
    sent: Instant,
    key: SectionKey,
    missing: bool,
}

/// Saved regional roots form a lazy spatial hierarchy. Each selection pops one
/// heap node and adds at most eight children, independent of empty border area.
struct Frontier {
    queue: BinaryHeap<Reverse<(u64, u8, u64)>>,
    regions: HashSet<(i32, i32)>,
    x: i32,
    z: i32,
}
impl Frontier {
    fn new(anchor: &Anchor) -> Self {
        Self {
            queue: BinaryHeap::new(),
            regions: HashSet::new(),
            x: anchor.x,
            z: anchor.z,
        }
    }
    fn clear(&mut self) {
        self.queue.clear();
        self.regions.clear();
    }
    fn offer(&mut self, key: SectionKey) -> Result<()> {
        self.queue.push(Reverse((
            rank(key, self.x, self.z)?,
            4 - key.level,
            key.packed(),
        )));
        Ok(())
    }
    fn add_region(
        &mut self,
        region: (i32, i32),
        metadata: &wire::DimensionMetadata,
        saved: &HashMap<(i32, i32), [u64; 16]>,
    ) -> Result<()> {
        if !self.regions.insert(region) {
            return Ok(());
        }
        let minimum = (i64::from(metadata.min_section_y) * 16).div_euclid(512);
        let maximum =
            ((i64::from(metadata.min_section_y) + i64::from(metadata.section_count)) * 16 - 1)
                .div_euclid(512);
        for y in minimum..=maximum {
            let key = SectionKey::new(4, region.0, y.try_into()?, region.1)?;
            if eligible(key, metadata, saved) {
                self.offer(key)?;
            }
        }
        Ok(())
    }
    fn move_to(&mut self, x: i32, z: i32) -> Result<()> {
        self.x = x;
        self.z = z;
        let mut nodes = std::mem::take(&mut self.queue).into_vec();
        for Reverse((priority, _, packed)) in &mut nodes {
            *priority = rank(SectionKey::unpack(*packed)?, x, z)?;
        }
        self.queue = BinaryHeap::from(nodes);
        Ok(())
    }
    fn next(
        &mut self,
        metadata: &wire::DimensionMetadata,
        saved: &HashMap<(i32, i32), [u64; 16]>,
    ) -> Result<Option<SectionKey>> {
        let Some(Reverse((_, _, packed))) = self.queue.pop() else {
            return Ok(None);
        };
        let key = SectionKey::unpack(packed)?;
        if key.level > 0 {
            let lower = i64::from(metadata.min_section_y) * 16;
            let upper =
                (i64::from(metadata.min_section_y) + i64::from(metadata.section_count)) * 16;
            for x in 0..2 {
                for y in 0..2 {
                    for z in 0..2 {
                        let child = SectionKey::new(
                            key.level - 1,
                            key.x * 2 + x,
                            key.y * 2 + y,
                            key.z * 2 + z,
                        )?;
                        let side = 32_i64 << child.level;
                        if i64::from(child.y) * side < upper
                            && i64::from(child.y + 1) * side > lower
                            && eligible(child, metadata, saved)
                        {
                            self.offer(child)?;
                        }
                    }
                }
            }
        }
        Ok(Some(key))
    }
}
fn plateau_phase(start: Option<Instant>, duration: u64) -> Option<usize> {
    start
        .filter(|start| start.elapsed() < Duration::from_secs(duration))
        .map(|start| {
            let seconds = start.elapsed().as_secs();
            if seconds >= 180 {
                2
            } else if seconds >= 90 {
                1
            } else {
                0
            }
        })
}
fn eligible(
    key: SectionKey,
    metadata: &wire::DimensionMetadata,
    saved: &HashMap<(i32, i32), [u64; 16]>,
) -> bool {
    let side = 32_i64 << key.level;
    let x = i64::from(key.x) * side;
    let z = i64::from(key.z) * side;
    let radius = if metadata.custom_border {
        metadata.size / 2.0
    } else {
        512.0
    };
    if x as f64 >= metadata.center_x + radius
        || (x + side) as f64 <= metadata.center_x - radius
        || z as f64 >= metadata.center_z + radius
        || (z + side) as f64 <= metadata.center_z - radius
    {
        return false;
    }
    let region = (x.div_euclid(512) as i32, z.div_euclid(512) as i32);
    let Some(bits) = saved.get(&region) else {
        return false;
    };
    let chunks = 2 << key.level;
    let cx = x.rem_euclid(512) as usize / 16;
    let cz = z.rem_euclid(512) as usize / 16;
    let mask = (1_u64 << chunks) - 1;
    (cz..cz + chunks).any(|row| {
        let offset = cx + row * 32;
        bits[offset / 64] & (mask << (offset % 64)) != 0
    })
}
fn age_ns(start: Instant) -> u64 {
    start.elapsed().as_nanos().min(u64::MAX as u128) as u64
}
fn completion_bucket(nanos: u64) -> usize {
    // Log2 microsecond upper bounds; the final bucket includes overflow.
    (64 - nanos.div_ceil(1000).saturating_sub(1).leading_zeros() as usize).min(31)
}
fn worker_record(
    directory: &Path,
    world: [u8; 32],
    mut descriptor: RecordDescriptor,
    compressed: Vec<u8>,
    previous: Option<RecordDescriptor>,
    catalogs: &Catalogs,
    scratch: &mut Scratch,
) -> Result<(RecordDescriptor, u64, u64)> {
    let began = Instant::now();
    let payload = if descriptor.status == RecordStatus::Reuse {
        let previous = previous.context("REUSE without committed cache")?;
        ensure!(
            descriptor.binding.same_body(previous.binding),
            "REUSE body mismatch"
        );
        descriptor.status = previous.status;
        let bytes = std::fs::read(
            directory
                .join(hex(&world))
                .join(format!("{:016x}.section", descriptor.key)),
        )?;
        ensure!(
            bytes.len() >= wire::RECORD_DESCRIPTOR_BYTES,
            "truncated REUSE cache"
        );
        let held = RecordDescriptor::decode(&bytes[..wire::RECORD_DESCRIPTOR_BYTES])?;
        ensure!(
            held.key == descriptor.key && held.binding.same_body(previous.binding),
            "REUSE cache binding mismatch"
        );
        bytes[wire::RECORD_DESCRIPTOR_BYTES..].to_vec()
    } else {
        compressed
    };
    validate(descriptor, &payload, catalogs, scratch)?;
    let decode_ns = age_ns(began);
    let persist = Instant::now();
    let mut bytes = descriptor.encode()?.to_vec();
    bytes.extend_from_slice(&payload);
    atomic(
        &directory
            .join(hex(&world))
            .join(format!("{:016x}.section", descriptor.key)),
        &bytes,
    )?;
    Ok((descriptor, decode_ns, age_ns(persist)))
}

async fn client(run: Run, anchor: Anchor, route: [u8; 32], owned: Arc<ClientState>) -> Result<()> {
    let connected = Instant::now();
    let profile = relay::Profile {
        seed: run.profile.seed
            ^ (anchor.index as u64 + 1)
                .wrapping_mul(0x9e3779b97f4a7c15_u64)
                .wrapping_add(1),
        ..run.profile
    };
    let (relay_stop, relay_stopping) = watch::channel(false);
    let relay = relay::Relay::start(run.server, profile, relay_stopping).await?;
    *owned.relay.lock().unwrap() = Some(relay.stats.clone());
    emit(&format!(
        "{{\"event\":\"relay_bound\",\"run\":{},\"client\":{},\"front\":{},\"backend\":{},\"front_inode\":{},\"back_inode\":{}}}",
        quote(&run.id),
        anchor.index,
        quote(&relay.local.to_string()),
        quote(&relay.backend.to_string()),
        relay.front_inode,
        relay.back_inode
    ));
    let endpoint = RoutedSocket::endpoint(route, relay.local)?;
    let directory = run.directory.join(format!("client-{:03}", anchor.index));
    let catalog_directory = directory.join("catalogues");
    std::fs::create_dir_all(&catalog_directory)?;
    let catalogs: Catalogs = Arc::new(Mutex::new(HashMap::new()));
    let scratch = Arc::new(Mutex::new(Scratch::new()?));
    let mut readers = Vec::new();
    let mut connection_owned = None;
    let mut opening_stop = run.stop.clone();
    let operation = async {
        let connection = endpoint
            .connect_with(run.tls.clone(), relay.local, "voxy.local")?
            .await?;
        let setup_rtt_ns = connection.stats().path.rtt.as_nanos().min(u64::MAX as u128) as u64;
        {
            let mut stats = owned.stats.lock().unwrap();
            stats.setup_rtt_ns = setup_rtt_ns;
            stats.rtt_ns = setup_rtt_ns;
        }
        emit(&format!(
            "{{\"event\":\"client_tls_ready\",\"run\":{},\"client\":{},\"elapsed_ns\":{},\"setup_rtt_ns\":{setup_rtt_ns}}}",
            quote(&run.id),
            anchor.index,
            age_ns(run.started)
        ));
        connection_owned = Some(connection.clone());
        *owned.connection.lock().unwrap() = Some(connection.clone());
        let watched = connection.clone();
        let state = owned.clone();
        let stop = run.stop.clone();
        readers.push(tokio::spawn(async move {
            let reason = watched.closed().await;
            let mut stats = state.stats.lock().unwrap();
            stats.authenticated = false;
            if !*stop.borrow() {
                stats
                    .failure
                    .get_or_insert(format!("connection closed: {reason}"));
            }
        }));
        let (mut send, control) = connection.open_bi().await?;
        send.write_u8(wire::STREAM_CONTROL).await?;
        send.write_all(&route).await?;
        let initial = SectionKey::new(
            4,
            anchor.x.div_euclid(512),
            anchor.y.div_euclid(512),
            anchor.z.div_euclid(512),
        )?;
        let initial_sent = Instant::now();
        write(
            &mut send,
            ControlMessage::Open {
                dimension: anchor.dimension.clone(),
                expected_world: [0; 32],
                held_catalog: [0; 32],
                settings: run.settings,
                anchor_x: anchor.x,
                anchor_z: anchor.z,
                desires: vec![Desire {
                    ticket: 1,
                    key: initial.packed(),
                    purpose: 0,
                    rank: rank(initial, anchor.x, anchor.z)?,
                    have: None,
                }],
            },
        )
        .await?;
        let (sink, mut incoming) = mpsc::channel(4);
        let ready = Arc::new(Notify::new());
        let (authority, authority_ready) = watch::channel(false);
        readers.push(reader(
            control,
            connection.clone(),
            sink.clone(),
            catalogs.clone(),
            ready.clone(),
            None,
        ));
        for lane in 0..=1 {
            let (mut output, input) = connection.open_bi().await?;
            output.write_u8(wire::STREAM_SECTION_LANE).await?;
            output.write_u8(lane).await?;
            readers.push(reader(
                input,
                connection.clone(),
                sink.clone(),
                catalogs.clone(),
                ready.clone(),
                Some(authority_ready.clone()),
            ));
        }
        let (mut output, input) = connection.open_bi().await?;
        output.write_u8(wire::STREAM_DISCOVERY).await?;
        readers.push(reader(
            input,
            connection.clone(),
            sink.clone(),
            catalogs.clone(),
            ready.clone(),
            Some(authority_ready),
        ));
        drop(sink);
        let mut scope = None;
        let mut metadata = None;
        let mut manifest_worlds = HashMap::new();
        let mut saved = HashMap::new();
        let mut inventory_ready = false;
        let mut frontier = Frontier::new(&anchor);
        let mut held: HashMap<u64, RecordDescriptor> = HashMap::new();
        let mut retired: HashMap<(u64, u64), RecordDescriptor> = HashMap::new();
        let mut seen = HashSet::new();
        seen.insert(initial.packed());
        let mut pending = HashMap::new();
        pending.insert(
            initial.packed(),
            Pending {
                ticket: 1,
                sent: initial_sent,
                key: initial,
                missing: true,
            },
        );
        let mut ticket = 1_u64;
        let mut phase = 0;
        let mut warm = std::collections::VecDeque::new();
        let mut prefer_warm = false;
        let mut pulse = tokio::time::interval(Duration::from_millis(100));
        pulse.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        let mut stop = run.stop.clone();
        loop {
            tokio::select! {
                _ = stop.changed() => { if *stop.borrow() { break; } }
                _ = connection.closed() => bail!("authenticated connection closed"),
                scheduled = pulse.tick() => {
                    let late = tokio::time::Instant::now().saturating_duration_since(scheduled).as_nanos().min(u64::MAX as u128) as u64;
                    let mut stats = owned.stats.lock().unwrap(); stats.event_late_ns = stats.event_late_ns.max(late);
                },
                event = incoming.recv() => {
                    let message = event.context("all reliable readers ended")??;
                    match message {
                        ControlMessage::ServerHello { active_dimension, world_identity, .. } => {
                            ensure!(scope.is_none() && world_identity != [0;32], "world/session mismatch");
                            scope = Some((active_dimension, world_identity));
                            emit(&format!("{{\"event\":\"client_hello\",\"run\":{},\"client\":{},\"dimension\":{active_dimension},\"world\":{},\"connected_ns\":{}}}", quote(&run.id), anchor.index, quote(&hex(&world_identity)), age_ns(connected)));
                        }
                        ControlMessage::Manifest { dimensions, excluded: _ } => {
                            let (id, world) = scope.context("manifest before hello")?;
                            metadata = dimensions.iter().find(|item| item.id == id && item.name == anchor.dimension && item.world_identity == world).cloned();
                            ensure!(metadata.is_some(), "manifest active scope mismatch");
                            for item in dimensions { manifest_worlds.insert(item.id, item.world_identity); }
                            authority.send(true)?;

                        }
                        ControlMessage::Inventory(item) => {
                            ensure!(manifest_worlds.contains_key(&item.dimension), "inventory outside manifest");
                            if Some(item.dimension) == scope.map(|value| value.0) { match item.state {
                                0 => { saved.clear(); frontier.clear(); inventory_ready = false; },
                                1 | 6 => { if run.saved_regions.as_ref().is_none_or(|regions| regions.contains(&(item.x,item.z))) { saved.insert((item.x,item.z),item.saved); if inventory_ready { frontier.add_region((item.x,item.z),metadata.as_ref().context("inventory before metadata")?,&saved)?; } } },
                                2 | 3 => { saved.remove(&(item.x,item.z)); },
                                4 => { inventory_ready = true; for region in saved.keys().copied() { frontier.add_region(region,metadata.as_ref().context("inventory before metadata")?,&saved)?; } }, 5 => bail!("saved inventory failed"), _ => bail!("unknown inventory state"),
                            }}
                            owned.stats.lock().unwrap().inventory += 1;
                        }
                        ControlMessage::Catalog { dimension, world_identity, fingerprint, canonical_length, compressed } => {
                            ensure!(scope == Some((dimension, world_identity)), "catalogue outside scope");
                            let catalogs = catalogs.clone(); let path = catalog_directory.clone(); let submitted = Instant::now();
                            let (queue, work, finished) = tokio::task::spawn_blocking(move || -> Result<_> {
                                let queue = age_ns(submitted); let began = Instant::now();
                                ensure!(zstd::zstd_safe::find_frame_compressed_size(&compressed).map_err(|error| anyhow::anyhow!("catalogue frame: {error:?}"))? == compressed.len(), "catalogue trailing frame");
                                // Catalogue scratch dies here; section workers must not retain
                                // a full catalogue-sized allocation throughout the pressure run.
                                let raw=zstd::bulk::decompress(&compressed,canonical_length as usize)?;
                                ensure!(raw.len() == canonical_length as usize, "catalogue canonical extent");
                                accept_catalog(&catalogs, &path, fingerprint, &raw)?;
                                Ok((queue, age_ns(began), Instant::now()))
                            }).await??;
                            let mut stats = owned.stats.lock().unwrap(); stats.worker_queue_ns += queue; stats.validation_ns += work; stats.resume_ns += age_ns(finished);
                            drop(stats); ready.notify_waiters();
                        }
                        ControlMessage::Record { dimension, world_identity, descriptor, compressed } => {
                            ensure!(scope == Some((dimension, world_identity)), "record outside authenticated scope");
                            if pending.get(&descriptor.key).is_none_or(|wanted| wanted.ticket != descriptor.ticket) {
                                let previous=retired.get(&(descriptor.key,descriptor.ticket)).copied().context("terrain ownership ticket/key mismatch")?;
                                ensure!(descriptor.generation>=previous.generation || descriptor.status==RecordStatus::NotReady,"retired record generation regressed");
                                if descriptor.status==RecordStatus::Reuse {
                                    ensure!(compressed.is_empty() && descriptor.binding.same_body(previous.binding),"retired REUSE differs from validated binding");
                                } else if descriptor.status!=RecordStatus::NotReady {
                                    let catalogs=catalogs.clone();let scratch=scratch.clone();
                                    tokio::task::spawn_blocking(move || validate(descriptor,&compressed,&catalogs,&mut scratch.lock().unwrap())).await??;
                                }
                                owned.stats.lock().unwrap().trailing_records+=1;
                                continue;
                            }
                            if descriptor.status == RecordStatus::NotReady { owned.stats.lock().unwrap().not_ready += 1; continue; }
                            let request = pending.remove(&descriptor.key).unwrap();
                            let status = descriptor.status; let payload_bytes = compressed.len() as u64;
                            let previous = held.get(&descriptor.key).copied(); let catalogs = catalogs.clone(); let path = directory.clone(); let scratch = scratch.clone();
                            let submitted = Instant::now();
                            let (descriptor, decode, persist, queue, finished) = tokio::task::spawn_blocking(move || -> Result<_> {
                                let queue = age_ns(submitted); let (record, decode, persist) = worker_record(&path, world_identity, descriptor, compressed, previous, &catalogs, &mut scratch.lock().unwrap())?;
                                Ok((record, decode, persist, queue, Instant::now()))
                            }).await??;
                            let latency = age_ns(request.sent); retired.insert((descriptor.key,descriptor.ticket),descriptor); held.insert(descriptor.key, descriptor);
                            write(&mut send, ControlMessage::Drop(vec![ScopedKey { dimension, key: descriptor.key }])).await?;
                            let mut stats = owned.stats.lock().unwrap(); stats.records += 1;
                            if let Some(cohort)=plateau_phase(*run.phase.borrow(),run.plateau_seconds) {
                                if status!=RecordStatus::Absent { stats.phase_records[cohort]+=1; }
                                if request.missing && matches!(status,RecordStatus::Data|RecordStatus::Empty) { stats.phase_missing[cohort]+=1; }
                                if matches!(status,RecordStatus::Data|RecordStatus::Empty) {
                                    if request.key.level==4 {stats.phase_coarse_records[cohort]+=1;}else{stats.phase_detail_records[cohort]+=1;}
                                    stats.phase_payload_bytes[cohort]+=payload_bytes;
                                }
                            }
                            stats.payload_bytes += payload_bytes;
                            stats.decode_ns += decode; stats.persist_ns += persist; stats.worker_queue_ns += queue; stats.resume_ns += age_ns(finished);
                            stats.completion_ns += latency; stats.completion_histogram[completion_bucket(latency)]+=1; stats.max_completion_ns = stats.max_completion_ns.max(latency); stats.last_progress_ns = age_ns(run.started);
                            match status { RecordStatus::Data => stats.data += 1, RecordStatus::Empty => stats.empty += 1,
                                RecordStatus::Absent => stats.absent += 1, RecordStatus::Reuse => { stats.reuse += 1; stats.cache_hits += 1; }, _ => {} }
                            if descriptor.status != RecordStatus::Absent {
                                stats.validated_lanes |= if request.key.level == 4 { 1 } else { 2 };
                                stats.authenticated = stats.validated_lanes == 3 && connection.close_reason().is_none();
                                if request.key.level == 4 && stats.first_coarse_ns == 0 { stats.first_coarse_ns = age_ns(connected); }
                                if request.key.level == 0 && stats.first_detail_ns == 0 { stats.first_detail_ns = age_ns(connected); }
                            }
                        }
                        ControlMessage::Error { code, message } => bail!("server error {code}: {message}"),
                        ControlMessage::Shutdown { message } => bail!("server shutdown: {message}"),
                        _ => bail!("unexpected client-bound control message"),
                    }
                }
            }
            if *stop.borrow() {
                break;
            }
            let quic = connection.stats();
            {
                let mut stats = owned.stats.lock().unwrap();
                stats.pending = pending.len();
                stats.missing_pending = pending.values().filter(|value| value.missing).count();
                stats.rtt_ns = quic.path.rtt.as_nanos().min(u64::MAX as u128) as u64;
                stats.cwnd = quic.path.cwnd;
                stats.lost_packets = quic.path.lost_packets;
                stats.lost_bytes = quic.path.lost_bytes;
                stats.sent_packets = quic.path.sent_packets;
                stats.udp_tx = quic.udp_tx.bytes;
                stats.udp_rx = quic.udp_rx.bytes;
                // A 100ms pulse offers an explicit event-loop late-poll cross-check.
                stats.max_pending_ns = pending
                    .values()
                    .map(|value| age_ns(value.sent))
                    .max()
                    .unwrap_or(0);
            }
            let Some((id, world)) = scope else {
                continue;
            };
            let Some(info) = &metadata else {
                continue;
            };
            if !inventory_ready {
                continue;
            }
            let elapsed = run
                .phase
                .borrow()
                .map(|start| start.elapsed())
                .unwrap_or_default();
            let desired_phase = if elapsed >= Duration::from_secs(180) {
                2
            } else if elapsed >= Duration::from_secs(90) {
                1
            } else {
                0
            };
            if desired_phase > phase {
                phase = desired_phase;
                if phase == 1 {
                    // Move one coarsest cell toward the border center: deterministic, in border.
                    let x = anchor.x
                        + if f64::from(anchor.x) < info.center_x {
                            512
                        } else {
                            -512
                        };
                    let z = anchor.z
                        + if f64::from(anchor.z) < info.center_z {
                            512
                        } else {
                            -512
                        };
                    frontier.move_to(x, z)?;
                    write(
                        &mut send,
                        ControlMessage::Settings {
                            settings: run.settings,
                            active_dimension: id,
                            anchors: vec![wire::DimensionAnchor {
                                dimension: id,
                                x,
                                z,
                            }],
                        },
                    )
                    .await?;
                } else {
                    frontier.move_to(anchor.x, anchor.z)?;
                    // Cache metadata only; full payloads remain on disk. The cold frontier
                    // continues alongside warm interests rather than entering an idle phase.
                    let mut ordered: Vec<_> = held
                        .iter()
                        .filter(|(_, record)| record.status != RecordStatus::Absent)
                        .map(|(&key, _)| key)
                        .collect();
                    ordered.sort_unstable();
                    warm.extend(ordered);
                    prefer_warm = true;
                    write(
                        &mut send,
                        ControlMessage::Settings {
                            settings: run.settings,
                            active_dimension: id,
                            anchors: vec![wire::DimensionAnchor {
                                dimension: id,
                                x: anchor.x,
                                z: anchor.z,
                            }],
                        },
                    )
                    .await?;
                }
            }
            // Mirrors the existing Java worker-slot request window (maximum16). It is
            // an ownership window, never a sections/sec production throttling change.
            while pending.len() < 16 {
                let mut selected = None;
                if phase == 2 && prefer_warm {
                    while let Some(key) = warm.pop_front() {
                        if !pending.contains_key(&key) {
                            selected = Some((SectionKey::unpack(key)?, true));
                            break;
                        }
                    }
                }
                if selected.is_none() {
                    loop {
                        let Some(key) = frontier.next(info, &saved)? else {
                            break;
                        };
                        if !eligible(key, info, &saved)
                            || seen.contains(&key.packed())
                            || pending.contains_key(&key.packed())
                        {
                            tokio::task::yield_now().await;
                            if *stop.borrow() || connection.close_reason().is_some() {
                                break;
                            }
                            continue;
                        }
                        selected = Some((key, false));
                        break;
                    }
                }
                let Some((key, revisit)) = selected else {
                    break;
                };
                if phase == 2 {
                    prefer_warm = !prefer_warm;
                }
                ticket = ticket.checked_add(1).context("ticket exhaustion")?;
                let have = if revisit {
                    held.get(&key.packed()).map(|record| record.binding)
                } else {
                    None
                };
                // A refresh-only interest can be intentionally silent when unchanged.
                // Warm body delivery uses the ordinary coverage/refinement claim with
                // HAVE, which returns REUSE without occupying an unsendable slot.
                let purpose = if revisit {
                    if key.level == 4 { 0 } else { 1 }
                } else if key.level == 4 {
                    0
                } else if key.level == 0 {
                    1
                } else {
                    3
                };
                let desire = Desire {
                    ticket,
                    key: key.packed(),
                    purpose,
                    rank: rank(key, frontier.x, frontier.z)?,
                    have,
                };
                let sent = Instant::now();
                write(
                    &mut send,
                    ControlMessage::Desires(vec![ScopedDesire {
                        dimension: id,
                        expected_world: world,
                        desire,
                    }]),
                )
                .await?;
                seen.insert(key.packed());
                pending.insert(
                    key.packed(),
                    Pending {
                        ticket,
                        sent,
                        key,
                        missing: have.is_none(),
                    },
                );
                owned.stats.lock().unwrap().requested += 1;
            }
            let mut stats = owned.stats.lock().unwrap();
            stats.pending = pending.len();
            stats.missing_pending = pending.values().filter(|request| request.missing).count();
            stats.frontier_nodes = frontier.queue.len();
        }
        Ok(())
    };
    let result: Result<()> =
        tokio::select! { result = operation => result, _ = opening_stop.changed() => Ok(()) };
    for task in &readers {
        task.abort();
    }
    for task in readers {
        let _ = task.await;
    }
    if let Some(connection) = connection_owned {
        connection.close(0_u32.into(), b"owned pressure run finished");
    }
    endpoint.close(0_u32.into(), b"owned pressure run finished");
    // Keep the relay alive during finite QUIC close/drain, then stop its queues.
    let drain = tokio::time::timeout_at(run.cleanup_deadline.into(), endpoint.wait_idle()).await;
    let _ = relay_stop.send(true);
    let finished = relay
        .finish(
            run.cleanup_deadline
                .saturating_duration_since(Instant::now()),
        )
        .await;
    {
        let mut stats = owned.stats.lock().unwrap();
        stats.closed = true;
        stats.authenticated = false;
        if let Err(error) = &result {
            stats.failures += 1;
            stats.failure = Some(error.to_string());
        }
        if drain.is_err() {
            stats
                .failure
                .get_or_insert("QUIC drain exceeded invocation deadline (endpoint closed)".into());
        }
    }
    finished?;
    result
}
fn counter_json(value: relay::Counters) -> String {
    format!(
        "{{\"attempted_packets\":{},\"attempted_ip_bytes\":{},\"serviced_packets\":{},\"serviced_ip_bytes\":{},\"dropped_packets\":{},\"dropped_ip_bytes\":{},\"delivered_packets\":{},\"delivered_ip_bytes\":{},\"pending_packets\":{},\"pending_ip_bytes\":{},\"abandoned_packets\":{},\"abandoned_ip_bytes\":{},\"socket_errors\":{},\"truncated\":{},\"late_ns\":{},\"max_late_ns\":{},\"first_service_ns\":{},\"last_service_ns\":{},\"max_accounted_packet\":{},\"rate_violation_windows\":{},\"max_window_excess_bytes\":{},\"send_rate_violation_windows\":{},\"max_send_window_excess_bytes\":{},\"max_send_window_ip_bytes\":{},\"loss_draws\":{},\"send_wait_ns\":{},\"max_send_wait_ns\":{},\"first_send_ns\":{},\"last_send_ns\":{},\"delivery_late_ns\":{},\"max_delivery_late_ns\":{}}}",
        value.attempted_packets,
        value.attempted_bytes,
        value.serviced_packets,
        value.serviced_bytes,
        value.dropped_packets,
        value.dropped_bytes,
        value.delivered_packets,
        value.delivered_bytes,
        value.pending_packets,
        value.pending_bytes,
        value.abandoned_packets,
        value.abandoned_bytes,
        value.socket_errors,
        value.truncated,
        value.late_ns,
        value.max_late_ns,
        value.first_service_ns,
        value.last_service_ns,
        value.max_accounted_packet,
        value.rate_violation_windows,
        value.max_window_excess_bytes,
        value.send_rate_violation_windows,
        value.max_send_window_excess_bytes,
        value.max_send_window_ip_bytes,
        value.loss_draws,
        value.send_wait_ns,
        value.max_send_wait_ns,
        value.first_send_ns,
        value.last_send_ns,
        value.delivery_late_ns,
        value.max_delivery_late_ns
    )
}
fn snapshot(id: &str, index: usize, state: &ClientState, started: Instant, event: &str) {
    let stats = state.stats.lock().unwrap();
    let completion_histogram = &stats.completion_histogram;
    let phase_coarse_records = &stats.phase_coarse_records;
    let phase_detail_records = &stats.phase_detail_records;
    let phase_payload_bytes = &stats.phase_payload_bytes;
    let relay_owned = state.relay.lock().unwrap();
    let relay = relay_owned.as_ref().map(|state| state.lock().unwrap());
    let up = relay
        .as_ref()
        .map_or_else(|| "null".into(), |relay| counter_json(relay.up));
    let down = relay
        .as_ref()
        .map_or_else(|| "null".into(), |relay| counter_json(relay.down));
    let relay_failure = relay
        .as_ref()
        .and_then(|relay| relay.failure.as_ref())
        .map_or_else(|| "null".into(), |error| quote(error));
    let failure = stats
        .failure
        .as_ref()
        .map_or_else(|| "null".into(), |error| quote(error));
    emit(&format!(
        "{{\"event\":{},\"run\":{},\"client\":{index},\"elapsed_ns\":{},\"authenticated\":{},\"closed\":{},\"records\":{},\"data\":{},\"empty\":{},\"absent\":{},\"reuse\":{},\"not_ready\":{},\"payload_bytes\":{},\"cache_hits\":{},\"inventory_records\":{},\"pending\":{},\"requested\":{},\"decode_ns\":{},\"persist_ns\":{},\"worker_queue_ns\":{},\"resume_ns\":{},\"catalogue_validation_ns\":{},\"completion_ns\":{},\"completion_histogram\":{completion_histogram:?},\"max_completion_ns\":{},\"first_coarse_ns\":{},\"first_detail_ns\":{},\"last_progress_ns\":{},\"max_pending_ns\":{},\"max_event_loop_late_ns\":{},\"phase_records\":[{},{},{}],\"phase_coarse_records\":{phase_coarse_records:?},\"phase_detail_records\":{phase_detail_records:?},\"phase_useful_payload_bytes\":{phase_payload_bytes:?},\"validated_lanes\":{},\"phase_missing\":[{},{},{}],\"missing_pending\":{},\"plateau_missing_samples\":{},\"plateau_idle_samples\":{},\"trailing_records\":{},\"frontier_nodes\":{},\"failures\":{},\"failure\":{failure},\"setup_rtt_ns\":{},\"rtt_ns\":{},\"cwnd\":{},\"lost_packets\":{},\"lost_bytes\":{},\"sent_packets\":{},\"quinn_udp_tx_bytes\":{},\"quinn_udp_rx_bytes\":{},\"up\":{up},\"down\":{down},\"relay_failure\":{relay_failure}}}",
        quote(event),
        quote(id),
        age_ns(started),
        stats.authenticated,
        stats.closed,
        stats.records,
        stats.data,
        stats.empty,
        stats.absent,
        stats.reuse,
        stats.not_ready,
        stats.payload_bytes,
        stats.cache_hits,
        stats.inventory,
        stats.pending,
        stats.requested,
        stats.decode_ns,
        stats.persist_ns,
        stats.worker_queue_ns,
        stats.resume_ns,
        stats.validation_ns,
        stats.completion_ns,
        stats.max_completion_ns,
        stats.first_coarse_ns,
        stats.first_detail_ns,
        stats.last_progress_ns,
        stats.max_pending_ns,
        stats.event_late_ns,
        stats.phase_records[0],
        stats.phase_records[1],
        stats.phase_records[2],
        stats.validated_lanes,
        stats.phase_missing[0],
        stats.phase_missing[1],
        stats.phase_missing[2],
        stats.missing_pending,
        stats.plateau_missing_samples,
        stats.plateau_idle_samples,
        stats.trailing_records,
        stats.frontier_nodes,
        stats.failures,
        stats.setup_rtt_ns,
        stats.rtt_ns,
        stats.cwnd,
        stats.lost_packets,
        stats.lost_bytes,
        stats.sent_packets,
        stats.udp_tx,
        stats.udp_rx
    ));
}
fn parse_anchors(path: &Path) -> Result<HashMap<usize, Anchor>> {
    let mut anchors = HashMap::new();
    for (line_number, line) in std::fs::read_to_string(path)?.lines().enumerate() {
        if line.trim().is_empty() || line.starts_with('#') || line.starts_with("client\t") {
            continue;
        }
        let fields: Vec<_> = line.split('\t').collect();
        ensure!(
            fields.len() == 5,
            "location line{} must be client/dimension/x/y/z TSV",
            line_number + 1
        );
        let anchor = Anchor {
            index: fields[0].parse()?,
            dimension: fields[1].into(),
            x: fields[2].parse()?,
            y: fields[3].parse()?,
            z: fields[4].parse()?,
        };
        ensure!(
            i64::from(anchor.x).abs() <= 30_000_000 && i64::from(anchor.z).abs() <= 30_000_000,
            "anchor outside Minecraft domain"
        );
        ensure!(
            anchors.insert(anchor.index, anchor).is_none(),
            "duplicate anchor client"
        );
    }
    Ok(anchors)
}
fn runner_arithmetic_check() -> Result<()> {
    ensure!(
        completion_bucket(0) == 0
            && completion_bucket(1000) == 0
            && completion_bucket(1001) == 1
            && completion_bucket(2000) == 1
            && completion_bucket(2001) == 2
            && completion_bucket(u64::MAX) == 31,
        "completion histogram boundaries"
    );
    let anchor = Anchor {
        index: 0,
        dimension: "minecraft:overworld".into(),
        x: 0,
        y: 64,
        z: 0,
    };
    let metadata = wire::DimensionMetadata {
        id: 0,
        name: anchor.dimension.clone(),
        world_identity: [1; 32],
        min_section_y: -4,
        section_count: 24,
        custom_border: true,
        center_x: 0.0,
        center_z: 0.0,
        size: 60_000_000.0,
        catalog_id: 1,
        catalog_fingerprint: [1; 32],
    };
    let mut bits = [0; 16];
    bits[0] = 1;
    let saved = HashMap::from([((0, 0), bits)]);
    let mut frontier = Frontier::new(&anchor);
    frontier.add_region((0, 0), &metadata, &saved)?;
    let mut keys = HashSet::new();
    while let Some(key) = frontier.next(&metadata, &saved)? {
        ensure!(keys.insert(key.packed()), "duplicate spatial frontier key");
        ensure!(
            eligible(key, &metadata, &saved),
            "frontier emitted missing source"
        );
    }
    ensure!(
        keys.len() == 27,
        "saved hierarchy traversed empty border area or lost height coverage"
    );
    ensure!(
        plateau_phase(None, 240).is_none(),
        "setup entered plateau cohort"
    );
    let old = Instant::now() - Duration::from_secs(241);
    ensure!(
        plateau_phase(Some(old), 240).is_none(),
        "cleanup entered plateau cohort"
    );
    let warm = Instant::now() - Duration::from_secs(181);
    ensure!(
        plateau_phase(Some(warm), 240) == Some(2),
        "warm cohort boundary"
    );
    Ok(())
}
fn unix_ms() -> Result<u64> {
    Ok(SystemTime::now()
        .duration_since(UNIX_EPOCH)?
        .as_millis()
        .try_into()?)
}

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<()> {
    let mut args = HashMap::new();
    let mut input = std::env::args().skip(1);
    while let Some(option) = input.next() {
        if option == "--help" {
            println!(
                "Rust authenticated pressure clients. No Minecraft connection or world mutation.\n\
                --server IP:PORT --cert DER --routes SECRET_TSV --locations TSV --cache OWNED_NEW_DIRECTORY --run-id ID\n\
                [--clients 30 --duration 330 --plateau-seconds 240 --stop-unix-ms PRESSURE_CUTOFF --cleanup-unix-ms ORIGINAL_RUN_DEADLINE]\n\
                [--rtt-ms 300 --loss-percent 10 --cap-kbps 1000 --seed 17]\n\
                Use --clients 1 --plateau-seconds 10 --duration 30 for finite calibration; --rtt-ms 0 --loss-percent 0 for baseline.\n\
                Secret TSV: zero-based index<TAB>token64. Location TSV: client<TAB>dimension<TAB>x<TAB>y<TAB>z.\n\
                --check-arithmetic invokes only focused ownership/rate/loss calculations, with no sockets."
            );
            return Ok(());
        }
        if option == "--check-arithmetic" {
            relay::arithmetic_check()?;
            runner_arithmetic_check()?;
            emit("{\"event\":\"arithmetic_check\",\"success\":true,\"network_started\":false}");
            return Ok(());
        }
        ensure!(option.starts_with("--"), "expected named option");
        let name = option.trim_start_matches("--");
        ensure!(
            [
                "server",
                "cert",
                "routes",
                "locations",
                "saved-regions",
                "cache",
                "run-id",
                "clients",
                "duration",
                "plateau-seconds",
                "stop-unix-ms",
                "cleanup-unix-ms",
                "rtt-ms",
                "loss-percent",
                "cap-kbps",
                "seed"
            ]
            .contains(&name),
            "unknown option--{name}"
        );
        ensure!(
            args.insert(
                name.to_owned(),
                input.next().context("missing option value")?
            )
            .is_none(),
            "duplicate option--{name}"
        );
    }
    let id = argument(&args, "run-id")?.to_owned();
    ensure!(
        !id.is_empty()
            && id.len() <= 128
            && id
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || b"-_.".contains(&byte)),
        "invalid run ID"
    );
    let clients: usize = number(&args, "clients", "30")?;
    ensure!((1..=30).contains(&clients), "clients must be1..30");
    let duration: u64 = number(&args, "duration", "330")?;
    let plateau_seconds: u64 = number(&args, "plateau-seconds", "240")?;
    ensure!(
        duration > 0 && plateau_seconds > 0 && duration >= plateau_seconds,
        "invalid duration/plateau"
    );
    let server: SocketAddr = argument(&args, "server")?.parse()?;
    let directory = PathBuf::from(argument(&args, "cache")?);
    ensure!(
        directory.is_absolute()
            && directory.starts_with("/home/aerosmp/Desktop/")
            && !directory.starts_with("/home/aerosmp/Desktop/Main"),
        "cache must be a run-owned absolute Desktop directory"
    );
    ensure!(
        !directory.exists(),
        "cold pressure cache directory already exists; choose a new owned directory (nothing deleted)"
    );
    std::fs::create_dir_all(&directory)?;
    let routes_path = PathBuf::from(argument(&args, "routes")?);
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        ensure!(
            std::fs::metadata(&routes_path)?.permissions().mode() & 0o077 == 0,
            "route secret file grants group/other permissions"
        );
    }
    let mut routes = HashMap::new();
    let mut unique = HashSet::new();
    let mut prefixes = HashSet::new();
    for line in std::fs::read_to_string(&routes_path)?.lines() {
        if line.trim().is_empty() {
            continue;
        }
        let (index, secret) = line
            .split_once('\t')
            .context("route TSV requires index/tab/token")?;
        let index: usize = index.parse()?;
        let route = token(secret.trim())?;
        ensure!(unique.insert(route), "duplicate authenticated route");
        ensure!(
            prefixes.insert(route[..16].to_vec()),
            "duplicate public routing prefix"
        );
        ensure!(
            routes.insert(index, route).is_none(),
            "duplicate route client index"
        );
    }
    let saved_regions = if let Some(path) = args.get("saved-regions") {
        let mut regions = HashSet::new();
        for line in std::fs::read_to_string(path)?.lines() {
            if line.trim().is_empty() || line.starts_with("dimension\t") {
                continue;
            }
            let fields: Vec<_> = line.split('\t').collect();
            ensure!(fields.len() == 3, "saved-regions TSV must be dimension/x/z");
            ensure!(
                fields[0] == "minecraft:overworld",
                "supplied region inventory must match current overworld workload"
            );
            regions.insert((fields[1].parse()?, fields[2].parse()?));
        }
        Some(Arc::new(regions))
    } else {
        None
    };
    let mut anchors = parse_anchors(Path::new(argument(&args, "locations")?))?;
    let profile = relay::Profile {
        kbps: number(&args, "cap-kbps", "1000")?,
        delay: Duration::from_nanos(
            number::<u64>(&args, "rtt-ms", "300")?
                .checked_mul(500_000)
                .context("delay overflow")?,
        ),
        loss_percent: number(&args, "loss-percent", "10")?,
        seed: number(&args, "seed", "17")?,
    };
    let settings = StreamingSettings {
        interval_millis: wire::DEFAULT_UPDATE_INTERVAL_MILLIS,
        bandwidth_kbps: profile.kbps,
        refresh_allowed: true,
    };
    settings.validate()?;
    ensure!(profile.loss_percent <= 100, "invalid loss percentage");
    relay::arithmetic_check()?;
    runner_arithmetic_check()?;
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
    transport.max_idle_timeout(Some(Duration::from_secs(60).try_into()?));
    transport.keep_alive_interval(Some(Duration::from_secs(10)));
    let mut mtu = quinn::MtuDiscoveryConfig::default();
    mtu.upper_bound(1435);
    transport.initial_mtu(1200).mtu_discovery_config(Some(mtu));
    let mut config = quinn::ClientConfig::new(Arc::new(
        quinn::crypto::rustls::QuicClientConfig::try_from(tls)?,
    ));
    config.transport_config(Arc::new(transport));
    let started = Instant::now();
    let external_ms: u64 = number(
        &args,
        "stop-unix-ms",
        &unix_ms()?
            .saturating_add(duration.saturating_mul(1000))
            .to_string(),
    )?;
    let remaining = external_ms.saturating_sub(unix_ms()?);
    ensure!(remaining > 0, "external deadline already elapsed");
    let deadline = started + Duration::from_millis(remaining.min(duration.saturating_mul(1000)));
    let cleanup_ms: u64 = number(&args, "cleanup-unix-ms", &external_ms.to_string())?;
    ensure!(
        cleanup_ms >= external_ms,
        "cleanup deadline precedes pressure stop"
    );
    let cleanup_remaining = cleanup_ms.saturating_sub(unix_ms()?);
    ensure!(cleanup_remaining > 0, "cleanup deadline already elapsed");
    let cleanup_deadline = Instant::now()
        .checked_add(Duration::from_millis(cleanup_remaining))
        .context("cleanup deadline cannot be represented by the monotonic clock")?;
    let (stop_sender, stop) = watch::channel(false);
    let (phase_sender, phase) = watch::channel(None);
    let run = Run {
        id: Arc::new(id.clone()),
        server,
        tls: config,
        directory: Arc::new(directory),
        settings,
        profile,
        phase,
        stop,
        started,
        saved_regions,
        cleanup_deadline,
        plateau_seconds,
    };
    for index in 0..clients {
        ensure!(
            routes.contains_key(&index) && anchors.contains_key(&index),
            "missing route or anchor for client{index}"
        );
    }
    let distinct: HashSet<_> = (0..clients)
        .map(|index| {
            let anchor = &anchors[&index];
            (
                anchor.dimension.clone(),
                anchor.x.div_euclid(512),
                anchor.z.div_euclid(512),
            )
        })
        .collect();
    ensure!(
        distinct.len() == clients,
        "clients must have distinct populated coarse-region anchors"
    );
    let mut tasks = tokio::task::JoinSet::new();
    let mut states = Vec::new();
    for index in 0..clients {
        let route = routes
            .remove(&index)
            .with_context(|| format!("missing route client{index}"))?;
        let anchor = anchors
            .remove(&index)
            .with_context(|| format!("missing anchor client{index}"))?;
        let state = Arc::new(ClientState {
            stats: Mutex::new(ClientStats::default()),
            relay: Mutex::new(None),
            connection: Mutex::new(None),
        });
        states.push(state.clone());
        let run = run.clone();
        tasks.spawn(async move { (index, client(run, anchor, route, state).await) });
    }
    let completion_bounds = (0..31)
        .map(|index| (1000_u64 << index).to_string())
        .chain(std::iter::once("null".into()))
        .collect::<Vec<_>>()
        .join(",");
    emit(&format!(
        "{{\"event\":\"run_started\",\"run\":{},\"clients\":{clients},\"rtt_ms\":{},\"loss_percent_per_direction\":{},\"full_duplex_cap_kbps\":{},\"seed\":{},\"external_stop_unix_ms\":{external_ms},\"external_cleanup_unix_ms\":{cleanup_ms},\"plateau_seconds\":{plateau_seconds},\"minecraft_connections\":0,\"runtime\":\"current_thread\",\"completion_histogram_upper_bounds_ns\":[{completion_bounds}]}}",
        quote(&id),
        profile.delay.as_secs_f64() * 2000.0,
        profile.loss_percent,
        profile.kbps,
        profile.seed
    ));
    let mut pulse = tokio::time::interval(Duration::from_secs(1));
    pulse.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut plateau = None;
    let mut completed_plateau = false;
    let mut failure = None;
    let mut minimum = clients;
    loop {
        tokio::select! {
            _=tokio::time::sleep_until(deadline.into())=>{failure=Some("external/setup deadline before full plateau".to_owned());break;},
            _=tokio::signal::ctrl_c()=>{failure=Some("operator interrupted".to_owned());break;},
            done=tasks.join_next()=>{
                if let Some(done)=done{match done{Ok((index,result))=>{failure=Some(format!("client{index} ended before plateau: {}",result.err().map_or_else(||"closed".into(),|error|error.to_string())));},Err(error)=>failure=Some(format!("client task failed:{error}")),}}
                break;
            },
            _=pulse.tick()=>{
                let count=states.iter().filter(|state|state.stats.lock().unwrap().authenticated &&state.connection.lock().unwrap().as_ref().is_some_and(|connection|connection.close_reason().is_none())).count();
                if count==clients && plateau.is_none(){let start=Instant::now();plateau=Some(start);phase_sender.send(Some(start))?;emit(&format!("{{\"event\":\"plateau_begin\",\"run\":{},\"clients\":{clients},\"elapsed_ns\":{}}}",quote(&id),age_ns(started)));}
                if plateau.is_some(){minimum=minimum.min(count);if count!=clients{failure=Some("authenticated plateau concurrency dropped".into());break;}}
                for(index,state)in states.iter().enumerate(){
                    if plateau.is_some() { let mut stats=state.stats.lock().unwrap(); if stats.missing_pending>0 {stats.plateau_missing_samples+=1;}else{stats.plateau_idle_samples+=1;} }
                    snapshot(&id,index,state,started,"client_snapshot");
                }
                emit(&format!("{{\"event\":\"concurrency\",\"run\":{},\"authenticated\":{count},\"minimum_plateau\":{minimum},\"plateau_ns\":{}}}",quote(&id),plateau.map_or(0,age_ns)));
                if plateau.is_some_and(|start|start.elapsed()>=Duration::from_secs(plateau_seconds)){completed_plateau=true;break;}
            }
        }
    }
    stop_sender.send(true)?;
    while !tasks.is_empty() {
        match tokio::time::timeout_at(cleanup_deadline.into(), tasks.join_next()).await {
            Ok(Some(Ok((index, Err(error))))) => {
                failure.get_or_insert(format!("client{index} cleanup:{error}"));
            }
            Ok(Some(Err(error))) => {
                failure.get_or_insert(format!("client cleanup task:{error}"));
            }
            Ok(Some(_)) => {}
            Ok(None) => break,
            Err(_) => {
                tasks.abort_all();
                while tasks.join_next().await.is_some() {}
                failure.get_or_insert("client cleanup timed out".into());
                break;
            }
        }
    }
    for (index, state) in states.iter().enumerate() {
        snapshot(&id, index, state, started, "client_final");
        let stats = state.stats.lock().unwrap();
        if clients > 1 && completed_plateau && stats.phase_missing.iter().any(|count| *count == 0) {
            failure.get_or_insert(format!(
                "client{index} did not continue missing DATA/EMPTY completions through each plateau phase"
            ));
        }
        if let Some(error) = &stats.failure {
            failure.get_or_insert(format!("client{index}: {error}"));
        }
        if clients > 1
            && completed_plateau
            && (stats.plateau_missing_samples == 0 || stats.plateau_idle_samples != 0)
        {
            failure.get_or_insert(format!(
                "client{index} lacks sustained missing demand: {} idle plateau observations",
                stats.plateau_idle_samples
            ));
        }
        if stats.data + stats.empty == 0 || stats.first_coarse_ns == 0 {
            failure.get_or_insert(format!(
                "client{index} did not validate useful coarse terrain"
            ));
        }
        if let Some(relay) = state.relay.lock().unwrap().as_ref() {
            let relay = relay.lock().unwrap();
            if relay.failure.is_some()
                || relay.up.rate_violation_windows != 0
                || relay.down.rate_violation_windows != 0
                || relay.up.send_rate_violation_windows != 0
                || relay.down.send_rate_violation_windows != 0
                || relay.up.truncated != 0
                || relay.down.truncated != 0
            {
                failure.get_or_insert(format!("client{index} relay fidelity failure"));
            }
        }
    }
    let success = completed_plateau && failure.is_none();
    emit(&format!(
        "{{\"event\":\"run_finished\",\"run\":{},\"clients\":{clients},\"full_plateau\":{completed_plateau},\"minimum_plateau\":{minimum},\"elapsed_ns\":{},\"technical_checks_passed\":{success},\"acceptance\":\"inconclusive\",\"acceptance_pending\":[\"profile_calibration\",\"native_route_and_shared_work_cleanup\",\"generator_fidelity_and_resources\",\"real_pc_and_backup_routes\"],\"profile_fidelity_verified\":false,\"failure\":{},\"route_cleanup_owner\":\"debug_java_and_external_operator\"}}",
        quote(&id),
        age_ns(started),
        failure
            .as_ref()
            .map_or_else(|| "null".into(), |error| quote(error))
    ));
    ensure!(
        success,
        "pressure run incomplete: {}",
        failure.unwrap_or_else(|| "full plateau missing".into())
    );
    Ok(())
}
