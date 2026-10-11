# `pyramidJungleChest` loot in ordinary roguelike dungeons — MECHANISM FOUND

**Outcome: a Zero Point Module in a roguelike dungeon chest is not an anomaly. Three gates multiply
to produce it, all three confirmed from source and corpus. The pack's scoring tooling called the
`pyramidJungleChest` table unreachable; it is reachable, and `seedsearch/loot-score.py` has been
corrected. `iglooChest` really is unreachable and stays that way.**

The trigger for this investigation: two seeds out of 5,000 put a Zero Point Module
(`gregtech:gt.metaitem.01:32598`, `{GT.ItemCharge:2147483647L}`) in a roguelike dungeon chest.
Neither dungeon was a jungle pyramid, and `MapGenScatteredFeature` never generates under RWG in this
pack, so the structure that owns that loot table does not exist in the world.

| seed | dungeon trigger | chest | stacks |
| --- | ---: | ---: | ---: |
| `3713780301194388147` | chunk `(26, 33)` | `/tp 384 7 588` slot 6 | 10 |
| `2981861848398263952` | chunk `(-28, 1)` | `/tp -490 10 -19` slot 8 | 10 |

Both reproduce byte-identically on 2.9.0-beta-3 (`gregtech-5.09.54.133`, `GTNewHorizonsCoreMod-2.9.61`)
under `gtnhdeterminism-v0.8-main.9+970e4eafdf-dirty`: 637/637 and 508/508 chests identical, 0 content
diffs, spawn and trigger counts unchanged.

## The mechanism

### Gate 1 — a hardcoded `ChestGenHooks` call, gated on `level == 4`

`greymerk/roguelike/treasure/loot/provider/ItemJunk.java:49-61` (tag `1.6.6-GTNH`, verified against
the shipped jar by `javap -c -p`):

```java
if (level > 0 && rand.nextInt(20 / (1 + level)) == 0) {
    if (level == 4 && rand.nextInt(10) == 0) {
        ChestGenHooks hook = rand.nextBoolean() ? ChestGenHooks.getInfo(ChestGenHooks.PYRAMID_JUNGLE_CHEST)
                : ChestGenHooks.getInfo(ChestGenHooks.PYRAMID_DESERT_CHEST);
        ItemStack toReturn = hook.getOneItem(rand);
        if (toReturn != null) return toReturn;
    }
    ChestGenHooks hook = ChestGenHooks.getInfo(ChestGenHooks.DUNGEON_CHEST);
    ItemStack toReturn = hook.getOneItem(rand);
    if (toReturn != null) return toReturn;
}
```

This is the **only** `ChestGenHooks` reference in the entire mod — `grep` over the tree returns
`ItemJunk.java` and nothing else. Level 4 is the deepest level: `Dungeon.java:156-163`,
`getLevel(int y)` returns 4 for `y < 15`.

**The nether rooms are innocent.** The level-4 room pool (`SettingsRooms.java:53-59`) is
`OBSIDIAN, FIRE, NETHERFORT, CORNER×10, NETHER×3, SPIDER`, so nether rooms are a *marker* of depth,
not a cause. `DungeonsNetherBrickFortress.java:130-133` picks an ordinary chest type from
`{ARMOUR, WEAPONS, ENCHANTING, ORE, TOOLS}`, and `DungeonsNetherBrick.java:73` goes through
`Treasure.getChestType`, which returns only `TOOLS/ARMOUR/WEAPONS`. There is no code path anywhere in
the mod that unions loot across chest categories — `Treasure` is only ever a *filter* in
`TreasureManager.getChests(type, level)`.

### Gate 2 — the built-in JUNK rule has to be active

`SettingsLootRules.java:45`:

```java
lootRules.add(null, loot.get(Loot.JUNK, i), i, true, 6);
```

Type `null`, `toEach == true`, 6 draws. `LootRule.process` routes a type-`null` to-each rule to
`TreasureManager.addItemToAll`, which (`TreasureManager.java:92-99`) returns **every** chest on the
level except `Treasure.EMPTY`. So when this rule is live, every non-empty level-4 chest gets 6 JUNK
draws, each able to hit the pyramid branch.

GTNH suppresses it. `config/roguelike_dungeons/settings/loot_all.json` declares
`"overrides": ["LOOTRULES"]`, and all eight `dungeon_*.json` inherit that list —
`DungeonSettings.java:75`, `if (!root.has("overrides")) this.overrides = base.overrides;`.
`SettingsResolver.getSettings:180-186` then skips `new SettingsLootRules()` when assembling
`customBase`.

### Gate 3 — suppression only reaches dungeons whose biome matches a custom setting

`SettingsResolver.getSettings` falls through when no custom setting matches:

```java
if (custom != null) { /* honours overrides */ return new DungeonSettings(customBase, custom); }
if (builtin != null && RogueConfig.getBoolean(RogueConfig.DONOVELTYSPAWN)) return new DungeonSettings(this.base, builtin);
if (this.base.isValid(editor, pos)) return new DungeonSettings(this.base);
```

and `this.base` is built **with** `new SettingsLootRules()` at `SettingsResolver.java:57`. GTNH's
custom dungeons cover only `SANDY, FOREST, SNOWY, JUNGLE, MESA, OCEAN, MOUNTAIN, HILLS, PLAINS,
SWAMP, RIVER`; a dungeon in a biome carrying none of those `BiomeDictionary` tags falls through and
gets the built-in rules back. `doNoveltySpawn=true` in `roguelike.cfg:4`, so the middle branch is
live too — and it also carries `this.base`.

## Evidence

Corpus: `~/.cache/gtnh-determinism/sweep-pair/` — 5,000 seeds, 26,710 dungeons, 2,863,998 chests,
54,822,129 item stacks. Full streaming passes, no sampling.

### Pyramid items are confined to level 4

36 `pyramidJungleChest`-exclusive and 17 `pyramidDesertChest`-exclusive occurrences corpus-wide.
**Every one at y ∈ {7, 9, 10, 11}.**

| y band | chests | 9–15-stack chests | pyrJungle | pyrDesert | dungeonChest |
| --- | ---: | ---: | ---: | ---: | ---: |
| 7 | 208,464 | 14,534 | 20 | 13 | 432 |
| 9 | 5,954 | 338 | 0 | 1 | 11 |
| 10 | 93,461 | 6,239 | 13 | 2 | 177 |
| 11 | 39,355 | 2,558 | 3 | 1 | 79 |
| ≥19 | 2,516,764 | 193,022 | **0** | **0** | 3,395 |

Zero of 193,022 candidate chests at y≥19, against ~300 expected under a level-independent model.
`dungeonChest`-exclusive items appear at levels 1–4 and never at level 0, matching `ItemJunk`'s
`level > 0` gate — an independent confirmation that the same call site is responsible.

### The hit population takes no GTNH config loot at all

Splitting level-4 chests by stack count (1,001 seeds), and asking whether each contains an item that
is modded, in GTNH's roguelike JSON, and in no vanilla chest category:

| L4 chest size | chests | carrying a GTNH-config-exclusive item |
| --- | ---: | ---: |
| 9–15 | 4,809 | **0 (0.00%)** |
| 16+ | 64,819 | 23,275 (35.91%) |

Zero out of 4,809 where ~1,727 would be expected if they drew from the same tables. The small chests
are a structurally separate population — the built-in-rules fallback, about 6.9% of level-4 chests.

### Arrival rate

Per JUNK draw at level 4, walking `ItemJunk.getLootItem` top-down (the `level < 3` gate short-circuits
and consumes no RNG at level 4):

```
(199/200)·(99/100)·(49/50) · (1/4) · (1/10) · (1/2) ≈ 1.207%   chance the draw comes from pyramidJungleChest
```

× p(ZPM within that table) = 1/434 ≈ 3.0e-5 per draw; × 6 draws per chest ≈ **1.8e-4 per level-4
chest in a fallback dungeon**, i.e. roughly 1 in 5,500.

Cross-checks:

- **Weight ratio.** Ancient Gold Coin (w=10) : ZPM (w=1) observed **34 : 2**. Under 36 draws with
  P(ZPM)=1/11, E=3.27, sd=1.72, z=−0.74, P(N≤2)=0.35. Consistent.
- **Implied roll count.** 36 hits ÷ (11/434) ≈ 1,420 pyramid rolls corpus-wide → ≈316 chests ≈ 1.33%
  of the 23,669 level-4 9–15-stack chests. `pyramidDesertChest` independently gives
  17 ÷ (4/425) ≈ 1,806 rolls → ≈401 chests ≈ 1.70%. Same order, as expected for sibling categories.
- **Contrast.** `dungeonChest` (45/1886 exclusive weight, fixed 8 rolls) implies ≈15.5% of the same
  bin — ~10× more common, which is why Mjolnir fragments are ordinary and ZPMs are not.
- **Arborist Frame** (`miscutils:frameArborists`, pyramidJungleChest-only) has **0** observed
  occurrences. It is in the `pre`-phase table (w=24, pool 397) and absent from `post` (pool 434), so
  0 sightings independently pins the runtime draw to the post-registration pool.

**Why it is rare: the gates multiply.** unthemed biome (~7% of dungeons) × level 4 × the 1/40 pyramid
branch × the 1/434 ZPM slot.

### Gate 2/3 measured directly on beta-3

Gate 3 was the one step still inferred. It is now measured, **without a biome lookup** — the probe
records the resolved settings object instead. `RoguelikePrefilter.CapturingDungeon` already captures
the `ISettings` that `SettingsResolver.getSettings` returned, and `ISettings.getOverrides()` exposes
the override list, so `Result.lootRulesSuppressed` reads the gate itself rather than trying to
re-derive the biome that fed it. Emitted per dungeon as `lootrules_suppressed`.

79 seeds on 2.9.0-beta-3 (the 49 corpus seeds holding a pyramid item, plus 30 controls), stage 0 at
radius 60, `gate3/run.sh`. 438 dungeons with resolved settings, 47,937 chests.

| | no pyramid item | has pyramid item |
| --- | ---: | ---: |
| `lootrules_suppressed = true` | 358 | **0** |
| `lootrules_suppressed = false` | 28 | 52 |

**Off-diagonal zero.** Not one pyramid draw in 358 suppressed dungeons. A single hit there would have
falsified the model.

The chest-size signature separates just as cleanly, confirming that the 9–15-stack population *is*
the fallback population rather than merely correlating with it:

| | <9 stacks | 9–15 | 16+ |
| --- | ---: | ---: | ---: |
| `suppressed = true` | 1,461 (3.7%) | 444 (1.1%) | 37,831 (**95.2%**) |
| `suppressed = false` | 0 (0.0%) | 8,157 (**99.5%**) | 44 (0.5%) |

All 53 pyramid-item occurrences are at level 4 and all are in unsuppressed dungeons — reproducing the
corpus total exactly (36 `pyramidJungleChest` + 17 `pyramidDesertChest` = 53). Both ZPM seeds land in
the unsuppressed set: seed `3713780301194388147` dungeon index 5 and seed `2981861848398263952`
dungeon index 0 are the only `false` entries that carry the ZPM.

Raw output: `~/.cache/gtnh-determinism/gate3/` (`all.jsonl`, `crosstab.py`, `run.sh`, batch logs).

## Corrections made

### `seedsearch/loot-score.py`

- `"pyramid / igloo"` split into two groups. **`pyramid` is now reachable** and scored; its note
  records the `ItemJunk` path. **`igloo` stays in `UNREACHABLE_GROUPS`.**
- `iglooChest` was a false positive of category attribution. Its only item not shared with another
  category and not in the roguelike config is `minecraft:stone_axe` — which `ItemTool.java:73`
  hardcodes. `minecraft:iron_shovel` at `ItemTool.java:88` is the same trap for `vn_toolsmith`, and
  `minecraft:enchanted_book` (555,432 occurrences, 78% of all apparent hook stacks) is the same trap
  at scale. Nothing draws from `iglooChest`; `ItemJunk` names only `pyramidJungleChest`,
  `pyramidDesertChest` and `dungeonChest`.
- The `vanilla WorldGenDungeons` note was incomplete for the same reason: the *room* is still
  unscoreable at stage 0, but the *table* is reachable and scored via `ItemJunk` at levels 1–4.
- The `key not in all_qty` guard at the `stuck` computation already carried a note that "of 27 items
  the model called unreachable, 2 — Platinum Ingot and Zero Point Module — turned up in real
  roguelike chests". That observation was correct and is now explained. The guard stays, because its
  general argument holds, but those two items now fall out of `stuck` by the group test rather than
  by having been observed.

Scorer diff against HEAD over the 5,000-seed corpus: exactly the three intended group changes and
Platinum Ingot leaving the unreachable list. Nothing else moved.

### `probe-build/.../ChestLootExport.java`

`captureRoguelike` globbed `loot_*.json` and so silently dropped the inline `loot_rules` carried by
the eight `dungeon_*.json` (biome filler: leaves, vine, sand, snowball, snow, ice, web, tallgrass,
waterlily, deadbush, melon, hardened clay, amethyst shard, BoP gems). Attribution then charged those
stacks to village and igloo hooks that share the same items, inventing hook draws that never
happened. Glob widened to all `*.json`; files without `loot_rules` are already skipped at the parse
step.

Verified by regenerating the dump on beta-3 with the fixed jar:

| | before | after |
| --- | ---: | ---: |
| roguelike distinct `(registry_name, meta)` | 1,010 | **1,033** (+23, 0 lost) |
| roguelike tables parsed | 20 | **28** (+`dungeon_desert/forest/ice/jungle/mesa/mountain/plains/swamp`) |
| roguelike rows | — | 3,001 |

Those 23 items are not a rounding error in the corpus: they account for **9,756,364 of 54,822,129
stacks — 17.8% of every item stack a roguelike dungeon produces**, measured over the full 5,000-seed
sweep. (An earlier estimate of ~5.7M was an undercount; the figure here is a direct count of all five
shards.)

One config file carries a typo key `"ntb"` where `"nbt"` is meant. Noted, not fixed here.

## Dead ends, recorded so they are not re-derived

- `LootSettings`' JSON constructor has no callers (`new LootSettings` appears once, at `Loot.java:48`,
  with the `int` ctor). `LevelSettings` has no `loot` field and `SettingsResolver.java:183` handles
  `case LOOT: break;` as a no-op. **A per-level `"loot"` block in JSON is silently ignored — only
  `loot_rules` has any effect.**
- The `ILoot loot` argument threaded through `Dungeon.java:74` → `LootRuleManager.process` →
  `LootRule.process` is never read (`LootRule.java:102-107`). `SettingsLootRules` calls
  `Loot.getLoot()` itself, so the JUNK provider cannot be swapped out from config.
- **Do not verify gate 3 with a biome lookup.** A trigger-chunk biome gate was built and removed as
  unsound — under RWG the block-level biome is not settled until generation, both `getBiomeGenAt` and
  `getBiomeGenForCoords` return the same wrong answer, and it killed 29% of seeds that demonstrably
  held the feature. See the note above `Prefilter.SURFACE_Y_COLUMNS`.
