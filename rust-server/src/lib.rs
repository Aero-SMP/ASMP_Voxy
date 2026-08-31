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
