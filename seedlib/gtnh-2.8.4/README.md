# Seed library: GTNH 2.8.4 — regeneration pending

The first 100-seed corpus (warm batches, probe 5b42e54) was **withdrawn**: warm slots
generated their spawn preload with post-`FMLServerStarting` loot tables, while real
worlds (and cold boots) fill spawn-region chests BEFORE TooMuchLoot rewrites the
tables at ServerStarting (`dungeonChest` 119→139 entries). Every spawn-window
structure chest rolled deterministic-but-wrong loot (~17 chests/seed).

Probe `worldgenprobe-0.6` fixes warm mode: it snapshots ChestGenHooks at
FMLLoadComplete (pre-server) and post-boot, restoring the pre-server tables around
each slot's recreate/preload and the post-boot tables for the walk. **Certified
warm == true-cold byte-identical** on 4 seeds / 189 chests (seed 4403169046063099793
+ 3 others, content and existence).

Regeneration of the 100-seed corpus (seeds: ../gtnh-2.8.4-seeds-100.txt) with the
fixed probe is scheduled; until then no tarball is published here.

- pack: GT_New_Horizons_2.8.4_Server_Java_17-25.zip
- fix jar: gtnhdeterminism 0.4 (md5 044d86ca21f8596775be3250d0579add)
- NOTE for routing: in every real 2.8.4 world, spawn-region dungeon chests roll the
  smaller pre-ServerStarting loot table (fewer GT ingots, no stainless/aluminium
  entries); chests generated outside the spawn preload use the full table.
