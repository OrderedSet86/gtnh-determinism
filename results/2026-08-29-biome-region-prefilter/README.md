# Stage-0 no-rain region + humid neighbour — GTNH daily-707, 2026-08-29

Pack: GTNH daily-2026-08-28+707, `~/.cache/gtnh-determinism/daily-707`, run from a hardlink clone at
`~/.cache/gtnh-determinism/prefilter/daily707`. Repo commit `d17a685`.
Probe jar `worldgenprobe-v0.5-main.26+d17a685496-dirty.jar` md5 `35a73ca2f49196b0e83fe49b6f57e6e2`.
Fix jar `gtnhdeterminism-test.jar` md5 `a1da08af02c2b7e305cfab96f764d30f`.
Report format 7 (adds per-chunk `biomeCounts` and the `biomes.json` sidecar).

```bash
# ground truth
PROBE_XMX=10G ./scripts/seed-search.sh ~/.cache/gtnh-determinism/prefilter/daily707 rand24.txt truth 15 12

# stage 0, golden-test configuration: radius 15 to match the corpus window, so the cache has to be
# raised to cover it. For a real sweep use the free configuration instead (see Cost).
PREFILTER_SERVER=~/.cache/gtnh-determinism/prefilter/daily707 \
PREFILTER_RADIUS=8 PREFILTER_TERRAIN=4 \
PROBE_JVMFLAGS="-Dprobe.prefilter.biomeregion=15 -Dprobe.prefilter.chunkcache=1200" \
  ./scripts/prefilter.sh @rand24.txt stage0.jsonl

# sweep configuration, terrain digest out to 15 chunks: 337 ms/seed (see Cost).
# The biome window stays route-local and inside the terrain window, so it costs nothing.
PREFILTER_RADIUS=64 PREFILTER_TERRAIN=15 PREFILTER_GATE_WATER=32 \
PROBE_JVMFLAGS="-Dprobe.prefilter.biomeregion=4 \
                -Dprobe.prefilter.gate.waterradius=4 \
                -Dprobe.prefilter.chunkcache=1200"

./seedsearch/prefilter-judge.py stage0.jsonl truth
```

## What the question turned out to be

"No rain" is four biome IDs and nothing else in the pack: **Hot River 207, Hot Plains 228, Hot Forest
229, Hot Desert 230**. No BiomesOPlenty biome reachable through RWG calls `setDisableRain()` — the only
one that does, `BiomeGenWasteland`, is commented out of `SupportBOP`.

"High humidity" on this pack line is CropsNH, not IC2. CropsPP is absent from daily-707 and nothing else
fills IC2's humidity table — stock IC2 declares `addBiomehumidityBonus` and calls it zero times — so
`getHumidityBiomeBonus` returns 0 for every biome here. CropsNH scores humidity as a continuous ramp on
`biome.rainfall`, read out of `TileEntityCropSticks.getNutrientsPerCycle`:

```
humidityBonus = (int) (clamp(0, 1, (rainfall - 0.5f) / 0.3f) * 14f)
```

saturating at rainfall 0.8, so the eight BOP biomes sitting exactly at 0.8 score the full 14. 24 of the
59 reachable overworld biomes reach 14. **No biome is both no-rain and humid**, so this is a genuine
adjacency search rather than a coincidence.

The confound that shapes the whole design: **228, 229 and 230 all paint their rivers with either Hot
River 207 (also no-rain) or River Oasis 211 (rains, and is itself humidity 14)**. One river column
through a desert chunk breaks the no-rain square and supplies its humid neighbour at the same time.
This is now machine-readable in the `rwgRivers` section of `biomes.json`.

## Accuracy: exact

24 random seeds, default `confirm=-1` (whole window), against full-generation reports for the same seeds.
Run at both radius 15 (`judge.txt`) and the recommended radius 8 (`judge-radius8.txt`); both are exact.

| field | exact | mismatched |
|---|---:|---:|
| largest no-rain square, side | 24/24 | **0** |
| largest humid square, side | 24/24 | **0** |
| gap from square to nearest humid chunk | 24/24 | **0** |
| >=5x5 gate verdict | 24/24 | **0** |

Predicted spawn, which anchors the window, was also 24/24 exact.

This is exactness by construction rather than by luck: in `confirm=-1` the module reads the same
generated per-column biome data the corpus reports, through `Chunk.getBiomeGenForWorldCoords`.

## The lattice screen cannot gate, and that is the main finding

The first two versions used the cheap `ChunkManagerRealistic.getBiomeGenAt` lattice as a kill gate. Both
dropped qualifying seeds:

| seed | lattice says | truth |
|---|---:|---:|
| 590749103374828709 | side 0 | **13** |
| 681789490782100011 | side 0 | 5 |
| 5473170400133757082 | side 2 | 5 |

The same three seeds failed under the strict rule (all four of a chunk's lattice points no-rain) and
under the permissive one (any point). The cause is structural: the generator blurs its input lattice
through a 17x17 parabolic window spanning +/-72 blocks, so a chunk can come out uniformly desert with no
desert at all among its own lattice points. **The lattice is not an upper bound on the output in either
direction**, so no early-out built on it is sound. `confirm=-1` therefore skips the screen entirely and
confirms every cell; a positive `confirm` budget keeps the screen and trades recall for speed, and marks
the rows it degraded with tier `A`/`Ab`.

This is why a cheap biome prefilter does not exist for this predicate. Unlike villages (a closed formula)
or Roguelike dungeons (pure RNG), both halves of this question depend on the generated per-column biome
array, and generating those chunks is the cost.

## Determinism

Two full runs of the same 24 seeds with the same jar produced identical `biomeregion` output for every
seed.

## Cost

### The biome criterion on its own

This is the number to quote when the biome region **is** the query — no village, pieces or water
criteria. Every other table below inherits the coke-funnel gates, which kill about 85% of seeds before
the biome stage runs and therefore understate this stage by roughly an order of magnitude. 100 seeds,
no gates, `PREFILTER_RADIUS=0 PREFILTER_PIECES=false`, so every seed pays in full:

| | wall clock | per seed | biome delta |
|---|---:|---:|---:|
| per-seed floor (no spawn, no biome) | 0.2 s | 2 ms | — |
| spawn walk only (`terrain=0`) | 13.2 s | 132 ms | — |
| + biome radius 4 (81 chunks) | 23.0 s | 230 ms | +126 ms |
| + biome radius 8 (289 chunks) | 60.3 s | 603 ms | +499 ms |
| + biome radius 15 (961 chunks) | 176.0 s | 1760 ms | +1656 ms |

Two costs, both chunk generation at ~1.7 ms/chunk, and at small radius they are comparable:

- **The spawn walk, ~130 ms/seed.** `WorldServer.createSpawnPosition` probes candidate positions until
  BOP's accept test passes, and each probe generates a whole chunk to read one column's top block.
  Measured over 100 seeds: median 12 iterations, mean 63, max 978.
- **The biome scan, 1.7 ms/chunk**, over `(2r+1)^2` chunks.

**There is no cheap pre-gate for this query.** The coke funnel works because villages are a closed
formula that kills most seeds before any chunk is generated. Here the criterion is the biome array
itself, so every seed pays. Anchoring the scan on the origin to skip the spawn walk does not work
either: spawn is a median 104 blocks from origin but p90 478 and max 1926, so only 74 of 100 seeds land
within radius 15 of it, and covering 91% would need radius 32 — a 4225-chunk scan, far worse than the
walk it avoids.

### Added to the existing coke-funnel sweep

Not the same question as the section above. This is the marginal cost of bolting the biome stage onto
a sweep that is *already* gating on villages, pieces and water, where those gates have killed most
seeds first. 2000 random seeds, village radius 64, `gate.villagedist=12`, `gate.pieces`,
`gate.water=32`, terrain radius 4:

| | wall clock | per seed |
|---|---:|---:|
| gated, no biome stage | 171.4 s | 85.7 ms |
| gated, `biomeregion=4` | 172.2 s | 86.1 ms |
| **attributable** | 0.8 s | **0.4 ms (0.5%)** |

The gates killed 1691 of 2000 seeds (1399 village, 292 water) before terrain ran, so only 309 seeds ever
reached the biome stage, at about 2.6 ms each. 85.7 ms/seed is the same baseline recorded elsewhere in
`results/`, so this is the configuration a sweep actually runs.

### At terrain radius 15

Terrain radius 4 is the default and every per-seed figure published for this prefilter, here and in
`results/2026-08-29-roguelike-prefilter`, assumes it. A sweep that needs the digest out to 15 chunks
pays 6.5x more, and the biome stage is not what costs. 100 seeds, gated, `biomeregion=4`:

| terrain radius | water gate at | digest path | per seed |
|---:|---:|---|---:|
| 4 | 4 | provider | 89 ms |
| 15 | 15 | provider | 569 ms |
| 15 | **4** | provider | **337 ms** |
| 15 | **4** | hand-rolled | **281 ms** |

The 490 ms that radius 15 adds is chunk generation for the terrain digest, at 1.86 ms/chunk over the
880 extra chunks. The biome stage rides along free at any radius inside the terrain window.

**`gate.waterradius` is where the win is.** The water gate counts columns over the whole terrain
window, so its meaning drifts with the radius: 32 columns inside 81 chunks means "water at spawn", 32
inside 961 means "water somewhere within 240 blocks", which nearly every seed satisfies. At radius 15
the gate stops killing and every village survivor pays all 961 chunks. Pinning the gate to a
route-local window restores the radius-4 meaning while the digest still covers 15 for ranking, and
kills the failures after 81 chunks instead of 961 — 14 of 30 survivors on this sample, 41% off the
wall clock.

Verified data-preserving on the same 100 seeds: gate@4 survivors are a strict subset of gate@15
survivors, and for all 16 common survivors `terrain`, `water_total`, `spawn`, `villages`,
`village_starts` and `biomeregion` are identical. Generating the inner window first does not perturb
the village passes.

It is a deliberate *semantic* change, not a free speedup: `gate.water=32` with
`gate.waterradius=4` means water within 64 blocks, which is stricter than the radius-15 reading and
drops seeds the wide gate keeps. That is the intended route-local meaning, but it must be chosen, so
the flag defaults to the terrain radius and changes nothing until set.

The hand-rolled digest row (`digestviaprovider=false`) was also identical on all 16 survivors, but
that path is off by default upstream because it skips the `generateMapGen` pass, and 16 seeds is thin
evidence. Treat the 337 ms row as the supported number and the 281 ms row as needing a wider golden
test first.

### Why the ungated numbers look worse

The stage reads generated chunks, so its whole cost is whether those chunks are already in
`VirginChunkProvider`'s LRU. Configured well it is free; configured badly it costs 400x more, with no
error and no difference in output. A/B on 24 seeds with **no gates**, so every seed pays the full terrain
digest — these rows isolate the stage, they are not sweep costs:

| terrain radius | chunkcache | biome radius | wall clock | per seed | attributable |
|---:|---:|---:|---:|---:|---:|
| 4 | 256 (default) | off | 6.7 s | 279 ms | — |
| 4 | 256 | **4** | 6.6 s | 275 ms | **~0** |
| 8 | 400 | off | 15.8 s | 658 ms | — |
| 8 | 400 | **8** | 15.9 s | 663 ms | **+4 ms** |
| 4 | 256 | 15 | 46.1 s | 1921 ms | +1642 ms |

The rule is one line: **keep the biome radius inside the terrain radius and the chunk cache at least as
big as the window, and the stage is free.** The terrain digest has already generated exactly those
chunks for the water gate; the biome pass re-reads them out of the LRU and `chunks_regenerated` stays 0.

The 1642 ms row is what happens when neither holds. Radius 15 needs 961 chunks against a 256-entry
cache, so the terrain stage's chunks are evicted and regenerated, and 880 chunks beyond the terrain
radius are generated for this stage alone. That row is a misconfiguration, not the cost of the feature.
`Prefilter.warnBiomeRegionCost` now logs both conditions at startup rather than absorbing them.

Read the two tables together. The ungated rows are 3 to 22x the gated per-seed cost for the same terrain
radius, because with no gates every seed pays a full terrain digest that a real sweep skips for 85% of
seeds. Neither table is wrong; they answer different questions, and quoting an ungated row as a sweep
cost is the mistake this section exists to prevent.

`-Dprobe.prefilter.biomeregion` defaults to `-1` (off), so nothing above changes any existing sweep.

## Base rates — indicative only, n=24

Not error-barred. 24 seeds establishes exactness, not a distribution.

| criterion (radius 15 of spawn, all 256 columns) | seeds |
|---|---:|
| >=5x5 no-rain square | 12/24 |
| ...and a humidity-14 chunk anywhere in the window | 12/24 |
| ...and that chunk within 2 chunks of the square | 9/24 |
| >=5x5 no-rain AND >=5x5 humid square | 6/24 |
| spawn chunk itself no-rain, plus both >=5x5 | 2/24 |

**The stated criterion is not selective.** About half of random seeds already carry a >=5x5 no-rain
square near spawn, and nearly all of those have a humid chunk beside it. Requiring both regions to be
>=5x5 is the variant that discriminates, and it does so for a structural reason: the two are
anti-correlated, because RWG picks the climate band from a single cell-noise field, so a window is
mostly hot band or mostly wet band. A window holding a large amount of both straddles the band
boundary. In this sample the largest no-rain square of 24 chunks came with a humid square of 3, and the
largest humid square of 28 came with no no-rain square at all.

## Known divergence, not yet measured

- **Post-population biome rewrites.** The corpus is post-population and the prefilter reads virgin
  chunks. Thaumcraft and Witchery can rewrite biome arrays during population. No disagreement appeared in
  these 24 seeds, which bounds it as rare near spawn but does not establish it as zero.
- **Radius sensitivity.** Everything here is radius 15 around the predicted spawn, chosen to match the
  corpus window. A region straddling the window edge is reported as truncated, not as missing, and no
  measurement of how often that happens has been made.
- **`EndlessIDs` forbids `Chunk.getBiomeArray()`** and crashes the run deliberately. Both the probe and
  the module read biomes column by column instead. Any future code touching biome arrays on a 2.9/daily
  pack has to do the same.

## Follow-ups

- Widen the base-rate sample; n=24 supports none of the percentages above as distribution facts.
- Decide whether the operative criterion is "both regions >=5x5" or something tighter, and pin the gate
  to it. `PREFILTER_GATE_BIOMEREGION=SIDE[,MAXGAP]` currently gates on the no-rain side and the gap only.
- The gate has no humid-side-size term. If "both >=5x5" is the real requirement, `hn` is already emitted
  and the gate should read it.
