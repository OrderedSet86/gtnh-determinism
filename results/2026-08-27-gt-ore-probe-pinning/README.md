# 2026-08-27/28 — GT ore worldgen: three attempts, none of them worked

**Outcome: no determinism improvement. All code changes reverted except a dependency fix.**
This directory is a record of what was tried, what it measured, and why each approach was wrong, so
none of it gets retried.

Seed `-1501259159663517643`, GTNH 2.8.4, radius 15, all arms COLD (`scripts/run-probe.sh`), ground
truth = persisted-world tile-entity diff over every region file.

## The baseline that matters

`GT_TileEntity_Ores` only:

| pair | differing TEs |
|---|---|
| **cold rows vs cold rows** — same jar, same order (launch noise floor) | **3** |
| cold rows vs cold spiral (stock-plus-existing-F4 fix) | 12,970 |

Floor is 3 out of ~390,000. Ore is launch-deterministic and order-broken. The target is 3.

## Attempt 1 — "probe pinning". REVERTED, it improved nothing

`WorldgenGTOreLayer.executeWorldgenChunkified:146` decides accept-vs-reroll from one block,
`aWorld.getBlock(aChunkX + 7, tMinY, aChunkZ + 9)` — local (7,9) of the TRIGGER chunk, whichever of
the 25 chunks in the ±2 box reached that oreseed first, with the answer then memoised region-wide in
the static `validOreveins`. The existing F4 mixin already answered that probe from virgin terrain;
the theory was that the *coordinate* was still route-dependent, so pinning it to the oreseed chunk
(`aSeedX + 7 / aSeedZ + 9`) would make identity seed-pure.

It moved the headline number from 12,970 to 10,259 and I reported that as −21%. **That was wrong.**
Measuring vein identity directly, from GT's own `debugOrevein` log:

| variant | oreseeds | same mix | differing |
|---|---|---|---|
| unpinned (stock trigger chunk) | 144 | 141 | **3** |
| pinned (oreseed chunk) | 144 | 142 | **2** |

Identity was already 141/144 without the change. Pinning bought one oreseed. And decomposing the
headline number by whether a differing ore TE lies inside a flipped vein's footprint:

| variant | ore diffs | inside flipped veins | residual outside |
|---|---|---|---|
| unpinned (3 flips) | 12,970 | 8,449 | **4,521** |
| pinned (2 flips) | 10,259 | 5,490 | **4,769** |

The entire −21% is "3 flipped veins instead of 2". The residual outside the flipped veins got
**worse by 248**. This was a lottery re-draw, not a fix. Reverted.

## Attempt 2 — neutralising `NO_ORE_IN_BOTTOM_LAYER`. REVERTED, it made identity worse

Second reroll gate: after the bottom layer is placed, stock rerolls the whole vein if that layer
placed nothing. Redirected the two bottom-layer `TileEntityOres.setOreBlock` calls so the real write
still happens but the call reports success, making the gate unable to fire.

**Identity went from 2 differing oreseeds to 5.** Reverted.

The gate has TWO impure branches and this closed only one:

1. `setOreBlock` answering from the live world — closed.
2. Whether `setOreBlock` is called at all. The loop attempts a placement only when
   `aRandom.nextInt(placeZ) == 0 || aRandom.nextInt(placeX) == 0`, and those bounds come from
   `localDensity = mDensity / sqrt(2 + (aChunkX/16 - aSeedX/16)² + (aChunkZ/16 - aSeedZ/16)²)` — the
   trigger chunk's distance from the oreseed — over a trigger-clipped window. Still impure, still
   fired 111/117 times.

Same lesson as attempt 1, stated generally: **partially purifying an arbitrary decision does not
partially fix it, it just picks a different arbitrary answer.** Only a decision that is a total
function of (world seed, dim, oreseed, mix) counts.

## Attempt 3 — the SliceApplier ordering theory. REFUTED by measurement

`PendingSlices.SliceApplier` registers at `Integer.MAX_VALUE`, GT at `1073741823`, so the applier
runs AFTER GT. That predicts an order-routed contest: a chunk with buffered dungeon writes lets GT
place ore first and then overwrites it, while a chunk on the live path already carries the dungeon
blocks when GT runs. Supporting evidence looked good — residual ore diffs are **2.4× enriched**
within 96 blocks of a Roguelike dungeon (43.7% vs an 18.2% baseline).

Measured with a temporary `-Dgtnhdet.sliceweight` lever at weight `1e9` (applier before GT):

| applier weight | ore diffs | residual | near-dungeon share |
|---|---|---|---|
| `Integer.MAX_VALUE` (default, after GT) | 10,259 | 4,769 | 43.7% |
| `1000000000` (before GT) | 10,268 | 4,802 | 44.1% |

No effect. Lever reverted. The enrichment is real but not causal — most likely dungeon regions are
simply full of carved air and non-stone, so placement there is more sensitive to any upstream
difference.

## The one thing worth keeping: the GT compile dependency was the wrong API line

`fix-build/dependencies.gradle` pinned `GT5-Unofficial:5.09.54.50` while the pack ships
`5.09.51.482`. Not compatible ore APIs — 54.x uses `IOreMaterial` instead of the `mPrimaryMeta`
shorts, `veinHeight` (a `ShortShortPair`) instead of `mMinY`/`mMaxY`, adds a `VeinPlacement` record,
and has **no `TileEntityOres.setOreBlock` at all**. Anything written against 54.x compiles and then
cannot bind at runtime — and with `require = 0` on these redirects that failure is silent.

Now `5.09.51.482` with `transitive = false` (it pulls `CodeChickenLib 1.3.0`, purged from the GTNH
maven — same workaround the repo already uses for LootGames). Jar md5 was unchanged across the swap,
confirming it altered no output. **Kept.**

## What is actually wrong, and what would fix it

Two independent order-dependencies remain, both in the same class of "a decision read from the live
world or from trigger-chunk-relative geometry":

1. **Identity** — 3 of 144 oreseeds resolve to a different vein mix by walk order, via
   `NO_ORE_IN_BOTTOM_LAYER`. Worth ~8,400 of the 12,970 differing ore TEs, because a flipped vein
   replaces its whole footprint.
2. **Placement** — ~4,500 differing ore TEs inside veins both orders agree on, because
   `TileEntityOres.setOreBlock` (`TileEntityOres.java:66-118`) reads the live world at every write
   coordinate and only replaces stone-like blocks.

The correct fix for (1) is a **total** re-evaluation, not a patch of one branch: rebuild
`XSTR(oreveinSeed ^ mPrimaryMeta)` (the same stream the call site builds, so draws 1-5 are
`tMinY, wXVein, eXVein, nZVein, sZVein`), clip the window to the ORESEED chunk, use that chunk's
`localDensity`, replay the RNG gate, and test virgin terrain at `tMinY - 1`. Then the verdict is a
function of (world seed, dim, oreseed, mix) and nothing else.

For (2), route `setOreBlock`'s replaceability test through `TerrainOracle` as well. The objection
previously recorded here — "ore would overwrite blocks placed earlier in the chunk" — is a
*semantic* cost, not a determinism one: virgin terrain is deterministic, so the placement decision
becomes deterministic regardless of whether population is. The real price is that ore will replace a
dungeon wall or lake block sitting where virgin stone was, deterministically but visibly unlike
stock. That is a design call, not a blocker.

## Reproduce

```sh
export PROBE_JAVA=~/.gradle/jdks/azul_systems__inc_-17-amd64-linux.2/bin/java
scripts/build-jar.sh fix --deploy <server-dir>
PROBE_SEARCH=true PROBE_PORT=25590 scripts/run-probe.sh <server-dir> -1501259159663517643 rows   out-rows.json   15
cp -a <server-dir>/World rows-cold          # the server saves on shutdown
PROBE_SEARCH=true PROBE_PORT=25591 scripts/run-probe.sh <server-dir> -1501259159663517643 spiral out-spiral.json 15
cp -a <server-dir>/World spiral-cold
python3 scripts/diff-region-tes.py rows-cold spiral-cold
```

For vein identity, set `B:debugOrevein=true` in `<server-dir>/config/GregTech/GregTech.cfg` and
**verify it is still `true` after the run** — a scripted edit that silently fails to match will give
you an empty log and a meaningless zero. Compare the `Added near|far oreveinSeed=… ore.mix.X` line
per `oreveinSeed` between arms. Logging-only, consumes no RNG. Restore to `false` afterwards.
