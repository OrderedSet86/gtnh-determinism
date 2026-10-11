# 2026-09-25 — GTNH 2.9.0-RC-1: a crash from Mobs-Info, fixed

**Question asked: does the fix jar work on 2.9.0-RC-1? Answer: released 0.13.0 does not; it crashes at
init, and so did the 0.14.0 working tree. With the fix in Part 4, the working tree boots on RC-1 and is as
deterministic there as on beta-3. Every class the jar binds to is byte-identical to beta-3. The
cause is a new global class transformer in Mobs-Info 0.6.0. It rewrites `Random.nextInt` call sites in
every loaded class. That shifts the ordinals that four of the jar's mixins use to pick a draw. Three of
the four crash (`require = 1`): vanilla dungeons in 0.13.0, and the mineshaft corridor chests and the
opt-in mesa corridor chests added in 0.14.0. The fourth, horse spawns (in 0.13.0), silently leaves two of
its seven draws unforked. With `-Dmobsinfo.disableRandomComparisonTransformer=true`, both jars boot.
Part 4 fixes the jar so it works with the rewrite in place.**

Pack: `GT_New_Horizons_2.9.0-RC-1_Server_Java_17-26.zip` (552,356,355 bytes), installed at
`~/.cache/gtnh-determinism/rc1`. Compared against the pristine `GT_New_Horizons_2.9.0-beta-3_Server_Java_17-26.zip`
(for configs) and the `beta3` install (for jars). GregTech is **5.09.54.183**.

## Part 1 — what the packs differ by

- **216 mod jars on both sides. No additions, no removals.** 65 version bumps on the server side.
- Forge universal, `minecraft_server.1.7.10.jar`, `java9args.txt` and `libraries/` are **byte-identical**.
  `lwjgl3ify-forgePatches.jar` differs (lwjgl3ify 3.0.31 → 3.0.35: relauncher UI and the LWJGL
  redirect transformer, no worldgen).

### Every mixin target, checked

Each changed jar was diffed per entry (md5) and cross-referenced against every `@Mixin` target in
`fix-build/src/main/java/.../mixins/`. **No mixin target class changed.** Targets in bumped jars are all
byte-identical: GT `GTWorldgenerator$WorldGenContainer`, `OreManager`, AE2 `MeteoritePlacer` /
`MeteoriteWorldGen`, TConstruct `SlimeIslandGen`, Et-Futurum `EtFuturumLateWorldGenerator` / cave vines /
`MesaCorridor`, Forestry `ComponentVillageBeeHouse`, ProjectRed `TileLily`. Across 5.09.54.133 → .183, GT's
`WorldgenGTOreLayer`, all `GTWorldgenerator*` classes, `StoneType` and `gregtech/common/ores/*` are also
unchanged. Thaumcraft, Witchery, Roguelike, RWG, TooMuchLoot, LootGames and BOP did not move.

Worldgen-adjacent classes that did change, each disassembled and diffed with constant-pool indices
normalised:

| class | change | matters? |
| --- | --- | --- |
| `bartworks/system/worldgen/MapGenRuins.setRandomBlockWAirChance` | `nextInt(100) <= chance` → `< chance` | Ross128b (dim 64) ruins only. Changes blocks there. The jar does not touch that dimension |
| `galacticgreg/WorldGeneratorSpace` | one `>` → `>=` | space asteroids only |
| `tconstruct/world/TinkerWorldEvents.onHurt` | battlesign damage factor 1.5 → 0.25 | combat, not worldgen |
| `gadomancy/.../EventHandlerWorld` | stops calling `loadModData`/`unloadModData` | not worldgen |
| GT `PortalSpawnCheckMixin` (new) | `BlockPortal` spawn check | not worldgen |
| Hodgepodge `MixinMinecraftServer_WorldDataSave` etc. (new) | world-data save queue on `MinecraftServer` | does not conflict: with the Mobs-Info transformer off, both jars boot, and the `loadAllWorlds` loot mixins bind (Part 3) |

### Config

Pristine beta-3 `config/` vs RC-1 `config/`: 11 files plus 118 quest files. Loot configs
(`TooMuchLoot/loot/`, `roguelike_dungeons/settings/loot_*.json`, `EnhancedLootBags/LootBags.xml`) are
byte-identical. One change reaches worldgen:

```
roguelike_dungeons/settings/dungeon_ice.json
-  "criteria": {"biomeTypes": ["SNOWY"]},
+  "criteria": {"biomeTypes": ["SNOWY"], "biomes": ["Tundra"]},
```

`Tundra` (id 115) has the types `COLD, SPARSE, DRY, DEAD, WASTELAND`. It is **not** `SNOWY`, so this is a
real change. On RC-1, Roguelike dungeons in Tundra resolve to the ice theme where beta-3 gave them
another theme. Determinism is not affected, but the same seed makes a different dungeon. Beta-3
Roguelike prefilter results do not carry over to Tundra sites. The prefilter resolves themes inside the
running pack, so it picks this up when run on an RC-1 install.

The other changes do not reach worldgen: GregTech.cfg (four Railcraft tile entities added to a list),
personalspace (two biome names, block-id case), SpecialMobs (`hungry` spawn weight 0 → 1 in three
biomes, a mob spawn not worldgen), backhand, angelica, NEI, CustomFuels, guidenh, and version strings.

## Part 2 — the break: Mobs-Info 0.6.0's `RandomComparisonTransformer`

`mobsinfo` 0.5.21 → 0.6.0 adds an FML coremod transformer,
`com.kuba6000.mobsinfo.asm.RandomComparisonTransformer`. It runs on **every class that references
`java/util/Random`**. The only exclusions are `java.`, `javax.`, `sun.`, `jdk.`, ASM, Mixin, log4j,
launchwrapper and `cpw.mods.fml.`. So it rewrites vanilla and every mod. The rewritten pattern is:

```
invokevirtual java/util/Random.nextInt(I)I      ; followed by
[int constant]                                  ; optional (absent for == 0)
if<cond> / if_icmp<cond>
```

It becomes `ldc const; ldc op; invokestatic com/kuba6000/mobsinfo/api/RandomComparison.nextInt(Ljava/util/Random;III)I`.
The same happens for `nextFloat()F` followed by a float compare. For a plain `Random`,
`RandomComparison.nextInt` simply returns `rand.nextInt(bound)`, so **stock world output is unchanged**.
What changes is the bytecode shape. A `Random.nextInt(I)I` call that is followed by a comparison is no
longer a `Random.nextInt(I)I` call, so every later `Random.nextInt` in the method gets a lower ordinal.
The transformer runs before Mixin applies. The log line
`[MobsInfo[Random comparisons]]: Optimized 1 random comparisons in net.minecraft.world.gen.feature.WorldGenDungeons`
comes immediately before our mixin fails. The switch `-Dmobsinfo.disableRandomComparisonTransformer=true`
turns it off. That switch also degrades Mobs-Info's own drop-chance display, which is what the rewrite
exists for.

### Which injections it hits

These results come from running Mobs-Info's own transformer offline on the target bytecode. For
vanilla that is Forge-patched `recompiled_minecraft-1.7.10.jar`. Unpatched `srg_merged_minecraft.jar` is
wrong for the mineshaft corridor, because Forge's `ChestGenHooks` patch changes that method. For
Thaumcraft and Et-Futurum it is the RC-1 jars. Both runtime crashes below match what the scan predicts. Every fix-jar
injection that targets a `java/util/Random` method is listed. The `nextDouble()D` targets
(`EntityHorseMixin`, `Ae2MeteoriteGenTraceMixin`) are not matched by the transformer.

| mixin | in 0.13.0? | stock target | on RC-1 | failure |
| --- | --- | --- | --- | --- |
| `WorldGenDungeonsScanMixin` | yes | `generate` `nextInt` ord 0, 1 (room extents), 3, 4 (chest X, Z). Ord 2 is the mossy roll | stock #2 (`nextInt(4) != 0`) rewritten. #3 → ord 2, #4 → ord 3 | **crash**: ord 4 has no target. Were it not `require = 1`, ord 3 would fork chest **Z** and leave chest X on the shared stream |
| `MesaCorridorChestMixin` | no (0.14.0, opt-in `-Dgtnhdet.mesapin=true`) | `func_74875_a` ord 0, 1 | stock #0, #1 both rewritten. #2 → ord 0 | **crash when opted in**: ord 1 has no target. Ord 0 would bind stock #2 (the spawner offset) |
| `MineshaftCorridorChestMixin` | no (0.14.0) | `addComponentParts` ord 1, 2 | Forge-patched body has 4 sites. #0–#2 rewritten, #3 → ord 0 | **crash**: ord 1 has no target (observed at runtime, `chestDrawA`) |
| `WorldGenEldritchRingMixin` | yes | `generate`, every `nextInt`, forking only bound 10 | stock #0 (`nextInt(4) != 0`, cosmetic) rewritten, so it escapes the redirect. #1 (`nextInt(10)`, stored) unchanged | **no effect today**: the escaped draw is one the handler passes through anyway. But "every site" has no count to check, so a rewrite of the spawner roll would also go unnoticed |
| `EntityHorseMixin` | yes | `onSpawnWithEgg` (`func_110161_a`), every `nextInt` | stock #1, #4 rewritten, so they escape | **silent leak**: 2 of 5 horse-variant draws are unforked |

No `@ModifyConstant` is used anywhere, and the probe jar has no `Random` targets.

## Part 3 — boots before the fix

Seed `-1636594104014467454`, `scripts/run-probe.sh … rows … 2`, Java 21. Probe jar matched to each fix jar.

| fix jar | Mobs-Info transformer | result |
| --- | --- | --- |
| **0.13.0 release** (md5 `f69b3774cf3b6a16bf030bd7706b1d0f`) | on (stock RC-1) | **crash at init**: `WorldGenDungeonsScanMixin` → `gtnhdet$forkedChestZ` (`nextInt` ordinal 4), `Scanned 0 target(s)` |
| 0.13.0 release | off (`-Dmobsinfo.disableRandomComparisonTransformer=true`) | 38 mixins selected, `Done (7.234s)`, 0 crash reports, radius-2 walk completes |
| **working tree 0.14.0** (`v0.13.0-gt-shape-probe-beta1+970e4eafdf-dirty`, md5 `a1448c208742569027e77b650f1f040e`) | on | **crash at init**: `MineshaftCorridorChestMixin` → `gtnhdet$chestDrawA` (ordinal 1) |
| working tree 0.14.0 | off | 38 mixins selected, 0 crash reports, radius-2 walk completes |

With the transformer off, both jars select the same 38 mixins as on beta-3. They also detect the same GT
shape (`StoneType variant`, F4d on dims `[0, -1, 7]`). That fits Part 1: nothing else in RC-1 moved.

## Part 4 — the fix: find each draw in both call shapes

The goal is to keep Mobs-Info's transformer running: it is how Mobs-Info computes drop odds for NEI. So the
jar now finds its draws whichever shape they have.

- **`RandomDrawPoint`** (`fix-build/.../determinism/RandomDrawPoint.java`) is a custom Mixin injection
  point. It walks the method once and counts plain `Random.nextInt(I)I` calls and Mobs-Info's
  `RandomComparison.nextInt(Random,III)I` calls as one sequence. The transformer swaps calls in place,
  one for one, so position `n` in that sequence is stock draw `n` on every pack. It returns only the draws
  of the requested `shape` (`virtual` or `comparison`). It has no default shape.
- **A draw that is never a comparison in stock code** keeps one `@Redirect` with `shape=virtual` and
  `require = 1`. That covers dungeon extents and chest offsets, and the Eldritch ring spawner roll, which
  is now pinned by position. If some future rewrite ever reaches one of these draws, the injector finds
  nothing and the game refuses to start.
- **A draw that is a comparison in stock code** gets a `@Redirect` (`shape=virtual`) and a MixinExtras
  `@WrapOperation` (`shape=comparison`) in one `@Group` with `min = max =` the stock count. That covers
  mineshaft and mesa corridor chest slots (1 each) and horse spawns (7). The wrap takes the stock draw
  through Mobs-Info's `original`, so the random stream advances exactly as without this jar. It calls
  through unchanged whenever the fix does not own the draw, so Mobs-Info's own behaviour is kept.
  `MineshaftChestPin.pinnedDraw` now takes the stock value instead of a `Random`, so both shapes share
  one implementation.

Mobs-Info never runs `EntityHorse.onSpawnWithEgg`. Its loader builds entities in its own `DummyWorld` and
drives only the drop methods, and the horse fork is armed only inside `onSpawnWithEgg`. So the fix cannot
change what Mobs-Info shows for horses.

### Boots and woven bytecode

Working-tree jar md5 `1b441b4bb9a97beb772f870f9a01cb24`. `-Dmixin.debug.export=true`, then every target method
disassembled. For `verify-284` and `pack-2.7.4` the jars were swapped in for the run and the originals put
back afterwards.

| pack | UniMixins | Mobs-Info rewrite | result |
| --- | --- | --- | --- |
| 2.9.0-RC-1 | 0.3.1 | **on** | 38 mixins (+ mesa when opted in), no errors, walk completes |
| 2.9.0-beta-3 | 0.3.1 | none | 38 (+ mesa), no errors, walk completes |
| 2.8.4 (`verify-284`) | 0.1.23 | none | 36, no errors, walk completes |
| 2.7.4 (`pack-2.7.4`) | 0.1.19 | none | 33, no errors, walk completes |

Woven order of `Random` draws in each target method, on RC-1. Unmarked calls are left to the pack:

| method | stock draw order → what it calls on RC-1 |
| --- | --- |
| `WorldGenDungeons.generate` | extentX → fork, extentZ → fork, mossy → `RandomComparison` (pack), chestX → fork, chestZ → fork |
| `Corridor.addComponentParts` | plank gap → `RandomComparison` (pack), chest A → wrap, chest B → wrap, spawner → `Random` (pack) |
| `MesaCorridor.addComponentParts` (`mesapin=true`) | chest A → wrap, chest B → wrap, spawner → `Random` (pack) |
| `EntityHorse.onSpawnWithEgg` | redirect, **wrap**, redirect, redirect, **wrap** |
| `EntityHorse` max-health helper | redirect, redirect |
| `WorldGenEldritchRing.generate` | cosmetic → `RandomComparison` (pack), spawner roll → fork |

On 2.7.4, 2.8.4 and beta-3 the same methods show the plain redirects at the same positions and no wraps.
That matches pre-fix behaviour. The Mesa row was confirmed on beta-3 as well.

**Negative control.** A throwaway jar that asks the horse group for 8 draws instead of 7 refuses to start
on beta-3: `Callback group @Group(name=gtnhdet$horseNextInt, min=8, max=8) ... expected 8 invocation(s)
but only 7 succeeded`, FATAL, no world generated. The group is enforced; it is not a comment.

### Measured on RC-1

Seed `-1636594104014467454`, radius 24 around spawn chunk (9,9), 2401 chunks, `PROBE_SEARCH=true`, cold
JVM per arm, default fix config. Horses were compared from the saved overworld with
`diff-region-entities.py` (UUIDs excluded).

| pair | chunk hash | vein mix / geometry / only-in-one | chests (227): existence / contents / NBT | horses (9) |
| --- | --- | --- | --- | --- |
| A vs B: rewrite on, rows ×2 (**noise floor**) | 12 / 2401 | 0 / 0 / 0 | 0 / 0 / 0 | identical |
| A vs C: rewrite **on vs off**, rows (**shape contrast**) | 11 / 2401 | 0 / 0 / 0 | 0 / 0 / 0 | identical |
| A vs D: rows vs spiral (**route contrast**) | 1579 / 2401 | 0 / 0 / 2 | 0 / 0 / 0 | identical |
| beta-3 rows vs spiral, same jar | 1580 / 2401 | 0 / 0 / 2 | 0 / 0 / 0 | identical |
| beta-3 rows vs RC-1 rows (**cross-pack**) | 130 / 2401 | 0 / 0 / 0 | 0 / 0 / 0 | identical |

The 227 containers include 27 mineshaft `EntityMinecartChest`s, so the corridor pin is covered.

- **Shape contrast = floor.** Toggling Mobs-Info's rewrite changes nothing beyond launch noise. That is the
  direct check that the wraps answer exactly what the redirects answer.
- **Route contrast on RC-1 matches beta-3 on every row.** The whole-chunk hash (1579) is not what this jar
  pins. beta-1 recorded 1623/2401 for the same comparison (results/2026-09-17-gt-shape-probe-beta1). The 2
  only-in-one vein regions are dim 0, oreseed X = −20, outside the walked box (x −15…33). That is the same
  frontier effect as beta-1. Inside the box the vein residual is 0.
- **Open residual, not introduced here:** rows vs spiral also changes passive-mob and item counts
  (RC-1: chickens −23, cows −45, pigs −15, sheep −33, items −32, foxes +15, rabbits +17, coven witches
  +3, goblins −3). beta-3 shows the same pattern with the same jar (cows −45, sheep −33, chickens −23,
  pigs −11, items −30, foxes +12, rabbits +17, coven witches +3, goblins −3). So it is neither RC-1 nor
  this fix, and it is not closed.
- **Cross-pack 130 chunks: not investigated.** 103 are TE-only, 22 blocks-only and 5 both. The probe's
  chunk hash covers raw numeric block IDs and `writeToNBT` output, and numeric IDs are allocated per world,
  so this pair cannot separate an ID reallocation from a real content change. The Roguelike Tundra theme
  change (Part 1) is one known real source. Everything compared by registry name is identical: veins,
  all 227 containers with contents, and all 9 horses.

## Part 5 — 2026-10-11: 2.9.0-RC-2 and the daily build

A crash report from a player on the GTNH daily build (`mclo.gs/A204tcQ`) shows the same failure on 0.13.0:
`Critical injection failure: Redirector gtnhdet$forkedChestZ … WorldGenDungeonsScanMixin`, with
`mobsinfo-0.6.0` in the mod list. All 7 mod versions checked in that log (GT 5.09.54.221, Et Futurum 2.6.63,
Hodgepodge 2.7.216, AE2 rv3-beta-1082, Tinkers' Construct 1.14.119, Mobs-Info 0.6.0, lwjgl3ify 3.0.37) match
DreamAssemblerXXL's `daily.json` exactly, so the same check was repeated on that build and on 2.9.0-RC-2.

Packs: RC-2 from `GT_New_Horizons_2.9.0-RC-2_Server_Java_17-26.zip` (`~/.cache/gtnh-determinism/rc2`). The daily
build has no server zip. It was assembled at `~/.cache/gtnh-determinism/daily` from RC-2 plus the 27
server-side jars `daily.json` changes (fetched from `gtnh-assets.json`, each `unzip -t` checked) and the
`2.9.0-nightly-2026-10-11` config. Its config changes against RC-2 are anvil GUI styles, crop breeding stats,
version strings and quests; none reaches worldgen.

**Static:** all 71 mixin targets that live in mod jars are byte-identical from RC-1 to RC-2 to daily. Forge,
`minecraft_server.1.7.10.jar`, `libraries/`, UniMixins and the `mobsinfo-0.6.0.jar` file itself are identical in
all three. No changed jar adds a coremod, a class transformer, or a mixin on any vanilla class the jar hooks.
Worldgen classes that did change: Twilight Forest tree generators (2.7.44, dimension 7) and a new Hodgepodge
2.7.215 mixin, `FIX_BOP_EMERALD_GENERATION`, which changes the `isReplaceableOreGen` result in the emerald
generation of six Biomes O' Plenty mountain biomes. Both change content, not determinism; whether the
Hodgepodge fix is on by default was not traced.

**Boots:** working-tree jar md5 `1b441b4bb9a97beb772f870f9a01cb24`, rebuilt from the current tree with the same
md5. Both packs: 38 mixins (39 with `mesapin`), 0 injection errors, 0 crash reports. The woven bytecode is
identical to RC-1 site for site (Part 4 table), including the reporter's crash site.

**Measured**, same seed, radius and arms as Part 4:

| pair | RC-2 chunks | daily chunks | veins mix / geometry / one-walk-only | containers (227) | horses (9) |
| --- | --- | --- | --- | --- | --- |
| A vs B, rewrite on ×2 (floor) | 12 | 14 | 0 / 0 / 0 | identical | identical |
| A vs C, rewrite on vs off | 22 | 16 | 0 / 0 / 0 | identical | identical |
| B vs C, rewrite on vs off | 16 | 12 | 0 / 0 / 0 | identical | identical |
| A vs D, rows vs spiral | 1582 | 1586 | 0 / 0 / 2 (outside the walked box) | identical | identical |
| cross-pack, rows | RC-1 vs RC-2: 582 | RC-2 vs daily: 19 | 0 / 0 / 0 | identical | identical |

The rows-vs-spiral passive-mob and item count differences match RC-1 and beta-3 (cows −45, chickens −23 in all
four packs). That residual is open and unchanged.

**The rewrite on/off chunk differences are launch noise.** Two chunk clusters recur. Cluster Y (chunks −8,−6 /
−8,−5 / −7,−5) has exactly two block-hash states, and in every pack before the daily one it split along
on/off, with the mapping reversed between RC-1 and RC-2. The daily arms A and B, both with the rewrite on,
landed in different states, so the cluster flips between identical launches. Cluster X (chunks 2,−4 / 4,−5 /
7,−6 and neighbours) already differed in RC-1's floor.

Runtime: two packs, 10 server boots, 12 min 45 s against an estimate of 15–18 min (one server at a time,
`nice -n 19`, pinned to 7 of 16 cores). Artifacts and tools: `~/.cache/gtnh-determinism/rc-check/`.

## What this means

- **Players on RC-1 with 0.13.0 cannot start the game.** Mobs-Info is `BOTH`, so the crash is on the
  client too. Stopgap: add `-Dmobsinfo.disableRandomComparisonTransformer=true` to the JVM arguments.
  This restores stock bytecode shape. Stock generation is unaffected, because the rewrite never changed a
  draw. The cost is less accurate drop odds in Mobs-Info's NEI pages.
- **The ordinal selectors are the fragile part, not these five mixins.** Any `@At(INVOKE, Random.next*)`
  selected by ordinal, or matched by "all occurrences", now depends on whether each neighbouring draw
  is followed by a comparison. A `require = 1` injection fails loudly. One with no `require` loses draws
  silently: that is horse spawns today, and it would have gone unnoticed without this audit.
- Part 4 does that for every `Random`-selecting injector in the jar, and a bad count now stops the
  game instead of shipping a half-fix.

## Reproduce

```
PROBE_JAVA=~/.gradle/jdks/azul_systems__inc_-21-amd64-linux.2/bin/java \
  scripts/run-probe.sh ~/.cache/gtnh-determinism/rc1 -1636594104014467454 rows OUT.json 2
```
Add `PROBE_EXTRA_ARGS=-Dmobsinfo.disableRandomComparisonTransformer=true` for the transformer-off arm.
Logs are in `~/.cache/gtnh-determinism/rc1-out/`. The offline transformer scanner is a single Java class
that runs `RandomComparisonTransformer.transform` on a class from a jar and diffs the `Random` call sites.
Classpath: `mobsinfo-0.6.0.jar`, ASM 9.4 + tree, launchwrapper 1.12, log4j 2.0-beta9.

Fix verification: `-Dmixin.debug.export=true` on each boot, then `javap -c -p` of `.mixin.out/class/...` for
the target methods. Measured arms: `PROBE_SEARCH=true PROBE_PORT=25611 scripts/run-probe.sh
~/.cache/gtnh-determinism/rc1 -1636594104014467454 {rows|spiral} OUT/seed.json 24`, with the transformer-off
arm adding the Mobs-Info switch. Compare with `diff-probe.py`, `diff-veincache.py`, `diff-chests.py` (move
`*.veincache.json` out first) and `diff-region-entities.py` on copies of `World/`. Artifacts are in
`~/.cache/gtnh-determinism/rc1-out/{fix,measure,measure-b3}`.

Use a dedicated `PROBE_PORT`. One RC-1 boot in this session died with `FAILED TO BIND TO PORT` because
something briefly held 25565; FML logs `ERRORED`, but no crash report is written.
