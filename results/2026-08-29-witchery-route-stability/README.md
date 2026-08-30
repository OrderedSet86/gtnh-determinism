# Stage 5 step 1: is Witchery already route-stable?

GTNH daily-707, `~/.cache/gtnh-determinism/daily-707`, fix jar md5 `1a5b30cece23e2bd20e7f4dc0fba2519`,
probe md5 `6005848bf70caf2237879ee5b3b74161`. Cold `run-probe.sh` pairs at radius 15, worlds kept and
diffed with `scripts/diff-region-blocks.py`; warm pairs at radius 12 for the structure lists.

The plan's step 2 (redirect Witchery's ground and clearance reads to `TerrainOracle`) is conditional
on this measurement. **It is not justified by what this found, and it is not implemented.**

## What is route-stable

**Structure sites.** The probe's `witchery` dump reads `WitcheryWorldGenerator.structuresList`, the
chunks where structures were placed. Warm rows vs warm spiral, same seed:

| seed | rows | spiral | match |
| --- | --- | --- | --- |
| 4329705733811276668 | `[-144,-192] [-192,144] [176,160]` | identical | yes |
| 3007102813260058640 | `[160,-208] [176,96]` | identical | yes |

Non-empty on both arms, so this is a real comparison rather than two empty lists agreeing.

**Component chest contents.** Witchery fills chests through `ComponentShack`,
`ComponentVillageBookShop`, `ComponentVillageApothecary`, `ComponentVillageKeep` and
`ComponentVillageWatchTower` — all present in the F10 chest trace. The route test that covers them
(seed `-777`, rows vs spiral, 124 chests / 1817 item stacks) is **0 existence / 0 contents / 0 NBT**.

## What this could NOT resolve

**Whether the structure blocks themselves are route-stable.** Two instruments failed, for reasons
worth recording:

1. *Filter by Witchery block id.* 135 Witchery block ids from the save's FML registry; the filtered
   diff reports **0 differing blocks**. That is very nearly vacuous — the whole radius-15 world
   contains exactly **1** block from that set. Witchery's surface structures (covens, wicker men,
   shacks, goblin huts) are built overwhelmingly from *vanilla* blocks, so an id filter cannot see
   them.
2. *Diff a window around each known site.* 5×5-chunk windows at the three sites differ by 435, 4,641
   and 25,213 blocks. Against a control of ten same-sized windows with no Witchery structure: seven
   are exactly **0**, but three are 2, 2,377 and 8,340. The ambient residual is patchy and of the same
   order, so **a block diff cannot separate a Witchery difference from the endemic decoration/terrain
   residual.** No conclusion either way.

Resolving it needs a Witchery-specific placement trace — the same shape as
`-Dgtnhdet.chesttrace`: log what the generator places and where, then compare the logs across arms.

**Done, same day: `-Dgtnhdet.witchtrace=true`.** Across 3 seeds and both route arms, every gate cell's
biome verdict, shuffled handler order and outcome is identical, and all 9 placements agree in cell and
type. Witchery placement is route-stable and the `TerrainOracle` redirect is not needed. See
[results/2026-08-29-witchery-placement-trace](../2026-08-29-witchery-placement-trace/README.md), which
also records that the probe's own `witchery` dump under-reports by one structure per seed.

## Two instruments were broken, and both would have produced a false pass

**`scripts/diff-region-blocks.py --ids` was accepted and silently discarded.** `main()` did
`args = [a for a in sys.argv[1:] if not a.startswith("--")]` and never looked at `--ids` again, so a
filtered run reported the *entire* world. The first Witchery-filtered diff came back with 310,849
differing blocks and transitions like `0:0 -> 1:0`, which is what exposed it — air-to-stone is not a
Witchery block. Now implemented, and a section present on only one side is reported separately rather
than folded into the filtered count, because 4096 unattributable blocks cannot be charged to an id set.

**The shell `grep` silently returns nothing on large files.** `grep -c witchery` on the 162 KB
`WorldgenProbe.java` printed nothing and exited 1; `/usr/bin/grep` on the same file returns 9. It is a
shell function, and it is fine on small files, which is what makes it dangerous. It caused a real wrong
turn here: "the probe has no witchery reporting" — it has a whole `dumpWitcheryStructures` method.
**Use `/usr/bin/grep` on anything large.**

Both of these have the same shape as the `PROBE_JVMFLAGS` trap already documented in
`scripts/prefilter.sh`: the command succeeds, the output looks reasonable, and the comparison is empty.
The habit that catches them is checking that a filter *changed* the number before trusting the result.

## Also corrected

`seedsearch/README.md` said the Witchery structure-type shuffle is live-world. F2 shuffles a sorted
copy with FML's per-chunk `Random`, so the winner is a pure function of (seed, chunk). The biome gate
is worldless too, and since the Stage-1 chunk provider the ground checks are evaluable as well — what
is missing for a stage-0 Witchery module is the module, not the information.

## Next

- ~~Witchery placement trace~~ — done, see above; step 2 is not needed.
- The stage-0 module: emit candidate cells and the shuffle winner as a distance feature.
