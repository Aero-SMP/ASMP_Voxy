//! Deterministic, class-scoped Zstd dictionaries for microtiles.

use super::{
    content::{MICROTILE_EDGE, content_kind},
    manifest::ContentClass,
    object::{CanonicalObject, ObjectKind},
};
use anyhow::{Context, Result, bail};
use std::borrow::Borrow;

const MAGIC: &[u8; 8] = b"VXYDICT\0";
const HEADER_BYTES: usize = 14;
const TRAINED_BYTES: usize = 8 * 1024;
const MAX_DICTIONARY_BYTES: usize = 64 * 1024;
const MAX_CORPUS_BYTES: usize = 4 * 1024 * 1024;
const MAX_SAMPLES: usize = 2_048;
const MIN_TRAINING_BYTES: usize = 1024 * 1024;
const ZSTD_DICTIONARY_MAGIC: [u8; 4] = [0x37, 0xa4, 0x30, 0xec];

#[derive(Clone, Copy, Debug)]
pub struct CompressionDictionary<'a> {
    pub class: ContentClass,
    pub zstd: &'a [u8],
}

/// Trains one deterministic dictionary from a bounded, hash-ordered class corpus.
///
/// Seed records make the format defined even before a world contains a member of one class;
/// actual canonical objects replace seeds within the fixed corpus bound whenever available.
pub fn train<I, T>(class: ContentClass, objects: I) -> Result<CanonicalObject>
where
    I: IntoIterator<Item = T>,
    T: Borrow<CanonicalObject>,
{
    let expected = content_kind(class);
    let mut actual = objects
        .into_iter()
        .map(|object| {
            let object = object.borrow();
            if object.kind() != expected {
                bail!("dictionary corpus contains an object from another content class");
            }
            Ok((object.hash(), object.bytes().to_vec()))
        })
        .collect::<Result<Vec<_>>>()?;
    actual.sort_unstable_by_key(|entry| entry.0);

    let mut samples = Vec::<Vec<u8>>::new();
    let mut corpus_bytes = 0usize;
    for (_, bytes) in actual {
        if samples.len() == MAX_SAMPLES || corpus_bytes + bytes.len() > MAX_CORPUS_BYTES {
            break;
        }
        corpus_bytes += bytes.len();
        samples.push(bytes);
    }
    let mut seed = 0u32;
    while samples.len() < MAX_SAMPLES && corpus_bytes < MIN_TRAINING_BYTES {
        let value = seed_sample(class, seed);
        if corpus_bytes + value.len() > MAX_CORPUS_BYTES {
            break;
        }
        corpus_bytes += value.len();
        samples.push(value);
        seed = seed.wrapping_add(1);
    }
    if samples.len() < 8 || corpus_bytes < TRAINED_BYTES * 8 {
        bail!("insufficient bounded corpus for a compression dictionary");
    }
    let trained = zstd::dict::from_samples(&samples, TRAINED_BYTES)
        .context("train Zstd dictionary")?;
    if trained.len() > MAX_DICTIONARY_BYTES || !trained.starts_with(&ZSTD_DICTIONARY_MAGIC) {
        bail!("Zstd produced an invalid or oversized trained dictionary");
    }

    let mut canonical = Vec::with_capacity(HEADER_BYTES + trained.len());
    canonical.extend_from_slice(MAGIC);
    canonical.push(class as u8);
    canonical.push(MICROTILE_EDGE as u8);
    canonical.extend_from_slice(&(trained.len() as u32).to_le_bytes());
    canonical.extend_from_slice(&trained);
    CanonicalObject::new(ObjectKind::CompressionDictionary, canonical)
}

/// Decodes the canonical type envelope and exposes exactly the bytes supplied to Zstd.
pub fn decode(bytes: &[u8]) -> Result<CompressionDictionary<'_>> {
    if bytes.len() < HEADER_BYTES || &bytes[..8] != MAGIC {
        bail!("truncated or invalid compression dictionary");
    }
    if bytes[9] != MICROTILE_EDGE as u8 {
        bail!("invalid compression dictionary edge");
    }
    let class = ContentClass::try_from(bytes[8])?;
    let length = u32::from_le_bytes(bytes[10..14].try_into().unwrap()) as usize;
    if length == 0
        || length > MAX_DICTIONARY_BYTES
        || bytes.len() != HEADER_BYTES + length
        || !bytes[HEADER_BYTES..].starts_with(&ZSTD_DICTIONARY_MAGIC)
    {
        bail!("invalid trained dictionary payload");
    }
    Ok(CompressionDictionary {
        class,
        zstd: &bytes[HEADER_BYTES..],
    })
}

fn seed_sample(class: ContentClass, seed: u32) -> Vec<u8> {
    // It follows the stable microtile envelope shape, then supplies structured palette/index
    // variation. Training data need not itself be a published object.
    let palette = 1 + (seed as usize % 8);
    let bits = if palette == 1 {
        0
    } else {
        usize::BITS as usize - (palette - 1).leading_zeros() as usize
    };
    let words = (512usize * bits).div_ceil(64);
    let mut value = Vec::with_capacity(40 + palette * 12 + words * 8);
    value.extend_from_slice(b"VXYTILE\0");
    value.extend_from_slice(&(1u64 + seed as u64 / 16).to_le_bytes());
    value.push(class as u8);
    value.push(MICROTILE_EDGE as u8);
    value.extend_from_slice(&[
        ((seed >> 0) & 3) as u8 * 8,
        ((seed >> 2) & 3) as u8 * 8,
        ((seed >> 4) & 3) as u8 * 8,
    ]);
    value.extend_from_slice(&512u32.to_le_bytes());
    value.extend_from_slice(&(palette as u16).to_le_bytes());
    value.push(bits as u8);
    value.extend_from_slice(&(words as u32).to_le_bytes());
    for index in 0..palette as u32 {
        value.extend_from_slice(&(1 + ((seed + index * 17) & 0x3ff)).to_le_bytes());
        value.extend_from_slice(&((seed / 7 + index) & 0x1ff).to_le_bytes());
        value.push((seed.wrapping_mul(13) as u8).wrapping_add(index as u8));
    }
    for word in 0..words as u64 {
        let pattern = word
            .wrapping_mul(0x9e37_79b9_7f4a_7c15)
            .rotate_left(seed & 63)
            ^ (seed as u64).wrapping_mul(0x1000_0000_01b3);
        value.extend_from_slice(&pattern.to_le_bytes());
    }
    value
}
