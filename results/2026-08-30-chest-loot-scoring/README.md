# Chest-loot seed scoring against a speedrun value table

Ranks 100 random seeds by the chest loot near spawn, weighted by a user-supplied item value table,
and reports the top 10 with coordinates and `/tp` commands. Result is [top10.md](top10.md).

GTNH daily-707, repo `d17a685` (`v0.5-31-gd17a685`) with the uncommitted `BiomeRegionPrefilter` work
in the tree. Fix jar `gtnhdeterminism-test.jar` md5 `a1da08af02c2b7e305cfab96f764d30f` on both
servers.

Two probe jars were used, and the difference does not touch chest data:

| stage | server dir | probe md5 |
| --- | --- | --- |
| stage 0 | `~/.cache/gtnh-determinism/prefilter/daily707` | `35a73ca2f49196b0e83fe49b6f57e6e2` |
| full generation | `~/.cache/gtnh-determinism/daily-707` | `b8f16e453e1a222737c0fdc5ca22037b` |

The stage-0 jar was rebuilt from the working tree for this run; the full-gen server kept the jar it
already had. The only `WorldgenProbe.java` difference between them is the field separator in
`dumpEntityCensus`'s digest (`" "` to `"\0"`). `dumpInventory` and the search report's chest arrays
are identical, so the chest measurements are comparable.

Reproduce with [run.sh](run.sh).

## Method

Two passes, because they answer different questions.

**Stage 0** ranks all 100 seeds worldlessly, at about 1 seed/s behind a single ~90 s boot. It sees
Roguelike dungeon chests (the mod's real generator run against virgin terrain) and village piece
chests (F10's fork rebuilt from the piece layout). It sees nothing else.

**Full generation** regenerates only the top 10 at radius 15, which adds every remaining chest
source and supplies real Y for the coordinates. 135 s wall for the batch: 49 s of boot, then 8 to
10 s per seed. That is well under the ~20 s/seed the plan assumed, because `seed-search.sh` sets
`PROBE_DIM0ONLY=true` and `PROBE_NOHASH=true`, and because the page cache was already warm from the
stage-0 run.

Seeds come from `random.Random(707)` in Python, written to [seeds-100.txt](seeds-100.txt) rather than
drawn by `prefilter.sh`'s `random:N:SEED` spec, so the list is reproducible without depending on a
Java RNG and can be replayed by the full-gen pass verbatim.

Scoring: per seed, sum `n` per item across every chest within 15 chunks (chebyshev) of the spawn
chunk, then `score = Σ value × min(quantity, limit)`. Item names match on the probe's `name` field
after stripping section-sign colour codes from both sides.

## Results

Full-generation ranking, with the stage-0 score that selected each seed:

| rank | seed | full-gen | stage-0 | blind set | chests |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | `-6292213143640744246` | 119033 | 84401 | 34632 | 202 |
| 2 | `-5906397841852343037` | 113029 | 76988 | 36041 | 205 |
| 3 | `8714553887829585232` | 104482 | 71169 | 33313 | 182 |
| 4 | `-6244135143557849758` | 103920 | 67624 | 36296 | 213 |
| 5 | `495433077778118046` | 101659 | 71253 | 30406 | 183 |
| 6 | `-6701391002807803687` | 100062 | 72980 | 27082 | 200 |
| 7 | `-5407451111107001215` | 99639 | 68013 | 31626 | 176 |
| 8 | `-8168323205341578169` | 94207 | 71862 | 22345 | 158 |
| 9 | `261155990197320239` | 88559 | 72129 | 16430 | 178 |
| 10 | `6448274471135530858` | 80635 | 62049 | 18586 | 168 |

## Did stage 0 predict what full generation produced?

Yes, exactly, inside the scored window.

```
=== prefilter-judge-chests: 10 seeds, radius 15 chunks around spawn ===
predicted chest positions      : 19
  present in the corpus        : 19
  predicted but ABSENT         : 0
  dropped, outside the window  : 20
contents identical at matched  : 19 of 19
NBT identical at those         : 19 of 19
```

Run without a radius the same data reads 39 predicted / 17 absent, which is wrong and was the
initial result here. All 17 sit at chunk distance 16 to 26: the prefilter scans village cells around
the **origin** to its own radius, while the corpus covers a window around **spawn**, so those chests
were counted absent from ground the corpus never examined. `prefilter-judge-chests.py` grew a
`--radius` flag and a warning for this run; without it the script silently reports a defect that is
not there.

## Four things worth knowing before using these numbers

**The stage-0 ranking is mostly "is there a Roguelike dungeon near spawn".** 71 of the 100 seeds have
zero Roguelike chests within 15 chunks. The 29 that do score 5890 to 84401; the 71 that do not score
0 to 7215. Every seed in the top 10 has 109 to 146 Roguelike chests. There is real loot
differentiation among dungeon-bearing seeds — rank 1 has fewer chests than rank 9 — but the first cut
is close to binary, and a 100-seed sample is a weak search for the rare high-value entries.

**The blind set moved the ranking, by a lot.** Full generation adds a mean of 28676 points per seed
(286757 over the 10). More important than the size is the churn: stage-0 rank 7 finishes 3rd,
stage-0 rank 3 finishes 6th, stage-0 rank 9 finishes 4th. Only ranks 1, 2 and 10 keep their position.
A stage-0 score is a good *filter* and not a ranking you should trust to the last place.

**The Limit column never bound at stage 0, and bound twice under full generation.** No item in any
stage-0 top-10 seed reached its cap. Under full generation seed `-5906397841852343037` loses 5000
points to the Division Sigil cap (limit 1, found twice) and `-6701391002807803687` loses 2500. So the
cap semantics matter only once the non-Roguelike sources are visible.

**Village chest coverage is a floor, not the truth.** The run reported **148 piece classes absent from
`chest-sites.json`**, spot-checked against the `chestless` list and genuinely unknown rather than
known-empty — the table was measured on a 7-seed corpus and a 100-seed sweep meets far more pieces.
It also refused **14 chest sites** whose roll count is not drawn from the loot table (all Witchery
village pieces plus `ComponentVillageBeeHouse`), and `towerChestContents` because its live table
reads `0..0` in the prefilter process. Every one of those is announced per run rather than silently
skipped, so "predicted nothing" never reads as "there is nothing".

## Value-table coverage

| | stage 0 | full generation |
| --- | ---: | ---: |
| item stacks in scope | 53419 | 28470 |
| matched by the value table | 10567 | 7181 |
| unvalued | 42852 | 21289 |
| stacks with no display name | 0 | 0 |
| table entries that appeared zero times | 131 of 225 | 124 of 225 |

The stage-0 numbers cover all 100 seeds; the full-generation numbers cover only the 10 winners, which
is why the totals are smaller.

**124 of 225 value-table entries never appeared even under full generation**, including
`Iron Capped Wooden Wand` (5000) and every one of the 2500-point items except `Division Sigil` and
`Miner's Backpack`. Some are simply rare. Others cannot appear at all: `Coal Jetpack`,
`Enchantment Table`, `Brewing Stand`, `Solid Fueled Boiler Firebox` and `High Pressure Boiler Tank`
come only from the `chest1`-`chest4` tables, which are LootGames **minigame rewards** rolled when a
player wins a stage, not worldgen chest loot. `Platinum Ingot` comes only from `pyramidDesertyChest`,
and RWG never constructs `MapGenScatteredFeature`, so no pyramid exists in this pack's overworld.

`Alumite Large Plate` (10000), the table's most valuable entry, did appear — twice, in two different
seeds, both 5 chunks from spawn: `-6292213143640744246` at `69, 21, 184` and `-8168323205341578169`
at `119, 20, 269`.

**Duplicate value row**: `Ardite Tough Rod` is listed at both 600 and 400. The scorer takes the
maximum and prints the resolution.

## Files

| file | what |
| --- | --- |
| `top10.md` | the deliverable — top 10 with chests, coordinates and `/tp` |
| `seeds-100.txt` | the seed list, `random.Random(707)` |
| `top10-seeds.txt` | the winners, input to the full-gen pass |
| `value-table.csv` | the user's table as supplied |
| `stage0-score.txt` | stage-0 ranking and diagnostics, 100 seeds |
| `fullgen-score.txt` | full-generation ranking and diagnostics, 10 seeds |
| `judge-chests.txt` | stage-0 vs full-generation chest agreement |
| `run.sh` | reproduces all of it |

Gitignored, so present locally but not committed: `prefilter-0.5-d17a685-gtnhdaily707-100-chest-loot-r15.jsonl`, `fullgen/seed-*.json`,
`biomes.json`, and `prefilter-100.log` — the last holds the refusal and unknown-piece lists quoted
above.
