use super::{
    content::content_kind,
    dictionary,
    object::{CanonicalObject, ObjectHash, ObjectKind},
};
use crate::{crc::crc32c, quarantine, sync_parent};
use anyhow::{Context, Result, bail};
use std::{
    cmp::Reverse,
    collections::{BTreeMap, BinaryHeap, HashMap},
    ffi::c_void,
    fmt,
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    os::{fd::AsRawFd, unix::fs::FileExt},
    path::{Path, PathBuf},
    ptr::NonNull,
    sync::{
        Arc, Mutex, RwLock,
        atomic::{AtomicU64, Ordering},
    },
};

const PACK_MAGIC: &[u8; 8] = b"VXYPACK\0";
const RECORD_MAGIC: &[u8; 4] = b"VXYO";
const INDEX_MAGIC: &[u8; 8] = b"VXYOIDX\0";
const INDEX_SEGMENT_PREFIX: &str = "objects.vxindex.";
const PACK_HEADER: u64 = 32;
const RECORD_HEADER: usize = 104;
const INDEX_ENTRY: usize = 104;
const DEFAULT_MAX_PACK_BYTES: u64 = 128 * 1024 * 1024;
pub const MAX_CANONICAL_OBJECT_BYTES: usize = 64 * 1024 * 1024;
/// Keeps one compressed object and a bounded same-lane QUIC batch below the stream data limit.
pub const MAX_COMPRESSED_OBJECT_BYTES: usize = 16 * 1024 * 1024 - 123;
const MAX_INDEX_ENTRIES: usize = 16_000_000;
const MAX_PACKS: usize = 1_000_000;
const ZSTD_LEVEL: i32 = 1;
const PREFIXES: usize = 256;
const MAX_INDEX_DELTA_ENTRIES: usize = 32 * 1024;
const HOT_INDEX_ENTRIES: usize = 4 * 1024;
const INDEX_REBUILD_BUFFER_ENTRIES: usize = 16 * 1024;
/// Delta indexes are immutable and cheap to publish. Periodic consolidation bounds lookup depth
/// without putting a world-sized rewrite on every terrain update's critical path.
const MAX_INDEX_SEGMENTS: usize = 64;
// Covers the bounded object/reference/output maps retained by one append transaction. Zstd is
// invoked sequentially so only one codec context exists inside the separate working allowance.

unsafe extern "C" {
    fn mmap(
        address: *mut c_void,
        length: usize,
        protection: i32,
        flags: i32,
        descriptor: i32,
        offset: isize,
    ) -> *mut c_void;
    fn munmap(address: *mut c_void, length: usize) -> i32;
    fn madvise(address: *mut c_void, length: usize, advice: i32) -> i32;
}

const PROT_READ: i32 = 1;
const MAP_SHARED: i32 = 1;
const MADV_DONTNEED: i32 = 4;

/// Read-only mapping used by the fixed-record object index. Linux keeps an existing mapping
/// valid across atomic index replacement, which gives every reader an immutable Arc snapshot.
struct ReadOnlyMap {
    pointer: NonNull<u8>,
    length: usize,
    _file: File,
}

unsafe impl Send for ReadOnlyMap {}
unsafe impl Sync for ReadOnlyMap {}

impl fmt::Debug for ReadOnlyMap {
    fn fmt(&self, output: &mut fmt::Formatter<'_>) -> fmt::Result {
        output
            .debug_struct("ReadOnlyMap")
            .field("length", &self.length)
            .finish()
    }
}

impl ReadOnlyMap {
    fn open(path: &Path) -> Result<Self> {
        let file = File::open(path)?;
        let length = usize::try_from(file.metadata()?.len()).context("index length overflow")?;
        if length == 0 {
            bail!("cannot map an empty surface object index");
        }
        // SAFETY: the descriptor remains owned by this object, the mapping is read-only, and
        // `length` is the exact stable file length of an immutable checkpoint.
        let pointer = unsafe {
            mmap(
                std::ptr::null_mut(),
                length,
                PROT_READ,
                MAP_SHARED,
                file.as_raw_fd(),
                0,
            )
        };
        let pointer = NonNull::new(pointer.cast::<u8>())
            .filter(|pointer| pointer.as_ptr() as isize != -1)
            .ok_or_else(std::io::Error::last_os_error)?;
        Ok(Self {
            pointer,
            length,
            _file: file,
        })
    }

    fn bytes(&self) -> &[u8] {
        // SAFETY: the mapping is immutable and valid for `length` until Drop.
        unsafe { std::slice::from_raw_parts(self.pointer.as_ptr(), self.length) }
    }

    fn discard_resident_pages(&self) {
        // The complete immutable index was validated once. Binary-search lookups should fault
        // only the pages they touch instead of retaining a world-sized startup scan in RSS.
        let _ = unsafe { madvise(self.pointer.as_ptr().cast(), self.length, MADV_DONTNEED) };
    }
}

impl Drop for ReadOnlyMap {
    fn drop(&mut self) {
        // SAFETY: this is the exact mapping returned by mmap and it is dropped once.
        let _ = unsafe { munmap(self.pointer.as_ptr().cast(), self.length) };
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ObjectLocation {
    pub pack_id: u64,
    pub record_offset: u64,
    pub canonical_size: u64,
    pub compressed_size: u64,
    pub compressed_crc: u32,
    pub kind: ObjectKind,
    pub dictionary: ObjectHash,
}

impl ObjectLocation {
    pub(crate) fn payload_offset(self) -> u64 {
        self.record_offset + RECORD_HEADER as u64
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct StoredObject {
    pub hash: ObjectHash,
    pub kind: ObjectKind,
    pub dictionary: ObjectHash,
    pub canonical_size: u64,
    pub compressed_crc: u32,
    pub compressed: Vec<u8>,
}

/// Immutable positional view of one stored compressed object. Client serving clones only the
/// file handle and metadata; payload bytes are read directly from the pack in bounded chunks.
#[derive(Clone, Debug)]
pub struct StoredObjectSource {
    hash: ObjectHash,
    location: ObjectLocation,
    file: Arc<File>,
}

impl StoredObjectSource {
    pub fn hash(&self) -> ObjectHash {
        self.hash
    }

    pub fn kind(&self) -> ObjectKind {
        self.location.kind
    }

    pub fn dictionary(&self) -> ObjectHash {
        self.location.dictionary
    }

    pub fn canonical_size(&self) -> u64 {
        self.location.canonical_size
    }

    pub fn compressed_size(&self) -> u64 {
        self.location.compressed_size
    }

    pub fn compressed_crc(&self) -> u32 {
        self.location.compressed_crc
    }

    pub fn read_exact_at(&self, offset: u64, output: &mut [u8]) -> Result<()> {
        let end = offset
            .checked_add(output.len() as u64)
            .context("compressed object read range overflow")?;
        if end > self.location.compressed_size {
            bail!("compressed object read exceeds its stored extent");
        }
        let absolute = self
            .location
            .payload_offset()
            .checked_add(offset)
            .context("compressed object pack offset overflow")?;
        self.file
            .read_exact_at(output, absolute)
            .context("read compressed surface object")
    }
}

impl StoredObject {
    pub fn decode(&self) -> Result<CanonicalObject> {
        if !self.dictionary.is_zero() {
            bail!("dictionary-compressed object requires its announced dictionary");
        }
        self.decode_with_dictionary(None)
    }

    pub fn decode_with_dictionary(&self, dictionary: Option<&[u8]>) -> Result<CanonicalObject> {
        if self.dictionary.is_zero() != dictionary.is_none() {
            bail!("stored object dictionary hash and supplied dictionary disagree");
        }
        let maximum = usize::try_from(self.canonical_size).context("canonical size overflow")?;
        if maximum > MAX_CANONICAL_OBJECT_BYTES {
            bail!("canonical object exceeds its size bound");
        }
        if crc32c(&self.compressed) != self.compressed_crc {
            bail!("compressed object checksum mismatch");
        }
        let bytes = decompress_zstd(&self.compressed, maximum, dictionary)?;
        if bytes.len() != maximum {
            bail!("decompressed object length mismatch");
        }
        let object = CanonicalObject::new(self.kind, bytes)?;
        if object.hash() != self.hash {
            bail!("canonical object hash mismatch");
        }
        Ok(object)
    }
}

#[derive(Debug)]
struct MappedIndex {
    map: ReadOnlyMap,
    entries_offset: usize,
    entry_count: usize,
    prefixes: [(u32, u32); PREFIXES],
}

impl MappedIndex {
    fn entry(&self, index: usize) -> (ObjectHash, ObjectLocation) {
        let offset = self.entries_offset + index * INDEX_ENTRY;
        decode_index_entry(&self.map.bytes()[offset..offset + INDEX_ENTRY])
            .expect("mapped index was completely validated before publication")
    }

    fn location(&self, hash: ObjectHash) -> Option<ObjectLocation> {
        let (start, end) = self.prefixes[hash.as_bytes()[0] as usize];
        let mut start = start as usize;
        let mut end = end as usize;
        while start < end {
            let middle = start + (end - start) / 2;
            let (candidate, location) = self.entry(middle);
            match candidate.cmp(&hash) {
                std::cmp::Ordering::Less => start = middle + 1,
                std::cmp::Ordering::Greater => end = middle,
                std::cmp::Ordering::Equal => return Some(location),
            }
        }
        None
    }
}

#[derive(Debug)]
struct ObjectIndex {
    /// Oldest-to-newest immutable index segments. Newer physical encodings of an identical
    /// canonical hash supersede older ones without changing object identity.
    segments: RwLock<Vec<Arc<MappedIndex>>>,
    /// The append delta is intentionally small between transactional publication checkpoints.
    /// Existing readers continue using the immutable base while a writer appends records.
    delta: RwLock<BTreeMap<ObjectHash, ObjectLocation>>,
    hot: Mutex<Box<[Option<(ObjectHash, ObjectLocation)>]>>,
}

impl ObjectIndex {
    fn new(
        segments: Vec<Arc<MappedIndex>>,
        delta: BTreeMap<ObjectHash, ObjectLocation>,
    ) -> Result<Self> {
        if delta.len() > MAX_INDEX_DELTA_ENTRIES {
            bail!("rebuilt surface index delta exceeds its fixed memory bound");
        }
        Ok(Self {
            segments: RwLock::new(segments),
            delta: RwLock::new(delta),
            hot: Mutex::new(vec![None; HOT_INDEX_ENTRIES].into_boxed_slice()),
        })
    }

    fn location(&self, hash: ObjectHash) -> Option<ObjectLocation> {
        if let Some(location) = self
            .delta
            .read()
            .unwrap_or_else(|poison| poison.into_inner())
            .get(&hash)
            .copied()
        {
            return Some(location);
        }
        let slot = hot_slot(hash);
        if let Some((cached_hash, location)) =
            self.hot.lock().unwrap_or_else(|poison| poison.into_inner())[slot]
            && cached_hash == hash
        {
            return Some(location);
        }
        let location = self
            .segments
            .read()
            .unwrap_or_else(|poison| poison.into_inner())
            .iter()
            .rev()
            .find_map(|segment| segment.location(hash));
        if let Some(location) = location {
            self.hot.lock().unwrap_or_else(|poison| poison.into_inner())[slot] =
                Some((hash, location));
        }
        location
    }

    fn insert(&self, hash: ObjectHash, location: ObjectLocation) {
        let mut delta = self
            .delta
            .write()
            .unwrap_or_else(|poison| poison.into_inner());
        delta.insert(hash, location);
        drop(delta);
        self.hot.lock().unwrap_or_else(|poison| poison.into_inner())[hot_slot(hash)] =
            Some((hash, location));
    }

    fn delta_len(&self) -> usize {
        self.delta
            .read()
            .unwrap_or_else(|poison| poison.into_inner())
            .len()
    }

    fn len(&self) -> usize {
        let segments = self
            .segments
            .read()
            .unwrap_or_else(|poison| poison.into_inner());
        let delta = self
            .delta
            .read()
            .unwrap_or_else(|poison| poison.into_inner());
        segments.iter().fold(delta.len(), |count, segment| {
            count.saturating_add(segment.entry_count)
        })
    }

    fn visit_sorted(
        &self,
        maximum: usize,
        mut visit: impl FnMut(ObjectHash, ObjectLocation) -> Result<()>,
    ) -> Result<usize> {
        let segments = self
            .segments
            .read()
            .unwrap_or_else(|poison| poison.into_inner())
            .clone();
        let delta = self
            .delta
            .read()
            .unwrap_or_else(|poison| poison.into_inner())
            .iter()
            .map(|(&hash, &location)| (hash, location))
            .collect::<Vec<_>>();
        let upper_bound = segments
            .iter()
            .try_fold(delta.len(), |count, segment| {
                count.checked_add(segment.entry_count)
            })
            .context("surface object index size overflow")?;
        if upper_bound > maximum.saturating_mul(segments.len().saturating_add(1)) {
            bail!("surface object index exceeds its entry bound");
        }

        let mut sources = segments
            .into_iter()
            .map(MergeSource::Segment)
            .chain((!delta.is_empty()).then_some(MergeSource::Delta(delta)))
            .map(|entries| MergeCursor { entries, index: 0 })
            .collect::<Vec<_>>();
        let mut heap = BinaryHeap::new();
        for (source, cursor) in sources.iter().enumerate() {
            if let Some((hash, _)) = cursor.current() {
                heap.push(Reverse((hash, source)));
            }
        }

        let mut count = 0usize;
        let mut same = Vec::with_capacity(sources.len());
        while let Some(Reverse((hash, source))) = heap.pop() {
            let mut winner = source;
            same.clear();
            same.push(source);
            while let Some(Reverse((candidate, candidate_source))) = heap.peek().copied() {
                if candidate != hash {
                    break;
                }
                heap.pop();
                winner = winner.max(candidate_source);
                same.push(candidate_source);
            }
            let location = sources[winner]
                .current()
                .expect("heap sources have current entries")
                .1;
            count = count
                .checked_add(1)
                .context("surface object index size overflow")?;
            if count > maximum {
                bail!("surface object index exceeds its entry bound");
            }
            visit(hash, location)?;
            for &source in &same {
                let cursor = &mut sources[source];
                cursor.index += 1;
                if let Some((next, _)) = cursor.current() {
                    heap.push(Reverse((next, source)));
                }
            }
        }
        Ok(count)
    }

    fn write_checkpoint(&self, path: &Path, pack_lengths: &BTreeMap<u64, u64>) -> Result<()> {
        if pack_lengths.len() > MAX_PACKS {
            bail!("surface object index exceeds its configured bounds");
        }
        let count = self.visit_sorted(MAX_INDEX_ENTRIES, |_hash, _location| Ok(()))?;
        let mut writer = IndexWriter::open(path, pack_lengths, count)?;
        self.visit_sorted(MAX_INDEX_ENTRIES, |hash, location| {
            writer.entry(hash, location)
        })?;
        writer.finish()
    }

    fn write_delta_checkpoint(
        &self,
        path: &Path,
        pack_lengths: &BTreeMap<u64, u64>,
    ) -> Result<usize> {
        let delta = self
            .delta
            .read()
            .unwrap_or_else(|poison| poison.into_inner());
        if delta.len() > MAX_INDEX_DELTA_ENTRIES || pack_lengths.len() > MAX_PACKS {
            bail!("surface object index exceeds its configured bounds");
        }
        let mut writer = IndexWriter::open(path, pack_lengths, delta.len())?;
        for (&hash, &location) in delta.iter() {
            writer.entry(hash, location)?;
        }
        writer.finish()?;
        Ok(delta.len())
    }

    fn segment_count(&self) -> usize {
        self.segments
            .read()
            .unwrap_or_else(|poison| poison.into_inner())
            .len()
    }

    fn publish_segment(&self, segment: Arc<MappedIndex>) {
        self.segments
            .write()
            .unwrap_or_else(|poison| poison.into_inner())
            .push(segment);
        self.delta
            .write()
            .unwrap_or_else(|poison| poison.into_inner())
            .clear();
    }

    fn publish_compacted(&self, segment: Arc<MappedIndex>) {
        *self
            .segments
            .write()
            .unwrap_or_else(|poison| poison.into_inner()) = vec![segment];
        self.delta
            .write()
            .unwrap_or_else(|poison| poison.into_inner())
            .clear();
    }
}

enum MergeSource {
    Segment(Arc<MappedIndex>),
    Delta(Vec<(ObjectHash, ObjectLocation)>),
}

struct MergeCursor {
    entries: MergeSource,
    index: usize,
}

impl MergeCursor {
    fn current(&self) -> Option<(ObjectHash, ObjectLocation)> {
        match &self.entries {
            MergeSource::Segment(segment) => {
                (self.index < segment.entry_count).then(|| segment.entry(self.index))
            }
            MergeSource::Delta(entries) => entries.get(self.index).copied(),
        }
    }
}

fn hot_slot(hash: ObjectHash) -> usize {
    let prefix = u64::from_le_bytes(hash.as_bytes()[..8].try_into().unwrap());
    (prefix as usize) & (HOT_INDEX_ENTRIES - 1)
}

/// Append-only content storage. Its in-memory and checkpoint indexes map canonical hashes to
/// physical locations, but neither location nor compression participates in object identity.
#[derive(Debug)]
pub struct PackStore {
    root: PathBuf,
    packs: Arc<RwLock<BTreeMap<u64, Arc<File>>>>,
    pack_lengths: BTreeMap<u64, u64>,
    index: Arc<ObjectIndex>,
    active_pack: u64,
    next_pack: u64,
    next_index_segment: AtomicU64,
    max_pack_bytes: u64,
}

/// Cloneable immutable-read handle. Append publication changes only the small delta/index Arc;
/// positional reads never borrow the writer and an old handle remains valid across pack-set
/// replacement until its leased root is released.
#[derive(Clone, Debug)]
pub struct PackReader {
    packs: Arc<RwLock<BTreeMap<u64, Arc<File>>>>,
    index: Arc<ObjectIndex>,
}

impl PackReader {
    pub fn location(&self, hash: ObjectHash) -> Option<ObjectLocation> {
        self.index.location(hash)
    }

    pub fn read_stored(&self, hash: ObjectHash) -> Result<Option<StoredObject>> {
        read_stored_from(&self.packs, &self.index, hash)
    }

    pub fn open_stored_source(&self, hash: ObjectHash) -> Result<Option<StoredObjectSource>> {
        let Some(location) = self.index.location(hash) else {
            return Ok(None);
        };
        let file = self
            .packs
            .read()
            .unwrap_or_else(|poison| poison.into_inner())
            .get(&location.pack_id)
            .cloned()
            .context("object references an unavailable pack")?;
        Ok(Some(StoredObjectSource {
            hash,
            location,
            file,
        }))
    }

    pub fn get(&self, hash: ObjectHash) -> Result<Option<CanonicalObject>> {
        get_from(self, hash)
    }
}

impl PackStore {
    pub fn open(root: impl AsRef<Path>) -> Result<Self> {
        Self::open_with_limit(root, DEFAULT_MAX_PACK_BYTES)
    }

    fn open_with_limit(root: impl AsRef<Path>, max_pack_bytes: u64) -> Result<Self> {
        if max_pack_bytes < PACK_HEADER + RECORD_HEADER as u64 + 1 {
            bail!("maximum pack size is too small");
        }
        let root = root.as_ref().to_path_buf();
        fs::create_dir_all(&root).with_context(|| format!("create {}", root.display()))?;
        let mut paths = Vec::new();
        let mut index_paths = Vec::new();
        let mut maximum_seen = None::<u64>;
        let mut maximum_index_seen = None::<u64>;
        for entry in fs::read_dir(&root)? {
            let path = entry?.path();
            if let Some(id) = parse_pack_name(&path) {
                maximum_seen = Some(maximum_seen.map_or(id, |old| old.max(id)));
                paths.push((id, path));
            } else if let Some(id) = parse_index_segment_name(&path) {
                maximum_index_seen = Some(maximum_index_seen.map_or(id, |old| old.max(id)));
                index_paths.push((id, path));
            }
        }
        paths.sort_unstable_by_key(|entry| entry.0);
        index_paths.sort_unstable_by_key(|entry| entry.0);
        let mut packs = BTreeMap::new();
        let mut pack_lengths = BTreeMap::new();
        let mut quarantined_pack = false;
        let mut valid_paths = Vec::new();
        for (id, path) in &paths {
            match open_pack_header(path, *id) {
                Ok((file, length)) => {
                    packs.insert(*id, Arc::new(file));
                    pack_lengths.insert(*id, length);
                    valid_paths.push((*id, path.clone()));
                }
                Err(error) => {
                    eprintln!(
                        "quarantining damaged surface pack {}: {error:#}",
                        path.display()
                    );
                    quarantine(path);
                    quarantined_pack = true;
                }
            }
        }

        let mut mapped_segments = Vec::new();
        let mut indexed_lengths = None;
        let mut indexes_valid = !quarantined_pack && !index_paths.is_empty();
        if indexes_valid {
            for (_, path) in &index_paths {
                match read_mapped_index(path) {
                    Ok(Some((stored_lengths, segment)))
                        if pack_lengths_cover(&pack_lengths, &stored_lengths) =>
                    {
                        indexed_lengths = Some(stored_lengths);
                        mapped_segments.push(segment);
                    }
                    Ok(_) => {
                        indexes_valid = false;
                        break;
                    }
                    Err(error) => {
                        eprintln!(
                            "discarding damaged surface object-index segment {}: {error:#}",
                            path.display()
                        );
                        indexes_valid = false;
                        break;
                    }
                }
            }
            indexes_valid &= indexed_lengths.as_ref() == Some(&pack_lengths);
        }

        let (segments, next_index_segment) = if indexes_valid {
            (
                mapped_segments,
                maximum_index_seen
                    .unwrap_or(0)
                    .checked_add(1)
                    .context("surface index-segment identifier exhausted")?,
            )
        } else {
            for (_, path) in &index_paths {
                quarantine(path);
            }
            packs.clear();
            pack_lengths.clear();
            let mut rebuilt = ExternalIndexBuilder::new(&root)?;
            for (id, path) in valid_paths {
                let run_count = rebuilt.begin_pack()?;
                match scan_pack(&path, id, |hash, location| rebuilt.push(hash, location)) {
                    Ok((file, length)) => {
                        rebuilt.finish_pack()?;
                        packs.insert(id, Arc::new(file));
                        pack_lengths.insert(id, length);
                    }
                    Err(error) => {
                        rebuilt.rollback_pack(run_count);
                        eprintln!(
                            "quarantining damaged surface pack {}: {error:#}",
                            path.display()
                        );
                        quarantine(&path);
                    }
                }
            }
            let segment_id = maximum_index_seen.map_or(Ok(0), |id| {
                id.checked_add(1)
                    .context("surface index-segment identifier exhausted")
            })?;
            let checkpoint_path = index_segment_path(&root, segment_id);
            let temporary = root.join(format!("{INDEX_SEGMENT_PREFIX}{segment_id:016x}.tmp"));
            rebuilt.finish_to_checkpoint(&temporary, &pack_lengths)?;
            fs::rename(&temporary, &checkpoint_path)?;
            sync_parent(&checkpoint_path)?;
            let (_, mapped) = read_mapped_index(&checkpoint_path)?
                .context("rebuilt surface object index disappeared")?;
            (
                vec![mapped],
                segment_id
                    .checked_add(1)
                    .context("surface index-segment identifier exhausted")?,
            )
        };
        let next_pack = maximum_seen.map_or(Ok(0), |value| {
            value
                .checked_add(1)
                .context("surface pack identifier exhausted")
        })?;
        let active_pack = match packs.last_key_value() {
            Some((&id, _)) => id,
            None => {
                let id = next_pack;
                let (file, length) = create_pack(&root, id)?;
                packs.insert(id, Arc::new(file));
                pack_lengths.insert(id, length);
                id
            }
        };
        let next_pack = next_pack.max(
            active_pack
                .checked_add(1)
                .context("surface pack identifier exhausted")?,
        );
        Ok(Self {
            root,
            packs: Arc::new(RwLock::new(packs)),
            pack_lengths,
            index: Arc::new(ObjectIndex::new(segments, BTreeMap::new())?),
            active_pack,
            next_pack,
            next_index_segment: AtomicU64::new(next_index_segment),
            max_pack_bytes,
        })
    }

    pub fn reader(&self) -> PackReader {
        PackReader {
            packs: self.packs.clone(),
            index: self.index.clone(),
        }
    }

    pub fn len(&self) -> usize {
        self.index.len()
    }

    pub fn contains(&self, hash: ObjectHash) -> bool {
        self.index.location(hash).is_some()
    }

    pub fn location(&self, hash: ObjectHash) -> Option<ObjectLocation> {
        self.index.location(hash)
    }

    pub fn visit_hashes_sorted(
        &self,
        maximum: usize,
        mut visit: impl FnMut(ObjectHash) -> Result<()>,
    ) -> Result<usize> {
        self.index
            .visit_sorted(maximum, |hash, _location| visit(hash))
    }

    pub fn put(&mut self, object: &CanonicalObject) -> Result<ObjectLocation> {
        Ok(self
            .put_many(std::iter::once(object))?
            .pop()
            .expect("one input produces one location"))
    }

    /// Compresses and appends a group with one durability barrier per touched pack. Individual
    /// records remain independently recoverable; root publication supplies generation-level
    /// atomicity after this method returns.
    pub fn put_many<'a>(
        &mut self,
        objects: impl IntoIterator<Item = &'a CanonicalObject>,
    ) -> Result<Vec<ObjectLocation>> {
        self.put_many_configured(objects, ObjectHash::ZERO, None, None)
    }

    /// Compresses each object as an independent frame with its class dictionary. The dictionary
    /// hash is stored as an immutable dependency and translated to a root-local wire ID later.
    pub fn put_many_with_dictionary<'a>(
        &mut self,
        objects: impl IntoIterator<Item = &'a CanonicalObject>,
        dictionary: &CanonicalObject,
    ) -> Result<Vec<ObjectLocation>> {
        if dictionary.kind() != ObjectKind::CompressionDictionary {
            bail!("compression dictionary has the wrong canonical object type");
        }
        let decoded = dictionary::decode(dictionary.bytes())?;
        let expected = content_kind(decoded.class);
        self.put_many_configured(
            objects,
            dictionary.hash(),
            Some(decoded.zstd),
            Some(expected),
        )
    }

    fn put_many_configured<'a>(
        &mut self,
        objects: impl IntoIterator<Item = &'a CanonicalObject>,
        dictionary: ObjectHash,
        dictionary_bytes: Option<&[u8]>,
        expected_kind: Option<ObjectKind>,
    ) -> Result<Vec<ObjectLocation>> {
        if dictionary.is_zero() != dictionary_bytes.is_none() {
            bail!("compression dictionary identity and bytes disagree");
        }
        struct Prepared<'a> {
            object: &'a CanonicalObject,
            output_indices: Vec<usize>,
        }

        let mut batch = Vec::new();
        for object in objects.into_iter() {
            if batch.len() == MAX_INDEX_DELTA_ENTRIES {
                bail!("one surface object publication batch exceeds the fixed index-delta bound");
            }
            if expected_kind.is_some_and(|expected| object.kind() != expected) {
                bail!("compression dictionary class disagrees with a content object");
            }
            batch.push(object);
        }
        let objects = batch;
        let mut output = vec![None; objects.len()];
        let mut prepared = Vec::<Prepared<'a>>::new();
        let mut pending = HashMap::<ObjectHash, usize>::new();
        for (output_index, object) in objects.iter().copied().enumerate() {
            if object.bytes().len() > MAX_CANONICAL_OBJECT_BYTES {
                bail!("canonical object exceeds {MAX_CANONICAL_OBJECT_BYTES} bytes");
            }
            if let Some(existing) = self.location(object.hash()) {
                if existing.kind != object.kind()
                    || existing.canonical_size != object.bytes().len() as u64
                {
                    bail!("existing object identity has conflicting metadata");
                }
                match self.get(object.hash()) {
                    Ok(Some(stored)) if stored == *object && existing.dictionary == dictionary => {
                        output[output_index] = Some(existing);
                        continue;
                    }
                    Ok(Some(stored)) if stored == *object => {
                        // Canonical identity is compression-independent. Supersede an older
                        // physical representation so final roots consistently publish the
                        // selected class dictionary without changing the hash.
                    }
                    // A bit-rotted record discovered after startup must not prevent repair.
                    Ok(Some(_)) | Ok(None) | Err(_) => {}
                }
            }
            if let Some(&prepared_index) = pending.get(&object.hash()) {
                let previous = &mut prepared[prepared_index];
                if previous.object != object {
                    bail!("two different canonical objects produced one hash");
                }
                previous.output_indices.push(output_index);
                continue;
            }
            pending.insert(object.hash(), prepared.len());
            prepared.push(Prepared {
                object,
                output_indices: vec![output_index],
            });
        }
        if self.index.delta_len().saturating_add(prepared.len()) > MAX_INDEX_DELTA_ENTRIES {
            self.checkpoint()?;
        }
        let mut starting_lengths = BTreeMap::<u64, u64>::new();
        let mut appended = Vec::<(ObjectHash, ObjectLocation, Vec<usize>)>::new();
        let write_result = (|| -> Result<()> {
            for item in prepared {
                // Compression and writing are deliberately sequential. Only one compressed
                // object and one codec workspace exist regardless of publication batch size.
                let compressed = compress_zstd(item.object.bytes(), dictionary_bytes)?;
                if compressed.is_empty() || compressed.len() > MAX_COMPRESSED_OBJECT_BYTES {
                    bail!("compressed object is empty or too large");
                }
                let record_size = RECORD_HEADER as u64
                    + compressed.len() as u64
                    + record_padding(compressed.len()) as u64;
                let current_length = self.pack_lengths[&self.active_pack];
                if current_length > PACK_HEADER
                    && current_length.saturating_add(record_size) > self.max_pack_bytes
                {
                    self.rotate()?;
                }
                let pack_id = self.active_pack;
                let record_offset = self.pack_lengths[&pack_id];
                starting_lengths.entry(pack_id).or_insert(record_offset);
                let location = ObjectLocation {
                    pack_id,
                    record_offset,
                    canonical_size: item.object.bytes().len() as u64,
                    compressed_size: compressed.len() as u64,
                    compressed_crc: crc32c(&compressed),
                    kind: item.object.kind(),
                    dictionary,
                };
                let header = encode_record_header(item.object.hash(), location);
                let file = self.pack_file(pack_id)?;
                file.write_all_at(&header, record_offset)?;
                file.write_all_at(&compressed, location.payload_offset())?;
                let padding = record_padding(compressed.len());
                if padding != 0 {
                    file.write_all_at(
                        &[0; 7][..padding],
                        location.payload_offset() + compressed.len() as u64,
                    )?;
                }
                let end = record_offset + record_size;
                file.set_len(end)?;
                self.pack_lengths.insert(pack_id, end);
                appended.push((item.object.hash(), location, item.output_indices));
            }
            for pack_id in starting_lengths.keys() {
                self.pack_file(*pack_id)?.sync_data()?;
            }
            Ok(())
        })();
        if let Err(error) = write_result {
            let mut rollback_error = None;
            for (&pack_id, &length) in &starting_lengths {
                if let Some(file) = self
                    .packs
                    .read()
                    .unwrap_or_else(|poison| poison.into_inner())
                    .get(&pack_id)
                    .cloned()
                    && let Err(failure) = file.set_len(length).and_then(|()| file.sync_all())
                {
                    rollback_error.get_or_insert(failure);
                }
                self.pack_lengths.insert(pack_id, length);
            }
            if let Some(rollback) = rollback_error {
                return Err(error).context(format!("pack append rollback also failed: {rollback}"));
            }
            return Err(error);
        }

        // No hash becomes visible until every touched pack has crossed its durability barrier.
        for (hash, location, indices) in appended {
            self.index.insert(hash, location);
            for index in indices {
                output[index] = Some(location);
            }
        }
        if self.index.delta_len() == MAX_INDEX_DELTA_ENTRIES {
            self.checkpoint()?;
        }
        Ok(output
            .into_iter()
            .map(|location| location.expect("every input was resolved"))
            .collect())
    }

    pub fn read_stored(&self, hash: ObjectHash) -> Result<Option<StoredObject>> {
        self.reader().read_stored(hash)
    }

    pub fn get(&self, hash: ObjectHash) -> Result<Option<CanonicalObject>> {
        self.reader().get(hash)
    }

    pub fn sync_all(&self) -> Result<()> {
        let packs = self
            .packs
            .read()
            .unwrap_or_else(|poison| poison.into_inner());
        for file in packs.values() {
            file.sync_all()?;
        }
        sync_parent(&self.root.join("pack-placeholder"))
    }

    /// Publishes a disposable, checksummed delta index. Most root transactions write only the
    /// newly appended hash locations; occasional consolidation bounds lookup depth. Pack data
    /// remains authoritative and can rebuild every segment after a crash.
    pub fn checkpoint(&self) -> Result<()> {
        if self.index.delta_len() == 0 {
            return Ok(());
        }
        let segment_id = self
            .next_index_segment
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |id| id.checked_add(1))
            .map_err(|_| anyhow::anyhow!("surface index-segment identifier exhausted"))?;
        let path = index_segment_path(&self.root, segment_id);
        let temporary = self
            .root
            .join(format!("{INDEX_SEGMENT_PREFIX}{segment_id:016x}.tmp"));
        let compact = self.index.segment_count() >= MAX_INDEX_SEGMENTS;
        if compact {
            self.index
                .write_checkpoint(&temporary, &self.pack_lengths)?;
        } else {
            self.index
                .write_delta_checkpoint(&temporary, &self.pack_lengths)?;
        }
        fs::rename(&temporary, &path)?;
        sync_parent(&path)?;
        let (lengths, mapped) = read_mapped_index(&path)?.context("new index disappeared")?;
        if lengths != self.pack_lengths {
            bail!("newly written surface object index failed validation");
        }
        if compact {
            self.index.publish_compacted(mapped);
            for entry in fs::read_dir(&self.root)? {
                let old = entry?.path();
                if parse_index_segment_name(&old).is_some() && old != path {
                    match fs::remove_file(&old) {
                        Ok(()) => {}
                        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                        Err(error) => return Err(error).context("remove superseded index segment"),
                    }
                }
            }
            sync_parent(&path)?;
        } else {
            self.index.publish_segment(mapped);
        }
        Ok(())
    }

    fn rotate(&mut self) -> Result<()> {
        let id = self.next_pack;
        self.next_pack = self
            .next_pack
            .checked_add(1)
            .context("surface pack identifier overflow")?;
        let (file, length) = create_pack(&self.root, id)?;
        self.packs
            .write()
            .unwrap_or_else(|poison| poison.into_inner())
            .insert(id, Arc::new(file));
        self.pack_lengths.insert(id, length);
        self.active_pack = id;
        Ok(())
    }

    fn pack_file(&self, id: u64) -> Result<Arc<File>> {
        self.packs
            .read()
            .unwrap_or_else(|poison| poison.into_inner())
            .get(&id)
            .cloned()
            .context("object references an unavailable pack")
    }
}

fn read_stored_from(
    packs: &RwLock<BTreeMap<u64, Arc<File>>>,
    index: &ObjectIndex,
    hash: ObjectHash,
) -> Result<Option<StoredObject>> {
    let Some(location) = index.location(hash) else {
        return Ok(None);
    };
    let length =
        usize::try_from(location.compressed_size).context("compressed object length overflow")?;
    if length == 0 || length > MAX_COMPRESSED_OBJECT_BYTES {
        bail!("stored compressed object length is invalid");
    }
    let mut compressed = vec![0; length];
    let file = packs
        .read()
        .unwrap_or_else(|poison| poison.into_inner())
        .get(&location.pack_id)
        .cloned()
        .context("object references an unavailable pack")?;
    file.read_exact_at(&mut compressed, location.payload_offset())?;
    if crc32c(&compressed) != location.compressed_crc {
        bail!("stored compressed object checksum mismatch");
    }
    Ok(Some(StoredObject {
        hash,
        kind: location.kind,
        dictionary: location.dictionary,
        canonical_size: location.canonical_size,
        compressed_crc: location.compressed_crc,
        compressed,
    }))
}

fn get_from(reader: &PackReader, hash: ObjectHash) -> Result<Option<CanonicalObject>> {
    let Some(stored) = read_stored_from(&reader.packs, &reader.index, hash)? else {
        return Ok(None);
    };
    if stored.dictionary.is_zero() {
        return stored.decode().map(Some);
    }
    let dictionary = read_stored_from(&reader.packs, &reader.index, stored.dictionary)?
        .with_context(|| format!("compression dictionary {} is missing", stored.dictionary))?;
    if dictionary.kind != ObjectKind::CompressionDictionary || !dictionary.dictionary.is_zero() {
        bail!("compression dictionary has an invalid stored type or dependency");
    }
    let dictionary = dictionary.decode()?;
    let dictionary = dictionary::decode(dictionary.bytes())?;
    stored
        .decode_with_dictionary(Some(dictionary.zstd))
        .map(Some)
}

fn compress_zstd(bytes: &[u8], dictionary: Option<&[u8]>) -> Result<Vec<u8>> {
    match dictionary {
        Some(dictionary) => {
            Ok(zstd::bulk::Compressor::with_dictionary(ZSTD_LEVEL, dictionary)?.compress(bytes)?)
        }
        None => Ok(zstd::bulk::compress(bytes, ZSTD_LEVEL)?),
    }
}

fn decompress_zstd(
    compressed: &[u8],
    maximum: usize,
    dictionary: Option<&[u8]>,
) -> Result<Vec<u8>> {
    match dictionary {
        Some(dictionary) => Ok(zstd::bulk::Decompressor::with_dictionary(dictionary)?
            .decompress(compressed, maximum)?),
        None => Ok(zstd::bulk::decompress(compressed, maximum)?),
    }
}

fn create_pack(root: &Path, id: u64) -> Result<(File, u64)> {
    let path = pack_path(root, id);
    let file = OpenOptions::new()
        .create_new(true)
        .read(true)
        .write(true)
        .open(&path)
        .with_context(|| format!("create surface pack {}", path.display()))?;
    let header = encode_pack_header(id);
    file.write_all_at(&header, 0)?;
    file.set_len(PACK_HEADER)?;
    file.sync_all()?;
    sync_parent(&path)?;
    Ok((file, PACK_HEADER))
}

fn open_pack_header(path: &Path, expected_id: u64) -> Result<(File, u64)> {
    let file = OpenOptions::new().read(true).write(true).open(path)?;
    let length = file.metadata()?.len();
    if length < PACK_HEADER {
        bail!("truncated surface pack header");
    }
    let mut header = [0u8; PACK_HEADER as usize];
    file.read_exact_at(&mut header, 0)?;
    if decode_pack_header(&header)? != expected_id {
        bail!("pack filename and header identifiers disagree");
    }
    Ok((file, length))
}

fn scan_pack(
    path: &Path,
    expected_id: u64,
    mut visit: impl FnMut(ObjectHash, ObjectLocation) -> Result<()>,
) -> Result<(File, u64)> {
    let file = OpenOptions::new().read(true).write(true).open(path)?;
    let mut pack_header = [0u8; PACK_HEADER as usize];
    file.read_exact_at(&mut pack_header, 0)?;
    let id = decode_pack_header(&pack_header)?;
    if id != expected_id {
        bail!("pack filename and header identifiers disagree");
    }
    let original_length = file.metadata()?.len();
    let mut length = original_length;
    let mut offset = PACK_HEADER;
    while offset < length {
        if length - offset < RECORD_HEADER as u64 {
            truncate_tail(&file, offset)?;
            length = offset;
            break;
        }
        let mut header = [0u8; RECORD_HEADER];
        file.read_exact_at(&mut header, offset)?;
        let (hash, mut location) = decode_record_header(&header)?;
        location.pack_id = id;
        location.record_offset = offset;
        let compressed_len = usize::try_from(location.compressed_size)
            .context("compressed record length overflow")?;
        let total = (RECORD_HEADER as u64)
            .checked_add(location.compressed_size)
            .and_then(|value| value.checked_add(record_padding(compressed_len) as u64))
            .context("object record length overflow")?;
        if offset.checked_add(total).is_none_or(|end| end > length) {
            truncate_tail(&file, offset)?;
            length = offset;
            break;
        }
        let mut compressed = vec![0; compressed_len];
        file.read_exact_at(&mut compressed, location.payload_offset())?;
        let padding = record_padding(compressed_len);
        if padding != 0 {
            let mut bytes = [0u8; 7];
            file.read_exact_at(
                &mut bytes[..padding],
                location.payload_offset() + compressed_len as u64,
            )?;
            if bytes[..padding] != [0; 7][..padding] {
                bail!("object record has nonzero padding");
            }
        }
        if crc32c(&compressed) == location.compressed_crc {
            visit(hash, location)?;
        }
        offset += total;
    }
    Ok((file, length))
}

fn truncate_tail(file: &File, length: u64) -> Result<()> {
    file.set_len(length)?;
    file.sync_all()?;
    Ok(())
}

fn encode_pack_header(id: u64) -> [u8; PACK_HEADER as usize] {
    let mut bytes = [0u8; PACK_HEADER as usize];
    bytes[..8].copy_from_slice(PACK_MAGIC);
    bytes[12..20].copy_from_slice(&id.to_le_bytes());
    let crc = crc32c(&bytes[..28]);
    bytes[28..32].copy_from_slice(&crc.to_le_bytes());
    bytes
}

fn decode_pack_header(bytes: &[u8; PACK_HEADER as usize]) -> Result<u64> {
    if &bytes[..8] != PACK_MAGIC
        || bytes[8..12] != [0; 4]
        || bytes[20..28] != [0; 8]
        || u32::from_le_bytes(bytes[28..32].try_into().unwrap()) != crc32c(&bytes[..28])
    {
        bail!("invalid surface pack header");
    }
    Ok(u64::from_le_bytes(bytes[12..20].try_into().unwrap()))
}

fn encode_record_header(hash: ObjectHash, location: ObjectLocation) -> [u8; RECORD_HEADER] {
    let mut bytes = [0u8; RECORD_HEADER];
    bytes[..4].copy_from_slice(RECORD_MAGIC);
    bytes[4] = location.kind as u8;
    bytes[12..20].copy_from_slice(&location.canonical_size.to_le_bytes());
    bytes[20..28].copy_from_slice(&location.compressed_size.to_le_bytes());
    bytes[28..32].copy_from_slice(&location.compressed_crc.to_le_bytes());
    bytes[32..64].copy_from_slice(hash.as_bytes());
    bytes[64..96].copy_from_slice(location.dictionary.as_bytes());
    let crc = crc32c(&bytes[..100]);
    bytes[100..104].copy_from_slice(&crc.to_le_bytes());
    bytes
}

fn decode_record_header(bytes: &[u8; RECORD_HEADER]) -> Result<(ObjectHash, ObjectLocation)> {
    if &bytes[..4] != RECORD_MAGIC
        || bytes[5..12] != [0; 7]
        || bytes[96..100] != [0; 4]
        || u32::from_le_bytes(bytes[100..104].try_into().unwrap()) != crc32c(&bytes[..100])
    {
        bail!("invalid surface object record header");
    }
    let kind = ObjectKind::try_from(bytes[4])?;
    let canonical_size = u64::from_le_bytes(bytes[12..20].try_into().unwrap());
    let compressed_size = u64::from_le_bytes(bytes[20..28].try_into().unwrap());
    if canonical_size > MAX_CANONICAL_OBJECT_BYTES as u64
        || compressed_size == 0
        || compressed_size > MAX_COMPRESSED_OBJECT_BYTES as u64
    {
        bail!("surface object record sizes are invalid");
    }
    let hash = ObjectHash::from_bytes(bytes[32..64].try_into().unwrap())?;
    let dictionary = ObjectHash::from_stored_bytes(bytes[64..96].try_into().unwrap());
    Ok((
        hash,
        ObjectLocation {
            pack_id: 0,
            record_offset: 0,
            canonical_size,
            compressed_size,
            compressed_crc: u32::from_le_bytes(bytes[28..32].try_into().unwrap()),
            kind,
            dictionary,
        },
    ))
}

fn decode_index_entry(bytes: &[u8]) -> Result<(ObjectHash, ObjectLocation)> {
    if bytes.len() != INDEX_ENTRY {
        bail!("surface object index entry has the wrong fixed size");
    }
    if bytes[69..72] != [0; 3] {
        bail!("surface object index entry has nonzero reserved bytes");
    }
    let hash = ObjectHash::from_bytes(bytes[..32].try_into().unwrap())?;
    let location = ObjectLocation {
        pack_id: u64::from_le_bytes(bytes[32..40].try_into().unwrap()),
        record_offset: u64::from_le_bytes(bytes[40..48].try_into().unwrap()),
        canonical_size: u64::from_le_bytes(bytes[48..56].try_into().unwrap()),
        compressed_size: u64::from_le_bytes(bytes[56..64].try_into().unwrap()),
        compressed_crc: u32::from_le_bytes(bytes[64..68].try_into().unwrap()),
        kind: ObjectKind::try_from(bytes[68])?,
        dictionary: ObjectHash::from_stored_bytes(bytes[72..104].try_into().unwrap()),
    };
    Ok((hash, location))
}

fn encode_index_entry(hash: ObjectHash, location: ObjectLocation) -> [u8; INDEX_ENTRY] {
    let mut bytes = [0u8; INDEX_ENTRY];
    bytes[..32].copy_from_slice(hash.as_bytes());
    bytes[32..40].copy_from_slice(&location.pack_id.to_le_bytes());
    bytes[40..48].copy_from_slice(&location.record_offset.to_le_bytes());
    bytes[48..56].copy_from_slice(&location.canonical_size.to_le_bytes());
    bytes[56..64].copy_from_slice(&location.compressed_size.to_le_bytes());
    bytes[64..68].copy_from_slice(&location.compressed_crc.to_le_bytes());
    bytes[68] = location.kind as u8;
    bytes[72..104].copy_from_slice(location.dictionary.as_bytes());
    bytes
}

struct IndexWriter {
    file: File,
    offset: u64,
    checksum: u32,
    expected_entries: usize,
    written_entries: usize,
    previous: Option<ObjectHash>,
}

impl IndexWriter {
    fn open(path: &Path, pack_lengths: &BTreeMap<u64, u64>, entry_count: usize) -> Result<Self> {
        if entry_count > MAX_INDEX_ENTRIES || pack_lengths.len() > MAX_PACKS {
            bail!("surface object index exceeds its configured bounds");
        }
        let file = OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(path)?;
        let mut writer = Self {
            file,
            offset: 0,
            checksum: 0,
            expected_entries: entry_count,
            written_entries: 0,
            previous: None,
        };
        let mut header = [0u8; 24];
        header[..8].copy_from_slice(INDEX_MAGIC);
        header[12..16].copy_from_slice(&(pack_lengths.len() as u32).to_le_bytes());
        header[16..24].copy_from_slice(&(entry_count as u64).to_le_bytes());
        writer.write(&header)?;
        for (&id, &length) in pack_lengths {
            let mut record = [0u8; 16];
            record[..8].copy_from_slice(&id.to_le_bytes());
            record[8..].copy_from_slice(&length.to_le_bytes());
            writer.write(&record)?;
        }
        Ok(writer)
    }

    fn write(&mut self, bytes: &[u8]) -> Result<()> {
        self.file.write_all_at(bytes, self.offset)?;
        self.offset = self
            .offset
            .checked_add(bytes.len() as u64)
            .context("surface index offset overflow")?;
        self.checksum = crc32c::crc32c_append(self.checksum, bytes);
        Ok(())
    }

    fn entry(&mut self, hash: ObjectHash, location: ObjectLocation) -> Result<()> {
        if self.previous.is_some_and(|old| old >= hash) {
            bail!("surface object index entries are not strictly sorted");
        }
        self.previous = Some(hash);
        self.write(&encode_index_entry(hash, location))?;
        self.written_entries += 1;
        Ok(())
    }

    fn finish(self) -> Result<()> {
        if self.written_entries != self.expected_entries {
            bail!("surface index merge produced the wrong entry count");
        }
        self.file
            .write_all_at(&self.checksum.to_le_bytes(), self.offset)?;
        self.file.set_len(self.offset + 4)?;
        self.file.sync_all()?;
        Ok(())
    }
}

#[derive(Debug)]
struct IndexRun {
    path: PathBuf,
    count: usize,
}

struct ExternalIndexBuilder {
    root: PathBuf,
    next: usize,
    buffer: Vec<(ObjectHash, ObjectLocation)>,
    runs: Vec<IndexRun>,
    paths: Vec<PathBuf>,
}

impl ExternalIndexBuilder {
    fn new(root: &Path) -> Result<Self> {
        Ok(Self {
            root: root.to_path_buf(),
            next: 0,
            buffer: Vec::with_capacity(INDEX_REBUILD_BUFFER_ENTRIES),
            runs: Vec::new(),
            paths: Vec::new(),
        })
    }

    fn push(&mut self, hash: ObjectHash, location: ObjectLocation) -> Result<()> {
        self.buffer.push((hash, location));
        if self.buffer.len() == INDEX_REBUILD_BUFFER_ENTRIES {
            self.flush()?;
        }
        Ok(())
    }

    fn begin_pack(&mut self) -> Result<usize> {
        self.flush()?;
        Ok(self.runs.len())
    }

    fn rollback_pack(&mut self, run_count: usize) {
        self.buffer.clear();
        while self.runs.len() > run_count {
            if let Some(run) = self.runs.pop() {
                let _ = fs::remove_file(run.path);
            }
        }
    }

    fn finish_pack(&mut self) -> Result<()> {
        self.flush()
    }

    fn run_path(&mut self) -> PathBuf {
        let path = self
            .root
            .join(format!("objects.vxindex.rebuild.{:08x}.tmp", self.next));
        self.next += 1;
        self.paths.push(path.clone());
        path
    }

    fn flush(&mut self) -> Result<()> {
        if self.buffer.is_empty() {
            return Ok(());
        }
        self.buffer.sort_unstable_by_key(|(hash, location)| {
            (*hash, location.pack_id, location.record_offset)
        });
        let mut kept = 0usize;
        for read in 0..self.buffer.len() {
            if kept != 0 && self.buffer[kept - 1].0 == self.buffer[read].0 {
                // Sort order puts the newest physical repair last.
                self.buffer[kept - 1] = self.buffer[read];
            } else {
                self.buffer.swap(kept, read);
                kept += 1;
            }
        }
        self.buffer.truncate(kept);
        let path = self.run_path();
        let mut output = std::io::BufWriter::new(File::create(&path)?);
        for &(hash, location) in &self.buffer {
            output.write_all(&encode_index_entry(hash, location))?;
        }
        output.flush()?;
        output.get_ref().sync_all()?;
        self.runs.push(IndexRun {
            path,
            count: self.buffer.len(),
        });
        self.buffer.clear();
        Ok(())
    }

    fn finish_to_checkpoint(
        mut self,
        temporary: &Path,
        pack_lengths: &BTreeMap<u64, u64>,
    ) -> Result<()> {
        self.flush()?;
        while self.runs.len() > 1 {
            let mut merged = Vec::with_capacity(self.runs.len().div_ceil(2));
            let mut runs = std::mem::take(&mut self.runs).into_iter();
            while let Some(left) = runs.next() {
                match runs.next() {
                    Some(right) => merged.push(self.merge(left, right)?),
                    None => merged.push(left),
                }
            }
            self.runs = merged;
        }
        let count = self.runs.first().map_or(0, |run| run.count);
        let mut writer = IndexWriter::open(temporary, pack_lengths, count)?;
        if let Some(run) = self.runs.pop() {
            let mut input = std::io::BufReader::new(File::open(&run.path)?);
            let mut bytes = [0u8; INDEX_ENTRY];
            for _ in 0..run.count {
                input.read_exact(&mut bytes)?;
                let (hash, location) = decode_index_entry(&bytes)?;
                writer.entry(hash, location)?;
            }
        }
        writer.finish()
    }

    fn merge(&mut self, left: IndexRun, right: IndexRun) -> Result<IndexRun> {
        let path = self.run_path();
        let mut output = std::io::BufWriter::new(File::create(&path)?);
        let mut left_reader = IndexRunReader::new(&left)?;
        let mut right_reader = IndexRunReader::new(&right)?;
        let mut left_next = left_reader.next()?;
        let mut right_next = right_reader.next()?;
        let mut count = 0usize;
        while left_next.is_some() || right_next.is_some() {
            let selected = match (left_next, right_next) {
                (Some(left_entry), Some(right_entry)) => match left_entry.0.cmp(&right_entry.0) {
                    std::cmp::Ordering::Less => {
                        left_next = left_reader.next()?;
                        left_entry
                    }
                    std::cmp::Ordering::Greater => {
                        right_next = right_reader.next()?;
                        right_entry
                    }
                    std::cmp::Ordering::Equal => {
                        left_next = left_reader.next()?;
                        right_next = right_reader.next()?;
                        if newer_location(left_entry.1, right_entry.1) {
                            left_entry
                        } else {
                            right_entry
                        }
                    }
                },
                (Some(entry), None) => {
                    left_next = left_reader.next()?;
                    entry
                }
                (None, Some(entry)) => {
                    right_next = right_reader.next()?;
                    entry
                }
                (None, None) => break,
            };
            output.write_all(&encode_index_entry(selected.0, selected.1))?;
            count += 1;
        }
        output.flush()?;
        output.get_ref().sync_all()?;
        let _ = fs::remove_file(left.path);
        let _ = fs::remove_file(right.path);
        Ok(IndexRun { path, count })
    }
}

impl Drop for ExternalIndexBuilder {
    fn drop(&mut self) {
        for path in &self.paths {
            let _ = fs::remove_file(path);
        }
    }
}

struct IndexRunReader {
    input: std::io::BufReader<File>,
    remaining: usize,
}

impl IndexRunReader {
    fn new(run: &IndexRun) -> Result<Self> {
        Ok(Self {
            input: std::io::BufReader::new(File::open(&run.path)?),
            remaining: run.count,
        })
    }

    fn next(&mut self) -> Result<Option<(ObjectHash, ObjectLocation)>> {
        if self.remaining == 0 {
            return Ok(None);
        }
        let mut bytes = [0u8; INDEX_ENTRY];
        self.input.read_exact(&mut bytes)?;
        self.remaining -= 1;
        decode_index_entry(&bytes).map(Some)
    }
}

fn newer_location(left: ObjectLocation, right: ObjectLocation) -> bool {
    (left.pack_id, left.record_offset) > (right.pack_id, right.record_offset)
}

fn read_mapped_index(path: &Path) -> Result<Option<(BTreeMap<u64, u64>, Arc<MappedIndex>)>> {
    match fs::metadata(path) {
        Ok(_) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    }
    let map = match ReadOnlyMap::open(path) {
        Ok(map) => map,
        Err(error) => {
            if error
                .downcast_ref::<std::io::Error>()
                .is_some_and(|error| error.kind() == std::io::ErrorKind::NotFound)
            {
                return Ok(None);
            }
            return Err(error);
        }
    };
    let bytes = map.bytes();
    if bytes.len() < 28 || &bytes[..8] != INDEX_MAGIC {
        bail!("bad surface object index header");
    }
    let expected_crc = u32::from_le_bytes(bytes[bytes.len() - 4..].try_into().unwrap());
    if crc32c(&bytes[..bytes.len() - 4]) != expected_crc {
        bail!("surface object index checksum mismatch");
    }
    let reserved = u32::from_le_bytes(bytes[8..12].try_into().unwrap());
    let pack_count = u32::from_le_bytes(bytes[12..16].try_into().unwrap()) as usize;
    let entry_count = usize::try_from(u64::from_le_bytes(bytes[16..24].try_into().unwrap()))
        .context("surface object index entry count overflow")?;
    if reserved != 0 || pack_count > MAX_PACKS || entry_count > MAX_INDEX_ENTRIES {
        bail!("unsupported or oversized surface object index");
    }
    let entries_offset = 24usize
        .checked_add(pack_count.checked_mul(16).context("pack index overflow")?)
        .context("surface object index offset overflow")?;
    let expected = entries_offset
        .checked_add(
            entry_count
                .checked_mul(INDEX_ENTRY)
                .context("index size overflow")?,
        )
        .and_then(|value| value.checked_add(4))
        .context("surface object index length overflow")?;
    if bytes.len() != expected {
        bail!("surface object index length mismatch");
    }
    let mut lengths = BTreeMap::new();
    let mut cursor = 24;
    for _ in 0..pack_count {
        let id = u64::from_le_bytes(bytes[cursor..cursor + 8].try_into().unwrap());
        let length = u64::from_le_bytes(bytes[cursor + 8..cursor + 16].try_into().unwrap());
        cursor += 16;
        if length < PACK_HEADER || lengths.insert(id, length).is_some() {
            bail!("duplicate or invalid pack in surface object index");
        }
    }
    let mut previous = None;
    for index in 0..entry_count {
        let offset = entries_offset + index * INDEX_ENTRY;
        let (hash, location) = decode_index_entry(&bytes[offset..offset + INDEX_ENTRY])?;
        if previous.is_some_and(|old| old >= hash) || !location_valid(&lengths, location) {
            bail!("surface object index is unsorted or contains an invalid location");
        }
        previous = Some(hash);
    }
    let mut prefixes = [(0u32, 0u32); PREFIXES];
    let mut index = 0usize;
    for (prefix, bounds) in prefixes.iter_mut().enumerate() {
        let start = index;
        while index < entry_count {
            let offset = entries_offset + index * INDEX_ENTRY;
            if bytes[offset] as usize != prefix {
                break;
            }
            index += 1;
        }
        *bounds = (start as u32, index as u32);
    }
    map.discard_resident_pages();
    Ok(Some((
        lengths,
        Arc::new(MappedIndex {
            map,
            entries_offset,
            entry_count,
            prefixes,
        }),
    )))
}

fn location_valid(lengths: &BTreeMap<u64, u64>, location: ObjectLocation) -> bool {
    let Ok(compressed) = usize::try_from(location.compressed_size) else {
        return false;
    };
    lengths.get(&location.pack_id).is_some_and(|length| {
        location.record_offset >= PACK_HEADER
            && (location.record_offset - PACK_HEADER) & 7 == 0
            && location.canonical_size <= MAX_CANONICAL_OBJECT_BYTES as u64
            && compressed != 0
            && compressed <= MAX_COMPRESSED_OBJECT_BYTES
            && location
                .payload_offset()
                .checked_add(location.compressed_size)
                .and_then(|value| value.checked_add(record_padding(compressed) as u64))
                .is_some_and(|end| end <= *length)
    })
}

fn pack_lengths_cover(current: &BTreeMap<u64, u64>, checkpoint: &BTreeMap<u64, u64>) -> bool {
    checkpoint
        .iter()
        .all(|(id, length)| current.get(id).is_some_and(|current| current >= length))
}

fn record_padding(compressed: usize) -> usize {
    (8 - ((RECORD_HEADER + compressed) & 7)) & 7
}

fn pack_path(root: &Path, id: u64) -> PathBuf {
    root.join(format!("pack-{id:016x}.vxp"))
}

fn index_segment_path(root: &Path, id: u64) -> PathBuf {
    root.join(format!("{INDEX_SEGMENT_PREFIX}{id:016x}"))
}

fn parse_index_segment_name(path: &Path) -> Option<u64> {
    let encoded = path
        .file_name()?
        .to_str()?
        .strip_prefix(INDEX_SEGMENT_PREFIX)?;
    (encoded.len() == 16)
        .then(|| u64::from_str_radix(encoded, 16).ok())
        .flatten()
}

fn parse_pack_name(path: &Path) -> Option<u64> {
    let name = path.file_name()?.to_str()?;
    let encoded = name.strip_prefix("pack-")?.strip_suffix(".vxp")?;
    (encoded.len() == 16)
        .then(|| u64::from_str_radix(encoded, 16).ok())
        .flatten()
}
