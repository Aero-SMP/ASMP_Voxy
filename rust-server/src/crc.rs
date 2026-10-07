pub fn crc32c(bytes: &[u8]) -> u32 {
    // This selects SSE4.2 at runtime on x86_64 and a slicing fallback elsewhere.
    crc32c::crc32c(bytes)
}

const P1: u64 = 11_400_714_785_074_694_791;
const P2: u64 = 14_029_467_366_897_019_727;
const P3: u64 = 1_609_587_929_392_839_161;
const P4: u64 = 9_650_029_242_287_828_579;
const P5: u64 = 2_870_177_450_012_600_261;

fn round(mut value: u64, input: u64) -> u64 {
    value = value.wrapping_add(input.wrapping_mul(P2));
    value.rotate_left(31).wrapping_mul(P1)
}

fn word(bytes: &[u8]) -> u64 {
    u64::from_le_bytes(bytes[..8].try_into().unwrap())
}

/// Incremental xxHash64. The only retained input is its unfinished 32-byte block.
pub struct Xxh64 {
    seed: u64,
    length: u64,
    lanes: [u64; 4],
    tail: [u8; 32],
    used: usize,
}

impl Xxh64 {
    pub fn new(seed: u64) -> Self {
        Self {
            seed,
            length: 0,
            lanes: [
                seed.wrapping_add(P1).wrapping_add(P2),
                seed.wrapping_add(P2),
                seed,
                seed.wrapping_sub(P1),
            ],
            tail: [0; 32],
            used: 0,
        }
    }

    pub fn update(&mut self, mut bytes: &[u8]) {
        self.length = self.length.wrapping_add(bytes.len() as u64);
        if self.used != 0 {
            let count = bytes.len().min(32 - self.used);
            self.tail[self.used..self.used + count].copy_from_slice(&bytes[..count]);
            self.used += count;
            bytes = &bytes[count..];
            if self.used != 32 {
                return;
            }
            Self::block(&mut self.lanes, &self.tail);
            self.used = 0;
        }
        while bytes.len() >= 32 {
            Self::block(&mut self.lanes, bytes);
            bytes = &bytes[32..];
        }
        self.tail[..bytes.len()].copy_from_slice(bytes);
        self.used = bytes.len();
    }

    fn block(lanes: &mut [u64; 4], bytes: &[u8]) {
        for (lane, input) in lanes.iter_mut().zip(bytes.chunks_exact(8)) {
            *lane = round(*lane, word(input));
        }
    }

    pub fn finish(&self) -> u64 {
        let mut hash = if self.length >= 32 {
            let mut hash = self.lanes[0]
                .rotate_left(1)
                .wrapping_add(self.lanes[1].rotate_left(7))
                .wrapping_add(self.lanes[2].rotate_left(12))
                .wrapping_add(self.lanes[3].rotate_left(18));
            for value in self.lanes {
                hash ^= round(0, value);
                hash = hash.wrapping_mul(P1).wrapping_add(P4);
            }
            hash
        } else {
            self.seed.wrapping_add(P5)
        };
        hash = hash.wrapping_add(self.length);
        let mut bytes = &self.tail[..self.used];
        while bytes.len() >= 8 {
            hash ^= round(0, word(bytes));
            hash = hash.rotate_left(27).wrapping_mul(P1).wrapping_add(P4);
            bytes = &bytes[8..];
        }
        if bytes.len() >= 4 {
            hash ^= u64::from(u32::from_le_bytes(bytes[..4].try_into().unwrap())).wrapping_mul(P1);
            hash = hash.rotate_left(23).wrapping_mul(P2).wrapping_add(P3);
            bytes = &bytes[4..];
        }
        for &byte in bytes {
            hash ^= u64::from(byte).wrapping_mul(P5);
            hash = hash.rotate_left(11).wrapping_mul(P1);
        }
        hash ^= hash >> 33;
        hash = hash.wrapping_mul(P2);
        hash ^= hash >> 29;
        hash = hash.wrapping_mul(P3);
        hash ^ (hash >> 32)
    }
}

/// One-shot convenience uses the same independently validated incremental algorithm.
pub fn xxh64(bytes: &[u8], seed: u64) -> u64 {
    let mut hash = Xxh64::new(seed);
    hash.update(bytes);
    hash.finish()
}
