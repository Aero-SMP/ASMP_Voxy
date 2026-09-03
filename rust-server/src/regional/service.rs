use super::{
    RegionalRuntime,
    wire::{
        ControlMessage, SectionReply, SectionReplyBatch, SectionReplyStatus, SectionRequestBatch,
    },
};
use crate::anvil::AnvilWorld;
use crate::{catalog::Catalog, read_lock, registry::Registry};
use anyhow::{Context, Result, bail};
use std::{
    collections::BTreeMap,
    path::Path,
    sync::{Arc, Mutex, RwLock, Weak},
    time::Duration,
};
use tokio::{
    sync::{Notify, broadcast},
    task::JoinHandle,
};

const ANNOUNCEMENT_CAPACITY: usize = 4_096;
const TARGET_SECTION_BATCH_BYTES: usize = 2 * 1024 * 1024;

#[derive(Clone, Debug)]
pub enum RegionalAnnouncement {
    Changed {
        dimension: String,
        region_x: i32,
        region_z: i32,
        generation: u64,
    },
    Shutdown(String),
}

/// Owns the current regional runtimes and their bounded publication loop. Each refresh publishes
/// at most one shard per dimension; serving remains independent and never waits for a world-wide
/// root or garbage-collection pass.
#[derive(Debug)]
pub struct RegionalService {
    runtimes: BTreeMap<String, Arc<RegionalRuntime>>,
    catalog: Arc<CatalogCache>,
    announcements: broadcast::Sender<RegionalAnnouncement>,
    wake: Arc<Notify>,
    worker: Mutex<Option<JoinHandle<()>>>,
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

    pub fn subscribe(&self) -> broadcast::Receiver<RegionalAnnouncement> {
        self.announcements.subscribe()
    }

    pub fn refresh_all(&self) -> Result<bool> {
        let mut more = false;
        for (dimension, runtime) in &self.runtimes {
            let refresh = runtime.refresh()?;
            more |= refresh.more_pending;
            for (region_x, region_z, generation) in refresh.changed {
                let _ = self.announcements.send(RegionalAnnouncement::Changed {
                    dimension: dimension.clone(),
                    region_x,
                    region_z,
                    generation,
                });
            }
            for (region_x, region_z) in refresh.removed {
                let _ = self.announcements.send(RegionalAnnouncement::Changed {
                    dimension: dimension.clone(),
                    region_x,
                    region_z,
                    generation: 0,
                });
            }
        }
        Ok(more)
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
    loop {
        let Some(current) = service.upgrade() else {
            return;
        };
        let wake = current.wake.clone();
        let refresh = tokio::task::spawn_blocking(move || current.refresh_all()).await;
        let more = match refresh {
            Ok(Ok(more)) => more,
            Ok(Err(error)) => {
                eprintln!("regional publication refresh failed safely: {error:#}");
                false
            }
            Err(error) if error.is_cancelled() => return,
            Err(error) => {
                eprintln!("regional publication worker failed safely: {error}");
                false
            }
        };
        // Continue immediately while clean-import shards remain. Once caught up, polling is cheap:
        // only Anvil headers and the compact source tables are compared.
        if !more {
            tokio::select! {
                _ = tokio::time::sleep(poll_interval) => {},
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

    pub fn hello(&self) -> Result<ControlMessage> {
        let catalog = self.catalog.get()?;
        Ok(ControlMessage::ServerHello {
            server_instance: self.server_instance,
            world_identity: self.runtime.world_identity(),
            catalog_id: catalog.catalog_id,
            catalog_fingerprint: catalog.fingerprint,
        })
    }

    pub fn catalog_response(&self) -> Result<ControlMessage> {
        let catalog = self.catalog.get()?;
        Ok(ControlMessage::Catalog {
            fingerprint: catalog.fingerprint,
            canonical: catalog.canonical.to_vec(),
        })
    }

    pub fn region(&self, region_x: i32, region_z: i32) -> Result<ControlMessage> {
        let Some(region) = self.runtime.region(region_x, region_z)? else {
            return Ok(ControlMessage::RegionAbsent { region_x, region_z });
        };
        let catalog = self.catalog.get()?;
        Ok(ControlMessage::Region {
            region_x,
            region_z,
            generation: region.generation(),
            fingerprint: region.index_fingerprint(),
            catalog_fingerprint: catalog.fingerprint,
            compressed: region.compressed_index().to_vec(),
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

    /// Completes exactly the requested coverage or refinement records from immutable generations.
    pub fn sections(
        &self,
        request: &SectionRequestBatch,
        mut emit: impl FnMut(SectionReplyBatch) -> Result<()>,
    ) -> Result<()> {
        let region = self.runtime.region(request.region_x, request.region_z)?;
        let current_generation = region.as_ref().map(|region| region.generation());
        let mut current = Vec::new();
        let mut current_bytes = 12usize;
        let mut start = 0u16;
        let mut damaged = false;
        for (position, &ordinal) in request.ordinals.iter().enumerate() {
            let reply = if let Some(region) = region.as_ref() {
                if damaged || current_generation != Some(request.generation) {
                    terminal_reply(SectionReplyStatus::StaleGeneration)
                } else {
                    let entry = region.entry_ordinal(ordinal)?;
                    if !entry.is_present() {
                        terminal_reply(SectionReplyStatus::Absent)
                    } else if entry.is_empty() {
                        SectionReply {
                            status: SectionReplyStatus::Empty,
                            compressed: Vec::new(),
                        }
                    } else {
                        match region.read_compressed_ordinal(ordinal) {
                            Ok(Some(compressed)) => SectionReply {
                                status: SectionReplyStatus::Data,
                                compressed,
                            },
                            Ok(None) => terminal_reply(SectionReplyStatus::StaleGeneration),
                            Err(error) => {
                                let removed = self.runtime.quarantine_generation(
                                    request.region_x,
                                    request.region_z,
                                    region.generation(),
                                )?;
                                if removed {
                                    eprintln!(
                                        "{}: quarantined damaged regional shard ({},{}) generation {} after payload read: {error:#}",
                                        self.runtime.dimension(),
                                        request.region_x,
                                        request.region_z,
                                        region.generation(),
                                    );
                                    self.wake.notify_one();
                                }
                                damaged = true;
                                terminal_reply(SectionReplyStatus::StaleGeneration)
                            }
                        }
                    }
                }
            } else {
                terminal_reply(SectionReplyStatus::Absent)
            };
            let reply_bytes = 1usize
                .checked_add(reply.compressed.len())
                .context("regional reply size overflow")?;
            if !current.is_empty() && current_bytes + reply_bytes > TARGET_SECTION_BATCH_BYTES {
                let batch = SectionReplyBatch {
                    epoch: request.epoch,
                    start,
                    replies: std::mem::take(&mut current),
                };
                batch.encode()?;
                emit(batch)?;
                start = position as u16;
                current_bytes = 12;
            }
            current_bytes = current_bytes
                .checked_add(reply_bytes)
                .context("regional reply batch size overflow")?;
            current.push(reply);
        }
        if !current.is_empty() {
            let batch = SectionReplyBatch {
                epoch: request.epoch,
                start,
                replies: current,
            };
            batch.encode()?;
            emit(batch)?;
        }
        Ok(())
    }
}

#[derive(Clone, Debug)]
struct CachedCatalog {
    generation: u64,
    catalog_id: u64,
    fingerprint: [u8; 32],
    canonical: Arc<[u8]>,
}

#[derive(Debug)]
struct CatalogCache {
    registry: Arc<RwLock<Registry>>,
    current: Mutex<Option<CachedCatalog>>,
}

impl CatalogCache {
    fn new(registry: Arc<RwLock<Registry>>) -> Self {
        Self {
            registry,
            current: Mutex::new(None),
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
        let snapshot = read_lock(&self.registry)?.snapshot();
        let canonical: Arc<[u8]> = Catalog::from_snapshot(&snapshot)?.encode()?.into();
        let cached = CachedCatalog {
            generation: snapshot.generation,
            catalog_id: snapshot.catalog_id,
            fingerprint: *blake3::hash(&canonical).as_bytes(),
            canonical,
        };
        *current = Some(cached.clone());
        Ok(cached)
    }
}

fn terminal_reply(status: SectionReplyStatus) -> SectionReply {
    SectionReply {
        status,
        compressed: Vec::new(),
    }
}
