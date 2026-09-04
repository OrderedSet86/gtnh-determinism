# Stage-0 prefilter speedup: measurements, one win, two refuted hypotheses

GTNH daily-707, repo `d17a685`. All figures measured with `-Dprobe.prefilter.timing=true`, which this
work added — before it there was **no per-stage timing anywhere** in the prefilter, and the built-in
`seeds/s` line truncates to an int (it prints `0 seeds/s` for any run slower than 1 s/seed).

## Baseline cost model (200 seeds, radius 60, 1466 ms/seed)

| stage | ms/seed | % of wall |
| --- | ---: | ---: |
| `dungeon.generate` | 1171 | 79.8 |
| — of which chunk generation | 661 | 45 |
| — of which the Roguelike write path | 510 | 35 |
| `chunkgen` (all stages) | 904 | 61.7 |
| `terrain.digest` | 141 | 9.6 |
| `spawn.walk` | 107 | 7.3 |
| `villageStarts` | 38 | 2.6 |
| `dungeon.triggers` | 1.9 | 0.1 |
| `village.cellscan` | 0.95 | 0.1 |

538 chunks/seed at ~1.68 ms. **The village cell scan is 0.1%** — 19881 reflective
`canSpawnStructureAtCoords` calls per seed, and enumerating candidate cells from the region math
instead (exact, ~500x fewer calls) would buy nothing. Recorded so it is not proposed again.

Two traps that misled the analysis before it was instrumented:

- **`chunks_generated` in the JSONL is emitted *before* the dungeon stage.** Its mean of 120.6 excludes
  the ~74 chunks each dungeon generates. Differencing two runs on it "shows" that dungeons generate no
  chunks, which is false.
- The 10-seed radius-60 figure (2.70 s/seed) overstates the sustained 1.49 by 1.8x from JIT warmup.

## The win: a two-tier funnel, 12.4x on the cheap tier

`-Dprobe.prefilter.tier1=N` emits spawn plus Roguelike trigger count and nothing else — no village
starts, no chest prediction, no terrain digest, no dungeon construction.

| | ms/seed |
| --- | ---: |
| full pipeline | 1466 |
| **tier 1** | **118** |

Tier 2 then runs the unchanged pipeline over the top slice. **60/60 tier-2 rows byte-identical** to the
original single-process sweep, so every downstream tool reads them as before.

### Recall is measured, not assumed

Ranking by tier-1 trigger count over the 5000-seed corpus in
`results/2026-08-30-chest-loot-sweep-5000`, whose exact scores are known:

| cut | top-1 | top-5 | top-10 | top-25 | top-50 |
| ---: | --- | --- | --- | --- | --- |
| 2% | 1/1 | 3/5 | 4/10 | 15/25 | 27/50 |
| 5% | 1/1 | 4/5 | 8/10 | 22/25 | 40/50 |
| 7.5% | 1/1 | 5/5 | **9/10** | 23/25 | 45/50 |
| 15% | 1/1 | 5/5 | **10/10** | 25/25 | 50/50 |

> **Note (2026-08-30).** The exact scores this recall was measured against are superseded — later fix-jar
> changes altered chest contents (see `results/2026-08-30-chest-loot-sweep-5000`). The *method* and the
> tier-1/tier-2 equivalence stand; the specific retention numbers would need a re-run to re-assert.

`corr(score, triggers) = 0.707`. **A 1000-seed sample said 10/10 at 7.5%; the full 5000 says 9/10.**
Small-sample flattery, caught only by re-validating — which is why the cut is chosen from the N you
care about rather than from a headline.

## Refuted: skip the skylight pass (H2)

`Chunk.generateSkylightMap` is pure lighting, and the prefilter never reads light —
`SeedProbeWorld` no-ops `updateLightByType`, `markBlockForUpdate` and `notifyBlockChange`. Predicted
10-25% of a chunk. Measured over 100 seeds / 54 406 chunks:

| | per seed | per chunk | share of chunkgen |
| --- | ---: | ---: | ---: |
| `skylight` | 28.0 ms | 51.5 us | **3.0%** |
| `chunkgen` | 922 ms | 1695 us | — |

**1.8% of wall. Not worth shipping.** `SkylightMixin` is kept as instrumentation (default off);
`-Dprobe.prefilter.skipskylight=true` exists but is not recommended, and was never validated by
JSONL diff because the payoff did not justify the risk to `heightMap`.

## Refuted: an origin-centred trigger scan (would have made tier 1 need no spawn)

Spawn costs 107 ms — 96% of tier 1 — because `createSpawnPosition` probes candidates until BOP's
accept test passes and **each probe generates a whole 16x16x256 chunk to read one column's top
block** (mean 57 chunks/seed, 0.4% of the computed columns used). Centring the trigger scan on the
origin instead removes that need entirely. Measured over 5000 seeds:

| scan centre | best radius | corr with true score | true top-10 retained |
| --- | ---: | ---: | --- |
| spawn | 60 | **0.707** | 10/10 at a 15% cut |
| origin | 60 | 0.375 | 3/10 at a 15% cut |
| origin | 90 | 0.064 | 0/10 at any cut |

At radius 90 every seed has 9-16 triggers and the signal is gone. **The scoring window is centred on
spawn, so dungeon count only means anything relative to it.** Recentring destroys the proxy.

Distance weighting does not rescue it either — over 200 seeds with spawn-centred ring counts, raw
`t60` (0.711) beats every weighted variant, and the inner rings are *negatively* correlated
(`t45` −0.049, `t30` −0.043). Count within the radius is the signal; position inside it carries none.

## What this says about "you cannot make a chunk cheaper"

The repo's maxim was inherited from two experiments — parallel column noise and a fastnoise flat-table
backport — that were **both measured on the full-generation probe**, whose decoration stream the
prefilter does not have (`VirginChunkProvider.populate` is a no-op), on **RWG 1.5.0** when this pack
ships 1.5.2 and `LateMixinLoader.isRwg150()` disables them. Neither tested this workload, so neither
carried much likelihood ratio for it.

Re-tested directly, the maxim holds anyway, for a different and better reason: **the phases of
`provideChunk` that are provably irrelevant to a block read total ~3%.** Terrain noise, surface
painting, caves and the three structure map-gens can all alter a top block, so an *exact* column
oracle must run ~97% of a chunk. There is no cheap exact column.

The remaining levers are therefore the maxim's own other two branches — generate fewer chunks (the
tier-1 funnel, done) and generate them on more cores (sharding, done) — plus one item that is **not**
chunk generation at all: the Roguelike write path, 510 ms/seed, buffering ~670k `Write` objects per
seed into a map that `SliceApplier` never applies and never frees.

## Attempted: the Roguelike write path — null result

Direct attribution (`dungeon.chunkgen`, added for this) finally splits the dungeon stage:

| inside `dungeon.generate` (1241 ms/seed) | ms/seed | share |
| --- | ---: | ---: |
| chunk generation | **702** | 57% |
| write path (remainder) | **539** | 43% |

So the write path really is ~35% of a seed — the earlier inference was right — at ~670k writes and
**805 ns per write**.

Three changes were made to it, all semantics-preserving:

- `WorldEditorMixin.setBlock` called `getBlock()` **and** `isAirBlock()`, which answer the same
  three-way overlay question (buffered write, own write, terrain). Factored into one `gtnhdet$resolve`,
  removing two hash lookups and a `MetaBlock` allocation per write.
- `shouldBuffer` then `buffer` were two `synchronized` entries that each re-resolved per-world state.
  Merged into `PendingSlices.bufferIfNeeded`, one lock acquisition.
- The three per-world `WeakHashMap`s (`PENDING`/`APPLIED`/`DEFERRED`) were hit 2-3 times per write, and
  `WeakHashMap.get` drains its `ReferenceQueue` on every call. Added a one-element weak memo in front.

**Verified byte-identical: 100/100 JSONL rows, 549 dungeons.** And **no measurable speedup.**

| run | total ms/seed |
| --- | ---: |
| old jar #1 | 1569.2 |
| old jar #2 | 1478.1 |
| old jar #3 | 1543.6 |
| **new jar** | **1518.5** |

The old jar varies **6.0% run to run on identical code**, and the new jar lands inside that spread.
The expected saving (50-100 ns of 805 ns/write, i.e. 2-4% of a seed) is genuinely below the noise floor
of a 100-seed run; resolving it needs ~10 paired runs or a much longer one.

The method error worth recording: the 510 ms write-path figure was optimised against *before* it was
measured. It was inference stacked on inference — `dungeon.generate` minus a chunkgen share that was
itself derived by subtracting other stages' chunk counts. It happened to be right (539 measured), but
the first A/B reported "-3.2%" and only a repeat run of the **unchanged** jar revealed that as noise.
Always measure the noise floor before believing a single-run delta.

The change is built (`gtnhdeterminism` md5 `451d369f7ac12d9160e46554096dde85`) but **not deployed** —
modifying shipped determinism-critical code for an unmeasurable benefit is not obviously worth the risk.

## Files

| file | what |
| --- | --- |
| `timing-baseline-200.txt` | full-pipeline stage timings, 200 seeds |
| `timing-tier1-200.txt` | tier-1 stage timings, 200 seeds |
| `seeds-20k.txt` | 20 000 fresh seeds (stream positions 5000-24999, disjoint from the corpus) |

Tooling added: `Prefilter.Timing`, `-Dprobe.prefilter.tier1`, `SkylightMixin`,
`scripts/prefilter-sweep.sh` (two-tier sharded driver with a `CPU_MAX_PCT` budget).
