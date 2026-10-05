//! One current-protocol live consumer using an already authenticated Minecraft-session route.
//! This is not a 100-player pressure receipt: virtual-session registration remains suspended.
use anyhow::{Context, Result, bail, ensure};
use quinn::{AsyncUdpSocket, Connection, Endpoint, RecvStream, Runtime, SendStream, UdpPoller};
use std::{
    collections::HashMap,
    io::{self, IoSliceMut, Write},
    net::SocketAddr,
    path::{Path, PathBuf},
    pin::Pin,
    sync::{Arc, Mutex},
    task::{Context as PollContext, Poll},
    time::{Duration, Instant},
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
fn validate(descriptor: RecordDescriptor, bytes: &[u8], catalogs: &Catalogs) -> Result<()> {
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
            let raw = zstd::bulk::decompress(bytes, binding.canonical_length as usize)?;
            ensure!(
                raw.len() == binding.canonical_length as usize
                    && blake3::hash(&raw).as_bytes()[..16] == binding.fingerprint,
                "canonical integrity mismatch"
            );
            let section = SectionFrame::decode(&raw)?;
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
    let mut owned = catalogs.lock().unwrap();
    if let std::collections::hash_map::Entry::Vacant(entry) = owned.entry(fingerprint) {
        let catalog = Arc::new(Catalog::decode(raw)?);
        atomic(
            &directory.join(format!("{}.catalog", hex(&fingerprint))),
            raw,
        )?;
        entry.insert(catalog);
    }
    Ok(())
}
fn cached(
    directory: &Path,
    world: [u8; 32],
    key: u64,
    catalogs: &Catalogs,
) -> Result<Option<(RecordDescriptor, Vec<u8>)>> {
    let path = directory
        .join(hex(&world))
        .join(format!("{key:016x}.section"));
    let bytes = match std::fs::read(path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    ensure!(
        bytes.len() >= wire::RECORD_DESCRIPTOR_BYTES,
        "truncated local record"
    );
    let descriptor = RecordDescriptor::decode(&bytes[..wire::RECORD_DESCRIPTOR_BYTES])?;
    ensure!(descriptor.key == key, "cached key mismatch");
    let payload = bytes[wire::RECORD_DESCRIPTOR_BYTES..].to_vec();
    validate(descriptor, &payload, catalogs)?;
    Ok(Some((descriptor, payload)))
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
fn reader(
    mut input: RecvStream,
    connection: Connection,
    sink: mpsc::Sender<Result<ControlMessage>>,
    catalogs: Catalogs,
    directory: PathBuf,
    ready: Arc<Notify>,
    discovery_gate: Option<watch::Receiver<bool>>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let result: Result<()> = async {
            if let Some(mut gate) = discovery_gate { gate.wait_for(|ready| *ready).await?; }
            while let Some(message) = wire::read_control(&mut input).await? {
                if let ControlMessage::Catalog { fingerprint, canonical_length, compressed, .. } = &message {
                    ensure!(zstd::zstd_safe::find_frame_compressed_size(compressed)
                        .map_err(|error| anyhow::anyhow!("catalogue Zstd frame: {error:?}"))? == compressed.len(), "catalogue frame extent");
                    let raw = zstd::bulk::decompress(compressed, *canonical_length as usize)?;
                    ensure!(raw.len() == *canonical_length as usize, "catalogue canonical extent");
                    accept_catalog(&catalogs, &directory, *fingerprint, &raw)?;
                    ready.notify_waiters();
                }
                if let ControlMessage::Record { descriptor, .. } = &message {
                    if matches!(descriptor.status, RecordStatus::Data | RecordStatus::Reuse)
                        && descriptor.binding.has_body() {
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

#[tokio::main]
async fn main() -> Result<()> {
    let mut args = HashMap::new();
    let mut input = std::env::args().skip(1);
    while let Some(option) = input.next() {
        if option == "--help" {
            println!(
                "Authenticated single live consumer; not a 100-player pressure test.\n\
                --server IP:PORT --cert FILE --route-token32 HEX64 --dimension NAME --cache OWNED_DIRECTORY\n\
                [--route-token-file FILE instead of --route-token32] [--x BLOCK --y BLOCK --z BLOCK]\n\
                [--duration SECONDS --cap-kbps 100..10000 --interval-ms >=1000 --refresh true|false]\n\
                The Minecraft server must already have registered this route and selected cap.\n\
                No session registration, block mutation, network impairment or player simulation is performed."
            );
            return Ok(());
        }
        ensure!(option.starts_with("--"), "expected named option");
        let name = option.trim_start_matches("--");
        ensure!(
            [
                "server",
                "cert",
                "route-token32",
                "route-token-file",
                "dimension",
                "cache",
                "x",
                "y",
                "z",
                "duration",
                "cap-kbps",
                "interval-ms",
                "refresh"
            ]
            .contains(&name),
            "unknown current-protocol option --{name}"
        );
        ensure!(
            args.insert(name.into(), input.next().context("missing option value")?)
                .is_none(),
            "duplicate option --{name}"
        );
    }
    let server: SocketAddr = argument(&args, "server")?.parse()?;
    ensure!(
        !(args.contains_key("route-token32") && args.contains_key("route-token-file")),
        "choose one route token source"
    );
    let secret = if let Some(file) = args.get("route-token-file") {
        std::fs::read_to_string(file)?
    } else {
        argument(&args, "route-token32")?.to_owned()
    };
    let route = token(secret.trim())?;
    let directory = PathBuf::from(argument(&args, "cache")?);
    let dimension = argument(&args, "dimension")?.to_owned();
    let x: i32 = number(&args, "x", "0")?;
    let y: i32 = number(&args, "y", "64")?;
    let z: i32 = number(&args, "z", "0")?;
    ensure!(
        i64::from(x).abs() <= 30_000_000 && i64::from(z).abs() <= 30_000_000,
        "anchor outside Minecraft domain"
    );
    let settings = StreamingSettings {
        interval_millis: number(&args, "interval-ms", "1000")?,
        bandwidth_kbps: number(&args, "cap-kbps", "1000")?,
        refresh_allowed: number(&args, "refresh", "false")?,
    };
    settings.validate()?;
    let duration: u64 = number(&args, "duration", "60")?;
    ensure!(duration > 0, "duration must be positive");
    let catalog_directory = directory.join("catalogues");
    std::fs::create_dir_all(&catalog_directory)?;
    let catalogs: Catalogs = Arc::new(Mutex::new(HashMap::new()));
    for entry in std::fs::read_dir(&catalog_directory)? {
        let path = entry?.path();
        if path
            .extension()
            .is_none_or(|extension| extension != "catalog")
        {
            continue;
        }
        let raw = std::fs::read(&path)?;
        let fingerprint = *blake3::hash(&raw).as_bytes();
        ensure!(
            path.file_stem().context("catalogue filename")? == hex(&fingerprint).as_str(),
            "catalogue filename mismatch"
        );
        accept_catalog(&catalogs, &catalog_directory, fingerprint, &raw)?;
    }
    let link = directory.join(format!(
        "{}.world",
        blake3::hash(dimension.as_bytes()).to_hex()
    ));
    let expected_world: [u8; 32] = match std::fs::read(&link) {
        Ok(bytes) => bytes
            .try_into()
            .map_err(|_| anyhow::anyhow!("invalid cached dimension identity"))?,
        Err(error) if error.kind() == io::ErrorKind::NotFound => [0; 32],
        Err(error) => return Err(error.into()),
    };
    let mut held = HashMap::new();
    let mut desires = Vec::new();
    for lod in (0..=4).rev() {
        let side = 32 << lod;
        let key = SectionKey::new(
            lod,
            x.div_euclid(side),
            y.div_euclid(side),
            z.div_euclid(side),
        )?;
        let hit = if expected_world != [0; 32] {
            cached(&directory, expected_world, key.packed(), &catalogs)?
        } else {
            None
        };
        let have = hit.as_ref().map(|(descriptor, _)| descriptor.binding);
        let ticket = u64::from(5 - lod);
        if let Some(record) = hit {
            held.insert(key.packed(), record);
        }
        if have.is_none() || settings.refresh_allowed {
            desires.push(Desire {
                ticket,
                key: key.packed(),
                purpose: if have.is_some() { 2 } else { 0 },
                rank: rank(key, x, z)?,
                have,
            });
        }
    }
    emit(&format!(
        "{{\"event\":\"consumer_cache\",\"clients\":1,\"cache_hits\":{},\"missing\":{},\"pressure_verified\":false}}",
        held.len(),
        5 - held.len()
    ));
    if desires.is_empty() {
        return Ok(());
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
    transport.max_idle_timeout(Some(Duration::from_secs(60).try_into()?));
    let mut discovery = quinn::MtuDiscoveryConfig::default();
    discovery.upper_bound(1435);
    transport
        .initial_mtu(1200)
        .mtu_discovery_config(Some(discovery));
    let mut config = quinn::ClientConfig::new(Arc::new(
        quinn::crypto::rustls::QuicClientConfig::try_from(tls)?,
    ));
    config.transport_config(Arc::new(transport));
    let endpoint = RoutedSocket::endpoint(route, server)?;
    let connection = endpoint.connect_with(config, server, "voxy.local")?.await?;
    let (mut send, control) = connection.open_bi().await?;
    send.write_u8(wire::STREAM_CONTROL).await?;
    send.write_all(&route).await?;
    let wanted: HashMap<u64, u64> = desires
        .iter()
        .map(|desire| (desire.key, desire.ticket))
        .collect();
    let held_catalog = held
        .values()
        .find_map(|(descriptor, _)| {
            catalogs
                .lock()
                .unwrap()
                .contains_key(&descriptor.binding.catalog_fingerprint)
                .then_some(descriptor.binding.catalog_fingerprint)
        })
        .unwrap_or([0; 32]);
    write(
        &mut send,
        ControlMessage::Open {
            dimension: dimension.clone(),
            expected_world,
            held_catalog,
            settings,
            anchor_x: x,
            anchor_z: z,
            desires,
        },
    )
    .await?;
    let (sink, mut incoming) = mpsc::channel(4); // One owned handoff from each reliable reader.
    let ready = Arc::new(Notify::new());
    let (authority, authority_ready) = watch::channel(false);
    let mut readers = vec![reader(
        control,
        connection.clone(),
        sink.clone(),
        catalogs.clone(),
        catalog_directory.clone(),
        ready.clone(),
        None,
    )];
    for lane in 0..=1 {
        let (mut output, input) = connection.open_bi().await?;
        output.write_u8(wire::STREAM_SECTION_LANE).await?;
        output.write_u8(lane).await?;
        readers.push(reader(
            input,
            connection.clone(),
            sink.clone(),
            catalogs.clone(),
            catalog_directory.clone(),
            ready.clone(),
            Some(authority_ready.clone()),
        ));
    }
    let (mut output, input) = connection.open_bi().await?;
    output.write_u8(wire::STREAM_DISCOVERY).await?;
    readers.push(reader(input, connection.clone(), sink.clone(), catalogs.clone(),
        catalog_directory.clone(), ready.clone(), Some(authority_ready)));
    drop(sink);
    let mut scope = None;
    let mut manifest_worlds = HashMap::new();
    let mut received = 0_u64;
    let mut records = 0_u64;
    let mut reused = 0_u64;
    let mut inventory_records = 0_u64;
    let mut not_ready = 0_u64;
    let started = Instant::now();
    let deadline = tokio::time::sleep(Duration::from_secs(duration));
    tokio::pin!(deadline);
    let result: Result<()> = async {
        loop {
            tokio::select! {
                _ = &mut deadline => break,
                _ = connection.closed() => bail!("authenticated QUIC connection closed"),
                event = incoming.recv() => {
                    let message = event.context("all stream readers ended")??;
                    match message {
                        ControlMessage::ServerHello { active_dimension, world_identity, .. } => {
                            ensure!(scope.is_none() && world_identity != [0; 32] && (expected_world == [0; 32] || expected_world == world_identity), "world/session mismatch");
                            scope = Some((active_dimension, world_identity)); atomic(&link, &world_identity)?;
                            emit(&format!("{{\"event\":\"consumer_hello\",\"clients\":1,\"dimension_id\":{active_dimension},\"world\":{}}}", quote(&hex(&world_identity))));
                        }
                        ControlMessage::Manifest(manifest) => {
                            if let Some((id, world)) = scope {
                                ensure!(manifest.iter().any(|item| item.id == id && item.name == dimension && item.world_identity == world), "manifest active scope mismatch");
                            }
                            emit(&format!("{{\"event\":\"consumer_manifest\",\"dimensions\":{}}}", manifest.len()));
                            for item in manifest { manifest_worlds.insert(item.id, item.world_identity); }
                            let _ = authority.send(true);
                        }
                        ControlMessage::Inventory(inventory) => {
                            ensure!(manifest_worlds.contains_key(&inventory.dimension), "inventory outside manifest scope");
                            inventory_records += 1;
                        }
                        ControlMessage::Catalog { dimension: id, world_identity, .. } => {
                            ensure!(scope == Some((id, world_identity)), "unexpected catalogue scope");
                        }
                        ControlMessage::Record { dimension: id, world_identity, mut descriptor, compressed } => {
                            ensure!(scope == Some((id, world_identity)), "terrain arrived outside active authenticated scope");
                            ensure!(wanted.get(&descriptor.key) == Some(&descriptor.ticket), "terrain ticket/key mismatch");
                            if descriptor.status == RecordStatus::NotReady { not_ready += 1; continue; }
                            let payload = if descriptor.status == RecordStatus::Reuse {
                                reused += 1;
                                let previous = held.get(&descriptor.key).context("REUSE without committed cache")?;
                                ensure!(descriptor.binding.same_body(previous.0.binding), "REUSE binding mismatch");
                                descriptor.status = previous.0.status;
                                previous.1.clone()
                            } else { received += compressed.len() as u64; compressed };
                            validate(descriptor, &payload, &catalogs)?;
                            let mut bytes = descriptor.encode()?.to_vec(); bytes.extend_from_slice(&payload);
                            atomic(&directory.join(hex(&world_identity)).join(format!("{:016x}.section", descriptor.key)), &bytes)?;
                            held.insert(descriptor.key, (descriptor, payload)); records += 1;
                            if !settings.refresh_allowed {
                                write(&mut send, ControlMessage::Drop(vec![ScopedKey { dimension: id, key: descriptor.key }])).await?;
                            } else if descriptor.status != RecordStatus::Absent {
                                write(&mut send, ControlMessage::Desires(vec![ScopedDesire { dimension: id, expected_world: world_identity,
                                    desire: Desire { ticket: descriptor.ticket, key: descriptor.key, purpose: 2,
                                        rank: rank(SectionKey::unpack(descriptor.key)?, x, z)?, have: Some(descriptor.binding) } }])).await?;
                            }
                            emit(&format!("{{\"event\":\"consumer_record\",\"dimension_id\":{id},\"key\":{},\"records\":{records},\"cache_entries\":{},\"received_payload_bytes\":{received},\"reused\":{reused}}}", quote(&format!("{:016x}", descriptor.key)), held.len()));
                        }
                        ControlMessage::Error { code, message } => bail!("server error {code}: {message}"),
                        ControlMessage::Shutdown { message } => bail!("server shutdown: {message}"),
                        _ => bail!("unexpected client-bound message"),
                    }
                }
            }
        }
        Ok(())
    }.await;
    for task in &readers {
        task.abort();
    }
    for task in readers {
        let _ = task.await;
    }
    connection.close(0_u32.into(), b"authenticated live consumer finished");
    endpoint.close(0_u32.into(), b"authenticated live consumer finished");
    endpoint.wait_idle().await;
    emit(&format!(
        "{{\"event\":\"consumer_finished\",\"clients\":1,\"elapsed_seconds\":{},\"records\":{records},\"cache_entries\":{},\"received_payload_bytes\":{received},\"reused\":{reused},\"not_ready\":{not_ready},\"inventory_records\":{inventory_records},\"pressure_verified\":false,\"success\":{}}}",
        started.elapsed().as_secs_f64(),
        held.len(),
        result.is_ok()
    ));
    result
}
