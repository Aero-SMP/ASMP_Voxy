//! End-to-end storage/mipping/source-change regressions using saved Anvil input.
use super::*;
use crate::{
    anvil::AnvilWorld,
    lod::{Cell, SECTION_VOLUME},
    registry::Registry,
};
use fastnbt::Value;
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::PathBuf,
    sync::{
        Arc, RwLock,
        atomic::{AtomicU64, Ordering},
    },
};

static NEXT: AtomicU64 = AtomicU64::new(0);
struct Fixture {
    root: PathBuf,
    source: Arc<AnvilWorld>,
    registry: Arc<RwLock<Registry>>,
}
impl Fixture {
    fn new() -> Self {
        let root = std::env::temp_dir().join(format!(
            "voxy-air-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(root.join("world/region")).unwrap();
        let registry = Arc::new(RwLock::new(Registry::open(root.join("registry")).unwrap()));
        let source = Arc::new(AnvilWorld {
            dimension: "minecraft:overworld".into(),
            root: root.join("world"),
        });
        Self {
            root,
            source,
            registry,
        }
    }
    fn save(&self, revision: u32) {
        fn compound(entries: impl IntoIterator<Item = (&'static str, Value)>) -> Value {
            Value::Compound(
                entries
                    .into_iter()
                    .map(|(key, value)| (key.into(), value))
                    .collect(),
            )
        }
        let mut bytes = vec![0u8; 8192];
        // Unchanged siblings at LODs 0, 1, 2 and 3, in a negative-coordinate region.
        for local_x in [0usize, 2, 4, 8, 16] {
            let changed = local_x == 0;
            let mut sections = Vec::new();
            for y in -4i8..20 {
                let solid = y < 2;
                let block = if solid {
                    "minecraft:stone"
                } else {
                    "minecraft:air"
                };
                let mut sky = vec![0i8; 2048];
                let mut light = vec![0i8; 2048];
                for i in 0..4096 {
                    // Two independent nibbles, nonuniform air, with a lighting-only update.
                    let delta = if changed { revision } else { 0 } as usize;
                    let s = if solid {
                        0
                    } else {
                        (15 + i / 256 + delta) & 15
                    };
                    let b = if solid { 0 } else { (i / 32 + delta * 3) & 15 };
                    sky[i / 2] = (sky[i / 2] as u8 | (s as u8) << ((i & 1) * 4)) as i8;
                    light[i / 2] = (light[i / 2] as u8 | (b as u8) << ((i & 1) * 4)) as i8;
                }
                sections.push(compound([
                    ("Y", Value::Byte(y)),
                    (
                        "block_states",
                        compound([(
                            "palette",
                            Value::List(vec![compound([("Name", Value::String(block.into()))])]),
                        )]),
                    ),
                    (
                        "biomes",
                        compound([(
                            "palette",
                            Value::List(vec![Value::String("minecraft:plains".into())]),
                        )]),
                    ),
                    ("SkyLight", Value::ByteArray(fastnbt::ByteArray::new(sky))),
                    (
                        "BlockLight",
                        Value::ByteArray(fastnbt::ByteArray::new(light)),
                    ),
                ]));
            }
            let nbt = fastnbt::to_bytes(&compound([
                ("xPos", Value::Int(-32 + local_x as i32)),
                ("zPos", Value::Int(-32)),
                ("Status", Value::String("minecraft:full".into())),
                ("sections", Value::List(sections)),
            ]))
            .unwrap();
            use std::io::Write;
            let mut encoder =
                flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::fast());
            encoder.write_all(&nbt).unwrap();
            let compressed = encoder.finish().unwrap();
            let sector = bytes.len() / 4096;
            let sectors = (compressed.len() + 5).div_ceil(4096);
            assert!(sectors < 256);
            bytes[local_x * 4..local_x * 4 + 4]
                .copy_from_slice(&((sector as u32) << 8 | sectors as u32).to_be_bytes());
            bytes[4096 + local_x * 4..4100 + local_x * 4]
                .copy_from_slice(&(if changed { revision + 1 } else { 1u32 }).to_be_bytes());
            bytes.extend_from_slice(&(compressed.len() as u32 + 1).to_be_bytes());
            bytes.push(2);
            bytes.extend_from_slice(&compressed);
            bytes.resize((sector + sectors) * 4096, 0);
        }
        fs::write(self.source.root.join("region/r.-1.-1.mca"), bytes).unwrap();
    }
    fn runtime(&self) -> RegionalRuntime {
        RegionalRuntime::open(
            self.root.join("data"),
            self.source.dimension.clone(),
            self.source.clone(),
            self.registry.clone(),
            RegionLayout::new(-2, 12, 5).unwrap(),
        )
        .unwrap()
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.root);
    }
}

fn compare(actual: &RegionFile, expected: &RegionFile) {
    assert_eq!(actual.layout(), expected.layout());
    for ordinal in 0..actual.layout().entry_count().unwrap() {
        let coordinate = actual.layout().coordinate(-1, -1, ordinal).unwrap();
        let a = actual.entry(coordinate).unwrap();
        let b = expected.entry(coordinate).unwrap();
        assert_eq!(
            (a.flags, a.non_empty_children),
            (b.flags, b.non_empty_children),
            "{coordinate:?}"
        );
        assert_eq!(
            actual.read_section(coordinate).unwrap(),
            expected.read_section(coordinate).unwrap(),
            "{coordinate:?}"
        );
    }
}

#[test]
fn corrupt_incremental_reuse_falls_back_once_and_matches_clean_full_build() {
    use std::{cell::RefCell, os::unix::fs::FileExt, rc::Rc};
    for x in [-15, -8] {
        let f = Fixture::new();
        f.save(0);
        let runtime = f.runtime();
        runtime.refresh(0).unwrap();
        let old = runtime.region(-1, -1).unwrap().unwrap();
        let coordinate = SectionCoordinate {
            level: 0,
            x,
            y: 0,
            z: -16,
        };
        let entry = old.entry(coordinate).unwrap();
        assert!(entry.has_payload());
        let file = fs::OpenOptions::new().write(true).open(old.path()).unwrap();
        file.write_all_at(&[0xff], entry.payload_offset).unwrap();
        file.sync_all().unwrap();
        f.save(1);
        let counts = Rc::new(RefCell::new((0, 0)));
        let seen = counts.clone();
        let guard = super::faults::set(move |stage, _| {
            if stage == "incremental" {
                seen.borrow_mut().0 += 1;
            }
            if stage == "full" {
                seen.borrow_mut().1 += 1;
            }
            Ok(())
        });
        let start = std::time::Instant::now();
        let report = runtime.refresh(1).unwrap();
        eprintln!(
            "corrupt reuse x={x} recovery_us={}",
            start.elapsed().as_micros()
        );
        assert_eq!(report.changed, vec![(-1, -1, 2)]);
        assert!(!report.incomplete);
        assert_eq!(*counts.borrow(), (1, 1));
        drop(guard);
        let fresh = f.source.region_header(-1, -1).unwrap().unwrap();
        let full = rebuild_region(
            &f.source,
            &f.registry,
            &fresh,
            f.root.join("clean.vxregion"),
            old.world_identity(),
            100,
            old.layout(),
        )
        .unwrap()
        .terrain
        .unwrap();
        compare(&runtime.region(-1, -1).unwrap().unwrap(), &full);
    }
}

#[test]
fn final_full_and_incremental_snapshot_changes_defer_without_stale_fallback() {
    use std::{cell::RefCell, rc::Rc};
    for incremental in [false, true] {
        let f = Fixture::new();
        f.save(0);
        let runtime = f.runtime();
        if incremental {
            runtime.refresh(0).unwrap();
            f.save(1);
        }
        let source_path = f.source.root.join("region/r.-1.-1.mca");
        let counts = Rc::new(RefCell::new((0, 0, 0)));
        let seen = counts.clone();
        let guard = super::faults::set(move |stage, _| {
            if stage == "incremental" {
                seen.borrow_mut().0 += 1;
            }
            if stage == "full" {
                seen.borrow_mut().1 += 1;
            }
            if stage == "verify_header" {
                seen.borrow_mut().2 += 1;
                if !incremental || seen.borrow().2 == 2 {
                    let mut bytes = fs::read(&source_path)?;
                    bytes[4096] ^= 1;
                    fs::write(&source_path, bytes)?;
                }
            }
            Ok(())
        });
        let report = runtime.refresh(1).unwrap();
        assert!(report.incomplete && report.changed.is_empty());
        assert_eq!(counts.borrow().0, usize::from(incremental));
        assert_eq!(counts.borrow().1, usize::from(!incremental));
        assert_eq!(runtime.region(-1, -1).unwrap().is_some(), incremental);
        drop(guard);
        assert!(!runtime.refresh(2).unwrap().incomplete);
    }
}

#[test]
fn unreadable_source_probe_does_not_trigger_equivalent_full_work() {
    let f = Fixture::new();
    f.save(0);
    let runtime = f.runtime();
    runtime.refresh(0).unwrap();
    let path = f.source.root.join("region/r.-1.-1.mca");
    let mut bytes = fs::read(&path).unwrap();
    bytes[4096] ^= 1;
    bytes[8192..8196].fill(0);
    fs::write(&path, bytes).unwrap();
    let guard = super::faults::set(|stage, _| {
        assert_ne!(stage, "incremental");
        assert_ne!(stage, "full");
        Ok(())
    });
    assert!(runtime.refresh(1).unwrap().incomplete);
    assert_eq!(runtime.region(-1, -1).unwrap().unwrap().generation(), 1);
    drop(guard);
    f.save(1);
    assert_eq!(runtime.refresh(2).unwrap().changed, vec![(-1, -1, 2)]);
}

#[test]
fn saved_lighting_updates_match_full_build_after_reopening_at_every_lod() {
    let f = Fixture::new();
    f.save(0);
    let mut runtime = f.runtime();
    assert_eq!(runtime.refresh(0).unwrap().changed.len(), 1);
    let first = runtime.region(-1, -1).unwrap().unwrap();
    let world = first.world_identity();
    let layout = first.layout();
    assert_eq!(&fs::read(first.path()).unwrap()[..8], b"VXYRGN\0\x01");
    let mut previous_path = first.path().to_owned();
    for revision in 1..=3 {
        let previous = RegionFile::open(&previous_path).unwrap();
        drop(runtime);
        f.save(revision);
        runtime = f.runtime();
        // Real source-table/header probe must recognize a lighting-only saved change.
        let refreshed = runtime.refresh(0).unwrap();
        assert_eq!(refreshed.metadata_only, 0);
        assert_eq!(refreshed.changed, vec![(-1, -1, revision as u64 + 1)]);
        let live = runtime.region(-1, -1).unwrap().unwrap();
        let header = f.source.region_headers().unwrap().valid.remove(0);
        let start = std::time::Instant::now();
        let full_build = rebuild_region(
            &f.source,
            &f.registry,
            &header,
            f.root.join("full.vxregion"),
            world,
            100 + revision as u64,
            layout,
        )
        .unwrap();
        let full = full_build.terrain.unwrap();
        let full_stats = full_build.stats;
        full_build
            .source
            .write_atomic(f.root.join("full.vxsource"))
            .unwrap();
        let full_time = start.elapsed();
        let table = RegionSourceTable::open(f.root.join("full.vxsource")).unwrap();
        let start = std::time::Instant::now();
        let incremental_build = rebuild_region_incremental(
            &f.source,
            &f.registry,
            &header,
            &previous,
            &table,
            &BTreeSet::from([(-16, -16)]),
            f.root.join(format!("incremental-{revision}.vxregion")),
            world,
            table.terrain_generation,
            layout,
        )
        .unwrap();
        let incremental = incremental_build.terrain.unwrap();
        let stats = incremental_build.stats;
        incremental_build
            .source
            .write_atomic(f.root.join("incremental.vxsource"))
            .unwrap();
        let incremental_time = start.elapsed();
        assert!(stats.reused_sections > 0);
        assert_eq!(stats.chunks_read, 4);
        assert!(stats.sections_by_level.iter().all(|count| *count > 0));
        compare(&RegionFile::open(incremental.path()).unwrap(), &full);
        compare(&live, &full);
        let stored_air = (0..layout.entry_count().unwrap())
            .filter_map(|i| {
                let e = full.entry_ordinal(i as u32).unwrap();
                (e.is_empty() && e.has_payload()).then_some(e.compressed_length as u64)
            })
            .collect::<Vec<_>>();
        assert!(!stored_air.is_empty());
        eprintln!(
            "air fixture revision={revision} full_us={} incremental_us={} file_bytes={} wire_index_bytes={} air_bodies={} air_bytes={} reused={}",
            full_time.as_micros(),
            incremental_time.as_micros(),
            full_stats.output_bytes,
            full.compressed_index().len(),
            stored_air.len(),
            stored_air.iter().sum::<u64>(),
            stats.reused_sections
        );
        // Preserve the runtime file's previous inode while reopening the independent build next time.
        previous_path = incremental.path().to_owned();
        assert!(runtime.refresh(0).unwrap().changed.is_empty());
    }
}

#[test]
fn obsolete_or_missing_terrain_cannot_be_skipped_by_a_current_source_table() {
    for obsolete in [true, false] {
        let f = Fixture::new();
        f.save(0);
        let runtime = f.runtime();
        runtime.refresh(0).unwrap();
        let old = runtime.region(-1, -1).unwrap().unwrap();
        let path = old.path().to_owned();
        let expected_source = fs::read(path.with_extension("vxsource")).unwrap();
        drop(old);
        drop(runtime);
        if obsolete {
            let mut bytes = fs::read(&path).unwrap();
            bytes[..8].copy_from_slice(b"VXYRGN\0\0");
            let crc = crate::crc::crc32c(&bytes[..252]);
            bytes[252..256].copy_from_slice(&crc.to_le_bytes());
            fs::write(&path, bytes).unwrap();
            assert!(RegionFile::open(&path).is_err());
        } else {
            fs::remove_file(&path).unwrap();
        }
        let runtime = f.runtime();
        assert!(runtime.region(-1, -1).unwrap().is_none());
        assert_eq!(
            fs::read(path.with_extension("vxsource")).unwrap(),
            expected_source
        );
        let result = runtime.refresh(0).unwrap();
        assert_eq!(result.changed, vec![(-1, -1, 1)]);
        assert_eq!(result.metadata_only, 0);
        assert_eq!(&fs::read(&path).unwrap()[..8], b"VXYRGN\0\x01");
        drop(runtime);
        let runtime = f.runtime();
        assert!(runtime.refresh(0).unwrap().changed.is_empty());
        f.save(1);
        assert_eq!(runtime.refresh(0).unwrap().changed, vec![(-1, -1, 2)]);
    }
}

#[test]
fn stored_air_is_normalized_and_served_empty_in_an_ordinary_mixed_batch() {
    let f = Fixture::new();
    f.save(0);
    let service = RegionalService::open(
        f.root.join("data"),
        &BTreeMap::from([(f.source.dimension.clone(), f.source.clone())]),
        f.registry.clone(),
    )
    .unwrap();
    service.refresh_all(0).unwrap();
    let region = service
        .runtime(&f.source.dimension)
        .unwrap()
        .region(-1, -1)
        .unwrap()
        .unwrap();
    let entries = RegionIndex::from_file(&region);
    let air = entries
        .entries
        .iter()
        .enumerate()
        .find(|(i, e)| e.is_empty() && region.entry_ordinal(*i as u32).unwrap().has_payload())
        .unwrap()
        .0 as u32;
    let solid = entries
        .entries
        .iter()
        .position(|e| e.is_present() && !e.is_empty())
        .unwrap() as u32;
    let absent = entries
        .entries
        .iter()
        .position(|e| !e.is_present())
        .unwrap() as u32;
    let canonical = entries.encode().unwrap();
    assert_eq!(RegionIndex::decode(&canonical).unwrap(), entries);
    assert_eq!(
        &blake3::hash(&canonical).as_bytes()[..16],
        &region.index_fingerprint()
    );
    assert_eq!(
        zstd::bulk::decompress(region.compressed_index(), canonical.len()).unwrap(),
        canonical
    );
    let responder = service.responder(&f.source.dimension, 1).unwrap();
    // A stored-air payload must not even be read on the serving path. Damage only its
    // bytes: the ordinary payload in this same batch must still be served successfully.
    use std::os::unix::fs::FileExt;
    fs::OpenOptions::new()
        .write(true)
        .open(region.path())
        .unwrap()
        .write_all_at(&[0xff], region.entry_ordinal(air).unwrap().payload_offset)
        .unwrap();
    assert!(region.read_compressed_ordinal(air).is_err());
    let other_air = entries
        .entries
        .iter()
        .enumerate()
        .find(|(i, e)| *i != air as usize && e.is_empty())
        .unwrap()
        .0 as u32;
    let request = wire::SectionRequestBatch {
        epoch: 1,
        region_x: -1,
        region_z: -1,
        generation: region.generation(),
        ordinals: vec![air, solid, absent, other_air],
    };
    let request = wire::SectionRequestBatch::decode(&request.encode().unwrap()).unwrap();
    let mut batches = Vec::new();
    responder
        .sections(&request, |batch| {
            batches.push(batch);
            Ok(())
        })
        .unwrap();
    assert_eq!(batches.len(), 1);
    let batch = &batches[0];
    assert_eq!(batch.start, 0);
    assert_eq!(
        batch.replies.iter().map(|r| r.status).collect::<Vec<_>>(),
        vec![
            wire::SectionReplyStatus::Empty,
            wire::SectionReplyStatus::Data,
            wire::SectionReplyStatus::Absent,
            wire::SectionReplyStatus::Empty
        ]
    );
    assert!(batch.replies[0].compressed.is_empty() && batch.replies[3].compressed.is_empty());
    assert_eq!(
        batch.replies[1].compressed,
        region.read_compressed_ordinal(solid).unwrap().unwrap()
    );
    assert_eq!(
        wire::SectionReplyBatch::decode(
            &batch.encode().unwrap(),
            &[0, entries.entries[solid as usize].compressed_length, 0, 0]
        )
        .unwrap(),
        *batch
    );
}

#[test]
fn surface_parent_lighting_survives_eight_child_storage_round_trip() {
    use crate::{
        key::SectionKey,
        lod::{Section, build_parent_from_refs, cell_index},
    };
    let f = Fixture::new();
    let layout = RegionLayout::new(0, 2, 2).unwrap();
    let parent_key = SectionKey::new(1, 0, 0, 0).unwrap();
    let children: [Section; 8] = std::array::from_fn(|slot| {
        let y = (slot >> 2) & 1;
        Section::from_cells(
            SectionKey::new(0, (slot & 1) as i32, y as i32, ((slot >> 1) & 1) as i32).unwrap(),
            vec![
                Cell {
                    block: if y == 0 { 1 } else { 0 },
                    biome: 0,
                    light: if y == 0 { 0 } else { 15 }
                };
                SECTION_VOLUME
            ],
        )
        .unwrap()
    });
    let expected =
        build_parent_from_refs(parent_key, &children.each_ref().map(Some), &[0, 15]).unwrap();
    assert_eq!(expected.cells[cell_index(0, 16, 0)].light, 15);
    let mut file = RegionFileBuilder::new([1; 32], [2; 32], 1, 0, 0, 1, layout).unwrap();
    for child in &children {
        file.insert(
            child.key.into(),
            SectionFrame::new(0, child.cells.clone()).unwrap(),
        )
        .unwrap();
    }
    let path = f.root.join("eight.vxregion");
    drop(file.write_atomic(&path).unwrap());
    let file = RegionFile::open(path).unwrap();
    let restored = children.each_ref().map(|child| {
        Section::from_cells(
            child.key,
            file.read_section(child.key.into()).unwrap().unwrap().cells,
        )
        .unwrap()
    });
    let actual =
        build_parent_from_refs(parent_key, &restored.each_ref().map(Some), &[0, 15]).unwrap();
    assert_eq!(actual, expected);
}

#[test]
fn shared_air_index_matches_the_real_storage_projection() {
    let f = Fixture::new();
    let layout = RegionLayout::new(0, 1, 5).unwrap();
    let mut build = RegionFileBuilder::new([1; 32], [2; 32], 1, 0, 0, 1, layout).unwrap();
    for (ordinal, cell, children) in [
        (0, Cell::AIR, 0),
        (
            1,
            Cell {
                block: 0,
                biome: 7,
                light: 15,
            },
            0,
        ),
        (
            2,
            Cell {
                block: 1,
                biome: 0,
                light: 15,
            },
            0,
        ),
        (
            340,
            Cell {
                block: 0,
                biome: 7,
                light: 15,
            },
            0xa5,
        ),
    ] {
        build
            .insert(
                layout.coordinate(0, 0, ordinal).unwrap(),
                SectionFrame::new(children, vec![cell; SECTION_VOLUME]).unwrap(),
            )
            .unwrap();
    }
    let file = build.write_atomic(f.root.join("wire.vxregion")).unwrap();
    let canonical = RegionIndex::from_file(&file).encode().unwrap();
    let hex = |bytes: &[u8]| bytes.iter().map(|b| format!("{b:02x}")).collect::<String>();
    let mut fixture = format!("header|{}\n", hex(&canonical[..36]));
    for ordinal in [0, 1, 2, 340] {
        fixture.push_str(&format!(
            "{ordinal}|{}\n",
            hex(&canonical[36 + ordinal * 48..36 + (ordinal + 1) * 48])
        ));
    }
    fixture.push_str(&format!("hash|{}\n", hex(&file.index_fingerprint())));
    assert_eq!(
        fixture,
        include_str!("../../../test-fixtures/regional-air-index.txt")
    );
    assert!(file.entry_ordinal(1).unwrap().has_payload());
    assert!(file.entry_ordinal(340).unwrap().has_payload());
    assert!(!file.entry_ordinal(0).unwrap().has_payload());
    assert!(!file.entry_ordinal(3).unwrap().is_present());
}
