use crate::surface::memory::{
    DEFAULT_MANAGED_MEMORY_MIB, MAX_MANAGED_MEMORY_MIB, MIN_MANAGED_MEMORY_MIB,
};
use crate::surface::visibility::DimensionVisibilityPolicy;
use anyhow::{Context, Result, bail};
use serde::Deserialize;
use std::{collections::BTreeMap, env, net::SocketAddr, path::PathBuf, time::Duration};

const MAX_VISIBILITY_POLICY_OVERRIDES: usize = 256;

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
    pub managed_memory_mib: usize,
    pub max_connections: usize,
    pub visibility_policies: BTreeMap<String, DimensionVisibilityPolicy>,
    pub once: bool,
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
    memory: MemoryConfig,
    #[serde(default)]
    visibility: BTreeMap<String, DimensionVisibilityPolicy>,
    #[serde(default)]
    once: bool,
    #[serde(default)]
    direct: DirectConfig,
    #[serde(default)]
    minecraft: MinecraftConfig,
}

#[derive(Deserialize)]
#[serde(default, deny_unknown_fields)]
struct DirectConfig {
    listen: SocketAddr,
    /// Advertised by the authenticated Minecraft controller; the native listener does not use
    /// it, but accepting it keeps one strict shared configuration file for both processes.
    advertise_host: String,
    /// Public port advertised by the Minecraft controller. Zero reuses `listen.port()`.
    advertise_port: u16,
}

impl Default for DirectConfig {
    fn default() -> Self {
        Self {
            listen: "127.0.0.1:25587".parse().unwrap(),
            advertise_host: String::new(),
            advertise_port: 0,
        }
    }
}

#[derive(Deserialize)]
#[serde(default, deny_unknown_fields)]
struct MinecraftConfig {
    socket: PathBuf,
}

#[derive(Deserialize)]
#[serde(default, deny_unknown_fields)]
struct MemoryConfig {
    managed_mib: usize,
    max_connections: usize,
}

impl Default for MemoryConfig {
    fn default() -> Self {
        Self {
            managed_mib: DEFAULT_MANAGED_MEMORY_MIB,
            max_connections: 256,
        }
    }
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
                "--once" => once = true,
                "--help" | "-h" => bail!(Self::usage()),
                unknown => bail!("unknown argument {unknown:?}\n{}", Self::usage()),
            }
        }
        let text = std::fs::read_to_string(&path)
            .with_context(|| format!("read configuration {}", path.display()))?;
        let file: FileConfig = toml::from_str(&text)
            .with_context(|| format!("parse configuration {}", path.display()))?;
        Self::from_file(file, once)
    }

    fn from_file(file: FileConfig, once: bool) -> Result<Self> {
        let transport = match file.transport.as_str() {
            "direct" => {
                if file.direct.advertise_host.len() > 253 {
                    bail!("direct.advertise_host is too long");
                }
                // Parsed for the strict shared controller configuration; the native listener
                // binds only `listen` and does not advertise itself.
                let _advertise_port = file.direct.advertise_port;
                Transport::Direct(file.direct.listen)
            }
            "minecraft" => Transport::Minecraft(file.minecraft.socket),
            _ => bail!("transport must be either \"direct\" or \"minecraft\""),
        };
        if file.poll_ms < 100 {
            bail!("poll_ms must be at least 100");
        }
        if file.rayon_threads > 256 {
            bail!("rayon_threads must be between 1 and 256, or 0 for the Rayon default");
        }
        if !(MIN_MANAGED_MEMORY_MIB..=MAX_MANAGED_MEMORY_MIB).contains(&file.memory.managed_mib) {
            bail!(
                "memory.managed_mib must be between {MIN_MANAGED_MEMORY_MIB} and {MAX_MANAGED_MEMORY_MIB}"
            );
        }
        if !(1..=4096).contains(&file.memory.max_connections) {
            bail!("memory.max_connections must be between 1 and 4096");
        }
        if file.dimension.is_empty()
            || file.dimension.len() > crate::surface::wire::MAX_DIMENSION_BYTES
        {
            bail!("dimension is outside the length limit");
        }
        validate_visibility_policies(&file.visibility)?;
        Ok(Self {
            world: file.world,
            data: file.data,
            transport,
            dimension: file.dimension,
            poll_interval: Duration::from_millis(file.poll_ms),
            rayon_threads: file.rayon_threads,
            managed_memory_mib: file.memory.managed_mib,
            max_connections: file.memory.max_connections,
            visibility_policies: file.visibility,
            once: once || file.once,
        })
    }

    pub fn usage() -> &'static str {
        "voxy-rust-server [--config voxy-rust.toml] [--once]"
    }
}

fn validate_visibility_policies(
    policies: &BTreeMap<String, DimensionVisibilityPolicy>,
) -> Result<()> {
    if policies.len() > MAX_VISIBILITY_POLICY_OVERRIDES {
        bail!(
            "visibility policy overrides exceed the {MAX_VISIBILITY_POLICY_OVERRIDES}-dimension limit"
        );
    }
    for (dimension, &policy) in policies {
        if dimension.is_empty() || dimension.len() > crate::surface::wire::MAX_DIMENSION_BYTES {
            bail!("visibility override dimension is outside the length limit");
        }
        DimensionVisibilityPolicy::configured(dimension, Some(policy))?;
    }
    Ok(())
}

fn default_dimension() -> String {
    "minecraft:overworld".to_owned()
}
fn default_poll_ms() -> u64 {
    2_000
}
