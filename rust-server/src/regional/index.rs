use super::{RegionFile, RegionLayout, RegionSectionEntry};
use anyhow::{Context, Result, bail};

const INDEX_MAGIC: &[u8; 8] = b"VXYRIDX\0";
const INDEX_HEADER_BYTES: usize = 36;
const INDEX_ENTRY_BYTES: usize = 32;
const MAX_INDEX_CANONICAL_BYTES: usize = 4 * 1024 * 1024;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RegionIndex {
    pub region_x: i32,
    pub region_z: i32,
    pub generation: u64,
    pub layout: RegionLayout,
    pub entries: Vec<RegionSectionEntry>,
}

impl RegionIndex {
    pub fn from_file(file: &RegionFile) -> Self {
        Self {
            region_x: file.region().0,
            region_z: file.region().1,
            generation: file.generation(),
            layout: file.layout(),
            entries: file.entries().to_vec(),
        }
    }

    pub fn encode(&self) -> Result<Vec<u8>> {
        if self.generation == 0 || self.entries.len() != self.layout.entry_count()? {
            bail!("regional wire index identity or entry count is invalid");
        }
        let length = INDEX_HEADER_BYTES
            .checked_add(
                self.entries
                    .len()
                    .checked_mul(INDEX_ENTRY_BYTES)
                    .context("regional wire index length overflow")?,
            )
            .context("regional wire index length overflow")?;
        if length > MAX_INDEX_CANONICAL_BYTES {
            bail!("regional wire index exceeds its safety bound");
        }
        let mut output = Vec::with_capacity(length);
        output.extend_from_slice(INDEX_MAGIC);
        output.extend_from_slice(&self.region_x.to_le_bytes());
        output.extend_from_slice(&self.region_z.to_le_bytes());
        output.extend_from_slice(&self.generation.to_le_bytes());
        output.extend_from_slice(&self.layout.min_base_y.to_le_bytes());
        output.extend_from_slice(&self.layout.base_y_count.to_le_bytes());
        output.push(self.layout.levels);
        output.push(0);
        output.extend_from_slice(&(self.entries.len() as u32).to_le_bytes());
        for entry in &self.entries {
            encode_entry(&mut output, *entry)?;
        }
        debug_assert_eq!(output.len(), length);
        Ok(output)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() < INDEX_HEADER_BYTES
            || bytes.len() > MAX_INDEX_CANONICAL_BYTES
            || &bytes[..8] != INDEX_MAGIC
            || bytes[31] != 0
        {
            bail!("invalid regional wire index header");
        }
        let layout = RegionLayout::new(
            i32::from_le_bytes(bytes[24..28].try_into().unwrap()),
            u16::from_le_bytes(bytes[28..30].try_into().unwrap()),
            bytes[30],
        )?;
        let count = u32::from_le_bytes(bytes[32..36].try_into().unwrap()) as usize;
        let expected = INDEX_HEADER_BYTES
            .checked_add(
                count
                    .checked_mul(INDEX_ENTRY_BYTES)
                    .context("index size overflow")?,
            )
            .context("index size overflow")?;
        if count != layout.entry_count()? || bytes.len() != expected {
            bail!("regional wire index entry count is invalid");
        }
        let entries = bytes[INDEX_HEADER_BYTES..]
            .chunks_exact(INDEX_ENTRY_BYTES)
            .map(decode_entry)
            .collect::<Result<Vec<_>>>()?;
        let index = Self {
            region_x: i32::from_le_bytes(bytes[8..12].try_into().unwrap()),
            region_z: i32::from_le_bytes(bytes[12..16].try_into().unwrap()),
            generation: u64::from_le_bytes(bytes[16..24].try_into().unwrap()),
            layout,
            entries,
        };
        if index.generation == 0 {
            bail!("regional wire index generation zero is reserved");
        }
        Ok(index)
    }

    pub fn fingerprint(&self) -> Result<[u8; 16]> {
        let mut output = [0; 16];
        output.copy_from_slice(&blake3::hash(&self.encode()?).as_bytes()[..16]);
        Ok(output)
    }

    pub fn compressed(&self) -> Result<Vec<u8>> {
        Ok(zstd::bulk::compress(&self.encode()?, 1)?)
    }
}

fn encode_entry(output: &mut Vec<u8>, entry: RegionSectionEntry) -> Result<()> {
    entry.validate_client()?;
    output.extend_from_slice(&entry.flags.to_le_bytes());
    output.push(entry.non_empty_children);
    output.push(0);
    output.extend_from_slice(&entry.compressed_length.to_le_bytes());
    output.extend_from_slice(&entry.canonical_length.to_le_bytes());
    output.extend_from_slice(&entry.fingerprint);
    output.extend_from_slice(&[0; 4]);
    debug_assert_eq!(
        output.len() % INDEX_ENTRY_BYTES,
        INDEX_HEADER_BYTES % INDEX_ENTRY_BYTES
    );
    Ok(())
}

fn decode_entry(bytes: &[u8]) -> Result<RegionSectionEntry> {
    if bytes.len() != INDEX_ENTRY_BYTES || bytes[3] != 0 || bytes[28..32] != [0; 4] {
        bail!("invalid regional wire index entry");
    }
    let entry = RegionSectionEntry {
        flags: u16::from_le_bytes(bytes[0..2].try_into().unwrap()),
        non_empty_children: bytes[2],
        payload_offset: 0,
        compressed_length: u32::from_le_bytes(bytes[4..8].try_into().unwrap()),
        canonical_length: u32::from_le_bytes(bytes[8..12].try_into().unwrap()),
        compressed_crc: 0,
        fingerprint: bytes[12..28].try_into().unwrap(),
    };
    entry.validate_client()?;
    Ok(entry)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::regional::SECTION_FLAG_EMPTY;

    #[test]
    fn compact_region_index_round_trips() {
        let layout = RegionLayout::new(-2, 12, 5).unwrap();
        let mut entries = vec![RegionSectionEntry::default(); layout.entry_count().unwrap()];
        entries[3] = RegionSectionEntry {
            flags: 1 << 15 | SECTION_FLAG_EMPTY,
            ..RegionSectionEntry::default()
        };
        let expected = RegionIndex {
            region_x: 1,
            region_z: -2,
            generation: 4,
            layout,
            entries,
        };
        assert_eq!(
            RegionIndex::decode(&expected.encode().unwrap()).unwrap(),
            expected
        );
        assert_ne!(expected.fingerprint().unwrap(), [0; 16]);
    }
}
