# Fix jar 0.5 is launch-deterministic but NOT order-deterministic

**2026-08-03.** Triggered by a field report: routing seed `-1501259159663517643` from the
2.8.4 fmt2 corpus, the Crystal Pick chest at `(-270, 10, -191)` did not exist in a real
singleplayer world (fix jar 0.5, 2.8.4); the Crystal Pick was found instead at
`(-297, 7, -180)`.

## Result

Same seed, same pack (2.8.4 server), same jars as the fmt2 corpus
(gtnhdeterminism-0.5pre `caddf9f0`, worldgenprobe main.20 `4f5a43cd`), radius 15,
`search=true`, cold boots. **Only the probe walk order varies.**

| arm | chests | empty chests | Crystal Pick position |
|---|---|---|---|
| corpus (rows, 2026-07-25, CRIU) | 190 | 49 | `-270, 10, -191` |
| rows (fresh cold boot) | 190 | 49 | `-270, 10, -191` |
| cols | 172 | 16 | **`-297, 7, -180`** |
| spiral | 206 | 23 | **`-297, 7, -180`** |
| spiral2 (spiral repeated) | 206 | 23 | **`-297, 7, -180`** |

Pairwise chest-set comparison (position key; contents compared on `(id, damage, count)`):

| A | B | only A | only B | shared | shared but differing |
|---|---|---|---|---|---|
| corpus | rows | 0 | 0 | 190 | 0 |
| spiral | spiral2 | 0 | 0 | 206 | 0 |
| rows | cols | 123 | 105 | 67 | 16 |
| rows | spiral | 118 | 134 | 72 | 17 |
| cols | spiral | 90 | 124 | 82 | 6 |

**Launch determinism is perfect.** Corpus vs a fresh cold boot 9 days later on the same
order: 0 chest diffs across all 190, and only the documented run-noise on terrain
(`gravel=3, water=2, ores=1` chunks of 1108). `spiral` vs `spiral2`: 0 chest diffs.

**Order determinism is broken.** Three walk orders produce three materially different
worlds. Only 67-82 of ~190 chest positions survive an order change, and the total chest
count itself moves 172 → 206.

Non-chest fields also move with order (chunks differing, of 1108 shared):

| pair | diffs |
|---|---|
| corpus vs rows (control) | `gravel=3, water=2, ores=1` |
| spiral vs spiral2 (control) | `surf=3, gravel=5, ores=1` |
| rows vs cols | `surf=34, sand=6, gravel=151, clay=1, water=68, ores=146, stainedclay=11, populated=2` |
| rows vs spiral | `surf=127, sand=7, gravel=317, clay=2, water=179, ores=356, stainedclay=7` |

`water` and `ores` are documented run-noise, but `surf` (surface digest), `gravel`,
`sand` and `stainedclay` are 1-2 orders of magnitude above their control values, so the
order effect is real and reaches terrain, not just dungeon loot.

**Stable across every order:** `villages`, `witchery`, `spawn`, and biome. Village-piece
routing data is unaffected. (`popseq` differs by construction — it *is* the order.)

## Mechanism (hypothesis, not yet instrumented)

Empty-chest counts track the effect: rows 49 empty / 190 total, spiral 23 / 206. That is
the signature of `PendingSlices` (fix 0.5) materialising dungeon chests **after** the
Roguelike loot rules have already run:

- `TreasureManagerMixin.addItemToAll` forks per chest position — order-independent.
- `TreasureManagerMixin.addItem` → `tcfix$addItemStable` picks ONE chest via
  `rand.nextInt(list.size())` over `getChests(type, level)`. Order-independent only if
  that list has identical membership at loot time.
- 0.5 slicing routes a structure write live when the target chunk's mod-gen already ran
  and buffers it otherwise. Which chests exist (and are registered with the
  TreasureManager) at loot-assignment time therefore depends on walk order — so the
  single-chest pick index moves, and chests applied after loot assignment stay empty.
- `TreasureChestMixin.tcfix$isLive()` returns `true` for a detached (buffered) TE
  because `getWorldObj() == null` ("do not over-filter"), but consults the real world
  block for a live-routed one — the two classes are not equivalent.

The 0.5 commit message claims "chest sets order-independent modulo window-fringe
triggers". This measurement says the residual is not confined to the window fringe:
118 of 190 rows chests are absent under spiral, at every dungeon stratum
(y = 10, 11, 20, 21, 29-33, 40, 41, 50-53).

The terrain-level `gravel`/`sand`/`surf` movement is a separate, unattributed finding
and needs its own bisect.

## Field-report correspondence

The user's real singleplayer world matched `cols` and `spiral` (Crystal Pick at
`-297, 7, -180`, no chest at `-270, 10, -191`), not the corpus's `rows`. A singleplayer
world preloads spawn centre-out and then generates along the player's route, so `rows`
is the least player-like of the three orders — and it is the one every published corpus
uses.

## Consequences for the seed libraries

Deep-structure chest data in **every** published corpus (2.7.4 and 2.8.4, all batches) is
valid only for the probe's `rows` walk. It should not be used to promise a specific
chest's existence, position, or contents to a player. Village, witchery, spawn and biome
data remain trustworthy.

## Reproduce

```sh
export PROBE_JAVA=~/.gradle/jdks/azul_systems__inc_-17-amd64-linux.2/bin/java
export PROBE_SEARCH=true
# clone template-2.8.4 per criu-pool.sh clone_server (remember forge-*.jar — the glob
# does not expand inside [ -e "$T/$f" ], which silently produces an unbootable clone)
PROBE_PORT=25571 scripts/run-probe.sh <dir> -1501259159663517643 rows   out-rows.json   15
PROBE_PORT=25572 scripts/run-probe.sh <dir> -1501259159663517643 spiral out-spiral.json 15
python3 cmpchests.py out-rows.json out-spiral.json
```

Each cold run is ~90 s on this box.
