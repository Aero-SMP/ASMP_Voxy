use crate::payload::{Catalog, Cell, Hash};
use anyhow::{Context, Result, bail, ensure};
use fastnbt::{ByteArray, LongArray};
use flate2::read::{GzDecoder, ZlibDecoder};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    fs::File,
    io::{Read, Seek, SeekFrom},
    path::{Path, PathBuf},
};

#[derive(Deserialize)]
struct Chunk {
    #[serde(rename = "xPos")]
    x: i32,
    #[serde(rename = "zPos")]
    z: i32,
    #[serde(default)]
    sections: Vec<Section>,
}
#[derive(Deserialize)]
struct Section {
    #[serde(rename = "Y")]
    y: i8,
    #[serde(default)]
    block_states: Option<Blocks>,
    #[serde(default)]
    biomes: Option<Biomes>,
    #[serde(rename = "BlockLight", default)]
    block_light: Option<ByteArray>,
    #[serde(rename = "SkyLight", default)]
    sky_light: Option<ByteArray>,
}
#[derive(Deserialize)]
struct Blocks {
    palette: Vec<Block>,
    #[serde(default)]
    data: Option<LongArray>,
}
#[derive(Deserialize)]
struct Block {
    #[serde(rename = "Name")]
    name: String,
    #[serde(rename = "Properties", default)]
    properties: BTreeMap<String, String>,
}
#[derive(Deserialize)]
struct Biomes {
    palette: Vec<String>,
    #[serde(default)]
    data: Option<LongArray>,
}

pub struct Region {
    pub file: File,
    pub path: PathBuf,
    pub x: i32,
    pub z: i32,
    locations: [u32; 1024],
}
pub struct ChunkBytes {
    pub fingerprint: Hash,
    compression: u8,
    bytes: Vec<u8>,
    x: i32,
    z: i32,
}
impl Region {
    pub fn open(path: &Path, x: i32, z: i32) -> Result<Self> {
        let mut file = File::open(path)?;
        let mut header = [0u8; 8192];
        file.read_exact(&mut header)?;
        let locations = std::array::from_fn(|i| {
            u32::from_be_bytes(header[i * 4..i * 4 + 4].try_into().unwrap())
        });
        Ok(Self {
            file,
            path: path.into(),
            x,
            z,
            locations,
        })
    }
    pub fn chunk_bytes(&mut self, local_x: usize, local_z: usize) -> Result<Option<ChunkBytes>> {
        let entry = self.locations[local_x | local_z << 5];
        if entry == 0 {
            return Ok(None);
        }
        let sector = entry >> 8;
        let sectors = entry & 255;
        ensure!(sector >= 2 && sectors != 0, "invalid chunk location");
        self.file.seek(SeekFrom::Start(sector as u64 * 4096))?;
        let mut head = [0u8; 5];
        self.file.read_exact(&mut head)?;
        let len = u32::from_be_bytes(head[..4].try_into()?);
        ensure!(
            len >= 1 && len as u64 + 4 <= sectors as u64 * 4096,
            "chunk length"
        );
        let x = self
            .x
            .checked_mul(32)
            .and_then(|v| v.checked_add(local_x as i32))
            .context("chunk x overflow")?;
        let z = self
            .z
            .checked_mul(32)
            .and_then(|v| v.checked_add(local_z as i32))
            .context("chunk z overflow")?;
        let bytes = if head[4] & 128 != 0 {
            std::fs::read(self.path.with_file_name(format!("c.{x}.{z}.mcc")))?
        } else {
            let mut data = vec![0; len as usize - 1];
            self.file.read_exact(&mut data)?;
            data
        };
        let compression = head[4] & 127;
        let fingerprint = Sha256::new()
            .chain_update([compression])
            .chain_update(&bytes)
            .finalize()
            .into();
        Ok(Some(ChunkBytes {
            fingerprint,
            compression,
            bytes,
            x,
            z,
        }))
    }
}

pub fn decode_chunk(source: ChunkBytes, catalog: &mut Catalog) -> Result<BTreeMap<i32, Vec<Cell>>> {
    let ChunkBytes {
        compression,
        bytes,
        x,
        z,
        ..
    } = source;
    let reader: Box<dyn Read> = match compression {
        1 => Box::new(GzDecoder::new(bytes.as_slice())),
        2 => Box::new(ZlibDecoder::new(bytes.as_slice())),
        3 => Box::new(bytes.as_slice()),
        4 => Box::new(lz4_java_wrc::Lz4BlockInput::new(bytes.as_slice())),
        kind => bail!("unsupported Anvil compression {kind}"),
    };
    // Malformed compressed NBT must not expand without limit; this is input validation.
    let mut nbt = Vec::new();
    reader.take(128 * 1024 * 1024 + 1).read_to_end(&mut nbt)?;
    ensure!(nbt.len() <= 128 * 1024 * 1024, "oversized NBT");
    let chunk: Chunk = fastnbt::from_bytes(&nbt)?;
    ensure!(chunk.x == x && chunk.z == z, "chunk coordinates");
    let mut out = BTreeMap::new();
    for section in chunk.sections {
        let (block_ids, block_indexes) = if let Some(blocks) = section.block_states {
            let indexes = unpack(
                blocks.data.as_ref().map(|d| &d[..]),
                blocks.palette.len(),
                4096,
                4,
            )?;
            let ids = blocks
                .palette
                .into_iter()
                .map(|block| {
                    let state = if block.properties.is_empty() {
                        block.name
                    } else {
                        format!(
                            "{}[{}]",
                            block.name,
                            block
                                .properties
                                .into_iter()
                                .map(|(k, v)| format!("{k}={v}"))
                                .collect::<Vec<_>>()
                                .join(",")
                        )
                    };
                    catalog.block(state)
                })
                .collect::<Vec<_>>();
            (ids, indexes)
        } else {
            (vec![0], vec![0; 4096])
        };
        let (biome_ids, biome_indexes) = if let Some(biomes) = section.biomes {
            let indexes = unpack(
                biomes.data.as_ref().map(|d| &d[..]),
                biomes.palette.len(),
                64,
                1,
            )?;
            (
                biomes
                    .palette
                    .into_iter()
                    .map(|b| catalog.biome(b))
                    .collect::<Vec<_>>(),
                indexes,
            )
        } else {
            (vec![0], vec![0; 64])
        };
        for light in [&section.block_light, &section.sky_light]
            .into_iter()
            .flatten()
        {
            ensure!(light.len() == 2048, "light array length");
        }
        let nibble = |bytes: &Option<ByteArray>, i: usize| {
            bytes
                .as_ref()
                .map_or(0, |b| ((b[i >> 1] as u8) >> ((i & 1) * 4)) & 15)
        };
        let cells = (0..4096)
            .map(|i| Cell {
                block: block_ids[block_indexes[i]],
                biome: biome_ids[biome_indexes
                    [(i & 15) >> 2 | ((i >> 4 & 15) >> 2) << 2 | ((i >> 8) >> 2) << 4]],
                light: nibble(&section.block_light, i) | nibble(&section.sky_light, i) << 4,
            })
            .collect();
        ensure!(
            out.insert(section.y as i32, cells).is_none(),
            "duplicate section Y"
        );
    }
    Ok(out)
}

pub fn unpack(
    data: Option<&[i64]>,
    palette: usize,
    count: usize,
    min_bits: usize,
) -> Result<Vec<usize>> {
    ensure!(palette > 0 && palette <= count, "Anvil palette size");
    if palette == 1 {
        return Ok(vec![0; count]);
    }
    let bits = min_bits.max(usize::BITS as usize - (palette - 1).leading_zeros() as usize);
    let per_long = 64 / bits;
    let data = data.context("missing packed palette")?;
    ensure!(
        data.len() == count.div_ceil(per_long),
        "packed palette length"
    );
    (0..count)
        .map(|i| {
            let value =
                ((data[i / per_long] as u64 >> (i % per_long * bits)) & ((1 << bits) - 1)) as usize;
            ensure!(value < palette, "packed palette index");
            Ok(value)
        })
        .collect()
}

pub fn dimensions(world: &Path) -> Result<BTreeMap<String, PathBuf>> {
    let mut out = BTreeMap::from([("minecraft:overworld".into(), world.into())]);
    for (name, path) in [
        ("minecraft:the_nether", "DIM-1"),
        ("minecraft:the_end", "DIM1"),
    ] {
        if world.join(path).is_dir() {
            out.insert(name.into(), world.join(path));
        }
    }
    fn walk(
        base: &Path,
        current: &Path,
        namespace: &str,
        out: &mut BTreeMap<String, PathBuf>,
    ) -> Result<()> {
        if current.join("region").is_dir() {
            let id = format!(
                "{namespace}:{}",
                current
                    .strip_prefix(base)?
                    .to_string_lossy()
                    .replace('\\', "/")
            );
            out.insert(id, current.into());
        }
        for entry in std::fs::read_dir(current)? {
            let entry = entry?;
            if entry.file_type()?.is_dir() && entry.file_name() != "region" {
                walk(base, &entry.path(), namespace, out)?;
            }
        }
        Ok(())
    }
    if world.join("dimensions").is_dir() {
        for entry in std::fs::read_dir(world.join("dimensions"))? {
            let entry = entry?;
            if entry.file_type()?.is_dir() {
                walk(
                    &entry.path(),
                    &entry.path(),
                    &entry.file_name().to_string_lossy(),
                    &mut out,
                )?;
            }
        }
    }
    Ok(out)
}
