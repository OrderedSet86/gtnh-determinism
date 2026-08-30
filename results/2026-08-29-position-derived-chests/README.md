# F10: structure chest contents derived from position

GTNH daily-707, `~/.cache/gtnh-determinism/daily-707`, seed `-777`, radius 8, warm probe.

| jar | md5 | role |
| --- | --- | --- |
| `gtnhdeterminism-v0.5-main.25+884548e365-dirty` | `1b514736ae723c922603f3f384fcebea` | F10 |
| same, three mixins removed from `mixins.gtnhdeterminism.json` | `5a3f1f8a45bac9c1f15472f83e35c536` | control |
| `worldgenprobe-v0.5-main.24+161ea14aab-dirty` | `b6f7f9fbca7d5a13a875474dfd90ff5d` | probe |

The control is the same build with `ChestGenHooksCaptureMixin`, `StructureComponentChestMixin` and
`StructureChestFillMixin` removed, so every other difference between the two jars is zero by
construction. F9 and D1 are present in both.

```bash
PROBE_SEARCH=true ./scripts/warm-probe.sh ~/.cache/gtnh-determinism/daily-707 -777 <order> out-{seed}.json 8
```

`PROBE_JVMFLAGS="-Dprobe.search=true"` does **not** work: `warm-probe.sh` places `PROBE_JVMFLAGS`
before its own explicit `-Dprobe.search="${PROBE_SEARCH:-false}"`, so the later flag wins and the run
silently produces a report with no `search` section. A chest diff against such a report reads
`chests A=0 B=0  existence=0  contents=0  ... ALL SEEDS IDENTICAL`, which is a vacuous pass. Use
`PROBE_SEARCH=true`.

## The fix took effect

`ChestFillContext` counts what it did and the probe logs it at the end of every run, because a fix
that silently does nothing produces the same "0 differences" as a fix that works.

```
[probe][loot] F10 structure chests: 67 refilled, 0 fell back to stock
```

Identical in all four runs. The fallback path — a caller whose item array did not come from a
`ChestGenHooks` we observed — was exercised once during development and named its own culprit:

```
Chest fill for a chest did not come from ChestGenHooks.getItems/getCount — leaving stock's roll.
count=1 captured=[items=true count=false value=0] category=dungeonChest
caller=com.emoniph.witchery.worldgen.ComponentShack.addComponentParts:117
```

Witchery's shack passes a literal count of `1` next to `ChestGenHooks.getItems(dungeonChest, rand)`
rather than calling `getCount(rand)`. A literal is already a constant, so there is nothing to make
deterministic and the contents can still be re-derived; the acceptance rule now requires only that the
item array came from a known table, and keeps a caller-chosen count as-is. After that change the
fallback count is 0.

## Determinism

`scripts/diff-chests.py`, which counts existence, contents and NBT separately:

| comparison | chests | existence | contents | NBT |
| --- | ---: | ---: | ---: | ---: |
| rows vs cols | 124 | 0 | 0 | 0 |
| rows vs spiral | 124 | 0 | 0 | 0 |
| rows vs rows, separate JVMs | 124 | 0 | 0 | 0 |

## The fix changes loot, and moves nothing else

Control versus F10, same seed and same walk order:

```
chests A=124 B=124  existence=0  contents=27  nbt=0
```

27 of 124 chests carry different items and not one chest appeared or disappeared. The other 97 are
Roguelike Dungeons chests, which use their own JSON tables and never touch this code path.

The design claim is that running the stock body first and refilling afterwards leaves the populate
stream untouched, so nothing but chest contents changes. `scripts/diff-probe.py` separates block
hashes from tile-entity hashes, which tests exactly that:

| comparison | chunks differing | blocks-only | te-only | both |
| --- | ---: | ---: | ---: | ---: |
| control vs F10 | 21 / 625 | 2 | 19 | 0 |
| F10 vs F10, separate JVMs | 2 / 625 | 2 | 0 | 0 |

Two chunks differ by blocks in **both** comparisons, so the second row is the noise floor rather than a
finding about F10. They are the same two chunks in both, with the same Y-section histogram:

```
noise floor        : ['-22,21', '-6,19']   y 16-31 x1, y 64-79 x1
control vs F10     : ['-22,21', '-6,19']   y 16-31 x1, y 64-79 x1
attributable to F10: []
```

**Zero blocks move.** The 19 tile-entity-only chunks are the chest contents.

This is warm-mode noise, already documented: a warm daemon moves terrain relative to a cold boot, and
`docs/HANDOFF.md` requires cold runs for any terrain claim. It is unrelated to this change and is not
made better or worse by it.

## Still open

- **Balance certification.** The pool, weights, roll range and weighted-draw algorithm are untouched
  and only the RNG source moves, so the per-chest distribution should be stock's — but that is an
  argument, not a measurement. It needs a Monte-Carlo pass in the shape of `mc=roguelike-loot` /
  `docs/balance/`.
- **Cold-run confirmation.** Everything above is warm. Chests are unaffected by warm reuse (0 diffs
  warm vs cold in earlier work), but the claim should be repeated cold before it goes in the README.
- **Wider corpus.** One seed at radius 8. The 124 chests here are enough to show the mechanism; a
  multi-seed pass is what would bound the fallback rate across mod structures this seed does not
  contain.
