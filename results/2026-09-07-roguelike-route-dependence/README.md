# Roguelike dungeons are still strongly route-dependent — F5's residual is far bigger than recorded

> **SUPERSEDED 2026-09-07 by `results/2026-09-07-roguelike-placement-escape`.** The measurement below
> is sound but its *attribution* is wrong in two ways, both corrected there:
>
> - It blames the **cross-population write race**. It is not the write race. A slice trace shows
>   `Dungeon.generate` entered at (-622,835) under rows and (-623,749) under spiral — the dungeon's
>   construction ORIGIN differs, decided in `generateNear` before any block is written. The
>   `PendingSlices` write path is exonerated.
> - It frames the variable as **walk order**. It is not walk order. Two `rows` walks differing only
>   in centre disagree, and two *different* orders with the trigger off-centre agree exactly on every
>   roguelike chest. The variable is how much of the dungeon's neighbourhood is already populated
>   when its trigger chunk fires.
>
> Also settled there: it is **not** a frontier artifact (r10 and r24 give identical dungeons), and
> only the empty-neighbourhood build matches the seed-pure prefilter oracle at 114/114 chests.

**THE ORIGINAL REPORT DID NOT REPRODUCE.** It was "the double alumite plate chest at
`/tp -711 21 776` on seed `-1636594104014467454` does not exist on beta 3". In the reporter's own
beta-3 singleplayer save the chest **is present**, 20 stacks, with `TConstruct:heavyPlate` meta 15
in slots 22 and 26 exactly as predicted. Whatever they hit was transient — most likely the
documented trigger-chunk trap (`seedsearch/loot-csv.py`), a session where that chunk had generated
without the dungeon. Nothing in this page explains that report; do not cite it as the cause.

**What chasing it did find is a separate and larger defect.** On one dedicated server, one seed, one
jar, one pack, changing ONLY the chunk walk order moves 206 of 216 dungeon tile entities. A rows
walk builds 47 chests / 47 spawners in the box; a spiral walk builds 104 / 112. `docs/HANDOFF.md`
records F5's open residual as "~2-3 deep rooms per dungeon" — the measured residual is most of the
dungeon.

Both facts matter and they are independent: normal play (spiral-like approach) reliably produces the
predicted dungeon, which is why singleplayer always works; but the generation is not route-invariant,
and a fixed-route harness sweeping in scanlines produces a materially different dungeon.

## The claim under test

`loot-1636594104014467454.csv` row 2-3: a roguelike chest at block (-711, 20, 776), `structure_tp`
`/tp -664 100 840`, carrying two `TConstruct:heavyPlate` meta 15 (Alumite Large Plate) in slots 22
and 26. `predicted=yes`.

## Result

Same seed, same pack (daily-707), same fix jar, same probe, same trigger-centred r10 walk
(`PROBE_CX=-42 PROBE_CZ=52`, i.e. centred on the dungeon's trigger chunk). Tile entities counted in
the box x[-780..-600] z[720..900]:

| arm | chests | spawners | chest at (-711,20,776) |
| --- | ---: | ---: | --- |
| **rows** walk | 47 | 47 | **absent** (chunk populated, 0 TEs) |
| **spiral** walk | 104 | 112 | **present, 20 stacks** |
| singleplayer, daily-707 instance (jar v0.8-main.3) | 102 | 112 | present, 20 stacks |
| singleplayer, beta-3 instance (jar `0.9`, md5 `b6ed85f7…`) | 105 | 113 | present, 20 stacks |

Both singleplayer instances land with the spiral arm, not the rows arm. The 102/104/105 spread is
the player having opened the world and looted, not a generation difference.

The beta-3 save's chest differs from daily-707's in exactly one respect: slot 7 holds item id
`10075` rather than `10074`. Item ids are per-world registry allocations, so that is a pack registry
difference, not a content difference — every other slot, including both alumite plates, is identical.

On the spiral arm the chest's contents match the prediction exactly, including
`4508 dmg=15 x1` in **slot 22** and **slot 26** — the two alumite plates.

Overlap between the two dedicated-server arms:

```
rows   :  94 dungeon TEs
spiral : 216 dungeon TEs
in both:  10
```

Those 10 are vanilla dungeon spawner+chest pairs at (-674,17,·), (-702,25,877), (-754,38,834) and
(-707,47,749) — i.e. *nothing* of the roguelike dungeon survives the change of walk order.

## What it is not

Each of these was tested, not assumed:

| hypothesis | test | verdict |
| --- | --- | --- |
| beta-3 regression | same trigger-centred rows walk on beta-3 and daily-707 | **identical**: 249 chests / 249 spawners at identical coordinates in both. Not beta-3. |
| fix-jar regression | daily-707 dedicated, rows, with v0.7 (`347c27a174`) vs v0.8-main.3 | chest absent under **both**. Not the jar. |
| singleplayer vs dedicated | both SP saves vs dedicated spiral | SP 102/112 and 105/113, spiral 104/112, all three contain the chest. **The spiral arm reproduces singleplayer.** Not SP-vs-MP. |
| the report itself | the reporter's own beta-3 SP save, generated with the `0.9` jar | **chest present with the predicted contents.** The report does not reproduce in the world it came from. |

The residual 102 vs 104 between SP and the spiral arm is the player having opened the world and
looted, not a generation difference.

## Why spiral matches a real player and rows does not

A player arriving at a dungeon loads chunks radiating outward from where they stand, which is what
`spiral` approximates. `rows` sweeps in scanlines, so the dungeon's trigger chunk populates at a
different point relative to its neighbours. Roguelike builds the entire dungeon when its trigger
chunk populates and writes far outside that chunk, so the surrounding chunks' own population either
happens before those writes (they survive) or after (they are overwritten). That is the write race
`docs/HANDOFF.md` already names under F5 — the finding here is only its size.

This also means **every fixed-route harness in this repo under-measures it**: a probe walk is one
route, and the rows-vs-spiral comparison is the only thing that exposes the difference. The
`validOreveins`-style metric has no analogue for dungeons.

## Consequences for the loot corpus

`loot-1636594104014467454.csv` and anything derived from it (the routemap loot layer, seed scoring)
describe the dungeon that a **spiral-like** approach produces. That matches singleplayer play, so
the predictions are usable in practice — but they are not route-invariant, and a differently-ordered
approach can produce a materially different dungeon. The CSV should not be described as a
prediction of "the" dungeon until F5's write race is closed.

## Reproducing

```
SEED=-1636594104014467454
# trigger chunk of the dungeon in question: block (-664,840) -> chunk (-42,52)
PROBE_CX=-42 PROBE_CZ=52 PROBE_SEARCH=false \
  scripts/run-probe.sh <server-dir> $SEED rows   /tmp/trig-rows.json   10
PROBE_CX=-42 PROBE_CZ=52 PROBE_SEARCH=false \
  scripts/run-probe.sh <server-dir> $SEED spiral /tmp/trig-spiral.json 10

python3 boxscan.py <world> -780 -600 720 900     # dungeon TEs in the box
python3 chestat.py <world> -711,20,776           # the chest and its contents
```

r10 around the trigger is enough — 441 chunks nominal, ~1200 after the dungeon's own cascade, about
a minute per arm. A radius-60 sweep is 14,641 chunks and tells you nothing extra.

Committed: `box-rows-dedicated.txt`, `box-spiral-dedicated.txt`, `box-singleplayer.txt`, and the two
inspection scripts.
