# Saved falling water at physical pixel (128,1244)

The first occupied cell on this inspected ray is water level8 at world `(-24,17,0)`, in selected L0 owner `(0,-1,0,0)`. Its payload hash `6021fd91070966da086f03d6c8c7a3ec7c5739f0b135d782bd3c5ce2681dc901` matches the actual geometry hash in the inspection. All22 ray-selected owners were hash-matched before this source investigation; nonselected parent mismatches are not used as rendered evidence.

The one-time saved-source acquisitions found the same falling water and sky14. Both chunks are `minecraft:full` and have `isLightOn=true`.

| Chunk | Acquisition UTC | Compressed chunk SHA-256 |
| --- | --- | --- |
| `(-2,0)` | `2026-10-04T08:56:23.649197+00:00` | `01f5f6478ae4b3c99c393506e3926dd475477569277982ce2a3567034f304601` |
| `(-2,-1)` | `2026-10-04T08:56:23.650622+00:00` | `58252628fe432d7a6f713a881cd97cc73a661acbe12fc3e2aef72230d4273469` |

Each region's size, modification time, location and timestamp remained unchanged during its own read. The two files were acquired separately and are not a cross-file atomic snapshot. Compressed chunk bytes, region headers, per-file receipts and analysis are preserved here. No world, server or client was changed.

## Column and neighboring samples

At x=-24,z=0:

- Y16–61: water level8, sky14.
- Y62: water level1, sky14.
- Y63–65: air, sky15.

The same profile occurs at x=-25 and x=-23,z=0. At z=1, all sampled Y16–65 cells are air with sky15. At x=-24,z=-1, Y16–45 contains stone/granite/gravel; Y46–62 contains source water level0, and Y63–65 is air.

The falling-water and adjacent-air columns through Y63 belong to selected, hash-matched L0 owners `(0,-1,0,0)` and `(0,-1,1,0)`. Their sampled states match current saved source. Source-only or nonselected samples above that range are marked separately in `analysis.json`; they are not used to infer displayed ownership. All50 ray cells available in these two source chunks also match cached states.

The complete 3×3×3 stencil around `(-24,17,0)` agrees between current saved source and the selected cached records: the z=-1 plane is stone, the z=0 plane falling water, and the z=1 plane air. Water has sky14; air has sky15. The stone's saved skylight tag is absent, which is recorded as unknown stored data rather than asserted to be an explicit zero array.

## Face and native fluid semantics

The ray enters this water cell at approximately `(-23.556137169,17.066605585,1.0)`, through its south face beside air. That face is at z=1, inside the owning section; it is not the section's outside z=0 cap.

Cached native source `LiquidBlock.java:70–76` maps block level8 to flowing amount8 with falling=true. `LiquidBlockRenderer.java:93–111,365–367` checks the neighboring block/fluids and gives water full cell height when water is above. At this location, water is above and below, falling water is on both x sides, stone is north, and air is south. A visible south-facing vertical fluid sheet is therefore consistent with actual saved data and native meshing.

This specific ray has a genuine tall saved falling-water column. Coarse reduction and forced solid boundary caps are not its direct source. It does not establish that every photographed curtain or every emitted fluid triangle is correct. The inspection reports voxel/AABB intersections, not a captured GPU triangle; surrounding topology and mesh output remain separate questions.

The displayed record snapshot was captured at `2026-10-04T08:45:46.370405+00:00`, before these source acquisitions. Current chunk metadata may therefore differ from the source version originally used to build that record. No sampled state difference was found, and no whole-chunk temporal identity is claimed.
