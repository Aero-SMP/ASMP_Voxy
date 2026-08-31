pub mod anvil;
pub mod config;
pub mod crc;
pub mod key;
pub mod lod;
pub mod protocol;
pub mod registry;
pub mod scanner;
pub mod server;
pub mod store;

pub const FORMAT_VERSION: u16 = 3;
pub const PROTOCOL_VERSION: u16 = 6;
pub const MAX_LOD: u8 = 4;

pub(crate) fn take<'a>(input: &mut &'a [u8], count: usize) -> anyhow::Result<&'a [u8]> {
    if input.len() < count {
        anyhow::bail!("truncated binary data");
    }
    let (head, tail) = input.split_at(count);
    *input = tail;
    Ok(head)
}

pub(crate) fn take_u16(input: &mut &[u8]) -> anyhow::Result<u16> {
    Ok(u16::from_le_bytes(take(input, 2)?.try_into().unwrap()))
}

pub(crate) fn take_u32(input: &mut &[u8]) -> anyhow::Result<u32> {
    Ok(u32::from_le_bytes(take(input, 4)?.try_into().unwrap()))
}

pub(crate) fn take_i32(input: &mut &[u8]) -> anyhow::Result<i32> {
    Ok(i32::from_le_bytes(take(input, 4)?.try_into().unwrap()))
}

pub(crate) fn take_u64(input: &mut &[u8]) -> anyhow::Result<u64> {
    Ok(u64::from_le_bytes(take(input, 8)?.try_into().unwrap()))
}

pub(crate) fn sync_parent(path: &std::path::Path) -> anyhow::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::File::open(parent)?.sync_all()?;
    }
    Ok(())
}

pub(crate) fn write_synced(path: &std::path::Path, bytes: &[u8]) -> anyhow::Result<()> {
    use std::io::Write;
    let mut file = std::fs::OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(path)?;
    file.write_all(bytes)?;
    file.sync_all()?;
    Ok(())
}

pub(crate) fn replace_synced(
    path: &std::path::Path,
    temporary: &std::path::Path,
    bytes: &[u8],
) -> anyhow::Result<()> {
    write_synced(temporary, bytes)?;
    std::fs::rename(temporary, path)?;
    sync_parent(path)
}

pub(crate) fn quarantine(path: &std::path::Path) {
    if !path.exists() {
        return;
    }
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("file");
    let target = path.with_file_name(format!("{name}.corrupt.{stamp}"));
    if let Err(error) = std::fs::rename(path, &target) {
        eprintln!("cannot quarantine {}: {error}", path.display());
    }
}

pub(crate) fn read_file_bounded(
    path: &std::path::Path,
    maximum: usize,
) -> std::io::Result<Vec<u8>> {
    use std::io::Read;
    let file = std::fs::File::open(path)?;
    let length = file.metadata()?.len();
    if length > maximum as u64 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            format!("{} exceeds its {} byte limit", path.display(), maximum),
        ));
    }
    let mut bytes = Vec::with_capacity(length as usize);
    file.take(maximum as u64 + 1).read_to_end(&mut bytes)?;
    if bytes.len() > maximum {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            format!("{} grew beyond its {} byte limit", path.display(), maximum),
        ));
    }
    Ok(bytes)
}
