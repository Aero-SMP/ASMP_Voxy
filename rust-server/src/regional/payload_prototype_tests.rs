//! First-gate experiment only. No alternative production reader or protocol is installed.
use super::{RegionFile, SectionFrame};
use crate::{
    catalog::{Catalog, CatalogBlock, MAX_CATALOG_BYTES},
    lod::{Cell, SECTION_VOLUME},
};
use anyhow::{Context, Result, ensure};
use std::{
    collections::HashMap,
    fs::{self, File},
    io::{BufWriter, Write},
    path::Path,
    time::Instant,
};

/// Stream the name expansion into a bounded-window encoder, never an expanded byte vector.
fn named<W: Write>(wire: &[u8], catalog: &Catalog, out: W) -> Result<(u64, [u8; 32])> {
    let count = u16::from_le_bytes(wire[..2].try_into()?) as usize;
    let mut blocks = Vec::new();
    let mut biomes = Vec::new();
    let mut block_ids = HashMap::new();
    let mut biome_ids = HashMap::new();
    let mut palette = Vec::with_capacity(count * 5);
    for cell in wire[2..2 + count * 9].chunks_exact(9) {
        let block = u32::from_le_bytes(cell[..4].try_into()?) as usize;
        let biome = u32::from_le_bytes(cell[4..8].try_into()?) as usize;
        let name = &catalog
            .blocks
            .get(block)
            .context("block outside catalog")?
            .canonical;
        let block_id = *block_ids.entry(name).or_insert_with(|| {
            blocks.push(name.as_str());
            (blocks.len() - 1) as u16
        });
        let name = catalog.biomes.get(biome).context("biome outside catalog")?;
        let biome_id = *biome_ids.entry(name).or_insert_with(|| {
            biomes.push(name.as_str());
            (biomes.len() - 1) as u16
        });
        palette.extend_from_slice(&block_id.to_le_bytes());
        palette.extend_from_slice(&biome_id.to_le_bytes());
        palette.push(cell[8]);
    }
    let mut name_bytes = 0u64;
    for name in blocks.iter().chain(&biomes) {
        ensure!(!name.is_empty() && name.len() <= 4096, "name bound");
        name_bytes += 2 + name.len() as u64;
    }
    ensure!(
        name_bytes <= (MAX_CATALOG_BYTES - 40) as u64,
        "name table bound"
    );
    let indexes = &wire[2 + count * 9..];
    let length = 6 + name_bytes + palette.len() as u64 + indexes.len() as u64;
    let mut encoder = zstd::stream::write::Encoder::new(out, 3)?;
    encoder.window_log(18)?;
    encoder.include_checksum(true)?;
    encoder.set_pledged_src_size(Some(length))?;
    let mut hash = blake3::Hasher::new();
    let mut write = |bytes: &[u8]| -> Result<()> {
        hash.update(bytes);
        encoder.write_all(bytes)?;
        Ok(())
    };
    for n in [count, blocks.len(), biomes.len()] {
        write(&(n as u16).to_le_bytes())?;
    }
    for name in blocks.iter().chain(&biomes) {
        write(&(name.len() as u16).to_le_bytes())?;
        write(name.as_bytes())?;
    }
    write(&palette)?;
    write(indexes)?;
    encoder.finish()?;
    Ok((length, *hash.finalize().as_bytes()))
}

fn fixture(
    root: &Path,
    id: &str,
    wire: &[u8],
    catalog: &Catalog,
    children: u8,
    manifest: &mut impl Write,
) -> Result<(u64, u64, u128)> {
    // Numeric baseline is the exact existing production encoding/compression profile.
    let old = zstd::bulk::compress(wire, 1)?;
    fs::write(root.join(format!("{id}.wire.zst")), &old)?;
    let output = root.join(format!("{id}.named.zst"));
    let start = Instant::now();
    let (canonical, hash) = named(wire, catalog, BufWriter::new(File::create(&output)?))?;
    let ns = start.elapsed().as_nanos();
    let compressed = fs::metadata(&output)?.len();
    let hex = |bytes: &[u8]| bytes.iter().map(|b| format!("{b:02x}")).collect::<String>();
    writeln!(
        manifest,
        "{id}\t{}\t{canonical}\t{compressed}\t{children}\t{}\t{}",
        wire.len(),
        hex(&hash[..16]),
        hex(blake3::hash(&old).as_bytes())
    )?;
    Ok((old.len() as u64, compressed, ns))
}

#[test]
#[ignore = "explicit filesystem fixture output and read-only source sample required"]
fn emit_unified_payload_fixtures() -> Result<()> {
    let root = std::env::var("VOXY_PAYLOAD_FIXTURES").context("set isolated fixture directory")?;
    let root = Path::new(&root);
    fs::create_dir(root)?; // Refuse to overwrite a previous run.
    let mut manifest = BufWriter::new(File::create(root.join("fixtures.tsv"))?);
    let mut state = 177u64;
    for (id, count, large) in [
        ("ordinary", 257, false),
        ("large", 16368, true),
        ("air_light", 1, false),
    ] {
        let mut blocks = Vec::with_capacity(count + 1);
        if id != "air_light" {
            blocks.push(CatalogBlock {
                canonical: "minecraft:air".into(),
                opacity: 0,
                authoritative: true,
            });
        }
        for i in 0..count {
            let mut name = if id == "air_light" {
                "minecraft:air".to_owned()
            } else {
                format!("test:b{i}_")
            };
            if large {
                // Leave room for the required source-catalog air entry within 64 MiB.
                let length = if i + 1 == count { 4090 } else { 4096 };
                while name.len() < length {
                    state ^= state << 13;
                    state ^= state >> 7;
                    state ^= state << 17;
                    name.push((b'a' + (state % 26) as u8) as char);
                }
            }
            blocks.push(CatalogBlock {
                canonical: name,
                opacity: 0,
                authoritative: id == "air_light",
            });
        }
        let catalog = Catalog {
            catalog_id: 1,
            generation: 1,
            mip_generation: 1,
            blocks,
            biomes: vec!["test:biome".into()],
        };
        catalog.validate()?;
        fs::write(root.join(format!("{id}.catalog")), catalog.encode()?)?;
        let frame = SectionFrame::new(
            0x81,
            (0..SECTION_VOLUME)
                .map(|i| Cell {
                    block: (i % count) as u32 + u32::from(id != "air_light"),
                    biome: 0,
                    light: if id == "air_light" {
                        0xf0
                    } else {
                        (i % count) as u8
                    },
                })
                .collect(),
        )?;
        let (old, bytes, ns) = fixture(root, id, &frame.encode()?, &catalog, 0x81, &mut manifest)?;
        if large {
            ensure!(
                bytes > 4 * 1024 * 1024,
                "large fixture did not exceed old body bound"
            );
        }
        println!("UNIFIED_FIXTURE id={id} numeric={old} named={bytes} encodeNs={ns}");
    }
    if let Ok(source) = std::env::var("VOXY_PAYLOAD_SAMPLE") {
        let source = Path::new(&source);
        let mut bytes = fs::read(source.join("catalog/catalog.a"))?;
        let end = bytes.len() - 4;
        ensure!(
            crc32c::crc32c(&bytes[..end]) == u32::from_le_bytes(bytes[end..].try_into()?),
            "registry CRC"
        );
        bytes.truncate(end);
        bytes[..8].copy_from_slice(b"VXYCAT\0\0");
        let catalog = Catalog::decode(&bytes)?;
        fs::write(root.join("sample.catalog"), &bytes)?;
        for dimension in ["minecraft_3aoverworld", "minecraft_3athe_5fend"] {
            let mut paths = fs::read_dir(source.join("regional").join(dimension))?
                .map(|p| p.map(|p| p.path()))
                .collect::<std::io::Result<Vec<_>>>()?;
            paths.sort();
            let (mut count, mut old, mut new, mut ns) = (0, 0, 0, 0);
            for path in paths
                .into_iter()
                .filter(|p| p.extension().is_some_and(|e| e == "vxregion"))
            {
                let region = RegionFile::open(path)?;
                ensure!(
                    region.catalog_id() == catalog.catalog_id,
                    "sample catalog identity mismatch"
                );
                for (ordinal, entry) in region.entries().iter().enumerate() {
                    if !entry.has_payload() {
                        continue;
                    }
                    let compressed = region.read_compressed_ordinal(ordinal as u32)?.unwrap();
                    let wire =
                        zstd::bulk::decompress(&compressed, entry.canonical_length as usize)?;
                    ensure!(
                        blake3::hash(&wire).as_bytes()[..16] == entry.fingerprint,
                        "source fingerprint"
                    );
                    SectionFrame::decode(&wire)?;
                    let (o, n, t) = fixture(
                        root,
                        &format!("sample_{dimension}_{count}"),
                        &wire,
                        &catalog,
                        entry.non_empty_children,
                        &mut manifest,
                    )?;
                    old += o;
                    new += n;
                    ns += t;
                    count += 1;
                    if count == 1000 {
                        break;
                    }
                }
                if count == 1000 {
                    break;
                }
            }
            ensure!(count > 0, "empty sample");
            println!(
                "UNIFIED_SAMPLE dimension={dimension} sections={count} numeric={old} named={new} encodeNs={ns}"
            );
        }
    }
    Ok(())
}
