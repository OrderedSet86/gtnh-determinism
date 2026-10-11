# Mineshaft corridor chest pin — multiseed validation

2026-09-17. Measures the mineshaft chest pin's **before/after delta**, which
results/2026-09-13-hammer-prefilter-validation §1 explicitly listed as missing: *"a stock-pin baseline
(`-Dgtnhdet.mineshaftpin=false`, same arms) was NOT run — the pin's before/after delta is
uncharacterized; 0.7% is the 'after' only."*

**Read that run first.** Route stability on the pin was already measured there — 2.8.4, warm r30, 25
seeds, 4 existence differences of 564 minecart chests. This run is not a replacement for it and is
**not directly comparable**: different pack (2.9.0-beta-3), different radius (r24), and a different
seed list, so 4/564 and 1/318 are separate samples. Only the 19 → 1 delta below is internal to a
single experiment.

It also supplies the block-level attribution neither run had: which of the two deliberately-unpinned
mechanisms the residual belongs to.

## Method

25 seeds, radius 24 (2401 chunks), dedicated server on a throwaway copy of the 2.9.0-beta-3 install
(GT 5.09.54.133), one jar throughout with `gtnhdet.mineshaftpin` as the only lever. Four arms per seed
— rows and spiral × pin on and off — 100 probe runs, zero failures. `PROBE_SEARCH=true`
`PROBE_ENTITIES=true`; compared with `scripts/diff-chests.py --type=EntityMinecartChest`, which
separates existence / contents / NBT and reads each arm's build stamp so a confounded A/B announces
itself.

The control arm is not optional. Without it, "0 differences" cannot be told apart from "this sample had
no route dependence to find".

## Result

| Arm | chests A / B | existence | contents | NBT | seeds differing |
|---|---|---|---|---|---|
| CONTROL `mineshaftpin=false` | 341 / 342 | **19** | 0 | 0 | 13 / 25 |
| TREATMENT `mineshaftpin=true` | 318 / 319 | **1** | 0 | 0 | 1 / 25 |

**Absolute residual: 1 chest across 25 seeds.** Not zero. Contents and NBT are clean in both arms, so
the F10 box-relative fork continues to hold and nothing here is an NBT-only pass.

The chest totals differ between arms (341 vs 318) by design: the pin replaces a stream-derived draw
with an independent function of (seed, piece anchor, draw index), so a different set of chests exists.
That is the intended change, not drift.

## The residual is NOT a chest-draw failure

Seed `-987654321012345678`, one chest at **(-555, 27, -494)**, present in the spiral walk and absent in
rows. Re-ran that seed in both orders with `PROBE_DUMP=-35,-31` to get raw blocks for the containing
chunk:

| | rows | spiral |
|---|---|---|
| block at the chest cell (local 5,27,2) | `minecraft:stone` | `minecraft:rail:1` |
| mineshaft-signature blocks at local z ≤ 6 | 31 | 45 |

In the rows arm that cell is **undisturbed stone** — the corridor piece was never built there at all,
while the rest of the chunk's mineshaft (local z ≈ 8–13) is present in both. So the divergence is the
**presence of the component**, not the `nextInt(100)` chest-existence draw that the mixin pins. The pin
is doing its job; what moved is whether the corridor exists to hold a chest.

That is the behaviour `isLiquidInStructureBoundingBox` is documented to produce — a window-scoped live
read at the head of `addComponentParts` that "can remove the component in one walk order before another
window builds it".

**Correcting an intermediate conclusion:** on first seeing flowing water at local (12,23,12–13) in the
rows dump I took it as the trigger. It is not evidence. The dumps show that liquid is **identical in
both arms** — same two `flowing_water` blocks, same positions. The check runs against the LIVE world at
component-build time, and water moves during population, so the final state a dump records cannot show
what the box looked like when it was tested. The liquid explanation remains the best-supported
candidate because it is the documented mechanism and the observation is component-level, but this run
does **not** prove it. Proving it needs an instrumented `isLiquidInStructureBoundingBox` that logs its
verdict and the box contents at call time.

## What this does and does not license

- The chest-existence pin itself is **not** contradicted by any observation here: 24 of 25 seeds are
  byte-identical on existence, contents and NBT, and the one exception is a different mechanism.
- It does **not** license calling mineshaft loot route-independent. The absolute residual is 1, the
  target is 0, and the cause is a read the mixin deliberately does not pin.
- The second unpinned read, the chest cell's air check, produced **no** observed divergence in this
  sample — but a sample of 25 seeds / ~330 chests cannot distinguish "never happens" from "rarer than
  1 in 330", so this is not evidence for pinning or not pinning it.

## Next lever: `isLiquidInStructureBoundingBox` has TWO route dependencies, not one

Reading the method (vanilla `StructureComponent`, shared by every structure) changes the shape of the
fix. It scans the six faces of the piece's bounding box and aborts the component if any is liquid:

```java
int i = Math.max(this.boundingBox.minX - 1, p_74860_2_.minX);   // p_74860_2_ = the POPULATE WINDOW
...
int l = Math.min(this.boundingBox.maxX + 1, p_74860_2_.maxX);
... world.getBlock(k1, j, l1).getMaterial().isLiquid() ...
```

1. **The reads are live.** `world.getBlock` sees liquid that neighbouring chunks' population has
   already placed — lakes, water flowing in from a neighbour. This is the `TerrainOracle` case, the
   same one `DungeonMixin` and `WorldEditorMixin` answer from virgin terrain.
2. **The scanned volume is window-clipped.** Every bound is an intersection of the piece box with the
   populate window currently building it. Two windows scan **different sub-volumes of the same piece**
   and can therefore reach different verdicts *from identical terrain*. Virginising the reads does not
   touch this.

**(2) is the better-supported explanation of this run's residual**, and the data already discriminates:
the block dumps show liquid is **identical in both arms**, yet the verdict differed. A pure live-read
difference requires the terrain to differ; a window-clipping difference does not. Not proof — the
reads happen mid-population and transient liquid is unobservable in a final dump — but (2) is the
parsimonious fit and (1) alone is not.

So a fix has to do both: scan the **full piece box**, unclipped, **and** read virgin. Either alone
leaves a route dependence. The clean shape is a `@Redirect` on the
`isLiquidInStructureBoundingBox` call **at the mineshaft call sites** (vanilla `Corridor` and
`MesaCorridor`), not a mixin on the method itself — that method is shared by strongholds, villages and
every other vanilla structure, and this evidence covers none of them.

**Why this is not a small change.** Unclipping the box changes verdicts against stock in both
directions: a piece stock accepted because the window it happened to build in saw no liquid can now be
rejected, and vice versa. It adds and removes whole corridors, so chest counts, spawner counts and the
mineshaft footprint all move. It also puts a per-piece surface-area scan through `TerrainOracle`, whose
cost has not been measured at that call rate.

**Before writing it:** run an instrumented build that logs the verdict, the piece box and the window
box at each call. That distinguishes (1) from (2) directly instead of by inference, and it is cheap —
one probe run on seed `-987654321012345678`, where the divergence is known to reproduce.

## That instrumented run happened — and it refutes the section above

`MineshaftLiquidTraceMixin` (`-Dgtnhdet.mineshafttrace=true`) logs, per `addComponentParts` call, the
piece box, the window box, and the shell scan evaluated four ways: {clipped, full} x {live, virgin}.
Seed `-987654321012345678`, r24, rows and spiral. 468 calls in rows, 532 in spiral.

**Finding 1 — the liquid check is NOT the cause of this run's residual.** The piece containing the
divergent chest, `-557,27,-505..-555,29,-486`, is visited by the **same three windows with the same
boxes in both walks**, and all four verdicts are `false` every time. The component is not being
rejected by the liquid check in either arm. Mechanisms (1) and (2) are both ruled out for this chest.

**Finding 2 — "unclip the box" would have made things WORSE, not better.** I predicted the full-box
scan would be window-independent. Measured, it is the opposite:

| variant | pieces whose verdict depends on which window asked (rows / spiral) | disagreements across walks, 230 common pieces |
|---|---|---|
| stock (clipped + live) | 1 / 0 | 2 |
| **full + live** | **44 / 33** | **41** |
| clipped + virgin | 0 / 0 | **0** |
| full + virgin | 0 / 0 | **0** |

Unclipping makes the scan read blocks outside the current window that neighbouring population has
already modified, so it samples *more* mutable terrain and becomes far more route-dependent. The
recommendation above — "scan the full piece box AND read virgin" — is wrong on the first half. The
correct lever is **virgin reads alone**; clipping is not the problem.

**Caveat on that table.** Stock returns `true` in only 1 of 468 calls, so the liquid check almost never
rejects anything here and the metric has very little dynamic range. The zeros for the virgin variants
are consistent with "virgin terrain has no liquid near these boxes" as much as with "virginising fixes
it". Treat 0/230 as necessary, not sufficient.

**Finding 3 — the real mineshaft route dependence is further upstream: the piece graph itself.**
Distinct pieces visited: **rows 230, spiral 267 — 37 pieces exist only in the spiral walk, and none
only in rows** (rows is a strict subset). Both walks generate the same 2401 chunks, so this is not
coverage: the mineshaft's component layout differs by traversal order. That is upstream of the chest
draw and of the liquid check alike, and it is a much larger effect than either. It does not explain
the single differing chest either — that chest's piece is in both sets — so there is at least one
further mechanism still unidentified, most likely the chest-cell air check inside
`generateStructureChestContents`, which this trace does not observe.

**Revised next step.** Not the full-box rewrite. Instead: (a) instrument `generateStructureChestContents`
to catch the air check and close out the single chest, and (b) treat the 37-piece layout divergence as
its own investigation, since it dominates everything measured here.

## Reproduce

```
~/.cache/gtnh-determinism/ms-out/drive.sh          # 25 seeds x 4 arms
python3 scripts/diff-chests.py ms-out/off-rows ms-out/off-spiral --type=EntityMinecartChest
python3 scripts/diff-chests.py ms-out/on-rows  ms-out/on-spiral  --type=EntityMinecartChest
```
Raw output in `~/.cache/gtnh-determinism/ms-out` (per-arm dirs plus `attrib/` for the block dumps).
