//! Authenticated shared-port routing and IP download pacing before the QUIC handshake.
use quinn::{AsyncUdpSocket, UdpPoller};
use std::{
    collections::{HashMap, VecDeque},
    future::{Future, poll_fn},
    io,
    io::IoSliceMut,
    net::SocketAddr,
    pin::Pin,
    sync::{Arc, Mutex, Weak},
    task::{Context, Poll, Waker},
    time::Duration,
};
use tokio::{
    sync::{Notify, futures::OwnedNotified},
    time::{Instant, Sleep},
};

pub const ENVELOPE_BYTES: usize = 17;
type Route = [u8; ENVELOPE_BYTES - 1];
type Routes = Arc<Mutex<HashMap<Route, Weak<Inbox>>>>;

#[derive(Debug)]
struct Packet {
    bytes: Box<[u8]>,
    meta: quinn::udp::RecvMeta,
}
#[derive(Debug, Default)]
struct ReadState {
    packets: VecDeque<Packet>,
    reader: Option<Waker>,
    error: Option<io::ErrorKind>,
}
#[derive(Debug, Default)]
struct Inbox(Mutex<ReadState>);
impl Inbox {
    fn deliver(&self, packet: Packet) {
        let reader = {
            let mut state = self.0.lock().expect("UDP receive owner poisoned");
            state.packets.push_back(packet);
            state.reader.take()
        };
        if let Some(reader) = reader {
            reader.wake();
        }
    }
    fn fail(&self, error: io::ErrorKind) {
        let reader = {
            let mut state = self.0.lock().expect("UDP receive owner poisoned");
            state.error = Some(error);
            state.reader.take()
        };
        if let Some(reader) = reader {
            reader.wake();
        }
    }
}

/// Only Java-registered, token-framed routes reach QUIC. Unknown UDP receives no response.
#[derive(Debug)]
pub struct UdpMux {
    physical: Arc<dyn AsyncUdpSocket>,
    routes: Routes,
    receiver: tokio::task::AbortHandle,
}
impl UdpMux {
    pub fn new(physical: Arc<dyn AsyncUdpSocket>) -> Arc<Self> {
        let routes = Arc::new(Mutex::new(HashMap::new()));
        let receiver = tokio::spawn(receive(physical.clone(), routes.clone()));
        Arc::new(Self {
            physical,
            routes,
            receiver: receiver.abort_handle(),
        })
    }
    pub fn route(self: &Arc<Self>, token: &[u8; 32]) -> io::Result<Arc<dyn AsyncUdpSocket>> {
        let route: Route = token[..ENVELOPE_BYTES - 1].try_into().unwrap();
        let inbox = Arc::new(Inbox::default());
        let mut routes = self.routes.lock().expect("UDP route owner poisoned");
        if routes.get(&route).and_then(Weak::upgrade).is_some() {
            return Err(io::Error::new(
                io::ErrorKind::AlreadyExists,
                "duplicate UDP session route",
            ));
        }
        routes.insert(route, Arc::downgrade(&inbox));
        Ok(Arc::new(RoutedSocket {
            mux: self.clone(),
            inbox,
            route,
            transmit: Mutex::new(Vec::new()),
        }))
    }
}
impl Drop for UdpMux {
    fn drop(&mut self) {
        self.receiver.abort();
    }
}

async fn receive(physical: Arc<dyn AsyncUdpSocket>, routes: Routes) {
    // Sized from the UDP wire limit and the physical socket's GRO capability, not workload.
    let mut bytes = vec![0u8; u16::MAX as usize * physical.max_receive_segments().max(1)];
    let error = loop {
        let mut buffers = [IoSliceMut::new(&mut bytes)];
        let mut metadata = [quinn::udp::RecvMeta::default()];
        let count = match poll_fn(|cx| physical.poll_recv(cx, &mut buffers, &mut metadata)).await {
            Ok(count) => count,
            Err(error) => break error,
        };
        if count == 0 {
            continue;
        }
        let meta = metadata[0];
        for packet in bytes[..meta.len].chunks(meta.stride.max(1)) {
            let destination = if packet.first() == Some(&0) && packet.len() > ENVELOPE_BYTES {
                let route: Route = packet[1..ENVELOPE_BYTES].try_into().unwrap();
                routes
                    .lock()
                    .expect("UDP route owner poisoned")
                    .get(&route)
                    .and_then(Weak::upgrade)
            } else {
                None
            };
            let Some(inbox) = destination else { continue };
            let payload = &packet[ENVELOPE_BYTES..];
            inbox.deliver(Packet {
                bytes: payload.into(),
                meta: quinn::udp::RecvMeta {
                    len: payload.len(),
                    stride: payload.len(),
                    ..meta
                },
            });
        }
    };
    eprintln!("Voxy UDP listener failed: {error}");
    for inbox in routes
        .lock()
        .expect("UDP route owner poisoned")
        .values()
        .filter_map(Weak::upgrade)
    {
        inbox.fail(error.kind());
    }
}

#[derive(Debug)]
struct RoutedSocket {
    mux: Arc<UdpMux>,
    inbox: Arc<Inbox>,
    route: Route,
    transmit: Mutex<Vec<u8>>,
}
impl Drop for RoutedSocket {
    fn drop(&mut self) {
        self.mux
            .routes
            .lock()
            .expect("UDP route owner poisoned")
            .remove(&self.route);
    }
}
impl AsyncUdpSocket for RoutedSocket {
    fn create_io_poller(self: Arc<Self>) -> Pin<Box<dyn UdpPoller>> {
        self.mux.physical.clone().create_io_poller()
    }
    fn try_send(&self, transmit: &quinn::udp::Transmit) -> io::Result<()> {
        if transmit
            .segment_size
            .is_some_and(|size| size < transmit.contents.len())
        {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "framed UDP requires one datagram",
            ));
        }
        let mut bytes = self.transmit.lock().expect("UDP transmit owner poisoned");
        bytes.clear();
        bytes.push(0);
        bytes.extend_from_slice(&self.route);
        bytes.extend_from_slice(transmit.contents);
        self.mux.physical.try_send(&quinn::udp::Transmit {
            contents: &bytes,
            segment_size: None,
            ..transmit.clone()
        })
    }
    fn poll_recv(
        &self,
        cx: &mut Context<'_>,
        buffers: &mut [IoSliceMut<'_>],
        metadata: &mut [quinn::udp::RecvMeta],
    ) -> Poll<io::Result<usize>> {
        let mut state = self.inbox.0.lock().expect("UDP receive owner poisoned");
        if let Some(error) = state.error {
            return Poll::Ready(Err(error.into()));
        }
        let mut count = 0;
        while count < buffers.len().min(metadata.len()) {
            let Some(packet) = state.packets.pop_front() else {
                break;
            };
            if packet.bytes.len() > buffers[count].len() {
                continue; // As with UDP recvmsg, an undersized receive buffer loses the datagram.
            }
            buffers[count][..packet.bytes.len()].copy_from_slice(&packet.bytes);
            metadata[count] = packet.meta;
            count += 1;
        }
        if count != 0 {
            Poll::Ready(Ok(count))
        } else {
            state.reader = Some(cx.waker().clone());
            Poll::Pending
        }
    }
    fn local_addr(&self) -> io::Result<SocketAddr> {
        self.mux.physical.local_addr()
    }
    fn max_transmit_segments(&self) -> usize {
        1
    }
    fn max_receive_segments(&self) -> usize {
        1
    }
    fn may_fragment(&self) -> bool {
        self.mux.physical.may_fragment()
    }
}

#[derive(Debug)]
struct Clock {
    rate: u64,
    next: Instant,
    bytes: u64,
    datagrams: u64,
}
#[derive(Debug)]
pub struct RateLedger {
    clock: Mutex<Clock>,
    changed: Arc<Notify>,
}
impl RateLedger {
    pub fn new(kbps: u64) -> Arc<Self> {
        Arc::new(Self {
            clock: Mutex::new(Clock {
                rate: kbps * 125,
                next: Instant::now(),
                bytes: 0,
                datagrams: 0,
            }),
            changed: Arc::new(Notify::new()),
        })
    }
    pub fn update(&self, kbps: u64) {
        let mut clock = self.clock.lock().expect("download rate owner poisoned");
        let now = Instant::now();
        let rate = kbps * 125;
        // Retain existing pacing debt when changing a live finite rate; never grant idle credit.
        let remaining = clock.next.saturating_duration_since(now).as_nanos();
        let debt = remaining * clock.rate as u128 / rate as u128;
        clock.rate = rate;
        clock.next = now + Duration::from_nanos(debt.min(u64::MAX as u128) as u64);
        drop(clock);
        self.changed.notify_waiters();
    }
    pub fn counters(&self) -> (u64, u64) {
        let clock = self.clock.lock().expect("download rate owner poisoned");
        (clock.bytes, clock.datagrams)
    }
}

#[derive(Debug)]
pub struct PacedSocket {
    inner: Arc<dyn AsyncUdpSocket>,
    ledger: Arc<RateLedger>,
}
impl PacedSocket {
    pub fn new(inner: Arc<dyn AsyncUdpSocket>, ledger: Arc<RateLedger>) -> Arc<Self> {
        Arc::new(Self { inner, ledger })
    }
}
impl AsyncUdpSocket for PacedSocket {
    fn create_io_poller(self: Arc<Self>) -> Pin<Box<dyn UdpPoller>> {
        Box::pin(PacedPoller {
            inner: self.inner.clone().create_io_poller(),
            timer: Box::pin(tokio::time::sleep(Duration::ZERO)),
            changed: Box::pin(self.ledger.changed.clone().notified_owned()),
            socket: self,
        })
    }
    fn try_send(&self, transmit: &quinn::udp::Transmit) -> io::Result<()> {
        let mut clock = self
            .ledger
            .clock
            .lock()
            .expect("download rate owner poisoned");
        let now = Instant::now();
        if now < clock.next {
            return Err(io::ErrorKind::WouldBlock.into());
        }
        // max_transmit_segments=1: exactly one unfragmented IP/UDP datagram per submission.
        let cost = (transmit.contents.len() + ENVELOPE_BYTES) as u64
            + if transmit.destination.is_ipv4() {
                28
            } else {
                48
            };
        self.inner.try_send(transmit)?;
        clock.bytes = clock.bytes.saturating_add(cost);
        clock.datagrams = clock.datagrams.saturating_add(1);
        let nanos = (cost as u128 * 1_000_000_000).div_ceil(clock.rate as u128);
        clock.next = now + Duration::from_nanos(nanos.min(u64::MAX as u128) as u64);
        Ok(())
    }
    fn poll_recv(
        &self,
        cx: &mut Context<'_>,
        bufs: &mut [IoSliceMut<'_>],
        meta: &mut [quinn::udp::RecvMeta],
    ) -> Poll<io::Result<usize>> {
        self.inner.poll_recv(cx, bufs, meta)
    }
    fn local_addr(&self) -> io::Result<SocketAddr> {
        self.inner.local_addr()
    }
    fn max_transmit_segments(&self) -> usize {
        1
    }
    fn max_receive_segments(&self) -> usize {
        self.inner.max_receive_segments()
    }
    fn may_fragment(&self) -> bool {
        self.inner.may_fragment()
    }
}

#[derive(Debug)]
struct PacedPoller {
    socket: Arc<PacedSocket>,
    inner: Pin<Box<dyn UdpPoller>>,
    timer: Pin<Box<Sleep>>,
    changed: Pin<Box<OwnedNotified>>,
}
impl UdpPoller for PacedPoller {
    fn poll_writable(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        loop {
            // Register before inspecting the clock so a concurrent setting change cannot strand us.
            if self.changed.as_mut().poll(cx).is_ready() {
                self.changed = Box::pin(self.socket.ledger.changed.clone().notified_owned());
                continue;
            }
            let next = {
                let clock = self
                    .socket
                    .ledger
                    .clock
                    .lock()
                    .expect("download rate owner poisoned");
                clock.next
            };
            if Instant::now() >= next {
                return self.inner.as_mut().poll_writable(cx);
            }
            self.timer.as_mut().reset(next);
            if self.timer.as_mut().poll(cx).is_pending() {
                return Poll::Pending;
            }
        }
    }
}
