use super::{
    memory::MemoryPressure,
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

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct RootRecovery {
    pub damaged_current: bool,
    pub damaged_previous: bool,
    pub conflicting_roots: bool,
    pub restored_previous: bool,
    pub discarded_candidate: bool,
    pub referenced_object_loss: bool,
    pub needs_regeneration: bool,
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
    recovery: RootRecovery,
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
        let mut recovery = RootRecovery::default();
        let current = read_slot(&current_path, dimension, &mut recovery.damaged_current)?;
        let previous = read_slot(&previous_path, dimension, &mut recovery.damaged_previous)?;

        let chosen = match (current, previous) {
            (Some(current), Some(previous_record))
                if current.generation == previous_record.generation
                    && current != previous_record =>
            {
                recovery.conflicting_roots = true;
                quarantine(&previous_path);
                Some(current)
            }
            (Some(current), Some(previous_record))
                if previous_record.generation > current.generation =>
            {
                recovery.restored_previous = true;
                Some(previous_record)
            }
            (Some(current), _) => Some(current),
            (None, Some(previous_record)) => {
                recovery.restored_previous = true;
                Some(previous_record)
            }
            (None, None) => None,
        };

        if recovery.restored_previous {
            let bytes = chosen.expect("restored root exists").encode()?;
            replace_synced(&current_path, &root.join("root.restore"), &bytes)?;
        }
        if candidate_path.exists() {
            fs::remove_file(&candidate_path)?;
            sync_parent(&candidate_path)?;
            recovery.discarded_candidate = true;
        }
        // Retain a distinct older complete generation for client fallback and conservative GC
        // pinning. If recovery promoted the previous slot, it is now the current root and must
        // not be reported twice.
        let mut previous_damaged = false;
        let retained_previous = read_slot(&previous_path, dimension, &mut previous_damaged)?
            .filter(|record| Some(*record) != chosen);
        recovery.damaged_previous |= previous_damaged;
        recovery.needs_regeneration = chosen.is_none();
        Ok(Self {
            root,
            dimension,
            current: chosen,
            previous: retained_previous,
            recovery,
        })
    }

    pub fn open_verified(
        root: impl AsRef<Path>,
        dimension: ObjectHash,
        objects: &PackStore,
    ) -> Result<Self> {
        let mut store = Self::open(root, dimension)?;
        store.recover_references(objects)?;
        Ok(store)
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
        self.recovery.referenced_object_loss = true;
        self.recovery.restored_previous = true;
        self.recovery.needs_regeneration = false;
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
        self.recovery.referenced_object_loss = true;
        self.recovery.needs_regeneration = true;
        Ok(())
    }

    pub fn recovery(&self) -> &RootRecovery {
        &self.recovery
    }

    /// Verifies that the active root's required immutable objects still exist and match their
    /// hashes. If not, the previous complete root is restored; if neither generation is
    /// complete, callers receive an explicit regeneration state.
    pub fn recover_references(&mut self, objects: &PackStore) -> Result<()> {
        let Some(current) = self.current else {
            self.recovery.needs_regeneration = true;
            return Ok(());
        };
        match verify_root_references(objects, current) {
            Ok(()) => {
                if let Some(previous) = self.previous {
                    match verify_root_references(objects, previous) {
                        Ok(()) => {}
                        Err(error) if is_memory_pressure(&error) => return Err(error),
                        Err(_) => {
                            let previous_path = self.root.join("root.previous");
                            quarantine(&previous_path);
                            self.previous = None;
                            self.recovery.referenced_object_loss = true;
                        }
                    }
                }
                return Ok(());
            }
            Err(error) if is_memory_pressure(&error) => return Err(error),
            Err(_) => {}
        }
        self.recovery.referenced_object_loss = true;
        let current_path = self.root.join("root.current");
        quarantine(&current_path);

        let previous_path = self.root.join("root.previous");
        let mut damaged_previous = false;
        let previous = read_slot(&previous_path, self.dimension, &mut damaged_previous)?;
        self.recovery.damaged_previous |= damaged_previous;
        if let Some(previous) = previous {
            match verify_root_references(objects, previous) {
                Ok(()) => {
                    replace_synced(
                        &current_path,
                        &self.root.join("root.restore"),
                        &previous.encode()?,
                    )?;
                    self.current = Some(previous);
                    self.previous = None;
                    self.recovery.restored_previous = true;
                    self.recovery.needs_regeneration = false;
                    return Ok(());
                }
                Err(error) if is_memory_pressure(&error) => return Err(error),
                Err(_) => {}
            }
        }
        if previous_path.exists() {
            quarantine(&previous_path);
        }
        self.current = None;
        self.previous = None;
        self.recovery.needs_regeneration = true;
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
        self.recovery.needs_regeneration = false;
        sync_parent(&current_path)?;
        self.recovery.needs_regeneration = false;
        Ok(PublishResult::Published)
    }
}

fn is_memory_pressure(error: &anyhow::Error) -> bool {
    error.chain().any(|cause| cause.is::<MemoryPressure>())
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

fn read_slot(path: &Path, dimension: ObjectHash, damaged: &mut bool) -> Result<Option<RootRecord>> {
    let bytes = match read_file_bounded(path, ROOT_BYTES) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => {
            *damaged = true;
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
            *damaged = true;
            quarantine(path);
            Ok(None)
        }
        Err(error) => {
            *damaged = true;
            quarantine(path);
            eprintln!(
                "discarding damaged surface root {}: {error:#}",
                path.display()
            );
            Ok(None)
        }
    }
}
