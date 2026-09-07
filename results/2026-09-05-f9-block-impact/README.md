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

**`warm-probe.sh` could not measure this flag when this was written, and all arms below are cold
`run-probe.sh` boots for that reason.** `WorldgenProbe.f9Active()` detected F9 by
`Class.forName("…EarlyLootTables")` and never read `gtnhdet.f9`, so a warm arm with the flag off
restored `lootSnapPost` for its replicated preload while a real cold boot in that configuration uses
`lootSnapPre` — the documented 17-wrong-chests-per-seed contamination with the sign flipped, and
invisible to a warm self-test.

> **Fixed 2026-09-06.** `f9Active()` now asks the fix jar via `EarlyLootTables.isActive()`, which
> reports the latched outcome of the last `apply()` — so it also catches the cases where F9 is
> configured on but left the split open anyway (TooMuchLoot absent, `failed`, no loot folder, threw).
> Class presence was never going to be enough: `-Dgtnhdet.f9=false` deliberately keeps the same jar on
> the classpath in both arms.
>
> Measured against the old detection under otherwise identical conditions, seed
> `-1636594104014467454`, radius 6, 625 chunks, beta-3:
>
> | warm run, `-Dgtnhdet.f9=false` | verdict logged | vs cold `f9=off` ground truth |
> | --- | --- | ---: |
> | old class-presence detection | `F9 ACTIVE — post-TML table` (wrong) | 321 / 625 |
> | `isActive()` detection | `F9 inactive — pre-TML table` | 292 / 625 |
>
> The two warm runs differ from each other by **64 / 625 chunks — 40 blocks-only, 13 te-only, 10
> both**. That is the contamination, quantified, and it is the same order as the real cold F9 effect
> (65 / 625): inside the preload the old warm arm was reproducing the *other* arm entirely.
>
> Warm still does not equal cold here — 292 residual, all blocks-only. That gap is present in the
> `f9=on` control too, so it is not this flag. It was chased separately and is real: against a
> cold-vs-cold floor of 5 / 625 and a warm-vs-warm floor of 8 / 625, warm vs cold is 293 / 625, and it
> contradicts `docs/HANDOFF.md:224`. See `results/2026-09-06-warm-vs-cold-terrain`.

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

That is the populate stream shifting, not chest contents being relabelled. What actually moves is
named below — do not read the y0-47 rows as "ores", most of it is dirt/gravel/stone.

Where they are, against the preload (spawn chunk ±12 = chunks -3..21 on both axes):

| | inside preload | outside |
| --- | ---: | ---: |
| effect (80) | **63** | 17 |
| noise floor (5) | 5 | 0 |

The 17 outside are all at `x ∈ [-5,-4]` or `z ∈ [-5,-4]` — one to two chunks past the preload
boundary, which is population spillover: a chunk's populate writes into its `+1` neighbourhood.
Nothing differs far from the preload, which is exactly the predicted shape.

## Persisted blocks — what actually moves, by name

Chunk hashes say *that* a chunk changed, never *what*. Three more cold runs with the walk pinned to
spawn (`PROBE_CX=9 PROBE_CZ=9`, radius 12, so the hashed window is exactly the 625 preload chunks),
saving `World` after each, then `diff-region-blocks.py` over chunks -3..21:

| comparison | differing blocks | chunks |
| --- | ---: | ---: |
| noise floor — F9-on vs F9-on | **63** | 5 |
| effect — F9-on vs F9-off | **28,083** | 53 |

Block ids resolved from the world's own FML registry (`level.dat` → `FML` → `ItemData`, block
entries are the ones prefixed `\x01`), and cross-checked against `PROBE_DUMP` name dumps of two
chunks — the two methods agree exactly on chunk `-1,2` (241 differing positions each).

Top transitions, F9-on → F9-off:

| count | from | to |
| ---: | --- | --- |
| 4692 | `minecraft:stone` | `minecraft:dirt` |
| 4056 | `minecraft:dirt` | `minecraft:stone` |
| 2211 | `minecraft:stone` | `minecraft:gravel` |
| 1927 | air | `BiomesOPlenty:colorizedLeaves1:3` |
| 1871 | `minecraft:gravel` | `minecraft:stone` |
| 1216 | `BiomesOPlenty:colorizedLeaves1:3` | air |
| 1154 | `minecraft:gravel` | `etfuturum:deepslate` |
| 1012 | `etfuturum:deepslate` | `minecraft:gravel` |

67 distinct block ids are involved. By volume this is **dirt/gravel/stone discs relocating**
(ids 1/3/13 touch 30,659 positions between them), the **EtFuturum deepslate/tuff band** shifting
(`2526`/`2530`), and **trees, foliage and tallgrass** (`954` BoP colorizedLeaves1, `913` BoP logs3,
`892` BoP foliage, `18` leaves, `31` tallgrass). `3023` is `gregtech:gt.blockstones` — a stone
variant, not an ore.

### Ore blocks specifically

The seven `gregtech:gt.blockores*` ids (3024, 3034-3039), classified per differing position:

| | ore appeared | ore vanished | ore rehosted (ore both sides, different block/meta) |
| --- | ---: | ---: | ---: |
| noise floor | 0 | 0 | 5 |
| effect | **169** | **305** | 21 |

So ore blocks do move — 474 positions appear or vanish against a floor of zero — but that is **1.7%
of the 28,083**, not the bulk of it, and the net is 136 fewer ore blocks in the F9-on arm over this
window. "Rehosted" is the ore surviving in place while its host stone changes, which is what the
noise floor's 5 are.

**Not established: caves.** Cave carving happens during chunk generation, not population, so it
should be immune to a populate-stream shift; nothing here demonstrates otherwise, and the air-side
transitions are accounted for by vegetation and discs. Earlier drafts of this writeup said "ores and
caves move" — the caves half was inference from the y-histogram, and is withdrawn.

### A bug in `diff-region-blocks.py`, found and fixed here

The script charged a flat 4096 differing blocks whenever a 16-block y-section existed on one side and
not the other. Anvil omits a section that is entirely air, so "absent" means 4096 air blocks, not 4096
differences — a chunk that merely grew or lost a tall tree was scored as wholly different. Chunk
`-1,2` reported **8390** where **241** actually differ, the other 8192 being two one-sided sections of
air-vs-air; the full window reported **56,460** where **28,083** differ. Absent sections are now
compared against air, and the one-sided count is printed rather than silently folded in.

Any earlier conclusion drawn from this script's totals may be inflated by 4096 per one-sided section
and is worth re-deriving — `results/2026-08-28-dirt-gravel-and-the-block-metric/`,
`results/2026-08-28-etfuturum-deepslate-band/`, `results/2026-08-29-witchery-route-stability/` and
`results/2026-09-01-gtnh-oil-route-stability/` all cite it. The inflation is not a clean multiple in
the reported total, so it cannot be spotted by inspection.

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
