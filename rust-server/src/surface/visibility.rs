//! Exact, conservative visibility domains for the fixed-8 production surface.
//!
//! Level-zero passable cells are flood-filled inside each 32³ section and joined through
//! matching boundary cells. Missing neighbors are UNKNOWN, except the open +Y boundary above a
//! populated column, which is the dimension's exterior air source. Every solid/complex 8³
//! microtile records all air components that can expose it. Coarser hierarchy memberships are
//! deterministic unions of their level-zero descendants.

use super::{
    content::MICROTILE_EDGE,
    object::{CanonicalObject, ObjectHash, ObjectKind},
};
use crate::{
    key::SectionKey,
    lod::{Cell, SECTION_EDGE, SECTION_VOLUME, Section, cell_index},
    registry::RegistrySnapshot,
    take as take_bytes, take_i32, take_u8, take_u16, take_u32, take_u64,
};
use anyhow::{Context, Result, bail};
use serde::Deserialize;
use std::{
    collections::{BTreeMap, BTreeSet, VecDeque},
    mem::size_of,
    sync::Arc,
};

const MICROTILES_PER_SECTION_EDGE: i32 = 4;
const SOLID_LABEL: u16 = u16::MAX;
const UNKNOWN_LABEL: u16 = u16::MAX - 1;
const EXTERIOR_DOMAIN: u64 = 1;
const MAX_COMPONENTS: usize = 8_000_000;
const MAX_VISIBILITY_MEMBERSHIPS: usize = 256;
const VISIBILITY_MAGIC: &[u8; 8] = b"VXYVDIR\0";
const PAGE_MAGIC: &[u8; 8] = b"VXYVPAGE";
const SUMMARY_MAGIC: &[u8; 8] = b"VXYVSUM\0";
const SECTIONS_PER_PAGE: usize = 256;
const SUMMARY_PAGE_TARGET_BYTES: usize = 8 * 1024 * 1024;
const MAX_PAGES: usize = 262_144;
const MAX_REGIONS: usize = 262_144;
const MAX_SUMMARY_PAGES: usize = 1_048_576;
const MAX_SECTIONS: usize = 8_000_000;
const MAX_INDEX_BYTES: usize = 64 * 1024 * 1024;
const COLUMN_FLAG_OPEN: u32 = 1 << 8;
const DIRECTORY_FLAG_COMPLETE: u16 = 1 << 15;
const CHUNKS_PER_REGION: usize = 1024;

const FACES: [(i32, i32, i32); 6] = [
    (-1, 0, 0),
    (1, 0, 0),
    (0, -1, 0),
    (0, 1, 0),
    (0, 0, -1),
    (0, 0, 1),
];

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CameraDomain {
    Unknown,
    Exterior,
    Interior(u64),
}

/// Conservative visibility semantics selected from the dimension identity. Only the vanilla
/// Overworld is allowed to infer exterior reachability from open sky. Nether connectivity is
/// portal-only, while the End and every unknown/modded dimension stay conservatively visible.
#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq)]
#[serde(rename_all = "snake_case")]
#[repr(u8)]
pub enum DimensionVisibilityPolicy {
    SkyExterior = 1,
    PortalOnly = 2,
    Conservative = 3,
}

impl DimensionVisibilityPolicy {
    pub fn for_dimension(dimension: &str) -> Self {
        match dimension {
            "minecraft:overworld" => Self::SkyExterior,
            "minecraft:the_nether" => Self::PortalOnly,
            _ => Self::Conservative,
        }
    }

    /// Resolves one optional operator override while preserving the absolute safety rule that
    /// only the vanilla Overworld may infer exterior reachability from open sky.
    pub fn configured(dimension: &str, configured: Option<Self>) -> Result<Self> {
        let policy = configured.unwrap_or_else(|| Self::for_dimension(dimension));
        if policy == Self::SkyExterior && dimension != "minecraft:overworld" {
            bail!("sky_exterior visibility is only valid for minecraft:overworld");
        }
        Ok(policy)
    }

    fn decode(value: u8) -> Result<Self> {
        match value {
            1 => Ok(Self::SkyExterior),
            2 => Ok(Self::PortalOnly),
            3 => Ok(Self::Conservative),
            _ => bail!("unknown dimension visibility policy {value}"),
        }
    }
}

impl CameraDomain {
    pub fn wire(self) -> (u8, u64) {
        match self {
            Self::Unknown => (0, 0),
            Self::Exterior => (1, EXTERIOR_DOMAIN),
            Self::Interior(domain) => (2, domain),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CameraDomainLease {
    pub domain: CameraDomain,
    pub min: [i32; 3],
    pub max: [i32; 3],
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct DomainMembership {
    pub domain: u64,
    pub microtile_mask: u64,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct DescriptorVisibility {
    pub exterior_mask: u64,
    pub unknown_mask: u64,
    pub memberships: Vec<DomainMembership>,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
struct Component {
    exterior: bool,
    unknown: bool,
    adjacent: BTreeMap<SectionKey, u64>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct LocalSection {
    labels: LocalLabels,
    components: Vec<Component>,
    direct_exterior: u64,
    direct_unknown: u64,
}

#[derive(Clone, Debug, Eq, PartialEq)]
enum LocalLabels {
    Uniform(u16),
    Runs(Vec<LabelRun>),
}

impl LocalLabels {
    fn get(&self, index: usize) -> u16 {
        match self {
            Self::Uniform(label) => *label,
            Self::Runs(runs) => {
                let run = runs.partition_point(|run| usize::from(run.end) <= index);
                runs.get(run).map_or(UNKNOWN_LABEL, |run| run.label)
            }
        }
    }

    fn runs(&self) -> Vec<LabelRun> {
        match self {
            Self::Uniform(label) => vec![LabelRun {
                end: SECTION_VOLUME as u16,
                label: *label,
            }],
            Self::Runs(runs) => runs.clone(),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
struct TileCoordinate {
    x: i32,
    y: i32,
    z: i32,
}

#[derive(Clone, Debug, Default)]
struct TileVisibilityBuilder {
    exterior: bool,
    unknown: bool,
    domains: BTreeSet<u64>,
}

impl TileVisibilityBuilder {
    fn merge(&mut self, other: &Self) {
        self.exterior |= other.exterior;
        self.unknown |= other.unknown;
        self.domains.extend(other.domains.iter().copied());
    }

    fn freeze(self) -> TileVisibility {
        TileVisibility {
            exterior: self.exterior,
            unknown: self.unknown,
            domains: self.domains.into_iter().collect(),
        }
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
struct TileVisibility {
    exterior: bool,
    unknown: bool,
    domains: Vec<u64>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct LabelRun {
    end: u16,
    label: u16,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct SectionLookup {
    runs: Vec<LabelRun>,
    components: Vec<CameraDomain>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct ColumnCoverage {
    top: Option<i32>,
    chunks: u8,
}

impl SectionLookup {
    fn label(&self, index: usize) -> u16 {
        let run = self
            .runs
            .partition_point(|run| usize::from(run.end) <= index);
        self.runs.get(run).map_or(SOLID_LABEL, |run| run.label)
    }

    fn domain(&self, label: u16) -> CameraDomain {
        if label == SOLID_LABEL || label == UNKNOWN_LABEL {
            CameraDomain::Unknown
        } else {
            self.components
                .get(label as usize)
                .copied()
                .unwrap_or(CameraDomain::Unknown)
        }
    }
}

/// Root-local immutable visibility metadata. It is swapped only with the complete root whose
/// manifests contain the matching per-microtile memberships.
#[derive(Clone, Debug)]
pub struct VisibilityIndex {
    policy: DimensionVisibilityPolicy,
    complete: bool,
    levels: [BTreeMap<TileCoordinate, TileVisibility>; 5],
    sections: BTreeMap<u64, SectionLookup>,
    /// `None` identifies a generated all-air column in a dimension with no stored sections.
    /// Otherwise the value is the highest level-zero section represented by this root.
    columns: BTreeMap<(i32, i32), ColumnCoverage>,
}

#[derive(Clone, Debug)]
pub struct VisibilityObjectGraph {
    pub directory: CanonicalObject,
    pub pages: Vec<CanonicalObject>,
    pub summary_pages: Vec<CanonicalObject>,
}

#[derive(Clone, Copy, Debug)]
struct PageReference {
    first: u64,
    last: u64,
    hash: ObjectHash,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RegionalVisibilitySummary {
    pub region_x: i32,
    pub region_z: i32,
    pub source_marker: u64,
    source_chunks: Arc<Vec<RegionChunkSource>>,
    generated_coverage: Arc<BTreeMap<(i32, i32), u8>>,
    level_zero_y_bounds: Option<(i32, i32)>,
    locals: Arc<BTreeMap<SectionKey, LocalSection>>,
    /// Exact normalized source state represented by 64 independently addressed 8³ objects per
    /// level-zero section. Only hashes are retained in memory; decoded 32³ sections never become
    /// part of the published-state heap.
    source_microtiles: Arc<BTreeMap<SectionKey, [ObjectHash; 64]>>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RegionChunkSource {
    pub header_marker: u64,
    pub fingerprint: Option<u64>,
    /// Fingerprint of normalized block, biome and light inputs. Raw Anvil rewrites that preserve
    /// this value update metadata but do not rebuild or republish terrain.
    pub terrain_fingerprint: Option<[u64; 2]>,
}

impl RegionalVisibilitySummary {
    /// Conservative retained-heap accounting used to transfer a completed regional build from
    /// its temporary reservation into the root-lifetime reservation before temporary memory is
    /// released.
    pub fn retained_bytes(&self) -> Result<usize> {
        let mut bytes = size_of::<Self>()
            .checked_add(self.source_chunks.capacity() * size_of::<RegionChunkSource>())
            .and_then(|value| {
                value.checked_add(
                    self.generated_coverage.len() * (64 + size_of::<((i32, i32), u8)>()),
                )
            })
            .and_then(|value| {
                value.checked_add(
                    self.source_microtiles.len()
                        * (64 + size_of::<SectionKey>() + 64 * size_of::<ObjectHash>()),
                )
            })
            .context("regional visibility memory estimate overflow")?;
        for local in self.locals.values() {
            bytes = bytes
                .checked_add(64 + size_of::<SectionKey>() + size_of::<LocalSection>())
                .context("regional visibility memory estimate overflow")?;
            if let LocalLabels::Runs(labels) = &local.labels {
                bytes = bytes
                    .checked_add(labels.capacity() * size_of::<LabelRun>())
                    .context("regional visibility memory estimate overflow")?;
            }
            bytes = bytes
                .checked_add(local.components.capacity() * size_of::<Component>())
                .context("regional visibility memory estimate overflow")?;
            for component in &local.components {
                bytes = bytes
                    .checked_add(
                        component.adjacent.len()
                            * (64 + size_of::<SectionKey>() + size_of::<u64>()),
                    )
                    .context("regional visibility memory estimate overflow")?;
            }
        }
        Ok(bytes)
    }

    pub fn source_chunks(&self) -> &[RegionChunkSource] {
        &self.source_chunks
    }

    pub fn source_complete(&self) -> bool {
        self.source_chunks
            .iter()
            .all(|source| source.fingerprint.is_none() || source.terrain_fingerprint.is_some())
    }

    pub fn source_microtiles(&self) -> &BTreeMap<SectionKey, [ObjectHash; 64]> {
        &self.source_microtiles
    }

    pub fn with_source_metadata(
        &self,
        source_marker: u64,
        source_chunks: Vec<RegionChunkSource>,
    ) -> Result<Self> {
        if source_chunks.len() != CHUNKS_PER_REGION
            || coverage_from_sources(self.region_x, self.region_z, &source_chunks)?
                != *self.generated_coverage
        {
            bail!("metadata-only refresh changed generated coverage");
        }
        if self
            .source_chunks
            .iter()
            .zip(&source_chunks)
            .any(|(old, new)| old.terrain_fingerprint != new.terrain_fingerprint)
        {
            bail!("metadata-only refresh changed normalized terrain");
        }
        let mut updated = self.clone();
        updated.source_marker = source_marker;
        updated.source_chunks = Arc::new(source_chunks);
        Ok(updated)
    }

    /// Starts one bounded regional refresh. Large immutable maps remain shared until the first
    /// actually changed group is replaced, so metadata-only Anvil saves never duplicate a
    /// region's visibility/source state.
    pub fn begin_refresh(
        region_x: i32,
        region_z: i32,
        source_marker: u64,
        source_chunks: Vec<RegionChunkSource>,
        previous: Option<&Self>,
    ) -> Result<Self> {
        if source_chunks.len() != CHUNKS_PER_REGION {
            bail!("regional visibility source table must contain 1024 chunks");
        }
        let generated_coverage = coverage_from_sources(region_x, region_z, &source_chunks)?;
        if let Some(previous) = previous {
            if (previous.region_x, previous.region_z) != (region_x, region_z) {
                bail!("regional visibility refresh coordinates disagree");
            }
            let mut value = previous.clone();
            value.source_marker = source_marker;
            value.source_chunks = Arc::new(source_chunks);
            value.generated_coverage = Arc::new(generated_coverage);
            return Ok(value);
        }
        Ok(Self {
            region_x,
            region_z,
            source_marker,
            source_chunks: Arc::new(source_chunks),
            generated_coverage: Arc::new(generated_coverage),
            level_zero_y_bounds: None,
            locals: Arc::new(BTreeMap::new()),
            source_microtiles: Arc::new(BTreeMap::new()),
        })
    }

    /// Replaces one 2×2-chunk level-zero group after its compact source objects are durable.
    /// Decoded Sections are consumed only for this call and never retained in the summary.
    pub fn replace_group(
        &mut self,
        group: (i32, i32),
        sections: &BTreeMap<SectionKey, Section>,
        source_microtiles: &BTreeMap<SectionKey, [ObjectHash; 64]>,
        registry: &RegistrySnapshot,
    ) -> Result<()> {
        if group.0.div_euclid(16) != self.region_x || group.1.div_euclid(16) != self.region_z {
            bail!("visibility replacement group is outside its Anvil region");
        }
        if sections.keys().ne(source_microtiles.keys())
            || sections
                .keys()
                .any(|key| key.level != 0 || (key.x, key.z) != group)
        {
            bail!("visibility replacement contains mismatched or non-level-zero source state");
        }
        let coverage = self.generated_coverage.get(&group).copied();
        if !sections.is_empty() && coverage.is_none() {
            bail!("visibility replacement has no generated chunk coverage");
        }
        Arc::make_mut(&mut self.locals).retain(|key, _| (key.x, key.z) != group);
        Arc::make_mut(&mut self.source_microtiles).retain(|key, _| (key.x, key.z) != group);
        if let Some(coverage) = coverage {
            for (&key, section) in sections {
                Arc::make_mut(&mut self.locals)
                    .insert(key, analyze_section(key, section, registry, coverage)?);
            }
        }
        Arc::make_mut(&mut self.source_microtiles).extend(
            source_microtiles
                .iter()
                .map(|(&key, hashes)| (key, *hashes)),
        );
        Ok(())
    }

    pub fn finish_refresh(
        mut self,
        source_marker: u64,
        source_chunks: Vec<RegionChunkSource>,
    ) -> Result<Self> {
        if source_chunks.len() != CHUNKS_PER_REGION {
            bail!("regional visibility source table must contain 1024 chunks");
        }
        let generated_coverage =
            coverage_from_sources(self.region_x, self.region_z, &source_chunks)?;
        self.source_marker = source_marker;
        self.source_chunks = Arc::new(source_chunks);
        self.generated_coverage = Arc::new(generated_coverage);
        if self.locals.keys().ne(self.source_microtiles.keys()) {
            bail!("regional visibility and exact source-state section keys disagree");
        }
        if self
            .locals
            .keys()
            .any(|key| !self.generated_coverage.contains_key(&(key.x, key.z)))
        {
            bail!("regional visibility section is outside generated coverage");
        }
        self.level_zero_y_bounds = self
            .locals
            .keys()
            .map(|key| key.y)
            .fold(None::<(i32, i32)>, |bounds, y| {
                Some(bounds.map_or((y, y), |(min, max)| (min.min(y), max.max(y))))
            });
        Ok(self)
    }
}

#[derive(Clone, Debug)]
pub struct RecoveredVisibility {
    pub index: VisibilityIndex,
    pub regions: BTreeMap<(i32, i32), RegionalVisibilitySummary>,
}

#[derive(Clone, Debug)]
struct RegionReference {
    region_x: i32,
    region_z: i32,
    source_marker: u64,
    level_zero_y_bounds: Option<(i32, i32)>,
    pages: Vec<ObjectHash>,
}

impl VisibilityIndex {
    pub fn exact_build_memory_bound(
        regions: &BTreeMap<(i32, i32), RegionalVisibilitySummary>,
    ) -> Result<usize> {
        let mut retained = 0usize;
        let mut sections = 0usize;
        let mut components = 0usize;
        let mut adjacency = 0usize;
        let mut columns = 0usize;
        for region in regions.values() {
            retained = retained
                .checked_add(region.retained_bytes()?)
                .context("visibility-build estimate overflow")?;
            sections = sections
                .checked_add(region.locals.len())
                .context("visibility-build estimate overflow")?;
            columns = columns
                .checked_add(region.generated_coverage.len())
                .context("visibility-build estimate overflow")?;
            for local in region.locals.values() {
                components = components
                    .checked_add(local.components.len())
                    .context("visibility-build estimate overflow")?;
                adjacency = adjacency
                    .checked_add(
                        local
                            .components
                            .iter()
                            .map(|component| component.adjacent.len())
                            .sum::<usize>(),
                    )
                    .context("visibility-build estimate overflow")?;
            }
        }
        // Covers the cloned local graph, DSU/root tables, five tile levels, and temporary
        // parent-level builders. The 64-tile term is a true structural maximum per section.
        let section_bytes = sections
            .checked_mul(64 * 96 + 256)
            .context("visibility-build estimate overflow")?;
        let component_bytes = components
            .checked_mul(160)
            .context("visibility-build estimate overflow")?;
        let adjacency_bytes = adjacency
            .checked_mul(128)
            .context("visibility-build estimate overflow")?;
        let column_bytes = columns
            .checked_mul(128)
            .context("visibility-build estimate overflow")?;
        retained
            .checked_add(section_bytes)
            .and_then(|value| value.checked_add(component_bytes))
            .and_then(|value| value.checked_add(adjacency_bytes))
            .and_then(|value| value.checked_add(column_bytes))
            .and_then(|value| value.checked_add(32 * 1024 * 1024))
            .context("visibility-build estimate overflow")
    }

    pub fn conservative_build_memory_bound(
        regions: &BTreeMap<(i32, i32), RegionalVisibilitySummary>,
    ) -> Result<usize> {
        let columns = regions.values().try_fold(0usize, |total, region| {
            total
                .checked_add(region.generated_coverage.len())
                .context("visibility-build estimate overflow")
        })?;
        columns
            .checked_mul(128)
            .and_then(|value| value.checked_add(1024 * 1024))
            .context("visibility-build estimate overflow")
    }

    /// Creates a root-local conservative index without joining the regional portal graph. This
    /// is the mandatory first publication after terrain changes and the pressure fallback for
    /// an exact rebuild: all content is marked UNKNOWN until a later complete root is ready.
    pub fn conservative_from_regions(
        dimension: &str,
        regions: &BTreeMap<(i32, i32), RegionalVisibilitySummary>,
    ) -> Result<Self> {
        Self::conservative_from_regions_with_policy(
            dimension,
            DimensionVisibilityPolicy::for_dimension(dimension),
            regions,
        )
    }

    pub fn conservative_from_regions_with_policy(
        dimension: &str,
        policy: DimensionVisibilityPolicy,
        regions: &BTreeMap<(i32, i32), RegionalVisibilitySummary>,
    ) -> Result<Self> {
        let policy = DimensionVisibilityPolicy::configured(dimension, Some(policy))?;
        if regions.len() > MAX_REGIONS {
            bail!("visibility source region count exceeds {MAX_REGIONS}");
        }
        let mut columns = BTreeMap::new();
        for (&coordinate, region) in regions {
            if coordinate != (region.region_x, region.region_z) {
                bail!("visibility region map key disagrees with its summary");
            }
            for (&column, &chunks) in region.generated_coverage.iter() {
                if columns
                    .insert(
                        column,
                        ColumnCoverage {
                            top: region.level_zero_y_bounds.map(|(_, max)| max),
                            chunks,
                        },
                    )
                    .is_some()
                {
                    bail!("two regional visibility summaries contain one column");
                }
            }
        }
        Ok(Self {
            policy,
            complete: false,
            levels: std::array::from_fn(|_| BTreeMap::new()),
            sections: BTreeMap::new(),
            columns,
        })
    }

    pub fn is_complete(&self) -> bool {
        self.complete
    }

    pub fn retained_bytes(&self) -> Result<usize> {
        let mut bytes = size_of::<Self>();
        for level in &self.levels {
            for visibility in level.values() {
                bytes = bytes
                    .checked_add(64 + size_of::<TileCoordinate>() + size_of::<TileVisibility>())
                    .and_then(|value| {
                        value.checked_add(visibility.domains.capacity() * size_of::<u64>())
                    })
                    .context("visibility-index memory estimate overflow")?;
            }
        }
        for lookup in self.sections.values() {
            bytes = bytes
                .checked_add(64 + size_of::<u64>() + size_of::<SectionLookup>())
                .and_then(|value| value.checked_add(lookup.runs.capacity() * size_of::<LabelRun>()))
                .and_then(|value| {
                    value.checked_add(lookup.components.capacity() * size_of::<CameraDomain>())
                })
                .context("visibility-index memory estimate overflow")?;
        }
        bytes = bytes
            .checked_add(
                self.columns.len() * (64 + size_of::<(i32, i32)>() + size_of::<ColumnCoverage>()),
            )
            .context("visibility-index memory estimate overflow")?;
        Ok(bytes)
    }

    pub fn canonical_bytes_bound(
        &self,
        regions: &BTreeMap<(i32, i32), RegionalVisibilitySummary>,
    ) -> Result<usize> {
        // Page encoders never retain more than the decoded lookup/source data plus fixed record
        // framing and one hash table. A factor of two covers canonical Vec capacities and the
        // simultaneously retained CanonicalObject wrappers.
        let region_bytes = regions.values().try_fold(0usize, |total, region| {
            total
                .checked_add(region.retained_bytes()?)
                .context("visibility canonical-size estimate overflow")
        })?;
        self.retained_bytes()?
            .checked_add(region_bytes)
            .and_then(|value| value.checked_mul(2))
            .and_then(|value| value.checked_add(16 * 1024 * 1024))
            .context("visibility canonical-size estimate overflow")
    }

    pub fn build(
        dimension: &str,
        hierarchy: &BTreeMap<SectionKey, Section>,
        registry: &RegistrySnapshot,
        generated_coverage: &BTreeMap<(i32, i32), u8>,
        level_zero_y_bounds: Option<(i32, i32)>,
    ) -> Result<Self> {
        let level_zero = hierarchy
            .iter()
            .filter(|(key, _)| key.level == 0)
            .map(|(&key, section)| (key, section))
            .collect::<BTreeMap<_, _>>();
        if generated_coverage
            .values()
            .any(|coverage| *coverage == 0 || *coverage & !0x0f != 0)
        {
            bail!("visibility generated-chunk coverage is invalid");
        }
        let mut columns = generated_coverage
            .iter()
            .map(|(&column, &chunks)| {
                (
                    column,
                    ColumnCoverage {
                        top: level_zero_y_bounds.map(|(_, max)| max),
                        chunks,
                    },
                )
            })
            .collect::<BTreeMap<_, _>>();
        for key in level_zero.keys() {
            let column = columns
                .get_mut(&(key.x, key.z))
                .context("level-zero section is outside generated chunk coverage")?;
            column.top = Some(column.top.map_or(key.y, |top| top.max(key.y)));
        }

        let mut locals = BTreeMap::<SectionKey, LocalSection>::new();
        for (&key, section) in &level_zero {
            let coverage = columns
                .get(&(key.x, key.z))
                .context("level-zero section has no generated coverage")?
                .chunks;
            locals.insert(key, analyze_section(key, section, registry, coverage)?);
        }
        if let Some((min_y, max_y)) = level_zero_y_bounds {
            if min_y > max_y {
                bail!("visibility level-zero y bounds are reversed");
            }
            let height = i64::from(max_y) - i64::from(min_y) + 1;
            let total = i64::try_from(columns.len())
                .context("visibility column count overflow")?
                .checked_mul(height)
                .context("visibility generated-volume size overflow")?;
            if total > MAX_SECTIONS as i64 {
                bail!("visibility generated volume exceeds {MAX_SECTIONS} sections");
            }
            for (&(x, z), column) in &columns {
                for y in min_y..=max_y {
                    let key = SectionKey::new(0, x, y, z)?;
                    locals
                        .entry(key)
                        .or_insert_with(|| empty_local_section(column.chunks));
                }
            }
        }
        if locals.len() > MAX_SECTIONS {
            bail!("visibility graph exceeds {MAX_SECTIONS} sections");
        }
        finalize_visibility(
            dimension,
            DimensionVisibilityPolicy::for_dimension(dimension),
            locals,
            columns,
        )
    }

    /// Produces the immutable local connectivity/portal summary for one Anvil region. It is
    /// deliberately independent from neighboring regions, allowing unchanged source regions
    /// to survive restarts and later publications without reparsing their chunks.
    pub fn analyze_region(
        region_x: i32,
        region_z: i32,
        source_marker: u64,
        source_chunks: Vec<RegionChunkSource>,
        hierarchy: &BTreeMap<SectionKey, Section>,
        registry: &RegistrySnapshot,
        generated_coverage: &BTreeMap<(i32, i32), u8>,
        level_zero_y_bounds: Option<(i32, i32)>,
        source_microtiles: BTreeMap<SectionKey, [ObjectHash; 64]>,
    ) -> Result<RegionalVisibilitySummary> {
        if source_chunks.len() != CHUNKS_PER_REGION {
            bail!("regional visibility source table must contain 1024 chunks");
        }
        if coverage_from_sources(region_x, region_z, &source_chunks)? != *generated_coverage {
            bail!("regional visibility coverage disagrees with its chunk source table");
        }
        if generated_coverage.iter().any(|(&(x, z), &coverage)| {
            x.div_euclid(16) != region_x
                || z.div_euclid(16) != region_z
                || coverage == 0
                || coverage & !0x0f != 0
        }) {
            bail!("regional visibility coverage is outside its Anvil region or invalid");
        }
        let level_zero = hierarchy
            .iter()
            .filter(|(key, _)| key.level == 0)
            .map(|(&key, section)| (key, section))
            .collect::<BTreeMap<_, _>>();
        if level_zero
            .keys()
            .any(|key| key.x.div_euclid(16) != region_x || key.z.div_euclid(16) != region_z)
        {
            bail!("regional visibility hierarchy crosses an Anvil-region boundary");
        }
        let mut locals = BTreeMap::new();
        for (&key, section) in &level_zero {
            let coverage = *generated_coverage
                .get(&(key.x, key.z))
                .context("regional level-zero section has no generated coverage")?;
            locals.insert(key, analyze_section(key, section, registry, coverage)?);
        }
        if locals.keys().ne(source_microtiles.keys()) {
            bail!("regional visibility and exact source-state section keys disagree");
        }
        Ok(RegionalVisibilitySummary {
            region_x,
            region_z,
            source_marker,
            source_chunks: Arc::new(source_chunks),
            generated_coverage: Arc::new(generated_coverage.clone()),
            level_zero_y_bounds,
            locals: Arc::new(locals),
            source_microtiles: Arc::new(source_microtiles),
        })
    }

    /// Replaces only locally changed 32³ groups inside one persisted regional summary. The
    /// unchanged local flood-fill/portal summaries remain immutable and are reused verbatim.
    pub fn refresh_region(
        previous: &RegionalVisibilitySummary,
        source_marker: u64,
        source_chunks: Vec<RegionChunkSource>,
        affected_groups: &BTreeSet<(i32, i32)>,
        changed_hierarchy: &BTreeMap<SectionKey, Section>,
        changed_source_microtiles: BTreeMap<SectionKey, [ObjectHash; 64]>,
        registry: &RegistrySnapshot,
    ) -> Result<RegionalVisibilitySummary> {
        if source_chunks.len() != CHUNKS_PER_REGION {
            bail!("regional visibility source table must contain 1024 chunks");
        }
        if affected_groups.iter().any(|&(x, z)| {
            x.div_euclid(16) != previous.region_x || z.div_euclid(16) != previous.region_z
        }) {
            bail!("affected visibility group is outside its persisted source region");
        }
        let mut locals = previous.locals.as_ref().clone();
        locals.retain(|key, _| !affected_groups.contains(&(key.x, key.z)));
        let mut persisted_sources = previous.source_microtiles.as_ref().clone();
        persisted_sources.retain(|key, _| !affected_groups.contains(&(key.x, key.z)));
        let level_zero = changed_hierarchy
            .iter()
            .filter(|(key, _)| key.level == 0)
            .map(|(&key, section)| (key, section))
            .collect::<BTreeMap<_, _>>();
        let generated_coverage =
            coverage_from_sources(previous.region_x, previous.region_z, &source_chunks)?;
        for (&key, section) in &level_zero {
            if !affected_groups.contains(&(key.x, key.z)) {
                bail!("changed visibility hierarchy contains an unaffected group");
            }
            let coverage = *generated_coverage
                .get(&(key.x, key.z))
                .context("changed level-zero section has no generated coverage")?;
            locals.insert(key, analyze_section(key, section, registry, coverage)?);
        }
        if level_zero.keys().ne(changed_source_microtiles.keys()) {
            bail!("changed visibility and exact source-state section keys disagree");
        }
        persisted_sources.extend(changed_source_microtiles);
        let level_zero_y_bounds = locals
            .keys()
            .map(|key| key.y)
            .fold(None::<(i32, i32)>, |bounds, y| {
                Some(bounds.map_or((y, y), |(min, max)| (min.min(y), max.max(y))))
            });
        Ok(RegionalVisibilitySummary {
            region_x: previous.region_x,
            region_z: previous.region_z,
            source_marker,
            source_chunks: Arc::new(source_chunks),
            generated_coverage: Arc::new(generated_coverage),
            level_zero_y_bounds,
            locals: Arc::new(locals),
            source_microtiles: Arc::new(persisted_sources),
        })
    }

    /// Reconnects immutable local summaries into exact global domains. Only this inexpensive
    /// component/portal join is global; Anvil parsing and local flood fills remain incremental.
    pub fn from_regions(
        dimension: &str,
        regions: &BTreeMap<(i32, i32), RegionalVisibilitySummary>,
    ) -> Result<Self> {
        Self::from_regions_with_policy(
            dimension,
            DimensionVisibilityPolicy::for_dimension(dimension),
            regions,
        )
    }

    pub fn from_regions_with_policy(
        dimension: &str,
        policy: DimensionVisibilityPolicy,
        regions: &BTreeMap<(i32, i32), RegionalVisibilitySummary>,
    ) -> Result<Self> {
        let policy = DimensionVisibilityPolicy::configured(dimension, Some(policy))?;
        if regions.len() > MAX_REGIONS {
            bail!("visibility source region count exceeds {MAX_REGIONS}");
        }
        let mut locals = BTreeMap::new();
        let mut columns = BTreeMap::new();
        let mut bounds = None::<(i32, i32)>;
        for (&coordinate, region) in regions {
            if coordinate != (region.region_x, region.region_z) {
                bail!("visibility region map key disagrees with its summary");
            }
            if let Some((min_y, max_y)) = region.level_zero_y_bounds {
                if min_y > max_y {
                    bail!("regional visibility y bounds are reversed");
                }
                bounds = Some(bounds.map_or((min_y, max_y), |(min, max)| {
                    (min.min(min_y), max.max(max_y))
                }));
            }
            for (&column, &chunks) in region.generated_coverage.iter() {
                if columns
                    .insert(column, ColumnCoverage { top: None, chunks })
                    .is_some()
                {
                    bail!("two regional visibility summaries contain one column");
                }
            }
            for (&key, local) in region.locals.iter() {
                if locals.insert(key, local.clone()).is_some() {
                    bail!("two regional visibility summaries contain one section");
                }
                let column = columns
                    .get_mut(&(key.x, key.z))
                    .context("regional visibility section is outside generated coverage")?;
                column.top = Some(column.top.map_or(key.y, |top| top.max(key.y)));
            }
        }
        if let Some((min_y, max_y)) = bounds {
            for column in columns.values_mut() {
                column.top = Some(max_y);
            }
            let height = i64::from(max_y) - i64::from(min_y) + 1;
            let total = i64::try_from(columns.len())
                .context("visibility column count overflow")?
                .checked_mul(height)
                .context("visibility generated-volume size overflow")?;
            if total > MAX_SECTIONS as i64 {
                bail!("visibility generated volume exceeds {MAX_SECTIONS} sections");
            }
            for (&(x, z), column) in &columns {
                for y in min_y..=max_y {
                    locals
                        .entry(SectionKey::new(0, x, y, z)?)
                        .or_insert_with(|| empty_local_section(column.chunks));
                }
            }
        }
        finalize_visibility(dimension, policy, locals, columns)
    }

    pub fn descriptor(&self, key: SectionKey, content_mask: u64) -> DescriptorVisibility {
        if !self.complete || self.policy == DimensionVisibilityPolicy::Conservative {
            return DescriptorVisibility {
                exterior_mask: 0,
                unknown_mask: content_mask,
                memberships: Vec::new(),
            };
        }
        let mut value = DescriptorVisibility::default();
        let mut domains = BTreeMap::<u64, u64>::new();
        for tile in bits(content_mask) {
            let coordinate = tile_coordinate(key, tile);
            let Some(visibility) = self.levels[key.level as usize].get(&coordinate) else {
                // No adjacent passable component and no incomplete boundary means this
                // microtile is completely enclosed solid content. It has no eligible domain.
                continue;
            };
            if visibility.exterior {
                value.exterior_mask |= 1u64 << tile;
            } else if visibility.unknown {
                value.unknown_mask |= 1u64 << tile;
            }
            for &domain in &visibility.domains {
                *domains.entry(domain).or_default() |= 1u64 << tile;
            }
        }
        if domains.len() > MAX_VISIBILITY_MEMBERSHIPS {
            value.unknown_mask |= content_mask & !value.exterior_mask;
            return value;
        }
        value.memberships = domains
            .into_iter()
            .map(|(domain, microtile_mask)| DomainMembership {
                domain,
                microtile_mask,
            })
            .collect();
        value
    }

    pub fn canonical_objects(
        &self,
        regions: &BTreeMap<(i32, i32), RegionalVisibilitySummary>,
    ) -> Result<VisibilityObjectGraph> {
        let mut pages = Vec::new();
        let mut summary_pages = Vec::new();
        let directory = self.canonical_objects_to(regions, |object| {
            match object.kind() {
                ObjectKind::VisibilityPage => pages.push(object.clone()),
                ObjectKind::VisibilitySummaryPage => summary_pages.push(object.clone()),
                _ => bail!("visibility page sink received a non-page object"),
            }
            Ok(())
        })?;
        Ok(VisibilityObjectGraph {
            directory,
            pages,
            summary_pages,
        })
    }

    /// Emits bounded pages immediately. Publication therefore retains only compact page
    /// references instead of a world-sized Vec of canonical page payloads.
    pub fn canonical_objects_to(
        &self,
        regions: &BTreeMap<(i32, i32), RegionalVisibilitySummary>,
        mut write: impl FnMut(&CanonicalObject) -> Result<()>,
    ) -> Result<CanonicalObject> {
        let mut references = Vec::new();
        let mut chunk = Vec::with_capacity(SECTIONS_PER_PAGE);
        for entry in &self.sections {
            chunk.push(entry);
            if chunk.len() != SECTIONS_PER_PAGE {
                continue;
            }
            let bytes = encode_page(&chunk)?;
            let page = CanonicalObject::new(ObjectKind::VisibilityPage, bytes)?;
            references.push(PageReference {
                first: *chunk.first().expect("nonempty page").0,
                last: *chunk.last().expect("nonempty page").0,
                hash: page.hash(),
            });
            write(&page)?;
            chunk.clear();
        }
        if !chunk.is_empty() {
            let bytes = encode_page(&chunk)?;
            let page = CanonicalObject::new(ObjectKind::VisibilityPage, bytes)?;
            references.push(PageReference {
                first: *chunk.first().expect("nonempty page").0,
                last: *chunk.last().expect("nonempty page").0,
                hash: page.hash(),
            });
            write(&page)?;
        }
        if references.len() > MAX_PAGES {
            bail!("visibility directory exceeds {MAX_PAGES} pages");
        }
        let mut summary_page_count = 0usize;
        let mut region_references = Vec::with_capacity(regions.len());
        for (&coordinate, region) in regions {
            if coordinate != (region.region_x, region.region_z) {
                bail!("visibility region map key disagrees with its summary");
            }
            let pages = encode_summary_pages(region)?;
            let hashes = pages.iter().map(CanonicalObject::hash).collect();
            summary_page_count = summary_page_count
                .checked_add(pages.len())
                .context("visibility summary page count overflow")?;
            for page in &pages {
                write(page)?;
            }
            region_references.push(RegionReference {
                region_x: region.region_x,
                region_z: region.region_z,
                source_marker: region.source_marker,
                level_zero_y_bounds: region.level_zero_y_bounds,
                pages: hashes,
            });
        }
        if region_references.len() > MAX_REGIONS || summary_page_count > MAX_SUMMARY_PAGES {
            bail!("visibility regional summary graph exceeds its bounds");
        }
        CanonicalObject::new(
            ObjectKind::VisibilityDirectory,
            encode_directory(
                self.policy,
                self.complete,
                &references,
                &self.columns,
                &region_references,
            )?,
        )
    }

    pub fn from_canonical_graph<T: AsRef<CanonicalObject>>(
        directory: &CanonicalObject,
        mut page: impl FnMut(ObjectHash) -> Result<T>,
    ) -> Result<RecoveredVisibility> {
        if directory.kind() != ObjectKind::VisibilityDirectory {
            bail!("root visibility directory has the wrong type");
        }
        let (policy, complete, references, columns, region_references) =
            decode_directory(directory.bytes())?;
        let mut sections = BTreeMap::new();
        for reference in references {
            let object = page(reference.hash)?;
            let object = object.as_ref();
            if object.kind() != ObjectKind::VisibilityPage {
                bail!("root visibility page has the wrong type");
            }
            let decoded = decode_page(object.bytes())?;
            if decoded.first().map(|entry| entry.0.packed()) != Some(reference.first)
                || decoded.last().map(|entry| entry.0.packed()) != Some(reference.last)
            {
                bail!("visibility page range disagrees with its directory");
            }
            for (key, lookup) in decoded {
                if sections.insert(key.packed(), lookup).is_some() {
                    bail!("visibility graph contains a duplicate section");
                }
            }
        }
        let mut regions = BTreeMap::new();
        for reference in region_references {
            let mut locals = BTreeMap::new();
            let mut source_microtiles = BTreeMap::new();
            let mut source_chunks = None;
            for hash in &reference.pages {
                let object = page(*hash)?;
                let object = object.as_ref();
                if object.kind() != ObjectKind::VisibilitySummaryPage {
                    bail!("regional visibility summary has the wrong type");
                }
                let decoded =
                    decode_summary_page(object.bytes(), reference.region_x, reference.region_z)?;
                if let Some(chunks) = decoded.source_chunks {
                    if source_chunks.replace(chunks).is_some() {
                        bail!("regional visibility graph contains two source tables");
                    }
                }
                for (key, local) in decoded.locals {
                    if locals.insert(key, local).is_some() {
                        bail!("regional visibility pages contain a duplicate section");
                    }
                }
                for (key, hashes) in decoded.source_microtiles {
                    if source_microtiles.insert(key, hashes).is_some() {
                        bail!("regional source-state pages contain a duplicate section");
                    }
                }
            }
            let source_chunks =
                source_chunks.context("regional visibility graph has no source table")?;
            let generated_coverage = columns
                .iter()
                .filter(|((x, z), _)| {
                    x.div_euclid(16) == reference.region_x && z.div_euclid(16) == reference.region_z
                })
                .map(|(&coordinate, coverage)| (coordinate, coverage.chunks))
                .collect::<BTreeMap<_, _>>();
            if locals
                .keys()
                .any(|key| !generated_coverage.contains_key(&(key.x, key.z)))
            {
                bail!("regional visibility summary contains an ungenerated section");
            }
            if locals.keys().ne(source_microtiles.keys()) {
                bail!("regional visibility and source-state section keys disagree");
            }
            let recovered_y_bounds = locals
                .keys()
                .map(|key| key.y)
                .fold(None::<(i32, i32)>, |bounds, y| {
                    Some(bounds.map_or((y, y), |(min, max)| (min.min(y), max.max(y))))
                });
            if recovered_y_bounds != reference.level_zero_y_bounds {
                bail!("regional visibility y bounds disagree with its persisted sections");
            }
            if coverage_from_sources(reference.region_x, reference.region_z, &source_chunks)?
                != generated_coverage
            {
                bail!("regional visibility coverage disagrees with its persisted source table");
            }
            let summary = RegionalVisibilitySummary {
                region_x: reference.region_x,
                region_z: reference.region_z,
                source_marker: reference.source_marker,
                source_chunks: Arc::new(source_chunks),
                generated_coverage: Arc::new(generated_coverage),
                level_zero_y_bounds: reference.level_zero_y_bounds,
                locals: Arc::new(locals),
                source_microtiles: Arc::new(source_microtiles),
            };
            if regions
                .insert((reference.region_x, reference.region_z), summary)
                .is_some()
            {
                bail!("visibility directory contains a duplicate source region");
            }
        }
        if columns
            .keys()
            .any(|&(x, z)| !regions.contains_key(&(x.div_euclid(16), z.div_euclid(16))))
        {
            bail!("visibility directory column has no source-region marker");
        }
        Ok(RecoveredVisibility {
            index: Self {
                policy,
                complete,
                levels: std::array::from_fn(|_| BTreeMap::new()),
                sections,
                columns,
            },
            regions,
        })
    }

    /// Loads only the root-bound camera lookup. Regional summaries are internal publication
    /// state and can be much larger than the bounded lookup needed by a leased client root.
    pub fn from_canonical_index<T: AsRef<CanonicalObject>>(
        directory: &CanonicalObject,
        mut page: impl FnMut(ObjectHash) -> Result<T>,
    ) -> Result<Self> {
        if directory.kind() != ObjectKind::VisibilityDirectory {
            bail!("root visibility directory has the wrong type");
        }
        let (policy, complete, references, columns, _) = decode_directory(directory.bytes())?;
        let mut sections = BTreeMap::new();
        for reference in references {
            let object = page(reference.hash)?;
            let object = object.as_ref();
            if object.kind() != ObjectKind::VisibilityPage {
                bail!("root visibility page has the wrong type");
            }
            let decoded = decode_page(object.bytes())?;
            if decoded.first().map(|entry| entry.0.packed()) != Some(reference.first)
                || decoded.last().map(|entry| entry.0.packed()) != Some(reference.last)
            {
                bail!("visibility page range disagrees with its directory");
            }
            for (key, lookup) in decoded {
                if sections.insert(key.packed(), lookup).is_some() {
                    bail!("visibility graph contains a duplicate section");
                }
            }
        }
        Ok(Self {
            policy,
            complete,
            levels: std::array::from_fn(|_| BTreeMap::new()),
            sections,
            columns,
        })
    }

    /// Recovers only selected regional build summaries. An empty selection means all regions.
    /// Visibility lookup pages are deliberately skipped so terrain refresh does not duplicate
    /// the active camera index while decoded Anvil groups are resident.
    pub fn regions_from_canonical_graph<T: AsRef<CanonicalObject>>(
        directory: &CanonicalObject,
        wanted: &BTreeSet<(i32, i32)>,
        mut page: impl FnMut(ObjectHash) -> Result<T>,
    ) -> Result<BTreeMap<(i32, i32), RegionalVisibilitySummary>> {
        if directory.kind() != ObjectKind::VisibilityDirectory {
            bail!("root visibility directory has the wrong type");
        }
        let (_, _, _, columns, references) = decode_directory(directory.bytes())?;
        let mut regions = BTreeMap::new();
        for reference in references {
            let coordinate = (reference.region_x, reference.region_z);
            if !wanted.is_empty() && !wanted.contains(&coordinate) {
                continue;
            }
            let mut locals = BTreeMap::new();
            let mut source_microtiles = BTreeMap::new();
            let mut source_chunks = None;
            for hash in &reference.pages {
                let object = page(*hash)?;
                let object = object.as_ref();
                if object.kind() != ObjectKind::VisibilitySummaryPage {
                    bail!("regional visibility summary has the wrong type");
                }
                let decoded =
                    decode_summary_page(object.bytes(), reference.region_x, reference.region_z)?;
                if let Some(chunks) = decoded.source_chunks
                    && source_chunks.replace(chunks).is_some()
                {
                    bail!("regional visibility graph contains two source tables");
                }
                for (key, local) in decoded.locals {
                    if locals.insert(key, local).is_some() {
                        bail!("regional visibility pages contain a duplicate section");
                    }
                }
                for (key, hashes) in decoded.source_microtiles {
                    if source_microtiles.insert(key, hashes).is_some() {
                        bail!("regional source-state pages contain a duplicate section");
                    }
                }
            }
            let source_chunks =
                source_chunks.context("regional visibility graph has no source table")?;
            let generated_coverage =
                coverage_from_sources(reference.region_x, reference.region_z, &source_chunks)?;
            for (&column, &chunks) in &generated_coverage {
                if columns.get(&column).map(|coverage| coverage.chunks) != Some(chunks) {
                    bail!("regional visibility coverage disagrees with its directory");
                }
            }
            if locals
                .keys()
                .any(|key| !generated_coverage.contains_key(&(key.x, key.z)))
                || locals.keys().ne(source_microtiles.keys())
            {
                bail!("regional visibility/source state disagrees with generated coverage");
            }
            let recovered_y_bounds = locals
                .keys()
                .map(|key| key.y)
                .fold(None::<(i32, i32)>, |bounds, y| {
                    Some(bounds.map_or((y, y), |(min, max)| (min.min(y), max.max(y))))
                });
            if recovered_y_bounds != reference.level_zero_y_bounds {
                bail!("regional visibility y bounds disagree with its persisted sections");
            }
            regions.insert(
                coordinate,
                RegionalVisibilitySummary {
                    region_x: reference.region_x,
                    region_z: reference.region_z,
                    source_marker: reference.source_marker,
                    source_chunks: Arc::new(source_chunks),
                    generated_coverage: Arc::new(generated_coverage),
                    level_zero_y_bounds: reference.level_zero_y_bounds,
                    locals: Arc::new(locals),
                    source_microtiles: Arc::new(source_microtiles),
                },
            );
        }
        Ok(regions)
    }

    pub fn camera_domain(&self, block_x: i32, block_y: i32, block_z: i32) -> CameraDomainLease {
        let point = [block_x, block_y, block_z];
        let single = |domain| CameraDomainLease {
            domain,
            min: point,
            max: point,
        };
        if !self.complete || self.policy == DimensionVisibilityPolicy::Conservative {
            return single(CameraDomain::Unknown);
        }
        let section_x = block_x.div_euclid(SECTION_EDGE as i32);
        let section_y = block_y.div_euclid(SECTION_EDGE as i32);
        let section_z = block_z.div_euclid(SECTION_EDGE as i32);
        let Ok(key) = SectionKey::new(0, section_x, section_y, section_z) else {
            return single(CameraDomain::Unknown);
        };
        let local_x = block_x.rem_euclid(SECTION_EDGE as i32) as usize;
        let local_y = block_y.rem_euclid(SECTION_EDGE as i32) as usize;
        let local_z = block_z.rem_euclid(SECTION_EDGE as i32) as usize;
        let chunk = (local_x / 16) | ((local_z / 16) << 1);
        let Some(column) = self.columns.get(&(section_x, section_z)) else {
            return single(CameraDomain::Unknown);
        };
        if column.chunks & (1 << chunk) == 0 {
            return single(CameraDomain::Unknown);
        }
        let Some(section) = self.sections.get(&key.packed()) else {
            let known_exterior = column.top.is_none_or(|top| section_y > top);
            if !known_exterior {
                return single(CameraDomain::Unknown);
            }
            let section_min_x = section_x.saturating_mul(SECTION_EDGE as i32);
            let section_min_z = section_z.saturating_mul(SECTION_EDGE as i32);
            let (min_x, max_x, min_z, max_z) = if column.chunks == 0x0f {
                (
                    section_min_x,
                    section_min_x.saturating_add(SECTION_EDGE as i32 - 1),
                    section_min_z,
                    section_min_z.saturating_add(SECTION_EDGE as i32 - 1),
                )
            } else {
                let chunk_min_x = section_min_x.saturating_add(((chunk & 1) * 16) as i32);
                let chunk_min_z = section_min_z.saturating_add(((chunk >> 1) * 16) as i32);
                (
                    chunk_min_x,
                    chunk_min_x.saturating_add(15),
                    chunk_min_z,
                    chunk_min_z.saturating_add(15),
                )
            };
            let section_floor = section_y.saturating_mul(SECTION_EDGE as i32);
            let exterior_floor = column
                .top
                .and_then(|top| top.checked_add(1))
                .and_then(|top| top.checked_mul(SECTION_EDGE as i32))
                .map_or(section_floor, |top| top.max(section_floor));
            return CameraDomainLease {
                domain: CameraDomain::Exterior,
                min: [min_x, exterior_floor, min_z],
                max: [max_x, i32::MAX, max_z],
            };
        };
        let domain = section.domain(section.label(cell_index(local_x, local_y, local_z)));
        if section
            .runs
            .iter()
            .all(|run| section.domain(run.label) == domain)
        {
            let min = [
                section_x.saturating_mul(SECTION_EDGE as i32),
                section_y.saturating_mul(SECTION_EDGE as i32),
                section_z.saturating_mul(SECTION_EDGE as i32),
            ];
            return CameraDomainLease {
                domain,
                min,
                max: min.map(|value| value.saturating_add(SECTION_EDGE as i32 - 1)),
            };
        }
        let micro_min = [local_x & !7, local_y & !7, local_z & !7];
        let uniform_microtile = (micro_min[1]..micro_min[1] + MICROTILE_EDGE).all(|y| {
            (micro_min[2]..micro_min[2] + MICROTILE_EDGE).all(|z| {
                (micro_min[0]..micro_min[0] + MICROTILE_EDGE)
                    .all(|x| section.domain(section.label(cell_index(x, y, z))) == domain)
            })
        });
        if !uniform_microtile {
            return single(domain);
        }
        let section_min = [
            section_x.saturating_mul(SECTION_EDGE as i32),
            section_y.saturating_mul(SECTION_EDGE as i32),
            section_z.saturating_mul(SECTION_EDGE as i32),
        ];
        let min =
            std::array::from_fn(|axis| section_min[axis].saturating_add(micro_min[axis] as i32));
        CameraDomainLease {
            domain,
            min,
            max: min.map(|value| value.saturating_add(MICROTILE_EDGE as i32 - 1)),
        }
    }

    pub fn camera_metadata_matches(&self, other: &Self) -> bool {
        self.policy == other.policy
            && self.complete == other.complete
            && self.sections == other.sections
            && self.columns == other.columns
    }

    /// Returns the level-four roots whose persisted manifest memberships differ. `None` means
    /// that policy/completeness changed and no local comparison is valid. The merge walk does
    /// not clone either world-sized level map.
    pub fn content_metadata_changed_roots(
        &self,
        other: &Self,
    ) -> Result<Option<BTreeSet<SectionKey>>> {
        if self.policy != other.policy || self.complete != other.complete {
            return Ok(None);
        }
        let mut roots = BTreeSet::new();
        for level in 0..self.levels.len() {
            let mut left = self.levels[level].iter().peekable();
            let mut right = other.levels[level].iter().peekable();
            loop {
                let changed = match (left.peek(), right.peek()) {
                    (Some((left_key, left_value)), Some((right_key, right_value))) => {
                        match left_key.cmp(right_key) {
                            std::cmp::Ordering::Less => left.next().map(|(key, _)| *key),
                            std::cmp::Ordering::Greater => right.next().map(|(key, _)| *key),
                            std::cmp::Ordering::Equal => {
                                let key = **left_key;
                                let differs = left_value != right_value;
                                left.next();
                                right.next();
                                differs.then_some(key)
                            }
                        }
                    }
                    (Some(_), None) => left.next().map(|(key, _)| *key),
                    (None, Some(_)) => right.next().map(|(key, _)| *key),
                    (None, None) => break,
                };
                let Some(coordinate) = changed else {
                    continue;
                };
                let mut root = SectionKey::new(
                    level as u8,
                    coordinate.x.div_euclid(MICROTILES_PER_SECTION_EDGE),
                    coordinate.y.div_euclid(MICROTILES_PER_SECTION_EDGE),
                    coordinate.z.div_euclid(MICROTILES_PER_SECTION_EDGE),
                )?;
                while let Some(parent) = root.parent() {
                    root = parent;
                }
                roots.insert(root);
            }
        }
        Ok(Some(roots))
    }
}

fn finalize_visibility(
    dimension: &str,
    policy: DimensionVisibilityPolicy,
    mut locals: BTreeMap<SectionKey, LocalSection>,
    columns: BTreeMap<(i32, i32), ColumnCoverage>,
) -> Result<VisibilityIndex> {
    let policy = DimensionVisibilityPolicy::configured(dimension, Some(policy))?;
    if locals.len() > MAX_SECTIONS {
        bail!("visibility graph exceeds {MAX_SECTIONS} sections");
    }
    let mut offsets = BTreeMap::<SectionKey, usize>::new();
    let mut component_count = 0usize;
    for (&key, local) in &locals {
        offsets.insert(key, component_count);
        component_count = component_count
            .checked_add(local.components.len())
            .context("visibility component count overflow")?;
        if component_count > MAX_COMPONENTS {
            bail!("visibility graph exceeds {MAX_COMPONENTS} components");
        }
    }

    let mut dsu = DisjointSet::new(component_count);
    let keys = locals.keys().copied().collect::<Vec<_>>();
    for key in keys {
        for face in 0..6usize {
            let Some(neighbor) = offset_key(key, face)? else {
                let exterior = policy == DimensionVisibilityPolicy::SkyExterior
                    && face == 3
                    && columns.get(&(key.x, key.z)).and_then(|column| column.top) == Some(key.y);
                mark_open_face(key, face, exterior, &mut locals)?;
                continue;
            };
            if locals.contains_key(&neighbor) {
                if face == 1 || face == 3 || face == 5 {
                    connect_face(key, neighbor, face, &offsets, &mut locals, &mut dsu)?;
                }
                continue;
            }
            let exterior = policy == DimensionVisibilityPolicy::SkyExterior
                && face == 3
                && columns.get(&(key.x, key.z)).and_then(|column| column.top) == Some(key.y);
            mark_open_face(key, face, exterior, &mut locals)?;
        }
    }

    let mut roots = BTreeMap::<usize, GlobalComponent>::new();
    for (&key, local) in &locals {
        let offset = offsets[&key];
        for (local_id, component) in local.components.iter().enumerate() {
            let root = dsu.find(offset + local_id);
            let target = roots.entry(root).or_default();
            target.exterior |= component.exterior;
            target.unknown |= component.unknown;
            target.members.push((key, local_id as u16));
            for (&section, &mask) in &component.adjacent {
                *target.adjacent.entry(section).or_default() |= mask;
            }
        }
    }
    if policy == DimensionVisibilityPolicy::Conservative {
        for component in roots.values_mut() {
            component.exterior = false;
            component.unknown = true;
        }
    }
    assign_domains(dimension, &mut roots)?;

    let mut fine = BTreeMap::<TileCoordinate, TileVisibilityBuilder>::new();
    for (&key, local) in &locals {
        add_direct_tiles(&mut fine, key, local.direct_exterior, true);
        add_direct_tiles(&mut fine, key, local.direct_unknown, false);
    }
    for component in roots.values() {
        for (&section, &mask) in &component.adjacent {
            for tile in bits(mask) {
                let entry = fine.entry(tile_coordinate(section, tile)).or_default();
                if component.exterior {
                    entry.exterior = true;
                } else if component.unknown {
                    entry.unknown = true;
                } else {
                    entry.domains.insert(component.domain);
                }
            }
        }
    }

    let mut component_domains = vec![CameraDomain::Unknown; component_count];
    for (&root, component) in &roots {
        component_domains[root] = if component.exterior {
            CameraDomain::Exterior
        } else if component.unknown {
            CameraDomain::Unknown
        } else {
            CameraDomain::Interior(component.domain)
        };
    }
    for global in 0..component_count {
        let root = dsu.find(global);
        component_domains[global] = component_domains[root];
    }

    let sections = locals
        .into_iter()
        .map(|(key, local)| {
            let offset = offsets[&key];
            let components = (0..local.components.len())
                .map(|id| component_domains[offset + id])
                .collect();
            (
                key.packed(),
                SectionLookup {
                    runs: local.labels.runs(),
                    components,
                },
            )
        })
        .collect();

    let mut builders: [BTreeMap<TileCoordinate, TileVisibilityBuilder>; 5] =
        std::array::from_fn(|_| BTreeMap::new());
    builders[0] = fine;
    for level in 1..=4usize {
        let previous = builders[level - 1].clone();
        for (coordinate, value) in previous {
            let parent = TileCoordinate {
                x: coordinate.x.div_euclid(2),
                y: coordinate.y.div_euclid(2),
                z: coordinate.z.div_euclid(2),
            };
            builders[level].entry(parent).or_default().merge(&value);
        }
    }
    let levels = builders.map(|level| {
        level
            .into_iter()
            .map(|(coordinate, value)| (coordinate, value.freeze()))
            .collect()
    });
    Ok(VisibilityIndex {
        policy,
        complete: true,
        levels,
        sections,
        columns,
    })
}

fn coverage_from_sources(
    region_x: i32,
    region_z: i32,
    sources: &[RegionChunkSource],
) -> Result<BTreeMap<(i32, i32), u8>> {
    if sources.len() != CHUNKS_PER_REGION {
        bail!("regional visibility source table must contain 1024 chunks");
    }
    let base_group_x = region_x
        .checked_mul(16)
        .context("regional visibility group x overflow")?;
    let base_group_z = region_z
        .checked_mul(16)
        .context("regional visibility group z overflow")?;
    let mut coverage = BTreeMap::new();
    for (slot, source) in sources.iter().enumerate() {
        if source.fingerprint.is_none() {
            continue;
        }
        let local_chunk_x = slot & 31;
        let local_chunk_z = slot >> 5;
        let group_x = base_group_x
            .checked_add((local_chunk_x / 2) as i32)
            .context("regional visibility group x overflow")?;
        let group_z = base_group_z
            .checked_add((local_chunk_z / 2) as i32)
            .context("regional visibility group z overflow")?;
        let bit = (local_chunk_x & 1) | ((local_chunk_z & 1) << 1);
        *coverage.entry((group_x, group_z)).or_default() |= 1u8 << bit;
    }
    Ok(coverage)
}

fn encode_page(entries: &[(&u64, &SectionLookup)]) -> Result<Vec<u8>> {
    if entries.is_empty() || entries.len() > SECTIONS_PER_PAGE {
        bail!("visibility page section count is out of bounds");
    }
    let mut output = Vec::new();
    output.extend_from_slice(PAGE_MAGIC);
    output.extend_from_slice(&(entries.len() as u32).to_le_bytes());
    let mut previous = None;
    for &(&packed, ref lookup) in entries {
        let key = SectionKey::unpack(packed)?;
        if key.level != 0 || previous.is_some_and(|value| value >= packed) {
            bail!("visibility page keys are not canonical level-zero order");
        }
        previous = Some(packed);
        output.extend_from_slice(&packed.to_le_bytes());
        output.extend_from_slice(&(lookup.runs.len() as u32).to_le_bytes());
        output.extend_from_slice(&(lookup.components.len() as u32).to_le_bytes());
        for run in &lookup.runs {
            output.extend_from_slice(&run.end.to_le_bytes());
            output.extend_from_slice(&run.label.to_le_bytes());
        }
        for component in &lookup.components {
            let (state, domain) = component.wire();
            output.push(state);
            output.extend_from_slice(&domain.to_le_bytes());
        }
    }
    if output.len() > MAX_INDEX_BYTES {
        bail!("visibility page exceeds {MAX_INDEX_BYTES} bytes");
    }
    Ok(output)
}

fn decode_page(bytes: &[u8]) -> Result<Vec<(SectionKey, SectionLookup)>> {
    if bytes.len() < 12 || bytes.len() > MAX_INDEX_BYTES || &bytes[..8] != PAGE_MAGIC {
        bail!("invalid or oversized visibility page");
    }
    let mut input = &bytes[8..];
    let count = take_u32(&mut input)? as usize;
    if count == 0 || count > SECTIONS_PER_PAGE {
        bail!("visibility page section count is out of bounds");
    }
    let mut output = Vec::with_capacity(count);
    let mut previous = None;
    for _ in 0..count {
        let packed = take_u64(&mut input)?;
        if previous.is_some_and(|value| value >= packed) {
            bail!("visibility page section keys are not strictly sorted");
        }
        previous = Some(packed);
        let key = SectionKey::unpack(packed)?;
        if key.level != 0 {
            bail!("visibility page contains a non-level-zero key");
        }
        let run_count = take_u32(&mut input)? as usize;
        let component_count = take_u32(&mut input)? as usize;
        if run_count == 0 || run_count > SECTION_VOLUME || component_count > u16::MAX as usize {
            bail!("visibility page lookup counts are out of bounds");
        }
        let mut runs = Vec::with_capacity(run_count);
        let mut last_end = 0usize;
        for _ in 0..run_count {
            let end = take_u16(&mut input)?;
            let label = take_u16(&mut input)?;
            if usize::from(end) <= last_end || usize::from(end) > SECTION_VOLUME {
                bail!("visibility label runs are not canonical");
            }
            last_end = usize::from(end);
            runs.push(LabelRun { end, label });
        }
        if last_end != SECTION_VOLUME {
            bail!("visibility label runs do not cover one section");
        }
        let mut components = Vec::with_capacity(component_count);
        for _ in 0..component_count {
            let state = take_u8(&mut input)?;
            let domain = take_u64(&mut input)?;
            components.push(match (state, domain) {
                (0, 0) => CameraDomain::Unknown,
                (1, 1) => CameraDomain::Exterior,
                (2, 2..) => CameraDomain::Interior(domain),
                _ => bail!("visibility component state and domain disagree"),
            });
        }
        if runs.iter().any(|run| {
            run.label != SOLID_LABEL
                && run.label != UNKNOWN_LABEL
                && run.label as usize >= components.len()
        }) {
            bail!("visibility label references an absent component");
        }
        output.push((key, SectionLookup { runs, components }));
    }
    if !input.is_empty() {
        bail!("trailing visibility page bytes");
    }
    Ok(output)
}

fn encode_summary_pages(region: &RegionalVisibilitySummary) -> Result<Vec<CanonicalObject>> {
    if region.source_chunks.len() != CHUNKS_PER_REGION {
        bail!("regional visibility source table must contain 1024 chunks");
    }
    if region.locals.keys().ne(region.source_microtiles.keys()) {
        bail!("regional visibility and source-state section keys disagree");
    }
    let mut pages = Vec::new();
    let mut entries = Vec::<Vec<u8>>::new();
    let mut bytes = 26usize + CHUNKS_PER_REGION * 36;
    let mut keys = region.locals.keys().copied().collect::<Vec<_>>();
    keys.sort_unstable_by_key(|key| key.packed());
    for key in keys {
        let local = &region.locals[&key];
        let source_microtiles = region
            .source_microtiles
            .get(&key)
            .context("regional source-state section disappeared")?;
        let entry = encode_summary_entry(key, local, source_microtiles)?;
        if !entries.is_empty() && bytes.saturating_add(entry.len()) > SUMMARY_PAGE_TARGET_BYTES {
            pages.push(summary_page(
                region.region_x,
                region.region_z,
                pages.is_empty().then_some(region.source_chunks.as_slice()),
                &entries,
            )?);
            entries.clear();
            bytes = 28;
        }
        bytes = bytes
            .checked_add(entry.len())
            .context("regional visibility summary size overflow")?;
        if bytes > MAX_INDEX_BYTES {
            bail!("one regional visibility section exceeds the summary-page bound");
        }
        entries.push(entry);
    }
    if !entries.is_empty() || pages.is_empty() {
        pages.push(summary_page(
            region.region_x,
            region.region_z,
            pages.is_empty().then_some(region.source_chunks.as_slice()),
            &entries,
        )?);
    }
    Ok(pages)
}

fn summary_page(
    region_x: i32,
    region_z: i32,
    source_chunks: Option<&[RegionChunkSource]>,
    entries: &[Vec<u8>],
) -> Result<CanonicalObject> {
    if source_chunks.is_some_and(|chunks| chunks.len() != CHUNKS_PER_REGION) {
        bail!("regional visibility source table must contain 1024 chunks");
    }
    let mut output = Vec::new();
    output.extend_from_slice(SUMMARY_MAGIC);
    output.extend_from_slice(&u16::from(source_chunks.is_some()).to_le_bytes());
    output.extend_from_slice(&region_x.to_le_bytes());
    output.extend_from_slice(&region_z.to_le_bytes());
    output.extend_from_slice(&(entries.len() as u32).to_le_bytes());
    output
        .extend_from_slice(&(source_chunks.map_or(0, |chunks| chunks.len()) as u32).to_le_bytes());
    if let Some(chunks) = source_chunks {
        for chunk in chunks {
            output.extend_from_slice(&chunk.header_marker.to_le_bytes());
            output.extend_from_slice(&chunk.fingerprint.unwrap_or(0).to_le_bytes());
            let terrain = chunk.terrain_fingerprint.unwrap_or([0; 2]);
            output.extend_from_slice(&terrain[0].to_le_bytes());
            output.extend_from_slice(&terrain[1].to_le_bytes());
            let flags = u32::from(chunk.fingerprint.is_some())
                | (u32::from(chunk.terrain_fingerprint.is_some()) << 1);
            output.extend_from_slice(&flags.to_le_bytes());
        }
    }
    for entry in entries {
        output.extend_from_slice(entry);
    }
    CanonicalObject::new(ObjectKind::VisibilitySummaryPage, output)
}

fn encode_summary_entry(
    key: SectionKey,
    local: &LocalSection,
    source_microtiles: &[ObjectHash; 64],
) -> Result<Vec<u8>> {
    let runs = local.labels.runs();
    if key.level != 0
        || runs.is_empty()
        || runs.len() > SECTION_VOLUME
        || source_microtiles.iter().any(|hash| hash.is_zero())
    {
        bail!("invalid regional visibility summary section");
    }
    let mut output = Vec::new();
    output.extend_from_slice(&key.packed().to_le_bytes());
    output.extend_from_slice(&local.direct_exterior.to_le_bytes());
    output.extend_from_slice(&local.direct_unknown.to_le_bytes());
    output.extend_from_slice(&(runs.len() as u32).to_le_bytes());
    output.extend_from_slice(&(local.components.len() as u32).to_le_bytes());
    output.extend_from_slice(&(source_microtiles.len() as u32).to_le_bytes());
    for run in runs {
        output.extend_from_slice(&run.end.to_le_bytes());
        output.extend_from_slice(&run.label.to_le_bytes());
    }
    for component in &local.components {
        let flags = u8::from(component.exterior) | (u8::from(component.unknown) << 1);
        output.push(flags);
        output.extend_from_slice(&[0; 3]);
        output.extend_from_slice(&(component.adjacent.len() as u32).to_le_bytes());
        for (&adjacent, &mask) in &component.adjacent {
            output.extend_from_slice(&adjacent.packed().to_le_bytes());
            output.extend_from_slice(&mask.to_le_bytes());
        }
    }
    for hash in source_microtiles {
        output.extend_from_slice(hash.as_bytes());
    }
    Ok(output)
}

struct DecodedSummaryPage {
    source_chunks: Option<Vec<RegionChunkSource>>,
    locals: Vec<(SectionKey, LocalSection)>,
    source_microtiles: Vec<(SectionKey, [ObjectHash; 64])>,
}

fn decode_summary_page(
    bytes: &[u8],
    expected_region_x: i32,
    expected_region_z: i32,
) -> Result<DecodedSummaryPage> {
    if bytes.len() < 26 || bytes.len() > MAX_INDEX_BYTES || &bytes[..8] != SUMMARY_MAGIC {
        bail!("invalid or oversized regional visibility summary page");
    }
    let mut input = &bytes[8..];
    let flags = take_u16(&mut input)?;
    if flags & !1 != 0
        || take_i32(&mut input)? != expected_region_x
        || take_i32(&mut input)? != expected_region_z
    {
        bail!("regional visibility summary envelope disagrees with its directory");
    }
    let count = take_u32(&mut input)? as usize;
    let source_count = take_u32(&mut input)? as usize;
    if count > MAX_SECTIONS || source_count != if flags & 1 != 0 { CHUNKS_PER_REGION } else { 0 } {
        bail!("regional visibility summary counts are out of bounds");
    }
    let source_chunks = if source_count == 0 {
        None
    } else {
        let mut chunks = Vec::with_capacity(source_count);
        for _ in 0..source_count {
            let header_marker = take_u64(&mut input)?;
            let fingerprint = take_u64(&mut input)?;
            let terrain = [take_u64(&mut input)?, take_u64(&mut input)?];
            let state = take_u32(&mut input)?;
            if state & !3 != 0
                || (state & 1 == 0 && fingerprint != 0)
                || (state & 2 == 0 && terrain != [0; 2])
                || (state & 2 != 0 && state & 1 == 0)
            {
                bail!("regional visibility chunk source state is invalid");
            }
            chunks.push(RegionChunkSource {
                header_marker,
                fingerprint: (state & 1 != 0).then_some(fingerprint),
                terrain_fingerprint: (state & 2 != 0).then_some(terrain),
            });
        }
        Some(chunks)
    };
    let mut output = Vec::with_capacity(count);
    let mut source_sections = Vec::with_capacity(count);
    let mut previous = None;
    for _ in 0..count {
        let packed = take_u64(&mut input)?;
        if previous.is_some_and(|old| old >= packed) {
            bail!("regional visibility summary keys are not strictly sorted");
        }
        previous = Some(packed);
        let key = SectionKey::unpack(packed)?;
        if key.level != 0
            || key.x.div_euclid(16) != expected_region_x
            || key.z.div_euclid(16) != expected_region_z
        {
            bail!("regional visibility summary key is outside its region");
        }
        let direct_exterior = take_u64(&mut input)?;
        let direct_unknown = take_u64(&mut input)?;
        let run_count = take_u32(&mut input)? as usize;
        let component_count = take_u32(&mut input)? as usize;
        let source_count = take_u32(&mut input)? as usize;
        if run_count == 0
            || run_count > SECTION_VOLUME
            || component_count > u16::MAX as usize
            || source_count != 64
        {
            bail!("regional visibility summary counts are out of bounds");
        }
        let mut runs = Vec::with_capacity(run_count);
        let mut end = 0usize;
        for _ in 0..run_count {
            let next = take_u16(&mut input)?;
            let label = take_u16(&mut input)?;
            if usize::from(next) <= end || usize::from(next) > SECTION_VOLUME {
                bail!("regional visibility summary label runs are invalid");
            }
            end = usize::from(next);
            runs.push(LabelRun { end: next, label });
        }
        if end != SECTION_VOLUME {
            bail!("regional visibility summary labels do not cover one section");
        }
        let mut components = Vec::with_capacity(component_count);
        for _ in 0..component_count {
            let flags = take_u8(&mut input)?;
            if flags & !3 != 0 || take_bytes(&mut input, 3)? != [0; 3] {
                bail!("regional visibility component flags are invalid");
            }
            let adjacent_count = take_u32(&mut input)? as usize;
            if adjacent_count > MAX_SECTIONS {
                bail!("regional visibility component adjacency exceeds its bound");
            }
            let mut adjacent = BTreeMap::new();
            for _ in 0..adjacent_count {
                let adjacent_key = SectionKey::unpack(take_u64(&mut input)?)?;
                let mask = take_u64(&mut input)?;
                if mask == 0 || adjacent.insert(adjacent_key, mask).is_some() {
                    bail!("regional visibility component adjacency is invalid");
                }
            }
            components.push(Component {
                exterior: flags & 1 != 0,
                unknown: flags & 2 != 0,
                adjacent,
            });
        }
        if runs.iter().any(|run| {
            run.label != SOLID_LABEL
                && run.label != UNKNOWN_LABEL
                && run.label as usize >= components.len()
        }) {
            bail!("regional visibility label references an absent component");
        }
        let labels = if runs.len() == 1 {
            LocalLabels::Uniform(runs[0].label)
        } else {
            LocalLabels::Runs(runs)
        };
        let mut hashes = [ObjectHash::ZERO; 64];
        for hash in &mut hashes {
            *hash = ObjectHash::from_bytes(take_bytes(&mut input, 32)?.try_into().unwrap())?;
        }
        source_sections.push((key, hashes));
        output.push((
            key,
            LocalSection {
                labels,
                components,
                direct_exterior,
                direct_unknown,
            },
        ));
    }
    if !input.is_empty() {
        bail!("trailing regional visibility summary bytes");
    }
    Ok(DecodedSummaryPage {
        source_chunks,
        locals: output,
        source_microtiles: source_sections,
    })
}

/// Returns every exact source-state object referenced by one canonical regional summary page.
/// GC uses this parser so its reachability rules cannot drift from the publication codec.
pub fn summary_page_references(bytes: &[u8]) -> Result<Vec<ObjectHash>> {
    if bytes.len() < 20 || &bytes[..8] != SUMMARY_MAGIC {
        bail!("invalid regional visibility summary envelope");
    }
    let region_x = i32::from_le_bytes(bytes[12..16].try_into().unwrap());
    let region_z = i32::from_le_bytes(bytes[16..20].try_into().unwrap());
    Ok(decode_summary_page(bytes, region_x, region_z)?
        .source_microtiles
        .into_iter()
        .flat_map(|(_, hashes)| hashes)
        .collect())
}

fn encode_directory(
    policy: DimensionVisibilityPolicy,
    complete: bool,
    pages: &[PageReference],
    columns: &BTreeMap<(i32, i32), ColumnCoverage>,
    regions: &[RegionReference],
) -> Result<Vec<u8>> {
    let summary_count = regions
        .iter()
        .map(|region| region.pages.len())
        .sum::<usize>();
    if pages.len() > MAX_PAGES
        || columns.len() > MAX_SECTIONS
        || regions.len() > MAX_REGIONS
        || summary_count > MAX_SUMMARY_PAGES
    {
        bail!("visibility directory exceeds its entry bounds");
    }
    let mut output = Vec::new();
    output.extend_from_slice(VISIBILITY_MAGIC);
    let flags = policy as u16 | if complete { DIRECTORY_FLAG_COMPLETE } else { 0 };
    output.extend_from_slice(&flags.to_le_bytes());
    output.extend_from_slice(&(pages.len() as u32).to_le_bytes());
    output.extend_from_slice(&(columns.len() as u32).to_le_bytes());
    output.extend_from_slice(&(regions.len() as u32).to_le_bytes());
    output.extend_from_slice(&(summary_count as u32).to_le_bytes());
    let mut previous = None;
    for page in pages {
        if page.first > page.last
            || page.hash.is_zero()
            || previous.is_some_and(|last| last >= page.first)
        {
            bail!("visibility page ranges are invalid or overlapping");
        }
        previous = Some(page.last);
        output.extend_from_slice(&page.first.to_le_bytes());
        output.extend_from_slice(&page.last.to_le_bytes());
        output.extend_from_slice(page.hash.as_bytes());
    }
    for (&(x, z), &column) in columns {
        output.extend_from_slice(&x.to_le_bytes());
        output.extend_from_slice(&z.to_le_bytes());
        output.extend_from_slice(&column.top.unwrap_or(0).to_le_bytes());
        let flags = u32::from(column.chunks)
            | if column.top.is_none() {
                COLUMN_FLAG_OPEN
            } else {
                0
            };
        output.extend_from_slice(&flags.to_le_bytes());
    }
    let mut previous_region = None;
    let mut first_page = 0u32;
    for region in regions {
        let coordinate = (region.region_x, region.region_z);
        if previous_region.is_some_and(|old| old >= coordinate) {
            bail!("visibility source regions are not in canonical order");
        }
        previous_region = Some(coordinate);
        output.extend_from_slice(&region.region_x.to_le_bytes());
        output.extend_from_slice(&region.region_z.to_le_bytes());
        output.extend_from_slice(&region.source_marker.to_le_bytes());
        let (min_y, max_y, flags) = region
            .level_zero_y_bounds
            .map_or((0, 0, 0u32), |(min, max)| (min, max, 1));
        if flags != 0 && min_y > max_y {
            bail!("visibility source region y bounds are reversed");
        }
        output.extend_from_slice(&min_y.to_le_bytes());
        output.extend_from_slice(&max_y.to_le_bytes());
        output.extend_from_slice(&flags.to_le_bytes());
        output.extend_from_slice(&first_page.to_le_bytes());
        output.extend_from_slice(&(region.pages.len() as u32).to_le_bytes());
        first_page = first_page
            .checked_add(region.pages.len() as u32)
            .context("visibility summary page range overflow")?;
    }
    for region in regions {
        for hash in &region.pages {
            output.extend_from_slice(hash.as_bytes());
        }
    }
    if output.len() > MAX_INDEX_BYTES {
        bail!("visibility directory exceeds {MAX_INDEX_BYTES} bytes");
    }
    Ok(output)
}

type DecodedDirectory = (
    DimensionVisibilityPolicy,
    bool,
    Vec<PageReference>,
    BTreeMap<(i32, i32), ColumnCoverage>,
    Vec<RegionReference>,
);

fn decode_directory(bytes: &[u8]) -> Result<DecodedDirectory> {
    if bytes.len() < 26 || bytes.len() > MAX_INDEX_BYTES || &bytes[..8] != VISIBILITY_MAGIC {
        bail!("invalid or oversized visibility directory");
    }
    let mut input = &bytes[8..];
    let policy_raw = take_u16(&mut input)?;
    if policy_raw & !(DIRECTORY_FLAG_COMPLETE | 0x00ff) != 0 {
        bail!("visibility directory policy has nonzero reserved bits");
    }
    let complete = policy_raw & DIRECTORY_FLAG_COMPLETE != 0;
    let policy = DimensionVisibilityPolicy::decode((policy_raw & 0x00ff) as u8)?;
    let page_count = take_u32(&mut input)? as usize;
    let column_count = take_u32(&mut input)? as usize;
    let region_count = take_u32(&mut input)? as usize;
    let summary_count = take_u32(&mut input)? as usize;
    if page_count > MAX_PAGES
        || column_count > MAX_SECTIONS
        || region_count > MAX_REGIONS
        || summary_count > MAX_SUMMARY_PAGES
    {
        bail!("visibility directory counts exceed their bounds");
    }
    let expected = page_count
        .checked_mul(48)
        .and_then(|value| value.checked_add(column_count.checked_mul(16)?))
        .and_then(|value| value.checked_add(region_count.checked_mul(36)?))
        .and_then(|value| value.checked_add(summary_count.checked_mul(32)?))
        .context("visibility directory size overflow")?;
    if input.len() != expected {
        bail!("visibility directory counts disagree with its length");
    }
    let mut pages = Vec::with_capacity(page_count);
    let mut previous = None;
    for _ in 0..page_count {
        let first = take_u64(&mut input)?;
        let last = take_u64(&mut input)?;
        let hash = ObjectHash::from_bytes(take_bytes(&mut input, 32)?.try_into().unwrap())?;
        if first > last || previous.is_some_and(|value| value >= first) {
            bail!("visibility directory page ranges overlap");
        }
        previous = Some(last);
        pages.push(PageReference { first, last, hash });
    }
    let mut columns = BTreeMap::new();
    for _ in 0..column_count {
        let x = take_i32(&mut input)?;
        let z = take_i32(&mut input)?;
        let top = take_i32(&mut input)?;
        let flags = take_u32(&mut input)?;
        let chunks = (flags & 0x0f) as u8;
        let open = flags & COLUMN_FLAG_OPEN != 0;
        if flags & !(COLUMN_FLAG_OPEN | 0x0f) != 0
            || chunks == 0
            || (open && top != 0)
            || columns
                .insert(
                    (x, z),
                    ColumnCoverage {
                        top: (!open).then_some(top),
                        chunks,
                    },
                )
                .is_some()
        {
            bail!("visibility directory columns are invalid or duplicated");
        }
    }
    let mut raw_regions = Vec::with_capacity(region_count);
    let mut previous_region = None;
    for _ in 0..region_count {
        let region_x = take_i32(&mut input)?;
        let region_z = take_i32(&mut input)?;
        let coordinate = (region_x, region_z);
        if previous_region.is_some_and(|old| old >= coordinate) {
            bail!("visibility directory source regions are not canonical");
        }
        previous_region = Some(coordinate);
        let source_marker = take_u64(&mut input)?;
        let min_y = take_i32(&mut input)?;
        let max_y = take_i32(&mut input)?;
        let flags = take_u32(&mut input)?;
        let first = take_u32(&mut input)? as usize;
        let count = take_u32(&mut input)? as usize;
        if flags & !1 != 0
            || (flags & 1 == 0 && (min_y != 0 || max_y != 0))
            || (flags & 1 != 0 && min_y > max_y)
            || first
                .checked_add(count)
                .is_none_or(|end| end > summary_count)
        {
            bail!("visibility source region metadata is invalid");
        }
        raw_regions.push((
            region_x,
            region_z,
            source_marker,
            min_y,
            max_y,
            flags,
            first,
            count,
        ));
    }
    let mut summary_hashes = Vec::with_capacity(summary_count);
    for _ in 0..summary_count {
        summary_hashes.push(ObjectHash::from_bytes(
            take_bytes(&mut input, 32)?.try_into().unwrap(),
        )?);
    }
    let mut expected_first = 0usize;
    for region in &raw_regions {
        if region.6 != expected_first {
            bail!("visibility summary page ranges are not contiguous");
        }
        expected_first = expected_first
            .checked_add(region.7)
            .context("visibility summary page range overflow")?;
    }
    if expected_first != summary_count {
        bail!("visibility summary page ranges do not cover their hash table");
    }
    let regions = raw_regions
        .into_iter()
        .map(
            |(region_x, region_z, source_marker, min_y, max_y, flags, first, count)| {
                Ok(RegionReference {
                    region_x,
                    region_z,
                    source_marker,
                    level_zero_y_bounds: (flags & 1 != 0).then_some((min_y, max_y)),
                    pages: summary_hashes[first..first + count].to_vec(),
                })
            },
        )
        .collect::<Result<Vec<_>>>()?;
    Ok((policy, complete, pages, columns, regions))
}

pub(crate) struct VisibilityReferences {
    pub pages: Vec<ObjectHash>,
    pub summary_pages: Vec<ObjectHash>,
}

pub(crate) fn object_references(bytes: &[u8]) -> Result<VisibilityReferences> {
    let (_, _, pages, _, regions) = decode_directory(bytes)?;
    Ok(VisibilityReferences {
        pages: pages.into_iter().map(|page| page.hash).collect(),
        summary_pages: regions
            .into_iter()
            .flat_map(|region| region.pages)
            .collect(),
    })
}

#[derive(Clone, Debug, Default)]
struct GlobalComponent {
    exterior: bool,
    unknown: bool,
    domain: u64,
    members: Vec<(SectionKey, u16)>,
    adjacent: BTreeMap<SectionKey, u64>,
}

fn analyze_section(
    key: SectionKey,
    section: &Section,
    registry: &RegistrySnapshot,
    coverage: u8,
) -> Result<LocalSection> {
    if key.level != 0 || section.key != key || section.cells.len() != SECTION_VOLUME {
        bail!("visibility analysis requires one canonical level-zero section");
    }
    if coverage == 0x0f && section.cells.iter().all(|&cell| passable(cell, registry)) {
        let mut component = Component::default();
        for y in 0..SECTION_EDGE {
            for z in 0..SECTION_EDGE {
                for x in 0..SECTION_EDGE {
                    if !section.cells[cell_index(x, y, z)].is_air() {
                        *component.adjacent.entry(key).or_default() |= 1u64 << microtile(x, y, z);
                    }
                }
            }
        }
        return Ok(LocalSection {
            labels: LocalLabels::Uniform(0),
            components: vec![component],
            direct_exterior: 0,
            direct_unknown: 0,
        });
    }
    if coverage == 0x0f && section.cells.iter().all(|&cell| !passable(cell, registry)) {
        return Ok(LocalSection {
            labels: LocalLabels::Uniform(SOLID_LABEL),
            components: Vec::new(),
            direct_exterior: 0,
            direct_unknown: 0,
        });
    }
    let mut labels = vec![SOLID_LABEL; SECTION_VOLUME];
    for y in 0..SECTION_EDGE {
        for z in 0..SECTION_EDGE {
            for x in 0..SECTION_EDGE {
                if !cell_known(x, z, coverage) {
                    labels[cell_index(x, y, z)] = UNKNOWN_LABEL;
                }
            }
        }
    }
    let mut components = Vec::new();
    let mut direct_unknown = 0u64;
    let mut queue = VecDeque::new();
    for y in 0..SECTION_EDGE {
        for z in 0..SECTION_EDGE {
            for x in 0..SECTION_EDGE {
                let start = cell_index(x, y, z);
                if labels[start] != SOLID_LABEL {
                    continue;
                }
                if !passable(section.cells[start], registry) {
                    if FACES.into_iter().any(|(dx, dy, dz)| {
                        let nx = x as i32 + dx;
                        let ny = y as i32 + dy;
                        let nz = z as i32 + dz;
                        (0..SECTION_EDGE as i32).contains(&nx)
                            && (0..SECTION_EDGE as i32).contains(&ny)
                            && (0..SECTION_EDGE as i32).contains(&nz)
                            && labels[cell_index(nx as usize, ny as usize, nz as usize)]
                                == UNKNOWN_LABEL
                    }) {
                        direct_unknown |= 1u64 << microtile(x, y, z);
                    }
                    continue;
                }
                if components.len() >= UNKNOWN_LABEL as usize {
                    bail!("one section exceeds the visibility component label bound");
                }
                let id: u16 = components
                    .len()
                    .try_into()
                    .context("one section exceeds the u16 component bound")?;
                labels[start] = id;
                queue.push_back((x, y, z));
                let mut component = Component::default();
                while let Some((cx, cy, cz)) = queue.pop_front() {
                    let cell = section.cells[cell_index(cx, cy, cz)];
                    if !cell.is_air() {
                        *component.adjacent.entry(key).or_default() |=
                            1u64 << microtile(cx, cy, cz);
                    }
                    for (dx, dy, dz) in FACES {
                        let nx = cx as i32 + dx;
                        let ny = cy as i32 + dy;
                        let nz = cz as i32 + dz;
                        if !(0..SECTION_EDGE as i32).contains(&nx)
                            || !(0..SECTION_EDGE as i32).contains(&ny)
                            || !(0..SECTION_EDGE as i32).contains(&nz)
                        {
                            continue;
                        }
                        let (nx, ny, nz) = (nx as usize, ny as usize, nz as usize);
                        let index = cell_index(nx, ny, nz);
                        if labels[index] == UNKNOWN_LABEL {
                            component.unknown = true;
                            continue;
                        }
                        if passable(section.cells[index], registry) {
                            if labels[index] == SOLID_LABEL {
                                labels[index] = id;
                                queue.push_back((nx, ny, nz));
                            }
                        } else {
                            *component.adjacent.entry(key).or_default() |=
                                1u64 << microtile(nx, ny, nz);
                        }
                    }
                }
                components.push(component);
            }
        }
    }
    Ok(LocalSection {
        labels: LocalLabels::Runs(encode_dense_runs(&labels)),
        components,
        direct_exterior: 0,
        direct_unknown,
    })
}

fn cell_known(x: usize, z: usize, coverage: u8) -> bool {
    let chunk = (x / 16) | ((z / 16) << 1);
    coverage & (1u8 << chunk) != 0
}

fn empty_local_section(coverage: u8) -> LocalSection {
    if coverage == 0x0f {
        return LocalSection {
            labels: LocalLabels::Uniform(0),
            components: vec![Component::default()],
            direct_exterior: 0,
            direct_unknown: 0,
        };
    }
    let mut roots = [None; 4];
    let mut components = Vec::new();
    for chunk in 0..4usize {
        if coverage & (1 << chunk) == 0 {
            continue;
        }
        let left = (chunk & 1 != 0).then_some(chunk - 1);
        let below = (chunk & 2 != 0).then_some(chunk - 2);
        let existing = left
            .into_iter()
            .chain(below)
            .filter_map(|neighbor| roots[neighbor])
            .min();
        let component = existing.unwrap_or_else(|| {
            let component = components.len() as u16;
            components.push(Component::default());
            component
        });
        roots[chunk] = Some(component);
        for neighbor in left.into_iter().chain(below) {
            if let Some(other) = roots[neighbor]
                && other != component
            {
                for root in &mut roots {
                    if *root == Some(other) {
                        *root = Some(component);
                    }
                }
            }
        }
    }
    let mut remap = BTreeMap::new();
    let mut compact = Vec::new();
    for root in roots.iter_mut().flatten() {
        let next = remap.len() as u16;
        let mapped = *remap.entry(*root).or_insert_with(|| {
            compact.push(Component::default());
            next
        });
        *root = mapped;
    }
    for chunk in 0..4usize {
        let Some(label) = roots[chunk] else {
            continue;
        };
        let x = chunk & 1;
        let z = chunk >> 1;
        for (nx, nz) in [
            (x.checked_sub(1), Some(z)),
            ((x + 1 < 2).then_some(x + 1), Some(z)),
            (Some(x), z.checked_sub(1)),
            (Some(x), (z + 1 < 2).then_some(z + 1)),
        ] {
            let (Some(nx), Some(nz)) = (nx, nz) else {
                continue;
            };
            if roots[nx | (nz << 1)].is_none() {
                compact[label as usize].unknown = true;
            }
        }
    }
    let mut labels = vec![UNKNOWN_LABEL; SECTION_VOLUME];
    for y in 0..SECTION_EDGE {
        for z in 0..SECTION_EDGE {
            for x in 0..SECTION_EDGE {
                let chunk = (x / 16) | ((z / 16) << 1);
                if let Some(label) = roots[chunk] {
                    labels[cell_index(x, y, z)] = label;
                }
            }
        }
    }
    LocalSection {
        labels: LocalLabels::Runs(encode_dense_runs(&labels)),
        components: compact,
        direct_exterior: 0,
        direct_unknown: 0,
    }
}

fn connect_face(
    left_key: SectionKey,
    right_key: SectionKey,
    face: usize,
    offsets: &BTreeMap<SectionKey, usize>,
    locals: &mut BTreeMap<SectionKey, LocalSection>,
    dsu: &mut DisjointSet,
) -> Result<()> {
    let mut left = locals
        .remove(&left_key)
        .context("visibility left section disappeared")?;
    let result = (|| {
        let right = locals
            .get_mut(&right_key)
            .context("visibility right section disappeared")?;
        if let (LocalLabels::Uniform(left_label), LocalLabels::Uniform(right_label)) =
            (&left.labels, &right.labels)
            && *left_label != SOLID_LABEL
            && *left_label != UNKNOWN_LABEL
            && *right_label != SOLID_LABEL
            && *right_label != UNKNOWN_LABEL
        {
            dsu.union(
                offsets[&left_key] + *left_label as usize,
                offsets[&right_key] + *right_label as usize,
            );
            return Ok(());
        }
        for v in 0..SECTION_EDGE {
            for u in 0..SECTION_EDGE {
                let (lx, ly, lz) = face_xyz(face, u, v);
                let (rx, ry, rz) = face_xyz(face ^ 1, u, v);
                let left_label = left.labels.get(cell_index(lx, ly, lz));
                let right_label = right.labels.get(cell_index(rx, ry, rz));
                let left_component = left_label != SOLID_LABEL && left_label != UNKNOWN_LABEL;
                let right_component = right_label != SOLID_LABEL && right_label != UNKNOWN_LABEL;
                match (left_component, right_component, left_label, right_label) {
                    (true, true, _, _) => dsu.union(
                        offsets[&left_key] + left_label as usize,
                        offsets[&right_key] + right_label as usize,
                    ),
                    (true, false, _, SOLID_LABEL) => {
                        *left.components[left_label as usize]
                            .adjacent
                            .entry(right_key)
                            .or_default() |= 1u64 << microtile(rx, ry, rz);
                    }
                    (false, true, SOLID_LABEL, _) => {
                        *right.components[right_label as usize]
                            .adjacent
                            .entry(left_key)
                            .or_default() |= 1u64 << microtile(lx, ly, lz);
                    }
                    (true, false, _, UNKNOWN_LABEL) => {
                        left.components[left_label as usize].unknown = true;
                    }
                    (false, true, UNKNOWN_LABEL, _) => {
                        right.components[right_label as usize].unknown = true;
                    }
                    (false, false, SOLID_LABEL, UNKNOWN_LABEL) => {
                        left.direct_unknown |= 1u64 << microtile(lx, ly, lz);
                    }
                    (false, false, UNKNOWN_LABEL, SOLID_LABEL) => {
                        right.direct_unknown |= 1u64 << microtile(rx, ry, rz);
                    }
                    _ => {}
                }
            }
        }
        Ok(())
    })();
    locals.insert(left_key, left);
    result
}

fn mark_open_face(
    key: SectionKey,
    face: usize,
    exterior: bool,
    locals: &mut BTreeMap<SectionKey, LocalSection>,
) -> Result<()> {
    let local = locals
        .get_mut(&key)
        .context("visibility open-face section disappeared")?;
    if let LocalLabels::Uniform(label) = &local.labels
        && *label != SOLID_LABEL
        && *label != UNKNOWN_LABEL
    {
        if exterior {
            local.components[*label as usize].exterior = true;
        } else {
            local.components[*label as usize].unknown = true;
        }
        return Ok(());
    }
    for v in 0..SECTION_EDGE {
        for u in 0..SECTION_EDGE {
            let (x, y, z) = face_xyz(face, u, v);
            let label = local.labels.get(cell_index(x, y, z));
            if label == UNKNOWN_LABEL {
                continue;
            } else if label == SOLID_LABEL {
                let bit = 1u64 << microtile(x, y, z);
                if exterior {
                    local.direct_exterior |= bit;
                } else {
                    local.direct_unknown |= bit;
                }
            } else if exterior {
                local.components[label as usize].exterior = true;
            } else {
                local.components[label as usize].unknown = true;
            }
        }
    }
    Ok(())
}

fn assign_domains(
    dimension: &str,
    components: &mut BTreeMap<usize, GlobalComponent>,
) -> Result<()> {
    let mut used = BTreeSet::from([EXTERIOR_DOMAIN]);
    for component in components.values_mut() {
        if component.exterior || component.unknown {
            continue;
        }
        component.members.sort_unstable();
        let mut nonce = 0u64;
        loop {
            let mut hasher = blake3::Hasher::new();
            hasher.update(b"Voxy surface exact visibility domain\0");
            hasher.update(&(dimension.len() as u64).to_le_bytes());
            hasher.update(dimension.as_bytes());
            hasher.update(&nonce.to_le_bytes());
            hasher.update(&(component.members.len() as u64).to_le_bytes());
            for (key, local) in &component.members {
                hasher.update(&key.packed().to_le_bytes());
                hasher.update(&local.to_le_bytes());
            }
            let mut domain = u64::from_le_bytes(
                hasher.finalize().as_bytes()[..8]
                    .try_into()
                    .expect("BLAKE3 output is at least eight bytes"),
            );
            if domain < 2 {
                domain = domain.wrapping_add(2);
            }
            if used.insert(domain) {
                component.domain = domain;
                break;
            }
            nonce = nonce
                .checked_add(1)
                .context("visibility domain nonce exhausted")?;
        }
    }
    Ok(())
}

fn add_direct_tiles(
    output: &mut BTreeMap<TileCoordinate, TileVisibilityBuilder>,
    key: SectionKey,
    mask: u64,
    exterior: bool,
) {
    for tile in bits(mask) {
        let value = output.entry(tile_coordinate(key, tile)).or_default();
        if exterior {
            value.exterior = true;
        } else {
            value.unknown = true;
        }
    }
}

fn encode_dense_runs(labels: &[u16]) -> Vec<LabelRun> {
    let mut output = Vec::new();
    let mut start = 0usize;
    while start < labels.len() {
        let label = labels[start];
        let mut end = start + 1;
        while end < labels.len() && labels[end] == label {
            end += 1;
        }
        output.push(LabelRun {
            end: end as u16,
            label,
        });
        start = end;
    }
    output
}

fn passable(cell: Cell, registry: &RegistrySnapshot) -> bool {
    cell.is_air()
        || !registry
            .blocks
            .get(cell.block as usize)
            .is_some_and(|block| block.authoritative && block.opacity == 15)
}

fn microtile(x: usize, y: usize, z: usize) -> usize {
    (x / MICROTILE_EDGE) | ((z / MICROTILE_EDGE) << 2) | ((y / MICROTILE_EDGE) << 4)
}

fn tile_coordinate(key: SectionKey, tile: usize) -> TileCoordinate {
    let x = tile & 3;
    let z = (tile >> 2) & 3;
    let y = (tile >> 4) & 3;
    TileCoordinate {
        x: key.x * MICROTILES_PER_SECTION_EDGE + x as i32,
        y: key.y * MICROTILES_PER_SECTION_EDGE + y as i32,
        z: key.z * MICROTILES_PER_SECTION_EDGE + z as i32,
    }
}

fn bits(mut mask: u64) -> impl Iterator<Item = usize> {
    std::iter::from_fn(move || {
        if mask == 0 {
            return None;
        }
        let bit = mask.trailing_zeros() as usize;
        mask &= mask - 1;
        Some(bit)
    })
}

fn offset_key(key: SectionKey, face: usize) -> Result<Option<SectionKey>> {
    let (dx, dy, dz) = FACES
        .get(face)
        .copied()
        .context("invalid visibility face")?;
    let Some(x) = key.x.checked_add(dx) else {
        return Ok(None);
    };
    let Some(y) = key.y.checked_add(dy) else {
        return Ok(None);
    };
    let Some(z) = key.z.checked_add(dz) else {
        return Ok(None);
    };
    match SectionKey::new(0, x, y, z) {
        Ok(key) => Ok(Some(key)),
        Err(_) => Ok(None),
    }
}

fn face_xyz(face: usize, u: usize, v: usize) -> (usize, usize, usize) {
    match face {
        0 => (0, v, u),
        1 => (SECTION_EDGE - 1, v, u),
        2 => (u, 0, v),
        3 => (u, SECTION_EDGE - 1, v),
        4 => (u, v, 0),
        5 => (u, v, SECTION_EDGE - 1),
        _ => unreachable!("validated face"),
    }
}

#[derive(Debug)]
struct DisjointSet {
    parent: Vec<usize>,
    size: Vec<usize>,
}

impl DisjointSet {
    fn new(length: usize) -> Self {
        Self {
            parent: (0..length).collect(),
            size: vec![1; length],
        }
    }

    fn find(&mut self, value: usize) -> usize {
        let mut root = value;
        while self.parent[root] != root {
            root = self.parent[root];
        }
        let mut cursor = value;
        while self.parent[cursor] != cursor {
            let next = self.parent[cursor];
            self.parent[cursor] = root;
            cursor = next;
        }
        root
    }

    fn union(&mut self, left: usize, right: usize) {
        let mut left = self.find(left);
        let mut right = self.find(right);
        if left == right {
            return;
        }
        if self.size[left] < self.size[right] {
            std::mem::swap(&mut left, &mut right);
        }
        self.parent[right] = left;
        self.size[left] += self.size[right];
    }
}
