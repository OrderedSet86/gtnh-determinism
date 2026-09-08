# Structure siting pins the shared `world.rand` to a function of the world seed

**Status:** measured 2026-09-07 against `daily-707` (GTNH 2.9.x daily 707, world type `rwg`), headless via
`scripts/run-probe.sh`. Not fixed in `fix-build`.

## Summary

`World.setRandomSeed(x, z, salt)` reseeds the **shared** `World.rand` to

```
x * 341873128712 + z * 132897987541 + worldSeed + salt
```

It is the only place in vanilla + Forge where `world.rand`'s seed is ever set after construction. Structure
siting calls it constantly during chunk generation. Every consumer of `world.rand` therefore reads a value
that is a pure function of the world seed and a structure-grid cell, for as long as nothing else advances the
stream.

This is a cross-contamination leak, not a worldgen determinism bug: it makes worldgen *more* reproducible
while silently making unrelated gameplay RNG seed-derived. Thaumcraft's loot bag is the case that surfaced it.

## Measurement

`probe-build` gained three flags for this (uncommitted at time of writing):

- `-Dprobe.randstate=<path>` — dump 8 `nextLong()` from `world.rand` the instant the generation walk ends
- `-Dprobe.randtrace=true` — swap `world.rand` for `TracingRandom`, counting `setSeed` calls, the last
  argument, and `next()` calls since that argument
- `-Dprobe.randpin=<long>` — force `world.rand` to a known state before the walk

> **`-Dprobe.randpin` is not for normal use.** A real world never re-seeds `world.rand` from outside
> generation, so a pinned run is not a faithful reproduction of how the game generates. Keep it out of
> determinism A/Bs, seed searches, and any run whose output is meant to describe real worldgen — leaving
> `world.rand` alone is part of what those runs measure.
>
> It existed to answer one question: does generation re-seed `world.rand`, or is a differing post-walk
> fingerprint just leftover state from a wall-clock-dependent number of boot ticks? That question is now
> answered below, and the answer makes the flag redundant — generation re-seeds tens of thousands of times per
> walk, so the pin is overwritten regardless, and a pinned run converges with an unpinned one (arms `e` and
> `b`). Use `-Dprobe.randtrace` instead: it reports what happened without changing it.

Seed 4242, radius 4 (121 chunks), GotG config (`disableChunkTerrainGeneration=true`,
`disableWorldTypeChunkPopulation=true`, modded chunk population left on).

Totals were identical in every run:

```
setSeedCalls          48132
totalNextCalls        96448
distinctSetSeedArgs      12
```

48132 reseeds over 121 chunks, across only 12 distinct arguments — the siting grids are large enough that a
whole walk falls into a handful of cells.

The end state was always `new Random(worldSeed + salt)` advanced by a short tail, reproduced bit-exactly
offline for all three observed outcomes:

| Seed argument | Salt | Tail | Caller |
| --- | --- | --- | --- |
| `4242 + 10387312` | 10387312 | 2 | `MapGenVillage` (see disambiguation below) |
| `4242 + 14357617` | 14357617 | 2 | `MapGenScatteredFeature` |
| `4242 + 10387313` | 10387313 | 4 | Et-Futurum Requiem `OceanMonument.java:183` |

Run-to-run variation is confined to *which* generator ran last. Total work is deterministic, so this is an
ordering effect rather than genuine nondeterminism.

### Disambiguating `MapGenVillage` from Roguelike

Both use salt 10387312, and near the origin both grids give cell (0,0). Re-running the walk centred at chunk
40 separates them, because the last walked chunk is 45 and `45 / 32 = 1` while `45 / 51 = 0`.

Three arms at `-Dprobe.cx=40 -Dprobe.cz=40` were **bit-identical** — no race at all:

```
setSeedCalls 70674   totalNextCalls 141548   distinctSetSeedArgs 9
lastSetSeedArg 474781507807   nextCallsAfterLastSetSeed 2
```

`474781507807 = 1*341873128712 + 1*132897987541 + 4242 + 10387312`, i.e. **cell (1,1), salt 10387312**. Cell
(1,1) is the 32-grid, so the last reseeder is `MapGenVillage`, not Roguelike.

That is a property of the walk, not of the pack, and the PoC below settles it the other way. The probe's walk
ends on `loadChunk(lastChunk)`, which runs `provideChunk` (village and scattered-feature siting) but never
populates that chunk — population needs the 2x2 neighbourhood, and Roguelike runs at populate time via
`GameRegistry.generateWorld`.

**When population completes, Roguelike is the last reseeder.** The PoC loads single chunks, so population
finishes, and the observed pins decode only on the 51-grid: chunk 40 pins to cell (0,0), which village's
32-grid cannot produce (`40/32 = 1` would give 474781507807). Chunk 80 pins to cell (1,1), again 51-grid. A
live session populates normally, so Roguelike is the caller that matters there.

The near-origin walk showed a three-way race and the chunk-40 walk showed none, which is consistent with the
spawn-area preparation generating chunks on a different path.

## Call chains

Vanilla, inside `ChunkProviderGenerate.provideChunk` (lines 235-240, gated on `mapFeaturesEnabled`):

```
villageGenerator.func_151539_a       -> MapGenBase.func_151539_a  (loops chunk +/- range=8, so 289 cells)
  -> MapGenStructure.func_151538_a
    -> MapGenVillage.canSpawnStructureAtCoords   -> world.setRandomSeed(i1, j1, 10387312)
scatteredFeatureGenerator.func_151539_a
  -> MapGenScatteredFeature.canSpawnStructureAtCoords -> world.setRandomSeed(i1, j1, 14357617)
```

Roguelike Dungeons, via `GameRegistry.generateWorld` (called unconditionally from
`ChunkProviderServer.populate`, whatever the chunk provider):

```
Roguelike.java:34            GameRegistry.registerWorldGenerator(worldGen, 0)
DungeonGenerator.generate    -> dungeon.spawnInChunk
Dungeon.java:131             editor.getSeededRandom(m, n, 10387312)
WorldEditor.java:83          return world.setRandomSeed(a, b, c)
```

`Dungeon.canSpawnInChunk` reseeds *before* any terrain or biome test — only `doNaturalSpawn` gates it — so it
fires even where a dungeon can never generate. Grid is `max = 32 * spawnFrequency / 10`; GTNH ships
`spawnFrequency=16`, giving a 51x51-chunk cell.

Mod copies of the same formula: `MapGenVillageRTG`, `MapGenScatteredFeatureRTG` (Realistic Terrain
Generation), `MapGenVillageCC` (Climate Control), `VNMapGenIgloo` (VillageNames),
`MapGenVillageMoon` (Galacticraft).

## Why Garden of Grind exposes it

Hodgepodge PR #358 adds three `ChunkProviderServer` mixins:

| Config | Annotation | Target |
| --- | --- | --- |
| `disableChunkTerrainGeneration` | `@ModifyVariable` in `originalLoadChunk` | `IChunkProvider.provideChunk` |
| `disableWorldTypeChunkPopulation` | `@Redirect` in `populate` | `IChunkProvider.populate` |
| `disableModdedChunkPopulation` | `@Redirect` in `populate` | `GameRegistry.generateWorld` |

The first modifies `provideChunk`'s *return value*, so `provideChunk` still executes and the vanilla siting
checks still run. The GotG ruleset leaves modded chunk population enabled, so Roguelike runs too. The net
effect is to strip out every ordinary *consumer* of `world.rand` — terrain, ores, decoration, mob spawning —
while leaving the *reseeder* running.

## Reaching a right-click

One server tick, in execution order. Line numbers are within each method in the RFG-decompiled sources.

```
MinecraftServer.updateTimeLightAndEntities()
  33  worldserver.tick()
        3   super.tick()                            -> updateWeather, occasional world.rand draws
        27  animalSpawner.findChunksForSpawning     -> world.rand draws (mob spawning)
        31  chunkProvider.unloadQueuedChunks()
        47  tickUpdates(false)
        49  func_147456_g()                         -> AMBIANCE: 2 next() per active chunk   SCRAMBLES
        51  thePlayerManager.updatePlayerInstances()-> chunk load/generate -> setRandomSeed  PINS
  44  worldserver.updateEntities()                  -> entities mostly use their own Entity.rand
  67  networkTick()                                 -> player packets, incl. the bag right-click  READS
```

The ordering favours the pin surviving: the ambiance scramble is at line 49, *before* generation at line 51,
and the right-click is read afterwards in `networkTick`. Nothing between line 51 and `networkTick` draws
appreciably from `world.rand` in a void world with few entities.

A bag opened in the same tick as a generation reads the pinned state directly, with only the 2-4 siting draws
between.

### The packet path makes this deliberately targetable

Chunk generation is not confined to `updatePlayerInstances` at line 51. `NetHandlerPlayServer.processPlayer`
— the `C03PacketPlayer` movement handler, which runs *inside* `networkTick()` — calls
`updatePlayerPertinentChunks` (line 264), which reaches `PlayerManager:405 loadChunk(x, z, runnable)`. And
`ChunkProviderServer.loadChunk` only uses the async queue for chunks already on disk ("We can only use the
queue for already generated chunks"); anything needing generation falls to `originalLoadChunk` **synchronously
on the packet-handler thread**.

So within one `networkTick()`, in packet arrival order:

1. `C03PacketPlayer` -> chunk generation -> structure siting -> `setRandomSeed(cell, salt)`
2. `C08PacketPlayerBlockPlacement` -> `ItemLootBag.onItemRightClick` -> reads the freshly pinned `world.rand`

A player can therefore *cause* the pin in the same packet burst as the click, rather than waiting for a
generating tick to coincide. Requirements, from `PlayerManager:264-273`: move at least 8 blocks from
`managedPos` (`d2 >= 64.0D`) **and** change chunk (`j1 != 0 || k1 != 0`), into chunks that have never been
generated. A single key tap is not enough. Ordering is effectively free because the client streams movement
packets every tick while moving, so a click lands after a movement packet.

This only makes the result *reproducible*, not *selectable*: the pin is `worldSeed + salt` for the
structure-grid cell, constant across 512 blocks, so every bag opened this way within one cell yields an
identical payload. Crossing into the next cell is the only reroll, and it is coarse and unaimed.

**The pin's influence is not limited to that tick.** The ambiance loop *advances* `world.rand`; it does not
re-seed it, and advancing an LCG by a deterministic number of steps preserves determinism. If the per-tick
draw count `D` is constant, the state `n` ticks after an anchor is `anchor + n*D` — still a pure function of
the world seed.

Stated precisely, with `t` the draws after the pin within the pin tick (the 2-4 siting draws measured above,
small because the pin at line 51 follows the ambiance at line 49), `n` the number of full ticks since, and `D`
the draws per full tick:

```
state at bag open = anchor advanced by (t + n*D)
```

**`D` is not constant, and worse, it is not deterministic.** `SpawnerAnimals.findChunksForSpawning` is the
problem, in three escalating ways:

- Line 95 shuffles the eligible-chunk list with the **no-argument** `Collections.shuffle`, which draws from
  the static `Collections.r` — nanoTime-seeded, shared JVM-wide, advanced by every other one-arg shuffle in
  the pack. Visit order is therefore re-randomised every tick from a source that is neither seed-derived nor
  observable. (Forge patch; vanilla iterates the keySet directly.)
- The per-chunk draw count is data-dependent. `func_151350_a` spends 3 draws choosing a point, then the
  `!isNormalCube() && getMaterial() == creatureMaterial` gate decides whether to enter an inner loop worth up
  to 3 x 4 iterations at 6 draws each. Whether that point lands in solid terrain or in air swings the count by
  up to 72.
- Once mobs do spawn, `countEntities` at line 91 shifts the gate and successful spawns take early exits.

Ambiance is the well-behaved consumer — exactly 2 draws per active chunk, both `rand.nextInt` calls second in
a short-circuit `&&`, so outcome-independent and constant given `|activeChunkSet|`. It is not enough to
rescue `D`.

The tick ordering does contain the damage, though. The spawner runs at line 27, the ambiance at line 49, and
the pin at line 51 — so **the pin erases every draw the spawner and ambiance made in that tick**. A bag opened
in the pin tick sees only `t`. A bag opened `n >= 1` ticks later has absorbed `n` ticks of
`Collections.r`-driven noise of unknowable size.

So `n = 0` is clean and `n >= 1` is unpredictable. Two further gaps would remain even if `D` were constant:
`t + n*D` propagates determinism but does not create it, so a seed-derived anchor is still required; and LCG
state is chaotic in `n`, so a player cannot reproduce `n` by repeating an action.

`IntegratedServer.tick()` skips `super.tick()` entirely while the game is paused, so the ESC menu stops the
churn. `World.rand = new Random()` is a field initialiser, so reloading a world constructs a fresh
nanoTime-seeded stream. Both player-visible triggers are real; neither is the cause.

## This DOES explain the player report

The GoG community's own conclusion:

> Due to the configs we enable in hodgepodge, the rng for the bags resets whenever you so much as go to the
> escape menu, rolling you back to the first ever drop for that world. To alleviate this, save up bags and
> open them all at once!

Every clause maps onto the measured mechanism. "Resets" is `setSeed`, a jump that discards history, not an
advance. "The first ever drop for that world" is `new Random(worldSeed + salt)` for the spawn cell — a fixed
per-world constant. "Save up bags and open them all at once" works because consecutive bags walk forward
through the stream: bag 2 reads the pin advanced by bag 1's draws, and so on.

The multi-bag PoC reproduces both halves (seed 4242, five bags per pin):

- **Bags within one pin all differ** — 5/5 distinct in every trial, with `q` varying 11/11/10/12/10.
- **Independent generations that pin to the same value give byte-identical bag sequences** — five separate
  chunk generations at pin 10391554 produced the same five bags in the same order. Bag 1 is always
  `gold_ingot` + 9 Gold Coins at `q=11`.
- A different pin (474781507807) yields a completely different sequence.

### The trigger: unpopulated chunks, not new terrain

A re-pin does not require newly generated terrain, because "already on disk" does not imply "populated".
`Chunk.populateChunk` only runs when the 2x2 neighbourhood exists:

```java
if (!this.isTerrainPopulated && p_76624_1_.chunkExists(x + 1, z + 1)
        && p_76624_1_.chunkExists(x, z + 1) && p_76624_1_.chunkExists(x + 1, z)) {
    p_76624_1_.populate(p_76624_2_, x, z);
}
```

So every loaded region keeps a frontier ring of generated-but-unpopulated chunks, saved to disk in that state.
Hodgepodge's `ChunkGenScheduler` re-tracks them on load —

```java
// Re-track orphaned chunks that were already loaded but not populated
if (existingChunk != null && !existingChunk.isTerrainPopulated) {
    trackIfUnpopulated(existingChunk, x, z);
}
```

— and `processTick` drains that queue into `GameRegistry.generateWorld`, which re-pins `world.rand`. **This
explains the reload trigger even in a fully explored base.**

### Measured: ESC does not re-pin

`-Dprobe.escbench` (see `EscBench`) drives a real GTNH client — daily 707, GoG config, world type RWG, created
fresh at seed 4242 — installs `TracingRandom` on the integrated server's `world.rand`, settles 600 ticks, then
alternates 100 ticks unpaused / 100 ticks with `GuiIngameMenu` open, six times, logging every server tick.

Result over 1235 server ticks:

```
6 pause gaps, each clientGap=101 (the pauses fired)
setSeedCalls: 0 at every row, across every gap
totalNext:    26,754 -> 33,098,287
```

The tracer was live — `world.rand` was drawn from **33 million times**, about 26,800 draws per tick — and its
seed was **never set once**. So in a settled world under GoG configs, opening the ESC menu does not re-pin
`world.rand`, and neither does anything else. An earlier revision claimed the ESC menu "gates exactly that
drain"; that was asserted without measurement and is now measured false.

This is consistent with the frontier-ring analysis: both scheduler entry points guard on
`!chunk.isTerrainPopulated`, and `Chunk.func_150809_p()` sets that flag inside `ChunkProviderServer.populate`
*before* the inner `populate` call GoG redirects, so populated chunks are never re-processed. With the player
stationary and the reachable frontier already populated, the queue is empty and there is nothing to re-pin.

**The reporters' ESC trigger therefore remains unexplained.** What is explained is the reload trigger, and the
pin mechanism itself. A settled, stationary base appears to be the one situation where the leak cannot fire —
which suggests their scenario involves chunk loading (movement, or rejoining) rather than the pause as such.

### Measured: draws per tick are not constant

The same run measures `D`, the per-tick draw count that an `anchor + n*D` reproducibility argument would need
to be stable:

```
ticks=1234  mean=26800.3  min=26568  max=38478  spread=11910
```

Not constant, and not close. This confirms from measurement what the source reading predicted: the
`Collections.shuffle(tmp)` at `SpawnerAnimals:95` draws from the unseeded JVM-global `Collections.r`, and the
per-chunk draw count is data-dependent. Any model that propagates a pin forward across ticks is dead.

### The loot table is not the explanation

An earlier revision of this document attributed the report to the loot table's weight concentration. That was
wrong. The table makes bags *uniformly* repetitive; it cannot make them identical when opened singly and
varied when opened in a batch. Only a re-pinned LCG produces that shape. The table numbers below remain
accurate and are worth knowing, but they describe a separate balance issue.

Dumped live with `-Dprobe.tclootdump` (daily-707):

| Table | Entries | Total weight | Dominant entry | Share | Effective items (inverse Simpson) |
| --- | --- | --- | --- | --- | --- |
| `lootBagCommon` | 97 | 2967 | `Thaumcraft:ItemResource@18` (Gold Coin) x1 | 84.26% | 1.4 |
| `lootBagUncommon` | 102 | 2837 | Gold Coin x2 | 79.31% | 1.6 |
| `lootBagRare` | 104 | 2615 | Gold Coin x3 | 76.48% | 1.7 |

`ItemLootBag` draws `8 + nextInt(5)` items, so a common bag averages 2.48 distinct item types. Simulated over
200k bags: 18.6% contain nothing but Gold Coins, and a further 36.5% contain Gold Coins plus exactly one other
type — **55.1% of common bags are "a pile of coins, maybe one other thing"**. The remaining variation is
carried by `minecraft:gold_ingot` and `minecraft:ender_pearl` at 3.37% each, then a tail below 1%.

A player opening these would reasonably describe the drops as consistent no matter what the RNG did. Note the
tables are pack-global and identical in every world, so they cannot produce genuine seed-dependence — the
"depending on seed" part of the report is most likely over-reading a constant.

This concentration is a balance problem in its own right — a "loot bag" whose common tier is 84% Gold Coins —
but it is not what produces the reported reset behaviour. The multi-bag PoC settles that: the table cannot
make bags identical when opened one at a time and varied when opened in a batch.

### Turning structures off does not remove the leak

`mapFeaturesEnabled=false` skips the whole `if (this.mapFeaturesEnabled)` block in `provideChunk`, removing
the vanilla village (salt 10387312) and scattered-feature (14357617) reseeders. Roguelike is **not** gated by
`mapFeaturesEnabled` — it runs via `GameRegistry.registerWorldGenerator`, gated only by `doNaturalSpawn` and
its dimension whitelist — so it keeps pinning on salt 10387312, grid 51.

The result is fewer reseeders competing to be last writer, which is what produced the three outcome groups
measured above. So disabling structures makes the anchor *more* predictable, not less.

The switch that actually removes it is `disableModdedChunkPopulation=true`, which redirects
`GameRegistry.generateWorld`. With structures off and modded population off, nothing calls `setRandomSeed` and
`world.rand` stays the nanoTime stream it was constructed with.

The GotG ruleset (structures allowed, modded chunk population left on) therefore has every reseeder active.
MGoG's superflat variant uses `ChunkProviderFlat(..., generateStructures=false, ...)`, so it has no vanilla
structures but still runs Roguelike.

## Proof of concept

`-Dprobe.bagpoc=<path>` runs the end-to-end demonstration. Each trial generates one never-before-generated
chunk, then immediately calls Thaumcraft's real `Utils.generateLoot` off `world.rand` in the exact shape of
`ItemLootBag.onItemRightClick` — `q = 8 + rand.nextInt(5)`, then `q` draws — with nothing in between. That is
the packet-path ordering, since `processPlayer` generates synchronously inside `networkTick` before the click
packet is handled. It reflects into the shipped Thaumcraft jar rather than reimplementing the loot logic, so
the payloads are real.

Six trials over three chunks per Roguelike 51-grid cell, seed 4242 twice in separate JVMs, seed 9999 as
control. All four predictions held:

| Prediction | Result |
| --- | --- |
| Separate generations in the same cell -> identical bag | PASS (5 trials, cell (0,0)) |
| Different cell -> different bag | PASS ((0,0) vs (1,1)) |
| Same seed, fresh JVM -> identical | PASS |
| Different seed -> different | PASS |

Cell (0,0), seed 4242, pin 10391554, tail 2, `q = 11` — byte-identical across chunks (5,5), (5,6), (6,5),
(40,40) and (40,41), five independent chunk generations:

```
gold_ingot@0 x1, ItemResource@18 x1 (x4), enchanted_book@0 x1, ItemResource@18 x1 (x5)
```

Cell (1,1), chunk (80,80), pin 474781507807, `q = 10` — a different payload led by `potion@16460` and
containing `ender_pearl` instead of `gold_ingot`.

Note the enchanted book reproduces too, which exercises the `EnchantmentHelper.addRandomEnchantment` branch
inside `generateLoot` — so the determinism extends through a nested consumer of the same stream, not just the
weighted pick.

Caveat: the PoC drives the ordering on the server thread rather than through a real client's packet stream. It
demonstrates that generation-then-bag-read yields seed-derived loot; that a player can land the movement and
click packets in one `networkTick` rests on the source reading above.

## Consumer that motivated this

`thaumcraft.common.items.ItemLootBag.onItemRightClick` takes no seeded `Random` of its own:

```java
int q = 8 + world.rand.nextInt(5);
for (int a = 0; a < q; ++a) {
    ItemStack is = Utils.generateLoot(stack.getItemDamage(), world.rand);
```

`Utils.generateLoot` draws `nextFloat` for the gear gate and `WeightedRandom.getRandomItem(rand, ...)` for the
item, all from the same `world.rand`.

## Open questions

- The headless probe has no player entity, so `activeChunkSet` is nearly empty and there is almost no ambiance
  churn. The tick-ordering argument above is read from source, not measured in a live session.
- The three-way ordering race seen near the origin is unexplained, and absent at chunk 40.
- Which caller lands last in a *live* session is untested. The probe walk never populates its final chunk, so
  it always ends on `provideChunk`-time siting; a real client, where population completes, may end on
  Roguelike instead.
- Whether a bag opened in one grid cell really repeats its payload across reloads has not been tested
  in-game. That is the prediction most worth checking, and the one with balance consequences.

## Not fixed

`fix-build` does not patch `getSeededRandom` or `setRandomSeed`. The formula currently appears in the repo
only as a seed-search accelerator, in `probe-build`'s `RoguelikePrefilter` (line 97), `WitcheryPrefilter`
(line 141) and `Prefilter` (line 2124).
