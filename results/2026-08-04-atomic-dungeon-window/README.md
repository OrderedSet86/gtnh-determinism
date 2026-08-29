# Atomic dungeon window — fixes the loot-loss half of the order dependence

**2026-08-04.** Follow-up to `results/2026-08-03-order-dependence/`. Implements the
"make the placed-chest list route-invariant" fix. It works, and it is **not sufficient**:
there is a second, independent order-dependence bug still open (see the bottom section).

Jar under test: `gtnhdeterminism-v0.4-main.35+102c03495a-dirty.jar`
(md5 `26ec0cd951cf1823d5637c4d091c3be5`, copy in this directory). Not released.

## What was wrong

`TreasureChest.generate` hands Roguelike's `Inventory` a **reference** to the chest tile
entity, and the loot rules run at the very END of `Dungeon.generate`. For a chest buffered
by `PendingSlices`, that reference is the DETACHED tile entity.

A dungeon builds during its trigger chunk's population but writes into neighbour chunks.
Writing into an ungenerated neighbour force-loads it, and that nested generation runs the
neighbour's own mod-worldgen phase — **including `SliceApplier`** — while the dungeon is
still under construction. `apply()` transplants a *copy* of the detached TE
(`writeToNBT` → `createAndLoadEntity`), so every item written afterwards landed in an
orphaned instance and was lost: the chest exists in the world but is empty. Applying also
marked the chunk applied, flipping the dungeon's remaining writes in that chunk from
buffered to live mid-construction.

Which neighbours that hits is a pure function of the route — hence route-dependent chest
contents.

Confirmed by trace, not by reading: `trace-rows.txt` / `trace-spiral.txt` show the window
catching real mid-construction applies — 3 deferred chunks under `rows`
(`-20,-12`, `-19,-14`, `-19,-13`), 2 under `spiral` (`-21,-14`, `-21,-13`).

## The fix

`PendingSlices` gains an **atomic dungeon window**, held by `DungeonMixin` across the whole
of `Dungeon.generate` (construction *and* the loot pass):

- `apply()` inside the window records the chunk as deferred instead of materialising it,
  and does **not** mark it applied.
- `shouldBuffer()` keeps buffering for a deferred chunk, so the previous-session
  `isTerrainPopulated` shortcut cannot flip it live mid-dungeon.
- The outermost close flushes every deferred chunk in ascending chunk-key order — still
  inside the trigger chunk's population, so no pop-in and "dungeon wins over that chunk's
  decoration" is unchanged. Nesting is counted (a cascade can trigger a second dungeon
  inside the first).

`-Dgtnhdet.atomicdungeon=false` disables it as an A/B lever. `-Dgtnhdet.traceslices=true`
enables the trace (`SliceTrace`, inert otherwise).

There is deliberately **no** min-weight "assert the window is closed" world generator: it
would also fire for chunks cascade-generated *inside* a dungeon, where a non-zero depth is
correct, and would force-flush exactly the case the mechanism protects. A leak can only
come from an exception escaping `Dungeon.generate`, which Forge does not catch — it takes
the chunk populate and the server down. `resetAtomicWindow()` at `FMLServerAboutToStart`
stops a leak outliving its world.

## Measured (seed -1501259159663517643, 2.8.4, radius 15)

| arm | chests | empty | Crystal Pick |
|---|---|---|---|
| OLD rows | 190 | 49 | `-270, 10, -191` |
| OLD cols | 172 | 16 | `-297, 7, -180` |
| OLD spiral | 206 | 23 | `-297, 7, -180` |
| **FIX rows** | 199 | **16** | **`-297, 7, -180`** |
| **FIX rows2** (repeat) | 199 | **16** | **`-297, 7, -180`** |
| **FIX cols** | 174 | **16** | **`-297, 7, -180`** |
| **FIX spiral** | 206 | **16** | **`-297, 7, -180`** |

- **Empty chests are now order-invariant** (49/16/23 → 16/16/16). The loot-loss class is gone.
- **The Crystal Pick agrees across all three orders**, at the coordinate the user's real
  singleplayer world had.
- **Launch determinism preserved**: FIX rows vs FIX rows2 = 0 diffs across all 199 chests.
- Same-position content diffs dropped: rows-vs-spiral 17 → 9, cols-vs-spiral 6 → 5.

## STILL BROKEN — second, independent bug

Chest **position sets** remain route-dependent, barely improved:

| A | B | only A | only B | shared | differing |
|---|---|---|---|---|---|
| FIX rows | FIX rows2 | 0 | 0 | 199 | 0 |
| FIX rows | FIX spiral | 129 | 136 | 70 | 9 |
| FIX rows | FIX cols | 134 | 109 | 65 | 11 |
| FIX cols | FIX spiral | 92 | 124 | 82 | 5 |

What the trace establishes about it:

- **Dungeon placement is order-invariant** — both dungeons begin at identical coordinates
  in both orders (`-287,-226` and `-344,108`). F5 is holding; this is not a placement bug.
- **No chest placement ever fails** — `placed=false` count is 0 in both orders. Chests are
  not being vetoed or carved over.
- The dungeon simply **emits a different set of chests**: dungeon `-287,-226` generates 105
  chests under `rows` and 111 under `spiral`; dungeon `-344,108` generates 113 vs 114. The
  emission sequences diverge from index 0-1, at level 0 (y≈50).

So the remaining bug is in Roguelike **room/segment content generation** — which rooms and
decorations a level builds — upstream of chest placement entirely. That is the P1
"decoration determinism" class again, and the 0.3-era `MinimumSpanningTreeMixin` /
`RoguelikeRoomShuffleMixin` fixes do not cover whatever still reads route-dependent state.

Next step: extend `SliceTrace` into the level/room/segment decision path
(`greymerk.roguelike.dungeon.DungeonGenerator.generate`, level generation, segment variant
selection) and diff two orders' logs to find the flipping decision. Non-Roguelike chests
remain 28/28 stable across all orders and are unaffected by any of this.

> **CORRECTION, 2026-08-27.** Two claims above are wrong.
>
> 1. The remaining bug was **not** in room/segment content generation. It was `InventoryMixin`
>    forking the slot shuffle to a position-seeded rand only for chests whose tile entity had a
>    world, so a detached (buffered) chest spent 26 draws on the shared room rand and an attached
>    one spent none. See `results/2026-08-27-inventory-fork-unconditional/README.md`.
> 2. **Non-Roguelike chests are NOT order-stable.** A persisted-world diff of two cold runs (the
>    only valid ground truth here) shows 158 chest tile entities under `rows` and 160 under
>    `spiral`, with `only-rows=0, changed=0`. The two extra are an entire **vanilla cave dungeon**
>    — a Skeleton `MobSpawner` at `(-337,30,-258)` plus its chests at `(-337,30,-256)` and
>    `(-334,30,-259)` — that exists under `spiral` and not under `rows`, `cols`, or two repeat cold
>    `rows` runs. Spawner totals are 168 vs 169. The 28/28 figure was measured from probe reports,
>    which cannot settle this: the probe records a chunk at walk time, so a cross-chunk structure
>    write can land after the observation.
