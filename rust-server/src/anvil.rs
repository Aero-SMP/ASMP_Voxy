use crate::{
    crc::xxh64,
    key::SectionKey,
    lod::{Cell, SECTION_EDGE, SECTION_VOLUME, Section, cell_index},
    read_file_bounded,
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
    io::{Cursor, Read, Seek, SeekFrom},
    path::{Path, PathBuf},
    sync::{Arc, RwLock},
};

const REGION_HEADER_BYTES: usize = 8192;
const MAX_COMPRESSED_CHUNK: usize = 255 * 4096;
const MAX_EXTERNAL_CHUNK: usize = 128 * 1024 * 1024;
const MAX_DECOMPRESSED_CHUNK: u64 = 128 * 1024 * 1024;
const MAX_PLAYER_DATA_BYTES: u64 = 16 * 1024 * 1024;

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
    world_root: PathBuf,
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
struct PlayerNbt {
    #[serde(rename = "Dimension")]
    dimension: String,
    #[serde(rename = "Pos")]
    position: Vec<f64>,
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

impl AnvilWorld {
    pub fn new(dimension: String, root: PathBuf, world_root: PathBuf) -> Self {
        Self {
            dimension,
            root,
            world_root,
        }
    }

    /// Last saved player positions are the strongest bootstrap hint available before the first
    /// client can lease a published root. Reading them changes only import order; it never loads
    /// or generates a Minecraft chunk.
    pub fn saved_player_regions(&self) -> Result<BTreeMap<(i32, i32), u64>> {
        let directory = self.world_root.join("playerdata");
        let entries = match fs::read_dir(&directory) {
            Ok(entries) => entries,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Ok(BTreeMap::new());
            }
            Err(error) => {
                return Err(error).with_context(|| format!("read {}", directory.display()));
            }
        };
        let mut regions: BTreeMap<(i32, i32), u64> = BTreeMap::new();
        for entry in entries {
            let entry = entry?;
            let path = entry.path();
            if path.extension().is_none_or(|extension| extension != "dat") {
                continue;
            }
            let Ok(file) = File::open(&path) else {
                continue;
            };
            let Ok(player) = fastnbt::from_reader::<_, PlayerNbt>(
                GzDecoder::new(file).take(MAX_PLAYER_DATA_BYTES),
            ) else {
                continue;
            };
            if player.dimension != self.dimension || player.position.len() < 3 {
                continue;
            }
            let x = player.position[0];
            let z = player.position[2];
            if !x.is_finite()
                || !z.is_finite()
                || x < i32::MIN as f64
                || x > i32::MAX as f64
                || z < i32::MIN as f64
                || z > i32::MAX as f64
            {
                continue;
            }
            let coordinate = (
                (x.floor() as i32).div_euclid(512),
                (z.floor() as i32).div_euclid(512),
            );
            let saved = entry
                .metadata()
                .ok()
                .and_then(|metadata| metadata.modified().ok())
                .and_then(|time| time.duration_since(std::time::UNIX_EPOCH).ok())
                .map_or(0, |duration| duration.as_secs());
            regions
                .entry(coordinate)
                .and_modify(|current| *current = (*current).max(saved))
                .or_insert(saved);
        }
        Ok(regions)
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
        let entries = match fs::read_dir(&directory) {
            Ok(entries) => entries,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(out),
            Err(error) => {
                return Err(error).with_context(|| format!("read {}", directory.display()));
            }
        };
        for entry in entries {
            let entry = entry?;
            let path = entry.path();
            let Some((x, z)) = parse_region_filename(&path) else {
                continue;
            };
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
        out.valid
            .sort_unstable_by_key(|header| (header.region_x, header.region_z));
        out.failed
            .sort_unstable_by_key(|header| (header.region_x, header.region_z));
        Ok(out)
    }

    /// Captures one region immediately before an incremental build. This avoids coupling a
    /// bounded regional transaction to the time required to enumerate every other region in a
    /// large, actively saving world.
    pub fn region_header(&self, region_x: i32, region_z: i32) -> Result<Option<RegionHeader>> {
        let path = self
            .region_dir()
            .join(format!("r.{region_x}.{region_z}.mca"));
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

    pub fn read_chunk(
        &self,
        chunk_x: i32,
        chunk_z: i32,
        registry: &Arc<RwLock<Registry>>,
    ) -> Result<Option<ParsedChunk>> {
        let path = self.region_path(chunk_x, chunk_z);
        let mut file = match File::open(&path) {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(error).with_context(|| format!("open {}", path.display())),
        };
        // Some pregenerators leave durable zero-byte placeholders. They are an unambiguous
        // empty region snapshot; a later Anvil header changes both length and file marker.
        if file.metadata()?.len() == 0 {
            return Ok(None);
        }
        let index = (chunk_x.rem_euclid(32) + chunk_z.rem_euclid(32) * 32) as u64;
        file.seek(SeekFrom::Start(index * 4))?;
        let mut location = [0u8; 4];
        file.read_exact(&mut location)?;
        let sector = u32::from_be_bytes([0, location[0], location[1], location[2]]);
        let sectors = location[3] as usize;
        if sector == 0 || sectors == 0 {
            return Ok(None);
        }
        if sector < 2 {
            bail!("chunk ({chunk_x},{chunk_z}) points inside its region header");
        }
        file.seek(SeekFrom::Start(u64::from(sector) * 4096))?;
        let mut length_bytes = [0u8; 4];
        file.read_exact(&mut length_bytes)?;
        let length = u32::from_be_bytes(length_bytes) as usize;
        if length == 0 || length > sectors * 4096 - 4 || length > MAX_COMPRESSED_CHUNK {
            bail!("chunk ({chunk_x},{chunk_z}) has invalid compressed length {length}");
        }
        let mut compression = [0u8; 1];
        file.read_exact(&mut compression)?;
        let external = compression[0] & 0x80 != 0;
        let compression = compression[0] & 0x7f;
        let compressed = if external {
            read_file_bounded(
                &self.region_dir().join(format!("c.{chunk_x}.{chunk_z}.mcc")),
                MAX_EXTERNAL_CHUNK,
            )?
        } else {
            let mut bytes = vec![0; length - 1];
            file.read_exact(&mut bytes)?;
            bytes
        };
        let source_fingerprint = fingerprint(sector, sectors as u8, compression, &compressed);
        let nbt = decompress(compression, &compressed).with_context(|| {
            format!(
                "decompress chunk ({chunk_x},{chunk_z}) in {}",
                path.display()
            )
        })?;
        let mut chunk = parse_chunk(&nbt, registry, self.default_sky_light())?;
        if chunk.x != chunk_x || chunk.z != chunk_z {
            bail!(
                "chunk coordinate mismatch: requested ({chunk_x},{chunk_z}), NBT says ({},{})",
                chunk.x,
                chunk.z
            );
        }
        chunk.source_fingerprint = source_fingerprint;
        Ok(Some(chunk))
    }

    pub fn chunk_fingerprint(&self, chunk_x: i32, chunk_z: i32) -> Result<Option<u64>> {
        let path = self.region_path(chunk_x, chunk_z);
        let mut file = match File::open(&path) {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(error.into()),
        };
        if file.metadata()?.len() == 0 {
            return Ok(None);
        }
        let index = (chunk_x.rem_euclid(32) + chunk_z.rem_euclid(32) * 32) as u64;
        file.seek(SeekFrom::Start(index * 4))?;
        let mut location = [0u8; 4];
        file.read_exact(&mut location)?;
        let sector = u32::from_be_bytes([0, location[0], location[1], location[2]]);
        let sectors = location[3] as usize;
        if sector == 0 || sectors == 0 {
            return Ok(None);
        }
        if sector < 2 {
            bail!("chunk ({chunk_x},{chunk_z}) points inside its region header");
        }
        file.seek(SeekFrom::Start(u64::from(sector) * 4096))?;
        let mut length_bytes = [0; 4];
        file.read_exact(&mut length_bytes)?;
        let length = u32::from_be_bytes(length_bytes) as usize;
        if length == 0 || length > sectors * 4096 - 4 || length > MAX_COMPRESSED_CHUNK {
            bail!("invalid chunk length while fingerprinting");
        }
        let mut compression = [0];
        file.read_exact(&mut compression)?;
        let external = compression[0] & 0x80 != 0;
        let kind = compression[0] & 0x7f;
        let bytes = if external {
            read_file_bounded(
                &self.region_dir().join(format!("c.{chunk_x}.{chunk_z}.mcc")),
                MAX_EXTERNAL_CHUNK,
            )?
        } else {
            let mut bytes = vec![0; length - 1];
            file.read_exact(&mut bytes)?;
            bytes
        };
        let limit = if external {
            MAX_EXTERNAL_CHUNK
        } else {
            MAX_COMPRESSED_CHUNK
        };
        if bytes.len() > limit {
            bail!("chunk exceeds compressed size limit while fingerprinting");
        }
        Ok(Some(fingerprint(sector, sectors as u8, kind, &bytes)))
    }

    /// Reads every canonical compressed-chunk fingerprint from one already-snapshotted region
    /// through a single file handle. Incremental surface publication uses this compact source table
    /// to distinguish the actually changed 2×2 chunk groups from unrelated region rewrites.
    pub fn region_fingerprints(&self, header: &RegionHeader) -> Result<Vec<Option<u64>>> {
        if header.entries.len() != 1024 {
            bail!("region fingerprint scan requires exactly 1024 header entries");
        }
        let expected_path = self
            .region_dir()
            .join(format!("r.{}.{}.mca", header.region_x, header.region_z));
        if header.path != expected_path {
            bail!("region fingerprint header path disagrees with its coordinates");
        }
        let mut file =
            File::open(&header.path).with_context(|| format!("open {}", header.path.display()))?;
        if region_file_marker(&file.metadata()?) != header.file_marker {
            bail!("region changed before its fingerprint scan began");
        }
        let base_x = header
            .region_x
            .checked_mul(32)
            .context("region chunk x overflow")?;
        let base_z = header
            .region_z
            .checked_mul(32)
            .context("region chunk z overflow")?;
        let mut output = Vec::with_capacity(1024);
        for (slot, entry) in header.entries.iter().enumerate() {
            let sector = entry.location >> 8;
            let sectors = (entry.location & 0xff) as usize;
            if sector == 0 || sectors == 0 {
                output.push(None);
                continue;
            }
            if sector < 2 {
                bail!("region chunk entry points inside its header");
            }
            file.seek(SeekFrom::Start(u64::from(sector) * 4096))?;
            let mut length_bytes = [0u8; 4];
            file.read_exact(&mut length_bytes)?;
            let length = u32::from_be_bytes(length_bytes) as usize;
            if length == 0 || length > sectors * 4096 - 4 || length > MAX_COMPRESSED_CHUNK {
                bail!("invalid compressed chunk length during region fingerprint scan");
            }
            let mut compression = [0u8; 1];
            file.read_exact(&mut compression)?;
            let external = compression[0] & 0x80 != 0;
            let kind = compression[0] & 0x7f;
            let chunk_x = base_x
                .checked_add(slot as i32 & 31)
                .context("region chunk x overflow")?;
            let chunk_z = base_z
                .checked_add(slot as i32 >> 5)
                .context("region chunk z overflow")?;
            let bytes = if external {
                read_file_bounded(
                    &self.region_dir().join(format!("c.{chunk_x}.{chunk_z}.mcc")),
                    MAX_EXTERNAL_CHUNK,
                )?
            } else {
                let mut bytes = vec![0; length - 1];
                file.read_exact(&mut bytes)?;
                bytes
            };
            output.push(Some(fingerprint(sector, sectors as u8, kind, &bytes)));
        }
        if region_file_marker(&file.metadata()?) != header.file_marker {
            bail!("region changed during its fingerprint scan");
        }
        Ok(output)
    }

    pub fn load_level_zero_group(
        &self,
        group_x: i32,
        group_z: i32,
        registry: &Arc<RwLock<Registry>>,
    ) -> Result<LevelZeroGroup> {
        let base_chunk_x = group_x.checked_mul(2).context("chunk-group x overflow")?;
        let base_chunk_z = group_z.checked_mul(2).context("chunk-group z overflow")?;
        let mut chunks = Vec::with_capacity(4);
        for dz in 0..2 {
            for dx in 0..2 {
                let x = base_chunk_x.checked_add(dx).context("chunk x overflow")?;
                let z = base_chunk_z.checked_add(dz).context("chunk z overflow")?;
                chunks.push(self.read_chunk(x, z, registry)?);
            }
        }
        Ok(LevelZeroGroup {
            x: group_x,
            z: group_z,
            chunks,
        })
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
        for y in 0..SECTION_EDGE {
            let section_y = base_section_y + (y / 16) as i32;
            let local_y = y & 15;
            for z in 0..SECTION_EDGE {
                let dz = z / 16;
                let local_z = z & 15;
                for x in 0..SECTION_EDGE {
                    let dx = x / 16;
                    let local_x = x & 15;
                    let Some(section) = self
                        .chunks
                        .get(dx + dz * 2)
                        .and_then(Option::as_ref)
                        .and_then(|chunk| chunk.sections.get(&section_y))
                    else {
                        continue;
                    };
                    cells[cell_index(x, y, z)] =
                        section.cells[local_x | (local_z << 4) | (local_y << 8)];
                }
            }
        }
        let section = Section::from_cells(key, cells)?;
        let sources = self.sources();
        Ok(BuiltLevelZero { section, sources })
    }
}

pub fn discover_dimensions(root: &Path, fallback_dimension: &str) -> Result<Vec<DimensionSpec>> {
    let mut out = Vec::new();
    let looks_like_world_root = root.join("level.dat").is_file()
        || root.join("DIM-1").is_dir()
        || root.join("DIM1").is_dir()
        || root.join("dimensions").is_dir();
    if root.join("region").is_dir() && !looks_like_world_root {
        out.push(DimensionSpec {
            id: fallback_dimension.to_owned(),
            root: root.to_owned(),
        });
        return Ok(out);
    }
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
    let mut file = File::open(path)?;
    let metadata = file.metadata()?;
    let mut header = [0u8; REGION_HEADER_BYTES];
    if metadata.len() != 0 {
        file.read_exact(&mut header)?;
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
    })
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

fn decompress(kind: u8, compressed: &[u8]) -> Result<Vec<u8>> {
    let reader: Box<dyn Read> = match kind {
        1 => Box::new(GzDecoder::new(compressed)),
        2 => Box::new(ZlibDecoder::new(compressed)),
        3 => Box::new(Cursor::new(compressed)),
        4 => Box::new(Lz4BlockInput::new(compressed)),
        _ => bail!("unsupported Anvil compression type {kind}"),
    };
    let mut output = Vec::new();
    reader
        .take(MAX_DECOMPRESSED_CHUNK + 1)
        .read_to_end(&mut output)?;
    if output.len() as u64 > MAX_DECOMPRESSED_CHUNK {
        bail!("decompressed chunk exceeds {MAX_DECOMPRESSED_CHUNK} bytes");
    }
    Ok(output)
}

fn parse_chunk(
    bytes: &[u8],
    registry: &Arc<RwLock<Registry>>,
    default_sky_light: u8,
) -> Result<ParsedChunk> {
    let decoded: ChunkNbt = fastnbt::from_bytes(bytes).context("decode chunk NBT")?;
    if decoded.status != "full" && decoded.status != "minecraft:full" {
        // A valid in-progress Anvil chunk is a confirmed temporary absence, not corruption.
        // Remembering its source fingerprint with an empty section set prevents a pregenerator
        // from forcing the same decompression and error log every poll; a later save changes the
        return Ok(ParsedChunk {
            x: decoded.x,
            z: decoded.z,
            sections: BTreeMap::new(),
            source_fingerprint: 0,
            terrain_fingerprint: terrain_fingerprint(&BTreeMap::new()),
        });
    }
    let mut sections = BTreeMap::new();
    for section in decoded.sections {
        let (block_names, block_data) = if let Some(block_states) = &section.block_states {
            if block_states.palette.is_empty() || block_states.palette.len() > 4096 {
                bail!(
                    "section {} block palette has invalid size {}",
                    section.y,
                    block_states.palette.len()
                );
            }
            (
                block_states
                    .palette
                    .iter()
                    .map(canonical_block_state)
                    .collect::<Vec<_>>(),
                block_states
                    .data
                    .as_ref()
                    .map(|data| data.iter().copied().collect::<Vec<_>>()),
            )
        } else {
            // Minecraft may retain only light/biome information for an all-air section.
            (vec!["minecraft:air".to_owned()], None)
        };
        let biome_names = section
            .biomes
            .as_ref()
            .map(|biomes| biomes.palette.clone())
            .filter(|palette| !palette.is_empty())
            .unwrap_or_else(|| vec!["minecraft:plains".to_owned()]);
        if biome_names.len() > 64 {
            bail!("section {} biome palette exceeds 64 entries", section.y);
        }
        if block_names
            .iter()
            .chain(biome_names.iter())
            .any(|name| name.is_empty() || name.len() > 4096)
        {
            bail!(
                "section {} contains an empty or overlong mapping name",
                section.y
            );
        }
        let biome_data = section
            .biomes
            .as_ref()
            .and_then(|biomes| biomes.data.as_ref())
            .map(|data| data.iter().copied().collect::<Vec<_>>());
        // Validate packed cardinality and every index before mutating the durable registry.
        let block_indexes =
            unpack_anvil_palette(block_data.as_deref(), block_names.len(), 4096, 4)?;
        let biome_indexes = unpack_anvil_palette(biome_data.as_deref(), biome_names.len(), 64, 1)?;
        let (block_ids, biome_ids) = {
            let mut registry = write_lock(registry)?;
            let blocks = block_names
                .iter()
                .map(|name| registry.block_id(name))
                .collect::<Result<Vec<_>>>()?;
            let biomes = biome_names
                .iter()
                .map(|name| registry.biome_id(name))
                .collect::<Result<Vec<_>>>()?;
            (blocks, biomes)
        };
        let block_light = section
            .block_light
            .as_ref()
            .map(|data| data.iter().copied().collect::<Vec<_>>());
        let sky_light = section
            .sky_light
            .as_ref()
            .map(|data| data.iter().copied().collect::<Vec<_>>());
        if block_light.as_ref().is_some_and(|data| data.len() != 2048)
            || sky_light.as_ref().is_some_and(|data| data.len() != 2048)
        {
            bail!("section {} has a malformed light array", section.y);
        }
        let mut cells = Vec::with_capacity(4096);
        for index in 0..4096 {
            let x = index & 15;
            let z = (index >> 4) & 15;
            let y = (index >> 8) & 15;
            let biome_index = (x >> 2) | ((z >> 2) << 2) | ((y >> 2) << 4);
            let block = block_ids[block_indexes[index]];
            let biome = if block == 0 {
                0
            } else {
                biome_ids[biome_indexes[biome_index]]
            };
            let block_value = nibble(block_light.as_deref(), index, 0);
            let sky_value = nibble(sky_light.as_deref(), index, default_sky_light);
            cells.push(Cell {
                block,
                biome,
                light: (block_value << 4) | sky_value,
            });
        }
        if sections
            .insert(
                section.y as i32,
                ChunkSection {
                    y: section.y as i32,
                    cells,
                },
            )
            .is_some()
        {
            bail!(
                "chunk ({},{}) contains duplicate section Y {}",
                decoded.x,
                decoded.z,
                section.y
            );
        }
    }
    let terrain_fingerprint = terrain_fingerprint(&sections);
    Ok(ParsedChunk {
        x: decoded.x,
        z: decoded.z,
        sections,
        source_fingerprint: 0,
        terrain_fingerprint,
    })
}

/// Hashes only the normalized inputs consumed by LOD generation. Palette order, unrelated NBT,
/// compression, and region-file placement have already disappeared at this boundary.
fn terrain_fingerprint(sections: &BTreeMap<i32, ChunkSection>) -> TerrainFingerprint {
    const SEED_A: u64 = 0x5658_5932_5445_5252;
    const SEED_B: u64 = 0x9e37_79b9_7f4a_7c15;
    let mut bytes = Vec::with_capacity(4 + sections.len() * (4 + 4096 * 9));
    bytes.extend_from_slice(&(sections.len() as u32).to_le_bytes());
    for (&y, section) in sections {
        bytes.extend_from_slice(&y.to_le_bytes());
        for cell in &section.cells {
            bytes.extend_from_slice(&cell.block.to_le_bytes());
            bytes.extend_from_slice(&cell.biome.to_le_bytes());
            bytes.push(cell.light);
        }
    }
    [xxh64(&bytes, SEED_A), xxh64(&bytes, SEED_B)]
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

pub fn unpack_anvil_palette(
    data: Option<&[i64]>,
    palette_len: usize,
    count: usize,
    minimum_bits: u8,
) -> Result<Vec<usize>> {
    if palette_len == 0 {
        bail!("empty Anvil palette");
    }
    if palette_len > count {
        bail!("Anvil palette has {palette_len} entries for only {count} values");
    }
    if palette_len == 1 {
        return Ok(vec![0; count]);
    }
    let bits = minimum_bits.max((usize::BITS - (palette_len - 1).leading_zeros()) as u8);
    if bits >= 64 {
        bail!("Anvil palette needs unsupported {bits}-bit indexes");
    }
    let per_long = 64 / bits as usize;
    let expected = count.div_ceil(per_long);
    let data = data.context("multi-value palette is missing packed data")?;
    if data.len() != expected {
        bail!(
            "packed palette has {} longs; expected exactly {expected}",
            data.len()
        );
    }
    let mask = (1u64 << bits) - 1;
    let mut output = Vec::with_capacity(count);
    for index in 0..count {
        let value = ((data[index / per_long] as u64 >> ((index % per_long) * bits as usize)) & mask)
            as usize;
        if value >= palette_len {
            bail!("packed palette index {value} exceeds {palette_len} entries");
        }
        output.push(value);
    }
    Ok(output)
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
