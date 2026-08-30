# F11: the F10 site hook was missing most village chests

GTNH daily-707, `~/.cache/gtnh-determinism/daily-707`, fix jar
`gtnhdeterminism-v0.5-main.25+884548e365-dirty` (md5 `34f289274d69dfac82e818291247ec03`).

This started as Stage 4's prerequisite — measure the piece-to-chest-site table so the prefilter can
predict village loot — and the measurement immediately contradicted an assumption F10 was built on.

## What the trace found

`-Dgtnhdet.chesttrace=true` logs, for every chest F10 refills, which structure piece it belongs to and
what coordinates the fork is derived from. On seed `-8802246784027196783`, radius 12:

```
10 of 71 chests    component-relative
61 of 71 chests    absolute-position fork
```

`StructureComponentChestMixin`'s javadoc claimed "Every vanilla and mod village, mineshaft, stronghold
and pyramid piece reaches its chest through these two methods, so one hook covers all of them with no
per-mod work." That is false for this pack. Adding the caller to the trace named the real fillers:

| category | filled by |
| --- | --- |
| `villageBlacksmith`, `vn_*` | `astrotibs.villagenames.village.biomestructures.*` inside `addComponentParts` |
| `TinkerHouse`, `TinkerPatterns` | `ComponentToolWorkshop.generateStructureCraftingStationContents` / `…PatternChestContents` |
| `railcraft:workshop` | `ComponentWorkshop.placeChest` |
| `dungeonChest` (Witchery) | `WitcheryComponent` declares a same-named `generateStructureChestContents` that shadows vanilla's rather than calling it |

VillageNames replaces the vanilla village pieces, so even the vanilla-category `villageBlacksmith`
chest no longer goes through the vanilla method.

**This was not a determinism defect** — the absolute-position fork is deterministic, and every chest
verification this project has recorded stays valid. What it cost was the two properties the
component-relative fork exists for: contents that survive a terrain shift, and contents computable
from a structure layout alone. The second is exactly what Stage 4 needs, so Stage 4 was blocked.

## The fix

`StructureStartPartsMixin` redirects the `addComponentParts` call inside
`StructureStart.generateStructure` — the only place in the game where component parts are built — and
pushes the piece's class and bounding box for the duration. One vanilla call site, every structure,
no per-mod work. The site stack is a `ArrayDeque` because the older per-chest hook pushes a more
precise site inside this one, and popping the inner must restore the outer.

Coverage, same seed:

| | before | after |
| --- | ---: | ---: |
| component-relative | 10 | **24** |
| absolute | 61 | 47 |

The 47 that remain absolute are correct: 191 of 196 across the 4-seed corpus are
`rwg.world.ChunkGeneratorRealistic.func_73153_a`, i.e. `WorldGenDungeons` rooms, which have no
component. The rest are 4 Witchery `setDispenser` calls and 1 Thaumcraft greatwood tree, all reached
outside `StructureStart.generateStructure`.

## The first version of this fix was wrong

It derived the local coordinates as `abs - box.min`, including Y, on the reasoning that a village
piece's box is offset to the ground it sits on so the difference is terrain-invariant. **The table
disproved that.** For one `PlainsWeaponsmith1` class, `localY` came out as 1, 49 and 14 in different
villages; `PlainsStable2` gave −16, i.e. the chest sits *below* its own box origin. A mod piece's
`box.minY` is a nominal value, not a ground anchor, so `y - minY` just re-encodes terrain height.

That version therefore re-rolled 16 chests per seed and bought nothing — the chests stayed exactly as
un-computable-from-a-layout as the absolute fork they replaced. Determinism held throughout, so
nothing was broken; it was churn without benefit, and only the measurement caught it.

**Corrected:** box-relative sites mix `y = 0`. The fork uses `(box.minX, box.minZ, piece class, local
x, local z)`, all of which are settled at structure-layout time. Caller-local sites keep their Y,
which is a genuine terrain-free structure coordinate.

Dropping Y is safe, and that is checked rather than assumed:

```
box-relative fills: 82 across 50 piece instances
same-chest refills (same absolute position — harmless, the fork makes them idempotent): 20
GENUINE distinct chests sharing a local XZ within one piece: 0
```

The refills are worth knowing about independently: a piece spanning several chunks runs
`addComponentParts` once per intersecting chunk box, and the mod fillers — unlike vanilla's, which
skips a position that is already a chest — refill it every time. F10 makes that idempotent.

## Determinism re-verified after the change

Seed `-777`, radius 10, **124 chests / 1817 item stacks** compared (checked non-empty — a chest diff
over zero chests reports success):

| test | existence | contents | NBT |
| --- | ---: | ---: | ---: |
| launch (rows vs rows) | 0 | 0 | 0 |
| route (rows vs spiral) | 0 | 0 | 0 |

## Artifacts

- `chesttrace.txt` — 328 raw trace lines, 4 seeds, corrected jar.
- `piece-chest-sites.txt` — the aggregated table: piece, loot category, site source, local sites.

Several pieces list more than one local XZ. That is the piece's orientation (`coordBaseMode`), not an
instability — and `Prefilter.predictVillagers` already computes `coordBaseMode`, so the prefilter can
key the table by it.

## Where this leaves Stage 4

Unblocked, not done. The prefilter now has everything it needs in principle: `villageStarts()` gives
piece class and bounding box, `predictVillagers` gives orientation, this table gives local chest sites,
and the fork is `(minX, minZ, piece.hashCode(), lx, lz)` with no terrain term. What remains is to build
the module, roll the contents off the live `ChestGenHooks` into a throwaway `TileEntityChest`, and
golden-test it with the four independent counters (existence, contents, NBT, piece recall).

Two things to carry into that work:

- The table is measured from 4 seeds. Any piece not present in those seeds is absent from the table,
  and a missing entry means silently predicting no chest. The module must report unknown piece classes
  it encounters rather than skipping them quietly.
- `ComponentToolWorkshop` fills `TinkerHouse` through *both* routes (box-relative and caller-local, the
  latter at a different local site). A module that assumes one route per piece will be wrong about it.
