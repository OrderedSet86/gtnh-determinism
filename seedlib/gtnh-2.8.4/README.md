# Seed library: GTNH 2.8.4 — 100-seed corpus

Tarball `seedlib-0.4-gtnh2.8.4-100seeds.tar.gz` lives in
[OrderedSet86/gtnh-seedlib](https://github.com/OrderedSet86/gtnh-seedlib) (git LFS) —
probe `search=true` reports (radius 15, nohash) for the 100 random seeds in
`../gtnh-2.8.4-seeds-100.txt`, plus `gtmats.json`. Generated 2026-07-24.

- pack: GT_New_Horizons_2.8.4_Server_Java_17-25.zip
- fix jar: gtnhdeterminism 0.4 (md5 044d86ca21f8596775be3250d0579add)
- probe jar: worldgenprobe v0.4-main.8+8ea6292 (md5 f714944a8d92cd187a5eaa52d5d583b1)
- run mode: CRIU pool restores (`scripts/criu-pool.sh`) — certified cold-equivalent
  (image certification: 4 ref seeds byte-identical vs true cold; this batch
  spot-checked by re-running seed -9090024975407965874, byte-identical).

An earlier warm-mode corpus was **withdrawn** (spawn-preload chests rolled
post-TooMuchLoot loot tables; probe 0.6 fixed warm mode). This corpus was
regenerated on the CRIU pool, which never had that bug.

Routing notes for 2.8.4 (differ from 2.7.4 — reports do NOT transfer across pack
versions):
- Village chests DO contain GT ingots here (brass is village-only loot); bronze is
  ~10x richer than in 2.7.4.
- In every real 2.8.4 world, spawn-region dungeon chests roll the smaller
  pre-ServerStarting loot table (fewer GT ingots, no stainless/aluminium entries);
  chests generated outside the spawn preload use the full table.
- Run-noise between any two runs: TiC tool NBT, flowing-water counts, ore-TE
  histograms. Compare chests on (id, damage, count) only.
