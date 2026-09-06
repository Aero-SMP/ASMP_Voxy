//! Serialized fixtures exercise the production parser, including its mutation ordering.
use super::*;
use fastnbt::Value;
use std::sync::atomic::{AtomicU64, Ordering};

fn compound(entries: impl IntoIterator<Item = (&'static str, Value)>) -> Value {
    Value::Compound(entries.into_iter().map(|(k, v)| (k.into(), v)).collect())
}
fn fields(value: &mut Value) -> &mut std::collections::HashMap<String, Value> {
    match value {
        Value::Compound(fields) => fields,
        _ => panic!("not compound"),
    }
}
fn packed(count: usize, palette: usize, min_bits: u32) -> LongArray {
    let bits = min_bits.max(usize::BITS - (palette - 1).leading_zeros());
    let per = 64 / bits as usize;
    let mut words = vec![0i64; count.div_ceil(per)];
    for i in 0..count {
        words[i / per] |= ((i % palette) as i64) << ((i % per) * bits as usize);
    }
    LongArray::new(words)
}
fn section(blocks: usize, biomes: usize, lights: u8) -> Value {
    let palette = (0..blocks)
        .map(|i| compound([("Name", Value::String(format!("test:block_{i}")))]))
        .collect();
    let mut value = compound([
        ("Y", Value::Byte(-4)),
        (
            "block_states",
            compound([
                ("palette", Value::List(palette)),
                ("data", Value::LongArray(packed(4096, blocks.max(1), 4))),
            ]),
        ),
        (
            "biomes",
            compound([
                (
                    "palette",
                    Value::List(
                        (0..biomes)
                            .map(|i| Value::String(format!("test:biome_{i}")))
                            .collect(),
                    ),
                ),
                ("data", Value::LongArray(packed(64, biomes.max(1), 1))),
            ]),
        ),
    ]);
    if lights & 1 != 0 {
        fields(&mut value).insert(
            "BlockLight".into(),
            Value::ByteArray(ByteArray::new((0..2048).map(|i| (i * 37) as i8).collect())),
        );
    }
    if lights & 2 != 0 {
        fields(&mut value).insert(
            "SkyLight".into(),
            Value::ByteArray(ByteArray::new(
                (0..2048).map(|i| (255 - i * 13) as i8).collect(),
            )),
        );
    }
    value
}
fn bytes(sections: Vec<Value>, status: &str) -> Vec<u8> {
    fastnbt::to_bytes(&compound([
        ("xPos", Value::Int(-33)),
        ("zPos", Value::Int(-7)),
        ("Status", Value::String(status.into())),
        ("sections", Value::List(sections)),
    ]))
    .unwrap()
}
static NEXT: AtomicU64 = AtomicU64::new(0);
struct Fixture {
    root: PathBuf,
    registry: Arc<RwLock<Registry>>,
}
impl Fixture {
    fn new() -> Self {
        let root = std::env::temp_dir().join(format!(
            "voxy-array-test-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        let registry = Arc::new(RwLock::new(Registry::open(&root).unwrap()));
        Self { root, registry }
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        fs::remove_dir_all(&self.root).unwrap();
    }
}

#[test]
fn serialized_arrays_preserve_all_cells_and_fingerprints() {
    let mut digest = blake3::Hasher::new();
    for blocks in [1, 2, 17, 257, 4096] {
        for biomes in [1, 3, 64] {
            for lights in 0..4 {
                let fixture = Fixture::new();
                let input = bytes(vec![section(blocks, biomes, lights)], "full");
                let parsed = parse_chunk(&input, &fixture.registry, 0).unwrap();
                drop(input); // Returned cells must own their storage.
                assert_eq!(
                    (parsed.x, parsed.z, parsed.source_fingerprint),
                    (-33, -7, 0)
                );
                for (i, cell) in parsed.sections[&-4].cells.iter().enumerate() {
                    let biome_index = (i % 16 / 4) + (i / 16 % 16 / 4) * 4 + (i / 256 / 4) * 16;
                    let shift = (i % 2) * 4;
                    let block_light = if lights & 1 == 0 {
                        0
                    } else {
                        (((i / 2 * 37) as u8) >> shift) & 15
                    };
                    let sky = if lights & 2 == 0 {
                        0
                    } else {
                        ((255i32 - (i / 2 * 13) as i32) as u8 >> shift) & 15
                    };
                    assert_eq!(
                        *cell,
                        Cell {
                            block: (i % blocks + 1) as u32,
                            biome: (biome_index % biomes + 1) as u32,
                            light: (block_light << 4) | sky
                        }
                    );
                    digest.update(&cell.block.to_le_bytes());
                    digest.update(&cell.biome.to_le_bytes());
                    digest.update(&[cell.light]);
                }
                for fingerprint in parsed.terrain_fingerprint {
                    digest.update(&fingerprint.to_le_bytes());
                }
            }
        }
    }
    let actual = digest.finalize().to_hex().to_string();
    assert_eq!(
        actual, "48aa47b2a8cfb67478b2d00a759c9a09fd1cc436fd1a726a684cd1a6a6090d7e",
        "pre-change complete cell/fingerprint output"
    );
}

#[test]
fn malformed_arrays_and_registry_order_are_unchanged() {
    for case in [
        "missing-block",
        "short-block",
        "long-block",
        "index-block",
        "empty-block",
        "oversize-block",
        "missing-biome",
        "short-biome",
        "long-biome",
        "index-biome",
        "oversize-biome",
        "short-light",
        "long-light",
        "duplicate",
    ] {
        let fixture = Fixture::new();
        let mut value = section(3, 3, 3);
        let (target, count, min) = if case.ends_with("biome") {
            ("biomes", 64, 1)
        } else {
            ("block_states", 4096, 4)
        };
        if case.starts_with("missing") {
            fields(fields(&mut value).get_mut(target).unwrap()).remove("data");
        } else if case.starts_with("short-") || case.starts_with("long-") {
            if case.ends_with("light") {
                fields(&mut value).insert(
                    "SkyLight".into(),
                    Value::ByteArray(ByteArray::new(vec![
                        -1;
                        if case.starts_with("short") {
                            2047
                        } else {
                            2049
                        }
                    ])),
                );
            } else {
                let mut data = packed(count, 3, min).to_vec();
                if case.starts_with("short") {
                    data.pop();
                } else {
                    data.push(0);
                }
                fields(fields(&mut value).get_mut(target).unwrap())
                    .insert("data".into(), Value::LongArray(LongArray::new(data)));
            }
        } else if case.starts_with("index") {
            let mut data = packed(count, 3, min).to_vec();
            data[0] |= 3;
            fields(fields(&mut value).get_mut(target).unwrap())
                .insert("data".into(), Value::LongArray(LongArray::new(data)));
        } else if case == "empty-block" {
            value = section(0, 1, 0);
        } else if case == "oversize-block" {
            value = section(4097, 1, 0);
        } else if case == "oversize-biome" {
            value = section(1, 65, 0);
        }
        let sections = if case == "duplicate" {
            vec![value.clone(), value]
        } else {
            vec![value]
        };
        let error = parse_chunk(&bytes(sections, "full"), &fixture.registry, 0).unwrap_err();
        let snapshot = fixture.registry.read().unwrap().snapshot();
        assert_eq!(
            snapshot.blocks.len() > 1,
            case.ends_with("light") || case == "duplicate",
            "mutation order: {case}: {error}"
        );
    }
    let fixture = Fixture::new();
    assert!(parse_chunk(&[0, 1, 2], &fixture.registry, 0).is_err());
    assert_eq!(fixture.registry.read().unwrap().snapshot().blocks.len(), 1);
}

#[test]
fn optional_arrays_light_only_and_many_sections_remain_owned() {
    for light in [None, Some(0i8), Some(-1i8)] {
        let fixture = Fixture::new();
        let mut value = section(1, 1, 0);
        // Single palettes accept absent or arbitrary packed lengths; do not tighten semantics.
        fields(fields(&mut value).get_mut("block_states").unwrap()).remove("data");
        fields(&mut value).remove("biomes");
        for key in ["SkyLight", "BlockLight"] {
            if let Some(light) = light {
                fields(&mut value).insert(
                    key.into(),
                    Value::ByteArray(ByteArray::new(vec![light; 2048])),
                );
            }
        }
        let output =
            parse_chunk(&bytes(vec![value.clone()], "full"), &fixture.registry, 0).unwrap();
        assert!(
            output.sections[&-4]
                .cells
                .iter()
                .all(|c| c.biome == 0 && c.light == if light == Some(-1) { 255 } else { 0 })
        );
        fields(&mut value).remove("block_states");
        let mut sections = Vec::new();
        for y in -4..20 {
            let mut section = value.clone();
            fields(&mut section).insert("Y".into(), Value::Byte(y));
            sections.push(section);
        }
        let input = bytes(sections, "minecraft:full");
        let output = parse_chunk(&input, &fixture.registry, 0).unwrap();
        drop(input);
        assert_eq!(output.sections.len(), 24);
        assert!(
            output
                .sections
                .values()
                .flat_map(|s| &s.cells)
                .all(|c| c.block == 0
                    && c.biome == 0
                    && c.light == if light == Some(-1) { 255 } else { 0 })
        );
        assert!(
            parse_chunk(&bytes(vec![value], "noise"), &fixture.registry, 0)
                .unwrap()
                .sections
                .is_empty()
        );
    }
}

#[test]
fn parsing_benchmark_when_requested() {
    if std::env::var_os("VOXY_SIMPLIFICATION_BENCH").is_none() {
        return;
    }
    for lights in [0, 3] {
        let fixture = Fixture::new();
        let input = bytes(vec![section(17, 3, lights)], "full");
        for _ in 0..100 {
            std::hint::black_box(parse_chunk(&input, &fixture.registry, 0).unwrap());
        }
        for _ in 0..7 {
            allocation::begin();
            let start = std::time::Instant::now();
            for _ in 0..500 {
                std::hint::black_box(parse_chunk(&input, &fixture.registry, 0).unwrap());
            }
            let nanos = start.elapsed().as_nanos();
            let (bytes, count, peak) = allocation::end();
            eprintln!(
                "ANVIL_BENCH lights={lights} ns/chunk={} bytes/chunk={} allocations/chunk={} peak={peak}",
                nanos / 500,
                bytes / 500,
                count / 500
            );
        }
    }
}

// Test-only allocation observation, enabled only around the isolated warmed parser benchmark.
mod allocation {
    use std::alloc::{GlobalAlloc, Layout, System};
    use std::cell::Cell;
    thread_local! { static STATS: Cell<Option<(usize, usize, usize, usize)>> = const { Cell::new(None) }; }
    struct Observer;
    #[global_allocator]
    static ALLOCATOR: Observer = Observer;
    fn record(add: usize, remove: usize) {
        let _ = STATS.try_with(|stats| {
            if let Some((bytes, count, live, peak)) = stats.get() {
                let live = live.saturating_sub(remove) + add;
                stats.set(Some((
                    bytes + add,
                    count + usize::from(add != 0),
                    live,
                    peak.max(live),
                )));
            }
        });
    }
    unsafe impl GlobalAlloc for Observer {
        unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
            let pointer = unsafe { System.alloc(layout) };
            if !pointer.is_null() {
                record(layout.size(), 0);
            }
            pointer
        }
        unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
            let pointer = unsafe { System.alloc_zeroed(layout) };
            if !pointer.is_null() {
                record(layout.size(), 0);
            }
            pointer
        }
        unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
            record(0, layout.size());
            unsafe {
                System.dealloc(pointer, layout);
            }
        }
        unsafe fn realloc(&self, pointer: *mut u8, layout: Layout, size: usize) -> *mut u8 {
            let result = unsafe { System.realloc(pointer, layout, size) };
            if !result.is_null() {
                record(size, layout.size());
            }
            result
        }
    }
    pub fn begin() {
        STATS.with(|stats| stats.set(Some((0, 0, 0, 0))));
    }
    pub fn end() -> (usize, usize, usize) {
        STATS.with(|stats| {
            let (bytes, count, _, peak) = stats.replace(None).unwrap();
            (bytes, count, peak)
        })
    }
}
