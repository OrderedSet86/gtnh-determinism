# 2.8.4 launch pair with 0.14.0: one aura node and 5 GregTech ore blocks vary per launch

2026-10-11. Not root-caused. Written down for later investigations on newer packs.

## Setup

Jar: 0.14.0 as merged (`gtnhdeterminism-v0.13.0-main.1+c9d024e217`, md5 `31282c3b`), probe from the same
tree. Seed `-1636594104014467454`, radius 24 around spawn chunk (9, 9), rows order, two cold launches per
pack, `PROBE_SEARCH=true`. Packs: `~/.cache/gtnh-determinism/pack-2.7.4` and `verify-284` (2.8.4), with
their own jars restored afterwards.

| Pair | Chunks differing (of 2,401) | Veins (of 331) | Chests |
|---|---|---|---|
| 2.7.4 | 0 | 0 | 216 of 216 identical |
| 2.8.4, first pair | 2, tile entities only | 0 | 231 of 231 identical |
| 2.8.4, `PROBE_TEDETAIL=true` | 5 (4 tile-entity, 1 block) | not compared | not compared |
| 2.8.4, `-XX:hashCode=3` | 3, tile entities only | not compared | not compared |

`-XX:hashCode=3` pins `System.identityHashCode` order, which pins the order of Forge world generators that
share a weight (see results/2026-09-07-populate-stream-census). On JDK 21 it needs
`-XX:+UnlockExperimentalVMOptions`.

## Difference 1: a Thaumcraft aura node's aspects

`TileExtendedNode` at (62, 8, −51), chunk (3, −4). Same type (1) and modifier (−1) in both launches.

```
A: Aspects:[{amount:27,key:"aer"},{amount:32,key:"ignis"}]
B: Aspects:[{amount:22,key:"aer"},{amount:27,key:"ignis"},{amount:10,key:"terra"}]
```

`ThaumcraftWorldGenerator.createRandomNodeAt` (Thaumcraft 4.2.3.5a, bytecode) reads `world.getBlock` over
x, y and z −5 to +5 around the node. It counts water (`Material.water`), lava (`Material.lava`), plain
stone (`Blocks.stone`) and foliage, and turns each count above a threshold (100) into an aspect. The box
spans chunks (3, −4), (4, −4), (3, −3) and (4, −3). In launch B's finished world it has 769 deepslate,
214 air, 114 nether brick, 71 gravel, 49 lava, 36 obsidian, 14 quartz ore and 12 soul sand, which looks
like the nether-themed bottom floor of a Roguelike dungeon. It has no plain stone, so terra 10 means
Thaumcraft counted stone before Et Futurum's deepslate pass replaced it.

The difference appears in both unpinned pairs and not in the pinned pair, which points at the per-launch
generator order. It is not the direct Thaumcraft vs deepslate order in one chunk:
`ThaumcraftWorldGenerator` has weight 0 and `EtFuturumLateWorldGenerator` has weight `Integer.MAX_VALUE`,
so in one chunk Thaumcraft always runs first. The race goes through a neighbouring chunk or through
another generator. Which pair races is open.

## Difference 2: GregTech ore at 5 fixed positions

At each position, either a `gregtech.common.blocks.TileEntityOres` is there (the same NBT hash every time
it appears), or another block is:

| Position | Chunk | Other outcome | First 2.8.4 pair | Pinned pair |
|---|---|---|---|---|
| (47, 15, −64) | (2, −4) | `etfuturum:deepslate` | ore in A | ore in A |
| (113, 18, −72) | (7, −5) | `etfuturum:tuff` | ore in A | ore in A |
| (118, 5, −79) | (7, −5) | `etfuturum:deepslate` | ore in A | ore in A |
| (267, 23, −78) | (16, −5) | `minecraft:redstone_ore` | ore in A | ore in B |
| (269, 22, −78) | (16, −5) | `minecraft:redstone_ore` | ore in A | ore in B |

The flip survives `-XX:hashCode=3`, so it is not the generator order. The three chunks are at z −4 and
−5. A dedicated server pre-generates 25 × 25 chunks around spawn at startup, chunks −3 to 21 here, so all
three sit one or two chunks outside that area's north edge. That makes the spawn pre-generation edge a
suspect, not a confirmed cause. GregTech on 2.8.4 is shape A (GT 5.09.51), where ore writes check the live
block, so whatever reaches the block first decides it.

## Next steps, if this matters later

- Run the same pair with the 0.13.0 jar to see whether the flip predates 0.14.0.
- Move the walk centre so chunks (2, −4), (7, −5) and (16, −5) sit well inside or well outside the spawn
  area, and see whether the flip follows the edge.
- On a newer pack, check the 2.9 launch floor first: same-jar launch pairs differ in 12 to 22 of 2,401
  chunks on 2.9.0-beta-3, RC-1, RC-2 and the 2026-10-11 daily (results/2026-09-25-2.9.0-RC-1-compatibility).

## Reproduce

Probe outputs: `~/.cache/gtnh-determinism/rc-check/launch-pair` (both packs),
`launch-pair-te` (tile-entity detail and a raw NBT dump of chunk (3, −4)), and `launch-pair-hc3` (pinned
order). Compare with `scripts/diff-probe.py` and `scripts/diff-tedetail.py`. Block reads used
`world_chunks()` from `scripts/diff-region-blocks.py` and `registry()` from
`scripts/inventory-region-diff.py` on `verify-284/World`. The node box counts and the "Other outcome" column
were read from launch B of the `launch-pair-te` pair. The pinned pair's launch B overwrote that world
later. Its blocks at the 5 positions match the table: deepslate, tuff and deepslate, and GregTech ore at
the two (16, −5) positions.
