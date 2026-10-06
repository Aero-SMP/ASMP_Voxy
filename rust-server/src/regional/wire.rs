//! Matched client/server spatial desires and self-described streamed records.
use crate::{key::SectionKey, take, take_u8, take_u16, take_u32, take_u64};
use anyhow::{Context, Result, bail};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const ALPN: &[u8] = b"voxy-region-cache-start";
pub const STREAM_CONTROL: u8 = 0;
pub const STREAM_SECTION_LANE: u8 = 1;
pub const STREAM_DISCOVERY: u8 = 2;
pub const MAX_DIMENSION_BYTES: usize = 1024;
pub const MAX_CATALOG_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_SECTION_COMPRESSED_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_SECTION_REQUESTS: usize = u16::MAX as usize;
pub const RECORD_DESCRIPTOR_BYTES: usize = 88;
pub const RECORD_SCOPE_BYTES: usize = 36;
pub const S_RECORD: u8 = 0x85;
pub const DEFAULT_UPDATE_INTERVAL_MILLIS: u64 = 2_000;

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
    /// Zero is uncapped; the authenticated server separately requires debug capability.
    pub bandwidth_kbps: u64,
    pub refresh_allowed: bool,
}
impl StreamingSettings {
    pub fn validate(self) -> Result<()> {
        if self.interval_millis < 1000 {
            bail!("terrain update interval must be at least one second");
        }
        if self.bandwidth_kbps != 0 && !(100..=20_000).contains(&self.bandwidth_kbps) {
            bail!("total download rate must be 100 through 20000 kbps, or debug uncapped");
        }
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
    pub fn from_entry(entry: super::RegionSectionEntry, catalog_fingerprint: [u8; 32]) -> Self {
        if !entry.is_present() {
            return Self::default();
        }
        Self {
            // Storage EMPTY describes geometry. Wire EMPTY means there is no payload;
            // air with lighting or biomes must retain its full DATA body.
            flags: Self::wire_flags(true, entry.has_payload()),
            children: entry.non_empty_children,
            catalog_fingerprint,
            fingerprint: entry.fingerprint,
            compressed_length: entry.compressed_length,
            canonical_length: entry.canonical_length,
            compressed_crc: entry.compressed_crc,
        }
    }
    fn wire_flags(present: bool, body: bool) -> u16 {
        if !present {
            0
        } else if body {
            0x8000
        } else {
            0x8001
        }
    }
    fn validate_flags(self) -> Result<()> {
        let present = self.flags & 0x8000 != 0;
        if self.flags != Self::wire_flags(present, self.has_body()) || (!present && self.has_body())
        {
            bail!("terrain binding flags disagree with payload");
        }
        Ok(())
    }
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
    pub rank: u64,
    pub have: Option<ContentBinding>,
}
impl Desire {
    fn validate(self) -> Result<()> {
        if self.ticket == 0 || self.purpose > 4 || self.rank == 0 {
            bail!("invalid terrain desire");
        }
        SectionKey::unpack(self.key)?;
        if let Some(have) = self.have {
            have.validate_flags()?;
            if have.flags & !0x8001 != 0
                || have.compressed_length as usize > MAX_SECTION_COMPRESSED_BYTES
            {
                bail!("invalid terrain holding");
            }
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ScopedDesire {
    pub dimension: u32,
    pub expected_world: [u8; 32],
    pub desire: Desire,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
pub struct ScopedKey {
    pub dimension: u32,
    pub key: u64,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct DimensionAnchor {
    pub dimension: u32,
    pub x: i32,
    pub z: i32,
}
#[derive(Clone, Debug, PartialEq)]
pub struct DimensionMetadata {
    pub id: u32,
    pub name: String,
    pub world_identity: [u8; 32],
    pub min_section_y: i32,
    pub section_count: u32,
    pub custom_border: bool,
    pub center_x: f64,
    pub center_z: f64,
    pub size: f64,
    pub catalog_id: u64,
    pub catalog_fingerprint: [u8; 32],
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct InventoryRecord {
    pub dimension: u32,
    pub revision: u64,
    /// 0 begin, 1 saved/published, 2 unreadable, 3 removed, 4 complete, 5 failure,
    /// 6 saved/not published. Readiness is coverage, not freshness of routine edits.
    pub state: u8,
    pub x: i32,
    pub z: i32,
    pub saved: [u64; 16],
}
#[derive(Clone, Debug, PartialEq)]
pub enum ControlMessage {
    Open {
        dimension: String,
        expected_world: [u8; 32],
        held_catalog: [u8; 32],
        settings: StreamingSettings,
        anchor_x: i32,
        anchor_z: i32,
        desires: Vec<Desire>,
    },
    Desires(Vec<ScopedDesire>),
    Drop(Vec<ScopedKey>),
    Settings {
        settings: StreamingSettings,
        active_dimension: u32,
        anchors: Vec<DimensionAnchor>,
    },
    ServerHello {
        server_instance: u64,
        active_dimension: u32,
        world_identity: [u8; 32],
        catalog_id: u64,
        catalog_fingerprint: [u8; 32],
    },
    Manifest {
        dimensions: Vec<DimensionMetadata>,
        excluded: Vec<(String, String)>,
    },
    Inventory(InventoryRecord),
    Catalog {
        dimension: u32,
        world_identity: [u8; 32],
        fingerprint: [u8; 32],
        canonical_length: u32,
        compressed: Vec<u8>,
    },
    Record {
        dimension: u32,
        world_identity: [u8; 32],
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
        self.binding.validate_flags()?;
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
            anchor_x,
            anchor_z,
            desires,
        } => {
            put_string(&mut payload, dimension, MAX_DIMENSION_BYTES)?;
            payload.extend_from_slice(expected_world);
            payload.extend_from_slice(held_catalog);
            put_settings(&mut payload, *settings)?;
            payload.extend_from_slice(&anchor_x.to_le_bytes());
            payload.extend_from_slice(&anchor_z.to_le_bytes());
            put_desires(&mut payload, desires)?;
            0x01
        }
        ControlMessage::Desires(desires) => {
            put_count(&mut payload, desires.len())?;
            for scoped in desires {
                payload.extend_from_slice(&scoped.dimension.to_le_bytes());
                payload.extend_from_slice(&scoped.expected_world);
                put_desire(&mut payload, scoped.desire)?;
            }
            0x02
        }
        ControlMessage::Drop(keys) => {
            put_count(&mut payload, keys.len())?;
            for scoped in keys {
                SectionKey::unpack(scoped.key)?;
                payload.extend_from_slice(&scoped.dimension.to_le_bytes());
                payload.extend_from_slice(&scoped.key.to_le_bytes());
            }
            0x04
        }
        ControlMessage::Settings {
            settings,
            active_dimension,
            anchors,
        } => {
            put_settings(&mut payload, *settings)?;
            payload.extend_from_slice(&active_dimension.to_le_bytes());
            put_count(&mut payload, anchors.len())?;
            for anchor in anchors {
                payload.extend_from_slice(&anchor.dimension.to_le_bytes());
                payload.extend_from_slice(&anchor.x.to_le_bytes());
                payload.extend_from_slice(&anchor.z.to_le_bytes());
            }
            0x05
        }
        ControlMessage::ServerHello {
            server_instance,
            active_dimension,
            world_identity,
            catalog_id,
            catalog_fingerprint,
        } => {
            payload.extend_from_slice(&server_instance.to_le_bytes());
            payload.extend_from_slice(&active_dimension.to_le_bytes());
            payload.extend_from_slice(world_identity);
            payload.extend_from_slice(&catalog_id.to_le_bytes());
            payload.extend_from_slice(catalog_fingerprint);
            0x81
        }
        ControlMessage::Manifest {
            dimensions,
            excluded,
        } => {
            put_count(&mut payload, dimensions.len())?;
            for d in dimensions {
                payload.extend_from_slice(&d.id.to_le_bytes());
                put_string(&mut payload, &d.name, MAX_DIMENSION_BYTES)?;
                payload.extend_from_slice(&d.world_identity);
                payload.extend_from_slice(&d.min_section_y.to_le_bytes());
                payload.extend_from_slice(&d.section_count.to_le_bytes());
                payload.push(u8::from(d.custom_border));
                payload.extend_from_slice(&d.center_x.to_le_bytes());
                payload.extend_from_slice(&d.center_z.to_le_bytes());
                payload.extend_from_slice(&d.size.to_le_bytes());
                payload.extend_from_slice(&d.catalog_id.to_le_bytes());
                payload.extend_from_slice(&d.catalog_fingerprint);
            }
            put_count(&mut payload, excluded.len())?;
            for (name, reason) in excluded {
                put_string(&mut payload, name, MAX_DIMENSION_BYTES)?;
                put_string(&mut payload, reason, 4096)?;
            }
            0x82
        }
        ControlMessage::Inventory(record) => {
            payload.extend_from_slice(&record.dimension.to_le_bytes());
            payload.extend_from_slice(&record.revision.to_le_bytes());
            payload.push(record.state);
            payload.extend_from_slice(&record.x.to_le_bytes());
            payload.extend_from_slice(&record.z.to_le_bytes());
            for bits in record.saved {
                payload.extend_from_slice(&bits.to_le_bytes());
            }
            0x84
        }
        ControlMessage::Catalog {
            dimension,
            world_identity,
            fingerprint,
            canonical_length,
            compressed,
        } => {
            validate_catalog(*fingerprint, *canonical_length, compressed.len())?;
            payload.extend_from_slice(&dimension.to_le_bytes());
            payload.extend_from_slice(world_identity);
            payload.extend_from_slice(fingerprint);
            payload.extend_from_slice(&canonical_length.to_le_bytes());
            payload.extend_from_slice(&(compressed.len() as u32).to_le_bytes());
            payload.extend_from_slice(compressed);
            0x83
        }
        ControlMessage::Record {
            dimension,
            world_identity,
            descriptor,
            compressed,
        } => {
            payload.extend_from_slice(&dimension.to_le_bytes());
            payload.extend_from_slice(world_identity);
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
    read_control_frame(input, None).await
}

pub async fn read_control_traced<R: AsyncRead + Unpin>(
    input: &mut R,
    session: u64,
    public_route: &str,
) -> Result<Option<ControlMessage>> {
    read_control_frame(input, Some((session, public_route))).await
}

async fn read_control_frame<R: AsyncRead + Unpin>(
    input: &mut R,
    trace: Option<(u64, &str)>,
) -> Result<Option<ControlMessage>> {
    let kind = match input.read_u8().await {
        Ok(value) => value,
        Err(error) if error.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    let length = input.read_u32_le().await? as usize;
    if length > maximum_payload(kind)? {
        bail!("control length exceeds its structural frame shape");
    }
    if let Some((session, route)) = trace {
        eprintln!(
            "VOXY_BOOTSTRAP stage=OPEN_HEADER session={session} route={route} kind={kind} frame_bytes={length}"
        );
    }
    let mut bytes = vec![0; length];
    let mut received = 0;
    while received < length {
        let count = input.read(&mut bytes[received..]).await?;
        if count == 0 {
            bail!("control frame ended after {received} of {length} bytes")
        }
        received += count;
        if let Some((session, route)) = trace {
            eprintln!(
                "VOXY_BOOTSTRAP stage=OPEN_BODY session={session} route={route} received_bytes={received} frame_bytes={length}"
            );
        }
    }
    decode_control_payload(kind, &bytes).map(Some)
}

fn maximum_payload(kind: u8) -> Result<usize> {
    Ok(match kind {
        0x01 => 2 + MAX_DIMENSION_BYTES + 64 + 25 + 2 + MAX_SECTION_REQUESTS * 89,
        0x02 => 2 + MAX_SECTION_REQUESTS * 125,
        0x04 => 2 + MAX_SECTION_REQUESTS * 12,
        0x05 => 23 + MAX_SECTION_REQUESTS * 12,
        0x81 => 84,
        0x82 => {
            4 + MAX_SECTION_REQUESTS
                * (2 + MAX_DIMENSION_BYTES + 109 + 4 + MAX_DIMENSION_BYTES + 4096)
        }
        0x84 => 149,
        0x83 => zstd::zstd_safe::compress_bound(MAX_CATALOG_BYTES)
            .checked_add(76)
            .context("catalog frame extent overflow")?,
        S_RECORD => RECORD_SCOPE_BYTES + RECORD_DESCRIPTOR_BYTES + MAX_SECTION_COMPRESSED_BYTES,
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
            anchor_x: take_i32(&mut input)?,
            anchor_z: take_i32(&mut input)?,
            desires: take_desires(&mut input)?,
        },
        0x02 => {
            let count = take_u16(&mut input)?;
            let mut desires = Vec::with_capacity(count as usize);
            for _ in 0..count {
                desires.push(ScopedDesire {
                    dimension: take_u32(&mut input)?,
                    expected_world: take(&mut input, 32)?.try_into().unwrap(),
                    desire: take_desire(&mut input)?,
                });
            }
            ControlMessage::Desires(desires)
        }
        0x04 => {
            let count = take_u16(&mut input)?;
            let mut keys = Vec::with_capacity(count as usize);
            for _ in 0..count {
                let scoped = ScopedKey {
                    dimension: take_u32(&mut input)?,
                    key: take_u64(&mut input)?,
                };
                SectionKey::unpack(scoped.key)?;
                keys.push(scoped);
            }
            ControlMessage::Drop(keys)
        }
        0x05 => {
            let settings = take_settings(&mut input)?;
            let active_dimension = take_u32(&mut input)?;
            let count = take_u16(&mut input)?;
            let mut anchors = Vec::with_capacity(count as usize);
            for _ in 0..count {
                anchors.push(DimensionAnchor {
                    dimension: take_u32(&mut input)?,
                    x: take_i32(&mut input)?,
                    z: take_i32(&mut input)?,
                });
            }
            ControlMessage::Settings {
                settings,
                active_dimension,
                anchors,
            }
        }
        0x81 => ControlMessage::ServerHello {
            server_instance: take_u64(&mut input)?,
            active_dimension: take_u32(&mut input)?,
            world_identity: take(&mut input, 32)?.try_into().unwrap(),
            catalog_id: take_u64(&mut input)?,
            catalog_fingerprint: take(&mut input, 32)?.try_into().unwrap(),
        },
        0x82 => {
            let count = take_u16(&mut input)?;
            let mut dimensions = Vec::with_capacity(count as usize);
            for _ in 0..count {
                dimensions.push(DimensionMetadata {
                    id: take_u32(&mut input)?,
                    name: take_string(&mut input, MAX_DIMENSION_BYTES)?,
                    world_identity: take(&mut input, 32)?.try_into().unwrap(),
                    min_section_y: take_i32(&mut input)?,
                    section_count: take_u32(&mut input)?,
                    custom_border: take_bool(&mut input)?,
                    center_x: take_f64(&mut input)?,
                    center_z: take_f64(&mut input)?,
                    size: take_f64(&mut input)?,
                    catalog_id: take_u64(&mut input)?,
                    catalog_fingerprint: take(&mut input, 32)?.try_into().unwrap(),
                });
            }
            let count = take_u16(&mut input)?;
            let mut excluded = Vec::with_capacity(count as usize);
            for _ in 0..count {
                excluded.push((
                    take_string(&mut input, MAX_DIMENSION_BYTES)?,
                    take_string(&mut input, 4096)?,
                ));
            }
            ControlMessage::Manifest {
                dimensions,
                excluded,
            }
        }
        0x84 => {
            let dimension = take_u32(&mut input)?;
            let revision = take_u64(&mut input)?;
            let state = take_u8(&mut input)?;
            if state > 6 {
                bail!("invalid inventory state");
            }
            let x = take_i32(&mut input)?;
            let z = take_i32(&mut input)?;
            let mut saved = [0; 16];
            for bits in &mut saved {
                *bits = take_u64(&mut input)?;
            }
            ControlMessage::Inventory(InventoryRecord {
                dimension,
                revision,
                state,
                x,
                z,
                saved,
            })
        }
        0x83 => {
            let dimension = take_u32(&mut input)?;
            let world_identity = take(&mut input, 32)?.try_into().unwrap();
            let fingerprint = take(&mut input, 32)?.try_into().unwrap();
            let canonical_length = take_u32(&mut input)?;
            let size = take_u32(&mut input)? as usize;
            validate_catalog(fingerprint, canonical_length, size)?;
            ControlMessage::Catalog {
                dimension,
                world_identity,
                fingerprint,
                canonical_length,
                compressed: take(&mut input, size)?.to_vec(),
            }
        }
        S_RECORD => {
            let dimension = take_u32(&mut input)?;
            let world_identity = take(&mut input, 32)?.try_into().unwrap();
            let descriptor = RecordDescriptor::decode(take(&mut input, RECORD_DESCRIPTOR_BYTES)?)?;
            let compressed = if descriptor.status == RecordStatus::Data {
                take(&mut input, descriptor.binding.compressed_length as usize)?.to_vec()
            } else {
                Vec::new()
            };
            ControlMessage::Record {
                dimension,
                world_identity,
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

/// Writes the rest of a record after its type byte has been admitted by the session owner.
pub async fn write_record_body<W: AsyncWrite + Unpin>(
    out: &mut W,
    dimension: u32,
    world_identity: [u8; 32],
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
    out.write_u32_le((RECORD_SCOPE_BYTES + header.len() + body.len()) as u32)
        .await?;
    out.write_u32_le(dimension).await?;
    out.write_all(&world_identity).await?;
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
    out.push(u8::from(settings.refresh_allowed));
    Ok(())
}
fn take_settings(input: &mut &[u8]) -> Result<StreamingSettings> {
    let settings = StreamingSettings {
        interval_millis: take_u64(input)?,
        bandwidth_kbps: take_u64(input)?,
        refresh_allowed: take_bool(input)?,
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
        put_desire(out, *desire)?;
    }
    Ok(())
}
fn put_desire(out: &mut Vec<u8>, desire: Desire) -> Result<()> {
    desire.validate()?;
    out.extend_from_slice(&desire.ticket.to_le_bytes());
    out.extend_from_slice(&desire.key.to_le_bytes());
    out.push(desire.purpose);
    out.push(u8::from(desire.have.is_some()));
    if let Some(have) = desire.have {
        put_binding(out, have);
    }
    out.extend_from_slice(&desire.rank.to_le_bytes());
    Ok(())
}
fn take_desires(input: &mut &[u8]) -> Result<Vec<Desire>> {
    let count = take_u16(input)? as usize;
    if count > input.len() / 26 {
        bail!("truncated desires");
    }
    (0..count).map(|_| take_desire(input)).collect()
}
fn take_desire(input: &mut &[u8]) -> Result<Desire> {
    let ticket = take_u64(input)?;
    let key = take_u64(input)?;
    let purpose = take_u8(input)?;
    let have = if take_bool(input)? {
        Some(take_binding(input)?)
    } else {
        None
    };
    let rank = take_u64(input)?;
    let desire = Desire {
        ticket,
        key,
        purpose,
        rank,
        have,
    };
    desire.validate()?;
    Ok(desire)
}
fn take_i32(input: &mut &[u8]) -> Result<i32> {
    Ok(i32::from_le_bytes(take(input, 4)?.try_into().unwrap()))
}
fn take_f64(input: &mut &[u8]) -> Result<f64> {
    Ok(f64::from_le_bytes(take(input, 8)?.try_into().unwrap()))
}
fn take_bool(input: &mut &[u8]) -> Result<bool> {
    match take_u8(input)? {
        0 => Ok(false),
        1 => Ok(true),
        _ => bail!("invalid boolean"),
    }
}

#[cfg(test)]
mod bandwidth_tests {
    use super::*;

    #[test]
    fn uncapped_settings_round_trip_without_relaxing_other_bounds() {
        for bandwidth_kbps in [0, 100, 5_000, 20_000] {
            let settings = StreamingSettings {
                interval_millis: 2_000,
                bandwidth_kbps,
                refresh_allowed: true,
            };
            let mut bytes = Vec::new();
            put_settings(&mut bytes, settings).unwrap();
            let mut input = bytes.as_slice();
            assert_eq!(take_settings(&mut input).unwrap(), settings);
            assert!(input.is_empty());
        }
        for bandwidth_kbps in [1, 99, 20_001, u64::MAX] {
            assert!(
                StreamingSettings {
                    interval_millis: 2_000,
                    bandwidth_kbps,
                    refresh_allowed: true,
                }.validate().is_err()
            );
        }
        assert!(
            StreamingSettings {
                interval_millis: 999,
                bandwidth_kbps: 0,
                refresh_allowed: true,
            }.validate().is_err()
        );
    }
}
