# Who is hooked to the populate RNG, and does the order hold

**Outcome: a complete consumer census, plus one retraction. The FML `IWorldGenerator` dispatch order is
NOT stable across launches of the same JVM — 19 of 30 positions differ between two back-to-back Java 17
runs with an identical jar set. `docs/HANDOFF.md` records the opposite ("default `hashCode=5` is a
thread-local xorshift with a FIXED seed, so identity-hash order is identical across JVM launches;
measured 3 launches, byte-identical ordering") and that claim does not survive contact with a 220-mod
pack. The terraingen event dispatch order, by contrast, is identical across all five arms.**

Nobody had ever enumerated who draws off the chunk populate `Random`. The largest bucket of the
published rows-vs-spiral residual — **38,856 blocks, "decoration (grass/flowers/trees/hives)"** — carries
the note *"endemic 1.7.10 decorator ordering; no per-mod fix known"*. That is a shrug, not an
attribution. This pass replaces it with a list.

Durable output: [docs/populate-stream-census.md](../../docs/populate-stream-census.md). This file is the
session record — how it was measured, what was retracted, and what is still unassessed.

## Provenance

GTNH `daily-707` at `~/.cache/gtnh-determinism/daily-707`, `level-type=rwg`, seed `-777`, radius 1
(no chunks are walked — `-Dprobe.gencensus` dumps and shuts down). Probe jar
`worldgenprobe-v0.8-main.2+4c6e016626-dirty`, md5 `e3e2c1fd9df11bf3623d0459bb6bca1e`, **the same jar for
all five arms** — this matters, see the trap below. Fix jar `gtnhdeterminism-v0.8-main.1+a5efcee3d6-dirty`
installed. JDKs `~/.gradle/jdks/azul_systems__inc_-{17,21}-amd64-linux.2`, default `hashCode` on every arm.

Source read from `all-gtnh`, with all 20 consumer repos that had an available tag checked out at the
version daily-707 ships (`GT5-Unofficial 5.09.54.50 -> 5.09.54.115`, `Et-Futurum-Requiem 2.6.49 -> 2.6.57`,
`NewHorizonsCoreMod 2.9.20 -> 2.9.53`, and 17 more). Six repos absent from `all-gtnh` were cloned:
`Automagy-GTNH`, `Galaxy-Space-GTNH`, `harvestcraft`, `CoFHCore@1.7.10`,
`BiomesOPlenty@BOP-1.7.10-2.1.x`, `EndlessIDs`.

Re-running the source scan before and after the version alignment changed **nothing but line numbers** —
the consumer set is stable across that version gap. Worth recording so the next person does not redo it.

## Method

Four tools, in this order. The point of having two halves is that neither is sound alone: source is a
superset (config-gated registrations that never happen) and a subset (closed source, Scala, reflection),
and only the reconciliation of the two is an argument for completeness.

| script | question |
|---|---|
| `scripts/map-jars-to-source.py` | which of the 217 shipped jars can be read as source, and is the checkout at the shipped version |
| `scripts/triage-sourceless-jars.py` | of the jars with no repo, which actually touch a populate hook — the only ones `javap` is justified on |
| `scripts/scan-populate-consumers.py` | the consumer superset, from source, restricted to repos the pack actually ships |
| `scripts/census-report.py` | reduce N `-Dprobe.gencensus` dumps; reconcile against the scan |

**191 of 217 jars have source.** Of the 26 without, 12 touch a populate hook; 2 of those are this repo's
own jars and 6 were cloned during this pass, leaving **four that genuinely need bytecode**: Thaumcraft,
Witchery, ExtraUtilities, IC2. Nothing else was decompiled.

## Result 1 — stream B: the FML generator order is per-launch, not per-JVM

`GameRegistry.computeSortedGeneratorList` stable-sorts an `ArrayList` copy of a `HashSet`, so within a
weight the order is `System.identityHashCode` order. Five arms, one jar, one pack, one seed:

| pair | positions differing |
|---|---:|
| **17a vs 17b** (same JVM, same jar) | **22 / 30** |
| **17b vs 17c** (same JVM, same jar) | **19 / 30** |
| 17a vs 17c | 25 / 30 |
| 21a vs 21b (same JVM, same jar) | 20 / 30 |
| 17a vs 21a | 25 / 30 |
| 17b vs 21a | 22 / 30 |
| 17c vs 21b | 23 / 30 |

**The same-JVM floor is 19–22 of 30, and the cross-JVM figures are 21–25 of 30.** They are the same
number. So this is launch variance, and the Java 17-vs-21 comparison adds nothing — which is exactly why
the floor had to be measured first (HANDOFF rule 10). Had only 17-vs-21 been run, the obvious and wrong
conclusion was "the JVM version reorders the generators".

Per weight tie-block, across the five arms:

| weight | members | distinct orders seen |
|---:|---:|---:|
| 0 | 19 | **5 of 5** |
| 1 | 1 | pinned |
| 2 | 1 | pinned |
| 20 | 3 (Natura crops / clouds / trees) | 3 |
| 1000 | 1 (Chisel) | pinned |
| 2147483647 | 5 | **5 of 5** |

The top block is the interesting one:

```
17a: SliceApplier   -> LootGames -> SpaceGen   -> EtFuturumLate -> GTWorldgenerator
17b: LootGames      -> EtFuturumLate -> SpaceGen -> SliceApplier -> GTWorldgenerator
17c: SpaceGen       -> SliceApplier -> LootGames -> EtFuturumLate -> GTWorldgenerator
21a: EtFuturumLate  -> SliceApplier -> LootGames -> SpaceGen     -> GTWorldgenerator
21b: EtFuturumLate  -> SliceApplier -> SpaceGen  -> LootGames    -> GTWorldgenerator
```

GregTech is genuinely last every time — its own `GameRegistryMixin` pulls `GTWorldgenerator` out of the
collection and appends it. But it pins **only itself**. EtFuturum's deepslate generator, LootGames and
this repo's own `PendingSlices$SliceApplier` are left tying among themselves, and the first two are named
categories in the residual table. `SliceApplier`'s position relative to them is a coin flip per launch,
which is worth knowing given that `results/2026-08-27-gt-ore-probe-pinning` ran a deliberate
SliceApplier-ordering experiment and reported "no effect" — that experiment moved the applier relative to
*GregTech*, which is pinned, and could not have moved it relative to EtFuturum or LootGames.

### What this does and does not license

**It does not license a claim that the world moves.** Two identical cold launches of this pack differ by
~6 blocks (README, "launch floor"), and a `-XX:hashCode=3` pair with the fix jar differs in 0 chunks. So
if 19+ of 30 generator positions shuffle on every launch and the world stays put, these generators mostly
do not contend for the same blocks. The honest statement is: **the order is measurably unstable and its
observable cost is bounded above by the measured launch floor.** Quoting the first half without the
second would be the "percentages flatter" mistake in reverse.

**It does retract a HANDOFF claim and a testing rule.** `docs/HANDOFF.md` currently says cold runs are a
bad launch-variance test because default `hashCode=5` gives identical ordering across launches, and that
`-XX:hashCode=3` or a Java 17/21 pair is needed instead. That was measured on 8 objects in a controlled
micro-test. In a 220-mod pack the xorshift *sequence* may well be fixed, but the **offset** into it
depends on how many identity hashes were requested during startup, and mod loading does not request the
same number twice. Cold runs do vary the ordering, on their own, with no flags.

## Result 2 — stream A: the event dispatch order is stable

Read from each event class's own `ListenerList`, so base-class subscribers appear in their real position:

| event | bus | listeners | identical across 5 arms |
|---|---|---:|---|
| `PopulateChunkEvent.Pre` | EVENT_BUS | 8 | yes |
| `PopulateChunkEvent.Post` | EVENT_BUS | 7 | yes |
| `PopulateChunkEvent.Populate` | TERRAIN_GEN_BUS | **0** | yes |
| `DecorateBiomeEvent.Pre` | EVENT_BUS | **0** | yes |
| `DecorateBiomeEvent.Post` | EVENT_BUS | 2 | yes |
| `DecorateBiomeEvent.Decorate` | TERRAIN_GEN_BUS | 7 | yes |
| `OreGenEvent.Pre` / `.Post` | ORE_GEN_BUS | **0** / **0** | yes |
| `OreGenEvent.GenerateMinable` | ORE_GEN_BUS | 5 | yes |

A useful negative result: **the event busses are not a source of order nondeterminism.** Registration
order within a priority phase is deterministic given a fixed mod set, and it held across five launches
and two JVMs. Whatever moves the decoration stream, it is not who-runs-first among the listeners.

Three structural facts fall out of the zero-entry rows:

- **No `PopulateChunkEvent.Populate` listener exists**, so RWG's `TerrainGen.populate(... LAKE / LAVA /
  DUNGEON / ANIMALS / ICE)` gates always return their default. Those five gates are not a lever anyone
  is pulling.
- RWG **discards the return of all 12 `TerrainGen.decorate` calls**, so every `Decorate`-stage
  `setResult(DENY)` in the pack is inert under `level-type=rwg` — including BiomesOPlenty's, which DENYs
  `LAKE` in every BOP biome and has no effect here.
- That leaves **`OreGenEvent.GenerateMinable` as the only live result-lever in the overworld**, with
  three listeners of which two are switched off by pack config. GregTech's `GTProxy.onOreGenEvent` is the
  sole active one — which is why `generateUndergroundDirtGen` behaves the way
  [2026-09-06-dirt-gravel-attribution](../2026-09-06-dirt-gravel-attribution/README.md) describes.

## Result 3 — named contributors to the "decoration" bucket

Applying the S1 test (*does the consumer draw on a code path whose reachability depends on a live world
read?*) to the registered stream-A consumers. Full table with flags in the census doc; the four that
matter:

| consumer | where | why it skews the shared stream |
|---|---|---|
| `WorldGenGeode.placeOre` (Railcraft + NHCore geodes) | `PopulateChunkEvent.Pre` | `if (getBlock(x,y,z) == blockStone) { rand.nextDouble(); … }` — the draw happens only where the geode's own stone already landed, and the geode is a 16³ cube spanning four chunks. At the **front** of the chunk, so a flip shifts every later draw |
| `SurfaceOreGen` via TiC `TerrainGenEventHandler` and NHCore `ZincGravelWorldgen` | `Decorate`, type `SAND` — the **first** of RWG's 12 | `if (alterSize) { startY = findSurface(...); if (startY == -1) return false; }` returns before the first draw. All seven surface ores pass `alterSize = true` |
| `HiveDecorator` (Forestry wild hives) | `PopulateChunkEvent.Post` | `Collections.shuffle(hives, rand)` on a **shared, persistent** list — the permutation depends on what the previous chunk's shuffle left behind, so hive choice is route-dependent with no world read involved. Plus `genHive` breaks out early on a live-terrain hit |
| `BiomeDecorationHandler.generateFlowers` (Botania) | `Decorate`, phase LOWEST | `isAirBlock` + `canBlockStay` gate a `nextBoolean()` / `nextDouble()` |

Two that look like S1 and are not, which is the whole reason for having a mechanical test:

- **`QuarryPopulator` / `NH_QuarryPopulator`** read `getTopSolidOrLiquidBlock` and `getBlock == dirt`
  before calling `quarry.generate` — but `WorldGenQuarry` makes **zero** draws. The read decides whether
  blocks are written, never how many draws are taken. `O1`, not `S1`.
- **`OilGeneratorFix`** takes exactly 2 draws, or 3 when `shouldSpawnOil` passes, and `shouldSpawnOil`
  decides on dimension, biome id and `biome.rootHeight` — never a live block read. Everything after
  (`checkOilPresent`, `getTopBlock`, `buildOilStructure`) reads and writes without drawing. So the oil
  route-instability in [2026-09-01-gtnh-oil-route-stability](../2026-09-01-gtnh-oil-route-stability/README.md)
  is **first-writer-wins across a multi-chunk sphere, not draw skew** — a different fix shape entirely.

## Completeness: the reconciliation

| | count |
|---|---:|
| jars shipped | 217 |
| with source in `all-gtnh` | 191 |
| source-less jars touching a populate hook | 12 (2 ours, 6 cloned, **4 need `javap`**) |
| repos hit by the source scan | 33 |
| `IWorldGenerator`s registered at runtime | 30 |

**In source, not registered — six, each with a named config key:**

| generator | reason |
|---|---|
| `QuartzWorldGen` (AE2) | `config/AppliedEnergistics2/AppliedEnergistics2.cfg` → `B:CertusQuartzWorldGen=false` (`MeteoriteWorldGen=true`, and it is registered) |
| `WorldGenPamSalt`, `PamBeeGenerator` | `config/harvestcraft.cfg` → `B:enablesaltGeneration=false`, `B:enablebeehiveGeneration=false`. Garden and tree generators are unconditional and are registered |
| `NaquadahOreWorldGen` (SGCraft) | `config/SGCraft.cfg` → `B:enableNaquadahOre=false` |
| `OverworldGenerator` ×5 (Galacticraft, GalaxySpace) | `config/Galacticraft/core.conf` → `B:"Enable Copper Ore Gen"=false` and siblings |
| `OreClusterGenerator` (ThaumicTinkerer) | never passed to `GameRegistry` — it is a field on `ChunkProviderBedrock`, the TT dimension's own chunk provider |

Railcraft is the same story on the event side: `config/railcraft/railcraft.cfg` ships `saltpeter=false`,
`sulfur=false`, `iron/gold/copper/tin/lead=false`, `abyssal=true`, `quarried=true`, `firestone=true` —
which is exactly the registered set (`GeodePopulator`, `QuarryPopulator`, `FirestoneGenerator` present;
`SaltpeterGenerator`, `SulfurGenerator` and the five `PoorOreGenerator`s absent).

**Registered, not in the source scan — four:** `IC2`, `ThaumcraftWorldGenerator`, `WitcheryWorldGenerator`
(no source anywhere, read with `javap`) and this repo's own `PendingSlices$SliceApplier`.

## Traps hit on the way

1. **Matching jars to repos on filenames does not work, and matching on `mcmod.info` alone does not
   either.** GTNH templates its `mcmod.info` — every repo literally contains `"modid": "${modId}"`,
   resolved at build time from `gradle.properties`. The first version of `map-jars-to-source.py` reported
   48 unmatched jars, most of which had source one directory away. Reading `gradle.properties` first took
   it to 26.
2. **A `*.java` glob misses Scala, and a `;` anchor misses Scala too.** MrTJPCore registers
   `object SimpleGenHandler extends IWorldGenerator` from a `.scala` file with no trailing semicolon, so
   the scan missed it twice over — once on the file glob, once on `REGISTER_RE`. It was found by the
   runtime dump, which is precisely why the runtime half exists. Both fixed.
3. **Changing the probe jar between arms invalidates the comparison.** The identity-hash offset depends
   on startup allocation, and the jar is part of that. An early Java 17 arm was taken before a rebuild
   and read as a difference; all five arms reported here share one md5. `census-report.py` says so in its
   docstring but cannot enforce it — check the md5s.
4. **Two of the three MXBean routes to `-XX:hashCode` throw under the Forge class loader** and silently
   reported `hashCodeMode=unknown` on a JVM that answers fine from the command line. Now read back off
   `RuntimeMXBean.getInputArguments()`, which reports `default` when no flag was passed.

## What this cannot see, and what is left

The probe shuts down inside `FMLServerStartedEvent`, **before the tick loop**. Anything deferred past
generation is invisible here: AE2 meteorites (`TickHandler` + `chunkExists`), Witchery's village walls,
and any other tick-time worldgen. No `CLEAN` in the census doc covers tick-time behaviour.

Still unassessed, all on `PopulateChunkEvent.Post`: Galacticraft `EventHandlerGC.populate` (probably
inert — `Oil Generation Factor=0` in the pack config), BuildCraft `SpringPopulate`, VillageNames
`WellDecorateEvent.onPopulating`. And the whole `BiomeDecorator`-subclass path (42 classes found, almost
all in other dimensions) has not been walked.

The obvious next instrument is a `CountingRandom` swapped into `ChunkGeneratorRealistic.rand` with
per-listener draw attribution, which would turn every S1 verdict above from a source reading into a
measurement and — via a per-chunk draw-balance identity — prove the consumer list complete rather than
argue it. That was scoped out of this pass deliberately: the ask was the list.

## Artifacts

Committed: `census-report.txt` (the reduction), `generator-order-per-arm.txt` (the five orders side by
side — the extract the headline claim rests on), `sourceless-enum.txt` (`javap` on the four).

Present but gitignored under the `results/**/*.json` bulk-dump rule: `gencensus-{17a,17b,17c,21a,21b}.json`
(the raw dumps), `consumers.json` (source scan), `jarmap.json` (jar → repo → shipped version → checkout
state). All five gencensus dumps are ~8.6 KB; regenerate with the commands at the end of
[docs/populate-stream-census.md](../../docs/populate-stream-census.md).
