# 2.9.0-beta-3 chest loot is identical to daily-707, on a dedicated server

**Outcome: zero differences. 704 chests compared across two windows — existence 0, contents 0,
NBT-only 0 — and both packs reproduce the daily-707 prefilter oracle exactly, 108/108 chests with
contents matching slot for slot.**

Seed `-1636594104014467454`. Dedicated server on both sides, which is the multiplayer case: the
probe runs a headless `DedicatedServer`, not an `IntegratedServer`, so none of the singleplayer-only
code paths (e.g. F9's `loadAllWorlds` override) are in play.

## Conditions held identical

Everything that could confound a cross-pack comparison was pinned first:

| | value |
| --- | --- |
| fix jar, both packs | `gtnhdeterminism-v0.8-main.3+5f5f73299a-dirty` md5 `170aad0a866639ab30ddfbb9a8141658` |
| probe jar, both packs | `worldgenprobe-v0.8-main.3+5f5f73299a-dirty` md5 `df703010673e7d4b081be2b6cc804419` |
| walk order | `rows` |
| `PROBE_SEARCH` | `true` |

The jars matter: beta-3 was still carrying `gtnhdeterminism-v0.8-main.1+a5efcee3d6-dirty` (pre-dating
the `getBiome` placement fix) and `worldgenprobe-v0.8-main.2`. Comparing against daily-707's newer
pair would have measured the jar difference, not the pack difference. Both were redeployed before any
arm was run.

This run includes the `WorldEditorMixin.getBiome` fix
(results/2026-09-07-roguelike-placement-escape). Without it, dungeon placement is route-dependent and
a cross-pack comparison would be measuring noise.

## Results

`scripts/diff-chests.py`, one directory per arm:

| window | chests | existence | contents | NBT-only | verdict |
| --- | ---: | ---: | ---: | ---: | --- |
| dungeon-covering box, centre chunk (-61,28) r24 | 475 vs 475 | **0** | **0** | **0** | ALL SEEDS IDENTICAL |
| spawn-centred r24 | 229 vs 229 | **0** | **0** | **0** | ALL SEEDS IDENTICAL |

Against the **daily-707 prefilter oracle** (the `loot-1636594104014467454.csv` rows for the reference
dungeon, trigger `/tp -664 100 840`), comparing item id, damage, count per slot:

| arm | chests present | missing | contents mismatch |
| --- | ---: | ---: | ---: |
| daily-707 full-gen | 108 / 108 | 0 | **0** |
| beta-3 full-gen | 108 / 108 | 0 | **0** |

108 rather than 114 because six of that dungeon's chests carry no predicted item rows; the 114 figure
elsewhere counts chest positions, this one counts chests with contents to compare.

## One caveat that would have produced a false positive

**Numeric item ids differ between the packs.** The same chest slot holds numeric id `10074` on
daily-707 and `10075` on beta-3 — registry allocation is per-world, not stable across pack versions.
A comparison keyed on numeric ids would report a spurious difference on that slot.

The probe's `dumpInventory` emits **registry names** (`"id": "Thaumcraft:ItemResource"`,
`"minecraft:emerald"`, `"gregtech:gt.metaitem.01"`), so `diff-chests.py` compares names and the
numeric allocation is invisible to it. That is the correct basis for a cross-pack comparison, and it
is why the earlier hand-inspection of raw NBT (which reads numeric ids) showed a one-slot difference
that is not a real one.

## Scope

Two windows on one seed. This says beta-3 and daily-707 agree on chest loot for this seed under a
dedicated server; it is not a multi-seed equivalence claim. It is consistent with
`results/2026-09-06-2.9.0-beta-3-compatibility`, which found `config/TooMuchLoot/loot/`,
`config/roguelike_dungeons/settings/loot_*.json` and `config/EnhancedLootBags/LootBags.xml`
byte-identical between the packs and roguelike itself unchanged at `1.6.6-GTNH`.

## Reproducing

```
SEED=-1636594104014467454
scripts/build-jar.sh fix   --deploy <beta3-dir> <daily707-dir>
scripts/build-jar.sh probe --deploy <beta3-dir> <daily707-dir>

PROBE_SEARCH=true PROBE_CX=-61 PROBE_CZ=28 \
  scripts/run-probe.sh <pack-dir> $SEED rows <dir>/seed-$SEED.json 24
PROBE_SEARCH=true \
  scripts/run-probe.sh <pack-dir> $SEED rows <dir>/seed-$SEED.json 24

python3 scripts/diff-chests.py <d707-dir> <beta3-dir>
```

Move `*.veincache.json` out of each arm directory first — `diff-chests.py` globs `*.json` and dies on
that sidecar with `AttributeError: 'list' object has no attribute 'get'`.

Committed: the four arms as `*.chests.json` (full chest list with item stacks) plus each arm's
stamped `*.provenance.json`.
