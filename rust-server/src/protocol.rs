use crate::{MAX_LOD, PROTOCOL_VERSION, crc::crc32c, lod::Section, registry::RegistrySnapshot};
use anyhow::{Context, Result, bail};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const MAGIC: u32 = 0x3259_5856; // ASCII "VXY2" in little-endian order.
pub const HEADER_LEN: usize = 16;
pub const MAX_PAYLOAD: usize = 16 * 1024 * 1024;
pub const MAX_CLIENT_PAYLOAD: usize = 4 + 2048 * 8;
pub const MAX_STRING: usize = 4096;
pub const WRITE_TIMEOUT: Duration = Duration::from_secs(30);
const MAPPING_TARGET: usize = 8 * 1024 * 1024;
const MAX_MAPPING_ENTRIES: usize = 256;

pub const C_HELLO: u16 = 0x0001;
pub const C_SUBSCRIBE: u16 = 0x0002;
pub const C_PING: u16 = 0x0003;
pub const C_BLOCK_PROPERTIES: u16 = 0x0004;
pub const C_CREDIT: u16 = 0x0005;
pub const S_HELLO: u16 = 0x8001;
pub const S_MAPPING_DELTA: u16 = 0x8002;
pub const S_SECTION: u16 = 0x8003;
pub const S_INVALIDATE: u16 = 0x8004;
pub const S_PONG: u16 = 0x8005;
pub const S_ERROR: u16 = 0x80ff;

pub const CAP_BLOCK_PROPERTIES: u32 = 1;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Frame {
    pub kind: u16,
    pub payload: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SubscriptionBatch {
    pub dimension: String,
    pub additions: Vec<(u64, u64)>,
    pub removals: Vec<u64>,
}

pub const MAX_SUBSCRIPTION_ENTRIES: usize = 256;

impl Frame {
    pub async fn read(reader: &mut (impl AsyncRead + Unpin)) -> Result<Option<Self>> {
        Self::read_bounded(reader, false).await
    }

    /// Server-side reader. It rejects impossible client frames from the 16-byte header before
    /// allocating their payload, including before HELLO completes.
    pub async fn read_client(reader: &mut (impl AsyncRead + Unpin)) -> Result<Option<Self>> {
        Self::read_bounded(reader, true).await
    }

    async fn read_bounded(
        reader: &mut (impl AsyncRead + Unpin),
        client: bool,
    ) -> Result<Option<Self>> {
        let mut header = [0u8; HEADER_LEN];
        match reader.read_exact(&mut header).await {
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
            Err(error) => return Err(error.into()),
        }
        if u32::from_le_bytes(header[..4].try_into().unwrap()) != MAGIC {
            bail!("bad frame magic");
        }
        if u16::from_le_bytes(header[4..6].try_into().unwrap()) != PROTOCOL_VERSION {
            bail!("unsupported protocol version");
        }
        let kind = u16::from_le_bytes(header[6..8].try_into().unwrap());
        let length = u32::from_le_bytes(header[8..12].try_into().unwrap()) as usize;
        let expected_crc = u32::from_le_bytes(header[12..16].try_into().unwrap());
        if length > MAX_PAYLOAD {
            bail!("frame is larger than {MAX_PAYLOAD} bytes");
        }
        if client {
            validate_client_frame_size(kind, length)?;
        }
        let mut payload = vec![0; length];
        reader.read_exact(&mut payload).await?;
        if crc32c(&payload) != expected_crc {
            bail!("frame checksum mismatch");
        }
        Ok(Some(Self { kind, payload }))
    }

    pub async fn write(&self, writer: &mut (impl AsyncWrite + Unpin)) -> Result<()> {
        self.write_with_timeout(writer, WRITE_TIMEOUT).await
    }

    async fn write_with_timeout(
        &self,
        writer: &mut (impl AsyncWrite + Unpin),
        deadline: Duration,
    ) -> Result<()> {
        if self.payload.len() > MAX_PAYLOAD {
            bail!("frame is larger than {MAX_PAYLOAD} bytes");
        }
        let mut header = [0u8; HEADER_LEN];
        header[..4].copy_from_slice(&MAGIC.to_le_bytes());
        header[4..6].copy_from_slice(&PROTOCOL_VERSION.to_le_bytes());
        header[6..8].copy_from_slice(&self.kind.to_le_bytes());
        header[8..12].copy_from_slice(&(self.payload.len() as u32).to_le_bytes());
        header[12..16].copy_from_slice(&crc32c(&self.payload).to_le_bytes());
        tokio::time::timeout(deadline, async {
            writer.write_all(&header).await?;
            writer.write_all(&self.payload).await
        })
        .await
        .context("client frame write timed out")??;
        Ok(())
    }
}

fn validate_client_frame_size(kind: u16, length: usize) -> Result<()> {
    let valid = match kind {
        C_HELLO => length == 4,
        C_SUBSCRIBE => (6..=6 + MAX_STRING + MAX_SUBSCRIPTION_ENTRIES * 16).contains(&length),
        C_PING => length == 8,
        C_CREDIT => length == 8,
        C_BLOCK_PROPERTIES => {
            (4..=MAX_CLIENT_PAYLOAD).contains(&length) && (length - 4).is_multiple_of(8)
        }
        _ => false,
    };
    if !valid {
        bail!("client frame {kind:#06x} has impossible payload length {length}");
    }
    Ok(())
}

pub fn parse_hello(payload: &[u8]) -> Result<u32> {
    let mut input = payload;
    let capabilities = take_u32(&mut input)?;
    finish(input)?;
    Ok(capabilities)
}

pub fn parse_subscriptions(payload: &[u8]) -> Result<SubscriptionBatch> {
    let mut input = payload;
    let dimension = take_string(&mut input)?;
    let additions_count = take_u16(&mut input)? as usize;
    let removals_count = take_u16(&mut input)? as usize;
    if additions_count + removals_count > MAX_SUBSCRIPTION_ENTRIES {
        bail!("too many subscription changes");
    }
    let mut additions = Vec::with_capacity(additions_count);
    for _ in 0..additions_count {
        additions.push((take_u64(&mut input)?, take_u64(&mut input)?));
    }
    let mut removals = Vec::with_capacity(removals_count);
    for _ in 0..removals_count {
        removals.push(take_u64(&mut input)?);
    }
    finish(input)?;
    Ok(SubscriptionBatch {
        dimension,
        additions,
        removals,
    })
}

pub fn parse_block_properties(payload: &[u8]) -> Result<Vec<(u32, u8)>> {
    let mut input = payload;
    let count = take_u32(&mut input)? as usize;
    if count > 2048 || input.len() != count.checked_mul(8).context("property count overflow")? {
        bail!("invalid block-properties count");
    }
    let mut values = Vec::with_capacity(count);
    for _ in 0..count {
        let id = take_u32(&mut input)?;
        let opacity = take(&mut input, 1)?[0];
        if take(&mut input, 3)? != [0, 0, 0] || opacity > 15 {
            bail!("invalid block-properties entry");
        }
        values.push((id, opacity));
    }
    Ok(values)
}

pub fn hello(server_instance: u64, flags: u32, block_epoch: u32, biome_epoch: u32) -> Frame {
    let mut payload = Vec::with_capacity(24);
    payload.extend_from_slice(&server_instance.to_le_bytes());
    payload.extend_from_slice(&flags.to_le_bytes());
    payload.push(MAX_LOD);
    payload.extend_from_slice(&[0; 3]);
    payload.extend_from_slice(&block_epoch.to_le_bytes());
    payload.extend_from_slice(&biome_epoch.to_le_bytes());
    Frame {
        kind: S_HELLO,
        payload,
    }
}

pub fn mapping_deltas(
    snapshot: &RegistrySnapshot,
    mut block_start: usize,
    mut biome_start: usize,
) -> Result<Vec<Frame>> {
    if block_start > snapshot.blocks.len() || biome_start > snapshot.biomes.len() {
        bail!("mapping cursor is beyond the registry");
    }
    let mut frames = Vec::new();
    while block_start < snapshot.blocks.len() || biome_start < snapshot.biomes.len() {
        let mut payload = vec![0; 4];
        let first_block = block_start;
        while block_start < snapshot.blocks.len() && block_start - first_block < MAX_MAPPING_ENTRIES
        {
            let entry = &snapshot.blocks[block_start];
            let size = 8 + entry.canonical.len();
            if payload.len() + size + 4 > MAPPING_TARGET && block_start != first_block {
                break;
            }
            payload.extend_from_slice(&(block_start as u32).to_le_bytes());
            payload.push(entry.opacity);
            payload.push(0);
            put_string(&mut payload, &entry.canonical)?;
            block_start += 1;
        }
        payload[..4].copy_from_slice(&((block_start - first_block) as u32).to_le_bytes());
        let biome_count_at = payload.len();
        payload.extend_from_slice(&0u32.to_le_bytes());
        let first_biome = biome_start;
        while biome_start < snapshot.biomes.len()
            && (block_start - first_block) + (biome_start - first_biome) < MAX_MAPPING_ENTRIES
        {
            let name = &snapshot.biomes[biome_start];
            let size = 6 + name.len();
            if payload.len() + size > MAPPING_TARGET && biome_start != first_biome {
                break;
            }
            payload.extend_from_slice(&(biome_start as u32).to_le_bytes());
            put_string(&mut payload, name)?;
            biome_start += 1;
        }
        payload[biome_count_at..biome_count_at + 4]
            .copy_from_slice(&((biome_start - first_biome) as u32).to_le_bytes());
        if payload.len() > MAX_PAYLOAD {
            bail!("one mapping delta exceeds the frame limit");
        }
        frames.push(Frame {
            kind: S_MAPPING_DELTA,
            payload,
        });
    }
    Ok(frames)
}

pub fn section(section: &Section, revision: u64) -> Result<Frame> {
    Ok(Frame {
        kind: S_SECTION,
        payload: section.network_body(revision)?,
    })
}

pub fn invalidate(key: u64, revision: u64, reason: u8) -> Frame {
    let mut payload = Vec::with_capacity(24);
    payload.extend_from_slice(&key.to_le_bytes());
    payload.extend_from_slice(&revision.to_le_bytes());
    payload.push(reason);
    payload.extend_from_slice(&[0; 7]);
    Frame {
        kind: S_INVALIDATE,
        payload,
    }
}

pub fn pong(nonce: u64) -> Frame {
    Frame {
        kind: S_PONG,
        payload: nonce.to_le_bytes().to_vec(),
    }
}

pub fn error(code: u16, message: &str) -> Frame {
    let bytes = message.as_bytes();
    let mut len = bytes.len().min(MAX_STRING);
    while !message.is_char_boundary(len) {
        len -= 1;
    }
    let mut payload = Vec::with_capacity(4 + len);
    payload.extend_from_slice(&code.to_le_bytes());
    payload.extend_from_slice(&(len as u16).to_le_bytes());
    payload.extend_from_slice(&bytes[..len]);
    Frame {
        kind: S_ERROR,
        payload,
    }
}

pub fn parse_nonce(payload: &[u8]) -> Result<u64> {
    if payload.len() != 8 {
        bail!("ping must contain one u64 nonce");
    }
    Ok(u64::from_le_bytes(payload.try_into().unwrap()))
}

pub fn parse_credit(payload: &[u8]) -> Result<u64> {
    parse_nonce(payload)
}

fn put_string(out: &mut Vec<u8>, value: &str) -> Result<()> {
    let bytes = value.as_bytes();
    if bytes.len() > MAX_STRING {
        bail!("protocol string is too long");
    }
    out.extend_from_slice(&(bytes.len() as u16).to_le_bytes());
    out.extend_from_slice(bytes);
    Ok(())
}

fn take<'a>(input: &mut &'a [u8], count: usize) -> Result<&'a [u8]> {
    if input.len() < count {
        bail!("truncated protocol payload");
    }
    let (head, tail) = input.split_at(count);
    *input = tail;
    Ok(head)
}
fn take_u16(input: &mut &[u8]) -> Result<u16> {
    Ok(u16::from_le_bytes(take(input, 2)?.try_into().unwrap()))
}
fn take_u32(input: &mut &[u8]) -> Result<u32> {
    Ok(u32::from_le_bytes(take(input, 4)?.try_into().unwrap()))
}
fn take_u64(input: &mut &[u8]) -> Result<u64> {
    Ok(u64::from_le_bytes(take(input, 8)?.try_into().unwrap()))
}
fn take_string(input: &mut &[u8]) -> Result<String> {
    let len = take_u16(input)? as usize;
    if len > MAX_STRING {
        bail!("protocol string exceeds {MAX_STRING} bytes");
    }
    Ok(std::str::from_utf8(take(input, len)?)?.to_owned())
}
fn finish(input: &[u8]) -> Result<()> {
    if !input.is_empty() {
        bail!("trailing protocol payload bytes");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        key::SectionKey,
        lod::{Cell, SECTION_VOLUME},
        registry::BlockEntry,
    };

    #[test]
    fn subscription_layout_is_frozen() {
        let mut payload = Vec::new();
        put_string(&mut payload, "minecraft:the_nether").unwrap();
        payload.extend_from_slice(&1u16.to_le_bytes());
        payload.extend_from_slice(&1u16.to_le_bytes());
        payload.extend_from_slice(&7u64.to_le_bytes());
        payload.extend_from_slice(&8u64.to_le_bytes());
        payload.extend_from_slice(&9u64.to_le_bytes());
        assert_eq!(
            parse_subscriptions(&payload).unwrap(),
            SubscriptionBatch {
                dimension: "minecraft:the_nether".into(),
                additions: vec![(7, 8)],
                removals: vec![9],
            }
        );
    }

    #[test]
    fn capability_hello_layout_is_frozen() {
        let payload = 1u32.to_le_bytes().to_vec();
        assert_eq!(parse_hello(&payload).unwrap(), 1);
    }

    #[tokio::test]
    async fn frame_crc_round_trip_and_rejects_damage() {
        let frame = Frame {
            kind: C_PING,
            payload: 123u64.to_le_bytes().to_vec(),
        };
        let mut bytes = Vec::new();
        frame.write(&mut bytes).await.unwrap();
        assert_eq!(&bytes[4..6], &PROTOCOL_VERSION.to_le_bytes());
        let decoded = Frame::read(&mut bytes.as_slice()).await.unwrap().unwrap();
        assert_eq!(decoded, frame);
        *bytes.last_mut().unwrap() ^= 1;
        assert!(Frame::read(&mut bytes.as_slice()).await.is_err());
    }

    #[tokio::test]
    async fn blocked_frame_write_reaches_its_deadline() {
        let (mut writer, _reader) = tokio::io::duplex(1);
        let frame = Frame {
            kind: S_SECTION,
            payload: vec![0; 64],
        };
        let error = frame
            .write_with_timeout(&mut writer, Duration::from_millis(20))
            .await
            .unwrap_err();
        assert!(error.to_string().contains("timed out"));
    }

    #[test]
    fn server_payload_sizes_match_the_java_decoder() {
        assert_eq!(hello(1, 0, 2, 3).payload.len(), 24);
        assert_eq!(invalidate(5, 6, 1).payload.len(), 24);

        let key = SectionKey::new(0, 0, 0, 0).unwrap();
        let stored_section = Section::from_cells(
            key,
            vec![
                Cell {
                    block: 1,
                    biome: 2,
                    light: 3
                };
                SECTION_VOLUME
            ],
        )
        .unwrap();
        let frame = section(&stored_section, 8).unwrap();
        // Fixed prefix (24), one 12-byte palette value, and no words for a single value.
        assert_eq!(frame.payload.len(), 36);

        let snapshot = RegistrySnapshot {
            catalog_id: 1,
            generation: 1,
            mip_generation: 0,
            blocks: (0..300)
                .map(|id| BlockEntry {
                    canonical: format!("test:block_{id}"),
                    opacity: 15,
                    authoritative: true,
                })
                .collect(),
            biomes: vec!["minecraft:plains".into()],
        };
        let frames = mapping_deltas(&snapshot, 0, 0).unwrap();
        assert!(frames.len() >= 2);
        for frame in frames {
            let blocks = u32::from_le_bytes(frame.payload[..4].try_into().unwrap()) as usize;
            // This fixture contains biomes only in the last room-bearing frame; total count is
            // nevertheless bounded by the frozen per-tick Java limit.
            assert!(blocks <= 256);
        }
    }
}
