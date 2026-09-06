# F9 corrections: it never ran in singleplayer, and it was nerfing the bonus chest

GTNH daily-707, `~/.cache/gtnh-determinism/daily-707`, fix jar
`gtnhdeterminism-v0.7-main.2+aef8faafdf-dirty` (md5 `f3e2ff44b60164afe33186fa6f9c752a`), probe
`worldgenprobe-v0.5-chest-loot-positional.32+73a973510a-dirty` (md5
`ab2c5a402543bea86f7ef5d010708996`).

```bash
PROBE_JVMFLAGS="-Dprobe.lootcsv=<dir>" \
  ./scripts/warm-probe.sh ~/.cache/gtnh-determinism/daily-707 1 rows /tmp/out-{seed}.json 1
```

Two defects in F9, found by auditing it rather than by a failing measurement. Neither had a symptom
the dedicated harness could produce.

## Defect 1: F9 never fired in singleplayer

`MinecraftServerLootMixin` injects at `MinecraftServer.loadAllWorlds` HEAD.
`net.minecraft.server.integrated.IntegratedServer` **overrides** that method and never calls
`super` — `build/rfg/minecraft-src/.../IntegratedServer.java:58-80` is a full reimplementation
ending in the inherited `this.initialWorldChunkLoad()`, and `javap -p -c` on the patched class shows
every `invokespecial` in the body is a constructor call, with no
`invokespecial MinecraftServer.loadAllWorlds`. Mixin does not propagate an `@Inject` into a subclass
override, so on a client the injected code was simply never executed.

`require = 1` does not catch this: the requirement is satisfied because the injection point *is*
found in `MinecraftServer`. There is no load-time error. `EarlyLootTables.apply()` never ran,
`consumeApplied()` returned false, and TooMuchLoot's own handler ran unchanged — so a singleplayer
world got exactly the pre/post spawn-preload split F9 exists to close.

The bug F9 targets is present in singleplayer identically. `IntegratedServer.startServer()` orders
`handleServerAboutToStart` (:95) → `loadAllWorlds` (:96) → `handleServerStarting` (:98), its
`loadAllWorlds` ends with the inherited 625-chunk `initialWorldChunkLoad()`, and
`FMLCommonHandler.handleServerStarting` delegates to `Loader.serverStarting` with no sided dispatch.
Singleplayer therefore sat on a *consistently* stock split rather than a randomly varying one, which
is why it never read as nondeterminism — but it does not match the corpus, and the split sits
squarely inside the ±12-chunk area a run spawns in.

It was invisible because every harness script boots `java -jar ... nogui` (`scripts/warm-probe.sh:64`,
`run-probe.sh:62`, `probe-queue.sh:46`, `prefilter.sh:141`, `criu-harness.sh:63`). F9 was only ever
measured in the one configuration where it fires.

**Fix:** `IntegratedServerLootMixin`, the same injector against `IntegratedServer`, registered in the
`client` block of `mixins.gtnhdeterminism.json`. No double-application guard is needed — a server
instance is one class or the other, so exactly one injector can fire per start.

## Defect 2: F9 was handing the bonus chest TooMuchLoot's table

The bonus chest is filled during `WorldServer` construction: `WorldServer.java:757
createSpawnPosition` → `:809 isBonusChestEnabled` → `:811 createBonusChest` → `:821
new WorldGeneratorBonusChest(ChestGenHooks.getItems(BONUS_CHEST, rand), ChestGenHooks.getCount(BONUS_CHEST, rand))`.
That is inside `loadAllWorlds` but before `FMLServerStartingEvent`, on both sides — so stock always
fills it from the pre-rewrite table, and it was never part of the spawn-preload split at all.

F9 injects at the head of `loadAllWorlds`, which is upstream of that constructor too, so it had been
handing a one-shot world-creation gift TooMuchLoot's reduced category. The original writeup listed
`bonusChest` among the ten categories it closed without noticing that this one was not a split.

Measured cost of the accident, 16 entries down to 13 (roll count unchanged at 10-10):

| in stock's bonus chest, removed by TooMuchLoot | weight | stack |
| --- | ---: | --- |
| Black Lotus | 1 | 1-1 |
| Codex | 1 | 1-1 |
| Lexica Botania | 7 | 1-1 |
| Broadsword | 10 | 0-1 |
| Starter Hatchet | 5 | 1-1 |
| Starter Pickaxe | 5 | 1-1 |

TooMuchLoot re-adds colour-coded `§fBronze Broadsword`, `§fStarter Hatchet` and `§fStarter Pickaxe`
in their place, so the net loss to a bonus-chest start is Botania's three items and the plain
Broadsword.

**Fix:** `EarlyLootTables` restores the pre-rewrite `bonusChest` after `loadFiles` and hands
TooMuchLoot's version to `TooMuchLootServerStartingMixin`, which runs at exactly the point stock
TooMuchLoot would have applied. The category is therefore pre-rewrite for the bonus chest and
TooMuchLoot's from server start onward, which is stock's behaviour in both windows — `/chestloot`
and anything else reading the category after boot still sees the pack's table.

The restore uses the *original* `ChestGenHooks` object, not `copyLootTable`'s deep copy. An `OVERRIDE`
group `putAll`s a fresh object and leaves the previous one untouched, so restoring it is byte-identical
to stock; the copy is not, because round-tripping an `ItemStack`'s NBT through a new compound reorders
the compound's keys — a first attempt using the copy produced two `firstpopulate` rows for
`TConstruct:pickaxe` and `TConstruct:hatchet` that differed from `pre` in NBT key order alone. `ADD`
and `REMOVE` mutate the live object in place instead, which is detectable by the map entry's reference
being unchanged, and only in that case is the copy the one true pre-rewrite snapshot.

## Measurement

Same three-phase export as `results/2026-08-29-post-only-loot/`. `pre` is a lifecycle snapshot at
`FMLServerAboutToStartEvent`, `firstpopulate` is captured on the world's first populated chunk, `post`
is at `FMLServerStartedEvent`.

Boot log:

```
21:09:30  [probe][lootcsv] chestgenhooks pre: 38 categories, 952 rows
21:09:30  [gtnhdeterminism] bonusChest held at its pre-rewrite table (16 entries rolling 10-10, not 13
          rolling 10-10) — stock fills the bonus chest during WorldServer construction, before
          TooMuchLoot applies
21:09:30  [gtnhdeterminism] TooMuchLoot applied before world load — one loot table for the whole world
          (38 categories cached; villageBlacksmith now rolls 4-11)
21:09:30  [probe][lootcsv] chestgenhooks firstpopulate: 42 categories, 1302 rows
21:09:31  Preparing start region for level 0
21:09:44  [gtnhdeterminism] bonusChest handed over to TooMuchLoot's table (rolls 10-10)
21:09:44  [gtnhdeterminism] TooMuchLoot already applied before world load — skipping its duplicate run
21:09:44  [probe][lootcsv] chestgenhooks post: 43 categories, 1302 rows
```

Against `results/2026-08-29-post-only-loot/chestloot-f9.csv`, comparing every `chestgenhooks` row by
(rolls_min, rolls_max, display_name, weight, stack_min, stack_max, registry_name, meta, nbt):

| comparison | categories differing |
| --- | ---: |
| `pre` vs the F9 baseline's `pre` | **0** of 38 |
| `post` vs the F9 baseline's `post` | **0** of 43 |
| `firstpopulate` vs the F9 baseline's `firstpopulate` | **1** of 42 — `bonusChest`, by design |
| `firstpopulate` vs `post`, this run | **1** of 42 — `bonusChest`, by design |

And the exemption lands exactly where stock puts it:

| check | result |
| --- | --- |
| `bonusChest` at `firstpopulate` == same run's `pre` | **identical** |
| `bonusChest` at `firstpopulate` == pre-F9 baseline's `pre` (`results/2026-08-29-chestloot/`) | **identical** |
| `bonusChest` at `post` == pre-F9 baseline's `post` | **identical** |

So F9's guarantee is intact — every category except the deliberately exempt one is the same table at
the first populated chunk as at server start — and the bonus chest is byte-for-byte what stock hands
it.

`chestloot.csv` here is the full export from this run.

## Client-side binding, confirmed on a real client

The harness is dedicated-only, so this was checked by launching the GTNH client itself —
PrismLauncher instance `GTNH-daily-2026-08-28+707-mmcprism-java17-26`, same pack, same fix jar.
`.minecraft/logs/fml-client-latest.log`:

```
[21:22:05] [Client thread/DEBUG] [mixin/]: Mixing worldgen.IntegratedServerLootMixin from
           mixins.gtnhdeterminism.json into net.minecraft.server.integrated.IntegratedServer
[21:22:05] [Client thread/DEBUG] [mixin/]: mixins.gtnhdeterminism.json:worldgen.IntegratedServerLootMixin
           from mod gtnhdeterminism->@Inject::gtnhdet$lootTablesBeforeAnyChunk(Ljava/lang/String;
           Ljava/lang/String;JLnet/minecraft/world/WorldType;Ljava/lang/String;
           Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V
```

So the `client` block is selected on a client, the mixin is applied to `IntegratedServer`, and the
`@Inject` bound against that class's own `loadAllWorlds`. `InvalidInjectionException` count in the
whole boot: **0**. Since the injector is `require = 1`, a failure to bind would have brought the game
down at class load rather than no-op'ing — it reached the main menu, so it bound.

Worth noting for anyone re-checking: `IntegratedServer` is class-loaded during startup, not at world
load — ArchaicFix and DreamCraft mix into it too — so the "Mixing" line appears in the boot log
without a world ever being opened.

## Client-side runtime, confirmed by loading a world

Same client, world `f9-mixin-test`, loaded twice in one session. Both loads, in order:

```
21:39:47 [Server thread/INFO] [gtnhdeterminism/gtnhdeterminism]: Loot tables reset to load-complete
         state for this world (0 categories were mutated)
21:39:47 [Server thread/INFO] [gtnhdeterminism/]:    bonusChest held at its pre-rewrite table
         (16 entries rolling 10-10, not 13 rolling 10-10) — stock fills the bonus chest during
         WorldServer construction, before TooMuchLoot applies
21:39:47 [Server thread/INFO] [gtnhdeterminism/]:    TooMuchLoot applied before world load — one loot
         table for the whole world (38 categories cached; villageBlacksmith now rolls 4-11)
21:39:50 [Server thread/INFO] [gtnhdeterminism/TML]: bonusChest handed over to TooMuchLoot's table
         (rolls 10-10)
21:39:50 [Server thread/INFO] [gtnhdeterminism/TML]: TooMuchLoot already applied before world load —
         skipping its duplicate run
```

So on a client: F7 restores at `FMLServerAboutToStartEvent`, F9 applies at `loadAllWorlds` HEAD with
`bonusChest` held back, and the changeover to TooMuchLoot's `bonusChest` plus the duplicate-run
suppression both happen at `FMLServerStartingEvent`. That is the same ordering the dedicated harness
produces, and it is what singleplayer never did before this change.

Note when grepping: FML tags these `[gtnhdeterminism/]` because `EarlyLootTables.apply()` runs from a
mixin outside FML event dispatch, and `[gtnhdeterminism/TML]` for the suppressor, which runs inside
TooMuchLoot's handler. Only F7's line, logged from the mod class during an event, gets the
`[gtnhdeterminism/gtnhdeterminism]` form the dedicated logs use. A grep anchored on
`[gtnhdeterminism]:` finds none of them.

One asymmetry visible in the second load of the session: `apply()` caches 38 categories on the first
world and 43 on the second, because five categories (`WG:PHOTOWORKSHOP` and friends) only register
when their classes load during the first world's generation. It has no effect on chest contents —
TooMuchLoot rewrites only the 16 categories its XML names, all of which are present in the first 38 —
but it does mean `lootTableCache`, which backs `/loot reset`, is wider on later worlds in a session.
The dedicated boot world shows the same 38-at-apply, 43-at-`post` split.
