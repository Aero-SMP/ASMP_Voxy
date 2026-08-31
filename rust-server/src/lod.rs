use crate::key::SectionKey;
use anyhow::{Context, Result, bail};
use std::collections::HashMap;

pub const SECTION_EDGE: usize = 32;
pub const SECTION_VOLUME: usize = SECTION_EDGE * SECTION_EDGE * SECTION_EDGE;
const DATA_SCHEMA: u8 = 1;

#[derive(Clone, Copy, Debug, Default, Eq, Hash, PartialEq)]
pub struct Cell {
    pub block: u32,
    pub biome: u32,
    /// Block light in the high nibble and sky light in the low nibble.
    pub light: u8,
}

impl Cell {
    pub const AIR: Self = Self {
        block: 0,
        biome: 0,
        light: 0,
    };

    pub fn is_air(self) -> bool {
        self.block == 0
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Section {
    pub key: SectionKey,
    pub non_empty_children: u8,
    pub cells: Vec<Cell>,
}

#[derive(Clone, Debug)]
pub struct PackedSection {
    pub non_empty_children: u8,
    pub bits_per_index: u8,
    pub palette: Vec<Cell>,
    pub words: Vec<u64>,
}

impl Section {
    pub fn empty(key: SectionKey) -> Self {
        Self {
            key,
            non_empty_children: 0,
            cells: vec![Cell::AIR; SECTION_VOLUME],
        }
    }

    pub fn from_cells(key: SectionKey, cells: Vec<Cell>) -> Result<Self> {
        if cells.len() != SECTION_VOLUME {
            bail!(
                "section has {} cells instead of {SECTION_VOLUME}",
                cells.len()
            );
        }
        let non_empty_children = if cells.iter().any(|cell| !cell.is_air()) {
            0xff
        } else {
            0
        };
        Ok(Self {
            key,
            non_empty_children,
            cells,
        })
    }

    pub fn is_empty(&self) -> bool {
        self.non_empty_children == 0 || self.cells.iter().all(|cell| cell.is_air())
    }

    pub fn packed(&self) -> Result<PackedSection> {
        let mut ids = HashMap::<Cell, u16>::new();
        let mut palette = Vec::new();
        let mut indexes = Vec::with_capacity(SECTION_VOLUME);
        for &cell in &self.cells {
            let id = match ids.get(&cell) {
                Some(&id) => id,
                None => {
                    if palette.len() == u16::MAX as usize {
                        bail!("section palette exceeds 65535 values");
                    }
                    let id = palette.len() as u16;
                    palette.push(cell);
                    ids.insert(cell, id);
                    id
                }
            };
            indexes.push(id);
        }
        let bits = if palette.len() == 1 {
            0
        } else {
            (usize::BITS - (palette.len() - 1).leading_zeros()) as u8
        };
        let words = pack_indexes(&indexes, bits);
        Ok(PackedSection {
            non_empty_children: self.non_empty_children,
            bits_per_index: bits,
            palette,
            words,
        })
    }

    pub fn encode(&self) -> Result<Vec<u8>> {
        let packed = self.packed()?;
        let mut out = Vec::with_capacity(12 + packed.palette.len() * 12 + packed.words.len() * 8);
        out.push(DATA_SCHEMA);
        out.push(packed.non_empty_children);
        out.push(packed.bits_per_index);
        out.push(0);
        out.extend_from_slice(&(packed.palette.len() as u16).to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&(packed.words.len() as u32).to_le_bytes());
        encode_palette_and_words(&mut out, &packed.palette, &packed.words);
        Ok(out)
    }

    pub fn decode(key: SectionKey, input: &[u8]) -> Result<Self> {
        if input.len() < 12 || input[0] != DATA_SCHEMA {
            bail!("unsupported or truncated section encoding");
        }
        let non_empty_children = input[1];
        let bits = input[2];
        if input[3] != 0 || u16::from_le_bytes(input[6..8].try_into().unwrap()) != 0 {
            bail!("nonzero reserved section fields");
        }
        let palette_len = u16::from_le_bytes(input[4..6].try_into().unwrap()) as usize;
        let word_count = u32::from_le_bytes(input[8..12].try_into().unwrap()) as usize;
        if palette_len == 0 || bits > 16 || (palette_len == 1) != (bits == 0) {
            bail!("invalid palette metadata");
        }
        let expected_bits = if palette_len == 1 {
            0
        } else {
            (usize::BITS - (palette_len - 1).leading_zeros()) as u8
        };
        if bits != expected_bits || word_count != words_for(bits) {
            bail!("non-canonical palette packing");
        }
        let expected_len = 12usize
            .checked_add(
                palette_len
                    .checked_mul(12)
                    .context("palette length overflow")?,
            )
            .and_then(|n| n.checked_add(word_count.checked_mul(8)?))
            .context("section length overflow")?;
        if input.len() != expected_len {
            bail!("section encoding length mismatch");
        }
        let mut cursor = 12;
        let mut palette = Vec::with_capacity(palette_len);
        for _ in 0..palette_len {
            let block = u32::from_le_bytes(input[cursor..cursor + 4].try_into().unwrap());
            let biome = u32::from_le_bytes(input[cursor + 4..cursor + 8].try_into().unwrap());
            let light = input[cursor + 8];
            if input[cursor + 9..cursor + 12] != [0, 0, 0] {
                bail!("nonzero palette reserved bytes");
            }
            palette.push(Cell {
                block,
                biome,
                light,
            });
            cursor += 12;
        }
        let words = input[cursor..]
            .chunks_exact(8)
            .map(|bytes| u64::from_le_bytes(bytes.try_into().unwrap()))
            .collect::<Vec<_>>();
        let indexes = unpack_indexes(&words, bits, palette_len)?;
        let cells = indexes.into_iter().map(|id| palette[id as usize]).collect();
        Ok(Self {
            key,
            non_empty_children,
            cells,
        })
    }

    pub fn network_body(&self, revision: u64) -> Result<Vec<u8>> {
        let packed = self.packed()?;
        let mut out = Vec::with_capacity(24 + packed.palette.len() * 12 + packed.words.len() * 8);
        out.extend_from_slice(&self.key.packed().to_le_bytes());
        out.extend_from_slice(&revision.to_le_bytes());
        out.push(packed.non_empty_children);
        out.push(packed.bits_per_index);
        out.extend_from_slice(&(packed.palette.len() as u16).to_le_bytes());
        out.extend_from_slice(&(packed.words.len() as u32).to_le_bytes());
        encode_palette_and_words(&mut out, &packed.palette, &packed.words);
        Ok(out)
    }
}

/// Builds an S_SECTION payload directly from the validated storage encoding. This avoids
/// materializing and re-palette-packing 32,768 cells for every client stream.
pub fn network_body_from_encoded(encoded: &[u8], key: u64, revision: u64) -> Result<Vec<u8>> {
    validate_encoded(encoded)?;
    let mut out = Vec::with_capacity(24 + encoded.len() - 12);
    out.extend_from_slice(&key.to_le_bytes());
    out.extend_from_slice(&revision.to_le_bytes());
    out.push(encoded[1]);
    out.push(encoded[2]);
    out.extend_from_slice(&encoded[4..6]);
    out.extend_from_slice(&encoded[8..12]);
    out.extend_from_slice(&encoded[12..]);
    Ok(out)
}

fn validate_encoded(input: &[u8]) -> Result<()> {
    if input.len() < 12 || input[0] != DATA_SCHEMA || input[3] != 0 || input[6..8] != [0, 0] {
        bail!("invalid stored section header");
    }
    let palette_len = u16::from_le_bytes(input[4..6].try_into().unwrap()) as usize;
    let bits = input[2];
    let words = u32::from_le_bytes(input[8..12].try_into().unwrap()) as usize;
    let expected_bits = if palette_len == 1 {
        0
    } else if palette_len > 1 {
        (usize::BITS - (palette_len - 1).leading_zeros()) as u8
    } else {
        bail!("empty stored palette");
    };
    if bits != expected_bits || words != words_for(bits) {
        bail!("invalid stored palette packing");
    }
    let expected = 12usize
        .checked_add(
            palette_len
                .checked_mul(12)
                .context("stored palette overflow")?,
        )
        .and_then(|size| size.checked_add(words.checked_mul(8)?))
        .context("stored section size overflow")?;
    if input.len() != expected {
        bail!("stored section length mismatch");
    }
    for entry in input[12..12 + palette_len * 12].chunks_exact(12) {
        if entry[9..12] != [0, 0, 0] {
            bail!("stored palette reserved bytes are nonzero");
        }
    }
    Ok(())
}

fn encode_palette_and_words(out: &mut Vec<u8>, palette: &[Cell], words: &[u64]) {
    for cell in palette {
        out.extend_from_slice(&cell.block.to_le_bytes());
        out.extend_from_slice(&cell.biome.to_le_bytes());
        out.push(cell.light);
        out.extend_from_slice(&[0; 3]);
    }
    for word in words {
        out.extend_from_slice(&word.to_le_bytes());
    }
}

fn words_for(bits: u8) -> usize {
    if bits == 0 {
        0
    } else {
        (SECTION_VOLUME * bits as usize).div_ceil(64)
    }
}

pub fn pack_indexes(indexes: &[u16], bits: u8) -> Vec<u64> {
    if bits == 0 {
        return Vec::new();
    }
    let mut words = vec![0u64; (indexes.len() * bits as usize).div_ceil(64)];
    for (index, &value) in indexes.iter().enumerate() {
        let bit = index * bits as usize;
        let word = bit >> 6;
        let shift = bit & 63;
        words[word] |= u64::from(value) << shift;
        if shift + bits as usize > 64 {
            words[word + 1] |= u64::from(value) >> (64 - shift);
        }
    }
    words
}

pub fn unpack_indexes(words: &[u64], bits: u8, palette_len: usize) -> Result<Vec<u16>> {
    if bits == 0 {
        if palette_len != 1 || !words.is_empty() {
            bail!("zero-bit storage requires one palette value and no words");
        }
        return Ok(vec![0; SECTION_VOLUME]);
    }
    if words.len() != words_for(bits) {
        bail!("packed index word count mismatch");
    }
    let mask = (1u64 << bits) - 1;
    let mut indexes = Vec::with_capacity(SECTION_VOLUME);
    for index in 0..SECTION_VOLUME {
        let bit = index * bits as usize;
        let word = bit >> 6;
        let shift = bit & 63;
        let mut value = words[word] >> shift;
        if shift + bits as usize > 64 {
            value |= words[word + 1] << (64 - shift);
        }
        let value = (value & mask) as u16;
        if value as usize >= palette_len {
            bail!("palette index {value} is outside {palette_len} entries");
        }
        indexes.push(value);
    }
    Ok(indexes)
}

pub fn mip(eight: [Cell; 8], opacity: &[u8]) -> Cell {
    let mut selected = None::<(u8, u8, Cell)>;
    // Codes exactly match Voxy Mipper: x is bit 2, y bit 1, z bit 0.
    for x in 0..2 {
        for y in 0..2 {
            for z in 0..2 {
                let code = ((x << 2) | (y << 1) | z) as u8;
                let cell = eight[child_index(x, y, z)];
                if !cell.is_air() {
                    let candidate = (
                        opacity.get(cell.block as usize).copied().unwrap_or(15),
                        code,
                        cell,
                    );
                    if selected.is_none_or(|old| (candidate.0, candidate.1) > (old.0, old.1)) {
                        selected = Some(candidate);
                    }
                }
            }
        }
    }
    if let Some((_, _, cell)) = selected {
        return cell;
    }

    let block_sum: u16 = eight.iter().map(|cell| u16::from(cell.light >> 4)).sum();
    let sky_sum: u16 = eight.iter().map(|cell| u16::from(cell.light & 15)).sum();
    let block = (block_sum / 8) as u8;
    let sky = sky_sum.div_ceil(8) as u8;
    let mut air = eight[child_index(1, 1, 1)];
    air.block = 0;
    air.biome = 0;
    air.light = (block << 4) | sky;
    air
}

pub fn build_parent(
    key: SectionKey,
    children: &[Option<Section>; 8],
    opacity: &[u8],
) -> Result<Section> {
    if key.level == 0 {
        bail!("a level-zero section has no Voxy child sections");
    }
    let mut non_empty_children = 0u8;
    for (index, child) in children.iter().enumerate() {
        if child.as_ref().is_some_and(|child| !child.is_empty()) {
            non_empty_children |= 1 << index;
        }
    }
    let mut cells = Vec::with_capacity(SECTION_VOLUME);
    for y in 0..SECTION_EDGE {
        for z in 0..SECTION_EDGE {
            for x in 0..SECTION_EDGE {
                let mut input = [Cell::AIR; 8];
                for dy in 0..2 {
                    for dz in 0..2 {
                        for dx in 0..2 {
                            let gx = x * 2 + dx;
                            let gy = y * 2 + dy;
                            let gz = z * 2 + dz;
                            let child_slot = child_index(
                                gx / SECTION_EDGE,
                                gy / SECTION_EDGE,
                                gz / SECTION_EDGE,
                            );
                            if let Some(child) = &children[child_slot] {
                                input[child_index(dx, dy, dz)] = child.cells[cell_index(
                                    gx % SECTION_EDGE,
                                    gy % SECTION_EDGE,
                                    gz % SECTION_EDGE,
                                )];
                            }
                        }
                    }
                }
                cells.push(mip(input, opacity));
            }
        }
    }
    Ok(Section {
        key,
        non_empty_children,
        cells,
    })
}

pub const fn cell_index(x: usize, y: usize, z: usize) -> usize {
    x | (z << 5) | (y << 10)
}

pub const fn child_index(x: usize, y: usize, z: usize) -> usize {
    (x & 1) | ((z & 1) << 1) | ((y & 1) << 2)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::registry::Registry;
    use std::{
        fs,
        sync::atomic::{AtomicU64, Ordering},
    };

    static NEXT: AtomicU64 = AtomicU64::new(0);

    fn registry() -> (std::path::PathBuf, Registry) {
        let path = std::env::temp_dir().join(format!(
            "voxy-lod-test-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(&path).unwrap();
        let mut registry = Registry::open(&path).unwrap();
        registry.block_id("minecraft:stone").unwrap();
        registry.save().unwrap();
        (path, registry)
    }

    #[test]
    fn palette_bitstream_crosses_word_boundaries() {
        let indexes = (0..SECTION_VOLUME)
            .map(|i| (i % 31) as u16)
            .collect::<Vec<_>>();
        let words = pack_indexes(&indexes, 5);
        assert_eq!(unpack_indexes(&words, 5, 31).unwrap(), indexes);
    }

    #[test]
    fn section_encoding_round_trip() {
        let key = SectionKey::new(0, -2, 3, 4).unwrap();
        let cells = (0..SECTION_VOLUME)
            .map(|i| Cell {
                block: (i % 17) as u32,
                biome: (i % 3) as u32,
                light: i as u8,
            })
            .collect();
        let section = Section::from_cells(key, cells).unwrap();
        let encoded = section.encode().unwrap();
        assert_eq!(Section::decode(key, &encoded).unwrap(), section);
    }

    #[test]
    fn mip_uses_opacity_then_voxy_position_order() {
        let (path, registry) = registry();
        let mut cells = [Cell::AIR; 8];
        cells[child_index(0, 0, 0)] = Cell {
            block: 1,
            biome: 0,
            light: 1,
        };
        cells[child_index(1, 1, 1)] = Cell {
            block: 1,
            biome: 0,
            light: 2,
        };
        assert_eq!(mip(cells, &registry.opacity_table()).light, 2);
        fs::remove_dir_all(path).unwrap();
    }

    #[test]
    fn all_air_mip_averages_light_like_java() {
        let (path, registry) = registry();
        let mut cells = [Cell::AIR; 8];
        for (i, cell) in cells.iter_mut().enumerate() {
            cell.light = ((i as u8) << 4) | i as u8;
        }
        assert_eq!(mip(cells, &registry.opacity_table()).light, 0x34);
        fs::remove_dir_all(path).unwrap();
    }
}
