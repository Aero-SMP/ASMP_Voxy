use anyhow::{Result, bail};
use std::fmt;

const OBJECT_DOMAIN: &[u8] = b"Voxy canonical object\0";
const DIMENSION_DOMAIN: &[u8] = b"Voxy dimension identity\0";

/// A full BLAKE3-256 content identity.
///
/// The all-zero value is reserved for optional references and can never be produced by the
/// constructors in this module.
#[derive(Clone, Copy, Default, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct ObjectHash([u8; 32]);

impl ObjectHash {
    pub const ZERO: Self = Self([0; 32]);

    pub fn from_bytes(bytes: [u8; 32]) -> Result<Self> {
        let hash = Self(bytes);
        if hash.is_zero() {
            bail!("the all-zero object hash is reserved");
        }
        Ok(hash)
    }

    pub(crate) const fn from_stored_bytes(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }

    pub const fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }

    pub fn is_zero(self) -> bool {
        self.0 == [0; 32]
    }

    pub fn dimension(name: &str) -> Result<Self> {
        if name.is_empty() || name.len() > 1024 {
            bail!("dimension name must contain 1..=1024 UTF-8 bytes");
        }
        Ok(domain_hash(DIMENSION_DOMAIN, name.as_bytes()))
    }
}

impl fmt::Debug for ObjectHash {
    fn fmt(&self, output: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Display::fmt(self, output)
    }
}

impl fmt::Display for ObjectHash {
    fn fmt(&self, output: &mut fmt::Formatter<'_>) -> fmt::Result {
        for byte in self.0 {
            write!(output, "{byte:02x}")?;
        }
        Ok(())
    }
}

/// The logical type is part of an object's identity. Equal bytes used for two different
/// purposes therefore cannot alias one another accidentally.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
#[repr(u8)]
pub enum ObjectKind {
    ExteriorMicrotile = 1,
    InteriorMicrotile = 2,
    ComplexMicrotile = 3,
    ManifestSubtree = 4,
    RootDirectory = 5,
    Catalog = 6,
    CompressionDictionary = 7,
    DictionarySet = 8,
    VisibilityDirectory = 9,
    VisibilityPage = 10,
    VisibilitySummaryPage = 11,
    /// Exact normalized 8³ build input. This is root-reachable internal publication state;
    /// it is never advertised as renderable client content.
    SourceMicrotile = 12,
    /// Bounded descriptor payload for 64 structural Morton slots in one five-level manifest.
    ManifestDescriptorPage = 13,
}

impl TryFrom<u8> for ObjectKind {
    type Error = anyhow::Error;

    fn try_from(value: u8) -> Result<Self> {
        Ok(match value {
            1 => Self::ExteriorMicrotile,
            2 => Self::InteriorMicrotile,
            3 => Self::ComplexMicrotile,
            4 => Self::ManifestSubtree,
            5 => Self::RootDirectory,
            6 => Self::Catalog,
            7 => Self::CompressionDictionary,
            8 => Self::DictionarySet,
            9 => Self::VisibilityDirectory,
            10 => Self::VisibilityPage,
            11 => Self::VisibilitySummaryPage,
            12 => Self::SourceMicrotile,
            13 => Self::ManifestDescriptorPage,
            _ => bail!("unknown object kind {value}"),
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CanonicalObject {
    kind: ObjectKind,
    hash: ObjectHash,
    bytes: Vec<u8>,
}

impl CanonicalObject {
    pub fn new(kind: ObjectKind, bytes: Vec<u8>) -> Result<Self> {
        if bytes.len() > u32::MAX as usize {
            bail!("canonical object is too large");
        }
        let hash = hash_object(kind, &bytes);
        Ok(Self { kind, hash, bytes })
    }

    pub fn kind(&self) -> ObjectKind {
        self.kind
    }

    pub fn hash(&self) -> ObjectHash {
        self.hash
    }

    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }

    /// Exact retained heap capacity used by the process-wide memory budget.
    pub(crate) fn byte_capacity(&self) -> usize {
        self.bytes.capacity()
    }

    pub fn verify(&self) -> bool {
        self.hash == hash_object(self.kind, &self.bytes)
    }
}

impl AsRef<CanonicalObject> for CanonicalObject {
    fn as_ref(&self) -> &CanonicalObject {
        self
    }
}

pub(crate) fn hash_object(kind: ObjectKind, bytes: &[u8]) -> ObjectHash {
    let mut hasher = blake3::Hasher::new();
    hasher.update(OBJECT_DOMAIN);
    hasher.update(&[kind as u8]);
    hasher.update(&(bytes.len() as u64).to_le_bytes());
    if bytes.len() >= 128 * 1024 {
        hasher.update_rayon(bytes);
    } else {
        hasher.update(bytes);
    }
    nonzero(*hasher.finalize().as_bytes())
}

fn domain_hash(domain: &[u8], bytes: &[u8]) -> ObjectHash {
    let mut hasher = blake3::Hasher::new();
    hasher.update(domain);
    hasher.update(&(bytes.len() as u64).to_le_bytes());
    hasher.update(bytes);
    nonzero(*hasher.finalize().as_bytes())
}

fn nonzero(mut bytes: [u8; 32]) -> ObjectHash {
    // BLAKE3 producing zero is already cryptographically negligible, but reserving ZERO is a
    // format invariant rather than a probability. Rehash deterministically if it ever occurs.
    if bytes == [0; 32] {
        bytes = *blake3::hash(b"Voxy nonzero hash escape").as_bytes();
    }
    ObjectHash(bytes)
}
