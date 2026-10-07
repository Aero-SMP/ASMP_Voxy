//! Run-owned opaque UDP links. A loss decision is made after serialized service,
//! so lost datagrams consume capacity and retransmissions traverse the same link.
use anyhow::{Result, ensure};
use std::{
    collections::VecDeque,
    net::SocketAddr,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use tokio::{
    net::UdpSocket,
    sync::{oneshot, watch},
    task::JoinHandle,
};

/// All impairment sockets/timers live on this one thread, away from client owners,
/// output and blocking validation. The handle is shared by all virtual clients.
pub struct RelayRuntime {
    pub handle: tokio::runtime::Handle,
    pub heartbeat: Arc<Mutex<Timing>>,
    stop: Option<oneshot::Sender<()>>,
    thread: Option<std::thread::JoinHandle<()>>,
}
impl RelayRuntime {
    pub fn start() -> Result<Self> {
        let (ready_send, ready_receive) = std::sync::mpsc::sync_channel(1);
        let (stop, stopped) = oneshot::channel();
        let heartbeat = Arc::new(Mutex::new(Timing::default()));
        let observed = heartbeat.clone();
        let thread =
            std::thread::Builder::new()
                .name("voxy-pressure-relay".into())
                .spawn(move || {
                    let runtime = match tokio::runtime::Builder::new_current_thread()
                        .enable_all()
                        .build()
                    {
                        Ok(runtime) => runtime,
                        Err(error) => {
                            let _ = ready_send.send(Err(error));
                            return;
                        }
                    };
                    if ready_send.send(Ok(runtime.handle().clone())).is_err() {
                        return;
                    }
                    runtime.block_on(async move {
                        let mut stopped = stopped;
                        let mut pulse = tokio::time::interval(Duration::from_millis(100));
                        pulse.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
                        loop {
                            tokio::select! {
                                _ = &mut stopped => break,
                                scheduled = pulse.tick() => {
                                    observed.lock().unwrap().record(
                                        tokio::time::Instant::now().saturating_duration_since(scheduled));
                                }
                            }
                        }
                    });
                })?;
        let handle = ready_receive
            .recv()
            .context("relay runtime startup ended")??;
        Ok(Self {
            handle,
            heartbeat,
            stop: Some(stop),
            thread: Some(thread),
        })
    }
    pub fn finish(mut self) -> Result<()> {
        if let Some(stop) = self.stop.take() {
            let _ = stop.send(());
        }
        if let Some(thread) = self.thread.take() {
            thread
                .join()
                .map_err(|_| anyhow::anyhow!("relay runtime thread panicked"))?;
        }
        Ok(())
    }
}
impl Drop for RelayRuntime {
    fn drop(&mut self) {
        if let Some(stop) = self.stop.take() {
            let _ = stop.send(());
        }
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

#[derive(Clone, Copy, Debug, Default)]
pub struct Timing {
    pub count: u64,
    pub sum_ns: u64,
    pub max_ns: u64,
    /// Bin i has upper bound 1000*2^i ns; the last bin is open ended.
    pub histogram: [u64; 32],
}
impl Timing {
    fn record(&mut self, duration: Duration) {
        let ns = duration.as_nanos().min(u64::MAX as u128) as u64;
        self.count += 1;
        self.sum_ns = self.sum_ns.saturating_add(ns);
        self.max_ns = self.max_ns.max(ns);
        let us = ns.div_ceil(1000).max(1);
        let bucket = (64 - (us - 1).leading_zeros() as usize).min(31);
        self.histogram[bucket] += 1;
    }
}

#[derive(Clone, Copy, Debug)]
pub struct Profile {
    pub kbps: u64,
    pub delay: Duration,
    pub loss_percent: u64,
    pub seed: u64,
}
#[derive(Clone, Copy, Debug, Default)]
pub struct Counters {
    pub attempted_packets: u64,
    pub attempted_bytes: u64,
    pub serviced_packets: u64,
    pub serviced_bytes: u64,
    pub dropped_packets: u64,
    pub dropped_bytes: u64,
    pub delivered_packets: u64,
    pub delivered_bytes: u64,
    pub abandoned_packets: u64,
    pub abandoned_bytes: u64,
    pub pending_packets: u64,
    pub pending_bytes: u64,
    pub socket_errors: u64,
    pub truncated: u64,
    pub late_ns: u64,
    pub max_late_ns: u64,
    pub first_service_ns: u64,
    pub last_service_ns: u64,
    pub max_accounted_packet: u64,
    pub rate_violation_windows: u64,
    pub max_window_excess_bytes: u64,
    pub send_rate_violation_windows: u64,
    pub max_send_window_excess_bytes: u64,
    pub max_send_window_ip_bytes: u64,
    pub loss_draws: u64,
    pub send_wait_ns: u64,
    pub max_send_wait_ns: u64,
    pub first_send_ns: u64,
    pub last_send_ns: u64,
    pub delivery_late_ns: u64,
    pub max_delivery_late_ns: u64,
    pub service_lateness: Timing,
    pub delivery_lateness: Timing,
    pub send_wait: Timing,
    pub queue_residence: Timing,
}
#[derive(Debug, Default)]
pub struct Stats {
    pub up: Counters,
    pub down: Counters,
    pub failure: Option<String>,
}
struct Packet {
    payload: Vec<u8>,
    arrived: Instant,
    accounted: u64,
    due: Option<Instant>,
}
struct Direction {
    queue: VecDeque<Packet>,
    due: Option<Instant>,
    rng: u64,
    last_service: Option<Instant>,
    profile: Profile,
    counters: Counters,
    window: VecDeque<(Instant, u64)>,
    window_bytes: u64,
    send_window: VecDeque<(Instant, u64)>,
    send_window_bytes: u64,
}
fn service_duration(bytes: u64, kbps: u64) -> Duration {
    // Ceil integer nanoseconds: never round a packet's serialization downward.
    Duration::from_nanos(((u128::from(bytes) * 8_000_000).div_ceil(u128::from(kbps))) as u64)
}
fn window_excess(
    window: &mut VecDeque<(Instant, u64)>,
    bytes: &mut u64,
    now: Instant,
    packet_bytes: u64,
    allowance: u64,
) -> u64 {
    while window
        .front()
        .is_some_and(|(time, _)| now.duration_since(*time) >= Duration::from_secs(1))
    {
        *bytes -= window.pop_front().unwrap().1;
    }
    window.push_back((now, packet_bytes));
    *bytes += packet_bytes;
    bytes.saturating_sub(allowance)
}
impl Direction {
    fn new(profile: Profile, seed: u64) -> Self {
        Self {
            queue: VecDeque::new(),
            due: None,
            rng: seed.max(1),
            last_service: None,
            profile,
            counters: Counters::default(),
            window: VecDeque::new(),
            window_bytes: 0,
            send_window: VecDeque::new(),
            send_window_bytes: 0,
        }
    }
    fn push(&mut self, payload: &[u8], now: Instant, ipv6: bool) {
        let accounted = payload.len() as u64 + if ipv6 { 48 } else { 28 };
        self.counters.attempted_packets += 1;
        self.counters.attempted_bytes += accounted;
        self.counters.pending_packets += 1;
        self.counters.pending_bytes += accounted;
        self.counters.max_accounted_packet = self.counters.max_accounted_packet.max(accounted);
        self.queue.push_back(Packet {
            payload: payload.to_vec(),
            arrived: now,
            accounted,
            due: None,
        });
        self.schedule();
    }
    fn schedule(&mut self) {
        if self.due.is_some() {
            return;
        }
        if let Some(packet) = self.queue.front() {
            let earliest = packet.arrived + self.profile.delay;
            let start = self
                .last_service
                .map_or(earliest, |last| last.max(earliest));
            self.due = Some(start + service_duration(packet.accounted, self.profile.kbps));
        }
    }
    fn take(&mut self, now: Instant, origin: Instant) -> Option<(Packet, bool)> {
        let due = self.due?;
        if now < due {
            return None;
        }
        let mut packet = self.queue.pop_front()?;
        packet.due = Some(due);
        self.due = None;
        // Rebase to actual service: delayed event-loop polls cannot catch up in a burst.
        self.last_service = Some(now);
        let late = now
            .saturating_duration_since(due)
            .as_nanos()
            .min(u64::MAX as u128) as u64;
        self.counters.late_ns += late;
        self.counters.max_late_ns = self.counters.max_late_ns.max(late);
        self.counters
            .service_lateness
            .record(now.saturating_duration_since(due));
        self.counters
            .queue_residence
            .record(now.saturating_duration_since(packet.arrived));
        self.counters.pending_packets -= 1;
        self.counters.pending_bytes -= packet.accounted;
        self.counters.serviced_packets += 1;
        self.counters.serviced_bytes += packet.accounted;
        let stamp = now.duration_since(origin).as_nanos().min(u64::MAX as u128) as u64;
        if self.counters.serviced_packets == 1 {
            self.counters.first_service_ns = stamp;
        }
        self.counters.last_service_ns = stamp;
        // Each packet is appended/removed once. Loss still consumes service capacity.
        let allowance = self.profile.kbps * 125 + self.counters.max_accounted_packet;
        let excess = window_excess(
            &mut self.window,
            &mut self.window_bytes,
            now,
            packet.accounted,
            allowance,
        );
        if excess > 0 {
            self.counters.rate_violation_windows += 1;
            self.counters.max_window_excess_bytes =
                self.counters.max_window_excess_bytes.max(excess);
        }
        self.rng ^= self.rng << 13;
        self.rng ^= self.rng >> 7;
        self.rng ^= self.rng << 17;
        self.counters.loss_draws += 1;
        let dropped = (u128::from(self.rng) * 100) < (u128::from(self.profile.loss_percent) << 64);
        if dropped {
            self.counters.dropped_packets += 1;
            self.counters.dropped_bytes += packet.accounted;
        }
        self.schedule();
        Some((packet, dropped))
    }
    fn finish_service(
        &mut self,
        actual: Instant,
        origin: Instant,
        send_start: Instant,
        packet: &Packet,
        sent: bool,
    ) {
        if sent {
            // Observe completed UDP sends independently of charged/drop service times.
            let excess = window_excess(
                &mut self.send_window,
                &mut self.send_window_bytes,
                actual,
                packet.accounted,
                self.profile.kbps * 125 + self.counters.max_accounted_packet,
            );
            self.counters.max_send_window_ip_bytes = self
                .counters
                .max_send_window_ip_bytes
                .max(self.send_window_bytes);
            if excess > 0 {
                self.counters.send_rate_violation_windows += 1;
                self.counters.max_send_window_excess_bytes =
                    self.counters.max_send_window_excess_bytes.max(excess);
            }
            let wait = actual
                .saturating_duration_since(send_start)
                .as_nanos()
                .min(u64::MAX as u128) as u64;
            let late = actual
                .saturating_duration_since(packet.due.unwrap())
                .as_nanos()
                .min(u64::MAX as u128) as u64;
            self.counters.send_wait_ns += wait;
            self.counters.max_send_wait_ns = self.counters.max_send_wait_ns.max(wait);
            self.counters.delivery_late_ns += late;
            self.counters.max_delivery_late_ns = self.counters.max_delivery_late_ns.max(late);
            self.counters
                .send_wait
                .record(actual.saturating_duration_since(send_start));
            self.counters
                .delivery_lateness
                .record(actual.saturating_duration_since(packet.due.unwrap()));
            let stamp = actual
                .duration_since(origin)
                .as_nanos()
                .min(u64::MAX as u128) as u64;
            if self.counters.delivered_packets == 1 {
                self.counters.first_send_ns = stamp;
            }
            self.counters.last_send_ns = stamp;
        }
        self.last_service = Some(actual);
        self.due = None;
        self.schedule();
    }
    fn abandon(&mut self) {
        self.counters.abandoned_packets += self.counters.pending_packets;
        self.counters.abandoned_bytes += self.counters.pending_bytes;
        self.counters.pending_packets = 0;
        self.counters.pending_bytes = 0;
        self.queue.clear();
        self.due = None;
    }
}
#[derive(Debug)]
pub struct Relay {
    pub local: SocketAddr,
    pub backend: SocketAddr,
    pub front_inode: u64,
    pub back_inode: u64,
    pub stats: Arc<Mutex<Stats>>,
    task: Option<JoinHandle<()>>,
}
impl Relay {
    pub async fn start(
        server: SocketAddr,
        profile: Profile,
        mut stop: watch::Receiver<bool>,
    ) -> Result<Self> {
        ensure!(
            profile.kbps > 0 && profile.loss_percent <= 100,
            "invalid relay profile"
        );
        let bind = if server.is_ipv6() {
            "[::1]:0"
        } else {
            "127.0.0.1:0"
        };
        let front = UdpSocket::bind(bind).await?;
        let back = UdpSocket::bind(bind).await?;
        back.connect(server).await?;
        let local = front.local_addr()?;
        let backend = back.local_addr()?;
        #[cfg(target_os = "linux")]
        let (front_inode, back_inode) = {
            use std::os::fd::AsRawFd;
            let inode = |socket: &UdpSocket| -> Result<u64> {
                let value = std::fs::read_link(format!("/proc/self/fd/{}", socket.as_raw_fd()))?;
                let value = value.to_str().context("non-UTF8 socket descriptor")?;
                Ok(value
                    .strip_prefix("socket:[")
                    .and_then(|value| value.strip_suffix(']'))
                    .context("socket inode unavailable")?
                    .parse()?)
            };
            (inode(&front)?, inode(&back)?)
        };
        #[cfg(not(target_os = "linux"))]
        let (front_inode, back_inode) = (0, 0);
        let stats = Arc::new(Mutex::new(Stats::default()));
        let published = stats.clone();
        let task = tokio::spawn(async move {
            let origin = Instant::now();
            let mut client = None;
            let mut up = Direction::new(profile, profile.seed ^ 0x9e3779b97f4a7c15);
            let mut down = Direction::new(profile, profile.seed ^ 0xd1b54a32d192ed03);
            let mut input_up = vec![0; 65536];
            let mut input_down = vec![0; 65536];
            let mut pulse = tokio::time::interval(Duration::from_secs(1));
            pulse.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            let result: Result<()> = async { loop {
                if *stop.borrow() { break; }
                let next = up.due.into_iter().chain(down.due).min().unwrap_or_else(|| Instant::now() + Duration::from_secs(1));
                tokio::select! {
                    changed = stop.changed() => { if changed.is_err() || *stop.borrow() { break; } }
                    received = front.recv_from(&mut input_up) => {
                        let (length, address) = received?;
                        ensure!(client.is_none_or(|known| known == address), "relay received a second local endpoint");
                        client = Some(address);
                        if length == input_up.len() { up.counters.truncated += 1; }
                        up.push(&input_up[..length], Instant::now(), server.is_ipv6());
                    }
                    received = back.recv(&mut input_down) => {
                        let length = received?;
                        if length == input_down.len() { down.counters.truncated += 1; }
                        down.push(&input_down[..length], Instant::now(), server.is_ipv6());
                    }
                    _ = tokio::time::sleep_until(next.into()) => {
                        if let Some((packet, dropped)) = up.take(Instant::now(), origin) { let send_start=Instant::now(); if !dropped {
                            match back.send(&packet.payload).await {
                                Ok(length) if length == packet.payload.len() => { up.counters.delivered_packets += 1; up.counters.delivered_bytes += packet.accounted; }
                                _ => { up.counters.socket_errors += 1; anyhow::bail!("upload relay send failed"); }
                            }
                        } up.finish_service(Instant::now(),origin,send_start,&packet,!dropped); }
                        if let Some((packet, dropped)) = down.take(Instant::now(), origin) { let send_start=Instant::now(); if !dropped {
                            let address = client.context("download before local endpoint")?;
                            match front.send_to(&packet.payload, address).await {
                                Ok(length) if length == packet.payload.len() => { down.counters.delivered_packets += 1; down.counters.delivered_bytes += packet.accounted; }
                                _ => { down.counters.socket_errors += 1; anyhow::bail!("download relay send failed"); }
                            }
                        } down.finish_service(Instant::now(),origin,send_start,&packet,!dropped); }
                    }
                    _ = pulse.tick() => { let mut state = published.lock().unwrap(); state.up = up.counters; state.down = down.counters; }
                }
            } Ok(()) }.await;
            up.abandon();
            down.abandon();
            let mut state = published.lock().unwrap();
            state.up = up.counters;
            state.down = down.counters;
            state.failure = result.err().map(|error| error.to_string());
        });
        Ok(Self {
            local,
            backend,
            front_inode,
            back_inode,
            stats,
            task: Some(task),
        })
    }
    pub async fn finish(mut self, timeout: Duration) -> Result<()> {
        let mut task = self.task.take().context("relay task already taken")?;
        match tokio::time::timeout(timeout, &mut task).await {
            Ok(joined) => joined.context("relay task failed")?,
            Err(_) => {
                task.abort();
                let _ = task.await;
                anyhow::bail!("relay cleanup timed out");
            }
        }
        Ok(())
    }
}
impl Drop for Relay {
    fn drop(&mut self) {
        if let Some(task) = self.task.take() {
            task.abort();
        }
    }
}
use anyhow::Context;

pub fn arithmetic_check() -> Result<()> {
    ensure!(
        service_duration(1250, 1000) == Duration::from_millis(10),
        "serialization arithmetic"
    );
    let origin = Instant::now();
    let profile = Profile {
        kbps: 1000,
        delay: Duration::from_millis(150),
        loss_percent: 10,
        seed: 17,
    };
    let mut direction = Direction::new(profile, 17);
    for _ in 0..1000 {
        direction.push(&[0; 1222], origin, false);
    }
    for index in 0..1000 {
        let time = origin + Duration::from_millis(160 + index * 10);
        let (packet, dropped) = direction
            .take(time, origin)
            .context("FIFO service missed")?;
        if !dropped {
            direction.counters.delivered_packets += 1;
            direction.counters.delivered_bytes += packet.accounted;
        }
        direction.finish_service(time, origin, time, &packet, !dropped);
    }
    ensure!(
        direction.counters.serviced_bytes == 1_250_000
            && direction.counters.rate_violation_windows == 0
            && direction.counters.send_rate_violation_windows == 0
            && direction.counters.max_send_window_ip_bytes <= 126_250,
        "rate accounting"
    );
    let mut window = VecDeque::new();
    let mut bytes = 0;
    for _ in 0..101 {
        ensure!(
            window_excess(&mut window, &mut bytes, origin, 1250, 126_250) == 0,
            "one-packet window allowance"
        );
    }
    ensure!(
        window_excess(&mut window, &mut bytes, origin, 1250, 126_250) == 1250,
        "window excess accounting"
    );
    ensure!(
        window_excess(
            &mut window,
            &mut bytes,
            origin + Duration::from_secs(1),
            1250,
            126_250,
        ) == 0
            && bytes == 1250,
        "window expiry boundary"
    );
    ensure!(
        direction.counters.dropped_packets > 50 && direction.counters.dropped_packets < 150,
        "seeded loss arithmetic"
    );
    ensure!(
        direction.counters.attempted_bytes == direction.counters.serviced_bytes
            && direction.queue.is_empty(),
        "packet reconciliation"
    );
    let mut delayed = Direction::new(profile, 17);
    delayed.push(&[0; 1222], origin, false);
    delayed.push(&[0; 1222], origin, false);
    ensure!(
        delayed
            .take(origin + Duration::from_secs(1), origin)
            .is_some(),
        "overdue first packet"
    );
    ensure!(
        delayed
            .take(origin + Duration::from_secs(1), origin)
            .is_none(),
        "overdue catch-up burst"
    );
    delayed.abandon();
    ensure!(
        delayed.counters.abandoned_packets == 1,
        "cleanup accounting"
    );
    Ok(())
}
