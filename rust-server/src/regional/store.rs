use super::{RegionIndex, section::SectionFrame};
use crate::{crc::crc32c, sync_parent};
use anyhow::{Context, Result, bail};
use std::{
    fs::{self, File, OpenOptions},
    io::Write,
    os::unix::fs::FileExt,
    path::{Path, PathBuf},
    sync::Arc,
    sync::atomic::{AtomicU64, Ordering},
};

const REGION_MAGIC: &[u8; 8] = b"VXYRGN\0\0";
const REGION_HEADER_BYTES: usize = 256;
pub(crate) const SECTION_ENTRY_BYTES: usize = 48;
const ZSTD_LEVEL: i32 = 1;
const MAX_REGION_FILE_BYTES: u64 = 256 * 1024 * 1024;
const MAX_SECTION_CANONICAL_BYTES: usize = 4 * 1024 * 1024;
const MAX_SECTION_COMPRESSED_BYTES: usize = 4 * 1024 * 1024;
const REGION_ENTRY_PRESENT: u16 = 1 << 15;
pub const SECTION_FLAG_EMPTY: u16 = 1 << 0;
const KNOWN_ENTRY_FLAGS: u16 = REGION_ENTRY_PRESENT | SECTION_FLAG_EMPTY;

static NEXT_TEMPORARY: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct SectionCoordinate {
    pub level: u8,
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

impl From<crate::key::SectionKey> for SectionCoordinate {
    fn from(key: crate::key::SectionKey) -> Self {
        Self {
            level: key.level,
            x: key.x,
            y: key.y,
            z: key.z,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RegionLayout {
    pub min_base_y: i32,
    pub base_y_count: u16,
    pub levels: u8,
}

impl RegionLayout {
    pub fn new(min_base_y: i32, base_y_count: u16, levels: u8) -> Result<Self> {
        let end = min_base_y
            .checked_add(i32::from(base_y_count))
            .context("regional vertical range overflow")?;
        if base_y_count == 0
            || levels == 0
            || levels > crate::MAX_LOD + 1
            || min_base_y < -128
            || end > 128
        {
            bail!("regional section layout is outside Voxy's coordinate bounds");
        }
        let layout = Self {
            min_base_y,
            base_y_count,
            levels,
        };
        if layout.entry_count()? > u32::MAX as usize {
            bail!("regional section directory exceeds u32 entries");
        }
        Ok(layout)
    }

    pub fn entry_count(self) -> Result<usize> {
        let mut total = 0usize;
        for level in 0..self.levels {
            let side = horizontal_side(level)?;
            total = total
                .checked_add(
                    side.checked_mul(side)
                        .and_then(|plane| plane.checked_mul(self.y_count(level).ok()?))
                        .context("regional section directory size overflow")?,
                )
                .context("regional section directory size overflow")?;
        }
        Ok(total)
    }

    pub fn level_y_range(self, level: u8) -> Result<std::ops::Range<i32>> {
        if level >= self.levels {
            bail!("section LOD is outside its regional layout");
        }
        let start = self.min_y(level);
        Ok(start..start + self.y_count(level)? as i32)
    }

    pub fn horizontal_side(self, level: u8) -> Result<usize> {
        if level >= self.levels {
            bail!("section LOD is outside its regional layout");
        }
        horizontal_side(level)
    }

    pub fn index(
        self,
        region_x: i32,
        region_z: i32,
        coordinate: SectionCoordinate,
    ) -> Result<usize> {
        if coordinate.level >= self.levels {
            bail!("section LOD is outside its regional layout");
        }
        let side = horizontal_side(coordinate.level)?;
        let base_x = region_x
            .checked_mul(side as i32)
            .context("regional x coordinate overflow")?;
        let base_z = region_z
            .checked_mul(side as i32)
            .context("regional z coordinate overflow")?;
        let local_x = coordinate.x - base_x;
        let local_z = coordinate.z - base_z;
        let min_y = self.min_y(coordinate.level);
        let local_y = coordinate.y - min_y;
        let y_count = self.y_count(coordinate.level)?;
        if !(0..side as i32).contains(&local_x)
            || !(0..side as i32).contains(&local_z)
            || !(0..y_count as i32).contains(&local_y)
        {
            bail!("section coordinate is not owned by the regional shard");
        }
        let mut offset = 0usize;
        for level in 0..coordinate.level {
            let previous_side = horizontal_side(level)?;
            offset = offset
                .checked_add(previous_side * previous_side * self.y_count(level)?)
                .context("regional section index overflow")?;
        }
        offset
            .checked_add((local_y as usize * side + local_z as usize) * side + local_x as usize)
            .context("regional section index overflow")
    }

    pub fn coordinate(
        self,
        region_x: i32,
        region_z: i32,
        mut index: usize,
    ) -> Result<SectionCoordinate> {
        if index >= self.entry_count()? {
            bail!("regional section index is out of range");
        }
        for level in 0..self.levels {
            let side = horizontal_side(level)?;
            let level_count = side * side * self.y_count(level)?;
            if index >= level_count {
                index -= level_count;
                continue;
            }
            let x = index % side;
            index /= side;
            let z = index % side;
            let y = index / side;
            return Ok(SectionCoordinate {
                level,
                x: region_x * side as i32 + x as i32,
                y: self.min_y(level) + y as i32,
                z: region_z * side as i32 + z as i32,
            });
        }
        unreachable!("validated regional index belongs to one level")
    }

    fn min_y(self, level: u8) -> i32 {
        self.min_base_y.div_euclid(1 << level)
    }

    fn y_count(self, level: u8) -> Result<usize> {
        let scale = 1i32 << level;
        let maximum = self
            .min_base_y
            .checked_add(i32::from(self.base_y_count) - 1)
            .context("regional vertical range overflow")?;
        Ok((maximum.div_euclid(scale) - self.min_y(level) + 1) as usize)
    }
}

fn horizontal_side(level: u8) -> Result<usize> {
    (level <= 4)
        .then_some(16usize >> level)
        .context("regional LOD has no horizontal ownership grid")
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct RegionSectionEntry {
    pub flags: u16,
    pub non_empty_children: u8,
    pub payload_offset: u64,
    pub compressed_length: u32,
    pub canonical_length: u32,
    pub compressed_crc: u32,
    pub fingerprint: [u8; 16],
}

impl RegionSectionEntry {
    pub fn is_present(self) -> bool {
        self.flags & REGION_ENTRY_PRESENT != 0
    }

    pub fn is_empty(self) -> bool {
        self.flags & SECTION_FLAG_EMPTY != 0
    }

    pub(crate) fn encode(self) -> [u8; SECTION_ENTRY_BYTES] {
        let mut output = [0u8; SECTION_ENTRY_BYTES];
        output[0..2].copy_from_slice(&self.flags.to_le_bytes());
        output[2] = self.non_empty_children;
        output[8..16].copy_from_slice(&self.payload_offset.to_le_bytes());
        output[16..20].copy_from_slice(&self.compressed_length.to_le_bytes());
        output[20..24].copy_from_slice(&self.canonical_length.to_le_bytes());
        output[24..28].copy_from_slice(&self.compressed_crc.to_le_bytes());
        output[28..44].copy_from_slice(&self.fingerprint);
        output
    }

    pub(crate) fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() != SECTION_ENTRY_BYTES || bytes[3..8] != [0; 5] || bytes[44..] != [0; 4] {
            bail!("invalid regional section directory entry");
        }
        let flags = u16::from_le_bytes(bytes[0..2].try_into().unwrap());
        if flags & !KNOWN_ENTRY_FLAGS != 0 {
            bail!("regional section directory flags are invalid");
        }
        let entry = Self {
            flags,
            non_empty_children: bytes[2],
            payload_offset: u64::from_le_bytes(bytes[8..16].try_into().unwrap()),
            compressed_length: u32::from_le_bytes(bytes[16..20].try_into().unwrap()),
            canonical_length: u32::from_le_bytes(bytes[20..24].try_into().unwrap()),
            compressed_crc: u32::from_le_bytes(bytes[24..28].try_into().unwrap()),
            fingerprint: bytes[28..44].try_into().unwrap(),
        };
        entry.validate_shape()?;
        Ok(entry)
    }

    fn validate_shape(self) -> Result<()> {
        if !self.is_present() {
            if self != Self::default() {
                bail!("absent regional section entry contains metadata");
            }
            return Ok(());
        }
        if self.non_empty_children != 0 && !self.is_present() {
            bail!("absent regional section advertises children");
        }
        if self.is_empty() {
            if self.payload_offset != 0
                || self.compressed_length != 0
                || self.canonical_length != 0
                || self.compressed_crc != 0
                || self.fingerprint != [0; 16]
            {
                bail!("empty regional section has a stored payload");
            }
        } else if self.payload_offset == 0
            || self.compressed_length == 0
            || self.canonical_length == 0
            || self.compressed_length as usize > MAX_SECTION_COMPRESSED_BYTES
            || self.canonical_length as usize > MAX_SECTION_CANONICAL_BYTES
            || self.fingerprint == [0; 16]
        {
            bail!("non-empty regional section has invalid payload metadata");
        }
        Ok(())
    }

    pub(crate) fn validate_packed(self) -> Result<()> {
        self.validate_shape()
    }
}

#[derive(Debug)]
enum PreparedPayload {
    Owned(Vec<u8>),
    Reused {
        file: Arc<File>,
        offset: u64,
        length: u32,
        crc: u32,
    },
}

impl PreparedPayload {
    fn length(&self) -> usize {
        match self {
            Self::Owned(bytes) => bytes.len(),
            Self::Reused { length, .. } => *length as usize,
        }
    }
}

#[derive(Debug, Default)]
struct PreparedEntry {
    directory: RegionSectionEntry,
    payload: Option<PreparedPayload>,
}

#[derive(Debug)]
pub struct RegionFileBuilder {
    world_identity: [u8; 32],
    catalog_fingerprint: [u8; 32],
    catalog_id: u64,
    region_x: i32,
    region_z: i32,
    generation: u64,
    layout: RegionLayout,
    sections: Vec<PreparedEntry>,
}

impl RegionFileBuilder {
    pub fn new(
        world_identity: [u8; 32],
        catalog_fingerprint: [u8; 32],
        catalog_id: u64,
        region_x: i32,
        region_z: i32,
        generation: u64,
        layout: RegionLayout,
    ) -> Result<Self> {
        if world_identity == [0; 32]
            || catalog_fingerprint == [0; 32]
            || catalog_id == 0
            || generation == 0
        {
            bail!("regional file identity, catalog, and generation must be nonzero");
        }
        Ok(Self {
            world_identity,
            catalog_fingerprint,
            catalog_id,
            region_x,
            region_z,
            generation,
            layout,
            sections: (0..layout.entry_count()?)
                .map(|_| PreparedEntry::default())
                .collect(),
        })
    }

    pub fn set_catalog_fingerprint(&mut self, fingerprint: [u8; 32]) -> Result<()> {
        if fingerprint == [0; 32] {
            bail!("regional catalog fingerprint zero is reserved");
        }
        self.catalog_fingerprint = fingerprint;
        Ok(())
    }

    pub fn insert(&mut self, coordinate: SectionCoordinate, frame: SectionFrame) -> Result<()> {
        let index = self
            .layout
            .index(self.region_x, self.region_z, coordinate)?;
        if self.sections[index].directory.is_present() {
            bail!("regional section coordinate was inserted twice");
        }
        let flags = REGION_ENTRY_PRESENT
            | if frame.is_empty() {
                SECTION_FLAG_EMPTY
            } else {
                0
            };
        let mut entry = RegionSectionEntry {
            flags,
            non_empty_children: frame.non_empty_children,
            ..RegionSectionEntry::default()
        };
        let payload = if frame.is_empty() {
            None
        } else {
            let canonical = frame.encode()?;
            if canonical.len() > MAX_SECTION_CANONICAL_BYTES {
                bail!("regional section canonical frame exceeds its corruption bound");
            }
            let compressed = zstd::bulk::compress(&canonical, ZSTD_LEVEL)?;
            if compressed.len() > MAX_SECTION_COMPRESSED_BYTES {
                bail!("regional section compressed frame exceeds its corruption bound");
            }
            entry.canonical_length = canonical.len() as u32;
            entry.compressed_length = compressed.len() as u32;
            entry.compressed_crc = crc32c(&compressed);
            entry
                .fingerprint
                .copy_from_slice(&blake3::hash(&canonical).as_bytes()[..16]);
            Some(PreparedPayload::Owned(compressed))
        };
        self.sections[index] = PreparedEntry {
            directory: entry,
            payload,
        };
        Ok(())
    }

    /// Reuses one verified compressed payload without decoding or recompressing it. The payload
    /// offset is assigned when this new immutable generation is written.
    pub fn copy_ordinal_from(&mut self, source: &RegionFile, ordinal: usize) -> Result<()> {
        if source.region() != (self.region_x, self.region_z)
            || source.layout() != self.layout
            || ordinal >= self.sections.len()
        {
            bail!("regional section reuse identity is invalid");
        }
        if self.sections[ordinal].directory.is_present() {
            bail!("regional section ordinal was inserted twice");
        }
        let mut entry = source.entry_ordinal(ordinal as u32)?;
        if !entry.is_present() {
            return Ok(());
        }
        let payload = if entry.is_empty() {
            None
        } else {
            Some(PreparedPayload::Reused {
                file: source.file.clone(),
                offset: entry.payload_offset,
                length: entry.compressed_length,
                crc: entry.compressed_crc,
            })
        };
        entry.payload_offset = 0;
        self.sections[ordinal] = PreparedEntry {
            directory: entry,
            payload,
        };
        Ok(())
    }

    /// Writes exactly one bounded shard transaction. The final name is never visible until the
    /// complete file and data have crossed a durability barrier.
    pub fn write_atomic(mut self, path: impl AsRef<Path>) -> Result<RegionFile> {
        let path = path.as_ref();
        let parent = path
            .parent()
            .context("regional file has no parent directory")?;
        fs::create_dir_all(parent).with_context(|| format!("create {}", parent.display()))?;
        let directory_offset = REGION_HEADER_BYTES as u64;
        let payload_offset = directory_offset
            + self
                .sections
                .len()
                .checked_mul(SECTION_ENTRY_BYTES)
                .context("regional directory byte length overflow")? as u64;
        let mut next_payload = payload_offset;
        for section in &mut self.sections {
            if let Some(payload) = &section.payload {
                section.directory.payload_offset = next_payload;
                next_payload = next_payload
                    .checked_add(payload.length() as u64)
                    .context("regional payload extent overflow")?;
            }
        }
        let file_length = next_payload;
        if file_length > MAX_REGION_FILE_BYTES {
            bail!("regional file exceeds its {MAX_REGION_FILE_BYTES} byte safety bound");
        }

        let published_sections = self
            .sections
            .iter()
            .map(|section| section.directory)
            .collect::<Vec<_>>();
        let mut directory_bytes =
            Vec::with_capacity(published_sections.len() * SECTION_ENTRY_BYTES);
        for section in &published_sections {
            directory_bytes.extend_from_slice(&section.encode());
        }
        let published_header = Header {
            region_x: self.region_x,
            region_z: self.region_z,
            generation: self.generation,
            catalog_id: self.catalog_id,
            world_identity: self.world_identity,
            catalog_fingerprint: self.catalog_fingerprint,
            layout: self.layout,
            entry_count: self.sections.len() as u32,
            directory_offset,
            payload_offset,
            file_length,
            directory_crc: crc32c(&directory_bytes),
        };
        let header = encode_header(published_header);

        let temporary = temporary_path(path);
        let result = (|| -> Result<()> {
            let mut file = OpenOptions::new()
                .create_new(true)
                .write(true)
                .open(&temporary)
                .with_context(|| format!("create {}", temporary.display()))?;
            file.write_all(&header)?;
            file.write_all(&directory_bytes)?;
            let mut reuse_buffer = vec![0u8; 64 * 1024];
            for section in self.sections {
                match section.payload {
                    Some(PreparedPayload::Owned(compressed)) => file.write_all(&compressed)?,
                    Some(PreparedPayload::Reused {
                        file: source,
                        mut offset,
                        length,
                        crc,
                    }) => {
                        let mut remaining = length as usize;
                        let mut actual_crc = 0;
                        while remaining != 0 {
                            let count = remaining.min(reuse_buffer.len());
                            source.read_exact_at(&mut reuse_buffer[..count], offset)?;
                            actual_crc = crc32c::crc32c_append(actual_crc, &reuse_buffer[..count]);
                            file.write_all(&reuse_buffer[..count])?;
                            offset += count as u64;
                            remaining -= count;
                        }
                        if actual_crc != crc {
                            bail!("reused regional section compressed checksum mismatch");
                        }
                    }
                    None => {}
                }
            }
            if file.metadata()?.len() != file_length {
                bail!("regional file writer produced an unexpected length");
            }
            file.sync_all()?;
            fs::rename(&temporary, path).with_context(|| format!("publish {}", path.display()))?;
            sync_parent(path)?;
            Ok(())
        })();
        if result.is_err() {
            let _ = fs::remove_file(&temporary);
        }
        result?;
        let mut region = RegionFile {
            path: path.to_owned(),
            file: Arc::new(File::open(path)?),
            header: published_header,
            sections: published_sections.into(),
            index_fingerprint: [0; 16],
            compressed_index: Arc::from([]),
        };
        let index = RegionIndex::from_file(&region);
        region.index_fingerprint = index.fingerprint()?;
        region.compressed_index = index.compressed()?.into();
        Ok(region)
    }
}

fn temporary_path(path: &Path) -> PathBuf {
    let sequence = NEXT_TEMPORARY.fetch_add(1, Ordering::Relaxed);
    let name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("region.vxregion");
    path.with_file_name(format!(".{name}.tmp.{}.{}", std::process::id(), sequence))
}

#[derive(Clone, Copy, Debug)]
struct Header {
    region_x: i32,
    region_z: i32,
    generation: u64,
    catalog_id: u64,
    world_identity: [u8; 32],
    catalog_fingerprint: [u8; 32],
    layout: RegionLayout,
    entry_count: u32,
    directory_offset: u64,
    payload_offset: u64,
    file_length: u64,
    directory_crc: u32,
}

fn encode_header(header: Header) -> [u8; REGION_HEADER_BYTES] {
    let mut output = [0u8; REGION_HEADER_BYTES];
    output[0..8].copy_from_slice(REGION_MAGIC);
    output[8..10].copy_from_slice(&(REGION_HEADER_BYTES as u16).to_le_bytes());
    output[10] = header.layout.levels;
    output[12..16].copy_from_slice(&header.region_x.to_le_bytes());
    output[16..20].copy_from_slice(&header.region_z.to_le_bytes());
    output[20..24].copy_from_slice(&header.layout.min_base_y.to_le_bytes());
    output[24..26].copy_from_slice(&header.layout.base_y_count.to_le_bytes());
    output[28..32].copy_from_slice(&header.entry_count.to_le_bytes());
    output[32..40].copy_from_slice(&header.generation.to_le_bytes());
    output[40..48].copy_from_slice(&header.catalog_id.to_le_bytes());
    output[48..80].copy_from_slice(&header.world_identity);
    output[80..112].copy_from_slice(&header.catalog_fingerprint);
    output[112..120].copy_from_slice(&header.directory_offset.to_le_bytes());
    output[120..128].copy_from_slice(&header.payload_offset.to_le_bytes());
    output[128..136].copy_from_slice(&header.file_length.to_le_bytes());
    output[136..140].copy_from_slice(&header.directory_crc.to_le_bytes());
    let crc = crc32c(&output[..252]);
    output[252..256].copy_from_slice(&crc.to_le_bytes());
    output
}

fn decode_header(bytes: &[u8; REGION_HEADER_BYTES]) -> Result<Header> {
    if &bytes[0..8] != REGION_MAGIC
        || u16::from_le_bytes(bytes[8..10].try_into().unwrap()) as usize != REGION_HEADER_BYTES
        || bytes[11] != 0
        || bytes[26..28] != [0; 2]
        || bytes[140..252] != [0; 112]
        || u32::from_le_bytes(bytes[252..256].try_into().unwrap()) != crc32c(&bytes[..252])
    {
        bail!("invalid regional file header");
    }
    let layout = RegionLayout::new(
        i32::from_le_bytes(bytes[20..24].try_into().unwrap()),
        u16::from_le_bytes(bytes[24..26].try_into().unwrap()),
        bytes[10],
    )?;
    let header = Header {
        region_x: i32::from_le_bytes(bytes[12..16].try_into().unwrap()),
        region_z: i32::from_le_bytes(bytes[16..20].try_into().unwrap()),
        generation: u64::from_le_bytes(bytes[32..40].try_into().unwrap()),
        catalog_id: u64::from_le_bytes(bytes[40..48].try_into().unwrap()),
        world_identity: bytes[48..80].try_into().unwrap(),
        catalog_fingerprint: bytes[80..112].try_into().unwrap(),
        layout,
        entry_count: u32::from_le_bytes(bytes[28..32].try_into().unwrap()),
        directory_offset: u64::from_le_bytes(bytes[112..120].try_into().unwrap()),
        payload_offset: u64::from_le_bytes(bytes[120..128].try_into().unwrap()),
        file_length: u64::from_le_bytes(bytes[128..136].try_into().unwrap()),
        directory_crc: u32::from_le_bytes(bytes[136..140].try_into().unwrap()),
    };
    if header.generation == 0
        || header.catalog_id == 0
        || header.world_identity == [0; 32]
        || header.catalog_fingerprint == [0; 32]
        || header.entry_count as usize != layout.entry_count()?
        || header.directory_offset != REGION_HEADER_BYTES as u64
        || header.payload_offset
            != header.directory_offset + header.entry_count as u64 * SECTION_ENTRY_BYTES as u64
        || header.file_length < header.payload_offset
        || header.file_length > MAX_REGION_FILE_BYTES
    {
        bail!("regional file header extents or identity are invalid");
    }
    Ok(header)
}

#[derive(Clone, Debug)]
pub struct RegionFile {
    path: PathBuf,
    file: Arc<File>,
    header: Header,
    sections: Arc<[RegionSectionEntry]>,
    index_fingerprint: [u8; 16],
    compressed_index: Arc<[u8]>,
}

impl RegionFile {
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        let path = path.as_ref().to_owned();
        let file = File::open(&path).with_context(|| format!("open {}", path.display()))?;
        let length = file.metadata()?.len();
        if length < REGION_HEADER_BYTES as u64 || length > MAX_REGION_FILE_BYTES {
            bail!("regional file length is outside its safety bounds");
        }
        let mut header_bytes = [0u8; REGION_HEADER_BYTES];
        file.read_exact_at(&mut header_bytes, 0)?;
        let header = decode_header(&header_bytes)?;
        if header.file_length != length {
            bail!("regional file header length disagrees with the file");
        }
        let directory_length = header.entry_count as usize * SECTION_ENTRY_BYTES;
        let mut directory_bytes = vec![0u8; directory_length];
        file.read_exact_at(&mut directory_bytes, header.directory_offset)?;
        if crc32c(&directory_bytes) != header.directory_crc {
            bail!("regional section directory checksum mismatch");
        }
        let sections = directory_bytes
            .chunks_exact(SECTION_ENTRY_BYTES)
            .map(RegionSectionEntry::decode)
            .collect::<Result<Vec<_>>>()?;
        let payload_end = length;
        let mut expected_offset = header.payload_offset;
        for entry in &sections {
            if entry.is_present() && !entry.is_empty() {
                if entry.payload_offset != expected_offset {
                    bail!("regional section payloads are not contiguous and canonical");
                }
                expected_offset = expected_offset
                    .checked_add(u64::from(entry.compressed_length))
                    .context("regional section payload extent overflow")?;
                if expected_offset > payload_end {
                    bail!("regional section payload exceeds the file");
                }
            }
        }
        if expected_offset != payload_end {
            bail!("regional file contains unreferenced payload bytes");
        }
        let mut region = Self {
            path,
            file: Arc::new(file),
            header,
            sections: sections.into(),
            index_fingerprint: [0; 16],
            compressed_index: Arc::from([]),
        };
        let index = RegionIndex::from_file(&region);
        region.index_fingerprint = index.fingerprint()?;
        region.compressed_index = index.compressed()?.into();
        Ok(region)
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn region(&self) -> (i32, i32) {
        (self.header.region_x, self.header.region_z)
    }

    pub fn generation(&self) -> u64 {
        self.header.generation
    }

    pub fn catalog_id(&self) -> u64 {
        self.header.catalog_id
    }

    pub fn world_identity(&self) -> [u8; 32] {
        self.header.world_identity
    }

    pub fn catalog_fingerprint(&self) -> [u8; 32] {
        self.header.catalog_fingerprint
    }

    pub fn index_fingerprint(&self) -> [u8; 16] {
        self.index_fingerprint
    }

    pub fn compressed_index(&self) -> &[u8] {
        &self.compressed_index
    }

    pub fn layout(&self) -> RegionLayout {
        self.header.layout
    }

    pub(crate) fn entries(&self) -> &[RegionSectionEntry] {
        &self.sections
    }

    pub fn entry(&self, coordinate: SectionCoordinate) -> Result<RegionSectionEntry> {
        let index =
            self.header
                .layout
                .index(self.header.region_x, self.header.region_z, coordinate)?;
        Ok(self.sections[index])
    }

    pub fn entry_ordinal(&self, ordinal: u32) -> Result<RegionSectionEntry> {
        self.sections
            .get(ordinal as usize)
            .copied()
            .context("regional section ordinal is out of range")
    }

    pub fn read_compressed(&self, coordinate: SectionCoordinate) -> Result<Option<Vec<u8>>> {
        let entry = self.entry(coordinate)?;
        if !entry.is_present() || entry.is_empty() {
            return Ok(None);
        }
        let mut compressed = vec![0u8; entry.compressed_length as usize];
        self.file
            .read_exact_at(&mut compressed, entry.payload_offset)
            .with_context(|| format!("read section payload from {}", self.path.display()))?;
        if crc32c(&compressed) != entry.compressed_crc {
            bail!("regional section compressed checksum mismatch");
        }
        Ok(Some(compressed))
    }

    pub fn read_compressed_ordinal(&self, ordinal: u32) -> Result<Option<Vec<u8>>> {
        let entry = self.entry_ordinal(ordinal)?;
        if !entry.is_present() || entry.is_empty() {
            return Ok(None);
        }
        let mut compressed = vec![0u8; entry.compressed_length as usize];
        self.file
            .read_exact_at(&mut compressed, entry.payload_offset)
            .with_context(|| format!("read section payload from {}", self.path.display()))?;
        if crc32c(&compressed) != entry.compressed_crc {
            bail!("regional section compressed checksum mismatch");
        }
        Ok(Some(compressed))
    }

    pub fn read_section(&self, coordinate: SectionCoordinate) -> Result<Option<SectionFrame>> {
        let entry = self.entry(coordinate)?;
        if !entry.is_present() {
            return Ok(None);
        }
        let mut frame = if entry.is_empty() {
            SectionFrame::empty(entry.non_empty_children)?
        } else {
            let compressed = self
                .read_compressed(coordinate)?
                .expect("non-empty entry has compressed payload");
            let canonical = zstd::bulk::decompress(&compressed, entry.canonical_length as usize)
                .context("decompress regional section")?;
            if canonical.len() != entry.canonical_length as usize
                || blake3::hash(&canonical).as_bytes()[..16] != entry.fingerprint
            {
                bail!("regional section canonical fingerprint mismatch");
            }
            SectionFrame::decode(&canonical)?
        };
        frame.non_empty_children = entry.non_empty_children;
        if frame.is_empty() != entry.is_empty() {
            bail!("regional section payload disagrees with its directory entry");
        }
        Ok(Some(frame))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::lod::{Cell, SECTION_VOLUME};
    use std::{
        process,
        sync::atomic::{AtomicU64, Ordering},
    };

    static NEXT_TEST: AtomicU64 = AtomicU64::new(0);

    struct TemporaryDirectory(PathBuf);

    impl TemporaryDirectory {
        fn new() -> Self {
            let path = std::env::temp_dir().join(format!(
                "voxy-region-test-{}-{}",
                process::id(),
                NEXT_TEST.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&path).unwrap();
            Self(path)
        }
    }

    impl Drop for TemporaryDirectory {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn builder(generation: u64) -> RegionFileBuilder {
        RegionFileBuilder::new(
            [1; 32],
            [2; 32],
            9,
            -2,
            3,
            generation,
            RegionLayout::new(-2, 12, 5).unwrap(),
        )
        .unwrap()
    }

    #[test]
    fn layout_round_trips_every_direct_index() {
        let layout = RegionLayout::new(-3, 17, 5).unwrap();
        for index in 0..layout.entry_count().unwrap() {
            let coordinate = layout.coordinate(-4, 7, index).unwrap();
            assert_eq!(layout.index(-4, 7, coordinate).unwrap(), index);
        }
    }

    #[test]
    fn published_sections_decode_exact_cells_and_directory_children() {
        let temporary = TemporaryDirectory::new();
        let path = temporary.0.join("r.-2.3.vxregion");
        let mut builder = builder(1);
        let layout = RegionLayout::new(-2, 12, 5).unwrap();
        let mut expected = Vec::new();
        for (ordinal, count) in [1, 3, 257, SECTION_VOLUME].into_iter().enumerate() {
            let cells = (0..SECTION_VOLUME)
                .map(|i| {
                    let id = i % count;
                    Cell {
                        block: if count > 1 && id == 0 {
                            0
                        } else {
                            id as u32 + 1
                        },
                        biome: id as u32 * 17,
                        light: id as u8,
                    }
                })
                .collect();
            let coordinate = layout.coordinate(-2, 3, ordinal).unwrap();
            let frame = SectionFrame::new(0, cells).unwrap();
            builder.insert(coordinate, frame.clone()).unwrap();
            expected.push((coordinate, frame));
        }
        let parent = SectionCoordinate {
            level: 1,
            x: -16,
            y: 0,
            z: 24,
        };
        let parent_frame = SectionFrame::new(
            0xa5,
            vec![
                Cell {
                    block: 9,
                    biome: 7,
                    light: 0xf3
                };
                SECTION_VOLUME
            ],
        )
        .unwrap();
        builder.insert(parent, parent_frame.clone()).unwrap();
        expected.push((parent, parent_frame));
        let empty = layout.coordinate(-2, 3, 4).unwrap();
        builder
            .insert(empty, SectionFrame::empty(0).unwrap())
            .unwrap();
        let published = builder.write_atomic(&path).unwrap();
        drop(published);
        let loaded = RegionFile::open(&path).unwrap();
        for (coordinate, frame) in expected {
            assert_eq!(
                loaded.read_section(coordinate).unwrap(),
                Some(frame.clone())
            );
            assert_eq!(
                loaded.entry(coordinate).unwrap().non_empty_children,
                frame.non_empty_children
            );
        }
        assert_eq!(
            loaded.read_section(empty).unwrap(),
            Some(SectionFrame::empty(0).unwrap())
        );
        assert!(loaded.read_compressed(empty).unwrap().is_none());
        assert!(
            loaded
                .read_section(layout.coordinate(-2, 3, 5).unwrap())
                .unwrap()
                .is_none()
        );
    }

    #[test]
    fn atomic_replacement_keeps_existing_reader_generation() {
        let temporary = TemporaryDirectory::new();
        let path = temporary.0.join("r.-2.3.vxregion");
        let coordinate = SectionCoordinate {
            level: 0,
            x: -32,
            y: 0,
            z: 48,
        };
        let mut first_builder = builder(1);
        let mut cells = vec![Cell::AIR; SECTION_VOLUME];
        cells[0] = Cell {
            block: 1,
            biome: 0,
            light: 15,
        };
        let expected = SectionFrame::new(1, cells).unwrap();
        first_builder.insert(coordinate, expected.clone()).unwrap();
        let old = first_builder.write_atomic(&path).unwrap();
        assert_eq!(old.read_section(coordinate).unwrap(), Some(expected));

        let mut second_builder = builder(2);
        second_builder
            .insert(coordinate, SectionFrame::empty(0).unwrap())
            .unwrap();
        let new = second_builder.write_atomic(&path).unwrap();
        assert_eq!(old.generation(), 1);
        assert!(!old.read_section(coordinate).unwrap().unwrap().is_empty());
        assert_eq!(new.generation(), 2);
        assert!(new.read_section(coordinate).unwrap().unwrap().is_empty());
    }

    #[test]
    fn replacement_reuses_verified_compressed_section_without_decoding() {
        let temporary = TemporaryDirectory::new();
        let path = temporary.0.join("r.-2.3.vxregion");
        let coordinate = SectionCoordinate {
            level: 0,
            x: -32,
            y: 0,
            z: 48,
        };
        let mut first = builder(1);
        let mut cells = vec![Cell::AIR; SECTION_VOLUME];
        cells[31] = Cell {
            block: 2,
            biome: 3,
            light: 4,
        };
        let expected = SectionFrame::new(0, cells).unwrap();
        first.insert(coordinate, expected.clone()).unwrap();
        let old = first.write_atomic(&path).unwrap();
        let ordinal = old.layout().index(-2, 3, coordinate).unwrap();
        let old_entry = old.entry_ordinal(ordinal as u32).unwrap();

        let mut second = builder(2);
        second.copy_ordinal_from(&old, ordinal).unwrap();
        let new = second.write_atomic(&path).unwrap();
        let new_entry = new.entry_ordinal(ordinal as u32).unwrap();
        assert_eq!(new_entry.compressed_length, old_entry.compressed_length);
        assert_eq!(new_entry.compressed_crc, old_entry.compressed_crc);
        assert_eq!(new_entry.fingerprint, old_entry.fingerprint);
        assert_eq!(new.read_section(coordinate).unwrap(), Some(expected));
    }

    #[test]
    fn directory_corruption_is_rejected() {
        let temporary = TemporaryDirectory::new();
        let path = temporary.0.join("r.-2.3.vxregion");
        let region = builder(1).write_atomic(&path).unwrap();
        drop(region);
        let file = OpenOptions::new().write(true).open(&path).unwrap();
        file.write_all_at(&[0xff], REGION_HEADER_BYTES as u64 + 4)
            .unwrap();
        file.sync_all().unwrap();
        assert!(RegionFile::open(&path).is_err());
    }

    #[test]
    fn payload_corruption_is_detected_lazily() {
        let temporary = TemporaryDirectory::new();
        let path = temporary.0.join("r.-2.3.vxregion");
        let coordinate = SectionCoordinate {
            level: 0,
            x: -32,
            y: 0,
            z: 48,
        };
        let mut region_builder = builder(1);
        let mut cells = vec![Cell::AIR; SECTION_VOLUME];
        cells[17] = Cell {
            block: 1,
            biome: 0,
            light: 15,
        };
        region_builder
            .insert(coordinate, SectionFrame::new(0, cells).unwrap())
            .unwrap();
        let region = region_builder.write_atomic(&path).unwrap();
        let payload = region.entry(coordinate).unwrap().payload_offset;
        drop(region);
        let file = OpenOptions::new().write(true).open(&path).unwrap();
        file.write_all_at(&[0xff], payload).unwrap();
        file.sync_all().unwrap();

        let reopened = RegionFile::open(&path).unwrap();
        assert!(reopened.read_section(coordinate).is_err());
    }
}
