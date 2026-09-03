//! Current-only regional LOD storage and section framing.
//!
//! A section is encoded, compressed, addressed, transferred, decoded, and activated as one unit.

mod builder;
mod index;
mod runtime;
mod section;
mod service;
mod source;
mod store;
pub mod wire;

pub use builder::{RegionalBuildStats, rebuild_region, rebuild_region_incremental};
pub use index::RegionIndex;
pub use runtime::{RegionalRefresh, RegionalRuntime};
pub use section::SectionFrame;
pub use service::{RegionalAnnouncement, RegionalResponder, RegionalService};
pub use source::{CHUNKS_PER_REGION, ChunkSourceRecord, RegionSourceTable};
pub use store::{
    RegionFile, RegionFileBuilder, RegionLayout, RegionSectionEntry, SECTION_FLAG_EMPTY,
    SectionCoordinate,
};
