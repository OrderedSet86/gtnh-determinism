# Step 0 — Nether baseline on the SHIPPED jar (provenance-carrying)

Replaces the 78/356 (21.9%) figure quoted in `docs/HANDOFF.md:702`, which had no committed raw
data, no command line, and a region count implying radius ~24-25 rather than the r60 that
produced the overworld headline.

Fix jar `gtnhdeterminism-v0.8-main.1+a5efcee3d6-dirty` md5 `a44cb01777493a5b5f986f71505d6c2f`,
probe `worldgenprobe-v0.8-main.2+4c6e016626-dirty` md5 `e3e2c1fd9df11bf3623d0459bb6bca1e`,
GTNH daily-707, `level-type=rwg`, seed `-1636594104014467454`, r60, centre (0,0).

**This is not a stock arm.** On the shipped jar the dimension whitelist gates only the coordinate
pin; `OreManagerVirginDryRunMixin` gates on the global `gtnhdet.orepin` and
`WorldgenGTOreLayerStoneTypeMixin` gates on nothing. So the Nether here is running virginised
`OreManager` reads and a virginised stone probe with unpinned trigger coordinates, and
`TerrainOracle` is calling `provideChunk` on the live `ChunkProviderHell`. The true stock arm is
step 2, after N2.

## Command

```
PROBE_JAVA=$JDK/bin/java PROBE_DIM=-1 PROBE_SEARCH=false PROBE_CX=0 PROBE_CZ=0 \
  scripts/run-probe.sh ~/.cache/gtnh-determinism/daily-707 -1636594104014467454 <order> <out>.json 60
```
orders: `rows`, `spiral`, `rows` (second launch).

## Vein identity — `diff-veincache.py --dim -1 --seed -1636594104014467454`

| comparison | mix differs | geometry differs | only in one |
| --- | ---: | ---: | ---: |
| rows vs rows, separate launches (**noise floor**) | **0 / 1811** | 0 | 0 |
| rows vs spiral | **369 / 1809 (20.40%)** | 36 (1.99%) | 6 (A 2, B 4) |

Unfiltered (all dimensions) gives the same 369 / 36 / 6 over 2010 common regions, so the
overworld and dim-64 entries carried in `validOreveins` are identical between arms — the whole
difference is dim -1.

## Block level — `diff-region-blocks.py <world>/DIM-1`

| comparison | differing blocks | chunks affected | common chunks |
| --- | ---: | ---: | ---: |
| rows vs rows (**noise floor**) | **2,137** | 1,572 | 15,488 |
| rows vs spiral | **11,111,905** | 13,796 | 15,468 |

Route dependence is ~5,200x the same-order floor, and reaches **89% of Nether chunks**.

Top transitions are not ore: air<->netherrack at 1.45M/1.40M, netherrack<->879 at 1.01M/0.99M,
lava(11)<->netherrack at 0.50M/0.48M, quartz_ore(153)<->netherrack at 92k/91k. This is the whole
decoration layer moving, which is D1 (`ChunkProviderHell.populate` never re-seeds `hellRNG`)
compounded by D2 (`TerrainOracle` calling `provideChunk` on the live provider). Ore veins are a
small part of the Nether's problem.

The nonzero same-order block floor (2,137) is not explained by this work. It is the size expected
from the FML `IWorldGenerator` dispatch-order instability recorded in
`results/2026-09-07-populate-stream-census` (19-22 of 30 positions differ between back-to-back
launches of one JVM with one jar set).
