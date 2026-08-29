# Warm seed-search perf session (2026-07-24, GTNH 2.8.4, r15, 16-core/60G box, idle)

JFR warm-phase attribution (server thread): rwg-terrain 38.8%, populate/deco 14.7%,
lighting 11.6% (parity-UNSAFE to skip: light-level checks gate mushroom/flower gen),
probe hash+search 8.1%, block-access overhead ~11% (fastutil chunk map + GT
getBlockDetector mixin on World.getBlock), caves 3.3%.

5-seed A/B (same seeds 201-205, tmpfs output, per-seed full cycle):
  b0 baseline (probe 0.6)                 10.78 s/seed
  b1 + section-array scans                 9.74  (-10%)
  b2 + probe.nohash (search fast path)     8.99  (-17%)
  b3 + -XX:+UseParallelGC                  8.68  (-19%)

Correctness: b0-vs-b1 BLOCK hashes 0 diffs / 4805 chunks (scan rewrite doesn't touch
worldgen). b1-vs-b2 noise == b0-vs-b2 noise: a few water/clay chunks (flowing-water
tick timing), 0-6 ore-histogram chunks (known TE materialization jitter), TiC tool
NBT rolls — ALL pre-existing run-to-run noise, present between any two runs; chest
(id,damage,count) 0 diffs everywhere.

Adopted defaults (seed-search.sh): PROBE_NOHASH=true, ParallelGC, tmpfs staging for
Dropbox-destined out dirs. Next multiplier: probe-farm N=3-4 instances (~2 cores +
10G each) => ~3-4x throughput on top.
