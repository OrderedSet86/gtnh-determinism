# Stage 2: Roguelike dungeon loot in the stage-0 prefilter

GTNH daily-707, `~/.cache/gtnh-determinism/daily-707`, fix jar
`gtnhdeterminism-v0.5-main.25+884548e365-dirty` (md5 `1b514736ae723c922603f3f384fcebea`), probe
`worldgenprobe-v0.5-main.25+884548e365-dirty` (md5 `99f70d776f9f7d416c6783d84057e4fa`).

```bash
PREFILTER_SERVER=~/.cache/gtnh-determinism/daily-707 \
PREFILTER_RADIUS=16 PREFILTER_TERRAIN=4 \
PROBE_JVMFLAGS="-Dprobe.prefilter.dungeon=10" \
  ./scripts/prefilter.sh -777 out.jsonl
```

## What it does

`RoguelikePrefilter` builds the real `greymerk.roguelike.worldgen.WorldEditor` and
`greymerk.roguelike.dungeon.Dungeon` against `Prefilter.SeedProbeWorld` and calls `spawnInChunk`,
then reads `Dungeon.getChests()`. Nothing is reimplemented — the mod's own `IWorldGenerator` body is
exactly `new WorldEditor(world); new Dungeon(editor); dungeon.spawnInChunk(rand, cx, cz);`, and this
runs the same three lines.

The trigger `Random` needed no registry introspection after all. `GameRegistry.generateWorld` calls
`fmlRandom.setSeed(chunkSeed)` before **each** generator, so what Roguelike receives is
`new Random(chunkSeed)` regardless of how many generators ran first or what they drew — a pure
function of `(worldSeed, cx, cz)`. The earlier plan's concern about sorted-generator order was
unfounded; the only subtlety is reproducing Forge's own `>> 2 + 1L` precedence quirk, which parses as
`>> 3`.

## Accuracy: exact

Seed `-777`, prefilter dungeon scan radius 10, compared against a full-generation probe run at radius
15 on the same pack and the same jars:

| | |
| --- | ---: |
| chests predicted | 108 |
| in chunks the full-gen run covers | 108 |
| **position matched** | **108** |
| position predicted-but-absent | **0** |
| **contents identical** (slot, id, damage, count) | **108** |
| contents differing | **0** |
| NBT-only differing | **0** |

Every chest of the dungeon, at the right block, with the right items in the right slots and the right
tags, without generating a world.

**The first comparison said 85 matched / 9 predicted-but-absent, and that was an artifact of my test,
not a finding.** I had compared against a radius-8 run whose `search.chunks` covers 649 of the 756
chunks in its own bounding box, so nine predicted chests sat in chunks the report simply does not
describe — three chunks, each reporting zero chests. It would have been easy to write that up as the
known route-dependent deep-chest loss. Re-running the full-gen side at radius 15 so the dungeon is
fully inside the window resolved it to 0.

56 chests in the window are not claimed by this module: 13 are hoppers, furnaces and dispensers, and
43 are chests belonging to villages, mineshafts and vanilla dungeons. That is by design — the module
emits Roguelike chests only. Widening the trigger scan to radius 23 finds no second dungeon, which is
consistent with `spawnFrequency=16` giving one trigger per 51×51 chunks.

## Determinism and robustness

- Repeat run of the same seed: **byte-identical** JSONL.
- 60-seed sweep: 7 seeds (12%) have a trigger within radius 10 — between the 7.2% measured at radius
  8 and 18.2% at radius 12. 7 dungeons generated, **0 errors**, 105–124 chests each (median 112).
- 3000-seed gated sweep: 73 dungeons, **0 errors**, 7791 chests predicted.
- Across all of it: no write-guard trip, no re-entrancy trip, and `chunks_regenerated` is **0**
  everywhere — the 256-chunk LRU never thrashed, even on the seed that generated 542.

## Cost

3000 seeds, village radius 64, terrain radius 4, gates `villagedist=12` + pieces + `water=32`
(the 650k sweep's configuration), with and without the dungeon stage:

| | ms/seed |
| --- | ---: |
| without the dungeon stage | 84.8 |
| with the dungeon stage | 89.5 |
| **attributable to dungeons** | **+4.7 (5.5%)** |

**193 ms per dungeon**, against the plan's 0.4–1.3 s estimate. 73 dungeons over 510 gate survivors.

**On the 100× floor, honestly: this configuration does not meet it, and the dungeon module is not
why.** Against a 7.7 s/seed warm probe the sweep is 86× with dungeons and 91× without, so the
baseline is already under the floor before this module is added. Two reasons, both worth stating
rather than averaging away:

- The 290× figure this project has quoted was measured on the **2.8.4** template. This is daily-707,
  where the village gate kills 69% of seeds rather than 88%, so 17% of seeds reach the terrain stage
  instead of ~6%. Terrain, not dungeons, is what those survivors pay for.
- The 7.7 s/seed probe baseline is itself a 2.8.4 radius-8 measurement. Comparing a daily-707 sweep
  against it is apples-to-oranges; a like-for-like probe baseline on daily-707 has not been measured.

The actionable version: the dungeon stage costs 5.5%, and if the funnel needs to be faster the lever
is the terrain stage's 84.8 ms, not this.

## Known divergence, not yet measured

In the prefilter every chest tile entity is detached, so the fix jar's `TreasureChest.gtnhdet$isLive()`
takes its `world == null → true` branch and no chest is ever treated as carved over. In a full run a
chest written live into an already-applied chunk can be carved over, at which point
`TreasureManagerMixin` skips it and drops its items. This seed shows no sign of it — 108/108 exact —
but that is one seed, and the mechanism is real. Quantifying it needs the per-dungeon
`isLive()==false` count from a full run, which is what the planned `-Dprobe.roguetrace` instrumentation
is for.

## Follow-ups

- Emit the chests as `search.chunks["cx,cz"].chests[]` in a `seed-<N>.json` so `ingot-hunt.py`,
  `coke-stage1.py` and `searchlib.py` run on prefilter output unchanged. The per-chest objects already
  use the probe's own `dumpInventory`, so only the grouping differs.
- Multi-seed accuracy: one seed is proof of mechanism, not of coverage. The 36 trigger-bearing seeds
  in `results/2026-07-24-seedlib-2.8.4-pool-500` are the natural corpus, on the 2.8.4 template.
- A dungeon-loot gate (`--require` on actual items) is now possible at stage 0, which is what turns
  this from a measurement into search throughput.
