use crate::{
    anvil::{Region, decode_chunk},
    payload::{Catalog, Cell, Hash, Key, MAX_BODY, VOLUME, decode_frame, encode_frame, hash, hex},
};
use anyhow::{Context, Result, ensure};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    time::UNIX_EPOCH,
};

const HEADER: usize = 44 + 1024 * 32;
const ENTRY: usize = 25;
pub type Marker = [u8; 24];

pub fn marker(path: &Path) -> Result<Marker> {
    let metadata = fs::metadata(path)?;
    let mut out = [0; 24];
    out[..16].copy_from_slice(
        &metadata
            .modified()?
            .duration_since(UNIX_EPOCH)?
            .as_nanos()
            .to_le_bytes(),
    );
    out[16..].copy_from_slice(&metadata.len().to_le_bytes());
    Ok(out)
}
pub fn atomic_write(path: &Path, bytes: &[u8]) -> Result<()> {
    fs::create_dir_all(path.parent().context("path parent")?)?;
    let tmp = path.with_extension("new");
    let mut file = File::create(&tmp)?;
    file.write_all(bytes)?;
    file.sync_all()?;
    drop(file);
    fs::rename(tmp, path)?;
    File::open(path.parent().unwrap())?.sync_all()?;
    Ok(())
}
pub fn region_path(data: &Path, dimension: &str, x: i32, z: i32) -> PathBuf {
    data.join("regions")
        .join(hex(&hash(dimension.as_bytes())))
        .join(format!("r.{x}.{z}.vxr"))
}
pub fn saved_marker(path: &Path) -> Option<Marker> {
    let mut head = [0; 44];
    File::open(path).ok()?.read_exact(&mut head).ok()?;
    if &head[..8] != b"VXRREG02" {
        return None;
    }
    head[20..44].try_into().ok()
}

fn entry_bytes(key: Key, offset: u64, len: u32) -> [u8; ENTRY] {
    let mut out = [0; ENTRY];
    out[0] = key.level;
    out[1..5].copy_from_slice(&key.x.to_le_bytes());
    out[5..9].copy_from_slice(&key.y.to_le_bytes());
    out[9..13].copy_from_slice(&key.z.to_le_bytes());
    out[13..21].copy_from_slice(&offset.to_le_bytes());
    out[21..].copy_from_slice(&len.to_le_bytes());
    out
}
fn entry_key(bytes: &[u8]) -> Key {
    Key {
        level: bytes[0],
        x: i32::from_le_bytes(bytes[1..5].try_into().unwrap()),
        y: i32::from_le_bytes(bytes[5..9].try_into().unwrap()),
        z: i32::from_le_bytes(bytes[9..13].try_into().unwrap()),
    }
}

pub fn read_frame(path: &Path, key: Key) -> Result<Option<Vec<u8>>> {
    let mut file = match File::open(path) {
        Ok(file) => file,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(e.into()),
    };
    let mut header = [0; 44];
    file.read_exact(&mut header)?;
    ensure!(
        matches!(&header[..8], b"VXRREG01" | b"VXRREG02"),
        "region magic"
    );
    let index = u64::from_le_bytes(header[8..16].try_into()?);
    let count = u32::from_le_bytes(header[16..20].try_into()?) as u64;
    ensure!(
        index >= 44 && index.checked_add(count * ENTRY as u64) == Some(file.metadata()?.len()),
        "region index"
    );
    let (mut low, mut high) = (0, count);
    while low < high {
        let mid = (low + high) / 2;
        file.seek(SeekFrom::Start(index + mid * ENTRY as u64))?;
        let mut entry = [0; ENTRY];
        file.read_exact(&mut entry)?;
        match entry_key(&entry).cmp(&key) {
            std::cmp::Ordering::Less => low = mid + 1,
            std::cmp::Ordering::Greater => high = mid,
            std::cmp::Ordering::Equal => {
                let offset = u64::from_le_bytes(entry[13..21].try_into()?);
                let len = u32::from_le_bytes(entry[21..25].try_into()?);
                ensure!(
                    offset >= 44 && offset + len as u64 <= index && len as usize <= MAX_BODY + 4096,
                    "region frame location"
                );
                file.seek(SeekFrom::Start(offset))?;
                let mut bytes = vec![0; len as usize];
                file.read_exact(&mut bytes)?;
                return Ok(Some(bytes));
            }
        }
    }
    Ok(None)
}

pub fn section_keys(path: &Path) -> Result<Vec<Key>> {
    let mut file = match File::open(path) {
        Ok(file) => file,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(e) => return Err(e.into()),
    };
    let mut header = [0; 44];
    file.read_exact(&mut header)?;
    ensure!(
        matches!(&header[..8], b"VXRREG01" | b"VXRREG02"),
        "region magic"
    );
    let index = u64::from_le_bytes(header[8..16].try_into()?);
    let count = u32::from_le_bytes(header[16..20].try_into()?);
    ensure!(
        index >= 44 && index + count as u64 * ENTRY as u64 == file.metadata()?.len(),
        "region index"
    );
    file.seek(SeekFrom::Start(index))?;
    let mut keys = Vec::new();
    for _ in 0..count {
        let mut entry = [0; ENTRY];
        file.read_exact(&mut entry)?;
        keys.push(entry_key(&entry));
    }
    Ok(keys)
}

fn append_frame(
    file: &mut File,
    entries: &mut BTreeMap<Key, (u64, u32)>,
    key: Key,
    frame: &[u8],
) -> Result<()> {
    let offset = file.seek(SeekFrom::End(0))?;
    file.write_all(frame)?;
    ensure!(
        entries.insert(key, (offset, frame.len() as u32)).is_none(),
        "duplicate section"
    );
    Ok(())
}

fn append_section(
    file: &mut File,
    entries: &mut BTreeMap<Key, (u64, u32)>,
    dirty: &mut BTreeSet<Key>,
    previous: &Path,
    key: Key,
    cells: &[Cell],
    children: u8,
) -> Result<()> {
    let candidate = encode_frame(cells, children, [0; 32])?;
    let frame = match read_frame(previous, key)? {
        Some(old) if old.len() >= 81 && old[40..81] == candidate[40..81] => old,
        _ => {
            dirty.insert(key);
            candidate
        }
    };
    append_frame(file, entries, key, &frame)
}

fn source_hashes(path: &Path) -> Result<Vec<Hash>> {
    let mut output = vec![[0; 32]; 1024];
    if let Ok(mut file) = File::open(path) {
        let mut header = [0; 44];
        file.read_exact(&mut header)?;
        if &header[..8] == b"VXRREG02" {
            file.read_exact(output.as_flattened_mut())?;
        }
    }
    Ok(output)
}

/// The block winning most votes among non-air samples survives reduction; ties
/// follow sample order. Biomes vote independently and both light channels use max.
pub fn reduce(samples: &[Cell; 8], catalog: &Catalog) -> Cell {
    if samples.iter().all(|cell| *cell == samples[0]) {
        return samples[0];
    }
    let mut out = samples[0];
    let mut block_votes = 0;
    let mut biome_votes = 0;
    for cell in samples {
        let votes = samples.iter().filter(|s| s.block == cell.block).count();
        if !catalog.is_air(cell.block) && votes > block_votes {
            out.block = cell.block;
            block_votes = votes;
        }
        let votes = samples.iter().filter(|s| s.biome == cell.biome).count();
        if votes > biome_votes {
            out.biome = cell.biome;
            biome_votes = votes;
        }
    }
    out.light = samples.iter().map(|c| c.light & 15).max().unwrap()
        | samples.iter().map(|c| c.light >> 4).max().unwrap() << 4;
    out
}

pub fn build_region(
    source: &Path,
    target: &Path,
    data: &Path,
    rx: i32,
    rz: i32,
    catalog: &mut Catalog,
) -> Result<usize> {
    let before = marker(source)?;
    let mut region = Region::open(source, rx, rz)?;
    let previous = section_keys(target)?.into_iter().collect::<BTreeSet<_>>();
    let previous_hashes = source_hashes(target)?;
    let mut current_hashes = vec![[0; 32]; 1024];
    fs::create_dir_all(target.parent().unwrap())?;
    let tmp = target.with_extension("building");
    let mut output = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(true)
        .open(&tmp)?;
    output.write_all(&[0; HEADER])?;
    let mut entries = BTreeMap::new();
    let mut dirty = BTreeSet::new();
    for gz in 0..16 {
        for gx in 0..16 {
            let mut blobs = Vec::new();
            let mut changed = false;
            for dz in 0..2 {
                for dx in 0..2 {
                    let x = gx * 2 + dx;
                    let z = gz * 2 + dz;
                    let blob = region.chunk_bytes(x, z)?;
                    let fingerprint = blob.as_ref().map_or([0; 32], |b| b.fingerprint);
                    current_hashes[x | z << 5] = fingerprint;
                    changed |= previous_hashes[x | z << 5] != fingerprint;
                    blobs.push(blob);
                }
            }
            if !changed {
                for &key in previous.iter().filter(|k| {
                    k.level == 0 && k.x == rx * 16 + gx as i32 && k.z == rz * 16 + gz as i32
                }) {
                    append_frame(
                        &mut output,
                        &mut entries,
                        key,
                        &read_frame(target, key)?.context("previous section missing")?,
                    )?;
                }
                continue;
            }
            let chunks = blobs
                .into_iter()
                .map(|blob| blob.map(|b| decode_chunk(b, catalog)).transpose())
                .collect::<Result<Vec<_>>>()?;
            let mut ys = chunks
                .iter()
                .flatten()
                .flat_map(|c| c.keys())
                .map(|y| y.div_euclid(2))
                .collect::<std::collections::BTreeSet<_>>();
            // A saved deletion is represented by an explicit air snapshot. Missing or
            // unreadable source files never reach this publication path.
            ys.extend(
                previous
                    .iter()
                    .filter(|k| {
                        k.level == 0 && k.x == rx * 16 + gx as i32 && k.z == rz * 16 + gz as i32
                    })
                    .map(|k| k.y),
            );
            let Some(&min) = ys.first() else { continue };
            let max = *ys.last().unwrap();
            for sy in min..=max {
                let mut cells = vec![Cell::default(); VOLUME];
                for (slot, chunk) in chunks.iter().enumerate() {
                    if let Some(chunk) = chunk {
                        for dy in 0..2 {
                            if let Some(source) = chunk.get(&(sy * 2 + dy)) {
                                for (i, &cell) in source.iter().enumerate() {
                                    let x = (i & 15) + (slot & 1) * 16;
                                    let z = (i >> 4 & 15) + (slot >> 1) * 16;
                                    let y = (i >> 8) + dy as usize * 16;
                                    cells[x | z << 5 | y << 10] = cell;
                                }
                            }
                        }
                    }
                }
                let key = Key {
                    level: 0,
                    x: rx
                        .checked_mul(16)
                        .and_then(|v| v.checked_add(gx as i32))
                        .context("section x overflow")?,
                    y: sy,
                    z: rz
                        .checked_mul(16)
                        .and_then(|v| v.checked_add(gz as i32))
                        .context("section z overflow")?,
                };
                append_section(
                    &mut output,
                    &mut entries,
                    &mut dirty,
                    target,
                    key,
                    &cells,
                    0,
                )?;
            }
        }
    }
    for level in 1..=4 {
        let mut parents: BTreeMap<Key, Vec<Key>> = BTreeMap::new();
        for &key in entries.keys().filter(|k| k.level == level - 1) {
            parents.entry(key.parent()).or_default().push(key);
        }
        for (parent, children) in parents {
            if previous.contains(&parent) && children.iter().all(|k| !dirty.contains(k)) {
                append_frame(
                    &mut output,
                    &mut entries,
                    parent,
                    &read_frame(target, parent)?.context("previous parent missing")?,
                )?;
                continue;
            }
            let mut cells = vec![Cell::default(); VOLUME];
            let mut mask = 0u8;
            for child in children {
                let slot = child.child_slot();
                mask |= 1 << slot;
                let (offset, len) = entries[&child];
                output.seek(SeekFrom::Start(offset))?;
                let mut frame = vec![0; len as usize];
                output.read_exact(&mut frame)?;
                let (source, _) = decode_frame(&frame)?;
                for y in 0..16 {
                    for z in 0..16 {
                        for x in 0..16 {
                            let samples = std::array::from_fn(|i| {
                                source[(x * 2 + (i & 1))
                                    | (z * 2 + (i >> 1 & 1)) << 5
                                    | (y * 2 + (i >> 2)) << 10]
                            });
                            let index = (x + (slot & 1) * 16)
                                | (z + (slot >> 1 & 1) * 16) << 5
                                | (y + (slot >> 2) * 16) << 10;
                            cells[index] = reduce(&samples, catalog);
                        }
                    }
                }
            }
            append_section(
                &mut output,
                &mut entries,
                &mut dirty,
                target,
                parent,
                &cells,
                mask,
            )?;
        }
    }
    ensure!(
        marker(source)? == before,
        "saved region changed during build; preserve previous generation"
    );
    let catalog_bytes = catalog.encode()?;
    let catalog_hash = hash(&catalog_bytes);
    let catalog_path = data
        .join("catalogs")
        .join(format!("{}.vxc", hex(&catalog_hash)));
    if !catalog_path.exists() {
        atomic_write(&catalog_path, &catalog_bytes)?;
    }
    // Persist append-only IDs before publishing any frame that references them.
    if fs::read(data.join("catalog.current")).ok().as_deref() != Some(catalog_bytes.as_slice()) {
        atomic_write(&data.join("catalog.current"), &catalog_bytes)?;
    }
    for key in &dirty {
        let (offset, _) = entries[key];
        output.seek(SeekFrom::Start(offset + 8))?;
        output.write_all(&catalog_hash)?;
    }
    let index = output.seek(SeekFrom::End(0))?;
    for (&key, &(offset, len)) in &entries {
        output.write_all(&entry_bytes(key, offset, len))?;
    }
    output.seek(SeekFrom::Start(0))?;
    output.write_all(b"VXRREG02")?;
    output.write_all(&index.to_le_bytes())?;
    output.write_all(&(entries.len() as u32).to_le_bytes())?;
    output.write_all(&before)?;
    output.write_all(current_hashes.as_flattened())?;
    output.sync_all()?;
    drop(output);
    fs::rename(tmp, target)?;
    File::open(target.parent().unwrap())?.sync_all()?;
    Ok(entries.len())
}
