use super::{ChunkSourceRecord, RegionFile, RegionLayout, RegionSourceTable, rebuild_region};
use crate::{
    anvil::{AnvilWorld, RegionHeader},
    read_lock,
    registry::Registry,
    safe_dimension_name, write_lock,
};
use anyhow::{Context, Result, bail};
use std::{
    collections::{BTreeMap, BTreeSet, VecDeque},
    fs,
    path::{Path, PathBuf},
    sync::{Arc, Mutex, RwLock},
};

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct RegionalRefresh {
    pub changed: Vec<(i32, i32, u64)>,
    pub removed: Vec<(i32, i32)>,
    pub metadata_only: usize,
    pub more_pending: bool,
}

#[derive(Debug)]
pub struct RegionalRuntime {
    dimension: String,
    source: Arc<AnvilWorld>,
    registry: Arc<RwLock<Registry>>,
    root: PathBuf,
    world_identity: [u8; 32],
    layout: RegionLayout,
    regions: RwLock<BTreeMap<(i32, i32), Arc<RegionFile>>>,
    sources: RwLock<BTreeMap<(i32, i32), RegionSourceTable>>,
    maintenance: Mutex<()>,
    priority: Mutex<PriorityRequests>,
}

#[derive(Debug, Default)]
struct PriorityRequests {
    order: VecDeque<(i32, i32)>,
    membership: BTreeSet<(i32, i32)>,
    subscriptions: BTreeMap<(i32, i32), usize>,
}

impl RegionalRuntime {
    pub fn open(
        data_root: impl AsRef<Path>,
        dimension: String,
        source: Arc<AnvilWorld>,
        registry: Arc<RwLock<Registry>>,
        layout: RegionLayout,
    ) -> Result<Self> {
        if source.dimension != dimension {
            bail!("regional runtime dimension and Anvil source disagree");
        }
        let root = data_root
            .as_ref()
            .join("regional")
            .join(safe_dimension_name(&dimension));
        fs::create_dir_all(&root).with_context(|| format!("create {}", root.display()))?;
        let catalog_id = read_lock(&registry)?.catalog_id();
        let mut identity = blake3::Hasher::new();
        identity.update(b"Voxy regional world identity\0");
        identity.update(&catalog_id.to_le_bytes());
        identity.update(dimension.as_bytes());
        let world_identity = *identity.finalize().as_bytes();

        let mut regions = BTreeMap::new();
        let mut sources = BTreeMap::new();
        for entry in fs::read_dir(&root)? {
            let entry = entry?;
            let path = entry.path();
            let Some((coordinate, kind)) = parse_file_name(&path) else {
                continue;
            };
            match kind {
                RegionalFileKind::Terrain => match RegionFile::open(&path) {
                    Ok(region)
                        if region.region() == coordinate
                            && region.world_identity() == world_identity
                            && region.catalog_id() == catalog_id
                            && region.layout() == layout =>
                    {
                        regions.insert(coordinate, Arc::new(region));
                    }
                    Ok(_) | Err(_) => crate::quarantine(&path),
                },
                RegionalFileKind::Source => match RegionSourceTable::open(&path) {
                    Ok(table) if (table.region_x, table.region_z) == coordinate => {
                        sources.insert(coordinate, table);
                    }
                    Ok(_) | Err(_) => crate::quarantine(&path),
                },
            }
        }
        sources.retain(|coordinate, table| {
            regions
                .get(coordinate)
                .is_some_and(|region| region.generation() == table.terrain_generation)
        });
        Ok(Self {
            dimension,
            source,
            registry,
            root,
            world_identity,
            layout,
            regions: RwLock::new(regions),
            sources: RwLock::new(sources),
            maintenance: Mutex::new(()),
            priority: Mutex::new(PriorityRequests::default()),
        })
    }

    pub fn dimension(&self) -> &str {
        &self.dimension
    }

    pub fn world_identity(&self) -> [u8; 32] {
        self.world_identity
    }

    pub fn region(&self, x: i32, z: i32) -> Result<Option<Arc<RegionFile>>> {
        Ok(read_lock(&self.regions)?.get(&(x, z)).cloned())
    }

    /// Moves an explicitly requested shard ahead of background import work. Duplicate hints are
    /// coalesced; this queue contains coordinates only, never payloads.
    pub fn prioritize_region(&self, region_x: i32, region_z: i32) -> Result<()> {
        let mut queue = self
            .priority
            .lock()
            .map_err(|_| anyhow::anyhow!("regional priority lock poisoned"))?;
        let coordinate = (region_x, region_z);
        if queue.membership.insert(coordinate) {
            queue.order.push_back(coordinate);
        }
        Ok(())
    }

    /// Tracks live client interest separately from immutable region data. The first subscriber
    /// queues missing work; the last release cancels that priority without deleting terrain.
    pub fn subscribe_region(&self, region_x: i32, region_z: i32) -> Result<()> {
        let mut queue = self
            .priority
            .lock()
            .map_err(|_| anyhow::anyhow!("regional priority lock poisoned"))?;
        let coordinate = (region_x, region_z);
        let subscribers = queue.subscriptions.entry(coordinate).or_default();
        *subscribers = subscribers
            .checked_add(1)
            .ok_or_else(|| anyhow::anyhow!("regional subscription count overflow"))?;
        if *subscribers == 1 && queue.membership.insert(coordinate) {
            queue.order.push_back(coordinate);
        }
        Ok(())
    }

    pub fn unsubscribe_region(&self, region_x: i32, region_z: i32) -> Result<()> {
        let mut queue = self
            .priority
            .lock()
            .map_err(|_| anyhow::anyhow!("regional priority lock poisoned"))?;
        let coordinate = (region_x, region_z);
        let remove = match queue.subscriptions.get_mut(&coordinate) {
            Some(subscribers) if *subscribers > 1 => {
                *subscribers -= 1;
                false
            }
            Some(_) => true,
            None => return Ok(()),
        };
        if remove {
            queue.subscriptions.remove(&coordinate);
            queue.membership.remove(&coordinate);
        }
        Ok(())
    }

    /// Removes only the corrupt generation observed by a reader. The immutable open file may
    /// finish serving other reads, while the publication worker rebuilds a clean replacement.
    pub fn quarantine_generation(
        &self,
        region_x: i32,
        region_z: i32,
        generation: u64,
    ) -> Result<bool> {
        let _maintenance = self
            .maintenance
            .lock()
            .map_err(|_| anyhow::anyhow!("regional maintenance lock poisoned"))?;
        let coordinate = (region_x, region_z);
        let removed = {
            let mut regions = write_lock(&self.regions)?;
            if regions
                .get(&coordinate)
                .is_none_or(|region| region.generation() != generation)
            {
                return Ok(false);
            }
            regions.remove(&coordinate)
        };
        if removed.is_none() {
            return Ok(false);
        }
        write_lock(&self.sources)?.remove(&coordinate);
        crate::quarantine(&self.terrain_path(coordinate));
        remove_if_exists(&self.source_path(coordinate))?;
        self.prioritize_region(region_x, region_z)?;
        Ok(true)
    }

    /// Publishes at most one terrain region per invocation. That makes initial coverage visible
    /// immediately and bounds build memory without throttling parsing, compression, or serving
    /// inside the selected region.
    pub fn refresh(&self) -> Result<RegionalRefresh> {
        let _maintenance = self
            .maintenance
            .lock()
            .map_err(|_| anyhow::anyhow!("regional maintenance lock poisoned"))?;
        let headers = self.source.region_headers()?;
        for failed in headers.failed {
            eprintln!(
                "{}: cannot snapshot Anvil region ({},{}): {}",
                self.dimension, failed.region_x, failed.region_z, failed.error
            );
        }
        let headers = headers
            .valid
            .into_iter()
            .map(|header| ((header.region_x, header.region_z), header))
            .collect::<BTreeMap<_, _>>();
        let stored_coordinates = read_lock(&self.regions)?
            .keys()
            .copied()
            .collect::<BTreeSet<_>>();
        let mut result = RegionalRefresh::default();

        for coordinate in stored_coordinates.difference(&headers.keys().copied().collect()) {
            write_lock(&self.regions)?.remove(coordinate);
            write_lock(&self.sources)?.remove(coordinate);
            remove_if_exists(&self.terrain_path(*coordinate))?;
            remove_if_exists(&self.source_path(*coordinate))?;
            result.removed.push(*coordinate);
        }

        let mut order = Vec::with_capacity(headers.len());
        let mut seen = BTreeSet::new();
        {
            let mut priority = self
                .priority
                .lock()
                .map_err(|_| anyhow::anyhow!("regional priority lock poisoned"))?;
            while let Some(&coordinate) = priority.order.front() {
                if !priority.membership.contains(&coordinate) {
                    priority.order.pop_front();
                    continue;
                }
                if let Some(header) = headers.get(&coordinate)
                    && !self.header_is_current(coordinate, header)
                {
                    order.push(coordinate);
                    seen.insert(coordinate);
                    // Keep the selected coordinate at the head until publication succeeds. If
                    // Anvil changes during the build, the next refresh retries the same requested
                    // region instead of leaving a permanent local hole.
                    break;
                }
                priority.order.pop_front();
                priority.membership.remove(&coordinate);
            }
        }
        for coordinate in headers.keys().copied() {
            if seen.insert(coordinate) {
                order.push(coordinate);
            }
        }

        for coordinate in order {
            let header = &headers[&coordinate];
            let region = read_lock(&self.regions)?.get(&coordinate).cloned();
            let stored_source = read_lock(&self.sources)?.get(&coordinate).cloned();
            if let (Some(region), Some(stored_source)) = (&region, &stored_source)
                && stored_source.terrain_generation == region.generation()
                && stored_source.header_matches(&header.entries, header.file_marker)
            {
                continue;
            }

            if let (Some(region), Some(previous)) = (&region, stored_source)
                && previous.terrain_generation == region.generation()
                && let Some(updated) = self.probe_metadata_only(header, &previous)?
            {
                updated.write_atomic(self.source_path(coordinate))?;
                write_lock(&self.sources)?.insert(coordinate, updated);
                result.metadata_only += 1;
                continue;
            }

            let generation = region.as_ref().map_or(1, |region| region.generation() + 1);
            let (built, stats) = rebuild_region(
                &self.source,
                &self.registry,
                header,
                self.terrain_path(coordinate),
                self.source_path(coordinate),
                self.world_identity,
                generation,
                self.layout,
            )?;
            let source = RegionSourceTable::open(self.source_path(coordinate))?;
            eprintln!(
                "{}: regional shard ({},{}) generation {} chunks={}/{} sections={:?} bytes={}",
                self.dimension,
                coordinate.0,
                coordinate.1,
                generation,
                stats.generated_chunks,
                stats.chunks_read,
                stats.sections_by_level,
                stats.output_bytes
            );
            write_lock(&self.regions)?.insert(coordinate, Arc::new(built));
            write_lock(&self.sources)?.insert(coordinate, source);
            result
                .changed
                .push((coordinate.0, coordinate.1, generation));
            result.more_pending = headers.iter().any(|(other, other_header)| {
                *other != coordinate && !self.header_is_current(*other, other_header)
            });
            return Ok(result);
        }
        Ok(result)
    }

    fn header_is_current(&self, coordinate: (i32, i32), header: &RegionHeader) -> bool {
        let Ok(regions) = self.regions.read() else {
            return false;
        };
        let Ok(sources) = self.sources.read() else {
            return false;
        };
        regions.get(&coordinate).is_some_and(|region| {
            sources.get(&coordinate).is_some_and(|source| {
                source.terrain_generation == region.generation()
                    && source.header_matches(&header.entries, header.file_marker)
            })
        })
    }

    /// Returns an updated source table only when every changed Anvil record normalizes to the
    /// same terrain. A real semantic change returns `None` and triggers a regional rebuild.
    fn probe_metadata_only(
        &self,
        header: &RegionHeader,
        previous: &RegionSourceTable,
    ) -> Result<Option<RegionSourceTable>> {
        let mut updated = RegionSourceTable::new(
            header.region_x,
            header.region_z,
            previous.terrain_generation,
            header.file_marker,
        )?;
        let base_x = header.region_x * 32;
        let base_z = header.region_z * 32;
        for (slot, entry) in header.entries.iter().copied().enumerate() {
            let local_x = (slot & 31) as u8;
            let local_z = (slot >> 5) as u8;
            let old = previous.record(local_x, local_z)?;
            let present = entry.location >> 8 != 0 && entry.location & 0xff != 0;
            let record = if entry.location == old.anvil_location
                && entry.timestamp == old.anvil_timestamp
                && present == old.generated
            {
                old
            } else {
                let chunk = self.source.read_chunk(
                    base_x + i32::from(local_x),
                    base_z + i32::from(local_z),
                    &self.registry,
                )?;
                let current = ChunkSourceRecord {
                    generated: chunk.is_some(),
                    anvil_location: entry.location,
                    anvil_timestamp: entry.timestamp,
                    semantic_fingerprint: chunk
                        .as_ref()
                        .map_or([0; 2], |chunk| chunk.terrain_fingerprint),
                };
                if current.generated != old.generated
                    || current.semantic_fingerprint != old.semantic_fingerprint
                {
                    return Ok(None);
                }
                current
            };
            updated.set_record(local_x, local_z, record)?;
        }
        let current = self
            .source
            .region_header(header.region_x, header.region_z)?
            .context("Anvil region disappeared during metadata probe")?;
        if current.file_marker != header.file_marker || current.entries != header.entries {
            bail!("Anvil region changed during metadata-only probe");
        }
        Ok(Some(updated))
    }

    fn terrain_path(&self, coordinate: (i32, i32)) -> PathBuf {
        self.root
            .join(format!("r.{}.{}.vxregion", coordinate.0, coordinate.1))
    }

    fn source_path(&self, coordinate: (i32, i32)) -> PathBuf {
        self.root
            .join(format!("r.{}.{}.vxsource", coordinate.0, coordinate.1))
    }
}

fn remove_if_exists(path: &Path) -> Result<()> {
    match fs::remove_file(path) {
        Ok(()) => crate::sync_parent(path),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum RegionalFileKind {
    Terrain,
    Source,
}

fn parse_file_name(path: &Path) -> Option<((i32, i32), RegionalFileKind)> {
    let name = path.file_name()?.to_str()?;
    let parts = name.split('.').collect::<Vec<_>>();
    if parts.len() != 4 || parts[0] != "r" {
        return None;
    }
    let kind = match parts[3] {
        "vxregion" => RegionalFileKind::Terrain,
        "vxsource" => RegionalFileKind::Source,
        _ => return None,
    };
    Some(((parts[1].parse().ok()?, parts[2].parse().ok()?), kind))
}
