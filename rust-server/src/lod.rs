use crate::key::SectionKey;
use anyhow::{Result, bail};

pub const SECTION_EDGE: usize = 32;
pub const SECTION_VOLUME: usize = SECTION_EDGE * SECTION_EDGE * SECTION_EDGE;

#[derive(Clone, Copy, Debug, Default, Eq, Hash, PartialEq)]
pub struct Cell {
    pub block: u32,
    pub biome: u32,
    /// Block light in the high nibble and sky light in the low nibble.
    pub light: u8,
}

impl Cell {
    pub const AIR: Self = Self {
        block: 0,
        biome: 0,
        light: 0,
    };

    pub fn is_air(self) -> bool {
        self.block == 0
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Section {
    pub key: SectionKey,
    pub non_empty_children: u8,
    pub cells: Vec<Cell>,
}

impl Section {
    pub fn from_cells(key: SectionKey, cells: Vec<Cell>) -> Result<Self> {
        if cells.len() != SECTION_VOLUME {
            bail!(
                "section has {} cells instead of {SECTION_VOLUME}",
                cells.len()
            );
        }
        let non_empty_children = if cells.iter().any(|cell| !cell.is_air()) {
            0xff
        } else {
            0
        };
        Ok(Self {
            key,
            non_empty_children,
            cells,
        })
    }

    pub fn is_empty(&self) -> bool {
        self.non_empty_children == 0 || self.cells.iter().all(|cell| cell.is_air())
    }
}

pub fn mip(eight: [Cell; 8], opacity: &[u8]) -> Cell {
    let mut selected = None::<(u8, u8, Cell)>;
    // Codes exactly match Voxy Mipper: x is bit 2, y bit 1, z bit 0.
    for x in 0..2 {
        for y in 0..2 {
            for z in 0..2 {
                let code = ((x << 2) | (y << 1) | z) as u8;
                let cell = eight[child_index(x, y, z)];
                if !cell.is_air() {
                    let candidate = (
                        opacity.get(cell.block as usize).copied().unwrap_or(15),
                        code,
                        cell,
                    );
                    if selected.is_none_or(|old| (candidate.0, candidate.1) > (old.0, old.1)) {
                        selected = Some(candidate);
                    }
                }
            }
        }
    }
    if let Some((_, _, cell)) = selected {
        return cell;
    }

    let block_sum: u16 = eight.iter().map(|cell| u16::from(cell.light >> 4)).sum();
    let sky_sum: u16 = eight.iter().map(|cell| u16::from(cell.light & 15)).sum();
    let block = (block_sum / 8) as u8;
    let sky = sky_sum.div_ceil(8) as u8;
    let mut air = eight[child_index(1, 1, 1)];
    air.block = 0;
    air.biome = 0;
    air.light = (block << 4) | sky;
    air
}

pub fn build_parent(
    key: SectionKey,
    children: &[Option<Section>; 8],
    opacity: &[u8],
) -> Result<Section> {
    if key.level == 0 {
        bail!("a level-zero section has no Voxy child sections");
    }
    let mut non_empty_children = 0u8;
    for (index, child) in children.iter().enumerate() {
        if child.as_ref().is_some_and(|child| !child.is_empty()) {
            non_empty_children |= 1 << index;
        }
    }
    let mut cells = Vec::with_capacity(SECTION_VOLUME);
    for y in 0..SECTION_EDGE {
        for z in 0..SECTION_EDGE {
            for x in 0..SECTION_EDGE {
                let mut input = [Cell::AIR; 8];
                for dy in 0..2 {
                    for dz in 0..2 {
                        for dx in 0..2 {
                            let gx = x * 2 + dx;
                            let gy = y * 2 + dy;
                            let gz = z * 2 + dz;
                            let child_slot = child_index(
                                gx / SECTION_EDGE,
                                gy / SECTION_EDGE,
                                gz / SECTION_EDGE,
                            );
                            if let Some(child) = &children[child_slot] {
                                input[child_index(dx, dy, dz)] = child.cells[cell_index(
                                    gx % SECTION_EDGE,
                                    gy % SECTION_EDGE,
                                    gz % SECTION_EDGE,
                                )];
                            }
                        }
                    }
                }
                cells.push(mip(input, opacity));
            }
        }
    }
    Ok(Section {
        key,
        non_empty_children,
        cells,
    })
}

pub const fn cell_index(x: usize, y: usize, z: usize) -> usize {
    x | (z << 5) | (y << 10)
}

pub const fn child_index(x: usize, y: usize, z: usize) -> usize {
    (x & 1) | ((z & 1) << 1) | ((y & 1) << 2)
}
