use super::{
    object::{ObjectHash, ObjectKind},
    pack::PackStore,
};
use crate::{
    crc::crc32c, quarantine, read_file_bounded, replace_synced, sync_parent, write_synced,
};
use anyhow::{Context, Result, bail};
use std::{
    fs,
    path::{Path, PathBuf},
};

const ROOT_MAGIC: &[u8; 8] = b"VXYROOT\0";
const ROOT_BYTES: usize = 180;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RootRecord {
    pub generation: u64,
    pub dimension: ObjectHash,
    pub root_manifest: ObjectHash,
    pub catalog: ObjectHash,
    pub dictionary_set: ObjectHash,
    pub visibility: ObjectHash,
}

impl RootRecord {
    pub fn new(
        generation: u64,
        dimension: ObjectHash,
        root_manifest: ObjectHash,
        catalog: ObjectHash,
        dictionary_set: ObjectHash,
        visibility: ObjectHash,
    ) -> Result<Self> {
        let record = Self {
            generation,
            dimension,
            root_manifest,
            catalog,
            dictionary_set,
            visibility,
        };
        record.validate()?;
        Ok(record)
    }

    pub fn validate(&self) -> Result<()> {
        if self.generation == 0 {
            bail!("root generation zero is reserved");
        }
        if self.dimension.is_zero()
            || self.root_manifest.is_zero()
            || self.catalog.is_zero()
            || self.dictionary_set.is_zero()
            || self.visibility.is_zero()
        {
            bail!("root contains a reserved zero required hash");
        }
        Ok(())
    }

    pub fn encode(&self) -> Result<[u8; ROOT_BYTES]> {
        self.validate()?;
        let mut bytes = [0u8; ROOT_BYTES];
        bytes[..8].copy_from_slice(ROOT_MAGIC);
        bytes[8..16].copy_from_slice(&self.generation.to_le_bytes());
        bytes[16..48].copy_from_slice(self.dimension.as_bytes());
        bytes[48..80].copy_from_slice(self.root_manifest.as_bytes());
        bytes[80..112].copy_from_slice(self.catalog.as_bytes());
        bytes[112..144].copy_from_slice(self.dictionary_set.as_bytes());
        bytes[144..176].copy_from_slice(self.visibility.as_bytes());
        let crc = crc32c(&bytes[..176]);
        bytes[176..180].copy_from_slice(&crc.to_le_bytes());
        Ok(bytes)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() != ROOT_BYTES
            || &bytes[..8] != ROOT_MAGIC
            || u32::from_le_bytes(bytes[176..180].try_into().unwrap()) != crc32c(&bytes[..176])
        {
            bail!("invalid root record envelope");
        }
        Self::new(
            u64::from_le_bytes(bytes[8..16].try_into().unwrap()),
            ObjectHash::from_bytes(bytes[16..48].try_into().unwrap())?,
            ObjectHash::from_bytes(bytes[48..80].try_into().unwrap())?,
            ObjectHash::from_bytes(bytes[80..112].try_into().unwrap())?,
            ObjectHash::from_bytes(bytes[112..144].try_into().unwrap())?,
            ObjectHash::from_bytes(bytes[144..176].try_into().unwrap())?,
        )
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PublishResult {
    Published,
    Unchanged,
}

/// Crash-safe authoritative root pointer for one dimension.
///
/// Canonical objects are synced first. The previous root is retained, then a fully synced
/// candidate atomically replaces the current pointer. Recovery never promotes an unrenamed
/// candidate, so a crash observes either the old complete generation or the new complete one.
#[derive(Debug)]
pub struct RootStore {
    root: PathBuf,
    dimension: ObjectHash,
    current: Option<RootRecord>,
    previous: Option<RootRecord>,
}

impl RootStore {
    pub fn open(root: impl AsRef<Path>, dimension: ObjectHash) -> Result<Self> {
        if dimension.is_zero() {
            bail!("root store dimension hash cannot be zero");
        }
        let root = root.as_ref().to_path_buf();
        fs::create_dir_all(&root).with_context(|| format!("create {}", root.display()))?;
        let current_path = root.join("root.current");
        let previous_path = root.join("root.previous");
        let candidate_path = root.join("root.next");
        let current = read_slot(&current_path, dimension)?;
        let previous = read_slot(&previous_path, dimension)?;
        let mut restored_previous = false;

        let chosen = match (current, previous) {
            (Some(current), Some(previous_record))
                if current.generation == previous_record.generation
                    && current != previous_record =>
            {
                quarantine(&previous_path);
                Some(current)
            }
            (Some(current), Some(previous_record))
                if previous_record.generation > current.generation =>
            {
                restored_previous = true;
                Some(previous_record)
            }
            (Some(current), _) => Some(current),
            (None, Some(previous_record)) => {
                restored_previous = true;
                Some(previous_record)
            }
            (None, None) => None,
        };

        if restored_previous {
            let bytes = chosen.expect("restored root exists").encode()?;
            replace_synced(&current_path, &root.join("root.restore"), &bytes)?;
        }
        if candidate_path.exists() {
            fs::remove_file(&candidate_path)?;
            sync_parent(&candidate_path)?;
        }
        // Retain a distinct older complete generation for client fallback and conservative GC
        // pinning. If recovery promoted the previous slot, it is now the current root and must
        // not be reported twice.
        let retained_previous =
            read_slot(&previous_path, dimension)?.filter(|record| Some(*record) != chosen);
        Ok(Self {
            root,
            dimension,
            current: chosen,
            previous: retained_previous,
        })
    }

    pub fn current(&self) -> Option<RootRecord> {
        self.current
    }

    pub fn previous(&self) -> Option<RootRecord> {
        self.previous
    }

    /// Restores a predecessor whose complete transitive graph was verified by the owning
    /// surface. RootStore itself can validate the direct root metadata objects, while only the
    /// the runtime understands the manifest/content formats needed for that stronger verification.
    pub(crate) fn restore_verified_previous(&mut self, record: RootRecord) -> Result<()> {
        if self.previous != Some(record) || record.dimension != self.dimension {
            bail!("cannot restore an unretained surface predecessor");
        }
        replace_synced(
            &self.root.join("root.current"),
            &self.root.join("root.restore"),
            &record.encode()?,
        )?;
        self.current = Some(record);
        self.previous = None;
        Ok(())
    }

    /// Clears root pointers only after the owning surface has rejected both transitive object
    /// graphs. Immutable pack objects remain available for canonical deduplication during the
    /// replacement build; no unverified generation can block the new generation clock.
    pub(crate) fn discard_unserviceable_graphs(&mut self) -> Result<()> {
        for path in [
            self.root.join("root.current"),
            self.root.join("root.previous"),
        ] {
            match fs::remove_file(&path) {
                Ok(()) => {}
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => {
                    return Err(error).with_context(|| {
                        format!("remove unusable root pointer {}", path.display())
                    });
                }
            }
        }
        sync_parent(&self.root.join("root.current"))?;
        self.current = None;
        self.previous = None;
        Ok(())
    }

    pub fn publish(&mut self, record: RootRecord, objects: &PackStore) -> Result<PublishResult> {
        record.validate()?;
        if record.dimension != self.dimension {
            bail!("root record belongs to a different dimension");
        }
        let unchanged = if let Some(current) = self.current {
            if record.generation < current.generation {
                bail!("root generation would move backward");
            }
            if record.generation == current.generation {
                if record == current {
                    true
                } else {
                    bail!("different roots cannot share one generation");
                }
            } else {
                false
            }
        } else {
            false
        };
        verify_root_references(objects, record)?;
        objects.sync_all()?;
        if unchanged {
            sync_parent(&self.root.join("root.current"))?;
            return Ok(PublishResult::Unchanged);
        }
        let retained_previous = self.current;
        if let Some(current) = retained_previous {
            let previous = self.root.join("root.previous");
            replace_synced(
                &previous,
                &self.root.join("root.previous.tmp"),
                &current.encode()?,
            )?;
        }
        let current_path = self.root.join("root.current");
        let candidate_path = self.root.join("root.next");
        write_synced(&candidate_path, &record.encode()?)?;
        fs::rename(&candidate_path, &current_path)?;
        self.current = Some(record);
        self.previous = retained_previous;
        sync_parent(&current_path)?;
        Ok(PublishResult::Published)
    }
}

fn verify_root_references(objects: &PackStore, record: RootRecord) -> Result<()> {
    verify_reference(
        objects,
        record.root_manifest,
        |kind| kind == ObjectKind::RootDirectory,
        "root manifest",
    )?;
    verify_reference(
        objects,
        record.catalog,
        |kind| kind == ObjectKind::Catalog,
        "catalog",
    )?;
    verify_reference(
        objects,
        record.dictionary_set,
        |kind| kind == ObjectKind::DictionarySet,
        "dictionary set",
    )?;
    verify_reference(
        objects,
        record.visibility,
        |kind| kind == ObjectKind::VisibilityDirectory,
        "visibility directory",
    )?;
    Ok(())
}

fn verify_reference(
    objects: &PackStore,
    hash: ObjectHash,
    expected: impl FnOnce(ObjectKind) -> bool,
    label: &str,
) -> Result<()> {
    let object = objects
        .get(hash)?
        .with_context(|| format!("{label} object {hash} is missing"))?;
    if !expected(object.kind()) {
        bail!("{label} object {hash} has the wrong type");
    }
    Ok(())
}

fn read_slot(path: &Path, dimension: ObjectHash) -> Result<Option<RootRecord>> {
    let bytes = match read_file_bounded(path, ROOT_BYTES) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => {
            quarantine(path);
            eprintln!(
                "discarding damaged surface root {}: {error}",
                path.display()
            );
            return Ok(None);
        }
    };
    match RootRecord::decode(&bytes) {
        Ok(record) if record.dimension == dimension => Ok(Some(record)),
        Ok(_) => {
            quarantine(path);
            Ok(None)
        }
        Err(error) => {
            quarantine(path);
            eprintln!(
                "discarding damaged surface root {}: {error:#}",
                path.display()
            );
            Ok(None)
        }
    }
}
