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

## Corpus facts (seedlib-0.4-60seeds, GTNH 2.7.4, radius-15 spawn window)

- Village chests contain **zero** GT ingots (241 chests / 60 seeds checked) — chest
  steel/bronze come only from Roguelike dungeons (underground) and surface ruins.
- Stainless & aluminium ingots are rare: max 2 of each per seed window; steel median
  26/seed; steel-per-chest ~uniform 1–6 with a 7–11 double-draw tail (record: 11).
- Reports only cover ±15 chunks around spawn; a seed can have better loot outside
  the window.
