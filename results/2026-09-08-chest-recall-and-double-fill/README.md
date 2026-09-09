# Chest recall on beta-3, Thaumcraft hilltop circles, and the F10 double-fill collapse

Seed `-1636594104014467454`, GTNH 2.9.0-beta-3 (`~/.cache/gtnh-determinism/beta3`), jars
`gtnhdeterminism-v0.8-main.3+5f5f73299a-dirty` / `worldgenprobe-v0.8-main.3+5f5f73299a-dirty`.
All runs cold, `level-type=rwg`, centre chunk (9,9) unless stated.

Prompted by a report that Thaumcraft circles (which carry chest loot) were non-deterministic, plus a
suspicion that village loot prediction was wrong. Both turned out to point at real defects, but
neither is the defect that was reported.

## Summary

1. **Hilltop circle placement was route-dependent, and is now fixed.** `WorldGenHilltopStones` gated
   on five live-terrain probes plus a live anchor. Stock: 12 circles walking `rows`, 14 walking
   `spiral`. Fixed: **20 and 20, identical positions.** Placement is now a pure function of the seed.
   Verified at 24 seeds. Circle count reads 22 -> 29, but that is not resolved at this sample size
   (CI -13..+69%); the mechanism (live probes at chunk borders reading air from ungenerated
   neighbours) predicts a rise, and the data neither confirms nor refutes it.
2. **Stage 0 predicts nothing for them.** `chest-sites.json` has two Thaumcraft classes, both village
   components. Hilltop circles and barrows are unpredicted; the block fingerprint here is exact.
3. **F10 collapses multi-filled chests.** 23 of 829 traced fills at radius 60 land twice or more in
   one inventory; two land four times. Every repeat re-rolls from the same position fork after
   `clear(inv)`, so all but one batch is discarded. 12 Thaumcraft sites, 11 Village Names sites — a
   general F10 defect, not a Thaumcraft one. **Fixed** by rebuilding the batch stack per fill;
   verified with 0 regressions.
4. **The judge never measured recall.** Fixed. At radius 60 against a correctly scoped stage-0 run:
   precision 967/967 present, 0 miscategorised, 954/954 NBT; recall 1016 of 1726, and the actionable
   residual is 22.
5. **Stock cannot serve as an A/B oracle.** Two identical cold stock runs differ by 85 / 125 chests.
6. **Bytecode is not what the pack runs.** The hilltop predicate was transcribed from Thaumcraft
   bytecode and was wrong — GTNH patches `GetValidSpawnBlocks` to accept sand. That error read as a
   58% circle-count *drop* and was nearly written up as a balance change. A live differential against
   the real method is what caught it.

## 1. Hilltop circles: confirmed, and unpredicted

`thaumcraft.common.lib.world.WorldGenHilltopStones.func_76484_a`, read with `javap -c` against
`beta3/mods/Thaumcraft-1.7.10-4.2.3.5.jar`. The chest placement sits behind a guard at bytecode
offset 319 that fires only at the ring's exact centre column on the first iteration
(`x == centreX && z == centreZ && j == 1`), which is why there is exactly one chest per circle:

| offset | y | block |
|---|---|---|
| 465 | `y` | `Blocks.field_150474_ac` — mob spawner |
| 348 | `y+1` | `ConfigBlocks.blockCosmeticSolid` meta 1 — obsidian pedestal |
| 383 | `y+2` | `Blocks.field_150486_ae` — a single `TileEntityChest` |
| — | `y+5` | `createRandomNodeAt` via `ThaumcraftWorldGeneratorMixin` — the aura node |

Confirmed against a real circle with `-Dprobe.dump=2,17`, chest `(36, 97, 278)`:

```
4,95,6  minecraft:mob_spawner
4,96,6  Thaumcraft:blockCosmeticSolid:1
4,97,6  minecraft:chest:3
4,100,6 Thaumcraft:blockAiry
```

`chest-sites.json` contains two Thaumcraft classes, both *village* components
(`ComponentWizardTower`, `ComponentBankerHome`). There is no stage-0 prediction for any Thaumcraft
outer-world structure, and `WorldGenMound` (barrows) is in the same position. `WorldGenEldritchRing`
places no chest and is not implicated.

The chest is filled from `ChestGenHooks.getInfo("dungeonChest")`, so it is indistinguishable by
category from a vanilla dungeon chest. The block fingerprint above is the only reliable
discriminator, and it is exact.

## 2. The F10 double-fill collapse

`WorldGenHilltopStones` calls `ChestGenHooks.getInfo("dungeonChest")` twice (offsets 357, 364) and
`WeightedRandomChestContent.func_76293_a` twice (offsets 432, 450) into the **same** inventory. Stock
accumulates two independent batches; `generateChestContents` does not clear, which is why
`ChestFillContext` carries its own `clear()`.

`StructureChestFillMixin` injects at `RETURN` on that method, so
`ChestFillContext.refillChest` runs once per fill. Each run does `clear(inv)` and then re-rolls from
`new Random(fork(inv))`. Both fills share one inventory, therefore one fork seed, therefore identical
contents — the second wipes the first and reproduces it exactly.

Measured with `-Dgtnhdet.chesttrace=true`, radius 60, `rows`: **829 chest fills, 23 distinct
positions filled more than once.**

| repeats | sites | example | caller |
|---:|---:|---|---|
| 4 | 2 | `837,64,1111` (`rolls=1` ×4) | `SwampStructures$SwampMasonHouse.func_74875_a:7581` |
| 4 | — | `803,63,1075` (`rolls=5` ×4) | `SwampStructures$SwampWeaponSmithy.func_74875_a:13538` |
| 3 | 2 | `855,64,1046` (`rolls=3` ×3) | `SwampStructures$SwampLibrary.func_74875_a:7111` |
| 3 | — | `219,75,-538` (`rolls=5` ×3) | `PlainsStructures$PlainsTannery1.func_74875_a:11452` |
| 2 | 19 | `36,97,278` (`rolls=8` ×2) | `WorldGenHilltopStones.func_76484_a:104` and `:105` |

By caller, the 23 sites are 12 `WorldGenHilltopStones` and 11 Village Names pieces spread across
Swamp, Savanna, Plains and Taiga structure sets. **This is a general F10 defect, not a Thaumcraft
one.**

The `rolls=` value is identical across every repeat at a given site — `rolls=1,1,1,1` and
`rolls=5,5,5,5` — which is the direct signature of the same fork being consumed each time. A site
filled four times keeps one batch and discards three.

Note what this is and is not. It is **deterministic**: the collapsed result is stable and
position-derived, so it does not break the determinism guarantee. It is a **fidelity** defect: the
chest holds less than the generator asked for. That distinction matters for how it gets fixed.

### Fixed: rebuild the whole batch stack on every fill

The naive repair — "don't clear on a repeat fill" — is wrong, and the reason is the injection point.
`StructureChestFillMixin` injects at `RETURN`, so by the time the second refill runs the inventory
holds *this class's* batch 0 plus *stock's* second batch. Accumulating onto that keeps stock's
contribution, which is exactly what the fix exists to replace.

`refillChest` now rebuilds the entire stack on every fill: `clear(inv)`, then replay batches
`0..N` with a per-batch seed. **Batch 0's seed is `fork(inv)` unchanged**, so a chest that is filled
once — every other chest in the world — is bit-identical to before. Repeat fills are consecutive on
one thread (same generator method, often the same line), so a last-seen slot recovers the index with
no map and no allocation.

Verified by A/B on one jar with `-Dgtnhdet.chestbatch=false` as the only variable, radius 60, `rows`:

| | off | on |
|---|---:|---:|
| inventories | 1689 | 1689 — **0 existence differences** |
| contents changed | — | 29 of 1689 |
| changed but NOT a multi-fill position | — | **0** |
| total stacks at multi-fill sites | 169 | **300** |

Every change lands on a known multi-fill position and nothing else moves, which is the property that
makes this safe to ship: it restores the intended quantity at 31 sites without re-rolling the world.

Two caveats. A 3-fill site went 5 -> 6 stacks rather than tripling, because a 27-slot inventory
collides once it is dense. And `consumeTable` pops a fresh table per fill while this applies the last
one to every batch — harmless where the repeats capture the same table (hilltop is `dungeonChest`
twice; the 4x village sites are a single line), but it would be wrong for a site that changed category
mid-stack. None has been observed.

**The first A/B of this was invalid** and is worth recording: it compared two different jars, so
dungeon placement changes appeared as 514/457 existence differences and ten "regressions" that were
actually hilltop circles. A lever on the one behaviour under test is the only comparison that means
anything — the same lesson as §2c.

### Why the stock A/B could not settle it

Planned as one stock arm against one fix arm, comparing stack counts at the fingerprinted chest. It
does not work, for two independent reasons measured here:

- **The chest does not exist in stock.** The stock block column at `36,*,278` ends at
  `4,94,6 minecraft:grass` — no spawner, no pedestal, no chest, no node. The fix changes RNG streams,
  so structure placement legitimately differs.
- **Stock is not self-consistent.** Two identical cold stock runs (`beta3-stock`, radius 24, `rows`):

  | | inventories | only in A | only in B | contents differ, shared |
  |---|---:|---:|---:|---:|
  | stockA vs stockB | 200 vs 240 | 85 | 125 | 1 of 115 |

  With a noise floor that large, no single-chest claim can be read off a stock comparison at all.

The mechanism is therefore established from the trace and the code, not from a stock delta, and the
"stock would have had N batches" half remains an inference from `generateChestContents` not clearing.

## 3. Stage-0 prefilter vs true worldgen, radius 60

This is the comparison that matters for the routemap loot layer: what stage 0 predicts against what
actually generates. Both sides beta-3, same seed.

- **Prediction side:** a fresh stage-0 run, `scripts/prefilter.sh` against
  `~/.cache/gtnh-determinism/beta3`, `PREFILTER_RADIUS=64`, with
  `-Dprobe.prefilter.chests=true -Dprobe.prefilter.dungeon=64 -Dprobe.prefilter.stronghold=64
  -Dprobe.prefilter.witchery=64`. **960 predicted chest positions** (village 32 + 8 refused,
  roguelike 893, stronghold 27, Witchery 0).
- **Worldgen side:** cold beta-3, `PROBE_SEARCH=true`, radius 60, centre chunk (9,9), `rows` —
  15213 chunks, **1738 inventories** (1726 inside the radius filter).

**`PREFILTER_RADIUS` is measured from the ORIGIN, the corpus window from its walk centre.** At
`PREFILTER_RADIUS=64` against a radius-60 window centred on chunk (9,9) — which spans chunks −51..69 —
the far corner of the corpus was outside the scan, and the prefilter found 3 village starts instead of
9. Every village figure from that run was wrong. Re-run at `PREFILTER_RADIUS=96`, which covers it:
**9 village starts, 82 village chests, 967 predicted positions in window.**

```
precision : 967 predicted positions in window, 967 present, 0 ABSENT
            954 contents identical, 13 refused on purpose, 0 MISCATEGORISED, 954/954 NBT
recall    : 1726 corpus chests, 1016 accounted for, 710 UNEXPLAINED
            54 XZ hosting >1 chest
```

**Precision is exact**: every one of the 967 in-window predictions generated, at the right position,
with the right contents and the right NBT. Nothing the prefilter claims is wrong.

Attribution of the 710, using the radius-60 chesttrace as ground truth (`--trace`), which names the
generator frame for 800 of the filled positions:

| bucket | n | basis |
|---|---:|---|
| vanilla `WorldGenDungeons` | 509 | `cat=dungeonChest` from the populate frame; declared blind spot |
| near-prediction (≤16 blocks) | 125 | heuristic, ambiguous |
| village-piece-empty | 41 | inside a village building, **no items** — casting tables, barrels, furnaces |
| traced, caller unmapped | 13 | Witchery `ComponentShack` (no-hooks), Witchery dispenser, `ComponentWizardTower` |
| **thaumcraft-hilltop** | **11** | caller is `WorldGenHilltopStones` |
| **village-piece** | **10** | loot-bearing, inside a known piece, unpredicted |
| unattributed | 1 | |

**Actionable: 22.** The village-piece 10 break down as 6 `ComponentVillageBeeHouse` (Forestry
apiaries holding bee genomes, which the prefilter refuses because `naturalistChest` reads 0..0),
2 `ComponentVillageBookShop` (the permanent gap already recorded in `chest-nohooks.json` — it writes
slots directly, so no hook sees it), 1 `ComponentVillageWatchTower`, 1 `ComponentVillageApothecary`.

So village loot prediction is in good shape. The earlier readings of 59 and 9 misses were artefacts
of, in order: a judge that never measured recall, an oracle scoped to the wrong radius, and counting
empty village furniture as missing loot. **The real residual is 11 Thaumcraft hilltop circles, 10
village chests of which 8 are documented refusals, and 1 unattributed.**

Note the earlier radius-24 figures in this file (226 chests, 124 unexplained) cover only a 49×49
chunk box, ±384 blocks around chunk (9,9). They were the only committed beta-3 corpus at the time.
The radius-60 numbers above supersede them.

## 2b. The reported bug reproduces: hilltop circles are route-dependent

Two cold beta-3 runs differing only in walk order, radius 60, centre chunk (9,9), same jars, both with
`-Dgtnhdet.chesttrace=true`. Hilltop circles counted from their `WorldGenHilltopStones` trace frames.

| | rows | spiral |
|---|---:|---:|
| **hilltop stone circles** | **12** | **14** |
| all inventories | 1738 | 1749 |

One circle exists only under `rows` (`-566,87,833`); three exist only under `spiral`
(`-363,88,254`, `357,125,-417`, `734,99,573`). Chest existence overall: 49 only-rows, 60 only-spiral.
Contents differ at 4 shared positions; 0 NBT-only.

This is the reported non-determinism, and it is consistent with the mechanism flagged in §4:
`ThaumcraftWorldGeneratorMixin:180-187` still runs `hilltopStones.generate` and `createRandomNodeAt`
off the shared `srand`, so a circle that generates in one walk order and not another shifts every
later draw in that chunk. The eldritch branch three lines above takes dedicated forks 9/10/11 for
precisely this hazard.

**Not claimed:** the same pair reports 12440 of 14641 chunks differing on the block hash (85%) and
1168 on the TE hash. Warm-vs-cold at the same radius was 547 (3.7%). An 85% figure is large enough
that it needs a rows-vs-rows control at radius 60 before it means anything, and no such control was
run. The chest and circle counts above do not depend on it.

## 2c. The fix: virgin-terrain siting, plus fork isolation

Two separate defects were in play, and only fixing both makes placement a function of the seed.

**Draw skew (forks 14/15).** `hilltopStones.generate` and the following `createRandomNodeAt` both ran
off the shared `srand`. The circle's cosmetic draws consume a live-condition-dependent COUNT of rolls,
so that count shifted the node's roll. Each now takes its own fork. The `srand.nextInt(40)` gate stays
on the shared stream, so placement probability and surrounding feature order are unchanged.

Measured: fork-only jar, radius 60 — `rows` 12 circles at **positions identical to pre-fix**, and
`rows` vs `spiral` still 12 vs 14 with 1 only-rows / 3 only-spiral, bit-identical to pre-fix. Exactly
as intended: the fork moves nothing, and on its own fixes nothing about existence.

**Existence (the actual bug).** `WorldGenHilltopStones.func_76484_a` gates on five
`LocationIsValidSpawn` probes at (x-2,z-2), (x,z), (x+2,z), (x+2,z+2), (x,z+2), each reading live
`World.getBlock`; and the caller anchored the test at live `world.getHeightValue`. Both are now read
from virgin terrain:

- `worldgen/HilltopSiting.java` — stock's predicate transcribed from bytecode against
  `TerrainOracle`: y≥85 floor, climb-while-solid with a `d>2` reject, base at `y+d-1`, air required
  above, base in {stone, grass, dirt} or snow/tallgrass sitting on one of those.
- `mixins/worldgen/WorldGenHilltopStonesMixin.java` — `@Redirect` on all five probes. Same predicate,
  same offsets, same floor; only the terrain source changes.
- `HilltopSiting.anchorY` replaces `world.getHeightValue` in `ThaumcraftWorldGeneratorMixin`.

**Anchor convention is the trap here.** Stock anchors at `getHeightValue`, which is one ABOVE the top
solid block. `EldritchRingLottery.surfaceY` returns the top solid block itself. Reusing the latter
would shift every circle by one. `anchorY` deliberately returns `y + 1`. Checked against the circle at
(36, 97, 278): virgin top solid is grass at y=94, so anchor 95, and the chest lands at 97.

`anchorY` returns -1 for a water-topped column, which stock could not express. It is not new
behaviour: stock would read `base == water`, which is not in the valid list, and reject too.

### Result

Radius 60, cold beta-3:

| jar | rows | spiral | rows vs spiral |
|---|---:|---:|---|
| pre-fix | 12 | 14 | 1 only-rows, 3 only-spiral |
| fork-only | 12 | 14 | 1 only-rows, 3 only-spiral (bit-identical to pre-fix) |
| **siting fix** | **20** | **20** | **0 / 0 — positions identical** |

**Route-dependence is gone.** Placement is now a pure function of the seed. Against stock's 12: 10
kept at the same positions, 2 lost, 10 new.

**The count goes UP, not down, and that is the expected direction.** Stock evaluates the five columns
against live terrain during population, so a probe at a ±2 offset can cross into a neighbouring chunk
that has not generated yet and read air — `base=minecraft:air`, site rejected. Virgin terrain always
has real blocks there, so sites that stock spuriously rejected now pass. The 2 stock circles that are
lost are the mirror case: they only passed because live terrain at that moment differed from what the
seed actually specifies.

### The predicate was wrong first, and the lever is what caught it

An earlier version of this fix read **5** circles, and the drop from 12 was written up here as an
unexplained balance change. It was not a balance change; it was a bug in the transcription.

`WorldGenHilltopStones.GetValidSpawnBlocks()` returns `{stone, grass, dirt}` in unpatched Thaumcraft
bytecode, and that is what was hardcoded. **GTNH patches it at runtime to also accept sand, gravel and
packed ice.** Desert hilltop circles are the common case, not an edge case. With
`-Dgtnhdet.hilltopdiff=true` logging every column where the transcription disagreed with the real
method: **43 disagreements, every one of them `stock=true mine=false why=base=minecraft:sand`.**

`HilltopSiting` now captures the array from the shadowed `GetValidSpawnBlocks()` instead of restating
it, so the two cannot drift again. Re-measured: **0 disagreements.**

Two process notes worth keeping:

- The A/B lever (`-Dgtnhdet.hilltopvirgin=false`) initially returned a *reimplementation* of stock
  rather than calling the real method. It read 1 circle where the stock jar built 12 — the lever was
  measuring the transcription, which is precisely the error it existed to catch. It now calls
  `self.LocationIsValidSpawn` directly.
- Reading mod bytecode is not the same as reading what the pack runs. Anything transcribed from a
  decompile needs a live differential against the real method before its numbers mean anything.

Circles sit on real ground, not on the oracle's idea of it. Block dump of the new circle at
(66, 93, -118), chunk (4,-8):

```
2,90,10  minecraft:grass                    <- real surface
2,91,10  minecraft:mob_spawner
2,92,10  Thaumcraft:blockCosmeticSolid:1
2,93,10  minecraft:chest:3
2,96,10  Thaumcraft:blockAiry
```

Anchor 91 = top solid 90 + 1, exactly what `anchorY` computes, so `TerrainOracle`'s virgin terrain
agrees with the terrain actually built here.

This changes circle placement relative to every earlier jar. That is unavoidable and correct — the
old placement was not a function of the seed, so there was no stable thing to preserve.

**Untouched, same hazard:** the `WorldGenMound` branch two lines above runs `mound.generate` and its
`createRandomNodeAt` off the shared `srand` and anchors on the same live `getHeightValue`.

## 2d. Barrows: same defect, same fix, verified on a seed that has them

`WorldGenMound` carries a byte-identical copy of `LocationIsValidSpawn`, differing only in having **no
minimum-Y floor**. It gates on five probes at (x+9,y+9,z+9), (x,y+9,z), (x+18,y+9,z), (x+18,y+9,z+18),
(x,y+9,z+18) — an 18x18 footprint sampled at surface level, since the caller passes
`getHeightValue() - 9` — and the caller's anchor is itself a live `getHeightValue`. Both are now read
from `TerrainOracle` (`MoundSiting`, `WorldGenMoundMixin`), and `mound.generate` /
`createRandomNodeAt` take forks 16 and 17.

Because the predicate is shared, it lives in `worldgen/TcSiting.java` and is stated once. Copying it
is what produced the sand bug in §2c.

**Seed `-1636594104014467454` has no barrows at all** — 91 candidates at radius 60, zero pass, in both
arms. A hunt across 6 seeds found 264 candidates and 18 barrows, so they are not dead under RWG; that
seed simply has none. Verified instead on seed `-8790602067241017142`, radius 40, centre chunk (0,0):

| siting | rows | spiral | identical |
|---|---:|---:|---|
| stock | 5 | 2 | no — 3 only-rows |
| **virgin** | **8** | **8** | **yes, positions identical** |

Candidate count is 46/46 in every arm, which is the control: the gate (`srand.nextInt(150)`) is
untouched and only the verdict changed.

Transcription fidelity checked before trusting any of it — `-Dgtnhdet.mounddiff=true` against the real
method: **0 disagreements** (and 0 for hilltop on the same run).

Count rises 5/2 -> 8, the same direction as hilltop and the opposite of vanilla dungeons. The sign
follows the predicate: hilltop and barrow both want *solid* ground, and live reads at chunk borders
return air for ungenerated neighbours, so live spuriously **rejected**. The dungeon predicate wants
*air openings*, and decorated neighbours supply extra air, so live spuriously **accepted**.

## 3a. Warm vs cold, radius 60: chests identical, terrain is not

Same seed, same centre chunk (9,9), same `rows` order, same beta-3 install and jars, radius 60.
Cold via `run-probe.sh`, warm via `warm-probe.sh` with `PROBE_SEARCH=true`.

| layer | result |
|---|---|
| chest inventories | **1738 vs 1738. 0 existence, 0 contents, 0 NBT differences.** |
| per-chunk block hash `b` | **547 of 14641 chunks differ** |
| per-chunk TE hash `t` | **7 of 14641 chunks differ** |
| `villages` | identical |
| `popseq` | differs (expected: warm skips `initialWorldChunkLoad`) |

Two things follow, and they point opposite ways.

**The loot layer is warm-safe.** Every chest in a radius-60 window is identical warm vs cold, down to
NBT. A warm run is a valid substrate for chest and loot work on beta-3, which is what
`f9warm/` could not show because it captured no chest data at all.

**The terrain divergence from `results/2026-09-06-warm-vs-cold-terrain/` is still present.** That run
measured 293 of 625 chunks differing at radius 6 on jars `v0.8-main.2+4c6e016626`; this one measures
547 of 14641 (3.7%) at radius 60 on `v0.8-main.3+5f5f73299a`. Lower rate, not resolved.

**And `te-only: 0` no longer holds.** The earlier run reported zero TE differences; at radius 60
there are 7 chunks whose TE hash differs while every chest inventory in the window matches. So those
7 are non-chest tile entities — GT machine/ore TEs are the obvious candidates — and they are
unidentified. Seven differing tile entities is not a rounding error and is not a pass; it needs
`-Dprobe.tedetail=true` on both arms to say what moved. Not done.

## 3b. Recall tooling, and a stale oracle

[prefilter-judge-chests.py](../../seedsearch/prefilter-judge-chests.py) advertised `recall` and
`miscategorised` in its docstring and computed neither — the loop ran predictions→corpus only, so an
unexplained corpus chest was never visited. It also keyed the corpus by `(x, z)` into a flat dict,
silently dropping one of any two chests sharing an XZ, and never radius-filtered the corpus side.

Rewritten to measure both directions. Against `beta3-spawn.chests.json` (radius 24, centre (9,9)):

```
precision : 94 predicted positions, 94 present, 0 absent
            91 contents-ok, 3 refused on purpose, 0 miscategorised, 91/91 NBT
recall    : 226 corpus chests in window, 102 accounted for, 124 UNEXPLAINED
            4 XZ hosting >1 chest (a flat dict would have dropped these)
```

[chest-attribution.py](../../seedsearch/chest-attribution.py) splits the 124:

| bucket | n |
|---|---:|
| village-piece | 9 |
| thaumcraft-hilltop | 1 (only chunk 2,17 has block data) |
| near-prediction | 13 |
| deep-blind-spot (`Y<64` heuristic; measured Y 11..61, median 31) | 98 |
| **unattributed** | **3** |

The residual is `(372,66,396)` and the pair `(523,86,-159)` / `(525,86,-162)`.

`deep-blind-spot` is a heuristic, not an identification, and prints its own Y range for that reason.
An earlier `Y<40` cutoff put 29 vanilla `WorldGenDungeons` chests — which run to Y58 here, in pairs —
into `unattributed` and made the headline read four times worse than it is.

### The village recall number is an artefact

Of the 9 village-piece misses, 4 are `ComponentSmeltery`, which `chest-sites.json` marks chestless for
all four modes; those corpus chests are all empty, so the table is right.

The other 5 are Village Names Taiga pieces, and the oracle at
`~/.cache/gtnh-determinism/sweep-final/one.jsonl` predicts **zero** chests for any Taiga piece while
laying out thirteen of them. The current table covers exactly the orientations they generated at:

| piece | generated `mode` (chesttrace) | `sites` modes in chest-sites.json |
|---|---:|---|
| TaigaTannery1 | 2 | `[2]` |
| TaigaSmallHouse3 | 0 | `[0]` |
| TaigaSmallHouse5 | 0 | `[0]` |
| TaigaCartographerHouse1 | 2 | `[2]` |
| TaigaMediumHouse3 | 1 | `[1]` |
| TaigaWeaponsmith1 | 3 | `[3]` |

Every one matches. The sweep predates those rows, so this is stale-oracle drift, not a live recall
defect. **Any village recall figure derived from `sweep-final/` is void until stage 0 is re-run on
beta-3 with the current table.** That re-run has not been done.

The single-mode coverage is still a live hazard even though it did not cause this: `loot-csv.py`
computes coverage on the simple class name and ignores `mode`, so a piece measured at one orientation
of four reads as fully covered.

## Artifacts

Under the scratchpad for this session (not committed — regenerate with the commands below):

| run | server | radius | notes |
|---|---|---:|---|
| `fix/` | beta3 | 24 | `PROBE_DUMP=2,17`, the fingerprint dump |
| `trace/` | beta3 | 24 | `-Dgtnhdet.chesttrace=true`, 157 fills |
| `wide/` | beta3 | 60 | `-Dgtnhdet.chesttrace=true`, 829 fills, blast radius |
| `stockA/`, `stockB/` | beta3-stock | 24 | fix jar absent, noise floor |

`~/.cache/gtnh-determinism/beta3-stock` is a full `rsync` copy of `beta3` with
`gtnhdeterminism-*.jar` removed. The original install was not modified.

```sh
SEED=-1636594104014467454
JAVA=$(ls -d ~/.gradle/jdks/*/bin/java | head -1)

# fingerprint + trace
PROBE_SEARCH=true PROBE_CX=9 PROBE_CZ=9 PROBE_PORT=25571 PROBE_DUMP=2,17 PROBE_JAVA=$JAVA \
  ./scripts/run-probe.sh ~/.cache/gtnh-determinism/beta3 $SEED rows <out>/seed-$SEED.json 24
PROBE_SEARCH=true PROBE_CX=9 PROBE_CZ=9 PROBE_PORT=25571 PROBE_JAVA=$JAVA \
  PROBE_EXTRA_ARGS="-Dgtnhdet.chesttrace=true" \
  ./scripts/run-probe.sh ~/.cache/gtnh-determinism/beta3 $SEED rows <out>/seed-$SEED.json 60

# analysis
python3 seedsearch/prefilter-judge-chests.py ~/.cache/gtnh-determinism/sweep-final/one.jsonl \
    results/2026-09-07-beta3-chest-loot-parity/beta3-spawn.chests.json
python3 seedsearch/chest-attribution.py \
    results/2026-09-07-beta3-chest-loot-parity/beta3-spawn.chests.json \
    ~/.cache/gtnh-determinism/sweep-final/one.jsonl --dump <out>/seed-$SEED.json.dump-2_17.txt
```

## Open

**Decided / done since first draft**

- The `rows` vs `spiral` hilltop test ran: 12 vs 14 before, **20 vs 20 identical** after the siting
  fix (§2c). Barrows likewise 5/2 -> 8/8 on a seed that has them (§2d).
- The double-fill collapse is **fixed and verified** (§2, "Fixed: rebuild the whole batch stack"),
  0 regressions against a single-jar A/B.
- Stage 0 re-run on beta-3 (§3). Traps found: `-Dprobe.prefilter.dungeon` / `.stronghold` /
  `.witchery` are **radii**, not booleans, defaulting to `-1` (off), so a run without them silently
  reports zero of those chests; `-Dprobe.prefilter.surfacey` takes a **file path** and crashes at mod
  init if given `true`; and `scripts/prefilter.sh` returns exit 0 even when the JVM crashes, so check
  the output file exists rather than the exit code.
- `seedsearch/README.md:43` and `seedsearch/loot-score.py:60` have been corrected — they described
  dungeon existence as held pending the GregTech ore read, which the fix disproves.

**Still open**

- **Witchery predicts 0 chests across 45 cells** at radius 64 while the trace shows 12 Witchery
  fills in the same window. Not investigated.
- **Count changes: only barrows is a demonstrated effect.** 24 seeds, paired permutation test —
  barrows +209% (CI +118..+300%, p=0.0002); dungeons -1.2% (CI -6.0..+3.8%, p=0.65) and hilltop
  circles +29% (CI -13..+69%, p=0.23) are not distinguishable from zero. Determinism is 24/24 clean.
  A balance decision is needed for barrows; the other two need ~105 and ~790 seeds respectively
  before any claim can be made. See `results/2026-09-08-vanilla-dungeon-determinism/`.
- **Chest-level radius-60 recall has not been re-measured** with the dungeon and hilltop fixes in.
  The 967/1726 figures in §3 predate them.
- **7 warm-vs-cold tile entities differ** at radius 60 (§3a) while every chest matches. They are
  non-chest TEs, unidentified; needs `-Dprobe.tedetail=true` on both arms plus
  `scripts/diff-tedetail.py`. Seven differing TEs is not a rounding error.
- **`refillDispenser` would collapse repeat fills** the way `refillChest` used to. Currently
  unreachable: zero `generateDispenserContents` calls at radius 60, because RWG never constructs
  `MapGenScatteredFeature`. The dispensers that do get filled go through the *chest* filler
  (Witchery's `setDispenser`) and are already covered. Known and bounded, not fixed.
- **Three unattributed chests** in the radius-60 attribution remain unidentified.
- **Multi-fill enumeration is one seed, one window.** 31 sites over 12 generator classes; the
  per-class behaviour generalises, the per-class *site counts* do not, and biome sets that did not
  generate here (desert, jungle, mesa Village Names) are unchecked.
