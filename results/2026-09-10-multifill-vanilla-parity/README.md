# Multi-fill chest loot: vanilla fills once, and 0.11 did not

Seed `-1636594104014467454`, GTNH 2.9.0-beta-3 (`~/.cache/gtnh-determinism/beta3`), radius 60,
`level-type=rwg`, centre chunk (9,9).

Release 0.11 (`c5c27e7d97fa`) added a batch rebuild to `ChestFillContext.refillChest` so that a chest
filled more than once would keep every batch rather than collapse to one. It reached the Thaumcraft
hilltop circles it was aimed at and **silently mis-indexed every Village Names chest**, and the
quantity it was restoring at those chests turned out not to be a quantity vanilla ever produces.

This corrects both halves. It supersedes the village portion of
`results/2026-09-08-chest-recall-and-double-fill/README.md`.

## The counter bug

`gtnhdet$fillIndex` recovered the batch index from a single last-seen inventory, on the documented
assumption that *"repeat fills of one inventory are consecutive on one thread"*.

Measured at `vn_mason` (837,64,1111), table 1..6, four fills. `refillChest` accumulates `rolls += n`
and traces the cumulative total, so a working 4-deep rebuild must emit `1, 3, 7, 12`:

| | batch 0 | 1 | 2 | 3 |
|---|---:|---:|---:|---:|
| stage-0 prefilter predicted | 1 | 2 | 4 | 5 |
| game actually drew (cumulative) | 1 | 1 | 1 | 3 |

`1, 1, 1, 3` decodes to a fill index of **0, 0, 0, 1**. The repeats are not consecutive, so the
counter kept resetting.

This is the same defect as the vanilla-dungeon attempt counter — one slot standing in for per-key
state — which `RwgDungeonAttemptMixin` documents after it cost four diagnostic iterations. The
pattern was left in `ChestFillContext` in the same release that fixed it in the dungeon mixin.

## Why the repeats are not consecutive

`astrotibs.villagenames.village.biomestructures.SwampStructures$SwampMasonHouse.func_74875_a:7581`,
and the same idiom in Weaponsmith, Tannery, Library and the rest — one call site each, no loop:

```java
this.placeBlockAtCurrentPosition(world, chestBlock, 0, u, v, w, structureBB); // clip-guarded
world.setBlockMetadataWithNotify(...);                                        // NOT guarded
TileEntity te = world.getTileEntity(...);                                     // NOT guarded
if (te instanceof IInventory)
    WeightedRandomChestContent.generateChestContents(random, ..., te, ...);    // NOT guarded
```

Only the block placement is clipped. `MapGenStructure.generateStructuresInChunk:90-101` builds a clip
box `[16k+8 … 16k+23]²` and `StructureStart.generateStructure:48` calls `addComponentParts` once per
clip box intersecting the **component** box. Those boxes tile the plane without overlap, so exactly
one invocation places the chest and every later invocation refills the tile entity already standing
there. Separate invocations, with other chests filled in between — hence the reset.

Observed fills against clip boxes intersecting each component's stage-0 box:

| piece | observed | clip boxes |
|---|---:|---:|
| SwampMasonHouse | 4 | 4 |
| SwampWeaponSmithy | 4 | 4 |
| ComponentWorkshop (Railcraft) | 4 | 4 |
| SavannaButchersShop2 | 2 | 2 |
| SwampLargeHouse | 2 | 2 |
| SwampHutFarm | 2 | 2 |
| TaigaSmallHouse3 | 2 | 2 |
| TaigaSmallHouse5 | 2 | 2 |
| SwampLibrary | 3 | 4 |
| PlainsTannery1 | 3 | 4 |
| SavannaMediumHouse1 | 2 | 4 |
| SwampFletcherHouse | 2 | 4 |

Eight exact. The four deficits are invocations that ran *before* the chest-placing one, found
`te == null`, and failed the `instanceof`.

**So stock's loot quantity at these chests is a function of chunk population order — anywhere from 1
to N.** There is no single stock number to match, which is why the count could not simply be measured
and written down.

## The anchor: vanilla fills exactly once

`StructureComponent.generateStructureChestContents:788`:

```java
if (sbb.isVecInside(i1, j1, k1) && world.getBlock(i1, j1, k1) != Blocks.chest) {
    world.setBlock(i1, j1, k1, Blocks.chest, 0, 2);
    ...
    WeightedRandomChestContent.generateChestContents(rand, items, tileentitychest, count);
}
```

Clip box **and** "not already a chest". Vanilla fills a structure chest once, ever, however many chunk
boxes intersect the piece. Railcraft's `ComponentWorkshop.placeChest` reproduces that and adds an NBT
`hasMadeChest` flag on top. Village Names dropped both guards; that omission is the whole defect.

**Vanilla's number is 1, and that is what the fix now produces.**

Thaumcraft hilltop is a different phenomenon and stays at 2. `WorldGenHilltopStones.func_76484_a` has
two deliberate call sites — traced at `:104` and `:105`, bytecode offsets 432 and 450 — each with its
own `ChestGenHooks.getInfo` capture. Two batches is what that generator asks for, and it is already
route-pure because both calls sit in one pass.

## The fix

`gtnhdet$fillIndex` now counts fills per inventory **within the innermost site scope**, in an
`IdentityHashMap` living on the `Site` frame rather than in one thread-wide slot.

| site kind | scope | index | batches | matches |
|---|---|---|---|---|
| component, box-relative (Village Names, Railcraft) | one `addComponentParts` invocation | always 0 | 1 | vanilla's guarded single fill |
| component, caller-local (vanilla path) | same | always 0 | 1 | vanilla |
| absolute, `piece=none` (hilltop) | the population barrier | 0, 1 | 2 | the two call sites in `WorldGenHilltopStones` |

Repeat invocations become idempotent by construction: each rebuilds the same single batch, so the
count no longer depends on how many chunks populated or in what order. Identity rather than position
is the key, because a re-placed chest is a different `TileEntityChest` — exactly the case where stock
also starts from empty.

Batch 0 still uses `fork(inv)` untouched, so every singly-filled chest in the world is bit-identical.
The map dies with its frame, which also removes the strong `TileEntity` reference the old
`LAST_FILLED` slot held indefinitely.

The trace now carries `fillidx=` directly. Reconstructing it from cumulative roll totals is what this
investigation had to do, and it is the only reason the bug survived a release.

## Measurements

Four cold radius-60 arms, centre chunk (9,9), `PROBE_SEARCH=true`, `-Dgtnhdet.chesttrace=true`:
`rows`, `rows2` (same order, noise floor), `spiral`, and `nobatch` (`rows` with
`-Dgtnhdet.chestbatch=false`).

### The counter now reads what it means to

839 fills over 801 positions, 32 of them filled more than once:

| | fillidx 0 | fillidx 1 |
|---|---:|---:|
| all fills | 819 | 20 |
| pieces reaching fillidx > 0 | — | `piece=none` × 20, i.e. hilltop only |

**No component chest reaches batch 1.** All 20 second batches are Thaumcraft hilltop circles. The 12
Village Names positions that are filled 2-4 times report identical `rolls` on every invocation —
each rebuilds the same single batch, so repeat invocations are idempotent and the count no longer
depends on population order.

### The discriminating A/B

`-Dgtnhdet.chestbatch=false` against default, same jar, same arm, the lever as the only variable:

| | result |
|---|---|
| existence differences | **0** |
| contents changed | 20 |
| of which village-piece sites | **0** |
| of which hilltop | **20** |
| stacks at the changed sites | 142 -> 249 |

This is the test that would have caught the original bug: before the fix the same lever moved village
sites too. Under 0.11 it moved 29 sites for 169 -> 300.

### Balance

Per-site stack counts, 0.11's world against this one, at every position traced as multi-filled:

| | 0.11 | fixed |
|---|---:|---:|
| 11 Village Names sites | 58 | **34** |
| 20 hilltop circles | 249 | **249** |

Hilltop is unchanged site for site — 12/13/12/12/11/10/13/12/12/12/14/13/14/13/14/12/13/12/11/14 in
both. Village loot drops by 24 stacks across 11 chests, to the amount vanilla's guard produces. Note
0.11's village numbers were not a fixed quantity to begin with: they came from a fill index that
reset on interleaving, so they varied with walk order.

### Predictor

`prefilter-judge-chests.py`, radius 60, at **identical predictor scope** (same stage-0 sections
enabled — villages, roguelike, strongholds, Witchery — so this is not "predicted fewer things"):

| | before | after |
|---|---:|---:|
| predicted positions | 967 | 967 |
| present in the corpus | 967 | 967 |
| predicted but absent | 0 | 0 |
| contents identical | 948 | **954** |
| **miscategorised** | **6** | **0** |
| NBT identical | 948/948 | 954/954 |
| recall (accounted / corpus) | 1016 / 1729 | 1016 / 1729 |

Recall is unchanged, so the six were fixed rather than dropped.

### Route purity, and what is left

Same-order noise floor first, because without it a cold-vs-cold difference cannot be attributed:

| | chests | existence | contents |
|---|---|---:|---:|
| `rows` vs `rows2` (same order) | 1745 / 1745 | **0** | **0** |
| `rows` vs `spiral` | 1745 / 1738 | 37 + 30 | 2 |

The noise floor is zero, so the `spiral` residual is real route dependence. Decomposed:

- **19 structures shifted in Y by 1-2 blocks**, contents byte-identical on both sides. Counted twice
  by a position-keyed diff (once as only-rows, once as only-spiral), which is 38 of the 67. Mostly
  `ComponentToolWorkshop` and `VillageComponentPhotoshop` — village piece Y anchoring reads live
  terrain.
- **29 true chest-existence differences** (18 only-rows, 11 only-spiral), Y 11-53, composition
  near-symmetric on both sides (11 `dungeonChest` each, plus TiC workshop and Witchery pieces).
- **2 chest-content differences**, (804,65,1119) and (142,88,-468). Neither appears in the chesttrace
  at all — F10 never refills them — and both are byte-identical between `rows` and
  `chestbatch=false`, so the batch logic has no influence on either. Their contents (Thaumonomicon,
  vampirebook, TConstruct manuals) are the `ComponentVillageBookShop` shape that
  `chest-nohooks.json` already records as writing slots directly, where "no hook sees it and neither
  jar can touch it".

**None of this residual comes from this change**: the `chestbatch` lever moves 0 chests' existence
and 20 chests' contents, all hilltop. It is reported here because it had never been measured —
`results/2026-09-08-vanilla-dungeon-determinism/README.md` closes with "the chest-level radius-60
figure has not been re-measured with the fix in", and this is that figure. **The residual is 29
existence + 2 contents, and the target is zero.**

### Multi-seed

25 seeds (`results/2026-09-07-roguelike-multiseed/seeds.txt`), warm, radius 30, both walk orders,
sharded four ways. Restricted to chunks both walks populated, and Y-shift pairs separated as above:

| | count |
|---|---:|
| raw existence differences | 201 |
| in chunks both walks populated | 200 (1 window artifact) |
| of those, Y-shift pairs | 36, accounting for 72 |
| **true existence differences** | **128** |
| **contents differences** | **9** |

`diff-chests.py` reports this as "25/25 seeds differ". That headline is dominated by the existence
class, which is the pre-existing residual, not this change. The nine contents differences are what
this fix could plausibly have touched, and none of them is a village multi-fill site or a hilltop
circle:

| container | n | attribution |
|---|---:|---|
| `TileApiary` | 7 | Forestry `naturalistChest` is registered 0..0. `refillChest` deliberately keeps the caller's count there, because deriving it would answer 0 and empty every bee house — see the `degenerate` branch. That count comes off the populate stream, so it stays route-dependent by design. |
| `TileEntityChest` | 2 | (142,88,-468) is the `ComponentVillageBookShop` case attributed above. (127,65,304) on seed -8790602067241017142 is **not attributed** — the multi-seed arms ran without `chesttrace`, so there is no per-fill record for it. |

So the apiary residual is a known, deliberate trade-off with a stated reason, and one chest on one
seed is genuinely unexplained. **Absolute residual across 25 seeds: 128 existence + 9 contents, of
which 1 content difference is unattributed.**

### What the existence differences are

Re-run with `-Dgtnhdet.chesttrace=true` so each differing chest could be attributed to the generator
that filled it (`~/.cache/gtnh-determinism/multifill-out/`, 131 differences on this run):

| n | generator |
|---:|---|
| **125** | vanilla `WorldGenDungeons` room |
| 2 | `ComponentToolWorkshop` / TinkerHouse |
| 1 | `ComponentWizardTower` / towerChestContents |
| 1 | `ComponentShack` / (no-hooks) |
| 2 | untraced (`TileEscritoire`, `StencilTableLogic`) |

**No Thaumcraft hilltop circle appears.** Hilltop is the only generator that fills one inventory twice
in a single pass, so `fillidx` separates it cleanly, and every differing chest is single-batch. The
0.11 hilltop placement fix holds across all 25 seeds.

Vanilla dungeon chests differing per seed: **mean 5.0, median 4, max 13, and only 1 of 25 seeds is
clean.**

## This contradicts the vanilla-dungeon result, and the dungeon fix owns it

`results/2026-09-08-vanilla-dungeon-determinism/README.md` reports the three-mechanism fix reaching
**0 existence diffs** and "24 of 24 seeds identical between `rows` and `spiral`". That was measured
**attempt-level** — `[dungeonattempt] built=` verdicts at radius 30, centre (0,0). Measured
**chest-level**, dungeon rooms differ on **24 of 25 seeds**.

Two independent measurements agree, so this is not noise and not a window artifact:

| measurement | dungeon-chest existence differences |
|---|---|
| cold r60, 1 seed, noise floor **0** (`rows` vs `rows2` bit-identical) | 11 only-rows + 11 only-spiral |
| warm r30, 25 seeds, shared chunks only | 125 |

### Resolved: the rooms are fine, the chests inside them move

Re-run with `-Dgtnhdet.dungeontrace=true` alongside `chesttrace`, 25 seeds, both orders, warm r30,
compared with the new `scripts/diff-dungeons.py` (boot world excluded):

| | rows | spiral |
|---|---:|---:|
| rooms built (`built=true` anchors) | 2082 | 2083 |
| **room existence differences** | \_ | **1**, on 1 of 25 seeds |
| **rooms in BOTH arms whose chests differ** | \_ | **37** (94 chest positions) |
| of those, a different *number* of chests | \_ | **20** |

**The three-mechanism dungeon fix holds.** Room existence is 1 difference in 2082 rooms — the
attempt-level "0 diffs" claim was substantially right, and the chest-level numbers were never
evidence against it. What they were measuring is chest *placement inside identical rooms*:

```
room (267, 16, 8)     rows (267,16,6) (267,16,10)     spiral (266,16,6) (268,16,10)
room (-50, 46, -76)   rows (-49,46,-79) (-47,46,-77)  spiral (-47,46,-78)
```

The first is the same two chest slots with X drifted by one; the second is a slot that failed
entirely in one arm.

The cause is unpinned draws and live reads in vanilla's chest loop, which `WorldGenDungeonsScanMixin`
never covered:

```java
for (int l2 = 0; l2 < 2; ++l2)                       // two chest slots
    for (int i3 = 0; i3 < 3; ++i3) {
        int j3 = x + rand.nextInt(l * 2 + 1) - l;    // nextInt ordinal >= 2 — NOT pinned
        int k3 = z + rand.nextInt(i1 * 2 + 1) - i1;  // NOT pinned
        if (world.isAirBlock(j3, l1, k3)) { ... }    // isAirBlock ordinal >= 2 — NOT pinned
    }
```

The mixin pins `nextInt` ordinals 0-1 (the room half-extents), `getBlock` ordinal 0 and `isAirBlock`
ordinals 0-1 (the scan). Everything above is ordinal >= 2 and still comes off the shared populate
stream against live terrain. Because a room only reaches this loop when it is built, and each
built room consumes a variable number of shared draws here, the stream position for *later* dungeon
attempts in the same chunk also moves — so the effect compounds within a chunk.

Fix shape: extend the same treatment — a per-room fork for the offset draws, `TerrainOracle` for the
air and adjacent-solid reads. That is the fourth input to vanilla dungeon determinism, after the
attempt coordinate, the scan terrain and the room extents.

None of this is the multi-fill fix: the `chestbatch` lever moves **0** chests' existence.

## Open

- **Railcraft `ComponentWorkshop` is triple-guarded and still traced 4 fills** at (855,64,1120).
  By source and bytecode — `hasMadeChest` (persisted to NBT), `isVecInside`, and
  `getBlock != Blocks.chest`, with the flag set *before* the block test — it cannot fill a position
  twice within an instance, and the block test blocks a second instance from refilling an existing
  chest. Under this fix it lands on one batch either way, so nothing depends on the answer, but the
  contradiction is unexplained and the source read may be wrong.
- **The population barrier pops on `@At("RETURN")`, not in a `finally`.**
  `ChunkPopulateBarrierMixin` pushes its `Site` at HEAD and pops at RETURN, so a `populate` that
  throws leaves the frame on the `SITE` stack. That leak predates this change — a stranded `Site` was
  already never reclaimed — but the frame now also carries an `IdentityHashMap` holding the tile
  entities filled during that call, so the per-exception cost goes from one small object to a handful
  of `TileEntity` references. Bounded by the chests in one chunk population, and only reachable on a
  worldgen exception that would be a failure in its own right. Not fixed here because changing the
  barrier's pairing is a behaviour change on a hot path and deserves its own measurement.
- **Only one seed.** The clip-box table above is one world. The mechanism is a property of the code
  and does not need re-measuring, but the per-piece fill counts are not claimed to generalise — and
  under this fix nothing reads them, which is the point.
