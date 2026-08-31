# Rarity-normalised value tables

Rescales the value table by measured occurrence rate, so that common bulk items stop dominating the
score. Built from the 5000-seed radius-60 corpus in this directory.

```
normalised_value = original_value / mean_appearances_per_seed
```

Items that never appeared are dropped: **121 of 151 kept, 30 dropped**.

Two tables are provided. Both achieve the stated goal; they differ in how much they trust a small
sample.

| file | rate estimate | use |
| --- | --- | --- |
| `value-table-normalised.csv` | `count / 5000` | exactly as requested |
| `value-table-normalised-k200.csv` | `(count + 200) / 5000` | **recommended** |

## It works: Redstone goes from 7% of all score to 0.01%

Share of total score across all 5000 seeds, per item:

| scale | Redstone share |
| --- | ---: |
| original | 7.04% |
| normalised, either variant | **0.01%** |

The property that makes this interpretable: after normalisation each item's *expected* contribution
per seed equals its original stated value. An average seed scores about the sum of the table's
values — 44486 for the 121 surviving items, against a measured median of 39537. What a seed's score
now measures is how far above or below average it runs on each item, weighted by how much you said
you care.

## One premise was off by 10×

The worked example was "alumite plate is 0.1 appearances on average, so 10000 / 0.1 = 100k".
Measured over 5000 seeds at radius 60, **Alumite Large Plate averages 1.07 per seed** — 5368
sightings. It normalises to **9314**, essentially unchanged from 10000. It is a near-coin-flip, not
a jackpot.

## The raw version is a lottery on four items

`value / rate` is unbounded as the rate falls, so an item seen twice in 5000 seeds gets an enormous
multiplier off a two-sample estimate. Measured on the raw table:

| item | sightings in 5000 seeds | normalised value |
| --- | ---: | ---: |
| Zero Point Module | 2 | 250000 |
| Bronze Shovel | 23 | 54350 |
| Block of Steel | 106 | 47170 |
| Platinum Ingot | 67 | 37310 |
| Division Sigil | 772 | 16190 |
| Alumite Large Plate | 5368 | 9314 |

A 100-point item seen twice outranks the 10000-point item you actually care about. The consequence
is not theoretical — **79.4% of the top-10 score comes from those four low-sample items**, and the
two seeds that happen to contain a Zero Point Module land at ranks 5 and 7.

## The fix: smooth the rate

`rate = (count + k) / seeds`. `k` is pseudo-sightings; it bounds the largest multiplier any item can
earn at `seeds / k`.

| k | mean share of top-10 score from items seen <100 times | top item by normalised value |
| ---: | ---: | --- |
| 0 | 79.4% | Zero Point Module (2 seen) |
| 1 | 77.9% | Zero Point Module (2 seen) |
| 10 | 74.8% | Block of Steel (106 seen) |
| 50 | 55.5% | Block of Steel (106 seen) |
| **200** | **31.7%** | Block of Steel (106 seen) |
| 1000 | 0.0% | Alumite Large Plate (5368 seen) |

`k = 200` caps any item at 25× its stated value and still ranks by rarity — Platinum Ingot (9363)
sits above Alumite Large Plate (8980). `k = 1000` over-smooths: the rarity signal is gone and the
table is close to the original. Pick `k` by how much of the score you are willing to have decided by
items with double-digit sample sizes.

Head of the `k = 200` table:

| normalised | was | sightings | item |
| ---: | ---: | ---: | --- |
| 16340 | 1000 | 106 | Block of Steel |
| 12860 | 2500 | 772 | Division Sigil |
| 9363 | 500 | 67 | Platinum Ingot |
| 8980 | 10000 | 5368 | Alumite Large Plate |
| 6378 | 1000 | 584 | Hopper Cart |

## Effect on the ranking

| scale | median score | max | max/median |
| --- | ---: | ---: | ---: |
| original | 224124 | 434232 | 1.94 |
| normalised k=0 | 39537 | 399862 | 10.11 |
| normalised k=200 | 37037 | 140731 | 3.80 |

Normalisation roughly doubles the spread at `k = 200`, which is the point — the original scale was
so compressed that the 5000-seed search saturated after 1000 seeds. A wider spread means a larger
sweep can keep paying.

Top-10 overlap between scales:

| | shared seeds |
| --- | ---: |
| original vs k=0 | **0** |
| original vs k=200 | **0** |
| k=0 vs k=200 | 6 |

Normalisation completely rewrites the answer. No seed from the original top 10 survives into either
normalised top 10, so the earlier `top10.md` is not a subset of this — it answers a different
question.

Rankings: [score-5000-normalised.txt](score-5000-normalised.txt) and
[score-5000-normalised-k200.txt](score-5000-normalised-k200.txt).

## Caveats

**The rates are stage-0 rates at radius 60.** They come from Roguelike dungeon and village chests
only. An item that also drops from strongholds, mineshafts or vanilla dungeon rooms has its true
rate understated here, so normalisation over-values it. The four blind sources are listed in
README.md.

**A rate measured on 5000 seeds is still an estimate.** Items with double-digit sightings have wide
confidence intervals; that is what `k` is compensating for, not fixing. Re-deriving the table on a
second, disjoint 5000-seed sweep and comparing would measure how stable these values actually are.

**The scorer now accepts float values** (`load_values`), because the normalised range spans eight
orders of magnitude — Redstone lands at 0.0016 — and no integer scale holds both ends.
