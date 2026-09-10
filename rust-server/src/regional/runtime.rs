use super::{
    ChunkSourceRecord, RegionFile, RegionLayout, RegionSourceTable, rebuild_region,
    rebuild_region_incremental,
};
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
    pub incomplete: bool,
    pub failure: Option<String>,
}

#[derive(Debug)]
pub struct RegionalRuntime {
    dimension: String,
    source: Arc<AnvilWorld>,
    registry: Arc<RwLock<Registry>>,
    root: PathBuf,
    world_identity: [u8; 32],
    layout: RegionLayout,
    // Keep only generation metadata resident. Region indexes and file handles are opened for the
    // one request or rebuild that owns them, so world size cannot multiply the full directory.
    regions: RwLock<BTreeMap<(i32, i32), u64>>,
    sources: RwLock<BTreeMap<(i32, i32), RegionSourceTable>>,
    // Successful Anvil inventory, including unreadable regions. Missing generated LOD data
    // alone never proves terrain deletion; a failed directory scan revokes absence evidence.
    inventory: RwLock<Option<BTreeSet<(i32, i32)>>>,
    maintenance: Mutex<Maintenance>,
    priority: Mutex<PriorityRequests>,
}

#[derive(Debug, Default)]
struct Maintenance {
    retry: BTreeMap<(i32, i32), Retry>,
    inventory_failed_round: Option<u64>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum RetryKind {
    Refresh,
    Reconcile,
    Cleanup,
}
#[derive(Clone, Copy, Debug)]
struct Retry {
    kind: RetryKind,
    generation: u64,
    round: Option<u64>,
}

#[derive(Debug, Default)]
struct PriorityRequests {
    order: VecDeque<(i32, i32)>,
    membership: BTreeSet<(i32, i32)>,
    subscriptions: BTreeMap<(i32, i32), usize>,
    active: BTreeMap<(i32, i32), Arc<RegionFile>>,
}

fn refresh_order<T>(
    headers: &BTreeMap<(i32, i32), T>,
    priority: &Mutex<PriorityRequests>,
    mut is_current: impl FnMut((i32, i32), &T) -> Result<bool>,
    mut deferred: impl FnMut((i32, i32)) -> bool,
) -> Result<Vec<(i32, i32)>> {
    let mut selected = None;
    {
        let mut priority = priority
            .lock()
            .map_err(|_| crate::UnsafeState("regional priority lock poisoned"))?;
        let mut remaining = priority.order.len();
        while remaining > 0 {
            remaining -= 1;
            let Some(&coordinate) = priority.order.front() else {
                break;
            };
            if !priority.membership.contains(&coordinate) {
                priority.order.pop_front();
                continue;
            }
            if let Some(header) = headers.get(&coordinate)
                && !is_current(coordinate, header)?
            {
                if deferred(coordinate) {
                    priority.order.pop_front();
                    priority.order.push_back(coordinate);
                    continue;
                }
                // Keep the subscription hint until publication succeeds.
                selected = Some(coordinate);
                break;
            }
            priority.order.pop_front();
            priority.membership.remove(&coordinate);
        }
    }
    let mut order = Vec::with_capacity(headers.len());
    order.extend(selected);
    order.extend(
        headers
            .keys()
            .copied()
            .filter(|coordinate| Some(*coordinate) != selected),
    );
    Ok(order)
}

#[cfg(test)]
mod order_tests {
    use super::*;

    #[test]
    fn confirmed_absence_requires_successful_source_inventory() -> Result<()> {
        let root = std::env::temp_dir().join(format!(
            "voxy-absence-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)?
                .as_nanos()
        ));
        let world = root.join("world");
        fs::create_dir_all(world.join("region"))?;
        // Corrupt source and no generated LOD file: repair/pending, never deletion.
        fs::write(world.join("region/r.0.0.mca"), [1])?;
        let runtime = RegionalRuntime::open(
            root.join("data"),
            "minecraft:overworld".into(),
            Arc::new(AnvilWorld::new("minecraft:overworld".into(), world.clone())),
            Arc::new(RwLock::new(Registry::open(root.join("registry"))?)),
            RegionLayout::new(0, 1, 5)?,
        )?;
        assert!(!runtime.confirmed_absent(0, 0)?);
        assert!(runtime.confirmed_absent(1, 0)?);
        runtime.refresh(0)?;
        assert!(!runtime.confirmed_absent(0, 0)?);
        // Deletion observed after an offline interval, with no live removal event required.
        fs::remove_file(world.join("region/r.0.0.mca"))?;
        runtime.refresh(0)?;
        assert!(runtime.confirmed_absent(0, 0)?);
        // Failed directory enumeration revokes all absence evidence.
        fs::remove_dir(world.join("region"))?;
        fs::write(world.join("region"), [1])?;
        assert!(runtime.refresh(1).unwrap().incomplete);
        assert!(!runtime.confirmed_absent(0, 0)?);
        fs::remove_file(world.join("region"))?;
        fs::remove_dir(&world)?;
        assert!(runtime.refresh(1).unwrap().incomplete);
        assert!(!runtime.confirmed_absent(1, 0)?);
        fs::create_dir(&world)?;
        runtime.refresh(2)?;
        assert!(runtime.confirmed_absent(0, 0)?);
        drop(runtime);
        fs::remove_dir_all(root)?;
        Ok(())
    }

    #[test]
    fn priority_order_is_unique_and_retries_until_current() {
        let headers = BTreeMap::from([((-2, 1), ()), ((0, 0), ()), ((4, -3), ())]);
        let natural = headers.keys().copied().collect::<Vec<_>>();
        let empty = Mutex::new(PriorityRequests::default());
        assert_eq!(
            refresh_order(&headers, &empty, |_, _| Ok(false), |_| false).unwrap(),
            natural
        );
        for chosen in natural.iter().copied() {
            let stale = (100, 100);
            let cancelled = (101, 101);
            let priority = Mutex::new(PriorityRequests {
                order: VecDeque::from([cancelled, stale, chosen, chosen]),
                membership: BTreeSet::from([stale, chosen]),
                ..Default::default()
            });
            let expected = std::iter::once(chosen)
                .chain(natural.iter().copied().filter(|key| *key != chosen))
                .collect::<Vec<_>>();
            // A build failure does not mark the header current: repeat the actual order path.
            for _ in 0..2 {
                assert_eq!(
                    refresh_order(&headers, &priority, |_, _| Ok(false), |_| false).unwrap(),
                    expected
                );
                assert_eq!(priority.lock().unwrap().order.front(), Some(&chosen));
            }
            assert_eq!(
                refresh_order(&headers, &priority, |key, _| Ok(key == chosen), |_| false).unwrap(),
                natural
            );
            let priority = priority.lock().unwrap();
            assert!(priority.order.is_empty());
            assert!(priority.membership.is_empty());
        }
    }
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

        let source_snapshot = source.region_headers().ok();
        let inventory_known = source_snapshot.is_some();
        let source_snapshot = source_snapshot.unwrap_or_default();
        let source_coordinates = source_snapshot
            .valid
            .iter()
            .map(|header| (header.region_x, header.region_z))
            .chain(
                source_snapshot
                    .failed
                    .iter()
                    .map(|failed| (failed.region_x, failed.region_z)),
            )
            .collect::<BTreeSet<_>>();
        drop(source_snapshot);

        let mut maintenance = Maintenance::default();
        let mut regions = BTreeMap::new();
        let mut sources = BTreeMap::new();
        for entry in fs::read_dir(&root)? {
            let entry = entry?;
            let path = entry.path();
            let Some((coordinate, kind)) = parse_file_name(&path) else {
                continue;
            };
            if inventory_known && !source_coordinates.contains(&coordinate) {
                maintenance.retry.insert(
                    coordinate,
                    Retry {
                        kind: RetryKind::Cleanup,
                        generation: 0,
                        round: None,
                    },
                );
                continue;
            }
            match kind {
                RegionalFileKind::Terrain => match RegionFile::open(&path) {
                    Ok(region)
                        if region.region() == coordinate
                            && region.world_identity() == world_identity
                            && region.catalog_id() == catalog_id
                            && region.layout() == layout =>
                    {
                        if durable_file(&path).is_ok() {
                            regions.insert(coordinate, region.generation());
                        } else {
                            maintenance.retry.insert(
                                coordinate,
                                Retry {
                                    kind: RetryKind::Reconcile,
                                    generation: region.generation(),
                                    round: None,
                                },
                            );
                        }
                    }
                    Ok(_) | Err(_) => crate::quarantine(&path),
                },
                RegionalFileKind::Source => match RegionSourceTable::open(&path) {
                    Ok(table) if (table.region_x, table.region_z) == coordinate => {
                        if durable_file(&path).is_ok() {
                            sources.insert(coordinate, table);
                        } else {
                            maintenance.retry.entry(coordinate).or_insert(Retry {
                                kind: RetryKind::Refresh,
                                generation: table.terrain_generation,
                                round: None,
                            });
                        }
                    }
                    Ok(_) | Err(_) => crate::quarantine(&path),
                },
            }
        }
        sources.retain(|coordinate, table| {
            regions
                .get(coordinate)
                .is_some_and(|generation| *generation == table.terrain_generation)
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
            inventory: RwLock::new(inventory_known.then_some(source_coordinates)),
            maintenance: Mutex::new(maintenance),
            priority: Mutex::new(PriorityRequests::default()),
        })
    }

    pub fn dimension(&self) -> &str {
        &self.dimension
    }

    pub fn world_identity(&self) -> [u8; 32] {
        self.world_identity
    }

    pub fn confirmed_absent(&self, x: i32, z: i32) -> Result<bool> {
        Ok(read_lock(&self.inventory)?
            .as_ref()
            .is_some_and(|coordinates| !coordinates.contains(&(x, z))))
    }

    pub fn region(&self, x: i32, z: i32) -> Result<Option<Arc<RegionFile>>> {
        let coordinate = (x, z);
        if let Some(region) = self
            .priority
            .lock()
            .map_err(|_| crate::UnsafeState("regional priority lock poisoned"))?
            .active
            .get(&coordinate)
            .cloned()
        {
            return Ok(Some(region));
        }
        let Some(generation) = read_lock(&self.regions)?.get(&coordinate).copied() else {
            return Ok(None);
        };
        match self.open_generation(coordinate, generation) {
            Ok(region) => {
                let region = Arc::new(region);
                let mut priority = self
                    .priority
                    .lock()
                    .map_err(|_| crate::UnsafeState("regional priority lock poisoned"))?;
                if priority.subscriptions.contains_key(&coordinate) {
                    priority.active.insert(coordinate, region.clone());
                }
                Ok(Some(region))
            }
            Err(error) => {
                let removed = self.quarantine_generation(x, z, generation)?;
                if removed {
                    eprintln!(
                        "{}: quarantined damaged regional shard ({x},{z}) generation {generation} while opening it: {error:#}",
                        self.dimension
                    );
                }
                Ok(None)
            }
        }
    }

    /// Moves an explicitly requested shard ahead of background import work. Duplicate hints are
    /// coalesced; this queue contains coordinates only, never payloads.
    pub fn prioritize_region(&self, region_x: i32, region_z: i32) -> Result<()> {
        let mut queue = self
            .priority
            .lock()
            .map_err(|_| crate::UnsafeState("regional priority lock poisoned"))?;
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
            .map_err(|_| crate::UnsafeState("regional priority lock poisoned"))?;
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

    #[cfg(test)]
    pub(crate) fn test_subscription_count(&self) -> usize {
        self.priority.lock().unwrap().subscriptions.values().sum()
    }

    pub fn unsubscribe_region(&self, region_x: i32, region_z: i32) -> Result<()> {
        let mut queue = self
            .priority
            .lock()
            .map_err(|_| crate::UnsafeState("regional priority lock poisoned"))?;
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
            queue.active.remove(&coordinate);
        }
        Ok(())
    }

    /// Quarantines only the corrupt generation observed by a reader. Its already-open immutable
    /// file remains available as last-known-good display state while a clean replacement builds.
    pub fn quarantine_generation(
        &self,
        region_x: i32,
        region_z: i32,
        generation: u64,
    ) -> Result<bool> {
        let mut maintenance = self
            .maintenance
            .lock()
            .map_err(|_| crate::UnsafeState("regional maintenance lock poisoned"))?;
        let coordinate = (region_x, region_z);
        {
            let regions = read_lock(&self.regions)?;
            if regions
                .get(&coordinate)
                .is_none_or(|current| *current != generation)
            {
                return Ok(false);
            }
        }
        // A failed post-rename publication owns the final path; a reader of the old
        // generation must not quarantine the newer file while reconciliation is pending.
        if maintenance
            .retry
            .get(&coordinate)
            .is_some_and(|r| r.kind == RetryKind::Reconcile)
        {
            return Ok(false);
        }
        let generation = generation.max(
            maintenance
                .retry
                .get(&coordinate)
                .map_or(0, |r| r.generation),
        );
        maintenance.retry.insert(
            coordinate,
            Retry {
                kind: RetryKind::Refresh,
                generation,
                round: None,
            },
        );
        write_lock(&self.regions)?.remove(&coordinate);
        write_lock(&self.sources)?.remove(&coordinate);
        crate::quarantine(&self.terrain_path(coordinate));
        self.priority
            .lock()
            .map_err(|_| crate::UnsafeState("regional priority lock poisoned"))?
            .active
            .remove(&coordinate);
        remove_if_exists(&self.source_path(coordinate))?;
        self.prioritize_region(region_x, region_z)?;
        Ok(true)
    }

    /// Publishes at most one terrain region per invocation. That makes initial coverage visible
    /// immediately and bounds build memory without throttling parsing, compression, or serving
    /// inside the selected region.
    pub fn refresh(&self, round: u64) -> Result<RegionalRefresh> {
        let mut maintenance = self
            .maintenance
            .lock()
            .map_err(|_| crate::UnsafeState("regional maintenance lock poisoned"))?;
        let mut result = RegionalRefresh::default();
        if maintenance.inventory_failed_round == Some(round) {
            result.incomplete = true;
            result.failure = Some(format!("{}: inventory deferred this round", self.dimension));
            return Ok(result);
        }
        let snapshot = match self.source.region_headers() {
            Ok(headers) => headers,
            Err(error) => {
                *write_lock(&self.inventory)? = None;
                maintenance.inventory_failed_round = Some(round);
                result.incomplete = true;
                result.failure = Some(format!("{}: inventory: {error:#}", self.dimension));
                eprintln!("{}", result.failure.as_ref().unwrap());
                return Ok(result);
            }
        };
        maintenance.inventory_failed_round = None;
        #[cfg(test)]
        super::faults::hit("inventory", &self.source.root)?;
        let inventory = snapshot
            .valid
            .iter()
            .map(|h| (h.region_x, h.region_z))
            .chain(snapshot.failed.iter().map(|h| (h.region_x, h.region_z)))
            .collect::<BTreeSet<_>>();
        *write_lock(&self.inventory)? = Some(inventory.clone());
        for failed in snapshot.failed {
            let coordinate = (failed.region_x, failed.region_z);
            if !Self::deferred(&maintenance, coordinate, round) {
                self.failed(
                    &mut maintenance,
                    &mut result,
                    coordinate,
                    round,
                    anyhow::anyhow!("header snapshot: {}", failed.error),
                )?;
            }
        }
        let headers = snapshot
            .valid
            .into_iter()
            .map(|h| ((h.region_x, h.region_z), h))
            .collect::<BTreeMap<_, _>>();
        let stored = read_lock(&self.regions)?
            .keys()
            .copied()
            .chain(maintenance.retry.keys().copied())
            .collect::<BTreeSet<_>>();
        for coordinate in stored.difference(&inventory).copied() {
            // Logical removal precedes fallible physical cleanup and is never rolled back.
            let removed_generation = write_lock(&self.regions)?.remove(&coordinate);
            if removed_generation.is_some() {
                result.removed.push(coordinate);
            }
            write_lock(&self.sources)?.remove(&coordinate);
            self.priority
                .lock()
                .map_err(|_| crate::UnsafeState("regional priority lock poisoned"))?
                .active
                .remove(&coordinate);
            let retry = maintenance.retry.entry(coordinate).or_insert(Retry {
                kind: RetryKind::Cleanup,
                generation: removed_generation.unwrap_or(0),
                round: None,
            });
            if retry.kind != RetryKind::Cleanup {
                retry.kind = RetryKind::Cleanup;
                retry.round = None;
            }
            if Self::deferred(&maintenance, coordinate, round) {
                continue;
            }
            if let Err(error) = self.cleanup(coordinate) {
                self.failed(
                    &mut maintenance,
                    &mut result,
                    coordinate,
                    round,
                    error.context("removal cleanup"),
                )?;
            } else {
                maintenance.retry.remove(&coordinate);
            }
        }
        let order = refresh_order(
            &headers,
            &self.priority,
            |coordinate, header| self.header_is_current(coordinate, header, &maintenance),
            |coordinate| Self::deferred(&maintenance, coordinate, round),
        )?;
        for coordinate in order {
            if Self::deferred(&maintenance, coordinate, round)
                || self.header_is_current(coordinate, &headers[&coordinate], &maintenance)?
            {
                continue;
            }
            // A reappeared source cancels cleanup. Only a complete inventory proves deletion.
            if let Some(retry) = maintenance.retry.get_mut(&coordinate)
                && retry.kind == RetryKind::Cleanup
            {
                retry.kind = RetryKind::Refresh;
            }
            let before = result.changed.len();
            if let Err(error) = self.refresh_coordinate(coordinate, &mut maintenance, &mut result) {
                self.failed(&mut maintenance, &mut result, coordinate, round, error)?;
            } else {
                maintenance.retry.remove(&coordinate);
            }
            if result.changed.len() > before {
                break;
            }
        }
        for (coordinate, header) in &headers {
            if !Self::deferred(&maintenance, *coordinate, round)
                && !self.header_is_current(*coordinate, header, &maintenance)?
            {
                result.more_pending = true;
                break;
            }
        }
        result.incomplete |= !maintenance.retry.is_empty();
        if result.incomplete && result.failure.is_none() {
            result.failure = Some(format!(
                "{}: {} unresolved regional operations",
                self.dimension,
                maintenance.retry.len()
            ));
        }
        Ok(result)
    }

    fn deferred(maintenance: &Maintenance, coordinate: (i32, i32), round: u64) -> bool {
        maintenance
            .retry
            .get(&coordinate)
            .is_some_and(|retry| retry.round == Some(round))
    }

    fn failed(
        &self,
        maintenance: &mut Maintenance,
        report: &mut RegionalRefresh,
        coordinate: (i32, i32),
        round: u64,
        error: anyhow::Error,
    ) -> Result<()> {
        if error.is::<crate::UnsafeState>() {
            return Err(error);
        }
        let generation = read_lock(&self.regions)?
            .get(&coordinate)
            .copied()
            .unwrap_or(0);
        let retry = maintenance.retry.entry(coordinate).or_insert(Retry {
            kind: RetryKind::Refresh,
            generation,
            round: None,
        });
        retry.round = Some(round);
        let summary = format!(
            "{}: region ({},{}) {:?} generation {} deferred: {error:#}",
            self.dimension, coordinate.0, coordinate.1, retry.kind, retry.generation
        );
        eprintln!("{summary}");
        report.incomplete = true;
        if report.failure.is_none() {
            report.failure = Some(summary);
        }
        Ok(())
    }

    fn cleanup(&self, coordinate: (i32, i32)) -> Result<()> {
        #[cfg(test)]
        super::faults::hit("cleanup", &self.terrain_path(coordinate))?;
        remove_if_exists(&self.terrain_path(coordinate))?;
        remove_if_exists(&self.source_path(coordinate))
    }

    fn refresh_coordinate(
        &self,
        coordinate: (i32, i32),
        maintenance: &mut Maintenance,
        result: &mut RegionalRefresh,
    ) -> Result<()> {
        use super::builder::{SourceChanged, UnusableBaseline, verify_header};
        #[cfg(test)]
        super::faults::hit("candidate", &self.terrain_path(coordinate))?;
        let header = self
            .source
            .region_header(coordinate.0, coordinate.1)
            .context("fresh candidate header")?
            .ok_or(SourceChanged)?;

        if let Some(retry) = maintenance.retry.get(&coordinate).copied()
            && retry.kind == RetryKind::Reconcile
        {
            match self.open_generation(coordinate, retry.generation) {
                Ok(region) => {
                    if let Err(error) = self.sync_replacement(&region) {
                        if error.is::<UnusableBaseline>() && !error.is::<std::io::Error>() {
                            self.quarantine_failed_replacement(coordinate, maintenance)?;
                        }
                        return Err(error.context("reconcile final terrain durability/integrity"));
                    }
                    self.install(region, result)?;
                    // No unpersisted source table survives a retry. Load a matching durable
                    // sidecar, or reconstruct safely on the next invocation.
                    self.load_source(coordinate, retry.generation)?;
                    maintenance.retry.remove(&coordinate);
                    return Ok(());
                }
                Err(error) => {
                    // A pre-rename failure may have left the known old generation untouched.
                    let old = read_lock(&self.regions)?.get(&coordinate).copied();
                    if old.is_some_and(|old| self.open_generation(coordinate, old).is_ok())
                        || !self.terrain_path(coordinate).try_exists()?
                    {
                        maintenance.retry.get_mut(&coordinate).unwrap().kind = RetryKind::Refresh;
                    } else {
                        if let Err(invalid) = RegionFile::open(self.terrain_path(coordinate))
                            && !invalid.is::<std::io::Error>()
                        {
                            self.quarantine_failed_replacement(coordinate, maintenance)?;
                        }
                        return Err(error.context("reconcile final terrain"));
                    }
                }
            }
        }
        let mut old_generation = read_lock(&self.regions)?
            .get(&coordinate)
            .copied()
            .unwrap_or(0);
        let was_authoritative = old_generation != 0;
        // A source can reappear before failed removal cleanup. The final file still owns
        // its generation even though logical authority was removed; never overwrite it
        // using generation 1 or a different world/catalog identity.
        if old_generation == 0
            && let Ok(disk) = RegionFile::open(self.terrain_path(coordinate))
        {
            self.open_generation(coordinate, disk.generation())?;
            old_generation = disk.generation();
        }
        if old_generation != 0 && maintenance.retry.contains_key(&coordinate) {
            self.load_source(coordinate, old_generation)
                .context("recover source-table durability")?;
        }
        let region = if old_generation != 0 {
            match self.open_generation(coordinate, old_generation) {
                Ok(region) => Some(region),
                Err(error) if error.is::<crate::UnsafeState>() => return Err(error),
                Err(error) => {
                    // Missing/damaged baseline permits full reconstruction. A valid but
                    // unexplained different identity/generation is never overwritten.
                    if RegionFile::open(self.terrain_path(coordinate)).is_ok() {
                        return Err(error);
                    }
                    None
                }
            }
        } else {
            None
        };
        let stored = read_lock(&self.sources)?.get(&coordinate).cloned();
        if let (Some(region), Some(source)) = (&region, &stored)
            && source.terrain_generation == region.generation()
            && source.header_matches(&header.entries, header.file_marker)
        {
            if !was_authoritative {
                self.install(region.clone(), result)?;
            }
            return Ok(());
        }
        let generation = old_generation
            .max(
                maintenance
                    .retry
                    .get(&coordinate)
                    .map_or(0, |r| r.generation),
            )
            .checked_add(1)
            .context("regional generation exhausted")?;
        let incremental = if let (Some(region), Some(previous)) = (&region, stored)
            && previous.terrain_generation == region.generation()
        {
            let probe = self
                .probe_saved_changes(&header, &previous, generation)
                .context("source probe")?;
            if probe.changed_groups.is_empty() {
                probe
                    .table
                    .write_atomic(self.source_path(coordinate))
                    .context("metadata-only sidecar")?;
                write_lock(&self.sources)?.insert(coordinate, probe.table);
                result.metadata_only += 1;
                if !was_authoritative {
                    self.install(region.clone(), result)?;
                }
                return Ok(());
            }
            Some(probe)
        } else {
            None
        };
        let full = || {
            rebuild_region(
                &self.source,
                &self.registry,
                &header,
                self.terrain_path(coordinate),
                self.world_identity,
                generation,
                self.layout,
            )
        };
        let attempted = match incremental {
            Some(probe) => rebuild_region_incremental(
                &self.source,
                &self.registry,
                &header,
                region.as_ref().unwrap(),
                &probe.table,
                &probe.changed_groups,
                self.terrain_path(coordinate),
                self.world_identity,
                generation,
                self.layout,
            ),
            None => full(),
        };
        let built = match attempted {
            Err(error) if error.is::<UnusableBaseline>() => {
                verify_header(&self.source, &header)?;
                full().context("full fallback for unusable reused payload")?
            }
            other => other.context("regional build")?,
        };
        let region = match built.terrain {
            Ok(region) => region,
            Err(error) => {
                maintenance.retry.insert(
                    coordinate,
                    Retry {
                        kind: RetryKind::Reconcile,
                        generation,
                        round: None,
                    },
                );
                let region = self
                    .open_generation(coordinate, generation)
                    .with_context(|| {
                        format!(
                            "terrain publication failed ({error:#}); final replacement unavailable"
                        )
                    })?;
                self.sync_replacement(&region)
                    .context("terrain publication durability recovery")?;
                region
            }
        };
        self.install(region, result)?;
        eprintln!(
            "{}: regional shard ({},{}) generation {} chunks={}/{} sections={:?} reused={} bytes={}",
            self.dimension,
            coordinate.0,
            coordinate.1,
            generation,
            built.stats.generated_chunks,
            built.stats.chunks_read,
            built.stats.sections_by_level,
            built.stats.reused_sections,
            built.stats.output_bytes
        );
        // Never pair old source metadata with new terrain, even if the sidecar write fails.
        write_lock(&self.sources)?.remove(&coordinate);
        maintenance.retry.insert(
            coordinate,
            Retry {
                kind: RetryKind::Refresh,
                generation,
                round: None,
            },
        );
        built
            .source
            .write_atomic(self.source_path(coordinate))
            .context("source-table publication")?;
        write_lock(&self.sources)?.insert(coordinate, built.source);
        Ok(())
    }

    fn sync_replacement(&self, region: &RegionFile) -> Result<()> {
        // Opening validates header/directory/identity. A failed writer requires payload
        // validation as well, before treating this replacement as authoritative.
        for ordinal in 0..region.layout().entry_count()? {
            region
                .read_compressed_ordinal(ordinal as u32)
                .context(super::builder::UnusableBaseline)?;
        }
        durable_file(region.path())
    }

    // Called only under the existing maintenance lock and only for an invalid file
    // from our failed writer. Valid unexplained identities/generations are not touched.
    fn quarantine_failed_replacement(
        &self,
        coordinate: (i32, i32),
        maintenance: &mut Maintenance,
    ) -> Result<()> {
        let path = self.terrain_path(coordinate);
        if !fs::symlink_metadata(&path)?.file_type().is_file() {
            bail!("failed replacement is not a regular file");
        }
        crate::quarantine(&path);
        if path.try_exists()? {
            bail!("failed replacement could not be quarantined");
        }
        crate::sync_parent(&path)?;
        // Preserve the failed generation as a high-water mark for the next build.
        maintenance.retry.get_mut(&coordinate).unwrap().kind = RetryKind::Refresh;
        Ok(())
    }

    fn install(&self, region: RegionFile, report: &mut RegionalRefresh) -> Result<()> {
        let coordinate = region.region();
        let generation = region.generation();
        let previous = write_lock(&self.regions)?.insert(coordinate, generation);
        let mut priority = self
            .priority
            .lock()
            .map_err(|_| crate::UnsafeState("regional priority lock poisoned"))?;
        if priority.subscriptions.contains_key(&coordinate) {
            priority.active.insert(coordinate, Arc::new(region));
        }
        if previous != Some(generation) {
            let mut sources = write_lock(&self.sources)?;
            if sources
                .get(&coordinate)
                .is_some_and(|table| table.terrain_generation != generation)
            {
                sources.remove(&coordinate);
            }
            report
                .changed
                .push((coordinate.0, coordinate.1, generation));
        }
        Ok(())
    }

    fn load_source(&self, coordinate: (i32, i32), generation: u64) -> Result<()> {
        write_lock(&self.sources)?.remove(&coordinate);
        if let Ok(table) = RegionSourceTable::open(self.source_path(coordinate))
            && (table.region_x, table.region_z) == coordinate
            && table.terrain_generation == generation
        {
            // A sidecar found after a failed sync must cross its durability barriers again.
            durable_file(&self.source_path(coordinate))?;
            write_lock(&self.sources)?.insert(coordinate, table);
        }
        Ok(())
    }

    fn header_is_current(
        &self,
        coordinate: (i32, i32),
        header: &RegionHeader,
        maintenance: &Maintenance,
    ) -> Result<bool> {
        if maintenance.retry.contains_key(&coordinate) {
            return Ok(false);
        }
        let regions = read_lock(&self.regions)?;
        let sources = read_lock(&self.sources)?;
        Ok(regions.get(&coordinate).is_some_and(|generation| {
            sources.get(&coordinate).is_some_and(|source| {
                source.terrain_generation == *generation
                    && source.header_matches(&header.entries, header.file_marker)
            })
        }))
    }

    fn open_generation(&self, coordinate: (i32, i32), generation: u64) -> Result<RegionFile> {
        let region = RegionFile::open(self.terrain_path(coordinate))?;
        if region.region() != coordinate
            || region.generation() != generation
            || region.world_identity() != self.world_identity
            || region.catalog_id() != read_lock(&self.registry)?.catalog_id()
            || region.layout() != self.layout
        {
            bail!("regional generation metadata disagrees with its file");
        }
        Ok(region)
    }

    /// Reads only Anvil records whose header changed. Semantic changes identify the exact 2x2
    /// chunk groups whose LOD columns and ancestors need replacement.
    fn probe_saved_changes(
        &self,
        header: &RegionHeader,
        previous: &RegionSourceTable,
        generation: u64,
    ) -> Result<SavedChangeProbe> {
        let mut updated = RegionSourceTable::new(
            header.region_x,
            header.region_z,
            generation,
            header.file_marker,
        )?;
        let mut changed_groups = BTreeSet::new();
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
                    changed_groups.insert((
                        (base_x + i32::from(local_x)).div_euclid(2),
                        (base_z + i32::from(local_z)).div_euclid(2),
                    ));
                }
                current
            };
            updated.set_record(local_x, local_z, record)?;
        }
        super::builder::verify_header(&self.source, header)?;
        if changed_groups.is_empty() {
            updated.terrain_generation = previous.terrain_generation;
        }
        Ok(SavedChangeProbe {
            table: updated,
            changed_groups,
        })
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

struct SavedChangeProbe {
    table: RegionSourceTable,
    changed_groups: BTreeSet<(i32, i32)>,
}

fn remove_if_exists(path: &Path) -> Result<()> {
    #[cfg(test)]
    super::faults::hit("remove_file", path)?;
    match fs::remove_file(path) {
        Ok(()) => crate::sync_parent(path),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => crate::sync_parent(path),
        Err(error) => Err(error.into()),
    }
}

fn durable_file(path: &Path) -> Result<()> {
    #[cfg(test)]
    super::faults::hit("reconcile_sync", path)?;
    fs::File::open(path)?.sync_all()?;
    crate::sync_parent(path)
}

#[cfg(test)]
#[path = "refresh_tests.rs"]
mod refresh_tests;

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
