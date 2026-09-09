# Roguelike route stability across 25 seeds — CLEAN

**Outcome: 7 roguelike dungeons compared across 25 seeds, rows vs spiral. All 7 have byte-identical
chest sets. Contents: 1,538 shared deep chests compared slot-for-slot, ZERO mismatches. No roguelike
difference of any kind was found.**

The only candidate — 2 chests in seed `-8085914287301557748` — was confirmed at block level to be a
**vanilla dungeon** sitting inside the roguelike dungeon's x/z footprint, not roguelike output. See
"the one candidate, confirmed vanilla" below.

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
| chest set identical in both arms | **7** |
| differing | **0** (the 1 candidate is confirmed vanilla, below) |
| shared deep chests compared for contents | 1,538 |
| contents mismatches | **0** |

### The one candidate, confirmed vanilla

Seed `-8085914287301557748` @(-471,-829): chests `(-428,27,-862)` and `(-426,27,-858)`, both
rows-only. Regenerated the seed cold with `PROBE_DUMP=-27,-54` and read every non-air block in the
chunk. It is textbook `WorldGenDungeons`:

| evidence | value |
| --- | --- |
| floor at y=26 | **7x7**, world x[-430..-424] z[-863..-857] |
| floor composition | **38 mossy_cobblestone + 11 cobblestone**, randomly mixed |
| spawner | `minecraft:mob_spawner` at **(-427,27,-860)** — dead centre of that floor, one block above it |
| chests | both at y=27, sitting on the floor patch |
| loot | includes `minecraft:record_stal` — a vanilla `dungeonChest` item; the GregTech metaitems are GTNH's additions to that same table |

Roguelike builds themed stonebrick rooms and does not lay randomly-mixed mossy-cobble floors with a
centred spawner. This is a vanilla dungeon that happens to sit ~50 blocks from the roguelike
dungeon's centroid, inside its clustering radius.

**So the roguelike count is 7 of 7 identical, and the residual belongs to the separately-tracked
vanilla `WorldGenDungeons` route dependence.**

## Whole-world picture, and why it is mostly not roguelike

`scripts/diff-chests.py` over all 25 seeds: **12 of 25 seeds differ, 62 existence differences, 1
contents difference, 0 NBT-only.** Classified:

| class | chests | what it is |
| --- | ---: | --- |
| **Y-shift pairs** | 36 | same x,z, **identical contents**, y differs by 1-3 |
| unpaired, outside any roguelike footprint | 24 | vanilla dungeons and surface chests |
| unpaired, inside a roguelike footprint | 2 | the vanilla dungeon confirmed above |

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
Y-shift pair. Adding `y<=60` drops it to 1 dungeon differing by 2 chests, and those 2 turned out to be vanilla.

Two further discriminators were tried and rejected:

- **Stack count.** "Roguelike chests have ~20 stacks, vanilla ~7" is false as a filter: roguelike
  chests have p10 = 0 because many are empty, and 43% carry fewer than 15 stacks. Measured
  distribution: roguelike median 20 / p90 21 / max 27 against non-roguelike median 7 / p90 8.
  Useful as evidence, useless as a gate.
- **Bounding box membership.** Contaminated by anything inside the box at any height, which is how
  the first pass went wrong.

Cluster **membership** with a depth restriction is the discriminator that holds.

## Answering "is roguelike clean now"

**Yes, on this evidence.** Chest contents: 1,538 shared deep chests, zero mismatches — and that has
been 0 in every comparison since the `getBiome` fix. Chest existence: 7 of 7 dungeons exactly
identical, with the single candidate confirmed at block level to be a vanilla dungeon.

Scope of the claim: 25 seeds, radius 15, one route pair (rows vs spiral), dedicated server, one pack
version. It does not cover the trigger-timing axis at scale — that was verified on one seed in
results/2026-09-07-roguelike-placement-escape (114/114 with the trigger firing first, midway and
last) — nor cold-launch variance across many seeds.

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

Block-level confirmation of the vanilla dungeon:

```
PROBE_SEARCH=true PROBE_DUMP=-27,-54 \
  scripts/run-probe.sh <server-dir> -8085914287301557748 rows /tmp/vd/probe.json 15
```

Committed: `rows.chests.json` / `spiral.chests.json` (every chest with its item stacks, per seed) and
`seeds.txt`.

Not committed: `vanilla-dungeon-confirmation.dump.txt`, every non-air block in chunk -27,-54 (472 KB,
gitignored as `results/**/*.dump.txt`). Regenerate with the `run-probe.sh` command above plus
`PROBE_DUMP=-27,-54`; the identification it supports — a 7x7 mossy-cobble floor at y=26 with a mob
spawner dead centre at (-427,27,-860) — is recorded above and does not need the raw listing.
