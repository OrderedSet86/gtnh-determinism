# Census of the chunk-populate RNG stream

Everything in GTNH that draws off a chunk's populate `Random`, or writes blocks during population, with
a per-consumer verdict on whether it can move with chunk load order.

Measured on **daily-707**, `level-type=rwg`, seed `-777`. Source read from `all-gtnh` with every consumer
repo checked out at the tag that built the shipped jar (`scripts/map-jars-to-source.py --versions`);
bytecode read only for the four consumer jars that have no source repo anywhere. Runtime dispatch order
dumped with `-Dprobe.gencensus`. Session record and the numbers behind every claim:
[results/2026-09-07-populate-stream-census](../results/2026-09-07-populate-stream-census/README.md).

**Scope: this census is the OVERWORLD under `level-type=rwg`.** The Nether is a third stream with a
different and worse shape — see [Stream C](#stream-c--the-nether-has-no-per-chunk-reseed-at-all) at the
bottom before assuming anything here transfers to dim -1.

## There are two streams, and the difference decides which defects are possible

**Stream A — the shared populate `Random`.** `rwg.world.ChunkGeneratorRealistic.populate` reseeds
`this.rand` from `(worldSeed, chunkX, chunkZ)` and then hands that *one object* to every consumer in the
chunk, in this order:

```
PopulateChunkEvent.Pre
  mineshaft / stronghold / village  generateStructuresInChunk
  underground water lake, lava lake, 8 dungeon attempts
OreGenEvent.Pre
  clay, dirt x10, gravel x5, coal, iron, gold, redstone, diamond, lapis, emerald
OreGenEvent.Post
  mushroom
DecorateBiomeEvent.Pre
  TerrainGen.decorate x12  (SAND, CLAY, SAND_PASS2, TREE, BIG_SHROOM, then
                            FLOWERS, GRASS, DEAD_BUSH, LILYPAD, SHROOM, REED, PUMPKIN, CACTUS, LAKE)
  RealisticBiomeBase.rDecorate, once per border biome
DecorateBiomeEvent.Post
  50 water springs, 20 lava springs
  SpawnerAnimals.performWorldGenSpawning
PopulateChunkEvent.Post
  ICE / snow pass
```

Because the object is shared, **a consumer that takes a different NUMBER of draws shifts every draw after
it in that chunk.** That is the defect this census exists to find.

**Stream B — FML's per-generator `Random`.** `GameRegistry.generateWorld` runs after the above, from
`ChunkProviderServer.populate`, and calls `fmlRandom.setSeed(chunkSeed)` **before every single**
`IWorldGenerator`. So stream-B generators are *structurally immune* to each other's draw-count skew. What
they are not immune to is write order: they all `setBlock` into the same chunk and first writer wins.

Headline: **B is immune to draw-count skew and exposed to everything else.**

### Three busses, not two

Enumerating two of them is a silent one-third miss.

| bus | carries |
|---|---|
| `MinecraftForge.EVENT_BUS` | `PopulateChunkEvent.Pre/Post`, `DecorateBiomeEvent.Pre/Post` |
| `MinecraftForge.TERRAIN_GEN_BUS` | `PopulateChunkEvent.Populate`, `DecorateBiomeEvent.Decorate` |
| `MinecraftForge.ORE_GEN_BUS` | `OreGenEvent.Pre/Post/GenerateMinable` |

`DecorateBiomeEvent.Decorate` is the largest consumer surface in the pack and it is on the bus a
`PopulateChunkEvent` grep never touches.

### Under RWG, only one result-lever is live

RWG **discards the return value of all 12 `TerrainGen.decorate` calls**
(`ChunkGeneratorRealistic.java:646-680`). It *does* use the return of `TerrainGen.generateOre` (`:539`
onward) and of `TerrainGen.populate` (`:498`, `:508`, `:519`, `:697`, `:709`).

So a handler that calls `setResult(DENY)` can only change what the generator does on
`OreGenEvent.GenerateMinable` and `PopulateChunkEvent.Populate` — and **no handler is registered on
`Populate` at all**. Every `Decorate`-stage `setResult` in this pack is inert under `level-type=rwg`.
This is why `generateUndergroundDirtGen` works the way
[results/2026-09-06-dirt-gravel-attribution](../results/2026-09-06-dirt-gravel-attribution/README.md)
describes: GregTech's `GTProxy.onOreGenEvent` is the only live result-lever in the overworld.

## The rubric

A consumer is order-safe iff **(1)** its output is a function only of `(seed, chunk coords)` **and (2)**
its effect on the shared stream is a constant. Flags are a set, not a class; the verdict is max severity.

**Axis I — stream-state impurity** (changes what everyone downstream draws)

| | |
|---|---|
| `S1` | variable draw count off the shared stream, gated on a live world read |
| `S2` | nested-populate cascade — reseeds the shared stream mid-flight |
| `S3` | `setResult`/`setCanceled` suppressing or enabling later work (changes *someone else's* draw count) |
| `S4` | re-posts a terraingen event, dispatching further consumers on the shared stream |

**Axis II — output impurity** (its own writes are not a function of seed + coords; applies to both streams)

| | |
|---|---|
| `O1` | fixed draws, but writes conditioned on live reads / first-writer-wins |
| `O2` | writes outside its own populate window |
| `O3` | unordered iteration (HashMap / HashSet / identity hash) inside the consumer |
| `O4` | clock or entropy RNG (`world.rand`, `new Random()`, `Math.random()`) |
| `O5` | cross-chunk mutable state: unseeded cache, never-cleared cache, accumulator, shared-list shuffle |
| `O6` | defers work to a later chunk or tick |
| `O7` | off-thread work |
| `O8` | dispatch position unstable |

**Neutral, recorded but not scored:** `N1` — the consumer derives its own `(seed, position)` Random. On
the order axis that is CLEAN; it is what the fixes in this repo do. Scoring it would flag every fix.

**Verdicts:** `ORDER-UNSAFE` > `LAUNCH-UNSAFE-ONLY` (O4 alone) > `UNRESOLVED` > `INACTIVE` (registered but
config- or dimension-disabled here) > `CLEAN` (**justification mandatory** — otherwise CLEAN only means
nobody looked hard).

**The mechanical S1-vs-O1 test**, so two readers agree: *does the consumer make a `Random` call on a code
path whose reachability depends on a world read?* Yes → S1. If every path draws the same number of times
and only the `setBlock` arguments, or whether the `setBlock` lands, vary → O1.

Worked examples, both from this pack:

- RWG's emerald loop (`ChunkGeneratorRealistic.java:611-621`) reads `isReplaceableOreGen` and writes
  conditionally, but draws 3× per iteration unconditionally. **O1, not S1.**
- `Railcraft QuarryPopulator` reads `getTopSolidOrLiquidBlock` and `getBlock == dirt` before calling
  `quarry.generate` — which looks like S1 until you check `WorldGenQuarry`, which makes **zero** draws.
  The read decides whether blocks are written, never how many draws are taken. **O1, not S1.**

## Stream A — what is registered, in dispatch order

Read from each event's own `ListenerList`, so base-class subscribers appear in their real position. This
order was **identical across all five launches measured** (Java 17 ×3, Java 21 ×2) — the event busses are
not a source of order nondeterminism.

### `PopulateChunkEvent.Pre` — EVENT_BUS, front of the chunk's stream

| # | consumer | flags | verdict |
|---|---|---|---|
| 1 | `cofh.core.world.WorldHandler.populateChunkEvent` | `O5` | CLEAN for the stream — appends a `ChunkReference` to the static `populatingChunks` list, zero draws. The list is cross-chunk mutable state, so it is flagged, but nothing in the populate path reads it |
| 2 | `mods.railcraft.common.worldgen.GeodePopulator.generate` | **`S1`**, `O2` | **ORDER-UNSAFE** — see below |
| 3 | `mods.railcraft.common.worldgen.QuarryPopulator.generate` | `O1` | draws exactly 1 (`nextDouble` in `canGen`) whenever the biome matches; `WorldGenQuarry` draws none |
| 4 | `com.dreammaster.railcraftStones.NH_GeodePopulator.generate` | **`S1`**, `O2` | **ORDER-UNSAFE** — same `WorldGenGeode` body, mountain biomes |
| 5 | `com.dreammaster.railcraftStones.NH_QuarryPopulator.generate` | `O1` | as #3 |
| 6 | `buildcraft.energy.worldgen.OilPopulate.populate` | **`S1`**, `S3`, `O2` | **ORDER-UNSAFE** — see below |
| 7 | `WorldgenProbe$PopSeqHandler` | — | the probe's own trace, zero draws, not shipped to players |

**`WorldGenGeode.placeOre` is the cleanest S1 in the pack.** The geode is a 16×16×16 cube centred at
`(chunkX*16+8, y, chunkZ*16+8)`, so it spans four chunks. Its `placeOre` reads the live world and only
then draws:

```java
private void placeOre(World world, Random rand, int x, int y, int z) {
    if (WorldPlugin.getBlock(world, x, y, z) == blockStone) {   // live read
        double chance = rand.nextDouble();                      // ... gates the draw
```

`blockStone` gets there via `placeStone`, which is itself gated on `isReplaceable` reading the live world.
So the number of draws a single geode takes depends on what the neighbouring chunks have already
populated — and this is at `PopulateChunkEvent.Pre`, the front of the chunk, so a flip shifts every
decoration draw that follows. `abyssal=true` and `quarried=true` in
`config/railcraft/railcraft.cfg`, so both geode populators are live.

**`OilPopulate.generateOil`** draws 2 (`nextInt(16)` ×2), then `BlockBuildCraftFluid.isFluidExplosive`
can `return` before the `nextDouble()` chain, then `getTopBlock` and `surfaceDeviation(world, x, y, z, 8)`
— an 8-block radius read that crosses chunk boundaries — gate the well. It also calls
`event.setResult(Result.ALLOW)` on the `!doGen` path. This is the mechanism behind
[results/2026-09-01-gtnh-oil-route-stability](../results/2026-09-01-gtnh-oil-route-stability/README.md),
which measured a whole oil deposit existing under one walk order and not another.

### `PopulateChunkEvent.Post` — EVENT_BUS

| # | consumer | status |
|---|---|---|
| 1 | `micdoodle8.mods.galacticraft.core.event.EventHandlerGC.populate` | not yet assessed |
| 2 | `buildcraft.core.SpringPopulate.populate` | not yet assessed |
| 3 | `com.rwtema.extrautils.EventHandlerServer.decoratePiEasterEgg` | **CLEAN** — fires only at chunk `(196349, 22436)` in dim 0, places one chest at `(3141592, 65, 358979)` with a written book. Zero draws, fixed position, ~3.1M blocks from spawn |
| 4 | `astrotibs.villagenames.handler.WellDecorateEvent.onPopulating` | not yet assessed |
| 5 | `forestry.core.worldgen.WorldGenerator.populateChunk` → `HiveDecorator` | **`S1` + `O5` — ORDER-UNSAFE**, see below |
| 6 | `com.dreammaster.modfixes.oilgen.OilGeneratorFix.populate` | `O1`, `O2` — takes exactly 2 draws, or 3 when `shouldSpawnOil` passes, and `shouldSpawnOil` decides on dimension, biome id and `biome.rootHeight`, never on a live block read. Everything after the radius draw (`checkOilPresent`, `getTopBlock`, `buildOilStructure`) reads and writes without drawing. So the **oil route-instability measured in [results/2026-09-01-gtnh-oil-route-stability](../results/2026-09-01-gtnh-oil-route-stability/README.md) is first-writer-wins across a multi-chunk sphere, not draw skew** |

**Forestry's wild hives are both `S1` and `O5`, and the `O5` half is the worse one.**
`HiveDecorator.decorateHives` opens with `Collections.shuffle(hives, rand)` on the mod's **shared,
persistent** hive list. Fisher-Yates produces a permutation that is a function of the draws *and of the
list's starting order* — and the starting order is whatever the previous chunk's shuffle left behind. So
which hive a chunk considers first depends on how many chunks were populated before it. That is route
dependence by construction, with no world read involved, and it is the same shape as the Witchery
shared-list shuffle the README already records as fixed.

On top of that, `genHive` draws `nextFloat()`, then up to 4×2 more, and leaves the loop as soon as
`tryGenHive` succeeds — and `tryGenHive` reads the live world for a valid ground column. So the draw
count moves with terrain too. Note this is *wild* hives; the README's Forestry entry covers village bee
houses, a different code path.

### `DecorateBiomeEvent.Decorate` — TERRAIN_GEN_BUS, the largest surface

| # | consumer | flags | verdict |
|---|---|---|---|
| 1 | `com.rwtema.extrautils…EventHandlerUnderdark.preventDoubleDecor` | — | **CLEAN** — the method body is a single `return`. Registered, dispatched, does nothing |
| 2 | `biomesoplenty…DecorationModificationEventHandler.modifyDecor` | `S3` | **INACTIVE under RWG** — `setResult(DENY)` on `LAKE` in every BOP biome and on `PUMPKIN` behind a feature flag, decided from biome + config with no world read and no draws. RWG discards the `decorate` return, so the DENY has no effect here |
| 3 | `tconstruct.world.gen.TerrainGenEventHandler.onDecorateEvent` | **`S1`** | **ORDER-UNSAFE** — see below |
| 4 | `com.dreammaster…ZincGravelWorldgen.onDecorateEvent` | **`S1`** | **ORDER-UNSAFE** — the same shape as #3, one ore, `new SurfaceOreGen(ZincGravelOre, 0, 12, true)` — `alterSize = true`, so the same `findSurface` gate |
| — | *phase LOWEST* | | |
| 5 | `vazkii.botania…BiomeDecorationHandler.onWorldDecoration` | **`S1`** | **ORDER-UNSAFE** — flowers only; see below |

**Botania's flower pass is S1; its mushroom pass is not.** Both run at `LOWEST`, so they are the last
stream-A consumers before RWG's own `rDecorate` and springs. `generateFlowers` draws a fixed 4 per inner
iteration, then:

```java
if(event.world.isAirBlock(x1, y1, z1) && … && ModBlocks.flower.canBlockStay(event.world, x1, y1, z1)) {
    if(primus) { … event.rand.nextBoolean() … }          // both branches draw,
    else { … if(event.rand.nextDouble() < flowerTallChance … ) }   // neither is reached otherwise
}
```

Three live reads gate two further draws. `generateMushrooms` has the same `isAirBlock`/`canBlockStay`
test but takes its 3 draws *before* it and none after, so it is **O1** — the same handler class holds one
of each, which is why the S1-vs-O1 test has to be applied per method rather than per mod.

**TiC surface ores are S1 and they fire early.** `onDecorateEvent` hooks `type == SAND`, which in RWG's
order is the *first* of the 12 decorate calls — before trees, flowers and grass. Per ore it draws
`nextInt(rarity)`, and on a hit draws 2 more and calls `SurfaceOreGen.generate`, whose body is:

```java
if (alterSize) {
    startY = findSurface(world, x, y, z);   // scans live grass/dirt/opaque-cube
    if (startY == -1) return false;         // ... returns BEFORE the first draw
}
float f = random.nextFloat() * (float) Math.PI;
```

All six surface ores (iron, gold, copper, tin, aluminium, cobalt) pass `alterSize = true`. So whether a
chunk's decoration stream advances by 1 draw or by many depends on whether the live column is grass/dirt
with air above — exactly what moves when a neighbour decorates first. This is a named, per-mod
contributor to the **38,856-block "decoration" bucket** the README currently records as *"endemic 1.7.10
decorator ordering; no per-mod fix known."*

### `DecorateBiomeEvent.Post` — EVENT_BUS

`mods.railcraft.common.worldgen.FirestoneGenerator.generate` — gated on
`BiomeDictionary.Type.NETHER`, so **INACTIVE** in the overworld.

### `OreGenEvent.GenerateMinable` — ORE_GEN_BUS, the one live result-lever

| # | consumer | priority | verdict |
|---|---|---|---|
| 1 | `cofh.core.world.WorldHandler.handleOreGenEvent` | HIGHEST | **INACTIVE** — first branch is `if (!genReplaceVanilla) return;` and the pack ships `B:ReplaceVanillaGeneration=false` in `config/cofh/core/common.cfg` |
| 2 | `gregtech.common.GTProxy.onOreGenEvent` | NORMAL | `S3`, live. DENYs `WorldGenMinable` for any type in `PREVENTED_ORES`; the veto **skips the loop without consuming RNG** |
| 3 | `com.rwtema.extrautils…EventHandlerUnderdark.noDirt` | NORMAL | **INACTIVE** — DENYs only when `world.provider.dimensionId == ExtraUtils.underdarkDimID` |

`OreGenEvent.Pre`, `OreGenEvent.Post`, `DecorateBiomeEvent.Pre`, `PopulateChunkEvent.Populate` and the
three base event classes have **zero registered listeners** in this pack.

## Stream B — the FML generator list

30 generators. **The dispatch order is not stable across launches of the same JVM** — 19 of 30 positions
differ between two back-to-back Java 17 runs with an identical jar set, and the cross-JVM figures are the
same magnitude. This retracts `docs/HANDOFF.md`'s "identity-hash ordering is identical across JVM
launches"; see the results README for the five-arm measurement and for what the finding does *not*
license. Within a weight, order is `System.identityHashCode` order; only distinct weights are pinned.

| weight | members | note |
|---:|---:|---|
| 0 | 19 | ic2, TiC `TBaseWorldGenerator`, Draconic, Binnie, Thaumcraft, EtFuturum, Roguelike, Fether ×3, Witchery, harvestcraft ×3, AE2 `MeteoriteWorldGen`, Forestry, VillageNames ×2, `mrtjp.core.world.SimpleGenHandler$`, CoFH `WorldHandler` |
| 1 | 1 | Automagy |
| 2 | 1 | TiC `SlimeIslandGen` |
| 20 | 3 | Natura crops / clouds / trees |
| 1000 | 1 | Chisel |
| 2147483647 | 5 | galacticgreg, EtFuturum **deepslate**, LootGames, this repo's `PendingSlices$SliceApplier`, GregTech |

Two things about the top block. GregTech's own `GameRegistryMixin` pulls `GTWorldgenerator` out of the
collection and appends it, so GT is genuinely last — but it pins **only itself**. EtFuturum's deepslate
generator, LootGames and our `SliceApplier` are left tying among themselves, and those are three of the
subsystems named in the residual table.

AE2's `MeteoriteWorldGen` is `O6` by construction: it queues `IWorldCallable`s onto `TickHandler` and
`MeteoriteSpawn.tryMeteorite` gates placement on `chunkProvider.chunkExists(cx, cz)`. The probe fires
**zero ticks**, so this census cannot see it at all — see the blind spot below.

## Completeness: three cardinalities

| | count | how |
|---|---:|---|
| jars shipped by daily-707 | 217 | `scripts/map-jars-to-source.py` |
| …with source in `all-gtnh` | 191 | matched on `gradle.properties` `modId`, then jar `mcmod.info`, then name |
| …source-less jars touching a populate hook | 12 | `scripts/triage-sourceless-jars.py` — of which 2 are this repo's own and 6 were cloned from upstream during this pass |
| repos hit by the source scan | 33 | `scripts/scan-populate-consumers.py` |
| `IWorldGenerator`s registered at runtime | 30 | `-Dprobe.gencensus` |

Gaps, each with a named reason rather than a shrug:

- **In source, not registered.** Every one has a named config key, not a shrug:

  | generator | reason |
  |---|---|
  | AE2 `QuartzWorldGen` | `AppliedEnergistics2.cfg` → `B:CertusQuartzWorldGen=false` (`MeteoriteWorldGen=true`, registered) |
  | harvestcraft `WorldGenPamSalt`, `PamBeeGenerator` | `harvestcraft.cfg` → `B:enablesaltGeneration=false`, `B:enablebeehiveGeneration=false`; garden + tree are unconditional and registered |
  | SGCraft `NaquadahOreWorldGen` | `SGCraft.cfg` → `B:enableNaquadahOre=false` |
  | Galacticraft / GalaxySpace `OverworldGenerator` ×5 | `Galacticraft/core.conf` → `B:"Enable Copper Ore Gen"=false` and siblings |
  | ThaumicTinkerer `OreClusterGenerator` | never passed to `GameRegistry` — a field on `ChunkProviderBedrock`, the TT dimension's own provider |
  | Railcraft `SaltpeterGenerator`, `SulfurGenerator`, 5× `PoorOreGenerator` | `railcraft/railcraft.cfg` → `saltpeter=false`, `sulfur=false`, `iron/gold/copper/tin/lead=false`; `abyssal`, `quarried`, `firestone` are `true` and all three are registered |
  | EndlessIDs `IR2Mixin.generateOrePre` | a mixin, applied conditionally |
- **Registered, not in the source scan.** `mrtjp.core.world.SimpleGenHandler$` — Scala. An earlier
  version of the scan globbed only `*.java` and anchored `registerWorldGenerator` on a `;`, and missed it
  twice over. Both are fixed; the miss is recorded because it is the reason the runtime half exists.
- **No source anywhere.** Thaumcraft, Witchery, ExtraUtilities, IC2 — read with `javap`. Witchery's
  `generate` passes `World.field_73012_v` (`world.rand`, clock-seeded) rather than the `Random` it was
  handed, which is the `O4` defect this repo already fixes; re-deriving a known fix from bytecode is the
  check that the method works.

## Stream C — the Nether has no per-chunk reseed at all

Stream A's whole premise is that `populate` reseeds the shared `Random` from `(worldSeed, chunkX,
chunkZ)` before handing it round, so a draw-count skew can only corrupt the rest of *that chunk*. The
Nether does not do this.

| provider | `provideChunk` seeds | `populate` reseeds it |
| --- | --- | --- |
| `ChunkProviderGenerate` | `rand` (`:226`) | yes — `:394`, `:397` |
| `rwg.ChunkGeneratorRealistic` | `rand` (`:158`) | yes — `:479-482` |
| **`ChunkProviderHell`** | `hellRNG` (`:280`) | **no** |

`ChunkProviderHell.populate` (`:459-553`) posts `PopulateChunkEvent.Pre` and then runs the fortress
(`:467`), lava lakes (`:476-479`), fire (`:488-491`), both glowstone passes (`:499-510`), mushrooms
(`:518-529`), nether quartz (`:538-541`) and the closed-lava pass (`:546-549`) straight off `hellRNG`,
and never calls `setSeed`. `DecorateBiomeEvent.Pre/Post` and `PopulateChunkEvent.Post` hand the same
object to every mod handler.

So `hellRNG` is **one continuous stream for the whole dimension**, and its state when a chunk populates
is the accumulated history of every `provideChunk` and every `populate` that ran before it. Chunk load
order is the player's route.

**Consequences that invert the rubric.** In stream A, `O1` (draw-count skew) is bounded to the rest of
the chunk and `CLEAN` means something. In stream C there is no boundary: *any* skew anywhere propagates
to every later chunk in the dimension, so per-consumer verdicts are close to meaningless until the
stream is cut per chunk. Do not port stream A's verdict table to dim -1.

Measured at r60 on seed `-1636594104014467454`, rows vs spiral: **11,111,905 differing blocks across
13,796 of 15,468 common chunks — 89% of the dimension.** The top transitions are air/netherrack, lava,
glowstone and quartz, not ore. See
[results/2026-09-07-nether-orevein-determinism](../results/2026-09-07-nether-orevein-determinism/README.md).

The same-order block floor here is **noisy — 2,137 and 9,422 blocks on two samples.** Quote a range,
not one sample; the variation is consistent with the stream-B generator-order instability this census
measured. Against a floor of order 10^4 an 11.1M route difference is still three orders clear, but any
Nether decoration fix has to beat a moving floor, so measure the floor in the same session as the arms.

**This is unfixed.** `ChunkProviderHellPopulateMixin` (`-Dgtnhdet.netherpop`, default **off**) was
written to give dim -1 the same per-chunk reseed `ChunkProviderGenerate` uses. It binds and never
fires — root cause unknown; see that class's javadoc for what has been ruled out. Do not read the
flag's existence as the defect being handled.

**One more trap this creates for our own tooling.** `TerrainOracle` calls `provideChunk` on the LIVE
generator, whose first statement re-seeds that generator's `Random`. Harmless wherever `populate`
reseeds; in the Nether it permanently reroutes decoration, and the number of oracle calls depends on
the oracle's LRU, hence on the route. `OracleRngGuard` (`-Dgtnhdet.oracleguard`) swaps the generator's
`Random` fields for scratch instances around the call. RWG's `mapRand` is the same shape in the
overworld — consumed in `provideChunk:174`, never reseeded — merely exercised far more rarely (only
biome 312).

## What this census structurally cannot see

The probe shuts down inside `FMLServerStartedEvent`, **before the server enters its tick loop**. So
`O6`-to-a-later-*tick* is invisible here: AE2 meteorites, Witchery's village walls (the bug that made
walls depend on route and timing), and anything else deferred past generation. This census finds
`O6`-to-a-later-*chunk* and nothing beyond it. Do not read a `CLEAN` in this document as covering
tick-time behaviour.

## Reproducing

```bash
python3 scripts/map-jars-to-source.py ~/.cache/gtnh-determinism/daily-707 --versions --json jarmap.json
python3 scripts/triage-sourceless-jars.py ~/.cache/gtnh-determinism/daily-707 jarmap.json
python3 scripts/scan-populate-consumers.py jarmap.json --json consumers.json --repos repos.txt

PROBE_JAVA=~/.gradle/jdks/azul_systems__inc_-17-amd64-linux.2/bin/java \
PROBE_JVMFLAGS="-Dprobe.gencensus=$PWD/census-j17.json" \
  scripts/run-probe.sh ~/.cache/gtnh-determinism/daily-707 -777 rows /tmp/unused.json 1

python3 scripts/census-report.py census-*.json --scan consumers.json
```
