#!/usr/bin/env python3
"""Balance equivalence for GT ore VEIN MIXES, from probe vein-cache dumps.

Usage: vein-balance.py <stock-dir> <fixed-dir> [--bound 0.10] [--reps 4000] [--dim 0]

Each directory holds one `seed-<seed>.json.veincache.json` per seed — GregTech's own
`GTWorldgenerator.validOreveins`, i.e. the mix each vein region actually resolved to.

WHY THIS EXISTS INSTEAD OF balance-report.py's vein rows. That tool counts big-ore TILE ENTITIES per
region, and on GT 5.09.54.x worldgen ores are plain blocks with no tile entities — so its vein table
comes back with zero entries and reports "0 FAIL", which reads exactly like a pass. searchlib.py
documents the trap on `has_ore_census`. Running it against a 5.09.54 pack and reporting the headline
"Total FAIL rows: 0" would be a false pass over an untested metric.

The vein cache is a better sample anyway: it is the vein IDENTITY decision itself, one entry per
region, rather than a proxy count of the blocks that decision later produced.

Statistical design is copied deliberately from balance-report.py so verdicts mean the same thing:
the resampling unit is the SEED (all of that seed's regions move together, respecting within-world
correlation and the pairing between arms), 4000 replicates, and

  PASS          95% bootstrap CI on the rate ratio inside [1-bound, 1+bound]
  FAIL          99.9% CI entirely outside it
  inconclusive  everything else — usually too few observations to bound the difference

Cross-seed contamination is checked, not assumed: every cache key is `(worldSeed << 16) ^
(dim << 56 | osX << 28 | osZ)`, so XORing the file's own seed back out must yield the requested
dimension. Entries that do not are counted and reported rather than silently included — warm
multi-seed batches share one JVM and `validOreveins` is a static that GregTech never clears.
"""
import argparse
import collections
import glob
import json
import os
import re
import sys

import numpy as np

MASK64 = (1 << 64) - 1


def load_arm(directory, dim):
    """seed -> Counter(mix -> regions), plus (kept, dropped) entry counts."""
    per_seed, kept, dropped = {}, 0, 0
    for path in sorted(glob.glob(os.path.join(directory, "**", "seed-*.veincache.json"), recursive=True)):
        m = re.search(r"seed-(-?\d+)\.", os.path.basename(path))
        if not m:
            continue
        seed = int(m.group(1))
        base = (seed << 16) & MASK64
        c = collections.Counter()
        for e in json.load(open(path, encoding="utf-8")):
            d = (((e["seed"] & MASK64) ^ base) >> 56) & 0xFF
            if d >= 0x80:
                d -= 0x100
            if d != dim:
                dropped += 1
                continue
            kept += 1
            layer = e.get("layer")
            if layer:
                c[layer] += 1
        per_seed[seed] = c
    return per_seed, kept, dropped


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("stock_dir")
    ap.add_argument("fixed_dir")
    ap.add_argument("--bound", type=float, default=0.10)
    ap.add_argument("--reps", type=int, default=4000)
    ap.add_argument("--dim", type=int, default=0)
    ap.add_argument("--min-count", type=int, default=5)
    args = ap.parse_args()

    S, ks, ds = load_arm(args.stock_dir, args.dim)
    F, kf, df = load_arm(args.fixed_dir, args.dim)
    seeds = sorted(set(S) & set(F))
    if not seeds:
        sys.exit("no seeds present in both arms")

    keys = sorted(set().union(*S.values()) | set().union(*F.values()))
    CS = np.array([[S[s].get(k, 0) for k in keys] for s in seeds], float)
    CF = np.array([[F[s].get(k, 0) for k in keys] for s in seeds], float)
    # Exposure = regions carrying any vein, per seed. Rate is "regions of this mix per region".
    DS = CS.sum(axis=1)
    DF = CF.sum(axis=1)

    rng = np.random.default_rng(20260905)
    W = rng.multinomial(len(seeds), np.full(len(seeds), 1.0 / len(seeds)), size=args.reps).astype(float)
    cs, cf = W @ CS, W @ CF
    ns, nf = W @ DS, W @ DF
    with np.errstate(divide="ignore", invalid="ignore"):
        ratio = (cf / nf[:, None]) / (cs / ns[:, None])
    lo95, hi95, lo999, hi999 = np.nanpercentile(ratio, [2.5, 97.5, 0.05, 99.95], axis=0)

    tot_s, tot_f = CS.sum(axis=0), CF.sum(axis=0)
    rate_s, rate_f = tot_s / DS.sum(), tot_f / DF.sum()

    print(f"# GT vein-mix balance equivalence (dim {args.dim})\n")
    print(f"Seeds paired: {len(seeds)}.  Regions: stock {int(DS.sum())}, fixed {int(DF.sum())}.")
    print(f"Cache entries kept {ks}/{kf}, dropped as other-dimension {ds}/{df}.")
    print(f"Equivalence bound +/-{args.bound:.0%}; {args.reps} seed-paired bootstrap replicates.\n")

    rows, fails, inconc = [], 0, 0
    order = sorted(range(len(keys)), key=lambda i: -(tot_s[i] + tot_f[i]))
    for i in order:
        if tot_s[i] + tot_f[i] < args.min_count:
            continue
        if 1 - args.bound <= lo95[i] and hi95[i] <= 1 + args.bound:
            v = "PASS"
        elif hi999[i] < 1 - args.bound or lo999[i] > 1 + args.bound:
            v = "FAIL"
            fails += 1
        else:
            v = "inconclusive"
            inconc += 1
        rows.append((keys[i], tot_s[i], tot_f[i], rate_s[i], rate_f[i],
                     ratio[:, i].mean(), lo95[i], hi95[i], v))

    print(f"{'mix':<34}{'stock':>7}{'fixed':>7}{'ratio':>8}  {'95% CI':<18}verdict")
    for k, a, b, _ra, _rb, r, lo, hi, v in rows:
        print(f"{k:<34}{int(a):>7}{int(b):>7}{r:>8.3f}  [{lo:.3f}, {hi:.3f}]{'':<3}{v}")
    print(f"\n{len(rows)} mixes tested (>= {args.min_count} observations): "
          f"{fails} FAIL, {inconc} inconclusive, {len(rows)-fails-inconc} PASS")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
