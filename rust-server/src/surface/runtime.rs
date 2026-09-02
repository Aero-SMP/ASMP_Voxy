//! Terrain publisher.
//!
//! Anvil chunks are normalized directly into the five-level hierarchy and independently
//! content-addressed 8³ exterior/interior/complex objects.

use super::{
    catalog::Catalog,
    content::{
        MICROTILE_EDGE, SourceMicrotile, content_kind, prepare_section, prepare_source_microtiles,
    },
    dictionary::{decode as decode_dictionary, train as train_dictionary},
    gc::{DictionarySet, GcMoment, GcPackStore, GcPins, GcPolicy, GcRunReport},
    manifest::{
        ContentClass, ContentDescriptor, DESCRIPTOR_PAGE_SLOTS, DirectoryTarget,
        MAX_SUBTREE_LEVELS, ManifestDescriptorPage, ManifestNode, ManifestSubtree, QuantizedBounds,
        RootDirectory, RootDirectoryEntry, SpatialNode, VisibilityMembership, availability_with,
        bit, descriptor_page_slot_count, descriptor_page_slots, directory_morton_key, level_offset,
        morton3, slots_for_levels,
    },
    object::{CanonicalObject, ObjectHash, ObjectKind},
    pack::{PackReader, PackStore, StoredObjectSource},
    root::{PublishResult, RootRecord, RootStore},
    visibility::{
        CameraDomainLease, DimensionVisibilityPolicy, RegionChunkSource, RegionalVisibilitySummary,
        VisibilityIndex,
    },
    wire::RootToken,
};
use crate::{
    MAX_LOD,
    anvil::{AnvilWorld, RegionHeader},
    key::SectionKey,
    lock,
    lod::{Cell, SECTION_VOLUME, Section, build_parent, cell_index},
    read_lock,
    registry::{Registry, RegistrySnapshot},
    safe_dimension_name, write_lock,
};
use anyhow::{Context, Result, bail};
use std::{
    collections::{BTreeMap, BTreeSet, HashSet},
    fmt,
    path::Path,
    sync::{Arc, Mutex, RwLock},
};

/// The requested immutable hash exists, but its physical compression dependency is not leased
/// by this exact root. This is a stale/foreign hash capability, not evidence of store damage.
#[derive(Debug)]
pub(crate) struct LeasedDictionaryMismatch;

impl fmt::Display for LeasedDictionaryMismatch {
    fn fmt(&self, output: &mut fmt::Formatter<'_>) -> fmt::Result {
        output.write_str("object dictionary is absent from its exact leased root")
    }
}

impl std::error::Error for LeasedDictionaryMismatch {}

const BUILD_BATCH: usize = 256;
const DICTIONARY_CORPUS_BYTES_PER_CLASS: usize = 4 * 1024 * 1024;
const MAX_DIRECTORY_DEPTH: usize = 16;
const DIRECTORY_PAGE_ENTRIES: usize = 4_096;
const MAX_ROOT_GRAPH_OBJECTS: usize = 2_200_000;
const MAX_RETAINED_VISIBILITY_ROOTS: usize = 2;
/// Import a few independent regions together, but retain one 2x2-chunk group as the bounded
/// source transaction within each region. This makes fresh stores useful in seconds without
/// retaining an entire region's normalized hierarchy and object index growth in one build.
const REGION_PUBLICATION_BATCH: usize = 4;
const GROUP_PUBLICATION_BATCH: usize = 1;
type RegionOrderKey = (i64, i32, i32);
type RegionCoordinate = (i32, i32);
type RegionHeaders = BTreeMap<RegionCoordinate, RegionHeader>;
type VisibilityRegions = BTreeMap<RegionCoordinate, RegionalVisibilitySummary>;

struct ChangedHierarchyRequest<'a> {
    headers: &'a RegionHeaders,
    changed: &'a BTreeSet<RegionCoordinate>,
    previous_regions: &'a VisibilityRegions,
    group_batch_limit: usize,
    force: bool,
}

struct RegionHierarchyRequest<'a> {
    header: &'a RegionHeader,
    expected_sources: Vec<RegionChunkSource>,
    candidate_groups: BTreeSet<RegionCoordinate>,
    previous_sources: Option<&'a [RegionChunkSource]>,
    previous_source_microtiles: &'a BTreeMap<SectionKey, [ObjectHash; 64]>,
    previous_summary: Option<&'a RegionalVisibilitySummary>,
    force: bool,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
struct SourceSnapshot(Vec<(i32, i32, u64)>);

#[derive(Clone, Debug, Eq, PartialEq)]
struct BuildStamp {
    source: SourceSnapshot,
    registry_generation: u64,
    mip_generation: u64,
}

#[derive(Debug)]
struct LoadedHierarchy {
    /// Exact 8³ source objects for changed nodes at all five structural levels. This compact
    /// hash table replaces retained decoded 32³ Sections.
    section_sources: BTreeMap<SectionKey, [ObjectHash; 64]>,
    visibility_summary: RegionalVisibilitySummary,
    affected_groups: BTreeSet<(i32, i32)>,
    replaced_keys: BTreeSet<SectionKey>,
}

impl LoadedHierarchy {
    fn source_complete(&self) -> bool {
        self.visibility_summary.source_complete()
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct SectionState {
    bounds: Option<QuantizedBounds>,
    contents: [Option<ContentDescriptor>; super::manifest::CONTENT_CLASS_COUNT],
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct GroupState {
    manifest: ObjectHash,
}

#[derive(Clone, Debug)]
struct RetainedVisibility {
    token: RootToken,
    index: Arc<VisibilityIndex>,
}

#[derive(Clone, Debug, Default)]
struct PublishedState {
    groups: Arc<BTreeMap<SectionKey, GroupState>>,
    /// Roots whose manifests were conservatively marked UNKNOWN and must be rewritten when the
    /// exact regional portal graph becomes available. This stays compact and avoids rebuilding
    /// unrelated roots during the conservative-to-exact transition.
    pending_visibility_roots: Arc<BTreeSet<SectionKey>>,
    root: Option<RootRecord>,
    ready: bool,
    build_stamp: Option<BuildStamp>,
    catalog: Option<Catalog>,
    visibility: Option<Arc<VisibilityIndex>>,
    retained_visibility: Vec<RetainedVisibility>,
}

#[derive(Debug)]
pub struct DimensionSurface {
    dimension: String,
    visibility_policy: DimensionVisibilityPolicy,
    source: Arc<AnvilWorld>,
    objects: Mutex<GcPackStore>,
    /// Independent positional reader used by client sessions while publication owns the writer.
    /// GC swaps this handle only after its replacement set is durable; a cloned old handle keeps
    /// every file needed by an already-authorized root alive until that read completes.
    reader: RwLock<PackReader>,
    roots: Mutex<RootStore>,
    state: RwLock<PublishedState>,
    build: Mutex<()>,
    /// Advances independently of root publication so metadata-only saves and failed source reads
    /// cannot let one near-origin region monopolize every bounded import transaction.
    region_publication_cursor: Mutex<Option<RegionOrderKey>>,
}

impl DimensionSurface {
    pub fn open(
        data_root: impl AsRef<Path>,
        dimension: impl Into<String>,
        source: Arc<AnvilWorld>,
    ) -> Result<Self> {
        let dimension = dimension.into();
        let policy = DimensionVisibilityPolicy::for_dimension(&dimension);
        Self::open_with_policy(data_root, dimension, source, policy)
    }

    pub fn open_with_policy(
        data_root: impl AsRef<Path>,
        dimension: impl Into<String>,
        source: Arc<AnvilWorld>,
        visibility_policy: DimensionVisibilityPolicy,
    ) -> Result<Self> {
        let dimension = dimension.into();
        if source.dimension != dimension {
            bail!("Anvil source and surface dimensions disagree");
        }
        let visibility_policy =
            DimensionVisibilityPolicy::configured(&dimension, Some(visibility_policy))?;
        let dimension_hash = ObjectHash::dimension(&dimension)?;
        let root_path = data_root
            .as_ref()
            .join("surface")
            .join("worlds")
            .join(safe_dimension_name(&dimension));
        let objects = GcPackStore::open(root_path.join("objects"))?;
        let roots = RootStore::open(root_path.join("roots"), dimension_hash)?;
        let reader = objects.active().reader();
        let surface = Self {
            dimension,
            visibility_policy,
            source,
            objects: Mutex::new(objects),
            reader: RwLock::new(reader),
            roots: Mutex::new(roots),
            state: RwLock::new(PublishedState::default()),
            build: Mutex::new(()),
            region_publication_cursor: Mutex::new(None),
        };
        if let Err(error) = surface.load_published_state() {
            eprintln!(
                "{} root needs regeneration and will not be announced: {error:#}",
                surface.dimension
            );
            lock(&surface.roots)?.discard_unserviceable_graphs()?;
        }
        Ok(surface)
    }

    pub fn current_root(&self) -> Result<Option<RootRecord>> {
        let state = read_lock(&self.state)?;
        Ok(state.ready.then_some(state.root).flatten())
    }

    pub fn previous_root(&self) -> Result<Option<RootRecord>> {
        if self.current_root()?.is_none() {
            return Ok(None);
        }
        Ok(lock(&self.roots)?.previous())
    }

    /// Resolves one camera position against the exact immutable root record leased by the
    /// session. Root-bound visibility pages are loaded from the content store when an older
    /// live lease has outlived the hot generation cache; a different generation can therefore
    /// never answer this query accidentally.
    pub fn camera_domain(
        &self,
        record: RootRecord,
        block_x: i32,
        block_y: i32,
        block_z: i32,
    ) -> Result<CameraDomainLease> {
        if record.dimension != ObjectHash::dimension(&self.dimension)? {
            bail!("camera-domain root belongs to a different dimension");
        }
        let token = RootToken::from(record);
        let cached = {
            let state = read_lock(&self.state)?;
            if state
                .root
                .is_some_and(|root| RootToken::from(root) == token)
            {
                state.visibility.clone()
            } else {
                state
                    .retained_visibility
                    .iter()
                    .rev()
                    .find(|retained| retained.token == token)
                    .map(|retained| retained.index.clone())
            }
        };
        let index = if let Some(index) = cached {
            index
        } else {
            let index = {
                let reader = read_lock(&self.reader)?.clone();
                Arc::new(load_visibility_index(&reader, record.visibility)?)
            };
            let mut state = write_lock(&self.state)?;
            if !state
                .retained_visibility
                .iter()
                .any(|retained| retained.token == token)
                && !state
                    .root
                    .is_some_and(|root| RootToken::from(root) == token)
            {
                state.retained_visibility.push(RetainedVisibility {
                    token,
                    index: index.clone(),
                });
                if state.retained_visibility.len() > MAX_RETAINED_VISIBILITY_ROOTS {
                    state.retained_visibility.remove(0);
                }
            }
            index
        };
        Ok(index.camera_domain(block_x, block_y, block_z))
    }

    /// Resolves the immutable dictionary table once when a connection leases a root.
    pub fn leased_dictionaries(&self, record: RootRecord) -> Result<Arc<[ObjectHash]>> {
        if record.dimension != ObjectHash::dimension(&self.dimension)? {
            bail!("wire object root belongs to a different dimension");
        }
        let reader = read_lock(&self.reader)?.clone();
        let set = reader
            .get(record.dictionary_set)?
            .context("root dictionary set is missing")?;
        if set.kind() != ObjectKind::DictionarySet {
            bail!("root dictionary set has the wrong object type");
        }
        Ok(DictionarySet::decode(set.bytes())?.dictionaries.into())
    }

    /// Opens one immutable compressed extent without decompressing, hashing, or copying it.
    /// Dictionary indices are one-based; zero means no dictionary.
    pub fn open_wire_object(
        &self,
        record: RootRecord,
        dictionaries: &[ObjectHash],
        hash: ObjectHash,
    ) -> Result<Option<(StoredObjectSource, u32)>> {
        if record.dimension != ObjectHash::dimension(&self.dimension)? {
            bail!("wire object root belongs to a different dimension");
        }
        let reader = read_lock(&self.reader)?.clone();
        open_wire_object_from_reader(&reader, dictionaries, hash)
    }

    pub fn collect_live(
        &self,
        recent: Vec<RootRecord>,
        moment: GcMoment,
        policy: GcPolicy,
    ) -> Result<GcRunReport> {
        let _build = lock(&self.build)?;
        let expected = ObjectHash::dimension(&self.dimension)?;
        if recent.iter().any(|root| root.dimension != expected) {
            bail!("surface GC live-session pin belongs to a different dimension");
        }
        let roots = lock(&self.roots)?;
        let pins = GcPins {
            current: roots.current().into_iter().collect(),
            previous: roots.previous().into_iter().collect(),
            building: Vec::new(),
            recent,
        };
        drop(roots);
        let mut objects = lock(&self.objects)?;
        let report = objects.collect(&pins, moment, policy)?;
        *write_lock(&self.reader)? = objects.active().reader();
        Ok(report)
    }

    pub fn refresh(&self, registry: &Arc<RwLock<Registry>>) -> Result<bool> {
        self.rebuild_incremental(registry, false)
    }

    /// Rebuilds the complete immutable graph even when source stamps are unchanged. The object
    /// store replaces only missing/corrupt physical records; canonical hashes and an unchanged
    /// root generation remain stable.
    pub fn repair(&self, registry: &Arc<RwLock<Registry>>) -> Result<bool> {
        self.rebuild_incremental(registry, true)
    }

    fn rebuild_incremental(
        &self,
        registry: &Arc<RwLock<Registry>>,
        force_rebuild: bool,
    ) -> Result<bool> {
        let _build = lock(&self.build)?;
        let (initial_source, headers) = source_headers(&self.source)?;
        let old_state = read_lock(&self.state)?.clone();
        save_registry(registry)?;
        let initial_registry = registry_snapshot(registry)?;
        let initial_catalog = Catalog::from_snapshot(&initial_registry)?;
        let initial_stamp = BuildStamp {
            source: initial_source.clone(),
            registry_generation: initial_registry.generation,
            mip_generation: initial_registry.mip_generation,
        };
        if !force_rebuild
            && old_state.ready
            && old_state.build_stamp.as_ref() == Some(&initial_stamp)
            && old_state
                .visibility
                .as_ref()
                .is_some_and(|visibility| visibility.is_complete())
        {
            return Ok(false);
        }

        let mut rebuild_all = force_rebuild
            || !old_state.ready
            || old_state.root.is_none()
            || old_state
                .catalog
                .as_ref()
                .is_none_or(|old| !catalog_incremental_compatible(old, &initial_catalog));
        let old_source = old_state
            .build_stamp
            .as_ref()
            .map(|stamp| stamp.source.clone())
            .unwrap_or_default();
        let mut changed = changed_regions(&old_source, &initial_source);
        if rebuild_all {
            changed.extend(old_source.0.iter().map(|&(x, z, _)| (x, z)));
            changed.extend(initial_source.0.iter().map(|&(x, z, _)| (x, z)));
        }
        // An initial import and ordinary terrain refresh are resumable publication streams, not
        // one global transaction. A catalog-incompatible rebuild of an already serviceable root
        // remains atomic because mixing catalogs in one authoritative graph is invalid.
        let batch_limit = if rebuild_all && old_state.ready {
            usize::MAX
        } else {
            REGION_PUBLICATION_BATCH
        };
        let represented_regions = old_source
            .0
            .iter()
            .map(|&(x, z, _)| (x, z))
            .collect::<BTreeSet<_>>();
        let current_regions = initial_source
            .0
            .iter()
            .map(|&(x, z, _)| (x, z))
            .collect::<BTreeSet<_>>();
        let unrepresented = changed
            .iter()
            .copied()
            .filter(|coordinate| {
                current_regions.contains(coordinate) && !represented_regions.contains(coordinate)
            })
            .collect::<BTreeSet<_>>();
        let importing_region = batch_limit != usize::MAX && !unrepresented.is_empty();
        if batch_limit != usize::MAX && !changed.is_empty() {
            if importing_region {
                // Finish the most recently saved missing region before ordinary refreshes. This
                // makes fresh stores useful around active players first while deterministic
                // near-origin ordering remains the fallback for equal or absent timestamps.
                changed = recent_region_batch(
                    unrepresented,
                    &headers,
                    &self.source.saved_player_regions()?,
                    batch_limit,
                )?;
            } else {
                let mut cursor = lock(&self.region_publication_cursor)?;
                changed = round_robin_region_batch(changed, batch_limit, &mut cursor);
            }
        }
        let group_batch_limit = if rebuild_all && old_state.ready {
            usize::MAX
        } else {
            GROUP_PUBLICATION_BATCH
        };

        let previous_changed_regions = if !rebuild_all && !changed.is_empty() {
            {
                let objects = lock(&self.objects)?;
                load_visibility_regions(
                    objects.active(),
                    old_state
                        .root
                        .context("incremental root is missing")?
                        .visibility,
                    &changed,
                )?
                .1
            }
        } else {
            BTreeMap::new()
        };

        let mut hierarchies = {
            let mut objects = lock(&self.objects)?;
            load_changed_hierarchies(
                &self.source,
                registry,
                objects.active_mut(),
                ChangedHierarchyRequest {
                    headers: &headers,
                    changed: &changed,
                    previous_regions: &previous_changed_regions,
                    group_batch_limit,
                    force: rebuild_all,
                },
            )?
        };
        save_registry(registry)?;
        let mut registry_snapshot = self::registry_snapshot(registry)?;
        let mut catalog_value = Catalog::from_snapshot(&registry_snapshot)?;
        if !rebuild_all
            && old_state
                .catalog
                .as_ref()
                .is_none_or(|old| !catalog_incremental_compatible(old, &catalog_value))
        {
            rebuild_all = true;
            changed.extend(old_source.0.iter().map(|&(x, z, _)| (x, z)));
            changed.extend(initial_source.0.iter().map(|&(x, z, _)| (x, z)));
            hierarchies = {
                let mut objects = lock(&self.objects)?;
                load_changed_hierarchies(
                    &self.source,
                    registry,
                    objects.active_mut(),
                    ChangedHierarchyRequest {
                        headers: &headers,
                        changed: &changed,
                        previous_regions: &BTreeMap::new(),
                        group_batch_limit: usize::MAX,
                        force: true,
                    },
                )?
            };
            save_registry(registry)?;
            registry_snapshot = self::registry_snapshot(registry)?;
            catalog_value = Catalog::from_snapshot(&registry_snapshot)?;
        }
        let accepted_regions = changed
            .iter()
            .copied()
            .filter(|coordinate| {
                !headers.contains_key(coordinate)
                    || hierarchies
                        .get(coordinate)
                        .is_some_and(LoadedHierarchy::source_complete)
            })
            .collect::<BTreeSet<_>>();
        let represented_source =
            apply_source_changes(&old_source, &initial_source, &accepted_regions);
        let metadata_only = is_metadata_only_refresh(
            rebuild_all,
            old_state
                .visibility
                .as_ref()
                .is_some_and(|visibility| visibility.is_complete()),
            hierarchies.len(),
            changed.len(),
            hierarchies
                .values()
                .any(|hierarchy| !hierarchy.affected_groups.is_empty()),
            old_state.catalog.as_ref() == Some(&catalog_value),
        );
        if metadata_only {
            let mut state = write_lock(&self.state)?;
            state.build_stamp = Some(BuildStamp {
                source: represented_source,
                registry_generation: registry_snapshot.generation,
                mip_generation: registry_snapshot.mip_generation,
            });
            return Ok(false);
        }
        let catalog = catalog_value.canonical_object()?;

        let mut visibility_regions = if rebuild_all {
            BTreeMap::new()
        } else if let Some(root) = old_state.root {
            {
                let objects = lock(&self.objects)?;
                load_visibility_regions(objects.active(), root.visibility, &BTreeSet::new())?.1
            }
        } else {
            BTreeMap::new()
        };
        for &coordinate in &changed {
            let Some(hierarchy) = hierarchies.get(&coordinate) else {
                visibility_regions.remove(&coordinate);
                continue;
            };
            visibility_regions.insert(coordinate, hierarchy.visibility_summary.clone());
        }
        let connectivity_changed = hierarchies
            .values()
            .any(|hierarchy| !hierarchy.affected_groups.is_empty());
        let visibility = Arc::new(if connectivity_changed {
            VisibilityIndex::conservative_from_regions_with_policy(
                &self.dimension,
                self.visibility_policy,
                &visibility_regions,
            )?
        } else {
            VisibilityIndex::from_regions_with_policy(
                &self.dimension,
                self.visibility_policy,
                &visibility_regions,
            )?
        });

        let mut object_sets = lock(&self.objects)?;
        let dictionaries = if let Some(old) = old_state.root {
            load_root_dictionaries(object_sets.active(), old)?
        } else {
            train_dictionaries_from_sources(
                &hierarchies,
                object_sets.active(),
                &registry_snapshot,
                &visibility,
            )?
        };
        let mut dictionary_hashes = dictionaries
            .iter()
            .map(CanonicalObject::hash)
            .collect::<Vec<_>>();
        dictionary_hashes.sort_unstable();
        let dictionary_set = DictionarySet::new(dictionary_hashes)?.canonical_object()?;

        object_sets.active_mut().put(&catalog)?;
        object_sets.active_mut().put_many(dictionaries.iter())?;
        object_sets.active_mut().put(&dictionary_set)?;

        let class_dictionaries = class_dictionary_hashes(&dictionaries)?;
        let replaced_keys = hierarchies
            .values()
            .flat_map(|hierarchy| hierarchy.replaced_keys.iter().copied())
            .collect::<BTreeSet<_>>();
        let removed_regions = changed
            .iter()
            .copied()
            .filter(|coordinate| !headers.contains_key(coordinate))
            .collect::<BTreeSet<_>>();
        let mut changed_roots = hierarchies
            .values()
            .flat_map(|hierarchy| hierarchy.section_sources.keys().copied())
            .chain(replaced_keys.iter().copied())
            .map(top_root)
            .collect::<BTreeSet<_>>();
        changed_roots.extend(
            old_state
                .groups
                .keys()
                .copied()
                .filter(|root| removed_regions.contains(&(root.x, root.z))),
        );
        // Ordinary refreshes contain only the bounded changed-group batch. Prepare their
        // renderable state once, then reuse it while rebuilding the owning roots and any
        // dependency-facing neighbours. A full repair can span the world, so it deliberately
        // retains only one root-local preparation below.
        let prepared_changes = if !rebuild_all {
            Some(prepare_section_states_from_sources(
                &hierarchies,
                None,
                &registry_snapshot,
                &visibility,
                &dictionaries,
                object_sets.active_mut(),
            )?)
        } else {
            None
        };
        let mut roots_to_rebuild = if rebuild_all {
            old_state.groups.keys().copied().collect::<BTreeSet<_>>()
        } else {
            changed_roots.clone()
        };
        roots_to_rebuild.extend(changed_roots.iter().copied());
        if !rebuild_all {
            for root in changed_roots.iter().copied() {
                roots_to_rebuild.extend(adjacent_top_roots(root)?);
            }
        }
        let mut pending_visibility_roots = (*old_state.pending_visibility_roots).clone();
        if connectivity_changed {
            pending_visibility_roots.extend(roots_to_rebuild.iter().copied());
        } else {
            let baseline = old_state
                .visibility
                .as_ref()
                .filter(|index| index.is_complete())
                .cloned()
                .or_else(|| {
                    old_state
                        .retained_visibility
                        .iter()
                        .rev()
                        .find(|retained| retained.index.is_complete())
                        .map(|retained| retained.index.clone())
                });
            match baseline {
                Some(baseline) => match baseline.content_metadata_changed_roots(&visibility)? {
                    Some(changed) => roots_to_rebuild.extend(changed),
                    None => roots_to_rebuild.extend(old_state.groups.keys().copied()),
                },
                None => roots_to_rebuild.extend(old_state.groups.keys().copied()),
            }
            roots_to_rebuild.extend(pending_visibility_roots.iter().copied());
            pending_visibility_roots.clear();
        }
        let mut next_groups = if rebuild_all {
            BTreeMap::new()
        } else {
            (*old_state.groups).clone()
        };
        for root in roots_to_rebuild {
            let mut dependency_roots = adjacent_top_roots(root)?
                .into_iter()
                .collect::<BTreeSet<_>>();
            dependency_roots.insert(root);
            let root_changes;
            let changed_states = if let Some(prepared) = prepared_changes.as_ref() {
                prepared
            } else {
                root_changes = prepare_section_states_from_sources(
                    &hierarchies,
                    Some(root),
                    &registry_snapshot,
                    &visibility,
                    &dictionaries,
                    object_sets.active_mut(),
                )?;
                &root_changes
            };
            let mut old_sections = BTreeMap::new();
            if !rebuild_all {
                for dependency_root in dependency_roots.iter().copied() {
                    let Some(group) = old_state.groups.get(&dependency_root) else {
                        continue;
                    };
                    let loaded = if dependency_root == root {
                        load_group_sections(
                            object_sets.active(),
                            dependency_root,
                            group,
                            &class_dictionaries,
                        )?
                    } else {
                        load_group_sections_matching(
                            object_sets.active(),
                            dependency_root,
                            group,
                            &class_dictionaries,
                            |key| touches_root_boundary(key, root, dependency_root),
                        )?
                    };
                    old_sections.extend(loaded);
                }
            }
            let mut sections = if rebuild_all || removed_regions.contains(&(root.x, root.z)) {
                BTreeMap::new()
            } else {
                old_sections
                    .iter()
                    .filter(|(key, _)| top_root(**key) == root)
                    .map(|(&key, value)| (key, value.clone()))
                    .collect()
            };
            for key in replaced_keys.iter().filter(|key| top_root(**key) == root) {
                sections.remove(key);
            }
            for (&key, state) in changed_states
                .iter()
                .filter(|(key, _)| top_root(**key) == root)
            {
                sections.insert(key, state.clone());
            }
            if sections.is_empty() {
                next_groups.remove(&root);
                continue;
            }
            apply_visibility_memberships(&mut sections, &visibility);
            attach_incremental_neighbor_dependencies(
                root,
                &mut sections,
                &old_sections,
                changed_states,
                &replaced_keys,
            )?;
            let manifest = build_manifest_tree(root, &sections, |object| {
                object_sets.active_mut().put(object).map(|_| ())
            })?;
            next_groups.insert(root, GroupState { manifest });
        }
        let visibility_directory = visibility
            .canonical_objects_to(&visibility_regions, |object| {
                object_sets.active_mut().put(object).map(|_| ())
            })?;
        object_sets.active_mut().put(&visibility_directory)?;
        drop(visibility_regions);
        drop(hierarchies);
        drop(previous_changed_regions);
        drop(changed_roots);
        drop(replaced_keys);
        let directory = build_directory_to(&next_groups, |object| {
            object_sets.active_mut().put(object).map(|_| ())
        })?;
        object_sets.active_mut().put(&directory)?;
        object_sets.checkpoint_active()?;

        let final_catalog =
            Catalog::from_snapshot(&self::registry_snapshot(registry)?)?.canonical_object()?;
        if final_catalog.hash() != catalog.hash() {
            bail!("registry catalog changed while an incremental surface root was being built");
        }

        let old = old_state.root;
        let record = next_record(
            &self.dimension,
            old,
            directory.hash(),
            catalog.hash(),
            dictionary_set.hash(),
            visibility_directory.hash(),
        )?;
        let published = {
            let mut roots = lock(&self.roots)?;
            publish_if_changed(&mut roots, record, object_sets.active(), old)?
        };
        let visible = if published {
            record
        } else {
            old.unwrap_or(record)
        };
        let pending = represented_source != initial_source;
        let build_stamp = BuildStamp {
            // This generation describes exactly the source markers captured for the bounded
            // batch above. If Minecraft saved one of them after it was read, the next poll sees
            // the new marker and publishes a successor; completed work is never discarded.
            source: represented_source,
            registry_generation: registry_snapshot.generation,
            mip_generation: registry_snapshot.mip_generation,
        };
        pending_visibility_roots.retain(|root| next_groups.contains_key(root));
        drop(object_sets);

        let mut retained_visibility = old_state.retained_visibility;
        if let Some(root) = old.filter(|root| *root != visible) {
            let index = old_state
                .visibility
                .context("serviceable predecessor has no visibility index")?;
            let token = RootToken::from(root);
            retained_visibility.retain(|retained| retained.token != token);
            retained_visibility.push(RetainedVisibility { token, index });
        }
        let visible_token = RootToken::from(visible);
        retained_visibility.retain(|retained| retained.token != visible_token);
        if retained_visibility.len() > MAX_RETAINED_VISIBILITY_ROOTS {
            let remove = retained_visibility.len() - MAX_RETAINED_VISIBILITY_ROOTS;
            retained_visibility.drain(..remove);
        }
        *write_lock(&self.state)? = PublishedState {
            groups: Arc::new(next_groups),
            pending_visibility_roots: Arc::new(pending_visibility_roots),
            root: Some(visible),
            ready: true,
            build_stamp: Some(build_stamp),
            catalog: Some(catalog_value),
            visibility: Some(visibility),
            retained_visibility,
        };
        Ok(pending)
    }

    fn load_published_state(&self) -> Result<()> {
        let (current, previous) = {
            let roots = lock(&self.roots)?;
            (roots.current(), roots.previous())
        };
        let Some(current) = current else {
            return Ok(());
        };
        let loaded =
            self.decode_published_state(current)
                .or_else(|current_error| {
                    let previous =
                        previous.context("published surface root graph is incomplete")?;
                    let state = self.decode_published_state(previous).with_context(|| {
                format!("current graph is incomplete ({current_error:#}); predecessor also failed")
            })?;
                    lock(&self.roots)?.restore_verified_previous(previous)?;
                    Ok::<_, anyhow::Error>(state)
                })?;
        *write_lock(&self.state)? = loaded;
        Ok(())
    }

    fn decode_published_state(&self, root: RootRecord) -> Result<PublishedState> {
        let objects = lock(&self.objects)?;
        let active = objects.active();
        let catalog_object = active
            .get(root.catalog)?
            .context("published catalog is missing")?;
        if catalog_object.kind() != ObjectKind::Catalog {
            bail!("published catalog has the wrong type");
        }
        let catalog = Catalog::decode(catalog_object.bytes())?;
        let (visibility_complete, visibility_regions) =
            load_visibility_regions(active, root.visibility, &BTreeSet::new())?;
        let visibility = if visibility_complete {
            VisibilityIndex::from_regions_with_policy(
                &self.dimension,
                self.visibility_policy,
                &visibility_regions,
            )?
        } else {
            VisibilityIndex::conservative_from_regions_with_policy(
                &self.dimension,
                self.visibility_policy,
                &visibility_regions,
            )?
        };
        let visibility = Arc::new(visibility);
        let dictionary_set_hash = root.dictionary_set;
        let set_object = active
            .get(dictionary_set_hash)?
            .context("published dictionary set is missing")?;
        let dictionary_set = DictionarySet::decode(set_object.bytes())?;
        let mut class_dictionaries = [ObjectHash::ZERO; super::manifest::CONTENT_CLASS_COUNT];
        for dictionary in &dictionary_set.dictionaries {
            let object = active
                .get(*dictionary)?
                .context("published dictionary is missing")?;
            if object.kind() != ObjectKind::CompressionDictionary {
                bail!("dictionary set references a wrong-type object");
            }
            let class = decode_dictionary(object.bytes())?.class;
            if !class_dictionaries[class.index()].is_zero() {
                bail!("dictionary set contains two dictionaries for one content class");
            }
            class_dictionaries[class.index()] = *dictionary;
        }
        if class_dictionaries.iter().any(|hash| hash.is_zero()) {
            bail!("root does not publish exactly one dictionary per content class");
        }
        let mut groups = BTreeMap::new();
        let mut visited = HashSet::new();
        load_directory(
            active,
            root.root_manifest,
            0,
            None,
            &mut visited,
            &mut groups,
        )?;
        check_recovered_groups(active, &groups, &class_dictionaries)?;
        let source = SourceSnapshot(
            visibility_regions
                .values()
                .filter(|region| region.source_complete())
                .map(|region| (region.region_x, region.region_z, region.source_marker))
                .collect(),
        );
        drop(visibility_regions);
        let pending_visibility_roots = if visibility.is_complete() {
            BTreeSet::new()
        } else {
            groups.keys().copied().collect()
        };
        Ok(PublishedState {
            groups: Arc::new(groups),
            pending_visibility_roots: Arc::new(pending_visibility_roots),
            root: Some(root),
            ready: true,
            build_stamp: Some(BuildStamp {
                source,
                registry_generation: catalog.generation,
                mip_generation: catalog.mip_generation,
            }),
            catalog: Some(catalog),
            visibility: Some(visibility),
            retained_visibility: Vec::new(),
        })
    }
}

fn open_wire_object_from_reader(
    reader: &PackReader,
    dictionaries: &[ObjectHash],
    hash: ObjectHash,
) -> Result<Option<(StoredObjectSource, u32)>> {
    let Some(stored) = reader.open_stored_source(hash)? else {
        return Ok(None);
    };
    let dictionary_id = if stored.dictionary().is_zero() {
        0
    } else {
        let position = dictionaries
            .binary_search(&stored.dictionary())
            .map_err(|_| LeasedDictionaryMismatch)?;
        u32::try_from(position + 1).context("dictionary index overflow")?
    };
    Ok(Some((stored, dictionary_id)))
}

trait CanonicalObjectReader {
    fn canonical(&self, hash: ObjectHash) -> Result<Option<CanonicalObject>>;
}

impl CanonicalObjectReader for PackStore {
    fn canonical(&self, hash: ObjectHash) -> Result<Option<CanonicalObject>> {
        self.get(hash)
    }
}

impl CanonicalObjectReader for PackReader {
    fn canonical(&self, hash: ObjectHash) -> Result<Option<CanonicalObject>> {
        self.get(hash)
    }
}

fn load_visibility_index(
    store: &impl CanonicalObjectReader,
    directory_hash: ObjectHash,
) -> Result<VisibilityIndex> {
    let directory = store
        .canonical(directory_hash)?
        .context("published visibility directory is missing")?;
    VisibilityIndex::from_canonical_index(&directory, |hash| {
        store
            .canonical(hash)?
            .with_context(|| format!("published visibility page {hash} is missing"))
    })
}

fn load_visibility_regions(
    store: &impl CanonicalObjectReader,
    directory_hash: ObjectHash,
    wanted: &BTreeSet<RegionCoordinate>,
) -> Result<(bool, VisibilityRegions)> {
    let directory = store
        .canonical(directory_hash)?
        .context("published visibility directory is missing")?;
    VisibilityIndex::regions_from_canonical_graph(&directory, wanted, |hash| {
        store
            .canonical(hash)?
            .with_context(|| format!("published visibility summary page {hash} is missing"))
    })
}

fn source_headers(source: &AnvilWorld) -> Result<(SourceSnapshot, RegionHeaders)> {
    let headers = source.region_headers()?;
    if !headers.failed.is_empty() {
        bail!("one or more Anvil region headers are unreadable");
    }
    let mut by_coordinate = BTreeMap::new();
    for header in headers.valid {
        let coordinate = (header.region_x, header.region_z);
        if by_coordinate.insert(coordinate, header).is_some() {
            bail!("Anvil source contains duplicate region coordinates");
        }
    }
    let snapshot = SourceSnapshot(
        by_coordinate
            .iter()
            .map(|(&(x, z), header)| (x, z, header.file_marker))
            .collect(),
    );
    Ok((snapshot, by_coordinate))
}

fn changed_regions(old: &SourceSnapshot, current: &SourceSnapshot) -> BTreeSet<(i32, i32)> {
    let old = old
        .0
        .iter()
        .map(|&(x, z, marker)| ((x, z), marker))
        .collect::<BTreeMap<_, _>>();
    let current = current
        .0
        .iter()
        .map(|&(x, z, marker)| ((x, z), marker))
        .collect::<BTreeMap<_, _>>();
    old.keys()
        .chain(current.keys())
        .copied()
        .filter(|coordinate| old.get(coordinate) != current.get(coordinate))
        .collect()
}

/// Advances only the regional source coordinates included in one successfully constructed
/// generation. Coordinates absent from `current` represent region deletion; every other
/// unprocessed marker remains at the state represented by the active root.
fn apply_source_changes(
    old: &SourceSnapshot,
    current: &SourceSnapshot,
    accepted: &BTreeSet<(i32, i32)>,
) -> SourceSnapshot {
    let mut represented = old
        .0
        .iter()
        .map(|&(x, z, marker)| ((x, z), marker))
        .collect::<BTreeMap<_, _>>();
    let current = current
        .0
        .iter()
        .map(|&(x, z, marker)| ((x, z), marker))
        .collect::<BTreeMap<_, _>>();
    for coordinate in accepted {
        match current.get(coordinate) {
            Some(&marker) => {
                represented.insert(*coordinate, marker);
            }
            None => {
                represented.remove(coordinate);
            }
        }
    }
    SourceSnapshot(
        represented
            .into_iter()
            .map(|((x, z), marker)| (x, z, marker))
            .collect(),
    )
}

fn region_order_key((x, z): (i32, i32)) -> RegionOrderKey {
    (
        i64::from(x)
            .saturating_mul(i64::from(x))
            .saturating_add(i64::from(z).saturating_mul(i64::from(z))),
        x,
        z,
    )
}

/// Selects represented regional work in deterministic near-to-far order, resumes strictly after
/// the last attempted coordinate, and wraps at the end. The cursor advances before source I/O,
/// so one persistently changing or unreadable region cannot starve the rest.
fn round_robin_region_batch(
    changed: BTreeSet<(i32, i32)>,
    limit: usize,
    cursor: &mut Option<RegionOrderKey>,
) -> BTreeSet<(i32, i32)> {
    if changed.is_empty() || limit == 0 {
        return BTreeSet::new();
    }
    let mut regions = changed.into_iter().collect::<Vec<_>>();
    regions.sort_unstable_by_key(|&coordinate| region_order_key(coordinate));
    let start = (*cursor).map_or(0, |previous| {
        regions.partition_point(|&coordinate| region_order_key(coordinate) <= previous)
    });
    let selected = (0..limit.min(regions.len()))
        .map(|offset| regions[(start + offset) % regions.len()])
        .collect::<Vec<_>>();
    *cursor = selected.last().copied().map(region_order_key);
    selected.into_iter().collect()
}

fn recent_region_batch(
    changed: BTreeSet<(i32, i32)>,
    headers: &BTreeMap<(i32, i32), RegionHeader>,
    saved_player_regions: &BTreeMap<(i32, i32), u64>,
    limit: usize,
) -> Result<BTreeSet<(i32, i32)>> {
    let mut regions = changed
        .into_iter()
        .map(|coordinate| {
            let header = headers.get(&coordinate).with_context(|| {
                format!(
                    "unrepresented region ({},{}) lacks its captured header",
                    coordinate.0, coordinate.1
                )
            })?;
            let timestamp = header
                .entries
                .iter()
                .filter(|entry| entry.location >> 8 != 0 && entry.location & 0xff != 0)
                .map(|entry| entry.timestamp)
                .max()
                .unwrap_or(0);
            Ok((
                coordinate,
                saved_player_regions.get(&coordinate).copied(),
                timestamp,
            ))
        })
        .collect::<Result<Vec<_>>>()?;
    regions.sort_unstable_by_key(|&(coordinate, saved, timestamp)| {
        (
            saved.is_none(),
            std::cmp::Reverse(saved.unwrap_or(0)),
            std::cmp::Reverse(timestamp),
            region_order_key(coordinate),
        )
    });
    regions.truncate(limit);
    Ok(regions
        .into_iter()
        .map(|(coordinate, _, _)| coordinate)
        .collect())
}

fn recent_group_batch(
    changed: BTreeSet<(i32, i32)>,
    header: &RegionHeader,
    limit: usize,
) -> BTreeSet<(i32, i32)> {
    let mut groups = changed
        .into_iter()
        .map(|coordinate| {
            let local_group_x = coordinate.0.rem_euclid(16) as usize;
            let local_group_z = coordinate.1.rem_euclid(16) as usize;
            let base_x = local_group_x * 2;
            let base_z = local_group_z * 2;
            let timestamp = (0..2usize)
                .flat_map(|z| (0..2usize).map(move |x| (base_x + x) | ((base_z + z) << 5)))
                .filter_map(|slot| header.entries.get(slot))
                .filter(|entry| entry.location >> 8 != 0 && entry.location & 0xff != 0)
                .map(|entry| entry.timestamp)
                .max()
                .unwrap_or(0);
            (coordinate, timestamp)
        })
        .collect::<Vec<_>>();
    groups.sort_unstable_by_key(|&(coordinate, timestamp)| {
        (std::cmp::Reverse(timestamp), region_order_key(coordinate))
    });
    groups.truncate(limit);
    groups
        .into_iter()
        .map(|(coordinate, _)| coordinate)
        .collect()
}

fn is_metadata_only_refresh(
    rebuild_all: bool,
    visibility_complete: bool,
    hierarchy_count: usize,
    changed_region_count: usize,
    has_affected_groups: bool,
    catalog_unchanged: bool,
) -> bool {
    !rebuild_all
        && visibility_complete
        && hierarchy_count == changed_region_count
        && !has_affected_groups
        && catalog_unchanged
}

fn affected_parents(children: &BTreeSet<SectionKey>) -> BTreeSet<SectionKey> {
    children.iter().filter_map(|key| key.parent()).collect()
}

fn current_region_sources(
    source: &AnvilWorld,
    header: &RegionHeader,
    previous: Option<&[RegionChunkSource]>,
) -> Result<Vec<RegionChunkSource>> {
    let fingerprints = source.region_fingerprints(header)?;
    if fingerprints.len() != header.entries.len() || fingerprints.len() != 1024 {
        bail!("Anvil region source table does not contain 1024 chunks");
    }
    Ok(header
        .entries
        .iter()
        .zip(fingerprints)
        .enumerate()
        .map(|(slot, (entry, fingerprint))| {
            let terrain_fingerprint = previous
                .and_then(|sources| sources.get(slot))
                .filter(|old| old.fingerprint == fingerprint)
                .and_then(|old| old.terrain_fingerprint);
            RegionChunkSource {
                header_marker: (u64::from(entry.location) << 32) | u64::from(entry.timestamp),
                fingerprint,
                terrain_fingerprint,
            }
        })
        .collect())
}

fn capture_region_sources(
    source: &AnvilWorld,
    coordinate: (i32, i32),
    previous: Option<&[RegionChunkSource]>,
) -> Result<Option<(RegionHeader, Vec<RegionChunkSource>)>> {
    let mut last_error = None;
    for _ in 0..3 {
        let Some(header) = source.region_header(coordinate.0, coordinate.1)? else {
            return Ok(None);
        };
        match current_region_sources(source, &header, previous) {
            Ok(sources) => return Ok(Some((header, sources))),
            Err(error) => last_error = Some(error),
        }
    }
    Err(last_error.expect("three regional snapshot attempts produced an error"))
}

fn affected_region_groups(
    region_x: i32,
    region_z: i32,
    previous: Option<&[RegionChunkSource]>,
    current: &[RegionChunkSource],
    force: bool,
) -> Result<BTreeSet<(i32, i32)>> {
    if current.len() != 1024 || previous.is_some_and(|sources| sources.len() != 1024) {
        bail!("incremental source comparison requires 1024 chunks per region");
    }
    let base_x = region_x
        .checked_mul(16)
        .context("region group x overflow")?;
    let base_z = region_z
        .checked_mul(16)
        .context("region group z overflow")?;
    let mut groups = BTreeSet::new();
    for slot in 0..1024usize {
        let old = previous.and_then(|sources| sources[slot].fingerprint);
        let normalized = previous.and_then(|sources| sources[slot].terrain_fingerprint);
        let new = current[slot].fingerprint;
        if (old.is_some() || new.is_some())
            && (force || old != new || (new.is_some() && normalized.is_none()))
        {
            let local_x = slot & 31;
            let local_z = slot >> 5;
            groups.insert((
                base_x
                    .checked_add((local_x / 2) as i32)
                    .context("region group x overflow")?,
                base_z
                    .checked_add((local_z / 2) as i32)
                    .context("region group z overflow")?,
            ));
        }
    }
    Ok(groups)
}

fn load_changed_hierarchies(
    source: &AnvilWorld,
    registry: &Arc<RwLock<Registry>>,
    store: &mut PackStore,
    request: ChangedHierarchyRequest<'_>,
) -> Result<BTreeMap<RegionCoordinate, LoadedHierarchy>> {
    let ChangedHierarchyRequest {
        headers,
        changed,
        previous_regions,
        group_batch_limit,
        force,
    } = request;
    let mut output = BTreeMap::new();
    for &coordinate in changed {
        if !headers.contains_key(&coordinate) {
            continue;
        }
        let previous = previous_regions.get(&coordinate);
        let Some((header, source_chunks)) = capture_region_sources(
            source,
            coordinate,
            previous.map(RegionalVisibilitySummary::source_chunks),
        )?
        else {
            continue;
        };
        let mut affected_groups = affected_region_groups(
            coordinate.0,
            coordinate.1,
            previous.map(RegionalVisibilitySummary::source_chunks),
            &source_chunks,
            force || previous.is_none(),
        )?;
        if affected_groups.len() > group_batch_limit {
            affected_groups = recent_group_batch(affected_groups, &header, group_batch_limit);
        }
        let had_candidate_groups = !affected_groups.is_empty();
        let mut summary = previous.cloned();
        let mut working_sources = source_chunks;
        let mut section_sources = BTreeMap::new();
        let mut replaced_keys = BTreeSet::new();
        let mut completed_groups = BTreeSet::new();
        let mut last_error = None;
        for group in affected_groups {
            let empty = BTreeMap::new();
            let result = load_region_hierarchy(
                source,
                registry,
                store,
                RegionHierarchyRequest {
                    header: &header,
                    expected_sources: working_sources.clone(),
                    candidate_groups: BTreeSet::from([group]),
                    previous_sources: summary
                        .as_ref()
                        .map(RegionalVisibilitySummary::source_chunks),
                    previous_source_microtiles: summary
                        .as_ref()
                        .map(RegionalVisibilitySummary::source_microtiles)
                        .unwrap_or(&empty),
                    previous_summary: summary.as_ref(),
                    force: force || previous.is_none(),
                },
            );
            match result {
                Ok(hierarchy) => {
                    working_sources = hierarchy.visibility_summary.source_chunks().to_vec();
                    summary = Some(hierarchy.visibility_summary);
                    section_sources.extend(hierarchy.section_sources);
                    replaced_keys.extend(hierarchy.replaced_keys);
                    completed_groups.extend(hierarchy.affected_groups);
                }
                Err(error) => {
                    last_error = Some(format!("{error:#}"));
                    eprintln!(
                        "surface source group ({}, {}) changed or failed while building; retrying only that group: {error:#}",
                        group.0, group.1
                    );
                }
            }
        }
        if !had_candidate_groups && let Some(previous) = summary.take() {
            summary = Some(previous.with_source_metadata(header.file_marker, working_sources)?);
        }
        let Some(visibility_summary) = summary else {
            bail!(
                "no source group in Anvil region ({}, {}) could be built{}",
                coordinate.0,
                coordinate.1,
                last_error
                    .as_deref()
                    .map_or(String::new(), |error| format!(": {error}"))
            );
        };
        output.insert(
            coordinate,
            LoadedHierarchy {
                section_sources,
                visibility_summary,
                affected_groups: completed_groups,
                replaced_keys,
            },
        );
    }
    Ok(output)
}

fn catalog_incremental_compatible(old: &Catalog, current: &Catalog) -> bool {
    old.catalog_id == current.catalog_id
        && old.mip_generation == current.mip_generation
        && old.blocks.len() <= current.blocks.len()
        && old.biomes.len() <= current.biomes.len()
        && old.blocks == current.blocks[..old.blocks.len()]
        && old.biomes == current.biomes[..old.biomes.len()]
}

fn save_registry(registry: &RwLock<Registry>) -> Result<()> {
    write_lock(registry)?.save()
}

fn train_dictionaries_from_sources(
    hierarchies: &BTreeMap<(i32, i32), LoadedHierarchy>,
    store: &PackStore,
    registry: &RegistrySnapshot,
    visibility: &VisibilityIndex,
) -> Result<Vec<CanonicalObject>> {
    let mut corpora: [Vec<CanonicalObject>; super::manifest::CONTENT_CLASS_COUNT] =
        std::array::from_fn(|_| Vec::new());
    let mut corpus_bytes = [0usize; super::manifest::CONTENT_CLASS_COUNT];
    let mut sampled = 0usize;
    'regions: for hierarchy in hierarchies.values() {
        for (&key, hashes) in &hierarchy.section_sources {
            let section = load_source_section(store, key, hashes, Some(registry.catalog_id))?;
            if section.is_empty() {
                continue;
            }
            let exterior = visibility.descriptor(section.key, u64::MAX).exterior_mask;
            let prepared = prepare_section(&section, registry, exterior)?;
            for class in ContentClass::ALL {
                if let Some(content) = prepared.contents[class.index()].as_ref() {
                    for object in &content.objects {
                        let next = corpus_bytes[class.index()]
                            .checked_add(object.bytes().len())
                            .context("dictionary corpus size overflow")?;
                        if next > DICTIONARY_CORPUS_BYTES_PER_CLASS {
                            break;
                        }
                        corpus_bytes[class.index()] = next;
                        corpora[class.index()].push(object.clone());
                    }
                }
            }
            sampled += 1;
            if sampled == BUILD_BATCH {
                break 'regions;
            }
        }
    }
    let dictionaries = ContentClass::ALL
        .map(|class| train_dictionary(class, std::mem::take(&mut corpora[class.index()])))
        .into_iter()
        .collect::<Result<Vec<_>>>()?;
    Ok(dictionaries)
}

fn prepare_section_states_from_sources(
    hierarchies: &BTreeMap<(i32, i32), LoadedHierarchy>,
    root: Option<SectionKey>,
    registry: &RegistrySnapshot,
    visibility: &VisibilityIndex,
    dictionaries: &[CanonicalObject],
    store: &mut PackStore,
) -> Result<BTreeMap<SectionKey, SectionState>> {
    if dictionaries.len() != super::manifest::CONTENT_CLASS_COUNT {
        bail!("publication requires one dictionary per content class");
    }
    let mut states = BTreeMap::new();
    for hierarchy in hierarchies.values() {
        for (&key, hashes) in &hierarchy.section_sources {
            if let Some(root) = root {
                let source_root = top_root(key);
                if source_root != root && !touches_root_boundary(key, root, source_root) {
                    continue;
                }
            }
            let section = load_source_section(store, key, hashes, Some(registry.catalog_id))?;
            if section.is_empty() {
                continue;
            }
            let exterior = visibility.descriptor(section.key, u64::MAX).exterior_mask;
            let prepared = prepare_section(&section, registry, exterior)?;
            let mut contents: [Option<ContentDescriptor>; super::manifest::CONTENT_CLASS_COUNT] =
                std::array::from_fn(|_| None);
            for class in ContentClass::ALL {
                let Some(content) = prepared.contents[class.index()].as_ref() else {
                    continue;
                };
                store.put_many_with_dictionary(
                    content.objects.iter(),
                    &dictionaries[class.index()],
                )?;
                let domain = visibility.descriptor(section.key, content.microtile_mask);
                contents[class.index()] = Some(ContentDescriptor {
                    microtile_edge: 8,
                    microtile_mask: content.microtile_mask,
                    objects: content.objects.iter().map(CanonicalObject::hash).collect(),
                    dependencies: Vec::new(),
                    neighbor_dependency_masks: [0; 6],
                    neighbor_dependencies: std::array::from_fn(|_| Vec::new()),
                    exterior_microtile_mask: domain.exterior_mask,
                    unknown_microtile_mask: domain.unknown_mask,
                    visibility_memberships: domain
                        .memberships
                        .into_iter()
                        .map(|membership| VisibilityMembership {
                            domain: membership.domain,
                            microtile_mask: membership.microtile_mask,
                        })
                        .collect(),
                    boundary_face_mask: content.boundary_face_mask,
                    boundary_summary: content.boundary_summary.clone(),
                });
            }
            if states
                .insert(
                    key,
                    SectionState {
                        bounds: prepared.bounds,
                        contents,
                    },
                )
                .is_some()
            {
                bail!("changed source hierarchy contains a duplicate structural section");
            }
        }
    }
    Ok(states)
}

fn apply_visibility_memberships(
    sections: &mut BTreeMap<SectionKey, SectionState>,
    visibility: &VisibilityIndex,
) {
    for (&key, section) in sections {
        for content in section.contents.iter_mut().flatten() {
            let value = visibility.descriptor(key, content.microtile_mask);
            content.exterior_microtile_mask = value.exterior_mask;
            content.unknown_microtile_mask = value.unknown_mask;
            content.visibility_memberships = value
                .memberships
                .into_iter()
                .map(|membership| VisibilityMembership {
                    domain: membership.domain,
                    microtile_mask: membership.microtile_mask,
                })
                .collect();
        }
    }
}

fn load_region_hierarchy(
    source: &AnvilWorld,
    registry: &Arc<RwLock<Registry>>,
    store: &mut PackStore,
    request: RegionHierarchyRequest<'_>,
) -> Result<LoadedHierarchy> {
    let RegionHierarchyRequest {
        header,
        mut expected_sources,
        candidate_groups,
        previous_sources,
        previous_source_microtiles,
        previous_summary,
        force,
    } = request;
    if header.entries.len() != 1024 || expected_sources.len() != 1024 {
        bail!("incremental region build requires 1024 header/source entries");
    }
    if candidate_groups
        .iter()
        .any(|&(x, z)| x.div_euclid(16) != header.region_x || z.div_euclid(16) != header.region_z)
    {
        bail!("incremental region build contains a group outside its Anvil region");
    }
    let generated_coverage =
        generated_coverage_from_sources(header.region_x, header.region_z, &expected_sources)?;
    let group_ids = candidate_groups.iter().copied().collect::<Vec<_>>();

    // Each raw-changed 2×2 group is normalized, converted to exact 8³ source objects, and
    // propagated through its affected ancestor chain before decoded Sections are dropped.
    let mut summary = RegionalVisibilitySummary::begin_refresh(
        header.region_x,
        header.region_z,
        header.file_marker,
        expected_sources.clone(),
        previous_summary,
    )?;
    let mut section_sources = BTreeMap::<SectionKey, [ObjectHash; 64]>::new();
    let mut replaced_keys = BTreeSet::<SectionKey>::new();
    let mut affected_groups = BTreeSet::new();
    for (x, z) in group_ids {
        let group = source.load_level_zero_group(x, z, registry)?;
        let coverage = group
            .chunks
            .iter()
            .enumerate()
            .fold(0u8, |mask, (index, chunk)| {
                mask | if chunk.is_some() { 1 << index } else { 0 }
            });
        if generated_coverage.get(&(x, z)).copied().unwrap_or(0) != coverage {
            bail!("Anvil group coverage changed during incremental normalization");
        }
        let sources = group.sources();
        for &(chunk_x, chunk_z, fingerprint) in &sources {
            let slot = chunk_x.rem_euclid(32) as usize | ((chunk_z.rem_euclid(32) as usize) << 5);
            if expected_sources[slot].fingerprint != fingerprint {
                bail!("Anvil chunk changed during incremental normalization");
            }
        }
        let base_chunk_x = x.checked_mul(2).context("chunk-group x overflow")?;
        let base_chunk_z = z.checked_mul(2).context("chunk-group z overflow")?;
        let mut semantic_change = force;
        for (index, chunk) in group.chunks.iter().enumerate() {
            let chunk_x = base_chunk_x + (index as i32 & 1);
            let chunk_z = base_chunk_z + (index as i32 >> 1);
            let slot = chunk_x.rem_euclid(32) as usize | ((chunk_z.rem_euclid(32) as usize) << 5);
            let terrain = chunk.as_ref().map(|chunk| chunk.terrain_fingerprint);
            semantic_change |= previous_sources
                .and_then(|sources| sources.get(slot))
                .and_then(|source| source.terrain_fingerprint)
                != terrain;
            expected_sources[slot].terrain_fingerprint = terrain;
        }
        source.verify_sources(&sources)?;
        if !semantic_change {
            continue;
        }
        affected_groups.insert((x, z));
        let previous_level_zero = previous_source_microtiles
            .keys()
            .filter(|key| key.level == 0 && key.x == x && key.z == z)
            .copied()
            .collect::<BTreeSet<_>>();
        replaced_keys.extend(previous_level_zero.iter().copied());
        let mut current = BTreeMap::<SectionKey, Section>::new();
        for key in group.keys() {
            let section = group.build(key, source)?.section;
            replaced_keys.insert(section.key);
            current.insert(section.key, section);
        }
        let registry_snapshot = read_lock(registry)?.snapshot();
        let current_sources =
            persist_source_sections(&current, registry_snapshot.catalog_id, store)?;
        section_sources.extend(current_sources.iter().map(|(&key, hashes)| (key, *hashes)));
        summary.replace_group((x, z), &current, &current_sources, &registry_snapshot)?;

        let opacity = read_lock(registry)?.opacity_table();
        let mut changed_children = previous_level_zero;
        changed_children.extend(current.keys().copied());
        let mut previous_cache = BTreeMap::<SectionKey, Option<Section>>::new();
        for level in 1..=MAX_LOD {
            let parents = affected_parents(&changed_children);
            let mut next = BTreeMap::<SectionKey, Section>::new();
            for &parent in &parents {
                if parent.x.div_euclid(1 << (MAX_LOD - level)) != header.region_x
                    || parent.z.div_euclid(1 << (MAX_LOD - level)) != header.region_z
                {
                    bail!("incremental parent hierarchy escaped its aligned Anvil region");
                }
                let mut children: [Option<Section>; 8] = [(); 8].map(|_| None);
                for dy in 0..2i32 {
                    for dz in 0..2i32 {
                        for dx in 0..2i32 {
                            let child = SectionKey::new(
                                level - 1,
                                parent.x * 2 + dx,
                                parent.y * 2 + dy,
                                parent.z * 2 + dz,
                            )?;
                            children[(dx | (dz << 1) | (dy << 2)) as usize] =
                                resolve_incremental_section(
                                    child,
                                    &current,
                                    &section_sources,
                                    &replaced_keys,
                                    previous_source_microtiles,
                                    store,
                                    &opacity,
                                    &mut previous_cache,
                                )?;
                        }
                    }
                }
                let section = build_parent(parent, &children, &opacity)?;
                replaced_keys.insert(parent);
                if !section.is_empty() {
                    next.insert(parent, section);
                }
            }
            let next_sources = persist_source_sections(&next, registry_snapshot.catalog_id, store)?;
            for parent in &parents {
                section_sources.remove(parent);
            }
            section_sources.extend(next_sources);
            current = next;
            changed_children = parents;
        }
    }
    let visibility_summary = summary.finish_refresh(header.file_marker, expected_sources)?;
    Ok(LoadedHierarchy {
        section_sources,
        visibility_summary,
        affected_groups,
        replaced_keys,
    })
}

fn persist_source_sections(
    sections: &BTreeMap<SectionKey, Section>,
    catalog_id: u64,
    store: &mut PackStore,
) -> Result<BTreeMap<SectionKey, [ObjectHash; 64]>> {
    let mut sources = BTreeMap::new();
    for (&key, section) in sections {
        let objects = prepare_source_microtiles(section, catalog_id)?;
        store.put_many(objects.iter())?;
        sources.insert(key, objects.map(|object| object.hash()));
    }
    Ok(sources)
}

#[allow(clippy::too_many_arguments)]
fn resolve_incremental_section(
    key: SectionKey,
    current: &BTreeMap<SectionKey, Section>,
    changed_sources: &BTreeMap<SectionKey, [ObjectHash; 64]>,
    replaced: &BTreeSet<SectionKey>,
    previous_source_microtiles: &BTreeMap<SectionKey, [ObjectHash; 64]>,
    store: &PackStore,
    opacity: &[u8],
    previous_cache: &mut BTreeMap<SectionKey, Option<Section>>,
) -> Result<Option<Section>> {
    if let Some(section) = current.get(&key) {
        return Ok(Some(section.clone()));
    }
    if let Some(hashes) = changed_sources.get(&key) {
        return load_source_section(store, key, hashes, None).map(Some);
    }
    if replaced.contains(&key) {
        return Ok(None);
    }
    derive_previous_section(
        key,
        previous_source_microtiles,
        store,
        opacity,
        previous_cache,
    )
}

fn derive_previous_section(
    key: SectionKey,
    previous_source_microtiles: &BTreeMap<SectionKey, [ObjectHash; 64]>,
    store: &PackStore,
    opacity: &[u8],
    cache: &mut BTreeMap<SectionKey, Option<Section>>,
) -> Result<Option<Section>> {
    if let Some(cached) = cache.get(&key) {
        return Ok(cached.clone());
    }
    let section = if key.level == 0 {
        previous_source_microtiles
            .get(&key)
            .map(|hashes| load_source_section(store, key, hashes, None))
            .transpose()?
    } else {
        let mut children: [Option<Section>; 8] = [(); 8].map(|_| None);
        for dy in 0..2i32 {
            for dz in 0..2i32 {
                for dx in 0..2i32 {
                    let child = SectionKey::new(
                        key.level - 1,
                        key.x * 2 + dx,
                        key.y * 2 + dy,
                        key.z * 2 + dz,
                    )?;
                    children[(dx | (dz << 1) | (dy << 2)) as usize] = derive_previous_section(
                        child,
                        previous_source_microtiles,
                        store,
                        opacity,
                        cache,
                    )?;
                }
            }
        }
        let parent = build_parent(key, &children, opacity)?;
        (!parent.is_empty()).then_some(parent)
    };
    cache.insert(key, section.clone());
    Ok(section)
}

fn load_source_section(
    store: &PackStore,
    key: SectionKey,
    hashes: &[ObjectHash; 64],
    expected_catalog: Option<u64>,
) -> Result<Section> {
    if hashes.iter().any(|hash| hash.is_zero()) {
        bail!("invalid exact source-state section descriptor");
    }
    let mut cells = vec![Cell::AIR; SECTION_VOLUME];
    let mut catalog = None;
    for (morton, hash) in hashes.iter().copied().enumerate() {
        let object = store
            .get(hash)?
            .with_context(|| format!("source microtile {hash} is missing"))?;
        if object.kind() != ObjectKind::SourceMicrotile {
            bail!("exact source-state descriptor references the wrong object type");
        }
        let tile = SourceMicrotile::decode(object.bytes())?;
        if catalog
            .replace(tile.catalog_id())
            .is_some_and(|old| old != tile.catalog_id())
        {
            bail!("one exact source section mixes catalog identities");
        }
        let [mx, my, mz] = inverse_morton2(morton);
        let expected = [
            mx * MICROTILE_EDGE,
            my * MICROTILE_EDGE,
            mz * MICROTILE_EDGE,
        ];
        if tile.origin().map(usize::from) != expected {
            bail!("source microtile origin disagrees with its Morton slot");
        }
        for y in 0..MICROTILE_EDGE {
            for z in 0..MICROTILE_EDGE {
                for x in 0..MICROTILE_EDGE {
                    let source = x | (z << 3) | (y << 6);
                    cells[cell_index(expected[0] + x, expected[1] + y, expected[2] + z)] =
                        tile.cells()[source];
                }
            }
        }
    }
    if expected_catalog.is_some_and(|expected| catalog != Some(expected)) {
        bail!("exact source-state catalog disagrees with its root catalog");
    }
    Section::from_cells(key, cells)
}

fn generated_coverage_from_sources(
    region_x: i32,
    region_z: i32,
    sources: &[RegionChunkSource],
) -> Result<BTreeMap<(i32, i32), u8>> {
    if sources.len() != 1024 {
        bail!("region source table must contain 1024 chunks");
    }
    let base_x = region_x
        .checked_mul(16)
        .context("region group x overflow")?;
    let base_z = region_z
        .checked_mul(16)
        .context("region group z overflow")?;
    let mut coverage = BTreeMap::new();
    for (slot, source) in sources.iter().enumerate() {
        if source.fingerprint.is_none() {
            continue;
        }
        let local_x = slot & 31;
        let local_z = slot >> 5;
        let group = (base_x + (local_x / 2) as i32, base_z + (local_z / 2) as i32);
        let bit = (local_x & 1) | ((local_z & 1) << 1);
        *coverage.entry(group).or_default() |= 1u8 << bit;
    }
    Ok(coverage)
}

fn registry_snapshot(registry: &RwLock<Registry>) -> Result<RegistrySnapshot> {
    Ok(read_lock(registry)?.snapshot())
}

fn load_root_dictionaries(store: &PackStore, root: RootRecord) -> Result<Vec<CanonicalObject>> {
    let set_hash = root.dictionary_set;
    let set = store
        .get(set_hash)?
        .context("root dictionary set is missing")?;
    let set = DictionarySet::decode(set.bytes())?;
    let mut by_class: [Option<CanonicalObject>; super::manifest::CONTENT_CLASS_COUNT] =
        std::array::from_fn(|_| None);
    for hash in set.dictionaries {
        let dictionary = store
            .get(hash)?
            .context("root compression dictionary is missing")?;
        let class = decode_dictionary(dictionary.bytes())?.class;
        if by_class[class.index()].replace(dictionary).is_some() {
            bail!("root has duplicate class compression dictionaries");
        }
    }
    let mut dictionaries = Vec::with_capacity(super::manifest::CONTENT_CLASS_COUNT);
    for (class, dictionary) in by_class.into_iter().enumerate() {
        dictionaries
            .push(dictionary.with_context(|| format!("root has no dictionary for class {class}"))?);
    }
    Ok(dictionaries)
}

fn class_dictionary_hashes(
    dictionaries: &[CanonicalObject],
) -> Result<[ObjectHash; super::manifest::CONTENT_CLASS_COUNT]> {
    if dictionaries.len() != super::manifest::CONTENT_CLASS_COUNT {
        bail!("root must contain one dictionary per content class");
    }
    let mut hashes = [ObjectHash::ZERO; super::manifest::CONTENT_CLASS_COUNT];
    for dictionary in dictionaries {
        let class = decode_dictionary(dictionary.bytes())?.class;
        if !hashes[class.index()].is_zero() {
            bail!("root contains duplicate class dictionaries");
        }
        hashes[class.index()] = dictionary.hash();
    }
    if hashes.iter().any(|hash| hash.is_zero()) {
        bail!("root is missing a content-class dictionary");
    }
    Ok(hashes)
}

fn load_group_sections(
    objects: &PackStore,
    root: SectionKey,
    group: &GroupState,
    class_dictionaries: &[ObjectHash; super::manifest::CONTENT_CLASS_COUNT],
) -> Result<BTreeMap<SectionKey, SectionState>> {
    load_group_sections_matching(objects, root, group, class_dictionaries, |_| true)
}

fn load_group_sections_matching(
    objects: &PackStore,
    root: SectionKey,
    group: &GroupState,
    class_dictionaries: &[ObjectHash; super::manifest::CONTENT_CLASS_COUNT],
    include: impl Fn(SectionKey) -> bool,
) -> Result<BTreeMap<SectionKey, SectionState>> {
    let mut sections = BTreeMap::new();
    let mut visited = HashSet::new();
    load_manifest_tree(
        objects,
        group.manifest,
        spatial(root),
        class_dictionaries,
        &include,
        &mut visited,
        &mut sections,
    )?;
    Ok(sections)
}

fn check_recovered_groups(
    objects: &PackStore,
    groups: &BTreeMap<SectionKey, GroupState>,
    class_dictionaries: &[ObjectHash; super::manifest::CONTENT_CLASS_COUNT],
) -> Result<()> {
    for (&root, group) in groups {
        load_group_sections(objects, root, group, class_dictionaries)?;
    }
    Ok(())
}

fn adjacent_top_roots(root: SectionKey) -> Result<[SectionKey; 6]> {
    if root.level != MAX_LOD {
        bail!("neighbor publication root is not level {MAX_LOD}");
    }
    Ok([
        SectionKey::new(
            root.level,
            root.x.checked_sub(1).context("root x underflow")?,
            root.y,
            root.z,
        )?,
        SectionKey::new(
            root.level,
            root.x.checked_add(1).context("root x overflow")?,
            root.y,
            root.z,
        )?,
        SectionKey::new(
            root.level,
            root.x,
            root.y.checked_sub(1).context("root y underflow")?,
            root.z,
        )?,
        SectionKey::new(
            root.level,
            root.x,
            root.y.checked_add(1).context("root y overflow")?,
            root.z,
        )?,
        SectionKey::new(
            root.level,
            root.x,
            root.y,
            root.z.checked_sub(1).context("root z underflow")?,
        )?,
        SectionKey::new(
            root.level,
            root.x,
            root.y,
            root.z.checked_add(1).context("root z overflow")?,
        )?,
    ])
}

fn touches_root_boundary(key: SectionKey, root: SectionKey, neighbor: SectionKey) -> bool {
    if key.level > MAX_LOD || root.level != MAX_LOD || neighbor.level != MAX_LOD {
        return false;
    }
    let width = 1i32 << (MAX_LOD - key.level);
    let (Some(start_x), Some(start_y), Some(start_z)) = (
        root.x.checked_mul(width),
        root.y.checked_mul(width),
        root.z.checked_mul(width),
    ) else {
        return false;
    };
    let Some(delta) = neighbor
        .x
        .checked_sub(root.x)
        .zip(neighbor.y.checked_sub(root.y))
        .zip(neighbor.z.checked_sub(root.z))
        .map(|((x, y), z)| [x, y, z])
    else {
        return false;
    };
    match delta {
        [-1, 0, 0] => start_x.checked_sub(1) == Some(key.x),
        [1, 0, 0] => start_x.checked_add(width) == Some(key.x),
        [0, -1, 0] => start_y.checked_sub(1) == Some(key.y),
        [0, 1, 0] => start_y.checked_add(width) == Some(key.y),
        [0, 0, -1] => start_z.checked_sub(1) == Some(key.z),
        [0, 0, 1] => start_z.checked_add(width) == Some(key.z),
        _ => false,
    }
}

fn attach_incremental_neighbor_dependencies(
    root: SectionKey,
    sections: &mut BTreeMap<SectionKey, SectionState>,
    old_sections: &BTreeMap<SectionKey, SectionState>,
    changed: &BTreeMap<SectionKey, SectionState>,
    replaced: &BTreeSet<SectionKey>,
) -> Result<()> {
    // A top-level group is the bounded publication unit. Only this group's descriptors are
    // cloned while neighbor hashes are resolved lazily from immutable old/changed maps.
    let snapshot = sections.clone();
    for (&key, section) in sections.iter_mut() {
        for class in ContentClass::ALL {
            let Some(content) = section.contents[class.index()].as_mut() else {
                continue;
            };
            let mut dependency_masks = [0u64; 6];
            let mut dependencies: [Vec<ObjectHash>; 6] = std::array::from_fn(|_| Vec::new());
            for microtile in 0..64usize {
                if content.microtile_mask & (1u64 << microtile) == 0 {
                    continue;
                }
                for face in 0..6usize {
                    let Some(hash) = exact_neighbor_hash(key, microtile, face, |target| {
                        if top_root(target) == root {
                            return snapshot.get(&target);
                        }
                        if let Some(state) = changed.get(&target) {
                            return Some(state);
                        }
                        if replaced.contains(&target) {
                            return None;
                        }
                        old_sections.get(&target)
                    })?
                    else {
                        continue;
                    };
                    dependency_masks[face] |= 1u64 << microtile;
                    dependencies[face].push(hash);
                }
            }
            if dependencies.iter().map(Vec::len).sum::<usize>() > 6 * 64 {
                bail!("microtile neighbor dependency set exceeds its bound");
            }
            content.neighbor_dependency_masks = dependency_masks;
            content.neighbor_dependencies = dependencies;
        }
    }
    Ok(())
}

fn exact_neighbor_hash<'a>(
    source_key: SectionKey,
    source_microtile: usize,
    face: usize,
    lookup: impl FnOnce(SectionKey) -> Option<&'a SectionState>,
) -> Result<Option<ObjectHash>> {
    let [x, y, z] = inverse_morton2(source_microtile);
    let (dx, dy, dz) = match face {
        0 => (-1, 0, 0),
        1 => (1, 0, 0),
        2 => (0, -1, 0),
        3 => (0, 1, 0),
        4 => (0, 0, -1),
        5 => (0, 0, 1),
        _ => bail!("invalid neighbor face"),
    };
    let mut coordinates = [x as i32 + dx, y as i32 + dy, z as i32 + dz];
    let mut target_key = source_key;
    let section_delta = coordinates.map(|coordinate| coordinate.div_euclid(4));
    coordinates = coordinates.map(|coordinate| coordinate.rem_euclid(4));
    if section_delta != [0; 3] {
        let Some(target_x) = source_key.x.checked_add(section_delta[0]) else {
            return Ok(None);
        };
        let Some(target_y) = source_key.y.checked_add(section_delta[1]) else {
            return Ok(None);
        };
        let Some(target_z) = source_key.z.checked_add(section_delta[2]) else {
            return Ok(None);
        };
        target_key = match SectionKey::new(source_key.level, target_x, target_y, target_z) {
            Ok(key) => key,
            Err(_) => return Ok(None),
        };
    }
    let Some(target) = lookup(target_key) else {
        return Ok(None);
    };
    let Some(complex) = &target.contents[ContentClass::Complex.index()] else {
        return Ok(None);
    };
    let target_morton = morton3(
        coordinates[0] as u32,
        coordinates[1] as u32,
        coordinates[2] as u32,
        2,
    )?;
    Ok(descriptor_hash(complex, target_morton))
}

fn inverse_morton2(index: usize) -> [usize; 3] {
    let high = index >> 3;
    let low = index & 7;
    [
        ((high & 1) << 1) | (low & 1),
        (((high >> 1) & 1) << 1) | ((low >> 1) & 1),
        (((high >> 2) & 1) << 1) | ((low >> 2) & 1),
    ]
}

fn build_manifest_tree(
    root: SectionKey,
    sections: &BTreeMap<SectionKey, SectionState>,
    mut write: impl FnMut(&CanonicalObject) -> Result<()>,
) -> Result<ObjectHash> {
    if root.level != MAX_LOD || !sections.contains_key(&root) {
        bail!("manifest has no LOD-4 structural root");
    }
    let levels = MAX_SUBTREE_LEVELS;
    let slots = slots_for_levels(levels);
    let mut by_slot = BTreeMap::new();
    for (&key, state) in sections {
        let Some(depth) = descendant_depth(root, key) else {
            continue;
        };
        if depth >= levels {
            bail!("section descends below the complete five-level manifest");
        }
        let slot = section_slot(root, key)?;
        if by_slot.insert(slot, (key, state)).is_some() {
            bail!("two sections map to one manifest slot");
        }
    }

    let page_slots = descriptor_page_slots(levels);
    let mut page_hashes = BTreeMap::new();
    let present_pages = by_slot
        .keys()
        .map(|slot| slot / DESCRIPTOR_PAGE_SLOTS)
        .collect::<BTreeSet<_>>();
    for page in present_pages {
        let first_slot = page * DESCRIPTOR_PAGE_SLOTS;
        let mut contents =
            vec![std::array::from_fn(|_| None); descriptor_page_slot_count(levels, page)?];
        for (&slot, &(_, state)) in by_slot.range(first_slot..first_slot + contents.len()) {
            contents[slot - first_slot] = state.contents.clone();
        }
        let descriptor_page = ManifestDescriptorPage::new(
            spatial(root),
            levels,
            page.try_into()
                .context("descriptor-page index exceeds u16")?,
            contents,
        )?
        .canonical_object()?;
        page_hashes.insert(page, descriptor_page.hash());
        write(&descriptor_page)?;
    }

    let tile_availability = availability_with(by_slot.keys().copied(), slots)?;
    let descriptor_page_availability = availability_with(page_hashes.keys().copied(), page_slots)?;
    let present = by_slot.keys().copied().collect::<HashSet<_>>();
    let mut nodes = Vec::with_capacity(by_slot.len());
    for (&slot, &(key, state)) in &by_slot {
        let depth = root.level - key.level;
        let morton = slot - level_offset(depth);
        let child_mask = if depth + 1 < levels {
            let child_offset = level_offset(depth + 1) + morton * 8;
            (0..8).fold(0, |mask, child| {
                mask | if present.contains(&(child_offset + child)) {
                    1 << child
                } else {
                    0
                }
            })
        } else {
            0
        };
        nodes.push(ManifestNode {
            child_mask,
            bounds: state.bounds,
            geometric_error_q16: geometric_error_q16(key, state),
        });
    }
    let manifest = ManifestSubtree::from_parts(
        spatial(root),
        levels,
        tile_availability,
        descriptor_page_availability,
        page_hashes.values().copied().collect(),
        nodes,
    )?
    .canonical_object()?;
    let hash = manifest.hash();
    write(&manifest)?;
    Ok(hash)
}

fn geometric_error_q16(key: SectionKey, state: &SectionState) -> u32 {
    let Some(bounds) = state.bounds else {
        return 0;
    };
    let extent = (0..3)
        .map(|axis| u32::from(bounds.max[axis].saturating_sub(bounds.min[axis])))
        .max()
        .unwrap_or(0)
        .max(1);
    let cell_scale = 1u32 << key.level;
    let complexity = if state.contents[ContentClass::Complex.index()]
        .as_ref()
        .is_some_and(|content| {
            let ordinary = state.contents[ContentClass::Exterior.index()]
                .as_ref()
                .map_or(0, |value| value.microtile_mask)
                | state.contents[ContentClass::Interior.index()]
                    .as_ref()
                    .map_or(0, |value| value.microtile_mask);
            content.microtile_mask & !ordinary != 0
        }) {
        2
    } else {
        1
    };
    // Bounds are quantized across 32 cells. Retaining at least one cell of error is
    // conservative; complex/model-dependent content receives twice the refinement pressure.
    let extent_cells_q16 = extent.saturating_mul(32).div_ceil(u16::MAX as u32);
    cell_scale
        .saturating_mul(complexity)
        .saturating_mul(extent_cells_q16.max(1))
        .saturating_mul(1 << 16)
}

fn build_directory_to(
    groups: &BTreeMap<SectionKey, GroupState>,
    mut write: impl FnMut(&CanonicalObject) -> Result<()>,
) -> Result<CanonicalObject> {
    let mut entries = groups
        .iter()
        .map(|(&root, group)| RootDirectoryEntry::manifest(spatial(root), group.manifest))
        .collect::<Vec<_>>();
    entries.sort_unstable_by_key(|entry| directory_morton_key(entry.node));
    if entries.is_empty() {
        return RootDirectory::new(Vec::new())?.canonical_object();
    }
    while entries.len() > DIRECTORY_PAGE_ENTRIES {
        let mut parents = Vec::with_capacity(entries.len().div_ceil(DIRECTORY_PAGE_ENTRIES));
        for page in entries.chunks(DIRECTORY_PAGE_ENTRIES) {
            let directory = RootDirectory::new(page.to_vec())?.canonical_object()?;
            parents.push(RootDirectoryEntry::union(page, directory.hash())?);
            write(&directory)?;
        }
        parents.sort_unstable_by_key(|entry| directory_morton_key(entry.node));
        entries = parents;
    }
    RootDirectory::new(entries)?.canonical_object()
}

fn publish_if_changed(
    roots: &mut RootStore,
    record: RootRecord,
    objects: &PackStore,
    old: Option<RootRecord>,
) -> Result<bool> {
    if old.is_some_and(|old| {
        old.dimension == record.dimension
            && old.root_manifest == record.root_manifest
            && old.catalog == record.catalog
            && old.dictionary_set == record.dictionary_set
            && old.visibility == record.visibility
    }) {
        return Ok(false);
    }
    Ok(matches!(
        roots.publish(record, objects)?,
        PublishResult::Published
    ))
}

fn next_record(
    dimension: &str,
    old: Option<RootRecord>,
    directory: ObjectHash,
    catalog: ObjectHash,
    dictionary_set: ObjectHash,
    visibility: ObjectHash,
) -> Result<RootRecord> {
    let unchanged = old.is_some_and(|old| {
        old.root_manifest == directory
            && old.catalog == catalog
            && old.dictionary_set == dictionary_set
            && old.visibility == visibility
    });
    let generation = match old {
        None => 1,
        Some(old) if unchanged => old.generation,
        Some(old) => old
            .generation
            .checked_add(1)
            .context("surface root generation is exhausted")?,
    };
    RootRecord::new(
        generation,
        ObjectHash::dimension(dimension)?,
        directory,
        catalog,
        dictionary_set,
        visibility,
    )
}

fn load_directory(
    objects: &PackStore,
    hash: ObjectHash,
    depth: usize,
    parent_extent: Option<([i32; 3], [i32; 3])>,
    visited: &mut HashSet<ObjectHash>,
    groups: &mut BTreeMap<SectionKey, GroupState>,
) -> Result<()> {
    if depth > MAX_DIRECTORY_DEPTH
        || visited.len() >= MAX_ROOT_GRAPH_OBJECTS
        || !visited.insert(hash)
    {
        bail!("root-directory graph is cyclic or exceeds its bound");
    }
    let object = objects
        .get(hash)?
        .context("root directory object is missing")?;
    if object.kind() != ObjectKind::RootDirectory {
        bail!("root-directory graph references the wrong object type");
    }
    for entry in RootDirectory::decode(object.bytes())?.entries {
        if parent_extent.is_some_and(|(min, max)| {
            (0..3).any(|axis| entry.min[axis] < min[axis] || entry.max[axis] > max[axis])
        }) {
            bail!("nested root-directory entry escapes its parent routing extent");
        }
        match entry.target {
            DirectoryTarget::RootDirectory => {
                load_directory(
                    objects,
                    entry.hash,
                    depth + 1,
                    Some((entry.min, entry.max)),
                    visited,
                    groups,
                )?;
            }
            DirectoryTarget::ManifestSubtree => {
                let root = key(entry.node)?;
                let manifest = objects
                    .get(entry.hash)?
                    .context("top-level manifest is missing")?;
                if manifest.kind() != ObjectKind::ManifestSubtree
                    || ManifestSubtree::decode(manifest.bytes())?.root != entry.node
                {
                    bail!("top-level manifest disagrees with its root directory entry");
                }
                if groups
                    .insert(
                        root,
                        GroupState {
                            manifest: entry.hash,
                        },
                    )
                    .is_some()
                {
                    bail!("directory contains a duplicate structural root");
                }
            }
        }
    }
    Ok(())
}

fn load_manifest_tree(
    objects: &PackStore,
    hash: ObjectHash,
    expected_root: SpatialNode,
    class_dictionaries: &[ObjectHash; super::manifest::CONTENT_CLASS_COUNT],
    include: &impl Fn(SectionKey) -> bool,
    visited: &mut HashSet<ObjectHash>,
    sections: &mut BTreeMap<SectionKey, SectionState>,
) -> Result<()> {
    if visited.len() >= MAX_ROOT_GRAPH_OBJECTS || !visited.insert(hash) {
        bail!("manifest graph is shared or exceeds its bound");
    }
    let manifest_object = objects.get(hash)?.context("manifest is missing")?;
    if manifest_object.kind() != ObjectKind::ManifestSubtree {
        bail!("manifest graph references a wrong-type object");
    }
    let manifest = ManifestSubtree::decode(manifest_object.bytes())?;
    if manifest.root != expected_root {
        bail!("manifest graph spatial roots disagree");
    }
    let mut wanted_pages = BTreeSet::new();
    for depth in 0..manifest.levels {
        for morton in 0..8usize.pow(u32::from(depth)) {
            let slot = level_offset(depth) + morton;
            if bit(&manifest.tile_availability, slot)
                && include(key_from_morton(manifest.root, depth, morton)?)
            {
                wanted_pages.insert(slot / DESCRIPTOR_PAGE_SLOTS);
            }
        }
    }
    let mut page_hashes = BTreeMap::new();
    let mut dense_page = 0usize;
    for page_index in 0..manifest.descriptor_page_slots() {
        if !bit(&manifest.descriptor_page_availability, page_index) {
            continue;
        }
        let page_hash = manifest.descriptor_pages[dense_page];
        dense_page += 1;
        page_hashes.insert(page_index, page_hash);
    }
    if dense_page != manifest.descriptor_pages.len() {
        bail!("manifest descriptor hashes disagree with page availability");
    }
    let mut pages = BTreeMap::new();
    for page_index in wanted_pages {
        let page_hash = *page_hashes
            .get(&page_index)
            .context("available structural tile has no descriptor page")?;
        if visited.len() >= MAX_ROOT_GRAPH_OBJECTS || !visited.insert(page_hash) {
            bail!("manifest descriptor graph is shared or exceeds its bound");
        }
        let page_object = objects
            .get(page_hash)?
            .context("manifest descriptor page is missing")?;
        if page_object.kind() != ObjectKind::ManifestDescriptorPage {
            bail!("manifest references a wrong-type descriptor page");
        }
        let page = ManifestDescriptorPage::decode(page_object.bytes())?;
        validate_descriptor_page_membership(&manifest, page_index, &page)?;
        if pages.insert(page_index, page).is_some() {
            bail!("manifest contains a duplicate descriptor page");
        }
    }
    for (key, state) in
        sections_from_manifest(&manifest, &pages, objects, class_dictionaries, include)?
    {
        if sections.insert(key, state).is_some() {
            bail!("manifest graph contains a duplicate structural node");
        }
    }
    Ok(())
}

fn sections_from_manifest(
    manifest: &ManifestSubtree,
    pages: &BTreeMap<usize, ManifestDescriptorPage>,
    objects: &PackStore,
    class_dictionaries: &[ObjectHash; super::manifest::CONTENT_CLASS_COUNT],
    include: &impl Fn(SectionKey) -> bool,
) -> Result<BTreeMap<SectionKey, SectionState>> {
    let mut result = BTreeMap::new();
    let mut dense = 0usize;
    for depth in 0..manifest.levels {
        for morton in 0..8usize.pow(u32::from(depth)) {
            let slot = level_offset(depth) + morton;
            if !bit(&manifest.tile_availability, slot) {
                continue;
            }
            let key = key_from_morton(manifest.root, depth, morton)?;
            let node = &manifest.nodes[dense];
            dense += 1;
            if !include(key) {
                continue;
            }
            let page_index = slot / DESCRIPTOR_PAGE_SLOTS;
            let local_slot = slot % DESCRIPTOR_PAGE_SLOTS;
            let contents = pages
                .get(&page_index)
                .and_then(|page| page.contents.get(local_slot))
                .context("available structural tile has no descriptor page entry")?;
            if all_contents_absent_runtime(contents) {
                bail!("available structural tile has no descriptor content");
            }
            let exterior_mask = contents[ContentClass::Exterior.index()]
                .as_ref()
                .map_or(0, |content| content.microtile_mask);
            let interior_mask = contents[ContentClass::Interior.index()]
                .as_ref()
                .map_or(0, |content| content.microtile_mask);
            let complex_mask = contents[ContentClass::Complex.index()]
                .as_ref()
                .map_or(0, |content| content.microtile_mask);
            if (exterior_mask | interior_mask) & !complex_mask != 0 {
                bail!("ordinary microtile lacks its independently selectable complex companion");
            }
            for class in ContentClass::ALL {
                let Some(content) = &contents[class.index()] else {
                    continue;
                };
                let expected_kind = content_kind(class);
                for &hash in &content.objects {
                    validate_content_object(
                        objects,
                        hash,
                        expected_kind,
                        class_dictionaries[class.index()],
                        "content microtile",
                    )?;
                }
                for dependency in &content.dependencies {
                    validate_content_object(
                        objects,
                        *dependency,
                        expected_kind,
                        class_dictionaries[class.index()],
                        "content dependency",
                    )?;
                }
                for dependencies in &content.neighbor_dependencies {
                    for &dependency_hash in dependencies {
                        validate_content_object(
                            objects,
                            dependency_hash,
                            ObjectKind::ComplexMicrotile,
                            class_dictionaries[ContentClass::Complex.index()],
                            "neighbor dependency",
                        )?;
                    }
                }
            }
            result.insert(
                key,
                SectionState {
                    bounds: node.bounds,
                    contents: contents.clone(),
                },
            );
        }
    }
    if dense != manifest.nodes.len() {
        bail!("manifest dense-node traversal disagrees with availability");
    }
    Ok(result)
}

fn validate_content_object(
    objects: &PackStore,
    hash: ObjectHash,
    expected_kind: ObjectKind,
    expected_dictionary: ObjectHash,
    label: &str,
) -> Result<()> {
    let location = objects
        .location(hash)
        .with_context(|| format!("{label} has no pack location"))?;
    if location.kind != expected_kind || location.dictionary != expected_dictionary {
        bail!("{label} has the wrong type or dictionary");
    }
    Ok(())
}

fn all_contents_absent_runtime(
    contents: &[Option<ContentDescriptor>; super::manifest::CONTENT_CLASS_COUNT],
) -> bool {
    contents.iter().all(Option::is_none)
}

fn validate_descriptor_page_membership(
    manifest: &ManifestSubtree,
    page_index: usize,
    page: &ManifestDescriptorPage,
) -> Result<()> {
    if page.root != manifest.root
        || page.levels != manifest.levels
        || usize::from(page.page_index) != page_index
        || page.contents.iter().enumerate().any(|(local, contents)| {
            let slot = page_index * DESCRIPTOR_PAGE_SLOTS + local;
            bit(&manifest.tile_availability, slot) == all_contents_absent_runtime(contents)
        })
    {
        bail!("descriptor page disagrees with its structural manifest");
    }
    Ok(())
}

fn descriptor_hash(content: &ContentDescriptor, microtile: usize) -> Option<ObjectHash> {
    if microtile >= 64 || content.microtile_mask & (1u64 << microtile) == 0 {
        return None;
    }
    let dense = (content.microtile_mask & ((1u64 << microtile).wrapping_sub(1))).count_ones();
    content.objects.get(dense as usize).copied()
}

fn section_slot(root: SectionKey, key: SectionKey) -> Result<usize> {
    if key.level > root.level {
        bail!("section is coarser than its manifest root");
    }
    let depth = root.level - key.level;
    let mut current = key;
    let mut path = Vec::with_capacity(depth as usize);
    while current.level < root.level {
        let octant = current.x.rem_euclid(2) as usize
            | ((current.y.rem_euclid(2) as usize) << 1)
            | ((current.z.rem_euclid(2) as usize) << 2);
        path.push(octant);
        current = current.parent().context("finer section has no parent")?;
    }
    if current != root {
        bail!("section is outside its manifest root");
    }
    let morton = path
        .into_iter()
        .rev()
        .fold(0usize, |value, octant| (value << 3) | octant);
    Ok(level_offset(depth) + morton)
}

fn descendant_depth(root: SectionKey, key: SectionKey) -> Option<u8> {
    if key.level > root.level {
        return None;
    }
    let depth = root.level - key.level;
    let mut current = key;
    while current.level < root.level {
        current = current.parent()?;
    }
    (current == root).then_some(depth)
}

fn key_from_morton(root: SpatialNode, depth: u8, morton: usize) -> Result<SectionKey> {
    let mut x = root.x;
    let mut y = root.y;
    let mut z = root.z;
    for step in (0..depth).rev() {
        let octant = (morton >> (usize::from(step) * 3)) & 7;
        x = x
            .checked_mul(2)
            .and_then(|value| value.checked_add((octant & 1) as i32))
            .context("manifest x overflow")?;
        y = y
            .checked_mul(2)
            .and_then(|value| value.checked_add(((octant >> 1) & 1) as i32))
            .context("manifest y overflow")?;
        z = z
            .checked_mul(2)
            .and_then(|value| value.checked_add(((octant >> 2) & 1) as i32))
            .context("manifest z overflow")?;
    }
    SectionKey::new(root.lod - depth, x, y, z)
}

fn top_root(mut key: SectionKey) -> SectionKey {
    while let Some(parent) = key.parent() {
        key = parent;
    }
    key
}

fn spatial(key: SectionKey) -> SpatialNode {
    SpatialNode {
        lod: key.level,
        x: key.x,
        y: key.y,
        z: key.z,
    }
}

fn key(node: SpatialNode) -> Result<SectionKey> {
    SectionKey::new(node.lod, node.x, node.y, node.z)
}
