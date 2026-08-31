use anyhow::{Result, bail};

pub const COORD_MIN: i32 = -(1 << 23);
pub const COORD_MAX: i32 = (1 << 23) - 1;

#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct SectionKey {
    pub level: u8,
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct ShardId {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

impl SectionKey {
    pub fn new(level: u8, x: i32, y: i32, z: i32) -> Result<Self> {
        if level > 4 {
            bail!("LOD level {level} is outside 0..=4");
        }
        if !(COORD_MIN..=COORD_MAX).contains(&x) || !(COORD_MIN..=COORD_MAX).contains(&z) {
            bail!("section coordinate ({x}, {z}) exceeds Voxy's signed 24-bit range");
        }
        if !(-128..=127).contains(&y) {
            bail!("section y {y} exceeds Voxy's signed 8-bit range");
        }
        Ok(Self { level, x, y, z })
    }

    pub fn packed(self) -> u64 {
        ((self.level as u64) << 60)
            | (((self.y as u64) & 0xff) << 52)
            | (((self.z as u64) & 0x00ff_ffff) << 28)
            | (((self.x as u64) & 0x00ff_ffff) << 4)
    }

    pub fn unpack(packed: u64) -> Result<Self> {
        if packed & 0xf != 0 {
            bail!("section key has nonzero reserved low bits");
        }
        let level = ((packed >> 60) & 0xf) as u8;
        let x = sign_extend((packed >> 4) & 0x00ff_ffff, 24);
        let y = sign_extend((packed >> 52) & 0xff, 8);
        let z = sign_extend((packed >> 28) & 0x00ff_ffff, 24);
        Self::new(level, x, y, z)
    }

    pub fn parent(self) -> Option<Self> {
        (self.level < 4).then(|| Self {
            level: self.level + 1,
            x: self.x.div_euclid(2),
            y: self.y.div_euclid(2),
            z: self.z.div_euclid(2),
        })
    }

    pub fn shard(self) -> ShardId {
        let shift = 4 - self.level;
        ShardId {
            x: self.x.div_euclid(1 << shift),
            y: self.y.div_euclid(1 << shift),
            z: self.z.div_euclid(1 << shift),
        }
    }
}

impl ShardId {
    pub fn filename(self) -> String {
        format!("{}_{}_{}.vxlog", self.x, self.y, self.z)
    }
}

fn sign_extend(value: u64, bits: u8) -> i32 {
    ((value << (64 - bits)) as i64 >> (64 - bits)) as i32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn key_packing_matches_java_layout() {
        for key in [
            SectionKey::new(0, 0, 0, 0).unwrap(),
            SectionKey::new(4, -1, -4, 8).unwrap(),
            SectionKey::new(2, COORD_MIN, 127, COORD_MAX).unwrap(),
        ] {
            assert_eq!(SectionKey::unpack(key.packed()).unwrap(), key);
        }
        assert_eq!(
            SectionKey::new(0, -1, -1, -1).unwrap().packed(),
            0x0fff_ffff_ffff_fff0
        );
        assert!(SectionKey::unpack(1).is_err());
    }

    #[test]
    fn parent_and_shard_use_floor_division() {
        let key = SectionKey::new(0, -1, -3, 31).unwrap();
        assert_eq!(
            key.parent().unwrap(),
            SectionKey::new(1, -1, -2, 15).unwrap()
        );
        assert_eq!(key.shard(), ShardId { x: -1, y: -1, z: 1 });
    }
}
