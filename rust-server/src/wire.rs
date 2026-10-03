use crate::{
    Backend,
    payload::{Key, hash, hex},
};
use anyhow::{Context, Result};
use quinn::{Endpoint, VarInt, crypto::rustls::QuicServerConfig};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use std::{fs, net::SocketAddr, sync::Arc, time::Duration};
use tokio::io::AsyncReadExt;

pub const ALPN: &[u8] = b"voxy-rewrite-1";

pub async fn serve(backend: Arc<Backend>, listen: SocketAddr) -> Result<()> {
    let identity = backend.data.join("quic");
    fs::create_dir_all(&identity)?;
    let certificate_path = identity.join("server-cert.der");
    let key_path = identity.join("server-key.der");
    if !certificate_path.exists() && !key_path.exists() {
        let certified = rcgen::generate_simple_self_signed(vec!["voxy.local".into()])?;
        crate::store::atomic_write(&key_path, &certified.key_pair.serialize_der())?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&key_path, fs::Permissions::from_mode(0o600))?;
        }
        crate::store::atomic_write(&certificate_path, certified.cert.der())?;
    }
    let certificate = fs::read(&certificate_path)?;
    let private_key = PrivatePkcs8KeyDer::from(fs::read(&key_path)?);
    let mut tls = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(
            vec![CertificateDer::from(certificate.clone())],
            PrivateKeyDer::from(private_key),
        )?;
    tls.alpn_protocols = vec![ALPN.to_vec()];
    let mut config = quinn::ServerConfig::with_crypto(Arc::new(QuicServerConfig::try_from(tls)?));
    let transport = Arc::get_mut(&mut config.transport).unwrap();
    // Terrain replies and their lazy catalog dependencies have separate streams
    // so a receiver can resolve a catalog while the terrain stream backpressures.
    transport.max_concurrent_bidi_streams(VarInt::from_u32(2));
    transport.max_concurrent_uni_streams(VarInt::from_u32(0));
    transport.keep_alive_interval(Some(Duration::from_secs(15)));
    transport.max_idle_timeout(Some(Duration::from_secs(300).try_into()?));
    let endpoint = Endpoint::server(config, listen)?;
    println!(
        "VOXY_READY udp_port={} alpn=voxy-rewrite-1 cert_sha256={}",
        endpoint.local_addr()?.port(),
        hex(&hash(&certificate))
    );
    loop {
        tokio::select! {
            incoming = endpoint.accept() => {
                let Some(incoming) = incoming else { break }; let backend = backend.clone();
                tokio::spawn(async move { if let Err(error) = connection(backend, incoming).await { eprintln!("VOXY_CLIENT_CLOSED {error:#}"); } });
            }
            _ = tokio::signal::ctrl_c() => { endpoint.close(VarInt::from_u32(0), b"shutdown"); break; }
        }
    }
    Ok(())
}

async fn connection(backend: Arc<Backend>, incoming: quinn::Incoming) -> Result<()> {
    let connection = incoming.await?;
    let (mut send, mut receive) = connection.accept_bi().await?;
    let dimension = hello(&backend, &mut send, &mut receive).await?;
    spawn_commands(backend.clone(), dimension.clone(), send, receive);
    while let Ok((mut send, mut receive)) = connection.accept_bi().await {
        let next = hello(&backend, &mut send, &mut receive).await?;
        anyhow::ensure!(next == dimension, "stream dimension changed");
        spawn_commands(backend.clone(), dimension.clone(), send, receive);
    }
    Ok(())
}

async fn hello(
    backend: &Backend,
    send: &mut quinn::SendStream,
    receive: &mut quinn::RecvStream,
) -> Result<String> {
    let length = receive.read_u16_le().await?;
    let mut dimension = vec![0; length as usize];
    receive.read_exact(&mut dimension).await?;
    let dimension = String::from_utf8(dimension)?;
    anyhow::ensure!(
        backend.dimensions.contains_key(&dimension),
        "unknown dimension {dimension}"
    );
    send.write_all(&backend.world_id).await?;
    Ok(dimension)
}

fn spawn_commands(
    backend: Arc<Backend>,
    dimension: String,
    send: quinn::SendStream,
    receive: quinn::RecvStream,
) {
    tokio::spawn(async move {
        if let Err(error) = commands(backend, dimension, send, receive).await {
            eprintln!("VOXY_STREAM_CLOSED {error:#}");
        }
    });
}

async fn commands(
    backend: Arc<Backend>,
    dimension: String,
    mut send: quinn::SendStream,
    mut receive: quinn::RecvStream,
) -> Result<()> {
    while let Ok(command) = receive.read_u8().await {
        match command {
            0 => {
                let key = Key {
                    level: receive.read_u8().await?,
                    x: receive.read_i32_le().await?,
                    y: receive.read_i32_le().await?,
                    z: receive.read_i32_le().await?,
                };
                let mut known = [0; 32];
                receive.read_exact(&mut known).await?;
                match backend.section(&dimension, key).await? {
                    None => send.write_all(&[0]).await?,
                    Some(frame) if hash(&frame) == known => send.write_all(&[1]).await?,
                    Some(frame) => {
                        send.write_all(&[2]).await?;
                        send.write_all(&(frame.len() as u32).to_le_bytes()).await?;
                        send.write_all(&frame).await?;
                    }
                }
            }
            1 => {
                let mut id = [0; 32];
                receive.read_exact(&mut id).await?;
                let path = backend
                    .data
                    .join("catalogs")
                    .join(format!("{}.vxc", hex(&id)));
                let bytes = match tokio::fs::read(path).await {
                    Ok(bytes) => bytes,
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => Vec::new(),
                    Err(e) => return Err(e.into()),
                };
                send.write_all(&(bytes.len() as u32).to_le_bytes()).await?;
                send.write_all(&bytes).await?;
            }
            _ => anyhow::bail!("unknown command {command}"),
        }
    }
    send.finish().context("finish stream")?;
    Ok(())
}
