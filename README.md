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
| Forge/FML (chest loot) | Loot tables are static and get rewritten once the first world starts — only the **first world created per client session** rolled spawn-region loot from pristine tables; every later world rolled different chests from the same seed | Tables reset before every world start: each world rolls like the first of a session, matching dedicated servers and the seed library |
| Witchery | Clock-seeded `world.rand`; structure *type* picked by shuffling a shared list in place per chunk; wicker-man spawner rolled off `world.rand` | Covens, wicker men, shacks, goblin huts — position, type, and spawner seed-stable |
| Witchery (village walls) | Walls were built by a hidden tile entity 40+ ticks after generation, probing whatever terrain existed at that moment — shape depended on route and timing, and idle worlds could skip walls entirely | Walls build during generation from virgin-terrain heights, sliced per chunk: shape, gates, and guard posts seed-stable |
| Thaumcraft | Terrain-gated draw skew, `world.rand` barrow loot, first-chunk-wins bonus nodes, maze gen on a racing thread | Nodes, totems, barrows + loot seed-stable |
| Thaumcraft (eldritch rings) | Obelisk presence was a population-order lottery — earlier rings suppressed up to 25 later candidates per window | One seed-pure site per 25×25-chunk region at stock density; spawner/banners deterministic |
| Thaumcraft (loot amulet) | `Config.initLoot()` seeds a `Random` from the wall clock at mod init and bakes the roll into the loot Vis Amulet's NBT, then copies that one stack into the chest and loot-bag tables — so every launch dealt a different charge, and every amulet within a session dealt the *same* one. It runs *before* the load-complete snapshot above, so that fix preserved the randomness rather than catching it | Charge derived from world seed + chest position + slot, so it is seed-stable and route-stable while differing from chest to chest. Per-aspect distribution is stock's `nextInt(5)`; only the correlation between amulets changes, from identical to independent. The init-time roll is pinned too, so an amulet pulled from a Thaumcraft loot bag carries a fixed charge rather than a per-launch one — but **which** item a bag gives you is rolled from `world.rand` when the player opens it, and stays gameplay-time random |
| GregTech | Vein retry probed *live* terrain — population noise flipped vein identity by approach route | The same retry logic answers from regenerated virgin terrain: vein identity/height seed-pure, reroll design preserved. Two mixins, one per GT line — 5.09.54.x moved the probe from `Block.isReplaceableOreGen` to `StoneType.findStoneType`, and the jar picks by which is installed |
| Et Futurum Requiem | `doDeepslateGen` gated the 4-block deepslate transition band with `chunk.worldObj.rand.nextInt(4)` per block — clock-seeded `World.rand`, so the y16-31 band was redrawn every launch. Cave-vine tile entities jittered their length the same way | Band derived from world seed + position; vine length seed-stable. Largest single source on the 2.9/daily line: 192,495 route-differing blocks before, 4,847 after |
| Roguelike Dungeons | Position probed live neighbor terrain; placement decisions read live world state; MST-floor decoration iterated an identity-hashed `HashSet`; three rooms placed fireplaces/chests with clock-seeded `Collections.shuffle`; loot pipeline shifted with chest membership; dungeons wrote far outside their trigger chunk, racing each neighbor chunk's own lakes/decoration by approach order (a deep chest could exist or not per route) | Dungeon position, layout, every floor's decoration, and every chest's contents are a pure function of the seed; writes are sliced per chunk and applied after that chunk's own decoration, so the dungeon-vs-lake contest resolves identically on every route |
| LootGames | Puzzle-room cracked-wall/broken-lamp variants rolled off a static clock-seeded `Random` | Room cosmetics seed-stable (minigame rewards are gameplay-time and untouched) |
| RWG | Terrain-gated draw skew in all 29 decorators; `Math.random()` big trees | Decoration streams stable; big trees keep their stock 7–13 size variety, rolled from the seed |
| TinkersConstruct | Slime islands sized/shaped by a clock-seeded field (`rand`/`random` shadowing bug) | Slime islands seed-stable |
| BiomesO'Plenty | Flora picked with `Math.random()` over an identity-ordered HashMap, shifting the shared decoration stream | Flora + downstream dirt/gravel patches seed-stable |
| ProjectRed | Lily colors (dye yield!) rolled clock-random at worldgen | Lily colors derived from seed+position |
| Forestry | Village bee house rolled bee species/frames/flowers off `world.rand` | Village bees seed-stable |

Running tally: **32 fixes** — 30 mixin-based plus 2 reflection patches — carried by **32 mixin
classes** across **11 mods and Forge/FML**, rewiring **60 worldgen classes**. Two fixes take two
mixins each: the GregTech pair are alternatives, exactly one binding per GT version, and the Vis
Amulet needs both an init-time pin and a per-chest derivation. Three further diagnostic mixins ship
inert behind `-Dgtnhdet.traceseg` and are not counted here.

## Verification

Tested headless against actual GTNH server packs with the WorldgenProbe harness in this repo, and
ground truth is the **persisted world** (region-file blocks + full tile-entity NBT, including chest
contents):

- **Launch tests** — same seed, two fresh JVMs, identical walk order: persisted worlds are
  **byte-identical** (primary seed: 1,184 chunks, 372,026 tile entities, zero differences —
  every block, every chest slot).
- **Route tests** — same seed generated in different chunk orders (rows / columns / spiral,
  simulating different approach paths): structures, layouts, veins, spawners, and all surviving
  chest contents identical.
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

| source | blocks | note |
|---|---|---|
| decoration (grass/flowers/trees/hives) | 39,425 | endemic 1.7.10 decorator ordering; no per-mod fix known |
| deep dirt/gravel/stone patches | 8,495 | GT ore placement is downstream of this — fixing it pays twice |
| GT stone-layer blobs (granite/stone) | 7,451 | the one category the jar does not move at all (−3% vs stock) |
| sand/gravel/clay/fluid settling | 6,389 | tick-timing; clay inherits it, since clay replaces sand/gravel |
| EtFuturum deepslate band | 4,847 | was 192,495 before the deepslate fix |
| GT / mod ore placement | 3,741 | vein *identity* is 99.0% stable; this is per-block placement |

Chest loot is fully launch-deterministic, measured two ways: 131/131 chests identical across two cold
launches of one seed with **zero tile-entity differences of any kind**, and 548 chests across
**10 seeds** identical between two separate JVMs — existence, item lists and NBT all counted
separately. That needed one more fix: `Thaumcraft.Config.initLoot()` seeds a `Random` from
`System.currentTimeMillis()` and bakes the roll into the loot Vis Amulet's NBT, so the charge changed
every launch. It runs before the load-complete loot snapshot, so the existing table-restore fix
preserved it rather than catching it.

Two rules this project has had to learn the hard way, both in `docs/HANDOFF.md`: **"only NBT differs"
never closes an investigation** — NBT is where chest contents, spawner types and charge levels live —
and **the category labels above are for prioritising work, never for declaring something benign.**

**Adopting the jar re-rolls seeds once per jar version.** The fixes change how randomness is
derived, so a seed produces a different — now canonical — world than stock (and than earlier jar
versions). Existing saves are safe: only newly generated chunks are affected.

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
