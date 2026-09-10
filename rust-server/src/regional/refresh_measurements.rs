use super::*;

#[test]
fn partial_write_measurement() {
    let root = std::env::temp_dir().join(format!("voxy-partial-measure-{}", std::process::id()));
    fs::create_dir_all(root.join("world/region")).unwrap();
    fs::write(root.join("world/region/r.0.0.mca"), vec![0u8; 8192]).unwrap();
    let registry = Arc::new(RwLock::new(Registry::open(root.join("registry")).unwrap()));
    let runtime = RegionalRuntime::open(
        root.join("data"),
        "a:world".into(),
        Arc::new(AnvilWorld::new("a:world".into(), root.join("world"))),
        registry,
        RegionLayout::new(0, 1, 5).unwrap(),
    )
    .unwrap();
    let directory = root
        .join("data/regional")
        .join(crate::safe_dimension_name("a:world"));
    let sidecar = directory.join("r.0.0.vxsource");
    fs::create_dir(&sidecar).unwrap(); // Real rename failure, not a simulated builder.
    let start = Instant::now();
    let attempt = runtime.refresh(0);
    let failed_us = start.elapsed().as_micros();
    let visible = runtime.region(0, 0).unwrap().map(|r| r.generation());
    let disk = RegionFile::open(directory.join("r.0.0.vxregion"))
        .unwrap()
        .generation();
    eprintln!(
        "partial attempt={attempt:?} visible={visible:?} disk_generation={disk} failed_us={failed_us}"
    );
    fs::remove_dir(&sidecar).unwrap();
    let start = Instant::now();
    for _ in 0..2 {
        runtime.refresh(1).unwrap();
    }
    let live = runtime.region(0, 0).unwrap().unwrap();
    let table = RegionSourceTable::open(&sidecar).unwrap();
    assert_eq!(live.generation(), table.terrain_generation);
    eprintln!(
        "partial recovery_us={} generation={} files={}",
        start.elapsed().as_micros(),
        live.generation(),
        fs::read_dir(directory).unwrap().count()
    );
    drop(runtime);
    fs::remove_dir_all(root).unwrap();
}

use crate::{anvil::AnvilWorld, registry::Registry};
use std::{
    fs,
    sync::{Arc, RwLock},
    time::Instant,
};

#[test]
fn identical_refresh_measurement() {
    let root = std::env::temp_dir().join(format!("voxy-refresh-measure-{}", std::process::id()));
    fs::create_dir_all(root.join("world/region")).unwrap();
    for x in 0..8 {
        fs::write(
            root.join(format!("world/region/r.{x}.0.mca")),
            vec![0u8; 8192],
        )
        .unwrap();
    }
    let registry = Arc::new(RwLock::new(Registry::open(root.join("registry")).unwrap()));
    let source = Arc::new(AnvilWorld::new("a:world".into(), root.join("world")));
    let open = || {
        RegionalRuntime::open(
            root.join("data"),
            "a:world".into(),
            source.clone(),
            registry.clone(),
            RegionLayout::new(0, 1, 5).unwrap(),
        )
        .unwrap()
    };
    let runtime = open();
    let start = Instant::now();
    let mut published = 0;
    for _ in 0..8 {
        published += runtime.refresh(0).unwrap().changed.len();
    }
    let initial_us = start.elapsed().as_micros();
    assert_eq!(published, 8);
    let start = Instant::now();
    for _ in 0..100 {
        assert!(runtime.refresh(0).unwrap().changed.is_empty());
    }
    let unchanged_us = start.elapsed().as_micros();
    for x in 0..8 {
        let mut bytes = vec![0u8; 8192];
        bytes[4096..4100].copy_from_slice(&1u32.to_be_bytes());
        fs::write(root.join(format!("world/region/r.{x}.0.mca")), bytes).unwrap();
    }
    let start = Instant::now();
    let metadata = runtime.refresh(0).unwrap();
    let metadata_us = start.elapsed().as_micros();
    assert_eq!(metadata.metadata_only, 8);
    assert!(metadata.changed.is_empty());
    drop(runtime);
    let start = Instant::now();
    let runtime = open();
    let reopen_us = start.elapsed().as_micros();
    let derived = fs::read_dir(
        root.join("data/regional")
            .join(crate::safe_dimension_name("a:world")),
    )
    .unwrap()
    .map(|e| e.unwrap().metadata().unwrap().len())
    .sum::<u64>();
    let mut broken = vec![0u8; 12288];
    broken[..4].copy_from_slice(&0x201u32.to_be_bytes());
    fs::write(root.join("world/region/r.-1.0.mca"), broken).unwrap();
    runtime.prioritize_region(-1, 0).unwrap();
    for x in 8..16 {
        fs::write(
            root.join(format!("world/region/r.{x}.0.mca")),
            vec![0u8; 8192],
        )
        .unwrap();
    }
    let start = Instant::now();
    let mut healthy = 0;
    let mut errors = 0;
    for _ in 0..8 {
        match runtime.refresh(1) {
            Ok(r) => healthy += r.changed.len(),
            Err(_) => errors += 1,
        }
    }
    eprintln!(
        "refresh fixture regions=8 initial_us={initial_us} unchanged_100_us={unchanged_us} metadata_8_us={metadata_us} reopen_us={reopen_us} derived_bytes={derived} backlog_8_us={} healthy={healthy} errors={errors}",
        start.elapsed().as_micros()
    );
    drop(runtime);
    fs::remove_dir_all(root).unwrap();
}
