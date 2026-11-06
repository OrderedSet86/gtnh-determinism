# RWG2_CONTINENTS on 2.9.0, and the RWG deco mixins that never bound

2026-10-31. Branch `claude/rwg-ocean-spawning-fix-095f06`.

## What changed in 2.9.0

2.9.0 final (DreamAssemblerXXL `releases/manifests/2.9.0.json`, 2026-10-31) adds Realistic-World-Gen-2
2.0.3, mod id `rwg2`, package `rwg2.*`. The RWG2 repository was created on 2026-10-30, and 2.0.0 to 2.0.3 were all released that day. The RCs did not have RWG2. RWG2 registers the world types `RWG2` and
`RWG2_CONTINENTS`. `RWG2_CONTINENTS` puts land around each Voronoi cell centre and ocean at the cell
edges (`rwg2/util/ContinentalNoise.java`).

The 2.9.0 `config/defaultworldgenerator.cfg` sets `World Generator=RWG2_CONTINENTS` and allows `flat`,
`RWG` and `RWG2_CONTINENTS`. RC-2 had `World Generator=RWG` and `Lock World Generator=true`.
DefaultWorldGenerator is client-only. The RC-2 and 2.8.4 server zips set `level-type=rwg`. The 2.9.0
server zip was not published at the expected URL when this was written.

2.9.0 also updates RWG from alpha-1.5.0 to alpha-1.5.3. alpha-1.5.3 (Realistic-World-Gen PR 19) adds the
missing `break`s to `Support.addBiome`, for new worlds only (`Use fixed biome lists for new worlds`,
default true, saved per world as `biomeListVersion` in `rwg_data.dat`).

## Pack

Assembled from the 2.9.0 manifest at `~/.cache/gtnh-determinism/v290`: 216 server-side jars, scaffolding
from the RC-2 server, lwjgl3ify 3.0.42 `forgePatches` launch jar, the 2.9.0 config zip. 59 jars were
downloaded and 156 copied from RC-2. Galaxy-Space-GTNH 1.1.144 is in a private repository, so the jar came
from the authenticated asset API. Every jar passes `unzip -t`. GregTech 5.09.54.264, RWG alpha-1.5.3, RWG2 2.0.3,
Java 21 (Zulu).

## Finding: `generate` injectors on RWG classes never bound

Mod jars are reobfuscated, so an override of `WorldGenerator.generate` exists only as `func_76484_a` in
the shipped RWG alpha-1.5.0, alpha-1.5.3 and RWG2 2.0.3 jars (javap). The RWG mixins are `remap = false`
and the refmap has no entry for them, so `method = "generate"` matched nothing in production. The late
mixin config has no `defaultRequire`, so this was silent:

| mixin (jar 0.14.0) | `require` | state in production, from `-Dmixin.debug.export=true` |
|---|---|---|
| `RwgDecoForkMixin` | unset (0) | handler merged into all 29 deco classes, never called from `func_76484_a` |
| `DecoBigTreeCtorMixin`, ctor | 0 | bound: the no-arg constructor sets `size = -1` |
| `DecoBigTreeCtorMixin`, `generate` | 0 | `func_76484_a` does not call the handler, so `size` stays -1 |

With `heightLimit = -1`, `checkBlockLine` scans from the tree base downward, stops at the ground block
one step below and returns 1, and `validTreeLocation()` rejects anything under 6. **Every no-arg
`DecoBigTree` (Wood Hills, Wood Mountains) failed to generate on RWG worlds with jar 0.14.0 and
earlier.** The RWG decoration stream was never forked, but RWG decoration was listed as fixed in the README.

The first RWG2 boot found this: the RWG2 copy of the size redirect was `require = 1` and crashed with
`could not find any targets matching 'generate'`. All three RWG and RWG2 deco injectors now have
`method = { "func_76484_a", "generate" }` and `require = 1`.

## Release jar: position-pure RWG seeding, multiseed

In the first fix for the two dead injectors, each decorator's `Random` was forked with one draw from the
chunk's shared populate stream. That stream is not position-pure: structure pieces, lakes and dungeons
draw from the stream before the ore pass, and stop early on live terrain. The release jar replaces the fork:

- `RwgPopulateReseedMixin` / `Rwg2PopulateReseedMixin` reseed the populate `Random` from (world seed,
  chunk, stage) at `new OreGenEvent.Pre` and at `new DecorateBiomeEvent.Pre`.
- `RwgDecoForkMixin` / `Rwg2DecoForkMixin` are `@WrapMethod` on all 29 decorators' `func_76484_a`, passing
  a `Random` seeded from (world seed, x, y, z, decorator class). A decorator draws nothing from the shared
  stream.

In the class dumps from the first boot of each pack, the wraps and both redirects are called. Jar md5
`65daeadd`, probe `ab459ecc`. Radius 8, fix jar, cold boot per arm, `PROBE_SEARCH=true`,
`-Dgtnhdet.dungeontrace=true`. Differing blocks, `rows` vs `spiral`, plus one same-order launch pair:

| pack, level type | -777 | 42 | 1234567890 | 8675309 | launch pair, -777 |
|---|---|---|---|---|---|
| 2.9.0, `RWG2_CONTINENTS` | 0 | 388 | 0 | 18 | 0 |
| 2.9.0, `rwg` | 133 | 0 | 839 | 172 | 23 |
| 2.8.4, `rwg` | 1 | 2 | 72 | 0 | 1 |

Dungeon rooms and chests are identical in all 15 pairs: 0 existence, 0 contents and 0 NBT differences.
The 2.9.0 `rwg` launch pair (same order, two JVMs) differs by 23 blocks: Et Futurum `glow_lichen`,
`cave_vine` and `cave_vine_plant` around (-115, 30, 142), and `deepslate` ↔ `gravel` at y 1 to 3. Those
23 blocks are also in the -777 route pair.

The residual blocks, by kind:

| kind | where |
|---|---|
| whole trees on one walk only: vanilla oak and jungle leaves and logs, Thaumcraft `blockMagicalLeaves`/`blockMagicalLog`, BiomesOPlenty `leaves3` written over vanilla leaves | every non-zero pair, for example 330 blocks in chunks (21..22, 7) on 2.9.0 `rwg` 1234567890 |
| `etfuturum:deepslate_redstone_ore` ↔ `etfuturum:deepslate` | 2.9.0 `rwg` -777 and 1234567890, about 100 blocks each |
| `etfuturum:deepslate` ↔ `minecraft:gravel` at y 1 to 4 | 2.9.0 and 2.8.4 |
| single HarvestCraft gardens, a ProjectRed lily, one ExtraBees hive | 2.8.4 |

On 2.9.0 `rwg` -777 with the jar built from `HEAD` (0.14.0 behaviour), the pair differs by 679 blocks, against 133 here. The
BiomesOPlenty tree at (-372, 76, 268) is gone.

## RWG2 2.0.6, final jar

The 2.9.0 manifest changed after the measurements above. The current manifest has RWG2 2.0.6, GregTech 5.09.54.265 and
three other updates. The server at `~/.cache/gtnh-determinism/v290` has the new jars. The fix jar here
has every change in 0.15.0: the Witchery foundation reads, the saved-data reset and the Java 25 build.
Jar md5 `f01287f6`, probe `ca072926`. Radius 8, cold boot per arm, `PROBE_SEARCH=true`,
`-Dgtnhdet.dungeontrace=true`, `RWG2_CONTINENTS`. 2.9.0 `rwg` and 2.8.4 boot with this jar and log no
`/ERROR] [mixin]` line. All 29 runs below log none either.

Differing blocks, `rows` vs `spiral`:

| -777 | 42 | 1234567890 | 8675309 | launch pair, -777 |
|---|---|---|---|---|
| 4 | 66 | 1 | 12 | 4 |

Dungeon rooms and chests are identical in all five pairs. The -777 walk pair and launch pair differ by the
same 4 blocks: logs and a HarvestCraft mushroom garden at (-242, 63, -106). Seed 42 differs by
`etfuturum:deepslate_redstone_ore` ↔ `etfuturum:deepslate` around (142, 19, 31), and by deepslate, gravel
and GT stone at y 3 to 4. Seed 8675309 differs by 9 blocks of `gravel` ↔ `deepslate` at y 2 and by 3
GregTech ore blocks. Seed 1234567890 differs by one ExtraBees hive.

### Witchery foundation reads

`witch-scan` generated spawn and the surrounding chunks for 200 seeds and found a Witchery structure
within 6 chunks of spawn on 39 of them. Six of those seeds were walked `rows` and `spiral`, with the
virgin reads (the default) and with `-Dgtnhdet.rwg2witchery=false`:

| seed | virgin reads: blocks | live reads: blocks | chunks generated / populated, `rows` vs `spiral` |
|---|---|---|---|
| -7567423175881781137 | 11 | 0 | 646 / 586 both, either way |
| 9087070175460388442 | 214 | 0 | 641 / 580 both, either way |
| 8218033039576139462 | 16,187 | 16,125 | 651 / 588 vs 652 / 589, either way |
| 6085785342464883720 | 98 | **43,086** | virgin: 750 / 615 both. Live: 745 / 607 vs 750 / 615 |
| 6040950234182934267 | 57 | 52 | 669 / 577 both, either way |
| 4611267950387309417 | 0 | 0 | 638 / 579 both, either way |

Chests and dungeon rooms are identical in every pair except one: on 6085785342464883720 with live reads,
`spiral` has 35 chests and `rows` 34. With live reads, the chunks around (0, -14), at x 0, z -224, are not generated or not populated on the `rows` walk of that seed. The 43,086 blocks and the extra chest are in those
chunks. With virgin reads, those chunks are generated and populated on both walks.

The other residuals are not Witchery structures:

- 8218033039576139462: 11,759 blocks of a GregTech red granite blob in chunks (-8..-6, 4..5) on `rows`
  only, with GT ores in a different host stone inside the blob. Around x -145, z 114 in chunks
  (-10..-9, 6..7), `spiral` alone has dirt and gravel pockets at y 1 to 4, a Thaumcraft great tree,
  IC2 rubber trees and BiomesOPlenty mud and poison. Both are populated in both walks.
- 9087070175460388442: vanilla oak leaves and logs and a HarvestCraft chestnut at three places.
- The rest: Et Futurum deepslate ore host stone, `deepslate` ↔ `gravel` near bedrock, glow lichen, and
  one Magic Bees hive.

A run of these pairs with the first Java 25 jar was stopped after five runs: under JvmDowngrader,
`MinimumSpanningTreeMixin`'s anonymous `Comparator` compiled to a nested class, and Mixin logged
`requires the following unsupported language features: [NESTING]` and did not apply the mixin, on all
three packs. The mixin uses a lambda now, and `scripts/build-jar.sh` rejects a jar with a nested class
under a `mixins` package.

## Route A/B: RWG2_CONTINENTS, first fix

Seed -777, radius 8, `rows` vs `spiral`, cold boot per arm, `PROBE_SEARCH=true`. Stock is the same server
with the fix jar moved out of `mods/`.

| arm | differing blocks | chunks affected | chests: existence / contents / NBT | dungeon rooms |
|---|---|---|---|---|
| stock | 158,785 | 570 of 631 | 1 / 0 / 1 (30 vs 31 chests) | not traced |
| fix | **0** | 0 of 634 | 0 / 0 / 0 (24 chests) | 13 = 13, identical |

Fix vs stock on `rows`: 2,126,312 differing blocks across 626 chunks, mostly dirt, gravel, stone and
deepslate below y 45. That figure mixes the jar's changes with stock launch variance.

Second seed, with the jar that also fixes the RWG deco mixins (RWG2 code unchanged):

| level type, seed | differing blocks | chunks | dungeon rooms | chests: existence / contents / NBT |
|---|---|---|---|---|
| `RWG2_CONTINENTS`, 42 | **177** | 4 of 638 | 17 = 17 | 53 chests, 0 / 0 / 0 |

Residual, by block: `minecraft:leaves` (18:9, 18:11, 18:1, 18:8) and `harvestcraft:pamMaple` around
(39, 95, -51), a HarvestCraft fruit tree on one walk only. 18 + 15 blocks of
`etfuturum:deepslate_redstone_ore` ↔ `etfuturum:deepslate` around (57, 18, 37), ore host-stone
placement.

## Binding proof

Every RWG and RWG2 deco injector is now `require = 1`, so a miss is a fatal `InvalidInjectionException`
when the class loads. Five boots (2.9.0 `rwg` ×2, 2.9.0 `RWG2_CONTINENTS` ×2, 2.8.4 `rwg`) logged 0
mixin failures. `-Dmixin.debug.export=true` on 2.8.4 (RWG alpha-1.5.0) and 2.9.0 (RWG2 2.0.3) shows the
handlers called from `func_76484_a`: `forkRand` at offset 2 in `DecoBirch`, `DecoGrass` and
`DecoBigTree`, and `resolveSize` at offset 60 in `DecoBigTree`. On 2.8.4 the GT ore probe line is still
`isReplaceableOreGen shape`.

## Upstream: RWG and RWG2 share `rwg_data`

Both mods save `RwgWorldSavedData` under the map-storage name `rwg_data`, and both register
`/rwg_noise` and `/rwg_buginfo`. Reading RWG's data on an RWG2 world throws
`ClassCastException: rwg2.world.RwgWorldSavedData cannot be cast to rwg.world.RwgWorldSavedData`
(seen from the probe). Worldgen is unaffected: each mod's `NoiseSelector` reads `rwg_data` only from that
mod's generator. The command whose mod registers second runs on both world types.

## Route A/B: `rwg` on 2.9.0, first fix

Seed -777, radius 8, `rows` vs `spiral`. The baseline jar is built from `HEAD` (0.14.0 behaviour: RWG
deco injectors inert, big oaks removed). "This branch" is the first fix, before the position-pure
seeding.

| jar | differing blocks | chunks | dungeon rooms | chests: existence / contents / NBT |
|---|---|---|---|---|
| `HEAD` | 679 | 6 of 654 | 11 = 11 | not compared |
| this branch | **685** | 9 of 654 | 11 = 11 | 128 chests, 0 / 0 / 0 |

Both jars share one residual at the same coordinates: a BiomesOPlenty tree (`colorizedLeaves2`, `logs4`,
`foliage`) and `minecraft:leaves` around (-372, 76, 268), in chunks (-24..-23, 16..17): 665 blocks with the
`HEAD` jar and 667 with this branch. Both also have 2 differing blocks in chunk (-7, 2). The rest differs
between the jars:

| jar | other residual |
|---|---|
| `HEAD` | 12 blocks of `gregtech:gt.blockgranites` and GT ore around (-364, 15, 336) |
| this branch | 16 blocks of `etfuturum:glow_lichen`, `cave_vine`, `cave_vine_plant` and one `Thaumcraft:blockAiry` at y 14 to 46 around (-115, 30, 142) |

One pair per jar cannot separate the change from run-to-run variance at this size.

## Not measured

- Launch pairs on 2.9.0 other than -777.
- Radii above 8.
- Chest contents on the `HEAD` `rwg` arm.
- Stage-0 prefilter predictions against full generation on `RWG2_CONTINENTS`, other than spawn.

## Prefilter smoke run on `RWG2_CONTINENTS`

`scripts/prefilter.sh -777,42` on the 2.9.0 server, which passes `-Dprobe.worldtype=RWG2_CONTINENTS`:
both seeds evaluated in 0.6 s, 83 and 93 terrain chunks, none regenerated. `biomes.json` lists the 30
RWG2 biomes (ids 300 to 329) with rain and humidity values. The predicted spawn is in the chunk that full
generation spawned in for both seeds: (-83, 12) in chunk (-6, 0), and (207, 64) in chunk (12, 4). The
full probe records spawn only to the chunk.

## Reference-seed route map

Seed -1636594104014467454, 2.9.0-beta-3 (`rwg`, RWG alpha-1.5.2), overworld at radius 120, rebuilt with
the release jar (`make-routemap.py` with a 24 GB heap, 16 min). Compared with the 0.14.0 build of the same
map (`rc-check/routemap`, 2026-10-11) using `rc-check/tools/bundle_diff.py`:

| comparison | chests identical | only in 0.14.0 | only in 0.15.0 | contents changed | POIs |
|---|---|---|---|---|---|
| 0.14.0 vs release jar with `-Dgtnhdet.rwgdecoseed=false` | 6,271 of 6,271 | 0 | 0 | 0 | identical |
| 0.14.0 vs release jar | 6,258 of 6,271 | 11 | 12 | 2 | vanilla dungeons 1,224 → 1,225 |

Every chest difference follows the position-pure RWG seeding. Ore veins differ only in veins at the
window edge (|x| or |z| around 1,820 to 1,880), and two 0.14.0-era builds differ there as well.

Stage-0 recall on the release-jar build: 2,394 chests predicted, 2,393 present, 2,352 with identical
contents and NBT. The absent chest is the village TinkerHouse chest at (176, 65, -490), which the
0.14.0 build generated at y 82.

The viewer loads the new bundle (overworld, 6,272 chests) with no console errors.
