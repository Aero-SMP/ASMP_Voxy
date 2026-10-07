use crate::{
    crc::{Xxh64, xxh64},
    diagnostics::{Counter, Stage},
    key::SectionKey,
    lod::{Cell, SECTION_VOLUME, Section, cell_index},
    registry::Registry,
    write_lock,
};
use anyhow::{Context, Result, bail};
use fastnbt::{ByteArray, LongArray};
use flate2::read::{GzDecoder, ZlibDecoder};
use lz4_java_wrc::Lz4BlockInput;
use serde::Deserialize;
use std::{
    collections::BTreeMap,
    fs::{self, File},
    io::{Cursor, Read},
    os::unix::fs::{FileExt, MetadataExt},
    path::{Path, PathBuf},
    sync::{Arc, RwLock},
};

const REGION_HEADER_BYTES: usize = 8192;
const MAX_COMPRESSED_CHUNK: usize = 255 * 4096;
const MAX_EXTERNAL_CHUNK: usize = 128 * 1024 * 1024;
const MAX_DECOMPRESSED_CHUNK: u64 = 128 * 1024 * 1024;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DimensionSpec {
    pub id: String,
    pub root: PathBuf,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct RegionEntry {
    pub location: u32,
    pub timestamp: u32,
}

#[derive(Clone, Debug)]
pub struct RegionHeader {
    pub path: PathBuf,
    pub region_x: i32,
    pub region_z: i32,
    pub entries: Vec<RegionEntry>,
    pub file_marker: u64,
    pub external_stamp: [u8; 16],
    file_identity: FileIdentity,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct FileIdentity {
    device: u64,
    inode: u64,
    length: u64,
    modified: (i64, i64),
    changed: (i64, i64),
}

impl FileIdentity {
    fn from_metadata(metadata: &fs::Metadata) -> Self {
        Self {
            device: metadata.dev(),
            inode: metadata.ino(),
            length: metadata.len(),
            modified: (metadata.mtime(), metadata.mtime_nsec()),
            changed: (metadata.ctime(), metadata.ctime_nsec()),
        }
    }
}

/// Compact discovery metadata. Full headers and semantic source tables belong to one build.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RegionAvailability {
    pub file_marker: u64,
    pub header_fingerprint: [u8; 16],
    pub external_stamp: [u8; 16],
    pub saved: [u64; 16],
    pub readable: bool,
}
impl RegionAvailability {
    pub fn same_inventory(&self, other: &Self) -> bool {
        self.readable == other.readable && self.saved == other.saved
    }

    pub fn from_header(header: &RegionHeader) -> Self {
        let mut saved = [0; 16];
        for (slot, entry) in header.entries.iter().enumerate() {
            if entry.location >> 8 != 0 && entry.location & 0xff != 0 {
                saved[slot / 64] |= 1 << (slot % 64);
            }
        }
        Self {
            file_marker: header.file_marker,
            header_fingerprint: header_fingerprint(&header.entries),
            external_stamp: header.external_stamp,
            saved,
            readable: true,
        }
    }
}

pub fn header_fingerprint(entries: &[RegionEntry]) -> [u8; 16] {
    let mut hash = blake3::Hasher::new();
    for entry in entries {
        hash.update(&entry.location.to_le_bytes());
        hash.update(&entry.timestamp.to_le_bytes());
    }
    hash.finalize().as_bytes()[..16].try_into().unwrap()
}

#[derive(Clone, Debug)]
pub struct FailedRegion {
    pub path: PathBuf,
    pub region_x: i32,
    pub region_z: i32,
    pub file_marker: u64,
    pub error: String,
}

#[derive(Clone, Debug, Default)]
pub struct RegionHeaders {
    pub valid: Vec<RegionHeader>,
    pub failed: Vec<FailedRegion>,
}

#[derive(Clone, Debug)]
pub struct ChunkSection {
    pub y: i32,
    pub cells: Vec<Cell>,
}

#[derive(Clone, Debug)]
pub struct ParsedChunk {
    pub x: i32,
    pub z: i32,
    pub sections: BTreeMap<i32, ChunkSection>,
    pub source_fingerprint: u64,
    pub terrain_fingerprint: TerrainFingerprint,
}

pub type TerrainFingerprint = [u64; 2];

#[derive(Clone, Debug)]
pub struct BuiltLevelZero {
    pub section: Section,
    pub sources: Vec<(i32, i32, Option<u64>)>,
}

#[derive(Clone, Debug)]
pub struct LevelZeroGroup {
    pub x: i32,
    pub z: i32,
    pub chunks: Vec<Option<ParsedChunk>>,
}

#[derive(Clone, Debug)]
pub struct AnvilWorld {
    pub dimension: String,
    pub root: PathBuf,
}

#[derive(Debug, Deserialize)]
struct ChunkNbt {
    #[serde(rename = "xPos")]
    x: i32,
    #[serde(rename = "zPos")]
    z: i32,
    #[serde(rename = "Status", default)]
    status: String,
    #[serde(default)]
    sections: Vec<SectionNbt>,
}

#[derive(Debug, Deserialize)]
struct SectionNbt {
    #[serde(rename = "Y")]
    y: i8,
    #[serde(default)]
    block_states: Option<BlockStatesNbt>,
    #[serde(default)]
    biomes: Option<BiomesNbt>,
    #[serde(rename = "BlockLight", default)]
    block_light: Option<ByteArray>,
    #[serde(rename = "SkyLight", default)]
    sky_light: Option<ByteArray>,
}

#[derive(Debug, Deserialize)]
struct BlockStatesNbt {
    palette: Vec<BlockPaletteNbt>,
    #[serde(default)]
    data: Option<LongArray>,
}

#[derive(Debug, Deserialize)]
struct BlockPaletteNbt {
    #[serde(rename = "Name")]
    name: String,
    #[serde(rename = "Properties", default)]
    properties: BTreeMap<String, String>,
}

#[derive(Debug, Deserialize)]
struct BiomesNbt {
    palette: Vec<String>,
    #[serde(default)]
    data: Option<LongArray>,
}

/// One source snapshot owns its handle and all decode scratch. Loaded bytes cannot outlive
/// a mutable borrow of this reader, so the next chunk cannot reset a live decoder's buffers.
pub struct RegionalReader<'a> {
    world: &'a AnvilWorld,
    header: &'a RegionHeader,
    file: File,
    compressed: Vec<u8>,
    decompressed: Vec<u8>,
    content: Box<[Option<Option<[u8; 32]>>; 1024]>,
    external: BTreeMap<usize, ExternalSource>,
}

struct ExternalSource {
    path: PathBuf,
    identity: FileIdentity,
    kind: u8,
    digest: [u8; 32],
}

pub struct SourceChunk<'a> {
    x: i32,
    z: i32,
    sector: u32,
    sectors: u8,
    kind: u8,
    compressed: &'a [u8],
    decompressed: &'a mut Vec<u8>,
    digest: [u8; 32],
    default_sky_light: u8,
}

impl SourceChunk<'_> {
    pub fn content_digest(&self) -> [u8; 32] {
        self.digest
    }

    fn source_fingerprint(&self) -> u64 {
        fingerprint(self.sector, self.sectors, self.kind, self.compressed)
    }

    fn decompress(&mut self) -> Result<()> {
        crate::diagnostics::sync_result(Stage::SourceDecode, || {
            decompress_into(self.kind, self.compressed, self.decompressed)
        })
        .with_context(|| format!("decompress chunk ({},{})", self.x, self.z))
    }

    pub fn inspect(&mut self, registry: &Arc<RwLock<Registry>>) -> Result<TerrainFingerprint> {
        self.decompress()?;
        crate::diagnostics::count(Counter::SemanticInspections, 1);
        crate::diagnostics::sync_result(Stage::SemanticInspect, || {
            let chunk = NormalizedChunk::read(self.decompressed, registry)?;
            chunk.check_coordinates(self.x, self.z)?;
            let fingerprint = chunk.fingerprint(self.default_sky_light);
            Ok(fingerprint)
        })
    }

    pub fn decode(&mut self, registry: &Arc<RwLock<Registry>>) -> Result<ParsedChunk> {
        self.decompress()?;
        let mut chunk = parse_chunk(self.decompressed, registry, self.default_sky_light)?;
        if chunk.x != self.x || chunk.z != self.z {
            bail!(
                "chunk coordinate mismatch: requested ({},{}), NBT says ({},{})",
                self.x,
                self.z,
                chunk.x,
                chunk.z
            );
        }
        chunk.source_fingerprint = self.source_fingerprint();
        Ok(chunk)
    }
}

impl RegionalReader<'_> {
    pub fn read_chunk(&mut self, x: i32, z: i32) -> Result<Option<SourceChunk<'_>>> {
        if x.div_euclid(32) != self.header.region_x || z.div_euclid(32) != self.header.region_z {
            bail!("chunk ({x},{z}) is outside its captured region");
        }
        crate::diagnostics::count(Counter::SourceChunks, 1);
        let slot = (z.rem_euclid(32) * 32 + x.rem_euclid(32)) as usize;
        let entry = self.header.entries[slot];
        let sector = entry.location >> 8;
        let sectors = (entry.location & 0xff) as usize;
        if sector == 0 || sectors == 0 {
            self.content[slot] = Some(None);
            return Ok(None);
        }
        if sector < 2 {
            bail!("chunk ({x},{z}) points inside its region header");
        }
        let reading = crate::diagnostics::Span::sync(Stage::SourceRead);
        let record = crate::diagnostics::Span::sync(Stage::SourceRecordRead);
        let mut prefix = [0u8; 5];
        self.file
            .read_exact_at(&mut prefix, u64::from(sector) * 4096)?;
        let length = u32::from_be_bytes(prefix[..4].try_into().unwrap()) as usize;
        if length == 0 || length > sectors * 4096 - 4 || length > MAX_COMPRESSED_CHUNK {
            bail!("chunk ({x},{z}) has invalid compressed length {length}");
        }
        let kind = prefix[4] & 0x7f;
        if !(1..=4).contains(&kind) {
            bail!("unsupported Anvil compression type {kind}");
        }
        let external = prefix[4] & 0x80 != 0;
        let external_source = if external {
            let path = self.world.region_dir().join(format!("c.{x}.{z}.mcc"));
            let identity = read_external(&path, &mut self.compressed)?;
            Some((path, identity))
        } else {
            self.compressed.resize(length - 1, 0);
            self.file
                .read_exact_at(&mut self.compressed, u64::from(sector) * 4096 + 5)?;
            None
        };
        let count = self.compressed.len() as u64;
        crate::diagnostics::count(Counter::SourceReads, 1);
        record.finish(true, count);
        reading.finish(true, count);
        let hashing = crate::diagnostics::Span::sync(Stage::RawHash);
        let digest = content_digest(kind, &self.compressed);
        hashing.finish(true, count);
        crate::diagnostics::count(Counter::RawBytes, count);
        if let Some((path, identity)) = external_source {
            if let Some(previous) = self.external.get(&slot)
                && (previous.identity != identity
                    || previous.digest != digest
                    || previous.kind != kind)
            {
                bail!(
                    "Anvil source snapshot changed: external chunk ({x},{z}) changed during reading"
                );
            }
            self.external.insert(
                slot,
                ExternalSource {
                    path,
                    identity,
                    kind,
                    digest,
                },
            );
        }
        self.content[slot] = Some(Some(digest));
        Ok(Some(SourceChunk {
            x,
            z,
            sector,
            sectors: sectors as u8,
            kind,
            compressed: &self.compressed,
            decompressed: &mut self.decompressed,
            digest,
            default_sky_light: self.world.default_sky_light(),
        }))
    }

    /// None means unread, Some(None) means observed absent, Some(Some(hash)) means present.
    pub fn content_digest(&self, slot: usize) -> Option<Option<[u8; 32]>> {
        self.content.get(slot).copied().flatten()
    }

    pub fn load_level_zero_group(
        &mut self,
        x: i32,
        z: i32,
        registry: &Arc<RwLock<Registry>>,
    ) -> Result<LevelZeroGroup> {
        let base_x = x.checked_mul(2).context("chunk-group x overflow")?;
        let base_z = z.checked_mul(2).context("chunk-group z overflow")?;
        let mut chunks = Vec::with_capacity(4);
        for dz in 0..2 {
            for dx in 0..2 {
                let x = base_x.checked_add(dx).context("chunk x overflow")?;
                let z = base_z.checked_add(dz).context("chunk z overflow")?;
                chunks.push(match self.read_chunk(x, z)? {
                    Some(mut source) => Some(source.decode(registry)?),
                    None => None,
                });
            }
        }
        Ok(LevelZeroGroup { x, z, chunks })
    }

    /// Recheck separate external payloads as actual bytes; regional headers do not own them.
    pub fn verify(&mut self) -> Result<()> {
        for external in self.external.values() {
            let identity = read_external(&external.path, &mut self.compressed)?;
            let digest = content_digest(external.kind, &self.compressed);
            crate::diagnostics::count(Counter::ExternalVerifications, 1);
            if identity != external.identity || digest != external.digest {
                bail!("Anvil source snapshot changed: external payload changed before publication");
            }
        }
        if FileIdentity::from_metadata(&self.file.metadata()?) != self.header.file_identity
            || FileIdentity::from_metadata(&fs::metadata(&self.header.path)?)
                != self.header.file_identity
        {
            bail!("Anvil source snapshot changed: regional descriptor/path changed");
        }
        let current = self
            .world
            .region_header(self.header.region_x, self.header.region_z)?
            .context("Anvil source snapshot changed: region vanished before publication")?;
        if current.file_identity != self.header.file_identity
            || current.entries != self.header.entries
        {
            bail!("Anvil source snapshot changed: regional header changed");
        }
        Ok(())
    }
}

fn read_external(path: &Path, buffer: &mut Vec<u8>) -> Result<FileIdentity> {
    let opening = crate::diagnostics::Span::sync(Stage::SourceOpen);
    let file = File::open(path).with_context(|| format!("open {}", path.display()))?;
    crate::diagnostics::count(Counter::ExternalOpens, 1);
    let identity = FileIdentity::from_metadata(&file.metadata()?);
    if identity.length > MAX_EXTERNAL_CHUNK as u64 {
        bail!("external chunk exceeds {MAX_EXTERNAL_CHUNK} bytes");
    }
    if FileIdentity::from_metadata(&fs::metadata(path)?) != identity {
        bail!("Anvil source snapshot changed: external file replaced before reading");
    }
    opening.finish(true, 0);
    buffer.resize(identity.length as usize, 0);
    file.read_exact_at(buffer, 0)?;
    if FileIdentity::from_metadata(&file.metadata()?) != identity
        || FileIdentity::from_metadata(&fs::metadata(path)?) != identity
    {
        bail!("Anvil source snapshot changed: external file changed during reading");
    }
    Ok(identity)
}

fn content_digest(kind: u8, bytes: &[u8]) -> [u8; 32] {
    let mut hash = blake3::Hasher::new();
    hash.update(b"Voxy compressed Anvil content\0");
    hash.update(&[kind]);
    hash.update(&(bytes.len() as u64).to_le_bytes());
    hash.update(bytes);
    *hash.finalize().as_bytes()
}

impl AnvilWorld {
    pub fn new(dimension: String, root: PathBuf) -> Self {
        Self { dimension, root }
    }

    pub fn region_dir(&self) -> PathBuf {
        self.root.join("region")
    }

    pub fn region_path(&self, chunk_x: i32, chunk_z: i32) -> PathBuf {
        self.region_dir().join(format!(
            "r.{}.{}.mca",
            chunk_x.div_euclid(32),
            chunk_z.div_euclid(32)
        ))
    }

    pub fn region_headers(&self) -> Result<RegionHeaders> {
        let directory = self.region_dir();
        let mut out = RegionHeaders::default();
        let mut external = BTreeMap::new();
        let entries = match fs::read_dir(&directory) {
            Ok(entries) => entries,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                // An empty dimension is valid, but unavailable world storage is not proof
                // that all previously saved terrain was deleted.
                if !fs::metadata(&self.root)?.is_dir() {
                    anyhow::bail!("Anvil world root is not a directory");
                }
                return Ok(out);
            }
            Err(error) => {
                return Err(error).with_context(|| format!("read {}", directory.display()));
            }
        };
        for entry in entries {
            let entry = entry?;
            let path = entry.path();
            if let Some((x, z)) = parse_external_filename(&path) {
                add_external_stamp(
                    external
                        .entry((x.div_euclid(32), z.div_euclid(32)))
                        .or_insert([0; 16]),
                    x,
                    z,
                    &entry.metadata()?,
                );
                continue;
            }
            let Some((x, z)) = parse_region_filename(&path) else {
                continue;
            };
            if !region_is_representable(x, z) {
                continue;
            }
            // Pregenerators may leave tens of thousands of durable zero-byte placeholders.
            // They contain no saved terrain and a later real header changes the file length,
            // so they must not become regional build work.
            if entry.metadata()?.len() == 0 {
                continue;
            }
            match read_region_header(&path, x, z) {
                Ok(header) => out.valid.push(header),
                Err(error) => out.failed.push(FailedRegion {
                    file_marker: fs::metadata(&path)
                        .map(|metadata| region_file_marker(&metadata) | (1 << 63))
                        .unwrap_or(u64::MAX),
                    path,
                    region_x: x,
                    region_z: z,
                    error: format!("{error:#}"),
                }),
            }
        }
        for header in &mut out.valid {
            header.external_stamp = external
                .get(&(header.region_x, header.region_z))
                .copied()
                .unwrap_or_default();
        }
        out.valid
            .sort_unstable_by_key(|header| (header.region_x, header.region_z));
        out.failed
            .sort_unstable_by_key(|header| (header.region_x, header.region_z));
        Ok(out)
    }

    /// Reconcile directory metadata once, reading a changed header only. Never retain all 8 KiB
    /// headers or reread the entire directory after each individual regional publication.
    pub fn region_inventory(
        &self,
        previous: &BTreeMap<(i32, i32), RegionAvailability>,
    ) -> Result<BTreeMap<(i32, i32), RegionAvailability>> {
        let directory = self.region_dir();
        let entries = match fs::read_dir(&directory) {
            Ok(entries) => entries,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound && self.root.is_dir() => {
                return Ok(BTreeMap::new());
            }
            Err(error) => return Err(error).context("enumerate saved Anvil regions"),
        };
        let mut inventory = BTreeMap::new();
        let mut external = BTreeMap::new();
        for entry in entries {
            let entry = entry?;
            let path = entry.path();
            if let Some((x, z)) = parse_external_filename(&path) {
                add_external_stamp(
                    external
                        .entry((x.div_euclid(32), z.div_euclid(32)))
                        .or_insert([0; 16]),
                    x,
                    z,
                    &entry.metadata()?,
                );
                continue;
            }
            let Some(coordinate) = parse_region_filename(&path) else {
                continue;
            };
            if !region_is_representable(coordinate.0, coordinate.1) {
                continue;
            }
            let metadata = entry.metadata()?;
            if metadata.len() == 0 {
                continue;
            }
            let marker = region_file_marker(&metadata);
            let availability = if let Some(old) = previous
                .get(&coordinate)
                .filter(|old| old.file_marker == marker && old.readable)
            {
                old.clone()
            } else {
                match read_region_header(&path, coordinate.0, coordinate.1) {
                    Ok(header) => RegionAvailability::from_header(&header),
                    Err(_) => RegionAvailability {
                        file_marker: marker,
                        header_fingerprint: [0; 16],
                        external_stamp: [0; 16],
                        saved: [0; 16],
                        readable: false,
                    },
                }
            };
            inventory.insert(coordinate, availability);
        }
        for (coordinate, availability) in &mut inventory {
            availability.external_stamp = external.get(coordinate).copied().unwrap_or_default();
        }
        Ok(inventory)
    }

    /// Captures one region immediately before an incremental build. This avoids coupling a
    /// bounded regional transaction to the time required to enumerate every other region in a
    /// large, actively saving world.
    pub fn region_header(&self, region_x: i32, region_z: i32) -> Result<Option<RegionHeader>> {
        if !region_is_representable(region_x, region_z) {
            return Ok(None);
        }
        let path = self
            .region_dir()
            .join(format!("r.{region_x}.{region_z}.mca"));
        match fs::metadata(&path) {
            Ok(metadata) if metadata.len() == 0 => return Ok(None),
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(error.into()),
        }
        match read_region_header(&path, region_x, region_z) {
            Ok(header) => Ok(Some(header)),
            Err(error)
                if error
                    .downcast_ref::<std::io::Error>()
                    .is_some_and(|error| error.kind() == std::io::ErrorKind::NotFound) =>
            {
                Ok(None)
            }
            Err(error) => Err(error),
        }
    }

    pub fn regional_reader<'a>(&'a self, header: &'a RegionHeader) -> Result<RegionalReader<'a>> {
        if header.entries.len() != 1024
            || header.path
                != self
                    .region_dir()
                    .join(format!("r.{}.{}.mca", header.region_x, header.region_z))
        {
            bail!("regional reader header does not belong to this source");
        }
        let opening = crate::diagnostics::Span::sync(Stage::SourceOpen);
        let file =
            File::open(&header.path).with_context(|| format!("open {}", header.path.display()))?;
        crate::diagnostics::count(Counter::SourceOpens, 1);
        if FileIdentity::from_metadata(&file.metadata()?) != header.file_identity
            || FileIdentity::from_metadata(&fs::metadata(&header.path)?) != header.file_identity
        {
            bail!("Anvil source snapshot changed: regional file changed before reading");
        }
        opening.finish(true, 0);
        Ok(RegionalReader {
            world: self,
            header,
            file,
            compressed: Vec::new(),
            decompressed: Vec::new(),
            content: Box::new([None; 1024]),
            external: BTreeMap::new(),
        })
    }

    pub fn read_chunk(
        &self,
        x: i32,
        z: i32,
        registry: &Arc<RwLock<Registry>>,
    ) -> Result<Option<ParsedChunk>> {
        let Some(header) = self.region_header(x.div_euclid(32), z.div_euclid(32))? else {
            return Ok(None);
        };
        let mut reader = self.regional_reader(&header)?;
        let chunk = match reader.read_chunk(x, z)? {
            Some(mut source) => Some(source.decode(registry)?),
            None => None,
        };
        reader.verify()?;
        Ok(chunk)
    }

    pub fn chunk_fingerprint(&self, x: i32, z: i32) -> Result<Option<u64>> {
        let Some(header) = self.region_header(x.div_euclid(32), z.div_euclid(32))? else {
            return Ok(None);
        };
        let mut reader = self.regional_reader(&header)?;
        let fingerprint = reader
            .read_chunk(x, z)?
            .map(|source| source.source_fingerprint());
        reader.verify()?;
        Ok(fingerprint)
    }

    pub fn region_fingerprints(&self, header: &RegionHeader) -> Result<Vec<Option<u64>>> {
        let base_x = header
            .region_x
            .checked_mul(32)
            .context("region chunk x overflow")?;
        let base_z = header
            .region_z
            .checked_mul(32)
            .context("region chunk z overflow")?;
        let mut reader = self.regional_reader(header)?;
        let mut output = Vec::with_capacity(1024);
        for slot in 0..1024 {
            let x = base_x
                .checked_add(slot & 31)
                .context("region chunk x overflow")?;
            let z = base_z
                .checked_add(slot >> 5)
                .context("region chunk z overflow")?;
            output.push(
                reader
                    .read_chunk(x, z)?
                    .map(|source| source.source_fingerprint()),
            );
        }
        reader.verify()?;
        Ok(output)
    }

    pub fn load_level_zero_group(
        &self,
        x: i32,
        z: i32,
        registry: &Arc<RwLock<Registry>>,
    ) -> Result<LevelZeroGroup> {
        let base_x = x.checked_mul(2).context("chunk-group x overflow")?;
        let base_z = z.checked_mul(2).context("chunk-group z overflow")?;
        let Some(header) = self.region_header(base_x.div_euclid(32), base_z.div_euclid(32))? else {
            return Ok(LevelZeroGroup {
                x,
                z,
                chunks: vec![None; 4],
            });
        };
        let mut reader = self.regional_reader(&header)?;
        let group = reader.load_level_zero_group(x, z, registry)?;
        reader.verify()?;
        Ok(group)
    }

    pub fn verify_sources(&self, sources: &[(i32, i32, Option<u64>)]) -> Result<()> {
        for &(x, z, expected) in sources {
            if self.chunk_fingerprint(x, z)? != expected {
                bail!("source chunk ({x},{z}) changed before LOD publication");
            }
        }
        Ok(())
    }

    /// Missing light arrays are not proof of sky exposure (especially underground or in custom
    /// dimension types), so only an explicit Anvil SkyLight array contributes sky light.
    pub fn default_sky_light(&self) -> u8 {
        0
    }
}

impl LevelZeroGroup {
    pub fn keys(&self) -> Vec<SectionKey> {
        self.chunks
            .iter()
            .filter_map(Option::as_ref)
            .flat_map(|chunk| chunk.sections.keys())
            .filter_map(|&y| SectionKey::new(0, self.x, y.div_euclid(2), self.z).ok())
            .collect::<std::collections::BTreeSet<_>>()
            .into_iter()
            .collect()
    }

    pub fn sources(&self) -> Vec<(i32, i32, Option<u64>)> {
        let base_x = self.x * 2;
        let base_z = self.z * 2;
        self.chunks
            .iter()
            .enumerate()
            .map(|(index, chunk)| {
                (
                    base_x + (index as i32 & 1),
                    base_z + (index as i32 >> 1),
                    chunk.as_ref().map(|chunk| chunk.source_fingerprint),
                )
            })
            .collect()
    }

    pub fn build(&self, key: SectionKey, world: &AnvilWorld) -> Result<BuiltLevelZero> {
        if key.level != 0 || key.x != self.x || key.z != self.z {
            bail!("level-zero key does not belong to loaded 2x2 chunk group");
        }
        let base_section_y = key.y * 2;
        let mut cells = vec![
            Cell {
                block: 0,
                biome: 0,
                light: world.default_sky_light(),
            };
            SECTION_VOLUME
        ];
        for dy in 0..2 {
            for dz in 0..2 {
                for dx in 0..2 {
                    let Some(section) = self
                        .chunks
                        .get(dx + dz * 2)
                        .and_then(Option::as_ref)
                        .and_then(|chunk| chunk.sections.get(&(base_section_y + dy as i32)))
                    else {
                        continue;
                    };
                    for y in 0..16 {
                        for z in 0..16 {
                            let source = (z << 4) | (y << 8);
                            let destination = cell_index(dx * 16, dy * 16 + y, dz * 16 + z);
                            cells[destination..destination + 16]
                                .copy_from_slice(&section.cells[source..source + 16]);
                        }
                    }
                }
            }
        }
        let section = Section::from_cells(key, cells)?;
        let sources = self.sources();
        Ok(BuiltLevelZero { section, sources })
    }
}

pub fn discover_dimensions(root: &Path) -> Result<Vec<DimensionSpec>> {
    let mut out = Vec::new();
    for (id, relative) in [
        ("minecraft:overworld", ""),
        ("minecraft:the_nether", "DIM-1"),
        ("minecraft:the_end", "DIM1"),
    ] {
        let path = root.join(relative);
        if path.join("region").is_dir() {
            out.push(DimensionSpec {
                id: id.into(),
                root: path,
            });
        }
    }
    let custom = root.join("dimensions");
    if custom.is_dir() {
        for namespace in fs::read_dir(&custom)? {
            let namespace = namespace?;
            if !namespace.path().is_dir() {
                continue;
            }
            find_custom_dimensions(
                &namespace.path(),
                namespace.file_name().to_string_lossy().as_ref(),
                Path::new(""),
                &mut out,
            )?;
        }
    }
    out.sort_unstable_by(|a, b| a.id.cmp(&b.id));
    out.dedup_by(|a, b| a.id == b.id);
    if out.is_empty() {
        bail!("{} contains no Anvil region directories", root.display());
    }
    Ok(out)
}

fn find_custom_dimensions(
    base: &Path,
    namespace: &str,
    relative: &Path,
    out: &mut Vec<DimensionSpec>,
) -> Result<()> {
    let current = base.join(relative);
    if current.join("region").is_dir() {
        let path = relative
            .to_string_lossy()
            .replace(std::path::MAIN_SEPARATOR, "/");
        out.push(DimensionSpec {
            id: format!("{namespace}:{path}"),
            root: current,
        });
        return Ok(());
    }
    for entry in fs::read_dir(&current)? {
        let entry = entry?;
        if entry.path().is_dir() {
            find_custom_dimensions(base, namespace, &relative.join(entry.file_name()), out)?;
        }
    }
    Ok(())
}

fn read_region_header(path: &Path, region_x: i32, region_z: i32) -> Result<RegionHeader> {
    let file = File::open(path)?;
    let metadata = file.metadata()?;
    let file_identity = FileIdentity::from_metadata(&metadata);
    let mut header = [0u8; REGION_HEADER_BYTES];
    if metadata.len() != 0 {
        file.read_exact_at(&mut header, 0)?;
    }
    if FileIdentity::from_metadata(&file.metadata()?) != file_identity
        || FileIdentity::from_metadata(&fs::metadata(path)?) != file_identity
    {
        bail!("Anvil source snapshot changed: file changed while capturing its header");
    }
    let mut entries = Vec::with_capacity(1024);
    for index in 0..1024 {
        let at = index * 4;
        entries.push(RegionEntry {
            location: u32::from_be_bytes([0, header[at], header[at + 1], header[at + 2]]) << 8
                | u32::from(header[at + 3]),
            timestamp: u32::from_be_bytes(header[4096 + at..4096 + at + 4].try_into().unwrap()),
        });
    }
    Ok(RegionHeader {
        path: path.to_owned(),
        region_x,
        region_z,
        entries,
        file_marker: region_file_marker(&metadata),
        external_stamp: [0; 16],
        file_identity,
    })
}

fn parse_external_filename(path: &Path) -> Option<(i32, i32)> {
    let name = path.file_name()?.to_str()?;
    let mut parts = name.split('.');
    if parts.next()? != "c" {
        return None;
    }
    let x: i32 = parts.next()?.parse().ok()?;
    let z: i32 = parts.next()?.parse().ok()?;
    if parts.next()? != "mcc" || parts.next().is_some() || name != format!("c.{x}.{z}.mcc") {
        return None;
    }
    Some((x, z))
}

/// Commutative metadata aggregation keeps directory order irrelevant. This discovers changes;
/// only subsequently read, verified content bytes may authorize semantic reuse.
fn add_external_stamp(stamp: &mut [u8; 16], x: i32, z: i32, metadata: &fs::Metadata) {
    let identity = FileIdentity::from_metadata(metadata);
    let mut hash = blake3::Hasher::new();
    hash.update(b"Voxy external Anvil metadata\0");
    hash.update(&x.to_le_bytes());
    hash.update(&z.to_le_bytes());
    for value in [
        identity.device,
        identity.inode,
        identity.length,
        identity.modified.0 as u64,
        identity.modified.1 as u64,
        identity.changed.0 as u64,
        identity.changed.1 as u64,
    ] {
        hash.update(&value.to_le_bytes());
    }
    for (output, input) in stamp.iter_mut().zip(hash.finalize().as_bytes()) {
        *output ^= input;
    }
}

fn region_file_marker(metadata: &fs::Metadata) -> u64 {
    metadata
        .modified()
        .ok()
        .and_then(|time| time.duration_since(std::time::UNIX_EPOCH).ok())
        .map_or(metadata.len(), |duration| {
            duration.as_nanos() as u64 ^ metadata.len()
        })
        & !(1 << 63)
}

fn parse_region_filename(path: &Path) -> Option<(i32, i32)> {
    let name = path.file_name()?.to_str()?;
    let parts = name.split('.').collect::<Vec<_>>();
    if parts.len() != 4 || parts[0] != "r" || parts[3] != "mca" {
        return None;
    }
    Some((parts[1].parse().ok()?, parts[2].parse().ok()?))
}

fn region_is_representable(region_x: i32, region_z: i32) -> bool {
    // One Anvil region spans 32 level-zero Voxy sections. Every local coordinate must fit the
    // signed 24-bit section-key fields.
    const MIN_REGION: i32 = crate::key::COORD_MIN / 32;
    const MAX_REGION: i32 = (crate::key::COORD_MAX - 31) / 32;
    (MIN_REGION..=MAX_REGION).contains(&region_x) && (MIN_REGION..=MAX_REGION).contains(&region_z)
}

fn decompress_into(kind: u8, compressed: &[u8], output: &mut Vec<u8>) -> Result<()> {
    output.clear();
    let reader: Box<dyn Read> = match kind {
        1 => Box::new(GzDecoder::new(compressed)),
        2 => Box::new(ZlibDecoder::new(compressed)),
        3 => Box::new(Cursor::new(compressed)),
        4 => Box::new(Lz4BlockInput::new(compressed)),
        _ => bail!("unsupported Anvil compression type {kind}"),
    };
    reader
        .take(MAX_DECOMPRESSED_CHUNK + 1)
        .read_to_end(output)?;
    if output.len() as u64 > MAX_DECOMPRESSED_CHUNK {
        bail!("decompressed chunk exceeds {MAX_DECOMPRESSED_CHUNK} bytes");
    }
    Ok(())
}

struct NormalizedSection {
    nbt: SectionNbt,
    blocks: Vec<u32>,
    biomes: Vec<u32>,
    block_layout: PackedPalette,
    biome_layout: PackedPalette,
}

impl NormalizedSection {
    fn prepare(nbt: SectionNbt, registry: &Arc<RwLock<Registry>>) -> Result<Self> {
        let (names, data) = if let Some(states) = &nbt.block_states {
            if states.palette.is_empty() || states.palette.len() > 4096 {
                bail!(
                    "section {} block palette has invalid size {}",
                    nbt.y,
                    states.palette.len()
                );
            }
            (
                states
                    .palette
                    .iter()
                    .map(canonical_block_state)
                    .collect::<Vec<_>>(),
                states.data.as_ref().map(|data| &data[..]),
            )
        } else {
            (vec!["minecraft:air".to_owned()], None)
        };
        let fallback;
        let biome_names = match &nbt.biomes {
            Some(biomes) if !biomes.palette.is_empty() => &biomes.palette[..],
            _ => {
                fallback = ["minecraft:plains".to_owned()];
                &fallback[..]
            }
        };
        if biome_names.len() > 64 {
            bail!("section {} biome palette exceeds 64 entries", nbt.y);
        }
        if names
            .iter()
            .chain(biome_names.iter())
            .any(|name| name.is_empty() || name.len() > 4096)
        {
            bail!(
                "section {} contains an empty or overlong mapping name",
                nbt.y
            );
        }
        let biome_data = nbt
            .biomes
            .as_ref()
            .and_then(|biomes| biomes.data.as_ref())
            .map(|data| &data[..]);
        // Cardinality and every packed index are validated before adding mapping IDs, as before.
        let block_layout = PackedPalette::validate(data, names.len(), 4096, 4)?;
        let biome_layout = PackedPalette::validate(biome_data, biome_names.len(), 64, 1)?;
        let (blocks, biomes) = {
            let mut registry = write_lock(registry)?;
            let blocks = names
                .iter()
                .map(|name| registry.block_id(name))
                .collect::<Result<Vec<_>>>()?;
            let biomes = biome_names
                .iter()
                .map(|name| registry.biome_id(name))
                .collect::<Result<Vec<_>>>()?;
            (blocks, biomes)
        };
        if nbt
            .block_light
            .as_ref()
            .is_some_and(|data| data.len() != 2048)
            || nbt
                .sky_light
                .as_ref()
                .is_some_and(|data| data.len() != 2048)
        {
            bail!("section {} has a malformed light array", nbt.y);
        }
        Ok(Self {
            nbt,
            blocks,
            biomes,
            block_layout,
            biome_layout,
        })
    }

    fn cell(&self, index: usize, default_sky_light: u8) -> Cell {
        let block_data = self
            .nbt
            .block_states
            .as_ref()
            .and_then(|states| states.data.as_ref())
            .map(|data| &data[..]);
        let biome_data = self
            .nbt
            .biomes
            .as_ref()
            .and_then(|biomes| biomes.data.as_ref())
            .map(|data| &data[..]);
        let biome_index =
            ((index & 15) >> 2) | ((((index >> 4) & 15) >> 2) << 2) | (((index >> 8) >> 2) << 4);
        let block = self.blocks[self.block_layout.index(block_data, index)];
        let biome = if block == 0 {
            0
        } else {
            self.biomes[self.biome_layout.index(biome_data, biome_index)]
        };
        let block_light = nibble(
            self.nbt.block_light.as_ref().map(|data| &data[..]),
            index,
            0,
        );
        let sky_light = nibble(
            self.nbt.sky_light.as_ref().map(|data| &data[..]),
            index,
            default_sky_light,
        );
        Cell {
            block,
            biome,
            light: (block_light << 4) | sky_light,
        }
    }
}

struct NormalizedChunk {
    x: i32,
    z: i32,
    sections: BTreeMap<i32, NormalizedSection>,
}

impl NormalizedChunk {
    fn read(bytes: &[u8], registry: &Arc<RwLock<Registry>>) -> Result<Self> {
        let decoded: ChunkNbt = crate::diagnostics::sync_result(Stage::SourceNbt, || {
            fastnbt::from_bytes(bytes).context("decode chunk NBT")
        })?;
        let mut sections = BTreeMap::new();
        if decoded.status == "full" || decoded.status == "minecraft:full" {
            for section in decoded.sections {
                let y = i32::from(section.y);
                // Keep input registration order; BTreeMap supplies only the digest's sorted order.
                let section = NormalizedSection::prepare(section, registry)?;
                if sections.insert(y, section).is_some() {
                    bail!(
                        "chunk ({},{}) contains duplicate section Y {}",
                        decoded.x,
                        decoded.z,
                        y
                    );
                }
            }
        }
        Ok(Self {
            x: decoded.x,
            z: decoded.z,
            sections,
        })
    }

    fn check_coordinates(&self, x: i32, z: i32) -> Result<()> {
        if self.x != x || self.z != z {
            bail!(
                "chunk coordinate mismatch: requested ({x},{z}), NBT says ({},{})",
                self.x,
                self.z
            );
        }
        Ok(())
    }

    fn fingerprint(&self, default_sky_light: u8) -> TerrainFingerprint {
        let hashing = crate::diagnostics::Span::sync(Stage::SemanticHash);
        let mut hash = TerrainHasher::new(self.sections.len());
        for (&y, section) in &self.sections {
            hash.section(
                y,
                (0..4096).map(|index| section.cell(index, default_sky_light)),
            );
        }
        hashing.finish(true, self.sections.len() as u64 * 4096);
        hash.finish()
    }

    fn materialize(self, default_sky_light: u8) -> ParsedChunk {
        let building = crate::diagnostics::Span::sync(Stage::CellBuild);
        let count = self.sections.len() as u64 * 4096;
        let sections = self
            .sections
            .into_iter()
            .map(|(y, section)| {
                let cells = (0..4096)
                    .map(|index| section.cell(index, default_sky_light))
                    .collect();
                (y, ChunkSection { y, cells })
            })
            .collect();
        crate::diagnostics::count(Counter::MaterializedCells, count);
        crate::diagnostics::count(
            Counter::MaterializedBytes,
            count * std::mem::size_of::<Cell>() as u64,
        );
        building.finish(true, count);
        let terrain_fingerprint = terrain_fingerprint(&sections);
        ParsedChunk {
            x: self.x,
            z: self.z,
            sections,
            source_fingerprint: 0,
            terrain_fingerprint,
        }
    }
}

fn parse_chunk(
    bytes: &[u8],
    registry: &Arc<RwLock<Registry>>,
    default_sky_light: u8,
) -> Result<ParsedChunk> {
    let chunk = NormalizedChunk::read(bytes, registry)?;
    Ok(chunk.materialize(default_sky_light))
}

const TERRAIN_SEED_A: u64 = 0x5658_5932_5445_5252;
const TERRAIN_SEED_B: u64 = 0x9e37_79b9_7f4a_7c15;

struct TerrainHasher {
    hashes: [Xxh64; 2],
}

impl TerrainHasher {
    fn new(count: usize) -> Self {
        let mut hash = Self {
            hashes: [Xxh64::new(TERRAIN_SEED_A), Xxh64::new(TERRAIN_SEED_B)],
        };
        hash.update(&(count as u32).to_le_bytes());
        hash
    }

    fn update(&mut self, bytes: &[u8]) {
        for hash in &mut self.hashes {
            hash.update(bytes);
        }
    }

    fn section(&mut self, y: i32, cells: impl Iterator<Item = Cell>) {
        self.update(&y.to_le_bytes());
        let mut bytes = [0u8; 32 * 9];
        let mut count = 0;
        for cell in cells {
            let at = count * 9;
            bytes[at..at + 4].copy_from_slice(&cell.block.to_le_bytes());
            bytes[at + 4..at + 8].copy_from_slice(&cell.biome.to_le_bytes());
            bytes[at + 8] = cell.light;
            count += 1;
            if count == 32 {
                self.update(&bytes);
                count = 0;
            }
        }
        self.update(&bytes[..count * 9]);
    }

    fn finish(self) -> TerrainFingerprint {
        [self.hashes[0].finish(), self.hashes[1].finish()]
    }
}

/// The digest byte order is shared by full decoding and semantic-only inspection.
fn terrain_fingerprint(sections: &BTreeMap<i32, ChunkSection>) -> TerrainFingerprint {
    let hashing = crate::diagnostics::Span::sync(Stage::SemanticHash);
    let mut hash = TerrainHasher::new(sections.len());
    for (&y, section) in sections {
        hash.section(y, section.cells.iter().copied());
    }
    hashing.finish(true, sections.len() as u64 * 4096);
    hash.finish()
}

fn canonical_block_state(entry: &BlockPaletteNbt) -> String {
    if entry.properties.is_empty() {
        return entry.name.clone();
    }
    let properties = entry
        .properties
        .iter()
        .map(|(key, value)| format!("{key}={value}"))
        .collect::<Vec<_>>()
        .join(",");
    format!("{}[{properties}]", entry.name)
}

#[derive(Clone, Copy)]
struct PackedPalette {
    per_long: usize,
    bits: u8,
    mask: u64,
}

impl PackedPalette {
    fn validate(
        data: Option<&[i64]>,
        palette_len: usize,
        count: usize,
        minimum_bits: u8,
    ) -> Result<Self> {
        if palette_len == 0 {
            bail!("empty Anvil palette");
        }
        if palette_len > count {
            bail!("Anvil palette has {palette_len} entries for only {count} values");
        }
        if palette_len == 1 {
            return Ok(Self {
                per_long: 0,
                bits: 0,
                mask: 0,
            });
        }
        let bits = minimum_bits.max((usize::BITS - (palette_len - 1).leading_zeros()) as u8);
        if bits >= 64 {
            bail!("Anvil palette needs unsupported {bits}-bit indexes");
        }
        let per_long = 64 / bits as usize;
        let expected = count.div_ceil(per_long);
        let values = data.context("multi-value palette is missing packed data")?;
        if values.len() != expected {
            bail!(
                "packed palette has {} longs; expected exactly {expected}",
                values.len()
            );
        }
        let layout = Self {
            per_long,
            bits,
            mask: (1u64 << bits) - 1,
        };
        for index in 0..count {
            let value = layout.index(data, index);
            if value >= palette_len {
                bail!("packed palette index {value} exceeds {palette_len} entries");
            }
        }
        Ok(layout)
    }

    fn index(self, data: Option<&[i64]>, index: usize) -> usize {
        if self.per_long == 0 {
            return 0;
        }
        ((data.expect("validated packed palette")[index / self.per_long] as u64
            >> ((index % self.per_long) * self.bits as usize))
            & self.mask) as usize
    }
}

pub fn unpack_anvil_palette(
    data: Option<&[i64]>,
    palette_len: usize,
    count: usize,
    minimum_bits: u8,
) -> Result<Vec<usize>> {
    let layout = PackedPalette::validate(data, palette_len, count, minimum_bits)?;
    Ok((0..count).map(|index| layout.index(data, index)).collect())
}

fn fingerprint(sector: u32, sectors: u8, compression: u8, bytes: &[u8]) -> u64 {
    let seed = (u64::from(sector) << 32)
        ^ (u64::from(sectors) << 24)
        ^ (u64::from(compression) << 16)
        ^ bytes.len() as u64;
    xxh64(bytes, seed)
}

fn nibble(data: Option<&[i8]>, index: usize, missing: u8) -> u8 {
    let Some(data) = data else { return missing };
    let Some(&byte) = data.get(index >> 1) else {
        return missing;
    };
    ((byte as u8) >> ((index & 1) * 4)) & 15
}

#[cfg(test)]
#[path = "anvil_array_tests.rs"]
mod array_tests;

#[cfg(test)]
mod region_boundary_tests {
    use super::*;
    use crate::regional::SectionFrame;

    #[test]
    fn group_rows_match_coordinate_oracle() {
        let world = AnvilWorld::new("minecraft:overworld".into(), PathBuf::new());
        for (gx, gy, gz) in [(0, 0, 0), (-3, -1, -7), (2, -2, 4), (-1, 1, 0)] {
            for present in [0u8, 255, 0b01011001] {
                let mut group = LevelZeroGroup {
                    x: gx,
                    z: gz,
                    chunks: vec![None; 4],
                };
                for dz in 0..2 {
                    for dx in 0..2 {
                        let mut sections = BTreeMap::new();
                        for dy in 0..2 {
                            let source = dx + dz * 2 + dy * 4;
                            if present & (1 << source) == 0 {
                                continue;
                            }
                            let cells = (0..4096)
                                .map(|i| Cell {
                                    // Include explicit stored air with non-default biome/light.
                                    block: if i % 11 == 0 {
                                        0
                                    } else {
                                        (source * 4096 + i + 1) as u32
                                    },
                                    biome: (source * 4096 + i) as u32,
                                    light: (i ^ (source * 37)) as u8,
                                })
                                .collect();
                            let y = gy * 2 + dy as i32;
                            sections.insert(y, ChunkSection { y, cells });
                        }
                        if !sections.is_empty() {
                            group.chunks[dx + dz * 2] = Some(ParsedChunk {
                                x: gx * 2 + dx as i32,
                                z: gz * 2 + dz as i32,
                                sections,
                                source_fingerprint: (dx + dz * 2 + 1) as u64,
                                terrain_fingerprint: [0; 2],
                            });
                        }
                    }
                }
                let key = SectionKey::new(0, gx, gy, gz).unwrap();
                let actual = group.build(key, &world).unwrap();
                let expected = (0..SECTION_VOLUME)
                    .map(|i| {
                        let x = i % 32;
                        let z = i / 32 % 32;
                        let y = i / 1024;
                        group.chunks[x / 16 + z / 16 * 2]
                            .as_ref()
                            .and_then(|chunk| chunk.sections.get(&(gy * 2 + (y / 16) as i32)))
                            .map(|section| section.cells[x % 16 + z % 16 * 16 + y % 16 * 256])
                            .unwrap_or(Cell {
                                block: 0,
                                biome: 0,
                                light: world.default_sky_light(),
                            })
                    })
                    .collect::<Vec<_>>();
                assert_eq!(
                    actual.section,
                    Section::from_cells(key, expected.clone()).unwrap()
                );
                assert_eq!(actual.sources, group.sources());
                if present != 0 {
                    let a = SectionFrame::new(0, actual.section.cells)
                        .unwrap()
                        .encode()
                        .unwrap();
                    let b = SectionFrame::new(0, expected).unwrap().encode().unwrap();
                    assert_eq!(a, b);
                    assert_eq!(blake3::hash(&a), blake3::hash(&b));
                }
                assert!(
                    group
                        .build(SectionKey::new(1, gx, gy, gz).unwrap(), &world)
                        .is_err()
                );
                assert!(
                    group
                        .build(SectionKey::new(0, gx + 1, gy, gz).unwrap(), &world)
                        .is_err()
                );
            }
        }
    }

    #[test]
    fn regional_source_ignores_unrepresentable_anvil_coordinates() {
        assert!(region_is_representable(-262_144, -262_144));
        assert!(region_is_representable(262_143, 262_143));
        assert!(!region_is_representable(-262_145, 0));
        assert!(!region_is_representable(262_144, 0));
    }
}
