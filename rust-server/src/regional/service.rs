use super::{
    RegionalRuntime,
    wire::{ContentBinding, ControlMessage, DimensionMetadata, RecordDescriptor, RecordStatus},
};
use crate::anvil::AnvilWorld;
use crate::{catalog::Catalog, read_lock, registry::Registry};
use anyhow::{Context, Result, bail};
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex, RwLock, Weak,
        atomic::{AtomicU64, Ordering},
    },
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
    Discovery {
        dimension: String,
        revision: u64,
        updates: Arc<[((i32, i32), Option<crate::anvil::RegionAvailability>)]>,
        reset: bool,
    },
    Manifest,
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
    runtimes: RwLock<BTreeMap<String, DimensionEntry>>,
    data_root: PathBuf,
    registry: Arc<RwLock<Registry>>,
    next_dimension: AtomicU64,
    catalog: Arc<CatalogCache>,
    announcements: broadcast::Sender<RegionalAnnouncement>,
    wake: Arc<Notify>,
    worker: Mutex<Option<JoinHandle<()>>>,
    cadences: Mutex<SharedCadence>,
    pending_saves: Mutex<BTreeMap<String, BTreeMap<(i32, i32), [u64; 16]>>>,
}

#[derive(Clone, Debug)]
struct DimensionEntry {
    metadata: DimensionMetadata,
    runtime: Arc<RegionalRuntime>,
}

impl RegionalService {
    pub fn open(
        data_root: impl AsRef<Path>,
        dimensions: &BTreeMap<String, Arc<AnvilWorld>>,
        registry: Arc<RwLock<Registry>>,
    ) -> Result<Self> {
        // Java supplies actual live dimension layouts before a runtime can build terrain.
        // Saved paths alone cannot establish custom height or an unset world border.
        let _ = dimensions;
        let (announcements, _) = broadcast::channel(ANNOUNCEMENT_CAPACITY);
        Ok(Self {
            runtimes: RwLock::new(BTreeMap::new()),
            data_root: data_root.as_ref().to_path_buf(),
            registry: registry.clone(),
            next_dimension: AtomicU64::new(1),
            catalog: Arc::new(CatalogCache::new(registry)),
            announcements,
            wake: Arc::new(Notify::new()),
            worker: Mutex::new(None),
            cadences: Mutex::new(SharedCadence::default()),
            pending_saves: Mutex::new(BTreeMap::new()),
        })
    }

    pub fn define_dimension(
        &self,
        name: String,
        root: PathBuf,
        min_y: i32,
        count: u32,
        custom_border: bool,
        center_x: f64,
        center_z: f64,
        size: f64,
    ) -> Result<()> {
        if name.is_empty()
            || !center_x.is_finite()
            || !center_z.is_finite()
            || !size.is_finite()
            || size <= 0.0
        {
            bail!("invalid dimension metadata")
        }
        let layout = super::RegionLayout::new(min_y, count.try_into()?, crate::MAX_LOD + 1)?;
        let existing = read_lock(&self.runtimes)?.get(&name).cloned();
        let runtime = match &existing {
            Some(entry) if entry.runtime.layout() == layout => entry.runtime.clone(),
            _ => Arc::new(RegionalRuntime::open(
                &self.data_root,
                name.clone(),
                Arc::new(AnvilWorld::new(name.clone(), root)),
                self.registry.clone(),
                layout,
            )?),
        };
        let id = existing.as_ref().map_or_else(
            || self.next_dimension.fetch_add(1, Ordering::Relaxed) as u32,
            |entry| entry.metadata.id,
        );
        let catalogue = self.catalog.get()?;
        let metadata = DimensionMetadata {
            id,
            name: name.clone(),
            world_identity: runtime.world_identity(),
            min_section_y: min_y,
            section_count: count,
            custom_border,
            center_x,
            center_z,
            size,
            catalog_id: catalogue.catalog_id,
            catalog_fingerprint: catalogue.definition.fingerprint,
        };
        if existing
            .as_ref()
            .is_some_and(|entry| entry.metadata == metadata)
        {
            return Ok(());
        }
        crate::write_lock(&self.runtimes)?.insert(
            name.clone(),
            DimensionEntry {
                metadata,
                runtime: runtime.clone(),
            },
        );
        // A storage worker can finish a dimension's first save before the next Minecraft tick
        // advertises its metadata. Retain only coalesced coordinates until that definition arrives.
        if let Some(regions) = self
            .pending_saves
            .lock()
            .map_err(|_| crate::UnsafeState("pending saves owner poisoned"))?
            .remove(&name)
        {
            for ((x, z), slots) in regions {
                for (word, mut bits) in slots.into_iter().enumerate() {
                    while bits != 0 {
                        let bit = bits.trailing_zeros() as usize;
                        let slot = word * 64 + bit;
                        runtime.saved_chunks(&[(
                            x * 32 + (slot % 32) as i32,
                            z * 32 + (slot / 32) as i32,
                        )])?;
                        bits &= bits - 1;
                    }
                }
            }
        }
        let _ = self.announcements.send(RegionalAnnouncement::Manifest);
        self.wake.notify_one();
        Ok(())
    }
    pub fn manifest(&self) -> Result<Vec<DimensionMetadata>> {
        let catalogue = self.catalog.get()?;
        Ok(read_lock(&self.runtimes)?
            .values()
            .map(|entry| {
                let mut metadata = entry.metadata.clone();
                metadata.catalog_id = catalogue.catalog_id;
                metadata.catalog_fingerprint = catalogue.definition.fingerprint;
                metadata
            })
            .collect())
    }
    pub fn dimension_id(&self, name: &str) -> Result<u32> {
        read_lock(&self.runtimes)?
            .get(name)
            .map(|entry| entry.metadata.id)
            .with_context(|| format!("unknown live dimension {name}"))
    }
    pub fn dimension_name(&self, id: u32) -> Result<String> {
        read_lock(&self.runtimes)?
            .values()
            .find(|entry| entry.metadata.id == id)
            .map(|entry| entry.metadata.name.clone())
            .with_context(|| format!("unknown dimension id {id}"))
    }
    pub fn runtime(&self, dimension: &str) -> Result<Arc<RegionalRuntime>> {
        read_lock(&self.runtimes)?
            .get(dimension)
            .map(|entry| entry.runtime.clone())
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
        for entry in read_lock(&self.runtimes)?.values() {
            entry.runtime.set_freshness_interval(shortest);
        }
        self.wake.notify_one();
        Ok(())
    }

    pub fn saved_chunks(&self, dimension: &str, chunks: &[(i32, i32)]) -> Result<()> {
        if let Some(runtime) = read_lock(&self.runtimes)?
            .get(dimension)
            .map(|entry| entry.runtime.clone())
        {
            return runtime.saved_chunks(chunks);
        }
        let mut pending = self
            .pending_saves
            .lock()
            .map_err(|_| crate::UnsafeState("pending saves owner poisoned"))?;
        let regions = pending.entry(dimension.to_owned()).or_default();
        for &(x, z) in chunks {
            let bits = regions
                .entry((x.div_euclid(32), z.div_euclid(32)))
                .or_default();
            let slot = (z.rem_euclid(32) * 32 + x.rem_euclid(32)) as usize;
            bits[slot / 64] |= 1 << (slot % 64);
        }
        Ok(())
    }

    pub fn wake(&self) {
        self.wake.notify_one();
    }

    pub fn subscribe(&self) -> broadcast::Receiver<RegionalAnnouncement> {
        self.announcements.subscribe()
    }

    pub fn refresh_all(&self, round: u64) -> Result<RefreshStatus> {
        let mut result = RefreshStatus::default();
        let runtimes = read_lock(&self.runtimes)?
            .iter()
            .map(|(name, entry)| (name.clone(), entry.runtime.clone()))
            .collect::<Vec<_>>();
        for (dimension, runtime) in runtimes {
            let refresh = runtime.refresh(round)?;
            if refresh.inventory_changed {
                let _ = self.announcements.send(RegionalAnnouncement::Discovery {
                    dimension: dimension.clone(),
                    revision: runtime.inventory_revision(),
                    updates: refresh.inventory_updates.clone().into(),
                    reset: refresh.inventory_reset,
                });
            }
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

    pub fn hello(&self, dimension: u32) -> Result<ControlMessage> {
        let catalog = self.catalog.get()?;
        Ok(ControlMessage::ServerHello {
            server_instance: self.server_instance,
            active_dimension: dimension,
            world_identity: self.runtime.world_identity(),
            catalog_id: catalog.catalog_id,
            catalog_fingerprint: catalog.definition.fingerprint,
        })
    }

    pub fn catalogue(&self) -> Result<Arc<CatalogDefinition>> {
        Ok(self.catalog.get()?.definition)
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
                    descriptor.binding = ContentBinding::from_entry(entry, catalog.fingerprint);
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
    pub payload: Arc<[u8]>,
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
            trace: std::env::var_os("VOXY_NETWORK_TRACE").is_some_and(|value| value == "1"),
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
        let mut payload = Vec::with_capacity(40 + compressed.len());
        payload.extend_from_slice(&fingerprint);
        payload.extend_from_slice(&canonical_length.to_le_bytes());
        payload.extend_from_slice(&compressed_length.to_le_bytes());
        payload.extend_from_slice(&compressed);
        let payload: Arc<[u8]> = payload.into();
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
                payload,
            }),
        };
        *current = Some(cached.clone());
        Ok(cached)
    }
}
