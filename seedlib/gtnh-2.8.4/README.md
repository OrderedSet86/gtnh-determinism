# Seed library: GTNH 2.8.4 — 700 seeds across three corpora

Probe `search=true` reports (radius 15, nohash, **all loaded chunks**: the 31x31
walk window plus every cascade-generated chunk, ~1100-1200 chunks/seed), generated
2026-07-24. Seed lists live in the gtnh-determinism repo's `seedlib/`; the two
corpora are disjoint — the browser and the query tools can use them together
(700 seeds total).

- `seedlib-0.4-gtnh2.8.4-100seeds-r2.tar.gz` — first batch, 100 seeds
  (`gtnh-2.8.4-seeds-100.txt`)
- `seedlib-0.4-gtnh2.8.4-500seeds.tar.gz` — second batch, 500 seeds
  (`gtnh-2.8.4-seeds-500.txt`); 500/500 clean, spot-check re-run byte-identical
- `seedlib-0.4-gtnh2.8.4-100seeds-b3.tar.gz` — third batch, 100 seeds
  (`gtnh-2.8.4-seeds-100b.txt`); 100/100 clean, spot-check re-run byte-identical

- pack: GT_New_Horizons_2.8.4_Server_Java_17-25.zip
- fix jar: gtnhdeterminism 0.4 (md5 044d86ca21f8596775be3250d0579add)
- probe jar: worldgenprobe v0.4-main.11+6056faa (md5 6bbb4899985277a9a3a24ed8898cc8d6)
- run mode: CRIU pool restores — certified cold-equivalent; every seed verified a
  strict superset of the r1 window-only corpus on stable fields.

**r2 supersedes r1** (same seeds, same worldgen): r1 reported only the fixed 31x31
window; r2 keeps every generated chunk — corpus-wide that recovered 18,494 extra
chunks and 1,512 additional chests. Chunks outside the walk window may carry
`"populated": false` = partial data (see the repo README's Report format section).

Routing notes for 2.8.4 (differ from 2.7.4 — reports do NOT transfer across pack
versions):
- Village chests DO contain GT ingots here (brass is village-only loot); bronze is
  ~10x richer than in 2.7.4.
- In every real 2.8.4 world, spawn-region dungeon chests roll the smaller
  pre-ServerStarting loot table (fewer GT ingots, no stainless/aluminium entries);
  chests generated outside the spawn preload use the full table.
- Run-noise between any two runs: TiC tool NBT, flowing-water counts, ore-TE
  histograms, and clay counts in swamp-type biomes. Compare chests on
  (id, damage, count) only.
- Known fix-jar 0.4 residual: rarely, one deep Roguelike-dungeon chest's EXISTENCE
  is launch-dependent (write race; in this corpus: one chest on seed
  7066592863814697627 at (101, 63, 196)). Treat deep-dungeon single chests as
  probable, not promised.

- `seedlib-0.5pre-gtnh2.8.4-99seeds-fmt2.tar.gz` — fourth batch, **report format 2**
  (per-height histograms, sand/gravel, hardened clay, terrain heightmap/burial depths,
  eldritch sites), 99 seeds (`gtnh-2.8.4-seeds-100c.txt` in gtnh-determinism; seed
  9082145801287029604 omitted — it crashes stock 2.8.4 world creation, an upstream
  GTNH bug fixed in later dailies, so it is unroutable and was dropped).
  Fix jar 0.5pre (md5 caddf9f0e0db564966d5983bede6a14f: session loot drift F7,
  per-chunk structure slicing for Roguelike + Witchery walls, seeded big-tree sizes),
  probe worldgenprobe-v0.4-main.20+43ffbd534b (md5 4f5a43cd5f7d1ff6aeaacdde4182b0aa),
  CRIU pool. NOTE: 0.5 slicing canonicalizes different dungeon layouts and adds
  generation-time village walls vs the 0.4 corpora above — same seed numbers would
  not produce comparable worlds across jar versions.

- `seedlib-0.5pre-gtnh2.8.4-300seeds-coke-funnel.tar.gz` — **FUNNELED corpus, NOT a
  random sample** (md5 078a8eb32266337bc18e60dc2ae2ce36): the top 300 coke%-ranked
  survivors of a 650,000-seed stage-0 prefilter sweep (worldless village piece
  layouts + spawn prediction + terrain digest, gtnh-determinism `scripts/prefilter.sh`,
  sweep spec `random:650000:45`; ranking rules: Photoshop/TiC/blacksmith pieces all
  in ONE village within 100 blocks of spawn, plus deep-sand/water/clay criteria —
  `seedsearch/coke-rank.py`). Use for ROUTING CANDIDATES only; never draw loot or
  worldgen statistics from it — these seeds were selected for exceptional spawns by
  construction, use the random corpora above for statistics. Same jars, run mode and
  format 2 as the fmt2 batch: fix 0.5pre (caddf9f0), probe main.20 (4f5a43cd), CRIU
  pool, radius 15. Seed list `gtnh-2.8.4-seeds-300-coke-funnel.txt` in
  gtnh-determinism (stage-0 rank order); the stage-0 sweep JSONL and combined
  stage-0/1 loot rankings live in gtnh-determinism `results/2026-07-26-*`.
