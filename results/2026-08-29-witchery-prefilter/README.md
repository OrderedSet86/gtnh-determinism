# Stage-0 Witchery module: candidate cells, biome gate, handler order

GTNH daily-707, fix jar md5 `b72e810a4b3730c880b9a67ad02bf9e4`, probe md5 `a53af14c6e80ab407ee373097e89cf1b`.

```bash
PREFILTER_SERVER=~/.cache/gtnh-determinism/daily-707 PREFILTER_RADIUS=12 PREFILTER_TERRAIN=4 \
PROBE_JVMFLAGS="-Dprobe.prefilter.witchery=14" ./scripts/prefilter.sh @seeds.txt out.jsonl
```

Emits, per seed, every Witchery candidate structure cell with its biome, the gate verdict, and the
order the handlers will be tried in — without generating a chunk for the decision itself.

```json
{"cell": [176, 160], "cs": 2629741033713258396, "biome": 230, "allowed": true,
 "order": ["WorldHandlerWickerMan", "WorldHandlerClonedStructure", "WorldHandlerShack", "WorldHandlerCoven"]}
```

## Accuracy against the generator's own decisions

Golden-tested against `-Dgtnhdet.witchtrace=true`, 3 seeds, boot world excluded:

| | |
| --- | ---: |
| predicted candidate cells | 9 (8 in the trace window, 1 beyond it) |
| **biome id correct** | **8 of 8** |
| **biome gate verdict correct** | **8 of 8** |
| **handler try-order correct** | **6 of 6** |
| **real placements covered** | **6 of 6** |

Every structure Witchery actually placed is among the predicted candidates, and every predicted cell's
biome and try-order matches what the generator computed.

## Why it is worldless

Each input was established by disassembling `witchery-1.7.10-0.24.1`, not assumed:

- **Candidate cell.** `nonInRange` is the vanilla scattered-feature region formula: reduce to a region
  of `field_82665_g` chunks, seed a `Random` from `World.setRandomSeed(regionX, regionZ, 10387312)`,
  accept only the chunk that region picked. It reads no blocks. Its `range` parameter is **never used**
  — all four handlers share one region grid.
- **Handler order.** With F2 in place the mixin shuffles a name-sorted *copy* with FML's per-chunk
  `Random`, and that shuffle is the first draw taken from it, so the order is
  `shuffle(sorted, new Random(chunkSeed(seed, cx, cz)))` — the same FML chunk seed the Roguelike module
  uses.
- **Biome gate.** `BiomeManager.DISALLOWED_BIOMES` against the biome at `(x + midX, z + midZ)`.

The region maximum, minimum, `midX`/`midZ`, the handler list and the disallowed-biome list are all read
reflectively from the live generator rather than hardcoded, so a config or pack change moves the module
with it.

## Two bugs the golden test caught

**The biome must come from the chunk, not the chunk manager.** `World.getBiomeGenForCoords` delegates
to `getBiomeGenForCoordsBody`, which for an existing chunk reads
`Chunk.getBiomeGenForWorldCoords` — the chunk's *stored* biome array — and only falls back to the chunk
manager otherwise. On RWG the two disagree substantially: asking the chunk manager returned 87 and 70
where the real run saw 211 and 230, which was enough to flip a gate verdict. The module now asks the
chunk, which `VirginChunkProvider` supplies. Biome accuracy went 2/4 → 8/8.

**The trace corpus was mixing two worlds.** The module predicted a different handler order than the
trace, while an independent Python emulation of Java's `Random` and `Collections.shuffle` agreed with
the *module*. Printing the chunk seed on both sides showed them disagreeing about `world.getSeed()` —
which is only possible if two different worlds are in play. A warm-probe run generates the server's own
boot world before the requested seed's, and Witchery generates in both. See the
[correction](../2026-08-29-witchery-placement-trace/README.md). Order accuracy went 3/6 → 6/6 once the
corpus was filtered, with no change to the module.

Neither bug would have shown up without checking each predicted field against the generator's own
recorded decision. A module that only emitted candidate cells would have looked perfect throughout.

## What it deliberately does not predict

**The winner.** Choosing it means calling `IWorldGenHandler.generate`, which reads terrain *and writes
blocks* — writes would trip `SeedProbeWorld`'s guards or corrupt the virgin-terrain oracle those guards
protect. So the module stops at the try-order. In practice the winner is usually the first handler that
is in range, and the module already tells you which handler that is and where.

Coverage is a candidate-cell superset: 9 predicted cells for 6 actual placements, because a cell can
pass the region and biome gates and still have every handler decline. As a search feature that is the
right direction — it never misses a real structure.

## Next

- Widen beyond 3 seeds.
- Use it: distance-to-nearest-coven and distance-to-nearest-shack are now stage-0 computable, which is
  what the Witchery coven dispenser predicate in `seedsearch/README.md` wanted.
