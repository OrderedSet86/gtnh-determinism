# 2026-08-28 — Et Futurum deepslate band: block-level launch determinism fixed

**Block-level launch nondeterminism 9,937 → 91 blocks (99.1%). Ore TE control 3 → 0.**
This unblocks block-level measurement, which was the stated prerequisite for working on dirt/gravel
and ore.

Seed `-1501259159663517643`, GTNH 2.8.4, radius 15, two COLD runs, same jar, same walk order.
Jar `gtnhdeterminism-v0.5-main.17+295ed5595e-dirty`, md5 `141694e705d2d7cb5aa1fb0aa83b26a6`.

## The pivot

The investigation started aimed at GregTech `WorldgenStone`, on the theory that the y~20
`2289:0 <-> 1:0` toggle was GT stone. It is not. Reading the FML block registry out of `level.dat`:

```
id 2289: etfuturum:deepslate
id 2293: etfuturum:tuff
id 2294: etfuturum:calcite
id 2480: etfuturum:cave_vine
id    1: minecraft:stone
```

Block 2289 occupies 4,854,639 blocks of this world against 394,154 persisted ore tile entities —
6.5% of all blocks. It is the deepslate layer, not ore and not GT stone. Identifying the block id
before designing the fix is what stopped a second wasted attempt.

## The bug

`ganymedes01.etfuturum.world.EtFuturumLateWorldGenerator.doDeepslateGen(Chunk)`, decompiled from the
shipped `etfuturum-2.6.2.25-GTNH.jar`:

```java
int maxY = whitelisted ? world.getHeight() : ConfigWorld.deepslateMaxY;
for (int y = 0; y <= Math.min(maxY, world.getActualHeight()); y++)
  for (int lx = 0; lx < 16; lx++)
    for (int lz = 0; lz < 16; lz++)
      if (deepslateMaxY >= 255 || whitelisted || y < deepslateMaxY - 4
          || y <= deepslateMaxY - world.rand.nextInt(4))     // <-- per-block, clock-seeded
        replaceBlockInChunk(...);
```

`world.rand` (`World.field_73012_v`) is the shared live world RNG: seeded from the clock at world
construction and advanced by mob spawning, block ticks, weather and every other mod. So the top
surface of the deepslate layer — a 4-block band under `deepslateMaxY`, decided per column — is not a
function of the world seed at all.

Same bug class as audit finding F2 (Witchery clock RNG) and the TiC slime island fix.

## The fix

`EtFuturumDeepslateMixin` — `@Inject` at HEAD of `doDeepslateGen` seeds a fork from
(world seed, chunk x, chunk z) via the existing `TcForkUtil.fork`, and a `@Redirect` on the
`World.field_73012_v` GETFIELD answers the band's draws from it. Both injectors are `require = 1`, so
a failed bind crashes the boot rather than silently no-opping.

Re-seeding per call is deliberate: `EtFuturumLateWorldGenerator` can replay a chunk from its static
`deepslateRedoCache`, and re-seeding makes a replay reproduce the identical boundary instead of
continuing a stream.

Targeted with `@Mixin(targets = "...")` so no Et Futurum symbols appear in the code — but Mixin's
annotation processor resolves `targets` at compile time, so `dependencies.gradle` still needs
`ganymedes01.etfuturum:Et-Futurum-Requiem:2.6.2.25-GTNH:dev` (`transitive = false`). Note the group
is `ganymedes01.etfuturum`, not `com.github.GTNewHorizons`.

## Result

Two identical cold runs, same jar, same order:

| metric | before | after |
|---|---|---|
| differing blocks | 9,937 across 62 chunks | **91 across 50 chunks** |
| `deepslate <-> stone` | 9,451 | **0** |
| ore tile entities | 3 | **0** |
| chest / spawner tile entities | 0 | 0 |

Remaining 91 blocks and the whole remaining TE control are Et Futurum too:

```
2480:0 <-> 2480:1   44   etfuturum:cave_vine metadata
2479:0 <-> 2479:1   22   (adjacent EF block, metadata)
2293   <-> 2289     24   tuff <-> deepslate, one cluster at y=8
TE: etfuturum.cave_vines  changed=105
```

Cave vines are a growing plant whose initial state is rolled at generation — almost certainly the
same `world.rand` pattern, and the obvious next target. The y=8 tuff/deepslate cluster is separate
and small.

## What this does and does not do

It fixes **launch** determinism, not order determinism. It says nothing about whether two different
walk orders agree — that is still open for ore, dirt/gravel and vanilla cave dungeons. What it buys
is that the block-level metric is now usable: a 91-block floor can resolve a dirt/gravel change,
where a 9,937-block floor could not.

Also worth noting for anyone re-reading older results: every "launch determinism is perfect" claim in
this repo was measured on chest TEs and the probe's per-chunk summary fields. Raw blocks were never
checked until now, and they were never clean.

## Reproduce

```sh
export PROBE_JAVA=~/.gradle/jdks/azul_systems__inc_-17-amd64-linux.2/bin/java
scripts/build-jar.sh fix --deploy <server-dir>
PROBE_SEARCH=true PROBE_PORT=25607 scripts/run-probe.sh <server-dir> -1501259159663517643 rows a.json 15
cp -a <server-dir>/World A
PROBE_SEARCH=true PROBE_PORT=25608 scripts/run-probe.sh <server-dir> -1501259159663517643 rows b.json 15
cp -a <server-dir>/World B
python3 scripts/diff-region-blocks.py A B    # same order, same jar
```

To identify an unknown block id, read the FML registry from `level.dat`: entries are
`{K: "\x01modid:name", V: id}` under `FML.ItemData`. Do this before theorising about which mod owns a
transition.

---

# Follow-on, same day: cave vines. Launch determinism now complete

Two more clock-seeded rolls in Et Futurum, both found by chasing the 93-block residual above.

**1. `BlockCaveVines.growVine(World, x, y, z, boolean)`** reads `world.rand`
(`World.field_73012_v`) twice to roll the glow-berry state. Worldgen calls it while building each
vine, so berries were never a function of the seed. That is the `2479`/`2480` metadata toggles.
Fixed by `EtFuturumCaveVineGrowMixin`: a `@Redirect` on the GETFIELD answers from a rand seeded on
(world seed, x, y, z). `func_149674_a`, the vanilla random tick, is deliberately untouched — that is
live gameplay growth and is supposed to be unpredictable.

**2. `TileEntityCaveVines()`** rolls the vine's maximum length in its constructor:

```java
public TileEntityCaveVines() {
    this.tipSheared = false;
    this.maxLength = new Random().nextInt(26) + 2;   // bare clock-seeded Random
}
```

`WorldGenCaveVines` never calls `setMaxLength`, so that value persists into saved NBT — the 105
differing `etfuturum.cave_vines` tile entities. The constructor has no world and no position, so it
cannot be seeded there. `EtFuturumCaveVineTeMixin` defers instead: the constructor's value is
provisional and gets replaced from (world seed, x, y, z) the first time anything reads or persists
it, by which point the tile entity is placed. A value loaded from NBT or set through
`setMaxLength` is authoritative and never re-rolled.

## Result: two identical cold runs, same jar, same order

| metric | stock-ish start | after deepslate | after cave vines |
|---|---|---|---|
| differing blocks | 9,937 (62 chunks) | 93 (52 chunks) | **1** |
| differing tile entities | 3 ore | 105 cave_vines | **0 of 388,803** |

The single remaining block is `etfuturum:deepslate -> minecraft:gravel` at `(-350, 4, 96)` — one
contested position in the gravel/deepslate mutual exclusion, not a distinct mechanism.

**Block-level launch determinism is now a usable measurement instrument.** Every prior "launch
determinism is perfect" claim in this repo was measured on chest tile entities and the probe's
per-chunk summary fields; raw blocks were never checked and were never clean until now.

## Still open: dirt/gravel needs a better test

Order determinism is untouched by any of this. The dirt/gravel attempt made earlier the same day
(`RwgPocketPinMixin`, pinning pocket position and blob shape to (seed, chunk, type, index)) was
reverted: it bound correctly and changed output, but the share of the residual that is
dirt/gravel-vs-plain-stone did not move (57.6% -> 57.5%), meaning the pockets were still landing
differently. Position skew is therefore NOT the binding constraint.

That conclusion deserves re-testing now that the instrument is clean, because it was measured
against a 91-93 block floor with an unexplained 0-vs-93 control discrepancy. A better test should:

- re-establish the order baseline on the current jar (floor is now 1, not 93);
- distinguish "pocket moved" from "pocket blocked" directly, by comparing the set of positions a
  blob *attempted* rather than the blocks that survived — the surviving-block metric conflates the
  two, which is what made the earlier result ambiguous;
- treat dirt/gravel and GT ore as one experiment, since they are a first-writer-wins mutual
  exclusion and fixing either alone only changes which arbitrary answer wins.
