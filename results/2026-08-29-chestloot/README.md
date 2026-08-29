# Chest loot tables, GTNH daily 707

Produced by `ChestLootExport` in `probe-build`, enabled with `-Dprobe.lootcsv=<dir>`:

```bash
PROBE_JVMFLAGS="-Dprobe.lootcsv=$PWD/results/2026-08-29-chestloot" \
  ./scripts/warm-probe.sh ~/.cache/gtnh-determinism/daily-707 1 rows /tmp/out-{seed}.json 1
```

Instance was stock: EMT `6368644637a6a61cdb84f8dbcb6bcd82`, TooMuchLoot
`6068a814e8c74aa2eba2b34a5b200576`, `dungeonChest.xml` `a1823f6d68cba253378ffa5424db7e96`.

## Files

| file | rows | contents |
| --- | ---: | --- |
| `chestloot.csv` | 5095 | Forge `ChestGenHooks` (952 pre + 1302 post) and Roguelike Dungeons (2841) |
| `lootbags.csv` | 2119 | EnhancedLootBags, 52 groups |
| `chestloot-pre.csv`, `chestloot-post.csv` | 952 / 1302 | earlier ChestGenHooks-only export, superseded by `chestloot.csv` |

The two systems are in one file with a `source` column. EnhancedLootBags is separate because its
`Chance` is an independent percentage per item, not a share of a weighted pool; putting it in the same
column as `weight` would make both wrong.

## Why ChestGenHooks appears twice

TooMuchLoot replaces whole categories at `FMLServerStartingEvent`. A cold boot generates the spawn
region inside `startServer()`, which runs earlier:

```
01:55:03  [probe][lootcsv] pre
01:55:04  Preparing start region for level 0
01:55:16  [TooMuchLoot]: Overriding loot-table [dungeonChest]
01:55:17  [probe][lootcsv] post
```

In 1.7.10 a chest is filled when it is placed, so the difference is permanent. Chests in the spawn
preload use `pre`; every later chest uses `post`. Ten categories differ; `villageBlacksmith` nearly
doubles, 60 to 118. `bonusChest` is always `pre`, because it is placed at spawn on first world load.

The preload is **25x25 chunks, 400x400 blocks**, centred on the spawn chunk. `MinecraftServer.initialWorldChunkLoad`
loops `k` and `l` from -192 to +192 in steps of 16, which is 25 values on each axis and 625
`loadChunk` calls, so the loaded box is `spawnChunk +/- 12`. A chunk populates only once its 2x2
neighbourhood is loaded, so `+/-11` is the box that certainly rolled `pre` and the `+/-12` ring is the
boundary. An earlier revision of this file said 21x21 / 336x336, which is two rings too small.

`villageBlacksmith` is also the only category whose ROLL COUNT moves, not just its item pool: `pre`
draws 3-9 stacks and `post` draws 4-11. Two loot-table restores copy only `ChestGenHooks.contents` and
leave `countMin`/`countMax` alone, so they reconstruct a table that never existed - see the fix jar's
F7 handler and the probe's `restoreLootTables`.

## Columns

**`chestloot.csv`** — `source, phase, table, category, level, rolls_min, rolls_max, to_each,
display_name, weight, stack_min, stack_max, pool_total_weight, pick_chance_per_roll,
registry_name, meta, entry_class, nbt`

Column order groups the columns by use: which chest, then what drops and how often, then the ids
needed to look an item up.

- `source` — `chestgenhooks` or `roguelike`.
- `phase` — `pre` or `post` for ChestGenHooks; empty for roguelike, which is read from config and has
  no phase.
- `table`, `level`, `to_each` — roguelike only. `table` is the loot file, `level` the dungeon depth.
  A rule covering five levels produces five rows, so the column can be filtered directly.
- `category` — the ChestGenHooks category, or the roguelike chest type.
- A chest draws `rolls_min` to `rolls_max` stacks, each an independent weighted pick.
  `pick_chance_per_roll` is `weight / pool_total_weight`.
- `entry_class` is empty for a plain `WeightedRandomChestContent`. A subclass builds its stack when
  the chest is filled, so its row cannot show a fixed item.

### `to_each`, roguelike only

From `LootRule.process`, which branches on the field directly:

- `true` — `TreasureManager.addItemToAll`: `rolls_min` draws into **every** chest of that type on that
  level.
- `false` — `TreasureManager.addItem`: `rolls_min` draws **in total**, scattered across the chests of
  that type, so most chests get none.

### Roguelike chest types

`category` for a roguelike row is a `greymerk.roguelike.treasure.Treasure` value, and `level` is the
dungeon floor, 0 at the top to 4 at the bottom. A chest type is placed by specific room classes:

| type | rows | placed by |
| --- | ---: | --- |
| `ARMOUR` | 884 | `DungeonsCrypt`, `DungeonObsidian`, `DungeonsNetherBrickFortress` |
| `FOOD` | 621 | `DungeonMess`, `DungeonsWood` |
| `TOOLS` | 436 | `DungeonPyramidSpawner`, `DungeonsBrick`, `DungeonsNetherBrickFortress` |
| `WEAPONS` | 183 | `SegmentTomb`, `DungeonsBrick`, `DungeonsNetherBrickFortress` |
| `MUSIC` | 136 | `DungeonsMusic` |
| `BLOCKS` | 135 | `DungeonStorage` |
| `POTIONS` | 100 | `DungeonLab` |
| `ENCHANTING` | 65 | `DungeonsEnchant`, `DungeonsNetherBrickFortress` |
| `REWARD` | 54 | `DungeonReward` |
| `SUPPLIES` | 45 | `DungeonStorage` |
| `STARTER` | 40 | `DungeonBedRoom`, `HouseTower` |
| `ORE` | 39 | `DungeonPyramidTomb`, `DungeonObsidian`, `DungeonsCreeperDen` |
| `SMITH` | 10 | `DungeonsSmithy` |
| (blank) | 93 | `loot_junk` rules carry no type in the JSON |

`EMPTY` exists in the enum (`DungeonsSmithy`, `DungeonBTeam`) but has no loot rules.

The room mapping comes from scanning which classes reference each enum constant, so treat it as a
strong indication rather than proof. The `Treasure` values, row counts and `to_each` semantics are
read directly from the config and the bytecode.

**`lootbags.csv`** — `group_meta_id, group_name, rarity, min_items, max_items, registry_name, meta,
display_name, amount, random_amount, chance_percent, item_group, limited_drop_count, nbt`

`chance_percent` is an independent roll per entry, not a weight.

Every field in both files is quoted.

## Display names are server-side

Names come from `ItemStack.getDisplayName()` on a dedicated server. GTNH renames some materials
client-side, so a name here can differ from what the client shows. The known case:

`TConstruct:heavyPlate` meta 15 is written as **"Alumite Large Plate"**. In game it reads **Obzinite
Large Plate**, because `GregTech.lang` sets `S:Material.alumite=Obzinite`. Search the CSV for the
internal name.

## Entries that cannot drop

Six rows name an id that does not resolve in this build. They still hold weight and still consume a
roll, so they are blanks rather than absences.

| source | category | item id | weight | share |
| --- | --- | --- | ---: | ---: |
| chestgenhooks | `strongholdLibrary` | `miscutils:frameVoid` | 23 | 9.5% |
| chestgenhooks | `chest1` | `harvestcraft:pamvanillaSapling` | 20 | 2.4% |
| chestgenhooks | `strongholdLibrary` | `IguanaTweaksTConstruct:wearableBucket` meta 3 | 1 | 0.4% |
| chestgenhooks | `villageBlacksmith` | `IguanaTweaksTConstruct:wearableBucket` meta 0 | 1 | 0.1% |
| roguelike | `ARMOUR` | `tinkersdefense:Heater` | 1 | |
| roguelike | `ARMOUR` | `Natura:natura.armor.immpjerkin` | 1 | |

A further 45 roguelike rows show `enchanted_book`. That is a generator, not an item id — roguelike
builds a random enchanted book at fill time. The rows are kept so the pool weights stay correct.

Whether an unresolvable entry yields nothing or throws during chest population is **not verified**.

## Coverage

Covered: Forge `ChestGenHooks`, Roguelike Dungeons, EnhancedLootBags.

Not covered: LootGames (`config/lootgames/`), Thaumcraft loot bags
(`WeightedRandomLoot.lootBag{Common,Uncommon,Rare}`), and any mod that fills a chest from its own
code without a table.

Witchery needs no separate export. Its stone-circle refilling chest
(`BlockRefillingChest$TileEntityRefillingChest`) reads `dungeonChest`, and `WitcheryComponent` reads
`mineshaftCorridor`. Both are already in `chestloot.csv`.
