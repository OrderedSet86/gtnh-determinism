# Seed library: GTNH 2.8.4, 0.4 jar, 100 random seeds

Per-seed probe search reports (radius-15 spawn window: chest inventories, GT ore
histograms, biomes, water/clay, villages, witchery) for the **2.8.4 server pack**
(latest stable) with the release 0.4 fix jar. Query with scripts/searchlib.py or
seedsearch/ingot-hunt.py.

- pack: GT_New_Horizons_2.8.4_Server_Java_17-25.zip
- fix jar: gtnhdeterminism-0.4pre.jar md5 044d86ca21f8596775be3250d0579add (= release 0.4)
- probe jar: worldgenprobe-5b42e54 (EndlessIDs-conditional build; 2.8.4 uses the NEID raw path)
- seeds: 100 random 64-bit (../gtnh-2.8.4-seeds-100.txt, generated 2026-07-24 via secrets)
- run mode: WARM batches (25 seeds/JVM) — chests/villages/witchery/biomes/water/clay
  are cold-parity-clean; ore host-STONE-variant digits in slot 2+ may carry the known
  cross-seed contamination noise (ore MATERIAL unaffected). Use cold runs to verify
  finalists.
