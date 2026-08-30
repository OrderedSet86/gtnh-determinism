# Closing the two-worlds footgun in warm probe runs

GTNH daily-707, fix jar md5 `a1da08af02c2b7e305cfab96f764d30f`, probe md5 `27991bdcf48a229e8cecb0efc71e1b1f`.

## The trap

A warm multi-seed run generates **two** worlds: the server's own boot world at startup, and then the
world for each requested seed. Every worldgen instrument fires in both, and a trace line carried no
clue which world it came from — so every consumer had to know to filter, and one that forgot analysed
two worlds as one.

It had already caused real damage. The Witchery placement trace was **46% boot-world lines**, and that
produced two wrong findings: a non-existent discrepancy in the probe's own structure dump, and inflated
cell and placement counts. Both were published before being caught. The `-Dgtnhdet.chesttrace` corpus
behind `chest-sites.json` had the same contamination and no way to detect it.

## The structural fix that does not work

Stop generating the boot world. In warm mode it is torn down before the first requested seed
generates, so its 625-chunk preload looks like pure waste — `docs/harness-speed.md` §A.3 even lists it
as an optional boot shave. **Measured, it is not free:**

| | chunks differing of 625 |
| --- | ---: |
| boot preload on vs off | **314** (313 blocks-only) |
| control: on vs on | 2 |
| control: off vs off | 4 |

314 against a 2–4 chunk noise floor is a real effect: generating the boot world is load-bearing for
what the measured world comes out as. The mechanism is not established — presumably some static the
reset registry does not cover is left in a different state when nothing has generated yet — and until
it is, enabling this silently changes every result.

It survives as `-Dprobe.skipbootpreload=true`, **off by default**, with that measurement in its
javadoc so the next person does not rediscover it the expensive way. Note that the same caveat now
applies to the boot-shave suggestion in `harness-speed.md` §A.3.

## The fix that does work

Scope the traces instead of changing what generates.

- `TraceScope` in the fix jar reads `gtnhdet.tracescope`. Unset means emit everything, which is right
  for a cold run (one world) and for standalone use. `none` denies everything. A seed emits only for
  that world.
- `warm-probe.sh` launches with `-Dgtnhdet.tracescope=none`, so warm mode is **deny by default** —
  necessary because the boot world generates before the probe can name a seed.
- `recreateWorlds` sets the property to each seed as it begins generating it.

A system property rather than an API call, so neither jar needs a compile-time dependency on the other,
and a stale scope cannot outlive the process.

### It works

Warm run, seed 4329705733811276668, all three instruments enabled:

| instrument | boot-world lines before | after |
| --- | ---: | ---: |
| witchtrace | 579 | **0** |
| chesttrace | 31 | **0** |
| piecetrace | 9 | **0** |

### And it does not touch worldgen

The boot world still generates; only the logging is scoped. Against two unscoped control runs:

```
scoped vs unscoped run A : 2 of 625 chunks differ
scoped vs unscoped run B : 2 of 625 chunks differ
```

Both inside the warm-run noise floor.

## Why this is the better shape

The first instinct was to tag every trace line with `seed=`, which is still done and still useful — it
is what makes a residual visible. But tagging only relocates the burden: it requires every present and
future consumer to remember to filter, and the failure mode when they forget is silent. Deny-by-default
inverts that. A new instrument added tomorrow gets the protection without its author knowing the trap
exists, and the worst case if the scope is somehow wrong is missing lines, which is loud, rather than
extra lines, which is not.

## Also worth recording

Cold runs of one seed are **not** byte-identical: there is a persistent 3–4 chunk floor. Warm runs of
one seed differ by 2–4 chunks. Any A/B on this harness needs a same-setting control before a difference
of that size means anything — the boot-preload result above is only interpretable because of one.
