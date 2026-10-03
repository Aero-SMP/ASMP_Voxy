//! Saved-Anvil workload setup; no synthetic backend or test handler.
use anyhow::{Context, Result};
use fastnbt::{ByteArray, LongArray};
use flate2::{Compression, write::ZlibEncoder};
use serde::Serialize;
use std::{
    collections::BTreeMap,
    fs::{self, File},
    io::Write,
    path::Path,
    sync::atomic::{AtomicUsize, Ordering},
    time::Instant,
};

#[derive(Serialize)]
struct Chunk {
    #[serde(rename = "DataVersion")]
    version: i32,
    #[serde(rename = "xPos")]
    x: i32,
    #[serde(rename = "zPos")]
    z: i32,
    #[serde(rename = "Status")]
    status: &'static str,
    sections: Vec<Section>,
}
#[derive(Serialize)]
struct Section {
    #[serde(rename = "Y")]
    y: i8,
    block_states: Blocks,
    biomes: Biomes,
    #[serde(rename = "BlockLight")]
    block_light: ByteArray,
    #[serde(rename = "SkyLight")]
    sky_light: ByteArray,
}
#[derive(Serialize)]
struct Blocks {
    palette: Vec<Block>,
    data: LongArray,
}
#[derive(Serialize)]
struct Block {
    #[serde(rename = "Name")]
    name: &'static str,
    #[serde(rename = "Properties", skip_serializing_if = "BTreeMap::is_empty")]
    properties: BTreeMap<&'static str, &'static str>,
}
#[derive(Serialize)]
struct Biomes {
    palette: Vec<&'static str>,
}

fn chunk(x: i32, z: i32) -> Result<Vec<u8>> {
    let heights = std::array::from_fn::<_, 256, _>(|i| {
        let wx = x as i64 * 16 + (i & 15) as i64;
        let wz = z as i64 * 16 + (i >> 4) as i64;
        22 + ((wx * 17) ^ (wz * 31) ^ (wx.div_euclid(11) * 47)).rem_euclid(21) as i32
    });
    let mut sections = Vec::new();
    for sy in 0..4 {
        let mut words = vec![0i64; 256];
        for i in 0..4096 {
            let y = sy * 16 + (i >> 8) as i32;
            let h = heights[i & 255];
            let id = if y < h - 3 {
                1
            } else if y < h {
                2
            } else if y == h {
                3
            } else if y < 27 {
                4
            } else {
                0
            };
            words[i / 16] |= (id as i64) << (i % 16 * 4);
        }
        let palette = [
            "minecraft:air",
            "minecraft:stone",
            "minecraft:dirt",
            "minecraft:grass_block",
            "minecraft:water",
            "minecraft:gold_block",
        ]
        .into_iter()
        .map(|name| Block {
            name,
            properties: match name {
                "minecraft:grass_block" => BTreeMap::from([("snowy", "false")]),
                "minecraft:water" => BTreeMap::from([("level", "0")]),
                _ => BTreeMap::new(),
            },
        })
        .collect();
        sections.push(Section {
            y: sy as i8,
            block_states: Blocks {
                palette,
                data: LongArray::new(words),
            },
            biomes: Biomes {
                palette: vec!["minecraft:plains"],
            },
            block_light: ByteArray::new(vec![0; 2048]),
            sky_light: ByteArray::new(vec![if sy >= 2 { -1 } else { 0 }; 2048]),
        });
    }
    let nbt = fastnbt::to_bytes(&Chunk {
        version: 3955,
        x,
        z,
        status: "minecraft:full",
        sections,
    })?;
    let mut encoder = ZlibEncoder::new(Vec::new(), Compression::fast());
    encoder.write_all(&nbt)?;
    Ok(encoder.finish()?)
}
fn region(root: &Path, i: usize) -> Result<()> {
    let rx = (i % 10) as i32 * 4 - 20;
    let rz = (i / 10) as i32 * 4 - 20;
    let mut output = vec![0u8; 8192];
    for z in 0..32 {
        for x in 0..32 {
            let data = chunk(rx * 32 + x, rz * 32 + z)?;
            let sector = output.len() / 4096;
            let sectors = (data.len() + 5).div_ceil(4096);
            let slot = (x | z << 5) as usize;
            output[slot * 4..slot * 4 + 4]
                .copy_from_slice(&(((sector as u32) << 8) | sectors as u32).to_be_bytes());
            output[4096 + slot * 4..4100 + slot * 4].copy_from_slice(&1u32.to_be_bytes());
            output.extend_from_slice(&((data.len() + 1) as u32).to_be_bytes());
            output.push(2);
            output.extend(data);
            output.resize((sector + sectors) * 4096, 0);
        }
    }
    let path = root.join(format!("r.{rx}.{rz}.mca"));
    let temporary = path.with_extension("mca.next");
    let mut file = File::create(&temporary)?;
    file.write_all(&output)?;
    file.sync_all()?;
    drop(file);
    fs::rename(temporary, path)?;
    Ok(())
}
fn main() -> Result<()> {
    let path = std::env::args()
        .nth(1)
        .context("Usage: full_fixture OUTPUT_WORLD")?;
    let region_root = Path::new(&path).join("region");
    fs::create_dir_all(&region_root)?;
    let start = Instant::now();
    let next = AtomicUsize::new(0);
    let workers = std::thread::available_parallelism()?.get().min(100);
    std::thread::scope(|scope| -> Result<()> {
        let handles = (0..workers)
            .map(|_| {
                scope.spawn(|| -> Result<()> {
                    loop {
                        let i = next.fetch_add(1, Ordering::Relaxed);
                        if i >= 100 {
                            return Ok(());
                        }
                        region(&region_root, i)?;
                    }
                })
            })
            .collect::<Vec<_>>();
        for handle in handles {
            handle
                .join()
                .map_err(|_| anyhow::anyhow!("fixture worker failed"))??;
        }
        Ok(())
    })?;
    File::open(&region_root)?.sync_all()?;
    let bytes = fs::read_dir(&region_root)?
        .map(|entry| entry?.metadata().map(|m| m.len()))
        .collect::<std::io::Result<Vec<_>>>()?
        .iter()
        .sum::<u64>();
    println!(
        "{{\"event\":\"fixture_ready\",\"regions\":100,\"chunks\":102400,\"chunks_per_region\":1024,\"vertical_sections\":4,\"saved_bytes\":{bytes},\"generation_seconds\":{},\"workers\":{workers},\"coordinates\":\"((i%10)*4-20,(i/10)*4-20)\",\"height\":\"22+((wx*17 ^ wz*31 ^ (wx//11)*47)%21)\",\"min_y\":0,\"max_y_exclusive\":64}}",
        start.elapsed().as_secs_f64()
    );
    Ok(())
}
