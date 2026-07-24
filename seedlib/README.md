# Seed libraries (per GTNH pack version)

Report corpora now live in **[OrderedSet86/gtnh-seedlib](https://github.com/OrderedSet86/gtnh-seedlib)**
(git LFS for the tarballs, so corpus updates don't bloat this repo's history).
This folder keeps only the harness inputs and per-version provenance notes:

- `gtnh-2.8.4-seeds-100.txt` — seed list for the 2.8.4 corpus (batch input).
- `gtnh-2.7.4/README.md`, `gtnh-2.8.4/README.md` — corpus provenance (pack version,
  jar md5s, run mode); the same READMEs ship alongside the tarballs in gtnh-seedlib.

Seed reports do NOT transfer across pack versions (mod updates change worldgen RNG
consumption, structure templates, and loot tables; verified 2026-07-24 when a 2.7.4
report failed to match a 2.8.4 world).

Query an extracted tarball with `scripts/searchlib.py` (generic),
`seedsearch/ingot-hunt.py` (chest-ingot rankings), or `seedsearch/village-hunt.py`
(village piece filters, e.g. Tinker's Construct houses).
