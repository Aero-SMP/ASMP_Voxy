use anyhow::{Context, Result, bail};
use serde::Deserialize;
use std::{env, net::SocketAddr, path::PathBuf, time::Duration};

#[derive(Clone, Debug)]
pub enum Transport {
    Direct(SocketAddr),
    Minecraft(PathBuf),
}

#[derive(Clone, Debug)]
pub struct Config {
    pub world: PathBuf,
    pub data: PathBuf,
    pub transport: Transport,
    pub dimension: String,
    pub poll_interval: Duration,
    pub rayon_threads: usize,
    pub once: bool,
    pub trust_client_opacity: bool,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct FileConfig {
    world: PathBuf,
    data: PathBuf,
    transport: String,
    #[serde(default = "default_dimension")]
    dimension: String,
    #[serde(default = "default_poll_ms")]
    poll_ms: u64,
    #[serde(default)]
    rayon_threads: usize,
    #[serde(default)]
    once: bool,
    #[serde(default)]
    trust_client_opacity: bool,
    #[serde(default)]
    direct: DirectConfig,
    #[serde(default)]
    minecraft: MinecraftConfig,
}

#[derive(Deserialize)]
#[serde(default, deny_unknown_fields)]
struct DirectConfig {
    listen: SocketAddr,
    #[serde(default)]
    advertise_host: String,
}

impl Default for DirectConfig {
    fn default() -> Self {
        Self {
            listen: "127.0.0.1:25587".parse().unwrap(),
            advertise_host: String::new(),
        }
    }
}

#[derive(Deserialize)]
#[serde(default, deny_unknown_fields)]
struct MinecraftConfig {
    socket: PathBuf,
}

impl Default for MinecraftConfig {
    fn default() -> Self {
        Self {
            socket: "voxy-rust.sock".into(),
        }
    }
}

impl Config {
    pub fn load() -> Result<Self> {
        let mut path = PathBuf::from("voxy-rust.toml");
        let mut once = false;
        let mut args = env::args_os().skip(1);
        while let Some(argument) = args.next() {
            match argument.to_string_lossy().as_ref() {
                "--config" => path = args.next().context("--config requires a path")?.into(),
                "--once" | "--index-only" => once = true,
                "--help" | "-h" => bail!(Self::usage()),
                unknown => bail!("unknown argument {unknown:?}\n{}", Self::usage()),
            }
        }
        let text = std::fs::read_to_string(&path)
            .with_context(|| format!("read configuration {}", path.display()))?;
        let file: FileConfig = toml::from_str(&text)
            .with_context(|| format!("parse configuration {}", path.display()))?;
        let transport = match file.transport.as_str() {
            "direct" => Transport::Direct(file.direct.listen),
            "minecraft" => Transport::Minecraft(file.minecraft.socket),
            _ => bail!("transport must be either \"direct\" or \"minecraft\""),
        };
        if file.poll_ms < 100 {
            bail!("poll_ms must be at least 100");
        }
        if file.rayon_threads > 256 {
            bail!("rayon_threads must be between 1 and 256, or 0 for the Rayon default");
        }
        if file.dimension.len() > u16::MAX as usize {
            bail!("dimension is too long");
        }
        if file.direct.advertise_host.chars().count() > 255 {
            bail!("direct.advertise_host is too long");
        }
        Ok(Self {
            world: file.world,
            data: file.data,
            transport,
            dimension: file.dimension,
            poll_interval: Duration::from_millis(file.poll_ms),
            rayon_threads: file.rayon_threads,
            once: once || file.once,
            trust_client_opacity: file.trust_client_opacity,
        })
    }

    pub fn usage() -> &'static str {
        "voxy-rust-server [--config voxy-rust.toml] [--once|--index-only]"
    }
}

fn default_dimension() -> String {
    "minecraft:overworld".to_owned()
}
fn default_poll_ms() -> u64 {
    2_000
}
