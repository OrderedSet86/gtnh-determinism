# Nether GT ore veins are route-stable. SHIPPED, default on

**Outcome: Nether (dim -1) vein identity goes from 413/1806 regions differing between a rows and a
spiral walk to ZERO, with zero differing geometry, against a zero same-order floor. The totality
audit goes from 1,287 disagreeing oreseeds to zero. `GtOrePin.DIMS` now defaults to `0,7,-1`.**

GTNH daily-707, GregTech 5.09.54.115, seed `-1636594104014467454`, r60 centred on (0,0),
`PROBE_DIM=-1 PROBE_SEARCH=false`. Metric = GregTech's own `GTWorldgenerator.validOreveins`, dumped
by `OreVeinTableDump` after each walk and compared per oreseed by resolved mix.

Measurement jar `gtnhdeterminism-v0.8-main.2+4c6e016626-dirty` md5
`0b225526d6a68142fd2c4da6324efb27`, one jar for every arm below — arms differ only by system
property. Probe `worldgenprobe-v0.8-main.2+4c6e016626-dirty` md5
`e3e2c1fd9df11bf3623d0459bb6bca1e`.

## The fix

Two lines of behaviour change, no new vein logic:

1. **The dimension whitelist now governs the whole F4 family.** It previously governed the
   coordinate pin alone — see "The whitelist was not a whitelist" below. This is what made a stock
   arm buildable; before it, no arm of any A/B in any dimension was stock.
2. **`-1` added to the default `gtnhdet.orepin.dims`.**

Nothing about the vein algorithm is Nether-specific. `WorldgenGTOreLayer.resolveVeinPlacement` reads
`chunkX`/`chunkZ` only inside its `!respectsOreVeinHeights()` branch, and `DimensionDef.java:29-32`
gives the Nether no `disableOreVeinHeightChecks()` call, so the Nether takes the short path and the
reason The End is excluded on merit does not apply here.

## Results

### Route stability

`diff-veincache.py --dim -1 --seed -1636594104014467454`:

| arm | comparison | mix differs | geometry | only in one |
| --- | --- | ---: | ---: | ---: |
| stock (`dims=0,7`) | rows vs rows, separate launches — **floor** | 0 / 1813 | 0 | 0 |
| stock (`dims=0,7`) | **rows vs spiral — the before** | **413 / 1806 (22.87%)** | 42 | 7 |
| fix (`dims=0,7,-1`) | rows vs rows — floor | 0 / 1815 | 0 | 0 |
| fix (`dims=0,7,-1`) | **rows vs spiral — the after** | **0 / 1806** | **0** | 13 |

Unfiltered (all dimensions) gives the same figures over 2007 common regions, so nothing moved in the
overworld or in dim 64 either.

**The only-in-one counts are a walk-boundary artifact, not a residual.** Decoding every one of them
from `(worldSeed << 16) ^ (dim << 56 | osX << 28 | osZ)` puts all 7 stock and all 13 fix regions at
oreseed chunk |coord| of 65, 68 or 71 — outside the r60 walk box (-60..60). They are regions
triggered by population cascading past the walk edge, and which of them a walk reaches depends on
the order it arrives at the boundary. Inside the walked region the count is 0 in both arms.

Zero `F4d dry run threw` lines in every arm.

Confirmed once more on the **shipped jar with no flags at all** (`a0b37ce23ef6ca431a2969713fbaa321`,
`dims=[0, -1, 7]`, `netherpop=false`): rows vs spiral is **0 / 1806** mix and **0** geometry, 14
only-in-one of which 0 are inside r60. Evidence in `final/`.

### Totality audit, with negative control

`-Dgtnhdet.orepin.audit=true` suppresses the `validOreveins` cache lookup so every chunk of the 5x5
box redoes the decision, and compares at the `put`. Under the pin all 25 chunks feed identical
arguments, so any disagreement is pure live-world residual — one walk proves totality and names the
failures. This is a far stronger test than rows-vs-spiral, which samples only two routes.

| arm | dim -1 lines | dim -1 distinct oreseeds | dim 64 lines / oreseeds |
| --- | ---: | ---: | ---: |
| pin OFF (`0,7`) — **control** | 8,694 | **1,287** | 21 / 12 |
| pin ON (`0,7,-1`) | **0** | **0** | 21 / 12 |

The control is the load-bearing half: a clean ON result means nothing without evidence the audit can
detect the problem at all.

**Read the audit per dimension or it will mislead you.** The raw line count is 21 with the pin ON,
and that number is entirely dim 64 (Ross128b, GalaxySpace) — outside the whitelist, unpinned,
*identical in both arms*. It is a constant of the pack, not a residual of this change. `validOreveins`
is a static GregTech never clears, so a walk carries entries from every dimension the server touched;
a `grep -c` over the audit log counts all of them. This was misread once during this session before
decoding.

### The vein result does not depend on the oracle guard

`OracleRngGuard` (`gtnhdet.oracleguard`, below) was on in the arms above. Re-measured with it off,
one property apart:

| arm | mix differs | geometry |
| --- | ---: | ---: |
| fix, `-Dgtnhdet.oracleguard=false`, rows vs spiral | **0 / 1809** | 0 |

So the vein claim rests on the coordinate pin and the virgin reads, not on a fix aimed at decoration.

### The column probe is unreachable under the pin, confirmed at runtime

Under the pin `chunkX == seedX`, and `resolveVeinPlacement:301-302,339-340` guarantees
`veinWestX <= seedX` and `veinEastX >= seedX + 16` (`mSize >= 1`), so `limitWestX` is always
`seedX + 2` and `limitEastX` at least `seedX + 16`. `executeWorldgenChunkified`'s
`limitWestX >= limitEastX` test therefore never fires, and the nine-sample column probe behind it is
dead code **in the dry run**. What filters instead is `generateWithPlacement` returning
`NO_OVERLAP_AIR_BLOCK` when a full dry-run placement at the oreseed lands zero blocks — the terrain
filter relocated to the oreseed, which is what F4d means by "relocated, not disabled".

That was an arithmetic argument over GregTech source, so it is now asserted at runtime
(`-Dgtnhdet.orepin.assertprobe=true`, default off):

```
F4d ASSERT armed: column-probe redirect is live (first sample at -41, 20, -39)
PINNED_DRY_RUN failures: 0
```

The "armed" line is the positive control and is not optional. Without it, "no failures" is
indistinguishable from "the instrumentation never ran" — and a vacuous assertion that reads as a pass
is worse than no assertion. F4's `WorldgenGTOreLayerStoneTypeMixin` is therefore nearly inert wherever
the pin applies; it still binds on `generateCachedVein`'s unpinned real call, where the return feeds
only a debug line and `VeinGenerateEvent.placementResult`, so it can move what a listener such as
VisualProspecting records but never a block.

## The whitelist was not a whitelist

Until this round, `GtOrePin.DIMS` gated `GTWorldGenContainerOrePinMixin` only.
`OreManagerVirginDryRunMixin` branched on the global `gtnhdet.orepin` and not on the dimension, and
`WorldgenGTOreLayerStoneTypeMixin` branched on nothing at all. Consequences:

- The Nether had been running virginised `OreManager` reads and a virginised stone probe, with only
  the trigger coordinates unpinned, since F4d shipped.
- `-Dgtnhdet.orepin=false` did **not** restore stock, in any dimension. That claim appears in
  `GtOrePin.java`, `GTWorldGenContainerOrePinMixin.java`, `docs/HANDOFF.md` twice and the F4d results
  README.
- The old "Nether 78/356 (21.9%)" negative control measured that hybrid state, not stock, so it
  demonstrated that the *pin* was excluded, not that the *fix* was. It also had no committed data or
  command line, and its ~356 regions imply r24-25 against an r60 headline.

All four handlers now route through `GtOrePin.appliesTo(World)`. This reverts The End, every
GalacticGreg/GalaxySpace body, asteroid belts, Underdark and SpectreWorld to stock — the safe
direction, and no measurement existed in any of them to invalidate.

Regression gate on the two previously-shipped dimensions, same jar, `dims=0,7`:

| dimension | mix differs | geometry |
| --- | ---: | ---: |
| overworld | 0 / 1766 | 0 |
| Twilight Forest | 0 / 1764 | 0 |

## Balance: the pin moves the Nether distribution decisively TOWARD the declared table

`scripts/vein-balance.py --dim -1 --bound 0.10`, 18 seed-paired arms, 4771/4770 regions, r20,
4000 seed-clustered bootstrap replicates. Raw output in `balance/vein-balance-dim-1.txt`.

**4 FAIL, 7 inconclusive, 1 PASS** against stock's realised distribution:

| mix | stock | fixed | ratio | 95% CI | verdict |
| --- | ---: | ---: | ---: | --- | --- |
| `ore.mix.sulfur` | 1124 | 849 | 0.756 | [0.725, 0.786] | FAIL |
| `ore.mix.netherquartz` | 436 | 540 | 1.239 | [1.161, 1.325] | FAIL |
| `ore.mix.manganese` | 123 | 172 | 1.404 | [1.266, 1.555] | FAIL |
| `NoOresInVein` | 29 | 3 | 0.102 | [0.000, 0.200] | FAIL |
| `ore.mix.tetrahedrite` | 530 | 511 | 0.965 | [0.912, 1.020] | PASS |

**A prediction from the plan was wrong and is retracted.** It said the two arms should sit *closer
together* in the Nether than in the overworld, because netherrack fills y 0-127 near-solidly so
stock's column probe rarely rejects. The opposite happened: 4 FAIL of 12 here against 2 FAIL of 20
in the overworld. The pin moves the Nether distribution MORE, not less.

But by the bar this project ships on — GregTech's **declared table weights**, not stock's realised
distribution (user decision, 2026-09-05) — the Nether result is far stronger than the overworld's.
Full table in `balance/declared-weights-dim-1.txt`:

| | TV distance from declared weights |
| --- | ---: |
| stock | 0.0853 |
| **pinned** | **0.0379** |
| *(overworld, for scale: stock 0.3032, pinned 0.2826)* | |

The pinned arm is closer to declared on **9 of 11 mixes**, and halves the total-variation distance.
The mechanism is the same one F4d documented in dim 0, and it is visible directly:

| mix | height band | declared | stock | pinned |
| --- | --- | ---: | ---: | ---: |
| `sulfur` | 5-20 (low) | 0.1600 | 0.2370 (**+48%**) | 0.1781 |
| `netherquartz` | 40-80 (high) | 0.1280 | 0.0919 (**-28%**) | 0.1133 |

Stock over-supplies the lowest vein and starves a high one — "high veins lose on low ground",
decided by whichever chunk the route reached first. Relocating the terrain filter to the oreseed
corrects it. `NoOresInVein` going 29 → 3 is the same effect: stock leaves regions empty when the
route-chosen chunk rejects every candidate.

So the FAILs are the fix working, not a regression, and they are the reason the declared-weight
comparison exists.

**Caveat on sample size.** 18 paired seeds, not the 24 the overworld used. The warm probe OOMs at
warm slot 19 of 25 at `-Xmx6G` in dim -1 (`java.lang.OutOfMemoryError`, both arms, same slot), so
the last 7 seeds produced no dump in either arm. The 18 that completed are identical in both arms
and therefore correctly paired. Re-run in batches or at a larger heap to extend it.

## Not covered
- **Block-level ore placement stays route-dependent.** `OreManager.setOreForWorldGen` reads the live
  world at every write, deliberately un-redirected. Report the veincache metric, not a tile-entity or
  block diff.
- **The End (dim 1)** is still excluded on merit; its `resolveVeinPlacement` EBS scan needs
  virginising first.
- **Everything below is about Nether DECORATION, not veins, and is not fixed.**

## Found on the way: the Nether's decoration layer is route-dependent in stock Minecraft

Not fixed this round, deliberately out of scope, and much larger than the vein defect.

`ChunkProviderHell.populate` **never re-seeds `hellRNG`**, unlike every other generator examined:

| provider | `provideChunk` seeds | `populate` re-seeds it |
| --- | --- | --- |
| `ChunkProviderGenerate` | `rand` (`:226`) | yes — `:394`, `:397` |
| `rwg.ChunkGeneratorRealistic` | `rand` (`:158`) | yes — `:479-482` |
| **`ChunkProviderHell`** | `hellRNG` (`:280`) | **no** |

`ChunkProviderHell.populate:459-553` runs the fortress (`:467`), lava lakes (`:476-479`), fire
(`:488-491`), both glowstone passes (`:499-510`), mushrooms (`:518-529`), nether quartz (`:538-541`)
and closed lava (`:546-549`) off one continuous `hellRNG`, and hands the same object to every mod
handler via `PopulateChunkEvent.Pre/Post` and `DecorateBiomeEvent.Pre/Post`. Its state when a chunk
populates is the accumulated history of every `provideChunk` and `populate` before it — i.e. the
route.

Measured on the shipped jar, r60, rows vs spiral, `diff-region-blocks.py <world>/DIM-1`:

**11,111,905 differing blocks across 13,796 of 15,468 common chunks — 89% of the dimension.**

Top transitions are not ore: air/netherrack 1.45M and 1.40M, netherrack/879 1.01M and 0.99M,
lava(11)/netherrack 0.50M and 0.48M, quartz_ore(153)/netherrack 92k and 91k.

**Same-order block floor is noisy** — 2,137 blocks on one jar/settings pair and 9,422 on another. Do
not quote a single floor sample; the instability is consistent with the FML `IWorldGenerator`
dispatch-order finding in `results/2026-09-07-populate-stream-census` (19-22 of 30 positions differ
between back-to-back launches). Against a floor of order 10^4, an 11.1M route difference is still
three orders of magnitude clear.

Written up as "Stream C" in `docs/populate-stream-census.md`, which otherwise describes only the
overworld under `level-type=rwg`. The rubric there does not transfer: stream A bounds a draw-count
skew to the rest of its chunk, stream C has no boundary at all.

### Two pieces of scaffolding this produced, one of which does not work

- **`OracleRngGuard` (`gtnhdet.oracleguard`, default on) — works, and is ours to fix.**
  `TerrainOracle.virginChunk` calls `provideChunk` on the LIVE generator, whose first statement
  re-seeds that generator's `Random`. Harmless wherever `populate` re-seeds; in the Nether it
  permanently reroutes decoration, and the number of oracle calls depends on the oracle's 256-entry
  LRU, hence on the route. The guard swaps every `Random`-typed field on the generator for a scratch
  instance of the same concrete class around the call, matching on `Field.getType()` so no MCP/SRG
  name is spelled. Shown vein-neutral above. RWG's `mapRand` is the same shape in the overworld —
  consumed in `provideChunk:174`, never re-seeded — merely exercised far more rarely.

- **`ChunkProviderHellPopulateMixin` (`gtnhdet.netherpop`, default OFF) — DOES NOT WORK.**
  Intended to give dim -1 the same per-chunk re-seed `ChunkProviderGenerate` uses. The mixin binds —
  the log shows it applied to `net.minecraft.world.gen.ChunkProviderHell` and the `require = 1`
  injector prepared without error — but `NetherPopulateRng.reseed` is never invoked: its one-time
  INFO line appears in no arm, and a same-order A/B differing only in `gtnhdet.netherpop` moved
  15,297 blocks against a 9,422 floor on the same jar, i.e. nothing distinguishable from noise.
  Ruled out: no mod subclasses or replaces the provider (ArchaicFix and VillageNames mix into it but
  add no fields and do not overwrite `populate`); `ChunkProviderServer.populate:313` does call
  `currentChunkProvider.populate`; the flag reads true; no exception is logged. **Root cause
  unknown.** Kept in the tree, defaulted off and marked broken in its javadoc, because the defect it
  documents is real.

  Any future attempt must start with the unconditional first-call probe already in
  `NetherPopulateRng.reseed` — without it, "no re-seed line" is ambiguous between flag off, hook
  never fired, and reflection threw, and that ambiguity cost a measurement round here.

## Reproducing

```
SD=~/.cache/gtnh-determinism/daily-707
SEED=-1636594104014467454
COMMON="PROBE_DIM=-1 PROBE_SEARCH=false PROBE_CX=0 PROBE_CZ=0"

# stock arm                     # fix arm
$COMMON PROBE_EXTRA_ARGS="-Dgtnhdet.orepin.dims=0,7" \
  scripts/run-probe.sh "$SD" $SEED rows|spiral <out>.json 60
$COMMON PROBE_EXTRA_ARGS="-Dgtnhdet.orepin.dims=0,7,-1" \
  scripts/run-probe.sh "$SD" $SEED rows|spiral <out>.json 60

python3 scripts/diff-veincache.py A.veincache.json B.veincache.json --dim -1 --seed $SEED
python3 scripts/diff-veincache.py A.veincache.json B.veincache.json      # and unfiltered

# audit (never in a measurement launch - it changes how often the live world is read)
$COMMON PROBE_EXTRA_ARGS="-Dgtnhdet.orepin.dims=0,7,-1 -Dgtnhdet.orepin.audit=true" ...
$COMMON PROBE_EXTRA_ARGS="-Dgtnhdet.orepin.dims=0,7    -Dgtnhdet.orepin.audit=true" ...   # control
# decode audit lines BY DIMENSION before counting them

# column-probe reachability assertion
$COMMON PROBE_EXTRA_ARGS="-Dgtnhdet.orepin.assertprobe=true" ...
grep "ASSERT armed" <log>      # positive control - must be present
grep -c "PINNED_DRY_RUN" <log> # must be 0
```

Committed: `step0/` (shipped-jar baseline), `step1/` (OW+TF regression gate), `step234/` (stock, fix,
audit, provenance), `step5/` (guard-off arms, assertprobe log), `final/` (shipped-defaults
confirmation), `balance/` (18-seed paired arms, vein-balance output, declared-weight table).

## Route map

The Nether is now a tab in the `gtnh-seedlib` route map for this seed, alongside Overworld and
Twilight Forest. 1262 veins, and `vein-csv.py`'s own independent route check reports **1262/1262
identical, 0 differ** against the spiral walk — a second tool agreeing with `diff-veincache.py`.

```
python3 seedsearch/vein-csv.py <rows>.veincache.json --dim -1 --seed -1636594104014467454 \
    --compare <spiral>.veincache.json --out-prefix veins-nether
tools/build_world_bundle.py --seed -1636594104014467454 --pack daily-707 \
    --veins-nether veins-nether.csv --nether-region <World>/DIM-1/region \
    --level-dat <World>/level.dat --out <tmp>            # then merge dims["-1"] into meta.json
```

**The Nether needed a new surface rule.** Both renderers took the topmost opaque block in a column,
which in a roofed dimension is the bedrock ceiling — the map came out a flat slab of bedrock at
y=127 with no terrain visible. `worldrender.scan_world(roofed=True)` instead takes the topmost
opaque block that has `ROOF_CLEAR` non-opaque blocks above it AND sits below `ROOF_Y=127`. Both
halves are needed: the clearance test alone still picks the roof, because the roof's own top face
has empty sky above it. Columns of solid rock floor-to-ceiling have no walkable floor at all and
fall back to the topmost opaque block below the roof, so they render as filled ground rather than
as holes. Result: median surface y=72, and netherrack/lava/soul sand/ash as the dominant surface
blocks instead of bedrock.

Known rough edge: 81.4% of the Nether surface has a palette colour, against 99.1% for the
overworld — `BiomesOPlenty:flesh`, `Natura:Cloud` and `minecraft:nether_brick` among others fall
back to the biome wash. Fortresses therefore do not render in their own colour.

**Spawn does not mean the same coordinate in every dimension.** The viewer anchored its distance
rings, its opening view and every "from spawn" number to `meta.spawn` verbatim. That is right for
the Twilight Forest, which is 1:1 with the overworld, and wrong for the Nether, which is 1:8: the
rings landed 183 blocks off centre (`hypot(152-19, 144-18)`), which is visible on the map. The
bundle now carries `dims["-1"].coordScale` and the viewer divides by it, so the anchor is the point
that *corresponds* to spawn — (19, 18) here, not (152, 144). Verified at zoom 0 where the map is
exactly 1 px per block: ring centre moved 183 px onto the intended point.

The ratio is not a constant. Vanilla is 8.0, but GTNH exposes it as `hodgepodge.cfg`
`netherPortalRatio` (range 0.125-64), so `--nether-portal-ratio` bakes the pack's value into the
bundle rather than letting the viewer assume. daily-707 uses 8.0.

The Nether vein CSV was regenerated with `--spawn 19 18` for the same reason — `vein-csv.py`
defaults to `--spawn 0 0`, so the first bundle's baked `d` values were distances from the origin
while the rings were drawn somewhere else again. 1252 veins after the re-run, still 0 route-unstable.
