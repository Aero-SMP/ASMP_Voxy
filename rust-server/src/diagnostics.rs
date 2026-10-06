//! Debug measurements use fixed storage. Async wall time is never called thread CPU time.
//! Nested stages are inclusive; their sums must not be added to obtain request latency.
use std::time::Duration;

#[derive(Clone, Copy, Debug)]
#[repr(usize)]
pub enum Stage {
    Tls,
    Bootstrap,
    ControlRead,
    ControlDecode,
    Apply,
    QueueVisible,
    QueuePrefetch,
    QueueRefresh,
    PrepareQueue,
    PrepareWork,
    PrepareResume,
    Lookup,
    DirectoryRead,
    DirectoryCrc,
    Index,
    CatalogueLock,
    CatalogueBuild,
    MetadataLock,
    MetadataWrite,
    BodyQueue,
    BodyWork,
    BodyResume,
    BodyRead,
    BodyCrc,
    Admission,
    RecordWrite,
    Request,
    SourceWait,
    PacerWait,
    SocketWait,
    PacerLate,
    PublicationQueue,
    PublicationWork,
    PublicationResume,
    Fanout,
    MaintenanceLock,
    Inventory,
    Refresh,
    FullBuild,
    IncrementalBuild,
    SourceRead,
    SourceDecode,
    SourceNbt,
    LodBuild,
    EncodeCompress,
    ReusedCopy,
    TerrainWrite,
    FsyncRename,
    DirtyCoalesce,
    SourceInspect,
    SourceTable,
}

#[cfg(not(feature = "debug-diagnostics"))]
#[derive(Debug)]
pub struct Span;
#[cfg(not(feature = "debug-diagnostics"))]
impl Span {
    #[inline(always)]
    pub fn new(_: Stage) -> Self {
        Self
    }
    #[inline(always)]
    pub fn sync(_: Stage) -> Self {
        Self
    }
    #[inline(always)]
    pub fn finish(self, _: bool, _: u64) {}
    #[inline(always)]
    pub fn cancelled(self) {}
}
#[cfg(not(feature = "debug-diagnostics"))]
#[inline(always)]
pub fn enabled() -> bool {
    false
}
#[cfg(not(feature = "debug-diagnostics"))]
#[inline(always)]
pub fn record(_: Stage, _: Duration, _: u64) {}
#[cfg(not(feature = "debug-diagnostics"))]
#[inline(always)]
pub fn hit(_: usize) {}
#[cfg(not(feature = "debug-diagnostics"))]
#[inline(always)]
pub fn quiet() -> bool {
    false
}
#[inline]
pub fn sync_result<T>(
    stage: Stage,
    operation: impl FnOnce() -> anyhow::Result<T>,
) -> anyhow::Result<T> {
    let span = Span::sync(stage);
    let result = operation();
    span.finish(result.is_ok(), 0);
    result
}

/// Queue, execution and resumption have separate ownership and clocks. Cancellation of the
/// awaiting task does not cancel an already running blocking closure.
pub async fn blocking<T: Send + 'static>(
    queue: Stage,
    work: Stage,
    resume: Stage,
    operation: impl FnOnce() -> anyhow::Result<T> + Send + 'static,
) -> std::result::Result<anyhow::Result<T>, tokio::task::JoinError> {
    #[cfg(not(feature = "debug-diagnostics"))]
    {
        let _ = (queue, work, resume);
        return tokio::task::spawn_blocking(operation).await;
    }
    #[cfg(feature = "debug-diagnostics")]
    {
        let queued = Span::new(queue);
        let (result, ended) = tokio::task::spawn_blocking(move || {
            queued.finish(true, 0);
            let work = Span::sync(work);
            let result = operation();
            work.finish(result.is_ok(), 0);
            (result, std::time::Instant::now())
        })
        .await?;
        record(resume, ended.elapsed(), 0);
        Ok(result)
    }
}

#[cfg(feature = "debug-diagnostics")]
mod debug {
    use super::*;
    use std::sync::{
        OnceLock,
        atomic::{AtomicBool, AtomicU64, Ordering::Relaxed},
    };
    use std::time::Instant;
    const N: usize = Stage::SourceTable as usize + 1;
    // Bin i covers elapsed <= 2^i microseconds; the last bin is open ended.
    const BINS: usize = 32;
    static ENABLED: AtomicBool = AtomicBool::new(false);
    static QUIET: AtomicBool = AtomicBool::new(false);
    static ORIGIN: OnceLock<Instant> = OnceLock::new();
    static STAGES: [Metric; N] = [const { Metric::new() }; N];
    static HITS: [AtomicU64; 6] = [const { AtomicU64::new(0) }; 6];
    pub const HIT_NAMES: [&str; 6] = [
        "snapshot_hit",
        "snapshot_miss",
        "catalogue_hit",
        "catalogue_miss",
        "metadata_only_refresh",
        "unchanged_source",
    ];
    const NAMES: [&str; N] = [
        "tls",
        "bootstrap",
        "control_read",
        "control_decode",
        "desire_apply",
        "queue_visible",
        "queue_prefetch",
        "queue_refresh",
        "prepare_executor_queue",
        "prepare_work",
        "prepare_async_resume",
        "regional_lookup",
        "directory_read",
        "directory_crc",
        "index_construct",
        "catalogue_lock",
        "catalogue_build",
        "metadata_lock",
        "metadata_write",
        "body_executor_queue",
        "body_work",
        "body_async_resume",
        "compressed_read",
        "compressed_crc",
        "record_admission",
        "record_write",
        "request_inclusive",
        "source_wait",
        "pacer_wait",
        "socket_wait",
        "pacer_resume_lateness",
        "publication_executor_queue",
        "publication_work_inclusive",
        "publication_async_resume",
        "publication_fanout",
        "maintenance_lock",
        "source_inventory",
        "regional_refresh_inclusive",
        "full_build_inclusive",
        "incremental_build_inclusive",
        "anvil_read",
        "anvil_decompress",
        "anvil_nbt",
        "lod_construct",
        "record_encode_compress",
        "reused_copy_validate_write",
        "terrain_write_inclusive",
        "fsync_rename",
        "dirty_columns_coalesced",
        "source_inspect",
        "source_table_write",
    ];
    struct Metric {
        count: AtomicU64,
        failed: AtomicU64,
        cancelled: AtomicU64,
        sum: AtomicU64,
        cpu: AtomicU64,
        max: AtomicU64,
        bytes: AtomicU64,
        active: AtomicU64,
        oldest: AtomicU64,
        bins: [AtomicU64; BINS],
    }
    impl Metric {
        const fn new() -> Self {
            Self {
                count: AtomicU64::new(0),
                failed: AtomicU64::new(0),
                cancelled: AtomicU64::new(0),
                sum: AtomicU64::new(0),
                cpu: AtomicU64::new(0),
                max: AtomicU64::new(0),
                bytes: AtomicU64::new(0),
                active: AtomicU64::new(0),
                oldest: AtomicU64::new(0),
                bins: [const { AtomicU64::new(0) }; BINS],
            }
        }
        fn complete(&self, ns: u64, cpu: u64, bytes: u64, status: u8) {
            self.count.fetch_add(1, Relaxed);
            self.sum.fetch_add(ns, Relaxed);
            self.cpu.fetch_add(cpu, Relaxed);
            self.max.fetch_max(ns, Relaxed);
            self.bytes.fetch_add(bytes, Relaxed);
            if status == 1 {
                self.failed.fetch_add(1, Relaxed);
            }
            if status == 2 {
                self.cancelled.fetch_add(1, Relaxed);
            }
            self.bins[bucket(ns)].fetch_add(1, Relaxed);
        }
    }
    pub fn bucket(ns: u64) -> usize {
        let us = ns.div_ceil(1000).max(1);
        (64 - (us - 1).leading_zeros() as usize).min(BINS - 1)
    }
    pub fn now_ns() -> u64 {
        nanos(ORIGIN.get_or_init(Instant::now).elapsed()).saturating_add(1)
    }
    fn nanos(duration: Duration) -> u64 {
        duration.as_nanos().min(u64::MAX as u128) as u64
    }
    // CLOCK_THREAD_CPUTIME_ID = 3 on Linux. These spans never cross an await point.
    #[repr(C)]
    struct Timespec {
        sec: std::ffi::c_long,
        ns: std::ffi::c_long,
    }
    unsafe extern "C" {
        fn clock_gettime(clock: std::ffi::c_int, out: *mut Timespec) -> std::ffi::c_int;
    }
    fn thread_cpu() -> u64 {
        let mut time = Timespec { sec: 0, ns: 0 };
        if unsafe { clock_gettime(3, &mut time) } != 0 {
            return 0;
        }
        (time.sec as u64)
            .saturating_mul(1_000_000_000)
            .saturating_add(time.ns as u64)
    }
    #[derive(Debug)]
    pub struct Span {
        stage: Stage,
        start: Option<Instant>,
        cpu: Option<(std::thread::ThreadId, u64)>,
        status: u8,
        bytes: u64,
    }
    impl Span {
        pub fn new(stage: Stage) -> Self {
            Self::begin(stage, false)
        }
        pub fn sync(stage: Stage) -> Self {
            Self::begin(stage, true)
        }
        fn begin(stage: Stage, cpu: bool) -> Self {
            let active = enabled();
            let start = active.then(Instant::now);
            if active {
                let metric = &STAGES[stage as usize];
                let now = now_ns();
                // Only the zero->one transition starts a busy epoch. The last drop does not
                // clear this stamp, avoiding a race that could erase a newly started span.
                if metric.active.fetch_add(1, Relaxed) == 0 {
                    metric.oldest.store(now, Relaxed);
                }
            }
            Self {
                stage,
                start,
                cpu: (active && cpu).then(|| (std::thread::current().id(), thread_cpu())),
                status: if cpu { 1 } else { 2 },
                bytes: 0,
            }
        }
        pub fn finish(mut self, success: bool, bytes: u64) {
            self.status = u8::from(!success);
            self.bytes = bytes;
        }
        pub fn cancelled(mut self) {
            self.status = 2;
        }
    }
    impl Drop for Span {
        fn drop(&mut self) {
            let Some(start) = self.start else { return };
            let cpu = self
                .cpu
                .filter(|(id, _)| *id == std::thread::current().id())
                .map_or(0, |(_, start)| thread_cpu().saturating_sub(start));
            let metric = &STAGES[self.stage as usize];
            metric.complete(nanos(start.elapsed()), cpu, self.bytes, self.status);
            metric.active.fetch_sub(1, Relaxed);
        }
    }
    pub fn enabled() -> bool {
        ENABLED.load(Relaxed)
    }
    pub fn quiet() -> bool {
        QUIET.load(Relaxed)
    }
    pub fn set_quiet(value: bool) {
        QUIET.store(value, Relaxed);
    }
    pub fn set_enabled(value: bool) {
        ENABLED.store(value, Relaxed);
    }
    pub fn record(stage: Stage, duration: Duration, bytes: u64) {
        if enabled() {
            STAGES[stage as usize].complete(nanos(duration), 0, bytes, 0);
        }
    }
    pub fn hit(index: usize) {
        if enabled() {
            HITS[index].fetch_add(1, Relaxed);
        }
    }
    pub fn snapshot() -> serde_json::Value {
        let now = now_ns();
        let stages = STAGES.iter().enumerate().map(|(i,m)| {
            let oldest = m.oldest.load(Relaxed);
            let active=m.active.load(Relaxed);
            serde_json::json!({"stage":NAMES[i], "counter_unit":if i==Stage::DirtyCoalesce as usize{"dirty_columns"}else{"bytes"}, "count":m.count.load(Relaxed), "failed":m.failed.load(Relaxed),
                "cancelled":m.cancelled.load(Relaxed), "wall_ns":m.sum.load(Relaxed), "thread_cpu_ns":m.cpu.load(Relaxed),
                "max_ns":m.max.load(Relaxed), "bytes":m.bytes.load(Relaxed), "active":active,
                "active_age_upper_bound_ns":if active==0||oldest==0 {0}else{now.saturating_sub(oldest)},
                "active_age_definition":"continuous_busy_epoch_upper_bound",
                "histogram":m.bins.iter().map(|b|b.load(Relaxed)).collect::<Vec<_>>()})
        }).collect::<Vec<_>>();
        let hits = HIT_NAMES
            .iter()
            .enumerate()
            .map(|(i, name)| ((*name).to_owned(), serde_json::json!(HITS[i].load(Relaxed))))
            .collect::<serde_json::Map<_, _>>();
        serde_json::json!({"enabled":enabled(),"monotonic_ns":now,"inclusive_stages":true,
            "histogram_upper_bounds_ns":(0..BINS).map(|i|if i==BINS-1 {None}else{Some((1u64<<i)*1000)}).collect::<Vec<_>>(),
            "hits":hits,"stages":stages})
    }
    /// Network-free arithmetic/ownership checks against the actual production probe helpers.
    pub fn self_check() -> anyhow::Result<serde_json::Value> {
        anyhow::ensure!(
            bucket(0) == 0
                && bucket(1000) == 0
                && bucket(1001) == 1
                && bucket(2000) == 1
                && bucket(2001) == 2
                && bucket(u64::MAX) == 31,
            "histogram bounds"
        );
        set_enabled(true);
        let stage = Stage::Request;
        let m = &STAGES[stage as usize];
        let count = m.count.load(Relaxed);
        let failed = m.failed.load(Relaxed);
        let cancelled = m.cancelled.load(Relaxed);
        Span::new(stage).finish(true, 17);
        Span::new(stage).finish(false, 19);
        {
            let _cancelled = Span::new(stage);
        }
        Span::new(stage).cancelled();
        anyhow::ensure!(
            m.count.load(Relaxed) == count + 4
                && m.failed.load(Relaxed) == failed + 1
                && m.cancelled.load(Relaxed) == cancelled + 2
                && m.active.load(Relaxed) == 0,
            "completion/failed/cancelled ownership"
        );
        let cpu = m.cpu.load(Relaxed);
        {
            let span = Span::sync(stage);
            let mut value = 0u64;
            for i in 0..100_000 {
                value = std::hint::black_box(value.wrapping_add(i));
            }
            std::hint::black_box(value);
            span.finish(true, 0);
        }
        anyhow::ensure!(m.cpu.load(Relaxed) > cpu, "same-thread CPU measurement");
        let disabled = m.count.load(Relaxed);
        set_enabled(false);
        Span::new(stage).finish(true, 0);
        record(stage, Duration::from_nanos(7), 0);
        anyhow::ensure!(
            m.count.load(Relaxed) == disabled && m.active.load(Relaxed) == 0,
            "disabled probes must be inert"
        );
        set_enabled(true);
        Ok(
            serde_json::json!({"status":"PASS","histogram_bounds":true,"failed_cancelled_ownership":true,"same_thread_cpu":true,"disabled_inert":true,"snapshot":snapshot()}),
        )
    }
}
#[cfg(feature = "debug-diagnostics")]
pub use debug::*;
