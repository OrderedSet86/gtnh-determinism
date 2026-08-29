# 2026-08-27 — Roguelike chest position sets made order-independent

Fixes the second order-dependence bug identified on 2026-08-04: a dungeon emitted 105 chests under a
`rows` walk and 111 under `spiral`, with only 65-82 of ~200 chest positions shared between any two
orders. Chest emissions are now identical in all three orders.

Seed `-1501259159663517643`, GTNH 2.8.4 server pack, radius 15, `search=true`.
Jars: `gtnhdeterminism-v0.5-main.16+9379a10ded-dirty` md5 `43a904e3c649f3afdceedcbb466c686e`
(copy in this directory), `worldgenprobe-v0.5-main.16+9379a10ded-dirty` md5
`427ccab66296467c1381d9813174e2ad`. Base commit `9379a10`.

## Root cause

`InventoryMixin` forked the chest slot shuffle to a position-seeded `Random` only when the chest tile
entity had a world. Buffered chest writes get a DETACHED tile entity from `PendingSlices`, whose
`getWorldObj()` is null, so those chests fell back to the shared room rand and spent 26 draws on it
while attached chests spent none.

Attachment is decided by `PendingSlices.shouldBuffer`, which answers on chunk-applier state, i.e. on
chunk generation order. So the shared room stream desynchronised at the first chest whose attachment
differed, and every later room, segment and chest decision in that dungeon moved with it.

The 2026-08-04 traces already carried the signature: in both dungeons, independently, chest positions
began diverging at exactly the emission after the first `detached` flip.

Stock Roguelike always spent those 26 draws. The mixin made the count conditional on an
order-dependent predicate, and the atomic dungeon window made that predicate fire more often.

## Changes

1. `InventoryMixin` — the position fork is now unconditional. When the tile entity has no world the
   seed comes from `PendingSlices.worldSeed()`, recorded in `tileEntityFor` (every detached tile
   entity passes through it, and 1.7.10 reports one level seed for all dimensions).
2. `WorldEditorMixin` — `gtnhdet$written` changed from `Set<Long>` to `Map<Long, Block>`, recording
   the block written rather than re-reading the live world. A live write and a buffered write now
   answer reads identically. Previously the live branch returned `world.getBlock`, which also reports
   anything a later writer put on top, so `Treasure.isValidChestSpace` could differ by route.

## Results

Chest emissions from the traces (ground truth for what the dungeon built, independent of the probe's
observation window):

| arm | chest-generate lines | placed=false |
|---|---|---|
| rows | 187 | 0 |
| cols | 187 | 0 |
| spiral | 187 | 0 |

Position sets, `detached` field stripped: **0 differing lines of 187 for every pair.** Was 105 vs 111
and 113 vs 114 before.

Chest contents from the probe reports, `cmpchests.py` slot-sensitive key `(slot, id, damage, count)`:

| pair | only A | only B | shared | differing |
|---|---|---|---|---|
| rows vs cols | 0 | 23 | 174 | **0** |
| rows vs spiral | 0 | 2 | 174 | **0** |
| cols vs spiral | 23 | 2 | 174 | **0** |
| rows vs rows2 (warm vs cold, control) | 0 | 0 | 174 | **0** |
| rows2 vs rows3 (cold vs cold, control) | 0 | 0 | 174 | **0** |

No shared chest ever differs in contents, including slot indices. The undocumented slot residual the
2026-08-03 and 2026-08-04 READMEs did not report (3-8 chests per pair holding the same items at
different slots) is also gone.

### The "only in B" chests are not generation differences

Restricted to chunks present in both reports, the 23 shrink to 5 and the 2 stay 2. All seven were
checked against the traces:

- `-380,31,89`, `-365,50,131`, `-358,10,141`, `-358,41,133`, `-354,33,131` appear in **all three**
  traces, so they were generated in every order. They appear only in `cols`' report. This is the
  documented probe artifact: the probe records a chunk at walk time, and a cross-chunk dungeon write
  can land after that. See HANDOFF "Probe hashes chunks at walk time".
- `-337,30,-256`, `-334,30,-259` appear in **no** trace, so they are not Roguelike chests. Only in
  `spiral`'s report. **Settled the same day by persisted-world diff: these are real.** They are the
  two chests of an entire vanilla cave dungeon (Skeleton `MobSpawner` at `(-337,30,-258)`) that
  generates under `spiral` and not under `rows`. See the section below and the correction appended
  to `results/2026-08-04-atomic-dungeon-window/README.md`.

Persisted-world diffs remain the only ground truth for multi-chunk structures. This run did not do
one.

## Vanilla cave dungeons ARE order-dependent (new finding)

Persisted-world diff, two COLD runs, `scripts/diff-region-tes.py` reader over every region file:

```
persisted CHEST TEs: rows=158 spiral=160   only-rows=0  only-spiral=2  changed=0
   only-spiral (-337, 30, -256) Chest
   only-spiral (-334, 30, -259) Chest
persisted MobSpawner: rows=168 spiral=169  only-spiral=1  only-rows=0
   only-spiral (-337, 30, -258) {EntityId:Skeleton, Delay:20, MaxNearbyEntities:6, ...}
```

That NBT is `TileEntityMobSpawner` defaults, i.e. vanilla `WorldGenDungeons`. One complete cave
dungeon — spawner plus both chests — exists under `spiral` and not under `rows`, `cols`, or two
repeat cold `rows` runs. `changed=0` across all 158 shared chests independently confirms the
Roguelike fix at the persisted level.

### It is NOT a standalone WorldGenDungeons bug

The first hypothesis was that vanilla `WorldGenDungeons.generate` flips because its placement scan
crosses chunk edges and reads neighbour population state. RWG does call it 8× per chunk from
`ChunkGeneratorRealistic.populate:519-525`, and the scan box (`x±(i+1), y-1..y+4, z±(j+1)`, `i,j ∈
{2,3}`) does cross into chunks `(-21,-17)`, `(-22,-16)` and `(-21,-16)` from this dungeon's position.
But the block and tile-entity evidence says that is not the isolated cause.

Block diff of the dungeon's own populate chunk `(-22,-17)`: **1014 differing blocks**, of which only
about 10 are the dungeon's cobble and mossy cobble. The rest are GT ores, dirt and gravel veins, and
**surface vegetation at y=66-68**. Widening to a 9×9-chunk window, `(-22,-17)` is not even the worst
chunk — `(-19,-16)` differs by 4119 blocks. Whole-chunk population diverges, far beyond anything a
dungeon could explain.

Full tile-entity breakdown of the two cold worlds:

| TE type | only-rows | only-spiral | changed |
|---|---|---|---|
| `GT_TileEntity_Ores` | 4351 | 7593 | 1026 |
| `etfuturum.cave_vines` | 4 | 6 | 102 |
| `etfuturum.glow_lichen` | 32 | 22 | 1 |
| `TileExtendedNode` (TC nodes) | 4 | 3 | 1 |
| projectred lily | 1 | 1 | 0 |
| `Chest` | 0 | 2 | 0 |
| `MobSpawner` | 0 | 1 | 0 |
| **total** | **4392** | **7628** | **1130** |

**GregTech ore generation is 12,970 of the 13,150 differing tile entities — 98.6%.** The vanilla
dungeon is 3 TEs. Thaumcraft nodes move slightly, which is the documented "TC nodes cannot
prefilter" class. **No Roguelike tile entity differs at all**: all 158 shared chests are identical,
and Roguelike spawners are identical too (168 vs 169, the single extra being this vanilla dungeon's,
against 133 vs 156 spawners in the pre-fix field report).

So the causal order is almost certainly the reverse of the first guess. GT ore worldgen is
order-dependent — the known vein terrain-reroll gate that reads the world — and ore placement
changes block solidity, which is exactly what the vanilla dungeon scan and `WorldGenLakes` read.
Vanilla `WorldGenLakes.generate` also `return false`s on a world read *before* consuming any draws,
so an upstream flip there shifts the populate rand and moves the 8 dungeon attempt positions
outright. Either path makes the dungeon a downstream symptom.

NOT FIXED, and deliberately so: mixing `WorldGenDungeons` onto `TerrainOracle` would very likely not
have changed this outcome, because the divergence its scan observes is already present in the
neighbour chunks. The lead to pursue is **GT ore worldgen**, which is also the obvious candidate for
the unattributed `gravel`/`surf`/`sand` terrain movement. Note also that every mixin in `fix-build`
is `remap = false` and targets a mod class; there is no precedent here for mixing an obfuscated
vanilla class, so a `WorldGenDungeons` patch carries extra build risk on top.

## Warm reuse moves terrain but not chests

Terrain fields, chunks differing of 1110 common:

| pair | diffs |
|---|---|
| rows2 vs rows3 — **cold vs cold, same order** | `gravel=1` |
| rows vs rows2 — **warm vs cold, same order** | `gravel=60, water=2, ores=268, stainedclay=7` |
| rows vs cols | `surf=30, sand=2, gravel=82, water=45, ores=97, stainedclay=1, populated=3` |
| rows vs spiral | `surf=130, sand=7, gravel=277, clay=2, water=152, ores=329, stainedclay=1` |
| cols vs spiral | `surf=127, sand=9, gravel=279, clay=2, water=149, ores=330, stainedclay=2, populated=3` |

Two things follow.

**Chests are immune to warm reuse; terrain is not.** Every same-order pair is 0 chest diffs, warm
against cold included. But a warm daemon moves 268 of 1102 ore chunks and 60 gravel chunks relative
to a cold boot, against a true cold-vs-cold floor of `gravel=1`. The 2026-08-03 README's control
(`gravel=3, water=2, ores=1`) was CRIU-restore against cold, which is evidently much closer to cold
than a daemon that has already run other worlds. **The terrain-order bisect must use cold runs.**

**The terrain order-dependence is untouched by this fix**, as expected — it is the separate
unattributed finding. `rows vs spiral` is `surf=130, gravel=277, sand=7` here against `surf=127,
gravel=317, sand=7` on 2026-08-03. Note these three arms ran as sequential jobs in one warm daemon,
so they carry the daemon-state effect above and are not a clean order measurement.

## Reproduce

```sh
export PROBE_JAVA=~/.gradle/jdks/azul_systems__inc_-17-amd64-linux.2/bin/java
scripts/build-jar.sh fix   --deploy <server-dir>
scripts/build-jar.sh probe --deploy <server-dir>

# three order arms in one traced daemon; SliceTrace.ON is static final, so a traced
# and an untraced arm cannot share a daemon
PROBE_JVMFLAGS="-Dgtnhdet.traceslices=true" PROBE_PORT=25581 \
  scripts/probe-queue.sh start <server-dir> <ctl-dir>
for o in rows cols spiral; do
  scripts/probe-queue.sh submit <ctl-dir> -1501259159663517643 out-$o.json \
    order=$o radius=15 search=true
  scripts/probe-queue.sh wait <ctl-dir> all
done
scripts/probe-queue.sh stop <ctl-dir>

# controls, fresh JVMs
PROBE_SEARCH=true PROBE_PORT=25582 scripts/run-probe.sh <server-dir> -1501259159663517643 rows out-rows2.json 15
PROBE_SEARCH=true PROBE_PORT=25583 scripts/run-probe.sh <server-dir> -1501259159663517643 rows out-rows3.json 15

python3 results/2026-08-03-order-dependence/cmpchests.py out-rows.json out-spiral.json
```

Trace comparison, stripping the `detached` field, which still flips by design — it reports real
applier state and no longer feeds any RNG:

```sh
grep -o 'chest-generate pos=[^ ]* level=[0-9]*' trace-rows.txt | sort > a
grep -o 'chest-generate pos=[^ ]* level=[0-9]*' trace-spiral.txt | sort > b
diff a b
```
