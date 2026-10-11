# GT shape probe + F4d backport on 2.9.0-beta-1 (GT 5.09.52.594)

2026-09-17. Branch `gt-shape-probe-beta1`.

Two things measured here: that the init crash reported from a beta-1 client is fixed, and that the
F4d backport for the 5.09.52.31–5.09.54.01 GregTech band actually makes vein identity
route-independent rather than merely binding.

## Pack

Assembled from `DreamAssemblerXXL/releases/manifests/2.9.0-beta-1.json` — 216 server-side mods at
their pinned versions plus the 2.9.0-beta-1 config — at `~/.cache/gtnh-determinism/beta1`.
GregTech is **5.09.52.594**, the exact version in the crash report. Server scaffolding (forge,
`lwjgl3ify-forgePatches.jar`, `libraries/`, `nestedmods/`) copied from the beta3 install. Java 21
(Zulu 21.0.11).

Three of 216 downloads silently returned HTML (external mods whose `browser_download_url` is a forum
or Drive page). FML reports such a jar as a **missing mod**, not a corrupt one, so the first boot
failed blaming `GraviSuite`. Validate every jar with `unzip -t` before booting.

## Crash: reproduced, then fixed

| Jar | Result |
|---|---|
| pre-fix (`main`) | `GT ore-vein probe: StoneType variant (GT 5.09.54.x or later)` on GT 5.09.52.594, then `InvalidInjectionException: @Redirect ... gtnhdet$virginStoneType could not find any targets matching executeWorldgenChunkified(...WorldgenGTOreLayer$VeinPlacement;ZZ)I` — **byte-identical to the user's report** |
| fixed | `findStoneType shape (GT 5.09.52.31-5.09.54.01) ... UNGATED (<= 5.09.53.10)`, `F4d: BACKPORTED implementation`, **`Done (9.057s)`, 0 mixin failures, 0 crash reports** |

All three shapes boot clean with the one jar: 2.8.4 / GT 5.09.51.482 → `isReplaceableOreGen shape,
F4 only` + `F4d: unavailable`, `Done (8.894s)`; beta-3 / GT 5.09.54.133 → `VeinPlacement shape,
F4 + native F4d`, `Done (7.849s)`. Shape A confirms `require = 10` binds and the newly added
`GtOrePin.appliesTo` gate did not break it.

## Route A/B — vein identity

Seed `-1636594104014467454`, radius 24 (2401 chunks walked), rows vs spiral, dedicated server, one
jar with `-Dgtnhdet.orepin` as the only lever. Metric is `GTWorldgenerator.validOreveins` dumped by
`OreVeinTableDump` and compared with `scripts/diff-veincache.py`.

| Restriction | Regions | CONTROL `orepin=false` mix differs | TREATMENT `orepin=true` mix differs |
|---|---|---|---|
| full box | 423 / 432 | **35 (8.27%)** | **0** |
| \|osX\|,\|osZ\| ≤ 24 | 262 | 28 | **0** |
| \|osX\|,\|osZ\| ≤ 20 | 202 | 19 | **0** |
| \|osX\|,\|osZ\| ≤ 15 | 104 | 10 | **0** |

**Vein identity residual on beta-1 is 0.** The control's 8.27% sits right next to the 7.95% measured
for shape C's Overworld in results/2026-09-05-gt-ore-canonical-trigger, which is independent
corroboration that this is the same F4d defect and that `orepin=false` really is the stock arm.

The control degrades smoothly as the box shrinks (35 → 28 → 19 → 10), i.e. the route dependence is
spread through the whole area rather than concentrated at the edge — so the signal is real and the
treatment's zero is not an artifact of where we looked.

### The one residual, and why it is the box and not the fix

`diff-veincache.py` exits 1 on the full box because of **`only in one : 2` (A-only 1, B-only 1)** —
two regions each walk decided and the other did not. Decoding the region keys:

- A-only `-6437153195499323397` → dim 0, oreseed chunk **(-29, -5)**, `ore.mix.mineralsand`
- B-only `-6437153196891570166` → dim 0, oreseed chunk **(-35, 10)**, `ore.mix.garnettin`

Both lie outside the radius-24 walk (observed dim-0 oreseed X range -35..25). A region whose oreseed
sits beyond the generated box is only decided if a walked edge chunk happens to trigger it, and which
edge chunks get touched depends on traversal order. Restricting to the walked box removes both and
leaves `only_in_one = 0` at every radius tested, while the control's mix signal survives every
restriction. Frontier artifact.

`geometry differs` is reported as 0 but is **vacuous on this band**: shape B caches the layer itself
rather than a `CachedOreVein`, so there is no `placement` record to compare and `geometry()` returns
all-`None` on both sides. Do not quote it as a pass here.

## Regression: same A/B on 2.9.0-beta-3 (shape C, native F4d)

Same seed, radius, orders and lever, on a throwaway copy of the beta3 install (GT **5.09.54.133**) so
the original pack and its `chesttest.json` artifacts were left alone. This arm tests that rewriting
the F4d gate from `hasClass("gregtech.common.ores.OreManager")` to per-target `hasMember` probes, and
adding `allow = 2` to `WorldgenGTOreLayerStoneTypeMixin`, did not disturb the native path.

| Arm | mix differs | geometry differs | only in one | `diff-veincache.py` exit |
|---|---|---|---|---|
| CONTROL `orepin=false` | 38 / 423 (8.98%) | 1 / 423 (0.24%) | 0 | 1 |
| TREATMENT `orepin=true` | **0 / 423** | **0 / 423** | **0** | **0** |

**No regression.** This is a cleaner pass than beta-1's: all three metrics are zero and the harness's
own gate exits 0, with no frontier residual. Geometry is a *real* metric here — shape C caches a
`CachedOreVein` with a `placement` record, and the control does register 1 geometry difference, which
confirms the field is actually being compared rather than silently absent as on shape B.

The two controls also agree with each other and with the published figure: 8.98% (beta-3) vs 8.27%
(beta-1) vs 7.95% (results/2026-09-05-gt-ore-canonical-trigger, shape C OW at r60). Three independent
measurements of the same defect.

## Compatibility with 0.13.0 on beta-3 — does the release change worldgen output?

Asked directly: are beta-3 vein outputs the same as shipped **0.13.0**? Measured against the real
release jar (`~/Downloads/gtnhdeterminism-0.13.0.jar`, `"version": "v0.13.0"`), same pack, same seed,
same order (rows), r24, and the **same probe jar in both arms** so only the determinism jar varies.

| Comparison | vein cache | whole-chunk block+TE hashes |
|---|---|---|
| 0.13.0 vs new jar (as built) | **0 / 0 / 0**, exit 0 | 154 / 2401 (6.4%) |
| 0.13.0 vs new jar, `-Dgtnhdet.mineshaftpin=false` | **0 / 0 / 0**, exit 0 | 17 / 2401 (0.7%) |
| **FLOOR: 0.13.0 vs 0.13.0**, identical jar/seed/order | **0 / 0 / 0**, exit 0 | **22 / 2401 (0.9%)** |

**Vein output is identical.** 423 regions, zero mix / geometry / only-in-one differences, in every
comparison including the floor.

The chunk-hash column needs the floor to read: this pack does **not** reproduce bit-identically
launch-to-launch — two runs of the *same* 0.13.0 jar differ on 22 chunks. The new jar with the
mineshaft pin off differs from 0.13.0 on 17, i.e. **below the floor**, so no effect from the GT
changes is detectable. That is consistent with the bytecode: on shape C every executed path is
identical (see below).

The 154 → 17 drop identifies the rest: **137 chunks are the in-progress mineshaft corridor chest pin**
(`MineshaftCorridorChestMixin`, registered unconditionally in `mixins.gtnhdeterminism.json`), which is
separate 0.14.0 work, not part of this fix. Its block noise concentrates at y 0–31, where mineshafts
live. **That feature does change worldgen output and is the thing a release has to account for** — the
GT shape/F4d work does not.

### Static corroboration

Disassembling both jars and comparing every class that executes on shape C:

- `GTWorldGenContainerOrePinMixin`, `OreManagerVirginDryRunMixin`, `GtOreDryRun`, `TerrainOracle`,
  `GtOreColumnProbe`, `GtOrePin.appliesTo` — **bytecode identical**.
- `WorldgenGTOreLayerStoneTypeMixin` — differs only by constant-pool index shifts caused by adding
  `allow = 2` to the annotation; identical instruction sequence, still calls `VirginStoneType.at`.
- `VirginStoneType` — pure append of `atUngated`, which no shape-C path calls.
- `GtOrePin.logState` — logging text only.

So `allow` is validation metadata with no codegen, and every new class is gated to a band beta-3 is
not in. Output-neutrality on shape C is structural, not luck.

## What is NOT shown

Whole-chunk block hashes (`diff-probe.py`) still differ on **1623/2401 chunks (67.6%)** in the pinned
arm, against 1704/2401 (71.0%) stock. That is **not** a determinism result and the 71 → 67.6 movement
is not progress worth quoting: the chunk hash covers all of worldgen, and this pack still carries the
separately-tracked open defects (The End `veinMinY` EBS scan, slime-island Y-shift, vanilla
`WorldGenDungeons`, and others). The F4d metric is the vein cache, and that is zero.

Also unverified: that `orepin=false` is *bit-identical* to a no-fix-jar run. It cannot be isolated by
removing the jar, because that also removes every other worldgen fix and perturbs terrain. The
evidence that the off switch is sound is indirect — it reproduces the stock route dependence at the
expected rate.

## Reproduce

```
PROBE_JAVA=~/.gradle/jdks/azul_systems__inc_-21-amd64-linux.2/bin/java \
PROBE_EXTRA_ARGS=-Dgtnhdet.orepin=false \
  scripts/run-probe.sh ~/.cache/gtnh-determinism/beta1 -1636594104014467454 rows OUT.json 24
```
then the same with `spiral`, and both again with `-Dgtnhdet.orepin=true`; compare the
`.veincache.json` pairs. Raw output in `~/.cache/gtnh-determinism/beta1-ab`.
