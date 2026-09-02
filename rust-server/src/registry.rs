use crate::{
    crc::crc32c, quarantine, read_file_bounded, replace_synced, take, take_u16, take_u32, take_u64,
};
use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

const MAGIC: &[u8; 8] = b"VXYREG\0\0";
const MAX_SNAPSHOT_BYTES: usize = 256 * 1024 * 1024;
pub const MAX_BLOCKS: usize = 1 << 20;
pub const MAX_BIOMES: usize = 512;

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct BlockEntry {
    pub canonical: String,
    pub opacity: u8,
    pub authoritative: bool,
}

#[derive(Clone, Debug)]
pub struct RegistrySnapshot {
    pub catalog_id: u64,
    pub generation: u64,
    pub mip_generation: u64,
    pub blocks: Vec<BlockEntry>,
    pub biomes: Vec<String>,
}

#[derive(Debug)]
pub struct Registry {
    root: PathBuf,
    catalog_id: u64,
    generation: u64,
    mip_generation: u64,
    blocks: Vec<BlockEntry>,
    biomes: Vec<String>,
    block_ids: HashMap<String, u32>,
    biome_ids: HashMap<String, u32>,
    dirty: bool,
}

impl Registry {
    pub fn open(root: impl AsRef<Path>) -> Result<Self> {
        let root = root.as_ref().to_owned();
        fs::create_dir_all(&root).with_context(|| format!("create {}", root.display()))?;
        let a_path = root.join("catalog.a");
        let b_path = root.join("catalog.b");
        let a = read_snapshot(&a_path);
        let b = read_snapshot(&b_path);
        if let Err(error) = &a {
            eprintln!(
                "registry snapshot {} is damaged: {error:#}",
                a_path.display()
            );
        }
        if let Err(error) = &b {
            eprintln!(
                "registry snapshot {} is damaged: {error:#}",
                b_path.display()
            );
        }
        // save() publishes one generation to both peers before any surface root may reference its
        // IDs. Therefore either valid peer is a safe high-water snapshot after a
        // single-file fault. A higher lone generation can only be an interrupted, unpublished
        // first copy; retaining its append-only IDs is also safe and prevents later aliasing.
        let selected = match (a, b) {
            (Ok(Some(a)), Ok(Some(b))) if a.catalog_id == b.catalog_id => {
                Some(if a.generation >= b.generation { a } else { b })
            }
            (Ok(Some(_)), Ok(Some(_))) => {
                eprintln!("registry peers name different catalogs; starting a new catalog");
                quarantine(&a_path);
                quarantine(&b_path);
                None
            }
            (Ok(Some(snapshot)), _) | (_, Ok(Some(snapshot))) => Some(snapshot),
            (Ok(None), Ok(None)) => None,
            _ => {
                eprintln!("no valid registry peer remains; starting a new catalog");
                quarantine(&a_path);
                quarantine(&b_path);
                None
            }
        };

        let (catalog_id, generation, mip_generation, blocks, biomes, dirty) =
            if let Some(snapshot) = selected {
                (
                    snapshot.catalog_id,
                    snapshot.generation,
                    snapshot.mip_generation,
                    snapshot.blocks,
                    snapshot.biomes,
                    false,
                )
            } else {
                (
                    new_catalog_id(),
                    0,
                    0,
                    vec![BlockEntry {
                        canonical: "minecraft:air".to_owned(),
                        opacity: 0,
                        authoritative: true,
                    }],
                    vec!["minecraft:plains".to_owned()],
                    true,
                )
            };
        validate_entries(&blocks, &biomes)?;
        let block_ids = blocks
            .iter()
            .enumerate()
            .map(|(id, entry)| (entry.canonical.clone(), id as u32))
            .collect();
        let biome_ids = biomes
            .iter()
            .enumerate()
            .map(|(id, entry)| (entry.clone(), id as u32))
            .collect();
        let mut registry = Self {
            root,
            catalog_id,
            generation,
            mip_generation,
            blocks,
            biomes,
            block_ids,
            biome_ids,
            dirty,
        };
        registry.apply_production_classification()?;
        registry.save()?;
        // Repair both peers to the selected committed/high-water state before returning.
        let snapshot = registry.snapshot();
        write_snapshot(&a_path, &snapshot)?;
        write_snapshot(&b_path, &snapshot)?;
        Ok(registry)
    }

    pub fn block_id(&mut self, canonical: &str) -> Result<u32> {
        if matches!(
            canonical.split('[').next().unwrap_or(canonical),
            "minecraft:air" | "minecraft:cave_air" | "minecraft:void_air"
        ) {
            return Ok(0);
        }
        if let Some(&id) = self.block_ids.get(canonical) {
            return Ok(id);
        }
        if self.blocks.len() >= MAX_BLOCKS {
            bail!("block registry reached Voxy's {MAX_BLOCKS} entry limit");
        }
        let id = self.blocks.len() as u32;
        let production_opacity = production_full_cube_opacity(canonical);
        let opacity = production_opacity.unwrap_or_else(|| estimated_opacity(canonical));
        self.blocks.push(BlockEntry {
            canonical: canonical.to_owned(),
            opacity,
            authoritative: production_opacity.is_some(),
        });
        self.block_ids.insert(canonical.to_owned(), id);
        self.dirty = true;
        Ok(id)
    }

    pub fn biome_id(&mut self, name: &str) -> Result<u32> {
        if let Some(&id) = self.biome_ids.get(name) {
            return Ok(id);
        }
        if self.biomes.len() >= MAX_BIOMES {
            bail!("biome registry reached Voxy's {MAX_BIOMES} entry limit");
        }
        let id = self.biomes.len() as u32;
        self.biomes.push(name.to_owned());
        self.biome_ids.insert(name.to_owned(), id);
        self.dirty = true;
        Ok(id)
    }

    pub fn snapshot(&self) -> RegistrySnapshot {
        RegistrySnapshot {
            catalog_id: self.catalog_id,
            generation: self.generation,
            mip_generation: self.mip_generation,
            blocks: self.blocks.clone(),
            biomes: self.biomes.clone(),
        }
    }

    pub fn opacity_table(&self) -> Vec<u8> {
        self.blocks.iter().map(|entry| entry.opacity).collect()
    }

    pub fn catalog_id(&self) -> u64 {
        self.catalog_id
    }

    pub fn save(&mut self) -> Result<()> {
        if !self.dirty {
            return Ok(());
        }
        let generation = self
            .generation
            .checked_add(1)
            .context("registry generation overflow")?;
        let snapshot = RegistrySnapshot {
            catalog_id: self.catalog_id,
            generation,
            mip_generation: self.mip_generation,
            blocks: self.blocks.clone(),
            biomes: self.biomes.clone(),
        };
        write_snapshot(&self.root.join("catalog.a"), &snapshot)?;
        write_snapshot(&self.root.join("catalog.b"), &snapshot)?;
        self.generation = generation;
        self.dirty = false;
        Ok(())
    }

    fn apply_production_classification(&mut self) -> Result<()> {
        let mut mip_changed = false;
        for entry in &mut self.blocks {
            let production = production_full_cube_opacity(&entry.canonical);
            let opacity = production.unwrap_or_else(|| estimated_opacity(&entry.canonical));
            let authoritative = production.is_some();
            mip_changed |= entry.opacity != opacity;
            if entry.opacity != opacity || entry.authoritative != authoritative {
                entry.opacity = opacity;
                entry.authoritative = authoritative;
                self.dirty = true;
            }
        }
        if mip_changed {
            self.mip_generation = self
                .mip_generation
                .checked_add(1)
                .context("production block classification generation overflow")?;
        }
        Ok(())
    }
}

fn validate_entries(blocks: &[BlockEntry], biomes: &[String]) -> Result<()> {
    if blocks.is_empty() || blocks[0].canonical != "minecraft:air" {
        bail!("block mapping zero must be minecraft:air");
    }
    if blocks.len() > MAX_BLOCKS || biomes.is_empty() || biomes.len() > MAX_BIOMES {
        bail!("registry entry counts are outside the configured limits");
    }
    if blocks.iter().any(|entry| {
        entry.opacity > 15 || entry.canonical.is_empty() || entry.canonical.len() > 4096
    }) || biomes
        .iter()
        .any(|entry| entry.is_empty() || entry.len() > 4096)
    {
        bail!("registry contains an invalid opacity or name");
    }
    let unique_blocks: std::collections::HashSet<_> = blocks.iter().map(|b| &b.canonical).collect();
    let unique_biomes: std::collections::HashSet<_> = biomes.iter().collect();
    if unique_blocks.len() != blocks.len() || unique_biomes.len() != biomes.len() {
        bail!("registry contains duplicate names");
    }
    Ok(())
}

fn estimated_opacity(canonical: &str) -> u8 {
    let name = canonical.split('[').next().unwrap_or(canonical);
    if matches!(
        name,
        "minecraft:air" | "minecraft:cave_air" | "minecraft:void_air"
    ) {
        return 0;
    }
    if name.contains("water") || name.contains("lava") {
        return 1;
    }
    // Voxy deliberately treats leaves as opaque when selecting a representative mip voxel.
    15
}

/// Conservative server-owned fast classification. Mod namespaces and vanilla shapes not proven
/// to be opaque full cubes remain non-authoritative and therefore use complex content.
pub(crate) fn production_full_cube_opacity(canonical: &str) -> Option<u8> {
    let name = canonical.split('[').next().unwrap_or(canonical);
    if matches!(
        name,
        "minecraft:air" | "minecraft:cave_air" | "minecraft:void_air"
    ) {
        return Some(0);
    }
    let path = name.strip_prefix("minecraft:")?;
    let exact = matches!(
        path,
        "stone"
            | "granite"
            | "diorite"
            | "andesite"
            | "cobblestone"
            | "bedrock"
            | "dirt"
            | "coarse_dirt"
            | "podzol"
            | "grass_block"
            | "mycelium"
            | "rooted_dirt"
            | "sand"
            | "red_sand"
            | "gravel"
            | "clay"
            | "bricks"
            | "netherrack"
            | "soul_sand"
            | "soul_soil"
            | "basalt"
            | "smooth_basalt"
            | "blackstone"
            | "end_stone"
            | "obsidian"
            | "crying_obsidian"
            | "tuff"
            | "calcite"
            | "deepslate"
            | "cobbled_deepslate"
    );
    let patterned = [
        "_ore",
        "_planks",
        "_log",
        "_wood",
        "_stem",
        "_hyphae",
        "_terracotta",
        "_concrete",
        "_concrete_powder",
        "_wool",
    ]
    .iter()
    .any(|suffix| path.ends_with(suffix))
        || (path.ends_with("_block")
            && !matches!(
                path,
                "glass_block" | "slime_block" | "honey_block" | "scaffolding_block"
            ));
    (exact || patterned).then_some(15)
}

fn encode_snapshot(snapshot: &RegistrySnapshot) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&snapshot.catalog_id.to_le_bytes());
    out.extend_from_slice(&snapshot.generation.to_le_bytes());
    out.extend_from_slice(&snapshot.mip_generation.to_le_bytes());
    out.extend_from_slice(&(snapshot.blocks.len() as u32).to_le_bytes());
    out.extend_from_slice(&(snapshot.biomes.len() as u32).to_le_bytes());
    for entry in &snapshot.blocks {
        let name = entry.canonical.as_bytes();
        if name.len() > u16::MAX as usize {
            bail!("block-state name is too long");
        }
        out.push(entry.opacity);
        out.push(u8::from(entry.authoritative));
        out.extend_from_slice(&(name.len() as u16).to_le_bytes());
        out.extend_from_slice(name);
    }
    for entry in &snapshot.biomes {
        let name = entry.as_bytes();
        if name.len() > u16::MAX as usize {
            bail!("biome name is too long");
        }
        out.extend_from_slice(&(name.len() as u16).to_le_bytes());
        out.extend_from_slice(name);
    }
    out.extend_from_slice(&crc32c(&out).to_le_bytes());
    Ok(out)
}

fn decode_snapshot(bytes: &[u8]) -> Result<RegistrySnapshot> {
    if bytes.len() < 44 || &bytes[..8] != MAGIC {
        bail!("bad registry header");
    }
    let stored_crc = u32::from_le_bytes(bytes[bytes.len() - 4..].try_into().unwrap());
    if crc32c(&bytes[..bytes.len() - 4]) != stored_crc {
        bail!("registry checksum mismatch");
    }
    let mut input = &bytes[8..bytes.len() - 4];
    let catalog_id = take_u64(&mut input)?;
    let generation = take_u64(&mut input)?;
    let mip_generation = take_u64(&mut input)?;
    let block_count = take_u32(&mut input)? as usize;
    let biome_count = take_u32(&mut input)? as usize;
    if catalog_id == 0 || block_count > MAX_BLOCKS || biome_count > MAX_BIOMES {
        bail!("registry identity or count is outside configured limits");
    }
    let mut blocks = Vec::with_capacity(block_count);
    for _ in 0..block_count {
        let opacity = take(&mut input, 1)?[0];
        let flags = take(&mut input, 1)?[0];
        if flags & !1 != 0 {
            bail!("unknown block registry flags");
        }
        let len = take_u16(&mut input)? as usize;
        let canonical = std::str::from_utf8(take(&mut input, len)?)?.to_owned();
        blocks.push(BlockEntry {
            canonical,
            opacity,
            authoritative: flags & 1 != 0,
        });
    }
    let mut biomes = Vec::with_capacity(biome_count);
    for _ in 0..biome_count {
        let len = take_u16(&mut input)? as usize;
        biomes.push(std::str::from_utf8(take(&mut input, len)?)?.to_owned());
    }
    if !input.is_empty() {
        bail!("trailing registry bytes");
    }
    validate_entries(&blocks, &biomes)?;
    Ok(RegistrySnapshot {
        catalog_id,
        generation,
        mip_generation,
        blocks,
        biomes,
    })
}

fn read_snapshot(path: &Path) -> Result<Option<RegistrySnapshot>> {
    let bytes = match read_file_bounded(path, MAX_SNAPSHOT_BYTES) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error).with_context(|| format!("open {}", path.display())),
    };
    decode_snapshot(&bytes)
        .with_context(|| format!("decode {}", path.display()))
        .map(Some)
}

fn write_snapshot(path: &Path, snapshot: &RegistrySnapshot) -> Result<()> {
    let bytes = encode_snapshot(snapshot)?;
    let tmp = path.with_extension("tmp");
    replace_synced(path, &tmp, &bytes)
}

fn new_catalog_id() -> u64 {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos() as u64;
    (nanos.rotate_left(17) ^ u64::from(std::process::id()) ^ 0x5658_5932_4341_5441).max(1)
}
