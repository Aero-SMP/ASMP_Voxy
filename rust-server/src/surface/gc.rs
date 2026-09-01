//! Conservative reachability collection for immutable surface objects.
//!
//! This module owns a generation of
//! [`PackStore`] files through a tiny, checksummed pointer.  Collection never edits the active
//! generation: it traces every pinned root, waits for two successful marks plus both grace
//! periods, copies the retained objects into a new generation, verifies that generation, and
//! only then changes the pointer.  Uncertain reachability always leaks space rather than data.

use super::{
    catalog::Catalog,
    content::{SourceMicrotile, content_kind},
    manifest::{ContentClass, ManifestDescriptorPage, ManifestSubtree, RootDirectory},
    memory::{MemoryClass, MemoryPermit, ServerMemoryBudget},
    object::{CanonicalObject, ObjectHash, ObjectKind},
    pack::{MAX_CANONICAL_OBJECT_BYTES, MAX_COMPRESSED_OBJECT_BYTES, PackStore},
    root::RootRecord,
    visibility::{object_references, summary_page_references},
};
use crate::{
    crc::crc32c, quarantine, read_file_bounded, replace_synced, sync_parent, take, take_u32,
};
use anyhow::{Context, Result, bail};
use crc32c::crc32c_append;
use std::{
    collections::BTreeSet,
    fs::{self, File},
    io::{BufReader, BufWriter, Read, Write},
    path::{Path, PathBuf},
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
};

const DICTIONARY_SET_MAGIC: &[u8; 8] = b"VXYDSET\0";
const MAX_DICTIONARIES: usize = 65_536;

const SET_POINTER_MAGIC: &[u8; 8] = b"VXYSET\0\0";
const SET_READY_MAGIC: &[u8; 8] = b"VXYREADY";
const SET_MEMBERS_MAGIC: &[u8; 8] = b"VXYMEM\0\0";
const SET_RECORD_BYTES: usize = 64;
const SET_MEMBERS_HEADER: usize = 16;
const SET_MEMBERS_TRAILER: usize = 4;

const MARK_MAGIC: &[u8; 8] = b"VXYGCM\0\0";
const MARK_HEADER_BYTES: usize = 36;
const MARK_ENTRY_BYTES: usize = 64;
const MARK_TRAILER_BYTES: usize = 4;
const HARD_MAX_OBJECTS: usize = 16_000_000;
const HARD_MAX_PINS: usize = 65_536;
const HARD_MAX_ISSUES: usize = 4_096;
const GC_SORT_BUFFER: usize = 16 * 1024;
const REFERENCE_RECORD_BYTES: usize = 40;
const GC_WORKING_BYTES: usize =
    MAX_CANONICAL_OBJECT_BYTES + 2 * MAX_COMPRESSED_OBJECT_BYTES + 16 * 1024 * 1024;
static NEXT_GC_SCRATCH: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DictionarySet {
    /// Sorted canonical hashes of every dictionary usable by objects in the pinned root.
    pub dictionaries: Vec<ObjectHash>,
}

impl DictionarySet {
    pub fn new(dictionaries: Vec<ObjectHash>) -> Result<Self> {
        let value = Self { dictionaries };
        value.validate()?;
        Ok(value)
    }

    pub fn validate(&self) -> Result<()> {
        if self.dictionaries.is_empty() || self.dictionaries.len() > MAX_DICTIONARIES {
            bail!("dictionary set must contain 1..={MAX_DICTIONARIES} hashes");
        }
        if self
            .dictionaries
            .iter()
            .any(|dictionary| dictionary.is_zero())
            || self.dictionaries.windows(2).any(|pair| pair[0] >= pair[1])
        {
            bail!("dictionary hashes must be nonzero and strictly sorted");
        }
        Ok(())
    }

    pub fn encode(&self) -> Result<Vec<u8>> {
        self.validate()?;
        let mut bytes = Vec::with_capacity(12 + self.dictionaries.len() * 32);
        bytes.extend_from_slice(DICTIONARY_SET_MAGIC);
        bytes.extend_from_slice(&(self.dictionaries.len() as u32).to_le_bytes());
        for dictionary in &self.dictionaries {
            bytes.extend_from_slice(dictionary.as_bytes());
        }
        Ok(bytes)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() < 12 || &bytes[..8] != DICTIONARY_SET_MAGIC {
            bail!("invalid surface dictionary-set envelope");
        }
        let mut input = &bytes[8..];
        let count = take_u32(&mut input)? as usize;
        if count == 0
            || count > MAX_DICTIONARIES
            || input.len()
                != count
                    .checked_mul(32)
                    .context("dictionary-set size overflow")?
        {
            bail!("invalid surface dictionary-set length");
        }
        let mut dictionaries = Vec::with_capacity(count);
        for _ in 0..count {
            dictionaries.push(ObjectHash::from_bytes(
                take(&mut input, 32)?.try_into().unwrap(),
            )?);
        }
        Self::new(dictionaries)
    }

    pub fn canonical_object(&self) -> Result<CanonicalObject> {
        CanonicalObject::new(ObjectKind::DictionarySet, self.encode()?)
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct GcPins {
    pub current: Vec<RootRecord>,
    pub previous: Vec<RootRecord>,
    pub building: Vec<RootRecord>,
    /// Roots selected by integration's safety-expiration policy.
    pub recent: Vec<RootRecord>,
}

impl GcPins {
    fn iter(&self) -> impl Iterator<Item = &RootRecord> {
        self.current
            .iter()
            .chain(&self.previous)
            .chain(&self.building)
            .chain(&self.recent)
    }

    fn len(&self) -> usize {
        self.current.len() + self.previous.len() + self.building.len() + self.recent.len()
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct GcPolicy {
    /// Includes the first absent mark. Values below two are rejected.
    pub successful_absent_marks: u32,
    pub grace_seconds: u64,
    pub grace_generations: u64,
    pub max_objects: usize,
    pub max_pinned_roots: usize,
    pub max_reported_issues: usize,
}

impl Default for GcPolicy {
    fn default() -> Self {
        Self {
            successful_absent_marks: 2,
            grace_seconds: 24 * 60 * 60,
            grace_generations: 2,
            max_objects: 4_000_000,
            max_pinned_roots: 4_096,
            max_reported_issues: 256,
        }
    }
}

impl GcPolicy {
    fn validate(self) -> Result<Self> {
        if self.successful_absent_marks < 2 {
            bail!("surface GC requires at least two successful absent marks");
        }
        if self.max_objects == 0 || self.max_objects > HARD_MAX_OBJECTS {
            bail!("surface GC object bound must be 1..={HARD_MAX_OBJECTS}");
        }
        if self.max_pinned_roots == 0 || self.max_pinned_roots > HARD_MAX_PINS {
            bail!("surface GC root bound must be 1..={HARD_MAX_PINS}");
        }
        if self.max_reported_issues == 0 || self.max_reported_issues > HARD_MAX_ISSUES {
            bail!("surface GC issue bound must be 1..={HARD_MAX_ISSUES}");
        }
        Ok(self)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct GcMoment {
    pub unix_seconds: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ReferenceIssueKind {
    Missing,
    Corrupt,
    WrongType,
    BoundExceeded,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ReferenceIssue {
    pub hash: Option<ObjectHash>,
    pub kind: ReferenceIssueKind,
    pub detail: String,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct MarkReport {
    pub reachable_objects: usize,
    pub issues: Vec<ReferenceIssue>,
    pub issue_overflow: bool,
    pub needs_regeneration: bool,
}

impl MarkReport {
    pub fn conclusive(&self) -> bool {
        !self.needs_regeneration && !self.issue_overflow && self.issues.is_empty()
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct GcRunReport {
    pub mark: MarkReport,
    pub successful_mark_cycle: Option<u64>,
    pub active_objects_before: usize,
    pub retained_objects: usize,
    pub grace_retained_objects: usize,
    pub reclaimed_objects: usize,
    pub copied_objects: usize,
    pub switched: bool,
    pub old_set_retired: bool,
}

impl GcRunReport {
    fn inconclusive(mark: MarkReport, active_objects: usize) -> Self {
        Self {
            mark,
            successful_mark_cycle: None,
            active_objects_before: active_objects,
            retained_objects: active_objects,
            grace_retained_objects: active_objects,
            reclaimed_objects: 0,
            copied_objects: 0,
            switched: false,
            old_set_retired: false,
        }
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct GcRecovery {
    pub damaged_current_pointer: bool,
    pub damaged_previous_pointer: bool,
    pub restored_previous_pointer: bool,
    pub damaged_mark_history: bool,
    pub orphaned_pack_sets: Vec<PathBuf>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct SetIdentity {
    id: u64,
    object_count: u64,
    digest: [u8; 32],
}

/// An atomically replaceable set of immutable packfiles.
#[derive(Debug)]
pub struct GcPackStore {
    root: PathBuf,
    sets: PathBuf,
    active_identity: SetIdentity,
    active_path: PathBuf,
    active: PackStore,
    recovery: GcRecovery,
    memory: Arc<ServerMemoryBudget>,
}

impl GcPackStore {
    pub fn open(root: impl AsRef<Path>) -> Result<Self> {
        Self::open_with_budget(root, ServerMemoryBudget::default_budget())
    }

    pub fn open_with_budget(
        root: impl AsRef<Path>,
        memory: Arc<ServerMemoryBudget>,
    ) -> Result<Self> {
        let root = root.as_ref().to_path_buf();
        let sets = root.join("sets");
        fs::create_dir_all(&sets).with_context(|| format!("create {}", sets.display()))?;
        sync_parent(&sets.join("placeholder"))?;

        let current_path = root.join("active.current");
        let previous_path = root.join("active.previous");
        let mut recovery = GcRecovery::default();
        let current = read_optional_identity(&current_path, SET_POINTER_MAGIC).inspect_err(|_| {
            recovery.damaged_current_pointer = true;
            quarantine(&current_path);
        });
        let previous =
            read_optional_identity(&previous_path, SET_POINTER_MAGIC).inspect_err(|_| {
                recovery.damaged_previous_pointer = true;
                quarantine(&previous_path);
            });
        let current = current.unwrap_or(None);
        let previous = previous.unwrap_or(None);

        let chosen = match current {
            Some(identity) => match open_identity(&sets, identity, memory.clone()) {
                Ok(opened) => Some((identity, opened)),
                Err(_) => {
                    recovery.damaged_current_pointer = true;
                    None
                }
            },
            None => None,
        };
        let chosen = match chosen {
            Some(chosen) => Some(chosen),
            None => match previous {
                Some(identity) => match open_identity(&sets, identity, memory.clone()) {
                    Ok(opened) => {
                        recovery.restored_previous_pointer = true;
                        Some((identity, opened))
                    }
                    Err(_) => {
                        recovery.damaged_previous_pointer = true;
                        None
                    }
                },
                None => None,
            },
        };

        let (active_identity, (active_path, active)) = match chosen {
            Some(chosen) => chosen,
            None => {
                let existing = set_directories(&sets, HARD_MAX_OBJECTS)?;
                if !existing.is_empty() {
                    bail!(
                        "no valid surface active pack-set pointer; refusing to guess among {} sets",
                        existing.len()
                    );
                }
                let path = set_path(&sets, 0);
                fs::create_dir(&path)?;
                sync_parent(&path)?;
                let store = PackStore::open_with_budget(&path, memory.clone())?;
                store.sync_all()?;
                store.checkpoint()?;
                let identity = identity_for(0, &store, HARD_MAX_OBJECTS)?;
                write_set_members(&path, &[])?;
                write_identity(&path.join("set.ready"), SET_READY_MAGIC, identity)?;
                write_identity(&current_path, SET_POINTER_MAGIC, identity)?;
                (identity, (path, store))
            }
        };
        if recovery.restored_previous_pointer {
            write_identity(&current_path, SET_POINTER_MAGIC, active_identity)?;
        }

        for path in set_directories(&sets, HARD_MAX_OBJECTS)? {
            if path != active_path && !previous.is_some_and(|id| resolves_to(&sets, id, &path)) {
                recovery.orphaned_pack_sets.push(path);
            }
        }
        Ok(Self {
            root,
            sets,
            active_identity,
            active_path,
            active,
            recovery,
            memory,
        })
    }

    pub fn active(&self) -> &PackStore {
        &self.active
    }

    /// Callers may append objects through this handle. They must still publish roots only after
    /// the ordinary PackStore durability barrier; GC itself seals and checkpoints replacements.
    pub fn active_mut(&mut self) -> &mut PackStore {
        &mut self.active
    }

    pub fn active_set_id(&self) -> u64 {
        self.active_identity.id
    }

    pub fn recovery(&self) -> &GcRecovery {
        &self.recovery
    }

    pub fn checkpoint_active(&self) -> Result<()> {
        self.active.sync_all()?;
        self.active.checkpoint()
    }

    pub fn collect(
        &mut self,
        pins: &GcPins,
        moment: GcMoment,
        policy: GcPolicy,
    ) -> Result<GcRunReport> {
        let policy = policy.validate()?;
        validate_pins(pins, policy.max_pinned_roots)?;
        // One all-or-nothing maintenance reservation covers the largest decoded object, its
        // compressed form, fixed external-sort buffers, and streaming I/O buffers. Additional
        // pack compression reservations remain separately accounted by the same global budget.
        let maintenance_memory = self
            .memory
            .try_reserve(MemoryClass::Maintenance, GC_WORKING_BYTES)?;
        let mut marker = Marker::new(&self.active, &policy, &self.root, &maintenance_memory)?;
        marker.add_roots(pins);
        marker.run();
        let (reachable, mut mark) = marker.finish()?;
        let all_hashes =
            SortedHashes::from_store(reachable.scratch.clone(), &self.active, policy.max_objects)?;
        let active_count = all_hashes.count;
        if !mark.conclusive() {
            return Ok(GcRunReport::inconclusive(mark, active_count));
        }

        // Grace is measured against authoritative published progress. A speculative building
        // root may use a future generation and must not make unrelated objects age faster.
        let maximum_generation = pins
            .current
            .iter()
            .map(|root| root.generation)
            .max()
            .context("surface GC requires at least one pinned root")?;
        let state_path = self.root.join("gc.marks");
        let history = match read_mark_history(&state_path, policy.max_objects) {
            Ok(history) => history,
            Err(error) => {
                eprintln!(
                    "discarding damaged surface GC history {}: {error:#}",
                    state_path.display()
                );
                quarantine(&state_path);
                self.recovery.damaged_mark_history = true;
                None
            }
        };
        let classification = classify_objects(
            &self.active,
            &all_hashes,
            &reachable,
            history.as_ref(),
            moment,
            maximum_generation,
            policy,
        )?;
        let retained = classification.retained;
        let reclaim = classification.reclaim;
        let grace_retained = classification.grace_retained;
        let cycle = classification.cycle;

        // A grace-retained object is still data. Verify it before advancing the successful
        // mark: if it cannot be copied, keep the old set and report regeneration instead.
        for_each_hash_difference(&retained, &reachable, |hash| {
            match self.active.get_scoped(hash, &maintenance_memory) {
                Ok(Some(_)) => {}
                Ok(None) => marker_issue(
                    &mut mark,
                    policy.max_reported_issues,
                    Some(hash),
                    ReferenceIssueKind::Missing,
                    "grace-retained object disappeared before copying",
                ),
                Err(error) => marker_issue(
                    &mut mark,
                    policy.max_reported_issues,
                    Some(hash),
                    ReferenceIssueKind::Corrupt,
                    format!("cannot verify grace-retained object: {error:#}"),
                ),
            }
            Ok(())
        })?;
        if !mark.conclusive() {
            return Ok(GcRunReport::inconclusive(mark, active_count));
        }

        write_mark_history(
            &state_path,
            cycle,
            moment.unix_seconds,
            &classification.absences,
            policy.max_objects,
        )?;
        if reclaim.count == 0 {
            write_identity(
                &self.root.join("active.previous"),
                SET_POINTER_MAGIC,
                self.active_identity,
            )?;
            let retired = self.reap_unreferenced_sets()?;
            return Ok(GcRunReport {
                mark,
                successful_mark_cycle: Some(cycle),
                active_objects_before: active_count,
                retained_objects: retained.count,
                grace_retained_objects: grace_retained,
                reclaimed_objects: 0,
                copied_objects: 0,
                switched: false,
                old_set_retired: retired != 0,
            });
        }

        let replacement_id = next_set_id(&self.sets)?;
        let replacement_path = set_path(&self.sets, replacement_id);
        fs::create_dir(&replacement_path)
            .with_context(|| format!("create replacement {}", replacement_path.display()))?;
        sync_parent(&replacement_path)?;
        let mut replacement = PackStore::open_with_budget(&replacement_path, self.memory.clone())?;
        let mut retained_cursor = HashCursor::new(&retained)?;
        while let Some(hash) = retained_cursor.next()? {
            let object = match self.active.get_scoped(hash, &maintenance_memory) {
                Ok(Some(object)) => object,
                Ok(None) => {
                    marker_issue(
                        &mut mark,
                        policy.max_reported_issues,
                        Some(hash),
                        ReferenceIssueKind::Missing,
                        "retained object vanished after the successful mark",
                    );
                    return Ok(failed_after_mark(mark, cycle, active_count, grace_retained));
                }
                Err(error) => {
                    marker_issue(
                        &mut mark,
                        policy.max_reported_issues,
                        Some(hash),
                        ReferenceIssueKind::Corrupt,
                        format!("retained object changed after the successful mark: {error:#}"),
                    );
                    return Ok(failed_after_mark(mark, cycle, active_count, grace_retained));
                }
            };
            if object.hash() != hash || !object.verify() {
                bail!("retained surface object {hash} failed independent verification");
            }
            let location = self
                .active
                .location(hash)
                .context("retained object disappeared from the active pack index")?;
            if location.dictionary.is_zero() {
                replacement.put(&object)?;
            } else {
                let dictionary = self
                    .active
                    .get_scoped(location.dictionary, &maintenance_memory)?
                    .context("retained object compression dictionary is missing")?;
                if dictionary.kind() != ObjectKind::CompressionDictionary {
                    bail!("retained object compression dependency has the wrong type");
                }
                replacement.put_many_with_dictionary(std::iter::once(&object), &dictionary)?;
            }
        }
        replacement.sync_all()?;
        replacement.checkpoint()?;

        // Reopen from the durable pack/index files and verify every copied object, rather than
        // trusting the writer's in-memory index.
        drop(replacement);
        let replacement = PackStore::open_with_budget(&replacement_path, self.memory.clone())?;
        if !retained.equals_store(&replacement, policy.max_objects)? {
            marker_issue(
                &mut mark,
                policy.max_reported_issues,
                None,
                ReferenceIssueKind::Corrupt,
                "replacement surface pack-set index differs from the retained object set",
            );
            return Ok(failed_after_mark(mark, cycle, active_count, grace_retained));
        }
        let mut retained_cursor = HashCursor::new(&retained)?;
        while let Some(hash) = retained_cursor.next()? {
            let object = match replacement.get_scoped(hash, &maintenance_memory) {
                Ok(Some(object)) => object,
                Ok(None) => {
                    marker_issue(
                        &mut mark,
                        policy.max_reported_issues,
                        Some(hash),
                        ReferenceIssueKind::Missing,
                        "copied object vanished before replacement verification",
                    );
                    return Ok(failed_after_mark(mark, cycle, active_count, grace_retained));
                }
                Err(error) => {
                    marker_issue(
                        &mut mark,
                        policy.max_reported_issues,
                        Some(hash),
                        ReferenceIssueKind::Corrupt,
                        format!("copied object failed replacement verification: {error:#}"),
                    );
                    return Ok(failed_after_mark(mark, cycle, active_count, grace_retained));
                }
            };
            if object.hash() != hash || !object.verify() {
                marker_issue(
                    &mut mark,
                    policy.max_reported_issues,
                    Some(hash),
                    ReferenceIssueKind::Corrupt,
                    "copied object failed canonical verification",
                );
                return Ok(failed_after_mark(mark, cycle, active_count, grace_retained));
            }
        }
        replacement.sync_all()?;
        replacement.checkpoint()?;
        let replacement_identity = identity_for(replacement_id, &replacement, policy.max_objects)?;
        write_set_members_run(&replacement_path, &retained)?;
        write_identity(
            &replacement_path.join("set.ready"),
            SET_READY_MAGIC,
            replacement_identity,
        )?;
        sync_parent(&replacement_path.join("set.ready"))?;

        write_identity(
            &self.root.join("active.previous"),
            SET_POINTER_MAGIC,
            self.active_identity,
        )?;
        write_identity(
            &self.root.join("active.current"),
            SET_POINTER_MAGIC,
            replacement_identity,
        )?;
        let old_identity = self.active_identity;
        let old_path = self.active_path.clone();
        self.active_identity = replacement_identity;
        self.active_path = replacement_path;
        self.active = replacement;

        // Once current points at the fully verified replacement, mirror it into the backup
        // pointer. Logical previous/recent roots are already present in the copied closure;
        // the backup pointer protects the pack-set selector itself, not an older object set.
        write_identity(
            &self.root.join("active.previous"),
            SET_POINTER_MAGIC,
            replacement_identity,
        )?;

        let retired_path = retired_path(&self.sets, old_identity.id);
        if old_path != retired_path {
            if retired_path.exists() {
                bail!(
                    "refusing to overwrite existing retired surface pack set {}",
                    retired_path.display()
                );
            }
            fs::rename(&old_path, &retired_path)?;
            sync_parent(&retired_path)?;
        }
        self.reap_unreferenced_sets()?;
        Ok(GcRunReport {
            mark,
            successful_mark_cycle: Some(cycle),
            active_objects_before: active_count,
            retained_objects: retained.count,
            grace_retained_objects: grace_retained,
            reclaimed_objects: reclaim.count,
            copied_objects: retained.count,
            switched: true,
            old_set_retired: true,
        })
    }

    fn reap_unreferenced_sets(&mut self) -> Result<usize> {
        let current = read_optional_identity(&self.root.join("active.current"), SET_POINTER_MAGIC)?
            .context("cannot retire packs without a current pack-set pointer")?;
        if current != self.active_identity {
            bail!("in-memory and durable current surface pack sets disagree");
        }
        let previous =
            read_optional_identity(&self.root.join("active.previous"), SET_POINTER_MAGIC)?;
        let mut protected = BTreeSet::new();
        protected.insert(self.active_path.clone());
        if let Some(path) = resolve_set_path(&self.sets, current.id) {
            protected.insert(path);
        }
        if let Some(previous) = previous
            && let Some(path) = resolve_set_path(&self.sets, previous.id)
        {
            protected.insert(path);
        }

        let mut removed = 0usize;
        for path in set_directories(&self.sets, HARD_MAX_OBJECTS)? {
            if protected.contains(&path) {
                continue;
            }
            fs::remove_dir_all(&path)
                .with_context(|| format!("retire surface pack set {}", path.display()))?;
            removed += 1;
        }
        if removed != 0 {
            sync_parent(&self.sets.join("placeholder"))?;
            self.recovery
                .orphaned_pack_sets
                .retain(|path| path.exists());
        }
        Ok(removed)
    }
}

#[derive(Debug)]
struct GcScratch {
    root: PathBuf,
    next: AtomicU64,
}

impl GcScratch {
    fn create(parent: &Path) -> Result<Arc<Self>> {
        let id = NEXT_GC_SCRATCH.fetch_add(1, Ordering::Relaxed);
        let root = parent.join(format!("gc-work-{}-{id}", std::process::id()));
        fs::create_dir(&root)
            .with_context(|| format!("create GC scratch directory {}", root.display()))?;
        Ok(Arc::new(Self {
            root,
            next: AtomicU64::new(0),
        }))
    }

    fn path(&self, label: &str) -> PathBuf {
        let id = self.next.fetch_add(1, Ordering::Relaxed);
        self.root.join(format!("{id:016x}-{label}.run"))
    }
}

impl Drop for GcScratch {
    fn drop(&mut self) {
        if let Err(error) = fs::remove_dir_all(&self.root)
            && error.kind() != std::io::ErrorKind::NotFound
        {
            eprintln!("cannot remove GC scratch {}: {error}", self.root.display());
        }
    }
}

#[derive(Clone, Debug)]
struct SortedHashes {
    scratch: Arc<GcScratch>,
    path: PathBuf,
    count: usize,
}

impl SortedHashes {
    fn empty(scratch: Arc<GcScratch>) -> Result<Self> {
        let path = scratch.path("empty-hashes");
        File::create(&path)?.sync_all()?;
        Ok(Self {
            scratch,
            path,
            count: 0,
        })
    }

    fn for_each(&self, mut visit: impl FnMut(ObjectHash) -> Result<()>) -> Result<()> {
        let mut cursor = HashCursor::new(self)?;
        while let Some(hash) = cursor.next()? {
            visit(hash)?;
        }
        Ok(())
    }

    fn from_store(scratch: Arc<GcScratch>, store: &PackStore, maximum: usize) -> Result<Self> {
        let mut writer = HashRunWriter::new(scratch, "store-hashes")?;
        let visited = store.visit_hashes_sorted(maximum, |hash| writer.push(hash))?;
        let run = writer.finish()?;
        if run.count != visited {
            bail!("streamed surface object index count changed while taking its GC snapshot");
        }
        Ok(run)
    }

    fn from_references(run: &ReferenceRun) -> Result<Self> {
        let mut writer = HashRunWriter::new(run.scratch.clone(), "reference-hashes")?;
        run.for_each(|reference| writer.push(reference.hash))?;
        writer.finish()
    }

    fn union(&self, other: &Self) -> Result<Self> {
        merge_hash_runs(self, other, false)
    }

    fn difference(&self, removed: &Self) -> Result<Self> {
        merge_hash_runs(self, removed, true)
    }

    fn equals_store(&self, store: &PackStore, maximum: usize) -> Result<bool> {
        let mut cursor = HashCursor::new(self)?;
        let mut equal = true;
        let count = store.visit_hashes_sorted(maximum, |hash| {
            if cursor.next()? != Some(hash) {
                equal = false;
            }
            Ok(())
        })?;
        Ok(equal && count == self.count && cursor.next()?.is_none())
    }
}

struct HashCursor {
    reader: BufReader<File>,
    remaining: usize,
}

impl HashCursor {
    fn new(run: &SortedHashes) -> Result<Self> {
        Ok(Self {
            reader: BufReader::new(File::open(&run.path)?),
            remaining: run.count,
        })
    }

    fn next(&mut self) -> Result<Option<ObjectHash>> {
        if self.remaining == 0 {
            return Ok(None);
        }
        let mut bytes = [0u8; 32];
        self.reader.read_exact(&mut bytes)?;
        self.remaining -= 1;
        Ok(Some(ObjectHash::from_bytes(bytes)?))
    }
}

struct HashRunWriter {
    scratch: Arc<GcScratch>,
    path: PathBuf,
    writer: BufWriter<File>,
    previous: Option<ObjectHash>,
    count: usize,
}

impl HashRunWriter {
    fn new(scratch: Arc<GcScratch>, label: &str) -> Result<Self> {
        let path = scratch.path(label);
        Ok(Self {
            scratch,
            writer: BufWriter::new(File::create(&path)?),
            path,
            previous: None,
            count: 0,
        })
    }

    fn push(&mut self, hash: ObjectHash) -> Result<()> {
        if hash.is_zero() || self.previous.is_some_and(|previous| previous >= hash) {
            bail!("external GC hash run is not nonzero and strictly sorted");
        }
        self.writer.write_all(hash.as_bytes())?;
        self.previous = Some(hash);
        self.count += 1;
        Ok(())
    }

    fn finish(mut self) -> Result<SortedHashes> {
        self.writer.flush()?;
        self.writer.get_ref().sync_all()?;
        Ok(SortedHashes {
            scratch: self.scratch,
            path: self.path,
            count: self.count,
        })
    }
}

/// Merges two strictly sorted hash runs. In difference mode only entries from `left` which do
/// not occur in `right` are retained; otherwise the set union is produced.
fn merge_hash_runs(
    left: &SortedHashes,
    right: &SortedHashes,
    difference: bool,
) -> Result<SortedHashes> {
    let mut output = HashRunWriter::new(
        left.scratch.clone(),
        if difference {
            "difference-hashes"
        } else {
            "union-hashes"
        },
    )?;
    let mut left_cursor = HashCursor::new(left)?;
    let mut right_cursor = HashCursor::new(right)?;
    let mut left_next = left_cursor.next()?;
    let mut right_next = right_cursor.next()?;
    while let Some(left_hash) = left_next {
        while right_next.is_some_and(|right_hash| right_hash < left_hash) {
            if !difference {
                output.push(right_next.unwrap())?;
            }
            right_next = right_cursor.next()?;
        }
        match right_next {
            Some(right_hash) if right_hash == left_hash => {
                if !difference {
                    output.push(left_hash)?;
                }
                right_next = right_cursor.next()?;
            }
            _ => output.push(left_hash)?,
        }
        left_next = left_cursor.next()?;
    }
    if !difference {
        while let Some(hash) = right_next {
            output.push(hash)?;
            right_next = right_cursor.next()?;
        }
    }
    output.finish()
}

#[derive(Debug)]
struct ReferenceRun {
    scratch: Arc<GcScratch>,
    path: PathBuf,
    count: usize,
}

impl ReferenceRun {
    fn for_each(&self, mut visit: impl FnMut(PendingReference) -> Result<()>) -> Result<()> {
        let mut reader = BufReader::new(File::open(&self.path)?);
        let mut bytes = [0u8; REFERENCE_RECORD_BYTES];
        for _ in 0..self.count {
            reader.read_exact(&mut bytes)?;
            visit(decode_reference(&bytes)?)?;
        }
        Ok(())
    }
}

struct ReferenceSorter {
    scratch: Arc<GcScratch>,
    buffer: Vec<PendingReference>,
    runs: Vec<ReferenceRun>,
}

impl ReferenceSorter {
    fn new(scratch: Arc<GcScratch>) -> Self {
        Self {
            scratch,
            buffer: Vec::with_capacity(GC_SORT_BUFFER),
            runs: Vec::new(),
        }
    }

    fn push(&mut self, reference: PendingReference) -> Result<()> {
        self.buffer.push(reference);
        if self.buffer.len() == GC_SORT_BUFFER {
            self.flush()?;
        }
        Ok(())
    }

    fn flush(&mut self) -> Result<()> {
        if self.buffer.is_empty() {
            return Ok(());
        }
        self.buffer
            .sort_unstable_by_key(|reference| (reference.hash, reference.expected.code()));
        self.buffer.dedup_by_key(|reference| reference.hash);
        let path = self.scratch.path("references");
        let mut writer = BufWriter::new(File::create(&path)?);
        for reference in &self.buffer {
            writer.write_all(&encode_reference(*reference))?;
        }
        writer.flush()?;
        writer.get_ref().sync_all()?;
        self.runs.push(ReferenceRun {
            scratch: self.scratch.clone(),
            path,
            count: self.buffer.len(),
        });
        self.buffer.clear();
        Ok(())
    }

    fn finish(mut self) -> Result<ReferenceRun> {
        self.flush()?;
        if self.runs.is_empty() {
            let path = self.scratch.path("empty-references");
            File::create(&path)?.sync_all()?;
            return Ok(ReferenceRun {
                scratch: self.scratch,
                path,
                count: 0,
            });
        }
        while self.runs.len() > 1 {
            let mut merged = Vec::with_capacity(self.runs.len().div_ceil(2));
            let mut runs = self.runs.into_iter();
            while let Some(left) = runs.next() {
                match runs.next() {
                    Some(right) => merged.push(merge_reference_runs(left, right)?),
                    None => merged.push(left),
                }
            }
            self.runs = merged;
        }
        Ok(self.runs.pop().unwrap())
    }
}

fn merge_reference_runs(left: ReferenceRun, right: ReferenceRun) -> Result<ReferenceRun> {
    let path = left.scratch.path("merged-references");
    let mut left_reader = BufReader::new(File::open(&left.path)?);
    let mut right_reader = BufReader::new(File::open(&right.path)?);
    let mut left_next = read_reference(&mut left_reader)?;
    let mut right_next = read_reference(&mut right_reader)?;
    let mut previous = None;
    let mut count = 0usize;
    let mut writer = BufWriter::new(File::create(&path)?);
    while left_next.is_some() || right_next.is_some() {
        let selected = match (left_next, right_next) {
            (Some(left), Some(right)) if left.hash <= right.hash => {
                left_next = read_reference(&mut left_reader)?;
                left
            }
            (_, Some(right)) => {
                right_next = read_reference(&mut right_reader)?;
                right
            }
            (Some(left), None) => {
                left_next = read_reference(&mut left_reader)?;
                left
            }
            (None, None) => break,
        };
        if previous == Some(selected.hash) {
            continue;
        }
        writer.write_all(&encode_reference(selected))?;
        previous = Some(selected.hash);
        count += 1;
    }
    writer.flush()?;
    writer.get_ref().sync_all()?;
    let _ = fs::remove_file(left.path);
    let _ = fs::remove_file(right.path);
    Ok(ReferenceRun {
        scratch: left.scratch,
        path,
        count,
    })
}

fn read_reference(reader: &mut impl Read) -> Result<Option<PendingReference>> {
    let mut bytes = [0u8; REFERENCE_RECORD_BYTES];
    match reader.read(&mut bytes[..1]) {
        Ok(0) => Ok(None),
        Ok(1) => {
            reader
                .read_exact(&mut bytes[1..])
                .context("truncated external GC reference record")?;
            Ok(Some(decode_reference(&bytes)?))
        }
        Ok(_) => unreachable!("one-byte read returned more than one byte"),
        Err(error) => Err(error.into()),
    }
}

fn encode_reference(reference: PendingReference) -> [u8; REFERENCE_RECORD_BYTES] {
    let mut bytes = [0u8; REFERENCE_RECORD_BYTES];
    bytes[..32].copy_from_slice(reference.hash.as_bytes());
    bytes[32] = reference.expected.code();
    bytes
}

fn decode_reference(bytes: &[u8; REFERENCE_RECORD_BYTES]) -> Result<PendingReference> {
    if bytes[33..] != [0; 7] {
        bail!("GC reference run contains nonzero padding");
    }
    Ok(PendingReference {
        hash: ObjectHash::from_bytes(bytes[..32].try_into().unwrap())?,
        expected: ExpectedObject::from_code(bytes[32])?,
    })
}

/// Removes already-seen hashes from a sorted frontier and atomically produces the next complete
/// sorted seen set. Both outputs are streamed and bounded.
fn admit_frontier(
    frontier: ReferenceRun,
    seen: SortedHashes,
) -> Result<(ReferenceRun, SortedHashes)> {
    let fresh_path = frontier.scratch.path("fresh-references");
    let merged_path = frontier.scratch.path("reachable-hashes");
    let mut fresh_writer = BufWriter::new(File::create(&fresh_path)?);
    let mut merged_writer = BufWriter::new(File::create(&merged_path)?);
    let mut seen_reader = BufReader::new(File::open(&seen.path)?);
    let mut seen_bytes = [0u8; 32];
    let mut seen_left = seen.count;
    let mut seen_next = if seen_left == 0 {
        None
    } else {
        seen_reader.read_exact(&mut seen_bytes)?;
        seen_left -= 1;
        Some(ObjectHash::from_bytes(seen_bytes)?)
    };
    let mut fresh_count = 0usize;
    let mut merged_count = 0usize;
    frontier.for_each(|reference| {
        while seen_next.is_some_and(|hash| hash < reference.hash) {
            let hash = seen_next.take().unwrap();
            merged_writer.write_all(hash.as_bytes())?;
            merged_count += 1;
            seen_next = if seen_left == 0 {
                None
            } else {
                seen_reader.read_exact(&mut seen_bytes)?;
                seen_left -= 1;
                Some(ObjectHash::from_bytes(seen_bytes)?)
            };
        }
        if seen_next == Some(reference.hash) {
            merged_writer.write_all(reference.hash.as_bytes())?;
            merged_count += 1;
            seen_next = if seen_left == 0 {
                None
            } else {
                seen_reader.read_exact(&mut seen_bytes)?;
                seen_left -= 1;
                Some(ObjectHash::from_bytes(seen_bytes)?)
            };
        } else {
            fresh_writer.write_all(&encode_reference(reference))?;
            merged_writer.write_all(reference.hash.as_bytes())?;
            fresh_count += 1;
            merged_count += 1;
        }
        Ok(())
    })?;
    while let Some(hash) = seen_next {
        merged_writer.write_all(hash.as_bytes())?;
        merged_count += 1;
        seen_next = if seen_left == 0 {
            None
        } else {
            seen_reader.read_exact(&mut seen_bytes)?;
            seen_left -= 1;
            Some(ObjectHash::from_bytes(seen_bytes)?)
        };
    }
    fresh_writer.flush()?;
    merged_writer.flush()?;
    fresh_writer.get_ref().sync_all()?;
    merged_writer.get_ref().sync_all()?;
    let scratch = frontier.scratch.clone();
    let _ = fs::remove_file(frontier.path);
    let _ = fs::remove_file(seen.path);
    Ok((
        ReferenceRun {
            scratch: scratch.clone(),
            path: fresh_path,
            count: fresh_count,
        },
        SortedHashes {
            scratch,
            path: merged_path,
            count: merged_count,
        },
    ))
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ExpectedObject {
    Exact(ObjectKind),
    RootManifest,
    Content,
}

impl ExpectedObject {
    fn accepts(self, actual: ObjectKind) -> bool {
        match self {
            Self::Exact(expected) => expected == actual,
            Self::RootManifest => actual == ObjectKind::RootDirectory,
            Self::Content => matches!(
                actual,
                ObjectKind::ExteriorMicrotile
                    | ObjectKind::InteriorMicrotile
                    | ObjectKind::ComplexMicrotile
            ),
        }
    }

    fn code(self) -> u8 {
        match self {
            Self::Exact(kind) => kind as u8,
            Self::RootManifest => 0x80,
            Self::Content => 0x81,
        }
    }

    fn from_code(code: u8) -> Result<Self> {
        Ok(match code {
            0x80 => Self::RootManifest,
            0x81 => Self::Content,
            value => Self::Exact(ObjectKind::try_from(value)?),
        })
    }
}

#[derive(Clone, Copy, Debug)]
struct PendingReference {
    hash: ObjectHash,
    expected: ExpectedObject,
}

struct Marker<'a> {
    store: &'a PackStore,
    memory: &'a MemoryPermit,
    policy: &'a GcPolicy,
    scratch: Arc<GcScratch>,
    pending: Option<ReferenceSorter>,
    reachable: Option<SortedHashes>,
    report: MarkReport,
}

impl<'a> Marker<'a> {
    fn new(
        store: &'a PackStore,
        policy: &'a GcPolicy,
        scratch_root: &Path,
        memory: &'a MemoryPermit,
    ) -> Result<Self> {
        let scratch = GcScratch::create(scratch_root)?;
        Ok(Self {
            store,
            memory,
            policy,
            scratch: scratch.clone(),
            pending: Some(ReferenceSorter::new(scratch)),
            reachable: None,
            report: MarkReport::default(),
        })
    }

    fn add_roots(&mut self, pins: &GcPins) {
        let expected_dimension = pins.current.first().map(|root| root.dimension);
        for root in pins.iter() {
            if let Err(error) = root.validate() {
                self.issue(
                    None,
                    ReferenceIssueKind::Corrupt,
                    format!("invalid pinned root: {error:#}"),
                );
                continue;
            }
            if Some(root.dimension) != expected_dimension {
                self.issue(
                    None,
                    ReferenceIssueKind::Corrupt,
                    "pinned roots from different dimensions cannot share one GC history",
                );
                continue;
            }
            self.enqueue(root.root_manifest, ExpectedObject::RootManifest);
            self.enqueue(root.catalog, ExpectedObject::Exact(ObjectKind::Catalog));
            self.enqueue(
                root.dictionary_set,
                ExpectedObject::Exact(ObjectKind::DictionarySet),
            );
            self.enqueue(
                root.visibility,
                ExpectedObject::Exact(ObjectKind::VisibilityDirectory),
            );
        }
    }

    fn enqueue(&mut self, hash: ObjectHash, expected: ExpectedObject) {
        if hash.is_zero() {
            self.issue(
                None,
                ReferenceIssueKind::Corrupt,
                "reachable reference uses the reserved zero hash",
            );
            return;
        }
        if let Some(location) = self.store.location(hash)
            && !expected.accepts(location.kind)
        {
            self.issue(
                Some(hash),
                ReferenceIssueKind::WrongType,
                format!("reference expected {expected:?}, found {:?}", location.kind),
            );
        }
        let result = self
            .pending
            .as_mut()
            .context("GC reference sink is unavailable")
            .and_then(|pending| pending.push(PendingReference { hash, expected }));
        if let Err(error) = result {
            self.issue(
                Some(hash),
                ReferenceIssueKind::BoundExceeded,
                format!("cannot spool reachable reference: {error:#}"),
            );
        }
    }

    fn run(&mut self) {
        if let Err(error) = self.run_inner() {
            self.issue(
                None,
                ReferenceIssueKind::BoundExceeded,
                format!("external GC traversal failed safely: {error:#}"),
            );
        }
    }

    fn run_inner(&mut self) -> Result<()> {
        let mut frontier = self
            .pending
            .take()
            .context("GC initial reference sink disappeared")?
            .finish()?;
        let mut reachable = SortedHashes::empty(self.scratch.clone())?;
        while frontier.count != 0 && !self.report.needs_regeneration {
            let (fresh, next_reachable) = admit_frontier(frontier, reachable)?;
            reachable = next_reachable;
            if reachable.count > self.policy.max_objects {
                self.issue(
                    None,
                    ReferenceIssueKind::BoundExceeded,
                    "reachable closure exceeds the configured object bound",
                );
                break;
            }
            self.pending = Some(ReferenceSorter::new(self.scratch.clone()));
            fresh.for_each(|reference| self.process_reference(reference))?;
            frontier = self
                .pending
                .take()
                .context("GC next reference sink disappeared")?
                .finish()?;
        }
        self.report.reachable_objects = reachable.count;
        self.reachable = Some(reachable);
        Ok(())
    }

    fn process_reference(&mut self, reference: PendingReference) -> Result<()> {
        let stored = match self.store.read_stored_scoped(reference.hash, self.memory) {
            Ok(Some(stored)) => stored,
            Ok(None) => {
                self.issue(
                    Some(reference.hash),
                    ReferenceIssueKind::Missing,
                    "pinned closure references a missing object",
                );
                return Ok(());
            }
            Err(error) => {
                self.issue(
                    Some(reference.hash),
                    ReferenceIssueKind::Corrupt,
                    format!("cannot read reachable object: {error:#}"),
                );
                return Ok(());
            }
        };
        if !reference.expected.accepts(stored.kind) {
            self.issue(
                Some(reference.hash),
                ReferenceIssueKind::WrongType,
                format!(
                    "reachable object expected {:?}, found {:?}",
                    reference.expected, stored.kind
                ),
            );
            return Ok(());
        }
        if !stored.dictionary.is_zero() {
            self.enqueue(
                stored.dictionary,
                ExpectedObject::Exact(ObjectKind::CompressionDictionary),
            );
        }
        let object = match self.store.get_scoped(reference.hash, self.memory) {
            Ok(Some(object)) => object,
            Ok(None) => {
                self.issue(
                    Some(reference.hash),
                    ReferenceIssueKind::Missing,
                    "reachable object disappeared while resolving its dictionary",
                );
                return Ok(());
            }
            Err(error) => {
                self.issue(
                    Some(reference.hash),
                    ReferenceIssueKind::Corrupt,
                    format!("cannot verify reachable object: {error:#}"),
                );
                return Ok(());
            }
        };
        self.follow(&object);
        Ok(())
    }

    fn follow(&mut self, object: &CanonicalObject) {
        match object.kind() {
            ObjectKind::RootDirectory => match RootDirectory::decode(object.bytes()) {
                Ok(directory) => {
                    for entry in directory.entries {
                        let kind = match entry.target {
                            super::manifest::DirectoryTarget::ManifestSubtree => {
                                ObjectKind::ManifestSubtree
                            }
                            super::manifest::DirectoryTarget::RootDirectory => {
                                ObjectKind::RootDirectory
                            }
                        };
                        self.enqueue(entry.hash, ExpectedObject::Exact(kind));
                    }
                }
                Err(error) => self.issue(
                    Some(object.hash()),
                    ReferenceIssueKind::Corrupt,
                    format!("cannot decode root directory: {error:#}"),
                ),
            },
            ObjectKind::ManifestSubtree => match ManifestSubtree::decode(object.bytes()) {
                Ok(manifest) => {
                    for page in manifest.descriptor_pages {
                        self.enqueue(
                            page,
                            ExpectedObject::Exact(ObjectKind::ManifestDescriptorPage),
                        );
                    }
                }
                Err(error) => self.issue(
                    Some(object.hash()),
                    ReferenceIssueKind::Corrupt,
                    format!("cannot decode manifest subtree: {error:#}"),
                ),
            },
            ObjectKind::ManifestDescriptorPage => {
                match ManifestDescriptorPage::decode(object.bytes()) {
                    Ok(page) => {
                        for contents in page.contents {
                            for class in ContentClass::ALL {
                                let Some(content) = &contents[class.index()] else {
                                    continue;
                                };
                                let expected = ExpectedObject::Exact(content_kind(class));
                                for &hash in &content.objects {
                                    self.enqueue(hash, expected);
                                }
                                for &hash in &content.dependencies {
                                    self.enqueue(hash, expected);
                                }
                                for &hash in content.neighbor_dependencies.iter().flatten() {
                                    self.enqueue(hash, ExpectedObject::Content);
                                }
                            }
                        }
                    }
                    Err(error) => self.issue(
                        Some(object.hash()),
                        ReferenceIssueKind::Corrupt,
                        format!("cannot decode manifest descriptor page: {error:#}"),
                    ),
                }
            }
            ObjectKind::DictionarySet => match DictionarySet::decode(object.bytes()) {
                Ok(set) => {
                    for dictionary in set.dictionaries {
                        self.enqueue(
                            dictionary,
                            ExpectedObject::Exact(ObjectKind::CompressionDictionary),
                        );
                    }
                }
                Err(error) => self.issue(
                    Some(object.hash()),
                    ReferenceIssueKind::Corrupt,
                    format!("cannot decode dictionary set: {error:#}"),
                ),
            },
            ObjectKind::VisibilityDirectory => match object_references(object.bytes()) {
                Ok(references) => {
                    for page in references.pages {
                        self.enqueue(page, ExpectedObject::Exact(ObjectKind::VisibilityPage));
                    }
                    for page in references.summary_pages {
                        self.enqueue(
                            page,
                            ExpectedObject::Exact(ObjectKind::VisibilitySummaryPage),
                        );
                    }
                }
                Err(error) => self.issue(
                    Some(object.hash()),
                    ReferenceIssueKind::Corrupt,
                    format!("cannot decode visibility directory: {error:#}"),
                ),
            },
            ObjectKind::VisibilityPage => {}
            ObjectKind::VisibilitySummaryPage => match summary_page_references(object.bytes()) {
                Ok(references) => {
                    for source in references {
                        self.enqueue(source, ExpectedObject::Exact(ObjectKind::SourceMicrotile));
                    }
                }
                Err(error) => self.issue(
                    Some(object.hash()),
                    ReferenceIssueKind::Corrupt,
                    format!("cannot decode visibility summary references: {error:#}"),
                ),
            },
            ObjectKind::SourceMicrotile => {
                if let Err(error) = SourceMicrotile::decode(object.bytes()) {
                    self.issue(
                        Some(object.hash()),
                        ReferenceIssueKind::Corrupt,
                        format!("cannot decode source microtile: {error:#}"),
                    );
                }
            }
            ObjectKind::Catalog => {
                if let Err(error) = Catalog::decode(object.bytes()) {
                    self.issue(
                        Some(object.hash()),
                        ReferenceIssueKind::Corrupt,
                        format!("cannot decode canonical catalog: {error:#}"),
                    );
                }
            }
            _ => {}
        }
    }

    fn issue(
        &mut self,
        hash: Option<ObjectHash>,
        kind: ReferenceIssueKind,
        detail: impl Into<String>,
    ) {
        marker_issue(
            &mut self.report,
            self.policy.max_reported_issues,
            hash,
            kind,
            detail,
        );
    }

    fn finish(mut self) -> Result<(SortedHashes, MarkReport)> {
        let reachable = match self.reachable.take() {
            Some(reachable) => reachable,
            None => SortedHashes::empty(self.scratch.clone())?,
        };
        Ok((reachable, self.report))
    }
}

fn marker_issue(
    report: &mut MarkReport,
    maximum: usize,
    hash: Option<ObjectHash>,
    kind: ReferenceIssueKind,
    detail: impl Into<String>,
) {
    report.needs_regeneration = true;
    if report.issues.len() < maximum {
        report.issues.push(ReferenceIssue {
            hash,
            kind,
            detail: detail.into(),
        });
    } else {
        report.issue_overflow = true;
    }
}

fn failed_after_mark(
    mark: MarkReport,
    cycle: u64,
    active_objects: usize,
    grace_retained_objects: usize,
) -> GcRunReport {
    GcRunReport {
        mark,
        successful_mark_cycle: Some(cycle),
        active_objects_before: active_objects,
        retained_objects: active_objects,
        grace_retained_objects,
        reclaimed_objects: 0,
        copied_objects: 0,
        switched: false,
        old_set_retired: false,
    }
}

fn validate_pins(pins: &GcPins, maximum: usize) -> Result<()> {
    if pins.current.is_empty() {
        bail!("surface GC requires at least one current root");
    }
    if pins.len() > maximum {
        bail!(
            "surface GC has {} pinned roots, exceeding {maximum}",
            pins.len()
        );
    }
    Ok(())
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct Absence {
    first_cycle: u64,
    first_seconds: u64,
    first_generation: u64,
    successful_marks: u32,
}

#[derive(Clone, Debug)]
struct MarkHistory {
    cycle: u64,
    _last_success_seconds: u64,
    count: usize,
    path: PathBuf,
}

#[derive(Debug)]
struct AbsenceRun {
    _scratch: Arc<GcScratch>,
    path: PathBuf,
    count: usize,
}

struct AbsenceRunWriter {
    scratch: Arc<GcScratch>,
    path: PathBuf,
    writer: BufWriter<File>,
    previous: Option<ObjectHash>,
    count: usize,
}

impl AbsenceRunWriter {
    fn new(scratch: Arc<GcScratch>) -> Result<Self> {
        let path = scratch.path("absence-history");
        Ok(Self {
            scratch,
            writer: BufWriter::new(File::create(&path)?),
            path,
            previous: None,
            count: 0,
        })
    }

    fn push(&mut self, hash: ObjectHash, absence: Absence) -> Result<()> {
        if hash.is_zero() || self.previous.is_some_and(|previous| previous >= hash) {
            bail!("external GC absence run is not nonzero and strictly sorted");
        }
        self.writer
            .write_all(&encode_absence_entry(hash, absence))?;
        self.previous = Some(hash);
        self.count += 1;
        Ok(())
    }

    fn finish(mut self) -> Result<AbsenceRun> {
        self.writer.flush()?;
        self.writer.get_ref().sync_all()?;
        Ok(AbsenceRun {
            _scratch: self.scratch,
            path: self.path,
            count: self.count,
        })
    }
}

struct MarkHistoryCursor {
    reader: Option<BufReader<File>>,
    remaining: usize,
}

impl MarkHistoryCursor {
    fn new(history: Option<&MarkHistory>) -> Result<Self> {
        let Some(history) = history else {
            return Ok(Self {
                reader: None,
                remaining: 0,
            });
        };
        let mut reader = BufReader::new(File::open(&history.path)?);
        let mut header = [0u8; MARK_HEADER_BYTES];
        reader.read_exact(&mut header)?;
        Ok(Self {
            reader: Some(reader),
            remaining: history.count,
        })
    }

    fn next(&mut self) -> Result<Option<(ObjectHash, Absence)>> {
        if self.remaining == 0 {
            return Ok(None);
        }
        let mut bytes = [0u8; MARK_ENTRY_BYTES];
        self.reader
            .as_mut()
            .context("GC history reader disappeared")?
            .read_exact(&mut bytes)?;
        self.remaining -= 1;
        decode_absence_entry(&bytes).map(Some)
    }
}

fn encode_absence_entry(hash: ObjectHash, absence: Absence) -> [u8; MARK_ENTRY_BYTES] {
    let mut bytes = [0u8; MARK_ENTRY_BYTES];
    bytes[..32].copy_from_slice(hash.as_bytes());
    bytes[32..40].copy_from_slice(&absence.first_cycle.to_le_bytes());
    bytes[40..48].copy_from_slice(&absence.first_seconds.to_le_bytes());
    bytes[48..56].copy_from_slice(&absence.first_generation.to_le_bytes());
    bytes[56..60].copy_from_slice(&absence.successful_marks.to_le_bytes());
    bytes
}

fn decode_absence_entry(bytes: &[u8; MARK_ENTRY_BYTES]) -> Result<(ObjectHash, Absence)> {
    if bytes[60..64] != [0; 4] {
        bail!("surface GC history entry has nonzero reserved bytes");
    }
    let hash = ObjectHash::from_bytes(bytes[..32].try_into().unwrap())?;
    let absence = Absence {
        first_cycle: u64::from_le_bytes(bytes[32..40].try_into().unwrap()),
        first_seconds: u64::from_le_bytes(bytes[40..48].try_into().unwrap()),
        first_generation: u64::from_le_bytes(bytes[48..56].try_into().unwrap()),
        successful_marks: u32::from_le_bytes(bytes[56..60].try_into().unwrap()),
    };
    if absence.first_cycle == 0 || absence.successful_marks == 0 {
        bail!("surface GC history entry has an impossible absence state");
    }
    Ok((hash, absence))
}

fn mark_header(cycle: u64, seconds: u64, count: usize) -> Result<[u8; MARK_HEADER_BYTES]> {
    let count = u32::try_from(count).context("surface GC history entry count overflow")?;
    let mut bytes = [0u8; MARK_HEADER_BYTES];
    bytes[..8].copy_from_slice(MARK_MAGIC);
    bytes[12..20].copy_from_slice(&cycle.to_le_bytes());
    bytes[20..28].copy_from_slice(&seconds.to_le_bytes());
    bytes[28..32].copy_from_slice(&count.to_le_bytes());
    Ok(bytes)
}

fn read_mark_history(path: &Path, maximum: usize) -> Result<Option<MarkHistory>> {
    if !path.exists() {
        return Ok(None);
    }
    let file = File::open(path)?;
    let length =
        usize::try_from(file.metadata()?.len()).context("surface GC history length overflow")?;
    let mut reader = BufReader::new(file);
    let mut header = [0u8; MARK_HEADER_BYTES];
    reader.read_exact(&mut header)?;
    if &header[..8] != MARK_MAGIC || header[8..12] != [0; 4] || header[32..36] != [0; 4] {
        bail!("invalid surface GC history envelope");
    }
    let cycle = u64::from_le_bytes(header[12..20].try_into().unwrap());
    let last_success_seconds = u64::from_le_bytes(header[20..28].try_into().unwrap());
    let count = u32::from_le_bytes(header[28..32].try_into().unwrap()) as usize;
    let expected = count
        .checked_mul(MARK_ENTRY_BYTES)
        .and_then(|bytes| bytes.checked_add(MARK_HEADER_BYTES + MARK_TRAILER_BYTES))
        .context("surface GC history size overflow")?;
    if cycle == 0 || count > maximum || length != expected {
        bail!("invalid surface GC history entry count or cycle");
    }
    let mut crc = crc32c(&header);
    let mut previous = None;
    for _ in 0..count {
        let mut bytes = [0u8; MARK_ENTRY_BYTES];
        reader.read_exact(&mut bytes)?;
        crc = crc32c_append(crc, &bytes);
        let (hash, absence) = decode_absence_entry(&bytes)?;
        if previous.is_some_and(|previous| previous >= hash)
            || absence.first_cycle > cycle
            || u64::from(absence.successful_marks) > cycle - absence.first_cycle + 1
        {
            bail!("invalid, unordered, or impossible surface GC history entry");
        }
        previous = Some(hash);
    }
    let mut trailer = [0u8; MARK_TRAILER_BYTES];
    reader.read_exact(&mut trailer)?;
    if u32::from_le_bytes(trailer) != crc {
        bail!("surface GC history checksum mismatch");
    }
    Ok(Some(MarkHistory {
        cycle,
        _last_success_seconds: last_success_seconds,
        count,
        path: path.to_path_buf(),
    }))
}

fn write_mark_history(
    path: &Path,
    cycle: u64,
    last_success_seconds: u64,
    absences: &AbsenceRun,
    maximum: usize,
) -> Result<()> {
    if cycle == 0 || absences.count > maximum {
        bail!("surface GC history exceeds its configured bound or has a zero cycle");
    }
    let temporary = path.with_extension("marks.tmp");
    let header = mark_header(cycle, last_success_seconds, absences.count)?;
    let mut output = BufWriter::new(File::create(&temporary)?);
    output.write_all(&header)?;
    let mut crc = crc32c(&header);
    let mut input = BufReader::new(File::open(&absences.path)?);
    let mut bytes = [0u8; MARK_ENTRY_BYTES];
    for _ in 0..absences.count {
        input.read_exact(&mut bytes)?;
        output.write_all(&bytes)?;
        crc = crc32c_append(crc, &bytes);
    }
    output.write_all(&crc.to_le_bytes())?;
    output.flush()?;
    output.get_ref().sync_all()?;
    drop(output);
    fs::rename(&temporary, path)?;
    sync_parent(path)
}

struct GcClassification {
    retained: SortedHashes,
    reclaim: SortedHashes,
    absences: AbsenceRun,
    cycle: u64,
    grace_retained: usize,
}

fn classify_objects(
    store: &PackStore,
    all: &SortedHashes,
    reachable: &SortedHashes,
    history: Option<&MarkHistory>,
    moment: GcMoment,
    maximum_generation: u64,
    policy: GcPolicy,
) -> Result<GcClassification> {
    let cycle = history
        .map_or(0, |history| history.cycle)
        .checked_add(1)
        .context("surface GC cycle overflow")?;
    let mut old = MarkHistoryCursor::new(history)?;
    let mut old_next = old.next()?;
    let mut reachable_cursor = HashCursor::new(reachable)?;
    let mut reachable_next = reachable_cursor.next()?;
    let mut retained = HashRunWriter::new(all.scratch.clone(), "retained-base")?;
    let mut absences = AbsenceRunWriter::new(all.scratch.clone())?;
    let mut all_cursor = HashCursor::new(all)?;
    let mut grace_retained = 0usize;
    while let Some(hash) = all_cursor.next()? {
        while old_next.is_some_and(|(old_hash, _)| old_hash < hash) {
            old_next = old.next()?;
        }
        while reachable_next.is_some_and(|reachable_hash| reachable_hash < hash) {
            bail!("reachable GC closure contains a hash absent from the active object index");
        }
        if reachable_next == Some(hash) {
            retained.push(hash)?;
            reachable_next = reachable_cursor.next()?;
            if old_next.is_some_and(|(old_hash, _)| old_hash == hash) {
                old_next = old.next()?;
            }
            continue;
        }

        let absence = if old_next.is_some_and(|(old_hash, _)| old_hash == hash) {
            let (_, mut absence) = old_next.take().unwrap();
            absence.successful_marks = absence
                .successful_marks
                .checked_add(1)
                .context("surface GC absent-mark overflow")?;
            old_next = old.next()?;
            absence
        } else {
            Absence {
                first_cycle: cycle,
                first_seconds: moment.unix_seconds,
                first_generation: maximum_generation,
                successful_marks: 1,
            }
        };
        let marks_ready = absence.successful_marks >= policy.successful_absent_marks;
        let time_ready =
            moment.unix_seconds.saturating_sub(absence.first_seconds) >= policy.grace_seconds;
        let generation_ready =
            maximum_generation.saturating_sub(absence.first_generation) >= policy.grace_generations;
        if marks_ready && time_ready && generation_ready {
            continue;
        }
        retained.push(hash)?;
        absences.push(hash, absence)?;
        grace_retained += 1;
    }
    if reachable_next.is_some() {
        bail!("reachable GC closure extends beyond the active object index");
    }
    let retained = retained.finish()?;
    let absences = absences.finish()?;

    // Physical dictionary dependencies of grace-retained records are part of the retained set
    // even when the logical root graph no longer reaches either object. Dictionary objects are
    // themselves required to be uncompressed, so this closure is exactly one bounded pass.
    let mut dictionaries = ReferenceSorter::new(all.scratch.clone());
    retained.for_each(|hash| {
        let location = store
            .location(hash)
            .with_context(|| format!("retained object {hash} disappeared from the active index"))?;
        if location.dictionary.is_zero() {
            return Ok(());
        }
        let dictionary = store
            .location(location.dictionary)
            .with_context(|| format!("retained object {hash} lost its compression dictionary"))?;
        if dictionary.kind != ObjectKind::CompressionDictionary || !dictionary.dictionary.is_zero()
        {
            bail!("retained object has an invalid transitive compression dependency");
        }
        dictionaries.push(PendingReference {
            hash: location.dictionary,
            expected: ExpectedObject::Exact(ObjectKind::CompressionDictionary),
        })
    })?;
    let dictionaries = ReferenceSorter::finish(dictionaries)?;
    let dictionaries = SortedHashes::from_references(&dictionaries)?;
    let retained = retained.union(&dictionaries)?;
    let reclaim = all.difference(&retained)?;
    Ok(GcClassification {
        retained,
        reclaim,
        absences,
        cycle,
        grace_retained,
    })
}

fn for_each_hash_difference(
    left: &SortedHashes,
    removed: &SortedHashes,
    mut visit: impl FnMut(ObjectHash) -> Result<()>,
) -> Result<()> {
    let mut left_cursor = HashCursor::new(left)?;
    let mut removed_cursor = HashCursor::new(removed)?;
    let mut removed_next = removed_cursor.next()?;
    while let Some(hash) = left_cursor.next()? {
        while removed_next.is_some_and(|removed_hash| removed_hash < hash) {
            removed_next = removed_cursor.next()?;
        }
        if removed_next == Some(hash) {
            removed_next = removed_cursor.next()?;
        } else {
            visit(hash)?;
        }
    }
    Ok(())
}

fn identity_for(id: u64, store: &PackStore, maximum: usize) -> Result<SetIdentity> {
    let expected_count = store.len();
    if expected_count > maximum {
        bail!("surface pack-set identity exceeds its configured object bound");
    }
    let mut hasher = blake3::Hasher::new();
    hasher.update(b"Voxy pack-set identity\0");
    hasher.update(&(expected_count as u64).to_le_bytes());
    let count = store.visit_hashes_sorted(maximum, |hash| {
        hasher.update(hash.as_bytes());
        Ok(())
    })?;
    if count != expected_count {
        bail!("surface pack-set changed while computing its durable identity");
    }
    Ok(SetIdentity {
        id,
        object_count: count as u64,
        digest: *hasher.finalize().as_bytes(),
    })
}

fn encode_identity(magic: &[u8; 8], identity: SetIdentity) -> [u8; SET_RECORD_BYTES] {
    let mut bytes = [0u8; SET_RECORD_BYTES];
    bytes[..8].copy_from_slice(magic);
    bytes[12..20].copy_from_slice(&identity.id.to_le_bytes());
    bytes[20..28].copy_from_slice(&identity.object_count.to_le_bytes());
    bytes[28..60].copy_from_slice(&identity.digest);
    let crc = crc32c(&bytes[..60]);
    bytes[60..64].copy_from_slice(&crc.to_le_bytes());
    bytes
}

fn decode_identity(bytes: &[u8], magic: &[u8; 8]) -> Result<SetIdentity> {
    if bytes.len() != SET_RECORD_BYTES
        || &bytes[..8] != magic
        || bytes[8..12] != [0; 4]
        || u32::from_le_bytes(bytes[60..64].try_into().unwrap()) != crc32c(&bytes[..60])
    {
        bail!("invalid surface pack-set identity envelope");
    }
    Ok(SetIdentity {
        id: u64::from_le_bytes(bytes[12..20].try_into().unwrap()),
        object_count: u64::from_le_bytes(bytes[20..28].try_into().unwrap()),
        digest: bytes[28..60].try_into().unwrap(),
    })
}

fn write_identity(path: &Path, magic: &[u8; 8], identity: SetIdentity) -> Result<()> {
    replace_synced(
        path,
        &path.with_extension("identity.tmp"),
        &encode_identity(magic, identity),
    )
}

fn read_optional_identity(path: &Path, magic: &[u8; 8]) -> Result<Option<SetIdentity>> {
    if !path.exists() {
        return Ok(None);
    }
    Ok(Some(decode_identity(
        &read_file_bounded(path, SET_RECORD_BYTES)?,
        magic,
    )?))
}

fn open_identity(
    sets: &Path,
    identity: SetIdentity,
    memory: Arc<ServerMemoryBudget>,
) -> Result<(PathBuf, PackStore)> {
    let path = resolve_set_path(sets, identity.id)
        .with_context(|| format!("surface pack set {} is unavailable", identity.id))?;
    let ready = read_optional_identity(&path.join("set.ready"), SET_READY_MAGIC)?
        .context("surface pack set has no durable ready marker")?;
    if ready != identity {
        bail!("surface pack-set pointer and ready marker disagree");
    }
    let store = PackStore::open_with_budget(&path, memory)?;
    // The activation snapshot remains stable even though the active set may append objects.
    // Checking exact members avoids accepting a set that lost one activated object merely
    // because later appends kept its total count above the marker's count.
    verify_set_members(&path, identity, &store, HARD_MAX_OBJECTS)?;
    Ok((path, store))
}

fn write_set_members(set: &Path, hashes: &[ObjectHash]) -> Result<()> {
    if hashes.len() > HARD_MAX_OBJECTS || hashes.windows(2).any(|pair| pair[0] >= pair[1]) {
        bail!("surface pack-set member list is oversized or not strictly sorted");
    }
    let mut index = 0usize;
    write_set_members_from(set, hashes.len(), || {
        let value = hashes.get(index).copied();
        index += usize::from(value.is_some());
        Ok(value)
    })
}

fn write_set_members_run(set: &Path, hashes: &SortedHashes) -> Result<()> {
    let mut cursor = HashCursor::new(hashes)?;
    write_set_members_from(set, hashes.count, || cursor.next())
}

fn write_set_members_from(
    set: &Path,
    count: usize,
    mut next: impl FnMut() -> Result<Option<ObjectHash>>,
) -> Result<()> {
    if count > HARD_MAX_OBJECTS {
        bail!("surface pack-set member list exceeds its hard bound");
    }
    let count_u32 = u32::try_from(count).context("surface pack-set member count overflow")?;
    let mut header = [0u8; SET_MEMBERS_HEADER];
    header[..8].copy_from_slice(SET_MEMBERS_MAGIC);
    header[12..16].copy_from_slice(&count_u32.to_le_bytes());
    let path = set.join("set.members");
    let temporary = set.join("set.members.tmp");
    let mut output = BufWriter::new(File::create(&temporary)?);
    output.write_all(&header)?;
    let mut crc = crc32c(&header);
    let mut previous = None;
    for _ in 0..count {
        let hash = next()?.context("surface pack-set member iterator ended early")?;
        if hash.is_zero() || previous.is_some_and(|previous| previous >= hash) {
            bail!("surface pack-set members are not nonzero and strictly sorted");
        }
        output.write_all(hash.as_bytes())?;
        crc = crc32c_append(crc, hash.as_bytes());
        previous = Some(hash);
    }
    if next()?.is_some() {
        bail!("surface pack-set member iterator exceeded its declared count");
    }
    output.write_all(&crc.to_le_bytes())?;
    output.flush()?;
    output.get_ref().sync_all()?;
    drop(output);
    fs::rename(&temporary, &path)?;
    sync_parent(&path)
}

fn verify_set_members(
    set: &Path,
    identity: SetIdentity,
    store: &PackStore,
    maximum: usize,
) -> Result<()> {
    let count =
        usize::try_from(identity.object_count).context("surface pack-set member count overflow")?;
    if count > maximum {
        bail!("surface pack-set member count exceeds its bound");
    }
    let expected_size = count
        .checked_mul(32)
        .and_then(|bytes| bytes.checked_add(SET_MEMBERS_HEADER + SET_MEMBERS_TRAILER))
        .context("surface pack-set member size overflow")?;
    let file = File::open(set.join("set.members"))?;
    if usize::try_from(file.metadata()?.len()).context("member file length overflow")?
        != expected_size
    {
        bail!("invalid surface pack-set member length");
    }
    let mut input = BufReader::new(file);
    let mut header = [0u8; SET_MEMBERS_HEADER];
    input.read_exact(&mut header)?;
    if &header[..8] != SET_MEMBERS_MAGIC
        || header[8..12] != [0; 4]
        || u32::from_le_bytes(header[12..16].try_into().unwrap()) as usize != count
    {
        bail!("invalid surface pack-set member envelope");
    }
    let mut crc = crc32c(&header);
    let mut digest = blake3::Hasher::new();
    digest.update(b"Voxy pack-set identity\0");
    digest.update(&identity.object_count.to_le_bytes());
    let mut previous = None;
    for _ in 0..count {
        let mut bytes = [0u8; 32];
        input.read_exact(&mut bytes)?;
        let hash = ObjectHash::from_bytes(bytes)?;
        if previous.is_some_and(|previous| previous >= hash) {
            bail!("surface pack-set members are not strictly sorted");
        }
        if !store.contains(hash) {
            bail!("surface pack set lost activated object {hash}");
        }
        crc = crc32c_append(crc, &bytes);
        digest.update(&bytes);
        previous = Some(hash);
    }
    let mut trailer = [0u8; SET_MEMBERS_TRAILER];
    input.read_exact(&mut trailer)?;
    if u32::from_le_bytes(trailer) != crc || *digest.finalize().as_bytes() != identity.digest {
        bail!("surface pack-set member checksum or ready digest disagrees");
    }
    Ok(())
}

fn set_path(sets: &Path, id: u64) -> PathBuf {
    sets.join(format!("set-{id:020}"))
}

fn retired_path(sets: &Path, id: u64) -> PathBuf {
    sets.join(format!("retired-{id:020}"))
}

fn resolve_set_path(sets: &Path, id: u64) -> Option<PathBuf> {
    let active = set_path(sets, id);
    if active.is_dir() {
        return Some(active);
    }
    let retired = retired_path(sets, id);
    retired.is_dir().then_some(retired)
}

fn resolves_to(sets: &Path, identity: SetIdentity, candidate: &Path) -> bool {
    resolve_set_path(sets, identity.id).as_deref() == Some(candidate)
}

fn parse_set_id(path: &Path) -> Option<u64> {
    let name = path.file_name()?.to_str()?;
    let encoded = name
        .strip_prefix("set-")
        .or_else(|| name.strip_prefix("retired-"))?;
    (encoded.len() == 20)
        .then(|| encoded.parse::<u64>().ok())
        .flatten()
}

fn set_directories(sets: &Path, maximum: usize) -> Result<Vec<PathBuf>> {
    let mut paths = Vec::new();
    for entry in fs::read_dir(sets)? {
        let path = entry?.path();
        if path.is_dir() && parse_set_id(&path).is_some() {
            if paths.len() == maximum {
                bail!("surface pack-set directory count exceeds its bound");
            }
            paths.push(path);
        }
    }
    paths.sort();
    Ok(paths)
}

fn next_set_id(sets: &Path) -> Result<u64> {
    let maximum = set_directories(sets, HARD_MAX_OBJECTS)?
        .iter()
        .filter_map(|path| parse_set_id(path))
        .max();
    maximum.map_or(Ok(0), |id| {
        id.checked_add(1).context("surface pack-set ID overflow")
    })
}
