# Stage 4: village chest contents in the stage-0 prefilter

GTNH daily-707, fix jar md5 `1a5b30cece23e2bd20e7f4dc0fba2519`, probe md5 `6005848bf70caf2237879ee5b3b74161`.
7 seeds, village radius 12, terrain disabled.

```bash
PREFILTER_SERVER=~/.cache/gtnh-determinism/daily-707 PREFILTER_RADIUS=12 PREFILTER_TERRAIN=-1 \
PROBE_JVMFLAGS="-Dprobe.prefilter.villagechests=true" ./scripts/prefilter.sh @seeds.txt out.jsonl
python3 seedsearch/prefilter-judge-chests.py out.jsonl <corpus-dir>
```

`VillageChestPrefilter` takes each piece already produced by `villageStarts()` — class, bounding box,
orientation — looks its chest sites up in the measured `chest-sites.json`, rebuilds F10's fork, rolls
the count and pool off the live `ChestGenHooks`, and runs the real
`WeightedRandomChestContent.generateChestContents` into a throwaway inventory of the real container's
measured slot count. No chunk is generated.

## Result

| | |
| --- | ---: |
| predicted chest positions | 95 |
| present in the corpus | 95 |
| predicted but absent | **0** |
| **contents identical at matched** | **95 of 95** |
| **NBT identical at those** | **95 of 95** |

Every category exact:

| category | matched | exact | absent |
| --- | ---: | ---: | ---: |
| TinkerHouse | 34 | 34 | 0 |
| TinkerPatterns | 17 | 17 | 0 |
| WG:PHOTOWORKSHOP | 9 | 9 | 0 |
| vn_plains_house | 8 | 8 | 0 |
| railcraft:workshop | 6 | 6 | 0 |
| vn_cartographer | 5 | 5 | 0 |
| vn_fisher | 5 | 5 | 0 |
| vn_tannery | 5 | 5 | 0 |
| villageBlacksmith | 3 | 3 | 0 |
| vn_savanna_house | 2 | 2 | 0 |
| vn_butcher | 1 | 1 | 0 |

A village's chest contents — item, damage, count, slot index and NBT — predicted from the structure
layout alone, without generating a chunk. `villageBlacksmith` included, which is the chest the coke%
funnel routes on.

Two fixes got it here from 35/69; both are described below, and one of them corrected a wrong
diagnosis rather than a wrong line of code.

Every predicted position exists and every one is exact. The two `PlainsStable2` over-predictions that
stood here turned out to be a defect in F10 itself, not in the prefilter — see below.

## What is deliberately not predicted

The module refuses rather than guesses, and says so at the end of every run — 14 sites on the 7-seed
run. Two reasons, both measured:

1. **Roll count not drawn from the table.** F10 keeps a caller's own count when the caller did not
   draw it through `ChestGenHooks.getCount` — correctly, since re-deriving it would move the populate
   stream. The assumption in `ChestFillContext` was that such a count is a literal constant. It is not:
   `ComponentVillageBeeHouse` fills `naturalistChest` with **5, 7, 9 and 10** items in different
   villages, so the count comes off the populate stream by another route. Those chests are genuinely
   not derivable from a layout. 34 of 194 traced component chests are in this class.
2. **Loot table reading 0..0 in this process.** `towerChestContents` reads `4..9` during real
   generation and `0..0` in the prefilter, so rolling it would emit a confidently empty chest.
   Caught by putting the table's range into the chest trace and diffing it against the prefilter's
   live `ChestGenHooks`; every other category agreed.
3. **No measured slot count.** A site whose container size was never traced cannot be rolled into the
   right inventory, and guessing one produces confident nonsense — see below.

## The TinkerConstruct failure: wrong inventory size, not a swapped table

`ComponentToolWorkshop` places two chests, `TinkerHouse` and `TinkerPatterns`. Both positions are
predicted correctly — 17 and 17 matched, 0 absent — and both contents are wrong.

**First diagnosis, recorded here because it was wrong:** that the table had the two categories swapped
for some orientations. Checking it against the trace instead of eyeballing one instance disproved it —
across **48** `ComponentToolWorkshop` fills, the table's `(orientation, category) -> local XZ` mapping
has **0 disagreements**. The positions were never the problem, which the judge's own "0 absent"
already said.

The actual cause is the inventory. `WeightedRandomChestContent.generateChestContents` places every
stack at `rand.nextInt(inv.getSizeInventory())`, and the module fills a 27-slot `TileEntityChest`.
TinkerConstruct's chests are not `TileEntityChest`:

| tile entity | size | read from |
| --- | ---: | --- |
| `PatternChestLogic` (`TinkerPatterns`) | **30** | `TiCChestLogic()` ctor, `TConstruct-1.14.104-GTNH.jar` |
| `CraftingStationLogic` (`TinkerHouse`) | **10** | `CraftingStationLogic()` ctor |
| vanilla `TileEntityChest` | 27 | |

A wrong size changes every slot draw, which shifts the whole stream, so the items differ too — exactly
the symptom seen (prefilter slots 0,1,2 where the real chest has 3,5,6).

Decisive check: **30 of 34** real `PatternChestLogic` chests use a slot ≥ 27, up to slot 29. A 27-slot
inventory cannot produce those at all.

The judge's planned "miscategorised" counter would not have caught this — it watches for a corpus
chest at a predicted XZ under a different category, and the categories here are right. What would have
caught it is a check that the corpus never uses a slot the module's inventory cannot reach.

## Coverage of the hand-built table

Built from 7 seeds at radius 12: **80 site rows** over 57 `(piece, orientation)` keys with chests,
plus **134 keys measured as chestless**. Re-derived on 2026-08-29 from a trace corpus with boot-world
lines excluded — that removed two entries, both of which had only ever been observed in the server's
boot world, and left the golden result unchanged
([results/2026-08-29-chest-reverification](../2026-08-29-chest-reverification/README.md)).

That second set matters. The chest trace only sees pieces that *fill* a chest, so a piece that
generates and places nothing is indistinguishable from one that was never measured — and the module
would silently predict no chest for a blacksmith it has never seen. `-Dgtnhdet.chesttrace` therefore
also emits a `[piecetrace]` line the first time each `(piece, orientation)` generates, which turns
"absent from the table" into a real answer for 134 of them. On the 7-seed run only 6 piece classes
remain genuinely unknown, and they are named in the run's own warning.

## The two fixes, as implemented

### 1. Rotation transform for caller-local sites

A caller-local site's `(lx, ly, lz)` are the piece's pre-rotation structure coordinates, so the world
position is vanilla's `getXWithOffset`/`getYWithOffset`/`getZWithOffset`, not `box.min` plus them.
Adding the origin directly had invented 14 phantom `TinkerHouse` chests.

The switch now lives once, as `Prefilter.xWithOffset`/`zWithOffset`/`yWithOffset`, and
`predictVillagers` — which had its own copy — calls it too. Writing it twice is how the two would
eventually disagree.

Only the emitted position changes; the fork mixes the raw locals either way. Effect: 28 previously
refused rows became predictions, taking matched positions from 69 to 95 with **zero** new
predicted-but-absent.

### 2. Measured inventory size

`generateChestContents` places every stack at `rand.nextInt(inv.getSizeInventory())`, so the container
decides both the slots and the stream after them. The module now rolls into a `SizedInventory` of the
slot count recorded per site, and emits the real container class as the chest's `type` so a corpus diff
compares like with like.

The size is **measured, not transcribed**: `-Dgtnhdet.chesttrace` records `inv.getSizeInventory()` and
`inv.getClass().getSimpleName()`, and both are carried on each row of `chest-sites.json`. Hardcoding 30
and 10 would have worked today and broken silently the next time TinkerConstruct resizes a chest. Sizes
seen across the corpus: 27 (`TileEntityChest`, `EntityMinecartChest`), 30 (`PatternChestLogic`), 10
(`CraftingStationLogic`), 9 (`TileEntityDispenser`).

A site with no measured size is refused rather than rolled into a guessed container.

Effect: `TinkerHouse` 0/34 -> **34/34** and `TinkerPatterns` 0/17 -> **17/17**.

### Determinism unaffected

The fix-jar changes this turn are trace-only, but the route test was re-run rather than assumed:
seed `-777`, rows vs spiral, 124 chests / 1817 item stacks, **0 existence / 0 contents / 0 NBT**.

## The PlainsStable2 over-prediction was an F10 bug

Two predicted chests did not exist: `PlainsStructures$PlainsStable2` / `dungeonChest`. The measured
table said that piece places chests at local `(-5, 0, 15)` and `(-4, 0, 12)` — **negative** local X,
outside its own bounding box, at `abs y = 48` when the box floor is `y = 64`. A stable does not have a
basement 16 blocks down.

Adding the caller and the site-stack depth to the chest trace named it immediately:

```
caller=rwg.world.ChunkGeneratorRealistic.func_73153_a:524   depth=1
```

`WorldGenDungeons`, not the stable. And a site was on the stack while it ran. Push/pop logging showed
why — it is not a leak, it is nesting:

```
push box PlainsStable2 depth=1
push box PlainsStable2 depth=2      <- nested population re-enters the same piece
pop                    depth=2
chesttrace ... dungeonChest ... caller=ChunkGeneratorRealistic   <- inherits the depth-1 site
chesttrace ... dungeonChest ...
pop                    depth=1
```

`PlainsStable2.addComponentParts` writes into a chunk that has not been populated yet, which cascades
straight into that chunk's population **inside** the outer call. `WorldGenDungeons` then fills its
chests while the stable's site is still current, and F10 derived two vanilla dungeon chests from a
village stable's bounding box. Deterministic, so no route or launch test could ever have caught it —
but wrong, and it means a dungeon's loot would move if the village moved.

**Fix:** `ChunkPopulateBarrierMixin` pushes a barrier around `ChunkProviderServer.populate`, the single
funnel all chunk population goes through. Anything filled during population sees "no component" unless
a component pushes its own site deeper in, and nesting resolves correctly because each nested
population pushes its own barrier.

**The obvious cheaper fix was wrong, and measuring saved it.** "Only trust a site if the chest is
inside the piece's bounding box" looks self-evident. It is false: of 17 out-of-box attributions, **11
are legitimate** — `ComponentToolWorkshop`, `PlainsWeaponsmith1`, `PlainsTannery1` and others really do
place chests outside their declared box, with the piece itself as the caller. Filtering on containment
would have silently discarded them.

Effect, measured on 3 seeds: chests with no component 150 -> 156, out-of-box attributions 17 -> 11 (all
11 legitimate), `PlainsStable2` attributions 6 -> 0. Route determinism re-checked afterwards, 124
chests / 1817 item stacks, 0/0/0.

This is a fix-jar behaviour change: the affected dungeon chests re-roll once, like every other fix.

## Next


- The two `PlainsStable2` over-predictions above.
- Widen the trace corpus. 7 seeds proves the mechanism; it is not coverage. Six piece classes are still
  unknown on this corpus and the run names them.
- Investigate why `towerChestContents` reads 0..0 in the prefilter process but 4..9 during generation.
- Add the check that would have caught the inventory-size bug on its own: flag any corpus chest using a
  slot index >= the module's recorded size for that site.
- Replace `coke-rank.py`'s piece-presence gates (`--require paper,tic,furnace`) with contents
  predicates. That is the step that converts this into search value.
