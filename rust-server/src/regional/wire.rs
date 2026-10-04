//! Matched client/server spatial desires and self-described streamed records.
use crate::{key::SectionKey, take, take_u8, take_u16, take_u32, take_u64};
use anyhow::{Context, Result, bail};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const ALPN: &[u8] = b"voxy-region-cache-start";
pub const STREAM_CONTROL: u8 = 0;
pub const STREAM_SECTION_LANE: u8 = 1;
pub const STREAM_BACKGROUND: u8 = 2;
pub const MAX_DIMENSION_BYTES: usize = 1024;
pub const MAX_CATALOG_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_SECTION_COMPRESSED_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_SECTION_REQUESTS: usize = u16::MAX as usize;
pub const RECORD_DESCRIPTOR_BYTES: usize = 88;
pub const S_RECORD: u8 = 0x85;

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
            _ => bail!("invalid terrain lane"),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct StreamingSettings {
    pub interval_millis: u64,
    pub bandwidth_kbps: u64,
}
impl StreamingSettings {
    pub fn validate(self) -> Result<()> {
        if self.interval_millis < 1000 {
            bail!("terrain update interval must be at least one second");
        }
        self.bandwidth_kbps
            .checked_mul(125)
            .ok_or_else(|| anyhow::anyhow!("background rate overflow"))?;
        if self.interval_millis > u64::MAX / 1_000_000 {
            bail!("terrain interval overflow");
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct ContentBinding {
    pub flags: u16,
    pub children: u8,
    pub catalog_fingerprint: [u8; 32],
    pub fingerprint: [u8; 16],
    pub compressed_length: u32,
    pub canonical_length: u32,
    pub compressed_crc: u32,
}
impl ContentBinding {
    pub fn has_body(self) -> bool {
        self.compressed_length != 0
    }
    pub fn same_body(self, other: Self) -> bool {
        self.catalog_fingerprint == other.catalog_fingerprint
            && self.fingerprint == other.fingerprint
            && self.compressed_length == other.compressed_length
            && self.canonical_length == other.canonical_length
            && self.compressed_crc == other.compressed_crc
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Desire {
    pub ticket: u64,
    pub key: u64,
    /// Coverage/refinement are misses; interest-only keeps adequate cached terrain current.
    pub purpose: u8,
    pub have: Option<ContentBinding>,
}
impl Desire {
    fn validate(self) -> Result<()> {
        if self.ticket == 0 || self.purpose > 2 {
            bail!("invalid terrain desire");
        }
        SectionKey::unpack(self.key)?;
        if let Some(have) = self.have {
            if have.flags & !0x8001 != 0
                || have.compressed_length as usize > MAX_SECTION_COMPRESSED_BYTES
            {
                bail!("invalid terrain holding");
            }
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ControlMessage {
    Open {
        dimension: String,
        expected_world: [u8; 32],
        held_catalog: [u8; 32],
        settings: StreamingSettings,
        desires: Vec<Desire>,
    },
    Desires(Vec<Desire>),
    Drop(Vec<u64>),
    Settings(StreamingSettings),
    ServerHello {
        server_instance: u64,
        world_identity: [u8; 32],
        catalog_id: u64,
        catalog_fingerprint: [u8; 32],
        background_token: [u8; 32],
    },
    Catalog {
        fingerprint: [u8; 32],
        canonical_length: u32,
        compressed: Vec<u8>,
    },
    Record {
        descriptor: RecordDescriptor,
        compressed: Vec<u8>,
    },
    Error {
        code: u16,
        message: String,
    },
    Shutdown {
        message: String,
    },
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum RecordStatus {
    NotReady = 0,
    Absent = 1,
    Empty = 2,
    Data = 3,
    Reuse = 4,
}
impl TryFrom<u8> for RecordStatus {
    type Error = anyhow::Error;
    fn try_from(value: u8) -> Result<Self> {
        match value {
            0 => Ok(Self::NotReady),
            1 => Ok(Self::Absent),
            2 => Ok(Self::Empty),
            3 => Ok(Self::Data),
            4 => Ok(Self::Reuse),
            _ => bail!("invalid terrain record status"),
        }
    }
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RecordDescriptor {
    pub ticket: u64,
    pub key: u64,
    pub generation: u64,
    pub status: RecordStatus,
    pub binding: ContentBinding,
}
impl RecordDescriptor {
    pub fn encode(self) -> Result<[u8; RECORD_DESCRIPTOR_BYTES]> {
        if self.ticket == 0 {
            bail!("zero terrain ownership ticket");
        }
        SectionKey::unpack(self.key)?;
        if self.binding.compressed_length as usize > MAX_SECTION_COMPRESSED_BYTES {
            bail!("oversized terrain record");
        }
        let mut out = Vec::with_capacity(RECORD_DESCRIPTOR_BYTES);
        out.extend_from_slice(&self.ticket.to_le_bytes());
        out.extend_from_slice(&self.key.to_le_bytes());
        out.extend_from_slice(&self.generation.to_le_bytes());
        out.push(self.status as u8);
        put_binding(&mut out, self.binding);
        Ok(out.try_into().expect("fixed terrain descriptor layout"))
    }
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        let mut input = bytes;
        let descriptor = Self {
            ticket: take_u64(&mut input)?,
            key: take_u64(&mut input)?,
            generation: take_u64(&mut input)?,
            status: RecordStatus::try_from(take_u8(&mut input)?)?,
            binding: take_binding(&mut input)?,
        };
        if !input.is_empty() {
            bail!("trailing descriptor bytes");
        }
        descriptor.encode()?;
        Ok(descriptor)
    }
}

pub fn encode_control_record(message: &ControlMessage) -> Result<Vec<u8>> {
    let mut payload = Vec::new();
    let kind = match message {
        ControlMessage::Open {
            dimension,
            expected_world,
            held_catalog,
            settings,
            desires,
        } => {
            put_string(&mut payload, dimension, MAX_DIMENSION_BYTES)?;
            payload.extend_from_slice(expected_world);
            payload.extend_from_slice(held_catalog);
            put_settings(&mut payload, *settings)?;
            put_desires(&mut payload, desires)?;
            0x01
        }
        ControlMessage::Desires(desires) => {
            put_desires(&mut payload, desires)?;
            0x02
        }
        ControlMessage::Drop(keys) => {
            put_count(&mut payload, keys.len())?;
            for key in keys {
                SectionKey::unpack(*key)?;
                payload.extend_from_slice(&key.to_le_bytes());
            }
            0x04
        }
        ControlMessage::Settings(settings) => {
            put_settings(&mut payload, *settings)?;
            0x05
        }
        ControlMessage::ServerHello {
            server_instance,
            world_identity,
            catalog_id,
            catalog_fingerprint,
            background_token,
        } => {
            if *server_instance == 0 || *catalog_id == 0 || *world_identity == [0; 32] {
                bail!("invalid server identity");
            }
            payload.extend_from_slice(&server_instance.to_le_bytes());
            payload.extend_from_slice(world_identity);
            payload.extend_from_slice(&catalog_id.to_le_bytes());
            payload.extend_from_slice(catalog_fingerprint);
            payload.extend_from_slice(background_token);
            0x81
        }
        ControlMessage::Catalog {
            fingerprint,
            canonical_length,
            compressed,
        } => {
            validate_catalog(*fingerprint, *canonical_length, compressed.len())?;
            payload.extend_from_slice(fingerprint);
            payload.extend_from_slice(&canonical_length.to_le_bytes());
            payload.extend_from_slice(&(compressed.len() as u32).to_le_bytes());
            payload.extend_from_slice(compressed);
            0x83
        }
        ControlMessage::Record {
            descriptor,
            compressed,
        } => {
            payload.extend_from_slice(&descriptor.encode()?);
            if descriptor.status == RecordStatus::Data {
                if compressed.len() != descriptor.binding.compressed_length as usize {
                    bail!("record body length mismatch");
                }
                payload.extend_from_slice(compressed);
            } else if !compressed.is_empty() {
                bail!("body on descriptor-only record");
            }
            S_RECORD
        }
        ControlMessage::Error { code, message } => {
            if *code == 0 {
                bail!("zero error code");
            }
            payload.extend_from_slice(&code.to_le_bytes());
            put_string(&mut payload, message, 4096)?;
            0xfe
        }
        ControlMessage::Shutdown { message } => {
            put_string(&mut payload, message, 4096)?;
            0xff
        }
    };
    if payload.len() > maximum_payload(kind)? {
        bail!("oversized control frame");
    }
    let mut out = Vec::with_capacity(5 + payload.len());
    out.push(kind);
    out.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    out.extend_from_slice(&payload);
    Ok(out)
}

pub async fn read_control<R: AsyncRead + Unpin>(input: &mut R) -> Result<Option<ControlMessage>> {
    let kind = match input.read_u8().await {
        Ok(value) => value,
        Err(error) if error.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    let length = input.read_u32_le().await? as usize;
    if length > maximum_payload(kind)? {
        bail!("control length exceeds its structural frame shape");
    }
    let mut bytes = vec![0; length];
    input.read_exact(&mut bytes).await?;
    decode_control_payload(kind, &bytes).map(Some)
}

fn maximum_payload(kind: u8) -> Result<usize> {
    Ok(match kind {
        0x01 => 2 + MAX_DIMENSION_BYTES + 64 + 16 + 2 + MAX_SECTION_REQUESTS * 81,
        0x02 => 2 + MAX_SECTION_REQUESTS * 81,
        0x04 => 2 + MAX_SECTION_REQUESTS * 8,
        0x05 => 16,
        0x81 => 112,
        0x83 => zstd::zstd_safe::compress_bound(MAX_CATALOG_BYTES)
            .checked_add(40)
            .context("catalog frame extent overflow")?,
        S_RECORD => RECORD_DESCRIPTOR_BYTES + MAX_SECTION_COMPRESSED_BYTES,
        0xfe => 4100,
        0xff => 4098,
        _ => bail!("unknown control record {kind:#04x}"),
    })
}

fn validate_catalog(fingerprint: [u8; 32], canonical_length: u32, compressed: usize) -> Result<()> {
    if fingerprint == [0; 32]
        || canonical_length == 0
        || canonical_length as usize > MAX_CATALOG_BYTES
        || compressed == 0
        || compressed > zstd::zstd_safe::compress_bound(canonical_length as usize)
    {
        bail!("invalid compressed catalog extent");
    }
    Ok(())
}

pub fn decode_control_payload(kind: u8, bytes: &[u8]) -> Result<ControlMessage> {
    let mut input = bytes;
    let message = match kind {
        0x01 => ControlMessage::Open {
            dimension: take_string(&mut input, MAX_DIMENSION_BYTES)?,
            expected_world: take(&mut input, 32)?.try_into().unwrap(),
            held_catalog: take(&mut input, 32)?.try_into().unwrap(),
            settings: take_settings(&mut input)?,
            desires: take_desires(&mut input)?,
        },
        0x02 => ControlMessage::Desires(take_desires(&mut input)?),
        0x04 => {
            let count = take_u16(&mut input)? as usize;
            if count > input.len() / 8 {
                bail!("truncated releases");
            }
            let mut keys = Vec::with_capacity(count);
            for _ in 0..count {
                let key = take_u64(&mut input)?;
                SectionKey::unpack(key)?;
                keys.push(key);
            }
            ControlMessage::Drop(keys)
        }
        0x05 => ControlMessage::Settings(take_settings(&mut input)?),
        0x81 => ControlMessage::ServerHello {
            server_instance: take_u64(&mut input)?,
            world_identity: take(&mut input, 32)?.try_into().unwrap(),
            catalog_id: take_u64(&mut input)?,
            catalog_fingerprint: take(&mut input, 32)?.try_into().unwrap(),
            background_token: take(&mut input, 32)?.try_into().unwrap(),
        },
        0x83 => {
            let fingerprint = take(&mut input, 32)?.try_into().unwrap();
            let canonical_length = take_u32(&mut input)?;
            let size = take_u32(&mut input)? as usize;
            validate_catalog(fingerprint, canonical_length, size)?;
            ControlMessage::Catalog {
                fingerprint,
                canonical_length,
                compressed: take(&mut input, size)?.to_vec(),
            }
        }
        S_RECORD => {
            let descriptor = RecordDescriptor::decode(take(&mut input, RECORD_DESCRIPTOR_BYTES)?)?;
            let compressed = if descriptor.status == RecordStatus::Data {
                take(&mut input, descriptor.binding.compressed_length as usize)?.to_vec()
            } else {
                Vec::new()
            };
            ControlMessage::Record {
                descriptor,
                compressed,
            }
        }
        0xfe => ControlMessage::Error {
            code: take_u16(&mut input)?,
            message: take_string(&mut input, 4096)?,
        },
        0xff => ControlMessage::Shutdown {
            message: take_string(&mut input, 4096)?,
        },
        _ => bail!("unknown control record {kind:#04x}"),
    };
    if !input.is_empty() {
        bail!("trailing control payload bytes");
    }
    Ok(message)
}

pub async fn write_record<W: AsyncWrite + Unpin>(
    out: &mut W,
    descriptor: RecordDescriptor,
    body: &[u8],
) -> Result<()> {
    let header = descriptor.encode()?;
    let size = if descriptor.status == RecordStatus::Data {
        descriptor.binding.compressed_length as usize
    } else {
        0
    };
    if size != body.len() {
        bail!("terrain body size mismatch");
    }
    out.write_u8(S_RECORD).await?;
    out.write_u32_le((header.len() + body.len()) as u32).await?;
    out.write_all(&header).await?;
    out.write_all(body).await?;
    Ok(())
}

pub async fn read_stream_role<R: AsyncRead + Unpin>(input: &mut R) -> Result<Option<u8>> {
    match input.read_u8().await {
        Ok(role) => Ok(Some(role)),
        Err(error) if error.kind() == std::io::ErrorKind::UnexpectedEof => Ok(None),
        Err(error) => Err(error.into()),
    }
}
pub async fn read_lane<R: AsyncRead + Unpin>(input: &mut R) -> Result<PriorityLane> {
    PriorityLane::try_from(input.read_u8().await?)
}

fn put_count(out: &mut Vec<u8>, count: usize) -> Result<()> {
    let count: u16 = count
        .try_into()
        .map_err(|_| anyhow::anyhow!("too many entries for one frame"))?;
    out.extend_from_slice(&count.to_le_bytes());
    Ok(())
}
fn put_string(out: &mut Vec<u8>, value: &str, maximum: usize) -> Result<()> {
    if value.is_empty() || value.len() > maximum {
        bail!("invalid string length");
    }
    put_count(out, value.len())?;
    out.extend_from_slice(value.as_bytes());
    Ok(())
}
fn take_string(input: &mut &[u8], maximum: usize) -> Result<String> {
    let size = take_u16(input)? as usize;
    if size == 0 || size > maximum {
        bail!("invalid string length");
    }
    Ok(std::str::from_utf8(take(input, size)?)?.to_owned())
}
fn put_settings(out: &mut Vec<u8>, settings: StreamingSettings) -> Result<()> {
    settings.validate()?;
    out.extend_from_slice(&settings.interval_millis.to_le_bytes());
    out.extend_from_slice(&settings.bandwidth_kbps.to_le_bytes());
    Ok(())
}
fn take_settings(input: &mut &[u8]) -> Result<StreamingSettings> {
    let settings = StreamingSettings {
        interval_millis: take_u64(input)?,
        bandwidth_kbps: take_u64(input)?,
    };
    settings.validate()?;
    Ok(settings)
}
fn put_binding(out: &mut Vec<u8>, binding: ContentBinding) {
    out.extend_from_slice(&binding.flags.to_le_bytes());
    out.push(binding.children);
    out.extend_from_slice(&binding.catalog_fingerprint);
    out.extend_from_slice(&binding.fingerprint);
    out.extend_from_slice(&binding.compressed_length.to_le_bytes());
    out.extend_from_slice(&binding.canonical_length.to_le_bytes());
    out.extend_from_slice(&binding.compressed_crc.to_le_bytes());
}
fn take_binding(input: &mut &[u8]) -> Result<ContentBinding> {
    Ok(ContentBinding {
        flags: take_u16(input)?,
        children: take_u8(input)?,
        catalog_fingerprint: take(input, 32)?.try_into().unwrap(),
        fingerprint: take(input, 16)?.try_into().unwrap(),
        compressed_length: take_u32(input)?,
        canonical_length: take_u32(input)?,
        compressed_crc: take_u32(input)?,
    })
}
fn put_desires(out: &mut Vec<u8>, desires: &[Desire]) -> Result<()> {
    put_count(out, desires.len())?;
    for desire in desires {
        desire.validate()?;
        out.extend_from_slice(&desire.ticket.to_le_bytes());
        out.extend_from_slice(&desire.key.to_le_bytes());
        out.push(desire.purpose);
        out.push(u8::from(desire.have.is_some()));
        if let Some(have) = desire.have {
            put_binding(out, have);
        }
    }
    Ok(())
}
fn take_desires(input: &mut &[u8]) -> Result<Vec<Desire>> {
    let count = take_u16(input)? as usize;
    if count > input.len() / 18 {
        bail!("truncated desires");
    }
    let mut desires = Vec::with_capacity(count);
    for _ in 0..count {
        let ticket = take_u64(input)?;
        let key = take_u64(input)?;
        let purpose = take_u8(input)?;
        let have = match take_u8(input)? {
            0 => None,
            1 => Some(take_binding(input)?),
            _ => bail!("invalid holding marker"),
        };
        let desire = Desire {
            ticket,
            key,
            purpose,
            have,
        };
        desire.validate()?;
        desires.push(desire);
    }
    Ok(desires)
}
