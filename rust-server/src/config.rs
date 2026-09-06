use anyhow::{Context, Result, bail};
use serde::Deserialize;
use std::{env, net::SocketAddr, path::PathBuf, time::Duration};

#[derive(Clone, Debug)]
pub struct Config {
    pub world: PathBuf,
    pub data: PathBuf,
    pub listen: SocketAddr,
    pub dimension: String,
    pub poll_interval: Duration,
    pub rayon_threads: usize,
    pub once: bool,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct FileConfig {
    world: PathBuf,
    data: PathBuf,
    #[serde(default = "default_dimension")]
    dimension: String,
    #[serde(default = "default_poll_ms")]
    poll_ms: u64,
    #[serde(default)]
    rayon_threads: usize,
    #[serde(default)]
    once: bool,
    #[serde(default)]
    quic: QuicConfig,
}

#[derive(Deserialize)]
#[serde(default, deny_unknown_fields)]
struct QuicConfig {
    listen: String,
    /// Advertised by the authenticated Minecraft controller; the native listener does not use
    /// it, but accepting it keeps one strict shared configuration file for both processes.
    advertise_host: String,
    /// Public port advertised by the controller. Zero uses the actual UDP port in VOXY_READY.
    advertise_port: u16,
}

impl Default for QuicConfig {
    fn default() -> Self {
        Self {
            listen: String::new(),
            advertise_host: String::new(),
            advertise_port: 0,
        }
    }
}

impl Config {
    pub fn load() -> Result<Self> {
        let mut path = PathBuf::from("voxy-rust.toml");
        let mut once = false;
        let mut minecraft_port = 25565;
        let mut args = env::args_os().skip(1);
        while let Some(argument) = args.next() {
            match argument.to_string_lossy().as_ref() {
                "--config" => path = args.next().context("--config requires a path")?.into(),
                "--once" => once = true,
                "--minecraft-port" => {
                    minecraft_port = args
                        .next()
                        .context("--minecraft-port requires a port")?
                        .to_str()
                        .context("Minecraft port must be UTF-8")?
                        .parse::<u16>()
                        .context("Minecraft port must be between 1 and 65535")?;
                    if minecraft_port == 0 {
                        bail!("Minecraft port must be between 1 and 65535");
                    }
                }
                "--help" | "-h" => bail!(Self::usage()),
                unknown => bail!("unknown argument {unknown:?}\n{}", Self::usage()),
            }
        }
        let text = std::fs::read_to_string(&path)
            .with_context(|| format!("read configuration {}", path.display()))?;
        let file: FileConfig = toml::from_str(&text)
            .with_context(|| format!("parse configuration {}", path.display()))?;
        Self::from_file(file, once, minecraft_port)
    }

    fn from_file(file: FileConfig, once: bool, minecraft_port: u16) -> Result<Self> {
        if file.quic.advertise_host.len() > 253 {
            bail!("quic.advertise_host is too long");
        }
        let _advertise_port = file.quic.advertise_port;
        if file.poll_ms < 100 {
            bail!("poll_ms must be at least 100");
        }
        if file.rayon_threads > 256 {
            bail!("rayon_threads must be between 1 and 256, or 0 for the Rayon default");
        }
        if file.dimension.is_empty()
            || file.dimension.len() > crate::regional::wire::MAX_DIMENSION_BYTES
        {
            bail!("dimension is outside the length limit");
        }
        Ok(Self {
            world: file.world,
            data: file.data,
            listen: resolve_listen(&file.quic.listen, minecraft_port)?,
            dimension: file.dimension,
            poll_interval: Duration::from_millis(file.poll_ms),
            rayon_threads: file.rayon_threads,
            once: once || file.once,
        })
    }

    pub fn usage() -> &'static str {
        "voxy-rust-server [--config voxy-rust.toml] [--minecraft-port 25565] [--once]"
    }
}

fn resolve_listen(configured: &str, minecraft_port: u16) -> Result<SocketAddr> {
    if !configured.is_empty() {
        return configured.parse().context("invalid quic.listen address");
    }
    let port = minecraft_port
        .checked_add(2000)
        .context("Minecraft port + 2000 exceeds 65535; set an explicit quic.listen address")?;
    Ok(SocketAddr::from(([0, 0, 0, 0], port)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn automatic_listener_is_resolved_without_persisting_the_port() {
        let text = include_str!("../../server/src/main/resources/voxy-rust-default.toml");
        for (minecraft, expected) in [(25565, 27565), (25586, 27586), (1, 2001), (63535, 65535)] {
            let file: FileConfig = toml::from_str(text).unwrap();
            assert_eq!(file.quic.listen, "");
            let config = Config::from_file(file, false, minecraft).unwrap();
            assert_eq!(config.listen, SocketAddr::from(([0, 0, 0, 0], expected)));
        }
        assert!(resolve_listen("", 63536).is_err());
        assert!(resolve_listen("", 65535).is_err());
        assert_eq!(
            resolve_listen("127.0.0.1:12345", 65535).unwrap().port(),
            12345
        );
        assert_eq!(resolve_listen("[::1]:12345", 25565).unwrap().port(), 12345);
        assert!(resolve_listen("not an address", 25565).is_err());
    }
}

fn default_dimension() -> String {
    "minecraft:overworld".to_owned()
}
fn default_poll_ms() -> u64 {
    2_000
}
