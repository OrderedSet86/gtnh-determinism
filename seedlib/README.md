# Seed libraries (per GTNH pack version)

Report corpora now live in **[OrderedSet86/gtnh-seedlib](https://github.com/OrderedSet86/gtnh-seedlib)**
(git LFS for the tarballs, so corpus updates don't bloat this repo's history).
This folder keeps only the harness inputs and per-version provenance notes:

- `gtnh-2.8.4-seeds-100.txt` — seed list for the 2.8.4 corpus (batch input).
- `gtnh-2.7.4/README.md`, `gtnh-2.8.4/README.md` — corpus provenance (pack version,
  jar md5s, run mode); the same READMEs ship alongside the tarballs in gtnh-seedlib.

## Where the routemap and world bundles actually live

**`../gtnh-seedlib/` — a sibling checkout of this repo**, i.e.
`~/Dropbox/OrderedSetCode/cloned-gtnh/gtnh-seedlib` next to
`~/Dropbox/OrderedSetCode/cloned-gtnh/gtnh-determinism`. Remote:
`git@orderedset:OrderedSet86/gtnh-seedlib.git`.

| what | where |
|---|---|
| routemap web viewer | `../gtnh-seedlib/routemap/` (`index.html`, `map.js`, `layers.js`, `run.sh`) |
| per-seed world bundle | `../gtnh-seedlib/worlds/<seed>/dim{0,7,-1}/` |
| chest loot layer | `.../dim0/loot.json` |
| POI layer | `.../dim0/pois.json` |
| GT vein layer | `.../dim0/veins.json` |
| base rasters | `.../dim0/base-{blocks,biome,topo}.png`, `climate.png` |
| pack + provenance | `../gtnh-seedlib/worlds/<seed>/meta.json` (carries the `pack` field) |
| layer builder | `../gtnh-seedlib/tools/build_world_bundle.py` |

Two traps that have cost time more than once:

- **`~/.cache/gtnh-seedlib/` is NOT a checkout.** It is a directory of `.pkl` parse caches. Nothing
  authoritative lives there.
- **`gtnh-determinism/seedlib/`** (in THIS repo) holds only harness inputs — seed lists and
  per-pack provenance READMEs. It is not the corpus and not the routemap. Its README links the
  GitHub remote but not the local sibling path, which is the specific wrong turn to avoid.

World bundles are built from a **full-generation radius-60 probe**, not from the stage-0 prefilter, so
they can carry structures stage 0 cannot predict (Thaumcraft hilltop circles and barrows, vanilla
`WorldGenDungeons` rooms).

Seed reports do NOT transfer across pack versions (mod updates change worldgen RNG
consumption, structure templates, and loot tables; verified 2026-07-24 when a 2.7.4
report failed to match a 2.8.4 world).

Query an extracted tarball with `scripts/searchlib.py` (generic),
`seedsearch/ingot-hunt.py` (chest-ingot rankings), or `seedsearch/village-hunt.py`
(village piece filters, e.g. Tinker's Construct houses).
