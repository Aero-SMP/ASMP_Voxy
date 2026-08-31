pub fn crc32c(bytes: &[u8]) -> u32 {
    // This selects SSE4.2 at runtime on x86_64 and a slicing fallback elsewhere.
    crc32c::crc32c(bytes)
}

/// Dependency-free xxHash64 for stable, fast Anvil content fingerprints.
pub fn xxh64(mut bytes: &[u8], seed: u64) -> u64 {
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

    let length = bytes.len();
    let mut hash = if bytes.len() >= 32 {
        let mut v1 = seed.wrapping_add(P1).wrapping_add(P2);
        let mut v2 = seed.wrapping_add(P2);
        let mut v3 = seed;
        let mut v4 = seed.wrapping_sub(P1);
        while bytes.len() >= 32 {
            v1 = round(v1, word(bytes));
            v2 = round(v2, word(&bytes[8..]));
            v3 = round(v3, word(&bytes[16..]));
            v4 = round(v4, word(&bytes[24..]));
            bytes = &bytes[32..];
        }
        let mut hash = v1
            .rotate_left(1)
            .wrapping_add(v2.rotate_left(7))
            .wrapping_add(v3.rotate_left(12))
            .wrapping_add(v4.rotate_left(18));
        for value in [v1, v2, v3, v4] {
            hash ^= round(0, value);
            hash = hash.wrapping_mul(P1).wrapping_add(P4);
        }
        hash
    } else {
        seed.wrapping_add(P5)
    };
    hash = hash.wrapping_add(length as u64);
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn standard_vector() {
        assert_eq!(crc32c(b"123456789"), 0xe306_9283);
        assert_eq!(xxh64(b"", 0), 0xef46_db37_51d8_e999);
        assert_eq!(xxh64(b"a", 0), 0xd24e_c4f1_a98c_6e5b);
    }
}
