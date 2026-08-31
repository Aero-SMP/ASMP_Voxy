use crate::{
    anvil::{AnvilWorld, TerrainFingerprint},
    crc::crc32c,
    key::SectionKey,
    lod::{Section, build_parent},
    read_file_bounded,
    registry::Registry,
    replace_synced,
    store::{EntryMeta, Invalidation, Store},
    write_synced,
};
use anyhow::{Context, Result};
use rayon::prelude::*;
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet, HashMap, HashSet},
    fs::{self, File},
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex, RwLock,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};
use tokio::sync::broadcast;

const STATE_VERSION: u32 = 9;
const LEGACY_STATE_VERSION: u32 = 8;
const STATE_MAGIC: &[u8; 8] = b"VXYREGS1";
const META_MAGIC: &[u8; 8] = b"VXYMETA1";
const DIRTY_MAGIC: &[u8; 8] = b"VXYDIRR1";
const GROUP_BATCH: usize = 8;
const LOD_BATCH: usize = 32;

#[derive(Clone, Debug)]
pub struct UpdateEvent {
    pub dimension: String,
    pub change: Update,
}

#[derive(Clone, Copy, Debug)]
pub enum Update {
    Invalidate(Invalidation),
    Section(EntryMeta),
}

#[derive(Debug)]
pub struct DimensionRuntime {
    pub anvil: AnvilWorld,
    pub store: Arc<Store>,
    state_dir: PathBuf,
    dirty_dir: PathBuf,
    state: Mutex<ScanState>,
    first_scan: AtomicBool,
    reconcile_required: AtomicBool,
}

#[derive(Clone, Debug, Default)]
struct ScanState {
    version: u32,
    catalog_id: u64,
    store_epoch: u64,
    mip_generation: u64,
    chunks: HashMap<String, ChunkState>,
    regions: HashMap<String, u64>,
    known_regions: HashSet<String>,
    meta_trusted: bool,
}

#[derive(Debug, Deserialize, Serialize)]
struct RegionState {
    version: u32,
    catalog_id: u64,
    region_x: i32,
    region_z: i32,
    file_marker: Option<u64>,
    chunks: HashMap<String, ChunkState>,
}

#[derive(Debug, Default, Deserialize, Serialize)]
struct ScanMeta {
    version: u32,
    catalog_id: u64,
    store_epoch: u64,
    mip_generation: u64,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct ChunkState {
    header_marker: u64,
    fingerprint: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    terrain_fingerprint: Option<TerrainFingerprint>,
    mip_generation: u64,
    level_zero_keys: Vec<u64>,
}

#[derive(Clone, Debug)]
struct PendingChunk {
    id: String,
    x: i32,
    z: i32,
    marker: Option<(u64, u64)>,
    old_keys: Vec<SectionKey>,
}

#[derive(Default)]
struct GroupWork {
    pending: Vec<PendingChunk>,
    repair_keys: BTreeSet<SectionKey>,
}

struct ChunkAdvance {
    id: String,
    marker: Option<(u64, u64)>,
    terrain_fingerprint: Option<TerrainFingerprint>,
    level_zero_keys: Vec<u64>,
}

impl DimensionRuntime {
    pub fn new(anvil: AnvilWorld, store: Arc<Store>, data_root: &Path) -> Self {
        let state_dir = data_root
            .join("scan-state")
            .join(safe_dimension_name(&anvil.dimension));
        let dirty_dir = state_dir.join("dirty");
        let state = load_state(&state_dir, store.catalog_id(), store.epoch());
        let reconcile = !state.meta_trusted || state.store_epoch != store.epoch();
        Self {
            anvil,
            store,
            state_dir,
            dirty_dir,
            state: Mutex::new(state),
            first_scan: AtomicBool::new(true),
            reconcile_required: AtomicBool::new(reconcile),
        }
    }

    /// Performs only the bounded metadata recovery required before accepting clients. Dirty or
    /// unowned regions are scheduled for reconciliation, but their last-known-good sections
    /// remain visible until regenerated replacements are ready.
    pub fn recover_before_serve(&self) -> Result<()> {
        let state = self
            .state
            .lock()
            .map_err(|_| anyhow::anyhow!("scan-state lock poisoned"))?;
        let mut regions = load_dirty_regions(&self.dirty_dir)?;
        regions.extend(
            self.store
                .live_horizontal_columns()?
                .into_iter()
                .filter(|region| !state.known_regions.contains(&region_id(*region))),
        );
        if !regions.is_empty() {
            save_dirty_regions(&self.dirty_dir, &regions)?;
            self.reconcile_required.store(true, Ordering::Release);
        }
        Ok(())
    }

    /// Once clients may observe the store, every published transaction must be durable. The
    /// deferred first-scan path is safe only for the finite `--once` importer because a crash
    /// must never leave a client holding a key that vanished before reaching disk.
    pub fn begin_serving(&self) {
        self.first_scan.store(false, Ordering::Release);
    }

    /// True while the durable store is known to be incomplete or inconsistent with its Anvil
    /// source. Missing records are not authoritative during this interval: clients must keep
    /// their subscriptions open until a replacement is published or reconciliation completes.
    pub fn is_reconciling(&self) -> bool {
        self.reconcile_required.load(Ordering::Acquire)
    }

    pub fn scan_once(
        &self,
        registry: &Arc<RwLock<Registry>>,
        updates: &broadcast::Sender<UpdateEvent>,
    ) -> Result<ScanReport> {
        let recovered = self.store.take_recovery_invalidations()?;
        send_invalidations(updates, &self.anvil.dimension, &recovered);
        let initial_scan = self.first_scan.swap(false, Ordering::AcqRel);
        let mut state = self
            .state
            .lock()
            .map_err(|_| anyhow::anyhow!("scan-state lock poisoned"))?;
        let mut state_regions_dirty = BTreeSet::new();
        let recovered_dirty = load_dirty_regions(&self.dirty_dir)?;
        if !recovered_dirty.is_empty() {
            self.reconcile_required.store(true, Ordering::Release);
            state_regions_dirty.extend(recovered_dirty);
        }
        let mip_generation = registry
            .read()
            .map_err(|_| anyhow::anyhow!("registry lock poisoned"))?
            .mip_generation();
        let catalog_id = self.store.catalog_id();
        let store_epoch = self.store.epoch();
        state.version = STATE_VERSION;
        state.catalog_id = catalog_id;
        let region_headers = match self.anvil.region_headers() {
            Ok(headers) => headers,
            Err(error) => {
                self.reconcile_required.store(true, Ordering::Release);
                eprintln!(
                    "{} source is unavailable; retaining last-known-good cache and retrying: {error:#}",
                    self.anvil.dimension
                );
                return Ok(ScanReport {
                    changed_chunks: 0,
                    generated_sections: 0,
                    failures: 1,
                });
            }
        };
        if state.store_epoch != store_epoch {
            self.reconcile_required.store(true, Ordering::Release);
        }
        let durable_each_transaction = !initial_scan;
        let reconcile = self.reconcile_required.load(Ordering::Acquire)
            || state.store_epoch != store_epoch
            || state.catalog_id != catalog_id;
        let degraded = self.store.degraded_shards()?;
        let degraded_regions = degraded
            .iter()
            .map(|id| (id.x, id.z))
            .collect::<BTreeSet<_>>();
        let mut reconcile_regions = degraded_regions.clone();
        reconcile_regions.extend(
            self.store
                .live_horizontal_columns()?
                .into_iter()
                .filter(|region| !state.known_regions.contains(&region_id(*region))),
        );
        if reconcile || !state.meta_trusted {
            reconcile_regions.extend(
                state
                    .known_regions
                    .iter()
                    .filter_map(|id| parse_region_id(id)),
            );
        }
        let mut repair_level_zero = BTreeSet::new();
        let mut reset_regions = BTreeSet::new();
        for region in reconcile_regions {
            let live = self
                .store
                .live_keys_in_column(region.0, region.1)?
                .into_iter()
                .map(SectionKey::packed)
                .collect::<HashSet<_>>();
            let level_zero = expected_region_level_zero(&state, region)?;
            let expected = level_zero
                .iter()
                .copied()
                .flat_map(ancestors)
                .map(SectionKey::packed)
                .collect::<HashSet<_>>();
            if live.iter().any(|key| !expected.contains(key)) {
                reset_regions.insert(region);
                continue;
            }
            repair_level_zero.extend(level_zero.into_iter().filter(|&key| {
                ancestors(key)
                    .into_iter()
                    .any(|ancestor| !live.contains(&ancestor.packed()))
            }));
        }
        if !reset_regions.is_empty() {
            save_dirty_regions(&self.dirty_dir, &reset_regions)?;
            for &region in &reset_regions {
                let invalidations = self.store.invalidate_column(region.0, region.1, 3)?;
                send_invalidations(updates, &self.anvil.dimension, &invalidations);
                remove_state_region(&mut state, region);
                state_regions_dirty.insert(region);
            }
        }

        let mut pending = Vec::new();
        let mut mip_advances = Vec::<ChunkAdvance>::new();
        let mut seen = HashSet::new();
        let mut current_regions = HashMap::new();
        let mut failures = 0usize;
        let mut failed_regions = BTreeSet::new();
        let mut failed_keys = BTreeSet::new();
        let mut state_changed = !state_regions_dirty.is_empty();
        for failed in region_headers.failed {
            let region = (failed.region_x, failed.region_z);
            let region_id = region_id(region);
            let unchanged = state.regions.get(&region_id).copied() == Some(failed.file_marker);
            current_regions.insert(region_id, failed.file_marker);
            if unchanged {
                continue;
            }
            state_regions_dirty.insert(region);
            record_failure(&self.reconcile_required, &mut failures);
            failed_regions.insert(region);
            let affected = state
                .chunks
                .iter()
                .filter_map(|(id, chunk)| {
                    let (x, z) = parse_chunk_id(id)?;
                    (x.div_euclid(32) == failed.region_x && z.div_euclid(32) == failed.region_z)
                        .then_some((id, chunk))
                })
                .collect::<Vec<_>>();
            if !affected.is_empty() {
                eprintln!(
                    "cannot read prior {} region {}: {}; retaining cached LODs and retrying",
                    self.anvil.dimension,
                    failed.path.display(),
                    failed.error
                );
                for (_, chunk) in affected {
                    failed_keys.extend(decode_keys(&chunk.level_zero_keys));
                }
            }
        }
        for header in region_headers.valid {
            let region = (header.region_x, header.region_z);
            let region_id = region_id(region);
            let region_changed = state.regions.get(&region_id).copied() != Some(header.file_marker);
            current_regions.insert(region_id, header.file_marker);
            if region_changed {
                state_regions_dirty.insert(region);
            }
            let Some(base_x) = header.region_x.checked_mul(32) else {
                record_failure(&self.reconcile_required, &mut failures);
                failed_regions.insert(region);
                continue;
            };
            let Some(base_z) = header.region_z.checked_mul(32) else {
                record_failure(&self.reconcile_required, &mut failures);
                failed_regions.insert(region);
                continue;
            };
            for (slot, entry) in header.entries.iter().enumerate() {
                let Some(x) = base_x.checked_add(slot as i32 & 31) else {
                    continue;
                };
                let Some(z) = base_z.checked_add(slot as i32 >> 5) else {
                    continue;
                };
                if SectionKey::new(0, x.div_euclid(2), 0, z.div_euclid(2)).is_err() {
                    record_failure(&self.reconcile_required, &mut failures);
                    failed_regions.insert(region);
                    continue;
                }
                let id = chunk_id(x, z);
                let old = state.chunks.get(&id);
                let needs_mip = old.is_none_or(|old| old.mip_generation != mip_generation);
                let needs_terrain_fingerprint =
                    old.is_none_or(|old| old.terrain_fingerprint.is_none());
                if entry.location == 0 {
                    if let Some(old) = old {
                        pending.push(PendingChunk {
                            id: id.clone(),
                            x,
                            z,
                            marker: None,
                            old_keys: decode_keys(&old.level_zero_keys),
                        });
                    }
                    continue;
                }
                seen.insert(id.clone());
                let header_marker = (u64::from(entry.timestamp) << 32) | u64::from(entry.location);
                let fingerprint = if old.is_some_and(|old| old.header_marker == header_marker)
                    && !region_changed
                    && !needs_mip
                    && !needs_terrain_fingerprint
                {
                    continue;
                } else if needs_mip
                    && old.is_some_and(|old| old.header_marker == header_marker)
                    && !region_changed
                    && !needs_terrain_fingerprint
                {
                    let old = old.expect("checked");
                    mip_advances.push(ChunkAdvance {
                        id,
                        marker: Some((header_marker, old.fingerprint)),
                        terrain_fingerprint: old.terrain_fingerprint,
                        level_zero_keys: old.level_zero_keys.clone(),
                    });
                    continue;
                } else {
                    match self.anvil.chunk_fingerprint(x, z) {
                        Ok(Some(value)) => value,
                        Ok(None) => {
                            record_failure(&self.reconcile_required, &mut failures);
                            failed_regions.insert(region);
                            eprintln!(
                                "occupied {} chunk header ({x},{z}) has no readable payload; retrying",
                                self.anvil.dimension
                            );
                            if let Some(old) = old {
                                failed_keys.extend(decode_keys(&old.level_zero_keys));
                            }
                            continue;
                        }
                        Err(error) => {
                            record_failure(&self.reconcile_required, &mut failures);
                            failed_regions.insert(region);
                            eprintln!(
                                "cannot fingerprint {} chunk ({x},{z}): {error:#}",
                                self.anvil.dimension
                            );
                            if let Some(old) = old {
                                failed_keys.extend(decode_keys(&old.level_zero_keys));
                            }
                            continue;
                        }
                    }
                };
                if !needs_mip
                    && !needs_terrain_fingerprint
                    && old.is_some_and(|old| {
                        old.header_marker == header_marker && old.fingerprint == fingerprint
                    })
                {
                    continue;
                }
                if needs_mip
                    && !needs_terrain_fingerprint
                    && old.is_some_and(|old| old.fingerprint == fingerprint)
                {
                    let old = old.expect("checked");
                    mip_advances.push(ChunkAdvance {
                        id,
                        marker: Some((header_marker, fingerprint)),
                        terrain_fingerprint: old.terrain_fingerprint,
                        level_zero_keys: old.level_zero_keys.clone(),
                    });
                    continue;
                }
                pending.push(PendingChunk {
                    id,
                    x,
                    z,
                    marker: Some((header_marker, fingerprint)),
                    old_keys: old.map_or_else(Vec::new, |old| decode_keys(&old.level_zero_keys)),
                });
            }
        }
        let pending_ids = pending
            .iter()
            .map(|item| item.id.clone())
            .collect::<HashSet<_>>();
        for (id, old) in &state.chunks {
            if seen.contains(id) || pending_ids.contains(id) {
                continue;
            }
            let Some((x, z)) = parse_chunk_id(id) else {
                continue;
            };
            if !self.anvil.region_path(x, z).exists() {
                pending.push(PendingChunk {
                    id: id.clone(),
                    x,
                    z,
                    marker: None,
                    old_keys: decode_keys(&old.level_zero_keys),
                });
            }
        }

        let mut groups = BTreeMap::<(i32, i32), GroupWork>::new();
        for item in pending {
            groups
                .entry((item.x.div_euclid(2), item.z.div_euclid(2)))
                .or_default()
                .pending
                .push(item);
        }
        for level_zero in repair_level_zero {
            groups
                .entry((level_zero.x, level_zero.z))
                .or_default()
                .repair_keys
                .insert(level_zero);
        }
        let mut levels = [(); 5].map(|_| BTreeSet::<SectionKey>::new());
        for advance in &mip_advances {
            let leaves = decode_keys(&advance.level_zero_keys);
            add_parents(&leaves, &mut levels);
        }
        let mut dirty_groups = groups.keys().copied().collect::<BTreeSet<_>>();
        dirty_groups.extend(failed_keys.iter().map(|key| (key.x, key.z)));
        dirty_groups.extend(mip_advances.iter().flat_map(|advance| {
            decode_keys(&advance.level_zero_keys)
                .into_iter()
                .map(|key| (key.x, key.z))
        }));
        let dirty_regions = dirty_groups
            .iter()
            .copied()
            .map(group_region)
            .chain(failed_regions.iter().copied())
            .collect::<BTreeSet<_>>();
        if !dirty_regions.is_empty() {
            save_dirty_regions(&self.dirty_dir, &dirty_regions)?;
        }
        if groups.is_empty() && mip_advances.is_empty() {
            self.store.sync_pending()?;
            if failures != 0 {
                self.reconcile_required.store(true, Ordering::Release);
            }
            mark_changed_regions(&state.regions, &current_regions, &mut state_regions_dirty);
            state.regions = current_regions.clone();
            state_changed |= !state_regions_dirty.is_empty();
            if failures == 0 {
                state.version = STATE_VERSION;
                state.catalog_id = catalog_id;
                state.store_epoch = store_epoch;
                state.mip_generation = mip_generation;
                state.regions = current_regions;
                state.meta_trusted = true;
            }
            refresh_known_regions(&mut state, &state_regions_dirty)?;
            if state_changed
                || state.store_epoch != store_epoch
                || state.mip_generation != mip_generation
            {
                save_state_regions(&self.state_dir, &state, &state_regions_dirty)?;
            }
            save_meta(&self.state_dir, &state)?;
            clear_dirty_regions_except(&self.dirty_dir, &failed_regions)?;
            let ending_mip_generation = registry
                .read()
                .map_err(|_| anyhow::anyhow!("registry lock poisoned"))?
                .mip_generation();
            if failures == 0
                && ending_mip_generation == mip_generation
                && self.store.epoch() == store_epoch
            {
                self.store.clear_degraded(&degraded)?;
                self.reconcile_required.store(false, Ordering::Release);
            }
            return Ok(ScanReport {
                changed_chunks: 0,
                generated_sections: 0,
                failures,
            });
        }

        let changed_chunks = groups
            .values()
            .map(|group| group.pending.len())
            .sum::<usize>()
            + mip_advances.len();
        let mut generated = 0usize;
        let mut advances = mip_advances;
        let group_ids = groups.keys().copied().collect::<Vec<_>>();
        for group_batch in group_ids.chunks(GROUP_BATCH) {
            let loaded = group_batch
                .par_iter()
                .map(|&(x, z)| ((x, z), self.anvil.load_level_zero_group(x, z, registry)))
                .collect::<Vec<_>>();
            let mut prepared = Vec::new();
            for (group_id, loaded) in loaded {
                let work = groups.remove(&group_id).expect("group work exists");
                let old_keys = work
                    .pending
                    .iter()
                    .flat_map(|chunk| chunk.old_keys.iter().copied())
                    .chain(work.repair_keys.iter().copied())
                    .collect::<BTreeSet<_>>();
                let mut old_keys = old_keys;
                if let (Some(base_x), Some(base_z)) =
                    (group_id.0.checked_mul(2), group_id.1.checked_mul(2))
                {
                    for dz in 0..2 {
                        for dx in 0..2 {
                            if let Some(chunk) =
                                state.chunks.get(&chunk_id(base_x + dx, base_z + dz))
                            {
                                old_keys.extend(decode_keys(&chunk.level_zero_keys));
                            }
                        }
                    }
                }
                let group = match loaded {
                    Ok(group) => group,
                    Err(error) => {
                        record_failure(&self.reconcile_required, &mut failures);
                        failed_regions.insert(group_region(group_id));
                        eprintln!(
                            "cannot load {} chunk group {group_id:?}; retrying: {error:#}",
                            self.anvil.dimension
                        );
                        continue;
                    }
                };
                if !pending_sources_match(&group, &work) {
                    record_failure(&self.reconcile_required, &mut failures);
                    failed_regions.insert(group_region(group_id));
                    eprintln!(
                        "{} chunk group {group_id:?} changed while it was loading; retrying",
                        self.anvil.dimension
                    );
                    continue;
                }
                if group_semantically_unchanged(&group, &work, &state, mip_generation) {
                    if let Err(error) = self.anvil.verify_sources(&group.sources()) {
                        record_failure(&self.reconcile_required, &mut failures);
                        failed_regions.insert(group_region(group_id));
                        eprintln!(
                            "{} chunk group {group_id:?} changed during semantic verification; retrying: {error:#}",
                            self.anvil.dimension
                        );
                        continue;
                    }
                    advances.extend(work.pending.into_iter().map(|pending| {
                        let old = state
                            .chunks
                            .get(&pending.id)
                            .expect("semantic match requires prior chunk state");
                        let terrain_fingerprint = loaded_chunk(&group, pending.x, pending.z)
                            .map(|chunk| chunk.terrain_fingerprint);
                        ChunkAdvance {
                            id: pending.id,
                            marker: pending.marker,
                            terrain_fingerprint,
                            level_zero_keys: old.level_zero_keys.clone(),
                        }
                    }));
                    continue;
                }
                let new_keys = group.keys().into_iter().collect::<BTreeSet<_>>();
                let affected = old_keys.union(&new_keys).copied().collect::<BTreeSet<_>>();
                prepared.push((group_id, group, work, new_keys, affected));
            }
            {
                let mut registry = registry
                    .write()
                    .map_err(|_| anyhow::anyhow!("registry lock poisoned"))?;
                registry.save()?;
            }

            let mut fine_sections = Vec::new();
            let mut fine_candidates = BTreeSet::new();
            let mut batch_advances = Vec::new();
            for (group_id, group, work, new_keys, affected) in prepared {
                let result = new_keys
                    .par_iter()
                    .map(|&key| group.build(key, &self.anvil).map(|built| built.section))
                    .collect::<Result<Vec<_>>>()
                    .and_then(|sections| {
                        self.anvil.verify_sources(&group.sources())?;
                        Ok(sections)
                    });
                let sections = match result {
                    Ok(sections) => sections,
                    Err(error) => {
                        record_failure(&self.reconcile_required, &mut failures);
                        failed_regions.insert(group_region(group_id));
                        eprintln!(
                            "cannot build {} chunk group {group_id:?}; retaining cached LODs and retrying: {error:#}",
                            self.anvil.dimension
                        );
                        continue;
                    }
                };
                add_affected(&affected, &mut levels);
                fine_candidates.extend(affected.iter().copied());
                let live_keys = sections
                    .iter()
                    .filter(|section| !section.is_empty())
                    .map(|section| section.key.packed())
                    .collect::<Vec<_>>();
                fine_sections.extend(sections.into_iter().filter(|section| !section.is_empty()));
                let pending_ids = work
                    .pending
                    .iter()
                    .map(|chunk| chunk.id.as_str())
                    .collect::<HashSet<_>>();
                for chunk in group.chunks.iter().filter_map(Option::as_ref) {
                    let id = chunk_id(chunk.x, chunk.z);
                    if pending_ids.contains(id.as_str()) {
                        continue;
                    }
                    // Give every loaded sibling ownership of the shared result. A new sibling,
                    // or one whose loaded fingerprint differs from ScanState, gets marker zero
                    // to force an exact check next poll. Unchanged siblings preserve their
                    // marker, avoiding an endless four-chunk rescan ping-pong.
                    batch_advances.push(loaded_sibling_advance(
                        chunk,
                        state.chunks.get(&id),
                        &live_keys,
                    ));
                }
                for pending in work.pending {
                    batch_advances.push(ChunkAdvance {
                        terrain_fingerprint: loaded_chunk(&group, pending.x, pending.z)
                            .map(|chunk| chunk.terrain_fingerprint),
                        level_zero_keys: live_keys.clone(),
                        id: pending.id,
                        marker: pending.marker,
                    });
                }
            }
            let fine_candidates = fine_candidates.into_iter().collect::<Vec<_>>();
            generated += publish_replacements(
                &self.store,
                updates,
                &self.anvil.dimension,
                &fine_sections,
                &fine_candidates,
                durable_each_transaction,
            )?;
            advances.extend(batch_advances);
        }

        let opacity = {
            let mut registry = registry
                .write()
                .map_err(|_| anyhow::anyhow!("registry lock poisoned"))?;
            registry.save()?;
            registry.opacity_table()
        };
        // Global level passes build every affected parent exactly once. The previous per-group
        // recursion rebuilt the same level-4 tile hundreds of times during a full world scan.
        for level in levels.iter().skip(1) {
            let keys = level.iter().copied().collect::<Vec<_>>();
            for batch in keys.chunks(LOD_BATCH) {
                let sections = batch
                    .par_iter()
                    .map(|&key| {
                        let children = load_children(&self.store, key)?;
                        build_parent(key, &children, &opacity)
                    })
                    .collect::<Result<Vec<_>>>()?
                    .into_iter()
                    .filter(|section| !section.is_empty())
                    .collect::<Vec<_>>();
                generated += publish_replacements(
                    &self.store,
                    updates,
                    &self.anvil.dimension,
                    &sections,
                    batch,
                    durable_each_transaction,
                )?;
            }
        }

        self.store.sync_pending()?;
        for advance in advances {
            if let Some(region) = chunk_region_from_id(&advance.id) {
                state_regions_dirty.insert(region);
            }
            if let Some((header_marker, fingerprint)) = advance.marker {
                state.chunks.insert(
                    advance.id,
                    ChunkState {
                        header_marker,
                        fingerprint,
                        terrain_fingerprint: advance.terrain_fingerprint,
                        mip_generation,
                        level_zero_keys: advance.level_zero_keys,
                    },
                );
            } else {
                state.chunks.remove(&advance.id);
            }
            state_changed = true;
        }

        let ending_mip_generation = registry
            .read()
            .map_err(|_| anyhow::anyhow!("registry lock poisoned"))?
            .mip_generation();
        mark_changed_regions(&state.regions, &current_regions, &mut state_regions_dirty);
        state.regions = current_regions.clone();
        state_changed |= !state_regions_dirty.is_empty();
        if failures == 0
            && ending_mip_generation == mip_generation
            && self.store.epoch() == store_epoch
        {
            state.version = STATE_VERSION;
            state.catalog_id = catalog_id;
            state.store_epoch = store_epoch;
            state.mip_generation = ending_mip_generation;
            state.regions = current_regions;
            state.meta_trusted = true;
            state_changed = true;
            self.store.clear_degraded(&degraded)?;
        }
        refresh_known_regions(&mut state, &state_regions_dirty)?;
        if state_changed {
            save_state_regions(&self.state_dir, &state, &state_regions_dirty)?;
        }
        save_meta(&self.state_dir, &state)?;
        if failures != 0 {
            self.reconcile_required.store(true, Ordering::Release);
        }
        clear_dirty_regions_except(&self.dirty_dir, &failed_regions)?;
        if failures == 0
            && ending_mip_generation == mip_generation
            && self.store.epoch() == store_epoch
        {
            self.reconcile_required.store(false, Ordering::Release);
        }
        Ok(ScanReport {
            changed_chunks,
            generated_sections: generated,
            failures,
        })
    }
}

fn record_failure(reconcile_required: &AtomicBool, failures: &mut usize) {
    *failures += 1;
    reconcile_required.store(true, Ordering::Release);
}

fn loaded_sibling_advance(
    chunk: &crate::anvil::ParsedChunk,
    old: Option<&ChunkState>,
    live_keys: &[u64],
) -> ChunkAdvance {
    let marker = old
        .filter(|old| old.fingerprint == chunk.source_fingerprint)
        .map_or((0, chunk.source_fingerprint), |old| {
            (old.header_marker, old.fingerprint)
        });
    ChunkAdvance {
        id: chunk_id(chunk.x, chunk.z),
        marker: Some(marker),
        terrain_fingerprint: Some(chunk.terrain_fingerprint),
        level_zero_keys: live_keys.to_vec(),
    }
}

fn loaded_chunk(
    group: &crate::anvil::LevelZeroGroup,
    x: i32,
    z: i32,
) -> Option<&crate::anvil::ParsedChunk> {
    group
        .chunks
        .iter()
        .filter_map(Option::as_ref)
        .find(|chunk| chunk.x == x && chunk.z == z)
}

/// A raw Anvil rewrite can be ignored only when every changed member of the shared 2x2 group
/// has known, identical normalized terrain and no mip or repair work is pending.
fn group_semantically_unchanged(
    group: &crate::anvil::LevelZeroGroup,
    work: &GroupWork,
    state: &ScanState,
    mip_generation: u64,
) -> bool {
    work.repair_keys.is_empty()
        && !work.pending.is_empty()
        && work.pending.iter().all(|pending| {
            pending.marker.is_some()
                && state.chunks.get(&pending.id).is_some_and(|old| {
                    old.mip_generation == mip_generation
                        && old.terrain_fingerprint.is_some_and(|expected| {
                            loaded_chunk(group, pending.x, pending.z)
                                .is_some_and(|chunk| chunk.terrain_fingerprint == expected)
                        })
                })
        })
}

#[derive(Clone, Copy, Debug, Default)]
pub struct ScanReport {
    pub changed_chunks: usize,
    pub generated_sections: usize,
    pub failures: usize,
}

pub async fn poll_dimension(
    runtime: Arc<DimensionRuntime>,
    registry: Arc<RwLock<Registry>>,
    updates: broadcast::Sender<UpdateEvent>,
    interval: Duration,
) {
    loop {
        let scan_runtime = runtime.clone();
        let scan_registry = registry.clone();
        let scan_updates = updates.clone();
        match tokio::task::spawn_blocking(move || {
            scan_runtime.scan_once(&scan_registry, &scan_updates)
        })
        .await
        {
            Ok(Ok(report)) if report.generated_sections != 0 || report.failures != 0 => eprintln!(
                "{}: generated {} sections ({} failures)",
                runtime.anvil.dimension, report.generated_sections, report.failures
            ),
            Ok(Ok(_)) => {}
            Ok(Err(error)) => eprintln!(
                "{} scan failed; retrying: {error:#}",
                runtime.anvil.dimension
            ),
            Err(error) => eprintln!("{} scan worker failed: {error}", runtime.anvil.dimension),
        }
        tokio::time::sleep(interval).await;
    }
}

/// Publishes a completed batch without making its previous generation temporarily absent.
/// New data becomes visible first; only completed candidates that produced no section are
/// tombstoned afterward. A failed build never calls this function, so last-good data remains.
fn publish_replacements(
    store: &Store,
    updates: &broadcast::Sender<UpdateEvent>,
    dimension: &str,
    sections: &[Section],
    candidates: &[SectionKey],
    durable: bool,
) -> Result<usize> {
    let expected = sections
        .iter()
        .map(|section| {
            let key = section.key.packed();
            Ok((key, store.revision(section.key)?.unwrap_or(0)))
        })
        .collect::<Result<HashMap<_, _>>>()?;
    let result = if durable {
        store.put_many_checked(sections, &expected)?
    } else {
        store.put_many_checked_deferred(sections, &expected)?
    };
    if result.accepted() + result.rejected != sections.len() {
        anyhow::bail!(
            "store accounted for {} of {} completed sections",
            result.accepted() + result.rejected,
            sections.len()
        );
    }
    // Publish every committed replacement even if another shard rejected stale work. On retry,
    // these entries compare unchanged and will not be returned again, so delaying their events
    // could otherwise leave an already-subscribed client on the older revision indefinitely.
    for &entry in &result.written {
        let _ = updates.send(UpdateEvent {
            dimension: dimension.to_owned(),
            change: Update::Section(entry),
        });
    }
    if result.rejected != 0 {
        anyhow::bail!("{} stale worker results were rejected", result.rejected);
    }

    let live = sections
        .iter()
        .map(|section| section.key.packed())
        .collect::<HashSet<_>>();
    let removed = candidates
        .iter()
        .copied()
        .filter(|key| !live.contains(&key.packed()))
        .collect::<Vec<_>>();
    let invalidations = if durable {
        store.remove_many(&removed, 1)?
    } else {
        store.remove_many_deferred(&removed, 1)?
    };
    send_invalidations(updates, dimension, &invalidations);
    Ok(result.written.len())
}

fn add_affected(level_zero: &BTreeSet<SectionKey>, levels: &mut [BTreeSet<SectionKey>; 5]) {
    for &leaf in level_zero {
        for key in ancestors(leaf) {
            levels[key.level as usize].insert(key);
        }
    }
}

fn add_parents(level_zero: &[SectionKey], levels: &mut [BTreeSet<SectionKey>; 5]) {
    for &leaf in level_zero {
        let mut key = leaf;
        while let Some(parent) = key.parent() {
            levels[parent.level as usize].insert(parent);
            key = parent;
        }
    }
}

fn pending_sources_match(group: &crate::anvil::LevelZeroGroup, work: &GroupWork) -> bool {
    work.pending.iter().all(|pending| {
        let actual = group
            .chunks
            .iter()
            .filter_map(Option::as_ref)
            .find(|chunk| chunk.x == pending.x && chunk.z == pending.z)
            .map(|chunk| chunk.source_fingerprint);
        actual == pending.marker.map(|(_, fingerprint)| fingerprint)
    })
}

fn send_invalidations(
    updates: &broadcast::Sender<UpdateEvent>,
    dimension: &str,
    invalidations: &[Invalidation],
) {
    for &entry in invalidations {
        let _ = updates.send(UpdateEvent {
            dimension: dimension.to_owned(),
            change: Update::Invalidate(entry),
        });
    }
}

fn load_children(store: &Store, parent: SectionKey) -> Result<[Option<Section>; 8]> {
    let mut children: [Option<Section>; 8] = [(); 8].map(|_| None);
    for dy in 0..2 {
        for dz in 0..2 {
            for dx in 0..2 {
                let key = SectionKey::new(
                    parent.level - 1,
                    parent.x * 2 + dx,
                    parent.y * 2 + dy,
                    parent.z * 2 + dz,
                )?;
                children[(dx | (dz << 1) | (dy << 2)) as usize] =
                    store.get(key)?.map(|stored| stored.section);
            }
        }
    }
    Ok(children)
}

fn ancestors(mut key: SectionKey) -> Vec<SectionKey> {
    let mut out = vec![key];
    while let Some(parent) = key.parent() {
        out.push(parent);
        key = parent;
    }
    out
}

fn decode_keys(keys: &[u64]) -> Vec<SectionKey> {
    keys.iter()
        .map(|&key| SectionKey::unpack(key).expect("validated scan-state section key"))
        .collect()
}

fn chunk_id(x: i32, z: i32) -> String {
    format!("{x},{z}")
}

fn parse_chunk_id(id: &str) -> Option<(i32, i32)> {
    let (x, z) = id.split_once(',')?;
    Some((x.parse().ok()?, z.parse().ok()?))
}

fn load_state(dir: &Path, catalog_id: u64, store_epoch: u64) -> ScanState {
    let mut state = ScanState {
        version: STATE_VERSION,
        catalog_id,
        store_epoch,
        ..ScanState::default()
    };
    let meta_path = dir.join("meta.state");
    if let Ok(bytes) = read_file_bounded(&meta_path, 4096) {
        match decode_envelope::<ScanMeta>(&bytes, META_MAGIC, 2048) {
            Ok(meta) if compatible_state_version(meta.version) && meta.catalog_id == catalog_id => {
                state.store_epoch = meta.store_epoch;
                state.mip_generation = meta.mip_generation;
                state.meta_trusted = true;
            }
            Ok(_) => {}
            Err(error) => eprintln!(
                "ignoring damaged scan metadata {}: {error:#}",
                meta_path.display()
            ),
        }
    }
    let entries = match fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return state,
        Err(error) => {
            eprintln!(
                "cannot enumerate scan-state directory {}: {error}",
                dir.display()
            );
            return state;
        }
    };
    for entry in entries {
        let path = match entry {
            Ok(entry) => entry.path(),
            Err(error) => {
                eprintln!("cannot enumerate a scan-state entry: {error}");
                continue;
            }
        };
        let Some(region) = parse_region_file(&path, ".state") else {
            continue;
        };
        let loaded = read_file_bounded(&path, 32 * 1024 * 1024)
            .map_err(anyhow::Error::from)
            .and_then(|bytes| decode_envelope::<RegionState>(&bytes, STATE_MAGIC, 32 * 1024 * 1024))
            .and_then(|region_state| {
                validate_region_state(&region_state, region, catalog_id)?;
                Ok(region_state)
            });
        match loaded {
            Ok(region_state) => {
                state.known_regions.insert(region_id(region));
                if let Some(marker) = region_state.file_marker {
                    state.regions.insert(region_id(region), marker);
                }
                state.chunks.extend(region_state.chunks);
            }
            Err(error) => eprintln!(
                "ignoring damaged scan-state region {}: {error:#}",
                path.display()
            ),
        }
    }
    state
}

fn save_state_regions(dir: &Path, state: &ScanState, regions: &BTreeSet<(i32, i32)>) -> Result<()> {
    if regions.is_empty() {
        return Ok(());
    }
    fs::create_dir_all(dir)?;
    let mut replacements = Vec::new();
    let mut removals = Vec::new();
    for &region in regions {
        let chunks = region_chunks(state, region)?;
        let marker = state.regions.get(&region_id(region)).copied();
        let path = region_state_path(dir, region);
        if marker.is_none() && chunks.is_empty() {
            removals.push(path);
            continue;
        }
        let snapshot = RegionState {
            version: STATE_VERSION,
            catalog_id: state.catalog_id,
            region_x: region.0,
            region_z: region.1,
            file_marker: marker,
            chunks,
        };
        validate_region_state(&snapshot, region, state.catalog_id)?;
        let bytes = encode_envelope(&snapshot, STATE_MAGIC)?;
        if bytes.len() > 32 * 1024 * 1024 {
            anyhow::bail!("scan-state region {region:?} exceeds 32 MiB");
        }
        let tmp = path.with_extension("state.tmp");
        write_synced(&tmp, &bytes)?;
        replacements.push((tmp, path));
    }
    for (tmp, path) in replacements {
        fs::rename(tmp, path)?;
    }
    for path in removals {
        match fs::remove_file(path) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
    }
    File::open(dir)?.sync_all()?;
    Ok(())
}

fn save_meta(dir: &Path, state: &ScanState) -> Result<()> {
    fs::create_dir_all(dir)?;
    let meta = ScanMeta {
        version: STATE_VERSION,
        catalog_id: state.catalog_id,
        store_epoch: state.store_epoch,
        mip_generation: state.mip_generation,
    };
    let bytes = encode_envelope(&meta, META_MAGIC)?;
    let path = dir.join("meta.state");
    if read_file_bounded(&path, 4096).is_ok_and(|old| old == bytes) {
        return Ok(());
    }
    let tmp = dir.join("meta.state.tmp");
    replace_synced(&path, &tmp, &bytes)
}

fn save_dirty_regions(dir: &Path, regions: &BTreeSet<(i32, i32)>) -> Result<()> {
    if regions.is_empty() {
        return Ok(());
    }
    fs::create_dir_all(dir)?;
    let mut replacements = Vec::new();
    for &region in regions {
        let path = dirty_region_path(dir, region);
        let mut bytes = Vec::with_capacity(20);
        bytes.extend_from_slice(DIRTY_MAGIC);
        bytes.extend_from_slice(&region.0.to_le_bytes());
        bytes.extend_from_slice(&region.1.to_le_bytes());
        bytes.extend_from_slice(&crc32c(&bytes).to_le_bytes());
        let tmp = path.with_extension("dirty.tmp");
        write_synced(&tmp, &bytes)?;
        replacements.push((tmp, path));
    }
    for (tmp, path) in replacements {
        fs::rename(tmp, path)?;
    }
    File::open(dir)?.sync_all()?;
    Ok(())
}

fn load_dirty_regions(dir: &Path) -> Result<BTreeSet<(i32, i32)>> {
    let entries = match fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(BTreeSet::new()),
        Err(error) => return Err(error.into()),
    };
    let mut regions = BTreeSet::new();
    for entry in entries {
        let path = entry?.path();
        let Some(region) = parse_region_file(&path, ".dirty") else {
            continue;
        };
        regions.insert(region);
        let valid = read_file_bounded(&path, 20).is_ok_and(|bytes| {
            bytes.len() == 20
                && &bytes[..8] == DIRTY_MAGIC
                && i32::from_le_bytes(bytes[8..12].try_into().unwrap()) == region.0
                && i32::from_le_bytes(bytes[12..16].try_into().unwrap()) == region.1
                && u32::from_le_bytes(bytes[16..20].try_into().unwrap()) == crc32c(&bytes[..16])
        });
        if !valid {
            eprintln!(
                "damaged dirty marker {}; conservatively recovering its complete region",
                path.display()
            );
        }
    }
    Ok(regions)
}

fn clear_dirty_regions_except(dir: &Path, retained: &BTreeSet<(i32, i32)>) -> Result<()> {
    let entries = match fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    let mut removed = false;
    for entry in entries {
        let path = entry?.path();
        if parse_region_file(&path, ".dirty").is_some_and(|region| !retained.contains(&region)) {
            fs::remove_file(path)?;
            removed = true;
        }
    }
    if removed {
        File::open(dir)?.sync_all()?;
    }
    Ok(())
}

fn encode_envelope<T: Serialize>(value: &T, magic: &[u8; 8]) -> Result<Vec<u8>> {
    let json = serde_json::to_vec(value)?;
    let mut bytes = Vec::with_capacity(20 + json.len());
    bytes.extend_from_slice(magic);
    bytes.extend_from_slice(&(json.len() as u64).to_le_bytes());
    bytes.extend_from_slice(&json);
    bytes.extend_from_slice(&crc32c(&bytes).to_le_bytes());
    Ok(bytes)
}

fn decode_envelope<T: for<'de> Deserialize<'de>>(
    bytes: &[u8],
    magic: &[u8; 8],
    max_json: usize,
) -> Result<T> {
    if bytes.len() < 20 || &bytes[..8] != magic {
        anyhow::bail!("bad checksummed JSON header");
    }
    let json_len = u64::from_le_bytes(bytes[8..16].try_into().unwrap()) as usize;
    if json_len > max_json || bytes.len() != 20 + json_len {
        anyhow::bail!("checksummed JSON length is invalid");
    }
    let expected = u32::from_le_bytes(bytes[bytes.len() - 4..].try_into().unwrap());
    if crc32c(&bytes[..bytes.len() - 4]) != expected {
        anyhow::bail!("checksummed JSON CRC mismatch");
    }
    Ok(serde_json::from_slice(&bytes[16..bytes.len() - 4])?)
}

fn validate_region_state(state: &RegionState, region: (i32, i32), catalog_id: u64) -> Result<()> {
    if !compatible_state_version(state.version)
        || state.catalog_id != catalog_id
        || (state.region_x, state.region_z) != region
        || state.chunks.len() > 1024
    {
        anyhow::bail!("region scan-state identity or size is invalid");
    }
    for (id, chunk) in &state.chunks {
        let (chunk_x, chunk_z) =
            parse_chunk_id(id).with_context(|| format!("invalid chunk identifier {id:?}"))?;
        if chunk_region(chunk_x, chunk_z) != region || chunk.level_zero_keys.len() > 256 {
            anyhow::bail!("chunk {id} is outside its scan-state region or has too many keys");
        }
        let mut unique = HashSet::new();
        for &packed in &chunk.level_zero_keys {
            let key = SectionKey::unpack(packed)?;
            if key.packed() != packed
                || key.level != 0
                || key.x != chunk_x.div_euclid(2)
                || key.z != chunk_z.div_euclid(2)
                || !unique.insert(packed)
            {
                anyhow::bail!("chunk {id} contains an invalid or duplicate level-zero key");
            }
        }
    }
    Ok(())
}

fn compatible_state_version(version: u32) -> bool {
    version == STATE_VERSION || version == LEGACY_STATE_VERSION
}

fn region_chunks(state: &ScanState, region: (i32, i32)) -> Result<HashMap<String, ChunkState>> {
    let base_x = region.0.checked_mul(32).context("region x overflow")?;
    let base_z = region.1.checked_mul(32).context("region z overflow")?;
    let mut chunks = HashMap::new();
    for dz in 0..32 {
        for dx in 0..32 {
            let id = chunk_id(base_x + dx, base_z + dz);
            if let Some(chunk) = state.chunks.get(&id) {
                chunks.insert(id, chunk.clone());
            }
        }
    }
    Ok(chunks)
}

fn remove_state_region(state: &mut ScanState, region: (i32, i32)) {
    if let (Some(base_x), Some(base_z)) = (region.0.checked_mul(32), region.1.checked_mul(32)) {
        for dz in 0..32 {
            for dx in 0..32 {
                state.chunks.remove(&chunk_id(base_x + dx, base_z + dz));
            }
        }
    }
    let id = region_id(region);
    state.regions.remove(&id);
    state.known_regions.remove(&id);
}

fn expected_region_level_zero(
    state: &ScanState,
    region: (i32, i32),
) -> Result<BTreeSet<SectionKey>> {
    Ok(region_chunks(state, region)?
        .values()
        .flat_map(|chunk| decode_keys(&chunk.level_zero_keys))
        .collect())
}

fn refresh_known_regions(state: &mut ScanState, regions: &BTreeSet<(i32, i32)>) -> Result<()> {
    for &region in regions {
        let id = region_id(region);
        if state.regions.contains_key(&id) || !region_chunks(state, region)?.is_empty() {
            state.known_regions.insert(id);
        } else {
            state.known_regions.remove(&id);
        }
    }
    Ok(())
}

fn mark_changed_regions(
    old: &HashMap<String, u64>,
    new: &HashMap<String, u64>,
    changed: &mut BTreeSet<(i32, i32)>,
) {
    for id in old.keys().chain(new.keys()) {
        if old.get(id) != new.get(id)
            && let Some(region) = parse_region_id(id)
        {
            changed.insert(region);
        }
    }
}

fn chunk_region(x: i32, z: i32) -> (i32, i32) {
    (x.div_euclid(32), z.div_euclid(32))
}

fn chunk_region_from_id(id: &str) -> Option<(i32, i32)> {
    parse_chunk_id(id).map(|(x, z)| chunk_region(x, z))
}

fn group_region(group: (i32, i32)) -> (i32, i32) {
    (group.0.div_euclid(16), group.1.div_euclid(16))
}

fn region_id(region: (i32, i32)) -> String {
    format!("{},{}", region.0, region.1)
}

fn parse_region_id(id: &str) -> Option<(i32, i32)> {
    parse_chunk_id(id)
}

fn region_state_path(dir: &Path, region: (i32, i32)) -> PathBuf {
    dir.join(format!("r.{}.{}.state", region.0, region.1))
}

fn dirty_region_path(dir: &Path, region: (i32, i32)) -> PathBuf {
    dir.join(format!("r.{}.{}.dirty", region.0, region.1))
}

fn parse_region_file(path: &Path, suffix: &str) -> Option<(i32, i32)> {
    let name = path.file_name()?.to_str()?;
    let body = name.strip_prefix("r.")?.strip_suffix(suffix)?;
    let (x, z) = body.split_once('.')?;
    Some((x.parse().ok()?, z.parse().ok()?))
}

pub fn safe_dimension_name(dimension: &str) -> String {
    let prefix = dimension
        .bytes()
        .map(|byte| match byte {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'-' | b'_' | b'.' => byte as char,
            _ => '_',
        })
        .take(80)
        .collect::<String>();
    let hash = dimension
        .bytes()
        .fold(0xcbf2_9ce4_8422_2325u64, |hash, byte| {
            (hash ^ u64::from(byte)).wrapping_mul(0x0000_0100_0000_01b3)
        });
    format!("{prefix}-{hash:016x}")
}

#[cfg(test)]
mod tests {
    use super::{
        ChunkState, GroupWork, LEGACY_STATE_VERSION, META_MAGIC, PendingChunk, RegionState,
        STATE_MAGIC, STATE_VERSION, ScanMeta, ScanState, Update, chunk_region,
        clear_dirty_regions_except, dirty_region_path, encode_envelope, group_region,
        group_semantically_unchanged, load_dirty_regions, load_state, loaded_sibling_advance,
        pending_sources_match, publish_replacements, record_failure, region_state_path,
        safe_dimension_name, save_dirty_regions, save_state_regions,
    };
    use crate::{
        anvil::{AnvilWorld, LevelZeroGroup, ParsedChunk},
        lod::{Cell, SECTION_VOLUME, Section},
        store::Store,
    };
    use std::{
        collections::{BTreeMap, BTreeSet, HashMap},
        fs,
        sync::{
            Arc,
            atomic::{AtomicBool, Ordering},
        },
    };

    #[test]
    fn dimension_storage_names_cannot_alias_after_sanitizing() {
        assert_ne!(safe_dimension_name("a:b"), safe_dimension_name("a/b"));
        assert_eq!(safe_dimension_name("a:b"), safe_dimension_name("a:b"));
    }

    #[test]
    fn negative_chunk_and_group_boundaries_map_to_the_correct_region() {
        assert_eq!(chunk_region(-1, -1), (-1, -1));
        assert_eq!(chunk_region(-32, -32), (-1, -1));
        assert_eq!(chunk_region(-33, -33), (-2, -2));
        assert_eq!(group_region((-1, -1)), (-1, -1));
        assert_eq!(group_region((-16, -16)), (-1, -1));
        assert_eq!(group_region((-17, -17)), (-2, -2));
    }

    #[test]
    fn one_damaged_or_missing_region_state_does_not_discard_its_neighbor() {
        let root = std::env::temp_dir().join(format!(
            "voxy-state-test-{}-{}",
            std::process::id(),
            safe_dimension_name("test:state")
        ));
        fs::create_dir_all(&root).unwrap();
        let first_key = crate::key::SectionKey::new(0, 1, 2, 2).unwrap().packed();
        let second_key = crate::key::SectionKey::new(0, 17, 2, 2).unwrap().packed();
        let state = ScanState {
            version: STATE_VERSION,
            catalog_id: 77,
            store_epoch: 9,
            regions: HashMap::from([("0,0".into(), 10), ("1,0".into(), 20)]),
            chunks: HashMap::from([
                (
                    "2,4".into(),
                    ChunkState {
                        header_marker: 1,
                        fingerprint: 2,
                        terrain_fingerprint: Some([20, 21]),
                        mip_generation: 3,
                        level_zero_keys: vec![first_key],
                    },
                ),
                (
                    "34,4".into(),
                    ChunkState {
                        header_marker: 4,
                        fingerprint: 5,
                        terrain_fingerprint: Some([50, 51]),
                        mip_generation: 6,
                        level_zero_keys: vec![second_key],
                    },
                ),
            ]),
            ..ScanState::default()
        };
        save_state_regions(&root, &state, &BTreeSet::from([(0, 0), (1, 0)])).unwrap();
        let neighbor_path = region_state_path(&root, (1, 0));
        let neighbor_before = fs::read(&neighbor_path).unwrap();
        let damaged_path = region_state_path(&root, (0, 0));
        let mut bytes = fs::read(&damaged_path).unwrap();
        bytes[17] ^= 1;
        fs::write(&damaged_path, bytes).unwrap();
        let loaded = load_state(&root, 77, 9);
        assert!(!loaded.regions.contains_key("0,0"));
        assert!(!loaded.chunks.contains_key("2,4"));
        assert_eq!(loaded.regions.get("1,0"), Some(&20));
        assert!(loaded.chunks.contains_key("34,4"));

        fs::remove_file(&neighbor_path).unwrap();
        let loaded = load_state(&root, 77, 9);
        assert!(loaded.regions.is_empty());
        assert!(loaded.chunks.is_empty());

        // Saving a changed region never rewrites an unrelated region shard.
        save_state_regions(&root, &state, &BTreeSet::from([(1, 0)])).unwrap();
        let neighbor_restored = fs::read(&neighbor_path).unwrap();
        save_state_regions(&root, &state, &BTreeSet::from([(0, 0)])).unwrap();
        assert_eq!(fs::read(&neighbor_path).unwrap(), neighbor_restored);
        assert_eq!(neighbor_before, neighbor_restored);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn a_corrupt_dirty_marker_recovers_only_its_named_region() {
        let root = std::env::temp_dir().join(format!(
            "voxy-dirty-test-{}-{}",
            std::process::id(),
            safe_dimension_name("test:dirty")
        ));
        fs::create_dir_all(&root).unwrap();
        let regions = BTreeSet::from([(0, -1), (2, 1)]);
        save_dirty_regions(&root, &regions).unwrap();
        let neighbor_path = dirty_region_path(&root, (2, 1));
        let neighbor = fs::read(&neighbor_path).unwrap();
        fs::write(dirty_region_path(&root, (0, -1)), [0]).unwrap();
        assert_eq!(load_dirty_regions(&root).unwrap(), regions);
        assert_eq!(fs::read(neighbor_path).unwrap(), neighbor);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn handled_failure_keeps_only_its_regional_dirty_marker() {
        let root = std::env::temp_dir().join(format!(
            "voxy-retained-dirty-test-{}-{}",
            std::process::id(),
            safe_dimension_name("test:retained-dirty")
        ));
        let regions = BTreeSet::from([(0, 0), (1, 0)]);
        save_dirty_regions(&root, &regions).unwrap();
        clear_dirty_regions_except(&root, &BTreeSet::from([(0, 0)])).unwrap();
        assert_eq!(load_dirty_regions(&root).unwrap(), BTreeSet::from([(0, 0)]));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn legacy_state_is_loaded_without_discarding_lod_ownership() {
        let root = std::env::temp_dir().join(format!(
            "voxy-legacy-state-test-{}-{}",
            std::process::id(),
            safe_dimension_name("test:legacy-state")
        ));
        fs::create_dir_all(&root).unwrap();
        let key = crate::key::SectionKey::new(0, 0, 1, 0).unwrap().packed();
        let chunk = ChunkState {
            header_marker: 3,
            fingerprint: 4,
            terrain_fingerprint: None,
            mip_generation: 5,
            level_zero_keys: vec![key],
        };
        let region = RegionState {
            version: LEGACY_STATE_VERSION,
            catalog_id: 77,
            region_x: 0,
            region_z: 0,
            file_marker: Some(6),
            chunks: HashMap::from([("0,0".into(), chunk)]),
        };
        let region_bytes = encode_envelope(&region, STATE_MAGIC).unwrap();
        assert!(!String::from_utf8_lossy(&region_bytes).contains("terrain_fingerprint"));
        fs::write(region_state_path(&root, (0, 0)), region_bytes).unwrap();
        let meta = ScanMeta {
            version: LEGACY_STATE_VERSION,
            catalog_id: 77,
            store_epoch: 9,
            mip_generation: 5,
        };
        fs::write(
            root.join("meta.state"),
            encode_envelope(&meta, META_MAGIC).unwrap(),
        )
        .unwrap();

        let loaded = load_state(&root, 77, 9);
        assert!(loaded.meta_trusted);
        assert!(loaded.known_regions.contains("0,0"));
        assert_eq!(loaded.chunks["0,0"].level_zero_keys, vec![key]);
        assert_eq!(loaded.chunks["0,0"].terrain_fingerprint, None);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn pre_serve_recovery_retains_last_good_sections_until_repair_finishes() {
        let root = std::env::temp_dir().join(format!(
            "voxy-local-recovery-test-{}-{}",
            std::process::id(),
            safe_dimension_name("test:local-recovery")
        ));
        fs::create_dir_all(&root).unwrap();
        let store = Arc::new(Store::open(root.join("store"), 77).unwrap());
        let first = crate::key::SectionKey::new(0, 0, 0, 0).unwrap();
        let second = crate::key::SectionKey::new(0, 16, 0, 0).unwrap();
        let section = |key, block| {
            Section::from_cells(
                key,
                vec![
                    Cell {
                        block,
                        biome: 0,
                        light: 15
                    };
                    SECTION_VOLUME
                ],
            )
            .unwrap()
        };
        store
            .put_many(&[section(first, 1), section(second, 2)])
            .unwrap();
        let dimension = "test:local-recovery";
        let state_dir = root
            .join("data")
            .join("scan-state")
            .join(safe_dimension_name(dimension));
        let state = ScanState {
            version: STATE_VERSION,
            catalog_id: 77,
            store_epoch: store.epoch(),
            regions: HashMap::from([("0,0".into(), 1), ("1,0".into(), 2)]),
            chunks: HashMap::from([
                (
                    "0,0".into(),
                    ChunkState {
                        header_marker: 1,
                        fingerprint: 1,
                        terrain_fingerprint: Some([10, 11]),
                        mip_generation: 0,
                        level_zero_keys: vec![first.packed()],
                    },
                ),
                (
                    "32,0".into(),
                    ChunkState {
                        header_marker: 2,
                        fingerprint: 2,
                        terrain_fingerprint: Some([20, 21]),
                        mip_generation: 0,
                        level_zero_keys: vec![second.packed()],
                    },
                ),
            ]),
            ..ScanState::default()
        };
        save_state_regions(&state_dir, &state, &BTreeSet::from([(0, 0), (1, 0)])).unwrap();
        let dirty_dir = state_dir.join("dirty");
        save_dirty_regions(&dirty_dir, &BTreeSet::from([(0, 0)])).unwrap();
        fs::write(dirty_region_path(&dirty_dir, (0, 0)), [0]).unwrap();

        let runtime = super::DimensionRuntime::new(
            AnvilWorld::new(dimension.into(), root.join("source")),
            store.clone(),
            &root.join("data"),
        );
        runtime.recover_before_serve().unwrap();
        assert_eq!(store.get(first).unwrap().unwrap().section.cells[0].block, 1);
        assert_eq!(
            store.get(second).unwrap().unwrap().section.cells[0].block,
            2
        );
        assert!(runtime.reconcile_required.load(Ordering::Acquire));

        // Missing/corrupt ownership metadata also schedules regional reconstruction without
        // punching a visible hole before that reconstruction has completed.
        fs::remove_file(dirty_region_path(&dirty_dir, (0, 0))).unwrap();
        fs::write(region_state_path(&state_dir, (0, 0)), [0]).unwrap();
        store.put_many(&[section(first, 3)]).unwrap();
        let runtime = super::DimensionRuntime::new(
            AnvilWorld::new(dimension.into(), root.join("source")),
            store.clone(),
            &root.join("data"),
        );
        runtime.recover_before_serve().unwrap();
        assert_eq!(store.get(first).unwrap().unwrap().section.cells[0].block, 3);
        assert_eq!(
            store.get(second).unwrap().unwrap().section.cells[0].block,
            2
        );
        assert!(runtime.reconcile_required.load(Ordering::Acquire));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn completed_replacement_never_broadcasts_a_temporary_invalidation() {
        let root = std::env::temp_dir().join(format!(
            "voxy-staged-publish-test-{}-{}",
            std::process::id(),
            safe_dimension_name("test:staged-publish")
        ));
        let store = Store::open(&root, 91).unwrap();
        let key = crate::key::SectionKey::new(0, 2, 0, -3).unwrap();
        let section = |block| {
            Section::from_cells(
                key,
                vec![
                    Cell {
                        block,
                        biome: 0,
                        light: 15,
                    };
                    SECTION_VOLUME
                ],
            )
            .unwrap()
        };
        store.put_many(&[section(1)]).unwrap();
        let (updates, mut receiver) = tokio::sync::broadcast::channel(8);

        assert_eq!(
            publish_replacements(
                &store,
                &updates,
                "test:staged-publish",
                &[section(2)],
                &[key],
                true,
            )
            .unwrap(),
            1
        );
        assert!(matches!(
            receiver.try_recv().unwrap().change,
            Update::Section(_)
        ));
        assert!(matches!(
            receiver.try_recv(),
            Err(tokio::sync::broadcast::error::TryRecvError::Empty)
        ));
        assert_eq!(store.get(key).unwrap().unwrap().section.cells[0].block, 2);
        let revision = store.revision(key).unwrap();

        assert_eq!(
            publish_replacements(
                &store,
                &updates,
                "test:staged-publish",
                &[section(2)],
                &[key],
                true,
            )
            .unwrap(),
            0
        );
        assert_eq!(store.revision(key).unwrap(), revision);
        assert!(matches!(
            receiver.try_recv(),
            Err(tokio::sync::broadcast::error::TryRecvError::Empty)
        ));

        publish_replacements(&store, &updates, "test:staged-publish", &[], &[key], true).unwrap();
        assert!(matches!(
            receiver.try_recv().unwrap().change,
            Update::Invalidate(_)
        ));
        assert!(store.get(key).unwrap().is_none());

        // Rebuilding another empty sibling must not mint the same tombstone again.
        publish_replacements(&store, &updates, "test:staged-publish", &[], &[key], true).unwrap();
        assert!(matches!(
            receiver.try_recv(),
            Err(tokio::sync::broadcast::error::TryRecvError::Empty)
        ));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn pending_chunk_must_still_match_its_prefingerprint() {
        let pending = PendingChunk {
            id: "0,0".into(),
            x: 0,
            z: 0,
            marker: Some((1, 99)),
            old_keys: Vec::new(),
        };
        let work = GroupWork {
            pending: vec![pending],
            ..GroupWork::default()
        };
        let mut group = LevelZeroGroup {
            x: 0,
            z: 0,
            chunks: vec![
                Some(ParsedChunk {
                    x: 0,
                    z: 0,
                    sections: BTreeMap::new(),
                    source_fingerprint: 99,
                    terrain_fingerprint: [100, 101],
                }),
                None,
                None,
                None,
            ],
        };
        assert!(pending_sources_match(&group, &work));
        group.chunks[0] = None;
        assert!(!pending_sources_match(&group, &work));
    }

    #[test]
    fn shared_group_skips_only_when_every_pending_chunk_is_semantically_unchanged() {
        let parsed = |x, source, terrain| ParsedChunk {
            x,
            z: 0,
            sections: BTreeMap::new(),
            source_fingerprint: source,
            terrain_fingerprint: terrain,
        };
        let mut group = LevelZeroGroup {
            x: 0,
            z: 0,
            chunks: vec![
                Some(parsed(0, 20, [100, 101])),
                Some(parsed(1, 40, [200, 201])),
                None,
                None,
            ],
        };
        let mut work = GroupWork {
            pending: vec![
                PendingChunk {
                    id: "0,0".into(),
                    x: 0,
                    z: 0,
                    marker: Some((2, 20)),
                    old_keys: Vec::new(),
                },
                PendingChunk {
                    id: "1,0".into(),
                    x: 1,
                    z: 0,
                    marker: Some((4, 40)),
                    old_keys: Vec::new(),
                },
            ],
            ..GroupWork::default()
        };
        let state = ScanState {
            chunks: HashMap::from([
                (
                    "0,0".into(),
                    ChunkState {
                        header_marker: 1,
                        fingerprint: 10,
                        terrain_fingerprint: Some([100, 101]),
                        mip_generation: 7,
                        level_zero_keys: Vec::new(),
                    },
                ),
                (
                    "1,0".into(),
                    ChunkState {
                        header_marker: 3,
                        fingerprint: 30,
                        terrain_fingerprint: Some([200, 201]),
                        mip_generation: 7,
                        level_zero_keys: Vec::new(),
                    },
                ),
            ]),
            ..ScanState::default()
        };
        assert!(group_semantically_unchanged(&group, &work, &state, 7));

        group.chunks[1].as_mut().unwrap().terrain_fingerprint[0] ^= 1;
        assert!(!group_semantically_unchanged(&group, &work, &state, 7));
        group.chunks[1].as_mut().unwrap().terrain_fingerprint[0] ^= 1;
        assert!(!group_semantically_unchanged(&group, &work, &state, 8));
        work.repair_keys
            .insert(crate::key::SectionKey::new(0, 0, 0, 0).unwrap());
        assert!(!group_semantically_unchanged(&group, &work, &state, 7));
    }

    #[test]
    fn a_handled_failure_keeps_reconciliation_active_for_the_next_poll() {
        let required = AtomicBool::new(false);
        let mut failures = 0;
        record_failure(&required, &mut failures);
        assert_eq!(failures, 1);
        assert!(required.load(Ordering::Acquire));
    }

    #[test]
    fn a_loaded_unscheduled_sibling_gets_owned_and_forced_to_rescan() {
        let chunk = ParsedChunk {
            x: 3,
            z: -2,
            sections: BTreeMap::new(),
            source_fingerprint: 77,
            terrain_fingerprint: [770, 771],
        };
        let advance = loaded_sibling_advance(&chunk, None, &[11, 12]);
        assert_eq!(advance.id, "3,-2");
        assert_eq!(advance.marker, Some((0, 77)));
        assert_eq!(advance.level_zero_keys, vec![11, 12]);

        let unchanged = ChunkState {
            header_marker: 99,
            fingerprint: 77,
            terrain_fingerprint: Some([770, 771]),
            mip_generation: 1,
            level_zero_keys: vec![5],
        };
        assert_eq!(
            loaded_sibling_advance(&chunk, Some(&unchanged), &[]).marker,
            Some((99, 77))
        );
        let changed = ChunkState {
            fingerprint: 76,
            ..unchanged
        };
        assert_eq!(
            loaded_sibling_advance(&chunk, Some(&changed), &[]).marker,
            Some((0, 77))
        );
    }
}
