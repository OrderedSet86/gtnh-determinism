# What the determinism fixes change for players

Audience: GTNH speedrunners and anyone routing set seeds. Status: draft, pending in-game testing of the candidate jars.

## The one-time cost: your seed notes reset once

Every fix changes *how* randomness is derived, so the first launch with fixed jars produces a **different world for a
given seed than stock does** — a different (but finally canonical) village layout, different trees, different TC
structures. Any routing notes made on stock generation are invalidated once. After that, the seed is stable forever:
same seed → same world, every launch, every machine, every route.

Existing saves are safe: worldgen fixes only affect **newly generated chunks**. Nothing regenerates or corrupts in
areas you've already explored.

**Singleplayer worlds made before 2026-09-05 reset a second time, and not just the loot.** The fix that unifies the
spawn-region chest table (F9) hooks `MinecraftServer.loadAllWorlds`, but singleplayer runs an `IntegratedServer`,
which overrides that method — so on a client the fix silently never ran, and the ~400x400-block area around spawn
kept stock's separate, richer loot table. Dedicated servers were always correct.

The catch: filling a chest consumes randomness from the shared chunk stream, and how much it consumes depends on
which loot table is live. So changing the table inside the spawn region also shifts everything generated after a
chest in those chunks — ores, caves, lakes, and in a few cases whether a chest exists at all. Measured on one seed:
about 76 of ~1000 chunks differ, against a 5-chunk noise floor, with ore-depth changes at y0-47. **Re-scout the area
within ~200 blocks of spawn; outside it nothing changes.**

**The bonus chest is not nerfed and never should have been.** If you tick "bonus chest" at world creation, its
contents come from the pre-TooMuchLoot table, exactly as on stock — that chest is filled before TooMuchLoot applies,
so it was never part of the spawn-region split. An earlier build of F9 handed it TooMuchLoot's reduced table by
accident (16 entries down to 13, losing the Botania and TiC starter items); that is fixed.

## What becomes reliable

| Area | Stock behavior | With fixes |
|---|---|---|
| Village buildings | Layout re-scrambled every game launch — blacksmith/TiC smeltery may or may not exist; measured: same seed gave a 38-piece vs an 81-piece village on consecutive launches | One canonical layout per seed; smeltery presence is a property of the seed, same for everyone |
| Village chest loot | "Cycles between ~15 forms" (really: layout scramble moving/removing the chests) | Fixed per seed given the now-stable layout |
| Witchery covens / wicker men / shacks / goblin huts | Rolled off a clock-seeded RNG — different every run | Seed-determined positions and contents |
| RWG trees (large pines, big trees, cherry blossoms) | Geometry varies per run; perturbs other features near chunk borders | Identical per seed |
| GT ore veins | Rarely reroll identity (copper↔iron) or height depending on approach direction near caves/mountainsides | Vein identity and height are pure functions of the seed. **0.3**: the "too-high vein on low ground → reroll to another vein" logic (why mountains carry the high veins like upper cassiterite) now works exactly as designed but answers from pre-decoration terrain — earlier fix builds had disabled it |
| Thaumcraft nodes, totems, barrows (draft fix) | Different even within one session; barrow urn/crate loot clock-random | Feature placement rolls are seed-derived and mutually independent; barrow containers seed-stable |
| Thaumcraft eldritch obelisks (crimson cult rings) — **reworked in 0.3** | Whether an obelisk exists at a given spot depended on which nearby candidate site your route generated first (a ~40-chunk-wide exclusion race) — the same seed could show or hide an obelisk per playthrough; the altar's cultist-spawner/banner roll also flipped by route | One obelisk site per 25×25-chunk region at **stock-like frequency**: nine seeded candidate spots are tried in a fixed order and the first with valid (pre-decoration) terrain wins — same seed → same obelisks, spawner type, and banners for everyone, any route. The maze/Outer Lands system is untouched. `scripts/ringscan.py` lists a seed's candidate spots offline. **Positions shift once vs 0.2/stock** |
| Roguelike dungeon position, rooms and chest loot — **new in 0.3** | The trigger region was seed-fixed, but the dungeon itself re-rolled position (~50 blocks), size (measured 133 vs 156 spawners) and every chest's loot depending on approach direction | Position, layout, and chest contents are functions of the seed: placement and room checks read pristine pre-decoration terrain, and each chest's loot is derived from its own position. Verified: same-position chest content differences dropped from 88 to ~3 between opposite walk orders |

## What still varies (known residuals)

- **Witchery village walls** — built by a delayed tick-time process; shape still depends on timing. Not fixed yet.
- **Roguelike Dungeons** — the dungeon's *region* is seed-fixed, but its exact position inside that region still
  depends on approach direction. Not fixed yet.
- **Block-level border effects** — vanilla-class bugs (dungeon edge checks, tree height reads near chunk borders,
  including inside Thaumcraft's Y-placement) can still cause small local differences depending on exploration order.
  Macro structure *placement* is stable; individual decoration blocks near chunk seams may not be.
- **Roguelike deep rooms vs lakes** — a dungeon spans many chunks; when a neighboring chunk generates its lakes and
  decorations *after* the dungeon carved through it, a couple of deep rooms can end up damaged (or not) depending on
  exploration order. Measured impact: ~2-3 rooms and a handful of chests per dungeon; everything else, including all
  other chest loot, is stable. A full fix (build the dungeon only after its whole footprint is generated) is a
  planned follow-up since it changes when dungeons visually appear.
- **Eldritch obelisk cosmetics** — the ring's above-ground merge with grass/flowers and the obsidian/ancient-stone
  mix can vary by route; the structure, altar, spawner type, and banners are exact.

## What this means in practice

- **Practice = the run.** You can scout a seed, plan the route, and the run world will match the practice world.
- **Seeds are shareable.** "Village with smeltery at X,Z" becomes a verifiable claim anyone can reproduce.
- **Set-seed categories become viable.** Verification can be automated: the worldgen probe generates a region
  headlessly and hashes it — two runners' worlds on the same seed and jar set should hash identically chunk-for-chunk
  (minus the documented residuals).
- **Pack updates still re-roll seeds.** Determinism is per mod-set: changing pack version (or adding/removing any mod
  that registers village buildings) legitimately changes what a seed generates. Pin the pack version per leaderboard
  category as usual.
