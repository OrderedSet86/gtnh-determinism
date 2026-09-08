# Roguelike dungeon PLACEMENT is route-dependent — the escape is an un-virginised BIOME read

> **ROOT CAUSE FOUND.** `DungeonMixin`'s `@Overwrite validLocation` virginised every BLOCK read
> through `TerrainOracle` but left its **first** statement — the biome read — live:
>
> ```java
> final BiomeGenBase biome = this.editor.getBiome(new Coord(x, 0, z));   // LIVE
> ```
>
> `WorldEditor.getBiome` is `world.getBiomeGenForCoords(x, z)`, and vanilla
> `World.getBiomeGenForCoordsBody` branches on whether the chunk is **loaded**:
>
> ```java
> if (this.blockExists(x, 0, z))                     // chunk loaded?
>     return chunk.getBiomeGenForWorldCoords(...);   // the chunk's STORED biome array
> // else -> worldChunkMgr.getBiomeGenAt(x, z)       // the raw provider, seed-pure
> ```
>
> RWG writes river carving into the stored array at generation, so the two answers differ.
> `WorldEditorMixin` overwrites `getBlock`, `isAirBlock`, `setBlock`, `setBlockMetadata` and
> `getTileEntity` — **not `getBiome`**.
>
> Confirmed at the exact divergent coordinate: the stored biome at (-623,749) is **"Hot River"
> (id 207, type RIVER)**, which `validLocation` rejects. Chunk loaded &rarr; reject; chunk not loaded
> &rarr; the provider says non-river &rarr; accept. That one flip moves the whole dungeon.
>
> **The 40-100 block trigger offset is NOT the problem and is already seed-deterministic** — both
> arms rolled an identical location sequence, so `getNearbyCoord` and the `Random` reaching
> `spawnInChunk` are pure. Only the gate was impure.

## FIX IMPLEMENTED AND VERIFIED — the defect is closed

`WorldEditorMixin` gained a sixth `@Overwrite`:

```java
public BiomeGenBase getBiome(Coord pos) {
    return world.provider.worldChunkMgr.getBiomeGenAt(pos.getX(), pos.getZ());
}
```

Jar `gtnhdeterminism-v0.8-main.3+5f5f73299a-dirty` md5 `170aad0a866639ab30ddfbb9a8141658`.

| check | before | after |
| --- | --- | --- |
| `generateNear` attempt trace, rows vs spiral | one verdict flipped at attempt 2 | **identical — same attempts, same verdicts** |
| trigger-centred r24, rows vs spiral, existence diffs | 281 | **14**, and all are 1-3 chest clusters, i.e. vanilla dungeons |
| oracle, trigger fires FIRST | 114 / 114 | 114 / 114 |
| oracle, trigger fires MIDWAY | **0 / 114** | **114 / 114** |
| August seed `-1501259159663517643`, r15, rows/cols/spiral | 23 and 2 existence diffs | **ALL SEEDS IDENTICAL** |

The August seed going fully clean is the strongest confirmation: that comparison had been failing
for a reason nobody had named, and the same one-line read fixes it.

### The apparent "write race" residual was a measurement artifact — RETRACTED

An earlier revision of this page reported that a dungeon built into an already-populated
neighbourhood keeps only 40 of 114 chests, and attributed it to `PendingSlices.shouldBuffer`'s live
branch. **That was wrong and is retracted.**

The trigger-LAST arm was centred at chunk (-66,28) r24, i.e. a block box of x[-1440..-657]. The
dungeon spans x[-711..-579]. **Only 40 of the 114 oracle chests were inside the walked box at all.**
The arm found 40 — every one that was reachable. The 74 "missing" positions hold untouched
`minecraft:stone`/`dirt`, and one chunk is absent entirely, because the probe never walked there.
Nothing was written and destroyed; nothing was written.

Re-run with a box that actually covers the dungeon while still firing the trigger last — centre
(-61,28) r24, block box x[-1360..-577], which contains 114/114 oracle chests, with the trigger chunk
(-42,52) in the final row of a rows sweep:

| trigger fires | order | oracle chests found |
| --- | --- | ---: |
| last | rows | **114 / 114** |
| last | spiral | **114 / 114** |

So there is no write race left to fix. `PendingSlices` and the atomic window are working.

This is the second time in this investigation that a too-small measurement window manufactured a
defect — the first was the "frontier artifact" hypothesis on the original r10 run. The mod's own
`results/2026-08-29-roguelike-prefilter` README already warned that an r8 window truncates a dungeon
and manufactures false "predicted but absent". **Before believing any dungeon count, check how many
oracle chests the walked box can physically contain.**

---

**Original finding, before the fix. Outcome: a dungeon's construction origin depends on how much of
its neighbourhood is already populated when its trigger chunk fires. Only the empty-neighbourhood build reproduces the seed-pure
oracle — 114/114 chests exact. With the trigger firing midway through a walk, the oracle's dungeon
is 0/114 present. This is decided inside `Dungeon.generateNear` BEFORE any block is written, so the
`PendingSlices` write path is exonerated. Root cause is the un-virginised biome read above.**

Seed `-1636594104014467454`, GTNH daily-707, jar `gtnhdeterminism-v0.8-main.3+5f5f73299a-dirty`
md5 `ac43c79e98b4b37e186955fb3283d652`, probe `worldgenprobe-v0.8-main.2+4c6e016626`, dedicated
server, `PROBE_SEARCH=true`. Metric = `search.chunks[*].chests`, compared with
`scripts/diff-chests.py`.

## The escape, in one line from the trace

`-Dgtnhdet.traceslices=true`, same seed, same trigger chunk, only the walk order differs:

```
rows  : [slicetrace] dungeon BEGIN at -622,835
spiral: [slicetrace] dungeon BEGIN at -623,749
```

`Dungeon.generate` is *entered* at two positions 86 blocks apart. Everything downstream — level
stack, room graph, loot rand — is derived from that origin (`Dungeon.getRandom` is
`editor.getSeed() * x * z`), so a different origin means a completely different dungeon, which is
why 0 of the oracle's 114 chests survive rather than some fraction.

**The write path is not the cause.** The divergence exists before the first `setBlock`. Both arms
show identical window bookkeeping: 2 `dungeon BEGIN`, 2 OPEN / 2 CLOSE, 3 `apply DEFERRED`.

## Against the seed-pure oracle

`RoguelikePrefilter` replays the mod's own generation against a virgin world (all writes buffered,
nothing pre-existing) and is verified 108/108 against full-gen. Its output for this dungeon is the
`loot-1636594104014467454.csv` rows with `structure_tp = /tp -664 100 840`: **114 chests**, span
x[-711..-579] z[701..837].

| arm | when the trigger fires | oracle chests found | missing | extra in span |
| --- | --- | ---: | ---: | ---: |
| spiral, centre ON trigger | first — empty neighbourhood | **114 / 114** | **0** | 37 |
| rows, centre ON trigger | midway | **0 / 114** | 114 | 40 |
| rows, centre OFF | last — box fully populated | 40 / 114 † | 74 † | 5 |
| spiral, centre OFF | last — box fully populated | 40 / 114 † | 74 † | 5 |

† **Do not read these two rows as loss.** That centre's walked box contains only 40 of the 114
oracle chests, so 40 was everything reachable — see the retraction above. They are kept here because
they are what the pre-fix run produced, not because they measure anything.

"Extra" is chests inside the oracle's bounding box belonging to neighbouring structures, expected in
all arms. Only the empty-neighbourhood build is correct.

## It is not a frontier artifact — radius is irrelevant

Trigger-centred (`PROBE_CX=-42 PROBE_CZ=52`), rows vs spiral:

| radius | rows | spiral | existence diffs | contents diffs | NBT diffs |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 10 | 273 | 298 | 269 | **0** | **0** |
| 16 | 298 | 322 | 270 | **0** | **0** |
| 24 | 350 | 375 | 281 | **0** | **0** |

The diff does not shrink as the walked box grows past the dungeon's span, and r10 vs r24 with the
same order and centre are **identical** (diff = 0 on the dungeon). This kills the hypothesis that my
2026-09-07 measurement was an artifact of the dungeon straddling the walk frontier.

**Contents are identical for every chest present in both arms, at every radius.** The loot pipeline
(`TreasureManagerMixin`, `InventoryMixin`, `TreasureChestMixin`) is doing its job. The defect is
purely which chests exist.

## The variable is neighbourhood state, not walk order

Same walk order (`rows`), only the walk centre moved so the trigger fires at a different point:

| arm | dungeon chests in x[-711..-527] z[695..873] |
| --- | ---: |
| rows, centre ON trigger | 125 |
| spiral, centre ON trigger | 152 |
| rows, centre OFF | 47 |
| spiral, centre OFF | 47 |

Two `rows` walks differing only in centre disagree (41 differing chests restricted to the boxes'
overlap). Two *different* orders with the trigger off-centre agree exactly on every roguelike chest —
their 10 differences are all isolated 1-2 chest clusters at y5-31, i.e. vanilla dungeons, a separate
known issue.

So "rows vs spiral" was never the real variable. The variable is **how much of the dungeon's
surroundings already exists at the moment its trigger chunk populates**, and walk order only changes
that incidentally.

## Also present on the August seed, but this is NOT a clean regression gate

`results/2026-08-27-inventory-fork-unconditional` reported 187 chest emissions across rows/cols/spiral
with 0 position and 0 content diffs, on seed `-1501259159663517643`. Re-run on the current jar at r15
spawn-centred:

| comparison | chests | existence diffs | contents diffs |
| --- | --- | ---: | ---: |
| rows vs cols | 175 / 198 | 23 | 0 |
| rows vs spiral | 175 / 177 | 2 | 0 |

**Do not read this as a proven regression.** August ran on the **GTNH 2.8.4** server pack; this ran
on **daily-707**. Worldgen differs between pack versions, so the absolute counts are not comparable
and a genuine pack difference could produce these numbers. What it does show is that route dependence
is present on that seed under daily-707, and again with contents diffs at 0. Settling
regression-vs-pack-difference needs the 2.8.4 pack, which was not run.

## The attempt trace that settled it

`-Dgtnhdet.tracegennear=true` (`DungeonAttemptTraceMixin`, diagnostic-only, registered only under
its flag) logs every attempt of the 50-attempt loop. Both arms, same seed, same trigger:

```
trigger=-668,836 attempt=0 loc=-668,891   rows: false   spiral: false
trigger=-668,836 attempt=1 loc=-726,871   rows: false   spiral: false
trigger=-668,836 attempt=2 loc=-623,749   rows: FALSE   spiral: TRUE     <-- the only divergence
trigger=-668,836 attempt=3 loc=-621,829   rows: false   (spiral already returned)
trigger=-668,836 attempt=4 loc=-622,835   rows: TRUE
```

The whole diff between the two arms is that one verdict. It discriminates the three candidates
cleanly:

- **Locations are identical** at every attempt &rarr; `getNearbyCoord` and the `Random` reaching
  `spawnInChunk` are pure. Hypothesis 3 is dead, and so is any concern about the 40-100 block
  offset being non-deterministic.
- **A verdict differs for the same coordinate** &rarr; `validLocation` is impure. Hypothesis 1.
- `settingsResolver.getSettings` is never reached differently — rows simply never gets to the
  location spiral chose. Hypothesis 2 untested but not implicated.

## The fix, and it is small

Make the biome read seed-pure by asking the biome provider directly instead of the world:

```java
world.provider.worldChunkMgr.getBiomeGenAt(x, z)     // pure function of the seed
```

That is exactly the branch vanilla takes when the chunk is NOT loaded, which is:

- what the **prefilter oracle** does (its virgin world never has a loaded chunk), so the fix aligns
  full-gen with the oracle by construction;
- what the **spiral / trigger-fires-first** arm did — the arm that scored 114/114 against the oracle;
- therefore what the **400k seed search** already assumed.

So the preferred spiral-equivalent behaviour is not just feasible, it is the *simpler* of the two
answers — no redesign, no slicing change, no tick deferral. The cleanest placement is on
`WorldEditorMixin` as a sixth `@Overwrite` of `getBiome`, which fixes it for every caller rather than
only for `validLocation`; `DungeonMixin.validLocation` then needs no change at all.

Two things to check when implementing, neither of which blocks it:

1. **Other `getBiome` callers.** Roguelike uses the biome elsewhere (theme/settings selection). Making
   it provider-pure changes those too — in the same direction, toward the oracle, but it should be
   measured rather than assumed.
2. **Does the stored array ever differ from the provider for a non-river reason?** Only rivers were
   observed here. If RWG rewrites biomes more broadly, the provider answer is still the right
   definition, but the blast radius on existing predictions is larger.

## Where to look next

The escape is inside `Dungeon.generateNear` (`all-gtnh/Roguelike-Dungeons/.../dungeon/Dungeon.java:51`):

```java
for (int i = 0; i < 50; i++) {
    Coord location = getNearbyCoord(rand, x, z, 40, 100);   // consumes rand
    if (!validLocation(rand, location.getX(), location.getZ())) continue;
    ISettings setting = settingsResolver.getSettings(editor, rand, location);
    generate(setting, location.getX(), location.getZ());
    return;
}
```

**ANSWERED** by the attempt trace above: it is candidate 1, and specifically the biome read rather
than any `TerrainOracle` block read. `OracleRngGuard` was ON in every arm and is not implicated —
the block probes were already pure. Candidate 3 is disproved outright (identical location
sequences). Candidate 2 is untested but not implicated.

## Reproducing

```
SEED=-1636594104014467454
# trigger chunk of the dungeon under test: block (-664,840) -> chunk (-42,52)
PROBE_SEARCH=true PROBE_CX=-42 PROBE_CZ=52 \
  scripts/run-probe.sh <server-dir> $SEED rows|spiral <dir>/seed-$SEED.json <radius>
python3 scripts/diff-chests.py <dirA> <dirB>

# escape trace
PROBE_SEARCH=true PROBE_CX=-42 PROBE_CZ=52 PROBE_EXTRA_ARGS="-Dgtnhdet.traceslices=true" \
  scripts/run-probe.sh <server-dir> $SEED rows|spiral /tmp/e4/<order>.json 10
grep "dungeon BEGIN" <log>
```

**Trap:** `diff-chests.py` globs `*.json` and crashes with `AttributeError: 'list' object has no
attribute 'get'` on the `*.veincache.json` sidecar the probe writes beside every report — the same
trap `docs/HANDOFF.md` records for `balance-report.py`. Move sidecars out of the arm directory
first. Committed dirs here have them under `_sidecar/` / `_sc/`.

Committed: `e1-radius-sweep/` (6 arms), `e1b-centre-control/` (3 arms), `e2-august-seed/` (3 arms) —
each as `*.chests.json` (seed, order, radius, centre, chest count and sorted positions) plus the
stamped provenance; and `e4-trace/` (both slice traces). Full probe reports were dropped for size;
regenerate with the commands above.
