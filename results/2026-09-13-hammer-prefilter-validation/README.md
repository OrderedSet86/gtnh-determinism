# Hammer% prefilter validation: mineshaft multiseed + prediction golden + 2k calibration

Three measurements gating the hammer% hunt (smeltery village + 4 Block of Steel + Roguelike
TOOLS parts within 20 chunks of spawn). Jars: probe `worldgenprobe-v0.8-main.9+970e4eafdf-dirty`
md5 `2dba190d7cd749827a4b8b432ddb4912` (House2 rows included), fix
`gtnhdeterminism-v0.8-main.9+970e4eafdf-dirty` md5 `ccadf0c801f8b994da6ffb581de695b6`
(MineshaftCorridorChestMixin pin ON in both arms). Pack: 2.8.4 template. "Block of Steel" is
matched by exact display-name equality everywhere — `Steel Ingot` cannot count.

## 1. Mineshaft route stability, 25 seeds, rows vs spiral — 4 existence, 0 contents

Seeds: `results/2026-09-07-roguelike-multiseed/seeds.txt`, warm r30, `PROBE_SEARCH=true`,
4 shards (2.8.4 clones `hammer-ms-s0..3`). Compared with
`scripts/diff-chests.py --type=EntityMinecartChest` (minecarts enter the report's `chests`
array as of this build). Extracts committed as `rows.minecarts.json` / `spiral.minecarts.json`.

| | |
| --- | ---: |
| minecart chests, rows / spiral | 564 / 562 |
| existence differences | **4** (3 rows-only, 1 spiral-only, 4 different seeds) |
| contents differences | **0** |
| NBT-only differences | **0** |

Whole-corpus context on the same 25 seeds: 131+ chest existence differences (the known
route-dependent background — greatwood, lakes, Y-shifts) and 0 contents differences anywhere.
Minecarts at 4/564 (0.7%) sit two orders below the world background.

The 4 residuals are the deliberately un-pinned mechanisms (`isLiquidInStructureBoundingBox`
window-scoped live reads, the chest-cell air check): the existence DRAWS are pinned, the
terrain verdicts are not. CAVEAT: a stock-pin baseline (`-Dgtnhdet.mineshaftpin=false`, same
arms) was NOT run — the pin's before/after delta is uncharacterized; 0.7% is the "after" only.

## 2. Prediction golden: prefilter vs full-gen (rows arm), 25 seeds

`prefilter.sh` on the same seeds (r45, `mineshaft=45`, `mineshaftloot=true`,
`gate.steel=0,900` for wide sim coverage, `chests=true`), judged with
`seedsearch/prefilter-judge-chests.py --radius 30` (now reads the `mineshafts` field).

| | |
| --- | ---: |
| predicted chests in window | 652 |
| present in corpus | 643 (**9 absent = 1.4%**) |
| contents identical where claimed | **531 / 531** (112 position-only rows refused on purpose) |
| NBT identical | 531 / 531 |
| miscategorised | 0 |

- The absent 9 are all minecarts — sim-vs-game existence residual, same mechanisms as §1.
- Reverse direction: 16 in-game minecarts unpredicted; **12 of them belong to one seed's
  mineshaft whose start cells sit beyond the 45-chunk origin scan** (chests at x≈890-1020).
  Coverage bound, not a defect: **run the real sweep with `PREFILTER_MINESHAFT=55`** so
  starts reachable from a p90-displaced spawn + 320 blocks are always enumerated. The other
  4 (~1%) are the same existence residual as the absents.
- **House2 vanilla blacksmith (the 2.8.4 gap): 15 predicted, 15 present, 15 contents
  byte-identical.** The hand-added `chest-sites.json` rows (caller-local 5,1,5, all four
  orientations) are hereby measured, not just transcribed. On this template the blacksmith
  is vanilla `StructureVillagePieces$House2` — the VN `*Weaponsmith*` rows never fire.
- Net for the steel gate: contents are exact; existence carries ~1% symmetric error, so a
  survivor's steel_total is a strong estimate, and winners get in-world verification anyway.

## 3. Calibration, 2000 random seeds (salt 20260913, `calib-seeds.txt`), gates off

Single-tier sweep, 4 shards, all hammer stages on, zero-bar `gate.toolloot` so `tools_agg`
is emitted per seed. 805 ms/seed ungated; the gated funnel (40-seed smoke at the real bars)
runs 148 ms/seed. NOTE: ran WITHOUT the House2 rows (blacksmith_steel reads 0 throughout);
mineshaft-only distributions below are unaffected.

| stage | ms/seed | share |
| --- | ---: | ---: |
| mineshaft trig + starts | 21 | 3% |
| spawn walk + terrain digest | 277 | 34% |
| mineshaft loot sim | 194 | 24% |
| roguelike dungeons (all in 480 of spawn) | 305 | 38% |

Distributions within 320 blocks of spawn (n=2000):

| bar | seeds ≥ bar |
| --- | ---: |
| steel_total ≥ 1 / 2 / 3 / 4 | 333 / 70 / 10 / **3** |
| alumite_plate ≥ 1 / 2 | 136 / **10** |
| plate ≥ 4 | 179 |
| hammer_head ≥ 1 | 458 |
| tough_rod ≥ 1 | 696 |
| all four loot bars jointly | **8** |
| loot bars + steel ≥ 1 | 2 |
| loot bars + steel ≥ 4 | 0 |

With the smeltery-within-320 requirement (~12.5% of seeds, 40-seed smoke), the full-bars
joint rate extrapolates to ~1 survivor per ~1.3M seeds; at 148 ms/seed that is ~2-3
survivors/day on 4-6 shards. `steel>=3` instead of 4 triples the yield. Kill rows carry
every count, so bars can be lowered post-hoc without re-sweeping.

## Recommended sweep invocation

```
SWEEP_SINGLE_TIER=1 SHARD_ROOT=~/.cache/gtnh-determinism/hammer-shards \
PREFILTER_TEMPLATE=~/.cache/gtnh-determinism/prefilter/server \
PF2_RADIUS=45 \
PREFILTER_MINESHAFT=55 PREFILTER_MINESHAFTLOOT=true \
PREFILTER_GATE_MINESHAFTTRIG=45 PREFILTER_GATE_ROGUETRIG=45 \
PREFILTER_GATE_VILLAGEDIST=45 PREFILTER_GATE_PIECES=ComponentSmeltery \
PREFILTER_GATE_VILLAGEPIECE=ComponentSmeltery,320 \
PREFILTER_GATE_STEEL=4,320 \
PREFILTER_GATE_TOOLLOOT=alumite=2,plate=4,hammer=1,rod=1,dist=320 \
SWEEP_T2_FLAGS="-Dprobe.prefilter.dungeon=26 -Dprobe.prefilter.chests=true \
  -Dprobe.prefilter.chunkcache=2048 -Dprobe.prefilter.timing=true" \
./scripts/prefilter-sweep.sh <seeds.txt> <out-dir> 6 6
python3 seedsearch/hammer-hunt.py <out-dir>/tier2.jsonl
```

## Remaining before trusting winners end-to-end

- tools_agg counting spot-check vs a full-gen roguelike dump (chests themselves are already
  proven byte-identical; only the category counting is new).
- Stock-pin A/B if the pin's delta ever needs quoting.
- Winners verified in-world; if the run happens on 2.9.0-beta-3, re-run survivors on a beta3
  prefilter template first (theme resolution flips at biome borders and TOOLS loot rides it).
