use anyhow::Result;
use quinn::{Endpoint, VarInt, crypto::rustls::QuicServerConfig};
use rustls::pki_types::{CertificateDer, PrivatePkcs8KeyDer};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    fs,
    net::SocketAddr,
    path::PathBuf,
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncBufReadExt, AsyncReadExt},
    sync::{Mutex, watch},
};

struct Server {
    data: PathBuf,
    world: Vec<u8>,
    jobs: Mutex<HashMap<String, watch::Sender<bool>>>,
}
fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}
fn hash(bytes: &[u8]) -> String {
    hex(&Sha256::digest(bytes))
}
fn phase(
    remote: SocketAddr,
    cid: quinn::ConnectionId,
    started: Instant,
    name: &str,
    connection: Option<&quinn::Connection>,
) {
    if let Some(connection) = connection {
        let stats = connection.stats();
        eprintln!(
            "VOXY_CONNECTION remote={remote} cid={cid} phase={name} elapsed_ms={} tx_udp={} rx_udp={} tx_crypto={} rx_crypto={} tx_ack={} rx_ack={} tx_stream={} rx_stream={} rx_ping={} rtt_ms={:.3} cwnd={}",
            started.elapsed().as_millis(),
            stats.udp_tx.datagrams,
            stats.udp_rx.datagrams,
            stats.frame_tx.crypto,
            stats.frame_rx.crypto,
            stats.frame_tx.acks,
            stats.frame_rx.acks,
            stats.frame_tx.stream,
            stats.frame_rx.stream,
            stats.frame_rx.ping,
            stats.path.rtt.as_secs_f64() * 1000.,
            stats.path.cwnd
        );
    } else {
        eprintln!(
            "VOXY_CONNECTION remote={remote} cid={cid} phase={name} elapsed_ms={}",
            started.elapsed().as_millis()
        );
    }
}
impl Server {
    async fn request(
        &self,
        ticket: &str,
        dimension: &str,
        level: u8,
        x: i32,
        y: i32,
        z: i32,
    ) -> watch::Receiver<bool> {
        let mut jobs = self.jobs.lock().await;
        jobs.entry(ticket.into())
            .or_insert_with(|| {
                println!("VOXY_NEED {dimension} {level} {x} {y} {z}");
                watch::channel(false).0
            })
            .subscribe()
    }
    async fn completed(self: Arc<Self>) {
        let mut lines = tokio::io::BufReader::new(tokio::io::stdin()).lines();
        while let Ok(Some(ticket)) = lines.next_line().await {
            if let Some(job) = self.jobs.lock().await.remove(&ticket) {
                job.send_replace(true);
            }
        }
    }
    async fn connection(self: Arc<Self>, incoming: quinn::Incoming) -> Result<()> {
        let remote = incoming.remote_address();
        let cid = incoming.orig_dst_cid();
        let started = Instant::now();
        phase(remote, cid, started, "accepted", None);
        let mut observed = None;
        let result: Result<()> = async {
            let connection = incoming.await?;
            observed = Some(connection.clone());
            phase(remote, cid, started, "tls_complete", observed.as_ref());
            let (mut send, mut receive) = connection.accept_bi().await?;
            phase(remote, cid, started, "bidi_accepted", observed.as_ref());
            let length = receive.read_u16_le().await?;
            let mut bytes = vec![0; length as usize];
            receive.read_exact(&mut bytes).await?;
            let dimension = String::from_utf8(bytes)?;
            anyhow::ensure!(
                dimension.contains(':')
                    && dimension.bytes().all(|c| c.is_ascii_lowercase()
                        || c.is_ascii_digit()
                        || b"_.:/-".contains(&c)),
                "Invalid dimension"
            );
            phase(remote, cid, started, "dimension_parsed", observed.as_ref());
            let namespace = hash(dimension.as_bytes());
            let directory = self.data.join("records").join(&namespace);
            send.write_all(&self.world).await?;
            phase(
                remote,
                cid,
                started,
                "world_write_complete",
                observed.as_ref(),
            );
            let mut first_get = true;
            while let Ok(command) = receive.read_u8().await {
                anyhow::ensure!(command == 0, "Invalid terrain command");
                let level = receive.read_u8().await?;
                let (x, y, z) = (
                    receive.read_i32_le().await?,
                    receive.read_i32_le().await?,
                    receive.read_i32_le().await?,
                );
                anyhow::ensure!(level <= 4, "Invalid LOD");
                let mut known = [0; 32];
                receive.read_exact(&mut known).await?;
                if first_get {
                    phase(remote, cid, started, "first_get_parsed", observed.as_ref());
                    first_get = false;
                }
                let filename = format!("{level}_{x}_{y}_{z}.vxs");
                let shift = 4 - level;
                let path = directory
                    .join(format!("r_{}_{}_{}", x >> shift, y >> shift, z >> shift))
                    .join(&filename);
                let ticket = format!("{namespace}_{filename}");
                let mut record = tokio::fs::File::open(&path).await;
                let mut job = self.request(&ticket, &dimension, level, x, y, z).await;
                if record
                    .as_ref()
                    .is_err_and(|e| e.kind() == std::io::ErrorKind::NotFound)
                {
                    tokio::select! {
                        _ = job.wait_for(|done| *done) => {}
                        _ = connection.closed() => return Ok(()),
                    }
                    record = tokio::fs::File::open(&path).await;
                }
                match record {
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                        send.write_all(&[0]).await?
                    }
                    Err(e) => return Err(e.into()),
                    Ok(mut file) => {
                        let length = file.metadata().await?.len();
                        anyhow::ensure!(
                            length > 32 && length - 32 <= u32::MAX as u64,
                            "Invalid published record"
                        );
                        let mut digest = [0; 32];
                        file.read_exact(&mut digest).await?;
                        if digest == known {
                            send.write_all(&[1]).await?;
                        } else {
                            send.write_all(&[2]).await?;
                            send.write_all(&((length - 32) as u32).to_le_bytes())
                                .await?;
                            tokio::io::copy(&mut file, &mut send).await?;
                        }
                    }
                }
            }
            send.finish()?;
            Ok(())
        }
        .await;
        if let Err(error) = &result {
            phase(
                remote,
                cid,
                started,
                &format!("error reason={error:#}"),
                observed.as_ref(),
            );
        } else {
            phase(remote, cid, started, "closed", observed.as_ref());
        }
        result
    }
}
#[tokio::main]
async fn main() -> Result<()> {
    let arguments: Vec<_> = std::env::args().skip(1).collect();
    let [world_flag, _, data_flag, data, listen_flag, listen] = arguments.as_slice() else {
        anyhow::bail!("Usage: voxy-server --world WORLD --data DATA --listen SOCKET");
    };
    anyhow::ensure!(
        world_flag == "--world" && data_flag == "--data" && listen_flag == "--listen",
        "Invalid native arguments"
    );
    let data = PathBuf::from(data);
    let listen: SocketAddr = listen.parse()?;
    fs::create_dir_all(data.join("quic"))?;
    let world_file = data.join("world.id");
    if !world_file.exists() {
        let mut id = [0; 16];
        std::io::Read::read_exact(&mut fs::File::open("/dev/urandom")?, &mut id)?;
        fs::write(&world_file, id)?;
    }
    let world = fs::read(world_file)?;
    anyhow::ensure!(world.len() == 16, "Invalid world identity");
    let certificate_path = data.join("quic/server-cert.der");
    let key_path = data.join("quic/server-key.der");
    if !certificate_path.exists() && !key_path.exists() {
        let generated = rcgen::generate_simple_self_signed(vec!["voxy.local".into()])?;
        fs::write(&key_path, generated.key_pair.serialize_der())?;
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&key_path, fs::Permissions::from_mode(0o600))?;
        fs::write(&certificate_path, generated.cert.der())?;
    }
    let certificate = fs::read(certificate_path)?;
    let mut tls = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(
            vec![CertificateDer::from(certificate.clone())],
            PrivatePkcs8KeyDer::from(fs::read(key_path)?).into(),
        )?;
    tls.alpn_protocols = vec![b"voxy".to_vec()];
    let mut config = quinn::ServerConfig::with_crypto(Arc::new(QuicServerConfig::try_from(tls)?));
    let transport = Arc::get_mut(&mut config.transport).unwrap();
    transport.congestion_controller_factory(Arc::new(quinn::congestion::BbrConfig::default()));
    transport.max_concurrent_bidi_streams(VarInt::from_u32(1));
    transport.max_concurrent_uni_streams(VarInt::from_u32(0));
    transport.max_idle_timeout(Some(Duration::from_secs(300).try_into()?));
    let endpoint = Endpoint::server(config, listen)?;
    let server = Arc::new(Server {
        data,
        world,
        jobs: Mutex::new(HashMap::new()),
    });
    tokio::spawn(server.clone().completed());
    println!(
        "VOXY_READY udp_port={} alpn=voxy cert_sha256={}",
        endpoint.local_addr()?.port(),
        hash(&certificate)
    );
    loop {
        tokio::select! {
            incoming = endpoint.accept() => {
                let Some(incoming) = incoming else {
                    break;
                };
                let server = server.clone();
                tokio::spawn(async move {
                    if let Err(error) = server.connection(incoming).await {
                        eprintln!("VOXY_CLIENT_CLOSED {error:#}");
                    }
                });
            }
            _ = tokio::signal::ctrl_c() => {
                endpoint.close(VarInt::from_u32(0), b"shutdown");
                break;
            }
        }
    }
    Ok(())
}
