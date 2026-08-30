# Seed search analysis

Tools for mining probe `search=true` reports (seedlib tarballs, results/ batches) for
routing-relevant loot. Complements `scripts/searchlib.py` (generic query lib) with
speedrun-specific rankings.

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

- Village chests contain **zero** GT ingots (241 chests / 60 seeds checked) — chest
  steel/bronze come only from Roguelike dungeons (underground) and surface ruins.
- Stainless & aluminium ingots are rare: max 2 of each per seed window; steel median
  26/seed; steel-per-chest ~uniform 1–6 with a 7–11 double-draw tail (record: 11).
- Reports only cover ±15 chunks around spawn; a seed can have better loot outside
  the window.
