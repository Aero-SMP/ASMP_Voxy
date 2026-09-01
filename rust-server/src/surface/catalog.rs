//! Canonical block/biome catalog referenced by every published surface root.

use super::object::{CanonicalObject, ObjectKind};
use crate::{
    registry::{MAX_BIOMES, MAX_BLOCKS, RegistrySnapshot, production_full_cube_opacity},
    take, take_u16, take_u32, take_u64,
};
use anyhow::{Context, Result, bail};

const MAGIC: &[u8; 8] = b"VXYCAT\0\0";
pub const MAX_CATALOG_BYTES: usize = 64 * 1024 * 1024;
const MAX_NAME_BYTES: usize = 4096;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CatalogBlock {
    pub canonical: String,
    pub opacity: u8,
    pub authoritative: bool,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Catalog {
    pub catalog_id: u64,
    pub generation: u64,
    pub mip_generation: u64,
    pub blocks: Vec<CatalogBlock>,
    pub biomes: Vec<String>,
}

impl Catalog {
    pub fn from_snapshot(snapshot: &RegistrySnapshot) -> Result<Self> {
        let catalog = Self {
            catalog_id: snapshot.catalog_id,
            generation: snapshot.generation,
            mip_generation: snapshot.mip_generation,
            blocks: snapshot
                .blocks
                .iter()
                .map(|block| CatalogBlock {
                    canonical: block.canonical.clone(),
                    opacity: block.opacity,
                    authoritative: block.authoritative,
                })
                .collect(),
            biomes: snapshot.biomes.clone(),
        };
        catalog.validate()?;
        Ok(catalog)
    }

    pub fn validate(&self) -> Result<()> {
        if self.catalog_id == 0 {
            bail!("catalog identity zero is reserved");
        }
        if self.blocks.is_empty()
            || self.blocks.len() > MAX_BLOCKS
            || self.biomes.is_empty()
            || self.biomes.len() > MAX_BIOMES
        {
            bail!("catalog entry counts are outside configured limits");
        }
        if self.blocks[0].canonical != "minecraft:air"
            || self.blocks[0].opacity != 0
            || !self.blocks[0].authoritative
        {
            bail!("catalog block zero must be authoritative minecraft:air");
        }
        let mut block_names = std::collections::HashSet::with_capacity(self.blocks.len());
        for block in &self.blocks {
            validate_name(&block.canonical, "block state")?;
            let production = production_full_cube_opacity(&block.canonical);
            if block.opacity > 15
                || block.authoritative != production.is_some()
                || production.is_some_and(|opacity| opacity != block.opacity)
            {
                bail!("catalog block classification is not the server-owned production value");
            }
            if !block_names.insert(&block.canonical) {
                bail!("catalog contains duplicate block-state names");
            }
        }
        let mut biome_names = std::collections::HashSet::with_capacity(self.biomes.len());
        for biome in &self.biomes {
            validate_name(biome, "biome")?;
            if !biome_names.insert(biome) {
                bail!("catalog contains duplicate biome names");
            }
        }
        Ok(())
    }

    pub fn encode(&self) -> Result<Vec<u8>> {
        self.validate()?;
        let mut output = Vec::new();
        output.extend_from_slice(MAGIC);
        output.extend_from_slice(&self.catalog_id.to_le_bytes());
        output.extend_from_slice(&self.generation.to_le_bytes());
        output.extend_from_slice(&self.mip_generation.to_le_bytes());
        output.extend_from_slice(&(self.blocks.len() as u32).to_le_bytes());
        output.extend_from_slice(&(self.biomes.len() as u32).to_le_bytes());
        for block in &self.blocks {
            let name = block.canonical.as_bytes();
            output.push(block.opacity);
            output.push(u8::from(block.authoritative));
            output.extend_from_slice(&(name.len() as u16).to_le_bytes());
            output.extend_from_slice(name);
        }
        for biome in &self.biomes {
            let name = biome.as_bytes();
            output.extend_from_slice(&(name.len() as u16).to_le_bytes());
            output.extend_from_slice(name);
        }
        if output.len() > MAX_CATALOG_BYTES {
            bail!("canonical catalog exceeds {MAX_CATALOG_BYTES} bytes");
        }
        Ok(output)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() < 40 || bytes.len() > MAX_CATALOG_BYTES {
            bail!("canonical catalog is truncated or oversized");
        }
        let mut input = bytes;
        if take(&mut input, 8)? != MAGIC {
            bail!("invalid canonical catalog envelope");
        }
        let catalog_id = take_u64(&mut input)?;
        let generation = take_u64(&mut input)?;
        let mip_generation = take_u64(&mut input)?;
        let block_count = take_u32(&mut input)? as usize;
        let biome_count = take_u32(&mut input)? as usize;
        if block_count == 0
            || block_count > MAX_BLOCKS
            || biome_count == 0
            || biome_count > MAX_BIOMES
        {
            bail!("canonical catalog entry counts are outside configured limits");
        }
        // Four bytes is the minimum block entry, two bytes the minimum biome entry. Validate
        // before reserving attacker-controlled counts.
        if input.len() < block_count.saturating_mul(4) + biome_count.saturating_mul(2) {
            bail!("canonical catalog counts cannot fit in its payload");
        }
        let mut blocks = Vec::with_capacity(block_count);
        for _ in 0..block_count {
            let opacity = take(&mut input, 1)?[0];
            let flags = take(&mut input, 1)?[0];
            if flags & !1 != 0 {
                bail!("unknown catalog block flags");
            }
            let canonical = take_name(&mut input, "block state")?;
            blocks.push(CatalogBlock {
                canonical,
                opacity,
                authoritative: flags & 1 != 0,
            });
        }
        let mut biomes = Vec::with_capacity(biome_count);
        for _ in 0..biome_count {
            biomes.push(take_name(&mut input, "biome")?);
        }
        if !input.is_empty() {
            bail!("trailing canonical catalog bytes");
        }
        let catalog = Self {
            catalog_id,
            generation,
            mip_generation,
            blocks,
            biomes,
        };
        catalog.validate()?;
        Ok(catalog)
    }

    pub fn canonical_object(&self) -> Result<CanonicalObject> {
        CanonicalObject::new(ObjectKind::Catalog, self.encode()?)
    }
}

fn take_name(input: &mut &[u8], kind: &str) -> Result<String> {
    let length = take_u16(input)? as usize;
    if length == 0 || length > MAX_NAME_BYTES {
        bail!("canonical {kind} name length is out of bounds");
    }
    std::str::from_utf8(take(input, length)?)
        .with_context(|| format!("canonical {kind} name is not UTF-8"))
        .map(ToOwned::to_owned)
}

fn validate_name(value: &str, kind: &str) -> Result<()> {
    if value.is_empty() || value.len() > MAX_NAME_BYTES {
        bail!("canonical {kind} name must contain 1..={MAX_NAME_BYTES} UTF-8 bytes");
    }
    Ok(())
}
