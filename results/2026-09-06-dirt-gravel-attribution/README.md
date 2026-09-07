# Dirt/gravel pockets carry a third of the whole block residual, and half the GT ore residual

**Outcome: attribution only, no fix shipped. Disabling RWG's dirt/gravel blobs drops rows-vs-spiral
route dependence from 67,519 to 45,933 blocks (−32%), and takes GT ore down 54% and the EtFuturum
deepslate band down 93% with it. Those two are pure downstream coupling: neither generator was
touched.**

GTNH `daily-2026-08-28+707`, GregTech 5.09.54.115, jar `gtnhdeterminism-v0.8-main.1+a5efcee3d6-dirty`
(md5 `a44cb017`), seed `-777`, radius 6, `scripts/run-probe.sh`, cold JVM per walk.
Metric: `scripts/inventory-region-diff.py` over the persisted region files.

## The lever

`config/GregTech/WorldGeneration.cfg` → `generateUndergroundDirtGen` / `generateUndergroundGravelGen`.

These are **not** GregTech generators, which the name suggests and which cost the first hour of this
investigation. They are a veto on **RWG's** blobs, delivered through Forge's ore-gen bus:

- `Worldgen.java:70-78` declares both, default `true`; `GTPreLoad.java:497-499` copies them to
  `GTProxy.enableUndergroundDirtGen/GravelGen`.
- `GTProxy.java:1072-1073`, at FML Init: `if (!enableUndergroundDirtGen) PREVENTED_ORES.add(DIRT);`
  — **inverted polarity**, `true` means "do not prevent".
- `GTProxy.java:1446-1452` `onOreGenEvent` DENYs when
  `mDisableVanillaOres && generator instanceof WorldGenMinable && PREVENTED_ORES.contains(type)`.
- `ChunkGeneratorRealistic.java:539-555` gates both blob loops behind
  `TerrainGen.generateOre(worldObj, rand, ore_dirt, x, y, DIRT)`, which posts that event. Dirt ×10,
  gravel ×5, `WorldGenMinable(…, 32)`.
- RWG never calls `BiomeDecorator.decorate`, so under `level-type=rwg` this is the **only** dirt/gravel
  blob source — there is no second vanilla ×20/×10 pass.
- The pack ships `disableVanillaOres=true`, so both toggles are live rather than dead options.

**The veto skips the loop without consuming RNG**, so the OFF arm is a different world, not the same
world minus pockets. Only route dependence *within* an arm is comparable; absolute block counts across
arms are not.

## Results

Six walks: {gen ON, gen OFF} × {rows, rows-again, spiral}.

| category | ON (stock) | OFF | change |
|---|---:|---:|---:|
| decoration (trees/plants/hives) | 38,856 | 34,012 | −4,844 (−12%) |
| dirt/gravel/stone patches | 7,574 | **164** | −7,410 (−98%) |
| GT stone-layer blobs (granite/stone) | 7,468 | 8,010 | **+542 (+7%)** |
| sand/gravel/clay/fluid settling | 6,087 | 2,086 | −4,001 (−66%) |
| EtFuturum deepslate band | 4,582 | **305** | −4,277 (−93%) |
| GT / mod ore placement | 2,952 | **1,356** | −1,596 (−54%) |
| **total** | **67,519 / 168 chunks** | **45,933 / 163 chunks** | **−21,586 (−32%)** |

### Which of these are real, and which are true by construction

Two of the drops are close to tautological and should not be quoted as achievements:

- **dirt/gravel/stone −98%.** 7,410 of the 7,574 blocks in that bucket are literally
  `dirt <-> stone` (4,099 one way, 3,311 the other) — RWG blob positions moving. Remove the blobs and
  the transitions cannot occur. This confirms *what the bucket is*, which was worth establishing:
  the bucket is the blobs, not some deeper terrain instability.
- **sand/gravel/clay/fluid −66%.** Partly the same effect, because `classify()` routes every
  `*↔gravel` transition here rather than to the bucket named after gravel.

The other three are **not** tautological — those generators were untouched and still produce the same
blocks in both arms:

- **GT ore −54% (2,952 → 1,356).** The first-writer-wins mutual exclusion, visible directly in the
  transitions: `minecraft:dirt:0 -> gregtech:gt.blockores2:8870` ×161, `…:8916` ×87, `…:8928` ×82,
  `…:8923` ×81, and `minecraft:gravel:0 -> …:8870` ×65. Where one walk put a pocket, the other put
  ore. This is the quantified version of the 2026-08-28 claim that ore is downstream of dirt/gravel:
  **over half of the GT ore residual is.**
- **EtFuturum deepslate −93% (4,582 → 305).** Not predicted. Deepslate replaces stone in a y-band that
  the pockets (`rand.nextInt(64)`) overlap, so which columns are still stone when the band runs is
  route-dependent for the same reason. The EFR fix made the band's *decision* seed-pure; it could not
  make the *substrate* stable.
- **GT stone-layer blobs +7% (7,468 → 8,010).** The one category that got worse: with fewer pockets
  there is more stone for GT's granite/stone blobs to vary over. Worth recording because it means a
  real dirt/gravel fix does not monotonically improve every bucket.

## The floor is not zero, and the README says it is

**P1 as written failed.** Same-order, separate-launch pairs:

| arm | floor |
|---|---|
| ON | **6 blocks / 5 chunks** (decoration 4, GT ore 1, sand-gravel 1) |
| OFF | **35 blocks / 3 chunks** (deepslate 21, GT ore 13, decoration 1) |

`results/2026-08-28-daily-2.9-compatibility/README.md:151-159` reports a daily launch pair of 122
blocks, all of it decoration, with **0 for every other category**. Those per-category zeros are what
this contradicts: GT ore and sand/gravel/clay now show 1 block each in the ON arm, and the deepslate
band shows 21 in the OFF arm. The floor is also not stable between run-sets — an earlier set of these
same six walks measured the ON floor at 2 blocks, not 6.

So the direction of that finding still holds (the floor is small, and decoration dominates it); what
does not hold is treating any category's floor as exactly zero.

It does not invalidate this result, and the reason is specific rather than hand-waved: **the
`dirt/gravel/stone` bucket's floor is 0 in both arms**, so P2 is clean; and the ore claim moves 1,596
blocks against a floor of 13. But every number above should be read with its floor attached, and
**"the block-level launch floor is zero on the daily line" is a claim that needs retracting**, not
repeating.

## Verdicts

| | prediction | outcome |
|---|---|---|
| **P1** | both floors 0 | **FAIL** — 6 and 35. Per-bucket floors still permit P2–P4. |
| **P2** | dirt/gravel/stone collapses toward 0 | **CONFIRMED** 7,574 → 164, though partly by construction |
| **P3** | ore drops materially below 2,952 | **CONFIRMED** → 1,356 (−54%), and not tautological |
| **P4** | sand/gravel/clay/fluid drops | **CONFIRMED** → 2,086 (−66%) |

## What this does and does not license

It does **not** license shipping the config change. Turning the pockets off deletes a real resource,
shifts every downstream RNG draw in `populate`, and makes a different world from the same seed.

It does establish that a correct dirt/gravel fix is the highest-value remaining target after
decoration: worth roughly **21,600 blocks**, of which ~5,900 are in generators that would not
otherwise be touched. That is a better return than any per-mod fix left on the list.

The fix direction is unchanged from 2026-08-28 and still hard: pocket position must become a pure
function of `(seed, chunk, pocket type, index)` — stateless per call, not a sequential rand in a single
slot, because `populate` re-enters through cascading `world.getBlock` — and the ore mutual exclusion
has to move at the same time, or it picks a different arbitrary answer rather than fixing anything.

**Cap on the claim:** one seed, radius 6, 168 chunks. This attributes the buckets and establishes the
coupling; it does not establish a pack-wide magnitude.

## Reproduce

```sh
export PROBE_JAVA=~/.gradle/jdks/azul_systems__inc_-17-amd64-linux.2/bin/java
SRV=~/.cache/gtnh-determinism/daily-707
OFF="config/GregTech/WorldGeneration.cfg:generateUndergroundDirtGen=false
config/GregTech/WorldGeneration.cfg:generateUndergroundGravelGen=false"

# ON arm: no PROBE_CONFIG. OFF arm: PROBE_CONFIG="$OFF". rows / rows again / spiral for each.
PROBE_PORT=25730 scripts/run-probe.sh $SRV -777 rows   /abs/on-rows.json 6   && cp -a $SRV/World /abs/on-rows
PROBE_CONFIG="$OFF" PROBE_PORT=25733 scripts/run-probe.sh $SRV -777 rows /abs/off-rows.json 6 && cp -a $SRV/World /abs/off-rows
# ...

python3 scripts/inventory-region-diff.py /abs/on-rows  /abs/on-rows2      # floor first
python3 scripts/inventory-region-diff.py /abs/on-rows  /abs/on-spiral
python3 scripts/inventory-region-diff.py /abs/off-rows /abs/off-spiral
python3 scripts/inventory-region-diff.py --detail "ORE placement" /abs/on-rows /abs/on-spiral
```

`run-probe.sh` restores the config from a per-run backup on an `EXIT` trap, asserts the value still
holds after the run, and stamps `World/.probe-provenance.json`, which the diff tools print. A key that
matches 0 or >1 lines aborts before the world is deleted.

## Tooling notes from this run

- **The provenance header paid for itself on first use.** It flagged `config_digest DIFFERS` between
  two runs of the *same* arm. Cause: of 8,605 config files exactly 4 rewrite on every boot
  (`DreamCoreMod.properties`, `hodgepodgeEarly.properties`, `jarjar.properties`, `IC2.ini`) and all 4
  differ only in a written timestamp comment. `.properties`/`.ini` are now comment-stripped like
  `.cfg`. Verified both ways: identical under timestamp churn, still detects a real setting change.
- **`--detail` shipped broken and was caught by using it.** Its value has no leading dashes, so the old
  `[a for a in argv if not a.startswith("--")]` filter kept it and it became the first positional,
  shifting both world paths. Exactly the defect `diff-region-blocks.py` documents for `--ids`. Fixed by
  consuming the flag and its value together.
- **The pre-0.8 `rel08-777` worlds disagree with this run by 117 blocks**, entirely in the deepslate
  band (4,465 vs 4,582); every other category matches exactly. They carry no provenance, so what
  produced them cannot be established. This measurement supersedes them.
