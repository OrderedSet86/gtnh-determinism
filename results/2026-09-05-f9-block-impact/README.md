# F9 moves blocks, and that closes HANDOFF item 12

GTNH daily-707, `~/.cache/gtnh-determinism/daily-707`, seed `-1636594104014467454`, spawn chunk
`(9,9)`. Fix jar `gtnhdeterminism-v0.7-f9-singleplayer-bonuschest.3+d3a232616f-dirty` (md5
`c831f5103d41967a2a5a7009204e422b`), probe `worldgenprobe-v0.5-chest-loot-positional.32+73a973510a-dirty`
(md5 `ab2c5a402543bea86f7ef5d010708996`).

Two questions, one experiment. F9 had been documented as a loot-table change; nobody had measured
whether it moves blocks. And HANDOFF item 12 recorded a probe-server-vs-client chest divergence
blamed on "the F1 family (registration/iteration order)". Both are answered by toggling one flag.

## The switch

`-Dgtnhdet.f9=false` makes `EarlyLootTables.apply()` return immediately, leaving `consumeApplied()`
false so TooMuchLoot's own `FMLServerStartingEvent` handler runs untouched — stock ordering, stock
spawn-preload split. Same jar in both arms; the mixins still load and still bind at `require = 1`, so
a binding failure is loud on both sides rather than silently turning one arm into a no-op. Same
reasoning as `gtnhdet.orepin`.

Verified: with the flag off, `firstpopulate` is 38 categories / 952 rows — byte-identical to `pre` —
and TooMuchLoot parses its XML after `Preparing start region`. With it on, `firstpopulate` is 42 /
1302. Every run below was checked for `F9 disabled by -Dgtnhdet.f9=false` (expect 1 in arm B, 0 in
arm A) and `TooMuchLoot applied before world load` (the reverse) before any number was read.

**`warm-probe.sh` cannot measure this flag.** `WorldgenProbe.f9Active()` detects F9 by
`Class.forName("…EarlyLootTables")` and never reads `gtnhdet.f9`, so a warm arm with the flag off
would restore `lootSnapPost` for its replicated preload while a real cold boot in that configuration
uses `lootSnapPre` — the documented 17-wrong-chests-per-seed contamination with the sign flipped, and
invisible to a warm self-test. All arms here are cold `run-probe.sh` boots.

## Blocks

Three cold runs, same seed, same `rows` walk order, radius 12, `PROBE_TEDETAIL=true` on all three.
994 chunks compared (`diff-probe.py` merges 369 `spawnextra` chunks into the main window).

| comparison | chunks differing | blocks-only | te-only | both |
| --- | ---: | ---: | ---: | ---: |
| **noise floor** — F9-on vs F9-on, separate cold boots | **5 / 994 (0.5%)** | 4 | 0 | 0 |
| **effect** — F9-on vs F9-off | **80 / 994 (8.0%)** | 54 | 13 | 12 |

Four of the five noise-floor chunks reappear in the effect set, so **~76 chunks are attributable to
F9** — sixteen times the floor. This is not a marginal signal.

The Y-section histogram of block noise spans the whole column, not just chest level:

```
y   0-15   ####################################  36
y  16-31   ############################################  44
y  32-47   #########################################  41
y  48-63   ####################################################  52
y  64-79   #####################################################  53
y  80-95   ######################  22
y  96-111  ##############  14
y 112-127  ###  3
```

Ores and caves at y0-47 move. That is the populate stream shifting, not chest contents being
relabelled.

Where they are, against the preload (spawn chunk ±12 = chunks -3..21 on both axes):

| | inside preload | outside |
| --- | ---: | ---: |
| effect (80) | **63** | 17 |
| noise floor (5) | 5 | 0 |

The 17 outside are all at `x ∈ [-5,-4]` or `z ∈ [-5,-4]` — one to two chunks past the preload
boundary, which is population spillover: a chunk's populate writes into its `+1` neighbourhood.
Nothing differs far from the preload, which is exactly the predicted shape.

## Mechanism

F10 does not insulate against this and was never meant to. `StructureChestFillMixin` injects at
`@At("RETURN")` and re-rolls from a separate `new Random(fork)`; the stock body has already run and
already taken its draws off the chunk's shared populate `Random`. Those draws are a function of the
live table:

- loop bound is `ChestGenHooks.getCount(rand)` — `villageBlacksmith` goes `3 + nextInt(6)` to
  `4 + nextInt(7)`
- item index is `nextInt(getTotalWeight(items))` — 60 entries vs 118, different bound, different item
  for the same stream state
- slot writes per iteration are `stacks.length`, which is item-dependent
  (`ChestGenHooks.java:118-126`) — the HungerOverhaul 1-32 vs 1-2 marshmallow case

This repo already stated the mechanism, for F7: *"The extra `generateChestContents` iterations then
shifted every later draw in that chunk."* Same field, same category, same direction.

The `21/625 chunks, blocks-only 2, attributable []` result in
`results/2026-08-29-position-derived-chests/` is **about F10 only** — that writeup says at line 13
that F9 was present in both arms, so it cancelled out. Its "zero blocks move" must not be read as a
statement about F9.

## Chests

`diff-chests.py`, same two arms, same walk order:

```
chests A(F9 on)=129  B(F9 off)=130   existence=3  contents=26  nbt=0
```

**Existence differences are the tell.** Three chests exist in one arm and not the other —
`(230,40,101)` only with F9 on, `(189,40,325)` and `(228,40,104)` only with F9 off. Chests appearing
and disappearing is structure placement moving, which no amount of loot-table relabelling can do.

## HANDOFF item 12: closed, and the original attribution was wrong

Item 12 recorded seed `-1636594104014467454`, chest `(43,63,27)`, piece
`TaigaStructures$TaigaWeaponsmith1`, category `villageBlacksmith`: probe server drew 3 stacks, the
user's PrismLauncher client drew 7. It concluded *"That points at the F1 family
(registration/iteration order) rather than at the chest fork."*

Both sides reproduce exactly on a dedicated server by toggling `gtnhdet.f9`:

| | stacks | contents |
| --- | ---: | --- |
| **F9 on** (fixed) | 3 | Thaumium Ingot x3 (slot 18), Apple x3 (19), Steel Axe x1 (22) |
| **F9 off** (stock ordering = old singleplayer) | 7 | Coal Coke x15 (slot 1), Block of Steel x1 (6), Bread x3 (8), Gold Ingot x3 (14), Coal Coke x7 (18), Crowbar x1 (19), Iron Pickaxe x1 (22) |

The F9-on column is item-for-item the probe server's recorded draw. The F9-off column is
item-for-item the client's recorded draw, in the same order item 12 lists them.

So the cause was never registration order. The client had no F9 at all — `IntegratedServer` overrides
`loadAllWorlds` without calling `super` (see `results/2026-09-05-f9-singleplayer-bonuschest/`) — so it
rolled that chest from the pre-TooMuchLoot table while the probe rolled it from the post table. Chunk
`(2,1)` against spawn chunk `(9,9)` is offset `(-7,-8)`: inside the preload, the only region where the
two tables differ. And `villageBlacksmith` is the one category whose roll count moves across the
split, which is why the stack *count* differed rather than just the items.

Item 12's own recommended next step — dump `villageBlacksmith` `ChestGenHooks` on both sides and
compare — would have found it: 60 entries rolling 3-9 versus 118 rolling 4-11.

`ENV_DEPENDENT = {"villageBlacksmith"}` in `seedsearch/loot-csv.py` was masking this and has been
emptied.

## What this means

- The prefilter and the seed corpus were always right *for an F9 world*: `VillageChestPrefilter`
  reads `ChestGenHooks.getInfo` unconditionally from `FMLServerStartedEvent`, i.e. the post table
  everywhere, and `loot-score.py` says so. Singleplayer was the wrong side. No regeneration needed.
- Singleplayer worlds generated before the `IntegratedServerLootMixin` fix are stale inside spawn ±12
  for **terrain as well as loot** — ~76 chunks on this seed, including ore at y0-47.
- F9's write-up in `README.md` and `docs/HANDOFF.md` described it as loot-only. Corrected.

## Reproduce

```bash
SRV=~/.cache/gtnh-determinism/daily-707; SEED=-1636594104014467454
./scripts/run-probe.sh $SRV $SEED rows /tmp/f9on-a.json 12                       # arm A
_JAVA_OPTIONS=-Dgtnhdet.f9=false ./scripts/run-probe.sh $SRV $SEED rows /tmp/f9off-b.json 12   # arm B
./scripts/run-probe.sh $SRV $SEED rows /tmp/f9on-a2.json 12                      # noise floor
python3 scripts/diff-probe.py /tmp/f9on-a.json /tmp/f9on-a2.json
python3 scripts/diff-probe.py /tmp/f9on-a.json /tmp/f9off-b.json
```

`run-probe.sh` does not forward `PROBE_JVMFLAGS`, hence `_JAVA_OPTIONS`. For the chest arms add
`PROBE_SEARCH=true`, which also re-centres the walk on spawn; the block arms above leave the walk
centred on the origin, with the preload reached through `spawnextra`. Classification above is by
absolute chunk coordinate, so the centre choice does not affect it.

Artifacts here: `diff-noise-floor.txt`, `diff-f9-on-vs-off.txt`, `diff-chests.txt`,
`chest-43-63-27.json`.
