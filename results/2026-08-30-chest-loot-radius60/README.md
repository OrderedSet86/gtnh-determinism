# Chest-loot scoring at radius 60 — throughput test

Timing run for a wide-radius stage-0 sweep, on 10 seeds, against the revised value table. No
full-generation pass. Companion to `results/2026-08-30-chest-loot-scoring/`, which did the same at
radius 15 with the earlier table.

GTNH daily-707, repo `d17a685`, server `~/.cache/gtnh-determinism/prefilter/daily707`, probe
`35a73ca2f49196b0e83fe49b6f57e6e2`, fix jar `a1da08af02c2b7e305cfab96f764d30f`.

```sh
PREFILTER_SERVER=~/.cache/gtnh-determinism/prefilter/daily707 \
PREFILTER_RADIUS=70 PREFILTER_TERRAIN=4 \
PROBE_JVMFLAGS="-Dprobe.prefilter.dungeon=60 -Dprobe.prefilter.villagechests=true -Dprobe.prefilter.chunkcache=2048" \
  ./scripts/prefilter.sh @seeds-10.txt prefilter-0.5-d17a685-gtnhdaily707-10-chest-loot-r60.jsonl

seedsearch/loot-score.py value-table.csv prefilter-0.5-d17a685-gtnhdaily707-10-chest-loot-r60.jsonl --radius 60 --top 10 --chests 2 \
  --loot-tables ~/.cache/gtnh-determinism/gtnh-daily707-chestloot-v2.csv
```

`PREFILTER_RADIUS=70` is the village cell scan radius around the **origin** and must exceed the
scoring radius, because spawn is offset from the origin. `chunkcache=2048` (default 256) keeps the
r=60 dungeon scan from thrashing the LRU; peak RSS was 6.9 GB against a 6 G heap.

## Throughput

| | |
| --- | ---: |
| boot | 56 s |
| scan, 10 seeds | 27 s |
| **per seed** | **2.7 s** |
| peak RSS | 6.9 GB |

Extrapolating, one boot amortised:

| seeds | wall |
| ---: | ---: |
| 100 | 5.4 min |
| 1000 | 46 min |
| 5000 | 3.8 h |
| 10000 | 7.5 h |

Radius 60 costs about 2.7× per seed against radius 15's ~1 s, for 16× the area. Sublinear because
the Roguelike trigger scan is arithmetic and only construction (~193 ms/dungeon) scales with the
count.

## Yield: radius 60 removes the sampling problem

At radius 15, **71 of 100 seeds had no Roguelike dungeon at all**, so the ranking was close to a
coin-flip on dungeon presence. At radius 60 every seed has 4 to 8:

| | radius 15 | radius 60 |
| --- | ---: | ---: |
| seeds with zero Roguelike dungeons | 71 of 100 | **0 of 10** |
| Roguelike chests per seed | ~120, for the 29% that had any | **574** |
| village chests per seed | ~4 | **56** |
| dungeons per seed | 0 to 1 | 4 to 8 |

Scores now spread 159014 to 292147 across the 10 seeds — a 1.8× spread driven by loot, not by
whether a dungeon exists.

## Results, 10 seeds

| rank | seed | score | capped away | chests in scope |
| ---: | --- | ---: | ---: | ---: |
| 1 | `-8607522308064511141` | 292147 | 5400 | 730 |
| 2 | `-1297854885530077460` | 282075 | 5100 | 705 |
| 3 | `-8168323205341578169` | 280345 | 4200 | 728 |
| 4 | `-7269948338495788698` | 237170 | 3400 | 695 |
| 5 | `5640528374229792510` | 205592 | 4400 | 576 |
| 6 | `-1497857703099570772` | 203578 | 2600 | 711 |
| 7 | `-6270331762397506834` | 203341 | 4000 | 523 |
| 8 | `-6244135143557849758` | 179854 | 2600 | 487 |
| 9 | `-8952052010658893278` | 167038 | 3300 | 545 |
| 10 | `-9107171013753322724` | 159014 | 3500 | 441 |

Full output with representative chests and `/tp` in [score-10.txt](score-10.txt).

**The Limit column now binds on every seed**, 2600 to 5400 points each. At radius 15 with the old
table it bound on nothing at stage 0. Wide radius plus the new caps on `Plant Lens` (2) and
`Potion of Swiftness` (10) is what changed.

## A bug this run caught

The first attempt used `PREFILTER_TERRAIN=-1` to skip the unused terrain digest. That silently
produced `spawn [0, 0, 0]` for every seed: `Prefilter.java:1194-1195` only computes the spawn point
when `probe.prefilter.terrain >= 0`, and line 1468 centres the Roguelike scan on it. The sweep
therefore searched around the origin, and every reported distance-from-spawn was wrong — with no
error anywhere, because the origin is a valid coordinate.

`loot-score.py` now refuses a stage-0 file whose records carry no `spawn` key rather than scoring it
against `[0, 0, 0]`. Keep `PREFILTER_TERRAIN >= 0` even when the digest itself is not wanted.

## Blind sources at this radius, against this table

Computed by `--loot-tables` rather than hardcoded, since every figure is a function of the value
table and the old constants were wrong the moment the table changed:

| source | seen | EV/chest |
| --- | --- | ---: |
| roguelike | yes | 25211 |
| village pieces | yes | 3369 |
| **stronghold** | no | **1594** |
| chest1-4 | no | 1169 |
| pyramid / igloo | no | 910 |
| vanilla `WorldGenDungeons` | no | 872 |
| mineshaft | no | 323 |

**Radius 60 changes the stronghold conclusion.** The earlier plan deprioritised strongholds because
the first ring sits 640-1150 blocks out, beyond a 240-block window. A 960-block window contains it.
Strongholds are now the largest reachable blind source, and they are also the cheapest to add:
`MapGenStronghold` is instantiated unconditionally by `ChunkGeneratorRealistic`, and
`ChunkManagerRealistic.findBiomePosition` returns `null`, so the biome viability relocation never
runs and the three ring positions are raw arithmetic.

## Value-table coverage

| | |
| --- | ---: |
| item stacks in scope | 110702 |
| matched by the value table | 21100 |
| unvalued | 89602 |
| stacks with no display name | 0 |
| entries that appeared zero times | 53 of 151 |

**Six entries no seed search can ever score**, because their only loot source cannot place an item in
a worldgen chest — the scorer now flags these explicitly:

| value | item | only source |
| ---: | --- | --- |
| 500 | Platinum Ingot | pyramid / igloo (dead in this pack) |
| 100 | Zero Point Module | pyramid / igloo (dead in this pack) |
| 100 | TNT | chest1-4 (LootGames reward) |
| 100 | Red Alloy Plate | chest1-4 (LootGames reward) |
| 100 | Bronze Fluid Pipe | chest1-4 (LootGames reward) |
| 20 | Machine Controller Cover | chest1-4 (LootGames reward) |

Top unvalued items by count, if the table should grow: Ice 29405, Gravel 24988, Bone 22517,
Torch 18796, Arrow of Weakness 16489.

## Reading the output

Scope is **chebyshev distance in chunks** from the spawn chunk, matching `searchlib.SeedReport._near`.
The per-chest "blocks from spawn" figure is straight-line distance, so it can exceed `radius × 16`;
a corner chest at chunk distance 54 shows 1207 blocks. Both numbers are correct, they measure
different things.

## Files

| file | what |
| --- | --- |
| `score-10.txt` | ranking, representative chests, `/tp`, diagnostics |
| `seeds-10.txt` | the seeds, first 10 of the radius-15 run's list |
| `value-table.csv` | the revised table, 151 items |
| `run.sh` | reproduces it |

`prefilter-0.5-d17a685-gtnhdaily707-10-chest-loot-r60.jsonl` (63 MB) and `prefilter-10.log` are gitignored.
