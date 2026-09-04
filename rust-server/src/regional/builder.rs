use super::{
    ChunkSourceRecord, RegionFile, RegionFileBuilder, RegionLayout, RegionSourceTable,
    SectionCoordinate, SectionFrame,
};
use crate::{
    anvil::{AnvilWorld, RegionHeader},
    catalog::Catalog,
    key::SectionKey,
    lod::{Section, build_parent_from_refs},
    read_lock,
    registry::Registry,
    write_lock,
};
use anyhow::{Context, Result, bail};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::Path,
    sync::{Arc, RwLock},
};

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct RegionalBuildStats {
    pub chunks_read: usize,
    pub generated_chunks: usize,
    pub sections_by_level: [usize; crate::MAX_LOD as usize + 1],
    pub reused_sections: usize,
    pub output_bytes: u64,
}

/// Publishes a normal saved-world update by rebuilding only changed 2x2-chunk groups and their
/// ancestors. Every unaffected section keeps its exact compressed representation.
#[allow(clippy::too_many_arguments)]
pub fn rebuild_region_incremental(
    source: &AnvilWorld,
    registry: &Arc<RwLock<Registry>>,
    header: &RegionHeader,
    previous: &RegionFile,
    source_table: &RegionSourceTable,
    changed_groups: &BTreeSet<(i32, i32)>,
    output: impl AsRef<Path>,
    source_output: impl AsRef<Path>,
    world_identity: [u8; 32],
    generation: u64,
    layout: RegionLayout,
) -> Result<(RegionFile, RegionalBuildStats)> {
    if changed_groups.is_empty()
        || previous.region() != (header.region_x, header.region_z)
        || previous.layout() != layout
        || source_table.terrain_generation != generation
    {
        bail!("incremental regional rebuild identity is invalid");
    }
    let initial_catalog = read_lock(registry)?.snapshot();
    let initial_catalog_bytes = Catalog::from_snapshot(&initial_catalog)?.encode()?;
    let mut file = RegionFileBuilder::new(
        world_identity,
        *blake3::hash(&initial_catalog_bytes).as_bytes(),
        initial_catalog.catalog_id,
        header.region_x,
        header.region_z,
        generation,
        layout,
    )?;
    let mut stats = RegionalBuildStats::default();
    let mut affected_horizontal = vec![BTreeSet::new(); layout.levels as usize];
    affected_horizontal[0] = changed_groups.clone();
    for lod in 1..layout.levels as usize {
        affected_horizontal[lod] = affected_horizontal[lod - 1]
            .iter()
            .map(|&(x, z)| (x.div_euclid(2), z.div_euclid(2)))
            .collect();
    }

    let top = layout.levels - 1;
    for &(x, z) in &affected_horizontal[top as usize] {
        let column = rebuild_changed_column(
            source,
            registry,
            previous,
            &mut file,
            layout,
            &affected_horizontal,
            top,
            x,
            z,
            &mut stats,
        )?;
        insert_column(&mut file, column)?;
    }

    let final_catalog = {
        let mut registry = write_lock(registry)?;
        registry.save()?;
        registry.snapshot()
    };
    if final_catalog.catalog_id != initial_catalog.catalog_id {
        bail!("catalog identity changed during an incremental regional rebuild");
    }
    let final_catalog_bytes = Catalog::from_snapshot(&final_catalog)?.encode()?;
    file.set_catalog_fingerprint(*blake3::hash(&final_catalog_bytes).as_bytes())?;

    for ordinal in 0..layout.entry_count()? {
        let coordinate = layout.coordinate(header.region_x, header.region_z, ordinal)?;
        if !affected_horizontal[coordinate.level as usize].contains(&(coordinate.x, coordinate.z)) {
            file.copy_ordinal_from(previous, ordinal)?;
            stats.reused_sections +=
                usize::from(previous.entry_ordinal(ordinal as u32)?.is_present());
        }
    }

    verify_header(source, header, "incremental regional rebuild")?;
    let region = file.write_atomic(output)?;
    source_table.write_atomic(source_output)?;
    stats.output_bytes = region.path().metadata()?.len();
    Ok((region, stats))
}

type SectionColumn = BTreeMap<i32, Section>;

#[allow(clippy::too_many_arguments)]
fn rebuild_changed_column(
    source: &AnvilWorld,
    registry: &Arc<RwLock<Registry>>,
    previous: &RegionFile,
    output: &mut RegionFileBuilder,
    layout: RegionLayout,
    affected: &[BTreeSet<(i32, i32)>],
    level: u8,
    x: i32,
    z: i32,
    stats: &mut RegionalBuildStats,
) -> Result<SectionColumn> {
    if !affected[level as usize].contains(&(x, z)) {
        bail!("incremental rebuild descended into an unaffected column");
    }
    if level == 0 {
        let group = source.load_level_zero_group(x, z, registry)?;
        stats.chunks_read += 4;
        stats.generated_chunks += group.chunks.iter().filter(|chunk| chunk.is_some()).count();
        let mut sections = SectionColumn::new();
        if group.chunks.iter().any(Option::is_some) {
            for y in layout.level_y_range(0)? {
                let key = SectionKey::new(0, x, y, z)?;
                sections.insert(y, group.build(key, source)?.section);
                stats.sections_by_level[0] += 1;
            }
        }
        return Ok(sections);
    }

    let child_level = level - 1;
    let mut changed_children = BTreeMap::<(i32, i32), SectionColumn>::new();
    for dz in 0..2i32 {
        for dx in 0..2i32 {
            let coordinate = (x * 2 + dx, z * 2 + dz);
            if affected[child_level as usize].contains(&coordinate) {
                let child = rebuild_changed_column(
                    source,
                    registry,
                    previous,
                    output,
                    layout,
                    affected,
                    child_level,
                    coordinate.0,
                    coordinate.1,
                    stats,
                )?;
                changed_children.insert(coordinate, child);
            }
        }
    }

    let opacity = read_lock(registry)?.opacity_table();
    let mut parents = SectionColumn::new();
    for y in layout.level_y_range(level)? {
        let key = SectionKey::new(level, x, y, z)?;
        let keys = child_keys(key)?;
        let mut loaded: [Option<Section>; 8] = std::array::from_fn(|_| None);
        for (slot, child) in keys.iter().copied().enumerate() {
            let horizontal = (child.x, child.z);
            // Coarser vertical ranges are rounded outward. Their first or last parent can
            // therefore name a child just beyond the shard's stored Y range. Full rebuilds
            // naturally see that child as absent; incremental rebuilds must do the same instead
            // of asking RegionFile to resolve an intentionally out-of-layout coordinate.
            if !changed_children.contains_key(&horizontal)
                && stored_y(layout, child.level, child.y)?
            {
                loaded[slot] =
                    previous
                        .read_section(SectionCoordinate::from(child))?
                        .map(|frame| Section {
                            key: child,
                            non_empty_children: frame.non_empty_children,
                            cells: frame.cells,
                        });
            }
        }
        let inputs = std::array::from_fn(|slot| {
            let child = keys[slot];
            changed_children
                .get(&(child.x, child.z))
                .and_then(|column| column.get(&child.y))
                .or(loaded[slot].as_ref())
        });
        if inputs.iter().any(Option::is_some) {
            parents.insert(y, build_parent_from_refs(key, &inputs, &opacity)?);
            stats.sections_by_level[level as usize] += 1;
        }
    }
    for column in changed_children.into_values() {
        insert_column(output, column)?;
    }
    Ok(parents)
}

fn insert_column(output: &mut RegionFileBuilder, column: SectionColumn) -> Result<()> {
    for section in column.into_values() {
        output.insert(
            SectionCoordinate::from(section.key),
            SectionFrame::new(section.non_empty_children, section.cells)?,
        )?;
    }
    Ok(())
}

/// Rebuilds one complete regional shard from its authoritative Anvil snapshot. Exact source cells
/// live only long enough to build one 2x2 level-zero group tile and the next parent level; no
/// normalized source objects are persisted.
#[allow(clippy::too_many_arguments)]
pub fn rebuild_region(
    source: &AnvilWorld,
    registry: &Arc<RwLock<Registry>>,
    header: &RegionHeader,
    output: impl AsRef<Path>,
    source_output: impl AsRef<Path>,
    world_identity: [u8; 32],
    generation: u64,
    layout: RegionLayout,
) -> Result<(RegionFile, RegionalBuildStats)> {
    if header.entries.len() != 1024 {
        bail!("regional rebuild requires exactly 1024 Anvil header entries");
    }
    let initial_catalog = read_lock(registry)?.snapshot();
    let initial_catalog_bytes = Catalog::from_snapshot(&initial_catalog)?.encode()?;
    let mut file = RegionFileBuilder::new(
        world_identity,
        *blake3::hash(&initial_catalog_bytes).as_bytes(),
        initial_catalog.catalog_id,
        header.region_x,
        header.region_z,
        generation,
        layout,
    )?;
    let mut source_table = RegionSourceTable::new(
        header.region_x,
        header.region_z,
        generation,
        header.file_marker,
    )?;
    let base_group_x = header
        .region_x
        .checked_mul(16)
        .context("regional group x overflow")?;
    let base_group_z = header
        .region_z
        .checked_mul(16)
        .context("regional group z overflow")?;
    let mut stats = RegionalBuildStats::default();
    let mut level = BTreeMap::<SectionKey, Section>::new();

    // Four adjacent 32-cubed level-zero columns are precisely the children needed by one LOD-1
    // column. This keeps decoded NBT and level-zero cells bounded to sixteen source chunks.
    for tile_z in 0..8i32 {
        for tile_x in 0..8i32 {
            let first_group_x = base_group_x + tile_x * 2;
            let first_group_z = base_group_z + tile_z * 2;
            let mut groups = Vec::with_capacity(4);
            for dz in 0..2i32 {
                for dx in 0..2i32 {
                    let group = source.load_level_zero_group(
                        first_group_x + dx,
                        first_group_z + dz,
                        registry,
                    )?;
                    stats.chunks_read += 4;
                    for (chunk_index, chunk) in group.chunks.iter().enumerate() {
                        let chunk_x = (first_group_x + dx) * 2 + (chunk_index as i32 & 1);
                        let chunk_z = (first_group_z + dz) * 2 + (chunk_index as i32 >> 1);
                        let local_x = chunk_x.rem_euclid(32) as u8;
                        let local_z = chunk_z.rem_euclid(32) as u8;
                        let slot = local_x as usize + local_z as usize * 32;
                        let entry = header.entries[slot];
                        let generated = chunk.is_some();
                        stats.generated_chunks += usize::from(generated);
                        source_table.set_record(
                            local_x,
                            local_z,
                            ChunkSourceRecord {
                                generated,
                                anvil_location: entry.location,
                                anvil_timestamp: entry.timestamp,
                                semantic_fingerprint: chunk
                                    .as_ref()
                                    .map_or([0; 2], |chunk| chunk.terrain_fingerprint),
                            },
                        )?;
                    }
                    groups.push(group);
                }
            }

            let opacity = read_lock(registry)?.opacity_table();
            let mut children = BTreeMap::<SectionKey, Section>::new();
            for group in &groups {
                if group.chunks.iter().all(Option::is_none) {
                    continue;
                }
                for y in layout.level_y_range(0)? {
                    let key = SectionKey::new(0, group.x, y, group.z)?;
                    let section = group.build(key, source)?.section;
                    insert_frame(&mut file, &section)?;
                    stats.sections_by_level[0] += 1;
                    children.insert(key, section);
                }
            }

            let parent_x = first_group_x.div_euclid(2);
            let parent_z = first_group_z.div_euclid(2);
            for y in layout.level_y_range(1)? {
                let key = SectionKey::new(1, parent_x, y, parent_z)?;
                let inputs = child_refs(key, &children)?;
                if inputs.iter().all(Option::is_none) {
                    continue;
                }
                let section = build_parent_from_refs(key, &inputs, &opacity)?;
                insert_frame(&mut file, &section)?;
                stats.sections_by_level[1] += 1;
                level.insert(key, section);
            }
        }
    }

    let final_catalog = {
        let mut registry = write_lock(registry)?;
        registry.save()?;
        registry.snapshot()
    };
    if final_catalog.catalog_id != initial_catalog.catalog_id {
        bail!("catalog identity changed during a regional rebuild");
    }
    let final_catalog_bytes = Catalog::from_snapshot(&final_catalog)?.encode()?;
    file.set_catalog_fingerprint(*blake3::hash(&final_catalog_bytes).as_bytes())?;
    let opacity = read_lock(registry)?.opacity_table();

    for lod in 2..layout.levels {
        let side = layout.horizontal_side(lod)? as i32;
        let base_x = header.region_x * side;
        let base_z = header.region_z * side;
        let mut parents = BTreeMap::<SectionKey, Section>::new();
        for y in layout.level_y_range(lod)? {
            for z in base_z..base_z + side {
                for x in base_x..base_x + side {
                    let key = SectionKey::new(lod, x, y, z)?;
                    let inputs = child_refs(key, &level)?;
                    if inputs.iter().all(Option::is_none) {
                        continue;
                    }
                    let section = build_parent_from_refs(key, &inputs, &opacity)?;
                    insert_frame(&mut file, &section)?;
                    stats.sections_by_level[lod as usize] += 1;
                    parents.insert(key, section);
                }
            }
        }
        level = parents;
    }

    // Publication must describe one source snapshot. A changed marker/header aborts this bounded
    // transaction; the caller retries the region rather than publishing mixed old/new cells.
    verify_header(source, header, "regional rebuild")?;
    let region = file.write_atomic(output)?;
    // Terrain is durable first. If this smaller publication fails or the process stops here, the
    // old source marker causes one safe regional rebuild on restart.
    source_table.write_atomic(source_output)?;
    stats.output_bytes = region.path().metadata()?.len();
    Ok((region, stats))
}

fn child_refs(
    parent: SectionKey,
    sections: &BTreeMap<SectionKey, Section>,
) -> Result<[Option<&Section>; 8]> {
    if parent.level == 0 {
        bail!("level-zero section has no child references");
    }
    let mut output = [None; 8];
    for dy in 0..2i32 {
        for dz in 0..2i32 {
            for dx in 0..2i32 {
                let key = SectionKey::new(
                    parent.level - 1,
                    parent.x * 2 + dx,
                    parent.y * 2 + dy,
                    parent.z * 2 + dz,
                )?;
                output[(dx | (dz << 1) | (dy << 2)) as usize] = sections.get(&key);
            }
        }
    }
    Ok(output)
}

fn child_keys(parent: SectionKey) -> Result<[SectionKey; 8]> {
    if parent.level == 0 {
        bail!("level-zero section has no child keys");
    }
    let mut output = [parent; 8];
    for dy in 0..2i32 {
        for dz in 0..2i32 {
            for dx in 0..2i32 {
                let slot = (dx | (dz << 1) | (dy << 2)) as usize;
                output[slot] = SectionKey::new(
                    parent.level - 1,
                    parent.x * 2 + dx,
                    parent.y * 2 + dy,
                    parent.z * 2 + dz,
                )?;
            }
        }
    }
    Ok(output)
}

fn stored_y(layout: RegionLayout, level: u8, y: i32) -> Result<bool> {
    Ok(layout.level_y_range(level)?.contains(&y))
}

fn verify_header(source: &AnvilWorld, header: &RegionHeader, operation: &str) -> Result<()> {
    let current = source
        .region_header(header.region_x, header.region_z)?
        .with_context(|| format!("Anvil region disappeared during {operation}"))?;
    if current.file_marker != header.file_marker || current.entries != header.entries {
        bail!("Anvil region changed during {operation}");
    }
    Ok(())
}

fn insert_frame(output: &mut RegionFileBuilder, section: &Section) -> Result<()> {
    output.insert(
        SectionCoordinate::from(section.key),
        SectionFrame::new(section.non_empty_children, section.cells.clone())?,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn incremental_parent_edges_treat_vertical_padding_as_absent() {
        let layout = RegionLayout::new(-2, 12, 5).unwrap();
        let mut padding_children = 0;
        for level in 1..layout.levels {
            for y in layout.level_y_range(level).unwrap() {
                let parent = SectionKey::new(level, -1, y, 2).unwrap();
                for child in child_keys(parent).unwrap() {
                    let stored = stored_y(layout, child.level, child.y).unwrap();
                    if !stored {
                        padding_children += 1;
                        assert!(layout.index(-1, 2, SectionCoordinate::from(child)).is_err());
                    }
                }
            }
        }
        assert!(
            padding_children > 0,
            "test layout must exercise rounded Y padding"
        );
    }
}
