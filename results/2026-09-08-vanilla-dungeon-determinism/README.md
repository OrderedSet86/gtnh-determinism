# Vanilla dungeon existence: three mechanisms, all live, all fixed

Seed `-1636594104014467454`, GTNH 2.9.0-beta-3 (`~/.cache/gtnh-determinism/beta3`), cold runs,
`level-type=rwg`, centre chunk (9,9). Attempt-level measurements at radius 30, chest-level at radius 60.

Vanilla `WorldGenDungeons` rooms were the last entry in `seedsearch/README.md`'s capability table still
marked **"no — held"**, and the largest unexplained bucket in the radius-60 chest audit (509 of 710
unpredicted chests). This closes it.

## Result

Attempt-level, radius 30, rows vs spiral, same jar, walk order the only variable:

| jar | rows | spiral | existence diffs | of which scan | of which draw-shift |
|---|---:|---:|---:|---:|---:|
| stock siting | 84 | 85 | 7 | 3 | 4 |
| attempt fork only | 74 | 72 | 6 | 6 | 0 |
| **fork + virgin scan** | **69** | **69** | **0** | **0** | **0** |

Chest-level confirmation of the starting state, radius 60, before the fix: **525 vs 535 vanilla dungeon
chests, 60 existence differences** (25 only-rows, 35 only-spiral), Y 5..100.

## The repo's standing assumption was wrong

`results/2026-08-27-inventory-fork-unconditional/README.md:142-144` recorded, and
`seedsearch/README.md:43` and `loot-score.py:61` still assert, that the divergence arrives via draw
shift and therefore:

> "mixing `WorldGenDungeons` onto `TerrainOracle` would very likely not have changed this outcome,
> because the divergence its scan observes is already present in the neighbour chunks."

Measured, the two mechanisms it names are roughly equal — 3 scan against 4 draw-shift — and **each
leaves the other standing** (a third, the room extents, surfaced later; see below). Fixing only the stream took 7 diffs to 6. Fixing only the scan would have left the
four draw-shift cases. Both were needed.

The diagnostic that separates them is logging each attempt's coordinate and verdict
(`-Dgtnhdet.dungeontrace=true`): identical coordinate with differing verdict is the scan; built at a
coordinate the other arm never tried is the draw shift.

### Mechanism 1 — draw shift

RWG runs eight attempts per chunk off the shared populate `Random`
(`rwg.world.ChunkGeneratorRealistic.populate`, after the two `WorldGenLakes` calls):

```java
for (int k1 = 0; k1 < 8 && gen; k1++) {
    int j5  = x + rand.nextInt(16) + 8;
    int k8  = rand.nextInt(128);
    int j11 = y + rand.nextInt(16) + 8;
    gen_dungeons.generate(worldObj, rand, j5, k8, j11);
}
```

`WorldGenLakes.generate` can `return false` on a live world read *before* consuming any draws, so an
upstream block flip changes the shared stream's position and every attempt coordinate moves. Measured:
**361 of ~30,800 attempt coordinates unique to `rows`, 353 unique to `spiral`** — 1.2% of attempts land
somewhere else entirely.

`mixins/worldgen/RwgDungeonAttemptMixin.java` redirects the `generate` call and recomputes the
coordinate from a fork of `(seed, chunkX, chunkZ, salt 40)`, replaying the loop's draw pattern for the
attempt index so attempt *N* is a pure function of the chunk and *N*. The chunk is recovered from the
passed coordinate — stock computes `x = chunkX*16 + nextInt(16) + 8` with the draw in `[0,15]`, so
`(x - 8) >> 4` is `chunkX` whatever the draw was, which is what makes the recovery safe even when the
coordinate arrived already skewed.

The shared `rand` is still passed into `generate`, so populate takes the same number of draws and
nothing else in the chunk moves.

### Mechanism 2 — the scan

`WorldGenDungeons.generate` scans `x±(l+1), y-1..y+4, z±(i1+1)` — a box that crosses chunk borders —
requiring a solid floor and ceiling and an air-opening count in `[1,5]`. Every read is live.

`mixins/worldgen/WorldGenDungeonsScanMixin.java` redirects exactly the three reads that feed the
verdict (the first loop's `getBlock`, and both `isAirBlock` calls, by ordinal) to `TerrainOracle`. The
construction phase afterwards keeps reading and writing the live world, so the room is still built
against the terrain that is actually there; only the decision to build becomes seed-pure.

This is the repo's first mixin on an obfuscated vanilla worldgen class. All three redirects are
`require = 1`.

## Multi-seed: the count is neutral, and one seed exposed a third mechanism

Four seeds, cold, radius 30, centre chunk (0,0), OFF arm with every lever off
(`hilltopvirgin`, `moundvirgin`, `dungeonfork`, `dungeonscan` all `=false`):

**24 seeds, radius 30, warm, restricted to chunks both walk orders populated.**

Determinism: **24 of 24 seeds identical between `rows` and `spiral`**, all three structures. One seed
showed a raw dungeon difference (70 vs 71) which is a window-boundary artifact — the extra room is in
a chunk only the spiral walk populated; restricted to shared chunks it is identical. Both readings are
given because the restriction could otherwise hide a real failure.

Counts, with paired bootstrap CIs and a paired permutation test on the per-seed deltas (the arms share
seeds, so a paired test is the right one; treating them as independent samples would overstate
precision):

| structure | OFF | ON | delta | 95% CI | p | verdict |
|---|---:|---:|---|---|---:|---|
| vanilla dungeons | 2031 | 2006 | -1.2% | **-6.0% .. +3.8%** | 0.65 | **not distinguishable from zero** |
| barrows | 11 | 34 | +209% | +118% .. +300% | 0.0002 | **real** |
| hilltop circles | 22 | 29 | +29% | **-13% .. +69%** | 0.23 | **not resolved** |

**Only the barrow increase is demonstrated.** Its per-seed delta is >= 0 on every seed (range +0..+3),
which is what makes a small absolute change significant. The dungeon per-seed delta has sd 10.46
against a mean of -1.04 — a tenth of a standard error.

Earlier drafts of this file reported the dungeon delta as an effect (-6.5% from four seeds, then
-1.2% from 24) and read the per-seed spread as "individual worlds change materially even though the
mean is flat". Both were over-readings: that spread IS the noise. Which sites pass is resampled by the
change, and per-seed counts carry Poisson-scale variance of about sqrt(85) ~ 9 rooms, which is exactly
the spread observed.

Power, for anyone extending this: 80% power at the observed effect sizes needs roughly **10 seeds for
barrows** (have 24), **105 for hilltop circles**, and **790 for dungeons**. This design cannot resolve
a dungeon count change below about 7%, so "no measurable change" is the honest claim, not "no change".

### The third mechanism: the room extents

Seed `-8622628182362538632` still differed rows-vs-spiral after both fixes, by 2 rooms, entirely
scan-mechanism. A same-order noise floor settled that it was real route-dependence rather than oracle
nondeterminism:

```
rows  vs rows2 (same order) : 79 vs 79, 0 diffs
rows2 vs rows3 (same order) : 79 vs 79, 0 diffs
rows  vs spiral             : 79 vs 79, 2 diffs
```

The cause is two draws at the very top of `generate`, taken from the shared populate rand before any
of the scanning:

```java
int l  = rand.nextInt(2) + 2;
int i1 = rand.nextInt(2) + 2;
```

They are the room's half-extents, and they size the scan box. Pinning the attempt coordinate and
virginising the blocks still left the *box* moving with the stream, and the verdict moved with it. So
vanilla dungeon existence had **three** inputs to fix, not two: attempt coordinate, scan terrain, and
room extents.

`WorldGenDungeonsScanMixin` now answers those two draws from a position-derived fork
(`nextInt` ordinals 0 and 1). Everything `generate` draws afterwards — the spawner's mob, the chest
roll counts — stays on the shared stream, so populate's draw count is unchanged and nothing downstream
moves.

## Single-seed detail: 84/85 -> 69 on the original seed

An earlier draft explained this as a systematic sign difference — hilltop's predicate wants solid
ground so live spuriously rejects, the dungeon predicate wants air openings so live spuriously accepts.
The multi-seed data does not support it: dungeons rise on two of four seeds and hilltop circles *fall*
on one. There is no systematic bias in either direction. Live terrain is not consistently more or less
permissive, only different, and the per-seed sign is whatever that seed's neighbourhood happened to
contain.

On this one seed the drop is 18%. Across four seeds it is +1 of 321 — see the multi-seed section
above. The single-seed figure is retained only to show the per-seed spread, not as an effect size.

## Reproduce

```sh
SEED=-1636594104014467454
JAVA=$(ls -d ~/.gradle/jdks/*/bin/java | head -1)
for ord in rows spiral; do
  PROBE_SEARCH=false PROBE_NOHASH=true PROBE_CX=9 PROBE_CZ=9 PROBE_PORT=25571 PROBE_JAVA=$JAVA \
    PROBE_EXTRA_ARGS="-Dgtnhdet.dungeontrace=true" \
    ./scripts/run-probe.sh ~/.cache/gtnh-determinism/beta3 $SEED $ord <out>/$ord.json 30
done
```

Levers: `-Dgtnhdet.dungeonfork=false` restores stock attempt coordinates. `-Dgtnhdet.dungeontrace=true`
logs `[dungeonattempt] x= y= z= built=`.

## A trap worth recording

`RwgDungeonAttemptMixin` first targeted `method = "populate"` and **bound nothing without erroring** —
the run completed, logged zero attempts, and looked like "no dungeons here". RWG's
`ChunkGeneratorRealistic` overrides `IChunkProvider.populate`, so at production runtime the method
carries its SRG name `func_73153_a`. The chest trace had already been printing
`caller=rwg.world.ChunkGeneratorRealistic.func_73153_a` for hours.

This is the same silent-non-binding failure `LateMixinLoader` documents for the GT ore mixin
("`require = 0`, so it applied, bound nothing, and left ore veins route-dependent with no log line").
Both target names are listed now, and the scan mixin is `require = 1`.

## Open

- **Count effect at 24 seeds**: only the barrow increase is statistically demonstrated
  (+209%, CI +118..+300%, p=0.0002). Dungeons (-1.2%, CI -6.0..+3.8%) and hilltop circles
  (+29%, CI -13..+69%) are not distinguishable from zero at n=24. A balance decision is only
  needed for barrows unless someone runs ~105 seeds (hilltop) or ~790 (dungeons).
- **`seedsearch/README.md:43` and `loot-score.py:61` are now stale** — they still describe dungeon
  existence as held pending the GT ore live-terrain read. The GT dependency was never the whole story:
  the fix here does not touch GT ore, and it reaches 0 diffs.
- **The chest-level radius-60 figure has not been re-measured** with the fix in. Only the attempt-level
  radius-30 numbers above are post-fix.
- **`WorldGenLakes` itself is untouched.** Its variable draw consumption still shifts everything
  downstream of it in the populate stream — ores and decoration — it simply no longer reaches the
  dungeon coordinates. That is a separate, larger finding.
