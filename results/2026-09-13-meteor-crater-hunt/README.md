# Meteor crater hunt — biggest AE2 crater by predicted carved volume

Stage-0 seed search for the largest possible AE2 meteorite crater: near-max size roll landing next
to tall terrain, ranked by how many blocks the crater paraboloid (`j > h + 0.02·d²`,
`h = y − size + 1 + adjustCrater`) deletes from virgin terrain. New `MeteorPrefilter` module,
`-Dprobe.prefilter.meteor=C`.

## Versions

- Pack: GTNH **2.9.0-beta-3** (`~/.cache/gtnh-determinism/beta3*`), AE2 rv3-beta-**1050**-GTNH,
  RWG-alpha-**1.5.2**. Cross-checked on 2.8.4 (AE2 rv3-beta-695, RWG 1.5.0): the golden seed's
  prefilter rows and probe verdicts are identical on both.
- probe jar: `worldgenprobe-v0.8-main.9+970e4eafdf-dirty.jar` md5 `f3007ec0ed7ed1a456d3107efbfdf818`
- fix jar: `gtnhdeterminism-v0.8-main.9+970e4eafdf-dirty.jar` md5 `108ec34f184694941452620f5b0a1a57`
  (in every server dir used here; the prefilter models FIX-JAR siting — see Accuracy)

## Commands

```bash
# sweep (4 shards, 50k seeds each, kill bar 40k carved blocks)
for i in 1 2 3 4; do
  PREFILTER_SERVER=~/.cache/gtnh-determinism/beta3-pf${i#1} PROBE_PORT=$((25800+i)) \
  PREFILTER_RADIUS=0 PREFILTER_TERRAIN=-1 PREFILTER_PIECES=false PREFILTER_METEOR=2 \
  PROBE_JVMFLAGS="-Dprobe.prefilter.gate.meteorvol=40000" \
  ./scripts/prefilter.sh random:50000:$((20260913+i)) sweep/shard$i.jsonl &
done

# rank
seedsearch/meteor-hunt.py sweep/shard*.jsonl --top 10

# golden / multiseed verification (warm r30 probe, meteortrace, drainticks default-on)
PROBE_SEARCH=true PROBE_JVMFLAGS="-Dgtnhdet.meteortrace=true" \
  ./scripts/warm-probe.sh ~/.cache/gtnh-determinism/beta3 @ms25-seeds.txt rows 'ms25b3/probe-{seed}.json' 30
results/2026-09-13-meteor-crater-hunt/compare-ms25.py ms25b3.log ms25-prefilter-b3.jsonl
```

## What it does

Per grid cell (707 blocks, `minMeteoriteDistance`) in ±C cells of the origin cell:

1. RNG replica through the real `Platform.seedFromGrid` — spawn-chance roll (picks start depth
   180+n vs 128, does NOT gate existence), position, `meteorSeed`, size = first placer draw.
2. Size gate (default 7.8; ~1 cell in 30 survives).
3. `tryMeteorite` descent replayed for real: each −15 rung's center block and both
   `spawnMeteoriteCenter` box scans against the placer's real `validSpawn`/`invalidSpawn` sets,
   through virgin `world.getBlock` — the same reads `Ae2MeteoriteSitingMixin` gives the game.
4. Crater proxy: burial ≤ 10 and solid virgin column below (skyMode stand-in).
5. Relief gate (default 20), then carved-volume integral over a 96-block disc.

Throughput: 48 ms/seed at C=2 with default gates (19 seeds/s per shard; the meteor stage is ~96%
of wall when it is the only module on).

## Accuracy

- Golden seed `-1636594104014467454`, r60 probe, meteortrace, both packs: existence **6/6**,
  landing y **5/5 exact**, size exact to all printed digits, crater verdict **5/6**.
- The single miss is one-way: at (1084,363) populate carves a lake (surface 97 → 88) and the
  in-game crater fires at skyMode 72 where virgin terrain says buried. The prefilter can MISS a
  crater that exists; the golden and multiseed runs found no FALSE crater. skyMode reads live
  terrain and is not covered by the siting mixin — irreducible at stage 0.
- Multiseed (warm r30, beta3; 23 of 25 seeds completed before the warm JVM ran out of heap at the
  default 6G — the missing 2 are absence-of-data, not failures): 14 in-window candidates over 11
  log-parsed seeds, existence **14/14**, landing y **10/10**, size **10/10**, crater verdict 7
  match + **3 missed in the known direction, 0 false**. See compare-ms25-output.txt.
- **The prediction assumes the fix jar.** Stock AE2 sits meteorites off decorated live terrain: a
  tree or pond on a rung moves the landing 15 deeper and typically buries the meteorite. Measured
  directly on seed `-9013805810112680516`: prefilter and fix-jar probe agree on a size-7.886
  surface crater at (-363,83,608) — user-reported stock-world visit found no surface trace.

## Hunt result (200k seeds, beta3) — top 5 ALL VERIFIED

4,963 scored craters ≥ 40k volume. Top hits, each confirmed by a targeted warm probe
(probe-queue daemon, per-job cx/cz, meteortrace): position exact, landing y exact, size exact to
all printed digits, crater carved in-game, TileSkyChest present (`top-verify.txt`).

| seed | impact | y | size | volume | maxbite | verified |
|---|---|---|---|---|---|---|
| 4717997228783405311 | -491,1281 | 88 | 7.923 | 301,197 | 73 | crater, skyMode 49 |
| 1679783095927474686 | -1094,989 | 77 | 7.833 | 291,890 | 82 | crater, skyMode 463 |
| 4857082301537480989 | 1930,549 | 105 | 7.911 | 288,248 | 106 | crater, skyMode 1823 |
| -5198606018767845758 | 1739,-143 | 83 | 7.854 | 278,983 | 88 | crater, skyMode 587 |
| 2302133856128621797 | -534,990 | 113 | 7.866 | 270,502 | 97 | crater, skyMode 2602, **lava floor** |

The #1 hit sits at skyMode 49 — real margin above the >10 crater gate, but the closest of the
five; 0.13.0's warning about near-threshold skyMode applies if anyone re-rolls this on a
different pack version.

## Sweep 2 (90k seeds): enclosure metric, size gate dropped to 6.5

Motivated by a user observation that every round-1 hit sat on a mountain EDGE: the paraboloid
carves per column, so azimuthal coverage should multiply volume, and the 7.8 size gate was the
binding constraint (size moves the crater floor by <= 6 blocks; relief moves the integrand by
100+). Jar changes: 1 -> 3x3 -> 5x5 staged relief peek (flatland pays one chunkgen, was the
0.5 seeds/s bottleneck; 3 seeds/s after), `enclosure` = of 16 compass sectors over the
24..volradius ring, how many reach surface+30. Gates: meteorsize=6.5, meteorrelief=25,
meteorvol=60000, salts 978-983. `survivors2-90k-enclosure.jsonl`, 43,029 scored candidates.

Winner (verified, main.9 daemon, exact landing + size): seed **-1877890734832540703**, impact
(1284,1935), size 6.508, y=98, relief 121, **403,971 carved**, maxbite 95, enclosure 7/16 —
a size roll round 1 would have gated out. Runner-up seed -1447171531626775250 (279,-1006),
size 7.984, 327,052, verified.

Enclosure verdict, honestly: full bowls exist (best 15/16, seed -1463797299086795660,
(1898,1515), 175,707) but top out at half the amphitheater volumes — RWG builds 100+ relief as
one-sided massifs, never as full 96-block rings. Coverage lost to wall height. The metric still
earns its place as a ranking axis and as the reason the size gate dropped.

## RWG structural ceiling

See `rwg-terrain-analysis.md`: 600-800k carve is structurally reachable (tallest mountain biomes
against river Voronoi edges / 3-way vertices, f<=~0.5), full-ring basins cap at ~176k (already
found), seven-figure carves do not exist in this generator.

## Sweep 4 (1.3M seeds, analytic screen) — the ceiling is empirical now

The analytic noise screen (`AnalyticScreen`: the biome's own `rNoise` + `getRiverStrength` +
`calculateRiver` at impact + 48 ring samples, reflected off the real generator, no chunkgen)
took throughput from 4.5 to ~36 seeds/s/shard. Calibration on the 7 known 300k+ hits was
perfectly separable: hits proxy 107-197, flats <= 59; gate set at 80
(`-Dprobe.prefilter.gate.meteorproxy=80`).

1.3M seeds evaluated (stopped by hand to free compute; salts 7701-7708, 165k/250k per shard).
Best: 386,785 (seed -976606973961262472, (-326,-1127), relief 135, lava). 20 sites >= 300k in
`survivors4-1.3M-partial.jsonl` (252 survivor rows).

**Across ~1.55M seeds total, no site beat the 404k champion and observed in-disc relief never
exceeded ~137**, against the source-analysis ceiling of ~158 sustained. Conclusion: the ~64-block
biome-blend kernel shaves the clamp walls below their single-biome height before they fit inside
a meteor disc, and **~400k is the practical carve ceiling on this pack**. The 500-750k estimates
in rwg-terrain-analysis.md assumed unblended walls; treat them as upper bounds that 1.55M seeds
of evidence say are not realized.

## The two funnel biases, and the 542k champion (2026-09-14)

Every sweep before the standalone volume ranker carried TWO structural false-negative biases,
found only when the game-free pipeline (seedsearch/rwg-analytic: exact blended-height port of
getNewNoise, calibrated 7/7 within 1 block, volume rank-order 7/7) surfaced candidates the game
funnel rejected:

1. **The staged relief peek reached ~70 blocks; the carve disc reaches 96.** Sites whose walls sit
   at 70-96 blocks were rejected "flat" before scoring — including a 400,167 site 274 blocks from
   origin. Worse, the staging cuts were hardcoded below the gate parameter, so even
   gate.meteorrelief=0 could not see them (fixed: cuts scale with the gate).
2. **The crater gate tested burial at the CENTER column; skyMode reads a +-15 box.** A cliff-foot
   impact with center burial 14 carves fine — the downhill half of the box is open sky. Verified
   in-game by the user on seed 5550213969531321496 before the model caught up. Fixed slope-aware
   in both layers: crater iff landing+10 clears the MIN surface within +-15.

With both fixed, ONE MILLION standalone-ranked seeds produced:

| seed | impact | landing | size | volume | verified |
|---|---|---|---|---|---|
| **5550213969531321496** | **(-376,186)** | y=64, burial 14 | 6.617 | **542,961** | daemon: skyMode 144, crater; in-game screenshot |
| 5119431112741940146 | (237,2016) | y=67, burial 13 | 6.521 | 469,899 | daemon: skyMode 118, crater |
| -7317414447129846878 | (-207,-174) | y=98, burial 4 | 7.370 | 400,167 | daemon: skyMode 4033, crater |
| 6976376896141785906 | (-975,1034) | y=83, burial 7 | 7.825 | 371,181 | daemon: skyMode 2615, crater |

The old champion (403,971, found through the biased funnel over 3.5M seeds) is now #3. Prior
"ceiling" claims in this file are superseded: they measured the funnel, not the terrain. skyMode
144 on the new champion is real margin but the thinnest of the verified set — the burial-14 +
slope geometry lives closer to the gate than bench sites do.

## Field note: populate lakes veto hollow-class candidates, invisibly to every model layer

Field-checked (2026-09-14): bridge-zone candidate seed -3725569335742155824 (183,1645), 12/16 at
~306k predicted, burial 13 — no meteorite in-game. An elevated pond (~y=100) sits at the impact:
a POPULATE-era lake, so it exists in no pre-populate layer (standalone blended terrain, game
prefilter virgin chunks, even the fix jar's siting oracle — virginity is the determinism design).
AE2's tick-time descent sees the water in the 30-cube invalidSpawn box and rejects every rung.
Closed hollows collect populate lakes, so the high-enclosure deep-center class fails stage 3 at an
elevated rate. One-way (false positive); daemon or field check is the only filter. Note these
lakes are themselves route-dependent (0.13.0 known limit), so this veto is not even stably
predictable in principle.

## Field note: burial 10 is the knife edge

Hand-checked in-game (fix jar, beta3) across the sweep-2 top list and the full enclosure>=12
list: every crater present EXCEPT seed -1463797299086795660 (1898,1515) — predicted burial 10,
i.e. y+10 == surface exactly. In-game: decay/fallout present (cobble + sky stone scatter on the
basin floor), no carve — live skyMode landed in 4..10. That is the first observed FALSE crater
and it is confined to the boundary case; burial <= 9 verified fine by the same hand check. Rank
with burial <= 9; treat 10 as decay-only. (The 403,971 champion is burial 10 by prediction but
verified carving at skyMode 3647 — the boundary case is sensitive to local sky, not doomed.)

## Field note: the jar in the CLIENT decides

Both "nothing at those coords" reports from in-game checks were worlds running gtnh-determinism
**0.12.0**, which predates the whole meteor chain (0.13.0 drainticks/WorldData/siting analysis,
main.9 virgin siting). Every such report should start with the client jar version; the model was
exact on every probe comparison this whole hunt (existence 16/16, landing 12/12, size 12/12
across golden + multiseed + two verification rounds).
