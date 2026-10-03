use anyhow::{Context, Result, ensure};
use sha2::{Digest, Sha256};
use std::{collections::HashMap, fs, io::Read, path::Path};

pub const VOLUME: usize = 32 * 32 * 32;
pub const FRAME_HEADER: usize = 81;
pub const MAX_BODY: usize = 2 + VOLUME * 11;
pub type Hash = [u8; 32];

pub fn hash(bytes: &[u8]) -> Hash {
    Sha256::digest(bytes).into()
}
pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub struct Cell {
    pub block: u32,
    pub biome: u32,
    pub light: u8,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Key {
    pub level: u8,
    pub x: i32,
    pub y: i32,
    pub z: i32,
}
impl Key {
    pub fn parent(self) -> Self {
        Self {
            level: self.level + 1,
            x: self.x.div_euclid(2),
            y: self.y.div_euclid(2),
            z: self.z.div_euclid(2),
        }
    }
    pub fn child_slot(self) -> usize {
        (self.x.rem_euclid(2) | self.z.rem_euclid(2) << 1 | self.y.rem_euclid(2) << 2) as usize
    }
    pub fn region(self) -> (i32, i32) {
        let side = 16 >> self.level;
        (self.x.div_euclid(side), self.z.div_euclid(side))
    }
}

pub struct Catalog {
    pub blocks: Vec<String>,
    pub biomes: Vec<String>,
    block_ids: HashMap<String, u32>,
    biome_ids: HashMap<String, u32>,
}
impl Catalog {
    pub fn load(data: &Path) -> Result<Self> {
        let (blocks, biomes) = match fs::read(data.join("catalog.current")) {
            Ok(bytes) => Self::decode(&bytes)?,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => (
                vec!["minecraft:air".into()],
                vec!["minecraft:plains".into()],
            ),
            Err(e) => return Err(e.into()),
        };
        let block_ids = blocks
            .iter()
            .enumerate()
            .map(|(i, s)| (s.clone(), i as u32))
            .collect();
        let biome_ids = biomes
            .iter()
            .enumerate()
            .map(|(i, s)| (s.clone(), i as u32))
            .collect();
        Ok(Self {
            blocks,
            biomes,
            block_ids,
            biome_ids,
        })
    }
    pub fn block(&mut self, name: String) -> u32 {
        let next = self.blocks.len() as u32;
        *self.block_ids.entry(name.clone()).or_insert_with(|| {
            self.blocks.push(name);
            next
        })
    }
    pub fn biome(&mut self, name: String) -> u32 {
        let next = self.biomes.len() as u32;
        *self.biome_ids.entry(name.clone()).or_insert_with(|| {
            self.biomes.push(name);
            next
        })
    }
    pub fn is_air(&self, id: u32) -> bool {
        matches!(
            self.blocks[id as usize].as_str(),
            "minecraft:air" | "minecraft:cave_air" | "minecraft:void_air"
        )
    }
    pub fn encode(&self) -> Result<Vec<u8>> {
        let mut out = b"VXRCAT01".to_vec();
        for names in [&self.blocks, &self.biomes] {
            out.extend_from_slice(&(names.len() as u32).to_le_bytes());
            for name in names {
                let n = u16::try_from(name.len()).context("catalog name too long")?;
                out.extend_from_slice(&n.to_le_bytes());
                out.extend_from_slice(name.as_bytes());
            }
        }
        Ok(out)
    }
    pub fn decode(bytes: &[u8]) -> Result<(Vec<String>, Vec<String>)> {
        let mut cursor = std::io::Cursor::new(bytes);
        let mut magic = [0; 8];
        cursor.read_exact(&mut magic)?;
        ensure!(&magic == b"VXRCAT01", "catalog magic");
        let mut lists = Vec::new();
        for _ in 0..2 {
            let mut count = [0; 4];
            cursor.read_exact(&mut count)?;
            let count = u32::from_le_bytes(count) as usize;
            ensure!(count <= bytes.len() / 2, "catalog count");
            let mut names = Vec::with_capacity(count);
            for _ in 0..count {
                let mut n = [0; 2];
                cursor.read_exact(&mut n)?;
                let mut name = vec![0; u16::from_le_bytes(n) as usize];
                cursor.read_exact(&mut name)?;
                ensure!(!name.is_empty(), "empty catalog name");
                names.push(String::from_utf8(name)?);
            }
            lists.push(names);
        }
        ensure!(
            cursor.position() as usize == bytes.len(),
            "catalog trailing bytes"
        );
        let biomes = lists.pop().unwrap();
        Ok((lists.pop().unwrap(), biomes))
    }
}

pub fn encode_frame(cells: &[Cell], children: u8, catalog: Hash) -> Result<Vec<u8>> {
    ensure!(cells.len() == VOLUME, "section volume");
    let mut ids = HashMap::new();
    let mut palette = Vec::new();
    let mut indexes = Vec::with_capacity(VOLUME);
    for &cell in cells {
        let next = palette.len() as u16;
        let id = *ids.entry(cell).or_insert_with(|| {
            palette.push(cell);
            next
        });
        indexes.push(id);
    }
    let bits = index_bits(palette.len());
    let per_word = 64 / bits;
    let mut body = Vec::with_capacity(2 + palette.len() * 9 + VOLUME.div_ceil(per_word) * 8);
    body.extend_from_slice(&(palette.len() as u16).to_le_bytes());
    for cell in palette {
        body.extend_from_slice(&cell.block.to_le_bytes());
        body.extend_from_slice(&cell.biome.to_le_bytes());
        body.push(cell.light);
    }
    for indexes in indexes.chunks(per_word) {
        let word = indexes
            .iter()
            .enumerate()
            .fold(0u64, |word, (i, id)| word | (*id as u64) << (i * bits));
        body.extend_from_slice(&word.to_le_bytes());
    }
    let compressed = zstd::bulk::compress(&body, 1)?;
    let mut out = b"VXRSEC01".to_vec();
    out.extend(catalog);
    out.extend(hash(&compressed));
    out.push(children);
    out.extend_from_slice(&(body.len() as u32).to_le_bytes());
    out.extend_from_slice(&(compressed.len() as u32).to_le_bytes());
    out.extend(compressed);
    Ok(out)
}

pub fn index_bits(count: usize) -> usize {
    (usize::BITS as usize - (count.saturating_sub(1)).leading_zeros() as usize).max(1)
}

pub fn decode_frame(frame: &[u8]) -> Result<(Vec<Cell>, u8)> {
    ensure!(
        frame.len() >= FRAME_HEADER && &frame[..8] == b"VXRSEC01",
        "frame header"
    );
    let body_len = u32::from_le_bytes(frame[73..77].try_into()?) as usize;
    let compressed_len = u32::from_le_bytes(frame[77..81].try_into()?) as usize;
    ensure!(
        body_len <= MAX_BODY && compressed_len == frame.len() - FRAME_HEADER,
        "frame lengths"
    );
    ensure!(
        hash(&frame[FRAME_HEADER..]) == frame[40..72],
        "frame checksum"
    );
    let body = zstd::bulk::decompress(&frame[FRAME_HEADER..], body_len)?;
    ensure!(body.len() == body_len && body.len() >= 2, "decoded length");
    let count = u16::from_le_bytes(body[..2].try_into()?) as usize;
    let bits = index_bits(count);
    let per_word = 64 / bits;
    ensure!(
        count > 0 && count <= VOLUME && body.len() == 2 + count * 9 + VOLUME.div_ceil(per_word) * 8,
        "palette length"
    );
    let palette = body[2..2 + count * 9]
        .as_chunks::<9>()
        .0
        .iter()
        .map(|p| Cell {
            block: u32::from_le_bytes(p[..4].try_into().unwrap()),
            biome: u32::from_le_bytes(p[4..8].try_into().unwrap()),
            light: p[8],
        })
        .collect::<Vec<_>>();
    let mut cells = Vec::with_capacity(VOLUME);
    for word in body[2 + count * 9..].as_chunks::<8>().0 {
        let word = u64::from_le_bytes(*word);
        for i in 0..per_word.min(VOLUME - cells.len()) {
            let id = ((word >> (i * bits)) & ((1 << bits) - 1)) as usize;
            cells.push(*palette.get(id).context("palette index")?);
        }
    }
    Ok((cells, frame[72]))
}
