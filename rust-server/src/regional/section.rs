use crate::lod::{Cell, SECTION_VOLUME};
use anyhow::{Context, Result, bail};
use std::collections::HashMap;

const SECTION_HEADER_BYTES: usize = 2;

/// Exact render cells. Spatial coordinates, child presence, lengths, and integrity metadata live
/// in the fixed regional directory and are intentionally not duplicated in this payload.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SectionFrame {
    pub non_empty_children: u8,
    pub cells: Vec<Cell>,
}

impl SectionFrame {
    pub fn new(non_empty_children: u8, cells: Vec<Cell>) -> Result<Self> {
        validate_cells(&cells)?;
        Ok(Self {
            non_empty_children,
            cells,
        })
    }

    pub fn empty(non_empty_children: u8) -> Result<Self> {
        Self::new(non_empty_children, vec![Cell::AIR; SECTION_VOLUME])
    }

    pub fn is_empty(&self) -> bool {
        self.cells.iter().all(|cell| cell.is_air())
    }

    pub fn encode(&self) -> Result<Vec<u8>> {
        self.validate()?;
        if self.is_empty() {
            bail!("empty regional sections have no canonical payload");
        }
        let mut palette = Vec::<Cell>::new();
        let mut palette_ids = HashMap::<Cell, u16>::new();
        let mut indexes = Vec::with_capacity(SECTION_VOLUME);
        for cell in &self.cells {
            let id = if let Some(&id) = palette_ids.get(cell) {
                id
            } else {
                let id = u16::try_from(palette.len()).context("section palette exceeds u16")?;
                palette.push(*cell);
                palette_ids.insert(*cell, id);
                id
            };
            indexes.push(id);
        }
        let bits = palette_bits(palette.len())?;
        let words = pack_indexes(&indexes, bits);
        let mut output = Vec::with_capacity(
            SECTION_HEADER_BYTES + palette.len() * 9 + words.len() * size_of::<u64>(),
        );
        output.extend_from_slice(&(palette.len() as u16).to_le_bytes());
        debug_assert_eq!(output.len(), SECTION_HEADER_BYTES);
        for cell in palette {
            output.extend_from_slice(&cell.block.to_le_bytes());
            output.extend_from_slice(&cell.biome.to_le_bytes());
            output.push(cell.light);
        }
        for word in words {
            output.extend_from_slice(&word.to_le_bytes());
        }
        Ok(output)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() < SECTION_HEADER_BYTES {
            bail!("truncated regional section frame");
        }
        let palette_count = u16::from_le_bytes(bytes[..2].try_into().unwrap()) as usize;
        let bits = palette_bits(palette_count)?;
        let word_count = words_for(SECTION_VOLUME, bits)?;
        let expected_length = SECTION_HEADER_BYTES
            .checked_add(
                palette_count
                    .checked_mul(9)
                    .context("section palette size overflow")?,
            )
            .and_then(|length| length.checked_add(word_count.checked_mul(8)?))
            .context("section frame length overflow")?;
        if bytes.len() != expected_length {
            bail!("regional section frame length mismatch");
        }
        let mut palette = Vec::with_capacity(palette_count);
        let mut unique = HashMap::<Cell, ()>::with_capacity(palette_count);
        let mut cursor = SECTION_HEADER_BYTES;
        for _ in 0..palette_count {
            let cell = Cell {
                block: u32::from_le_bytes(bytes[cursor..cursor + 4].try_into().unwrap()),
                biome: u32::from_le_bytes(bytes[cursor + 4..cursor + 8].try_into().unwrap()),
                light: bytes[cursor + 8],
            };
            if unique.insert(cell, ()).is_some() {
                bail!("duplicate cell in regional section palette");
            }
            palette.push(cell);
            cursor += 9;
        }
        let words = bytes[cursor..]
            .chunks_exact(8)
            .map(|word| u64::from_le_bytes(word.try_into().unwrap()))
            .collect::<Vec<_>>();
        let cells = unpack_cells(&words, bits, &palette, SECTION_VOLUME)?;
        let frame = Self {
            non_empty_children: 0,
            cells,
        };
        frame.validate()?;
        if frame.is_empty() {
            bail!("empty regional section unexpectedly has a payload");
        }
        Ok(frame)
    }

    fn validate(&self) -> Result<()> {
        validate_cells(&self.cells)
    }
}

fn validate_cells(cells: &[Cell]) -> Result<()> {
    if cells.len() != SECTION_VOLUME {
        bail!(
            "section frame has {} cells instead of {SECTION_VOLUME}",
            cells.len()
        );
    }
    Ok(())
}

fn palette_bits(palette_count: usize) -> Result<u8> {
    if palette_count == 0 || palette_count > u16::MAX as usize {
        bail!("regional section palette count is outside its bound");
    }
    Ok(if palette_count == 1 {
        0
    } else {
        (usize::BITS - (palette_count - 1).leading_zeros()) as u8
    })
}

fn words_for(count: usize, bits: u8) -> Result<usize> {
    count
        .checked_mul(bits as usize)
        .map(|value| value.div_ceil(64))
        .context("packed section index length overflow")
}

fn pack_indexes(indexes: &[u16], bits: u8) -> Vec<u64> {
    if bits == 0 {
        return Vec::new();
    }
    let mut words = vec![0u64; indexes.len() * bits as usize / 64 + 1];
    for (index, value) in indexes.iter().copied().enumerate() {
        let bit = index * bits as usize;
        let word = bit / 64;
        let shift = bit % 64;
        words[word] |= u64::from(value) << shift;
        if shift + bits as usize > 64 {
            words[word + 1] |= u64::from(value) >> (64 - shift);
        }
    }
    words.truncate((indexes.len() * bits as usize).div_ceil(64));
    words
}

fn unpack_cells(words: &[u64], bits: u8, palette: &[Cell], count: usize) -> Result<Vec<Cell>> {
    if bits == 0 {
        if palette.len() != 1 || !words.is_empty() {
            bail!("zero-bit section indexes require one palette cell");
        }
        if count == 0 {
            bail!("unused regional section palette entry");
        }
        return Ok(vec![palette[0]; count]);
    }
    if words.len() != words_for(count, bits)? {
        bail!("packed regional section index length mismatch");
    }
    let mask = (1u64 << bits) - 1;
    let mut output = Vec::with_capacity(count);
    let mut next = 0usize;
    for index in 0..count {
        let bit = index * bits as usize;
        let word = bit / 64;
        let shift = bit % 64;
        let mut value = words[word] >> shift;
        if shift + bits as usize > 64 {
            value |= words[word + 1] << (64 - shift);
        }
        let value = (value & mask) as usize;
        if value >= palette.len() {
            bail!("regional section palette index is out of range");
        }
        if value > next {
            bail!("noncanonical section palette order");
        }
        if value == next {
            next += 1;
        }
        output.push(palette[value]);
    }
    if !(count * bits as usize).is_multiple_of(64) {
        let used = count * bits as usize % 64;
        if words.last().copied().unwrap_or_default() >> used != 0 {
            bail!("nonzero regional section index padding");
        }
    }
    if next != palette.len() {
        bail!("unused regional section palette entry");
    }
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_every_valid_palette_width_and_power_boundary() {
        let mut sizes = std::collections::BTreeSet::from([1, SECTION_VOLUME]);
        for bit in 1..=15 {
            for size in [(1 << bit) - 1, 1 << bit, (1 << bit) + 1] {
                if size <= SECTION_VOLUME {
                    sizes.insert(size);
                }
            }
        }
        let mut widths = std::collections::BTreeSet::new();
        for size in sizes {
            widths.insert(palette_bits(size).unwrap());
            let palette = (0..size)
                .map(|id| Cell {
                    block: if id == 0 && size > 1 {
                        0
                    } else {
                        id as u32 + 1
                    },
                    biome: (id as u32).wrapping_mul(7919).wrapping_add(13),
                    light: (id * 37 + 255) as u8,
                })
                .collect::<Vec<_>>();
            let mut seed = 0x98765432u32;
            let cells = (0..SECTION_VOLUME)
                .map(|i| {
                    seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
                    palette[if i < size { i } else { seed as usize % size }]
                })
                .collect();
            let expected = SectionFrame::new(0, cells).unwrap();
            let bytes = expected.encode().unwrap();
            let decoded = SectionFrame::decode(&bytes).unwrap();
            assert_eq!(decoded, expected, "palette size {size}");
            assert_eq!(decoded.encode().unwrap(), bytes, "palette size {size}");
        }
        assert_eq!(widths, (0..=15).collect());
    }

    #[test]
    fn packed_cells_validate_padding_length_and_zero_bit_cases() {
        let palette = [
            Cell::AIR,
            Cell {
                block: 1,
                biome: 9,
                light: 255,
            },
        ];
        let valid = pack_indexes(&[0, 1, 0], 1);
        assert_eq!(
            unpack_cells(&valid, 1, &palette, 3).unwrap(),
            vec![palette[0], palette[1], palette[0]]
        );
        let mut padded = valid.clone();
        padded[0] |= 1 << 3;
        // These indexes already satisfy bounds, first-use and all-used checks.
        assert!(unpack_cells(&padded, 1, &palette, 3).is_err());
        assert!(unpack_cells(&[], 1, &palette, 3).is_err());
        assert!(unpack_cells(&[valid[0], 0], 1, &palette, 3).is_err());
        assert_eq!(
            unpack_cells(&[], 0, &palette[1..], 3).unwrap(),
            vec![palette[1]; 3]
        );
        assert!(unpack_cells(&[0], 0, &palette[1..], 3).is_err());
        assert!(unpack_cells(&[], 0, &palette, 3).is_err());
        assert!(unpack_cells(&[], 0, &[], 3).is_err());
        assert!(unpack_cells(&[], 0, &palette[1..], 0).is_err());
    }

    #[test]
    fn malformed_lengths_counts_and_all_air_are_rejected() {
        let frame = SectionFrame::new(
            0,
            vec![
                Cell {
                    block: 1,
                    biome: 42,
                    light: 255
                };
                SECTION_VOLUME
            ],
        )
        .unwrap();
        let valid = frame.encode().unwrap();
        for length in 0..valid.len() {
            assert!(SectionFrame::decode(&valid[..length]).is_err());
        }
        let mut extra = valid.clone();
        extra.push(0);
        assert!(SectionFrame::decode(&extra).is_err());
        let mut mixed = frame.clone();
        mixed.cells[SECTION_VOLUME - 1] = Cell::AIR;
        let packed = mixed.encode().unwrap();
        assert!(SectionFrame::decode(&packed[..packed.len() - 1]).is_err());
        let mut extra_packed = packed;
        extra_packed.push(0);
        assert!(SectionFrame::decode(&extra_packed).is_err());
        assert!(SectionFrame::decode(&[0, 0]).is_err());
        assert!(SectionFrame::decode(&u16::MAX.to_le_bytes()).is_err());
        for cell in [
            Cell::AIR,
            Cell {
                block: 0,
                biome: 123,
                light: 255,
            },
        ] {
            let mut bytes = 1u16.to_le_bytes().to_vec();
            bytes.extend_from_slice(&cell.block.to_le_bytes());
            bytes.extend_from_slice(&cell.biome.to_le_bytes());
            bytes.push(cell.light);
            assert!(
                SectionFrame::decode(&bytes).is_err(),
                "air metadata made an empty payload nonempty"
            );
            assert!(
                SectionFrame::new(0, vec![cell; SECTION_VOLUME])
                    .unwrap()
                    .encode()
                    .is_err()
            );
        }
        // A 16-bit palette is representable in the header but cannot have all entries used
        // in a 32^3 section. Reach the real all-used check with otherwise valid packed data.
        let count = SECTION_VOLUME + 1;
        let mut bytes = (count as u16).to_le_bytes().to_vec();
        for id in 0..count {
            bytes.extend_from_slice(&(id as u32 + 1).to_le_bytes());
            bytes.extend_from_slice(&0u32.to_le_bytes());
            bytes.push(0);
        }
        for word in pack_indexes(&(0..SECTION_VOLUME as u16).collect::<Vec<_>>(), 16) {
            bytes.extend_from_slice(&word.to_le_bytes());
        }
        assert!(SectionFrame::decode(&bytes).is_err());
    }

    #[test]
    fn shared_palette_fixtures() {
        for line in include_str!("../../../test-fixtures/regional-section-cases.txt").lines() {
            if line.starts_with('#') || line.is_empty() {
                continue;
            }
            let fields = line.split_whitespace().collect::<Vec<_>>();
            let count: usize = fields[1].parse().unwrap();
            let mode = fields[2];
            let mut bytes = (count as u16).to_le_bytes().to_vec();
            for i in 0..count {
                let i = if mode == "duplicate" && i == 1 { 0 } else { i };
                bytes.extend_from_slice(&(i as u32 + 1).to_le_bytes());
                bytes.extend_from_slice(&(i as u32 % 17).to_le_bytes());
                bytes.push(i as u8);
            }
            let indexes = (0..SECTION_VOLUME)
                .map(|i| {
                    (match mode {
                        "repeat" => i / 2 % count,
                        "first" if i == 0 => 1,
                        "skip" if i == 1 => 2,
                        "overflow" if i == SECTION_VOLUME - 1 => count,
                        "unused" => i % (count - 1),
                        _ => i % count,
                    }) as u16
                })
                .collect::<Vec<_>>();
            for word in pack_indexes(&indexes, palette_bits(count).unwrap()) {
                bytes.extend_from_slice(&word.to_le_bytes());
            }
            let hash = blake3::hash(&bytes).to_hex()[..32].to_owned();
            assert_eq!(hash, fields[4], "{}", fields[0]);
            let result = SectionFrame::decode(&bytes);
            assert_eq!(
                result.is_ok(),
                fields[3] == "true",
                "{}: {result:?}",
                fields[0]
            );
            if let Ok(frame) = result {
                for (cell, index) in frame.cells.iter().zip(&indexes) {
                    assert_eq!(
                        *cell,
                        Cell {
                            block: *index as u32 + 1,
                            biome: *index as u32 % 17,
                            light: *index as u8
                        }
                    );
                }
                assert_eq!(frame.encode().unwrap(), bytes);
            }
        }
        assert!(SectionFrame::decode(&[0, 0]).is_err());
        assert!(SectionFrame::decode(&[1]).is_err());
    }

    #[test]
    fn section_frame_round_trip_keeps_one_cell_payload() {
        let mut cells = vec![Cell::AIR; SECTION_VOLUME];
        cells[0] = Cell {
            block: 1,
            biome: 0,
            light: 15,
        };
        cells[17] = Cell {
            block: 2,
            biome: 0,
            light: 3,
        };
        let frame = SectionFrame::new(3, cells).unwrap();
        let mut decoded = SectionFrame::decode(&frame.encode().unwrap()).unwrap();
        decoded.non_empty_children = frame.non_empty_children;
        assert_eq!(decoded, frame);
    }
}
