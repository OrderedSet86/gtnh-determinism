# Witchery placement trace: the structures are route-stable

GTNH daily-707, fix jar md5 `1ecf783b97ef47f4d005db18ed32a1a1`. Warm probe, radius 12, rows vs spiral,
3 seeds.

```sh
PROBE_SEARCH=true PROBE_JVMFLAGS="-Dgtnhdet.witchtrace=true" \
  scripts/warm-probe.sh <server-dir> <seed> rows out.json 12
```

`-Dgtnhdet.witchtrace=true` emits one line per Witchery gate cell:

```
[witchtrace] seed=4329705733811276668 cell=176,160 biome=230 order=[WorldHandlerWickerMan ...]
  tried=[WorldHandlerWickerMan:outofrange:no ...] winner=WorldHandlerCoven predorder=[...]
```

— the cell, the biome verdict, the shuffled handler order, the outcome of every handler tried, and the
winner. This exists because [the previous
attempt](../2026-08-29-witchery-route-stability/README.md) established that a persisted-world block
diff **cannot** answer the question: Witchery builds from vanilla blocks, so an id filter sees almost
nothing, and a window around a site is indistinguishable from the endemic decoration residual.

## Result: identical on every arm

| seed | cells | first-visit verdicts differing | (cell,verdict) multiset identical | placements | identical |
| --- | ---: | ---: | :---: | ---: | :---: |
| 4329705733811276668 | 678 | **0** | yes | 3 / 3 | yes |
| 3007102813260058640 | 679 | **0** | yes | 2 / 2 | yes |
| -8802246784027196783 | 685 | **0** | yes | 1 / 1 | yes |

All 6 placements across the 3 seeds agree in cell **and** structure type:

```
4329705733811276668  Shack(-192,144)  Shack(-144,-192)  Coven(176,160)
3007102813260058640  Coven(160,-208)  Shack(176,96)
-8802246784027196783 Coven(128,64)
```

**These counts are the corrected ones.** The first version of this measurement reported 836/935/864
cells and 9 placements — see the correction below.

Cells are re-visited within a run and a re-visit legitimately changes
the verdict — the second pass finds the structure already there and the range check refuses. So the
comparison is done three ways rather than on last-write-wins: first-visit verdict per cell, the full
multiset of (cell, verdict) pairs, and the placement list. All three agree.

**Conclusion: the planned step 2 — redirecting Witchery's ground and clearance reads to
`TerrainOracle` — is not needed.** Nothing in the placement decision differs by route on these seeds.

## The instrument was verified before it was believed

The trace required restructuring `gtnhdet$generateOverworld` from one compound condition into named
steps, which is live worldgen code. Control flow is provably unchanged — `nonInRange` is still
evaluated first and still short-circuits `generate()` — but "provably" has not been a good enough
standard today, so it was measured.

The first check looked alarming: **4 of 961 chunks differed** between the old and new jar on the same
seed and route. The control settles it — two cold runs of the *same* jar also differ:

| pair | differing chunks |
| --- | --- |
| old body vs old body (control) | 4: `-4,4  7,6  8,5  12,8` |
| new body vs new body (control) | 3: `7,6  8,5  12,8` |
| old body vs new body | 3–4, always a subset of the same set |

Every differing chunk, `-4,4` included, appears in a same-jar control pair. This is the cold-run noise
floor, not the restructure. Worth recording on its own: **cold runs of this seed are not byte-identical
— there is a persistent 3–4 chunk floor** at `{-4,4; 7,6; 8,5; 12,8}`.

## Correction: the trace was measuring two worlds at once

The first version of this measurement reported a discrepancy — the probe's `witchery` dump appearing to
report **one fewer** structure than the trace, always at `(80,64)` — and speculated about the warm
harness's static reset. **That was wrong, and the fault was in the trace, not the probe.**

A warm-probe run generates **two** worlds: the server's own boot world, and then the requested seed's.
Witchery generates in both, and the trace had no way to tell them apart. Tagging every line with
`world.getSeed()` shows the split immediately:

```
579 [witchtrace] seed=1                     <- the server's boot world
678 [witchtrace] seed=4329705733811276668   <- the requested seed
```

**46% of the original corpus was boot-world contamination**, and `(80,64)` is a boot-world placement —
which is why it appeared at the identical coordinate in all three "seeds" and why the probe's dump
correctly excluded it. Filtered to the requested seed, the dump and the trace agree exactly:

| seed | probe dump | trace placements | agree |
| --- | ---: | ---: | :---: |
| 4329705733811276668 | 3 | 3 | yes |
| 3007102813260058640 | 2 | 2 | yes |
| -8802246784027196783 | 1 | 1 | yes |

The route-stability conclusion survives — both arms contained the same boot-world prefix, so it was
always a like-for-like comparison — but the cell and placement counts above are the corrected ones.

What exposed it: the stage-0 module predicted a different handler order than the trace, while an
independent Python emulation agreed with the *module*. Printing the chunk seed on both sides showed
them disagreeing on `world.getSeed()`, which only makes sense if two worlds are in play.

## Correction: there is no structuresList order-dependence

The first version of this write-up flagged a residual risk — that `nonInRange` consults
`structuresList`, making cell verdicts depend on what was placed earlier in the run. **Disassembling
the class shows that is false.** `structuresList` is touched in exactly three places: the constructor,
an `add` after a successful placement, and `clear()` in `initiate()`. It is **never read** for gating.

`nonInRange` is the vanilla scattered-feature region formula and reads no world state at all — see
[the stage-0 module](../2026-08-29-witchery-prefilter/README.md). That is *why* placement is perfectly
route-stable, and it means there is no order-dependence surface here to worry about.

3 seeds is still not coverage, and widening the corpus remains worthwhile.

## Files

- `witchtrace.txt` — raw trace lines, 3 seeds × 2 route arms. **Every line carries `seed=`; filter on
  it.** Lines with `seed=1` are the server's boot world and must be excluded from any per-seed
  analysis.
