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
        let indexes = unpack_indexes(&words, bits, palette_count, SECTION_VOLUME)?;
        let mut next = 0usize;
        let cells = indexes
            .into_iter()
            .map(|index| {
                let index = index as usize;
                if index > next {
                    return Err(anyhow::anyhow!("noncanonical section palette order"));
                }
                if index == next {
                    next += 1;
                }
                Ok(palette[index])
            })
            .collect::<Result<Vec<_>>>()?;
        if next != palette_count {
            bail!("unused regional section palette entry");
        }
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

fn unpack_indexes(words: &[u64], bits: u8, palette_count: usize, count: usize) -> Result<Vec<u16>> {
    if bits == 0 {
        if palette_count != 1 || !words.is_empty() {
            bail!("zero-bit section indexes require one palette cell");
        }
        return Ok(vec![0; count]);
    }
    if words.len() != words_for(count, bits)? {
        bail!("packed regional section index length mismatch");
    }
    let mask = (1u64 << bits) - 1;
    let mut output = Vec::with_capacity(count);
    for index in 0..count {
        let bit = index * bits as usize;
        let word = bit / 64;
        let shift = bit % 64;
        let mut value = words[word] >> shift;
        if shift + bits as usize > 64 {
            value |= words[word + 1] << (64 - shift);
        }
        let value = (value & mask) as u16;
        if value as usize >= palette_count {
            bail!("regional section palette index is out of range");
        }
        output.push(value);
    }
    if !(count * bits as usize).is_multiple_of(64) {
        let used = count * bits as usize % 64;
        if words.last().copied().unwrap_or_default() >> used != 0 {
            bail!("nonzero regional section index padding");
        }
    }
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;

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
        assert!(
            unpack_indexes(&[2], 1, 2, 1).is_err(),
            "nonzero padding accepted"
        );
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
