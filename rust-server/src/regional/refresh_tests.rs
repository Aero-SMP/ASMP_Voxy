use super::*;
use crate::regional::{RegionalAnnouncement, RegionalService, faults};
use std::{
    cell::RefCell,
    rc::Rc,
    sync::atomic::{AtomicU64, Ordering},
};

static NEXT: AtomicU64 = AtomicU64::new(0);
struct Fixture {
    root: PathBuf,
    registry: Arc<RwLock<Registry>>,
    source: Arc<AnvilWorld>,
}
impl Fixture {
    fn new() -> Self {
        let root = std::env::temp_dir().join(format!(
            "voxy-refresh-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(root.join("world/region")).unwrap();
        let registry = Arc::new(RwLock::new(Registry::open(root.join("registry")).unwrap()));
        let source = Arc::new(AnvilWorld::new("a:world".into(), root.join("world")));
        Self {
            root,
            registry,
            source,
        }
    }
    fn save(&self, x: i32, timestamp: u32) {
        let mut bytes = vec![0u8; 8192];
        bytes[4096..4100].copy_from_slice(&timestamp.to_be_bytes());
        fs::write(self.source.region_dir().join(format!("r.{x}.0.mca")), bytes).unwrap();
    }
    fn runtime(&self) -> RegionalRuntime {
        RegionalRuntime::open(
            self.root.join("data"),
            self.source.dimension.clone(),
            self.source.clone(),
            self.registry.clone(),
            RegionLayout::new(0, 1, 5).unwrap(),
        )
        .unwrap()
    }
    fn malformed(&self, x: i32) {
        // Valid inventory/header, unusable chunk body: this must cross the real build path.
        let mut bytes = vec![0u8; 12288];
        bytes[..4].copy_from_slice(&0x201u32.to_be_bytes());
        fs::write(self.source.region_dir().join(format!("r.{x}.0.mca")), bytes).unwrap();
    }
    fn service(&self) -> RegionalService {
        fs::create_dir_all(self.root.join("other/region")).unwrap();
        fs::write(self.root.join("other/region/r.0.0.mca"), vec![0u8; 8192]).unwrap();
        RegionalService::open(
            self.root.join("data"),
            &BTreeMap::from([
                (self.source.dimension.clone(), self.source.clone()),
                (
                    "z:other".into(),
                    Arc::new(AnvilWorld::new("z:other".into(), self.root.join("other"))),
                ),
            ]),
            self.registry.clone(),
        )
        .unwrap()
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.root);
    }
}

#[test]
fn failures_preserve_removal_and_other_dimensions_and_once_is_truthful() {
    let f = Fixture::new();
    f.save(0, 0);
    let service = f.service();
    service.refresh_once().unwrap();
    let runtime = service.runtime("a:world").unwrap();
    let responder = service.responder("a:world", 1).unwrap();
    responder.region(0, 0).unwrap();
    let old = runtime.region(0, 0).unwrap().unwrap();
    let mut announcements = service.subscribe();
    fs::remove_file(f.source.region_dir().join("r.0.0.mca")).unwrap();
    f.malformed(-1);
    runtime.prioritize_region(-1, 0).unwrap();
    f.save(1, 0);
    fs::write(f.root.join("other/region/r.1.0.mca"), vec![0u8; 8192]).unwrap();
    let error = service.refresh_once().unwrap_err();
    assert!(error.to_string().contains("incomplete"));
    let mut changes = Vec::new();
    while let Ok(RegionalAnnouncement::Changed {
        dimension,
        region_x,
        region_z,
        generation,
    }) = announcements.try_recv()
    {
        if generation != 0 {
            let serving = service
                .runtime(&dimension)
                .unwrap()
                .region(region_x, region_z)
                .unwrap()
                .unwrap();
            assert_eq!(serving.generation(), generation);
        }
        changes.push((dimension, region_x, generation));
    }
    assert!(changes.contains(&("a:world".into(), 0, 0)));
    assert!(changes.contains(&("a:world".into(), 1, 1)));
    assert!(changes.contains(&("z:other".into(), 1, 1)));
    assert_eq!(old.generation(), 1);
    assert!(old.read_compressed_ordinal(0).unwrap().is_none());
    assert!(runtime.region(0, 0).unwrap().is_none());
    assert!(!runtime.confirmed_absent(-1, 0).unwrap());
    assert_eq!(runtime.maintenance.lock().unwrap().retry.len(), 1);
    f.save(-1, 0);
    assert!(service.refresh_all(0).unwrap().incomplete);
    let recovered = service.refresh_all(1).unwrap();
    assert!(!recovered.incomplete);
    assert!(runtime.maintenance.lock().unwrap().retry.is_empty());
}

#[test]
fn inventory_failure_does_not_delete_and_other_dimension_progresses() {
    let f = Fixture::new();
    f.save(0, 0);
    let service = f.service();
    service.refresh_once().unwrap();
    fs::rename(f.source.region_dir(), f.root.join("saved-region")).unwrap();
    fs::write(f.source.region_dir(), [1]).unwrap();
    fs::write(f.root.join("other/region/r.1.0.mca"), vec![0u8; 8192]).unwrap();
    let mut announcements = service.subscribe();
    assert!(service.refresh_all(1).unwrap().incomplete);
    let runtime = service.runtime("a:world").unwrap();
    assert!(!runtime.confirmed_absent(0, 0).unwrap());
    assert!(runtime.region(0, 0).unwrap().is_some());
    assert!(matches!(
        announcements.try_recv().unwrap(),
        RegionalAnnouncement::Changed { generation: 1, .. }
    ));
    assert!(announcements.try_recv().is_err());
    // Startup with inaccessible source storage retains valid derived files, not absence.
    drop(service);
    let runtime = RegionalRuntime::open(
        f.root.join("data"),
        f.source.dimension.clone(),
        f.source.clone(),
        f.registry.clone(),
        RegionLayout::new(-2, 12, 5).unwrap(),
    )
    .unwrap();
    assert!(runtime.region(0, 0).unwrap().is_some());
    assert!(!runtime.confirmed_absent(0, 0).unwrap());
}

#[test]
fn candidates_are_fresh_and_final_source_change_never_falls_back() {
    let f = Fixture::new();
    f.save(0, 0);
    let runtime = f.runtime();
    let path = f.source.region_dir().join("r.0.0.mca");
    let guard = faults::set(move |stage, _| {
        if stage == "inventory" {
            let mut bytes = fs::read(&path)?;
            bytes[4096] = 1;
            fs::write(&path, bytes)?;
        }
        Ok(())
    });
    assert_eq!(runtime.refresh(0).unwrap().changed, vec![(0, 0, 1)]);
    drop(guard);
    assert!(runtime.refresh(0).unwrap().changed.is_empty());
    f.save(0, 3);
    let path = f.source.region_dir().join("r.0.0.mca");
    let counts = Rc::new(RefCell::new(Vec::new()));
    let seen = counts.clone();
    let guard = faults::set(move |stage, _| {
        seen.borrow_mut().push(stage.to_string());
        if stage == "verify_header" {
            let mut bytes = fs::read(&path)?;
            bytes[4097] += 1;
            fs::write(&path, bytes)?;
        }
        Ok(())
    });
    let report = runtime.refresh(1).unwrap();
    assert!(report.incomplete && report.changed.is_empty());
    assert!(!counts.borrow().iter().any(|s| s == "full"));
    assert_eq!(runtime.region(0, 0).unwrap().unwrap().generation(), 1);
    drop(guard);
    assert!(!runtime.refresh(2).unwrap().incomplete);
    assert!(runtime.refresh(2).unwrap().changed.is_empty());
}

#[test]
fn failed_priority_rotates_and_natural_rediscovery_cannot_retry_same_round() {
    let f = Fixture::new();
    for x in 0..8 {
        f.save(x, 0);
    }
    let runtime = f.runtime();
    runtime.prioritize_region(0, 0).unwrap();
    runtime.prioritize_region(1, 0).unwrap();
    runtime.prioritize_region(2, 0).unwrap();
    let counts = Rc::new(RefCell::new(BTreeMap::new()));
    let seen = counts.clone();
    let guard = faults::set(move |stage, path| {
        if stage == "candidate" {
            let name = path.file_name().unwrap().to_string_lossy().into_owned();
            *seen.borrow_mut().entry(name.clone()).or_insert(0usize) += 1;
            if name == "r.0.0.vxregion" || name == "r.1.0.vxregion" {
                anyhow::bail!("injected source read failure");
            }
        }
        Ok(())
    });
    let mut published = 0;
    for _ in 0..20 {
        let report = runtime.refresh(9).unwrap();
        published += report.changed.len();
        assert!(report.changed.len() <= 1);
        runtime.prioritize_region(0, 0).unwrap(); // Subscriber wake/hint does not start a round.
    }
    assert_eq!(published, 6);
    assert_eq!(counts.borrow()["r.0.0.vxregion"], 1);
    assert_eq!(counts.borrow()["r.1.0.vxregion"], 1);
    assert_eq!(runtime.maintenance.lock().unwrap().retry.len(), 2);
    runtime.refresh(10).unwrap();
    assert_eq!(counts.borrow()["r.0.0.vxregion"], 2);
    drop(guard);
    assert_eq!(runtime.refresh(11).unwrap().changed.len(), 1);
    assert_eq!(runtime.refresh(11).unwrap().changed.len(), 1);
    assert!(runtime.maintenance.lock().unwrap().retry.is_empty());
}

#[test]
fn publication_boundaries_recover_without_wedging_or_reusing_generation() {
    for stage in [
        "terrain_before_rename",
        "terrain_after_rename",
        "terrain_reopen",
        "source_before_rename",
        "source_after_rename",
    ] {
        for restart in [false, true] {
            let f = Fixture::new();
            f.save(0, 0);
            let mut runtime = f.runtime();
            let fired = Rc::new(RefCell::new(false));
            let hit = fired.clone();
            let guard = faults::set(move |at, _| {
                if at == stage && !*hit.borrow() {
                    *hit.borrow_mut() = true;
                    anyhow::bail!("injected {stage}");
                }
                Ok(())
            });
            let report = runtime.refresh(0).unwrap();
            assert!(*fired.borrow());
            let disk = RegionFile::open(runtime.terrain_path((0, 0))).ok();
            if stage == "terrain_before_rename" {
                assert!(report.changed.is_empty() && report.incomplete && disk.is_none());
            } else {
                assert_eq!(report.changed, vec![(0, 0, 1)]);
                assert_eq!(disk.unwrap().generation(), 1);
                assert_eq!(runtime.region(0, 0).unwrap().unwrap().generation(), 1);
                if stage.starts_with("source_") {
                    assert!(report.incomplete);
                    assert!(!read_lock(&runtime.sources).unwrap().contains_key(&(0, 0)));
                }
            }
            drop(guard);
            if restart {
                drop(runtime);
                runtime = f.runtime();
            }
            for _ in 0..4 {
                if !runtime.refresh(1).unwrap().more_pending {
                    break;
                }
            }
            let live = runtime.region(0, 0).unwrap().unwrap();
            let table = RegionSourceTable::open(runtime.source_path((0, 0))).unwrap();
            assert_eq!(live.generation(), table.terrain_generation);
            assert!(
                runtime.maintenance.lock().unwrap().retry.is_empty(),
                "{stage} restart={restart}"
            );
            assert!(!runtime.refresh(2).unwrap().incomplete);
            assert!(
                fs::read_dir(&runtime.root).unwrap().all(|e| !e
                    .unwrap()
                    .file_name()
                    .to_string_lossy()
                    .contains(".tmp."))
            );
        }
    }
}

#[test]
fn failed_sync_keeps_old_handle_until_durable_reconciliation() {
    let f = Fixture::new();
    f.save(0, 0);
    let runtime = f.runtime();
    runtime.refresh(0).unwrap();
    runtime.subscribe_region(0, 0).unwrap();
    let old = runtime.region(0, 0).unwrap().unwrap();
    // Missing sidecar forces full replacement without changing the source cells.
    write_lock(&runtime.sources).unwrap().clear();
    fs::remove_file(runtime.source_path((0, 0))).unwrap();
    let guard = faults::set(|stage, _| {
        if stage == "terrain_after_rename" || stage == "reconcile_sync" {
            anyhow::bail!("injected sync failure");
        }
        Ok(())
    });
    let report = runtime.refresh(1).unwrap();
    assert!(report.incomplete && report.changed.is_empty());
    assert_eq!(
        RegionFile::open(runtime.terrain_path((0, 0)))
            .unwrap()
            .generation(),
        2
    );
    assert_eq!(runtime.region(0, 0).unwrap().unwrap().generation(), 1);
    assert!(!runtime.quarantine_generation(0, 0, 1).unwrap());
    assert!(runtime.refresh(2).unwrap().changed.is_empty());
    drop(guard);
    assert_eq!(runtime.refresh(3).unwrap().changed, vec![(0, 0, 2)]);
    assert_eq!(old.generation(), 1);
    assert_eq!(runtime.region(0, 0).unwrap().unwrap().generation(), 2);
    runtime.refresh(3).unwrap(); // Reconstruct the missing baseline, never reuse generation 2.
    assert_eq!(runtime.region(0, 0).unwrap().unwrap().generation(), 3);
}

#[test]
fn deletion_cleanup_is_retryable_and_reappearance_cancels_it() {
    for extension in ["vxregion", "vxsource"] {
        for reappear in [false, true] {
            let f = Fixture::new();
            f.save(0, 0);
            let mut runtime = f.runtime();
            runtime.refresh(0).unwrap();
            fs::remove_file(f.source.region_dir().join("r.0.0.mca")).unwrap();
            let guard = faults::set(move |stage, path| {
                if stage == "remove_file"
                    && path.extension().is_some_and(|value| value == extension)
                {
                    anyhow::bail!("injected deletion failure");
                }
                Ok(())
            });
            let report = runtime.refresh(1).unwrap();
            assert_eq!(report.removed, vec![(0, 0)]);
            assert!(report.incomplete);
            assert!(runtime.region(0, 0).unwrap().is_none());
            assert!(runtime.refresh(2).unwrap().removed.is_empty());
            drop(guard);
            if reappear {
                f.save(0, 1);
            } else {
                drop(runtime);
                runtime = f.runtime();
            }
            let report = runtime.refresh(3).unwrap();
            assert!(!report.incomplete);
            assert_eq!(runtime.region(0, 0).unwrap().is_some(), reappear);
            assert!(runtime.maintenance.lock().unwrap().retry.is_empty());
        }
    }
}

#[test]
fn healthy_polling_has_no_candidate_reads_and_metadata_only_keeps_generation() {
    let f = Fixture::new();
    f.save(0, 0);
    let runtime = f.runtime();
    runtime.refresh(0).unwrap();
    let guard = faults::set(|stage, _| {
        assert_ne!(
            stage, "candidate",
            "unchanged shard performed a second header read"
        );
        Ok(())
    });
    let start = std::time::Instant::now();
    for _ in 0..100 {
        assert!(!runtime.refresh(1).unwrap().more_pending);
    }
    eprintln!(
        "refresh unchanged polls=100 elapsed_us={} retry_records={}",
        start.elapsed().as_micros(),
        runtime.maintenance.lock().unwrap().retry.len()
    );
    drop(guard);
    f.save(0, 1);
    let report = runtime.refresh(2).unwrap();
    assert_eq!(report.metadata_only, 1);
    assert!(report.changed.is_empty());
    assert_eq!(runtime.region(0, 0).unwrap().unwrap().generation(), 1);
    assert_eq!(runtime.refresh(2).unwrap().metadata_only, 0);
}

#[test]
fn poisoned_shared_registry_is_not_a_local_retry() {
    let f = Fixture::new();
    f.save(0, 0);
    let runtime = f.runtime();
    let registry = f.registry.clone();
    let _ = std::thread::spawn(move || {
        let _held = registry.write().unwrap();
        panic!("injected registry poisoning");
    })
    .join();
    assert!(runtime.refresh(0).unwrap_err().is::<crate::UnsafeState>());
}

#[test]
fn failures_before_or_after_metadata_do_not_discard_successes() {
    for broken in [-1, 3] {
        let f = Fixture::new();
        for x in 0..3 {
            f.save(x, 0);
        }
        let runtime = f.runtime();
        for _ in 0..3 {
            runtime.refresh(0).unwrap();
        }
        f.save(0, 1);
        f.save(1, 1);
        fs::remove_file(f.source.region_dir().join("r.2.0.mca")).unwrap();
        f.malformed(broken);
        let report = runtime.refresh(1).unwrap();
        assert!(report.incomplete && !report.more_pending);
        assert_eq!(report.metadata_only, 2);
        assert_eq!(report.removed, vec![(2, 0)]);
        assert!(report.changed.is_empty());
        assert_eq!(runtime.region(0, 0).unwrap().unwrap().generation(), 1);
        let report = runtime.refresh(1).unwrap();
        assert!(report.incomplete && report.removed.is_empty());
        assert_eq!(report.metadata_only, 0);
    }
}

#[test]
fn once_source_instability_finishes_healthy_work_then_fails() {
    let f = Fixture::new();
    f.save(0, 0);
    f.save(1, 0);
    let service = f.service();
    let guard = faults::set(|stage, path| {
        if stage == "candidate" && path.ends_with("a_3aworld/r.0.0.vxregion") {
            return Err(super::super::builder::SourceChanged.into());
        }
        Ok(())
    });
    assert!(
        service
            .refresh_once()
            .unwrap_err()
            .to_string()
            .contains("incomplete")
    );
    assert!(
        service
            .runtime("a:world")
            .unwrap()
            .region(1, 0)
            .unwrap()
            .is_some()
    );
    assert!(
        service
            .runtime("z:other")
            .unwrap()
            .region(0, 0)
            .unwrap()
            .is_some()
    );
    drop(guard);
    assert!(!service.refresh_all(1).unwrap().incomplete);
}

#[test]
fn crash_child() {
    let Ok(root) = std::env::var("VOXY_REFRESH_CRASH_ROOT") else {
        return;
    };
    let stage = std::env::var("VOXY_REFRESH_CRASH_STAGE").unwrap();
    let root = PathBuf::from(root);
    let registry = Arc::new(RwLock::new(Registry::open(root.join("registry")).unwrap()));
    let source = Arc::new(AnvilWorld::new("a:world".into(), root.join("world")));
    let runtime = RegionalRuntime::open(
        root.join("data"),
        "a:world".into(),
        source,
        registry,
        RegionLayout::new(0, 1, 5).unwrap(),
    )
    .unwrap();
    let _guard = faults::set(move |at, _| {
        if at == stage {
            std::process::exit(71);
        }
        Ok(())
    });
    runtime.refresh(0).unwrap();
    panic!("crash boundary not reached");
}

#[test]
fn process_exit_between_pair_publications_recovers() {
    for stage in [
        "terrain_after_rename",
        "terrain_reopen",
        "source_before_rename",
        "source_after_rename",
    ] {
        let f = Fixture::new();
        f.save(0, 0);
        let runtime = f.runtime();
        runtime.refresh(0).unwrap();
        fs::remove_file(runtime.source_path((0, 0))).unwrap();
        drop(runtime);
        let status = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "regional::runtime::refresh_tests::crash_child",
                "--nocapture",
            ])
            .env("VOXY_REFRESH_CRASH_ROOT", &f.root)
            .env("VOXY_REFRESH_CRASH_STAGE", stage)
            .status()
            .unwrap();
        assert_eq!(status.code(), Some(71));
        let runtime = f.runtime();
        assert_eq!(runtime.region(0, 0).unwrap().unwrap().generation(), 2);
        let report = runtime.refresh(1).unwrap();
        assert!(!report.incomplete);
        let live = runtime.region(0, 0).unwrap().unwrap();
        let sidecar = RegionSourceTable::open(runtime.source_path((0, 0))).unwrap();
        assert_eq!(sidecar.terrain_generation, live.generation());
        assert_eq!(
            live.generation(),
            if stage == "source_after_rename" { 2 } else { 3 }
        );
    }
}

#[test]
fn missing_or_changed_candidate_is_deferred_not_authoritative_absence() {
    let f = Fixture::new();
    f.save(0, 0);
    let runtime = f.runtime();
    let path = f.source.region_dir().join("r.0.0.mca");
    let guard = faults::set(move |stage, _| {
        if stage == "inventory" {
            fs::remove_file(&path)?;
        }
        Ok(())
    });
    let report = runtime.refresh(0).unwrap();
    assert!(report.incomplete && report.removed.is_empty() && report.changed.is_empty());
    assert!(!runtime.confirmed_absent(0, 0).unwrap());
    drop(guard);
    let report = runtime.refresh(1).unwrap();
    assert!(!report.incomplete);
    assert!(runtime.confirmed_absent(0, 0).unwrap());
}

#[test]
fn invalid_final_replacement_is_not_announced_or_overwritten() {
    for invalid_identity in [false, true] {
        let f = Fixture::new();
        f.save(0, 0);
        let runtime = f.runtime();
        runtime.refresh(0).unwrap();
        runtime.subscribe_region(0, 0).unwrap();
        let old = runtime.region(0, 0).unwrap().unwrap();
        write_lock(&runtime.sources).unwrap().clear();
        fs::remove_file(runtime.source_path((0, 0))).unwrap();
        let guard = faults::set(move |stage, path| {
            if stage == "terrain_after_rename" {
                let mut bytes = fs::read(path)?;
                if invalid_identity {
                    // A valid directory/header with the wrong world identity is not our generation.
                    bytes[48] ^= 1;
                    let crc = crate::crc::crc32c(&bytes[..252]);
                    bytes[252..256].copy_from_slice(&crc.to_le_bytes());
                } else {
                    bytes[0] ^= 1;
                }
                fs::write(path, bytes)?;
                anyhow::bail!("injected bad final file");
            }
            Ok(())
        });
        let report = runtime.refresh(1).unwrap();
        assert!(report.incomplete && report.changed.is_empty());
        drop(guard);
        let bytes = fs::read(runtime.terrain_path((0, 0))).unwrap();
        let report = runtime.refresh(2).unwrap();
        assert!(report.incomplete && report.changed.is_empty());
        if invalid_identity {
            assert_eq!(fs::read(runtime.terrain_path((0, 0))).unwrap(), bytes);
        } else {
            assert!(!runtime.terrain_path((0, 0)).exists());
        }
        assert_eq!(
            runtime.region(0, 0).unwrap().unwrap().generation(),
            old.generation()
        );
        if !invalid_identity {
            let report = runtime.refresh(3).unwrap();
            assert!(!report.incomplete);
            assert_eq!(report.changed, vec![(0, 0, 3)]);
            assert!(runtime.maintenance.lock().unwrap().retry.is_empty());
        }
    }
}
