use crate::{
    FORMAT_VERSION,
    crc::crc32c,
    key::{SectionKey, ShardId},
    lod::{Section, network_body_from_encoded},
    read_file_bounded,
};
use anyhow::{Context, Result, bail};
use std::{
    collections::{BTreeMap, HashMap},
    fs::{self, File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex, RwLock,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{SystemTime, UNIX_EPOCH},
};

const FILE_MAGIC: &[u8; 8] = b"VXYSHD2\0";
const INDEX_MAGIC: &[u8; 8] = b"VXYIDX2\0";
const STORE_MAGIC: &[u8; 8] = b"VXYSTO2\0";
const MANIFEST_MAGIC: &[u8; 8] = b"VXYMNF2\0";
const REPAIR_MAGIC: &[u8; 8] = b"VXYRPR1\0";
const RECORD_MAGIC: &[u8; 4] = b"VXR2";
const FILE_HEADER: u64 = 48;
const RECORD_HEADER: usize = 40;
const MAX_RECORD_PAYLOAD: usize = 16 * 1024 * 1024;
const KIND_INVALIDATE: u8 = 1;
const KIND_PUT: u8 = 2;
const KIND_COMMIT: u8 = 3;
const MAX_RECOVERY_ENTRIES: usize = 1_500_000;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct EntryMeta {
    pub key: u64,
    pub revision: u64,
    offset: u64,
    length: u32,
    payload_crc: u32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Invalidation {
    pub key: u64,
    pub revision: u64,
    pub reason: u8,
}

#[derive(Debug)]
pub struct StoredSection {
    pub section: Section,
    pub meta: EntryMeta,
}

#[derive(Debug)]
pub enum NetworkItem {
    Section(Vec<u8>),
    Invalidate(Invalidation),
}

#[derive(Debug)]
pub struct Store {
    root: PathBuf,
    identity_path: PathBuf,
    manifest_path: PathBuf,
    catalog_id: u64,
    epoch: AtomicU64,
    shards: RwLock<HashMap<ShardId, Arc<Mutex<Shard>>>>,
    columns: RwLock<HashMap<(i32, i32), Vec<i32>>>,
    manifest_lock: Mutex<()>,
    manifest_dirty: AtomicBool,
}

#[derive(Debug)]
struct Shard {
    id: ShardId,
    path: PathBuf,
    index_path: PathBuf,
    file: File,
    catalog_id: u64,
    incarnation: u64,
    generation: u64,
    index: HashMap<u64, EntryMeta>,
    revisions: HashMap<u64, u64>,
    log_len: u64,
    degraded: bool,
    dirty: bool,
    recovery_invalidations: Vec<Invalidation>,
    transactions_since_checkpoint: u32,
    destructive_reset: bool,
}

impl Store {
    pub fn open(root: impl AsRef<Path>, catalog_id: u64) -> Result<Self> {
        let base = root.as_ref();
        fs::create_dir_all(base).with_context(|| format!("create {}", base.display()))?;
        let identity_path = base.join("store.identity");
        let previous = read_store_identity(&identity_path).ok().flatten();
        let identity_matches = previous.is_some_and(|(stored, _)| stored == catalog_id);
        let manifest_path = base.join("store.shards");
        let expected = if identity_matches {
            match read_shard_manifest(&manifest_path, catalog_id) {
                Ok(value) => value,
                Err(error) => {
                    eprintln!(
                        "damaged store manifest {}: {error:#}",
                        manifest_path.display()
                    );
                    quarantine(&manifest_path);
                    None
                }
            }
        } else {
            None
        };
        let root = base.join(format!("catalog-{catalog_id:016x}"));
        let catalog_existed = root.is_dir();
        fs::create_dir_all(&root).with_context(|| format!("create {}", root.display()))?;
        let mut shards = HashMap::<ShardId, Arc<Mutex<Shard>>>::new();
        let mut unreadable = false;
        let mut recovered_damage = false;
        for item in fs::read_dir(&root)? {
            let path = item?.path();
            if path.extension().and_then(|value| value.to_str()) != Some("vxlog") {
                continue;
            }
            match Shard::open_existing(path.clone(), catalog_id) {
                Ok(mut shard) => {
                    recovered_damage |= shard.degraded || shard.destructive_reset;
                    shard.destructive_reset = false;
                    if path.file_name().and_then(|name| name.to_str())
                        != Some(shard.id.filename().as_str())
                    {
                        eprintln!(
                            "quarantining shard whose header does not match its filename: {}",
                            path.display()
                        );
                        quarantine(&path);
                        quarantine(&path.with_extension("vxidx"));
                        quarantine(&path.with_extension("vxrepair"));
                        unreadable = true;
                        continue;
                    }
                    if let Some(existing) = shards.remove(&shard.id) {
                        let existing_path = lock(&existing)?.path.clone();
                        eprintln!("quarantining duplicate shard identity {:?}", shard.id);
                        quarantine(&existing_path);
                        quarantine(&existing_path.with_extension("vxidx"));
                        quarantine(&existing_path.with_extension("vxrepair"));
                        quarantine(&path);
                        quarantine(&path.with_extension("vxidx"));
                        quarantine(&path.with_extension("vxrepair"));
                        unreadable = true;
                        continue;
                    }
                    shards.insert(shard.id, Arc::new(Mutex::new(shard)));
                }
                Err(error) => {
                    unreadable = true;
                    eprintln!(
                        "quarantining unreadable shard {}: {error:#}",
                        path.display()
                    );
                    quarantine(&path);
                    quarantine(&path.with_extension("vxidx"));
                    quarantine(&path.with_extension("vxrepair"));
                }
            }
        }
        let mut columns = HashMap::<(i32, i32), Vec<i32>>::new();
        for id in shards.keys() {
            columns.entry((id.x, id.z)).or_default().push(id.y);
        }
        for values in columns.values_mut() {
            values.sort_unstable();
            values.dedup();
        }
        let actual = shards
            .keys()
            .copied()
            .collect::<std::collections::BTreeSet<_>>();
        let lost_expected = expected
            .as_ref()
            .is_some_and(|expected| !expected.is_subset(&actual));
        let identity_must_change = !identity_matches
            || !catalog_existed
            || expected.is_none()
            || lost_expected
            || unreadable
            || recovered_damage;
        let epoch = if identity_must_change {
            new_incarnation()
        } else {
            previous.expect("matching identity exists").1
        };
        if identity_must_change {
            write_store_identity(&identity_path, catalog_id, epoch)?;
        }
        write_shard_manifest(&manifest_path, catalog_id, &actual)?;
        Ok(Self {
            root,
            identity_path,
            manifest_path,
            catalog_id,
            epoch: AtomicU64::new(epoch),
            shards: RwLock::new(shards),
            columns: RwLock::new(columns),
            manifest_lock: Mutex::new(()),
            manifest_dirty: AtomicBool::new(false),
        })
    }

    pub fn catalog_id(&self) -> u64 {
        self.catalog_id
    }

    pub fn epoch(&self) -> u64 {
        self.epoch.load(Ordering::Acquire)
    }

    pub fn invalidate_many(&self, keys: &[SectionKey], reason: u8) -> Result<Vec<Invalidation>> {
        self.invalidate_many_inner(keys, reason, true, false)
    }

    pub fn invalidate_many_deferred(
        &self,
        keys: &[SectionKey],
        reason: u8,
    ) -> Result<Vec<Invalidation>> {
        self.invalidate_many_inner(keys, reason, false, false)
    }

    /// Tombstones only keys that are currently visible. This is used after replacements have
    /// been fully built: an already-absent key must not acquire and broadcast a fresh tombstone
    /// every time one of its siblings changes.
    pub fn remove_many(&self, keys: &[SectionKey], reason: u8) -> Result<Vec<Invalidation>> {
        self.invalidate_many_inner(keys, reason, true, true)
    }

    pub fn remove_many_deferred(
        &self,
        keys: &[SectionKey],
        reason: u8,
    ) -> Result<Vec<Invalidation>> {
        self.invalidate_many_inner(keys, reason, false, true)
    }

    fn invalidate_many_inner(
        &self,
        keys: &[SectionKey],
        reason: u8,
        durable: bool,
        visible_only: bool,
    ) -> Result<Vec<Invalidation>> {
        let mut groups = BTreeMap::<ShardId, Vec<u64>>::new();
        for &key in keys {
            groups.entry(key.shard()).or_default().push(key.packed());
        }
        let mut out = Vec::with_capacity(keys.len());
        for (id, mut keys) in groups {
            keys.sort_unstable();
            keys.dedup();
            let Some(shard) = read_lock(&self.shards)?.get(&id).cloned() else {
                continue;
            };
            let mut shard = lock(&shard)?;
            if visible_only {
                keys.retain(|key| shard.index.contains_key(key));
            }
            out.extend(shard.invalidate(&keys, reason, durable)?);
        }
        Ok(out)
    }

    /// Invalidates one horizontal store column. Every LOD derived from one Anvil region stays
    /// in this column because level-0 groups span 2x2 chunks and the shard scale halves with
    /// every parent level. This is the recovery boundary for per-region source metadata.
    pub fn invalidate_column(&self, x: i32, z: i32, reason: u8) -> Result<Vec<Invalidation>> {
        let ids = read_lock(&self.columns)?
            .get(&(x, z))
            .cloned()
            .unwrap_or_default()
            .into_iter()
            .map(|y| ShardId { x, y, z })
            .collect::<Vec<_>>();
        let shards = {
            let map = read_lock(&self.shards)?;
            ids.into_iter()
                .filter_map(|id| map.get(&id).cloned())
                .collect::<Vec<_>>()
        };
        let mut out = Vec::new();
        for shard in shards {
            let mut shard = lock(&shard)?;
            let keys = shard.revisions.keys().copied().collect::<Vec<_>>();
            out.extend(shard.invalidate(&keys, reason, true)?);
        }
        Ok(out)
    }

    pub fn live_horizontal_columns(&self) -> Result<Vec<(i32, i32)>> {
        let columns = read_lock(&self.columns)?
            .iter()
            .map(|(&column, ys)| (column, ys.clone()))
            .collect::<Vec<_>>();
        // Clone handles under the global map lock and drop it before taking a shard mutex.
        // Recovery must obey the same lock order as view/get paths: a shard reset can acquire
        // manifest_lock, while shard creation holds manifest_lock before taking shards.write.
        let columns = {
            let shards = read_lock(&self.shards)?;
            columns
                .into_iter()
                .map(|(column, ys)| {
                    let column_shards = ys
                        .into_iter()
                        .filter_map(|y| {
                            shards
                                .get(&ShardId {
                                    x: column.0,
                                    y,
                                    z: column.1,
                                })
                                .cloned()
                        })
                        .collect::<Vec<_>>();
                    (column, column_shards)
                })
                .collect::<Vec<_>>()
        };
        let mut out = Vec::new();
        for (column, column_shards) in columns {
            let mut live = false;
            for shard in column_shards {
                if !lock(&shard)?.index.is_empty() {
                    live = true;
                    break;
                }
            }
            if live {
                out.push(column);
            }
        }
        Ok(out)
    }

    pub fn live_keys_in_column(&self, x: i32, z: i32) -> Result<Vec<SectionKey>> {
        let ids = read_lock(&self.columns)?
            .get(&(x, z))
            .cloned()
            .unwrap_or_default()
            .into_iter()
            .map(|y| ShardId { x, y, z })
            .collect::<Vec<_>>();
        let shards = {
            let map = read_lock(&self.shards)?;
            ids.into_iter()
                .filter_map(|id| map.get(&id).cloned())
                .collect::<Vec<_>>()
        };
        let mut out = Vec::new();
        for shard in shards {
            out.extend(
                lock(&shard)?
                    .index
                    .keys()
                    .map(|&key| SectionKey::unpack(key))
                    .collect::<Result<Vec<_>>>()?,
            );
        }
        Ok(out)
    }

    pub fn put_many(&self, sections: &[Section]) -> Result<Vec<EntryMeta>> {
        let mut expected = HashMap::with_capacity(sections.len());
        for section in sections {
            expected.insert(
                section.key.packed(),
                self.revision(section.key)?.unwrap_or(0),
            );
        }
        self.put_many_checked(sections, &expected)
    }

    /// Publishes only results whose source/invalidation revision is still current. A worker
    /// finishing after a newer invalidation is silently rejected instead of overwriting it.
    pub fn put_many_checked(
        &self,
        sections: &[Section],
        expected: &HashMap<u64, u64>,
    ) -> Result<Vec<EntryMeta>> {
        self.put_many_checked_inner(sections, expected, true)
    }

    pub fn put_many_checked_deferred(
        &self,
        sections: &[Section],
        expected: &HashMap<u64, u64>,
    ) -> Result<Vec<EntryMeta>> {
        self.put_many_checked_inner(sections, expected, false)
    }

    fn put_many_checked_inner(
        &self,
        sections: &[Section],
        expected: &HashMap<u64, u64>,
        durable: bool,
    ) -> Result<Vec<EntryMeta>> {
        let mut groups = BTreeMap::<ShardId, Vec<(u64, u64, Vec<u8>)>>::new();
        for section in sections {
            let key = section.key.packed();
            let expected = *expected
                .get(&key)
                .with_context(|| format!("missing expected revision for section {key}"))?;
            groups
                .entry(section.key.shard())
                .or_default()
                .push((key, expected, section.encode()?));
        }
        let mut out = Vec::with_capacity(sections.len());
        for (id, mut values) in groups {
            values.sort_unstable_by_key(|entry| entry.0);
            let shard = self.get_or_create(id)?;
            out.extend(lock(&shard)?.put(&values, durable)?);
        }
        Ok(out)
    }

    pub fn get(&self, key: SectionKey) -> Result<Option<StoredSection>> {
        let Some(shard) = read_lock(&self.shards)?.get(&key.shard()).cloned() else {
            return Ok(None);
        };
        let mut shard = lock(&shard)?;
        let result = shard.read(key);
        self.observe_destructive_reset(&mut shard)?;
        result
    }

    pub fn revision(&self, key: SectionKey) -> Result<Option<u64>> {
        let Some(shard) = read_lock(&self.shards)?.get(&key.shard()).cloned() else {
            return Ok(None);
        };
        let result = lock(&shard)?.revisions.get(&key.packed()).copied();
        Ok(result)
    }

    /// Resolves only the keys selected by the client's renderer. A matching known revision is
    /// omitted, making reconnects use the durable client cache without downloading it again.
    pub fn requested_items(&self, requests: &[(u64, u64)]) -> Result<Vec<NetworkItem>> {
        let mut groups = BTreeMap::<ShardId, Vec<(u64, u64)>>::new();
        for &(packed, revision) in requests {
            let key = SectionKey::unpack(packed)?;
            groups
                .entry(key.shard())
                .or_default()
                .push((packed, revision));
        }
        let handles = {
            let shards = read_lock(&self.shards)?;
            groups
                .keys()
                .filter_map(|id| shards.get(id).map(|shard| (*id, shard.clone())))
                .collect::<HashMap<_, _>>()
        };
        let mut out = Vec::with_capacity(requests.len());
        'groups: for (id, requests) in groups {
            let Some(shard) = handles.get(&id) else {
                continue;
            };
            let mut shard = lock(shard)?;
            let mut sections = Vec::new();
            for (packed, known_revision) in requests {
                let Some(revision) = shard.revisions.get(&packed).copied() else {
                    continue;
                };
                if revision == known_revision {
                    continue;
                }
                let Some(current) = shard.index.get(&packed).copied() else {
                    out.push(NetworkItem::Invalidate(Invalidation {
                        key: packed,
                        revision,
                        reason: 2,
                    }));
                    continue;
                };
                sections.push(current);
            }
            sections.sort_unstable_by_key(|entry| entry.offset);
            for current in sections {
                match shard.read_payload(current).and_then(|encoded| {
                    network_body_from_encoded(&encoded, current.key, current.revision)
                }) {
                    Ok(payload) => out.push(NetworkItem::Section(payload)),
                    Err(error) => {
                        eprintln!("cannot stream section {}: {error:#}", current.key);
                        let recovery = shard.reset_corrupt();
                        self.observe_destructive_reset(&mut shard)?;
                        for invalidation in recovery? {
                            out.push(NetworkItem::Invalidate(invalidation));
                        }
                        continue 'groups;
                    }
                };
            }
            self.observe_destructive_reset(&mut shard)?;
        }
        Ok(out)
    }

    pub fn checkpoint_all(&self) -> Result<()> {
        let shards = read_lock(&self.shards)?
            .values()
            .cloned()
            .collect::<Vec<_>>();
        for shard in shards {
            let mut shard = lock(&shard)?;
            shard.sync_pending()?;
            shard.checkpoint()?;
        }
        Ok(())
    }

    pub fn sync_pending(&self) -> Result<()> {
        let shards = read_lock(&self.shards)?
            .values()
            .cloned()
            .collect::<Vec<_>>();
        for shard in shards {
            lock(&shard)?.sync_pending()?;
        }
        Ok(())
    }

    pub fn compact_if_needed(&self) -> Result<usize> {
        let mut count = 0;
        let shards = read_lock(&self.shards)?
            .values()
            .cloned()
            .collect::<Vec<_>>();
        for shard in shards {
            let mut shard = lock(&shard)?;
            if shard.should_compact()? {
                let result = shard.compact();
                self.observe_destructive_reset(&mut shard)?;
                result?;
                count += 1;
            }
        }
        Ok(count)
    }

    pub fn degraded_shards(&self) -> Result<Vec<ShardId>> {
        let mut out = Vec::new();
        let shards = read_lock(&self.shards)?
            .iter()
            .map(|(&id, shard)| (id, shard.clone()))
            .collect::<Vec<_>>();
        for (id, shard) in shards {
            if lock(&shard)?.degraded {
                out.push(id);
            }
        }
        Ok(out)
    }

    pub fn clear_degraded(&self, ids: &[ShardId]) -> Result<()> {
        let shards = {
            let map = read_lock(&self.shards)?;
            ids.iter()
                .filter_map(|id| map.get(id).cloned())
                .collect::<Vec<_>>()
        };
        for shard in shards {
            let mut shard = lock(&shard)?;
            shard.clear_repair_marker()?;
            shard.degraded = false;
        }
        Ok(())
    }

    pub fn take_recovery_invalidations(&self) -> Result<Vec<Invalidation>> {
        let mut out = Vec::new();
        let shards = read_lock(&self.shards)?
            .values()
            .cloned()
            .collect::<Vec<_>>();
        for shard in shards {
            out.append(&mut lock(&shard)?.recovery_invalidations);
        }
        Ok(out)
    }

    fn get_or_create(&self, id: ShardId) -> Result<Arc<Mutex<Shard>>> {
        if let Some(shard) = read_lock(&self.shards)?.get(&id).cloned() {
            self.register_column(id)?;
            if self.manifest_dirty.load(Ordering::Acquire) {
                let _manifest = lock(&self.manifest_lock)?;
                self.sync_manifest_if_dirty()?;
            }
            return Ok(shard);
        }
        let _manifest = lock(&self.manifest_lock)?;
        let mut map = write_lock(&self.shards)?;
        if let Some(shard) = map.get(&id).cloned() {
            drop(map);
            self.register_column(id)?;
            self.sync_manifest_if_dirty()?;
            return Ok(shard);
        }
        let shard = Arc::new(Mutex::new(Shard::create(
            self.root.join(id.filename()),
            id,
            self.catalog_id,
        )?));
        map.insert(id, shard.clone());
        self.manifest_dirty.store(true, Ordering::Release);
        drop(map);
        self.register_column(id)?;
        self.sync_manifest_if_dirty()?;
        Ok(shard)
    }

    fn register_column(&self, id: ShardId) -> Result<()> {
        let mut columns = write_lock(&self.columns)?;
        let values = columns.entry((id.x, id.z)).or_default();
        if !values.contains(&id.y) {
            values.push(id.y);
            values.sort_unstable();
        }
        Ok(())
    }

    fn sync_manifest_if_dirty(&self) -> Result<()> {
        if !self.manifest_dirty.load(Ordering::Acquire) {
            return Ok(());
        }
        let manifest = read_lock(&self.shards)?
            .keys()
            .copied()
            .collect::<std::collections::BTreeSet<_>>();
        write_shard_manifest(&self.manifest_path, self.catalog_id, &manifest)?;
        self.manifest_dirty.store(false, Ordering::Release);
        Ok(())
    }

    fn observe_destructive_reset(&self, shard: &mut Shard) -> Result<()> {
        if !shard.destructive_reset {
            return Ok(());
        }
        let _identity = lock(&self.manifest_lock)?;
        let epoch = new_incarnation();
        // Close existing client identities even if persisting the new epoch fails. The durable
        // repair marker makes startup roll it again, while keeping the old in-memory identity
        // here could leave unknown cached keys visible until the disk error clears.
        self.epoch.store(epoch, Ordering::Release);
        write_store_identity(&self.identity_path, self.catalog_id, epoch)?;
        shard.destructive_reset = false;
        Ok(())
    }
}

impl Shard {
    fn create(path: PathBuf, id: ShardId, catalog_id: u64) -> Result<Self> {
        let index_path = path.with_extension("vxidx");
        let incarnation = new_incarnation();
        let mut file = OpenOptions::new()
            .create_new(true)
            .read(true)
            .write(true)
            .open(&path)
            .with_context(|| format!("create shard {}", path.display()))?;
        write_file_header(&mut file, id, catalog_id, incarnation)?;
        file.sync_all()?;
        sync_parent(&path)?;
        Ok(Self {
            id,
            path,
            index_path,
            file,
            catalog_id,
            incarnation,
            generation: 0,
            index: HashMap::new(),
            revisions: HashMap::new(),
            log_len: FILE_HEADER,
            degraded: false,
            dirty: false,
            recovery_invalidations: Vec::new(),
            transactions_since_checkpoint: 0,
            destructive_reset: false,
        })
    }

    fn open_existing(path: PathBuf, catalog_id: u64) -> Result<Self> {
        let index_path = path.with_extension("vxidx");
        let mut file = OpenOptions::new().read(true).write(true).open(&path)?;
        let (id, stored_catalog, incarnation) = read_file_header(&mut file)?;
        if stored_catalog != catalog_id {
            bail!("shard belongs to catalog {stored_catalog:016x}, expected {catalog_id:016x}");
        }
        let mut shard = Self {
            id,
            path,
            index_path,
            file,
            catalog_id,
            incarnation,
            generation: 0,
            index: HashMap::new(),
            revisions: HashMap::new(),
            log_len: FILE_HEADER,
            degraded: false,
            dirty: false,
            recovery_invalidations: Vec::new(),
            transactions_since_checkpoint: 0,
            destructive_reset: false,
        };
        let scan_from = shard.load_checkpoint().unwrap_or_else(|error| {
            eprintln!(
                "discarding invalid checkpoint {}: {error:#}",
                shard.index_path.display()
            );
            shard.index.clear();
            shard.revisions.clear();
            shard.generation = 0;
            FILE_HEADER
        });
        shard.scan(scan_from)?;
        if shard.path.with_extension("vxrepair").exists() {
            shard.degraded = true;
        }
        Ok(shard)
    }

    fn invalidate(&mut self, keys: &[u64], reason: u8, durable: bool) -> Result<Vec<Invalidation>> {
        let keys = keys
            .iter()
            .copied()
            .filter(|key| self.revisions.contains_key(key))
            .collect::<Vec<_>>();
        if keys.is_empty() {
            return Ok(Vec::new());
        }
        let generation = self.next_generation()?;
        for &key in &keys {
            self.append_record(KIND_INVALIDATE, generation, key, &[reason])?;
        }
        // The invalidation is durable and effective before any replacement can be trusted.
        if durable {
            self.file.sync_data()?;
        } else {
            self.dirty = true;
        }
        for &key in &keys {
            self.index.remove(&key);
            self.revisions.insert(key, generation);
        }
        self.after_transaction(durable)?;
        Ok(keys
            .into_iter()
            .map(|key| Invalidation {
                key,
                revision: generation,
                reason,
            })
            .collect())
    }

    fn put(&mut self, values: &[(u64, u64, Vec<u8>)], durable: bool) -> Result<Vec<EntryMeta>> {
        let values = values
            .iter()
            .filter(|(key, expected, _)| self.revisions.get(key).copied().unwrap_or(0) == *expected)
            .collect::<Vec<_>>();
        if values.is_empty() {
            return Ok(Vec::new());
        }
        let generation = self.next_generation()?;
        let mut pending = Vec::with_capacity(values.len());
        for (key, _, payload) in values {
            let offset = self.append_record(KIND_PUT, generation, *key, payload)?;
            pending.push(EntryMeta {
                key: *key,
                revision: generation,
                offset,
                length: payload.len() as u32,
                payload_crc: crc32c(payload),
            });
        }
        self.append_record(KIND_COMMIT, generation, 0, &[])?;
        if durable {
            self.file.sync_data()?;
        } else {
            self.dirty = true;
        }
        for entry in &pending {
            self.revisions.insert(entry.key, generation);
            self.index.insert(entry.key, *entry);
        }
        self.after_transaction(durable)?;
        Ok(pending)
    }

    fn read(&mut self, key: SectionKey) -> Result<Option<StoredSection>> {
        let packed = key.packed();
        let Some(meta) = self.index.get(&packed).copied() else {
            return Ok(None);
        };
        match self.read_payload(meta) {
            Ok(payload) => match Section::decode(key, &payload) {
                Ok(section) => Ok(Some(StoredSection { section, meta })),
                Err(error) => {
                    eprintln!(
                        "invalid section {} in {}: {error:#}",
                        packed,
                        self.path.display()
                    );
                    self.reset_corrupt()?;
                    Ok(None)
                }
            },
            Err(error) => {
                eprintln!(
                    "damaged section {} in {}: {error:#}",
                    packed,
                    self.path.display()
                );
                self.reset_corrupt()?;
                Ok(None)
            }
        }
    }

    fn read_payload(&mut self, meta: EntryMeta) -> Result<Vec<u8>> {
        self.file
            .seek(SeekFrom::Start(meta.offset + RECORD_HEADER as u64))?;
        let mut payload = vec![0; meta.length as usize];
        self.file.read_exact(&mut payload)?;
        if crc32c(&payload) != meta.payload_crc {
            bail!("payload checksum mismatch");
        }
        Ok(payload)
    }

    fn append_record(
        &mut self,
        kind: u8,
        generation: u64,
        key: u64,
        payload: &[u8],
    ) -> Result<u64> {
        if payload.len() > MAX_RECORD_PAYLOAD {
            bail!("record exceeds {MAX_RECORD_PAYLOAD} bytes");
        }
        let offset = self.log_len;
        let header = encode_record_header(kind, generation, key, payload);
        self.file.seek(SeekFrom::Start(offset))?;
        self.file.write_all(&header)?;
        self.file.write_all(payload)?;
        let padding = record_padding(payload.len());
        if padding != 0 {
            self.file.write_all(&[0; 7][..padding])?;
        }
        self.log_len += (RECORD_HEADER + payload.len() + padding) as u64;
        Ok(offset)
    }

    fn next_generation(&mut self) -> Result<u64> {
        self.generation = self
            .generation
            .checked_add(1)
            .context("shard generation overflow")?
            .max(new_revision_base());
        Ok(self.generation)
    }

    fn after_transaction(&mut self, durable: bool) -> Result<()> {
        self.transactions_since_checkpoint += 1;
        if durable && self.transactions_since_checkpoint >= 32 {
            self.checkpoint()?;
        }
        Ok(())
    }

    fn sync_pending(&mut self) -> Result<()> {
        if self.dirty {
            self.file.sync_data()?;
            self.dirty = false;
            if self.transactions_since_checkpoint >= 32 {
                self.checkpoint()?;
            }
        }
        Ok(())
    }

    fn scan(&mut self, start: u64) -> Result<()> {
        let file_len = self.file.metadata()?.len();
        if start > file_len {
            bail!("checkpoint points beyond the log");
        }
        let mut offset = start;
        let mut pending = HashMap::<u64, Vec<EntryMeta>>::new();
        while offset < file_len {
            if file_len - offset < RECORD_HEADER as u64 {
                self.truncate_torn_tail(offset)?;
                break;
            }
            self.file.seek(SeekFrom::Start(offset))?;
            let mut header = [0u8; RECORD_HEADER];
            self.file.read_exact(&mut header)?;
            let decoded = match decode_record_header(&header) {
                Ok(decoded) => decoded,
                Err(error) => {
                    eprintln!(
                        "bad record header at {}:{}: {error:#}",
                        self.path.display(),
                        offset
                    );
                    self.reset_corrupt()?;
                    break;
                }
            };
            let total = RECORD_HEADER as u64
                + decoded.length as u64
                + record_padding(decoded.length as usize) as u64;
            if offset + total > file_len {
                self.truncate_torn_tail(offset)?;
                break;
            }
            let mut payload = vec![0; decoded.length as usize];
            self.file.read_exact(&mut payload)?;
            self.generation = self.generation.max(decoded.generation);
            if crc32c(&payload) != decoded.payload_crc {
                self.reset_corrupt()?;
                break;
            }
            if matches!(decoded.kind, KIND_INVALIDATE | KIND_PUT)
                && !SectionKey::unpack(decoded.key).is_ok_and(|key| key.shard() == self.id)
            {
                self.reset_corrupt()?;
                break;
            }
            match decoded.kind {
                KIND_INVALIDATE => {
                    if decoded.length != 1 {
                        self.reset_corrupt()?;
                        break;
                    }
                    self.index.remove(&decoded.key);
                    self.revisions.insert(decoded.key, decoded.generation);
                }
                KIND_PUT => pending
                    .entry(decoded.generation)
                    .or_default()
                    .push(EntryMeta {
                        key: decoded.key,
                        revision: decoded.generation,
                        offset,
                        length: decoded.length,
                        payload_crc: decoded.payload_crc,
                    }),
                KIND_COMMIT => {
                    if decoded.length != 0 || decoded.key != 0 {
                        self.reset_corrupt()?;
                        break;
                    }
                    if let Some(entries) = pending.remove(&decoded.generation) {
                        for entry in entries {
                            if entry.revision
                                >= self.revisions.get(&entry.key).copied().unwrap_or(0)
                            {
                                self.revisions.insert(entry.key, entry.revision);
                                self.index.insert(entry.key, entry);
                            }
                        }
                    }
                }
                _ => {
                    self.reset_corrupt()?;
                    break;
                }
            }
            offset += total;
        }
        self.log_len = self.file.metadata()?.len();
        Ok(())
    }

    fn truncate_torn_tail(&mut self, offset: u64) -> Result<()> {
        self.write_repair_marker()?;
        self.degraded = true;
        self.file.set_len(offset)?;
        self.file.sync_data()?;
        self.log_len = offset;
        Ok(())
    }

    fn reset_corrupt(&mut self) -> Result<Vec<Invalidation>> {
        let known_keys = self.revisions.keys().copied().collect::<Vec<_>>();
        let generation = self
            .generation
            .checked_add(1)
            .context("shard generation overflow during recovery")?
            .max(new_revision_base());
        self.degraded = true;
        let incarnation = new_incarnation();
        self.write_repair_marker()?;
        let replacement_path = self.path.with_extension("vxlog.recover");
        let mut replacement = OpenOptions::new()
            .create(true)
            .truncate(true)
            .read(true)
            .write(true)
            .open(&replacement_path)
            .with_context(|| format!("prepare replacement shard {}", self.path.display()))?;
        write_file_header(&mut replacement, self.id, self.catalog_id, incarnation)?;
        let mut replacement_len = FILE_HEADER;
        for &key in &known_keys {
            let header = encode_record_header(KIND_INVALIDATE, generation, key, &[2]);
            replacement.seek(SeekFrom::Start(replacement_len))?;
            replacement.write_all(&header)?;
            replacement.write_all(&[2])?;
            replacement.write_all(&[0; 7])?;
            replacement_len += 48;
        }
        replacement.set_len(replacement_len)?;
        replacement.sync_all()?;
        quarantine(&self.index_path);
        // Atomically replace the corrupt pathname while both old and replacement descriptors
        // remain open. Renaming the old log away first creates a failure window where the live
        // path is absent and later writes can target only the quarantined inode.
        fs::rename(&replacement_path, &self.path)?;
        // The synced replacement descriptor remains valid across rename on the Linux server.
        // Moving it into self makes the namespace transition immediately infallible; a failed
        // reopen must never leave later writes targeting the quarantined, unlinked old inode.
        self.file = replacement;
        self.incarnation = incarnation;
        self.generation = generation;
        self.index.clear();
        self.revisions.clear();
        self.log_len = replacement_len;
        self.dirty = false;
        self.transactions_since_checkpoint = 0;
        let invalidations = known_keys
            .iter()
            .map(|&key| Invalidation {
                key,
                revision: generation,
                reason: 2,
            })
            .collect::<Vec<_>>();
        if !invalidations.is_empty() {
            for entry in &invalidations {
                let key = entry.key;
                self.revisions.insert(key, generation);
            }
        }
        self.recovery_invalidations
            .extend_from_slice(&invalidations);
        self.destructive_reset = true;
        sync_parent(&self.path)?;
        Ok(invalidations)
    }

    fn write_repair_marker(&self) -> Result<()> {
        let path = self.path.with_extension("vxrepair");
        let tmp = self.path.with_extension("vxrepair.tmp");
        let mut bytes = Vec::with_capacity(36);
        bytes.extend_from_slice(REPAIR_MAGIC);
        bytes.extend_from_slice(&self.catalog_id.to_le_bytes());
        bytes.extend_from_slice(&self.id.x.to_le_bytes());
        bytes.extend_from_slice(&self.id.y.to_le_bytes());
        bytes.extend_from_slice(&self.id.z.to_le_bytes());
        bytes.extend_from_slice(&crc32c(&bytes).to_le_bytes());
        let mut file = OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(&tmp)?;
        file.write_all(&bytes)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&tmp, &path)?;
        sync_parent(&path)
    }

    fn clear_repair_marker(&self) -> Result<()> {
        let path = self.path.with_extension("vxrepair");
        match fs::remove_file(&path) {
            Ok(()) => sync_parent(&path),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(error.into()),
        }
    }

    fn load_checkpoint(&mut self) -> Result<u64> {
        let bytes = read_file_bounded(
            &self.index_path,
            64 + MAX_RECOVERY_ENTRIES.saturating_mul(2).saturating_mul(40),
        )?;
        if bytes.len() < 64 || &bytes[..8] != INDEX_MAGIC {
            bail!("bad checkpoint header");
        }
        let stored_crc = u32::from_le_bytes(bytes[bytes.len() - 4..].try_into().unwrap());
        if crc32c(&bytes[..bytes.len() - 4]) != stored_crc {
            bail!("checkpoint checksum mismatch");
        }
        let mut input = &bytes[8..bytes.len() - 4];
        let version = take_u16(&mut input)?;
        let reserved = take_u16(&mut input)?;
        let catalog = take_u64(&mut input)?;
        let incarnation = take_u64(&mut input)?;
        let id = ShardId {
            x: take_i32(&mut input)?,
            y: take_i32(&mut input)?,
            z: take_i32(&mut input)?,
        };
        let log_len = take_u64(&mut input)?;
        let generation = take_u64(&mut input)?;
        let count = take_u32(&mut input)? as usize;
        if version != FORMAT_VERSION
            || reserved != 0
            || catalog != self.catalog_id
            || incarnation != self.incarnation
            || id != self.id
        {
            bail!("checkpoint identity mismatch");
        }
        if count > MAX_RECOVERY_ENTRIES * 2 || input.len() != count * 40 {
            bail!("checkpoint entry count or size is invalid");
        }
        for _ in 0..count {
            let key = take_u64(&mut input)?;
            if SectionKey::unpack(key)?.shard() != self.id {
                bail!("checkpoint key belongs to a different shard");
            }
            let revision = take_u64(&mut input)?;
            let offset = take_u64(&mut input)?;
            let length = take_u32(&mut input)?;
            let payload_crc = take_u32(&mut input)?;
            let live = take_u32(&mut input)?;
            let entry_reserved = take_u32(&mut input)?;
            if entry_reserved != 0 || live > 1 {
                bail!("bad checkpoint entry");
            }
            self.revisions.insert(key, revision);
            if live == 1 {
                self.index.insert(
                    key,
                    EntryMeta {
                        key,
                        revision,
                        offset,
                        length,
                        payload_crc,
                    },
                );
            }
        }
        self.generation = generation;
        Ok(log_len)
    }

    fn checkpoint(&mut self) -> Result<()> {
        let mut bytes = Vec::with_capacity(64 + self.revisions.len() * 40);
        bytes.extend_from_slice(INDEX_MAGIC);
        bytes.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        bytes.extend_from_slice(&0u16.to_le_bytes());
        bytes.extend_from_slice(&self.catalog_id.to_le_bytes());
        bytes.extend_from_slice(&self.incarnation.to_le_bytes());
        bytes.extend_from_slice(&self.id.x.to_le_bytes());
        bytes.extend_from_slice(&self.id.y.to_le_bytes());
        bytes.extend_from_slice(&self.id.z.to_le_bytes());
        bytes.extend_from_slice(&self.log_len.to_le_bytes());
        bytes.extend_from_slice(&self.generation.to_le_bytes());
        bytes.extend_from_slice(&(self.revisions.len() as u32).to_le_bytes());
        let mut revisions = self.revisions.iter().collect::<Vec<_>>();
        revisions.sort_unstable_by_key(|entry| *entry.0);
        for (&key, &revision) in revisions {
            let entry = self.index.get(&key);
            bytes.extend_from_slice(&key.to_le_bytes());
            bytes.extend_from_slice(&revision.to_le_bytes());
            bytes.extend_from_slice(&entry.map_or(0, |entry| entry.offset).to_le_bytes());
            bytes.extend_from_slice(&entry.map_or(0, |entry| entry.length).to_le_bytes());
            bytes.extend_from_slice(&entry.map_or(0, |entry| entry.payload_crc).to_le_bytes());
            bytes.extend_from_slice(&(entry.is_some() as u32).to_le_bytes());
            bytes.extend_from_slice(&0u32.to_le_bytes());
        }
        bytes.extend_from_slice(&crc32c(&bytes).to_le_bytes());
        let tmp = self.index_path.with_extension("vxidx.tmp");
        let mut file = OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(&tmp)?;
        file.write_all(&bytes)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&tmp, &self.index_path)?;
        sync_parent(&self.index_path)?;
        self.transactions_since_checkpoint = 0;
        Ok(())
    }

    fn should_compact(&mut self) -> Result<bool> {
        let live = self
            .index
            .values()
            .map(|entry| RECORD_HEADER as u64 + entry.length as u64 + 8)
            .sum::<u64>();
        Ok(self.log_len > 16 * 1024 * 1024 && self.log_len > (live + FILE_HEADER) * 2)
    }

    fn compact(&mut self) -> Result<()> {
        #[derive(Clone, Copy)]
        enum Latest {
            Live(EntryMeta),
            Deleted { key: u64, revision: u64 },
        }
        impl Latest {
            fn order(self) -> (u64, u64) {
                match self {
                    Self::Live(entry) => (entry.revision, entry.key),
                    Self::Deleted { key, revision } => (revision, key),
                }
            }
        }
        // Metadata is small and sortable; payloads are copied one at a time so compaction never
        // retains an entire shard (potentially gigabytes) in RAM.
        let mut latest = self
            .revisions
            .iter()
            .map(|(&key, &revision)| {
                self.index
                    .get(&key)
                    .copied()
                    .map_or(Latest::Deleted { key, revision }, Latest::Live)
            })
            .collect::<Vec<_>>();
        latest.sort_unstable_by_key(|entry| entry.order());
        let tmp = self.path.with_extension("vxlog.compact");
        let new_incarnation = new_incarnation();
        let mut replacement = OpenOptions::new()
            .create(true)
            .truncate(true)
            .read(true)
            .write(true)
            .open(&tmp)?;
        write_file_header(&mut replacement, self.id, self.catalog_id, new_incarnation)?;
        let mut offset = FILE_HEADER;
        let mut new_index = HashMap::new();
        let mut current_revision = None;
        let mut current_has_put = false;
        for entry in latest {
            let (revision, key) = entry.order();
            if current_revision.is_some_and(|current| current != revision) {
                if current_has_put {
                    let header =
                        encode_record_header(KIND_COMMIT, current_revision.unwrap(), 0, &[]);
                    replacement.seek(SeekFrom::Start(offset))?;
                    replacement.write_all(&header)?;
                    offset += RECORD_HEADER as u64;
                }
                current_has_put = false;
            }
            current_revision = Some(revision);
            match entry {
                Latest::Deleted { .. } => {
                    let header = encode_record_header(KIND_INVALIDATE, revision, key, &[2]);
                    replacement.seek(SeekFrom::Start(offset))?;
                    replacement.write_all(&header)?;
                    replacement.write_all(&[2])?;
                    replacement.write_all(&[0; 7])?;
                    offset += 48;
                }
                Latest::Live(old) => {
                    current_has_put = true;
                    let payload = match self.read_payload(old) {
                        Ok(payload) => payload,
                        Err(error) => {
                            let _ = fs::remove_file(&tmp);
                            self.reset_corrupt()?;
                            return Err(error).context("damaged live payload during compaction");
                        }
                    };
                    let header = encode_record_header(KIND_PUT, revision, key, &payload);
                    replacement.seek(SeekFrom::Start(offset))?;
                    replacement.write_all(&header)?;
                    replacement.write_all(&payload)?;
                    let padding = record_padding(payload.len());
                    replacement.write_all(&[0; 7][..padding])?;
                    new_index.insert(key, EntryMeta { offset, ..old });
                    offset += (RECORD_HEADER + payload.len() + padding) as u64;
                }
            }
        }
        if current_has_put {
            let revision = current_revision.expect("put has a revision");
            let header = encode_record_header(KIND_COMMIT, revision, 0, &[]);
            replacement.seek(SeekFrom::Start(offset))?;
            replacement.write_all(&header)?;
            offset += RECORD_HEADER as u64;
        }
        replacement.set_len(offset)?;
        replacement.sync_all()?;
        fs::rename(&tmp, &self.path)?;
        self.file = replacement;
        self.incarnation = new_incarnation;
        self.index = new_index;
        self.log_len = offset;
        self.degraded = false;
        self.dirty = false;
        sync_parent(&self.path)?;
        self.checkpoint()?;
        Ok(())
    }
}

#[derive(Debug)]
struct RecordHeader {
    kind: u8,
    length: u32,
    payload_crc: u32,
    generation: u64,
    key: u64,
}

fn write_file_header(
    file: &mut File,
    id: ShardId,
    catalog_id: u64,
    incarnation: u64,
) -> Result<()> {
    let mut header = [0u8; FILE_HEADER as usize];
    header[..8].copy_from_slice(FILE_MAGIC);
    header[8..10].copy_from_slice(&FORMAT_VERSION.to_le_bytes());
    header[10..12].copy_from_slice(&(FILE_HEADER as u16).to_le_bytes());
    header[12..20].copy_from_slice(&catalog_id.to_le_bytes());
    header[20..24].copy_from_slice(&id.x.to_le_bytes());
    header[24..28].copy_from_slice(&id.y.to_le_bytes());
    header[28..32].copy_from_slice(&id.z.to_le_bytes());
    header[32..40].copy_from_slice(&incarnation.to_le_bytes());
    let crc = crc32c(&header[..40]);
    header[40..44].copy_from_slice(&crc.to_le_bytes());
    file.seek(SeekFrom::Start(0))?;
    file.write_all(&header)?;
    Ok(())
}

fn read_file_header(file: &mut File) -> Result<(ShardId, u64, u64)> {
    let mut header = [0u8; FILE_HEADER as usize];
    file.seek(SeekFrom::Start(0))?;
    file.read_exact(&mut header)?;
    if &header[..8] != FILE_MAGIC
        || u16::from_le_bytes(header[8..10].try_into().unwrap()) != FORMAT_VERSION
        || u16::from_le_bytes(header[10..12].try_into().unwrap()) != FILE_HEADER as u16
        || u32::from_le_bytes(header[40..44].try_into().unwrap()) != crc32c(&header[..40])
        || header[44..48] != [0; 4]
    {
        bail!("invalid shard header");
    }
    Ok((
        ShardId {
            x: i32::from_le_bytes(header[20..24].try_into().unwrap()),
            y: i32::from_le_bytes(header[24..28].try_into().unwrap()),
            z: i32::from_le_bytes(header[28..32].try_into().unwrap()),
        },
        u64::from_le_bytes(header[12..20].try_into().unwrap()),
        u64::from_le_bytes(header[32..40].try_into().unwrap()),
    ))
}

fn encode_record_header(
    kind: u8,
    generation: u64,
    key: u64,
    payload: &[u8],
) -> [u8; RECORD_HEADER] {
    let mut header = [0u8; RECORD_HEADER];
    header[..4].copy_from_slice(RECORD_MAGIC);
    header[4] = kind;
    header[6..8].copy_from_slice(&(RECORD_HEADER as u16).to_le_bytes());
    header[8..12].copy_from_slice(&(payload.len() as u32).to_le_bytes());
    header[12..16].copy_from_slice(&crc32c(payload).to_le_bytes());
    header[16..24].copy_from_slice(&generation.to_le_bytes());
    header[24..32].copy_from_slice(&key.to_le_bytes());
    let header_crc = crc32c(&header[..32]);
    header[32..36].copy_from_slice(&header_crc.to_le_bytes());
    header
}

fn decode_record_header(header: &[u8; RECORD_HEADER]) -> Result<RecordHeader> {
    if &header[..4] != RECORD_MAGIC
        || header[5] != 0
        || u16::from_le_bytes(header[6..8].try_into().unwrap()) != RECORD_HEADER as u16
        || u32::from_le_bytes(header[32..36].try_into().unwrap()) != crc32c(&header[..32])
        || header[36..40] != [0; 4]
    {
        bail!("record header checksum or structure mismatch");
    }
    let length = u32::from_le_bytes(header[8..12].try_into().unwrap());
    if length as usize > MAX_RECORD_PAYLOAD {
        bail!("record length exceeds limit");
    }
    Ok(RecordHeader {
        kind: header[4],
        length,
        payload_crc: u32::from_le_bytes(header[12..16].try_into().unwrap()),
        generation: u64::from_le_bytes(header[16..24].try_into().unwrap()),
        key: u64::from_le_bytes(header[24..32].try_into().unwrap()),
    })
}

fn record_padding(payload: usize) -> usize {
    (8 - ((RECORD_HEADER + payload) & 7)) & 7
}

fn sync_parent(path: &Path) -> Result<()> {
    if let Some(parent) = path.parent() {
        File::open(parent)?.sync_all()?;
    }
    Ok(())
}

fn read_store_identity(path: &Path) -> Result<Option<(u64, u64)>> {
    let mut bytes = [0u8; 28];
    let mut file = match File::open(path) {
        Ok(file) => file,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    file.read_exact(&mut bytes)?;
    let mut extra = [0];
    if file.read(&mut extra)? != 0
        || &bytes[..8] != STORE_MAGIC
        || u32::from_le_bytes(bytes[24..28].try_into().unwrap()) != crc32c(&bytes[..24])
    {
        bail!("invalid store identity");
    }
    Ok(Some((
        u64::from_le_bytes(bytes[8..16].try_into().unwrap()),
        u64::from_le_bytes(bytes[16..24].try_into().unwrap()),
    )))
}

fn write_store_identity(path: &Path, catalog_id: u64, epoch: u64) -> Result<()> {
    let mut bytes = [0u8; 28];
    bytes[..8].copy_from_slice(STORE_MAGIC);
    bytes[8..16].copy_from_slice(&catalog_id.to_le_bytes());
    bytes[16..24].copy_from_slice(&epoch.to_le_bytes());
    let crc = crc32c(&bytes[..24]);
    bytes[24..28].copy_from_slice(&crc.to_le_bytes());
    let tmp = path.with_extension("identity.tmp");
    let mut file = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(&tmp)?;
    file.write_all(&bytes)?;
    file.sync_all()?;
    drop(file);
    fs::rename(&tmp, path)?;
    sync_parent(path)
}

fn read_shard_manifest(
    path: &Path,
    catalog_id: u64,
) -> Result<Option<std::collections::BTreeSet<ShardId>>> {
    let bytes = match read_file_bounded(path, 28 + 1_000_000 * 12) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    if bytes.len() < 28 || &bytes[..8] != MANIFEST_MAGIC {
        bail!("invalid shard manifest header");
    }
    let stored_crc = u32::from_le_bytes(bytes[bytes.len() - 4..].try_into().unwrap());
    if stored_crc != crc32c(&bytes[..bytes.len() - 4]) {
        bail!("shard manifest checksum mismatch");
    }
    let stored_catalog = u64::from_le_bytes(bytes[8..16].try_into().unwrap());
    let count = u32::from_le_bytes(bytes[16..20].try_into().unwrap()) as usize;
    if stored_catalog != catalog_id
        || bytes[20..24] != [0; 4]
        || count > 1_000_000
        || bytes.len() != 28 + count * 12
    {
        bail!("shard manifest identity or size mismatch");
    }
    let mut out = std::collections::BTreeSet::new();
    for entry in bytes[24..bytes.len() - 4].chunks_exact(12) {
        let id = ShardId {
            x: i32::from_le_bytes(entry[..4].try_into().unwrap()),
            y: i32::from_le_bytes(entry[4..8].try_into().unwrap()),
            z: i32::from_le_bytes(entry[8..12].try_into().unwrap()),
        };
        if !out.insert(id) {
            bail!("duplicate shard in manifest");
        }
    }
    Ok(Some(out))
}

fn write_shard_manifest(
    path: &Path,
    catalog_id: u64,
    shards: &std::collections::BTreeSet<ShardId>,
) -> Result<()> {
    if shards.len() > 1_000_000 {
        bail!("store contains more than 1000000 shards");
    }
    let mut bytes = Vec::with_capacity(28 + shards.len() * 12);
    bytes.extend_from_slice(MANIFEST_MAGIC);
    bytes.extend_from_slice(&catalog_id.to_le_bytes());
    bytes.extend_from_slice(&(shards.len() as u32).to_le_bytes());
    bytes.extend_from_slice(&0u32.to_le_bytes());
    for id in shards {
        bytes.extend_from_slice(&id.x.to_le_bytes());
        bytes.extend_from_slice(&id.y.to_le_bytes());
        bytes.extend_from_slice(&id.z.to_le_bytes());
    }
    bytes.extend_from_slice(&crc32c(&bytes).to_le_bytes());
    let tmp = path.with_extension("shards.tmp");
    let mut file = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(&tmp)?;
    file.write_all(&bytes)?;
    file.sync_all()?;
    drop(file);
    fs::rename(&tmp, path)?;
    sync_parent(path)
}

fn new_incarnation() -> u64 {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos() as u64;
    nanos.rotate_left(23) ^ u64::from(std::process::id()) ^ 0x5658_5932_5348_4152
}

fn new_revision_base() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos()
        .min(u64::MAX as u128) as u64
}

fn quarantine(path: &Path) {
    if !path.exists() {
        return;
    }
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("shard");
    let target = path.with_file_name(format!("{name}.corrupt.{stamp}"));
    if let Err(error) = fs::rename(path, &target) {
        eprintln!("cannot quarantine {}: {error}", path.display());
    }
}

fn take<'a>(input: &mut &'a [u8], count: usize) -> Result<&'a [u8]> {
    if input.len() < count {
        bail!("truncated checkpoint");
    }
    let (head, tail) = input.split_at(count);
    *input = tail;
    Ok(head)
}

fn take_u16(input: &mut &[u8]) -> Result<u16> {
    Ok(u16::from_le_bytes(take(input, 2)?.try_into().unwrap()))
}
fn take_u32(input: &mut &[u8]) -> Result<u32> {
    Ok(u32::from_le_bytes(take(input, 4)?.try_into().unwrap()))
}
fn take_i32(input: &mut &[u8]) -> Result<i32> {
    Ok(i32::from_le_bytes(take(input, 4)?.try_into().unwrap()))
}
fn take_u64(input: &mut &[u8]) -> Result<u64> {
    Ok(u64::from_le_bytes(take(input, 8)?.try_into().unwrap()))
}

fn lock<T>(value: &Mutex<T>) -> Result<std::sync::MutexGuard<'_, T>> {
    value.lock().map_err(|_| anyhow::anyhow!("mutex poisoned"))
}
fn read_lock<T>(value: &RwLock<T>) -> Result<std::sync::RwLockReadGuard<'_, T>> {
    value.read().map_err(|_| anyhow::anyhow!("rwlock poisoned"))
}
fn write_lock<T>(value: &RwLock<T>) -> Result<std::sync::RwLockWriteGuard<'_, T>> {
    value
        .write()
        .map_err(|_| anyhow::anyhow!("rwlock poisoned"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::lod::{Cell, SECTION_VOLUME};
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT: AtomicU64 = AtomicU64::new(0);

    fn temp() -> PathBuf {
        let path = std::env::temp_dir().join(format!(
            "voxy-store-test-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(&path).unwrap();
        path
    }

    fn sample(key: SectionKey, block: u32) -> Section {
        Section::from_cells(
            key,
            vec![
                Cell {
                    block,
                    biome: 0,
                    light: 0xf
                };
                SECTION_VOLUME
            ],
        )
        .unwrap()
    }

    #[test]
    fn invalidation_then_commit_recovers() {
        let root = temp();
        let key = SectionKey::new(0, 1, 2, 3).unwrap();
        {
            let store = Store::open(&root, 44).unwrap();
            store.put_many(&[sample(key, 1)]).unwrap();
            store.invalidate_many(&[key], 1).unwrap();
            assert!(store.get(key).unwrap().is_none());
            store.put_many(&[sample(key, 2)]).unwrap();
            store.checkpoint_all().unwrap();
        }
        let store = Store::open(&root, 44).unwrap();
        assert_eq!(store.get(key).unwrap().unwrap().section.cells[0].block, 2);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn torn_tail_is_discarded_without_losing_committed_data() {
        let root = temp();
        let key = SectionKey::new(0, 0, 0, 0).unwrap();
        let log;
        {
            let store = Store::open(&root, 9).unwrap();
            store.put_many(&[sample(key, 1)]).unwrap();
            log = store.root.join(key.shard().filename());
        }
        let valid_len = fs::metadata(&log).unwrap().len();
        OpenOptions::new()
            .append(true)
            .open(&log)
            .unwrap()
            .write_all(b"VXR2torn")
            .unwrap();
        let store = Store::open(&root, 9).unwrap();
        assert!(store.get(key).unwrap().is_some());
        assert_eq!(fs::metadata(&log).unwrap().len(), valid_len);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn corrupt_payload_quarantines_its_shard_transaction() {
        let root = temp();
        let a = SectionKey::new(0, 0, 0, 0).unwrap();
        let b = SectionKey::new(0, 1, 0, 0).unwrap();
        let store = Store::open(&root, 12).unwrap();
        let original_epoch = store.epoch();
        store.put_many(&[sample(a, 1), sample(b, 2)]).unwrap();
        let meta = store.get(a).unwrap().unwrap().meta;
        let log = store.root.join(a.shard().filename());
        let mut file = OpenOptions::new()
            .read(true)
            .write(true)
            .open(&log)
            .unwrap();
        file.seek(SeekFrom::Start(meta.offset + RECORD_HEADER as u64 + 4))
            .unwrap();
        let mut byte = [0];
        file.read_exact(&mut byte).unwrap();
        byte[0] ^= 1;
        file.seek(SeekFrom::Current(-1)).unwrap();
        file.write_all(&byte).unwrap();
        file.sync_all().unwrap();
        drop(file);
        assert!(store.get(a).unwrap().is_none());
        assert_ne!(store.epoch(), original_epoch);
        assert!(store.get(b).unwrap().is_none());
        assert_eq!(store.degraded_shards().unwrap(), vec![a.shard()]);
        let a_revision = store.revision(a).unwrap().unwrap();
        let b_revision = store.revision(b).unwrap().unwrap();
        let runtime_epoch = store.epoch();
        drop(store);
        let reopened = Store::open(&root, 12).unwrap();
        assert_ne!(reopened.epoch(), runtime_epoch);
        assert!(reopened.get(a).unwrap().is_none());
        assert!(reopened.get(b).unwrap().is_none());
        assert!(reopened.revision(a).unwrap().unwrap() >= a_revision);
        assert!(reopened.revision(b).unwrap().unwrap() >= b_revision);
        assert_eq!(reopened.degraded_shards().unwrap(), vec![a.shard()]);
        reopened.clear_degraded(&[a.shard()]).unwrap();
        assert!(!log.with_extension("vxrepair").exists());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn stale_checked_worker_cannot_replace_a_newer_invalidation() {
        let root = temp();
        let key = SectionKey::new(0, 2, 0, 2).unwrap();
        let store = Store::open(&root, 21).unwrap();
        store.put_many(&[sample(key, 1)]).unwrap();
        let first = store.invalidate_many(&[key], 1).unwrap()[0];
        store.invalidate_many(&[key], 1).unwrap();
        let expected = HashMap::from([(key.packed(), first.revision)]);
        assert!(
            store
                .put_many_checked(&[sample(key, 3)], &expected)
                .unwrap()
                .is_empty()
        );
        assert!(store.get(key).unwrap().is_none());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn missing_manifested_shard_changes_store_identity() {
        let root = temp();
        let key = SectionKey::new(0, 0, 0, 0).unwrap();
        let (epoch, log) = {
            let store = Store::open(&root, 33).unwrap();
            store.put_many(&[sample(key, 1)]).unwrap();
            (store.epoch(), store.root.join(key.shard().filename()))
        };
        fs::remove_file(log).unwrap();
        let store = Store::open(&root, 33).unwrap();
        assert_ne!(store.epoch(), epoch);
        assert!(store.get(key).unwrap().is_none());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn manifest_failure_keeps_new_shard_visible_and_retries_on_next_write() {
        let root = temp();
        let key = SectionKey::new(0, 0, 0, 0).unwrap();
        let store = Store::open(&root, 34).unwrap();
        fs::remove_file(&store.manifest_path).unwrap();
        fs::create_dir(&store.manifest_path).unwrap();
        assert!(store.put_many(&[sample(key, 1)]).is_err());
        assert!(read_lock(&store.shards).unwrap().contains_key(&key.shard()));
        assert!(
            read_lock(&store.columns)
                .unwrap()
                .get(&(key.shard().x, key.shard().z))
                .is_some_and(|ys| ys.contains(&key.shard().y))
        );

        fs::remove_dir(&store.manifest_path).unwrap();
        store.put_many(&[sample(key, 2)]).unwrap();
        assert!(!store.manifest_dirty.load(Ordering::Acquire));
        assert_eq!(
            store
                .requested_items(&[(key.packed(), u64::MAX)])
                .unwrap()
                .len(),
            1
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn requested_items_are_revision_aware_and_tombstones_are_visible() {
        let root = temp();
        let fine = SectionKey::new(0, 0, 0, 0).unwrap();
        let coarse = SectionKey::new(4, 0, 0, 0).unwrap();
        let store = Store::open(&root, 35).unwrap();
        store
            .put_many(&[sample(fine, 1), sample(coarse, 2)])
            .unwrap();
        let fine_revision = store.revision(fine).unwrap().unwrap();
        let items = store
            .requested_items(&[(fine.packed(), u64::MAX), (coarse.packed(), u64::MAX)])
            .unwrap();
        assert_eq!(items.len(), 2);
        assert!(
            store
                .requested_items(&[(fine.packed(), fine_revision)])
                .unwrap()
                .is_empty()
        );
        assert_eq!(store.live_horizontal_columns().unwrap(), vec![(0, 0)]);

        store.invalidate_many(&[fine, coarse], 1).unwrap();
        assert!(store.live_horizontal_columns().unwrap().is_empty());
        assert!(matches!(
            store
                .requested_items(&[(fine.packed(), fine_revision)])
                .unwrap()
                .as_slice(),
            [NetworkItem::Invalidate(_)]
        ));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn stale_checkpoint_is_rejected_after_log_incarnation_changes() {
        let root = temp();
        let key = SectionKey::new(0, 0, 0, 0).unwrap();
        {
            let store = Store::open(&root, 55).unwrap();
            store.put_many(&[sample(key, 7)]).unwrap();
            store.checkpoint_all().unwrap();
            let shard = read_lock(&store.shards)
                .unwrap()
                .get(&key.shard())
                .unwrap()
                .clone();
            let mut shard = lock(&shard).unwrap();
            let stale = fs::read(&shard.index_path).unwrap();
            shard.compact().unwrap();
            let mut file = OpenOptions::new()
                .create(true)
                .truncate(true)
                .write(true)
                .open(&shard.index_path)
                .unwrap();
            file.write_all(&stale).unwrap();
            file.sync_all().unwrap();
        }
        let store = Store::open(&root, 55).unwrap();
        assert_eq!(store.get(key).unwrap().unwrap().section.cells[0].block, 7);
        fs::remove_dir_all(root).unwrap();
    }
}
