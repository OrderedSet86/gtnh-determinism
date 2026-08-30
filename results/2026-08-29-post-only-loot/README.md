# F9: one loot table for the whole world

GTNH daily-707, `~/.cache/gtnh-determinism/daily-707`, fix jar
`gtnhdeterminism-v0.5-main.23+5aa22c1659-dirty` (md5 `6dda33c8af1e132085d12af923caf51b`), probe
`worldgenprobe-v0.5-main.23+5aa22c1659-dirty` (md5 `3173f15e87d8f2d3926ea5b6d30ae77d`).

```bash
PROBE_JVMFLAGS="-Dprobe.lootcsv=<dir>" \
  ./scripts/warm-probe.sh ~/.cache/gtnh-determinism/daily-707 1 rows /tmp/out-{seed}.json 1
```

## The defect

A cold boot generates chunks inside `MinecraftServer.loadAllWorlds` — the spawn-point search, then a
25x25-chunk preload — and only afterwards fires `FMLServerStartingEvent`, where TooMuchLoot replaces
whole `ChestGenHooks` categories. In 1.7.10 a chest is filled when it is placed, so every chest inside
the preload kept the pre-rewrite table permanently and every later chest used the post-rewrite one.
Ten categories differed.

## The fix

`EarlyLootTables.apply()`, injected at `MinecraftServer.loadAllWorlds` HEAD, runs TooMuchLoot's own
loader before the first chunk exists. `TooMuchLootServerStartingMixin` then suppresses TooMuchLoot's
own later run, because `ChestLootLoader.loadFiles` is idempotent for `OVERRIDE` groups (it `putAll`s
fresh `ChestGenHooks` objects) but not for `ADD` ones, which call `getInfo(category).addItem` against
the live table and would double every added entry. GTNH daily-707 ships 16 groups, all `OVERRIDE`; a
user's XML need not be.

## Measurement

The probe exports the table at three points. `pre` is a lifecycle snapshot at
`FMLServerAboutToStartEvent`. `firstpopulate` is captured on the world's first populated chunk, which
is the only phase that answers "what did the preload actually roll" without depending on where a mod
puts its rewrite. `post` is at `FMLServerStartedEvent`.

Boot log, in order:

```
13:19:41  [probe][lootcsv] chestgenhooks pre: 38 categories, 952 rows
13:19:41  [gtnhdeterminism] TooMuchLoot applied before world load — one loot table for the whole
          world (38 categories cached; villageBlacksmith now rolls 4-11)
13:19:41  [probe][lootcsv] chestgenhooks firstpopulate: 42 categories, 1299 rows
13:19:42  Preparing start region for level 0
13:19:55  [gtnhdeterminism] TooMuchLoot already applied before world load — skipping its duplicate run
13:19:56  [probe][lootcsv] chestgenhooks post: 43 categories, 1302 rows
```

Comparing the exported rows per category (`chestloot-f9.csv`):

| comparison | categories differing |
| --- | ---: |
| `firstpopulate` vs `post` | **0** |
| `pre` vs `firstpopulate` | 10 |

The 10 are `bonusChest`, `dungeonChest`, `mineshaftCorridor`, `pyramidDesertyChest`,
`pyramidJungleChest`, `railcraft:workshop`, `strongholdCorridor`, `strongholdCrossing`,
`strongholdLibrary`, `villageBlacksmith` — the same ten that used to split the world in two. They now
all change before the first chunk.

`WG:PHOTOWORKSHOP` is the one category present at `post` and not at `firstpopulate` (3 rows). That is
class loading, not a table split: `witchinggadgets.common.world.VillageComponentPhotoshop` creates and
fills the category from its static initializer, which runs when the first Photoshop piece is
constructed — and construction precedes the chest fill, so the table is populated by the time it is
read.

The final table is unchanged from stock. Every `post` row is byte-identical to the pre-F9 baseline in
`results/2026-08-29-chestloot/chestloot.csv` (952 `pre` rows and 1302 `post` rows, both exact
matches), so the early `loadFiles` produced the same table TooMuchLoot would have.

## Balance consequence: the big marshmallow stacks are gone

HungerOverhaul injects food into the loot tables before `FMLServerAboutToStartEvent`, and TooMuchLoot
replaces those categories wholesale, so its entries only ever reached chests inside the spawn preload.
Under F9 they reach nothing.

| table | `DraconicEvolution:dezilsMarshmallow` in `dungeonChest` |
| --- | --- |
| pre (HungerOverhaul) | weight 5, stack **1-32** |
| post (TooMuchLoot XML) | weight 2, stack **1-2** |

The same shape holds in `mineshaftCorridor`, `pyramidDesertyChest` and `pyramidJungleChest`. The
20-30-marshmallow stacks that spawn-window chests used to carry no longer occur anywhere.

This is deliberate. TooMuchLoot's XML is the balance the pack intends; the preload exposure was an
accident of boot ordering. Keeping a bug because it helps routing means a future upstream fix breaks
routing silently, so the bug goes. **The marshmallow corpus and the coke% ranking in `seedsearch/`
were derived from the old behaviour and must be re-measured** — `seedsearch/README.md`'s marshmallow
attribution section and `coke-stage1.py`'s `marsh_n`/`marsh_d` scoring both assume the 1-32 stacks
exist.

## Knock-on: the probe's warm-mode preload replication

`restoreLootTables(lootSnapPre, ...)` before a warm recreate existed to reproduce a cold boot's split;
restoring the post table there was the 2.8.4 warm-slot contamination, 17 wrong chests per seed. Under
F9 a cold boot has no split, so restoring `pre` would be that same contamination with the sign
flipped. `lootSnapForPreload()` now detects the fix jar (by the presence of
`com.gtnhspeedrun.determinism.worldgen.EarlyLootTables`) and restores `post` when F9 is active,
logging which it chose.

## Related fix in the same change

Both loot-table restores — the fix jar's F7 and the probe's warm-mode one — copied only
`ChestGenHooks.contents` and left `countMin`/`countMax` alone. `villageBlacksmith` is the only
category whose roll count moves (pre 3-9, post 4-11), so a restore produced a table that never existed
anywhere: the pristine item pool with the mutated roll count. The extra `generateChestContents`
iterations then shifted every later draw in that chunk. Both now snapshot and restore all three
fields.
