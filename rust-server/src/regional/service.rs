use super::{
    RegionalRuntime,
    wire::{ContentBinding, ControlMessage, RecordDescriptor, RecordStatus, encode_control_record},
};
use crate::anvil::AnvilWorld;
use crate::{catalog::Catalog, read_lock, registry::Registry};
use anyhow::{Context, Result, bail};
use std::{
    collections::BTreeMap,
    path::Path,
    sync::{Arc, Mutex, RwLock, Weak},
    time::{Duration, Instant},
};
use tokio::{
    sync::{Notify, broadcast},
    task::JoinHandle,
};

const ANNOUNCEMENT_CAPACITY: usize = 4_096;

#[derive(Debug, Default)]
pub struct RefreshStatus {
    pub more_pending: bool,
    pub incomplete: bool,
    pub failure: Option<String>,
}

struct RetryRound {
    number: u64,
    deadline: Instant,
    interval: Duration,
}
impl RetryRound {
    fn new(now: Instant, interval: Duration) -> Self {
        Self {
            number: 0,
            deadline: now + interval,
            interval,
        }
    }
    fn advance(&mut self, now: Instant) {
        if now >= self.deadline {
            self.number = self
                .number
                .checked_add(1)
                .expect("regional retry round exhausted");
            self.deadline = now + self.interval;
        }
    }
}

#[cfg(test)]
mod round_tests {
    use super::*;
    #[test]
    fn wakeups_and_backlogs_share_round_until_deadline() {
        let now = Instant::now();
        let mut round = RetryRound::new(now, Duration::from_secs(2));
        for _ in 0..1000 {
            round.advance(now + Duration::from_secs(1));
            assert_eq!(round.number, 0);
        }
        round.advance(now + Duration::from_secs(2));
        assert_eq!(round.number, 1);
        for _ in 0..1000 {
            round.advance(now + Duration::from_secs(3));
            assert_eq!(round.number, 1);
        }
        round.advance(now + Duration::from_secs(4));
        assert_eq!(round.number, 2);
    }
}

#[derive(Clone, Debug)]
pub enum RegionalAnnouncement {
    Changed {
        dimension: String,
        region_x: i32,
        region_z: i32,
        generation: u64,
        changed_ordinals: Option<Arc<[u32]>>,
    },
    Shutdown(String),
}

/// Owns the current regional runtimes and their bounded publication loop. Each refresh publishes
/// at most one shard per dimension; serving remains independent and never waits for a world-wide
/// root or garbage-collection pass.
#[derive(Debug, Default)]
struct SharedCadence {
    sessions: BTreeMap<usize, u64>,
    intervals: BTreeMap<u64, usize>,
}

#[derive(Debug)]
pub struct RegionalService {
    runtimes: BTreeMap<String, Arc<RegionalRuntime>>,
    catalog: Arc<CatalogCache>,
    announcements: broadcast::Sender<RegionalAnnouncement>,
    wake: Arc<Notify>,
    worker: Mutex<Option<JoinHandle<()>>>,
    cadences: Mutex<SharedCadence>,
}

impl RegionalService {
    pub fn open(
        data_root: impl AsRef<Path>,
        dimensions: &BTreeMap<String, Arc<AnvilWorld>>,
        registry: Arc<RwLock<Registry>>,
    ) -> Result<Self> {
        let layout = super::RegionLayout::new(-2, 12, crate::MAX_LOD + 1)?;
        let runtimes = dimensions
            .iter()
            .map(|(dimension, source)| {
                Ok((
                    dimension.clone(),
                    Arc::new(RegionalRuntime::open(
                        data_root.as_ref(),
                        dimension.clone(),
                        source.clone(),
                        registry.clone(),
                        layout,
                    )?),
                ))
            })
            .collect::<Result<BTreeMap<_, _>>>()?;
        let (announcements, _) = broadcast::channel(ANNOUNCEMENT_CAPACITY);
        Ok(Self {
            runtimes,
            catalog: Arc::new(CatalogCache::new(registry)),
            announcements,
            wake: Arc::new(Notify::new()),
            worker: Mutex::new(None),
            cadences: Mutex::new(SharedCadence::default()),
        })
    }

    pub fn runtime(&self, dimension: &str) -> Result<Arc<RegionalRuntime>> {
        self.runtimes
            .get(dimension)
            .cloned()
            .with_context(|| format!("unknown regional dimension {dimension}"))
    }

    pub fn responder(&self, dimension: &str, server_instance: u64) -> Result<RegionalResponder> {
        RegionalResponder::new(
            self.runtime(dimension)?,
            self.catalog.clone(),
            server_instance,
            self.wake.clone(),
        )
    }

    pub fn set_cadence(&self, session: usize, millis: Option<u64>) -> Result<()> {
        let mut cadences = self
            .cadences
            .lock()
            .map_err(|_| crate::UnsafeState("cadence owner poisoned"))?;
        let millis = millis.map(|value| value.max(1000));
        let previous = cadences.sessions.get(&session).copied();
        if previous == millis {
            return Ok(());
        }
        if let Some(previous) = previous {
            let count = cadences
                .intervals
                .get_mut(&previous)
                .expect("registered cadence");
            *count -= 1;
            if *count == 0 {
                cadences.intervals.remove(&previous);
            }
        }
        if let Some(millis) = millis {
            cadences.sessions.insert(session, millis);
            *cadences.intervals.entry(millis).or_default() += 1;
        } else {
            cadences.sessions.remove(&session);
        }
        let shortest = cadences
            .intervals
            .first_key_value()
            .map_or(1000, |(&interval, _)| interval);
        for runtime in self.runtimes.values() {
            runtime.set_freshness_interval(shortest);
        }
        self.wake.notify_one();
        Ok(())
    }

    pub fn saved_chunks(&self, dimension: &str, chunks: &[(i32, i32)]) -> Result<()> {
        self.runtime(dimension)?.saved_chunks(chunks)
    }

    /// The Java supervisor owns stdin and sends coalesced completed-save coordinates.
    pub fn start_save_reader(self: &Arc<Self>) {
        let service = self.clone();
        // Tokio's stdin uses an uncancellable blocking task that can hold runtime shutdown open.
        // This process-owned thread exits at pipe EOF and never participates in Tokio shutdown.
        let started = std::thread::Builder::new()
            .name("Voxy completed saves".into())
            .spawn(move || {
                use std::io::Read;
                let stdin = std::io::stdin();
                let mut input = std::io::BufReader::new(stdin.lock());
                let result: Result<()> = (|| {
                    loop {
                        let mut length = [0; 2];
                        match input.read_exact(&mut length) {
                            Ok(()) => {}
                            Err(error) if error.kind() == std::io::ErrorKind::UnexpectedEof => {
                                return Ok(());
                            }
                            Err(error) => return Err(error.into()),
                        }
                        let size = u16::from_le_bytes(length) as usize;
                        if size == 0 || size > super::wire::MAX_DIMENSION_BYTES {
                            bail!("invalid save notification dimension");
                        }
                        let mut name = vec![0; size];
                        input.read_exact(&mut name)?;
                        let runtime = service.runtime(std::str::from_utf8(&name)?)?;
                        let mut count = [0; 4];
                        input.read_exact(&mut count)?;
                        for _ in 0..u32::from_le_bytes(count) {
                            let mut chunk = [0; 8];
                            input.read_exact(&mut chunk)?;
                            let x = i32::from_le_bytes(chunk[..4].try_into().unwrap());
                            let z = i32::from_le_bytes(chunk[4..].try_into().unwrap());
                            runtime.saved_chunks(&[(x, z)])?;
                        }
                    }
                })();
                if let Err(error) = result {
                    eprintln!(
                        "Voxy completed-save pipe ended: {error:#}; source reconciliation continues"
                    );
                }
            });
        if let Err(error) = started {
            eprintln!(
                "Voxy completed-save reader unavailable: {error}; source reconciliation continues"
            );
        }
    }

    pub fn subscribe(&self) -> broadcast::Receiver<RegionalAnnouncement> {
        self.announcements.subscribe()
    }

    pub fn refresh_all(&self, round: u64) -> Result<RefreshStatus> {
        let mut result = RefreshStatus::default();
        for (dimension, runtime) in &self.runtimes {
            let refresh = runtime.refresh(round)?;
            result.more_pending |= refresh.more_pending;
            result.incomplete |= refresh.incomplete;
            if result.failure.is_none() {
                result.failure = refresh.failure;
            }
            for (region_x, region_z, generation) in refresh.changed {
                let _ = self.announcements.send(RegionalAnnouncement::Changed {
                    dimension: dimension.clone(),
                    region_x,
                    region_z,
                    generation,
                    changed_ordinals: refresh.changed_ordinals.get(&(region_x, region_z)).cloned(),
                });
            }
            for (region_x, region_z) in refresh.removed {
                let _ = self.announcements.send(RegionalAnnouncement::Changed {
                    dimension: dimension.clone(),
                    region_x,
                    region_z,
                    generation: 0,
                    changed_ordinals: None,
                });
            }
        }
        Ok(result)
    }

    /// One logical round: finish independent eligible work, then truthfully report failures.
    pub fn refresh_once(&self) -> Result<()> {
        let mut failure = None;
        loop {
            let status = self.refresh_all(0)?;
            if failure.is_none() && status.incomplete {
                failure = status.failure;
            }
            if !status.more_pending {
                if let Some(failure) = failure {
                    bail!("regional import incomplete: {failure}");
                }
                return Ok(());
            }
        }
    }

    pub fn start(self: &Arc<Self>, poll_interval: Duration) -> Result<()> {
        let mut worker = self
            .worker
            .lock()
            .map_err(|_| anyhow::anyhow!("regional worker lock poisoned"))?;
        if worker.is_some() {
            bail!("regional publication worker was already started");
        }
        let weak = Arc::downgrade(self);
        *worker = Some(tokio::spawn(async move {
            publication_loop(weak, poll_interval).await;
        }));
        Ok(())
    }

    pub fn shutdown(&self, message: impl Into<String>) {
        let _ = self
            .announcements
            .send(RegionalAnnouncement::Shutdown(message.into()));
        if let Ok(mut worker) = self.worker.lock()
            && let Some(worker) = worker.take()
        {
            worker.abort();
        }
    }
}

async fn publication_loop(service: Weak<RegionalService>, poll_interval: Duration) {
    let mut round = RetryRound::new(Instant::now(), poll_interval);
    loop {
        let Some(current) = service.upgrade() else {
            return;
        };
        let wake = current.wake.clone();
        round.advance(Instant::now());
        let number = round.number;
        let refresh = tokio::task::spawn_blocking(move || current.refresh_all(number)).await;
        let more = match refresh {
            Ok(Ok(status)) => status.more_pending,
            Ok(Err(error)) => {
                eprintln!("regional publication stopped on unsafe shared-state failure: {error:#}");
                return;
            }
            Err(error) if error.is_cancelled() => return,
            Err(error) => {
                eprintln!("regional publication worker stopped: {error}");
                return;
            }
        };
        // Continue immediately while clean-import shards remain. Once caught up, polling is cheap:
        // only Anvil headers and the compact source tables are compared.
        if !more {
            tokio::select! {
                _ = tokio::time::sleep_until(round.deadline.into()) => {},
                _ = wake.notified() => {},
            }
        } else {
            tokio::task::yield_now().await;
        }
    }
}

/// Stateless preparation for the regional QUIC service. Disk reads use immutable `Arc<File>`
/// generations, so a concurrent atomic region replacement cannot mix response metadata or bytes.
#[derive(Clone, Debug)]
pub struct RegionalResponder {
    runtime: Arc<RegionalRuntime>,
    catalog: Arc<CatalogCache>,
    server_instance: u64,
    wake: Arc<Notify>,
}

impl RegionalResponder {
    fn new(
        runtime: Arc<RegionalRuntime>,
        catalog: Arc<CatalogCache>,
        server_instance: u64,
        wake: Arc<Notify>,
    ) -> Result<Self> {
        if server_instance == 0 {
            bail!("regional server instance zero is reserved");
        }
        Ok(Self {
            runtime,
            catalog,
            server_instance,
            wake,
        })
    }

    pub fn hello(&self, background_token: [u8; 32]) -> Result<ControlMessage> {
        let catalog = self.catalog.get()?;
        Ok(ControlMessage::ServerHello {
            server_instance: self.server_instance,
            world_identity: self.runtime.world_identity(),
            catalog_id: catalog.catalog_id,
            catalog_fingerprint: catalog.definition.fingerprint,
            background_token,
        })
    }

    pub fn world_identity(&self) -> [u8; 32] {
        self.runtime.world_identity()
    }
    pub fn layout(&self) -> super::RegionLayout {
        self.runtime.layout()
    }

    pub fn prepare(&self, ticket: u64, key: u64) -> Result<PreparedSection> {
        let coordinate = crate::key::SectionKey::unpack(key)?;
        let side = 16i32 >> coordinate.level;
        let region_x = coordinate.x.div_euclid(side);
        let region_z = coordinate.z.div_euclid(side);
        let region = self.runtime.region(region_x, region_z)?;
        let catalog = self.catalog.get()?.definition;
        let mut descriptor = RecordDescriptor {
            ticket,
            key,
            generation: 0,
            status: RecordStatus::NotReady,
            binding: ContentBinding::default(),
        };
        let mut ordinal = 0;
        if let Some(region) = &region {
            descriptor.generation = region.generation();
            match region.layout().index(region_x, region_z, coordinate.into()) {
                Ok(index) => {
                    ordinal = index as u32;
                    let entry = region.entry_ordinal(ordinal)?;
                    descriptor.status = if !entry.is_present() {
                        RecordStatus::Absent
                    } else if !entry.has_payload() {
                        RecordStatus::Empty
                    } else {
                        RecordStatus::Data
                    };
                    descriptor.binding = ContentBinding {
                        flags: entry.flags,
                        children: entry.non_empty_children,
                        catalog_fingerprint: catalog.fingerprint,
                        fingerprint: entry.fingerprint,
                        compressed_length: entry.compressed_length,
                        canonical_length: entry.canonical_length,
                        compressed_crc: entry.compressed_crc,
                    };
                    if !entry.is_present() {
                        descriptor.binding = ContentBinding::default();
                    }
                }
                Err(_) => descriptor.status = RecordStatus::Absent,
            }
        } else if self.runtime.confirmed_absent(region_x, region_z)? {
            descriptor.status = RecordStatus::Absent;
        }
        Ok(PreparedSection {
            descriptor,
            region,
            ordinal,
            catalog,
            runtime: self.runtime.clone(),
            wake: self.wake.clone(),
        })
    }

    pub fn subscribe_region(&self, region_x: i32, region_z: i32) -> Result<()> {
        self.runtime.subscribe_region(region_x, region_z)?;
        self.wake.notify_one();
        Ok(())
    }

    pub fn unsubscribe_region(&self, region_x: i32, region_z: i32) -> Result<()> {
        self.runtime.unsubscribe_region(region_x, region_z)
    }
}

pub struct PreparedSection {
    pub descriptor: RecordDescriptor,
    region: Option<Arc<super::RegionFile>>,
    ordinal: u32,
    pub catalog: Arc<CatalogDefinition>,
    runtime: Arc<RegionalRuntime>,
    wake: Arc<Notify>,
}
impl PreparedSection {
    pub fn body(&self) -> Result<Vec<u8>> {
        if self.descriptor.status != RecordStatus::Data {
            return Ok(Vec::new());
        }
        let region = self
            .region
            .as_ref()
            .context("terrain record lost its snapshot")?;
        match region.read_compressed_ordinal(self.ordinal) {
            Ok(body) => body.context("terrain record lost its body"),
            Err(error) => {
                let coordinate = region.region();
                if self.runtime.quarantine_generation(
                    coordinate.0,
                    coordinate.1,
                    region.generation(),
                )? {
                    self.wake.notify_one();
                }
                Err(error.context("quarantined damaged terrain snapshot"))
            }
        }
    }
}

#[derive(Clone, Debug)]
struct CachedCatalog {
    generation: u64,
    catalog_id: u64,
    definition: Arc<CatalogDefinition>,
}

/// One immutable, globally encoded definition retained by its prepared records and writers.
#[derive(Debug)]
pub struct CatalogDefinition {
    pub fingerprint: [u8; 32],
    pub canonical_length: u32,
    pub compressed_length: u32,
    pub frame: Arc<[u8]>,
}

#[derive(Debug)]
struct CatalogCache {
    registry: Arc<RwLock<Registry>>,
    current: Mutex<Option<CachedCatalog>>,
    trace: bool,
}

impl CatalogCache {
    fn new(registry: Arc<RwLock<Registry>>) -> Self {
        Self {
            registry,
            current: Mutex::new(None),
            trace: std::env::var_os("VOXY_BACKGROUND_TRACE").is_some_and(|value| value == "1"),
        }
    }

    fn get(&self) -> Result<CachedCatalog> {
        let generation = read_lock(&self.registry)?.generation();
        let mut current = self
            .current
            .lock()
            .map_err(|_| anyhow::anyhow!("regional catalog cache lock poisoned"))?;
        if let Some(cached) = current.as_ref()
            && cached.generation == generation
        {
            return Ok(cached.clone());
        }
        let started = Instant::now();
        let snapshot = read_lock(&self.registry)?.snapshot();
        let canonical = Catalog::from_snapshot(&snapshot)?.encode()?;
        let fingerprint = *blake3::hash(&canonical).as_bytes();
        let canonical_length = u32::try_from(canonical.len())?;
        let compressed =
            zstd::bulk::compress(&canonical, 1).context("compress catalogue definition")?;
        let compressed_length = u32::try_from(compressed.len())?;
        let frame: Arc<[u8]> = encode_control_record(&ControlMessage::Catalog {
            fingerprint,
            canonical_length,
            compressed,
        })?
        .into();
        if self.trace {
            eprintln!(
                "VOXY_CATALOG_BUILT catalog_id={} generation={} fingerprint={} canonical_bytes={canonical_length} compressed_bytes={compressed_length} build_ns={}",
                snapshot.catalog_id,
                snapshot.generation,
                blake3::Hash::from(fingerprint),
                started.elapsed().as_nanos()
            );
        }
        let cached = CachedCatalog {
            generation: snapshot.generation,
            catalog_id: snapshot.catalog_id,
            definition: Arc::new(CatalogDefinition {
                fingerprint,
                canonical_length,
                compressed_length,
                frame,
            }),
        };
        *current = Some(cached.clone());
        Ok(cached)
    }
}
