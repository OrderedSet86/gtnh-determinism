# gtnh-determinism

Speedrun mod for GT: New Horizons 2.7.4+. Makes worldgen fully deterministic based on seed.

Discord: https://discord.gg/PbMWTcnZgC

## The fix jar

**Grab `gtnhdeterminism-0.5.jar` (or latest) from [Releases](../../releases) and drop it into `mods/` of a stock
GTNH 2.7.4+ stable instance.**

| Target | What was wrong | What the jar fixes |
|---|---|---|
| Forge/FML | Village building handlers iterate in per-launch HashMap order | Village layouts (smeltery/blacksmith presence) identical per seed |
| Forge/FML (chest loot) | Loot tables are static and get rewritten once the first world starts — only the **first world created per client session** rolled spawn-region loot from pristine tables; every later world rolled different chests from the same seed | Tables reset before every world start: each world rolls like the first of a session, matching dedicated servers and the seed library |
| Witchery | Clock-seeded `world.rand`; structure *type* picked by shuffling a shared list in place per chunk; wicker-man spawner rolled off `world.rand` | Covens, wicker men, shacks, goblin huts — position, type, and spawner seed-stable |
| Witchery (village walls) | Walls were built by a hidden tile entity 40+ ticks after generation, probing whatever terrain existed at that moment — shape depended on route and timing, and idle worlds could skip walls entirely | Walls build during generation from virgin-terrain heights, sliced per chunk: shape, gates, and guard posts seed-stable |
| Thaumcraft | Terrain-gated draw skew, `world.rand` barrow loot, first-chunk-wins bonus nodes, maze gen on a racing thread | Nodes, totems, barrows + loot seed-stable |
| Thaumcraft (eldritch rings) | Obelisk presence was a population-order lottery — earlier rings suppressed up to 25 later candidates per window | One seed-pure site per 25×25-chunk region at stock density; spawner/banners deterministic |
| GregTech | Vein retry probed *live* terrain — population noise flipped vein identity by approach route | The same retry logic answers from regenerated virgin terrain: vein identity/height seed-pure, reroll design preserved |
| Roguelike Dungeons | Position probed live neighbor terrain; placement decisions read live world state; MST-floor decoration iterated an identity-hashed `HashSet`; three rooms placed fireplaces/chests with clock-seeded `Collections.shuffle`; loot pipeline shifted with chest membership; dungeons wrote far outside their trigger chunk, racing each neighbor chunk's own lakes/decoration by approach order (a deep chest could exist or not per route) | Dungeon position, layout, every floor's decoration, and every chest's contents are a pure function of the seed; writes are sliced per chunk and applied after that chunk's own decoration, so the dungeon-vs-lake contest resolves identically on every route |
| LootGames | Puzzle-room cracked-wall/broken-lamp variants rolled off a static clock-seeded `Random` | Room cosmetics seed-stable (minigame rewards are gameplay-time and untouched) |
| RWG | Terrain-gated draw skew in all 29 decorators; `Math.random()` big trees | Decoration streams stable; big trees keep their stock 7–13 size variety, rolled from the seed |
| TinkersConstruct | Slime islands sized/shaped by a clock-seeded field (`rand`/`random` shadowing bug) | Slime islands seed-stable |
| BiomesO'Plenty | Flora picked with `Math.random()` over an identity-ordered HashMap, shifting the shared decoration stream | Flora + downstream dirt/gravel patches seed-stable |
| ProjectRed | Lily colors (dye yield!) rolled clock-random at worldgen | Lily colors derived from seed+position |
| Forestry | Village bee house rolled bee species/frames/flowers off `world.rand` | Village bees seed-stable |

## Verification

Tested headless against the actual GTNH 2.7.4 server pack with the WorldgenProbe
harness in this repo, and ground truth is the **persisted world** (region-file blocks + full
tile-entity NBT, including chest contents):

- **Launch tests** — same seed, two fresh JVMs, identical walk order: persisted worlds are
  **byte-identical** (primary seed: 1,184 chunks, 372,026 tile entities, zero differences —
  every block, every chest slot).
- **Route tests** — same seed generated in different chunk orders (rows / columns / spiral,
  simulating different approach paths): structures, layouts, veins, spawners, and all surviving
  chest contents identical.
- **Balance evidence** — a 60-seed A/B corpus (stock vs fixed, cold runs) shows vein materials,
  small ores, village pieces, and witchery counts statistically equivalent (±10% bounds); a
  500k-draw Monte-Carlo over the shipped loot tables certifies rare chest items.

Known remaining variance (the fine print):

- Decoration-level detail (individual grass/flowers/trees) can differ with exploration route —
  endemic 1.7.10 behavior, not practically fixable per-mod. The routing layer is order-robust.
- Falling gravel/sand and flowing-water edges settle by tick timing (a handful of chunks per
  window differ between any two runs) — cosmetic, and it drags swamp clay counts along with it
  (clay generation replaces sand/gravel, so it inherits their settling noise).
- If two multi-chunk structures write into the same chunk, which one wins there follows generation
  order (rare; each structure is itself seed-stable).
- A few tens of blocks of deep underground (y1–4) dirt/gravel patches toggle per launch, one
  contested flower position, and a rare sky-height anomaly

**Adopting the jar re-rolls seeds once per jar version.** The fixes change how randomness is
derived, so a seed produces a different — now canonical — world than stock (and than earlier jar
versions). Existing saves are safe: only newly generated chunks are affected.

**Reporting a worldgen bug?** Please include the jar version, seed, and coordinates.

## Repo layout

- `tcfix-build/` — source of the fix jar (mod id `gtnhdeterminism`)
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
  (search reports are format 2: per-height block histograms, sand/gravel, terrain heightmap with
  burial depths, hardened clay, eldritch sites — corpora live in the
  [gtnh-seedlib](https://github.com/OrderedSet86/gtnh-seedlib) repo with a Streamlit browser)
- `scripts/effects-ab.sh` + `scripts/effects-report.py` + `scripts/balance-report.py` — stock-vs-fixed
  effects and balance-equivalence evidence

All probe scripts need `PROBE_JAVA` pointed at a Java 17-21 runtime (the pack's supported range).
