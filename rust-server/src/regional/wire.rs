//! Current-only regional QUIC records.
//!
//! The ALPN and message set intentionally have no legacy branch or negotiated version. Changing
//! this format means changing its magic/ALPN and deploying the matching client and server.

use crate::{take, take_i32, take_u8, take_u16, take_u32, take_u64};
use anyhow::{Context, Result, bail};
use std::collections::HashSet;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const ALPN: &[u8] = b"voxy-region";
pub const STREAM_CONTROL: u8 = 0;
pub const STREAM_SECTION_LANE: u8 = 1;
pub const MAX_DIMENSION_BYTES: usize = 1024;
pub const MAX_CONTROL_PAYLOAD: usize = 64 * 1024 * 1024;
pub const MAX_REGION_INDEX_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_CATALOG_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_SECTION_REQUESTS: usize = 512;
pub const MAX_SECTION_COMPRESSED_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_SECTION_BATCH_BYTES: usize = 64 * 1024 * 1024;

const C_HELLO: u8 = 0x01;
const C_REGION_REQUEST: u8 = 0x02;
const C_CATALOG_REQUEST: u8 = 0x03;
const C_REGION_RELEASE: u8 = 0x04;
const S_HELLO: u8 = 0x81;
const S_REGION: u8 = 0x82;
const S_CATALOG: u8 = 0x83;
const S_REGION_CHANGED: u8 = 0x84;
const S_ERROR: u8 = 0xfe;
const S_SHUTDOWN: u8 = 0xff;
const MAX_ERROR_BYTES: usize = 4096;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum PriorityLane {
    Coverage = 0,
    Refinement = 1,
}

impl TryFrom<u8> for PriorityLane {
    type Error = anyhow::Error;

    fn try_from(value: u8) -> Result<Self> {
        match value {
            0 => Ok(Self::Coverage),
            1 => Ok(Self::Refinement),
            _ => bail!("invalid regional priority lane"),
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ControlMessage {
    Hello {
        dimension: String,
    },
    ServerHello {
        server_instance: u64,
        world_identity: [u8; 32],
        catalog_id: u64,
        catalog_fingerprint: [u8; 32],
    },
    RegionRequest {
        region_x: i32,
        region_z: i32,
    },
    RegionRelease {
        region_x: i32,
        region_z: i32,
    },
    Region {
        region_x: i32,
        region_z: i32,
        generation: u64,
        fingerprint: [u8; 16],
        catalog_fingerprint: [u8; 32],
        compressed: Vec<u8>,
    },
    RegionAbsent {
        region_x: i32,
        region_z: i32,
    },
    CatalogRequest,
    Catalog {
        fingerprint: [u8; 32],
        canonical: Vec<u8>,
    },
    RegionChanged {
        region_x: i32,
        region_z: i32,
        generation: u64,
    },
    Error {
        code: u16,
        message: String,
    },
    Shutdown {
        message: String,
    },
}

pub fn encode_control_record(message: &ControlMessage) -> Result<Vec<u8>> {
    let (kind, payload) = encode_control_payload(message)?;
    if payload.len() > MAX_CONTROL_PAYLOAD {
        bail!("regional control payload exceeds its safety bound");
    }
    let mut output = Vec::with_capacity(5 + payload.len());
    output.push(kind);
    output.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    output.extend_from_slice(&payload);
    Ok(output)
}

pub async fn write_control<W: AsyncWrite + Unpin>(
    output: &mut W,
    message: &ControlMessage,
) -> Result<()> {
    output.write_all(&encode_control_record(message)?).await?;
    Ok(())
}

pub async fn read_control<R: AsyncRead + Unpin>(input: &mut R) -> Result<Option<ControlMessage>> {
    let kind = match input.read_u8().await {
        Ok(kind) => kind,
        Err(error) if error.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    let length = input.read_u32_le().await? as usize;
    if length > MAX_CONTROL_PAYLOAD {
        bail!("regional control payload exceeds its safety bound");
    }
    let mut payload = vec![0; length];
    input.read_exact(&mut payload).await?;
    Ok(Some(decode_control_payload(kind, &payload)?))
}

fn encode_control_payload(message: &ControlMessage) -> Result<(u8, Vec<u8>)> {
    let mut output = Vec::new();
    let kind = match message {
        ControlMessage::Hello { dimension } => {
            put_string(&mut output, dimension, MAX_DIMENSION_BYTES)?;
            C_HELLO
        }
        ControlMessage::ServerHello {
            server_instance,
            world_identity,
            catalog_id,
            catalog_fingerprint,
        } => {
            if *server_instance == 0
                || *catalog_id == 0
                || *world_identity == [0; 32]
                || *catalog_fingerprint == [0; 32]
            {
                bail!("regional server hello identity contains a reserved zero");
            }
            output.extend_from_slice(&server_instance.to_le_bytes());
            output.extend_from_slice(world_identity);
            output.extend_from_slice(&catalog_id.to_le_bytes());
            output.extend_from_slice(catalog_fingerprint);
            S_HELLO
        }
        ControlMessage::RegionRequest { region_x, region_z } => {
            output.extend_from_slice(&region_x.to_le_bytes());
            output.extend_from_slice(&region_z.to_le_bytes());
            C_REGION_REQUEST
        }
        ControlMessage::RegionRelease { region_x, region_z } => {
            output.extend_from_slice(&region_x.to_le_bytes());
            output.extend_from_slice(&region_z.to_le_bytes());
            C_REGION_RELEASE
        }
        ControlMessage::Region {
            region_x,
            region_z,
            generation,
            fingerprint,
            catalog_fingerprint,
            compressed,
        } => {
            if *generation == 0
                || *fingerprint == [0; 16]
                || *catalog_fingerprint == [0; 32]
                || compressed.len() > MAX_REGION_INDEX_BYTES
                || compressed.is_empty()
            {
                bail!("regional response metadata is invalid");
            }
            put_region_identity(&mut output, *region_x, *region_z, *generation);
            output.extend_from_slice(fingerprint);
            output.extend_from_slice(catalog_fingerprint);
            output.extend_from_slice(&(compressed.len() as u32).to_le_bytes());
            output.extend_from_slice(compressed);
            S_REGION
        }
        ControlMessage::RegionAbsent { region_x, region_z } => {
            put_region_identity(&mut output, *region_x, *region_z, 0);
            S_REGION
        }
        ControlMessage::CatalogRequest => C_CATALOG_REQUEST,
        ControlMessage::Catalog {
            fingerprint,
            canonical,
        } => {
            if *fingerprint == [0; 32]
                || canonical.is_empty()
                || canonical.len() > MAX_CATALOG_BYTES
            {
                bail!("regional catalog response metadata is invalid");
            }
            output.extend_from_slice(fingerprint);
            output.extend_from_slice(&(canonical.len() as u32).to_le_bytes());
            output.extend_from_slice(canonical);
            S_CATALOG
        }
        ControlMessage::RegionChanged {
            region_x,
            region_z,
            generation,
        } => {
            put_region_identity(&mut output, *region_x, *region_z, *generation);
            S_REGION_CHANGED
        }
        ControlMessage::Error { code, message } => {
            if *code == 0 {
                bail!("regional error code zero is reserved");
            }
            output.extend_from_slice(&code.to_le_bytes());
            put_string(&mut output, message, MAX_ERROR_BYTES)?;
            S_ERROR
        }
        ControlMessage::Shutdown { message } => {
            put_string(&mut output, message, MAX_ERROR_BYTES)?;
            S_SHUTDOWN
        }
    };
    Ok((kind, output))
}

fn decode_control_payload(kind: u8, bytes: &[u8]) -> Result<ControlMessage> {
    let mut input = bytes;
    let message = match kind {
        C_HELLO => ControlMessage::Hello {
            dimension: take_string(&mut input, MAX_DIMENSION_BYTES)?,
        },
        S_HELLO => {
            let server_instance = take_u64(&mut input)?;
            let world_identity = take(&mut input, 32)?.try_into().unwrap();
            let catalog_id = take_u64(&mut input)?;
            let catalog_fingerprint = take(&mut input, 32)?.try_into().unwrap();
            let message = ControlMessage::ServerHello {
                server_instance,
                world_identity,
                catalog_id,
                catalog_fingerprint,
            };
            encode_control_payload(&message)?;
            message
        }
        C_REGION_REQUEST => ControlMessage::RegionRequest {
            region_x: take_i32(&mut input)?,
            region_z: take_i32(&mut input)?,
        },
        C_REGION_RELEASE => ControlMessage::RegionRelease {
            region_x: take_i32(&mut input)?,
            region_z: take_i32(&mut input)?,
        },
        S_REGION => {
            let region_x = take_i32(&mut input)?;
            let region_z = take_i32(&mut input)?;
            let generation = take_u64(&mut input)?;
            if generation == 0 {
                ControlMessage::RegionAbsent { region_x, region_z }
            } else {
                let fingerprint = take(&mut input, 16)?.try_into().unwrap();
                let catalog_fingerprint = take(&mut input, 32)?.try_into().unwrap();
                let length = take_u32(&mut input)? as usize;
                if length > MAX_REGION_INDEX_BYTES {
                    bail!("regional response exceeds its safety bound");
                }
                let compressed = take(&mut input, length)?.to_vec();
                let message = ControlMessage::Region {
                    region_x,
                    region_z,
                    generation,
                    fingerprint,
                    catalog_fingerprint,
                    compressed,
                };
                encode_control_payload(&message)?;
                message
            }
        }
        C_CATALOG_REQUEST => ControlMessage::CatalogRequest,
        S_CATALOG => {
            let fingerprint = take(&mut input, 32)?.try_into().unwrap();
            let length = take_u32(&mut input)? as usize;
            if length > MAX_CATALOG_BYTES {
                bail!("regional catalog response exceeds its safety bound");
            }
            let canonical = take(&mut input, length)?.to_vec();
            let message = ControlMessage::Catalog {
                fingerprint,
                canonical,
            };
            encode_control_payload(&message)?;
            message
        }
        S_REGION_CHANGED => ControlMessage::RegionChanged {
            region_x: take_i32(&mut input)?,
            region_z: take_i32(&mut input)?,
            generation: take_u64(&mut input)?,
        },
        S_ERROR => ControlMessage::Error {
            code: take_u16(&mut input)?,
            message: take_string(&mut input, MAX_ERROR_BYTES)?,
        },
        S_SHUTDOWN => ControlMessage::Shutdown {
            message: take_string(&mut input, MAX_ERROR_BYTES)?,
        },
        _ => bail!("unknown regional control record type {kind:#04x}"),
    };
    if !input.is_empty() {
        bail!("trailing regional control payload bytes");
    }
    Ok(message)
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SectionRequestBatch {
    pub epoch: u64,
    pub region_x: i32,
    pub region_z: i32,
    pub generation: u64,
    pub ordinals: Vec<u32>,
}

impl SectionRequestBatch {
    pub fn encode(&self) -> Result<Vec<u8>> {
        if self.epoch == 0
            || self.generation == 0
            || self.ordinals.is_empty()
            || self.ordinals.len() > MAX_SECTION_REQUESTS
        {
            bail!("invalid regional section request batch bounds");
        }
        let mut output = Vec::with_capacity(26 + self.ordinals.len() * 4);
        output.extend_from_slice(&self.epoch.to_le_bytes());
        output.extend_from_slice(&self.region_x.to_le_bytes());
        output.extend_from_slice(&self.region_z.to_le_bytes());
        output.extend_from_slice(&self.generation.to_le_bytes());
        output.extend_from_slice(&(self.ordinals.len() as u16).to_le_bytes());
        let mut unique = HashSet::with_capacity(self.ordinals.len());
        for ordinal in &self.ordinals {
            if !unique.insert(*ordinal) {
                bail!("duplicate section in one regional request batch");
            }
            output.extend_from_slice(&ordinal.to_le_bytes());
        }
        Ok(output)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self> {
        let mut input = bytes;
        let epoch = take_u64(&mut input)?;
        let region_x = take_i32(&mut input)?;
        let region_z = take_i32(&mut input)?;
        let generation = take_u64(&mut input)?;
        let count = take_u16(&mut input)? as usize;
        if epoch == 0
            || generation == 0
            || count == 0
            || count > MAX_SECTION_REQUESTS
            || input.len() != count * 4
        {
            bail!("invalid regional section request batch bounds");
        }
        let mut ordinals = Vec::with_capacity(count);
        for _ in 0..count {
            ordinals.push(take_u32(&mut input)?);
        }
        let batch = Self {
            epoch,
            region_x,
            region_z,
            generation,
            ordinals,
        };
        batch.encode()?;
        Ok(batch)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum SectionReplyStatus {
    StaleGeneration = 0,
    Absent = 1,
    Empty = 2,
    Data = 3,
}

impl TryFrom<u8> for SectionReplyStatus {
    type Error = anyhow::Error;

    fn try_from(value: u8) -> Result<Self> {
        match value {
            0 => Ok(Self::StaleGeneration),
            1 => Ok(Self::Absent),
            2 => Ok(Self::Empty),
            3 => Ok(Self::Data),
            _ => bail!("invalid regional section reply status"),
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SectionReply {
    pub status: SectionReplyStatus,
    pub compressed: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SectionReplyBatch {
    pub epoch: u64,
    pub start: u16,
    pub replies: Vec<SectionReply>,
}

impl SectionReplyBatch {
    pub fn encode(&self) -> Result<Vec<u8>> {
        if self.epoch == 0 || self.replies.is_empty() || self.replies.len() > MAX_SECTION_REQUESTS {
            bail!("invalid regional section reply batch bounds");
        }
        let body_bytes = self.replies.iter().try_fold(0usize, |total, reply| {
            validate_reply(reply)?;
            total
                .checked_add(reply.compressed.len())
                .context("regional section reply body size overflow")
        })?;
        if usize::from(self.start) + self.replies.len() > MAX_SECTION_REQUESTS {
            bail!("regional section reply range exceeds its request");
        }
        let length = 12usize
            .checked_add(self.replies.len())
            .and_then(|length| length.checked_add(body_bytes))
            .context("regional section reply frame size overflow")?;
        if length > MAX_SECTION_BATCH_BYTES {
            bail!("regional section reply frame exceeds its safety bound");
        }
        let mut output = Vec::with_capacity(length);
        output.extend_from_slice(&self.epoch.to_le_bytes());
        output.extend_from_slice(&self.start.to_le_bytes());
        output.extend_from_slice(&(self.replies.len() as u16).to_le_bytes());
        for reply in &self.replies {
            output.push(reply.status as u8);
        }
        for reply in &self.replies {
            output.extend_from_slice(&reply.compressed);
        }
        debug_assert_eq!(output.len(), length);
        Ok(output)
    }

    pub fn decode(bytes: &[u8], compressed_lengths: &[u32]) -> Result<Self> {
        if bytes.len() > MAX_SECTION_BATCH_BYTES {
            bail!("regional section reply frame exceeds its safety bound");
        }
        let mut input = bytes;
        let epoch = take_u64(&mut input)?;
        let start = take_u16(&mut input)?;
        let count = take_u16(&mut input)? as usize;
        if epoch == 0
            || count == 0
            || count > MAX_SECTION_REQUESTS
            || usize::from(start) + count > MAX_SECTION_REQUESTS
            || compressed_lengths.len() != count
            || input.len() < count
        {
            bail!("invalid regional section reply batch bounds");
        }
        let mut statuses = Vec::with_capacity(count);
        for _ in 0..count {
            let status = SectionReplyStatus::try_from(take_u8(&mut input)?)?;
            statuses.push(status);
        }
        let mut replies = Vec::with_capacity(count);
        for (status, &indexed_length) in statuses.into_iter().zip(compressed_lengths) {
            let length = if status == SectionReplyStatus::Data {
                indexed_length as usize
            } else {
                0
            };
            if length > MAX_SECTION_COMPRESSED_BYTES {
                bail!("regional section reply body exceeds its safety bound");
            }
            let reply = SectionReply {
                status,
                compressed: take(&mut input, length)?.to_vec(),
            };
            validate_reply(&reply)?;
            replies.push(reply);
        }
        if !input.is_empty() {
            bail!("trailing regional section reply bytes");
        }
        Ok(Self {
            epoch,
            start,
            replies,
        })
    }
}

fn validate_reply(reply: &SectionReply) -> Result<()> {
    if reply.compressed.len() > MAX_SECTION_COMPRESSED_BYTES {
        bail!("regional section reply bounds are invalid");
    }
    match reply.status {
        SectionReplyStatus::Data => {
            if reply.compressed.is_empty() {
                bail!("regional data reply metadata is invalid");
            }
        }
        SectionReplyStatus::Empty => {
            if !reply.compressed.is_empty() {
                bail!("regional empty reply metadata is invalid");
            }
        }
        SectionReplyStatus::StaleGeneration | SectionReplyStatus::Absent => {
            if !reply.compressed.is_empty() {
                bail!("regional terminal reply contains section metadata");
            }
        }
    }
    Ok(())
}

pub async fn write_reply_batch<W: AsyncWrite + Unpin>(
    output: &mut W,
    batch: &SectionReplyBatch,
) -> Result<()> {
    let bytes = batch.encode()?;
    output.write_u32_le(bytes.len() as u32).await?;
    output.write_all(&bytes).await?;
    Ok(())
}

fn put_region_identity(output: &mut Vec<u8>, x: i32, z: i32, generation: u64) {
    output.extend_from_slice(&x.to_le_bytes());
    output.extend_from_slice(&z.to_le_bytes());
    output.extend_from_slice(&generation.to_le_bytes());
}

pub async fn write_stream_role<W: AsyncWrite + Unpin>(output: &mut W, role: u8) -> Result<()> {
    if role != STREAM_CONTROL && role != STREAM_SECTION_LANE {
        bail!("invalid regional stream role");
    }
    output.write_u8(role).await?;
    Ok(())
}

pub async fn read_stream_role<R: AsyncRead + Unpin>(input: &mut R) -> Result<Option<u8>> {
    match input.read_u8().await {
        Ok(role) if role == STREAM_CONTROL || role == STREAM_SECTION_LANE => Ok(Some(role)),
        Ok(_) => bail!("invalid regional stream role"),
        Err(error) if error.kind() == std::io::ErrorKind::UnexpectedEof => Ok(None),
        Err(error) => Err(error.into()),
    }
}

pub async fn write_lane<W: AsyncWrite + Unpin>(output: &mut W, lane: PriorityLane) -> Result<()> {
    output.write_u8(lane as u8).await?;
    Ok(())
}

pub async fn read_lane<R: AsyncRead + Unpin>(input: &mut R) -> Result<PriorityLane> {
    PriorityLane::try_from(input.read_u8().await?)
}

pub async fn write_request_batch<W: AsyncWrite + Unpin>(
    output: &mut W,
    batch: &SectionRequestBatch,
) -> Result<()> {
    let bytes = batch.encode()?;
    output.write_u32_le(bytes.len() as u32).await?;
    output.write_all(&bytes).await?;
    Ok(())
}

pub async fn read_request_batch<R: AsyncRead + Unpin>(
    input: &mut R,
) -> Result<Option<SectionRequestBatch>> {
    let length = match input.read_u32_le().await {
        Ok(length) => length as usize,
        Err(error) if error.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    let maximum = 26 + MAX_SECTION_REQUESTS * 4;
    if length > maximum {
        bail!("regional section request frame exceeds its safety bound");
    }
    let mut bytes = vec![0; length];
    input.read_exact(&mut bytes).await?;
    Ok(Some(SectionRequestBatch::decode(&bytes)?))
}

fn put_string(output: &mut Vec<u8>, value: &str, maximum: usize) -> Result<()> {
    let bytes = value.as_bytes();
    if bytes.is_empty() || bytes.len() > maximum || bytes.len() > u16::MAX as usize {
        bail!("regional string length is outside its bound");
    }
    output.extend_from_slice(&(bytes.len() as u16).to_le_bytes());
    output.extend_from_slice(bytes);
    Ok(())
}

fn take_string(input: &mut &[u8], maximum: usize) -> Result<String> {
    let length = take_u16(input)? as usize;
    if length == 0 || length > maximum {
        bail!("regional string length is outside its bound");
    }
    std::str::from_utf8(take(input, length)?)
        .context("regional string is not UTF-8")
        .map(ToOwned::to_owned)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_control_record_round_trips_without_negotiation() {
        let messages = vec![
            ControlMessage::Hello {
                dimension: "minecraft:overworld".into(),
            },
            ControlMessage::ServerHello {
                server_instance: 1,
                world_identity: [2; 32],
                catalog_id: 3,
                catalog_fingerprint: [4; 32],
            },
            ControlMessage::RegionRequest {
                region_x: -2,
                region_z: 3,
            },
            ControlMessage::RegionRelease {
                region_x: -2,
                region_z: 3,
            },
            ControlMessage::Region {
                region_x: -2,
                region_z: 3,
                generation: 7,
                fingerprint: [3; 16],
                catalog_fingerprint: [4; 32],
                compressed: vec![1, 2, 3],
            },
            ControlMessage::RegionAbsent {
                region_x: 8,
                region_z: -9,
            },
            ControlMessage::CatalogRequest,
            ControlMessage::Catalog {
                fingerprint: [6; 32],
                canonical: vec![9],
            },
            ControlMessage::RegionChanged {
                region_x: 1,
                region_z: 2,
                generation: 8,
            },
            ControlMessage::Error {
                code: 2,
                message: "bad request".into(),
            },
            ControlMessage::Shutdown {
                message: "stopping".into(),
            },
        ];
        for expected in messages {
            let encoded = encode_control_record(&expected).unwrap();
            let length = u32::from_le_bytes(encoded[1..5].try_into().unwrap()) as usize;
            assert_eq!(encoded.len(), length + 5);
            assert_eq!(
                decode_control_payload(encoded[0], &encoded[5..]).unwrap(),
                expected
            );
        }
    }

    #[test]
    fn section_request_batch_round_trips_and_rejects_duplicates() {
        let batch = SectionRequestBatch {
            epoch: 11,
            region_x: -2,
            region_z: 3,
            generation: 7,
            ordinals: vec![41],
        };
        assert_eq!(
            SectionRequestBatch::decode(&batch.encode().unwrap()).unwrap(),
            batch
        );
        assert!(
            SectionRequestBatch {
                epoch: 11,
                region_x: -2,
                region_z: 3,
                generation: 7,
                ordinals: vec![41, 41],
            }
            .encode()
            .is_err()
        );
    }

    #[test]
    fn section_reply_batch_round_trips_complete_compressed_records() {
        let compressed = vec![1, 2, 3, 4];
        let reply = SectionReply {
            status: SectionReplyStatus::Data,
            compressed,
        };
        let batch = SectionReplyBatch {
            epoch: 9,
            start: 3,
            replies: vec![reply],
        };
        assert_eq!(batch.encode().unwrap().len(), 12 + 1 + 4);
        assert_eq!(
            SectionReplyBatch::decode(&batch.encode().unwrap(), &[4]).unwrap(),
            batch
        );
    }
}
