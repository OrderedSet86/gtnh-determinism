# Vanilla dungeon chest placement: the fourth unpinned input

Seed set `results/2026-09-07-roguelike-multiseed/seeds.txt` (25 seeds), GTNH 2.9.0-beta-3, warm,
radius 30, `rows` vs `spiral`. Single-seed lever checks on `-1636594104014467454` at radius 20.

`results/2026-09-08-vanilla-dungeon-determinism/README.md` fixed three inputs to vanilla dungeon
determinism — the attempt coordinate, the scan terrain, and the room half-extents — and measured room
existence at 0 differences. That result stands. What it did not measure is where the chests land
*inside* a room, and that was still route-dependent.

## The mistake this corrects

A chest-level diff of the same worlds showed dungeon chests differing on 24 of 25 seeds, which read
as "the dungeon fix is broken". It was not: a position-keyed chest diff cannot tell a room that moved
from a room that stayed and put its chests somewhere else, and vanilla places **up to two** chests per
room with three tries each, so an identical room can legitimately yield 0, 1 or 2 chests.

Separating the two with `scripts/diff-dungeons.py` (new, committed — the earlier attempt-level result
came from an ad-hoc script that was never in the repo, which is why the disagreement could not be
audited):

| | rows | spiral |
|---|---:|---:|
| rooms built (`built=true` anchors) | 2082 | 2082 |
| room existence differences | \_ | **0** |
| rooms in BOTH arms whose chests differ | \_ | **37** (94 chest positions) |

So room existence was already clean and the defect was entirely in placement. (An earlier draft
reported 1 existence difference; that room was in a chunk only the spiral walk reached, and the tool
failed to exclude it because it recovered the chunk as `x >> 4` instead of `(x - 8) >> 4`.)

## The defect

```java
for (int k1 = 0; k1 < 2; ++k1)                        // two chest slots
    for (int l1 = 0; l1 < 3; ++l1) {
        i2 = x + rand.nextInt(l * 2 + 1) - l;         // shared populate stream
        j2 = z + rand.nextInt(i1 * 2 + 1) - i1;
        if (world.isAirBlock(i2, y, j2)) { ...exactly one solid neighbour... setBlock(chest); break; }
    }
```

Both offsets come off the shared populate `Random`, whose position on arrival depends on how many
mossy-cobblestone rolls the wall loop took — one per solid block it found, so a function of the
surrounding terrain. The effect also compounds: a room only reaches this loop when it is built, and
each built room consumes a variable number of shared draws here, so later attempts in the same chunk
shift too.

### The ordinal map, which is where this is easy to get wrong

`@At(ordinal = N)` counts **call sites in bytecode**, not runtime invocations, so the loop's two draws
are one ordinal each — and they are not 2 and 3, because the wall loop has a `nextInt` between them:

| `Random.nextInt` ordinal | call | status |
|---:|---|---|
| 0, 1 | `nextInt(2)` room half-extents | pinned before this change |
| 2 | `nextInt(4)` mossy cobblestone, wall loop | **left alone** |
| **3** | `nextInt(l * 2 + 1)` chest X | **pinned here** |
| **4** | `nextInt(i1 * 2 + 1)` chest Z | **pinned here** |

Each draw is answered by `splitmix(worldSeed, roomX/Y/Z, drawIndex)` rather than by successive values
of one stream, because the loop breaks early and is reached a variable number of times; an independent
function of (room, n) does not care.

The draw counter is keyed per room anchor in a `ThreadLocal<HashMap>` and reset at `generate` HEAD.
Not a single slot: the wall loop's `setBlock` crosses chunk borders and can trigger a neighbour's
population, and therefore a nested `generate` on the same thread, before the outer room reaches its
chest loop. That is the third time this repo has needed the keyed form — `RwgDungeonAttemptMixin` and
`ChestFillContext.gtnhdet$fillIndex` both shipped the single-slot version first.

## Result

25 seeds, warm r30, `rows` vs `spiral`. Two changes, measured separately:

| | before | pin chest draws | + virginise construction |
|---|---:|---:|---:|
| rooms with differing chests | 37 | 9 | **0** |
| chest positions | 94 | 18 | **0** |
| rooms yielding a different chest COUNT | 20 | 4 | **0** |
| room existence differences | 0 | 0 | 0 |

**Vanilla dungeon rooms and chest placement are both route-independent: 0 differences over 25 seeds
and 2082 rooms.** Chests still differ IN THE WORLD, for a reason outside this code.

`diff-dungeons.py` measures what the generator *filled*; the probe's search report measures what the
world *holds*. Those diverge when a chest is filled and then destroyed:

```
chest (-348, 23, 201), seed -1636594104014467454
  rows   : filled-by-generator=True   present-in-final-world=True
  spiral : filled-by-generator=True   present-in-final-world=False
```

That chest sits within 5 blocks of two room anchors, `(-347,23,199)` and `(-349,23,204)`. When rooms
overlap, the second one's interior carve is an unconditional `setBlockToAir` over cells the first
already used — including its chest — and which room runs second depends on chunk population order.
Nothing here touches that: the carve has no read to virginise.

Final-world chest existence differences over 25 seeds, attributed by the trace's `caller=` field
(shared chunks only, Y-shift pairs separated):

| generator | before | + chest-draw pin | + virgin construction |
|---|---:|---:|---:|
| **vanilla dungeon** | 92 | 25 | **13** |
| Thaumcraft greatwood tree | 31 | 33 | **32** |
| ComponentToolWorkshop | 2 | 2 | 2 |
| ComponentShack / WizardTower | 1 | 2 | 2 |
| untraced | 2 | 2 | 2 |
| **total** | **128** | 64 | **51** |

So the dungeon work took its own class 92 -> 13, and the largest remaining class is not dungeons at
all — see "Greatwood trees" below.

### The second change: virginising room construction

Pinning the draws left 9 rooms. Candidates were by then a pure function of (room, draw index) and so
identical in both arms, which meant the residual could only be the acceptance test — and its
adjacent-solid count reads walls that `WorldGenDungeons` places only where the block was *already*
solid on the live world. `getBlock` ordinals 1 and 2 (the construction phase's "solid floor below"
and "solid here" tests) now answer from `TerrainOracle`. Lever `-Dgtnhdet.dungeonbuild=false`.

That is safe to virginise because neither read ever touches a cell the loop has already written: the
loop runs `k1` outermost, `l1` **descending**, `i2` innermost, so the current cell is untouched and
`l1 - 1` is processed later. Both see the pre-room world either way.

Balance, 25 seeds, `rows` arm: **3409 -> 3422 dungeon chests** (+13, +0.4%), 33 chest positions
moved. At radius 20 on one seed the lever changed nothing at all — population mostly swaps solid for
solid (ore replaces stone; both are `isSolid()`), so virgin and live usually agree on the only
property these reads ask about. The change bites exactly in the rare cases that were causing the
residual.

### Lever validation

`-Dgtnhdet.dungeonchest=false` against default, same jar, radius 20, one seed:

| | result |
|---|---|
| rooms built | 36 vs 36, **0 existence differences** |
| rooms whose chests moved | 35 of 36 |

Room invariance is the load-bearing check on the ordinals. Redirecting the extents by mistake would
have changed room existence; redirecting the mossy roll would have changed nothing at all. Neither
happened, so ordinals 3 and 4 are the chest draws.

### Balance

Radius 20, 36 rooms: **59 chests against 58**, **409 stacks against 410**. Positions are
redistributed; quantity is not meaningfully changed, which is the property the position-derived
fixes are supposed to have.

## What is deliberately not changed

- **The block reads inside the chest loop.** They inspect the room that was just carved — air
  interior, cobblestone walls — so answering them from `TerrainOracle` would test the terrain as it
  was before the room existed and put chests through walls.
- **Ordinal 2, the mossy-cobblestone roll.** Its draw *count* varies with terrain and does shift the
  stream, but its effect is which of two solid blocks a wall is made of. That is a block-level
  question, not a chest one.

## Overlapping rooms are stock behaviour, not a consequence of the virgin scan

The obvious suspicion was that virginising the scan lets two rooms pass without seeing each other,
where stock's live scan would reject the second because the first room's air changes the wall-ring
opening count. Measured with `-Dgtnhdet.dungeonscan=false`, 25 seeds, both orders:

| scan | rooms | overlapping pairs | per 100 rooms |
|---|---:|---:|---:|
| virgin (shipped), rows | 2082 | 15 | 0.72 |
| virgin (shipped), spiral | 2082 | 15 | 0.72 |
| live (= stock), rows | 2157 | 15 | 0.70 |
| live (= stock), spiral | 2175 | 18 | 0.83 |

**The overlap rate is the same.** Stock's opening-count gate does not meaningfully suppress
overlapping rooms, so virginising the scan did not create this. 15 overlapping pairs against 13
differing chests is close to 1:1, which is the mechanism confirmed.

Two other things fall out of that arm. Virginising the scan costs about **3.5% of dungeon rooms**
(2082 against 2157) — consistent with the -1.2% (CI -6.0..+3.8%) reported in
`results/2026-09-08-vanilla-dungeon-determinism/README.md`, now measured more precisely. And the live
scan is itself route-dependent in room COUNT (2157 rows against 2175 spiral, a spread of 18), which
is the defect that fix addressed.

Consequence for the fix: there is no stock overlap-suppression to restore, and no stock winner to
match either — with the live scan, which of two overlapping rooms survives is decided by walk order,
so stock has a 50/50 rather than an answer. Any *canonical* resolution order is therefore equally
faithful to stock's distribution while being deterministic. What is needed is not suppression but a
seed-pure rule for which room's blocks win, which means enumerating the overlapping neighbour — the
same layout solver a prefilter needs.

## Open

- **Attempt-count divergence**, above. 1 room in 2082, one chunk on one seed.
- **`gtnhdet$forkedExtentX/Z` never consume from the shared `rand`**, so populate takes 2 fewer draws
  per dungeon attempt than stock, directly contradicting the javadoc above them ("the number of draws
  populate takes is unchanged and nothing downstream moves"). The chest redirects added here DO
  consume, so the two are now inconsistent. Deterministic either way; correcting the extents would
  move worldgen against stock and needs its own A/B.
- **Virginising construction does not make the finished room byte-identical.** Where virgin says "not
  solid" the generator places nothing and whatever the live world holds survives in place. Only the
  room's own decisions are seed-pure. Not currently known to matter — chest placement is at 0 — but
  a block-level room diff has not been run.

## Reproduce

```sh
SHARD_DIRS="$HOME/.cache/gtnh-determinism/beta3 $HOME/.cache/gtnh-determinism/beta3-s1 \
  $HOME/.cache/gtnh-determinism/beta3-s2 $HOME/.cache/gtnh-determinism/beta3-s3" \
PROBE_SEARCH=true PROBE_XMX=6G ./scripts/warm-shard.sh \
  results/2026-09-07-roguelike-multiseed/seeds.txt <out>/multi 30 rows,spiral \
  "-Dgtnhdet.chesttrace=true -Dgtnhdet.dungeontrace=true"

python3 scripts/diff-dungeons.py "<out>/multi/rows-shard*.log" "<out>/multi/spiral-shard*.log"
```

`diff-dungeons.py` excludes the warm boot world (`level-seed=1`) by default — it contributed 14 rooms
and a phantom 26th seed on the first run, because `[dungeonattempt]` does not go through `TraceScope`
the way `[chesttrace]` does. Raw data: `~/.cache/gtnh-determinism/dungeon-verify/` (after) and
`~/.cache/gtnh-determinism/dungeon-recon/` (before).
