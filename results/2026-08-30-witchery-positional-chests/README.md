# Witchery chest contents, made positional and predicted

GTNH daily-707, repo `d17a685`. Fix jar `74aa30422483e9648d627a67601064b6`, probe jar
`bd4162be904c25cbed71549ca5a7bfa3`.

Witchery's village chests are now predicted exactly by the stage-0 prefilter. Getting there needed a
change to what the game generates, not only to what the prefilter computes.

| check | result |
| --- | --- |
| no-hooks chest contents predicted, byte-identical incl. NBT | **10 / 10** |
| phantom or missing predictions | **0** |
| piece classes covered | Keep 2, Apothecary 4, WatchTower 4 |
| order A/B (`rows` vs `spiral`), content differences | **0 / 37** |

Three seeds, three village-centred windows, verified against full worldgen.

## Why the count was the whole problem

These pieces call `generateStructureChestContents` but never ask Forge for a loot table. They pass
their own compile-time array and their own count:

```java
this.generateStructureChestContents(world, box, rand, x, y, z,
    villageTowerChestContents,   // static field on the piece class
    3 + rand.nextInt(6));        // ComponentVillageKeep
```

The pool is a constant, so contents were never the issue. The count came off the chunk-populate
`Random`.

**That Random is already position-seeded.** RWG reseeds it from `(worldSeed, cx, cz)` immediately
before the structure generators run:

```java
this.rand.setSeed(worldSeed);
long i1 = rand.nextLong()/2*2+1;  long j1 = rand.nextLong()/2*2+1;
this.rand.setSeed(i*i1 + j*j1 ^ worldSeed);
MinecraftForge.EVENT_BUS.post(new PopulateChunkEvent.Pre(...));
if (ConfigRWG.generateMineshafts) mineshaftGenerator.generateStructuresInChunk(worldObj, rand, i, j);
strongholdGenerator.generateStructuresInChunk(worldObj, rand, i, j);
if (ConfigRWG.generateVillages)   villageGenerator.generateStructuresInChunk(worldObj, rand, i, j);
```

So these chests were deterministic already. What they were not is **cheap**: reaching the count means
replaying that prologue — the `PopulateChunkEvent.Pre` handlers, then the mineshaft and stronghold
generators, each running real `addComponentParts` — for the chunk. Stage 0 does no population at all.

A replay harness was scoped and abandoned in favour of the simpler change below.

## The change

F10 now refills these chests from the chest's own position fork, using the caller's own array, with
the count redrawn over **the range the mod itself uses**:

```java
final int rolls = range[0] + rand.nextInt(range[1] - range[0] + 1);
WeightedRandomChestContent.generateChestContents(rand, items, inv, rolls);
```

Ranges are read from the mods' bytecode, not inferred from samples:

| piece | formula | range |
| --- | --- | ---: |
| `ComponentVillageKeep` | `3 + rand.nextInt(6)` | 3-8 |
| `ComponentVillageApothecary` | `2 + rand.nextInt(4)` | 2-5 |
| `ComponentVillageWatchTower` | `2 + rand.nextInt(4)` | 2-5 |
| `ComponentShack` | `1 + rand.nextInt(3)` | 1-3 |

Same pool, same distribution, no dependence on anything but position — so the prefilter predicts it
with the same two lines.

`shared/chest-nohooks.json` is the single copy of that table, and `scripts/build-jar.sh` copies it
into both jars on every build. If the two ever disagreed the prefilter would be confidently wrong, so
they are not edited separately. The probe reads the pool straight off the piece class's static field,
so it uses the mod's real array rather than a transcription of it.

## This invalidates the earlier corpus

Chest contents for these pieces are different from what daily-707 generates unpatched, and different
from what earlier fix jars generated. `results/2026-08-30-chest-loot-sweep-5000` and every score
derived from it are stale. This was an accepted trade: the loot distribution is preserved, the exact
items are not.

## Coverage and what is still out of reach

`ComponentShack` is in the table but **appears in none of the three windows**, so its range is read
from bytecode but not yet confirmed against a generated world.

`ComponentVillageBookShop` cannot be reached by either jar. It never calls
`generateStructureChestContents` at all — it places the chest and writes slots directly through
`TileEntityChest.setInventorySlotContents`, so no hook sees it:

```
World.func_147465_d (setBlock) -> World.func_147438_o (getTileEntity)
  -> TileEntityChest.func_70299_a (setInventorySlotContents)
```

Witchery's **standalone** structures (covens, shacks placed outside villages) remain blocked for a
separate reason recorded in `results/2026-08-30-stronghold-witchery-chests`: picking the winning
handler requires `IWorldGenHandler.generate`, which writes blocks.

## Residuals, absolute

The order A/B still shows **2+2 chest existence differences** on this window — chests present under
one walk order and absent under the other. Unattributed, not village pieces, untouched by any of this
work. One content difference survives on the earlier window: `(240,68,309)`, a Forestry bee chest that
appears in no chesttrace line because Forestry fills it without reaching
`WeightedRandomChestContent.generateChestContents`.

Content differences among chests F10 refills: **0**.

## Reproducing

```sh
PREFILTER_RADIUS=70 PREFILTER_TERRAIN=4 \
PROBE_JVMFLAGS="-Dprobe.prefilter.chests=true" ./scripts/prefilter.sh @seeds.txt out.jsonl

PROBE_CX=13 PROBE_CZ=8 PROBE_SEARCH=true \
  ./scripts/run-probe.sh <server> -6270331762397506834 rows world.json 8
```

Predicted no-hooks chests carry `"category": "(no-hooks)"`; compare against
`search.chunks[<cx,cz>].chests` on XZ, since the prefilter emits a nominal Y.
