# F10 refilled chests from a loot table their caller never asked for

GTNH daily-707, repo `d17a685`. Fix jar `c7ea8104ba08f906904504e58ed81ff2` before,
`517a9769091269c6560a8f42534bf248` after.

**A determinism fix was itself a source of order-dependence.** F10 replaced some chests' contents
using whichever loot table happened to be left in a ThreadLocal, and which table that was depended on
chunk generation order.

## The measurement

Same seed, same window (village-centred, chunk 14,18, radius 8), same jar, `rows` walk against
`spiral` walk. F10 active in both, **zero fallbacks reported**, so every chest was refilled.

```
abs=244,91,268  ComponentVillageKeep
  rows  : cat=WG:PHOTOWORKSHOP    rolls=7 countdrawn=false
  spiral: cat=railcraft:workshop  rolls=7 countdrawn=false

abs=295,68,304  ComponentVillageBookShop
  rows  : cat=towerChestContents  rolls=6 countdrawn=false
  spiral: cat=TinkerHouse         rolls=6 countdrawn=false
```

Same position, same piece, same roll count, **different loot table**. In the world that is Thaumcraft
loot against TConstruct, and paper/dye against Railcraft.

The split is exact and is the whole diagnosis:

| | order-stable | order-dependent |
| --- | ---: | ---: |
| `countdrawn=true` | 48 | 0 |
| `countdrawn=false` | 2 | 2 |

## Mechanism

`ChestFillContext.TABLE` is a ThreadLocal set by `notedItems` on **any** `ChestGenHooks.getItems`
call, and cleared only when `consumeTable` runs. A caller that calls `getItems` and then never reaches
a hooked filler leaves it set. The next chest that fills **without** calling `getItems` itself
consumes that stale table, and `consumeTable` accepted it because `itemsSeen` was true.

Which stale table is sitting there is a function of what generated before — i.e. of walk order.

Witchery's village pieces are the standing example. `ComponentVillageKeep` has no `ChestGenHooks`
reference anywhere in its bytecode, and `WitcheryComponent`'s only `getItems`/`getCount`/`getInfo`
call sites are all inside `setDispenser`. So those chests never ask for a table, and every table they
were refilled from was someone else's.

`countdrawn=true` chests were never affected: a caller that drew its count through
`ChestGenHooks.getCount` also drew its items through the same hooks object, so the capture was real.

## The fix

`getItems` is injected at `RETURN`, so the returned array is already in hand, and
`refillChest(WeightedRandomChestContent[] items, …)` already receives the array the filler is about to
use and ignored it. Capture one, compare identity with the other:

```java
if (t != null && t.itemsSeen && t.items == items && (!t.countSeen || t.count == count)) return t;
```

Identity, not equality — the point is proving the array came from *this* capture.

A rejected capture falls through to the existing fallback path, which leaves stock's roll and logs the
caller. That log now names the table it refused, which is what makes the leak visible at all:

```
Chest fill for a chest did not come from ChestGenHooks.getItems/getCount — leaving stock's roll.
count=7 captured=[items=true count=false value=0] category=WG:PHOTOWORKSHOP
caller=com.emoniph.witchery.worldgen.ComponentVillageKeep.func_74875_a:311
```

## Result

| | before | after |
| --- | ---: | ---: |
| order-dependent chest contents, shared positions | **3 / 75** | **1 / 75** |
| order-dependent among chests F10 refills | 2 / 50 | **0 / 50** |
| chest existence differences | 2+3 | 2+3 (unchanged) |
| stronghold chest predictions still exact | 9 / 9 | **9 / 9** |

Three chests changed contents against the old jar on an identical walk: the two leak victims, which
now fall back instead of being wrongly refilled, and one at (222,71,291).

**F10 was the sole cause of those two chests' order-dependence.** Under fallback they are order-stable,
so stock's own fill for them was already route-stable and the refill was what broke it.

### The one remaining difference is outside F10's reach

`(240,68,309)` is a Forestry bee chest holding a princess, a drone and frames. It differs by both
count and contents between walks, and it appears in **no** chesttrace line — Forestry fills it without
going through `WeightedRandomChestContent.generateChestContents`, so no hook sees it. Separate defect,
separate fix.

The 5 existence differences are likewise untouched and unattributed. None are village pieces.

## The count change was a null result here — say so

The same change also derives the roll count when the caller did not draw one, guarded against tables
registered `0..0` (Forestry's `naturalistChest` reads 0..0, and `rollCount` answers 0, which would
empty every bee-house chest).

**It changed nothing in this window.** The only `countdrawn=false` chests that survive the identity
check here are two bee houses, both on the degenerate `naturalistChest` table, so both keep the
caller's count (10 and 8) exactly as before. The change only bites on a chest that has a
proven-identity table *and* a non-degenerate range *and* no drawn count, and this window contains
none.

The motivation was still measured, not assumed: `ComponentVillageBeeHouse` arrives with counts 10, 10,
10, 7, 7, 7, 9 and 5 across villages, so the pre-existing comment that such a count is "a literal it
chose itself, already a constant" was false.

## What this does NOT deliver

Witchery village chests are **not** now predictable by the stage-0 prefilter. They moved from "wrongly
refilled from a leaked table" to "not refilled at all". See
`results/2026-08-30-stronghold-witchery-chests`.

**Three** earlier explanations for that refusal were wrong and are retracted:

- "re-deriving the count would move the stream" — it would not. F10 runs after the caller's own draws;
  ignoring the value it passed consumes nothing from the caller's rand.
- "the count is a literal it chose itself" — measured false, see above.
- "the caller never asks for a loot table, so there is nothing to roll" — false, and it came from a
  grep whose pattern required a `Class.method` form and therefore silently dropped every unqualified
  method reference in the disassembly. `ComponentVillageKeep` does fill a chest:

```java
this.generateStructureChestContents(world, box, rand, x, y, z,
    villageTowerChestContents,   // static field on the piece class
    3 + rand.nextInt(6));        // count off addComponentParts' own Random
```

`villageTowerChestContents` is a 10-entry compile-time constant — gold ingots (1-6, weight 10), gold
nuggets (1-15, weight 20), golden sword, golden armour (1-1, weight 5). It is a static field, so it is
reflectively readable and needs no measurement pass at all. **The contents are perfectly
deterministic; nothing here is drawn from a category.**

The real reason is narrower than any of the above: **the roll count is drawn mid-`addComponentParts`
from that method's own `Random`, and the prefilter never executes `addComponentParts`** — that is the
terrain-writing phase `SeedProbeWorld` exists to keep out. Reproducing the count means replaying every
draw the piece makes before it, which is the reimplementation that `villageStarts` already records as
producing "self-stable but real-divergent" results. Dispatch, don't reimplement — and here there is
nothing to dispatch to.

That rand is seeded per chunk by `StructureStart.generateStructure`, so these chests are **already
route-stable without F10**, which is exactly what the A/B above measured once the bogus refill stopped.

## Reproducing

```sh
for order in rows spiral; do
  PROBE_CX=14 PROBE_CZ=18 PROBE_SEARCH=true PROBE_EXTRA_ARGS="-Dgtnhdet.chesttrace=true" \
    ./scripts/run-probe.sh <server> -7269948338495788698 $order out-$order.json 8
done
```

Compare `search.chunks[<cx,cz>].chests` between the two, and the `[chesttrace]` lines for the `cat`
each position was filled from.
