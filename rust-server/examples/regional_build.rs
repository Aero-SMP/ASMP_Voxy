use anyhow::{Context, Result, bail};
use std::{
    path::PathBuf,
    sync::{Arc, RwLock},
};
use voxy_rust_server::{
    anvil::AnvilWorld,
    regional::{RegionFile, RegionLayout, rebuild_region},
    registry::Registry,
};

fn main() -> Result<()> {
    let mut arguments = std::env::args_os().skip(1);
    let world = PathBuf::from(arguments.next().context("missing world directory")?);
    let region_x = arguments
        .next()
        .context("missing region x")?
        .to_string_lossy()
        .parse::<i32>()?;
    let region_z = arguments
        .next()
        .context("missing region z")?
        .to_string_lossy()
        .parse::<i32>()?;
    let output = PathBuf::from(arguments.next().context("missing output directory")?);
    if arguments.next().is_some() {
        bail!("usage: regional_build WORLD REGION_X REGION_Z OUTPUT_DIRECTORY");
    }
    std::fs::create_dir_all(&output)?;
    let registry = Arc::new(RwLock::new(Registry::open(output.join("catalog"))?));
    let source = AnvilWorld::new("minecraft:overworld".into(), world.clone());
    let header = source
        .region_header(region_x, region_z)?
        .context("requested Anvil region does not exist")?;
    let mut identity_input = world.as_os_str().as_encoded_bytes().to_vec();
    identity_input.extend_from_slice(b"\0minecraft:overworld");
    let world_identity = *blake3::hash(&identity_input).as_bytes();
    let terrain_path = output.join(format!("r.{region_x}.{region_z}.vxregion"));
    let (region, stats) = rebuild_region(
        &source,
        &registry,
        &header,
        &terrain_path,
        output.join(format!("r.{region_x}.{region_z}.vxsource")),
        world_identity,
        1,
        RegionLayout::new(-2, 12, 5)?,
    )?;
    let reopened = RegionFile::open(&terrain_path)?;
    if reopened.generation() != region.generation() {
        bail!("reopened regional generation disagrees with the publication");
    }
    println!(
        "region=({region_x},{region_z}) generation={} chunks={}/{} sections={:?} bytes={}",
        region.generation(),
        stats.generated_chunks,
        stats.chunks_read,
        stats.sections_by_level,
        stats.output_bytes,
    );
    Ok(())
}
