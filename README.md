# gtnh-determinism

Speedrun mod for GT: New Horizons 2.7.4 through 2.9.0-RC-2. Makes worldgen fully
deterministic based on seed.

Discord: https://discord.gg/PbMWTcnZgC

![Four cold boots of one seed on GTNH 2.9.0-beta-3, cropped to five fixed windows. Rows 1-2 are
stock, rows 3-4 have the jar installed. Red marks every surface column that differs from the row
directly above.](docs/img/launch-variance.png)

(Note: structures are forced above to make for a nice splash photo.)

## The fix jar

**Grab the latest `gtnhdeterminism` jar from [Releases](../../releases) and drop it into `mods/` of a stock
GTNH instance.**

| Pack | Status |
|---|---|
| 2.7.4 | Tested. Launch pair byte-identical |
| 2.8.0 – 2.8.4 | Expected to work; last tested at 2.8.4 |
| 2.9.0-nightly / daily | Works on 0.14.0+ (tested on 2026-10-11 daily) |
| 2.9.0-beta-3 | Tested. Same veins as daily-707 from the same seed, and the loot tables are unchanged |
| 2.9.0-RC-1, RC-2 | Works on 0.14.0+ (tested on RC-1 and RC-2) |

| Target | What was wrong | What the jar fixes |
|---|---|---|
| Forge/FML | Village building handlers iterate in per-launch HashMap order | Village layouts (smeltery/blacksmith presence) identical per seed |
| Forge/FML (chest loot) | Loot tables are rewritten when the first world starts, so only the first world per client session rolled pristine loot. The reset also left roll counts mutated (`villageBlacksmith` drew 4-11 from a 3-9 table) | Tables fully reset (items, min, max) before every world start |
| Forge/FML (spawn-preload split) | A cold boot fills spawn-region chests before TooMuchLoot rewrites the tables, so the 25×25-chunk preload used a different table from the rest of the world (10 categories differed) | TooMuchLoot applied before the first chunk exists, so the whole world uses one table, in singleplayer and on a dedicated server. Changes spawn loot (see [Balance changes](#balance-changes)) and moves blocks near spawn |
| Minecraft (structure chests) | Village, mineshaft, stronghold, pyramid, vanilla-dungeon and Witchery chests drew items from the chunk's shared populate `Random`, so contents depended on whatever consumed draws earlier in that chunk | Contents derived from the structure piece and the chest's position in it. Stock draws are still made, so **zero blocks move**; pool, weights and roll range unchanged |
| Witchery | Clock-seeded `world.rand`; structure *type* picked by shuffling a shared list in place per chunk; wicker-man spawner rolled off `world.rand` | Covens, wicker men, shacks, goblin huts — position, type, and spawner seed-stable |
| Witchery (village walls) | Walls were built by a hidden tile entity 40+ ticks after generation, probing whatever terrain existed at that moment — shape depended on route and timing, and idle worlds could skip walls entirely | Walls build during generation from virgin-terrain heights, sliced per chunk: shape, gates, and guard posts seed-stable |
| Thaumcraft | Terrain-gated draw skew, `world.rand` barrow loot, first-chunk-wins bonus nodes, maze gen on a racing thread | Nodes, totems, barrows + loot seed-stable |
| Thaumcraft (eldritch rings) | Obelisk presence was a population-order lottery — earlier rings suppressed up to 25 later candidates per window | One seed-pure site per 25×25-chunk region at stock density; spawner/banners deterministic |
| Thaumcraft (hilltop stone circles) | Existence read live blocks, so a circle appeared or vanished with chunk load order (12 circles `rows` vs 14 `spiral`) | Anchor and probes read virgin terrain. 24 of 24 seeds route-identical |
| Thaumcraft (barrows) | Same live-read defect as hilltop circles (5 barrows `rows` vs 2 `spiral`) | Virgin-terrain anchor and probes. 24 of 24 seeds route-identical |
| Minecraft (vanilla dungeons) | Room existence depended on chunk load order through the attempt coordinates, a cross-chunk placement scan and the room-size draws (84 rooms `rows` vs 85 `spiral`) | Attempt coordinates forked per chunk; scan and size evaluated on virgin terrain. 24 of 24 seeds route-identical |
| Thaumcraft (loot amulet) | Vis Amulet charge rolled from a wall-clock `Random` at mod init, so it changed every launch | Charge derived from world seed + chest position + slot. Which item a loot bag gives is still rolled when the bag is opened |
| GregTech (ore veins) | Vein identity was decided by whichever nearby chunk the route populated first | Decision pinned to the vein's own oreseed chunk, read from virgin terrain. Rows-vs-spiral differing veins: 0 in the Overworld, Twilight Forest and Nether (the only dimensions measured; `gtnhdet.orepin.dims`). `-Dgtnhdet.orepin=false` restores stock. Nether *decoration* is not fixed |
| Et Futurum Requiem | Deepslate band (y16-31) and cave-vine length rolled off clock-seeded `World.rand` | Derived from world seed + position. Largest source on 2.9: 192,495 → 4,847 route-differing blocks |
| Roguelike Dungeons | Position, placement and decoration read live terrain, hash-ordered sets and clock-seeded shuffles, and dungeons wrote into neighbour chunks in approach order | Position, layout, decoration and chest contents are a pure function of the seed; cross-chunk writes applied after each chunk's own decoration |
| LootGames | Puzzle-room cracked-wall/broken-lamp variants rolled off a static clock-seeded `Random` | Room cosmetics seed-stable (minigame rewards are gameplay-time and untouched) |
| RWG | Terrain-gated draw skew in all 29 decorators; `Math.random()` big trees | Decoration streams stable; big trees rolled from the seed |
| TinkersConstruct | Slime islands sized/shaped by a clock-seeded field (`rand`/`random` shadowing bug) | Slime islands seed-stable |
| BiomesO'Plenty | Flora picked with `Math.random()` over an identity-ordered HashMap, shifting the shared decoration stream | Flora + downstream dirt/gravel patches seed-stable |
| ProjectRed | Lily colors (dye yield!) rolled clock-random at worldgen | Lily colors derived from seed+position |
| Forestry | Village bee house rolled bee species/frames/flowers off `world.rand` | Village bees seed-stable |
| Minecraft (villagers) | A two-villager building on a populate-window boundary permanently lost a villager when the far window built first | Placement tracked per villager, so every villager spawns in any order. Also covers the Forestry, Railcraft and TiC workshops |
| Minecraft (passive mobs) | Worldgen animal *species* rolled off clock-seeded `world.rand` | Species from the populate `Random`; sheep colour and ocelot kittens from world seed + spawn position |
| Minecraft (horses) | Type, coat, health, jump and speed rolled off clock-seeded `Entity.rand`, so speed could differ up to 3× between launches | All five derived from world seed + spawn position. Breeding unchanged |

### Balance changes

What a player can get compared with stock:

- **Spawn-region loot:** TooMuchLoot loot tables are meant to apply to every chunk, but on stock they
  did not reach the chunks around spawn, which generate before TooMuchLoot runs. The jar fixes this
  pre-emptively, because it is likely to be fixed upstream later. Spawn chests now use the TooMuchLoot
  tables like the rest of the world, so HungerOverhaul's 1-32 marshmallow stacks are now 1-2. The bonus
  chest is not changed.
- **Barrows:** about 0.5 → 1.4 per seed (+209%, p=0.0002, 24 seeds at radius 30).
- **Villagers:** some villages get 1 more villager than on stock. Stock lost one villager from a
  two-villager building that crosses a chunk border, depending on which side generated first.

**Adopting the jar re-rolls seeds once per jar version.** The fixes change how randomness is
derived, so a seed produces a different — now canonical — world than stock (and than earlier jar
versions). Existing saves are safe: only newly generated chunks are affected. The spawn-preload fix also
moves blocks within ~200 blocks of spawn compared with stock (28,083 blocks on one seed, including 474 GT
ore); see [docs/user-impact.md](docs/user-impact.md).

## Verification

Tested headless on real GTNH server packs with the WorldgenProbe harness in this repo. Ground truth is
the **persisted world**: region-file blocks, full tile-entity NBT including chest contents, and (probe
format 5 onward) the entity list.

- **Launch tests** — same seed, two fresh JVMs, same walk order: persisted worlds are
  **byte-identical** (primary seed: 1,184 chunks, 372,026 tile entities, zero differences).
- **Route tests** — same seed generated in different chunk orders (rows / columns / spiral):
  structures, layouts, veins, spawners and surviving chest contents identical.
- **Entity tests** — villagers, worldgen animal counts and all horse stats identical across a launch
  pair: [results/2026-08-29-villager-spawn-and-animal-determinism](results/2026-08-29-villager-spawn-and-animal-determinism/README.md).
- **Balance evidence** — a 60-seed stock-vs-fixed corpus shows vein materials, small ores, village
  pieces and Witchery counts equivalent within ±10%; a 500k-draw Monte-Carlo over the shipped loot
  tables checks rare chest items.
- **Daily build** — on `daily-2026-08-28+707`, rows-vs-spiral differing blocks drop from 371,406 to
  67,519; see [Known remaining nondeterminism](#known-remaining-nondeterminism) and
  [results/2026-08-28-daily-2.9-compatibility](results/2026-08-28-daily-2.9-compatibility/README.md).

### Known remaining nondeterminism

**The target is zero; this list is a defect backlog.** Daily build, seed `-777`, radius 6, rows vs
spiral, 0.8 jar: **67,519 differing blocks across 168 chunks**, against a launch floor of 2-6 blocks.
These are block-ID differences; counting `id:meta` gives 157,325, because GT ore meta also encodes the
host stone.

| source | blocks | note |
|---|---|---|
| decoration (grass/flowers/trees/hives) | 38,856 | Four populate-`Random` consumers move with load order: Railcraft/NHCore geodes, TiC + NHCore surface ores, Forestry wild hives and Botania flowers. [docs/populate-stream-census.md](docs/populate-stream-census.md) |
| deep dirt/gravel/stone patches | 7,574 | 97.8% is RWG `WorldGenMinable` dirt pockets; ~21,600 blocks counting knock-ons ([attribution](results/2026-09-06-dirt-gravel-attribution/README.md)) |
| GT stone-layer blobs (granite/stone) | 7,468 | Not moved by the jar at all; grows 7% when dirt pockets are removed |
| sand/gravel/clay/fluid settling | 6,087 | Tick timing; includes clay and every `*↔gravel` transition |
| EtFuturum deepslate band | 4,582 | Was 192,495. 93% of the rest follows the dirt pockets |
| GT / mod ore placement | 2,952 | Per-block placement only; vein identity is exact (0 differing regions). `OreManager.setOreForWorldGen` reads the live world at every write, mostly giving the same ore in a different host stone. Over half follows the dirt pockets |

**Village Names chests are under-filled (bug).** Village Names pieces refill a chest once per chunk box
that intersects them, so on stock a chest gets 2-4 batches depending on chunk order. The jar fills each
one once, so these chests hold fewer stacks than on stock.

**Chest loot** is launch- and route-deterministic: 536 chests across 10 seeds are identical between two
JVMs, and 124 chests on `-777` are identical across `rows`, `cols` and `spiral`. Evidence:
[chest-reverification](results/2026-08-29-chest-reverification/README.md),
[position-derived-chests](results/2026-08-29-position-derived-chests/README.md),
[post-only-loot](results/2026-08-29-post-only-loot/README.md),
[chest-site-coverage](results/2026-08-29-chest-site-coverage/README.md).

**Entities** (probe format 5 onward): villagers and worldgen animal counts are launch-stable. What
remains:

| source | note |
|---|---|
| villager trade offers | Rolled off clock-seeded `Entity.rand` and a process-global shuffle, lazily on first interaction, so no generated world contains them |
| `generic.followRange` on every mob | ±5% `nextGaussian` spawn bonus; ~99% of remaining entity NBT differences |
| mod-mob variant fields | `RabbitType`, Witchery goblin `Profession`, fox `Equipment` — 45 entities on the measured seed |
| Witchery wolf/goblin conversion | A compensating `Wolf −4` / `goblin +4` on a launch pair, suggesting an event handler converting wolves off `world.rand` |
| co-located spawns | Two mobs on the same block share a position-derived roll where stock rolls independently — 5 of 95 horses on the measured seed |

**"Only NBT differs" never closes an investigation, and the category labels above are for prioritising
work, never for declaring something benign.**

## Seed search: what the stage-0 prefilter answers

Without generating a chunk, the prefilter computes chest contents exactly for:

| source | how | verified |
| --- | --- | --- |
| Roguelike dungeon chests | the mod's own generator, run against virgin terrain | 108/108, with NBT |
| Village piece chests | measured piece-to-site table, position-derived fork | 95/95, XZ exact, Y not predicted |
| Stronghold chests | ring positions are pure arithmetic; piece layouts enumerated | 26/26, with NBT |
| Witchery village chests | roll count redrawn from the chest's own position | 10/10, with NBT |
| Witchery circles and other standalone structures | the real handler, run against a scratch overlay | 5/5, with NBT |
| Mineshaft minecart chests (`-Dprobe.prefilter.mineshaft=R`) | real structure generation replayed against a scratch overlay | 531/531 contents where predicted |

It also answers village layouts, spawn point, terrain and Witchery structure cells. It does **not**
cover vanilla `WorldGenDungeons` rooms or temples.

Limits:

- Witchery structures generate after chunk decoration, but the prefilter sees only virgin terrain, so
  a predicted Y can be one block low. Contents are unaffected.
- `ComponentVillageBookShop` writes its chest slots directly, so no hook sees it and it cannot be
  predicted.

**Deterministic** and **stage-0 computable** are different questions;
[seedsearch/README.md](seedsearch/README.md) separates them.

**Reporting a worldgen bug?** Please include the jar version, seed, and coordinates.

## Route map viewer and world bundles

The route-map viewer and per-seed world bundles (chest loot, POIs, GT veins, base rasters) live in
[gtnh-seedlib](https://github.com/OrderedSet86/gtnh-seedlib), under `routemap/` and
`worlds/<seed>/dim{0,7,-1}/`. Bundles are built from a full-generation radius-60 probe, so they include
structures the prefilter cannot predict.

## Generating a route map

One command, on Windows, macOS or Linux:

```bash
scripts/routemap.py --seed <seed> --instance <prism-instance-name>
scripts/routemap.py --list          # which GTNH instances Prism knows about
scripts/routemap.py                 # no arguments: a window instead of a CLI
```

It builds a headless server from the player's **own Prism instance** (about 13 MB of downloads instead
of the 620 MB server pack), so the bundle matches the mod set the player loads, and it works for daily
and beta packs. Bundles land in `<instance>/routemap/<seed>/`. The assembled server (~550 MB) is deleted
afterwards unless `--keep-server` is passed; nothing else in the instance is written to.

- `scripts/routemap-bootstrap.py` — Prism discovery, JRE selection, server assembly. Drops only
  client-side mods, which abort a headless boot.
- `scripts/make-routemap.py` — the driver: one cold boot per dimension, then the prefilter, the CSV
  exports and `build_world_bundle.py`.
- `scripts/build-routemap-zip.py` — builds the Windows distributable around python.org's embeddable
  Python rather than PyInstaller, whose packed binaries antivirus tools flag.

The driver fails loudly on a degraded run: low palette coverage, empty region files, an empty loot
table, or trace lines missing from the run log.

## Repo layout

- `fix-build/` — source of the fix jar (mod id `gtnhdeterminism`)
- `qol-build/` — **GTNH Speedrun QoL** (mod id `gtnhspeedrunqol`), a separate client-side jar that fixes
  interface friction only. It never changes what the game simulates, so install it with or without the fix jar.
- `probe-build/` — **WorldgenProbe**, the headless determinism tester (inert without `-Dprobe.*` flags)
- `scripts/` — verification + evidence tooling (see below)
- `docs/populate-stream-census.md` — every consumer of the chunk populate `Random` in this pack, in
  dispatch order, with a per-consumer verdict on whether it can move with chunk load order
- `forks/` — mod forks carrying the same fixes at source level for upstream PRs (branch `determinism-fixes`)
- `docs/` — audit report, fix list, user-impact notes
- `jars/` — staging area for testing builds (releases are built by CI from tags)

## Headless verification harness

Quick pair test (fresh JVM per run = launch test):

```bash
PROBE_JAVA=/path/to/java17 scripts/run-probe.sh <server-dir> <seed> rows a.json 8
PROBE_JAVA=/path/to/java17 scripts/run-probe.sh <server-dir> <seed> rows b.json 8
scripts/diff-probe.py a.json b.json      # expect IDENTICAL
```

Walk orders `rows|cols|rows-reverse|spiral` test chunk-order dependence. For structures that span
chunks (dungeons!), live hashes can miss late writes — compare persisted worlds instead: submit a
`save=true` job, then `scripts/diff-region-blocks.py` / `scripts/diff-region-tes.py` on the two
world dirs. That is the ground truth this project's claims rest on.

Batch tooling (boot the 200-mod pack once, then ~10 s per world):

- `scripts/probe-queue.sh start|submit|wait|stop` — warm queue daemon (multi-seed, search reports,
  save jobs, Monte-Carlo loot jobs)
- `scripts/probe-farm.sh` — N hardlink-cloned server instances with a shared job queue
- `scripts/seed-search.sh` + `scripts/searchlib.py` — seed search over biomes/water/clay/chests/ores;
  corpora live in [gtnh-seedlib](https://github.com/OrderedSet86/gtnh-seedlib)
- `scripts/effects-ab.sh` + `scripts/effects-report.py` + `scripts/balance-report.py` — stock-vs-fixed
  effects and balance-equivalence evidence

All probe scripts need `PROBE_JAVA` pointed at a Java 17-21 runtime (the pack's supported range).
