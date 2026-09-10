use super::*;
use std::{cell::Cell, time::Instant};

const SHORT: Duration = Duration::from_millis(500);
thread_local! { static PROGRESS: Cell<(usize, usize)> = const { Cell::new((0, 0)) }; }
pub(super) fn record_progress(bytes: usize) {
    PROGRESS.with(|p| {
        let (calls, total) = p.get();
        p.set((calls + 1, total + bytes));
    });
}

struct Pair {
    server: Endpoint,
    client: Endpoint,
    remote: quinn::Connection,
    local: quinn::Connection,
}
impl Pair {
    fn endpoints() -> Result<(Endpoint, Endpoint)> {
        let cert = rcgen::generate_simple_self_signed(vec!["voxy.local".into()])?;
        let identity =
            PersistentIdentity::new(cert.cert.der().to_vec(), cert.key_pair.serialize_der())?;
        let mut config = make_server_config(&identity)?;
        // Test-only short keepalives; production transport windows and priorities stay intact.
        Arc::get_mut(&mut config.transport)
            .unwrap()
            .keep_alive_interval(Some(Duration::from_millis(30)));
        let server = Endpoint::server(config, "127.0.0.1:0".parse()?)?;
        Ok((server, Self::client(&identity)?))
    }
    fn client(identity: &PersistentIdentity) -> Result<Endpoint> {
        let mut roots = rustls::RootCertStore::empty();
        roots.add(CertificateDer::from(identity.certificate.clone()))?;
        let mut tls = rustls::ClientConfig::builder()
            .with_root_certificates(roots)
            .with_no_client_auth();
        tls.alpn_protocols = vec![ALPN.to_vec()];
        let mut config = quinn::ClientConfig::new(Arc::new(
            quinn::crypto::rustls::QuicClientConfig::try_from(tls)?,
        ));
        let mut transport = quinn::TransportConfig::default();
        transport.stream_receive_window(VarInt::from_u32(8192));
        transport.receive_window(VarInt::from_u32(1024 * 1024));
        config.transport_config(Arc::new(transport));
        let mut client = Endpoint::client("127.0.0.1:0".parse()?)?;
        client.set_default_client_config(config);
        Ok(client)
    }
    async fn new() -> Result<Self> {
        let (server, client) = Self::endpoints()?;
        let (local, remote) = tokio::try_join!(
            async {
                Ok::<_, anyhow::Error>(client.connect(server.local_addr()?, "voxy.local")?.await?)
            },
            async {
                Ok::<_, anyhow::Error>(
                    server
                        .accept()
                        .await
                        .context("incoming endpoint closed")?
                        .await?,
                )
            },
        )?;
        Ok(Self {
            server,
            client,
            remote,
            local,
        })
    }
    async fn stream(&self) -> Result<(quinn::SendStream, quinn::RecvStream)> {
        let (mut request, recv) = self.local.open_bi().await?;
        request.write_all(&[STREAM_CONTROL]).await?;
        let (send, mut incoming) = self.remote.accept_bi().await?;
        assert_eq!(read_stream_role(&mut incoming).await?, Some(STREAM_CONTROL));
        send.set_priority(CONTROL_STREAM_PRIORITY)?;
        Ok((send, recv))
    }
}
impl Drop for Pair {
    fn drop(&mut self) {
        self.client.close(VarInt::from_u32(0), b"test complete");
        self.server.close(VarInt::from_u32(0), b"test complete");
    }
}

fn message() -> ControlMessage {
    ControlMessage::Catalog {
        fingerprint: [37; 32],
        canonical: (0..128 * 1024)
            .map(|i| ((i * 17 + i / 251) % 256) as u8)
            .collect(),
    }
}
async fn watchdog<T>(future: impl Future<Output = Result<T>>) -> Result<T> {
    tokio::time::timeout(Duration::from_secs(50), future)
        .await
        .context("TEST WATCHDOG expired (not a write-timeout result)")?
}

async fn paced_transfer(deadline: Duration, pace: Duration, old: bool) -> Result<()> {
    let pair = Pair::new().await?;
    let (mut send, mut recv) = pair.stream().await?;
    let first = message();
    let second = ControlMessage::RegionChanged {
        region_x: -3,
        region_z: 17,
        generation: 1234567,
    };
    let expected = encode_control_record(&first)?;
    PROGRESS.with(|p| p.set((0, 0)));
    let writer = async {
        let start = Instant::now();
        let result = if old {
            // Test-only baseline comparison; never used by production connections.
            tokio::time::timeout(deadline, send.write_all(&expected))
                .await
                .context("old total deadline")
                .and_then(|r| r.map_err(Into::into))
        } else {
            write_control(&mut send, &first, deadline).await
        };
        let elapsed = start.elapsed();
        let counts = PROGRESS.with(Cell::get);
        if result.is_ok() {
            write_control(&mut send, &second, deadline).await?;
        }
        send.finish()?;
        Ok::<_, anyhow::Error>((result, elapsed, counts))
    };
    let reader = async {
        let mut bytes = Vec::new();
        let mut buffer = [0u8; 4096];
        while let Some(count) = recv.read(&mut buffer).await? {
            bytes.extend_from_slice(&buffer[..count]);
            tokio::time::sleep(pace).await;
        }
        Ok::<_, anyhow::Error>(bytes)
    };
    let ((result, elapsed, counts), bytes) = tokio::try_join!(writer, reader)?;
    eprintln!(
        "paced old={old} deadline_ms={} elapsed_ms={} observed_partial_writes={:?} observed_accepted_bytes={:?} received_bytes={}",
        deadline.as_millis(),
        elapsed.as_millis(),
        (!old).then_some(counts.0),
        (!old).then_some(counts.1),
        bytes.len()
    );
    if old {
        assert!(result.unwrap_err().is::<tokio::time::error::Elapsed>());
        assert!(bytes.len() < expected.len());
        assert_eq!(&expected[..bytes.len()], bytes.as_slice());
    } else {
        result?;
        assert!(elapsed > deadline);
        assert!(counts.0 > 2);
        assert_eq!(counts.1, expected.len());
        let mut input = bytes.as_slice();
        assert_eq!(read_control(&mut input).await?, Some(first));
        assert_eq!(read_control(&mut input).await?, Some(second));
        assert_eq!(read_control(&mut input).await?, None);
    }
    Ok(())
}

#[tokio::test]
async fn progressing_record_exceeds_deadline_and_preserves_framing() -> Result<()> {
    watchdog(paced_transfer(SHORT, Duration::from_millis(60), false)).await
}

#[tokio::test]
async fn old_total_deadline_rejects_the_same_progressing_fixture() -> Result<()> {
    watchdog(paced_transfer(SHORT, Duration::from_millis(60), true)).await
}

#[tokio::test]
async fn real_fifteen_second_deadline_accepts_longer_progressing_record() -> Result<()> {
    watchdog(paced_transfer(
        CONTROL_WRITE_PROGRESS_TIMEOUT,
        Duration::from_millis(650),
        false,
    ))
    .await
}

#[tokio::test]
async fn stalled_or_trickling_reader_times_out_despite_keepalives() -> Result<()> {
    watchdog(async {
        for trickle in [false, true] {
            let pair = Pair::new().await?;
            let (mut send, mut recv) = pair.stream().await?;
            let message = message();
            let mut bytes = 0;
            let start = Instant::now();
            let write = write_control(&mut send, &message, SHORT);
            tokio::pin!(write);
            let result = loop {
                tokio::select! {
                    result = &mut write => break result,
                    _ = tokio::time::sleep(Duration::from_millis(60)), if trickle => {
                        let mut buffer = [0u8; 32];
                        bytes += recv.read(&mut buffer).await?.unwrap_or(0);
                    }
                }
            };
            assert!(result.unwrap_err().is::<tokio::time::error::Elapsed>());
            assert!(start.elapsed() >= SHORT && start.elapsed() < Duration::from_secs(3));
            assert!(pair.local.close_reason().is_none());
            assert!(pair.local.stats().frame_rx.ping > 0);
            if trickle {
                assert!(bytes > 0 && bytes < 8192);
            }
            eprintln!(
                "stalled trickle={trickle} consumed={bytes} elapsed_ms={}",
                start.elapsed().as_millis()
            );
        }
        Ok(())
    })
    .await
}

#[tokio::test]
async fn pause_resume_or_timeout_never_replays_a_prefix() -> Result<()> {
    watchdog(async {
        for long_pause in [false, true] {
            let pair = Pair::new().await?;
            let (mut send, mut recv) = pair.stream().await?;
            let message = message();
            let expected = encode_control_record(&message)?;
            let writer = async {
                let result = write_control(&mut send, &message, SHORT).await;
                send.finish()?;
                Ok::<_, anyhow::Error>(result)
            };
            let reader = async {
                let mut bytes = vec![0u8; 16 * 1024];
                recv.read_exact(&mut bytes).await?;
                tokio::time::sleep(if long_pause { SHORT * 2 } else { SHORT / 3 }).await;
                bytes.extend(recv.read_to_end(256 * 1024).await?);
                Ok::<_, anyhow::Error>(bytes)
            };
            let (result, bytes) = tokio::try_join!(writer, reader)?;
            assert_eq!(&expected[..bytes.len()], bytes.as_slice());
            if long_pause {
                assert!(result.unwrap_err().is::<tokio::time::error::Elapsed>());
                assert!(bytes.len() < expected.len());
            } else {
                result?;
                assert_eq!(bytes, expected);
            }
        }
        Ok(())
    })
    .await
}

#[tokio::test]
async fn pending_write_cancellation_reset_and_disconnect_finish_promptly() -> Result<()> {
    watchdog(async {
        for mode in 0..3 {
            let pair = Pair::new().await?;
            let (mut send, mut recv) = pair.stream().await?;
            let message = message();
            let expected = encode_control_record(&message)?;
            let mut write = Box::pin(write_control(&mut send, &message, SHORT * 4));
            let mut prefix = [0; 1024];
            tokio::select! {
                result = &mut write => panic!("writer completed before cancellation: {result:?}"),
                result = recv.read_exact(&mut prefix) => result?,
            }
            assert_eq!(prefix.as_slice(), &expected[..prefix.len()]);
            if mode == 0 {
                drop(write); // Cancels only the pending write; already-accepted bytes remain.
                send.finish()?;
                let rest = recv.read_to_end(256 * 1024).await?;
                assert_eq!(
                    rest.as_slice(),
                    &expected[prefix.len()..prefix.len() + rest.len()]
                );
                assert!(prefix.len() + rest.len() < expected.len());
            } else {
                if mode == 1 {
                    recv.stop(VarInt::from_u32(7))?;
                } else {
                    pair.local
                        .close(VarInt::from_u32(8), b"test peer disconnect");
                }
                let error = tokio::time::timeout(Duration::from_secs(1), write)
                    .await?
                    .unwrap_err();
                assert!(error.is::<quinn::WriteError>());
            }
        }
        Ok(())
    })
    .await
}

#[tokio::test]
async fn unrelated_stream_cannot_extend_control_deadline() -> Result<()> {
    watchdog(async {
        let pair = Pair::new().await?;
        let (mut control, _blocked) = pair.stream().await?;
        let (mut other, mut reader) = pair.stream().await?;
        let message = message();
        let write = write_control(&mut control, &message, SHORT);
        tokio::pin!(write);
        let mut traffic = 0;
        let start = Instant::now();
        let result = loop {
            tokio::select! {
                result = &mut write => break result,
                _ = tokio::time::sleep(Duration::from_millis(30)) => {
                    other.write_all(&[7; 32]).await?;
                    let mut bytes = [0; 32]; reader.read_exact(&mut bytes).await?;
                    traffic += bytes.len();
                }
            }
        };
        assert!(traffic > 0);
        assert!(result.unwrap_err().is::<tokio::time::error::Elapsed>());
        assert!(start.elapsed() < Duration::from_secs(3));
        Ok(())
    })
    .await
}

#[tokio::test]
async fn invalid_record_is_rejected_before_any_bytes() -> Result<()> {
    watchdog(async {
        let pair = Pair::new().await?;
        let (mut send, mut recv) = pair.stream().await?;
        let invalid = ControlMessage::Hello {
            dimension: "x".repeat(crate::regional::wire::MAX_DIMENSION_BYTES + 1),
        };
        assert!(write_control(&mut send, &invalid, SHORT).await.is_err());
        let valid = ControlMessage::Shutdown {
            message: "ok".into(),
        };
        write_control(&mut send, &valid, SHORT).await?;
        send.finish()?;
        assert_eq!(read_control(&mut recv).await?, Some(valid));
        assert_eq!(read_control(&mut recv).await?, None);
        Ok(())
    })
    .await
}

struct ServiceFixture {
    root: PathBuf,
    state: Arc<ServerState>,
}

#[tokio::test]
async fn real_endpoint_shutdown_interrupts_blocked_control() -> Result<()> {
    watchdog(async {
        let fixture = ServiceFixture::new()?;
        let identity = load_or_create_identity(&fixture.root.join("identity"))?;
        let client = Pair::client(&identity)?;
        let socket = std::net::UdpSocket::bind("127.0.0.1:0")?;
        let address = socket.local_addr()?;
        drop(socket);
        let (stop, shutdown) = tokio::sync::oneshot::channel();
        let state = fixture.state.clone();
        let identity_path = fixture.root.join("identity");
        let mut endpoint = Task(tokio::spawn(async move {
            serve(state, address, &identity_path, async {
                shutdown.await?;
                Ok(())
            })
            .await
        }));
        let connection = client.connect(address, "voxy.local")?.await?;
        let (mut request, mut response) = connection.open_bi().await?;
        request.write_all(&[STREAM_CONTROL]).await?;
        send_request(
            &mut request,
            ControlMessage::Hello {
                dimension: "minecraft:overworld".into(),
            },
        )
        .await?;
        assert!(matches!(
            read_control(&mut response).await?,
            Some(ControlMessage::ServerHello { .. })
        ));
        send_request(
            &mut request,
            ControlMessage::RegionRequest {
                region_x: 0,
                region_z: 0,
            },
        )
        .await?;
        read_control(&mut response).await?;
        assert_eq!(fixture.subscriptions(), 1);
        send_request(&mut request, ControlMessage::CatalogRequest).await?;
        response.read_exact(&mut [0]).await?;
        let start = Instant::now();
        stop.send(()).unwrap();
        tokio::time::timeout(Duration::from_secs(3), &mut endpoint.0).await???;
        tokio::time::timeout(Duration::from_secs(1), async {
            while fixture.subscriptions() != 0 {
                tokio::task::yield_now().await;
            }
        })
        .await?;
        assert!(start.elapsed() < Duration::from_secs(3));
        assert!(connection.close_reason().is_some());
        eprintln!(
            "real serve shutdown elapsed_ms={} subscriptions=0",
            start.elapsed().as_millis()
        );
        client.close(VarInt::from_u32(0), b"done");
        Ok(())
    })
    .await
}
impl ServiceFixture {
    fn new() -> Result<Self> {
        use std::sync::{
            RwLock,
            atomic::{AtomicU64, Ordering},
        };
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let root = std::env::temp_dir().join(format!(
            "voxy-control-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(root.join("world/region"))?;
        let mut registry = crate::registry::Registry::open(root.join("registry"))?;
        for i in 0..2048 {
            registry.block_id(&format!("test:control_fixture_block_{i:04}"))?;
        }
        registry.save()?;
        let dimensions = BTreeMap::from([(
            "minecraft:overworld".into(),
            Arc::new(AnvilWorld::new(
                "minecraft:overworld".into(),
                root.join("world"),
            )),
        )]);
        let regional = Arc::new(RegionalService::open(
            root.join("data"),
            &dimensions,
            Arc::new(RwLock::new(registry)),
        )?);
        Ok(Self {
            root,
            state: Arc::new(ServerState {
                server_instance: 17,
                regional,
            }),
        })
    }
    fn subscriptions(&self) -> usize {
        self.state
            .regional
            .runtime("minecraft:overworld")
            .unwrap()
            .test_subscription_count()
    }
}
impl Drop for ServiceFixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.root);
    }
}

// Abort on test failure/watchdog as well as explicitly awaiting every normal completion.
struct Task<T>(tokio::task::JoinHandle<T>);
impl<T> Drop for Task<T> {
    fn drop(&mut self) {
        self.0.abort();
    }
}

async fn send_request(send: &mut quinn::SendStream, message: ControlMessage) -> Result<()> {
    send.write_all(&encode_control_record(&message)?).await?;
    Ok(())
}

#[tokio::test]
async fn actual_handler_stall_stop_disconnect_lag_and_endpoint_cleanup() -> Result<()> {
    watchdog(async {
        for mode in ["stall", "stop", "disconnect", "lag", "slow", "shutdown"] {
            let fixture = ServiceFixture::new()?;
            let (server, client) = Pair::endpoints()?;
            let pending = Arc::new(tokio::sync::Semaphore::new(1));
            let live = Arc::new(tokio::sync::Semaphore::new(1));
            let handshake_permit = pending.clone().acquire_owned().await?;
            let live_permit = live.clone().acquire_owned().await?;
            let endpoint = server.clone();
            let state = fixture.state.clone();
            let mut handler = Task(tokio::spawn(async move {
                let incoming = endpoint.accept().await.context("test endpoint closed")?;
                serve_connection(state, incoming, handshake_permit, live_permit).await
            }));
            let connection = client.connect(server.local_addr()?, "voxy.local")?.await?;
            let (mut request, mut response) = connection.open_bi().await?;
            request.write_all(&[STREAM_CONTROL]).await?;
            send_request(
                &mut request,
                ControlMessage::Hello {
                    dimension: "minecraft:overworld".into(),
                },
            )
            .await?;
            assert!(matches!(
                read_control(&mut response).await?,
                Some(ControlMessage::ServerHello { .. })
            ));
            send_request(
                &mut request,
                ControlMessage::RegionRequest {
                    region_x: 0,
                    region_z: 0,
                },
            )
            .await?;
            assert!(matches!(
                read_control(&mut response).await?,
                Some(ControlMessage::RegionUnavailable { .. })
            ));
            assert_eq!(fixture.subscriptions(), 1);
            assert_eq!(pending.available_permits(), 1);
            assert_eq!(live.available_permits(), 0);
            send_request(&mut request, ControlMessage::CatalogRequest).await?;
            let mut prefix = [0];
            response.read_exact(&mut prefix).await?;
            let start = Instant::now();
            match mode {
                "slow" => {
                    let expected_message = fixture
                        .state
                        .regional
                        .responder("minecraft:overworld", 17)?
                        .catalog_response()?;
                    let expected = encode_control_record(&expected_message)?;
                    let mut bytes = prefix.to_vec();
                    let mut buffer = [0; 4096];
                    while bytes.len() < expected.len() {
                        let count = response
                            .read(&mut buffer)
                            .await?
                            .context("catalog ended early")?;
                        bytes.extend_from_slice(&buffer[..count]);
                        if bytes.len() < expected.len() {
                            tokio::time::sleep(Duration::from_millis(1250)).await;
                        }
                    }
                    assert!(start.elapsed() > CONTROL_WRITE_PROGRESS_TIMEOUT);
                    assert_eq!(bytes, expected);
                    assert_eq!(
                        read_control(&mut bytes.as_slice()).await?,
                        Some(expected_message)
                    );
                    send_request(
                        &mut request,
                        ControlMessage::RegionRelease {
                            region_x: 0,
                            region_z: 0,
                        },
                    )
                    .await?;
                    send_request(
                        &mut request,
                        ControlMessage::RegionRequest {
                            region_x: 1,
                            region_z: 0,
                        },
                    )
                    .await?;
                    assert!(matches!(
                        read_control(&mut response).await?,
                        Some(ControlMessage::RegionUnavailable { region_x: 1, .. })
                    ));
                    request.finish()?;
                }
                "stop" => response.stop(VarInt::from_u32(9))?,
                "disconnect" => connection.close(VarInt::from_u32(10), b"test cancellation"),
                "shutdown" => {
                    fixture.state.regional.shutdown("test service shutdown");
                    tokio::time::sleep(SERVICE_SHUTDOWN_GRACE).await;
                    server.close(VarInt::from_u32(0), b"test endpoint shutdown");
                    let _ = tokio::time::timeout(ENDPOINT_DRAIN_TIMEOUT, server.wait_idle()).await;
                }
                "lag" => {
                    fixture.state.regional.test_overflow_announcements();
                    let mut bytes = prefix.to_vec();
                    // Drain the stalled response, allowing the handler to observe Lagged.
                    let mut buffer = [0; 4096];
                    while let Ok(Some(count)) = response.read(&mut buffer).await {
                        bytes.extend_from_slice(&buffer[..count]);
                    }
                    // Lag is fatal: connection cleanup may discard the final buffered suffix.
                    // Whatever arrived must still be one exact prefix, never a replay/error append.
                    let expected = encode_control_record(
                        &fixture
                            .state
                            .regional
                            .responder("minecraft:overworld", 17)?
                            .catalog_response()?,
                    )?;
                    assert_eq!(bytes.as_slice(), &expected[..bytes.len()]);
                    eprintln!(
                        "lag received_prefix={} encoded_bytes={}",
                        bytes.len(),
                        expected.len()
                    );
                }
                _ => {}
            }
            let result = tokio::time::timeout(
                CONTROL_WRITE_PROGRESS_TIMEOUT + Duration::from_secs(3),
                &mut handler.0,
            )
            .await??;
            let outcome = format!("{result:?}");
            if mode == "slow" {
                result?;
            } else {
                let error = result.unwrap_err();
                if mode == "stall" {
                    assert!(error.is::<tokio::time::error::Elapsed>());
                    assert!(
                        start.elapsed() >= CONTROL_WRITE_PROGRESS_TIMEOUT - Duration::from_secs(1)
                    );
                } else {
                    assert!(start.elapsed() < Duration::from_secs(3));
                    if mode == "lag" {
                        assert!(error.to_string().contains("missed"));
                    }
                }
            }
            assert_eq!(fixture.subscriptions(), 0, "{mode} leaked subscriptions");
            assert_eq!(
                live.available_permits(),
                1,
                "{mode} leaked connection permit"
            );
            assert_eq!(pending.available_permits(), 1);
            eprintln!(
                "handler mode={mode} elapsed_ms={} subscriptions=0 permits=1 outcome={outcome}",
                start.elapsed().as_millis()
            );
            client.close(VarInt::from_u32(0), b"done");
            server.close(VarInt::from_u32(0), b"done");
        }
        Ok(())
    })
    .await
}

#[tokio::test]
async fn terminal_error_and_handshake_deadline_remain_bounded() -> Result<()> {
    watchdog(async {
        // Exercise the actual HELLO timeout and invalid first message, including terminal FIN.
        for silent in [false, true] {
            let fixture = ServiceFixture::new()?;
            let (server, client) = Pair::endpoints()?;
            let permits = Arc::new(tokio::sync::Semaphore::new(2));
            let handshake = permits.clone().acquire_owned().await?;
            let live = permits.clone().acquire_owned().await?;
            let endpoint = server.clone();
            let state = fixture.state.clone();
            let mut task = Task(tokio::spawn(async move {
                serve_connection(state, endpoint.accept().await.unwrap(), handshake, live).await
            }));
            let connection = client.connect(server.local_addr()?, "voxy.local")?.await?;
            let (mut request, mut response) = connection.open_bi().await?;
            request.write_all(&[STREAM_CONTROL]).await?;
            if !silent {
                send_request(&mut request, ControlMessage::CatalogRequest).await?;
            }
            let start = Instant::now();
            assert!(matches!(
                read_control(&mut response).await?,
                Some(ControlMessage::Error { code: 1, .. })
            ));
            assert_eq!(read_control(&mut response).await?, None);
            assert!((&mut task.0).await?.is_err());
            assert_eq!(permits.available_permits(), 2);
            if silent {
                assert!(start.elapsed() >= HANDSHAKE_TIMEOUT);
            }
            assert!(start.elapsed() < HANDSHAKE_TIMEOUT + Duration::from_secs(2));
            client.close(VarInt::from_u32(0), b"done");
            server.close(VarInt::from_u32(0), b"done");
        }
        // A terminal record too large for remaining credit still uses its short reset policy.
        let pair = Pair::new().await?;
        let (mut send, mut recv) = pair.stream().await?;
        send.write_all(&[0; 8192]).await?;
        let start = Instant::now();
        write_terminal_control(
            &mut send,
            &ControlMessage::Error {
                code: 1,
                message: "x".repeat(4096),
            },
        )
        .await;
        assert!(start.elapsed() >= TERMINAL_CONTROL_WRITE_TIMEOUT);
        assert!(start.elapsed() < TERMINAL_CONTROL_WRITE_TIMEOUT + Duration::from_secs(2));
        assert!(recv.read_to_end(16384).await.is_err());
        Ok(())
    })
    .await
}
