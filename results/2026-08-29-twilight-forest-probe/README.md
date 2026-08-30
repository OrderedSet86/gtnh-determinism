# Twilight Forest probe: ore veins and chest loot

The probe could only ever walk dimension 0. It can now walk any dimension, and the Twilight Forest is
the first one measured. This is also the first predictor-versus-corpus check this repo has run outside
the overworld.

Two questions drove it: where are the ore veins carrying the six Thaumcraft magic shards, and what is
in Twilight Forest chests.

## What shipped

- `-Dprobe.dim=N` selects the dimension to walk. `-Dprobe.dim0only` now means "dim 0 plus the
  dimension under test" rather than "dim 0". Report format bumped to 6: `dim`, `center` and
  `centerSource` are new, `spawnextra` and `villages` are empty outside dim 0.
- `-Dprobe.tffeatures=N` emits a per-region Twilight Forest feature map. It is independent of
  `probe.dim` and generates no chunks, so a dim-0 seed-search run can emit it for free.
- `chestloot.csv` gains a `tftreasure` source and an `enchant_level` column.
- `gtdims.json` beside `gtmats.json`: runtime dim id to GT ore-mix token.
- `vein_predict.py` predicts any dimension, returns vein geometry, and carries the oreseed grid.
- `seedsearch/tf-shard-veins.py` ranks shard-vein sites; `seedsearch/tests/validate-vein-predict.sh`
  is the golden test.

## The six shards come from three veins

Each of the three Twilight-Forest shard mixes carries a shard in both its primary and its secondary
slot, so three vein cells cover all six shards. Amber and Cinnabar are the between and sporadic
materials of all three, which is why neither can identify a mix.

| mix | primary | secondary |
|---|---|---|
| `ore.mix.aquaignis` | InfusedWater | InfusedFire |
| `ore.mix.terraaer` | InfusedEarth | InfusedAir |
| `ore.mix.perditioordo` | InfusedEntropy | InfusedOrder |

All three are weight 16 of the 1053 Twilight Forest weight, minY 5, maxY 20, size 16, density 2.
Neither shard of a pair is a trace amount: `WorldgenGTOreLayer` places the secondary on layers -1/0/1/2
relative to `tMinY` and the primary on 4/5/6/7, which puts the secondary at y 4-16 and the primary at
y 9-21.

## Predictor accuracy in the Twilight Forest

24 seeds, radius 10, **1485 oreseed cells** with a vein-scale ore census — `tf-vein-judge.py` output in
`vein-judge-24-seeds.txt`.

**Shard mixes: 51 of 52 predictions correct, 98.1% precision.** The pass criterion committed to in
advance was 85%.

| mix | predicted | correct | precision | recall |
|---|---|---|---|---|
| `ore.mix.aquaignis` | 20 | 20 | 100.0% | 37.0% |
| `ore.mix.terraaer` | 16 | 16 | 100.0% | 34.0% |
| `ore.mix.perditioordo` | 16 | 15 | 93.8% | 33.3% |
| all three | 52 | 51 | **98.1%** | 34.9% |

Band on the **rolled `tMinY`**, not on the mix's `minY` — the gate tests one block at `tMinY`, and
`ore.mix.gold` has minY 30 but rolls `tMinY` as high as 54. With the correct discriminator the split is
stark:

| predicted vein depth | exact |
|---|---|
| `tMinY` <= 30 (ground level) | **462 / 514 = 89.9%** |
| `tMinY` > 30 | **20 / 721 = 2.8%** |

Per-mix, everything at minY 5-10 predicts well — `tfgalena` 100%, `aquaignis` 100%, `terraaer` 100%,
`olivine` 95%, `perditioordo` 94%, `diamond` 94%, `sapphire` 86% — and everything that rolls above
ground collapses: gold 5%, salts 3%, apatite 2%, magnetite / coal / cassiterite 0%.

Caveat on the 98.1%: it is precision over cells whose census resolved to exactly one mix, the same
convention `prefilter-judge.py` uses. 14 further shard predictions landed on cells that could not be
resolved (207 cells corpus-wide read as `no-matching-mix`, most likely two veins overlapping in one
cell). Counting those as failures instead gives 77.3%.

### Recall is low on purpose, and it is measurable

The predictor finds about a third of the shard veins that exist, and almost never claims one that does
not. The 95 `reroll_beneficiary` cells say why: the corpus *gained* those shard veins because an
earlier high-band pick was rejected, and layer 1 by definition cannot see that.

`--depth k` accepts the first k dimension-eligible draws instead of only the first, trading precision
for recall. Measured on the same corpus:

| depth | shard predictions | correct | precision | recall |
|---|---|---|---|---|
| 1 | 66 | 51 | 77.3% | 34.9% |
| 2 | 134 | 86 | 64.2% | 58.9% |
| 3 | 194 | 107 | 55.2% | 73.3% |
| 4 | 261 | 122 | 46.7% | 83.6% |
| 6 | 378 | 141 | 37.3% | 96.6% |

Use depth 1 as a gate you trust and depth 3-4 as a candidate generator to verify with a probe run.

## How close together are the shard veins

Layer-1 prediction, the terrain gate ignored.

**Loose — a triple of one cell per mix, scored by smallest enclosing circle.** Present in every seed
tried (150/150 at both window sizes). Median radius 87 blocks in a +/-32-chunk window and 54 blocks in
+/-64. This ranks sites; it does not filter seeds.

**Tight — the three cells mutually adjacent, all pairs within one grid step (48 blocks).**

The layer-1 predictor puts this at 17% of seeds within +/-64 chunks
(`adjacency-60-seeds.jsonl`). **That number is wrong by an order of magnitude, and the corpus says so.**

### The reroll model, confirmed to two decimal places

| per-mix share of Twilight Forest oreseed cells | value |
|---|---|
| layer-1 theory, no gate: 16 / 1053 | 1.52% |
| ceiling if every above-ground mix rerolled: 16 / 493 | 3.25% |
| **measured, 24 seeds / 1485 cells** | **3.28%** |

The measurement lands on the full-reroll ceiling. Shard veins are more than twice as common as the
gate-less predictor implies, because the gate keeps discarding high-band mixes until something deep
sticks — and the shard mixes, at minY 5, always stick.

Adjacent-triple density scales as the cube of the per-mix share, so this is roughly a 10x correction.
Monte Carlo at the measured 3.28%:

| travel budget from the portal site | P(three touching shard veins) |
|---|---|
| 128 blocks | 0.05 |
| 256 blocks | 0.14 |
| 384 blocks | 0.26 |
| 512 blocks | 0.43 |
| 1024 blocks | 0.88 |
| anywhere within +/-64 chunks | **0.91** |

**So "all six shards in three touching veins" is not a seed filter — 91% of seeds have one within a
kilometre.** What is selective is wanting it *close*: about 1 seed in 7 puts one within 256 blocks of
the portal, and 1 in 20 within 128. Filter on the travel budget, not on existence.

The loose predicate is even less selective: every seed tried has a shard triple, median enclosing
radius 87 blocks at +/-32 chunks and 54 at +/-64.

## Chest loot

Twilight Forest chests are vanilla `Blocks.chest` with a `TileEntityChest`, filled by TF's own
`TFTreasure.generate` rather than through `ChestGenHooks` or `WeightedRandomChestContent`. The probe's
existing `IInventory` sweep therefore captures them with no change, and the determinism jar's
structure-chest fix never touches them.

It does not need to. `TFTreasure.generate` re-seeds with
`treasureRNG.setSeed(world.getSeed() * x + y ^ z)` before every draw, so a Twilight Forest chest is a
pure function of its position and the world seed, with no dependence on the chunk populate stream.

The static tables are now in `chestloot.csv` under `source=tftreasure`: 22 tables, 86 non-empty pools,
630 rows. Every pool's `weight` column sums to its `pool_total_weight`. Each chest draws 4 common, 2
uncommon and 1 rare item; `useless` and `ultrarare` take a quarter of their group's draws when
populated, which is what the `to_each` column records.

31 rows carry a non-blank `enchant_level` — matching the 31 `addRandomEnchanted` call sites in
`TFTreasure`. That count is the check: the column was silently empty on the first run because the row
builder took the new parameter without appending it.

### Measured over the corpus

24 seeds, 13175 chunks, 574 chests — `chest-report-24-seeds.txt`. Chests per *instance*, and instances
per 16x16-chunk region (a region holds at most one major feature, so a per-chunk rate is meaningless):

| feature | share of regions | chests per instance (min / median / max) |
|---|---|---|
| Small Hollow Hill | 23.7% | 1 / 2 / 4 |
| Medium Hollow Hill | 11.6% | 1 / 4 / 5 |
| Naga Courtyard | 10.1% | 1 / 2 / 2 |
| Lich Tower | 9.6% | 4 / 5.5 / 10 |
| Hedge Maze | 8.0% | 2 / 5 / 8 |
| Knight Stronghold | 6.3% | 2 / 7.5 / 13 |
| **Labyrinth** | 5.9% | **54 / 55.5 / 57** |
| Large Hollow Hill | 3.8% | 4 / 7.5 / 11 |
| Dark Tower | 1.5% | 19 / 19 / 19 |

The Labyrinth is the outlier by an order of magnitude and is where the metal is: 104 Steeleaf, 84
Ironwood Ingot and 78 Iron Ingot across two walked instances, against 20 Steeleaf and 21 Ironwood for
the Dark Tower. Hollow hills are the Thaumium Dust source. Charms cluster in hollow-tree caches rather
than in structures.

**54% of chests (312 of 574) sit outside any major feature.** They are hollow-tree leaf caches —
`MapGenTFHollowTree` is not a `TFFeature`, so those chests are correctly outside the feature grid
rather than mis-attributed. The report gives them their own heading instead of dropping them; an
earlier draft dropped them and lost most of the loot along with a Wrought Iron Ingot count higher than
any structure's.

## Verification run

- **Format-6 no-op (V1).** Against the pre-patch jar, same seed and walk: the only differences are
  the three added keys. Every hash section, `popseq`, `villages`, `witchery` and `search` is
  byte-identical.
- **Predictor golden test (V8).** 20000/20000 bit-exact against a JVM running GT's own XSTR, across
  dims 0, -1, 1 and 7, covering mix identity, attempt index, `tMinY` and the XZ bounding box. Nothing
  in this repo exercised a non-zero dimension before, so this is the only check that could catch a
  sign or shift error in the dimension byte of `orevein_seed`.
- **Dim-7 walk (V2).** TF biomes only, `spawnextra` empty, `villages` empty, 12 chunks attributed to a
  Medium Hollow Hill, 8 chests, 33879 ore blocks over 85 metas. Every vein-scale material belongs to a
  TF-eligible mix. The hollow-hill chest at (-258, 29, 295) held Ore Magnet, Manganese Ingot and
  Thaumium Dust — and Thaumium Dust is in `hill1`'s rare pool in the exported CSV.
- **Warm/cold parity (V3).** Seed -777 as slot 3 of a 4-slot warm batch is **byte-identical** to the
  cold run. Warm slots cost about 12s each in dim 7 against roughly 90s for a cold boot.
- **Seed independence (V4).** The same seed twice in one batch gives byte-identical reports; two
  different seeds differ in 46 of 81 shared feature regions and in every chunk hash.

### Cross-slot residual (V3b) — real, and not caused by this change

Permuting the batch order and re-diffing is the harder test, and it fails. But it fails *worse in the
overworld*, which is what says it is a property of the warm harness rather than of the dimension work.
Same three seeds, same walk, main window only:

| dimension | seed -777 | seed 111 | seed 222 |
|---|---|---|---|
| dim 0 (control) | 59/169 | 42/169 | 2/169 |
| dim 7 | 0/169 | 0/169 | 7/169 |

In the Twilight Forest the affected chunks are four in Twilight Highlands whose `ores` census shifts by
one to three blocks, plus three in Thornlands with block noise at y 128-175. The ore deltas are not
cosmetic — they move `InfusedWater` (128 vs 131), `InfusedFire` and `Amber`, which are shard-vein
materials — but they are the same JVM-history-dependent GT ore-bookkeeping residual `harness-speed.md`
already documents for the overworld, and the control shows dim 0 carrying far more of it.

The standing policy already covers this: warm mode for search filtering, cold or CRIU for anything
published. Nothing here changes that, and a shard-vein *identity* result is unaffected — the residual
moves block counts within a vein, not which mix the cell drew.

### Hazard assertions (V5)

Both fire before a single chunk generates, and both name the config key responsible:

- `S:TwilightForestSeed=abc` → *"config/TwilightForest.cfg S:TwilightForestSeed is set to "abc" …
  Every seed in this batch would produce an identical Twilight Forest."*
- `B:OldMapGen=true` → *"selects the pre-1.7 feature grid … The emitted feature map would describe
  geometry this world does not have."*

The first attempt at V5 tripped the generic "this provider overrides getSeed()" check instead, which
is correct but does not name the config key. `probeTargetWorld` now gives the dimension-specific check
the first word.

## Reproduce

```sh
# one-off: clone a server dir from the template, then deploy the probe into it
scripts/build-jar.sh probe --deploy ~/.cache/gtnh-determinism/tfprobe

JDK=$HOME/.gradle/jdks/azul_systems__inc_-21-amd64-linux.2

# overworld baseline
PROBE_JAVA=$JDK/bin/java PROBE_SEARCH=true \
  scripts/run-probe.sh ~/.cache/gtnh-determinism/tfprobe -777 rows /tmp/ow.json 6

# Twilight Forest walk plus the feature map
PROBE_JAVA=$JDK/bin/java PROBE_SEARCH=true PROBE_DIM=7 PROBE_TFFEATURES=4 \
  scripts/run-probe.sh ~/.cache/gtnh-determinism/tfprobe -777 rows /tmp/tf.json 6

# loot tables, including source=tftreasure
PROBE_JAVA=$JDK/bin/java PROBE_EXTRA_ARGS="-Dprobe.lootcsv=/tmp/loot" \
  scripts/run-probe.sh ~/.cache/gtnh-determinism/tfprobe -777 rows /tmp/loot.json 2

# predictor golden test — run before quoting any non-overworld number
seedsearch/tests/validate-vein-predict.sh

# shard-vein site ranking; --require adjacent is the stage-0 gate
seedsearch/tf-shard-veins.py predict @seeds.txt --window-chunks 64 --require adjacent
seedsearch/tf-shard-veins.py corpus <tf-report-dir>
```

## Follow-ups

- Widen the corpus. 24 seeds settled the shard-mix precision question at 98.1%, but the per-mix rows
  for the rarer TF mixes are still tens of cells, and `no-matching-mix` at 207 of 1485 cells wants
  explaining — most likely two veins overlapping one cell, which `vein_cells` cannot currently split.
- Fold the corpus-measured 3.28% density into `tf-shard-veins.py` so its own adjacency estimate stops
  being the layer-1 floor. Today the calibrated number lives only in this writeup.
- The cross-slot residual (V3b) is worth a run of its own now that it has a dim-0 control showing it
  is 5-30x larger there. It predates this work, but nothing has characterized it since the harness
  gained a second dimension to compare against.
- A Twilight Forest prefilter is reachable for the feature map and for vein identity, through a
  sibling worldless world built on `WorldProviderTwilightForest` rather than a registered throwaway
  dimension. Generated chest contents are not: the chest position comes from structure components that
  write thousands of blocks, and `SeedProbeWorld` refuses writes by design.
