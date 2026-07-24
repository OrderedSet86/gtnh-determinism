# Seed libraries (per GTNH pack version)

Probe `search=true` report corpora, **one folder per pack version** — seed reports do
NOT transfer across pack versions (mod updates change worldgen RNG consumption,
structure templates, and loot tables; verified 2026-07-24 when a 2.7.4 report failed
to match a 2.8.4 world). Each folder's README records the pack version, fix-jar md5,
run mode (cold/warm), and seed provenance.

- `gtnh-2.7.4/` — 60-seed balance corpus, 0.4 jar, cold runs. Canonical for 2.7.4;
  doubles as the fixed arm of docs/balance/balance-report-0.4.md.
- `gtnh-2.8.4/` — regeneration pending (see its README: the first warm corpus was
  withdrawn over the spawn-preload loot-table timing bug, fixed in probe 0.6).

Query with `scripts/searchlib.py` (generic) or `seedsearch/ingot-hunt.py`
(chest-ingot rankings). Extract a tarball to a temp dir first.
