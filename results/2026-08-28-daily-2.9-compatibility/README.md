# 2026-08-28 — GTNH daily (2.9.0-nightly) support, without losing 2.7.4

**The jar now works on the whole 2.7.4 → daily range from one build.**

> ## 70,348 differing blocks remain on a route pair. That is NOT a good result.
>
> The target for this project is **zero**. A world that differs by 70k blocks depending on which
> direction the player walked is still a world where the seed does not fully determine the terrain.
> The −81% reduction next to that number is a measure of progress, not of success, and it should
> never be quoted without the absolute residual beside it.
>
> Specifically **not** claimed here: that the remaining categories are benign. "Decoration" and
> "settling" are labels for prioritising work, not verdicts. See rule 7 in `docs/HANDOFF.md` —
> the classifier is a filter and filters hide things.

Route divergence on daily drops from 97.8% of chunks with no jar to 25.8% with it. Vein **identity**
is 99.0% route-stable — 37 of 3,627 ore-involved blocks change material. Vein **placement** is not,
and was never claimed to be: the other 99% of ore differences are host-stone and present/absent
flips, unchanged in magnitude from 2.7.4.

> **Correction (same day).** An earlier revision of this file claimed "zero `gt.blockores`
> transitions" on daily. That was wrong. It came from grepping `diff-region-blocks.py` output, which
> prints only the **top 40** transitions; the largest ore transition is 230 and the cut-off was 303,
> so every ore row was below the fold. The real figure is 3,627 ore-involved blocks. Full inventory
> below, produced by `scripts/inventory-region-diff.py` and `scripts/ore-route-report.py`, which
> account for 100% of differing blocks precisely so this class of mistake cannot recur.

Pack under test: `daily-2026-08-28+707`, config `2.9.0-nightly-2026-08-28` (GT `5.09.54.115`,
RWG `alpha-1.5.2`, Roguelike `1.6.6`, LootGames `2.2.14`, EFR `2.6.57`, EndlessIDs replacing NEID).
Backwards-compat pack: GTNH `2.7.4` server (GT `5.09.50.119`, RWG `alpha-1.5.0`, Roguelike `1.6.0`,
LootGames `2.1.4`, NotEnoughIDs, no EFR).
Jar `gtnhdeterminism-v0.5-main.20+372c8ad66a-dirty`, md5 `830e50d3cf5fe2f59cc8d1256b635b20`.
Seed `-777`, `scripts/run-probe.sh` (cold JVM per run).

## What was broken

Every mixin target except GregTech's is unchanged between the 2.7.4-era and daily versions of its
mod. That was established by disassembling each target class at both versions and diffing:
Forestry `ComponentVillageBeeHouse`, EFR's deepslate and cave-vine classes, TiC `SlimeIslandGen` and
ProjectRed `TileLily` are bytecode-identical, and all ten Roguelike targets differ only by a credits
string, `ldc`→`ldc_w`, and a newer javac's synthetic enum `$values()`.

GregTech is the exception, and it failed in the worst possible way. 5.09.54.x deleted
`Block.isReplaceableOreGen`, which `WorldgenGTOreLayerMixin` redirects. That injector was
`require = 0`, so on daily the mixin applied, bound nothing, and left ore veins route-dependent
**with no log line**. `docs/HANDOFF.md:813` predicted this; this run confirms it and closes it.

Two more blockers, both found by trying to run rather than by reading:

- `LootGames 2.1.4-dev.jar` now 404s on the GTNH Nexus. `fix-build` resolved it only from a local
  Gradle cache, so the next clean CI release build would have failed.
- `WorldgenProbe.resetStatics()` reads `GTWorldgenerator.mList` and `ProcChunks` with no fallback.
  5.09.54.x deleted both, so warm/batch probe mode died on daily and could not verify anything.

## The fix

The reroll probe survived GT's rewrite unchanged in shape — nine samples at the chunk-centre column,
reroll the vein when fewer than five are stone — but it now asks
`StoneType.findStoneType(World,int,int,int)`. `WorldgenGTOreLayerStoneTypeMixin` redirects that to
virgin terrain, exactly as the pre-54 mixin does for `isReplaceableOreGen`. `LateMixinLoader` picks
between the two by whether `gregtech.api.enums.StoneType` is on the classpath, read through
`Launch.classLoader.getClassBytes` so no GT class is defined before its mixins apply.

Two details cost a debugging cycle each and are worth recording:

1. **The bare method name stopped being unique.** 5.09.51.482 had one
   `executeWorldgenChunkified`; 5.09.54.115 has two, and a bare-name selector then matches nothing —
   `failed injection check, (0/1) succeeded. Scanned 0 target(s)`. The new mixin spells out the full
   descriptor of the private ten-argument overload.
2. **`findStoneType(Block,int)` is not a substitute for `findStoneType(World,int,int,int)`.** The
   two-argument overload drops the `canGenerateInWorld` gate, so the handler replicates GT's loop
   over `STONE_TYPES` against the virgin block and metadata instead of calling it.

## Results

### Daily, radius 8, 837 chunks compared (incl. spawn region)

| pair | chunks differing |
|---|---|
| launch pair — rows vs rows, two cold JVMs | **3/837 (0.4%)** — blocks-only 2, te-only 1 |
| route — rows vs spiral, **fix jar** | 216/837 (25.8%) — blocks-only 187, te-only 1 |
| route — rows vs spiral, **no jar** | 819/837 (97.8%) — blocks-only 687, te-only 0 |

**Do not quote these percentages next to the block counts below — they are different units.** A chunk
counts as differing here if a single one of its 65,536 blocks differs, so this measures how *widely*
a difference is spread, never how *much* differs. It is the right metric for "is the jar doing
anything at all" and the wrong one for "how much is left". The block inventory is the latter.

### 2.7.4, radius 8, 849 chunks compared

| pair | result |
|---|---|
| launch pair — rows vs rows, two cold JVMs | **IDENTICAL by live chunk hash** (see caveat below) |
| route — rows vs spiral | 186/849 (21.9%) — blocks-only 4, te-only 160, both 22 |

27 mixins selected, `isReplaceableOreGen` variant chosen, zero injection failures. The jar is built
against daily-era compile pins and still binds correctly on the oldest supported pack.

> **That "IDENTICAL" is the weaker metric.** `diff-probe.py` hashes chunks at generation time;
> the persisted world is this project's stated ground truth and it is not identical. The same pair of
> 2.7.4 runs is IDENTICAL by live hash and differs by **49 blocks** on disk — settling that happens
> after hashing. A third cold launch puts the pairwise persisted floor at **49 / 67 / 116 blocks**,
> ~85-98% of it deep dirt/gravel patch toggles, with the jar held constant. So 2.7.4 launch
> determinism is exact for worldgen and imperfect for what reaches disk. See `docs/HANDOFF.md`
> rules 9 and 10.

## Full inventory of what still differs

### What the two tests are

Both compare two generated worlds on the same seed. They differ only in what is held constant.

| test | run A | run B | the question it answers |
|---|---|---|---|
| **launch pair** | rows | rows, after a cold JVM restart | Does this seed produce the same world twice in a row? A failure means seed notes are worthless — the same seed gives a different world tomorrow, or on someone else's machine. |
| **route** | rows | **spiral** | Does the world depend on where the player walked first? Same chunks, generated in a different order. A failure means two players on one seed get different worlds because they explored differently. |

Route is the strictly harder test and its numbers are correspondingly larger. A launch pair that
passes says nothing about route stability.

### The numbers

Persisted worlds, radius 6, seed `-777`. Every differing block is assigned to a category and the
categories sum to 100% — `scripts/inventory-region-diff.py` classifies decoration structurally
("one side is a block the terrain stage cannot produce") rather than by an allowlist, so no tail
hides in an "other" bucket.

#### Stock vs fixed, same units, same test (daily, route, r6)

The chunk percentages above say the jar helps. This says by how much, and where.

| category | stock (no jar) | with jar | change |
|---|---|---|---|
| EtFuturum deepslate band | 192,495 | 4,847 | **−97%** |
| decoration (trees/plants/hives) | 131,902 | 39,425 | −70% |
| dirt/gravel/stone patches | 18,813 | 8,495 | −55% |
| GT / mod ore placement | 10,397 | 3,741 | −64% |
| sand/gravel/clay/fluid settling | 10,084 | 6,389 | −37% |
| GT stone-layer (granite/stone blobs) | 7,715 | 7,451 | −3% |
| **total** | **371,406 / 805 chunks** | **70,348 / 169 chunks** | **−81%** |

Two readings that the chunk metric hides:

- **The deepslate band was over half the stock problem** and is now 2.5% of what it was. The EFR
  deepslate fix, not the GT work, is what moves the headline on this pack.
- **GT stone-layer blobs are the one category the jar barely touches** (−3%). It rises from 2.1% of
  the stock problem to 10.6% of the residual purely because everything around it shrank. Earlier
  notes in this file called it a "2.9-era regression"; that overstated it — it is untouched, not
  worsened, and it is now the largest category with no fix behind it.

| category | daily route | 2.7.4 route | daily launch pair | 2.7.4 launch pair |
|---|---|---|---|---|
| decoration (trees/plants/hives) | 39,425 — 56.0% | 44,850 — 55.6% | 122 — 100% | 0–1 |
| dirt/gravel/stone patches | 8,495 — 12.1% | 18,104 — 22.4% | 0 | 48–99 |
| GT stone-layer (granite/stone blobs) | 7,451 — 10.6% | 859 — 1.1% | 0 | 0 |
| sand/gravel/clay/fluid settling | 6,389 — 9.1% | 13,489 — 16.7% | 0 | 0–16 |
| EtFuturum deepslate band | 4,847 — 6.9% | n/a (no EFR in 2.7.4) | 0 | n/a |
| GT / mod ore placement | 3,741 — 5.3% | 3,407 — 4.2% | 0 | 0 |
| **total** | **70,348 / 169 chunks** | **80,709 / 188 chunks** | **122 / 5 chunks** | **49 / 67 / 116** |

The 2.7.4 launch-pair column is a **range over three cold launches**, not one pair, because two runs
give a number with no scale attached to it. All three are IDENTICAL by live chunk hash — the spread
is settling written to disk after hashing, dominated by deep dirt/gravel patch toggles. The daily
launch pair is a single pair and should get the same three-run treatment before its 122 is trusted
as a floor rather than a sample.

Tile entities, same pairs:

| type | daily route | 2.7.4 route | daily launch pair | 2.7.4 launch pair |
|---|---|---|---|---|
| `GT_TileEntity_Ores` | 0 | **3,112 — 99.6%** | 0 | 0 |
| `etfuturum.glow_lichen` | 18 | n/a | 0 | n/a |
| `forestry.Swarm` | 5 | 4 | 0 | 0 |
| `TileExtendedNode` | 4 | 6 | 0 | 0 |
| `projectred…lily` | 3 | 4 | 0 | 0 |
| `etfuturum.cave_vines` | 1 | n/a | 0 | n/a |
| `Chest` | 1 | 0 | 1 | 0 |
| **total** | **32** | **3,126** | **1** | **0** |

Read across the rows, not down the columns: **launch determinism is effectively solved on both packs**
(2.7.4 exact, daily 122 decoration blocks and one chest), while **route determinism is not solved on
either** — and the bulk of what remains is decoration, which the root README has always listed as
endemic 1.7.10 behaviour rather than something this mod fixes.

### Ore, split by failure mode

The 3,627 ore-involved blocks in the daily route pair, via `scripts/ore-route-report.py`:

| bucket | blocks | share |
|---|---|---|
| ore ↔ non-ore — placement decided differently | 2,025 | 55.8% |
| host stone changed — same material, different rock | 1,544 | 42.6% |
| **material changed — VEIN IDENTITY** | **37** | **1.0%** |
| ore metadata differs, other | 21 | 0.6% |

Host-stone flips are dominated by `stone -> redgranite` (1,041), and ore appears/vanishes almost
entirely against `dirt` (1,126) and `gravel` (552). That is the causal chain: the dirt/gravel patch
nondeterminism moves the host rock, `StoneType.findStoneType` returns null for dirt and gravel, and
`OreManager.getOreBlockForWorldGen` then places no ore at all. **Ore placement is downstream of the
dirt/gravel patch problem, not independent of it** — which makes dirt/gravel the higher-value target.

## Reading the two route numbers together

The chunk percentages (21.9% on 2.7.4, 25.8% on daily) are the least informative view: daily has
*fewer* differing blocks (70,348 vs 80,709) and 100× fewer differing tile entities (32 vs 3,126).

The tile-entity collapse is representation, not repair. Pre-54, host stone lived in
`TileEntityOres.mMetaData`, so every host-stone flip was a TE diff — 3,112 of them on 2.7.4, matching
the ~12,970 at radius 15 recorded in `results/2026-08-27-gt-ore-probe-pinning/`. 5.09.54.x writes
ores as plain blocks with the stone type in metadata, so the same flips land in the block diff
instead: 3,741 blocks on daily against 3,407 on 2.7.4. **Ore churn did not shrink; it moved.**

What did hold is the part F4 actually targets. Vein identity — which mix a region resolves to —
accounts for 37 of 3,627 ore blocks, 1.0%. The other 99% is per-block placement, which F4 has never
addressed and which `results/2026-08-27-gt-ore-probe-pinning/` documents three failed attempts at.

## Also fixed, found on the way

- `WorldgenProbe.java:883` looked up `com.gtnhspeedrun.tcworldgenfix.TerrainOracle`, renamed to
  `com.gtnhspeedrun.determinism.worldgen.TerrainOracle` in commit `1340ae3`. The
  `ClassNotFoundException` was swallowed, so the fix jar's virgin-terrain cache was **never cleared
  between warm slots** and "fix jar NOT installed" was logged on every reset even when it was. This
  affected every pack version, not just daily.
- The per-chunk ore census walks `TileEntityOres`, so on 5.09.54.x it reports an empty map that
  reads as "this seed has no ores". It now logs that the census is empty rather than zero. A
  block-level ore census for 54.x is **not** implemented; use region-block diffs for ore questions.
- `scripts/searchlib.py` decoded ore metadata without the `+8000` natural-ore offset 5.09.54.x
  added, which would have mislabelled every worldgen ore's stone type. The decoder now matches
  `GTOreAdapter.getOreInfo` and still reads pre-54 values correctly.
- Twelve `require = 0` injectors across seven mixins were tightened to `require = 1`, now that their
  targets are confirmed unchanged across the range. All of them bound on both 2.7.4 and daily.
  `DecoBigTreeCtorMixin` stays soft: RWG ships 1.5.0 up to 2.8.4 and 1.5.2 from 2.9, and the 1.5.0
  artifact is purged from the maven, so the assumption cannot be checked.

## Chest loot: one NBT defect, found and FIXED

131 inventory-bearing tile entities in the daily window, A=131 B=131 in every pair. Item lists were
already identical — same ids, counts, damage values and slot indices, no chest gained, lost or moved.

One difference remained, in both the launch pair and the route pair, at the same position: chest
`(4, 39, 233)`, slot 2, a `Thaumcraft:ItemAmuletVis`.

```
launch A   tag:{aer:300, aqua:300, ignis:400, ordo:400, perditio:400, terra:400}
launch B   tag:{aer:200, aqua:200, ignis:0,   ordo:400, perditio:200, terra:300}
```

The amulet's stored vis is gameplay state and it varied across two **cold launches** of the same
seed, making it launch-class rather than route-class — the more serious of the two. It was nearly
dismissed as "only NBT differs"; see `docs/HANDOFF.md` rule 6, which exists because of this.

### Root cause

`thaumcraft.common.config.Config.initLoot()`, called once from `Thaumcraft.class` at mod init:

```java
Random random = new Random(System.currentTimeMillis());          // clock-seeded
ItemStack stack = new ItemStack(ConfigItems.itemAmuletVis, 1, 0);
for (Aspect a : Aspect.getPrimalAspects())
    amulet.addVis(stack, a, random.nextInt(5), true);            // 0-4 vis = 0/100/../400 centi-vis
...
ChestGenHooks.addItem(category, new WeightedRandomChestContent(stack.copy(), ...));
```

`nextInt(5)` per primal aspect reproduces the observed values exactly. **One** stack is built and
copied into both `ChestGenHooks` and the loot-bag tables, so every amulet in a session shares a
charge and every launch draws a new one.

**F7 could not have caught this.** The loot-table snapshot in `GtnhDeterminism` captures
`ChestGenHooks.contents` at `FMLLoadCompleteEvent`, and `initLoot()` runs before that — the
clock-random NBT is already baked into the entry being snapshotted. F7 preserves it faithfully,
including the part that should not have been random. That is exactly why the charge was stable
within a run and varied across launches.

The TooMuchLoot XML entries that also list the amulet are a red herring: they specify a *fixed* NBT
(`aqua:100,terra:100,ignis:300,ordo:200,perditio:200,aer:400`) matching none of the observed values.

### Fix: two mixins, because one is not enough

**`ThaumcraftInitLootMixin`** — a one-line `@Redirect` on `System.currentTimeMillis()` inside
`initLoot`, returning a fixed constant. Same shape as the existing `DecoBigTreeCtorMixin`
`Math.random()` redirect. `require = 1` is safe across the whole range: Thaumcraft's `Config.class`
is byte-identical (md5 `f6cf23b7d3f2967b93b9338cecb95348`) between 2.7.4's 4.2.3.5a and daily's
4.2.3.5, and `initLoot` calls `currentTimeMillis` exactly once.

That alone can only produce a *constant*, because `initLoot` runs at mod init before any world
exists. It also inherits a stock oddity worth naming: because Thaumcraft builds one stack and copies
it everywhere, **every amulet in a session carries the same charge**. Deterministic, but plainly not
what a per-item charge is for.

**`ChestAmuletVisMixin`** — hooks the vanilla chest filler
`WeightedRandomChestContent.func_76293_a(Random, WeightedRandomChestContent[], IInventory, int)` at
RETURN and re-derives any Vis Amulet from `(world seed, chest x/y/z, slot)`, using the same mixing
constants as `TileLilyMixin`. That is the only point where the item and its destination coordinates
are both in scope. Dungeon, mineshaft, stronghold, pyramid and village chests all route through it;
minecart chests arrive as an `Entity` rather than a `TileEntity` and are handled too. Anything with
no world or position falls back to the pinned constant, which is still deterministic.

Each aspect is drawn as `nextInt(5)` and applied through Thaumcraft's own
`addVis(stack, aspect, n, true)` — the exact call and argument domain `initLoot` uses — so the
per-amulet distribution is stock's. **What changes is the correlation between amulets**: stock made
any two amulets in a session identical, this makes them independent. Marginal balance is untouched;
a player looting two amulets now gets two different charges instead of two copies.

Compiling it needed `Baubles-Expanded` as a new `compileOnly`: `ItemAmuletVis implements
baubles.api.IBauble`, and javac must resolve that interface to type-check any call on the class.

### Proof

1. **Launch stability, one seed, persisted worlds** — total tile-entity differences 1 → 0. The whole
   TE layer is byte-identical between two cold launches, 131/131 chests included.
2. **Launch stability, 10 seeds, warm batches in two JVMs** — 548 chests each, `existence=0
   contents=0 nbt=0` on every seed.
3. **The hook actually fires** — same seed, same chest `(4, 39, 233)`: init-pin-only gave
   `aqua:200 ignis:400 perditio:0 terra:300`, position-derived gives
   `aqua:100 ignis:100 perditio:200 terra:400`.
4. **The derivation is what it claims** — a Python replica of the mixin's arithmetic reproduces the
   in-world amulet exactly: draw order `[0,4,1,1,4,2]` matches the observed tag under Thaumcraft's
   primal order `[aer, ordo, aqua, ignis, terra, perditio]`.
5. **Variety** — only one amulet exists across those 10 seeds (1 in 548 chests), too few to show
   variety empirically, so it is shown from the validated model instead: 8 of 8 nearby positions,
   4 of 4 slots in one chest, and 3 of 3 seeds at one position all give distinct charges.

## Is the classifier hiding structures?

Rule 7 says to check, so: of the 39,425 blocks in the "decoration" bucket on the daily route pair,
**16 (0.04%) are structure-like** — `Railcraft:cube` ×10, `catwalks:cagedLadder` ×3, cobblestone ×2,
one `obsidian -> lava`. Small enough that the bucket totals are not materially misleading on this
data, but non-zero, and the check must be re-run on any new pair rather than assumed.

## Open, in rough value order

1. **Dirt/gravel patches (12.1% of daily route blocks) are worth more than their own share**, because
   GT ore placement is downstream of them: 1,678 of the 2,025 ore present/absent flips are against
   dirt or gravel. Fixing the patches would take a bite out of the ore number for free.
2. **GT stone-layer worldgen** (`gt.blockgranites`, `gt.blockstones`) — 7,451 blocks, and the only
   category the jar does not move (−3% against stock). It is the largest category with nothing
   behind it. Not investigated.
3. **Decoration is 56% on both lines** and is the documented endemic 1.7.10 tail. Unchanged by any of
   this work; listed so its share is not mistaken for something new.
4. **Ore placement**: 99% of ore churn, and out of scope for F4 by design. Any attempt should read
   `results/2026-08-27-gt-ore-probe-pinning/` first — the "partially purifying an arbitrary decision
   does not partially fix it" lesson still applies.
5. **The 37 vein-identity blocks.** Small, but F4's whole job is to make this zero. Worth finding
   which oreseed flips and why.
6. **Persisted launch determinism is not exact on either pack**, though worldgen itself is: every
   launch pair measured here is IDENTICAL by live chunk hash. Daily is 122 blocks / 5 chunks, 100%
   decoration, and zero TEs after the amulet fix. 2.7.4 is 49/67/116 blocks over three launches,
   85-98% deep dirt/gravel patch toggles. Both are post-hash settling. The daily figure is a single
   pair and needs a third run before it counts as a floor.
7. New-mod screening was static only. Of 34 mods added since 2.7.4, `bugtorch` (8 worldgen, 7 village
   classes), `fether` (4) and `VillageNames` (993 village classes) touch relevant areas. The launch
   pair is evidence that none adds launch nondeterminism; route behaviour per mod was not isolated.
