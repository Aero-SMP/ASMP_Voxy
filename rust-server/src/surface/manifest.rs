use super::object::{CanonicalObject, ObjectHash, ObjectKind};
use crate::{take, take_i32, take_u16, take_u32, take_u64};
use anyhow::{Context, Result, bail};

const MANIFEST_MAGIC: &[u8; 8] = b"VXYMNFT\0";
const DESCRIPTOR_PAGE_MAGIC: &[u8; 8] = b"VXYDESC\0";
const DIRECTORY_MAGIC: &[u8; 8] = b"VXYDIR\0\0";
pub const MAX_SUBTREE_LEVELS: u8 = 5;
pub const DESCRIPTOR_PAGE_SLOTS: usize = 64;
pub const MAX_MANIFEST_BYTES: usize = 16 * 1024 * 1024;
const MAX_DEPENDENCIES_PER_CONTENT: usize = 256;
const MAX_NEIGHBOR_DEPENDENCIES_PER_CONTENT: usize = 6 * 64;
const MAX_OBJECT_REFERENCES: usize = 262_144;
pub const MAX_DIRECTORY_ENTRIES: usize = 4_096;
const DIRECTORY_ENTRY_BYTES: usize = 70;
pub const CONTENT_CLASS_COUNT: usize = 3;
const BOUNDARY_FACE_BYTES: usize = 32 * 32 / 8;
const MAX_BOUNDARY_SUMMARY_BYTES: usize = 6 * BOUNDARY_FACE_BYTES;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SpatialNode {
    /// Voxy hierarchy level: zero is finest and four is currently the coarsest.
    pub lod: u8,
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum DirectoryTarget {
    ManifestSubtree = 1,
    RootDirectory = 2,
}

impl TryFrom<u8> for DirectoryTarget {
    type Error = anyhow::Error;

    fn try_from(value: u8) -> Result<Self> {
        match value {
            1 => Ok(Self::ManifestSubtree),
            2 => Ok(Self::RootDirectory),
            _ => bail!("unknown surface root-directory target {value}"),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RootDirectoryEntry {
    /// First descendant in canonical signed-Morton order. This is a routing key, not a
    /// containment bound.
    pub node: SpatialNode,
    /// Inclusive extent in level-`node.lod` structural coordinates. Clients use this AABB for
    /// relevance; nested pages may cover many disjoint roots inside the conservative extent.
    pub min: [i32; 3],
    pub max: [i32; 3],
    pub target: DirectoryTarget,
    pub hash: ObjectHash,
}

impl RootDirectoryEntry {
    pub fn manifest(node: SpatialNode, hash: ObjectHash) -> Self {
        let point = [node.x, node.y, node.z];
        Self {
            node,
            min: point,
            max: point,
            target: DirectoryTarget::ManifestSubtree,
            hash,
        }
    }

    pub fn directory(node: SpatialNode, min: [i32; 3], max: [i32; 3], hash: ObjectHash) -> Self {
        Self {
            node,
            min,
            max,
            target: DirectoryTarget::RootDirectory,
            hash,
        }
    }

    pub fn union(entries: &[Self], hash: ObjectHash) -> Result<Self> {
        let first = *entries
            .first()
            .context("cannot route an empty nested directory")?;
        let min = std::array::from_fn(|axis| {
            entries
                .iter()
                .map(|entry| entry.min[axis])
                .min()
                .expect("entries are nonempty")
        });
        let max = std::array::from_fn(|axis| {
            entries
                .iter()
                .map(|entry| entry.max[axis])
                .max()
                .expect("entries are nonempty")
        });
        Ok(Self::directory(first.node, min, max, hash))
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RootDirectory {
    /// Entries are sorted by `(lod, x, y, z)` and cover disjoint structural roots.
    pub entries: Vec<RootDirectoryEntry>,
}

impl RootDirectory {
    pub fn new(entries: Vec<RootDirectoryEntry>) -> Result<Self> {
        let value = Self { entries };
        value.validate()?;
        Ok(value)
    }

    pub fn validate(&self) -> Result<()> {
        if self.entries.len() > MAX_DIRECTORY_ENTRIES {
            bail!("root directory exceeds {MAX_DIRECTORY_ENTRIES} entries");
        }
        let mut previous = None;
        for entry in &self.entries {
            if entry.hash.is_zero() || entry.node.lod != crate::MAX_LOD {
                bail!("root directory contains an invalid node or hash");
            }
            if (0..3).any(|axis| entry.min[axis] > entry.max[axis])
                || entry.node.x < entry.min[0]
                || entry.node.x > entry.max[0]
                || entry.node.y < entry.min[1]
                || entry.node.y > entry.max[1]
                || entry.node.z < entry.min[2]
                || entry.node.z > entry.max[2]
            {
                bail!("root directory contains an invalid routing extent");
            }
            if entry.target == DirectoryTarget::ManifestSubtree
                && (entry.min != entry.max
                    || entry.min != [entry.node.x, entry.node.y, entry.node.z])
            {
                bail!("manifest directory entry must describe exactly one top-level root");
            }
            let key = directory_morton_key(entry.node);
            if previous.is_some_and(|old| old >= key) {
                bail!("root directory entries are not strictly Morton sorted");
            }
            previous = Some(key);
        }
        Ok(())
    }

    pub fn encode(&self) -> Result<Vec<u8>> {
        let mut out = Vec::with_capacity(12 + self.entries.len() * DIRECTORY_ENTRY_BYTES);
        out.extend_from_slice(DIRECTORY_MAGIC);
        out.extend_from_slice(&(self.entries.len() as u32).to_le_bytes());
        for entry in &self.entries {
            out.push(entry.node.lod);
            out.push(entry.target as u8);
            out.extend_from_slice(&entry.node.x.to_le_bytes());
            out.extend_from_slice(&entry.node.y.to_le_bytes());
            out.extend_from_slice(&entry.node.z.to_le_bytes());
            for coordinate in entry.min {
                out.extend_from_slice(&coordinate.to_le_bytes());
            }
            for coordinate in entry.max {
                out.extend_from_slice(&coordinate.to_le_bytes());
            }
            out.extend_from_slice(entry.hash.as_bytes());
        }
        if out.len() > MAX_MANIFEST_BYTES {
            bail!("root directory exceeds {MAX_MANIFEST_BYTES} bytes");
        }
        Ok(out)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() < 12 || bytes.len() > MAX_MANIFEST_BYTES || &bytes[..8] != DIRECTORY_MAGIC {
            bail!("bad or oversized surface root directory");
        }
        let mut input = &bytes[8..];
        let count = take_u32(&mut input)? as usize;
        if count > MAX_DIRECTORY_ENTRIES
            || input.len()
                != count
                    .checked_mul(DIRECTORY_ENTRY_BYTES)
                    .context("root-directory size overflow")?
        {
            bail!("invalid surface root-directory entry count");
        }
        let mut entries = Vec::with_capacity(count);
        for _ in 0..count {
            let lod = take(&mut input, 1)?[0];
            let target = DirectoryTarget::try_from(take(&mut input, 1)?[0])?;
            let node = SpatialNode {
                lod,
                x: take_i32(&mut input)?,
                y: take_i32(&mut input)?,
                z: take_i32(&mut input)?,
            };
            let mut min = [0; 3];
            let mut max = [0; 3];
            for coordinate in &mut min {
                *coordinate = take_i32(&mut input)?;
            }
            for coordinate in &mut max {
                *coordinate = take_i32(&mut input)?;
            }
            entries.push(RootDirectoryEntry {
                node,
                min,
                max,
                target,
                hash: ObjectHash::from_bytes(take(&mut input, 32)?.try_into().unwrap())?,
            });
        }
        Self::new(entries)
    }

    pub fn canonical_object(&self) -> Result<CanonicalObject> {
        CanonicalObject::new(ObjectKind::RootDirectory, self.encode()?)
    }
}

/// Lexicographic octant path over sign-biased coordinates. Comparing these 96-bit keys is the
/// same as comparing a three-dimensional Morton code without truncating the i32 coordinate
/// domain into one machine integer.
pub fn directory_morton_key(node: SpatialNode) -> [u8; 32] {
    let coordinates = [node.x, node.y, node.z].map(|value| value as u32 ^ 0x8000_0000);
    std::array::from_fn(|bit| {
        let shift = 31 - bit;
        ((coordinates[0] >> shift) & 1) as u8
            | (((coordinates[1] >> shift) & 1) as u8) << 1
            | (((coordinates[2] >> shift) & 1) as u8) << 2
    })
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct QuantizedBounds {
    pub min: [u16; 3],
    pub max: [u16; 3],
}

impl QuantizedBounds {
    fn validate(self) -> Result<()> {
        if (0..3).any(|axis| self.min[axis] > self.max[axis]) {
            bail!("quantized bounds have a minimum above their maximum");
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
#[repr(u8)]
pub enum ContentClass {
    Exterior = 0,
    Interior = 1,
    Complex = 2,
}

impl ContentClass {
    pub const ALL: [Self; CONTENT_CLASS_COUNT] = [Self::Exterior, Self::Interior, Self::Complex];

    pub const fn index(self) -> usize {
        self as usize
    }
}

impl TryFrom<u8> for ContentClass {
    type Error = anyhow::Error;

    fn try_from(value: u8) -> Result<Self> {
        match value {
            0 => Ok(Self::Exterior),
            1 => Ok(Self::Interior),
            2 => Ok(Self::Complex),
            _ => bail!("unknown surface production content class {value}"),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct VisibilityMembership {
    pub domain: u64,
    pub microtile_mask: u64,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ContentDescriptor {
    /// Edge length in Voxy cells. Final production content is independently addressable 8³.
    pub microtile_edge: u8,
    /// Morton-ordered microtile availability within a 32^3 section.
    pub microtile_mask: u64,
    /// One object per set mask bit, in ascending Morton-bit order.
    pub objects: Vec<ObjectHash>,
    /// Extra immutable objects required before this content may activate.
    pub dependencies: Vec<ObjectHash>,
    /// Source-microtile masks for exact adjacent content, in canonical
    /// -X,+X,-Y,+Y,-Z,+Z face order. Each set bit consumes one hash from the matching dense
    /// vector, in ascending source-Morton order.
    pub neighbor_dependency_masks: [u64; 6],
    /// Exact adjacent complex microtiles required for boundary-correct activation. Dependencies
    /// are independently addressable and attached to one source microtile rather than the whole
    /// structural node.
    pub neighbor_dependencies: [Vec<ObjectHash>; 6],
    pub exterior_microtile_mask: u64,
    pub unknown_microtile_mask: u64,
    pub visibility_memberships: Vec<VisibilityMembership>,
    /// Present parent faces in -X,+X,-Y,+Y,-Z,+Z order.
    pub boundary_face_mask: u8,
    /// One 32×32 occupancy bitmap for every set face bit, in face order.
    pub boundary_summary: Vec<u8>,
}

impl ContentDescriptor {
    pub fn validate(&self) -> Result<()> {
        if self.microtile_edge != 8 || self.microtile_mask == 0 {
            bail!("content requires a nonempty 8³ microtile mask");
        }
        if self.objects.len() != self.microtile_mask.count_ones() as usize {
            bail!("microtile object count does not match its mask");
        }
        let neighbor_count = self
            .neighbor_dependencies
            .iter()
            .map(Vec::len)
            .sum::<usize>();
        if self.dependencies.len() > MAX_DEPENDENCIES_PER_CONTENT
            || neighbor_count > MAX_NEIGHBOR_DEPENDENCIES_PER_CONTENT
            || self.visibility_memberships.len() > MAX_DEPENDENCIES_PER_CONTENT
        {
            bail!("content has too many dependencies");
        }
        for face in 0..6 {
            let mask = self.neighbor_dependency_masks[face];
            if mask & !self.microtile_mask != 0
                || mask.count_ones() as usize != self.neighbor_dependencies[face].len()
            {
                bail!("per-microtile neighbor dependency mask and hashes disagree");
            }
        }
        if self.boundary_face_mask & !0x3f != 0
            || self.boundary_summary.len() > MAX_BOUNDARY_SUMMARY_BYTES
            || self.boundary_summary.len()
                != self.boundary_face_mask.count_ones() as usize * BOUNDARY_FACE_BYTES
        {
            bail!("content boundary mask and summary length disagree");
        }
        if self.exterior_microtile_mask & self.unknown_microtile_mask != 0
            || (self.exterior_microtile_mask | self.unknown_microtile_mask) & !self.microtile_mask
                != 0
        {
            bail!("content visibility masks reference absent microtiles");
        }
        let mut previous_domain = None;
        for membership in &self.visibility_memberships {
            if membership.domain < 2
                || membership.microtile_mask == 0
                || membership.microtile_mask & !self.microtile_mask != 0
                || previous_domain.is_some_and(|previous| previous >= membership.domain)
            {
                bail!("content visibility memberships are invalid or noncanonical");
            }
            previous_domain = Some(membership.domain);
        }
        if self
            .objects
            .iter()
            .chain(&self.dependencies)
            .chain(self.neighbor_dependencies.iter().flatten())
            .any(|hash| hash.is_zero())
        {
            bail!("content contains a reserved zero object hash");
        }
        if !strictly_sorted_unique(&self.dependencies) {
            bail!("content dependency hashes are not a canonical sorted set");
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct ManifestNode {
    /// Must exactly describe available immediate children in this manifest.
    pub child_mask: u8,
    pub bounds: Option<QuantizedBounds>,
    /// Unsigned Q16.16 screen-space/geometric error.
    pub geometric_error_q16: u32,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ManifestSubtree {
    pub root: SpatialNode,
    /// Number of structural levels represented by this object, including the root.
    pub levels: u8,
    /// One bit per breadth-first, Morton-ordered structural slot.
    pub tile_availability: Vec<u8>,
    /// One bit per fixed 64-slot descriptor page. Every page containing an available tile is
    /// advertised, while descriptor payloads remain independently requestable and bounded.
    pub descriptor_page_availability: Vec<u8>,
    /// One descriptor-page hash per available page bit, in ascending page order.
    pub descriptor_pages: Vec<ObjectHash>,
    /// Dense structural metadata for available tiles in structural-slot order. Contents must be
    /// absent in the structural manifest object.
    pub nodes: Vec<ManifestNode>,
}

impl ManifestSubtree {
    pub fn from_parts(
        root: SpatialNode,
        levels: u8,
        tile_availability: Vec<u8>,
        descriptor_page_availability: Vec<u8>,
        descriptor_pages: Vec<ObjectHash>,
        nodes: Vec<ManifestNode>,
    ) -> Result<Self> {
        let value = Self {
            root,
            levels,
            tile_availability,
            descriptor_page_availability,
            descriptor_pages,
            nodes,
        };
        value.validate()?;
        Ok(value)
    }

    pub fn structural_slots(&self) -> usize {
        slots_for_levels(self.levels)
    }

    pub fn descriptor_page_slots(&self) -> usize {
        descriptor_page_slots(self.levels)
    }

    pub fn validate(&self) -> Result<()> {
        if self.root.lod != crate::MAX_LOD || self.levels != MAX_SUBTREE_LEVELS {
            bail!("a manifest must expose all five levels from one LOD-4 root");
        }
        let slots = self.structural_slots();
        let page_slots = self.descriptor_page_slots();
        validate_bits(&self.tile_availability, slots, "tile")?;
        validate_bits(
            &self.descriptor_page_availability,
            page_slots,
            "descriptor-page",
        )?;
        if self.descriptor_pages.len() != popcount(&self.descriptor_page_availability)
            || self.descriptor_pages.iter().any(|hash| hash.is_zero())
        {
            bail!("descriptor-page hashes do not match availability");
        }
        if !bit(&self.tile_availability, 0) {
            bail!("manifest root is unavailable");
        }
        for page in 0..page_slots {
            let start = page * DESCRIPTOR_PAGE_SLOTS;
            let end = (start + DESCRIPTOR_PAGE_SLOTS).min(slots);
            let expected = (start..end).any(|slot| bit(&self.tile_availability, slot));
            if bit(&self.descriptor_page_availability, page) != expected {
                bail!("descriptor pages do not exactly cover available structural tiles");
            }
        }
        for depth in 1..self.levels {
            let offset = level_offset(depth);
            let parent_offset = level_offset(depth - 1);
            for morton in 0..8usize.pow(u32::from(depth)) {
                if bit(&self.tile_availability, offset + morton)
                    && !bit(&self.tile_availability, parent_offset + (morton >> 3))
                {
                    bail!("available manifest tile has an unavailable parent");
                }
            }
        }
        let present = popcount(&self.tile_availability);
        if present != self.nodes.len() {
            bail!("dense manifest node count does not match tile availability");
        }
        let mut dense = 0usize;
        for depth in 0..self.levels {
            let offset = level_offset(depth);
            for morton in 0..8usize.pow(u32::from(depth)) {
                if !bit(&self.tile_availability, offset + morton) {
                    continue;
                }
                let node = &self.nodes[dense];
                dense += 1;
                let expected_children = self.expected_child_mask(depth, morton);
                if node.child_mask != expected_children {
                    bail!("manifest child mask disagrees with availability");
                }
                if let Some(bounds) = node.bounds {
                    bounds.validate()?;
                }
            }
        }
        Ok(())
    }

    pub fn encode(&self) -> Result<Vec<u8>> {
        let slots = self.structural_slots();
        let page_slots = self.descriptor_page_slots();
        let mut out = Vec::new();
        out.extend_from_slice(MANIFEST_MAGIC);
        out.push(self.levels);
        out.push(self.root.lod);
        out.extend_from_slice(&self.root.x.to_le_bytes());
        out.extend_from_slice(&self.root.y.to_le_bytes());
        out.extend_from_slice(&self.root.z.to_le_bytes());
        out.extend_from_slice(&(slots as u32).to_le_bytes());
        out.extend_from_slice(&(page_slots as u32).to_le_bytes());
        out.extend_from_slice(&(self.nodes.len() as u32).to_le_bytes());
        out.extend_from_slice(&self.tile_availability);
        out.extend_from_slice(&self.descriptor_page_availability);
        for hash in &self.descriptor_pages {
            out.extend_from_slice(hash.as_bytes());
        }
        let mut dense = 0usize;
        for slot in 0..slots {
            if !bit(&self.tile_availability, slot) {
                continue;
            }
            let node = &self.nodes[dense];
            dense += 1;
            out.push(node.child_mask);
            out.push(node.bounds.is_some() as u8);
            out.extend_from_slice(&node.geometric_error_q16.to_le_bytes());
            let bounds = node.bounds.unwrap_or(QuantizedBounds {
                min: [0; 3],
                max: [0; 3],
            });
            for value in bounds.min.into_iter().chain(bounds.max) {
                out.extend_from_slice(&value.to_le_bytes());
            }
        }
        if out.len() > MAX_MANIFEST_BYTES {
            bail!("encoded manifest exceeds {MAX_MANIFEST_BYTES} bytes");
        }
        Ok(out)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() > MAX_MANIFEST_BYTES {
            bail!("manifest exceeds {MAX_MANIFEST_BYTES} bytes");
        }
        let mut input = bytes;
        if take(&mut input, 8)? != MANIFEST_MAGIC {
            bail!("bad surface manifest magic");
        }
        let levels = take(&mut input, 1)?[0];
        let root_lod = take(&mut input, 1)?[0];
        let root = SpatialNode {
            lod: root_lod,
            x: take_i32(&mut input)?,
            y: take_i32(&mut input)?,
            z: take_i32(&mut input)?,
        };
        if levels != MAX_SUBTREE_LEVELS || root.lod != crate::MAX_LOD {
            bail!("a manifest must be a complete five-level LOD-4 root");
        }
        let expected_slots = slots_for_levels(levels);
        let expected_page_slots = descriptor_page_slots(levels);
        let slots = take_u32(&mut input)? as usize;
        let page_slots = take_u32(&mut input)? as usize;
        let node_count = take_u32(&mut input)? as usize;
        if root.lod != crate::MAX_LOD
            || levels != MAX_SUBTREE_LEVELS
            || slots != expected_slots
            || page_slots != expected_page_slots
            || node_count > slots
        {
            bail!("non-canonical manifest slot counts");
        }
        let tile_bytes = bytes_for_bits(slots);
        let page_bytes = bytes_for_bits(page_slots);
        let tile_availability = take(&mut input, tile_bytes)?.to_vec();
        let descriptor_page_availability = take(&mut input, page_bytes)?.to_vec();
        validate_bits(&descriptor_page_availability, page_slots, "descriptor-page")?;
        let page_count = popcount(&descriptor_page_availability);
        let mut descriptor_pages = Vec::with_capacity(page_count);
        for _ in 0..page_count {
            descriptor_pages.push(ObjectHash::from_bytes(
                take(&mut input, 32)?.try_into().unwrap(),
            )?);
        }
        let mut nodes = Vec::with_capacity(node_count);
        for slot in 0..slots {
            if !bit(&tile_availability, slot) {
                continue;
            }
            let child_mask = take(&mut input, 1)?[0];
            let bounds_present = take(&mut input, 1)?[0];
            if bounds_present > 1 {
                bail!("invalid manifest node flags");
            }
            let geometric_error_q16 = take_u32(&mut input)?;
            let mut values = [0u16; 6];
            for value in &mut values {
                *value = take_u16(&mut input)?;
            }
            let bounds = if bounds_present == 1 {
                Some(QuantizedBounds {
                    min: values[..3].try_into().unwrap(),
                    max: values[3..].try_into().unwrap(),
                })
            } else {
                if values != [0; 6] {
                    bail!("absent manifest bounds contain nonzero data");
                }
                None
            };
            nodes.push(ManifestNode {
                child_mask,
                bounds,
                geometric_error_q16,
            });
        }
        if nodes.len() != node_count || !input.is_empty() {
            bail!("manifest node count or trailing data is invalid");
        }
        Self::from_parts(
            root,
            levels,
            tile_availability,
            descriptor_page_availability,
            descriptor_pages,
            nodes,
        )
    }

    pub fn canonical_object(&self) -> Result<CanonicalObject> {
        CanonicalObject::new(ObjectKind::ManifestSubtree, self.encode()?)
    }

    fn expected_child_mask(&self, depth: u8, morton: usize) -> u8 {
        let mut mask = 0u8;
        if depth + 1 < self.levels {
            let child_offset = level_offset(depth + 1);
            for child in 0..8 {
                if bit(&self.tile_availability, child_offset + morton * 8 + child) {
                    mask |= 1 << child;
                }
            }
        }
        mask
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ManifestDescriptorPage {
    pub root: SpatialNode,
    pub levels: u8,
    pub page_index: u16,
    /// One entry per local structural slot. Empty slots contain no descriptors.
    pub contents: Vec<[Option<ContentDescriptor>; CONTENT_CLASS_COUNT]>,
}

impl ManifestDescriptorPage {
    pub fn new(
        root: SpatialNode,
        levels: u8,
        page_index: u16,
        contents: Vec<[Option<ContentDescriptor>; CONTENT_CLASS_COUNT]>,
    ) -> Result<Self> {
        let value = Self {
            root,
            levels,
            page_index,
            contents,
        };
        value.validate()?;
        Ok(value)
    }

    pub fn validate(&self) -> Result<()> {
        if self.root.lod != crate::MAX_LOD || self.levels != MAX_SUBTREE_LEVELS {
            bail!("a descriptor page must belong to one complete LOD-4 manifest");
        }
        let expected = descriptor_page_slot_count(self.levels, usize::from(self.page_index))?;
        if self.contents.len() != expected || self.contents.iter().all(all_contents_absent) {
            bail!("descriptor page has a non-canonical slot count or is empty");
        }
        let mut object_references = 0usize;
        for contents in &self.contents {
            validate_contents(contents, &mut object_references)?;
        }
        if object_references > MAX_OBJECT_REFERENCES {
            bail!("descriptor page exceeds its object-reference bound");
        }
        Ok(())
    }

    pub fn encode(&self) -> Result<Vec<u8>> {
        let slot_count = self.contents.len();
        let availability = ContentClass::ALL.map(|class| {
            availability_with(
                self.contents
                    .iter()
                    .enumerate()
                    .filter_map(|(slot, contents)| contents[class.index()].as_ref().map(|_| slot)),
                slot_count,
            )
            .expect("validated descriptor slots fit their page")
        });
        let entry_count = (0..slot_count)
            .filter(|&slot| {
                ContentClass::ALL
                    .iter()
                    .any(|class| bit(&availability[class.index()], slot))
            })
            .count();
        let mut out = Vec::new();
        out.extend_from_slice(DESCRIPTOR_PAGE_MAGIC);
        out.extend_from_slice(&self.page_index.to_le_bytes());
        out.push(self.levels);
        out.push(self.root.lod);
        out.extend_from_slice(&self.root.x.to_le_bytes());
        out.extend_from_slice(&self.root.y.to_le_bytes());
        out.extend_from_slice(&self.root.z.to_le_bytes());
        out.extend_from_slice(&(slot_count as u16).to_le_bytes());
        out.extend_from_slice(&(entry_count as u16).to_le_bytes());
        for bits in &availability {
            out.extend_from_slice(bits);
        }
        for contents in &self.contents {
            for class in ContentClass::ALL {
                if let Some(content) = &contents[class.index()] {
                    encode_content_descriptor(&mut out, content)?;
                }
            }
        }
        if out.len() > MAX_MANIFEST_BYTES {
            bail!("encoded descriptor page exceeds {MAX_MANIFEST_BYTES} bytes");
        }
        Ok(out)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() > MAX_MANIFEST_BYTES {
            bail!("descriptor page exceeds {MAX_MANIFEST_BYTES} bytes");
        }
        let mut input = bytes;
        if take(&mut input, 8)? != DESCRIPTOR_PAGE_MAGIC {
            bail!("bad surface descriptor-page magic");
        }
        let page_index = take_u16(&mut input)?;
        let levels = take(&mut input, 1)?[0];
        let lod = take(&mut input, 1)?[0];
        let root = SpatialNode {
            lod,
            x: take_i32(&mut input)?,
            y: take_i32(&mut input)?,
            z: take_i32(&mut input)?,
        };
        let slot_count = take_u16(&mut input)? as usize;
        let entry_count = take_u16(&mut input)? as usize;
        if root.lod != crate::MAX_LOD
            || levels != MAX_SUBTREE_LEVELS
            || slot_count != descriptor_page_slot_count(levels, usize::from(page_index))?
            || entry_count > slot_count
        {
            bail!("non-canonical descriptor-page header");
        }
        let availability_bytes = bytes_for_bits(slot_count);
        let mut availability = Vec::with_capacity(CONTENT_CLASS_COUNT);
        for _ in 0..CONTENT_CLASS_COUNT {
            let bits = take(&mut input, availability_bytes)?.to_vec();
            validate_bits(&bits, slot_count, "descriptor content")?;
            availability.push(bits);
        }
        let availability: [Vec<u8>; CONTENT_CLASS_COUNT] = availability
            .try_into()
            .expect("descriptor content has a fixed class count");
        let actual_entries = (0..slot_count)
            .filter(|&slot| {
                ContentClass::ALL
                    .iter()
                    .any(|class| bit(&availability[class.index()], slot))
            })
            .count();
        if actual_entries != entry_count {
            bail!("descriptor-page entry count disagrees with availability");
        }
        let mut object_references = 0usize;
        let mut contents = vec![std::array::from_fn(|_| None); slot_count];
        for (slot, slot_contents) in contents.iter_mut().enumerate() {
            for class in ContentClass::ALL {
                if bit(&availability[class.index()], slot) {
                    slot_contents[class.index()] = Some(decode_content_descriptor(
                        &mut input,
                        &mut object_references,
                    )?);
                }
            }
        }
        if object_references > MAX_OBJECT_REFERENCES || !input.is_empty() {
            bail!("descriptor page exceeds its reference bound or contains trailing data");
        }
        Self::new(root, levels, page_index, contents)
    }

    pub fn canonical_object(&self) -> Result<CanonicalObject> {
        CanonicalObject::new(ObjectKind::ManifestDescriptorPage, self.encode()?)
    }
}

fn all_contents_absent(contents: &[Option<ContentDescriptor>; CONTENT_CLASS_COUNT]) -> bool {
    contents.iter().all(Option::is_none)
}

fn validate_contents(
    contents: &[Option<ContentDescriptor>; CONTENT_CLASS_COUNT],
    object_references: &mut usize,
) -> Result<()> {
    if all_contents_absent(contents) {
        return Ok(());
    }
    let masks = ContentClass::ALL.map(|class| {
        contents[class.index()]
            .as_ref()
            .map_or(0, |descriptor| descriptor.microtile_mask)
    });
    let ordinary = masks[ContentClass::Exterior.index()] | masks[ContentClass::Interior.index()];
    if ordinary & !masks[ContentClass::Complex.index()] != 0 {
        bail!("ordinary microtiles require complex companion availability");
    }
    if masks[ContentClass::Exterior.index()] & masks[ContentClass::Interior.index()] != 0 {
        bail!("exterior and interior microtiles must be disjoint");
    }
    for descriptor in contents.iter().flatten() {
        descriptor.validate()?;
        *object_references = object_references
            .checked_add(descriptor.objects.len())
            .and_then(|value| value.checked_add(descriptor.dependencies.len()))
            .and_then(|value| {
                value.checked_add(
                    descriptor
                        .neighbor_dependencies
                        .iter()
                        .map(Vec::len)
                        .sum::<usize>(),
                )
            })
            .context("descriptor-page object-reference count overflow")?;
    }
    Ok(())
}

fn encode_content_descriptor(out: &mut Vec<u8>, content: &ContentDescriptor) -> Result<()> {
    out.push(content.microtile_edge);
    out.push(0);
    out.push(0);
    out.push(content.boundary_face_mask);
    out.extend_from_slice(&(content.objects.len() as u16).to_le_bytes());
    out.extend_from_slice(&(content.dependencies.len() as u16).to_le_bytes());
    for dependencies in &content.neighbor_dependencies {
        out.extend_from_slice(&(dependencies.len() as u16).to_le_bytes());
    }
    out.extend_from_slice(&(content.boundary_summary.len() as u16).to_le_bytes());
    out.extend_from_slice(&(content.visibility_memberships.len() as u16).to_le_bytes());
    out.extend_from_slice(&content.microtile_mask.to_le_bytes());
    out.extend_from_slice(&content.exterior_microtile_mask.to_le_bytes());
    out.extend_from_slice(&content.unknown_microtile_mask.to_le_bytes());
    for mask in content.neighbor_dependency_masks {
        out.extend_from_slice(&mask.to_le_bytes());
    }
    for hash in content
        .objects
        .iter()
        .chain(&content.dependencies)
        .chain(content.neighbor_dependencies.iter().flatten())
    {
        out.extend_from_slice(hash.as_bytes());
    }
    for membership in &content.visibility_memberships {
        out.extend_from_slice(&membership.domain.to_le_bytes());
        out.extend_from_slice(&membership.microtile_mask.to_le_bytes());
    }
    out.extend_from_slice(&content.boundary_summary);
    Ok(())
}

fn decode_content_descriptor(
    input: &mut &[u8],
    object_references: &mut usize,
) -> Result<ContentDescriptor> {
    let microtile_edge = take(input, 1)?[0];
    if take(input, 1)?[0] != 0 || take(input, 1)?[0] != 0 {
        bail!("nonzero content flags");
    }
    let boundary_face_mask = take(input, 1)?[0];
    let object_count = take_u16(input)? as usize;
    let dependency_count = take_u16(input)? as usize;
    let mut neighbor_counts = [0usize; 6];
    for count in &mut neighbor_counts {
        *count = take_u16(input)? as usize;
    }
    let neighbor_dependency_count = neighbor_counts.iter().sum::<usize>();
    let boundary_summary_bytes = take_u16(input)? as usize;
    let visibility_membership_count = take_u16(input)? as usize;
    if dependency_count > MAX_DEPENDENCIES_PER_CONTENT
        || neighbor_dependency_count > MAX_NEIGHBOR_DEPENDENCIES_PER_CONTENT
        || visibility_membership_count > MAX_DEPENDENCIES_PER_CONTENT
        || boundary_summary_bytes > MAX_BOUNDARY_SUMMARY_BYTES
    {
        bail!("invalid content descriptor counts");
    }
    let microtile_mask = take_u64(input)?;
    let exterior_microtile_mask = take_u64(input)?;
    let unknown_microtile_mask = take_u64(input)?;
    let mut neighbor_dependency_masks = [0u64; 6];
    for mask in &mut neighbor_dependency_masks {
        *mask = take_u64(input)?;
    }
    *object_references = object_references
        .checked_add(object_count)
        .and_then(|value| value.checked_add(dependency_count))
        .and_then(|value| value.checked_add(neighbor_dependency_count))
        .context("descriptor-page object-reference count overflow")?;
    if *object_references > MAX_OBJECT_REFERENCES {
        bail!("descriptor page exceeds its object-reference bound");
    }
    let mut hashes =
        Vec::with_capacity(object_count + dependency_count + neighbor_dependency_count);
    for _ in 0..object_count + dependency_count + neighbor_dependency_count {
        hashes.push(ObjectHash::from_bytes(
            take(input, 32)?.try_into().unwrap(),
        )?);
    }
    let mut dependencies = hashes.split_off(object_count);
    let mut neighbors = dependencies.split_off(dependency_count);
    let mut neighbor_dependencies: [Vec<ObjectHash>; 6] = std::array::from_fn(|_| Vec::new());
    for (face, count) in neighbor_counts.into_iter().enumerate() {
        let remaining = neighbors.split_off(count);
        neighbor_dependencies[face] = neighbors;
        neighbors = remaining;
    }
    let mut visibility_memberships = Vec::with_capacity(visibility_membership_count);
    for _ in 0..visibility_membership_count {
        visibility_memberships.push(VisibilityMembership {
            domain: take_u64(input)?,
            microtile_mask: take_u64(input)?,
        });
    }
    let descriptor = ContentDescriptor {
        microtile_edge,
        microtile_mask,
        objects: hashes,
        dependencies,
        neighbor_dependency_masks,
        neighbor_dependencies,
        exterior_microtile_mask,
        unknown_microtile_mask,
        visibility_memberships,
        boundary_face_mask,
        boundary_summary: take(input, boundary_summary_bytes)?.to_vec(),
    };
    Ok(descriptor)
}

pub fn slots_for_levels(levels: u8) -> usize {
    (0..levels).map(|depth| 8usize.pow(u32::from(depth))).sum()
}

pub fn descriptor_page_slots(levels: u8) -> usize {
    slots_for_levels(levels).div_ceil(DESCRIPTOR_PAGE_SLOTS)
}

pub fn descriptor_page_slot_count(levels: u8, page_index: usize) -> Result<usize> {
    let slots = slots_for_levels(levels);
    let start = page_index
        .checked_mul(DESCRIPTOR_PAGE_SLOTS)
        .context("descriptor-page slot offset overflow")?;
    if start >= slots {
        bail!("descriptor-page index is outside the manifest");
    }
    Ok((slots - start).min(DESCRIPTOR_PAGE_SLOTS))
}

pub fn level_offset(depth: u8) -> usize {
    slots_for_levels(depth)
}

/// Path-order Morton index. Each successive three-bit octant is a child step, so immediate
/// children are contiguous and a node's parent is `index >> 3`.
pub fn morton3(x: u32, y: u32, z: u32, bits: u8) -> Result<usize> {
    if bits > MAX_SUBTREE_LEVELS
        || x >= (1u32 << bits)
        || y >= (1u32 << bits)
        || z >= (1u32 << bits)
    {
        bail!("coordinate is outside its Morton cube");
    }
    let mut result = 0usize;
    for bit_index in (0..bits).rev() {
        let octant =
            ((x >> bit_index) & 1) | (((y >> bit_index) & 1) << 1) | (((z >> bit_index) & 1) << 2);
        result = (result << 3) | octant as usize;
    }
    Ok(result)
}

pub fn availability_with(
    indices: impl IntoIterator<Item = usize>,
    slots: usize,
) -> Result<Vec<u8>> {
    let mut bits = vec![0; bytes_for_bits(slots)];
    for index in indices {
        if index >= slots {
            bail!("availability index {index} exceeds {slots} slots");
        }
        set_bit(&mut bits, index);
    }
    Ok(bits)
}

fn bytes_for_bits(bits: usize) -> usize {
    bits.div_ceil(8)
}

pub(crate) fn bit(bytes: &[u8], index: usize) -> bool {
    bytes[index / 8] & (1 << (index & 7)) != 0
}

fn set_bit(bytes: &mut [u8], index: usize) {
    bytes[index / 8] |= 1 << (index & 7);
}

fn popcount(bytes: &[u8]) -> usize {
    bytes.iter().map(|byte| byte.count_ones() as usize).sum()
}

fn strictly_sorted_unique(values: &[ObjectHash]) -> bool {
    values.windows(2).all(|pair| pair[0] < pair[1])
}

fn validate_bits(bytes: &[u8], bits: usize, label: &str) -> Result<()> {
    if bytes.len() != bytes_for_bits(bits) {
        bail!("{label} availability has a non-canonical length");
    }
    if bits & 7 != 0 {
        let allowed = (1u8 << (bits & 7)) - 1;
        if bytes.last().is_some_and(|byte| byte & !allowed != 0) {
            bail!("{label} availability has nonzero padding bits");
        }
    }
    Ok(())
}
