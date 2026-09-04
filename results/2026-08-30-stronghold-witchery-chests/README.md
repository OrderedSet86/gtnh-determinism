# Stronghold and Witchery chest tracking in the stage-0 prefilter

GTNH daily-707, repo `d17a685`. Probe jar md5 `9b5bbcd32dbcbb0259cc514db9c35e57`, fix jar md5
`c7ea8104ba08f906904504e58ed81ff2`.

Two structures, two different answers. Strongholds are now predicted exactly. Witchery village pieces
are predicted too, as of `results/2026-08-30-witchery-positional-chests`; Witchery STANDALONE structures are blocked,
and this records what blocks it and how that was measured, so the gap is not re-litigated from
memory.

## Strongholds: exact positions, layouts and contents

`-Dprobe.prefilter.stronghold=N` emits all three strongholds within N chunks of the predicted spawn,
each with its full piece layout and its chest contents.

| check | result |
| --- | --- |
| stronghold chests found, of those the world generates | **26 / 26** |
| predicted contents byte-identical to the world, items **and** NBT | **26 / 26** |
| phantom chests (predicted, absent in world) | **0** |
| piece and loot-category agreement on matched positions | 26 / 26 |
| village chest predictions changed by this work | **0 of 464** |
| rows byte-identical to the 5000-seed corpus | 8 / 8 |

Validated on 3 seeds against full worldgen. Cost: **6.28 ms/seed, 0.3% of wall** in a full pipeline
run (2010 ms/seed with dungeons, villages and chests all on).

### Why this was cheap

`MapGenStronghold.canSpawnStructureAtCoords` derives all three ring positions from
`new Random(worldSeed)` alone, and RWG's `ChunkManagerRealistic.findBiomePosition` returns null, so
the biome-viability relocation never runs and the raw arithmetic positions stand. The positions are
read straight out of `structureCoords` — no cell scan, no terrain, no chunk generated.

**`ranBiomeCheck` must be reset per seed.** It is an instance flag latched the first time the ring
positions are computed. A generator reused across seeds hands every later seed the FIRST seed's
stronghold positions, silently and with no error — the same shape as the village generator's
`structureMap` / `field_143029_e` carry-over. Two related traps cost a build each:

- The SRG names in the deobfuscated source are not the runtime ones. `ranBiomeCheck` is
  `field_75056_f` and `structureCoords` is `field_75057_g`, per `conf/fields.csv`.
- `CAN_SPAWN` and `GENERATE` are resolved against the *village* generator's class, and
  `Method.invoke` rejects a receiver that is not an instance of the declaring class. Strongholds need
  their own handles.

### How the coverage gap was closed

`chest-sites.json` had **zero** stronghold pieces. The trace corpus that built it ran at radius 8-15
around spawn, and the nearest stronghold ring sits 640-1150 blocks out, so it had never seen one.

The enumeration above is what fixed that: it gives an exact stronghold chunk, and `run-probe.sh`
already accepts `PROBE_CX` / `PROBE_CZ`, so a `-Dgtnhdet.chesttrace=true` full-worldgen run can be
centred **on a stronghold** instead of on spawn. One radius-10 run then yielded every site.

The measurement said `src=caller-local` and `countdrawn=true` — component-relative and self-contained,
i.e. predictable from a structure layout alone. That contradicted the standing assumption that
strongholds were as blocked as Witchery, which had been inferred from Witchery's behaviour rather
than measured for strongholds.

Four sites, entered at all four orientations (caller-local locals carry no rotation term, and vanilla
passes them as constants with no mode branch):

| piece | local | loot table | condition |
| --- | --- | --- | --- |
| `ChestCorridor` | 3,2,3 | `strongholdCorridor` (2-4) | none |
| `Library` | 3,3,5 | `strongholdLibrary` (1-5) | none |
| `Library` | 12,8,1 | `strongholdLibrary` (1-5) | `ysize>6` (the tall variant) |
| `RoomCrossing` | 3,4,8 | `strongholdCrossing` (1-5) | `roomType==2` |

The live loot-table ranges match the `tmin`/`tmax` the trace recorded during real generation, which
is the check that a silent range disagreement — which shifts every draw and looks like a fork bug —
is not happening.

### Conditional sites

Two of those four exist only in one variant of their piece. A table entry without the guard emits a
chest that is not there, so `Site.cond` was added: `ysize>N` reads the piece box, `field==N` reads an
int field off the piece instance. Both answer from the piece's construction, before any terrain
exists. **An unrecognised guard suppresses its chest rather than waving it through.**

Both guards discriminate in both directions in the measured data: 2 libraries yield 3 chests (one
tall, one short) and 6 room crossings yield 1, 3 and 2 chests on the three seeds.

## Witchery: blocked, for two independent measured reasons

### Standalone structures — the winner cannot be chosen

`WitcheryPrefilter` already emits candidate cells, the biome-gate verdict and the handler try-order.
It stops there because choosing the winner means calling `IWorldGenHandler.generate`, which reads
terrain **and writes blocks**, and writes trip `SeedProbeWorld`'s guards or corrupt the virgin-terrain
oracle those guards protect. No winner means no structure, no pieces and no chests.

Even given a winner, the loot is out of reach. The trace shows Witchery's own filler is not
component-relative:

```
piece=none src=absolute mode=-1 countdrawn=true min=0,0,0 local=0,0,0 abs=-153,78,-243
cat=mineshaftCorridor rolls=4 size=9 itype=TileEntityDispenser
caller=com.emoniph.witchery.worldgen.WitcheryComponent.setDispenser:100
```

The fork is derived from the **absolute** world position including `y=78`, which is terrain height.
`piece=none` because `setDispenser` runs outside `StructureStart.generateStructure`, so
`StructureStartPartsMixin` never sees it and there is no component frame to be relative to.

### Village pieces — SUPERSEDED: contents are now predicted

**This section is out of date.** Witchery's village chests are predicted exactly as of
`results/2026-08-30-witchery-positional-chests`: F10 redraws their roll count from the chest's own
position fork over the range the mod uses, which makes them computable without replaying the populate
prologue. 10/10 byte-identical. What follows is kept for the reasoning, not the conclusion.

### Village pieces — positions yes, contents no (superseded)

Witchery's village pieces *are* reachable: they come through the village generator, which the
prefilter already enumerates. Their contents still are not — but not for the reason first recorded
here, which was wrong. See `results/2026-08-30-chest-table-leak` for the full retraction.

These pieces do not use `ChestGenHooks` at all. They pass their own compile-time array to
`generateStructureChestContents`, so the loot pool is a constant and is reflectively readable:

```java
this.generateStructureChestContents(world, box, rand, x, y, z,
    villageTowerChestContents,   // static field on the piece class
    3 + rand.nextInt(6));        // count off addComponentParts' own Random
```

**The blocker is the count, and only the count.** It is drawn mid-`addComponentParts` from that
method's own `Random`, and the prefilter never runs `addComponentParts` — that is the terrain-writing
phase `SeedProbeWorld` exists to keep out. Getting the count means replaying every draw the piece
makes before it, i.e. the reimplementation `villageStarts` already records as producing "self-stable
but real-divergent" layouts.

The categories the table records for these pieces are **not real**. `ComponentVillageApothecary` mode
3 appears as both `WG:PHOTOWORKSHOP` and `naturalistChest` because F10 was refilling these chests from
a leaked ThreadLocal loot table whose identity depended on chunk generation order. That is now fixed,
and those chests fall back to their own array. The `countDrawn: false` rows in `chest-sites.json`
therefore carry a **meaningless `category`** and should not be read as naming a loot table.

### What is delivered instead: the position, said out loud

Position and contents fail independently, so collapsing them loses information. Refusing the whole
site under-reports where chests are; emitting a guessed item list is worse. Refused sites now appear
in a separate `chests_unpredicted` field carrying position, piece, category and reason, and never in
`chests`:

```json
{"piece": "com.emoniph.witchery.worldgen.ComponentVillageApothecary",
 "category": "mineshaftCorridor", "pos": [1142, 64, -565],
 "reason": "roll count is not table-drawn"}
```

This surfaced **227 chest positions over 8 seeds** that the prefilter previously located and then
discarded. The refusals were only ever a warning line at the end of a run, so a consumer reading one
seed's row could not tell a refused chest from a chest that does not exist.

**X and Z are exact; Y is nominal, not predicted** — the same caveat the village module carries, since
the fork does not use Y. Checked against full worldgen on 2 village-centred windows: 9/9 refused and
17/17 predicted positions are real chests **in XZ**, and 0/26 match on Y.

Three other reasons use the same path: `loot table not registered in this process`,
`loot table reads 0..0 in this process` (Thaumcraft's `ComponentWizardTower` hits this) and
`no measured inventory size`.

## Still not covered

- **Mineshafts.** 15 `StructureMineshaftPieces$Corridor` sites are in the table already, but the
  prefilter does not enumerate mineshaft starts, so nothing consumes them.
- **Pyramids**, and vanilla `WorldGenDungeons` rooms — 191 of 196 absolute-fork chests in the earlier
  4-seed corpus were dungeon rooms, which have no component at all.
- **LootGames `chest1`-`chest4`** remain non-deterministic at the source: they are minigame rewards
  filled from an unseeded `RandHelper.RAND`. That is a fix-jar gap, not a prefilter gap.

## Reproducing

```sh
# enumerate, and predict contents
PREFILTER_RADIUS=70 PREFILTER_TERRAIN=4 \
PROBE_JVMFLAGS="-Dprobe.prefilter.stronghold=90 -Dprobe.prefilter.chests=true" \
  ./scripts/prefilter.sh @seeds.txt out.jsonl

# ground truth: full worldgen centred on a stronghold the line above located
PROBE_CX=54 PROBE_CZ=-18 PROBE_SEARCH=true PROBE_EXTRA_ARGS="-Dgtnhdet.chesttrace=true" \
  ./scripts/run-probe.sh ~/.cache/gtnh-determinism/daily-707 -7269948338495788698 rows trace.json 10
```

Chests land at `search.chunks[<cx,cz>].chests` in the probe report and compare against
`strongholds[].chests[].chest` in the prefilter row.
