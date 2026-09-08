# Roguelike route stability across 25 seeds — clean, with one unresolved pair

**Outcome: 7 roguelike dungeons compared across 25 seeds, rows vs spiral. 6 have byte-identical
chest sets. The 7th differs by 2 chests that carry the vanilla-dungeon signature, not the roguelike
one. Contents: 1,538 shared deep chests compared slot-for-slot, ZERO mismatches.**

**The world as a whole is NOT clean** — 62 chests differ across 12 of 25 seeds — but essentially none
of that is roguelike. See the breakdown below.

Jar `gtnhdeterminism-v0.8-main.3+5f5f73299a-dirty` md5 `170aad0a866639ab30ddfbb9a8141658` (includes
the `WorldEditorMixin.getBiome` fix), probe `df703010673e7d4b081be2b6cc804419`. 25 seeds
(`seeds.txt`), r15, `PROBE_SEARCH=true`, dedicated server, warm batches of 13 + 12 at `-Xmx16G`.
Every one of the 50 reports verified to carry a `search` section before comparison.

## Headline: roguelike only

Roguelike dungeons identified as clusters of >=40 chests **restricted to y<=60**. The depth limit is
load-bearing — see "the clustering trap" below.

| | |
| --- | ---: |
| roguelike dungeons compared | 7 |
| chest set identical in both arms | **6** |
| differing | **1** (2 chests of 121) |
| shared deep chests compared for contents | 1,538 |
| contents mismatches | **0** |

The one differing dungeon is seed `-8085914287301557748` @(-471,-829): chests
`(-428,27,-862)` and `(-426,27,-858)`, both rows-only, 7-8 stacks, GregTech/EMT/vanilla contents.
**These look like a vanilla dungeon inside the roguelike footprint, not roguelike loot**: two chests
4 blocks apart is `WorldGenDungeons`' signature, y=27 is not one of the roguelike floor levels, and
7-8 stacks matches the vanilla-dungeon distribution (median 7) rather than the roguelike one
(median 20). **Not confirmed** — no block-level check was run to look for a spawner or mossy
cobblestone. This is the one open item.

## Whole-world picture, and why it is mostly not roguelike

`scripts/diff-chests.py` over all 25 seeds: **12 of 25 seeds differ, 62 existence differences, 1
contents difference, 0 NBT-only.** Classified:

| class | chests | what it is |
| --- | ---: | --- |
| **Y-shift pairs** | 36 | same x,z, **identical contents**, y differs by 1-3 |
| unpaired, outside any roguelike footprint | 24 | vanilla dungeons and surface chests |
| unpaired, inside a roguelike footprint | 2 | the pair above |

The Y-shift class is the dominant residual and is its own defect, unrelated to roguelike: a
structure's vertical placement moves by 1-3 blocks between routes while its contents stay identical.
Examples: TConstruct slime islands at y 93-96 (`TConstruct:*` contents), Witchery at y 105/106,
vanilla at y 110/112. That is a live terrain-height read at generation time. `SlimeIslandGenMixin`
exists but evidently does not cover the height.

The single contents difference is at `(151,65,159)` on seed `-8462503657986418155`: 3 vs 4 stacks,
`Forestry:frameUntreated` vs `Forestry:frameImpregnated` — a village bee house
(`ComponentVillageBeeHouseMixin` territory), not roguelike.

## The clustering trap this run exposed

A first pass clustered chests by x/z proximity alone and reported **3 of 10 dungeons differing, 33
differing chests inside roguelike footprints**. That was wrong. Clustering on x/z sweeps in every
structure that happens to sit *above* a dungeon — the 28 "roguelike" diffs on seed
`-6588393237702451894` were all slime-island and Witchery chests at y 93-112, every one of them a
Y-shift pair. Adding `y<=60` drops it to 1 dungeon differing by 2 chests.

Two further discriminators were tried and rejected:

- **Stack count.** "Roguelike chests have ~20 stacks, vanilla ~7" is false as a filter: roguelike
  chests have p10 = 0 because many are empty, and 43% carry fewer than 15 stacks. Measured
  distribution: roguelike median 20 / p90 21 / max 27 against non-roguelike median 7 / p90 8.
  Useful as evidence, useless as a gate.
- **Bounding box membership.** Contaminated by anything inside the box at any height, which is how
  the first pass went wrong.

Cluster **membership** with a depth restriction is the discriminator that holds.

## Answering "is roguelike clean now"

For **chest contents**: yes, unambiguously — 1,538 shared deep chests, zero mismatches, and this has
been 0 in every comparison since the `getBiome` fix.

For **chest existence**: 6 of 7 dungeons exactly identical, and the 7th's 2 chests carry the vanilla
signature. Confirming that pair needs a block-level look for a spawner or mossy cobblestone at
`(-428,27,-862)`; until then the honest statement is "no roguelike difference identified, one pair
unresolved".

What is emphatically **not** clean is the rest of the world: 36 Y-shifted structure chests and ~24
vanilla-dungeon/surface chests across 12 seeds. Those are separate defects with their own owners.

## Reproducing

```
PROBE_SEARCH=true PROBE_XMX=16G \
  scripts/warm-probe.sh <server-dir> @seeds.txt rows|spiral <dir>/seed-{seed}.json 15
python3 scripts/diff-chests.py <rows-dir> <spiral-dir>
```

Move `*.veincache.json` out of each arm directory first — `diff-chests.py` globs `*.json` and dies on
that sidecar. `warm-probe.sh` does not stamp provenance, so verify `search` is present in each report
rather than trusting the flag. Do not pass `-Dprobe.search` via `PROBE_JVMFLAGS`: warm-probe places
`PROBE_JVMFLAGS` before its own `-Dprobe.*`, so it is silently overridden — use `PROBE_SEARCH=true`.

Committed: `rows.chests.json` / `spiral.chests.json` (every chest with its item stacks, per seed) and
`seeds.txt`.
