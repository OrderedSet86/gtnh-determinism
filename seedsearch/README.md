# Seed search analysis

Tools for mining probe `search=true` reports (seedlib tarballs, results/ batches) for
routing-relevant loot. Complements `scripts/searchlib.py` (generic query lib) with
speedrun-specific rankings.

## What stage 0 can and cannot answer (2026-08-29)

**"Stage 0" is the cheapest tier of the three-tier seed-search funnel** in
`docs/seed-search-speed-plan.md` §2 — layered filters, cheapest first, full simulation only for
finalists:

| tier | what it is | cost | tooling |
| --- | --- | --- | --- |
| **Stage 0** | the **worldless prefilter** — no `WorldServer`, no save directory, calls the pack's real classes in-JVM | ~11 seeds/s with terrain + dungeon modules on survivors; far faster arithmetic-only | `scripts/prefilter.sh`, `Prefilter.java` |
| Stage 1 | cheap full-generation probe, radius 8 | ~4-5 s/seed warm | `warm-probe.sh` |
| Stage 2 | full radius-15 report, finalists only | ~90 s/seed cold | `run-probe.sh` |

Worldless does **not** mean "no generation". Stage 0 builds real `Chunk` objects from the pack's own
generator (`Prefilter.VirginChunkProvider`) and runs Roguelike's real dungeon generator against them.
The older "pure-math prefilter" name is retired, because it is what makes people assume chest loot is
out of reach.

Read this before concluding that a search needs full generation. Older notes in
`docs/harness-speed.md` and `docs/HANDOFF.md` said chest contents require real chunk generation and
that prefiltering "cannot answer the main question". **That is no longer true**, and the corrected
scope is:

**Two different questions, and it is easy to conflate them.** *Deterministic* means the fix jar makes it
a function of the seed. *Stage-0 computable* means the prefilter can tell you the answer without
generating a chunk. A thing can be deterministic and still unpredictable from a layout.

| source | deterministic? | stage 0 | evidence / why |
| --- | --- | --- | --- |
| **Roguelike dungeon chests** | yes | **exact** — position, items, damage, count, slot, NBT | 108/108 on seed -777 vs a full-gen radius-15 run, 0 predicted-but-absent |
| **Village piece chests** | yes | **contents exact, XZ exact, Y not predicted** | 95/95 across 7 seeds, contents and NBT |
| Village layout / pieces / villagers | yes | exact | 79/79 piece-exact over 99 corpus seeds |
| Spawn point, terrain digest, biomes | yes | exact | 99/99 spawns exact |
| Witchery structure siting | yes | cell, biome verdict, handler try-order | 8/8 biomes, 6/6 orders, all 6 real placements covered |
| **Witchery village-piece chests** | yes | **exact** | 10/10 with NBT; roll count redrawn from position (2026-08-30) |
| **Witchery circles and other standalone structures** | yes | **exact contents, Y may be one low** | 5/5 with NBT; 26/26 cells resolved to a winner (2026-08-30) |
| **Vanilla `WorldGenDungeons` contents** | **yes — F10** | **no** | fork is on absolute XZ, which stage 0 could supply, but stage 0 does not enumerate dungeon-room positions |
| **Vanilla `WorldGenDungeons` existence** | **no — held** | no | `WorldGenLakes.generate` returns false on a world read *before* consuming a draw, so one block flipped by a neighbour chunk's GT ore gen shifts all 8 dungeon attempt positions. Measured: a whole Skeleton dungeon exists under `spiral` and not under `rows`, `cols` or two repeat cold `rows` runs. Held until the GT ore live-terrain read is fixed (HANDOFF priority #1) |
| **Strongholds** | yes | **exact** | 26/26 with NBT; all 3 rings enumerated, 6.28 ms/seed (2026-08-30) |
| Mineshafts, temples | yes | no | chests are component-relative and so computable in principle, but stage 0 does not enumerate those structure starts |
| GT veins | yes | no | see harness-speed.md C.4 |

**Witchery contents came in on 2026-08-30**, by two different routes, and each keeps a limit:

- **Coven circles and other standalone structures** are placed by a handler whose winner was thought to
  need the placing call and therefore world writes. It does need the writes — but not a random stream:
  `GameRegistry.generateWorld` reseeds before each generator, so Witchery's Random is a pure function of
  (seed, cx, cz). `SeedProbeWorld` gained a scoped scratch overlay that swallows the writes, and the real
  handler now runs against it under `-Dprobe.prefilter.witchery.replay=true`.
  **Limit:** Witchery is an FML `IWorldGenerator` and so runs AFTER chunk decoration, while stage 0 has
  only virgin terrain. Where decoration raised the sampled column the predicted Y is one block low.
  Contents survive that — F10's absolute fork no longer uses Y — but the reported position does not, and
  a larger disagreement could in principle flip the handler's water checks and so the winner.
- **Witchery's village pieces** (`Apothecary`, `Keep`, `WatchTower`, `ComponentShack`) pass their own
  compile-time item array and their own roll count, so there is no loot table to look up. The count came
  off the chunk-populate Random — position-seeded, and therefore deterministic, but only reachable by
  replaying the populate prologue. F10 now redraws it from the chest's own position over the range the
  mod uses, recorded in `shared/chest-nohooks.json` and loaded by both jars.
  **Limit:** `ComponentVillageBookShop` writes its chest slots directly through
  `TileEntityChest.setInventorySlotContents` and never calls `generateStructureChestContents`, so no hook
  sees it and neither jar can predict it.

Stage 0 is **not** "village-focused". Roguelike dungeon loot is the part it does most completely,
because it runs the mod's real generator against virgin terrain rather than predicting anything —
`-Dprobe.prefilter.dungeon=N`, about 193 ms per dungeon.

Also refused rather than guessed, and reported at the end of every run: chests whose roll count is not
drawn from the loot table and whose piece is not in `shared/chest-nohooks.json`, piece classes absent
from the measured site table, and loot categories whose live table reads 0..0 in the prefilter process.
"Predicted nothing" and "there is nothing" are never conflated.

Refused chests now appear in a per-seed `chests_unpredicted` field carrying position, piece and reason,
never in `chests`. Position and contents fail independently, so a chest whose location is known and
whose contents are not is reported as exactly that. Those entries carry no `category`: for that grade
of site the recorded category came from a loot-table leak that has since been fixed
(`results/2026-08-30-chest-table-leak`) and is not real.

See `results/2026-08-29-roguelike-prefilter`, `results/2026-08-29-village-chest-prefilter`,
`results/2026-08-29-witchery-prefilter`, `results/2026-08-30-stronghold-witchery-chests`,
`results/2026-08-30-witchery-positional-chests` and `results/2026-08-30-witchery-circles`.

## ingot-hunt.py

Ranks seeds / single chests / chest clusters by ingot content
(default items: steel, stainless steel, aluminium, bronze — resolved via the report
dir's `gtmats.json`, GT ingot damage = 11000 + material id).

```sh
# extract a seedlib first
mkdir /tmp/lib && tar xzf ../seedlib/seedlib-0.4-60seeds.tar.gz -C /tmp/lib

./ingot-hunt.py totals   /tmp/lib                        # per-seed window totals
./ingot-hunt.py chests   /tmp/lib --rank steel           # richest single chests
./ingot-hunt.py clusters /tmp/lib --radius 100           # best cluster per seed
./ingot-hunt.py villages /tmp/lib --ruins-only           # no-dungeon route: ruins
                                                         # clusters + village distance
```

Filters: `--y-min 50` ≈ surface only; `--ruins-only` classifies chests by the ruins
loot profile (iridium shards / binding agent markers) instead of altitude — catches
sunken ruins, excludes Roguelike. `--rank a,b` sorts by a subset while still
displaying all `--items`.

## Corpus facts (GTNH 2.8.4)

- Village chests DO contain GT ingots on 2.8.4 (steel, bronze, brass — brass is
  village-only in practice); bronze in chests is ~10× richer than 2.7.4.
- Spawn-window quirk (all real 2.8.4 worlds, and **removed by fix jar F9**): dungeon
  chests inside the spawn preload roll the pre-`FMLServerStarting` loot table
  (TooMuchLoot's rewrite hasn't run yet — 119 vs 139 dungeonChest entries, no
  stainless/aluminium ingot entries); chests generated later use the full table.
  Probe reports (probe >= 0.6) reproduce this faithfully. With F9 installed there is
  one table for the whole world and this quirk is gone; corpora recorded before it
  still show it.

## ⚠ Both sections below predate fix jar F9 and F10 (2026-08-29)

**F9 removed the pre/post loot-table split.** TooMuchLoot is now applied before the first chunk
exists, so no chest anywhere rolls the pre-rewrite table. HungerOverhaul's food injection only ever
reached chests through that table, which means **the 1-32 marshmallow stacks no longer occur** — the
post table gives weight 2, stack 1-2. Everything below that rests on the big spawn-window stacks,
including `coke-stage1.py`'s `marsh_n`/`marsh_d` scoring, must be re-measured. See
`results/2026-08-29-post-only-loot/`.

**F10 re-rolled every structure chest.** Village, mineshaft, stronghold, pyramid, vanilla-dungeon and
Witchery chest contents are now derived from the structure piece and the chest's position within it
rather than from the shared populate stream. Item pool, weights and roll range are unchanged, so
per-chest statistics hold, but **any corpus fact about a specific chest on a specific seed is void**.
Roguelike chests are unaffected. See `results/2026-08-29-position-derived-chests/`.

One consequence is a gain: village chest contents are now a pure function of the piece layout, which
the stage-0 prefilter already computes exactly and without terrain. A contents gate can therefore
replace the piece-presence proxies (`--require paper,tic,furnace`, and the "22/25 Photoshop chests
carry >=4 paper" conditional in `coke-rank.py`).

## Marshmallow / "sunken ruins" attribution (GTNH 2.8.4, investigated 2026-07-25)

Dezil's Marshmallow (`DraconicEvolution:dezilsMarshmallow`, coke% fuel criterion) enters
worldgen chests through two registrations into the same four ChestGenHooks categories
(`dungeonChest`, `mineshaftCorridor`, `pyramidDesertyChest`, `pyramidJungleChest`):

- **TooMuchLoot XML** (post-TML table): weight 2, count 1–2 in dungeonChest.
- **HungerOverhaul runtime food injection** (pre-TML table): weight 5, count 1–32 —
  the source of the 20–30-marshmallow stacks in spawn-window chests.

The structures that roll those categories in the RWG overworld:

- **Vanilla `WorldGenDungeons` cave spawner rooms = the "sunken ruins"** (124/128
  marshmallow containers in a 199-seed corpus; RWG populate makes 8 attempts/chunk at
  y=rand(128), so rooms reach mountain surfaces). Placement depends on populate-rand
  draw offsets AND cave-carved air pockets → **not worldless-predictable; evaluate at
  stage 1** (small-window probe).
- **Witchery coven dispensers** (4/128; fill from `mineshaftCorridor`): candidate
  chunks are vanilla scattered-feature region math (region 20 chunks, salt 10387312,
  `nextInt(12)`) → **worldless-predictable**. The structure-type shuffle is worldless
  too, as of F2 — `WitcheryWorldGeneratorMixin` shuffles a sorted COPY with FML's
  per-chunk `Random`, so the winner is a pure function of (seed, chunk) rather than of
  how many chunks generated first. The biome gate is worldless as well (the chunk
  manager answers it), and since 2026-08-29 the ground checks are evaluable too, because
  `Prefilter.VirginChunkProvider` serves `world.getBlock`. The stage-0 module now exists —
  `-Dprobe.prefilter.witchery=N` emits candidate cells, biome verdict and handler try-order, golden
  8/8 on biomes and 6/6 on order with every real placement covered
  (`results/2026-08-29-witchery-prefilter`). Contents are covered by F10.
- NOT the sources: BartWorks Ross ruins (Ross128b only), RWG `DecoRuinsAncient`
  (cosmetic, no chests), Roguelike (own loot; can pull single marshmallows via
  ItemJunk), desert/jungle temples (RWG registers no MapGenScatteredFeature).

Only ~9% of marshmallow containers sit at y≥64; the practical coke% predicate is
"dungeon-dense near-spawn terrain" (stage 1), not a stage-0 formula.

## prefilter-judge.py

Golden-tests stage-0 prefilter JSONL (`scripts/prefilter.sh`) against a corpus dir of
full-gen search reports for the same seeds: village recall, piece-level layout match
(name + XZ box; Y is a pre-terrain placeholder in the prefilter), spawn XZ exactness.

```sh
scripts/prefilter.sh @seeds.txt out.jsonl
seedsearch/prefilter-judge.py out.jsonl results/2026-07-24-seedlib-2.8.4-fmt2-100c
```

## Corpus facts (seedlib-0.4-60seeds, GTNH 2.7.4, radius-15 spawn window)

- ~~Village chests contain **zero** GT ingots (241 chests / 60 seeds checked) — chest
  steel/bronze come only from Roguelike dungeons (underground) and surface ruins.~~
  **RETRACTED 2026-08-29 — false against its own corpus.** Re-checked with
  `ingot-hunt.py`'s own `PIECE_RE` over the same `seedlib-0.4-60seeds` tarball:
  244 chests inside a village piece box, carrying **12 GT ingot stacks**, not zero —
  Tin, Brass, Zinc, Manganese, Nickel, Silver, Molybdenum, Magnesium, WroughtIron.
  Only the narrow reading survives: no **Steel** and no **Bronze** in 2.7.4 village
  chests, which is what the second clause actually says. The headline generalised it to
  "zero GT ingots" and that was never true.
  Two further things worth knowing before routing on it: only **26 of the 60** reports in
  that corpus have a parsable village piece list at all, so the sample is a quarter of what
  the seed count suggests; and even the Steel claim is version-bound — measured on
  daily-707 with the current jar, **2 of 3 `villageBlacksmith` chests carry Steel ingots**
  (stacks of 4 and 10), from the 95-chest village set that the stage-0 module predicts
  exactly.
- Stainless & aluminium ingots are rare: max 2 of each per seed window; steel median
  26/seed; steel-per-chest ~uniform 1–6 with a 7–11 double-draw tail (record: 11).
- Reports only cover ±15 chunks around spawn; a seed can have better loot outside
  the window.
