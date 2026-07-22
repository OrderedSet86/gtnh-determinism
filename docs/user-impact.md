# What the determinism fixes change for players

Audience: GTNH speedrunners and anyone routing set seeds. Status: draft, pending in-game testing of the candidate jars.

## The one-time cost: your seed notes reset once

Every fix changes *how* randomness is derived, so the first launch with fixed jars produces a **different world for a
given seed than stock does** — a different (but finally canonical) village layout, different trees, different TC
structures. Any routing notes made on stock generation are invalidated once. After that, the seed is stable forever:
same seed → same world, every launch, every machine, every route.

Existing saves are safe: worldgen fixes only affect **newly generated chunks**. Nothing regenerates or corrupts in
areas you've already explored.

## What becomes reliable

| Area | Stock behavior | With fixes |
|---|---|---|
| Village buildings | Layout re-scrambled every game launch — blacksmith/TiC smeltery may or may not exist; measured: same seed gave a 38-piece vs an 81-piece village on consecutive launches | One canonical layout per seed; smeltery presence is a property of the seed, same for everyone |
| Village chest loot | "Cycles between ~15 forms" (really: layout scramble moving/removing the chests) | Fixed per seed given the now-stable layout |
| Witchery covens / wicker men / shacks / goblin huts | Rolled off a clock-seeded RNG — different every run | Seed-determined positions and contents |
| RWG trees (large pines, big trees, cherry blossoms) | Geometry varies per run; perturbs other features near chunk borders | Identical per seed |
| GT ore veins | Rarely reroll identity (copper↔iron) or height depending on approach direction near caves/mountainsides | Vein identity and height are pure functions of the seed |
| Thaumcraft nodes, totems, barrows, eldritch rings (draft fix) | Different even within one session; barrow urn/crate loot clock-random | Feature placement rolls are seed-derived and mutually independent; barrow containers seed-stable |

## What still varies (known residuals)

- **Witchery village walls** — built by a delayed tick-time process; shape still depends on timing. Not fixed yet.
- **Roguelike Dungeons** — the dungeon's *region* is seed-fixed, but its exact position inside that region still
  depends on approach direction. Not fixed yet.
- **Block-level border effects** — vanilla-class bugs (dungeon edge checks, tree height reads near chunk borders,
  including inside Thaumcraft's Y-placement) can still cause small local differences depending on exploration order.
  Macro structure *placement* is stable; individual decoration blocks near chunk seams may not be.

## What this means in practice

- **Practice = the run.** You can scout a seed, plan the route, and the run world will match the practice world.
- **Seeds are shareable.** "Village with smeltery at X,Z" becomes a verifiable claim anyone can reproduce.
- **Set-seed categories become viable.** Verification can be automated: the worldgen probe generates a region
  headlessly and hashes it — two runners' worlds on the same seed and jar set should hash identically chunk-for-chunk
  (minus the documented residuals).
- **Pack updates still re-roll seeds.** Determinism is per mod-set: changing pack version (or adding/removing any mod
  that registers village buildings) legitimately changes what a seed generates. Pin the pack version per leaderboard
  category as usual.
