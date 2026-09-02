//! Canonical production content for the virtual surface.
//!
//! A structural 32³ node is partitioned into independently addressable 8³ microtiles. Every
//! non-air microtile has one server classification: exterior, interior, or directly complex.
//! Every ordinary exterior/interior tile also has an independently typed exact complex companion
//! for client resource/model incompatibility. Unknown, non-authoritative, translucent, and
//! model-dependent blocks are directly complex.

use super::{
    manifest::{ContentClass, QuantizedBounds, morton3},
    object::{CanonicalObject, ObjectKind},
};
use crate::{
    lod::{Cell, SECTION_EDGE, SECTION_VOLUME, Section, cell_index},
    registry::RegistrySnapshot,
};
use anyhow::{Context, Result, bail};
use std::collections::HashMap;

const MAGIC: &[u8; 8] = b"VXYTILE\0";
pub const MICROTILE_EDGE: usize = 8;
pub const MICROTILE_VOLUME: usize = MICROTILE_EDGE * MICROTILE_EDGE * MICROTILE_EDGE;
const HEADER_BYTES: usize = 32;
const FACE_BYTES: usize = SECTION_EDGE * SECTION_EDGE / 8;

#[derive(Clone, Debug)]
pub struct PreparedContent {
    pub microtile_mask: u64,
    pub objects: Vec<CanonicalObject>,
    pub boundary_face_mask: u8,
    pub boundary_summary: Vec<u8>,
}

#[derive(Clone, Debug)]
pub struct PreparedSection {
    pub contents: [Option<PreparedContent>; super::manifest::CONTENT_CLASS_COUNT],
    pub bounds: Option<QuantizedBounds>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Microtile {
    pub catalog_id: u64,
    pub class: ContentClass,
    pub edge: u8,
    pub origin: [u8; 3],
    pub cells: Vec<Cell>,
}

/// Exact normalized level-zero source state used only by the Rust publisher. Keeping this as a
/// distinct object kind prevents an internal rebuild dependency from becoming client content.
/// The canonical payload deliberately reuses the proven palette codec used by complex tiles.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SourceMicrotile(Microtile);

impl SourceMicrotile {
    pub fn new(catalog_id: u64, origin: [u8; 3], cells: Vec<Cell>) -> Result<Self> {
        let tile = Microtile {
            catalog_id,
            class: ContentClass::Complex,
            edge: MICROTILE_EDGE as u8,
            origin,
            cells,
        };
        tile.validate()?;
        Ok(Self(tile))
    }

    pub fn decode(bytes: &[u8]) -> Result<Self> {
        Ok(Self(Microtile::decode(
            bytes,
            ObjectKind::ComplexMicrotile,
        )?))
    }

    pub fn catalog_id(&self) -> u64 {
        self.0.catalog_id
    }

    pub fn origin(&self) -> [u8; 3] {
        self.0.origin
    }

    pub fn cells(&self) -> &[Cell] {
        &self.0.cells
    }

    pub fn canonical_object(&self) -> Result<CanonicalObject> {
        CanonicalObject::new(ObjectKind::SourceMicrotile, self.0.encode()?)
    }
}

/// Splits one exact normalized 32³ level-zero section into its fixed 64 source objects. Air
/// tiles are retained because their light values participate in deterministic parent mips.
pub fn prepare_source_microtiles(
    section: &Section,
    catalog_id: u64,
) -> Result<[CanonicalObject; 64]> {
    if section.cells.len() != SECTION_VOLUME || catalog_id == 0 {
        bail!("invalid hierarchy section or catalog for source microtiles");
    }
    let mut objects = Vec::with_capacity(64);
    for bz in 0..4usize {
        for by in 0..4usize {
            for bx in 0..4usize {
                let origin = [
                    bx * MICROTILE_EDGE,
                    by * MICROTILE_EDGE,
                    bz * MICROTILE_EDGE,
                ];
                let mut cells = Vec::with_capacity(MICROTILE_VOLUME);
                for y in 0..MICROTILE_EDGE {
                    for z in 0..MICROTILE_EDGE {
                        for x in 0..MICROTILE_EDGE {
                            cells.push(
                                section.cells
                                    [cell_index(origin[0] + x, origin[1] + y, origin[2] + z)],
                            );
                        }
                    }
                }
                let morton = morton3(bx as u32, by as u32, bz as u32, 2)?;
                objects.push((
                    morton,
                    SourceMicrotile::new(catalog_id, origin.map(|value| value as u8), cells)?
                        .canonical_object()?,
                ));
            }
        }
    }
    objects.sort_unstable_by_key(|(morton, _)| *morton);
    objects
        .into_iter()
        .map(|(_, object)| object)
        .collect::<Vec<_>>()
        .try_into()
        .map_err(|_| anyhow::anyhow!("source microtile partition did not produce 64 objects"))
}

impl Microtile {
    pub fn encode(&self) -> Result<Vec<u8>> {
        self.validate()?;
        let mut palette = Vec::<Cell>::new();
        let mut ids = HashMap::<Cell, u16>::new();
        let mut indexes = Vec::with_capacity(self.cells.len());
        for &cell in &self.cells {
            let id = if let Some(&id) = ids.get(&cell) {
                id
            } else {
                let id: u16 = palette
                    .len()
                    .try_into()
                    .context("microtile palette exceeds u16")?;
                palette.push(cell);
                ids.insert(cell, id);
                id
            };
            indexes.push(id);
        }
        let bits = if palette.len() == 1 {
            0
        } else {
            (usize::BITS - (palette.len() - 1).leading_zeros()) as u8
        };
        let words = pack_indexes(&indexes, bits);
        let mut out = Vec::with_capacity(HEADER_BYTES + palette.len() * 9 + words.len() * 8);
        out.extend_from_slice(MAGIC);
        out.extend_from_slice(&self.catalog_id.to_le_bytes());
        out.push(self.class as u8);
        out.push(self.edge);
        out.extend_from_slice(&self.origin);
        out.extend_from_slice(&(self.cells.len() as u32).to_le_bytes());
        out.extend_from_slice(&(palette.len() as u16).to_le_bytes());
        out.push(bits);
        out.extend_from_slice(&(words.len() as u32).to_le_bytes());
        for cell in palette {
            out.extend_from_slice(&cell.block.to_le_bytes());
            out.extend_from_slice(&cell.biome.to_le_bytes());
            out.push(cell.light);
        }
        for word in words {
            out.extend_from_slice(&word.to_le_bytes());
        }
        Ok(out)
    }

    pub fn decode(bytes: &[u8], expected_kind: ObjectKind) -> Result<Self> {
        if bytes.len() < HEADER_BYTES || &bytes[..8] != MAGIC {
            bail!("truncated or invalid surface microtile envelope");
        }
        let class = ContentClass::try_from(bytes[16])?;
        if content_kind(class) != expected_kind {
            bail!("microtile content class and object kind disagree");
        }
        let edge = bytes[17];
        let origin = bytes[18..21].try_into().unwrap();
        let cell_count = u32::from_le_bytes(bytes[21..25].try_into().unwrap()) as usize;
        let palette_count = u16::from_le_bytes(bytes[25..27].try_into().unwrap()) as usize;
        let bits = bytes[27];
        let word_count = u32::from_le_bytes(bytes[28..32].try_into().unwrap()) as usize;
        if edge != MICROTILE_EDGE as u8
            || cell_count != MICROTILE_VOLUME
            || palette_count == 0
            || palette_count > MICROTILE_VOLUME
        {
            bail!("invalid surface microtile dimensions or palette count");
        }
        let expected_bits = if palette_count == 1 {
            0
        } else {
            (usize::BITS - (palette_count - 1).leading_zeros()) as u8
        };
        let expected_words = words_for(cell_count, expected_bits);
        let expected_len = HEADER_BYTES
            .checked_add(
                palette_count
                    .checked_mul(9)
                    .context("palette size overflow")?,
            )
            .and_then(|value| value.checked_add(expected_words.checked_mul(8)?))
            .context("microtile length overflow")?;
        if bits != expected_bits || word_count != expected_words || bytes.len() != expected_len {
            bail!("non-canonical surface microtile palette packing");
        }
        let mut palette = Vec::with_capacity(palette_count);
        let mut unique = HashMap::<Cell, ()>::new();
        let mut cursor = HEADER_BYTES;
        for _ in 0..palette_count {
            let cell = Cell {
                block: u32::from_le_bytes(bytes[cursor..cursor + 4].try_into().unwrap()),
                biome: u32::from_le_bytes(bytes[cursor + 4..cursor + 8].try_into().unwrap()),
                light: bytes[cursor + 8],
            };
            if unique.insert(cell, ()).is_some() {
                bail!("duplicate canonical surface microtile palette entry");
            }
            palette.push(cell);
            cursor += 9;
        }
        let words = bytes[cursor..]
            .chunks_exact(8)
            .map(|word| u64::from_le_bytes(word.try_into().unwrap()))
            .collect::<Vec<_>>();
        let indexes = unpack_indexes(&words, bits, palette_count, cell_count)?;
        let mut first = vec![None; palette_count];
        for (position, &index) in indexes.iter().enumerate() {
            first[index as usize].get_or_insert(position);
        }
        if first.iter().any(Option::is_none)
            || first
                .windows(2)
                .any(|pair| pair[0].unwrap() >= pair[1].unwrap())
        {
            bail!("surface microtile palette is not in first-encounter order");
        }
        let tile = Self {
            catalog_id: u64::from_le_bytes(bytes[8..16].try_into().unwrap()),
            class,
            edge,
            origin,
            cells: indexes
                .into_iter()
                .map(|index| palette[index as usize])
                .collect(),
        };
        tile.validate()?;
        Ok(tile)
    }

    pub fn canonical_object(&self) -> Result<CanonicalObject> {
        CanonicalObject::new(content_kind(self.class), self.encode()?)
    }

    fn validate(&self) -> Result<()> {
        if self.catalog_id == 0
            || self.edge != MICROTILE_EDGE as u8
            || self.cells.len() != MICROTILE_VOLUME
        {
            bail!("invalid surface production microtile metadata");
        }
        for origin in self.origin {
            if origin as usize >= SECTION_EDGE || !(origin as usize).is_multiple_of(MICROTILE_EDGE)
            {
                bail!("surface microtile origin is not 8-cell aligned inside its parent");
            }
        }
        Ok(())
    }
}

/// Partitions one non-empty hierarchy section into disjoint 8³ content objects.
pub fn prepare_section(
    section: &Section,
    registry: &RegistrySnapshot,
    exterior_microtiles: u64,
) -> Result<PreparedSection> {
    if section.cells.len() != SECTION_VOLUME || registry.catalog_id == 0 {
        bail!("invalid section or catalog for surface microtile generation");
    }
    let mut by_class: [Vec<(usize, CanonicalObject)>; super::manifest::CONTENT_CLASS_COUNT] =
        std::array::from_fn(|_| Vec::new());
    let mut class_cells: [Vec<bool>; super::manifest::CONTENT_CLASS_COUNT] =
        std::array::from_fn(|_| vec![false; SECTION_VOLUME]);

    for bz in 0..4usize {
        for by in 0..4usize {
            for bx in 0..4usize {
                let origin = [
                    bx * MICROTILE_EDGE,
                    by * MICROTILE_EDGE,
                    bz * MICROTILE_EDGE,
                ];
                let mut cells = Vec::with_capacity(MICROTILE_VOLUME);
                let mut non_air = false;
                let mut complex = false;
                for y in 0..MICROTILE_EDGE {
                    for z in 0..MICROTILE_EDGE {
                        for x in 0..MICROTILE_EDGE {
                            let gx = origin[0] + x;
                            let gy = origin[1] + y;
                            let gz = origin[2] + z;
                            let index = cell_index(gx, gy, gz);
                            let cell = section.cells[index];
                            cells.push(cell);
                            if cell.is_air() {
                                continue;
                            }
                            non_air = true;
                            if !safe_opaque(cell, registry) {
                                complex = true;
                            }
                        }
                    }
                }
                if !non_air {
                    continue;
                }
                let morton = morton3(bx as u32, by as u32, bz as u32, 2)?;
                let class = if complex {
                    ContentClass::Complex
                } else if exterior_microtiles & (1u64 << morton) != 0 {
                    ContentClass::Exterior
                } else {
                    ContentClass::Interior
                };
                for y in 0..MICROTILE_EDGE {
                    for z in 0..MICROTILE_EDGE {
                        for x in 0..MICROTILE_EDGE {
                            let gx = origin[0] + x;
                            let gy = origin[1] + y;
                            let gz = origin[2] + z;
                            if !section.cells[cell_index(gx, gy, gz)].is_air() {
                                class_cells[class.index()][cell_index(gx, gy, gz)] = true;
                            }
                        }
                    }
                }
                let companion_cells = (class != ContentClass::Complex).then(|| cells.clone());
                let object = Microtile {
                    catalog_id: registry.catalog_id,
                    class,
                    edge: MICROTILE_EDGE as u8,
                    origin: origin.map(|value| value as u8),
                    cells,
                }
                .canonical_object()?;
                by_class[class.index()].push((morton, object));
                // Both ordinary classifications are optional fast paths. The independently
                // typed complex companion preserves exact cells for clients whose resource pack
                // or baked models reject the server's safe-opaque classification without
                // requiring server knowledge of client assets.
                if class != ContentClass::Complex {
                    for y in 0..MICROTILE_EDGE {
                        for z in 0..MICROTILE_EDGE {
                            for x in 0..MICROTILE_EDGE {
                                let gx = origin[0] + x;
                                let gy = origin[1] + y;
                                let gz = origin[2] + z;
                                if !section.cells[cell_index(gx, gy, gz)].is_air() {
                                    class_cells[ContentClass::Complex.index()]
                                        [cell_index(gx, gy, gz)] = true;
                                }
                            }
                        }
                    }
                    let companion = Microtile {
                        catalog_id: registry.catalog_id,
                        class: ContentClass::Complex,
                        edge: MICROTILE_EDGE as u8,
                        origin: origin.map(|value| value as u8),
                        cells: companion_cells.expect("ordinary companion cells exist"),
                    }
                    .canonical_object()?;
                    by_class[ContentClass::Complex.index()].push((morton, companion));
                }
            }
        }
    }

    let mut contents: [Option<PreparedContent>; super::manifest::CONTENT_CLASS_COUNT] =
        std::array::from_fn(|_| None);
    for class in ContentClass::ALL {
        let objects = &mut by_class[class.index()];
        if objects.is_empty() {
            continue;
        }
        objects.sort_unstable_by_key(|entry| entry.0);
        let mask = objects
            .iter()
            .fold(0u64, |mask, (morton, _)| mask | (1u64 << morton));
        let (boundary_face_mask, boundary_summary) = boundary_summary(&class_cells[class.index()]);
        contents[class.index()] = Some(PreparedContent {
            microtile_mask: mask,
            objects: std::mem::take(objects)
                .into_iter()
                .map(|(_, object)| object)
                .collect(),
            boundary_face_mask,
            boundary_summary,
        });
    }
    if contents.iter().all(Option::is_none) && !section.is_empty() {
        bail!("non-empty section produced no microtiles");
    }
    Ok(PreparedSection {
        contents,
        bounds: quantized_bounds(&section.cells),
    })
}

pub fn content_kind(class: ContentClass) -> ObjectKind {
    match class {
        ContentClass::Exterior => ObjectKind::ExteriorMicrotile,
        ContentClass::Interior => ObjectKind::InteriorMicrotile,
        ContentClass::Complex => ObjectKind::ComplexMicrotile,
    }
}

fn safe_opaque(cell: Cell, registry: &RegistrySnapshot) -> bool {
    if cell.is_air() {
        return false;
    }
    registry
        .blocks
        .get(cell.block as usize)
        .is_some_and(|block| block.authoritative && block.opacity == 15)
}

fn quantized_bounds(cells: &[Cell]) -> Option<QuantizedBounds> {
    let mut min = [SECTION_EDGE; 3];
    let mut max = [0usize; 3];
    let mut any = false;
    for y in 0..SECTION_EDGE {
        for z in 0..SECTION_EDGE {
            for x in 0..SECTION_EDGE {
                if cells[cell_index(x, y, z)].is_air() {
                    continue;
                }
                any = true;
                min[0] = min[0].min(x);
                min[1] = min[1].min(y);
                min[2] = min[2].min(z);
                max[0] = max[0].max(x + 1);
                max[1] = max[1].max(y + 1);
                max[2] = max[2].max(z + 1);
            }
        }
    }
    any.then(|| QuantizedBounds {
        min: min.map(|value| ((value * u16::MAX as usize) / SECTION_EDGE) as u16),
        max: max.map(|value| ((value * u16::MAX as usize) / SECTION_EDGE) as u16),
    })
}

fn boundary_summary(class_cells: &[bool]) -> (u8, Vec<u8>) {
    let mut faces = [[0u8; FACE_BYTES]; 6];
    for (face, summary) in faces.iter_mut().enumerate() {
        for v in 0..SECTION_EDGE {
            for u in 0..SECTION_EDGE {
                let (x, y, z) = match face {
                    0 => (0, u, v),
                    1 => (SECTION_EDGE - 1, u, v),
                    2 => (u, 0, v),
                    3 => (u, SECTION_EDGE - 1, v),
                    4 => (u, v, 0),
                    5 => (u, v, SECTION_EDGE - 1),
                    _ => unreachable!(),
                };
                if class_cells[cell_index(x, y, z)] {
                    let bit = u + v * SECTION_EDGE;
                    summary[bit / 8] |= 1 << (bit & 7);
                }
            }
        }
    }
    let mut mask = 0u8;
    let mut bytes = Vec::new();
    for (face, summary) in faces.into_iter().enumerate() {
        if summary.iter().any(|&byte| byte != 0) {
            mask |= 1 << face;
            bytes.extend_from_slice(&summary);
        }
    }
    (mask, bytes)
}

fn pack_indexes(indexes: &[u16], bits: u8) -> Vec<u64> {
    if bits == 0 {
        return Vec::new();
    }
    let mut words = vec![0u64; words_for(indexes.len(), bits)];
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

fn unpack_indexes(words: &[u64], bits: u8, palette: usize, count: usize) -> Result<Vec<u16>> {
    if bits == 0 {
        if palette != 1 || !words.is_empty() {
            bail!("zero-bit microtile storage requires exactly one palette value");
        }
        return Ok(vec![0; count]);
    }
    if words.len() != words_for(count, bits) {
        bail!("microtile packed-index length mismatch");
    }
    let mask = (1u64 << bits) - 1;
    let mut indexes = Vec::with_capacity(count);
    for index in 0..count {
        let bit = index * bits as usize;
        let word = bit >> 6;
        let shift = bit & 63;
        let mut value = words[word] >> shift;
        if shift + bits as usize > 64 {
            value |= words[word + 1] << (64 - shift);
        }
        let value = (value & mask) as u16;
        if value as usize >= palette {
            bail!("microtile palette index is out of range");
        }
        indexes.push(value);
    }
    Ok(indexes)
}

fn words_for(count: usize, bits: u8) -> usize {
    count.saturating_mul(bits as usize).div_ceil(64)
}
