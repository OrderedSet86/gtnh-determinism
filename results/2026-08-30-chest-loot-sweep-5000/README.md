# 5000-seed chest-loot sweep at radius 60

> **SUPERSEDED 2026-08-30 — these scores cannot be reproduced with the current jars.**
>
> Three later fix-jar changes alter what chests contain, so every score, ranking and `top10` entry below
> describes loot the game no longer generates:
>
> - the loot-table leak fix (`results/2026-08-30-chest-table-leak`), which stopped F10 refilling some
>   chests from a table their caller never asked for;
> - positional roll counts for pieces that carry their own item array
>   (`results/2026-08-30-witchery-positional-chests`);
> - dropping Y from F10's absolute fork (`results/2026-08-30-witchery-circles`), which changes every
>   absolute-fork chest, including `WorldGenDungeons` rooms.
>
> The sweep's *method* is still valid and the seed list is still the first 5000 of
> `random.Random(707)`, so a re-run is a re-run, not a redesign. The recall figures in
> `results/2026-08-30-prefilter-speedup` were measured against this corpus and inherit the same caveat.
>
> Kept for the method and for the tier-1 recall measurement. **Do not quote the item values.**


Stage-0 sweep of 5000 seeds, scored against the revised speedrun value table. Result is
[top10.md](top10.md). No full-generation pass.

GTNH daily-707, repo `d17a685`, server `~/.cache/gtnh-determinism/prefilter/daily707`, probe
`35a73ca2f49196b0e83fe49b6f57e6e2`, fix jar `a1da08af02c2b7e305cfab96f764d30f`.

Seeds are the first 5000 of `random.Random(707)`, the same stream as
`results/2026-08-30-chest-loot-scoring` and `results/2026-08-30-chest-loot-radius60`, so their
seed lists are prefixes of this one and every result is comparable.

## Cost

**2 h 04 min wall for 5000 seeds** (02:52:14 to 04:56:52), run as 5 batches of 1000 in fresh JVMs.
Per batch: 24m34s to 25m15s, so **1.49 s/seed** — well under the 2.7 s/seed the 10-seed test
predicted, which had not amortised its fixed costs. Scoring the 5.2 GB of JSONL takes 2m46s.

Batching was for failure isolation: each 56 s boot costs 0.4% of a 46 min batch, and `sweep.sh`
skips any batch that already has 1000 lines, so a late failure loses at most one. Nothing failed.

## The search saturates at about 1000 seeds

This is the load-bearing result. Best score along the seed stream:

| best of | score | gain |
| ---: | ---: | ---: |
| 10 | 292147 | |
| 100 | 372808 | +80661 |
| 500 | 424574 | +51766 |
| 1000 | 434232 | +9658 |
| 2000 | 434232 | **+0** |
| 5000 | 434232 | **+0** |

**The last 4000 seeds bought nothing** — about 1.5 h of compute for zero improvement. The top 10 is
spread across all five batches (positions 341, 708, 1534, 1622, 3526, 3538, 3635, 3711, 4004, 4806),
so this is not batch bias; the ceiling is simply reached early.

The reason is that widening the radius made the search *easier to saturate*. Score distribution over
5000 seeds:

| | |
| --- | ---: |
| min | 52180 |
| median | 224124 |
| mean | 227453 |
| stdev | 53566 |
| p90 | 298912 |
| p99 | 368144 |
| max | 434232 |

The max is only 1.9× the median and the coefficient of variation is 24%. At radius 15 the score
hinged on a rare event — 71 of 100 seeds had no Roguelike dungeon at all — so more seeds kept
helping. At radius 60 **every seed has a dungeon** (minimum 105 Roguelike chests, median 537,
maximum 1103), the score is an aggregate over hundreds of chests, and per-seed totals concentrate.
Law of large numbers, working against the search.

**If you want more differentiation, change the scoring function, not the seed count.** A 960-block
total rewards mass, which every seed has. Something that rewards *concentration* — best cluster
within 100 or 200 blocks, or a distance-weighted score — would spread the distribution and keep
rewarding a larger sweep. `seedsearch/ingot-hunt.py clusters` already implements a cluster-anchor
scan that could be adapted.

## Results

| rank | seed | score | capped away | roguelike chests | village chests |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | `8887376907815907526` | 434232 | 7100 | 913 | 67 |
| 2 | `-1582571907206479973` | 424574 | 10000 | 968 | 56 |
| 3 | `-5032394761372236186` | 412945 | 8000 | 906 | 37 |
| 4 | `868405088317304842` | 411941 | 6400 | 851 | 29 |
| 5 | `2866585384833469571` | 405023 | 8100 | 1103 | 41 |
| 6 | `-4127691572316635119` | 403562 | 16500 | 864 | 29 |
| 7 | `-7944005064263457986` | 400738 | 6200 | 869 | 28 |
| 8 | `-8122284878085604715` | 400714 | 6600 | 920 | 31 |
| 9 | `9221695188705102932` | 399703 | 6000 | 941 | 27 |
| 10 | `4141519542530654349` | 398953 | 6500 | 841 | 43 |

Winner `8887376907815907526`, spawn `268, 64, 238`, with two Alumite Large Plates inside 500 blocks:
`/tp 353 21 -90` and `/tp 498 20 666`.

Per-seed scores for all 5000 are in [per-seed-scores.csv](per-seed-scores.csv), in stream order, so
the best-of-N curve above can be recomputed for any prefix.

## Your item limits are the binding constraint

Every seed in the top 10 discards 6000 to 16500 points to caps. Across the sweep the caps that bite
are `Alumite Large Plate` (2), `Miner's Backpack` (10), `Potion of Swiftness` (10) and
`Plant Lens` (2). At radius 15 with the earlier table, no cap bound at all at stage 0.

One consequence worth deciding on: because the caps bind on nearly every seed, they compress the
score range and make the ranking *less* discriminating, which contributes to the saturation above.
If the caps are meant to model "what 10 players can actually carry", that is correct behaviour. If
they were meant only to stop one item dominating, raising them would spread the field.

## Value-table coverage

| | |
| --- | ---: |
| item stacks in scope | 54011022 |
| matched by the value table | 11195132 |
| unvalued | 42815890 |
| stacks with no display name | 0 |
| entries that appeared zero times | 30 of 151 |

30 of 151 entries never appeared across 5000 seeds and ~54 M item stacks. **Every one of the 30 is
accounted for; none is a mystery, and only one is plain bad luck.**

| absences | why |
| ---: | --- |
| 25 | **structurally unreachable** — their only loot source cannot put an item in a worldgen chest in this pack |
| 4 | reachable, but from a source stage 0 is blind to (stronghold, mineshaft, `chest4`) |
| 1 | genuinely absent from a source stage 0 does see: `Furnace` (value 1), only in `vn_snowy_house` |

The unreachable 25, which the scorer now flags rather than letting them score a silent zero:

| count | only source | why it cannot appear |
| ---: | --- | --- |
| 18 | roguelike `REWARD` | the loot table is registered but no room that places a REWARD chest is in any room pool this pack uses — see below |
| 3 | `chest1`-`chest4` + roguelike `REWARD` | both dead |
| 2 | pyramid / igloo | RWG never constructs `MapGenScatteredFeature` |
| 2 | `chest1`-`chest4` | LootGames rewards, rolled from an unseeded `Random` at win time |

Headed by `Iron Capped Wooden Wand` (5000), `Item Dislocator` (2500) and `Hang Glider` (2500) — all
roguelike `REWARD`.

### The roguelike REWARD table is dead in this pack

Worth stating plainly because it corrects earlier analysis in this repo, including the plan that
produced these runs.

`Treasure.REWARD` chests are **not** gated behind player action. `DungeonReward.generate` calls
`Treasure.generate(..., Treasure.REWARD, level)` inline and returns, so the chest is placed and
filled during worldgen exactly like every other Roguelike chest.

They never appear because **nothing ever places the room**:

- `Treasure.common`, the pool `getChestType` draws from, is `[TOOLS, ARMOUR, WEAPONS]` only.
- `DungeonRoom.REWARD` exists in the enum and `DungeonReward` is constructible by name, but the only
  bytecode reference to `DungeonRoom.REWARD` is the enum's own switch-map class.
- The mod's builtin `SettingsRooms` registers 15 rooms — BRICK, CAKE, CORNER, CREEPER, CRYPT, ENDER,
  FIRE, NETHER, NETHERFORT, OBSIDIAN, OSSUARY, PIT, PRISON, SLIME, SPIDER — and REWARD is not among
  them.
- None of GTNH's six `rooms_*.json` names it either. `grep -rn REWARD config/roguelike_dungeons/`
  matches only `settings/loot_reward.json`.

The loot *table* is still registered — `SettingsLootRules` maps `Treasure.REWARD` to `Loot.REWARD`,
and `loot_reward.json` defines five levels — which is why `ChestLootExport` captures REWARD rows and
why an EV calculation over the raw tables credits them. That credit is phantom: **3086 of the 25211
EV/chest this repo previously attributed to roguelike was REWARD**. The real figure is 22125.

Measured confirmation: 0 hits for all 18 REWARD-only items across ~2.7 M roguelike chests.

## What this sweep could not see

EV per chest, computed against this value table by `--loot-tables` rather than hardcoded:

| source | seen | EV/chest |
| --- | --- | ---: |
| roguelike | yes | 25211 |
| village pieces | yes | 3369 |
| **stronghold** | no | **1594** |
| chest1-4 | no | 1169 |
| pyramid / igloo | no | 910 |
| vanilla `WorldGenDungeons` | no | 872 |
| mineshaft | no | 323 |

At this radius **stronghold is the largest reachable blind source** — the first ring sits 640-1150
blocks out, inside a 960-block window. It is also the cheapest to add: `ChunkGeneratorRealistic`
instantiates `MapGenStronghold` unconditionally, and `ChunkManagerRealistic.findBiomePosition`
returns `null`, so the biome viability relocation never runs and the three ring positions are raw
arithmetic.

## Files

| file | what |
| --- | --- |
| `top10.md` | the deliverable — top 10 with chests, coordinates and `/tp` |
| `score-5000.txt` | full ranking output and diagnostics |
| `per-seed-scores.csv` | score, uncapped score and chest counts for all 5000, in stream order |
| `seeds-5000.txt` | the seed list |
| `top10-seeds.txt` | the winners |
| `value-table.csv` | the table as supplied |
| `run.sh` | reproduces the sweep and the scoring |

The 5.2 GB `prefilter-0.5-d17a685-gtnhdaily707-5000-chest-loot-r60.jsonl` and the per-batch logs stay in
`~/.cache/gtnh-determinism/sweep-5000/`, outside the Dropbox-synced tree.
