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
- Spawn-window quirk (all real 2.8.4 worlds): dungeon chests inside the spawn
  preload roll the pre-`FMLServerStarting` loot table (TooMuchLoot's rewrite hasn't
  run yet — 119 vs 139 dungeonChest entries, no stainless/aluminium ingot entries);
  chests generated later use the full table. Probe reports (probe >= 0.6) reproduce
  this faithfully.

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
  `nextInt(12)`) → **worldless-predictable**, but the structure-type shuffle, biome
  gate and ground checks are live-world; rare enough that stage 1 covers it too.
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
