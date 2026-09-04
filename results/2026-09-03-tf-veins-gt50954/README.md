# GT 5.09.54 vein selection: decoded and verified, and why TF veins still cannot be predicted

GTNH daily-707 (gregtech-5.09.54.115) vs GTNH 2.8.4 (5.09.51.482). Probe jar `a416a288…`.

Task: make `vein_predict.py` work on both versions. Outcome, stated up front: the **selection protocol
is now implemented for both versions and verified against the live mod**, but on 5.09.54 the **winning
vein of a cell is a function of Twilight Forest terrain**, which the worldless prefilter cannot
produce. Shard-vein prediction on daily does NOT meet the 85% precision bar and is OFF by default.

## What changed in 5.09.54

Everything except the hash:

| | 5.09.51 (2.8.4) | 5.09.54 (daily) |
| --- | --- | --- |
| oreveinSeed | `(seed<<16) ^ (dim<<56 \| osX<<28 \| osZ)` | unchanged |
| per-attempt RNG | one XSTR stream, sequential draws | **fresh seed per attempt: FNV-1a-64(oreveinSeed, attempt)** |
| the draw | `nextInt(3568)` over ALL 79 mixes, wrong-dim rejected after | `nextInt(1053)` over the **dimension-filtered** list |
| placement fail | falls to next attempt | **redraws on the same advancing XSTR**, up to `oreveinMaxPlacementAttempts=8` per attempt |
| ore lattice | `abs(chunk)%3==1` | `floorMod(chunk,3)==1` (`OregenPattern.EQUAL_SPACING`) |
| pattern storage | — | `OregenPatternSavedData`: **new worlds always EQUAL_SPACING**; the save exists for legacy-world back-compat, not randomness |
| XSTR.nextInt | validated 2.8.4 algorithm | different xorshift/modulo — but bit-compatible with `uo_oil.XSTR` (measured) |

`vein_predict.ALGO` selects the protocol (sniffed from the table filename; `GTNH_VEIN_ALGO` overrides),
and `data/oremixes-gtnh-daily707.json` carries the 122-mix table extracted from the 5.09.54 builder
bytecode (`data/extract_oremixes_from_jar.py`, rewritten for the `OreMixBuilder` chain).

## Verification ladder — each rung measured

1. **FNV-1a-64**: bit-exact vs the real gtnhlib, 18/18, including the sign-extension of bytes ≥ 0x80.
2. **Mix table**: runtime dump of `WorldgenGTOreLayer.sList` == static table — 122 layers, order,
   weights, TF eligibility, primary ids: 0 mismatches.
3. **The draw**: 96/96 picks match GT's own `WorldgenQuery.findRandom` driven via reflection.
4. **The live path**: `GTWorldgenerator.validOreveins` dumped after a real dim-7 walk — 153 regions.
   For 34/37 TF and ~94/100 overworld regions, the real winner appears in the predicted attempt
   stream. The draw chain is right against the running game.

## Why that still is not a prediction

The winner is *which attempt survives the terrain gate*, and the real winners sit at attempt depths
0–63 with no closed form: `testWorldgenChunkified` probes actual TF blocks, and a failure both
advances to the next attempt *and* (the 5.09.54 novelty) can redraw within the attempt on the same
advancing XSTR. The missing 3+6 winners above are exactly redraw products.

Measured on the 37 real TF regions: **both** real shard veins came from redraws, so a
"shard mix appears in the first D attempts" candidate rule scores **0–8% precision** at every depth.
There is no honest worldless shard prediction on 5.09.54 without TF terrain.

**The unlock, if wanted later**: a Twilight Forest analogue of `VirginChunkProvider`. With virgin TF
chunks the gate (and the fix jar's own virginised stone probe) is computable and the whole chain is
already in place. That is a real build — TF's chunk provider, biome layout and its own decoration —
not a flag.

## What still works, and where

- **2.8.4 line**: the legacy protocol is untouched and its golden test is pinned to the legacy
  algorithm + table (`GTNH_VEIN_ALGO=legacy`, 2.8.4 mix json): **20000/20000** vs the JVM reference
  across all four dimensions. The old TF result (98.1% shard precision) remains a 2.8.4 claim.
- **daily-707**: identity streams (`predict_all`) are exact and validated; winners are not. The
  seed-search TF criterion stays **off by default** with the reason on the flag.

## Instruments added

- `OreVeinTableDump` (probe): runtime `sList` dump + real `findRandom` picks + `validOreveins` cache
  dump after every full-gen walk (`<out>.veincache.json`).
- `debugOrevein` postmortem: the config flag alone prints nothing — `GT_FML_LOGGER.debug` is filtered
  below INFO, and log4j 2.0-beta9 lacks `Configurator.setLevel(String, Level)`. The cache dump made
  log-fighting unnecessary and is the better instrument anyway: it reads decisions, not prints.

## Wrong turns, recorded

- The first "validated" comparison (96/96) proved Python == my own reflection driver, not Python ==
  the game; the driver and Python shared the seeding assumption. The cache dump broke that circle.
- Two dumper bugs cost a run each: field-scanning a downgraded record (accessors are the API), and
  assuming the cache value was the record when it is the layer itself.
- An earlier session claim — "the pattern saved-data makes veins unpredictable" — was wrong in the
  interesting direction: the save is back-compat, new worlds are deterministic EQUAL_SPACING.
