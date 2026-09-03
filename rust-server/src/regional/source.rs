use crate::{crc::crc32c, sync_parent};
use anyhow::{Context, Result, bail};
use std::{
    fs::{self, File, OpenOptions},
    io::Write,
    os::unix::fs::FileExt,
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};

const SOURCE_MAGIC: &[u8; 8] = b"VXYSRC\0\0";
const SOURCE_HEADER_BYTES: usize = 64;
const SOURCE_RECORD_BYTES: usize = 32;
const SOURCE_CHECKSUM_BYTES: usize = 32;
const SOURCE_FILE_BYTES: usize =
    SOURCE_HEADER_BYTES + CHUNKS_PER_REGION * SOURCE_RECORD_BYTES + SOURCE_CHECKSUM_BYTES;
pub const CHUNKS_PER_REGION: usize = 32 * 32;
static NEXT_TEMPORARY: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct ChunkSourceRecord {
    pub generated: bool,
    pub anvil_location: u32,
    pub anvil_timestamp: u32,
    pub semantic_fingerprint: [u64; 2],
}

impl ChunkSourceRecord {
    fn encode(self) -> [u8; SOURCE_RECORD_BYTES] {
        let mut output = [0u8; SOURCE_RECORD_BYTES];
        output[0] = u8::from(self.generated);
        output[4..8].copy_from_slice(&self.anvil_location.to_le_bytes());
        output[8..12].copy_from_slice(&self.anvil_timestamp.to_le_bytes());
        output[12..20].copy_from_slice(&self.semantic_fingerprint[0].to_le_bytes());
        output[20..28].copy_from_slice(&self.semantic_fingerprint[1].to_le_bytes());
        output
    }

    fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() != SOURCE_RECORD_BYTES
            || bytes[0] > 1
            || bytes[1..4] != [0; 3]
            || bytes[28..32] != [0; 4]
        {
            bail!("invalid regional chunk source record");
        }
        let record = Self {
            generated: bytes[0] == 1,
            anvil_location: u32::from_le_bytes(bytes[4..8].try_into().unwrap()),
            anvil_timestamp: u32::from_le_bytes(bytes[8..12].try_into().unwrap()),
            semantic_fingerprint: [
                u64::from_le_bytes(bytes[12..20].try_into().unwrap()),
                u64::from_le_bytes(bytes[20..28].try_into().unwrap()),
            ],
        };
        if !record.generated && record.semantic_fingerprint != [0; 2] {
            bail!("absent chunk source has a semantic terrain fingerprint");
        }
        Ok(record)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RegionSourceTable {
    pub region_x: i32,
    pub region_z: i32,
    pub terrain_generation: u64,
    pub anvil_file_marker: u64,
    records: Box<[ChunkSourceRecord; CHUNKS_PER_REGION]>,
}

impl RegionSourceTable {
    pub fn new(
        region_x: i32,
        region_z: i32,
        terrain_generation: u64,
        anvil_file_marker: u64,
    ) -> Result<Self> {
        if terrain_generation == 0 {
            bail!("regional source table generation zero is reserved");
        }
        Ok(Self {
            region_x,
            region_z,
            terrain_generation,
            anvil_file_marker,
            records: Box::new([ChunkSourceRecord::default(); CHUNKS_PER_REGION]),
        })
    }

    pub fn record(&self, local_x: u8, local_z: u8) -> Result<ChunkSourceRecord> {
        Ok(self.records[source_index(local_x, local_z)?])
    }

    pub fn set_record(
        &mut self,
        local_x: u8,
        local_z: u8,
        record: ChunkSourceRecord,
    ) -> Result<()> {
        if !record.generated && record.semantic_fingerprint != [0; 2] {
            bail!("absent chunk source has a semantic terrain fingerprint");
        }
        self.records[source_index(local_x, local_z)?] = record;
        Ok(())
    }

    pub fn header_matches(&self, entries: &[crate::anvil::RegionEntry], marker: u64) -> bool {
        entries.len() == CHUNKS_PER_REGION
            && marker == self.anvil_file_marker
            && entries
                .iter()
                .zip(self.records.iter())
                .all(|(entry, record)| {
                    entry.location == record.anvil_location
                        && entry.timestamp == record.anvil_timestamp
                        && (entry.location >> 8 != 0 && entry.location & 0xff != 0)
                            == record.generated
                })
    }

    pub fn write_atomic(&self, path: impl AsRef<Path>) -> Result<()> {
        let path = path.as_ref();
        let parent = path
            .parent()
            .context("regional source file has no parent")?;
        fs::create_dir_all(parent).with_context(|| format!("create {}", parent.display()))?;
        let mut bytes = Vec::with_capacity(SOURCE_FILE_BYTES);
        bytes.extend_from_slice(SOURCE_MAGIC);
        bytes.extend_from_slice(&self.region_x.to_le_bytes());
        bytes.extend_from_slice(&self.region_z.to_le_bytes());
        bytes.extend_from_slice(&self.terrain_generation.to_le_bytes());
        bytes.extend_from_slice(&self.anvil_file_marker.to_le_bytes());
        bytes.extend_from_slice(&(CHUNKS_PER_REGION as u16).to_le_bytes());
        bytes.extend_from_slice(&[0; 26]);
        let header_crc = crc32c(&bytes);
        bytes.extend_from_slice(&header_crc.to_le_bytes());
        debug_assert_eq!(bytes.len(), SOURCE_HEADER_BYTES);
        for record in self.records.iter().copied() {
            bytes.extend_from_slice(&record.encode());
        }
        bytes.extend_from_slice(blake3::hash(&bytes).as_bytes());
        debug_assert_eq!(bytes.len(), SOURCE_FILE_BYTES);

        let temporary = temporary_path(path);
        let result = (|| -> Result<()> {
            let mut file = OpenOptions::new()
                .create_new(true)
                .write(true)
                .open(&temporary)?;
            file.write_all(&bytes)?;
            file.sync_all()?;
            fs::rename(&temporary, path)?;
            sync_parent(path)
        })();
        if result.is_err() {
            let _ = fs::remove_file(&temporary);
        }
        result
    }

    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        let path = path.as_ref();
        let file = File::open(path).with_context(|| format!("open {}", path.display()))?;
        if file.metadata()?.len() != SOURCE_FILE_BYTES as u64 {
            bail!("regional source table has an invalid fixed length");
        }
        let mut bytes = vec![0; SOURCE_FILE_BYTES];
        file.read_exact_at(&mut bytes, 0)?;
        if &bytes[..8] != SOURCE_MAGIC
            || u16::from_le_bytes(bytes[32..34].try_into().unwrap()) as usize != CHUNKS_PER_REGION
            || bytes[34..60] != [0; 26]
            || u32::from_le_bytes(bytes[60..64].try_into().unwrap()) != crc32c(&bytes[..60])
            || blake3::hash(&bytes[..SOURCE_FILE_BYTES - SOURCE_CHECKSUM_BYTES]).as_bytes()
                != &bytes[SOURCE_FILE_BYTES - SOURCE_CHECKSUM_BYTES..]
        {
            bail!("regional source table checksum or header is invalid");
        }
        let records = bytes[SOURCE_HEADER_BYTES..SOURCE_FILE_BYTES - SOURCE_CHECKSUM_BYTES]
            .chunks_exact(SOURCE_RECORD_BYTES)
            .map(ChunkSourceRecord::decode)
            .collect::<Result<Vec<_>>>()?
            .try_into()
            .map_err(|_| anyhow::anyhow!("regional source table record count mismatch"))?;
        let table = Self {
            region_x: i32::from_le_bytes(bytes[8..12].try_into().unwrap()),
            region_z: i32::from_le_bytes(bytes[12..16].try_into().unwrap()),
            terrain_generation: u64::from_le_bytes(bytes[16..24].try_into().unwrap()),
            anvil_file_marker: u64::from_le_bytes(bytes[24..32].try_into().unwrap()),
            records: Box::new(records),
        };
        if table.terrain_generation == 0 {
            bail!("regional source table generation zero is reserved");
        }
        Ok(table)
    }
}

fn source_index(local_x: u8, local_z: u8) -> Result<usize> {
    if local_x >= 32 || local_z >= 32 {
        bail!("regional chunk source coordinate is outside 0..32");
    }
    Ok(local_x as usize + local_z as usize * 32)
}

fn temporary_path(path: &Path) -> PathBuf {
    let sequence = NEXT_TEMPORARY.fetch_add(1, Ordering::Relaxed);
    let name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("region.vxsource");
    path.with_file_name(format!(".{name}.tmp.{}.{}", std::process::id(), sequence))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn source_table_round_trip_is_fixed_and_compact() {
        let root = std::env::temp_dir().join(format!(
            "voxy-source-test-{}-{}",
            std::process::id(),
            NEXT_TEMPORARY.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&root).unwrap();
        let path = root.join("r.1.-2.vxsource");
        let mut table = RegionSourceTable::new(1, -2, 4, 99).unwrap();
        table
            .set_record(
                3,
                7,
                ChunkSourceRecord {
                    generated: true,
                    anvil_location: 55,
                    anvil_timestamp: 66,
                    semantic_fingerprint: [77, 88],
                },
            )
            .unwrap();
        table.write_atomic(&path).unwrap();
        assert_eq!(path.metadata().unwrap().len(), SOURCE_FILE_BYTES as u64);
        assert_eq!(RegionSourceTable::open(&path).unwrap(), table);
        let _ = fs::remove_dir_all(root);
    }
}
