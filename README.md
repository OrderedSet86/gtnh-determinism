# gtnh-determinism

Same-seed world generation for GT: New Horizons 2.7.4 (1.7.10), made reproducible — so speedruns can
be routed, seeds can be shared, and set-seed categories can be verified.

Companion to the audit report (docs/gtnh-determinism-audit.html) and the `#run-determinism` Discord archive.

## The fix jar

**Grab `gtnhdeterminism-0.4.jar` from [Releases](../../releases) and drop it into `mods/` of a stock
GTNH 2.7.4 instance. No other changes, no config.**

One jar carries every fix: a reflection fix for Forge/FML plus 23 late mixins, each gated on its
target mod being present (the jar is safe on modified packs — a fix simply deactivates if its mod is
absent).

| Target | What was wrong | What the jar fixes |
|---|---|---|
| Forge/FML | Village building handlers iterate in per-launch HashMap order | Village layouts (smeltery/blacksmith presence) identical per seed |
| Witchery | Clock-seeded `world.rand`; structure *type* picked by shuffling a shared list in place per chunk; wicker-man spawner rolled off `world.rand` | Covens, wicker men, shacks, goblin huts — position, type, and spawner seed-stable |
| Thaumcraft | Terrain-gated draw skew, `world.rand` barrow loot, first-chunk-wins bonus nodes, maze gen on a racing thread | Nodes, totems, barrows + loot seed-stable |
| Thaumcraft (eldritch rings) | Obelisk presence was a population-order lottery — earlier rings suppressed up to 25 later candidates per window | One seed-pure site per 25×25-chunk region at stock density; spawner/banners deterministic |
| GregTech | Vein retry probed *live* terrain — population noise flipped vein identity by approach route | The same retry logic answers from regenerated virgin terrain: vein identity/height seed-pure, reroll design preserved |
| Roguelike Dungeons | Position probed live neighbor terrain; placement decisions read live world state; MST-floor decoration iterated an identity-hashed `HashSet`; three rooms placed fireplaces/chests with clock-seeded `Collections.shuffle`; loot pipeline shifted with chest membership | Dungeon position, layout, every floor's decoration, and every chest's contents are a pure function of the seed |
| LootGames | Puzzle-room cracked-wall/broken-lamp variants rolled off a static clock-seeded `Random` | Room cosmetics seed-stable (minigame rewards are gameplay-time and untouched) |
| RWG | Terrain-gated draw skew in all 29 decorators; `Math.random()` big trees | Decoration streams stable |
| TinkersConstruct | Slime islands sized/shaped by a clock-seeded field (`rand`/`random` shadowing bug) | Slime islands seed-stable |
| BiomesO'Plenty | Flora picked with `Math.random()` over an identity-ordered HashMap, shifting the shared decoration stream | Flora + downstream dirt/gravel patches seed-stable |
| ProjectRed | Lily colors (dye yield!) rolled clock-random at worldgen | Lily colors derived from seed+position |
| Forestry | Village bee house rolled bee species/frames/flowers off `world.rand` | Village bees seed-stable |

## Verification

Every claim is tested headless against the actual GTNH 2.7.4 server pack with the WorldgenProbe
harness in this repo, and ground truth is the **persisted world** (region-file blocks + full
tile-entity NBT, including chest contents), not just live hashes:

- **Launch tests** — same seed, two fresh JVMs, identical walk order: persisted worlds are
  **byte-identical** (primary seed: 1,184 chunks, 372,026 tile entities, zero differences —
  every block, every chest slot).
- **Route tests** — same seed generated in different chunk orders (rows / columns / spiral,
  simulating different approach paths): structures, layouts, veins, spawners, and all surviving
  chest contents identical.
- **Balance evidence** — a 60-seed A/B corpus (stock vs fixed, cold runs) shows vein materials,
  small ores, village pieces, and witchery counts statistically equivalent (±10% bounds); a
  500k-draw Monte-Carlo over the shipped loot tables certifies rare chest items. Two disclosed,
  mechanistic deltas: one high-altitude vein type is ~15% more common (stock rerolled it away on
  live terrain), and dungeon-placement semantics shift the chest-loot mix slightly.

Known remaining variance (the fine print):

- Decoration-level detail (individual grass/flowers/trees) can differ with exploration route —
  endemic 1.7.10 behavior, not practically fixable per-mod. The routing layer is order-robust.
- Whether a *deep* dungeon chest exists at all can depend on approach route (later lava-lake
  population can carve it) — surviving chests' contents never change. A fix (deferring dungeon
  construction to tick time) is designed but changes when dungeons appear; pending a community call.
- Witchery village walls build on a delayed timer after generation; shape is still timing-dependent.
- A few tens of blocks of deep underground (y1–4) dirt/gravel patches toggle per launch, one
  contested flower position, and a rare sky-height anomaly — all under investigation, none
  routing-relevant. GT ore tile-entity bookkeeping jitters in diffs without changing blocks.

**Adopting the jar re-rolls seeds once per jar version.** The fixes change how randomness is
derived, so a seed produces a different — now canonical — world than stock (and than earlier jar
versions). Old seed notes reset once, then hold for every runner, every launch. Existing saves are
safe: only newly generated chunks are affected.

**Reporting a worldgen bug?** Include the jar's md5 (`md5sum gtnhdeterminism-*.jar`) along with the
seed and coordinates — it distinguishes stale copies from real regressions in one message.

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
- `scripts/effects-ab.sh` + `scripts/effects-report.py` + `scripts/balance-report.py` — stock-vs-fixed
  effects and balance-equivalence evidence

All probe scripts need `PROBE_JAVA` pointed at a Java 17 runtime (the pack's supported range).

## Releasing

Actions → "Release jars" → enter a version (e.g. `0.4`). CI tags `v<version>`, builds both jars on
the tag, and attaches them to a GitHub release.
