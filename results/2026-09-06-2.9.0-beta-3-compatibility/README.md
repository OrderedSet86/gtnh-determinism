# 2026-09-06 — GTNH 2.9.0-beta-3: nothing moved

**Question asked: does 2.9.0-beta-3 change chest loot or worldgen? Answer: chest loot, not at all.
Worldgen, one GregTech change, and it is persistence-only — a freshly created world resolves the same
ore-vein pattern it did on daily-707, so vein layout for a given seed is unchanged. The fix jar needs
no code change and every mixin still binds.**

> **Chest loot re-verified 2026-09-07 on a dedicated server** with the same seed the rest of
> this project uses (`-1636594104014467454`), both packs on byte-identical fix and probe jars:
> **704 chests compared across two windows, 0 existence / 0 contents / 0 NBT differences**, and both
> packs reproduce the daily-707 prefilter oracle 108/108 slot for slot. See
> results/2026-09-07-beta3-chest-loot-parity. Note that raw NUMERIC item ids do differ between the
> packs (registry allocation is per-world); the probe compares registry names, which is the correct
> basis.
Packs compared: `GT_New_Horizons_2.9.0-beta-3_Java_17-26.zip` against
`GTNH-daily-2026-08-28+707-mmcprism-java17-26.zip`, plus the matching server zips.
Jar under test `gtnhdeterminism-v0.8-main.1+a5efcee3d6-dirty`, md5 `a44cb01777493a5b5f986f71505d6c2f`.
Probe `worldgenprobe-v0.8-main.1+a5efcee3d6-dirty`, md5 `04b51a7f7569004f9a5a006cb060b5fc`.

Two things were done, and the second is what makes this a result rather than a reading:
a class-level diff of both packs, and a measured run on beta-3 itself.

## Part 1 — what the packs differ by

- **242 mod jars on both sides. No additions, no removals.** 46 version bumps (40 in the server pack,
  which omits client-only mods).
- **`mmc-pack.json`, `patches/` and `libraries/` are byte-identical** → Forge, FML and vanilla
  Minecraft are the same build. That covers eleven of this repo's mixins outright: the nine vanilla
  targets (`ChunkPopulateBarrierMixin`, `StructureComponentChestMixin`, `StructureStartPartsMixin`,
  `StructureChestFillMixin`, `SpawnVillagersMixin`, `SpawnerAnimalsMixin`, `EntitySheepMixin`,
  `EntityOcelotMixin`, `EntityHorseMixin`, plus both `loadAllWorlds` loot mixins) and Forge's
  `ChestGenHooks`.
- **`config/` differs in 11 files, all cosmetic**: `CustomMainMenu/*`, `DreamCoreMod.properties` and
  `GTNewHorizons/dreamcraft.cfg` (version string only — `2.9.x (Daily 707)` → `2.9.0-beta-3`),
  `NEI/collapsibleitems.cfg`, `NEI/hiddenitems.cfg`, `txloader/.../version.txt`, and a new
  `gtnh-credits` directory. **`config/TooMuchLoot/loot/`, `config/roguelike_dungeons/settings/loot_*.json`
  and `config/EnhancedLootBags/LootBags.xml` are byte-identical.** No loot table moved.

### Every mixin target, checked

Each of the 46 changed jars was unpacked and diffed at the file level (md5 per entry), and the
resulting changed-class list cross-referenced against every `@Mixin` target in
`fix-build/src/main/java/.../mixins/worldgen/`.

**The only target class touched anywhere in the pack is `gregtech.common.GTWorldgenerator`.**

Unchanged, at the exact versions `fix-build/dependencies.gradle` pins: Thaumcraft `4.2.3.5`,
witchery `0.24.1`, roguelike `1.6.6-GTNH`, RWG `alpha-1.5.2`, lootgames `2.2.14`, TooMuchLoot
`4.3.0-GTNH`, BiomesOPlenty `2308`, ProjRed `4.12.43-GTNH`, MrTJPCore, Forestry `4.11.37`,
HungerOverhaul `jenkins104`.

Three bumps that look relevant and are not:

| jar | bump | why it does not matter |
| --- | --- | --- |
| `etfuturum` | 2.6.57 → 2.6.58 | changed classes are `EtFuturum`, `Reference`, `ModRecipes`, `NEIEtFuturumConfig` and two client loading-screen classes. `EtFuturumLateWorldGenerator`, `BlockCaveVines` and `TileEntityCaveVines` — all three mixin targets — are unchanged |
| `TConstruct` | 1.14.104 → 1.14.108 | tools, smeltery, NEI and WAILA. `tconstruct.world.gen.SlimeIslandGen` unchanged |
| `hodgepodge` | 2.7.191 → 2.7.196 | login-session and BiblioCraft mixins. `MixinSpawnerAnimals_optimizeSpawning` unchanged, so `SpawnerAnimalsMixin`'s priority-1500 override of it still resolves the same way |

`GTNewHorizonsCoreMod` 2.9.53 → 2.9.61 touches `modfixes/oilgen/OilGeneratorFix`, which does generate
world content. Disassembled and diffed with constant-pool indices normalised, the entire change is
`ldc "BuildCraft|Energy"` → `getstatic Mods.BuildCraftEnergy.ID` — the same string via a constant.
Behaviour identical.

## Part 2 — the one real change: GregTech 5.09.54.115 → .133

The only worldgen-relevant PR in the 18-version range is
[GT5-Unofficial#7919](https://github.com/GTNewHorizons/GT5-Unofficial/pull/7919), "Fix permanent
corruption of GregTech_OregenPattern.dat", landed in 5.09.54.125. It adds a `PatternSource` enum, a
format version stamp, name-instead-of-ordinal storage, and a request packet.

`javap -c -p` on every class the fix jar binds to or reads:

| class | verdict |
| --- | --- |
| `GTWorldgenerator$WorldGenContainer` | **disassembly byte-for-byte identical** |
| `WorldgenGTOreLayer` | unchanged (not in the changed-entry list at all) |
| `gregtech.common.ores.OreManager` | unchanged |
| `GTWorldgenerator$OregenPattern` | identical |
| `GTWorldgenerator$CachedOreVein` | identical |
| `GTWorldgenerator` | one change, below |
| `GTWorldgenerator$OregenPatternSavedData`, `$PatternSource` | the persistence rework |

`$WorldGenContainer` being identical is the load-bearing fact: `generateVein(II)V`, both
`resolveVeinPlacement` and `testWorldgenChunkified` call sites with their argument indices, and the
two `Long2ObjectOpenHashMap` call sites are exactly where `GTWorldGenContainerOrePinMixin` expects
them. `WorldgenGTOreLayer` and `OreManager` being unchanged covers
`WorldgenGTOreLayerStoneTypeMixin` (`require = 2`) and `OreManagerVirginDryRunMixin`, and leaves
`LateMixinLoader`'s class-shape gating (`hasClass("gregtech.common.ores.OreManager")`,
`hasClass("gregtech.api.enums.StoneType")`) selecting the same variants.

### Does the vein layout move? No.

The pattern decides the oreseed grid, so if it changed, every vein would move. It does not, for a
freshly created world — which is the only case a world-per-run harness has:

- **.115** `loadData`: `if (worldInfo.getLastTimePlayed() == 0L) oregenPattern = values()[values().length - 1];`
  The enum is `[AXISSYMMETRICAL, EQUAL_SPACING]`, so a new world got **EQUAL_SPACING**.
- **.133** `loadData`: new world takes `pattern = EQUAL_SPACING; source = NEW_WORLD; markDirty()`.

Same pattern. Existing worlds with no `.dat` now fall back to `AXISSYMMETRICAL` *without* persisting
it, which only affects saves predating the EQUAL_SPACING era.

Note the static-initialiser default did change — `.115` initialised the field to `AXISSYMMETRICAL`,
`.133` to `EQUAL_SPACING`. That default is only observable if generation runs before `loadData`, which
is what the second change closes.

### The behavioural delta, and it is an upstream determinism fix

`GTWorldgenerator.generate`:

```java
// .115
if (!world.isRemote && world.provider.dimensionId == 0) OregenPatternSavedData.ensureLoaded(world);

// .133
if (!world.isRemote) {
    World w = world.provider.dimensionId == 0 ? world : DimensionManager.getWorld(0);
    if (w != null) OregenPatternSavedData.ensureLoaded(w);
}
```

Before: ore generated in a non-overworld dimension, in a session where the overworld had never loaded
the pattern, ran against the static default — `AXISSYMMETRICAL`, i.e. **the wrong grid**. After: the
pattern is resolved from the overworld's saved data whichever dimension generates first.

That is a cross-dimension ordering hazard of exactly the kind this repo catalogues, closed upstream.
It is not reachable on an overworld-first route, so it does not invalidate any existing measurement —
but it is one fewer thing for this project to find later.

## Part 3 — measured on beta-3

Boot is the binding proof. Every injector on the GregTech path is `require = 1` (six in
`GTWorldGenContainerOrePinMixin`, three in `OreManagerVirginDryRunMixin`) or `require = 2`
(`WorldgenGTOreLayerStoneTypeMixin`), so a moved target aborts the launch rather than silently
binding nothing — the exact failure mode that made 5.09.54.x dangerous in the first place
(`results/2026-08-28-daily-2.9-compatibility`).

```
[gtnhdeterminism]: GT ore-vein probe: StoneType variant (GT 5.09.54.x or later)
[gtnhdeterminism]: 34 worldgen mixins selected for this pack: [... GTWorldGenContainerOrePinMixin,
                   OreManagerVirginDryRunMixin, WorldgenGTOreLayerStoneTypeMixin ...]
[gtnhdeterminism]: GT ore-vein identity pin (F4d): gtnhdet.orepin=true dims=[0, 7] (whitelist)
[GregTech GTNH]:   Ore veins in this world use the EQUAL_SPACING pattern (NEW_WORLD)
```

All 34 mixins selected and applied; no injection failures. And GregTech's own new log line confirms
the prediction from Part 2 directly: `EQUAL_SPACING`, source `NEW_WORLD`.

### Route stability

Seed `-1636594104014467454`, radius 60 centred on the spawn chunk (9,9), `scripts/run-probe.sh`, cold
JVM per run. Metric is GregTech's own `GTWorldgenerator.validOreveins` — the vein-identity decision
itself, one entry per region — dumped by the probe and compared with the new
`scripts/diff-veincache.py`.

Every entry in the cache is compared, in every dimension. The `dim` column below is the filtered
view; the unfiltered figure is given beside it because the filter is approximate and dropping
regions is the one way this metric can flatter itself.

| beta-3 pair | mix differs | geometry | only in one | unfiltered |
| --- | ---: | ---: | ---: | ---: |
| overworld rows vs rows2 — **launch-noise floor** | 0 / 1764 | 0 | 0 | 0 / 1863 |
| overworld rows vs spiral — route contrast | 0 / 1764 | 0 | 0 | 0 / 1863 |
| Twilight Forest rows vs spiral | 0 / 1766 | 0 | 0 | 0 / 1967 |

Zero on every axis, and the floor run is what makes the other two mean anything: a route contrast of
zero taken without it cannot be distinguished from a metric that stopped measuring.

### The stronger result: beta-3 and daily-707 generate the same veins

Route stability only says beta-3 is self-consistent. The question asked was whether beta-3 *changed*
anything, which needs a cross-pack comparison — same seed, same jars, different pack:

| comparison | mix differs | geometry | only in one |
| --- | ---: | ---: | ---: |
| **daily-707 vs beta-3, both on jar v0.8** (pack is the only variable) | **0 / 1863** | 0 | 0 |

Every vein region, in every dimension, resolves to the same mix, the same generation seed and the
same bounding box on both packs. Ore vein worldgen on 2.9.0-beta-3 is not merely as deterministic as
before — it is the same world.

### Chest loot tables, measured rather than argued

Part 1 shows the loot *configs* are byte-identical, but a table is built from configs plus mod code,
so it was exported and diffed directly. `-Dprobe.lootcsv` dumps the assembled tables — Forge
`ChestGenHooks` (both the pre- and post-TooMuchLoot phases and the first-populate capture),
Roguelike Dungeons, Twilight Forest and EnhancedLootBags. Both packs exported with the identical
v0.8 fix and probe jars, so the pack is the only variable:

| file | rows | differing lines |
| --- | ---: | ---: |
| `lootbags.csv` | 2120 | **0** (md5-identical) |
| `enchantments.csv` | 102 | **0** |
| `item-attributes.csv` | 122 | **0** |
| `chestloot.csv` | 7031 | 6 |

The six are three items, each appearing twice, and the change is confined to the `display_name`
column:

```
- "roguelike",…,"Ultra Low Voltage Fluid Tank","10","1","1","100","0.100000","gregtech:gt.blockmachines",817,…
+ "roguelike",…,"ULV Fluid Tank",             "10","1","1","100","0.100000","gregtech:gt.blockmachines",817,…
```

`registry_name`, `meta`, `weight`, `stack_min`, `stack_max`, `pool_total_weight` and
`pick_chance_per_roll` are identical on all three. This is GregTech moving machine names into lang
files (PRs 7892 and 7895, 5.09.54.120 and .124) — the item you get is the same item with the same
odds. **No loot table entry, weight or roll range moved between the two packs.**

### Side finding, and it is ours not beta-3's

The first cross-pack run compared beta-3 on jar v0.8 against the stored `f4d` daily-707 artifacts,
which were made on jar v0.7 — so pack, fix jar and probe jar all differed at once. That comparison
showed 6 mix and 1 geometry differences out of 1863. Splitting the variables:

| held constant | varied | mix differs | geometry |
| --- | --- | ---: | ---: |
| jars (v0.8) | pack (707 → beta-3) | **0 / 1863** | 0 |
| pack (daily-707) | fix+probe jar (v0.7 → v0.8) | **6 / 1863** | 1 |

**The seven regions are the jar, not the pack.** All seven are dimension 64, Ross128b — a GalaxySpace
planet, outside the ore-pin whitelist (`gtnhdet.orepin.dims`, default `0,7`), so it runs stock
GregTech and its vein identity is trigger-derived by design. They are launch-stable and route-stable
*within* each jar version (rows vs rows2 and rows vs spiral are both 0 unfiltered, which includes
dim 64); v0.8 simply lands on a different stock answer than v0.7 did.

v0.7 → v0.8 is the F9 work — applying TooMuchLoot at `loadAllWorlds` and the bonus-chest exemption —
which changes what happens during server startup, and dimension 64 is loaded and unloaded during
startup on every run. A startup-order shift moving an unpinned dimension's vein triggers is exactly
the failure mode the pin exists to close in dims 0 and 7. It does not affect this pack comparison,
but it should not be filed as noise: see the follow-up task.

## Conclusion

2.9.0-beta-3 changes nothing about chest loot and nothing about worldgen output. The jar needs no
code change, binds identically, and produces the same world from the same seed as daily-707.

## Tooling gap this run closed

The F4d numbers in `results/2026-09-05-gt-ore-canonical-trigger` were produced by an ad-hoc
comparison that was never committed, so they could not be re-derived. `scripts/diff-veincache.py`
now does it, and was validated against the stored F4d artifacts before being trusted here:

| stored pair | mix differs | geometry differs | only in one |
| --- | ---: | ---: | ---: |
| `veins-nof4c` rows vs rows2 (stock, same order) | 0 / 1764 | 0 | 0 |
| `veins-nof4c` rows vs spiral (stock, route contrast) | **143 / 1764** | 7 | 0 |
| `f4d` pin rows vs spiral (overworld) | 0 / 1764 | 0 | 0 |
| `f4d` pin rows vs spiral (Twilight Forest) | 0 / 1766 | 0 | 0 |

It reports three numbers rather than one, because they fail differently: mix (vein identity, the
thing the pin targets), geometry (same mix, moved box), and regions present in only one walk — the
last being invisible to any comparison taken over the intersection.

The region counts differ slightly from the ones quoted in the F4d writeup (1764 vs 1760 overworld,
1766 vs 1728 Twilight Forest). That is an accounting difference in the uncommitted original, not a
decode problem — the dimension decode was checked against the mix names rather than trusted. An
overworld walk splits 1764 dim-0 / 99 dim-64, and all 99 carry `ore.mix.ross128.*`; a Twilight Forest
walk splits 1766 dim-7 / 102 dim-0 / 99 dim-64, each group's mixes matching its dimension.
`validOreveins` is a static GregTech never clears, so a walk in one dimension accumulates entries
from every dimension the server touched.

**Report the unfiltered number.** A dim-0 filter reported the cross-pack comparison as a clean zero
while seven regions were in fact differing one dimension over. Filtering is for attributing a
difference, not for deciding whether there is one.
