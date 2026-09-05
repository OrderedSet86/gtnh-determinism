# F4c: virginising the ore-vein dry run. NEGATIVE RESULT, reverted

**Outcome: no useful improvement. 140 unstable vein regions became 137 — by fixing 5 and breaking 2.
The mixins are reverted. The measurement infrastructure and the eliminated candidates are the value
here.**

GTNH daily-707, `~/.cache/gtnh-determinism/daily-707`, GregTech 5.09.54.115, seed
`-1636594104014467454`, radius 60 chunks centred on the spawn chunk (9,9), `scripts/run-probe.sh`.
Ground truth = GregTech's own `GTWorldgenerator.validOreveins` cache, dumped by `OreVeinTableDump`
after each full-generation walk, compared per oreseed by resolved vein mix.

## The noise floor, measured for the first time

Vein identity had never had a same-order baseline. It does now:

| pair | common regions | different mix |
| --- | ---: | ---: |
| **rows vs rows** (same jar, same order, separate launches) | 1762 | **0 — 0.00%** |
| rows vs spiral | 1760 | 140 — 7.95% |

**The floor is exactly zero.** Vein identity is perfectly launch-deterministic and purely
order-broken. This matters for reading any future result: a 3-region change is NOT noise, and must
not be dismissed as such. It also means the metric is unusually clean to work against — unlike the
tile-entity diff used in 2026-08-27, which had a floor of 3.

Twilight Forest, same method, one walk pair each: **225 of 1702 (13.2%)** — worse than the overworld.

## The mechanism, which is real

GT 5.09.54 restructured vein selection. `GTWorldgenerator$WorldGenContainer.generateVein` runs up to
`oreveinAttempts` candidates and accepts one only if a dry run approves it:

```java
placementResult = oreLayer.testWorldgenChunkified(...);
if (placementResult == ORE_PLACED || placementResult == NO_OVERLAP) { cachedOreVein = ...; }
```

`testWorldgenChunkified` → `executeWorldgenChunkified(..., dryRun=true, ...)` →
`generateWithPlacement` → per block `OreManager.canSetOreForWorldGenOrAlreadySet` →
`getOreBlockForWorldGen` → **`StoneType.findStoneType(world, x, y, z)` on the LIVE world.**

That read is genuinely impure and genuinely outside F4's reach: `WorldgenGTOreLayerStoneTypeMixin`
scopes its `@Redirect` to `executeWorldgenChunkified`, and this call is in `OreManager`, a different
class. A rejected candidate advances to the next attempt, so the live read changes vein IDENTITY, not
merely which blocks get placed.

## The fix, and why it did not work

`GtOreDryRunScope` (a ThreadLocal depth) opened around `testWorldgenChunkified`, with
`OreManagerVirginDryRunMixin` answering both impure reads from `TerrainOracle` while the scope is
open — `findStoneType`, and the `getOreInfo` "is my ore already here" branch. Real placement left
untouched, so only identity was targeted. All three mixins bound at `require = 1` and were confirmed
in the selected-mixin log line (35, up from 32).

Clean A/B, same jar version, differing only by the mixins (md5 `c2a4331b` without, `fd27a7a8` with):

| jar | rows vs spiral | unstable |
| --- | ---: | ---: |
| v0.7 without F4c | 1760 common | 140 (7.95%) |
| v0.7 with F4c | 1760 common | 137 (7.78%) |

Decomposed, which is the part that matters:

| | regions |
| --- | ---: |
| unstable before, stable after — **fixed** | **5** |
| stable before, unstable after — **broken** | **2** |
| unstable in both | 135 |

It is a lottery re-draw, not a partial fix. Only 2 of 1762 regions changed their rows-walk vein at
all, so the mixin barely perturbs the system; the 135 that remain unstable are unaffected by it. This
reproduces the lesson already recorded in `results/2026-08-27-gt-ore-probe-pinning`:
**partially purifying an arbitrary decision does not partially fix it, it just picks a different
arbitrary answer.** That README was read before this attempt and the trap was walked into anyway.

Reverted: `OreManagerVirginDryRunMixin`, `WorldgenGTOreLayerDryRunScopeMixin`,
`GTWorldGenContainerScopeResetMixin`, `GtOreDryRunScope`, and the LateMixinLoader registration.

## Candidates eliminated by source reading (do not re-investigate)

- **`resolveVeinPlacement`'s `ExtendedBlockStorage` scan.** It walks the live chunk's block storage to
  derive `veinMinY`, which would be an ideal culprit — but it sits behind
  `if (!dimensionDef.respectsOreVeinHeights())`, the field defaults to `true`, and
  `disableOreVeinHeightChecks()` is called for **The End only**
  (`galacticgreg/api/enums/DimensionDef.java:38`). Overworld and Twilight Forest never take that
  branch.
- **The two `findStoneType` probes at `WorldgenGTOreLayer.java:218` and `:240`.** Already covered by
  `WorldgenGTOreLayerStoneTypeMixin` (`require = 2` binds both).
- **The mixin failing to bind.** It is present in the shipped jar and appears in the
  "worldgen mixins selected" log line on daily-707. F4 is active; 7.95% is the residual *with* it.
- **A mod-set or config difference between probe server and client.** Unrelated to this, but checked
  while chasing it: the 243-vs-217 mod delta is entirely client-side UI mods, and TooMuchLoot's
  configs are byte-identical.

## What is left, and the discipline it needs

The remaining impurity is inside `generateWithPlacement`'s per-block loop — not the world reads that
were redirected, but the ordering and count of `rng` draws. A placement is attempted only when
`rng.nextInt(placeZ) == 0 || rng.nextInt(placeX) == 0`, with bounds derived from the trigger chunk's
distance to the oreseed over a trigger-clipped window. That is the same second impure branch flagged
as open for 5.09.51 in 2026-08-27; it survived the 5.09.54 rewrite.

Do **not** attempt another targeted redirect. Given a zero noise floor and a decomposition that shows
fix-and-break in the same change, the only thing that counts is making acceptance a total function of
`(world seed, dim, oreseed, mix)` — which means running the dry run against a fully virgin chunk view
rather than patching individual reads. Anything less will move the headline by single digits and
break as much as it fixes.

## Methodology error worth not repeating

The first A/B compared the F4c jar (`v0.7-main+347c27a174`) against the previously deployed
`v0.5-chest-loot-positional.30+a74ca2b307`, which differ by every change between those tags, not just
F4c. It read as 140 → 137 and was reported before the confound was noticed. The numbers above are
from a re-run where both arms are v0.7 and differ only by the mixins.

Note also that the two v0.7 jars carry the **same version string** and differ only by md5
(`c2a4331b` vs `fd27a7a8`), so a version string is not sufficient to tell which arm a result came
from. Record the md5.
