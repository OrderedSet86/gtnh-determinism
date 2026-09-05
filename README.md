# gtnh-determinism

Speedrun mod for GT: New Horizons 2.7.4 through daily-2026-08-28+707. Makes worldgen fully
deterministic based on seed.

Discord: https://discord.gg/PbMWTcnZgC

## The fix jar

**Grab `gtnhdeterminism-0.5.jar` (or latest) from [Releases](../../releases) and drop it into `mods/` of a stock
GTNH instance.** One jar covers the whole supported range: it picks its GregTech fix by which
version is installed, and every other fix targets code that is unchanged across the range.

| Pack | Status |
|---|---|
| 2.7.4 | Tested. Launch pair byte-identical |
| 2.8.0 – 2.8.4 | Expected to work; last tested at 2.8.4 |
| 2.9.0-nightly / daily | Tested at `daily-2026-08-28+707` |

| Target | What was wrong | What the jar fixes |
|---|---|---|
| Forge/FML | Village building handlers iterate in per-launch HashMap order | Village layouts (smeltery/blacksmith presence) identical per seed |
| Forge/FML (chest loot) | Loot tables are static and get rewritten once the first world starts — only the **first world created per client session** rolled spawn-region loot from pristine tables; every later world rolled different chests from the same seed. The reset itself restored only a category's item list and left its roll COUNT at the mutated value, so `villageBlacksmith` drew 4-11 stacks from the 3-9 table — a table that never existed anywhere | Tables reset before every world start, all three fields (items, min, max), so each world rolls like the first of a session |
| Forge/FML (spawn-preload split) | A cold boot generates its spawn region inside `loadAllWorlds`, before `FMLServerStartingEvent` where TooMuchLoot replaces whole categories. A chest is filled when it is placed, so the 25×25-chunk preload kept the pre-rewrite table forever and everything outside it used the post-rewrite one. Ten categories differed; `villageBlacksmith` went 60 entries rolling 3-9 to 118 rolling 4-11 | TooMuchLoot applied before the first chunk exists and its own later run suppressed — one table for the whole world. **Balance note: HungerOverhaul's food injection only ever reached chests through the pre table, so the 1-32 marshmallow stacks are gone; the post table gives 1-2.** Kept as a fix rather than preserved for routing, so a future upstream fix cannot break routing silently |
| Minecraft (structure chests) | Village, mineshaft, stronghold, pyramid, vanilla-dungeon and Witchery chests draw every item from the chunk's shared populate `Random`, so contents depend on everything that consumed draws earlier in that chunk — mod `PopulateChunkEvent.Pre` handlers, an intersecting mineshaft, whichever village pieces were built first — and each of those is only as stable as the terrain reads behind it | Contents derived from the structure piece and the chest's position within it. The stock body still runs first, so every draw it would have made is still made and **zero blocks move** — measured: the block-differing chunk set is identical to the two-run noise floor. Pool, weights and roll range unchanged; only the RNG source moves |
| Witchery | Clock-seeded `world.rand`; structure *type* picked by shuffling a shared list in place per chunk; wicker-man spawner rolled off `world.rand` | Covens, wicker men, shacks, goblin huts — position, type, and spawner seed-stable |
| Witchery (village walls) | Walls were built by a hidden tile entity 40+ ticks after generation, probing whatever terrain existed at that moment — shape depended on route and timing, and idle worlds could skip walls entirely | Walls build during generation from virgin-terrain heights, sliced per chunk: shape, gates, and guard posts seed-stable |
| Thaumcraft | Terrain-gated draw skew, `world.rand` barrow loot, first-chunk-wins bonus nodes, maze gen on a racing thread | Nodes, totems, barrows + loot seed-stable |
| Thaumcraft (eldritch rings) | Obelisk presence was a population-order lottery — earlier rings suppressed up to 25 later candidates per window | One seed-pure site per 25×25-chunk region at stock density; spawner/banners deterministic |
| Thaumcraft (loot amulet) | `Config.initLoot()` seeds a `Random` from the wall clock at mod init and bakes the roll into the loot Vis Amulet's NBT, then copies that one stack into the chest and loot-bag tables — so every launch dealt a different charge, and every amulet within a session dealt the *same* one. It runs *before* the load-complete snapshot above, so that fix preserved the randomness rather than catching it | Charge derived from world seed + chest position + slot, so it is seed-stable and route-stable while differing from chest to chest. Per-aspect distribution is stock's `nextInt(5)`; only the correlation between amulets changes, from identical to independent. The init-time roll is pinned too, so an amulet pulled from a Thaumcraft loot bag carries a fixed charge rather than a per-launch one — but **which** item a bag gives you is rolled from `world.rand` when the player opens it, and stays gameplay-time random |
| GregTech (ore veins) | Vein identity was decided from whichever of the surrounding 5x5 chunks the player's route populated FIRST: that chunk's coordinates set the clipping window, the local density AND the probe column, and a rejected candidate rolls a *different* mix. Answering the probe from virgin terrain closed only one of those three channels, which is why two earlier attempts each moved single digits | The identity decision is pinned to the vein's own oreseed chunk, and the reads it still makes answer from virgin terrain — a function of (seed, dim, oreseed, mix). Rows-vs-spiral on `-1636594104014467454` r60: overworld **140/1760 -> 0/1764**, Twilight Forest **225/1702 -> 0/1728**, against a 0/1762 same-order floor. Whitelisted to those two dimensions (`gtnhdet.orepin.dims`, default `0,7`) because they are the only two measured; the Nether is 21.9% and stays on stock. `-Dgtnhdet.orepin=false` restores stock bit-for-bit. The reroll is relocated, not disabled |
| Et Futurum Requiem | `doDeepslateGen` gated the 4-block deepslate transition band with `chunk.worldObj.rand.nextInt(4)` per block — clock-seeded `World.rand`, so the y16-31 band was redrawn every launch. Cave-vine tile entities jittered their length the same way | Band derived from world seed + position; vine length seed-stable. Largest single source on the 2.9/daily line: 192,495 route-differing blocks before, 4,847 after |
| Roguelike Dungeons | Position probed live neighbor terrain; placement decisions read live world state; MST-floor decoration iterated an identity-hashed `HashSet`; three rooms placed fireplaces/chests with clock-seeded `Collections.shuffle`; loot pipeline shifted with chest membership; dungeons wrote far outside their trigger chunk, racing each neighbor chunk's own lakes/decoration by approach order (a deep chest could exist or not per route) | Dungeon position, layout, every floor's decoration, and every chest's contents are a pure function of the seed; writes are sliced per chunk and applied after that chunk's own decoration, so the dungeon-vs-lake contest resolves identically on every route |
| LootGames | Puzzle-room cracked-wall/broken-lamp variants rolled off a static clock-seeded `Random` | Room cosmetics seed-stable (minigame rewards are gameplay-time and untouched) |
| RWG | Terrain-gated draw skew in all 29 decorators; `Math.random()` big trees | Decoration streams stable; big trees keep their stock 7–13 size variety, rolled from the seed |
| TinkersConstruct | Slime islands sized/shaped by a clock-seeded field (`rand`/`random` shadowing bug) | Slime islands seed-stable |
| BiomesO'Plenty | Flora picked with `Math.random()` over an identity-ordered HashMap, shifting the shared decoration stream | Flora + downstream dirt/gravel patches seed-stable |
| ProjectRed | Lily colors (dye yield!) rolled clock-random at worldgen | Lily colors derived from seed+position |
| Forestry | Village bee house rolled bee species/frames/flowers off `world.rand` | Village bees seed-stable |
| Minecraft (villagers) | A village piece is built once per populate window it overlaps, and `spawnVillagers` uses `break` rather than `continue` while bumping its persisted counter *before* spawning. A two-villager building straddling a window boundary (world x/z ≡ 8 mod 16) permanently loses one when the far window builds first | Placement tracked per villager index instead of as a high-water mark, so each villager is placed by whichever window contains it, in any order. Output equals stock's best-case route, so no seed loses a villager it previously had. Also covers Forestry's bee house, Railcraft's workshop and the TiC tool workshop, which call the same method |
| Minecraft (passive mobs) | `SpawnerAnimals.performWorldGenSpawning` picks the *species* off `world.rand` while every other draw in the method uses the populate-seeded `Random` — the same shadowing shape as the TiC slime bug. Which animals a seed starts with, and therefore the first leather, wool and food on the route, was clock-random | Species drawn from the populate Random. Sheep fleece colour and ocelot kittens, which are rolled later off `worldObj.rand`, derive from world seed + spawn position |
| Minecraft (horses) | `EntityHorse` rolls type, coat variant, max health, jump strength and movement speed off the clock-seeded `Entity.rand`. Speed spans 0.1125–0.3375, so the same seed gave a horse up to **three times faster** depending on the launch, and donkey-versus-horse — portable storage or not — was a coin flip | All five derived from world seed + spawn position. Variation is preserved, not flattened: 87 distinct speeds and 87 distinct jump strengths across 95 horses on one seed. Horse *breeding* keeps the stock RNG — the fork is armed only while `onSpawnWithEgg` runs |

Scope, counted from the source rather than tallied by hand: **46 mixin classes** rewiring **73 target
classes** across **12 mods, Forge/FML and vanilla Minecraft**, plus two reflection patches that are not
mixins. Re-derive with `grep -rl '@Mixin' fix-build/src/main/java | wc -l` (subtract the diagnostics
below) and by counting distinct `@Mixin` targets — a hand-maintained "number of fixes" was dropped
because nothing defined what counted as one, so it drifted and could not be checked.

Some fixes need more than one mixin. GregTech carries four: two are alternatives with exactly one
binding per GT version (the vein-reroll probe moved class between 5.09.51 and 5.09.54), and two more
pin the vein-identity decision to the oreseed and virginise the reads it still makes — those two always
bind. The Vis Amulet needs an init-time pin and a per-chest derivation; the passive-mob spawn fix takes
one mixin each for the shared spawner, sheep, ocelots and horses; the spawn-preload split needs one to
apply TooMuchLoot early and one to suppress its own later run; and structure chest contents need five —
capture the table, capture the piece two ways, fence off chunk population, refill the chest.
Three further diagnostic mixins ship inert behind `-Dgtnhdet.traceseg` and are not counted here.

Eleven mixins patch Minecraft or Forge itself rather than a mod — nine vanilla classes plus Forge's
`ChestGenHooks`. Most use default `remap` and are registered in the early mixin config rather than
through the late loader, because their targets load before the late loader runs.

## Verification

Tested headless against actual GTNH server packs with the WorldgenProbe harness in this repo, and
ground truth is the **persisted world** (region-file blocks, full tile-entity NBT including chest
contents, and — from probe format 5 — the entity list):

- **Launch tests** — same seed, two fresh JVMs, identical walk order: persisted worlds are
  **byte-identical** (primary seed: 1,184 chunks, 372,026 tile entities, zero differences —
  every block, every chest slot).
- **Route tests** — same seed generated in different chunk orders (rows / columns / spiral,
  simulating different approach paths): structures, layouts, veins, spawners, and all surviving
  chest contents identical.
- **Entity tests** — villager count, position and profession, and the per-animal counts from the
  vanilla worldgen spawner, are identical across a launch pair on the persisted world. Horse type,
  coat, health, jump and speed match across launches for all 95 horses of the test seed while keeping
  87 distinct speed values, so the fix pins the seed without flattening the variety.
  [results/2026-08-29-villager-spawn-and-animal-determinism](results/2026-08-29-villager-spawn-and-animal-determinism/README.md).
- **Balance evidence** — a 60-seed A/B corpus (stock vs fixed, cold runs) shows vein materials,
  small ores, village pieces, and witchery counts statistically equivalent (±10% bounds); a
  500k-draw Monte-Carlo over the shipped loot tables certifies rare chest items.
- **Daily build** — on `daily-2026-08-28+707` a rows-vs-spiral route test drops from 371,406 to
  70,348 differing blocks (−81%), and vein identity is 99.0% route-stable (37 of 3,627 ore-involved
  blocks change material). **70,348 is not a good number — the target is zero**, and the reduction
  should not be quoted without it. A full inventory of the residual, every differing block assigned
  to a category summing to 100%, is in
  [results/2026-08-28-daily-2.9-compatibility](results/2026-08-28-daily-2.9-compatibility/README.md).

### Known remaining nondeterminism

**The target is zero and this list is a defect backlog, not fine print.** Measured on the daily
build, seed `-777`, radius 6, rows vs spiral, with the jar installed — 70,348 differing blocks
across 169 chunks:

> **Re-measured on 0.8** (same seed, radius and walk pair): **66,034** differing blocks, down 4,314.
> Every category is at or below the figure below, and GT ore drops 36% — that is the vein-identity fix:
> identity no longer flips, so whole-footprint replacements are gone and only host-stone variation on
> agreed veins remains. 0.8 values: decoration 38,082 / deep dirt-gravel-stone 7,459 / GT stone blobs
> 7,044 / sand-gravel-clay-fluid 5,976 / deepslate 4,863 / **GT ore 2,385**.
>
> Counting note, because it cost a false alarm: these are block-**ID** differences.
> `scripts/diff-region-blocks.py` reports `id:meta` transitions and counts a change when EITHER moves,
> which on this world reads 157,325 — mostly GT ore blocks whose meta encodes material *and* host stone,
> i.e. double-counting the thing being measured. Metadata-only differences are just 1,370. Compare like
> with like or the table looks like it doubled.

| source | blocks | note |
|---|---|---|
| decoration (grass/flowers/trees/hives) | 39,425 | endemic 1.7.10 decorator ordering; no per-mod fix known |
| deep dirt/gravel/stone patches | 8,495 | GT ore placement is downstream of this — fixing it pays twice |
| GT stone-layer blobs (granite/stone) | 7,451 | the one category the jar does not move at all (−3% vs stock) |
| sand/gravel/clay/fluid settling | 6,389 | tick-timing; clay inherits it, since clay replaces sand/gravel |
| EtFuturum deepslate band | 4,847 | was 192,495 before the deepslate fix |
| GT / mod ore placement | 3,741 → **2,385** on 0.8 | per-BLOCK placement only. Vein *identity* is now exact: 0 differing regions rows-vs-spiral on this seed (106 regions), and on `-1636594104014467454` at r60 in both the overworld (0/1764) and Twilight Forest (0/1728). What remains is `OreManager.setOreForWorldGen` reading the live world at every write — mostly the same material in a different host stone — deliberately un-redirected |

Chest loot is fully launch-deterministic, measured two ways: 131/131 chests identical across two cold
launches of one seed with **zero tile-entity differences of any kind**, and 536 chests / 3,929 item
stacks across **10 seeds** identical between two separate JVMs — existence, item lists and NBT all
counted separately. That multi-seed check was re-run after the structure-chest fix changed three times
on 2026-08-29, so it reflects the jar as it stands rather than an earlier build
([results/2026-08-29-chest-reverification](results/2026-08-29-chest-reverification/README.md)). That needed one more fix: `Thaumcraft.Config.initLoot()` seeds a `Random` from
`System.currentTimeMillis()` and bakes the roll into the loot Vis Amulet's NBT, so the charge changed
every launch. It runs before the load-complete loot snapshot, so the existing table-restore fix
preserved it rather than catching it.

Structure chest contents are also route-independent as of the position-derived fix: on seed `-777` at
radius 8, 124 chests are identical across `rows`, `cols` and `spiral` and across two separate JVMs —
existence, contents and NBT all zero. That fix demonstrably acts rather than merely agreeing with
itself: against a control jar with only those three mixins removed, 27 of the 124 chests carry
different items, no chest appears or disappears, and the set of chunks whose **blocks** differ is
identical to the two-run noise floor, so nothing but chest contents moved. Evidence:
[results/2026-08-29-position-derived-chests](results/2026-08-29-position-derived-chests/README.md)
and [results/2026-08-29-post-only-loot](results/2026-08-29-post-only-loot/README.md).

That fix derives a chest's contents from the structure piece it sits in rather than from the chest's
absolute position, which is what keeps contents stable when a pack update shifts terrain under a
village. Instrumenting a real run showed the piece was being identified for only 10 of 71 chests on
one seed: most village pieces in this pack — VillageNames' biome structures, TinkerConstruct,
Railcraft, Witchery — fill their chests without calling the vanilla method the hook watched, and were
falling back to absolute position. Deterministic, but not terrain-stable. Wrapping
`StructureStart.generateStructure`, the single call site that builds every structure piece, raises
that to 24 of 71; the rest are `WorldGenDungeons` rooms, which genuinely have no piece. Measured, and
including the correction to a first version of the fix that did not actually work:
[results/2026-08-29-chest-site-coverage](results/2026-08-29-chest-site-coverage/README.md).

Naming the piece exposed a second defect. A village piece that writes into an unpopulated chunk
cascades into that chunk's population *inside* its own call, so vanilla dungeon chests generated
there were inheriting the village piece as their component and deriving their loot from its bounding
box — deterministic, so no route or launch test could catch it, but wrong enough that a dungeon's
contents would move if the village moved. A barrier around `ChunkProviderServer.populate` scopes the
piece correctly. The tempting cheaper rule, "only trust a piece if the chest is inside its bounding
box", is false: 11 of 17 out-of-box chests are legitimate, because several pieces really do place
chests outside their own box.

**Entities** are measured from format 5 onward and are not covered by the block table above, since
entities are not blocks. With the jar installed, villager count, position and profession are stable
across a launch pair (`Villager 40/40`), as are the counts of every animal spawned through the vanilla
worldgen spawner. What remains:

| source | note |
|---|---|
| villager trade offers | Nondeterministic by construction and not fixable by measurement: `EntityVillager` rolls its list off the clock-seeded `Entity.rand`, then selects with the no-`Random` `Collections.shuffle` overload backed by a process-global static. Offers are also rolled lazily on first interaction, so no generated world contains them yet |
| `generic.followRange` on every mob | A `nextGaussian() * 0.05` "Random spawn bonus" from `EntityLiving.onSpawnWithEgg` — ±5% of a 16-block tracking radius. ~99% of all remaining entity NBT differences, and inert |
| mod-mob variant fields | `RabbitType`, Witchery goblin `Profession`, fox `Equipment` — 45 entities on the measured seed |
| Witchery wolf/goblin conversion | A compensating `Wolf −4` / `goblin +4` on a launch pair, suggesting an event handler converting wolves off `world.rand` |
| co-located spawns | Two mobs on the same block share a position-derived roll where stock rolls independently — 5 of 95 horses on the measured seed |

Two rules this project has had to learn the hard way: **"only NBT differs"
never closes an investigation** — NBT is where chest contents, spawner types and charge levels live —
and **the category labels above are for prioritising work, never for declaring something benign.**

**Adopting the jar re-rolls seeds once per jar version.** The fixes change how randomness is
derived, so a seed produces a different — now canonical — world than stock (and than earlier jar
versions). Existing saves are safe: only newly generated chunks are affected.

## Seed search: what the stage-0 prefilter answers

Determinism is what makes seed search cheap, so the two are worth stating together. Without generating
a chunk, the prefilter computes chest contents exactly for:

| source | how | verified |
| --- | --- | --- |
| Roguelike dungeon chests | the mod's own generator, run against virgin terrain | 108/108, with NBT |
| Village piece chests | measured piece-to-site table, position-derived fork | 95/95, XZ exact, Y not predicted |
| Stronghold chests | ring positions are pure arithmetic; piece layouts enumerated | 26/26, with NBT |
| Witchery village chests | roll count redrawn from the chest's own position | 10/10, with NBT |
| Witchery circles and other standalone structures | the real handler, run against a scratch overlay | 5/5, with NBT |

It also answers village layouts, spawn point, terrain and Witchery structure cells. It does **not**
cover vanilla `WorldGenDungeons` rooms, mineshafts or temples.

Two limits belong with the capability:

- Witchery structures generate **after** chunk decoration, and the prefilter has only virgin terrain.
  Where decoration raised the sampled column, the predicted Y is one block low. Contents survive that;
  the reported position does not.
- `ComponentVillageBookShop` writes its chest slots directly instead of calling
  `generateStructureChestContents`, so no hook sees it and neither jar can predict it.

Note that **deterministic** and **stage-0 computable** are different questions, and the table in
[seedsearch/README.md](seedsearch/README.md) separates them. Vanilla dungeon *existence* is the one
entry in that table still held as non-deterministic. The GregTech ore-vein identity fix has since landed (see the fix table above); per-block ore placement remains.

**Reporting a worldgen bug?** Please include the jar version, seed, and coordinates.

## Repo layout

- `fix-build/` — source of the fix jar (mod id `gtnhdeterminism`)
- `qol-build/` — **GTNH Speedrun QoL** (mod id `gtnhspeedrunqol`), a separate client-side jar that fixes
  interface friction only. It never changes what the game simulates, so install it with or without the fix jar.
- `probe-build/` — **WorldgenProbe**, the headless determinism tester (inert without `-Dprobe.*` flags)
- `scripts/` — verification + evidence tooling (see below)
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
- `scripts/seed-search.sh` + `scripts/searchlib.py` — seed search over biomes/water/clay/chests/ores
  (search reports are format 4: per-height block histograms, sand/gravel, terrain heightmap with
  burial depths, hardened clay, eldritch sites — corpora live in the
  [gtnh-seedlib](https://github.com/OrderedSet86/gtnh-seedlib) repo with a Streamlit browser)
- `scripts/effects-ab.sh` + `scripts/effects-report.py` + `scripts/balance-report.py` — stock-vs-fixed
  effects and balance-equivalence evidence

All probe scripts need `PROBE_JAVA` pointed at a Java 17-21 runtime (the pack's supported range).
