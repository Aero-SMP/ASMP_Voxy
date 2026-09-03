//! Direct QUIC endpoint and persistent certificate identity.

use crate::{
    anvil::AnvilWorld,
    crc::crc32c,
    quarantine,
    regional::{
        RegionalAnnouncement, RegionalResponder, RegionalService,
        wire::{
            ALPN, ControlMessage, STREAM_CONTROL, STREAM_SECTION_LANE, encode_control_record,
            read_control, read_lane, read_request_batch, read_stream_role, write_control,
            write_reply_batch,
        },
    },
    replace_synced, sync_parent,
};
use anyhow::{Context, Result, bail};
use quinn::{Endpoint, IdleTimeout, VarInt, crypto::rustls::QuicServerConfig};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, HashSet},
    fs,
    future::Future,
    io::Write,
    net::SocketAddr,
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};

const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
const TERMINAL_CONTROL_WRITE_TIMEOUT: Duration = Duration::from_secs(1);
const TERMINAL_CONTROL_DRAIN_TIMEOUT: Duration = Duration::from_millis(250);
const SERVICE_SHUTDOWN_GRACE: Duration = Duration::from_millis(500);
const ENDPOINT_DRAIN_TIMEOUT: Duration = Duration::from_millis(250);
const CONTROL_STREAM_PRIORITY: i32 = 3;
const CONTROL_WRITE_TIMEOUT: Duration = Duration::from_secs(15);
const SECTION_HEADER_TIMEOUT: Duration = Duration::from_secs(5);
// Fixed unauthenticated-protocol admission bounds. These limit task/handshake amplification;
// they are not a configurable server-wide memory governor.
const MAX_PENDING_HANDSHAKES: usize = 128;
const MAX_LIVE_CONNECTIONS: usize = 1_024;
const MAX_SUBSCRIBED_REGIONS: usize = 16_384;
const IDLE_TIMEOUT: Duration = Duration::from_secs(60);
const KEEPALIVE_INTERVAL: Duration = Duration::from_secs(15);
const MAX_IDENTITY_BYTES: usize = 64 * 1024;
const CERTIFICATE_FILE: &str = "certificate.der";
const PRIVATE_KEY_FILE: &str = "private-key.der";

#[derive(Debug)]
pub struct ServerState {
    server_instance: u64,
    regional: Arc<RegionalService>,
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
        }
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
    let endpoint = Endpoint::server(server_config, listen)
        .with_context(|| format!("bind Voxy QUIC UDP endpoint {listen}"))?;
    let actual = endpoint.local_addr()?;
    eprintln!(
        "VOXY_READY udp_port={} alpn={} cert_sha256={}",
        actual.port(),
        std::str::from_utf8(ALPN).expect("ALPN is static ASCII"),
        identity.fingerprint
    );

    let pending_handshakes = Arc::new(tokio::sync::Semaphore::new(MAX_PENDING_HANDSHAKES));
    let live_connections = Arc::new(tokio::sync::Semaphore::new(MAX_LIVE_CONNECTIONS));
    tokio::pin!(shutdown);
    loop {
        tokio::select! {
            incoming = endpoint.accept() => {
                let Some(incoming) = incoming else {
                    return Ok(());
                };
                let Ok(live_permit) = live_connections.clone().try_acquire_owned() else {
                    incoming.refuse();
                    continue;
                };
                let Ok(handshake_permit) = pending_handshakes.clone().try_acquire_owned() else {
                    incoming.refuse();
                    continue;
                };
                let peer = incoming.remote_address();
                let state = state.clone();
                tokio::spawn(async move {
                    let result = serve_connection(state, incoming, handshake_permit, live_permit).await;
                    if let Err(error) = result {
                        eprintln!("Voxy QUIC client {peer} disconnected: {error:#}");
                    }
                });
            }
            result = &mut shutdown => {
                result?;
                state.regional.shutdown("Voxy server shutting down");
                tokio::time::sleep(SERVICE_SHUTDOWN_GRACE).await;
                endpoint.close(VarInt::from_u32(0), b"Voxy server shutting down");
                let _ = tokio::time::timeout(ENDPOINT_DRAIN_TIMEOUT, endpoint.wait_idle()).await;
                return Ok(());
            }
        }
    }
}

async fn serve_connection(
    state: Arc<ServerState>,
    incoming: quinn::Incoming,
    handshake_permit: tokio::sync::OwnedSemaphorePermit,
    live_permit: tokio::sync::OwnedSemaphorePermit,
) -> Result<()> {
    let connection = tokio::time::timeout(HANDSHAKE_TIMEOUT, incoming)
        .await
        .context("QUIC handshake timeout")??;
    let (mut send, mut recv) = tokio::time::timeout(HANDSHAKE_TIMEOUT, connection.accept_bi())
        .await
        .context("control-stream timeout")??;
    send.set_priority(CONTROL_STREAM_PRIORITY)
        .context("set QUIC control-stream priority")?;
    let handshake = tokio::time::timeout(HANDSHAKE_TIMEOUT, async {
        if read_stream_role(&mut recv).await? != Some(STREAM_CONTROL) {
            bail!("the first bidirectional stream must be the control stream");
        }
        match read_control(&mut recv).await? {
            Some(ControlMessage::Hello { dimension }) => Ok(dimension),
            Some(_) => bail!("HELLO must be the first control message"),
            None => bail!("control stream ended before HELLO"),
        }
    })
    .await;
    let dimension = match handshake {
        Ok(Ok(dimension)) => dimension,
        Ok(Err(error)) => {
            write_terminal_control(
                &mut send,
                &ControlMessage::Error {
                    code: 1,
                    message: error.to_string(),
                },
            )
            .await;
            return Err(error);
        }
        Err(_) => {
            let error = anyhow::anyhow!("control HELLO timeout");
            write_terminal_control(
                &mut send,
                &ControlMessage::Error {
                    code: 1,
                    message: error.to_string(),
                },
            )
            .await;
            return Err(error);
        }
    };
    drop(handshake_permit);
    let responder = state
        .regional
        .responder(&dimension, state.server_instance)?;
    let result = serve_regional_connection(
        state.regional.clone(),
        responder,
        connection,
        send,
        recv,
        dimension,
    )
    .await;
    drop(live_permit);
    result
}

async fn serve_regional_connection(
    service: Arc<RegionalService>,
    responder: RegionalResponder,
    connection: quinn::Connection,
    mut send: quinn::SendStream,
    mut recv: quinn::RecvStream,
    dimension: String,
) -> Result<()> {
    write_control_timeout(&mut send, &responder.hello()?).await?;
    let mut announcements = service.subscribe();
    let mut subscribed_regions = HashSet::new();
    let result = async {
        loop {
            tokio::select! {
            incoming = connection.accept_bi() => {
                let (section_send, section_recv) = incoming.context("accept regional section lane")?;
                let lane_responder = responder.clone();
                tokio::spawn(async move {
                    if let Err(error) = serve_section_lane(lane_responder, section_send, section_recv).await {
                        eprintln!("regional section lane ended: {error:#}");
                    }
                });
            }
            control = read_control(&mut recv) => {
                let Some(control) = control? else {
                    return Ok(());
                };
                let response = match control {
                    ControlMessage::RegionRequest { region_x, region_z } => {
                        if !subscribed_regions.contains(&(region_x, region_z))
                                && subscribed_regions.len() == MAX_SUBSCRIBED_REGIONS {
                            bail!("regional subscription set exceeded its safety bound");
                        }
                        if subscribed_regions.insert((region_x, region_z)) {
                            responder.subscribe_region(region_x, region_z)?;
                        }
                        Some(responder.region(region_x, region_z)?)
                    }
                    ControlMessage::RegionRelease { region_x, region_z } => {
                        if subscribed_regions.remove(&(region_x, region_z)) {
                            responder.unsubscribe_region(region_x, region_z)?;
                        }
                        None
                    }
                    ControlMessage::CatalogRequest => Some(responder.catalog_response()?),
                    _ => bail!("client sent a server-only or duplicate regional control record"),
                };
                if let Some(response) = response {
                    write_control_timeout(&mut send, &response).await?;
                }
            }
            announcement = announcements.recv() => {
                let announcement = match announcement {
                    Ok(value) => value,
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(skipped)) => {
                        bail!("regional client missed {skipped} current-state announcements");
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => return Ok(()),
                };
                let message = match announcement {
                    RegionalAnnouncement::Changed {
                        dimension: changed, region_x, region_z, generation,
                    } if changed == dimension
                            && subscribed_regions.contains(&(region_x, region_z)) => Some(ControlMessage::RegionChanged {
                        region_x, region_z, generation,
                    }),
                    RegionalAnnouncement::Shutdown(message) => {
                        write_control_timeout(&mut send, &ControlMessage::Shutdown { message }).await?;
                        return Ok(());
                    }
                    _ => None,
                };
                if let Some(message) = message {
                    write_control_timeout(&mut send, &message).await?;
                }
            }
            }
        }
    }.await;
    for (region_x, region_z) in subscribed_regions {
        if let Err(error) = responder.unsubscribe_region(region_x, region_z) {
            eprintln!(
                "cannot release disconnected regional subscription ({region_x},{region_z}): {error:#}"
            );
        }
    }
    result
}

async fn serve_section_lane(
    responder: RegionalResponder,
    mut send: quinn::SendStream,
    mut recv: quinn::RecvStream,
) -> Result<()> {
    let role = tokio::time::timeout(SECTION_HEADER_TIMEOUT, read_stream_role(&mut recv))
        .await
        .context("regional section-lane role timeout")??;
    if role != Some(STREAM_SECTION_LANE) {
        bail!("non-section role on a regional section lane");
    }
    let lane = tokio::time::timeout(SECTION_HEADER_TIMEOUT, read_lane(&mut recv))
        .await
        .context("regional section-lane priority timeout")??;
    send.set_priority(match lane {
        crate::regional::wire::PriorityLane::Coverage => 2,
        crate::regional::wire::PriorityLane::Refinement => 1,
    })?;
    while let Some(request) = read_request_batch(&mut recv).await? {
        let responder = responder.clone();
        let (sender, mut receiver) = tokio::sync::mpsc::channel(2);
        let response_worker = tokio::task::spawn_blocking(move || {
            responder.sections(&request, |response| {
                sender
                    .blocking_send(response)
                    .map_err(|_| anyhow::anyhow!("regional section lane closed"))
            })
        });
        while let Some(response) = receiver.recv().await {
            write_reply_batch(&mut send, &response).await?;
        }
        response_worker
            .await
            .context("regional section response task failed")??;
    }
    send.finish()?;
    Ok(())
}

async fn write_control_timeout(
    send: &mut quinn::SendStream,
    message: &ControlMessage,
) -> Result<()> {
    tokio::time::timeout(CONTROL_WRITE_TIMEOUT, write_control(send, message))
        .await
        .context("regional control write timeout")??;
    Ok(())
}

async fn write_terminal_control(send: &mut quinn::SendStream, message: &ControlMessage) {
    let Ok(record) = encode_control_record(message) else {
        let _ = send.reset(VarInt::from_u32(1));
        return;
    };
    match tokio::time::timeout(TERMINAL_CONTROL_WRITE_TIMEOUT, send.write_all(&record)).await {
        Ok(Ok(())) => drain_terminal_control(send).await,
        Ok(Err(_)) | Err(_) => {
            let _ = send.reset(VarInt::from_u32(1));
        }
    }
}

async fn drain_terminal_control(send: &mut quinn::SendStream) {
    if send.finish().is_ok() {
        let _ = tokio::time::timeout(TERMINAL_CONTROL_DRAIN_TIMEOUT, send.stopped()).await;
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
    server.transport_config(Arc::new(transport));
    Ok(server)
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
