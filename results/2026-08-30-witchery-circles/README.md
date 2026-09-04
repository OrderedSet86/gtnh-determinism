# Witchery circles: predicted, with a measured bound

GTNH daily-707, repo `d17a685`. Probe jar `fb3c5ce4929dcd8d91a95f818bfa6bda`, fix jar
`6f7d4b41bb1e64edaccf9f438d82b752`.

`-Dprobe.prefilter.witchery.replay=true` now names the winning handler at every candidate cell and
emits its container contents. Coven circles included.

| check | result |
| --- | --- |
| candidate cells resolved to a winner | **26 / 26**, 0 errors |
| covens found | 4 |
| container contents byte-identical incl. NBT | **5 / 5** (4 covens, 1 shack) |
| of those, cases where predicted Y disagreed with the world | 1, contents still exact |
| order A/B content differences | 1 / 75, unchanged and pre-existing |
| chests sharing an XZ column (the accepted collision cost) | **0 of 263** |

Verified against full worldgen on 5 windows, seed `-1297854885530077460`.

## The winner was never a random-stream problem

It was recorded as unpredictable because choosing it means calling `IWorldGenHandler.generate`, which
writes blocks. Half of that was right. The **Random** was never an obstacle:
`GameRegistry.generateWorld` reseeds before *every* generator —

```java
long chunkSeed = (xSeed * chunkX + zSeed * chunkZ) ^ worldSeed;
for (IWorldGenerator generator : sortedGeneratorList) {
    fmlRandom.setSeed(chunkSeed);
    generator.generate(fmlRandom, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
}
```

— so the Random arriving at Witchery is a pure function of `(worldSeed, cx, cz)` and owes nothing to
any other mod's draws. There is no stream to replay and no mixin needed to make it positional.

Only the writes were real, and writes are the one thing an overlay solves.

## The overlay

`SeedProbeWorld` gains a scoped scratch overlay: inside `beginOverlay()`/`endOverlay()` writes land in
a discardable map and reads answer own-writes first, virgin terrain otherwise — the same semantics
`WorldEditorMixin` gives Roguelike under F5. Outside that window every write is refused exactly as
before, so no other consumer can be affected.

`setBlock` also builds the tile entity, because that is how a dispenser gets its loot: generation code
writes the block, asks for the tile entity back and fills it. An overlay that skipped that step would
hand back null and silently place an empty container.

The real handler loop then runs against it — one `Random(chunkSeed)` shuffled into a sorted copy, then
each handler's real `generate` — and the containers are read straight out of the overlay. No
reimplementation of placement rules, terrain gates or loot.

Two bugs cost a run each, both the same shape as one from the stronghold work: reflective handles
resolved against the **first handler's class** rather than the `IWorldGenHandler` interface, so
`Method.invoke` threw `object is not an instance of declaring class` on every other handler. 18 of 26
cells failed silently into `ERROR` before this was fixed.

## The bound, and the fix for it: Witchery generates AFTER decoration

```java
this.currentChunkProvider.populate(...);            // structures + full decoration
GameRegistry.generateWorld(cx, cz, worldObj, ...);  // Witchery runs here
```

Witchery is an FML `IWorldGenerator`, so `ComponentCoven.calcGroundHeight` reads **decorated** terrain
— trees, plants, snow layers, gravel, lakes. The prefilter has only virgin terrain, and where
decoration raised the sampled column the circle lands one block lower in the replay than in the world.
Measured: the coven at cell `[32, 64]` is predicted at `(39, 63, 71)` and the world has it at
`(39, 64, 71)`.

With Y in F10's absolute fork that one-block disagreement silently changed the contents. **Y is now
dropped from the absolute fork**, so contents are a function of XZ alone and survive it:

```
world:            abs=39,64,71  rolls=7
prefilter replay: abs=39,63,71  rolls=7   <- contents byte-identical
```

The accepted cost is that two chests sharing an XZ column at different heights now share a fork, and
therefore their contents. **Not observed: 0 of 263 chests examined share an XZ column.** That bounds
it as rare, not as impossible — stacked containers exist in principle.

What this does NOT fix, and should not be claimed to: the reported **position** is still one out in Y
in that case, and a large enough ground-height disagreement could in principle flip
`ComponentCoven`'s `isWaterBelow` corner checks and so the winner, or whether anything places at all.
Neither was observed; neither is excluded by 5 samples.

Determinism is unchanged by the fork change. A `rows` vs `spiral` A/B over the same window gives 1
content difference in 75 shared positions, and it is the pre-existing Forestry bee chest at
`(240,68,309)` that no hook sees — the same residual recorded before this work.

## A stale jar invalidated one round of this

The first attempt at the Y drop appeared to fail: contents still differed, and the traces showed
`rolls=7` against `rolls=5`. The cause was not the fix. `cp -r src dst` where `dst` already exists
creates `dst/src`, so a re-clone of the server silently left the parallel arm on the previous jar and
the comparison ran two different builds against each other.

Both arms are now checked by md5 before the comparison is believed. This is the second stale-jar
incident in this line of work; the other was a prefilter server that had been carrying an old fix jar
for the whole session.

## Reproducing

```sh
PREFILTER_RADIUS=70 PREFILTER_TERRAIN=4 \
PROBE_JVMFLAGS="-Dprobe.prefilter.witchery=50 -Dprobe.prefilter.witchery.replay=true \
                -Dprobe.prefilter.chunkcache=4096" \
  ./scripts/prefilter.sh @seeds.txt out.jsonl
```

Each `witchery_cells` entry gains `"winner"` and `"chests"`. Compare against
`search.chunks[<cx,cz>].chests` from a `PROBE_CX`/`PROBE_CZ` run centred on the cell.
