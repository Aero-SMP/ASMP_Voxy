pub mod anvil;
pub mod payload;
pub mod store;
pub mod wire;

use anyhow::{Context, Result};
use payload::{Catalog, Key};
use std::{
    collections::{BTreeMap, VecDeque},
    fs,
    path::{Path, PathBuf},
    sync::{Arc, mpsc},
    thread,
    time::{Duration, Instant},
};
use tokio::sync::oneshot;

pub struct Backend {
    pub data: PathBuf,
    pub world_id: [u8; 16],
    pub dimensions: BTreeMap<String, PathBuf>,
    demand: mpsc::Sender<(String, i32, i32, oneshot::Sender<()>)>,
}
impl Backend {
    pub fn open(world: &Path, data: &Path) -> Result<Arc<Self>> {
        Self::open_with_refresh(world, data, Duration::from_secs(1))
    }
    pub fn open_with_refresh(world: &Path, data: &Path, cadence: Duration) -> Result<Arc<Self>> {
        anyhow::ensure!(!cadence.is_zero(), "refresh cadence must be positive");
        anyhow::ensure!(world.is_dir(), "world directory missing");
        fs::create_dir_all(data)?;
        let world_id = match fs::read(data.join("world.id")) {
            Ok(bytes) => bytes
                .try_into()
                .map_err(|_| anyhow::anyhow!("invalid world identity"))?,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                use std::io::Read;
                let mut id = [0; 16];
                fs::File::open("/dev/urandom")?.read_exact(&mut id)?;
                store::atomic_write(&data.join("world.id"), &id)?;
                id
            }
            Err(e) => return Err(e.into()),
        };
        let dimensions = anvil::dimensions(world)?;
        let (demand, receiver) = mpsc::channel();
        let backend = Arc::new(Self {
            data: data.into(),
            world_id,
            dimensions,
            demand,
        });
        let weak = Arc::downgrade(&backend);
        thread::Builder::new()
            .name("voxy-terrain".into())
            .spawn(move || {
                let Some(backend) = weak.upgrade() else {
                    return;
                };
                let data = backend.data.clone();
                let dimensions = backend.dimensions.clone();
                drop(backend);
                if let Err(e) = build_loop(&data, &dimensions, receiver, cadence) {
                    eprintln!("VOXY_BUILD_ERROR {e:#}");
                }
            })?;
        Ok(backend)
    }
    pub async fn section(self: &Arc<Self>, dimension: &str, key: Key) -> Result<Option<Vec<u8>>> {
        anyhow::ensure!(key.level <= 4, "unsupported section level");
        let (x, z) = key.region();
        let path = store::region_path(&self.data, dimension, x, z);
        let bytes = tokio::task::spawn_blocking({
            let path = path.clone();
            move || store::read_frame(&path, key)
        })
        .await??;
        if bytes.is_some() || path.exists() {
            return Ok(bytes);
        }
        let root = self
            .dimensions
            .get(dimension)
            .context("unknown dimension")?;
        if !root.join("region").join(format!("r.{x}.{z}.mca")).is_file() {
            return Ok(None);
        }
        let (send, receive) = oneshot::channel();
        self.demand
            .send((dimension.into(), x, z, send))
            .context("terrain worker stopped")?;
        let _ = receive.await;
        tokio::task::spawn_blocking(move || store::read_frame(&path, key)).await?
    }
    pub fn build_all(world: &Path, data: &Path) -> Result<usize> {
        fs::create_dir_all(data)?;
        let mut catalog = Catalog::load(data)?;
        let mut total = 0;
        for (dimension, root) in anvil::dimensions(world)? {
            for (x, z, source) in region_sources(&root)? {
                total += store::build_region(
                    &source,
                    &store::region_path(data, &dimension, x, z),
                    data,
                    x,
                    z,
                    &mut catalog,
                )?;
            }
        }
        Ok(total)
    }
}

fn region_sources(root: &Path) -> Result<Vec<(i32, i32, PathBuf)>> {
    coordinate_files(&root.join("region"), "mca", 8192)
}
fn coordinate_files(
    directory: &Path,
    extension: &str,
    minimum_length: u64,
) -> Result<Vec<(i32, i32, PathBuf)>> {
    let mut out = Vec::new();
    if !directory.is_dir() {
        return Ok(out);
    }
    for entry in fs::read_dir(directory)? {
        let entry = entry?;
        let name = entry.file_name();
        let parts = name
            .to_string_lossy()
            .split('.')
            .map(str::to_owned)
            .collect::<Vec<_>>();
        if parts.len() == 4
            && parts[0] == "r"
            && parts[3] == extension
            && entry.metadata()?.len() >= minimum_length
            && let (Ok(x), Ok(z)) = (parts[1].parse(), parts[2].parse())
        {
            out.push((x, z, entry.path()));
        }
    }
    out.sort_by_key(|(x, z, _)| (*x, *z));
    Ok(out)
}

fn build_loop(
    data: &Path,
    dimensions: &BTreeMap<String, PathBuf>,
    receiver: mpsc::Receiver<(String, i32, i32, oneshot::Sender<()>)>,
    cadence: Duration,
) -> Result<()> {
    let mut catalog = Catalog::load(data)?;
    let mut pending = BTreeMap::new();
    let mut order = VecDeque::new();
    let mut refresh = Instant::now();
    loop {
        let request = match receiver.recv_timeout(if pending.is_empty() {
            Duration::from_millis(100)
        } else {
            Duration::ZERO
        }) {
            Ok(request) => Some(request),
            Err(mpsc::RecvTimeoutError::Disconnected) => return Ok(()),
            Err(mpsc::RecvTimeoutError::Timeout) => None,
        };
        if let Some(request) = request {
            let (dimension, x, z, done) = request;
            let key = (dimension, x, z);
            if !pending.contains_key(&key) {
                order.push_front(key.clone());
            }
            let waiters: &mut Vec<oneshot::Sender<()>> = pending.entry(key).or_default();
            waiters.push(done);
        }
        for (dimension, x, z, done) in receiver.try_iter() {
            let key = (dimension, x, z);
            if !pending.contains_key(&key) {
                order.push_back(key.clone());
            }
            pending.entry(key).or_insert_with(Vec::new).push(done);
        }
        if refresh.elapsed() >= cadence {
            for (dimension, root) in dimensions {
                let folder = store::region_path(data, dimension, 0, 0)
                    .parent()
                    .unwrap()
                    .to_owned();
                let published_regions = match coordinate_files(&folder, "vxr", 44) {
                    Ok(sources) => sources,
                    Err(e) => {
                        eprintln!("VOXY_SOURCE_RETRY dimension={dimension} error={e:#}");
                        continue;
                    }
                };
                for (x, z, target) in published_regions {
                    let source = root.join("region").join(format!("r.{x}.{z}.mca"));
                    let key = (dimension.clone(), x, z);
                    if let Ok(marker) = store::marker(&source)
                        && store::saved_marker(&target) != Some(marker)
                        && !pending.contains_key(&key)
                    {
                        order.push_back(key.clone());
                        pending.insert(key, Vec::new());
                    }
                }
            }
            refresh = Instant::now();
        }
        let Some(key) = order.pop_front() else {
            continue;
        };
        let waiters = pending.remove(&key).unwrap();
        let (dimension, x, z) = key;
        let source = dimensions[&dimension]
            .join("region")
            .join(format!("r.{x}.{z}.mca"));
        let target = store::region_path(data, &dimension, x, z);
        if store::saved_marker(&target) != store::marker(&source).ok() {
            let start = Instant::now();
            match store::build_region(&source, &target, data, x, z, &mut catalog) {
                Ok(sections) => eprintln!(
                    "VOXY_PUBLISHED dimension={dimension} region={x},{z} sections={sections} elapsed_ms={}",
                    start.elapsed().as_millis()
                ),
                Err(e) => {
                    eprintln!("VOXY_REBUILD_RETRY dimension={dimension} region={x},{z} error={e:#}")
                }
            }
        }
        for done in waiters {
            let _ = done.send(());
        }
    }
}
