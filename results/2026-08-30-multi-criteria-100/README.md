# 100-seed multi-criteria search: no seed clears all four

GTNH daily-707, branch `chest-loot-positional` (`a74ca2b`). Probe jar `28c78d62…`, fix jar
`e70eb436…`. 100 seeds, first 100 of `random.Random(707)`, radius 60 chunks, 2666 ms/seed.

Seven criteria were asked for. **Five are implemented; two are not** — see "Not evaluated" below.
Of the five, **no seed satisfies all of them**, and 100 seeds was never enough to expect one.

## Result

| criteria met (of 4 positional) | seeds |
| ---: | ---: |
| 4 | **0** |
| 3 | 2 |
| 2 | 12 |
| 1 | 51 |
| 0 | 35 |

Individually, over all 100 seeds:

| criterion | passes | note |
| --- | ---: | --- |
| Village within 100 blocks | **2** | the binding constraint |
| Witchery coven circle within 100 blocks | 10 | |
| No-rain square ≥5×5 chunks within 200 blocks | 19 | |
| High-humidity square ≥5×5 chunks within 200 blocks | 50 | |

Treating them as independent gives **~1 in 5 300 seeds** for all four. A 100-seed corpus has about a
2% chance of containing one. **This is a sample-size result, not a "no such seed" result.** Roughly
15 000–20 000 seeds would give a good chance of a handful, and that is before oil and TF are added,
which can only make it rarer.

Separately, the loot table's `Min` column (`Alumite Large Plate >= 2`) rejects **75 of 100** seeds.
That gate and the positional gates are close to independent, so they multiply.

## Best candidates

**Best that also clears the loot `Min` gate — `6676977092889541756`, 3/4, loot 239 312**

| | |
| --- | --- |
| coven circle | 88 blocks ✅ |
| no-rain 17×17 | 116 blocks ✅ |
| high humidity | ✅ |
| village | 571 blocks ❌ |

**Best village proximity — `5211795028609473266`, 2/4, loot 251 464**

| | |
| --- | --- |
| village | 55 blocks ✅ |
| high humidity 19×19 | 94 blocks ✅ |
| coven circle | 503 blocks ❌ |
| no-rain | no square ≥5 ❌ |

**Highest loot overall — `8308683508822170135`, 1/4, loot 300 039.** Coven circle 149 blocks,
humid 13×13 at 92 blocks, village 173 blocks, no no-rain square.

`3/4` was also reached by `2159086644058109626` (circle 55 blocks, no-rain 13×13 at 107 blocks), but
it fails the `Alumite Large Plate >= 2` minimum.

Full ranking in `report.txt`; raw rows in `prefilter.jsonl.gz`.

## Not evaluated

Neither is defaulted to zero or quietly folded into the score. A seed clearing every bar above has
**not** cleared all seven.

- **Oil spouts.** These are BuildCraft, not GregTech: `buildcraft.energy.worldgen.OilPopulate`, a
  `PopulateChunkEvent.Pre` handler. No prefilter module reads it. It looks *more* tractable than
  Witchery was — RWG fires `PopulateChunkEvent.Pre` immediately after reseeding the populate Random
  from `(worldSeed, cx, cz)` and before the structure generators, so `OilPopulate` is the **first**
  consumer of a freshly position-seeded stream, and it reads virgin terrain rather than decorated
  terrain. That avoids the one-block bound the Witchery replay carries.
- **Twilight Forest Thaumcraft crystal veins.** Not covered, and not previously covered either —
  the only Thaumcraft class the probe touches anywhere is `MazeHandler`, and stage 0 is overworld
  only. The full-generation probe records TC eldritch ring sites under `search.eldritch`, which is a
  different feature.

## Thaumcraft obsidian rings

The obsidian totem circle in the screenshot is a Thaumcraft **eldritch ring** (obelisk), and it **is**
covered by the determinism work — finding F3b, `WorldGenEldritchRingMixin` plus
`ThaumcraftWorldGeneratorMixin`'s `EldritchRingLottery`. Siting is decided by a seed-pure region grid
with validity evaluated on virgin terrain, which removed a population-order race: the first ring
generated used to suppress every later candidate within a 43×43-chunk window, so which obelisk existed
depended on the player's route. Observed at block `-787,460` on seed 88888888.

It is fixed, but it is **not** a stage-0 prefilter output — the full-generation probe reports the sites
under `search.eldritch`. It is not one of the criteria scored here.

## Reproducing

```sh
PREFILTER_RADIUS=70 PREFILTER_TERRAIN=4 \
PROBE_JVMFLAGS="-Dprobe.prefilter.dungeon=60 -Dprobe.prefilter.chests=true \
                -Dprobe.prefilter.stronghold=60 -Dprobe.prefilter.witchery=60 \
                -Dprobe.prefilter.witchery.replay=true -Dprobe.prefilter.biomeregion=13 \
                -Dprobe.prefilter.biomeregion.min=5 -Dprobe.prefilter.chunkcache=4096" \
  ./scripts/prefilter.sh @seeds-100.txt out.jsonl

python3 seedsearch/multi-criteria.py values.csv out.jsonl --radius 60 --top 10
```

## Update: 200-block allowances, TF shard veins in, oil identified

Village and coven-circle allowances raised to 200 blocks, and TF shard veins added as a fifth
criterion via `seedsearch/tf-shard-veins.py`. Full output in `report-200.txt`.

| criteria met (of 5) | seeds |
| ---: | ---: |
| 5 | **0** |
| 4 | 0 |
| 3 | 6 |
| 2 | 24 |
| 1 | 47 |
| 0 | 23 |

| criterion | passes /100 |
| --- | ---: |
| Village within 200 | 15 |
| Coven circle within 200 | 25 |
| No-rain ≥5×5 within 200 | 19 |
| High humidity ≥5×5 within 200 | 50 |
| TF shard triple within 1000 | **4** — now the binding constraint |

Independent estimate for all five: **~1 in 7 000**. Doubling the village and circle allowances helped
each of those individually (2→15 and 10→25) but the joint rate got *worse*, because TF shard adjacency
is rarer than either. Best remains **3/5**.

Best clearing the loot `Min` gate: `8308683508822170135` (3/5, loot 300 039 — village 173 m, circle
149 m, humid 13×13 at 92 m) and `6676977092889541756` (3/5, loot 239 312 — circle 88 m, no-rain 17×17
at 116 m, humid).

**TF shard veins under-report by design.** `tf-shard-veins.py` predicts GT vein identity in dim 7 by
pure arithmetic: measured **98.1% precision** on the three shard mixes over 1 485 cells, but **34.9%
recall**, because the terrain reroll gate kills veins the predictor does not model. A "no adjacent
triple" is therefore weak evidence of absence; a hit is strong evidence of presence. Three mixes cover
all six shards, since each carries a shard in both its primary and secondary slot.

## Oil: identified, and it is not BuildCraft's

The spout is generated by **`com.dreammaster.modfixes.oilgen.OilGeneratorFix`** in
GTNewHorizonsCoreMod, whose `OilConfig` carries `OilFountainSizeSmall` / `OilFountainSizeLarge`.
BuildCraft's own generator is switched off in this pack — `config/buildcraft/main.cfg` has
`D:oilWellGenerationRate=0` and `B:spawnOilSprings=false`.

A stage-0 module for the BuildCraft generator was written and works, but it now **refuses rather than
returning an empty list**, because "no oil here" and "this pack cannot make BuildCraft oil anywhere"
are different answers:

```
"oil_error": "BuildCraft oil generation is disabled in this pack (oilWellScalar=0); GTNH generates
oil via com.dreammaster.modfixes.oilgen.OilGeneratorFix, which this module does not read"
```

**GTNH's generator is a much harder target than BuildCraft's, and possibly a defect.** Two reasons,
both from its bytecode:

- It listens on `PopulateChunkEvent.**Post**`, not `Pre`. Its Random has already been through every
  lake, lava, dungeon, ore and decoration draw in the chunk, so there is no cheap position-derived
  entry the way there is for a Pre handler. Reaching it means replaying essentially all of populate.
- `shouldSpawnOil` gates on `OilConfig.OilDepostMinDistance` against previously placed deposits. That
  is order-dependent state, the same shape as Witchery's `structuresList` residual and the Thaumcraft
  maze race that F3b fixed. **Whether GTNH oil is route-stable at all has not been tested here**, and
  it is worth testing independently of any seed search.
