# Tallest brick house hunt (Roguelike HOUSE tower)

Community claim: brick houses (Roguelike HOUSE tower, forest settings) "sometimes generate on top
of slime islands, and there's so many bricks" — seed-search for the tallest one.

## Part 1: slime-island interaction — real in stock, impossible in the fixed build

Mechanism (source): stock `Tower.getBaseCoord` scans y=128→60 against the LIVE world; slime dirt
(`TConstruct:CraftedSoil`, `Material.ground`) is valid footing. TiC islands float at
`yCenter = 50 + liveHeight + rand(50)`; a body intersecting (terrain, 128] catches the house, and
HouseTower pours a 5×5 brick shaft from y=60 to floor+3, porch columns to y=60, and a recursive
brick step curtain to y=60.

New stage-0 module (`-Dprobe.prefilter.slimehouse=R`) proved the coincidence is predictable:
island rolls are pure (seed, chunk) — FML reseeds per IWorldGenerator — and with
`SlimeIslandGenMixin` the shape draws are too. A 20k sweep found HOUSE entrances under island
ellipses at ~1/1400 seeds (hits in `hits-20k-salt20260912.jsonl`; arithmetic verified: predicted
island chunk (-33,-26) on seed -3778973554573079292 materialized CraftedSoil y120-139 exactly
there in full generation).

**But the phenomenon cannot occur under the determinism jar.** Full-gen verification (two walk
orders, identical results) showed the house generating at ground level under the island, and the
cause is by design: F5's `WorldEditorMixin.getBlock` answers Roguelike reads with own-writes +
VIRGIN terrain, so the tower base scan never sees an island. On stock GTNH the interaction is
real but doubly irreproducible: island altitude is clock-seeded (the F-series SlimeIslandGen bug)
and footing depends on populate order (player route). So "house on slime island" is a
stock-only, route-dependent event — findable in space, not materializable deterministically.

## Part 2: cliff houses — the seed-pure equivalent

Under the fixed build, tower footing is a pure function of virgin terrain, so the tallest brick
house is a HOUSE entrance on a plateau edge over a deep drop:

- entrance floor can reach ~y100 (validLocation needs air at upperLimit=100 at the column);
- porch columns and the `step()` brick curtain descend to y=60 regardless of terrain;
- granite supports `fillDown` to the first opaque block — the valley floor;
- if the surroundings drop below 60, the brick curtain ends dangling in mid-air.

New stage (`-Dprobe.prefilter.cliffhouse=R`): place-only dungeons (settings + entrance, no
construction) for every trigger with FOREST provider-biome within 64 blocks, keep HOUSE towers,
score `drop = (virgin topSolid + 1 at entrance) − min topSolid within ±16 blocks`.
Gate `-Dprobe.prefilter.gate.cliffhouse=D`. ~7.5 HOUSE placements/seed, 5 seeds/s at R=100.
Showcase metric (post-processed): exposed brick face `floor+13 − max(60, min_surf)`, prefer
floor ≥ 90.

## Perf notes

Slimehouse/cliffhouse both ride on `RoguelikePrefilter` place-only mode (new): capture resolved
settings + entrance from `Dungeon.generate`'s arguments and skip construction — 7-8 ms vs ~260 ms
per dungeon. Slimehouse went 12 → 44 seeds/s with it plus an r50 pre-cut before the RwgTerrain
height read.

## Files

- `predict_islands.py` — pure-Python island arithmetic (Java LCG), matches probe stage
- `islandscan.py` — verify a hit against a generated world save (entrance column, island
  material, brick extent)
- `hits-20k-salt20260912.jsonl` — slime-house survivor rows (documentation of the stock-only
  phenomenon)
- cliffhouse sweep outputs: `~/.cache/gtnh-determinism/cliffhouse-*.jsonl` (top rows copied here)

## Winner (verified)

**Seed 226905126988615045, entrance (-710, ~95, 998)** — HOUSE tower on a plateau edge,
prefilter predicted floor 95 / min surrounding surface 53 (drop 42). Full generation (fixed
build, beta3-s1, spiral walk centred cx=-44 cz=62, radius 12) confirms exactly:

- house floor 95 (spiral-stair oak pillar y50-98 at the entrance column = floor+3);
- brick family spans y=55..104, **2256 brick blocks** in the 41×41 window around the entrance
  (a flat-ground HOUSE measures ~508) — 34 uninterrupted levels of exposed shaft/porch/step
  brick from y=60 to the house body;
- granite supports continue below to the valley floor (~53).

Scan: `cliff-verify-226905126988615045.txt` (islandscan.py; island fields N/A here). Sweep
survivors (drop ≥ 30, 5k seeds, salt 424242): `cliff-survivors-5k-salt424242.jsonl` — 59 seeds,
464 scored HOUSE placements; deepest wet drop 54 (floor 67 over an ocean trench at 13, seed
8524227239314487876) for anyone who wants "most total structure" over water instead: porch and
step columns pour through water to y=60 and the supports fillDown to the seabed, so wet drops
are strictly more blocks. `min_dry` is emitted per row for ranking dry cliff faces.

Metric note: brick specifically stops at y=60 (porch/step fills are hardcoded to 60); below that
only the polished-granite supports continue. Maximizing BRICK means maximizing floor height
(cap ≈ 99 via upperLimit=100); maximizing total structure means wet drop.

## Part 3: the front staircase (the shot people actually want)

The iconic look is `step()`'s descending 3-wide brick staircase off the door, not the shaft. Its
geometry is a pure function of virgin terrain: `dir = Cardinal.directions[(base+2)%4]` where base
is `Tower.getBaseCoord`'s Y — so a ±1 error in base swings the door 90°. The digest-based base
estimate did exactly that (predicted W, generated S on seed -7347640923000673639); the stage now
replicates the 128→60 `validGroundBlock` scan against `world.getBlock` (virgin in the prefilter
world, identical to what TerrainOracle serves the fix jar), which ground-truthed exactly: door
predicted = door generated on both verified seeds.

Metrics, emitted per HOUSE (`stair_len`, `stair_vis`, `stair_dir`, `door`; gates
`gate.cliffstair`, `gate.cliffvis`):
- `stair_len` — steps until the probe hits ground or y≤60; cap = floor−62 (39–40 at floor 101-102).
  Seed -1048667646972582280 (entrance -670,101,316) has the full 39, verified in-world — but a
  long staircase can hug a slope, with the curtain buried (screenshot evidence from the user).
- `stair_vis` — visible brick: per step, the 3-wide curtain column counted from
  max(61, virgin topSolid+1) up to the step. Airborne-off-a-cliff maximizes this
  (theoretical ~2460); slope-huggers score a few hundred. Note the world-side check must use
  VIRGIN terrain: step columns replace the hillside (fill replaceSolid=true), so a saved-world
  scan that looks for terrain inside the column line counts buried brick as visible.

**Staircase winner (verified on 2.8.4): seed 9030989148700162261 — door (-663, 96, 127),
staircase runs WEST, 34 steps y=94→61 over a valley at ~62, stair_vis 1041 (best of 10k seeds,
salt 5150).** Scan: `staircase-verify-9030989148700162261.txt`; survivors:
`vis-survivors-10k-salt5150.jsonl`.

**FINAL WINNER — user's actual pack (2.9.0-beta-3 + fix jar): seed 3413486109741234596,
door (-16, 96, -1490), 31-step brick staircase running WEST from the door down to y=63 over
flat lowland at y≈62 — curtain wall 32 blocks tall at the door end, stair_vis 1824 (74% of the
theoretical ~2460; best of 15k beta3 seeds, salt 8686). Verified block-for-block on beta3-s1.**
Survivors: `b3-vis-survivors-15k-salt8686.jsonl`. The hunt was re-run against `beta3-pf` as
prefilter template after the caveat below surfaced (the user plays beta-3, and 2.8.4-derived
theme predictions flip at biome borders).

**PACK VERSION CAVEAT (bit us twice):** the prefilter runs the 2.8.4 template, and the original
verify server (`beta3-s1`) is 2.9.0-beta-3. Terrain (RWG noise) is identical across the two, but
biome tables shifted, and `SettingsResolver` picks the theme by biome — the two highest-stair_vis
hits of the 10k sweep (9030989148700162261, -1395281302759738443) resolve HOUSE on 2.8.4 but a
slab tower on beta-3. River-cliff sites are enriched for biome borders, so staircase hunting
flips more than flat-forest hunting. All predictions in this directory are 2.8.4; verify on
`verify-284` (cloned from `template-2.8.4`, current probe+fix jars), not on the beta3 servers.
Earlier winners (226905126988615045, -1048667646972582280) happen to resolve HOUSE on both.
