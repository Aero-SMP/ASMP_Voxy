//! Strict frame and payload codecs for terrain streaming.

pub use super::{
    manifest::MAX_MANIFEST_BYTES,
    pack::{MAX_CANONICAL_OBJECT_BYTES, MAX_COMPRESSED_OBJECT_BYTES},
};
use super::{
    memory::{MemoryClass, MemoryPermit, ServerMemoryBudget},
    object::{ObjectHash, ObjectKind},
    pack::StoredObject,
    root::RootRecord,
};
use crate::{crc::crc32c, take, take_i32, take_u16, take_u32, take_u64};
use anyhow::{Context, Result, bail};
use std::{collections::HashSet, sync::Arc, time::Duration};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const MAGIC: u32 = 0x5958_4f56; // ASCII "VOXY" in little-endian order.
pub const HEADER_LEN: usize = 14;
pub const C_HELLO: u16 = 0x0001;
pub const C_PING: u16 = 0x0003;
pub const C_CREDIT: u16 = 0x0005;
pub const C_SUBTREE_REQUEST: u16 = 0x0006;
pub const C_OBJECT_REQUEST: u16 = 0x0007;
pub const C_ROOT_READY: u16 = 0x0008;
pub const C_CAMERA_DOMAIN: u16 = 0x0009;
pub const S_HELLO: u16 = 0x8001;
pub const S_PONG: u16 = 0x8005;
pub const S_ROOT_ANNOUNCE: u16 = 0x8007;
pub const S_SUBTREE_DATA: u16 = 0x8008;
pub const S_OBJECT_BUNDLE: u16 = 0x8009;
pub const S_CAMERA_DOMAIN: u16 = 0x800a;
pub const S_ERROR: u16 = 0x80ff;

pub const HASH_BYTES: usize = 32;
pub const MAX_FRAME_PAYLOAD: usize = 16 * 1024 * 1024;
pub const MAX_DIMENSION_BYTES: usize = 1024;
pub const MAX_REQUEST_ENTRIES: usize = 256;
pub const MAX_BUNDLE_ENTRIES: usize = 256;
const ROOT_TOKEN_BYTES: usize = 8 + HASH_BYTES * 2;
const OBJECT_HEADER_BYTES: usize = HASH_BYTES + 17;
const MAX_ERROR_STRING: usize = 4096;
pub const WRITE_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Frame {
    pub kind: u16,
    pub payload: Vec<u8>,
}

impl Frame {
    pub async fn read_client(reader: &mut (impl AsyncRead + Unpin)) -> Result<Option<Self>> {
        Ok(Self::read_client_parts(reader, None)
            .await?
            .map(|value| value.0))
    }

    pub async fn read_client_budgeted(
        reader: &mut (impl AsyncRead + Unpin),
        budget: &Arc<ServerMemoryBudget>,
    ) -> Result<Option<BudgetedFrame>> {
        Ok(Self::read_client_parts(reader, Some(budget))
            .await?
            .map(|(frame, memory)| BudgetedFrame {
                frame,
                _memory: memory.expect("budgeted read always creates a permit"),
            }))
    }

    async fn read_client_parts(
        reader: &mut (impl AsyncRead + Unpin),
        budget: Option<&Arc<ServerMemoryBudget>>,
    ) -> Result<Option<(Self, Option<MemoryPermit>)>> {
        let mut header = [0; HEADER_LEN];
        if reader.read(&mut header[..1]).await? == 0 {
            return Ok(None);
        }
        reader
            .read_exact(&mut header[1..])
            .await
            .context("truncated client frame header")?;
        let (kind, length, expected_crc) = decode_frame_header(&header)?;
        let memory = match budget {
            Some(budget) => Some(
                budget
                    .reserve(MemoryClass::Network, length.saturating_mul(2))
                    .await?,
            ),
            None => None,
        };
        let mut payload = vec![0; length];
        reader.read_exact(&mut payload).await?;
        if crc32c(&payload) != expected_crc {
            bail!("frame checksum mismatch");
        }
        Ok(Some((Self { kind, payload }, memory)))
    }

    pub async fn write(&self, writer: &mut (impl AsyncWrite + Unpin)) -> Result<()> {
        if self.payload.len() > MAX_FRAME_PAYLOAD {
            bail!("frame is larger than {MAX_FRAME_PAYLOAD} bytes");
        }
        let mut header = [0; HEADER_LEN];
        header[..4].copy_from_slice(&MAGIC.to_le_bytes());
        header[4..6].copy_from_slice(&self.kind.to_le_bytes());
        header[6..10].copy_from_slice(&(self.payload.len() as u32).to_le_bytes());
        header[10..14].copy_from_slice(&crc32c(&self.payload).to_le_bytes());
        tokio::time::timeout(WRITE_TIMEOUT, async {
            writer.write_all(&header).await?;
            writer.write_all(&self.payload).await
        })
        .await
        .context("client frame write timed out")??;
        Ok(())
    }
}

#[derive(Debug)]
pub struct BudgetedFrame {
    pub frame: Frame,
    _memory: MemoryPermit,
}

impl BudgetedFrame {
    pub(crate) fn into_parts(self) -> (Frame, MemoryPermit) {
        (self.frame, self._memory)
    }
}

fn decode_frame_header(header: &[u8; HEADER_LEN]) -> Result<(u16, usize, u32)> {
    if u32::from_le_bytes(header[..4].try_into().unwrap()) != MAGIC {
        bail!("bad frame magic");
    }
    let kind = u16::from_le_bytes(header[4..6].try_into().unwrap());
    let length = u32::from_le_bytes(header[6..10].try_into().unwrap()) as usize;
    let crc = u32::from_le_bytes(header[10..14].try_into().unwrap());
    if length > MAX_FRAME_PAYLOAD {
        bail!("frame is larger than {MAX_FRAME_PAYLOAD} bytes");
    }
    if !valid_client_frame_size(kind, length) {
        bail!("client frame {kind:#06x} has impossible payload length {length}");
    }
    Ok((kind, length, crc))
}

fn valid_client_frame_size(kind: u16, length: usize) -> bool {
    const ROOT_AND_COUNT: usize = 8 + 2 * HASH_BYTES + 2;
    match kind {
        C_HELLO => (3..=2 + MAX_DIMENSION_BYTES).contains(&length),
        C_PING | C_CREDIT => length == 8,
        C_SUBTREE_REQUEST | C_OBJECT_REQUEST => {
            (ROOT_AND_COUNT + HASH_BYTES..=ROOT_AND_COUNT + MAX_REQUEST_ENTRIES * HASH_BYTES)
                .contains(&length)
                && (length - ROOT_AND_COUNT).is_multiple_of(HASH_BYTES)
        }
        C_ROOT_READY => (2 + 1 + ROOT_TOKEN_BYTES..=2 + MAX_DIMENSION_BYTES + ROOT_TOKEN_BYTES)
            .contains(&length),
        C_CAMERA_DOMAIN => length == ROOT_TOKEN_BYTES + 8 + 3 * 4,
        _ => false,
    }
}

pub fn hello(server_instance: u64) -> Frame {
    let mut payload = Vec::with_capacity(8);
    payload.extend_from_slice(&server_instance.to_le_bytes());
    Frame {
        kind: S_HELLO,
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
    let mut length = bytes.len().min(MAX_ERROR_STRING);
    while !message.is_char_boundary(length) {
        length -= 1;
    }
    let mut payload = Vec::with_capacity(4 + length);
    payload.extend_from_slice(&code.to_le_bytes());
    payload.extend_from_slice(&(length as u16).to_le_bytes());
    payload.extend_from_slice(&bytes[..length]);
    Frame {
        kind: S_ERROR,
        payload,
    }
}

pub fn parse_control_u64(payload: &[u8]) -> Result<u64> {
    if payload.len() != 8 {
        bail!("control frame must contain one u64 value");
    }
    Ok(u64::from_le_bytes(payload.try_into().unwrap()))
}

pub fn decode_client_hello(mut input: &[u8]) -> Result<String> {
    let dimension = take_string(&mut input)?;
    if !input.is_empty() {
        bail!("trailing bytes in surface client HELLO");
    }
    Ok(dimension)
}

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct RootToken {
    pub generation: u64,
    /// Canonical identity of the dimension that owns this root.
    ///
    /// This is part of every request/response capability.  A generation and root-directory
    /// hash are not sufficient: two sparse dimensions can legitimately publish identical
    /// directory objects at the same generation.
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

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct WireObject {
    pub hash: ObjectHash,
    pub kind: ObjectKind,
    /// Unsigned index into the root's dictionary set. Zero means no dictionary.
    pub dictionary_id: u32,
    pub canonical_size: u32,
    pub compressed_crc: u32,
    pub compressed: Vec<u8>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum CameraDomainState {
    Unknown = 0,
    Exterior = 1,
    Interior = 2,
}

impl TryFrom<u8> for CameraDomainState {
    type Error = anyhow::Error;

    fn try_from(value: u8) -> Result<Self> {
        match value {
            0 => Ok(Self::Unknown),
            1 => Ok(Self::Exterior),
            2 => Ok(Self::Interior),
            _ => bail!("unknown surface camera-domain state {value}"),
        }
    }
}

impl WireObject {
    // This mirrors the fixed wire header one-for-one; grouping fields would make validation less
    // auditable across the Rust and Java codecs.
    pub fn new(
        hash: ObjectHash,
        kind: ObjectKind,
        dictionary_id: u32,
        canonical_size: u32,
        compressed_crc: u32,
        compressed: Vec<u8>,
    ) -> Result<Self> {
        let value = Self {
            hash,
            kind,
            dictionary_id,
            canonical_size,
            compressed_crc,
            compressed,
        };
        value.validate()?;
        Ok(value)
    }

    pub fn from_stored_with_dictionary_id(value: StoredObject, dictionary_id: u32) -> Result<Self> {
        if value.dictionary.is_zero() != (dictionary_id == 0) {
            bail!("stored dictionary hash and announced dictionary index disagree");
        }
        Self::new(
            value.hash,
            value.kind,
            dictionary_id,
            value
                .canonical_size
                .try_into()
                .context("canonical object length does not fit the surface wire format")?,
            value.compressed_crc,
            value.compressed,
        )
    }

    pub fn validate(&self) -> Result<()> {
        if matches!(
            self.kind,
            ObjectKind::VisibilityDirectory
                | ObjectKind::VisibilityPage
                | ObjectKind::VisibilitySummaryPage
                | ObjectKind::SourceMicrotile
        ) {
            bail!("server-internal surface object kind cannot be sent on the wire");
        }
        if self.hash.is_zero() {
            bail!("object hash is invalid");
        }
        if self.canonical_size as usize > MAX_CANONICAL_OBJECT_BYTES {
            bail!("canonical object is larger than {MAX_CANONICAL_OBJECT_BYTES} bytes");
        }
        if self.compressed.len() > MAX_COMPRESSED_OBJECT_BYTES {
            bail!("compressed object is larger than {MAX_COMPRESSED_OBJECT_BYTES} bytes");
        }
        let content = matches!(
            self.kind,
            ObjectKind::ExteriorMicrotile
                | ObjectKind::InteriorMicrotile
                | ObjectKind::ComplexMicrotile
        );
        if content != (self.dictionary_id != 0) {
            bail!("only content microtiles require a class dictionary ID");
        }
        if crc32c(&self.compressed) != self.compressed_crc {
            bail!("compressed object checksum mismatch");
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Message {
    RootAnnounce {
        dimension: String,
        root: RootToken,
        catalog: ObjectHash,
        dictionary_set: ObjectHash,
        visibility: ObjectHash,
    },
    SubtreeRequest {
        root: RootToken,
        hashes: Vec<ObjectHash>,
    },
    SubtreeData {
        root: RootToken,
        objects: Vec<WireObject>,
    },
    ObjectRequest {
        root: RootToken,
        hashes: Vec<ObjectHash>,
    },
    ObjectBundle {
        root: RootToken,
        objects: Vec<WireObject>,
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
}

impl Message {
    pub fn kind(&self) -> u16 {
        match self {
            Self::RootAnnounce { .. } => S_ROOT_ANNOUNCE,
            Self::SubtreeRequest { .. } => C_SUBTREE_REQUEST,
            Self::SubtreeData { .. } => S_SUBTREE_DATA,
            Self::ObjectRequest { .. } => C_OBJECT_REQUEST,
            Self::ObjectBundle { .. } => S_OBJECT_BUNDLE,
            Self::RootReady { .. } => C_ROOT_READY,
            Self::CameraDomainRequest { .. } => C_CAMERA_DOMAIN,
            Self::CameraDomain { .. } => S_CAMERA_DOMAIN,
        }
    }

    pub fn encode(&self) -> Result<Vec<u8>> {
        self.validate()?;
        let mut output = Vec::with_capacity(256);
        match self {
            Self::RootAnnounce {
                dimension,
                root,
                catalog,
                dictionary_set,
                visibility,
            } => {
                put_string(&mut output, dimension)?;
                put_root(&mut output, *root)?;
                put_hash(&mut output, *catalog);
                put_hash(&mut output, *dictionary_set);
                put_hash(&mut output, *visibility);
            }
            Self::SubtreeRequest { root, hashes } | Self::ObjectRequest { root, hashes } => {
                put_request(&mut output, *root, hashes)?
            }
            Self::SubtreeData { root, objects } | Self::ObjectBundle { root, objects } => {
                put_bundle(&mut output, *root, objects)?
            }
            Self::RootReady { dimension, root } => {
                put_string(&mut output, dimension)?;
                put_root(&mut output, *root)?;
            }
            Self::CameraDomainRequest {
                root,
                sequence,
                block_x,
                block_y,
                block_z,
            } => {
                put_root(&mut output, *root)?;
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
                output.extend_from_slice(&sequence.to_le_bytes());
                output.push(*state as u8);
                output.extend_from_slice(&domain.to_le_bytes());
                for coordinate in min.iter().chain(max.iter()) {
                    output.extend_from_slice(&coordinate.to_le_bytes());
                }
            }
        }
        if output.len() > MAX_FRAME_PAYLOAD {
            bail!("encoded surface payload exceeds {MAX_FRAME_PAYLOAD} bytes");
        }
        Ok(output)
    }

    pub fn decode(kind: u16, payload: &[u8]) -> Result<Self> {
        if payload.len() > MAX_FRAME_PAYLOAD {
            bail!("surface payload is larger than {MAX_FRAME_PAYLOAD} bytes");
        }
        let mut input = payload;
        let message = match kind {
            S_ROOT_ANNOUNCE => {
                let dimension = take_string(&mut input)?;
                let root = take_root(&mut input)?;
                let catalog = take_hash(&mut input)?;
                let dictionary_set = take_hash(&mut input)?;
                let visibility = take_hash(&mut input)?;
                Self::RootAnnounce {
                    dimension,
                    root,
                    catalog,
                    dictionary_set,
                    visibility,
                }
            }
            C_SUBTREE_REQUEST | C_OBJECT_REQUEST => {
                let root = take_root(&mut input)?;
                let hashes = take_hash_list(&mut input)?;
                if kind == C_SUBTREE_REQUEST {
                    Self::SubtreeRequest { root, hashes }
                } else {
                    Self::ObjectRequest { root, hashes }
                }
            }
            S_SUBTREE_DATA | S_OBJECT_BUNDLE => {
                let root = take_root(&mut input)?;
                let objects = take_object_list(&mut input, kind == S_SUBTREE_DATA)?;
                if kind == S_SUBTREE_DATA {
                    Self::SubtreeData { root, objects }
                } else {
                    Self::ObjectBundle { root, objects }
                }
            }
            C_ROOT_READY => {
                let dimension = take_string(&mut input)?;
                let root = take_root(&mut input)?;
                Self::RootReady { dimension, root }
            }
            C_CAMERA_DOMAIN => Self::CameraDomainRequest {
                root: take_root(&mut input)?,
                sequence: take_u64(&mut input)?,
                block_x: take_i32(&mut input)?,
                block_y: take_i32(&mut input)?,
                block_z: take_i32(&mut input)?,
            },
            S_CAMERA_DOMAIN => {
                let root = take_root(&mut input)?;
                let sequence = take_u64(&mut input)?;
                let state = CameraDomainState::try_from(take(&mut input, 1)?[0])?;
                Self::CameraDomain {
                    root,
                    sequence,
                    state,
                    domain: take_u64(&mut input)?,
                    min: [
                        take_i32(&mut input)?,
                        take_i32(&mut input)?,
                        take_i32(&mut input)?,
                    ],
                    max: [
                        take_i32(&mut input)?,
                        take_i32(&mut input)?,
                        take_i32(&mut input)?,
                    ],
                }
            }
            _ => bail!("unknown frame type {kind:#06x}"),
        };
        if !input.is_empty() {
            bail!("trailing bytes in payload");
        }
        message.validate()?;
        Ok(message)
    }

    pub fn validate(&self) -> Result<()> {
        match self {
            Self::RootAnnounce {
                dimension,
                root,
                catalog,
                dictionary_set,
                visibility,
            } => {
                validate_dimension(dimension)?;
                validate_root(*root)?;
                if root.dimension_hash != ObjectHash::dimension(dimension)? {
                    bail!("root announcement dimension name and capability hash disagree");
                }
                if catalog.is_zero() || dictionary_set.is_zero() || visibility.is_zero() {
                    bail!("root announcement contains a reserved zero hash");
                }
            }
            Self::SubtreeRequest { root, hashes } | Self::ObjectRequest { root, hashes } => {
                validate_root(*root)?;
                validate_hashes(hashes)?;
            }
            Self::SubtreeData { root, objects } => {
                validate_root(*root)?;
                validate_objects(objects, true)?;
            }
            Self::ObjectBundle { root, objects } => {
                validate_root(*root)?;
                validate_objects(objects, false)?;
            }
            Self::RootReady { dimension, root } => {
                validate_dimension(dimension)?;
                validate_root(*root)?;
                if root.dimension_hash != ObjectHash::dimension(dimension)? {
                    bail!("root-ready dimension name and capability hash disagree");
                }
            }
            Self::CameraDomainRequest { root, sequence, .. } => {
                validate_root(*root)?;
                if *sequence == 0 {
                    bail!("camera-domain request sequence zero is reserved");
                }
            }
            Self::CameraDomain {
                root,
                sequence,
                state,
                domain,
                min,
                max,
            } => {
                validate_root(*root)?;
                if *sequence == 0
                    || min.iter().zip(max.iter()).any(|(min, max)| min > max)
                    || match state {
                        CameraDomainState::Unknown => *domain != 0,
                        CameraDomainState::Exterior => *domain != 1,
                        CameraDomainState::Interior => *domain < 2,
                    }
                {
                    bail!("camera-domain response state and identity disagree");
                }
            }
        }
        Ok(())
    }
}

fn put_request(output: &mut Vec<u8>, root: RootToken, hashes: &[ObjectHash]) -> Result<()> {
    validate_root(root)?;
    validate_hashes(hashes)?;
    put_root(output, root)?;
    put_u16(output, hashes.len().try_into().unwrap());
    for hash in hashes {
        put_hash(output, *hash);
    }
    Ok(())
}

fn put_bundle(output: &mut Vec<u8>, root: RootToken, objects: &[WireObject]) -> Result<()> {
    validate_root(root)?;
    // The caller's variant is checked by Message::validate before this helper runs.
    put_root(output, root)?;
    put_u16(
        output,
        objects
            .len()
            .try_into()
            .context("too many bundled objects")?,
    );
    for object in objects {
        put_hash(output, object.hash);
        output.push(object.kind as u8);
        put_u32(output, object.dictionary_id);
        put_u32(output, object.canonical_size);
        put_u32(
            output,
            object
                .compressed
                .len()
                .try_into()
                .context("compressed object length does not fit u32")?,
        );
        put_u32(output, object.compressed_crc);
        output.extend_from_slice(&object.compressed);
    }
    Ok(())
}

fn take_hash_list(input: &mut &[u8]) -> Result<Vec<ObjectHash>> {
    let count = take_u16(input)? as usize;
    if count == 0 || count > MAX_REQUEST_ENTRIES {
        bail!("request hash count is out of bounds");
    }
    if input.len()
        != count
            .checked_mul(HASH_BYTES)
            .context("request size overflow")?
    {
        bail!("request hash count does not match payload length");
    }
    let mut hashes = Vec::with_capacity(count);
    for _ in 0..count {
        hashes.push(take_hash(input)?);
    }
    validate_hashes(&hashes)?;
    Ok(hashes)
}

fn take_object_list(input: &mut &[u8], manifests_only: bool) -> Result<Vec<WireObject>> {
    let count = take_u16(input)? as usize;
    if count == 0 || count > MAX_BUNDLE_ENTRIES {
        bail!("bundle object count is out of bounds");
    }
    if input.len()
        < count
            .checked_mul(OBJECT_HEADER_BYTES)
            .context("object count overflow")?
    {
        bail!("object count cannot fit in payload");
    }
    let mut objects = Vec::with_capacity(count);
    for _ in 0..count {
        let hash = take_hash(input)?;
        let kind = ObjectKind::try_from(take(input, 1)?[0])?;
        let dictionary_id = take_u32(input)?;
        let canonical_size = take_u32(input)?;
        if canonical_size as usize > MAX_CANONICAL_OBJECT_BYTES {
            bail!("canonical object is larger than {MAX_CANONICAL_OBJECT_BYTES} bytes");
        }
        let compressed_size = take_u32(input)? as usize;
        if compressed_size > MAX_COMPRESSED_OBJECT_BYTES {
            bail!("compressed object is larger than {MAX_COMPRESSED_OBJECT_BYTES} bytes");
        }
        let compressed_crc = take_u32(input)?;
        let compressed = take(input, compressed_size)?.to_vec();
        objects.push(WireObject::new(
            hash,
            kind,
            dictionary_id,
            canonical_size,
            compressed_crc,
            compressed,
        )?);
    }
    validate_objects(&objects, manifests_only)?;
    Ok(objects)
}

fn validate_objects(objects: &[WireObject], manifests_only: bool) -> Result<()> {
    if objects.is_empty() || objects.len() > MAX_BUNDLE_ENTRIES {
        bail!("object count must be between 1 and {MAX_BUNDLE_ENTRIES}");
    }
    let mut hashes = HashSet::with_capacity(objects.len());
    let mut canonical_bytes = 0usize;
    let mut encoded_bytes = ROOT_TOKEN_BYTES + 2;
    for object in objects {
        object.validate()?;
        if !hashes.insert(object.hash) {
            bail!("duplicate content object");
        }
        let is_manifest = matches!(
            object.kind,
            ObjectKind::ManifestSubtree
                | ObjectKind::ManifestDescriptorPage
                | ObjectKind::RootDirectory
        );
        if is_manifest != manifests_only {
            bail!("surface object type does not match its bundle channel");
        }
        if manifests_only
            && (object.canonical_size as usize > MAX_MANIFEST_BYTES
                || object.compressed.len() > MAX_MANIFEST_BYTES)
        {
            bail!("subtree data must contain bounded manifest objects");
        }
        canonical_bytes = canonical_bytes
            .checked_add(object.canonical_size as usize)
            .context("bundle canonical size overflow")?;
        encoded_bytes = encoded_bytes
            .checked_add(OBJECT_HEADER_BYTES + object.compressed.len())
            .context("bundle encoded size overflow")?;
    }
    let maximum = if manifests_only {
        MAX_MANIFEST_BYTES
    } else {
        MAX_CANONICAL_OBJECT_BYTES
    };
    if canonical_bytes > maximum {
        bail!("bundle canonical data exceeds {maximum} bytes");
    }
    if encoded_bytes > MAX_FRAME_PAYLOAD {
        bail!("encoded surface payload exceeds {MAX_FRAME_PAYLOAD} bytes");
    }
    Ok(())
}

fn validate_hashes(hashes: &[ObjectHash]) -> Result<()> {
    if hashes.is_empty() || hashes.len() > MAX_REQUEST_ENTRIES {
        bail!("hash count must be between 1 and {MAX_REQUEST_ENTRIES}");
    }
    let unique = hashes.iter().copied().collect::<HashSet<_>>();
    if unique.len() != hashes.len() || hashes.iter().any(|hash| hash.is_zero()) {
        bail!("request contains a duplicate or zero content hash");
    }
    Ok(())
}

fn validate_dimension(dimension: &str) -> Result<()> {
    if dimension.is_empty() || dimension.len() > MAX_DIMENSION_BYTES {
        bail!("dimension name must contain 1..={MAX_DIMENSION_BYTES} UTF-8 bytes");
    }
    Ok(())
}

fn validate_root(root: RootToken) -> Result<()> {
    RootToken::new(root.generation, root.dimension_hash, root.root_hash).map(|_| ())
}

fn take_string(input: &mut &[u8]) -> Result<String> {
    let length = take_u16(input)? as usize;
    if length == 0 || length > MAX_DIMENSION_BYTES {
        bail!("dimension name is out of bounds");
    }
    let value = std::str::from_utf8(take(input, length)?)
        .context("dimension name is not valid UTF-8")?
        .to_owned();
    Ok(value)
}

fn take_root(input: &mut &[u8]) -> Result<RootToken> {
    RootToken::new(take_u64(input)?, take_hash(input)?, take_hash(input)?)
}

fn take_hash(input: &mut &[u8]) -> Result<ObjectHash> {
    ObjectHash::from_bytes(take(input, HASH_BYTES)?.try_into().unwrap())
}

fn put_string(output: &mut Vec<u8>, value: &str) -> Result<()> {
    validate_dimension(value)?;
    put_u16(output, value.len().try_into().unwrap());
    output.extend_from_slice(value.as_bytes());
    Ok(())
}

fn put_root(output: &mut Vec<u8>, value: RootToken) -> Result<()> {
    validate_root(value)?;
    output.extend_from_slice(&value.generation.to_le_bytes());
    put_hash(output, value.dimension_hash);
    put_hash(output, value.root_hash);
    Ok(())
}

fn put_hash(output: &mut Vec<u8>, value: ObjectHash) {
    output.extend_from_slice(value.as_bytes());
}

fn put_u16(output: &mut Vec<u8>, value: u16) {
    output.extend_from_slice(&value.to_le_bytes());
}

fn put_u32(output: &mut Vec<u8>, value: u32) {
    output.extend_from_slice(&value.to_le_bytes());
}
