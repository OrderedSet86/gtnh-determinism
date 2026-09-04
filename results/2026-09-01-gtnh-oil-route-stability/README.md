# GTNH oil is not route-stable: a whole deposit exists under one walk order and not the other

GTNH daily-707, branch `chest-loot-positional`. Fix jar `e70eb436…` (all determinism fixes active).
Seed `-1297854885530077460`, radius 12 (743 chunks), `rows` against `spiral`.

## Result

| | rows | spiral |
| --- | ---: | ---: |
| BuildCraft oil blocks | 35 875 | **39 050** |
| chunks containing oil | 44 | **49** |

| | |
| --- | ---: |
| oil chunks present only under `spiral` | **5** |
| oil chunks present only under `rows` | 0 |
| oil chunks in both with a different block count | **0** |
| block positions where exactly one arm has oil | **2 497** |

The five chunks are contiguous — `(-17,-18)`, `(-17,-17)`, `(-16,-19)`, `(-16,-18)`, `(-16,-17)` —
and every chunk that has oil in both arms has *exactly* the same number of oil blocks. So this is not
jitter at the edge of a deposit. **An entire oil deposit exists under `spiral` and does not exist
under `rows`.**

That is the same signature as the known vanilla `WorldGenDungeons` defect already recorded in
`seedsearch/README.md`: a whole structure present under one walk order and absent under another.

## Mechanism, and what the evidence does not separate

`OilGeneratorFix` listens on `PopulateChunkEvent.Post` and draws from `event.rand` — the chunk-populate
Random **after** every lake, lava, dungeon, ore and decoration draw in that chunk.

The same diff shows the populate stream is already diverging upstream of oil:

| transition | positions |
| --- | ---: |
| stone → `etfuturum:deepslate` | 4 637 |
| stone → `gregtech:gt.blockstones` | 2 532 |
| stone → `BuildCraft|Energy:blockOil` | 2 301 |
| stone → dirt | 956 |
| stone → gravel | 602 |

16 806 differing block positions across 72 of 743 chunks in total.

So there are two candidate explanations and **this experiment cannot separate them**:

1. Oil is a downstream victim. A route-dependent number of draws upstream (the GregTech ore
   live-terrain read, already held as HANDOFF priority #1) shifts the populate Random's position by
   the time `Post` fires, so the oil gate flips. Oil would then need no fix of its own.
2. `OilGeneratorFix` is independently order-dependent.

Explanation 1 is the more economical reading given the deepslate and `gt.blockstones` differences in
the same chunks, but that is an argument, not a measurement. Settling it means re-running this A/B
with the GT ore defect fixed, or instrumenting the populate Random's draw count at `Post`.

## A retraction

Reporting on this earlier, I said `OilConfig.OilDepostMinDistance` "gates on previously placed
deposits", making it order-dependent state of the same shape as Witchery's `structuresList`. **That is
wrong.** The pack's own config documents it as pure arithmetic:

```
# The minimum distance of 2 Oil-Deposits in chunks. Modulo-Based; A 2 here means an deposit can only
# spawn in chunks that have a number that is a multiple of 2 (Chunknumber * 16 = X/Z coord)
I:OilDepostMinDistance=2
```

There is no cross-deposit state. The only route-dependent input is the `Post` Random.

## Two measurement errors worth recording

**The oil block id is 684, not 2133.** A hand-rolled regex over `level.dat` returned 2133, which is
`chisel:hempcretesand`. Scanning for it found zero oil in 743 chunks and nearly produced the
conclusion "no oil generates here" for a window holding **35 875 oil blocks**. The id was only
corrected by parsing `/FML/ItemData` properly with the NBT reader in `scripts/diff-region-blocks.py`
instead of pattern-matching the compressed bytes.

**A wrong-but-plausible zero is the dangerous failure.** Both errors — the bad id, and an earlier
double-parse of `world_chunks` output that reported 0 distinct block ids in 60 chunks — returned
empty rather than throwing. The double-parse was caught only because "0 distinct block ids" is
obviously impossible; the bad id was not obviously impossible and survived several steps.

## Consequence for the seed search

Oil cannot be used as a stage-0 search criterion, and not because the prefilter lacks a module:
**the target itself is not stable**, so a predicted oil site would not be reproducible in the player's
own world unless they generated chunks in the same order the predictor assumed.

## Reproducing

```sh
for order in rows spiral; do
  PROBE_SEARCH=true ./scripts/run-probe.sh <server> -1297854885530077460 $order out-$order.json 12
  cp -a <server>/World world-$order
done
python3 scripts/diff-region-blocks.py world-rows world-spiral --ids 684
```
