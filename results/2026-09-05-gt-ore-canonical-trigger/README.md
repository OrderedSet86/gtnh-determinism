# F4d: GT ore-vein identity is now route-stable. SHIPPED, default on

**Outcome: rows-vs-spiral vein identity goes from 140/1760 (7.95%) overworld and 225/1702 (13.2%)
Twilight Forest to ZERO in both, against a measured zero noise floor. Shipped default-on behind
`gtnhdet.orepin`, which can be set `false` to restore stock bit-for-bit.**

GTNH daily-707, GregTech 5.09.54.115, fix jar `gtnhdeterminism-v0.7-main+347c27a174`, seed
`-1636594104014467454`, radius 60 centred on the spawn chunk. Metric = GregTech's own
`GTWorldgenerator.validOreveins`, dumped by `OreVeinTableDump` after each walk and compared per
oreseed by resolved mix.

## The defect

`GTWorldgenerator$WorldGenContainer.generateVein` walks candidate mixes and accepts the first whose
dry run approves. Both calls in that loop pass the **trigger chunk's** coordinates —
`this.mX * 16, this.mZ * 16`, whichever of the surrounding 5x5 chunks Forge populates first, i.e. the
player's route (`GTWorldgenerator.java:396-412`). Those coordinates steer the accept/reject test three
ways:

1. `WorldgenGTOreLayer.java:210-233` — the clipping window `[chunkX+2, chunkX+18)` decides whether the
   dry run takes the early `NO_OVERLAP` / `NO_OVERLAP_AIR_BLOCK` return (a 9-sample stone probe) or
   falls through to a full placement attempt. **Dominant channel.**
2. `:366-371` — `localDensity = mDensity / sqrt(2 + dx² + dz²)`, where `dx`,`dz` are the
   trigger-to-oreseed chunk offset.
3. `:216-243` — the probe column `(chunkX+7, chunkZ+9)`, inside the trigger chunk.

`NO_OVERLAP_AIR_BLOCK` burns a placement attempt and rolls a **different mix**, so the trigger chunk
does not perturb the vein, it selects it. `validOreveins` is keyed purely on `oreveinSeed` but its
value is trigger-derived, and GregTech never clears or persists it — so a server restart re-triggers
unvisited regions from a different chunk.

**Both previous attempts closed channel 3 only**, which is why each moved single digits:
`results/2026-08-27-gt-ore-probe-pinning` (probe coordinate; 3 oreseeds → 2) and
`results/2026-09-05-gt-ore-dryrun-virgin` (the dry run's live reads; 140 → 137, by fixing 5 and
breaking 2).

## The fix

Two halves, shipped and measured as one change because neither works alone:

- **A — pin the decision** (`GTWorldGenContainerOrePinMixin`). Pass the oreseed chunk instead of the
  trigger chunk to `resolveVeinPlacement` and `testWorldgenChunkified`, closing all three channels at
  once. `@ModifyArg` in single-argument mode with the oreseed stashed by a HEAD `@Inject`.
  **Not `@ModifyArgs`**: it generates a synthetic `Args` class whose getters `CHECKCAST` every
  argument type, including the package-private `WorldgenGTOreLayer$VeinPlacement`, which resolves to
  `IllegalAccessError` at the first vein. `@ModifyArg` keeps the trailing args in locals of the target
  method, so no inaccessible type is ever named.
- **B — virginise what the pinned decision still reads** (`OreManagerVirginDryRunMixin`). After the
  pin the dry run's entire live-world surface is two calls, both in `OreManager`:
  `StoneType.findStoneType` inside `getOreBlockForWorldGen` (gated on the dry-run scope, since the
  real write path shares it) and `getOreInfo` inside `canSetOreForWorldGenOrAlreadySet` (dry-run-only
  call site). Everything else it touches is pure per dimension.

The dry-run scope is opened around the inner `canSetOreForWorldGen` call in a try/finally rather than
around `testWorldgenChunkified`, because `canSetOreForWorldGenOrAlreadySet` has exactly one caller in
the whole GregTech tree — inside `if (dryRun)` — so it cannot leak into the write path even in
principle.

`VirginStoneType` is factored out of `WorldgenGTOreLayerStoneTypeMixin` rather than copied: two
mixins now ask the same virgin question from different classes, and a divergence between two
transcriptions of that loop would be silent.

## Results

### Route stability — the point of the exercise

| comparison | before | after |
| --- | ---: | ---: |
| rows vs rows, separate launches (**noise floor**) | 0 / 1762 | **0 / 1764** |
| overworld rows vs spiral | 140 / 1760 (7.95%) | **0 / 1764** |
| Twilight Forest rows vs spiral | 225 / 1702 (13.2%) | **0 / 1728** |

Zero differing geometry and zero regions present in only one walk, too — `only-in-one` went 3 → 0, so
the vein *set* is stable, not just the mixes. No `F4d dry run threw` lines in any run.

### Totality audit, with a negative control

`-Dgtnhdet.orepin.audit=true` suppresses the `validOreveins` lookup so every chunk of the 5x5 box
redoes the decision, and compares at the `put`. Under the pin all chunks feed identical arguments, so
any disagreement is pure live-world residual — one walk proves totality and names any failures.

| arm | disagreeing oreseeds |
| --- | ---: |
| pin ON | **0** |
| pin OFF (control) | **590** |

The control is the load-bearing half: a clean ON result means nothing without evidence that the audit
can detect the problem at all.

### Balance: fails equivalence vs stock, and that is the wrong bar

`scripts/vein-balance.py`, 24 seeds, 3521 regions per arm, seed-paired region-clustered bootstrap,
±10% bound — the same design as `balance-report.py`:

**2 FAIL, 12 inconclusive, 6 PASS.** `ore.mix.oilsand` 1.475x [1.240, 1.729] and
`ore.mix.cassiterite` 2.388x [1.438, 4.502].

But measured against GregTech's **declared table weights** — the distribution with no terrain filter
at all — the pinned arm is *closer to intent than stock*:

| | total-variation distance from declared weights |
| --- | ---: |
| stock | 0.3032 |
| pinned | **0.2826** |

and the mixes that "failed" are the ones stock suppresses hardest:

| mix | declared | stock | pinned |
| --- | ---: | ---: | ---: |
| `cassiterite` | 0.0329 | 0.0037 (**11x under**) | 0.0080 |
| `lignite` | 0.1053 | 0.0057 (**18x under**) | 0.0077 |
| `magnetite` | 0.1053 | 0.0145 (7x under) | 0.0207 |
| `oilsand` | 0.0263 | 0.0187 | 0.0275 (near exact) |
| `gold` | 0.1053 | 0.1690 (60% over) | 0.1599 |

Mechanism: a vein rejected by the terrain gate is replaced by the next weighted draw, so
heavily-filtered mixes bleed their share to lightly-filtered ones. Stock's realised distribution is
itself a large, route-chosen distortion of the table. The pin reduces it on 11 of the top 16 mixes.

**The filter is relocated, not disabled.** It still runs, at the vein's own oreseed rather than at an
arbitrary route-chosen chunk. Cassiterite remains 4x below its declared weight, so high veins still
lose on low ground — the "mountains host the high veins" incentive that `WorldgenGTOreLayerMixin`'s
javadoc requires is intact. This is the distinction from the reverted v1 fix (jar 0.1-0.2), which
disabled the reroll outright and silently no-op'd high veins.

Ship decision (user, 2026-09-05): declared weights are the better bar; equivalence against stock's
realised distribution is not a meaningful gate when stock is itself arbitrary.

## Rule C2, designed and not built

The fallback was to force GT's own 9-sample probe at the canonical oreseed column — same totality,
stock's exact strictness. It was abandoned once the declared-weight comparison showed stock's
strictness is not a target worth preserving. The scaffolding (pin + virgin reads) is what C2 would
have reused, so it remains cheap to revisit if the distribution shift proves unpopular in play.

## Two tooling traps this run exposed

- **`balance-report.py` cannot measure veins on GT 5.09.54.x.** Its vein metric counts big-ore
  *tile entities*, and 5.09.54 made worldgen ores plain blocks — so the table comes back with **0
  entries** and the report prints `Total FAIL rows: 0 of 544`, which reads exactly like a pass over a
  metric that tested nothing. `searchlib.py:270-277` documents the underlying trap on
  `has_ore_census`. `scripts/vein-balance.py` was written to measure vein identity directly from the
  cache dumps instead, with a negative control proving its per-seed decode discriminates.
- **`OreVeinTableDump` writes `<out>.veincache.json` beside each report**, which matches
  `seed-*.json` globs and crashes `balance-report.py` with a list-vs-dict `TypeError`. Move them
  aside before running arm comparisons.

## Not covered

- **Only the overworld and Twilight Forest are pinned.** `GtOrePin.DIMS` is a WHITELIST, default `0,7`,
  overridable with `-Dgtnhdet.orepin.dims=`. Those are the only two dimensions with evidence; every other
  keeps stock behaviour until measured. Verified with a negative control: with the whitelist in place the
  overworld is 0/334 and TF 0/330, while the **Nether is 78/356 (21.9%)** — still route-dependent, which is
  what proves the whitelist excludes rather than silently applying. The Nether is therefore a known-broken
  dimension, worse than the overworld's original 7.95%, and a candidate for the next round.
  Two review catches worth recording, both of which shipped briefly: the first version *documented* excluding
  The End but had no dimension check at all, so dim 1 was silently pinned; the second fixed that with
  `dimensionId != 1`, which is a blacklist — it left the Nether, every Galacticraft/GalaxySpace body,
  asteroid belts, Underdark and SpectreWorld pinned on no evidence. Only the third version is a whitelist.
- **The End (dim 1)** is additionally excluded on merit. It is the only dimension calling
  `disableOreVeinHeightChecks()`
  (`galacticgreg/api/enums/DimensionDef.java:38`), which makes `resolveVeinPlacement:306-336` scan
  live chunk terrain for `veinMinY` and consume a variable RNG draw — contaminating vein *geometry*,
  not just identity. The pin sets its coordinate but the scan stays live. No dim-1 baseline exists to
  verify against; measure one before attempting it.
- **Block-level ore placement remains route-dependent.** `OreManager.setOreForWorldGen` reads the live
  world at every write, deliberately un-redirected — this fix targets identity, which is what routing
  depends on. Expect the ~4,500-block residual from `results/2026-08-27-gt-ore-probe-pinning` to
  persist; report the veincache metric, not a tile-entity diff.
