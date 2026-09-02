//! QUIC application records.
//!
//! Every client-initiated bidirectional stream starts with one role byte. Role zero is the
//! connection's single permanent control stream; role one is a short-lived object request.
//! Control records are `u8 type, u16 little-endian payload length, payload`. Object streams use
//! the fixed request/response layouts encoded below. QUIC supplies integrity, flow control,
//! ordering, liveness, and cancellation, so this layer has no frame CRC, credit, ping, or
//! correlation protocol.

pub use super::pack::{MAX_CANONICAL_OBJECT_BYTES, MAX_COMPRESSED_OBJECT_BYTES};
use super::{
    object::{ObjectHash, ObjectKind},
    root::RootRecord,
};
use crate::{take, take_i32, take_u16, take_u64};
use anyhow::{Context, Result, bail};
use std::collections::HashSet;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const ALPN: &[u8] = b"voxy";
pub const STREAM_CONTROL: u8 = 0;
pub const STREAM_OBJECT_REQUEST: u8 = 1;

pub const C_HELLO: u8 = 0x01;
pub const C_ROOT_READY: u8 = 0x02;
pub const C_CAMERA_DOMAIN: u8 = 0x03;
pub const S_HELLO: u8 = 0x81;
pub const S_ROOT_ANNOUNCE: u8 = 0x82;
pub const S_CAMERA_DOMAIN: u8 = 0x83;
pub const S_SHUTDOWN: u8 = 0xfe;
pub const S_ERROR: u8 = 0xff;

pub const OBJECT_RESPONSE_OK: u8 = 0;
pub const OBJECT_RESPONSE_ERROR: u8 = 1;
pub const HASH_BYTES: usize = 32;
pub const ROOT_TOKEN_BYTES: usize = 8 + 2 * HASH_BYTES;
pub const OBJECT_RESPONSE_HEADER_BYTES: usize = HASH_BYTES + 1 + 4 * 4;
pub const MAX_DIMENSION_BYTES: usize = 1024;
pub const MAX_CONTROL_PAYLOAD: usize = 4096;
pub const MAX_REQUEST_ENTRIES: usize = 256;
pub const MAX_STREAM_COMPRESSED_BYTES: usize = MAX_COMPRESSED_OBJECT_BYTES;
pub const MAX_STREAM_CANONICAL_BYTES: usize = MAX_CANONICAL_OBJECT_BYTES;
const MAX_ERROR_STRING: usize = 1024;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum PriorityLane {
    Coverage = 0,
    Current = 1,
    Predicted = 2,
}

impl PriorityLane {
    pub const ALL: [Self; 3] = [Self::Coverage, Self::Current, Self::Predicted];

    pub const fn index(self) -> usize {
        self as usize
    }
}

impl TryFrom<u8> for PriorityLane {
    type Error = anyhow::Error;

    fn try_from(value: u8) -> Result<Self> {
        match value {
            0 => Ok(Self::Coverage),
            1 => Ok(Self::Current),
            2 => Ok(Self::Predicted),
            _ => bail!("unknown object-request priority lane {value}"),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct RootToken {
    pub generation: u64,
    pub dimension_hash: ObjectHash,
    pub root_hash: ObjectHash,
}

impl RootToken {
    pub fn new(generation: u64, dimension_hash: ObjectHash, root_hash: ObjectHash) -> Result<Self> {
        if generation == 0 || dimension_hash.is_zero() || root_hash.is_zero() {
            bail!("root generation, dimension hash, and manifest hash must be nonzero");
        }
        Ok(Self {
            generation,
            dimension_hash,
            root_hash,
        })
    }
}

impl From<RootRecord> for RootToken {
    fn from(value: RootRecord) -> Self {
        Self {
            generation: value.generation,
            dimension_hash: value.dimension,
            root_hash: value.root_manifest,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum CameraDomainState {
    Unknown = 0,
    Exterior = 1,
    Interior = 2,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ControlMessage {
    Hello {
        dimension: String,
    },
    ServerHello {
        server_instance: u64,
    },
    RootAnnounce {
        dimension: String,
        root: RootToken,
        catalog: ObjectHash,
        dictionary_set: ObjectHash,
        visibility: ObjectHash,
    },
    RootReady {
        dimension: String,
        root: RootToken,
    },
    CameraDomainRequest {
        root: RootToken,
        sequence: u64,
        block_x: i32,
        block_y: i32,
        block_z: i32,
    },
    CameraDomain {
        root: RootToken,
        sequence: u64,
        state: CameraDomainState,
        domain: u64,
        min: [i32; 3],
        max: [i32; 3],
    },
    Error {
        code: u16,
        message: String,
    },
    Shutdown {
        message: String,
    },
}

impl ControlMessage {
    fn kind(&self) -> u8 {
        match self {
            Self::Hello { .. } => C_HELLO,
            Self::ServerHello { .. } => S_HELLO,
            Self::RootAnnounce { .. } => S_ROOT_ANNOUNCE,
            Self::RootReady { .. } => C_ROOT_READY,
            Self::CameraDomainRequest { .. } => C_CAMERA_DOMAIN,
            Self::CameraDomain { .. } => S_CAMERA_DOMAIN,
            Self::Shutdown { .. } => S_SHUTDOWN,
            Self::Error { .. } => S_ERROR,
        }
    }

    fn encode(&self) -> Result<Vec<u8>> {
        let mut output = Vec::with_capacity(256);
        match self {
            Self::Hello { dimension } => put_string(&mut output, dimension)?,
            Self::ServerHello { server_instance } => {
                output.extend_from_slice(&server_instance.to_le_bytes());
            }
            Self::RootAnnounce {
                dimension,
                root,
                catalog,
                dictionary_set,
                visibility,
            } => {
                put_string(&mut output, dimension)?;
                put_root(&mut output, *root)?;
                put_hash(&mut output, *catalog)?;
                put_hash(&mut output, *dictionary_set)?;
                put_hash(&mut output, *visibility)?;
                if root.dimension_hash != ObjectHash::dimension(dimension)? {
                    bail!("root announcement dimension and capability disagree");
                }
            }
            Self::RootReady { dimension, root } => {
                put_string(&mut output, dimension)?;
                put_root(&mut output, *root)?;
                if root.dimension_hash != ObjectHash::dimension(dimension)? {
                    bail!("root-ready dimension and capability disagree");
                }
            }
            Self::CameraDomainRequest {
                root,
                sequence,
                block_x,
                block_y,
                block_z,
            } => {
                put_root(&mut output, *root)?;
                if *sequence == 0 {
                    bail!("camera-domain sequence zero is reserved");
                }
                output.extend_from_slice(&sequence.to_le_bytes());
                output.extend_from_slice(&block_x.to_le_bytes());
                output.extend_from_slice(&block_y.to_le_bytes());
                output.extend_from_slice(&block_z.to_le_bytes());
            }
            Self::CameraDomain {
                root,
                sequence,
                state,
                domain,
                min,
                max,
            } => {
                put_root(&mut output, *root)?;
                if *sequence == 0
                    || min.iter().zip(max).any(|(min, max)| min > max)
                    || match state {
                        CameraDomainState::Unknown => *domain != 0,
                        CameraDomainState::Exterior => *domain != 1,
                        CameraDomainState::Interior => *domain < 2,
                    }
                {
                    bail!("camera-domain response state and identity disagree");
                }
                output.extend_from_slice(&sequence.to_le_bytes());
                output.push(*state as u8);
                output.extend_from_slice(&domain.to_le_bytes());
                for coordinate in min.iter().chain(max) {
                    output.extend_from_slice(&coordinate.to_le_bytes());
                }
            }
            Self::Error { code, message } => {
                output.extend_from_slice(&code.to_le_bytes());
                put_error_string(&mut output, message);
            }
            Self::Shutdown { message } => put_error_string(&mut output, message),
        }
        if output.len() > MAX_CONTROL_PAYLOAD {
            bail!("control payload exceeds {MAX_CONTROL_PAYLOAD} bytes");
        }
        Ok(output)
    }

    fn decode_client(kind: u8, mut input: &[u8]) -> Result<Self> {
        let message = match kind {
            C_HELLO => Self::Hello {
                dimension: take_string(&mut input)?,
            },
            C_ROOT_READY => {
                let dimension = take_string(&mut input)?;
                let root = take_root(&mut input)?;
                if root.dimension_hash != ObjectHash::dimension(&dimension)? {
                    bail!("root-ready dimension and capability disagree");
                }
                Self::RootReady { dimension, root }
            }
            C_CAMERA_DOMAIN => {
                let root = take_root(&mut input)?;
                let sequence = take_u64(&mut input)?;
                if sequence == 0 {
                    bail!("camera-domain sequence zero is reserved");
                }
                Self::CameraDomainRequest {
                    root,
                    sequence,
                    block_x: take_i32(&mut input)?,
                    block_y: take_i32(&mut input)?,
                    block_z: take_i32(&mut input)?,
                }
            }
            _ => bail!("unknown client control message {kind:#04x}"),
        };
        if !input.is_empty() {
            bail!("trailing bytes in control message");
        }
        Ok(message)
    }
}

pub async fn read_stream_role(reader: &mut (impl AsyncRead + Unpin)) -> Result<Option<u8>> {
    let mut byte = [0u8; 1];
    if reader.read(&mut byte).await? == 0 {
        return Ok(None);
    }
    Ok(Some(byte[0]))
}

pub async fn read_control(reader: &mut (impl AsyncRead + Unpin)) -> Result<Option<ControlMessage>> {
    let Some(kind) = read_stream_role(reader).await? else {
        return Ok(None);
    };
    let mut length = [0u8; 2];
    reader
        .read_exact(&mut length)
        .await
        .context("truncated control-record length")?;
    let length = u16::from_le_bytes(length) as usize;
    if length > MAX_CONTROL_PAYLOAD {
        bail!("control payload exceeds {MAX_CONTROL_PAYLOAD} bytes");
    }
    let mut payload = vec![0; length];
    reader
        .read_exact(&mut payload)
        .await
        .context("truncated control-record payload")?;
    ControlMessage::decode_client(kind, &payload).map(Some)
}

pub fn encode_control_record(message: &ControlMessage) -> Result<Vec<u8>> {
    let payload = message.encode()?;
    let mut record = Vec::with_capacity(3 + payload.len());
    record.push(message.kind());
    record.extend_from_slice(&(payload.len() as u16).to_le_bytes());
    record.extend_from_slice(&payload);
    Ok(record)
}

#[derive(Debug)]
pub struct ObjectRequest {
    pub lane: PriorityLane,
    pub root: RootToken,
    pub hashes: Vec<ObjectHash>,
}

impl ObjectRequest {
    /// Reads the request after its stream-role byte and requires an immediate clean FIN.
    pub async fn read(reader: &mut (impl AsyncRead + Unpin)) -> Result<Self> {
        let mut header = [0u8; 3 + ROOT_TOKEN_BYTES];
        reader
            .read_exact(&mut header)
            .await
            .context("truncated object-request header")?;
        let lane = PriorityLane::try_from(header[0])?;
        let count = u16::from_le_bytes(header[1..3].try_into().unwrap()) as usize;
        if count == 0 || count > MAX_REQUEST_ENTRIES {
            bail!("object-request count is outside 1..={MAX_REQUEST_ENTRIES}");
        }
        let mut root_input = &header[3..];
        let root = take_root(&mut root_input)?;
        let byte_count = count
            .checked_mul(HASH_BYTES)
            .context("object-request hash byte count overflow")?;
        let mut bytes = vec![0u8; byte_count];
        reader
            .read_exact(&mut bytes)
            .await
            .context("truncated object-request hashes")?;
        let mut hashes = Vec::with_capacity(count);
        let mut unique = HashSet::with_capacity(count);
        for chunk in bytes.chunks_exact(HASH_BYTES) {
            let hash = ObjectHash::from_bytes(chunk.try_into().unwrap())?;
            if !unique.insert(hash) {
                bail!("object request contains a duplicate hash");
            }
            hashes.push(hash);
        }
        let mut trailing = [0u8; 1];
        if reader.read(&mut trailing).await? != 0 {
            bail!("object request contains trailing bytes");
        }
        Ok(Self { lane, root, hashes })
    }
}

#[derive(Clone, Copy, Debug)]
/// Success records are exactly `hash, kind, dictionary ID, canonical length, compressed length,
/// compressed CRC32C`, all integer fields little-endian, followed immediately by the stored Zstd
/// extent.
pub struct ObjectResponseHeader {
    pub hash: ObjectHash,
    pub kind: ObjectKind,
    pub dictionary_id: u32,
    pub canonical_size: u32,
    pub compressed_size: u32,
    pub compressed_crc: u32,
}

impl ObjectResponseHeader {
    pub fn encode(self) -> Result<[u8; OBJECT_RESPONSE_HEADER_BYTES]> {
        if self.hash.is_zero()
            || self.canonical_size as usize > MAX_CANONICAL_OBJECT_BYTES
            || self.compressed_size == 0
            || self.compressed_size as usize > MAX_COMPRESSED_OBJECT_BYTES
        {
            bail!("object response header is outside protocol bounds");
        }
        if matches!(
            self.kind,
            ObjectKind::VisibilityDirectory
                | ObjectKind::VisibilityPage
                | ObjectKind::VisibilitySummaryPage
                | ObjectKind::SourceMicrotile
        ) {
            bail!("server-internal object kind cannot be transferred");
        }
        let content = matches!(
            self.kind,
            ObjectKind::ExteriorMicrotile
                | ObjectKind::InteriorMicrotile
                | ObjectKind::ComplexMicrotile
        );
        if content != (self.dictionary_id != 0) {
            bail!("only content microtiles require a dictionary ID");
        }
        let mut bytes = [0u8; OBJECT_RESPONSE_HEADER_BYTES];
        bytes[..HASH_BYTES].copy_from_slice(self.hash.as_bytes());
        bytes[32] = self.kind as u8;
        bytes[33..37].copy_from_slice(&self.dictionary_id.to_le_bytes());
        bytes[37..41].copy_from_slice(&self.canonical_size.to_le_bytes());
        bytes[41..45].copy_from_slice(&self.compressed_size.to_le_bytes());
        bytes[45..49].copy_from_slice(&self.compressed_crc.to_le_bytes());
        Ok(bytes)
    }
}

pub async fn write_object_success(
    writer: &mut (impl AsyncWrite + Unpin),
    count: usize,
) -> Result<()> {
    if count == 0 || count > MAX_REQUEST_ENTRIES {
        bail!("object response count is outside protocol bounds");
    }
    writer.write_all(&[OBJECT_RESPONSE_OK]).await?;
    writer.write_all(&(count as u16).to_le_bytes()).await?;
    Ok(())
}

pub async fn write_object_error(
    writer: &mut (impl AsyncWrite + Unpin),
    code: u16,
    message: &str,
) -> Result<()> {
    let message = bounded_string(message);
    writer.write_all(&[OBJECT_RESPONSE_ERROR]).await?;
    writer.write_all(&code.to_le_bytes()).await?;
    writer
        .write_all(&(message.len() as u16).to_le_bytes())
        .await?;
    writer.write_all(message.as_bytes()).await?;
    Ok(())
}

fn put_root(output: &mut Vec<u8>, root: RootToken) -> Result<()> {
    RootToken::new(root.generation, root.dimension_hash, root.root_hash)?;
    output.extend_from_slice(&root.generation.to_le_bytes());
    output.extend_from_slice(root.dimension_hash.as_bytes());
    output.extend_from_slice(root.root_hash.as_bytes());
    Ok(())
}

fn take_root(input: &mut &[u8]) -> Result<RootToken> {
    RootToken::new(take_u64(input)?, take_hash(input)?, take_hash(input)?)
}

fn put_hash(output: &mut Vec<u8>, hash: ObjectHash) -> Result<()> {
    if hash.is_zero() {
        bail!("zero object hash is reserved");
    }
    output.extend_from_slice(hash.as_bytes());
    Ok(())
}

fn take_hash(input: &mut &[u8]) -> Result<ObjectHash> {
    ObjectHash::from_bytes(take(input, HASH_BYTES)?.try_into().unwrap())
}

fn put_string(output: &mut Vec<u8>, value: &str) -> Result<()> {
    if value.is_empty() || value.len() > MAX_DIMENSION_BYTES {
        bail!("dimension is outside its protocol length bound");
    }
    output.extend_from_slice(&(value.len() as u16).to_le_bytes());
    output.extend_from_slice(value.as_bytes());
    Ok(())
}

fn take_string(input: &mut &[u8]) -> Result<String> {
    let length = take_u16(input)? as usize;
    if length == 0 || length > MAX_DIMENSION_BYTES {
        bail!("dimension is outside its protocol length bound");
    }
    std::str::from_utf8(take(input, length)?)
        .context("dimension is not valid UTF-8")
        .map(ToOwned::to_owned)
}

fn put_error_string(output: &mut Vec<u8>, message: &str) {
    let message = bounded_string(message);
    output.extend_from_slice(&(message.len() as u16).to_le_bytes());
    output.extend_from_slice(message.as_bytes());
}

fn bounded_string(message: &str) -> &str {
    let mut length = message.len().min(MAX_ERROR_STRING);
    while !message.is_char_boundary(length) {
        length -= 1;
    }
    &message[..length]
}
