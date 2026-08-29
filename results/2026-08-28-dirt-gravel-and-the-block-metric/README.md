# 2026-08-28 — dirt/gravel pockets, and why we cannot currently measure them

**Outcome: no fix landed. One attempt reverted. But a blocking measurement problem was found that
has to be solved before dirt/gravel or ore can be worked on at all.**

Seed `-1501259159663517643`, GTNH 2.8.4, radius 15, all arms COLD.

## THE BLOCKER: block-level worldgen is not launch-deterministic

Two **identical** cold runs — same jar, same walk order, same seed:

```
cold rows vs cold rows:  differing blocks 9,937 across 62 chunks
top transitions:
  2289:0 -> 1:0   4866   [(-361, 20, -176), (-360, 20, -176), (-363, 20, -175)]
  1:0 -> 2289:0   4585   [(-367, 20, -176), (-365, 20, -176), (-363, 20, -176)]
  3:0 -> 2289:0    237
  2289:0 -> 3:0    223
  2480:0 -> 2480:1  13
```

Block 2289 is **not** GT ore — it occupies 4,854,639 blocks of this world against 394,154 persisted
ore tile entities, i.e. ~6.5% of all blocks. It is a GT/UndergroundBiomes stone variant. So two
identical runs disagree about stone-vs-modded-stone on ~10k blocks, in a tight cluster at y≈20.

**This invalidates the metric that was about to be used.** Everything previously called "launch
determinism is perfect" was measured on chest tile entities (floor 0) and the probe's per-chunk
summary fields (floor `gravel=1`). Raw blocks were never checked. The tile-entity floor is 3 and is
trustworthy; the block floor is at least 9,937 and possibly much larger — a second control pair
measured 243,756 differing blocks across 896 chunks, so the floor is not even stable between pairs.

Dirt and gravel are plain blocks, not tile entities. **There is currently no reliable way to measure
a dirt/gravel change.** Any before/after in that range is inside the noise.

Leading suspect, from source: GT `WorldgenStone`. `validStoneSeeds`
(`forks/GT5-Unofficial/.../WorldgenStone.java:44`) is an instance field on a long-lived singleton,
never cleared, populated from a **live-world air probe** at `:176-177` and `:198-202` — first writer
wins for the whole region. It also retroactively rewrites the host-stone digit of already-placed ore
at `:263-271` via `TileEntityOres.overrideOreBlockMaterial`. GT stones run *before* ore veins in the
same `WorldGenContainer.run()`.

## What dirt/gravel actually are

Not a biome decorator. Two hard-coded vanilla `WorldGenMinable` blobs in
`ChunkGeneratorRealistic.populate` — dirt ×10, gravel ×5 — at RWG alpha-1.5.0 lines 516-532
(1.5.2 clone: 539-555), positioned entirely from the shared populate RNG:

```java
int l5 = x + rand.nextInt(16); int i9 = rand.nextInt(64); int l11 = y + rand.nextInt(16);
ore_dirt.generate(worldObj, rand, l5, i9, l11);
```

Two independent, compounding order-dependencies:

1. **Position skew.** Everything upstream in the same stream has a route-dependent draw count. The
   worst is `WorldGenLakes.generate`, called twice immediately above: it descends with a live
   `isAirBlock` scan and `return false`s **before consuming a single draw**. The 8 vanilla
   `WorldGenDungeons` attempts and the mineshaft/stronghold/village passes have the same shape. One
   flipped solidity read relocates all 15 pockets in the chunk.
2. **Mutual exclusion with GT ore.** `WorldGenMinable` only replaces `Blocks.stone` — the default
   `Block.isReplaceableOreGen` is an identity compare — so a GT ore block already there blocks
   dirt/gravel. Conversely `TileEntityOres.setOreBlock` matches none of its host-stone branches for
   dirt or gravel and falls through to `return false`, so dirt/gravel already there blocks ore.
   **First-writer-wins in both directions.** Confirmed empirically: at positions where one world has
   an ore TE and the other does not, the other holds dirt 2154 times and gravel 1258 times.

Both sides must move together. Purifying one side alone repeats the mistake already made twice on
GT ore — it picks a different arbitrary answer rather than fixing anything.

Note for future bisects: `RwgDecoForkMixin` already targets `DecoClay`, which is invoked immediately
before the dirt loop on river-strong chunks. Stock that call draws 24; with the fix jar it draws 1.
So fixed-jar dirt/gravel positions already differ from stock. Do not compare across that boundary.

## Attempt: pinning pocket position and shape. REVERTED, inconclusive

`RwgPocketPinMixin` redirected the two `WorldGenMinable` call sites (ordinals 0 and 1 of
`func_76484_a` inside `func_73153_a`) to derive each pocket's position and blob shape from a rand
seeded on (world seed, chunk, pocket type), making a pocket a pure function of its chunk and immune
to upstream draw skew. The chunk was recovered as `x >> 4`, since stock computes
`x = chunkX*16 + nextInt(16)`.

Measured rows vs spiral: 98,161 differing blocks before, 151,569 after — apparently worse. But the
same-jar control was 9,937, and the other available control was 243,756, so this measurement carries
no signal either way.

It also had a genuine design flaw, independent of the measurement problem: the per-(chunk, type)
rand was kept in a **single slot**. `populate` can re-enter through a `world.getBlock` that cascades
a neighbour's generation, and a nested chunk resets the slot, so the outer chunk's remaining pockets
restart their sequence — and whether that happens is itself route-dependent. A correct version needs
a per-(chunk, type) counter map and a stateless per-call seed, not a sequential rand.

Reverted. Jar md5 returned to `6408326d`, matching the pre-attempt build.

## Revised order of work

1. **Make block-level worldgen launch-deterministic.** Until two identical cold runs agree on blocks,
   nothing about dirt, gravel or stone can be measured. Start at GT `WorldgenStone`'s
   `validStoneSeeds` first-writer-wins cache and its live air probe.
2. **Then dirt/gravel**, fixing position skew and the ore mutual exclusion together.
3. **Then ore**, per the standing decision: identity deterministic from the oreseed, and ore's writes
   pinned to a fixed point in every chunk's sequence so ore consistently comes last and therefore
   respects structures instead of leaving ore embedded in them.

## Reproduce

```sh
export PROBE_JAVA=~/.gradle/jdks/azul_systems__inc_-17-amd64-linux.2/bin/java
PROBE_SEARCH=true PROBE_PORT=25604 scripts/run-probe.sh <server-dir> -1501259159663517643 rows out-a.json 15
cp -a <server-dir>/World A
PROBE_SEARCH=true PROBE_PORT=25605 scripts/run-probe.sh <server-dir> -1501259159663517643 rows out-b.json 15
cp -a <server-dir>/World B
python3 scripts/diff-region-blocks.py A B     # same order, same jar — should be 0, is ~10k
```
