# 2026-08-29 — Villager spawn determinism, and the first entity measurements

**Answer up front.**

- **Villager profession and position are seed-pure and now launch-stable.** Persisted-world launch
  pair: `Villager 40/40, +0`. They are also predictable *before* generation — the blacksmith of
  seed `-3312870596887951991` was located worldlessly at `(-290, -763)` and the generated world put
  it at `(-290, -763)`.
- **Villager count was wrong, by one, on the first seed measured.** `Hall@-301,77,-715` spawned 1 of
  its 2 villagers. Fixed; 1 of 29 villager-bearing pieces short → 0 of 29.
- **Villager trade offers are nondeterministic by construction and are NOT fixed here.** Three
  unseeded RNGs on the path. No measurement changes this; see Mechanism.
- **Initial animals were clock-random and are now count-stable.** Classes with differing counts
  across a launch pair: 9 → 0 for everything spawned through `performWorldGenSpawning`.
- **Horse speed, jump and health were clock-random and are now pinned.** Movement speed varied
  threefold on the same seed between launches; donkey-versus-horse was a coin flip.
- **Residual: entity NBT content still differs, but ~99% of it is inert** — a ±5% follow-range jitter
  applied to every mob. The meaningful remainder is 45 entities across three mod-mob variant fields.

Pack: `template-2.8.4` (GTNH 2.8.4). Seed `-3312870596887951991`, walk `rows`/`spiral`, radius 6–8,
JDK 21 (Azul). Probe report format 5. Daily pack was **not** measured — see What this does not show.

## Mechanism

### Q1 — route-shaped villager loss (fixed)

`StructureVillagePieces.Village.spawnVillagers` (`:1689-1710`) uses `break`, not `continue`, and bumps
the persisted high-water counter *before* spawning:

```java
for (int i = this.villagersSpawned; i < count; ++i) {
    if (!sbb.isVecInside(x, y, z)) break;   // :1699
    ++this.villagersSpawned;                 // :1704, persisted as "VCount" (:1595-1606)
```

`MapGenStructure.generateStructuresInChunk:90-101` clips each build to `[cx*16+8, cx*16+23]`, so
window boundaries sit at world x/z ≡ 8 (mod 16), and consecutive villager indices map to adjacent
columns (`StructureComponent.getXWithOffset:184-198`). A two-villager piece straddling a boundary
loses one permanently when the far window builds first.

Observed: `Hall` spawns at `minX+4 = -297` and `minX+5 = -296`. −297 ≡ 7, −296 ≡ 8. The fix recovered
a villager at block x = **−296**, the predicted far side.

### Q2 — animal species off the world RNG (fixed)

`SpawnerAnimals.performWorldGenSpawning:246` picks the species with
`WeightedRandom.getRandomItem(p_77191_0_.rand, list)` — the **World** RNG — while every other draw in
the method uses the populate-seeded `p_77191_6_`. `World.rand` is a bare `new Random()`
(`World.java:120`; BugTorch's `replaceRandomInWorld=false` in both packs). Same shadowing shape as the
TiC slime-island bug. Downstream, `EntitySheep.onSpawnWithEgg:292` and `EntityOcelot:357` compound it
off `worldObj.rand`.

### Trades — why they are out of scope

1. `EntityVillager.getRecipes:433-441` rolls the list off `this.rand`; `Entity.rand = new Random()`
   (`Entity.java:204`), BugTorch-redirected to a `nanoTime`-seeded Xoshiro
   (`replaceRandomInEntity=true`, both packs).
2. `EntityVillager.java:586` calls the **no-`Random`** `Collections.shuffle` overload, backed by a
   process-global static, and `:592-596` keeps only index 0 — so that shuffle is the selector.
3. Trades are rolled lazily on first tick or interaction, so no probe-generated world contains an
   `Offers` tag at all.

## Results

### Villagers, seed -3312870596887951991

| arm | pieces short of `expect` | `Hall@-301,77,-715` | villagers |
| --- | --- | --- | --- |
| pre-fix (`0.5pre`) | 1 of 29 | `vcount=1 actual=1 expect=2` | 39 |
| post-fix | **0 of 29** | `vcount=2 actual=2 expect=2` | 40 |

One villager gained, **zero lost** — the fix is additive, not a re-roll, as designed. Post-fix launch
pair: all 40 `EntityVillager` lines byte-identical; `villagepieces` identical.

### Animals — persisted-world launch pair, radius 6

Classes with differing counts across a launch pair, before → after:

| | before | after |
| --- | --- | --- |
| differing counts | 9 (Chicken, Cow, Fox, Goblin, Item, Mooshroom, Pig, Rabbit, Sheep) | Mooshroom, Wolf, goblin, Item |

`Chicken 43/43, Cow 27/27, Pig 49/49, Sheep 44/44, fox 16/16, rabbit 36/36, covenwitch 5/5,
villageguard 6/6, Villager 40/40` — all `+0`.

Remaining count differences and the reason they are **not** `SpawnerAnimals`:
`MushroomCow -7`, `Wolf -4`, `witchery.goblin +4`, `Item -1`. Wolf −4 against goblin +4 is
compensating, which points at Witchery converting wolves on an event handler off `world.rand`.

### Worldless villager prediction

`Prefilter` now emits `Class:profN@x,z` per village piece. Against the generated world:

- **40/40 positions** exact
- **37/40** including profession; the 3 gaps are mod pieces emitted as `prof-1` by design, because
  their ids are config-driven per pack (TiC 78943 ×2, Witchery apothecary 2435) — positions correct
- blacksmith exact: predicted `(-290,-763,prof3)`, actual `(-290,-763,prof3)`

Y is deliberately not emitted: `getYWithOffset` adds `boundingBox.minY`, set from
`getAverageGroundLevel` (`:1653-1679`), which needs terrain.

## Horses — speed, jump and health (fixed)

The routing-relevant one. `EntityHorse.onSpawnWithEgg` decides type, coat variant and three stats from
`this.rand`, via three private helpers:

```
func_110267_cL() = 15 + rand.nextInt(8) + rand.nextInt(9)          max health,     15..30
func_110245_cM() = 0.40 + 3 x (rand.nextDouble() * 0.2)            jump strength,  0.40..1.00
func_110203_cN() = (0.45 + 3 x (rand.nextDouble() * 0.3)) * 0.25   movement speed, 0.1125..0.3375
```

`Entity.rand` is BugTorch's `nanoTime`-seeded Xoshiro, so movement speed varied **threefold** on the
same seed between launches, and donkey-versus-horse was a per-launch coin flip.

`EntityHorseMixin` arms a position-seeded fork for the duration of `onSpawnWithEgg` only, and
redirects `Random.nextInt`/`nextDouble` inside `onSpawnWithEgg` and the three helpers. Two constraints
shaped it: the helpers are separate methods, so a redirect scoped to `onSpawnWithEgg` alone misses
them; and those helpers are shared with `createChild`, so arming the fork by scope rather than
unconditionally keeps horse *breeding* on the stock RNG. Redirecting the `Random.nextInt` **invocation**
rather than the `Entity.rand` field read also sidesteps the GETFIELD-owner ambiguity — `this.rand` is
inherited, so the owner could be emitted as either `Entity` or `EntityHorse`.

Verified on **seed 42** (95 horses at radius 6; found by sweeping 20 seeds — 8 had horses, 42 by far
the most).

**Launch stability.** Across a persisted launch pair, every one of the 95 horses matches on `Type`,
`Variant`, `generic.maxHealth`, `generic.movementSpeed` and `horse.jumpStrength`. The only attribute
that differs is `generic.followRange` — the inert `"Random spawn bonus"` applied to every mob by
`EntityLiving.onSpawnWithEgg`, itemised in the residual table below, deliberately not covered because
it is a ±5% jitter on a 16-block tracking radius.

**Variation is preserved.** Determinism here must not mean uniformity — stats have to differ between
and within spawn packs, just reproducibly. Measured over the 95 horses of one arm:

| stat | distinct values | observed range | vanilla range |
| --- | --- | --- | --- |
| `generic.movementSpeed` | 87 of 95 | 0.1388–0.2887 | 0.1125–0.3375 |
| `horse.jumpStrength` | 87 of 95 | 0.4640–0.9364 | 0.40–1.00 |
| `generic.maxHealth` | 16 | 15–30 | 15–30 |
| `Variant` | 27 | — | — |
| `Type` | 2 (89 horses, 6 donkeys) | — | — |

Within-pack spread is present: the 7-horse pack at chunk (32,24) has speeds 0.256, 0.193, 0.164,
0.245, 0.139, 0.211.

Two caveats read off that data:

- A pack of `Type=1` **donkeys** shows a flat 0.175 speed / 0.50 jump. That is stock behaviour, not a
  collapse: `onSpawnWithEgg` gates the speed and jump rolls (`:1600`, `:1615`) on horse type, so
  donkeys and mules keep the class defaults. Their `maxHealth` still varies.
- **95 horses occupy 90 distinct blocks, so 5 blocks hold two horses each, and each such pair gets
  identical stats.** Position seeding cannot separate two entities at the same coordinate, where
  vanilla would roll them independently — about 5% of horses on this seed. Now that Q2 routes
  `performWorldGenSpawning` entirely through the populate-seeded Random, a per-call spawn ordinal is
  itself deterministic and could be mixed into the fork seed to break these ties without
  reintroducing order-dependence. Not done.

## What this does not show

- No stock-vs-fixed balance run for Q2, which *is* a re-roll.

- **Q1 was never demonstrated to be route-*dependent*.** `rows` vs `spiral` produced identical
  villager output pre-fix. The village sits in the spawn-preload region, generated by
  `initialWorldChunkLoad` before the walk begins, so walk order cannot reach it. The loss is
  confirmed and fixed; the route mechanism is inferred from the geometry, not measured. Demonstrating
  it needs a village inside the walked window (`probe.cx/cz` onto one, or a larger radius).
- **One seed.** Q1 needs a 2-villager piece straddling a ≡ 8 (mod 16) boundary, roughly 1 piece in 8,
  so the 1-of-29 rate here is a single sample. The 20-seed sweep was not run.
- **Daily pack not measured.** VillageNames replaces the generator, registers ~235 creation handlers
  and rolls careers on tick.
- **Entity NBT content is still nondeterministic, but almost all of it is inert.** `changed=441` of
  522 persisted entities. Broken down by which tag actually moves:

  | tag | count | matters? |
  | --- | --- | --- |
  | `Attributes` (all mobs) | 301 | **no** — a `"Random spawn bonus"` modifier on `generic.followRange`, `rand.nextGaussian() * 0.05` from `EntityLiving.onSpawnWithEgg`. ±5% of a 16-block tracking radius. |
  | `Item` `Rotation`/`Motion` | 138 | no — dropped items |
  | `etfuturum.rabbit` `RabbitType` | 24 | yes, minor — rabbit variant |
  | `witchery.goblin` `Profession` | 12 | yes, minor |
  | `etfuturum.fox` `Equipment` | 9 | yes, minor — held item |
  | `witchery.covenwitch` `SkinType` | 2 | no — cosmetic |

  All trace to `Entity.rand`, clock-seeded via BugTorch's Xoshiro redirect. An earlier draft of this
  file blamed `EntityChicken`'s `timeUntilNextEgg`; that was wrong — 1.7.10 does not persist it, and
  it does not appear in the diff.
## Bonus finding: the warm-run rule was wrong

`docs/HANDOFF.md` required cold runs on the grounds that a warm daemon moves terrain. Re-measured
here, same seed/order/radius, post-fix jar, 914 chunks:

```
warm (first job of a fresh daemon) vs cold
DIFFERENT — 165/914 chunks differ (18.1%)
  blocks-only: 0   te-only: 165   both: 0
```

**Zero differing blocks.** The original `gravel=60` figure predated the Et Futurum deepslate and
cave-vine fixes, which were themselves `world.rand` bugs. Terrain, block layout and villager spawn can
be measured warm; the surviving difference is tile-entity-only and matches the documented ore-TE /
small-ore warm residual, so cold runs are still required for ore TEs and chest contents. This retires
a rule that cost roughly 8x wall-clock on every terrain investigation.

## Corrections to earlier notes

- `docs/HANDOFF.md` asserted the warm-vs-cold terrain figure as a measurement rule in two places
  while retracting it in a third. The retraction is newer; the two assertions now point at it.
- `docs/HANDOFF.md` claimed no precedent for mixing an obfuscated vanilla class. Four now ship.
- The `spawnVillagers` call-site map: line 736 is `Hall` (butcher + farmer), not `House4Garden`, and
  `House3` also spawns 2. Only `Hall` and `House3` are Q1-exposed among vanilla pieces.
- `World.spawnEntityInWorld`'s `chunkExists` drop (`World.java:1491-1494`) **cannot** fire for vanilla
  village villagers: `Chunk.populateChunk:1140` requires all four window chunks to exist and
  `isVecInside` confines the spawn to them. The live drop vector is the `EntityJoinWorldEvent` cancel
  at `:1503`.

## Harness changes

- `REPORT_FORMAT` 4 → 5. Additive and opt-in (`-Dprobe.entities=true`): `b`/`s`/`t`/`o` are unchanged,
  and omitting the flag reproduces a format-4 report exactly. Entities are deliberately **not** a
  per-chunk digest key — Q2 noise would have made one ~100% different between any two runs, and
  `diff-probe.py:36` compares whole chunk dicts, so a new key would invalidate the corpus while the
  classifier still printed zero differences in every category.
- New sections `villagers`, `villagepieces` (`vcount`/`actual`/`expect`, which separates Q1 from an
  `EntityJoinWorldEvent` cancel from position movement), `entities` (per-class count + content digest).
- `scripts/diff-region-entities.py`. Not a fork of `diff-region-tes.py`: position is not a unique key
  for entities (four `EntityVillageGuard` share one block in this seed), and its `%.6g` float format
  resolves only to ~0.001 at |x| ≈ 1000. Keys on `(id,x,y,z,ordinal)`, formats with `repr()`, strips
  only `UUIDMost`/`UUIDLeast` and says so in the output.
- `PROBE_ENTITIES` in `run-probe.sh`.

## Reproduce

```sh
export PROBE_JAVA=~/.gradle/jdks/azul_systems__inc_-21-amd64-linux.2/bin/java
T284=~/.cache/gtnh-determinism/template-2.8.4
scripts/build-jar.sh fix   --deploy $T284
scripts/build-jar.sh probe --deploy $T284

# villager + animal arms
PROBE_ENTITIES=true PROBE_PORT=25701 scripts/run-probe.sh $T284 -3312870596887951991 rows a.json 6
cp -a $T284/World wA            # run-probe.sh wipes World at the start of every run
PROBE_ENTITIES=true PROBE_PORT=25702 scripts/run-probe.sh $T284 -3312870596887951991 rows b.json 6
cp -a $T284/World wB
python3 scripts/diff-region-entities.py wA wB

# worldless villager prediction
scripts/prefilter.sh -3312870596887951991 pf.jsonl   # village_starts[].villagers
```
