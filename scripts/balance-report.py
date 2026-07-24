#!/usr/bin/env python3
"""Upstream balance-equivalence report: stock vs fix-jar frequency comparison.

Usage: balance-report.py <stock-dir> <fixed-dir> [--md out.md] [--bound 0.10] [--reps 4000]

Input: probe search reports (seed-*.json, any number of repeats per seed) from both arms.
Output: per-metric rate tables with a seed-paired, region-clustered BOOTSTRAP CI on the rate
ratio (the verdict column) plus the analytic draw-based log-normal CI as a secondary column.

Bootstrap design: the resampling unit is the SEED (all of a seed's repeats in both arms move
together), which respects (a) within-region correlation — chests/veins inside one world are not
independent draws — and (b) the seed pairing between arms, since both arms generate the same
seed list. Seeds present in only one arm are dropped from the bootstrap (counted in the header).

Verdicts (equivalence bound ±B, default 10%):
  PASS         95% bootstrap CI inside [1-B, 1+B]
  FAIL         99.9% bootstrap CI entirely outside — strict per-row confidence so that across
               the m rows tested, expected false FAILs = 0.001*m (reported in the header)
  inconclusive everything else (usually: sample too small to bound the difference)

Sample units:
  chest items   -> occurrences per 100 chests (item identity = id:damage)
  vein material -> big-ore TEs per probed region
  small ores    -> small-ore TEs per probed region
  village piece -> pieces per village
  witchery      -> structures per region
Region = one report (fixed radius assumed uniform across runs).
"""
import json
import math
import re
import sys
from collections import Counter
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from searchlib import SeedReport, decode_ore  # noqa: E402

PIECE_RE = re.compile(r"(\w+)@")


def load_arm(d):
    d = Path(d)
    mats_f = d / "gtmats.json"
    mats = json.load(open(mats_f)) if mats_f.exists() else {}
    return [SeedReport(p, mats) for p in sorted(d.glob("seed-*.json"))], mats


def new_agg():
    return {
        "regions": 0,
        "chests": 0,
        "villages": 0,
        "chest_items": Counter(),   # id:d -> total item units (stack-weighted)
        "chest_draws": Counter(),   # id:d -> number of stacks (statistical unit)
        "veins": Counter(),         # material -> big-ore TE count
        "smalls": Counter(),        # material -> small-ore TE count
        "pieces": Counter(),        # village piece type -> count
        "witchery": Counter(),      # structure entry count (type detail unavailable -> total)
    }


def add_report(agg, r):
    agg["regions"] += 1
    for k, c in r.chunks.items():
        for chest in c.get("chests", []):
            agg["chests"] += 1
            for it in chest["items"]:
                key = f'{it["id"]}:{it["d"]}'
                agg["chest_items"][key] += it["n"]
                agg["chest_draws"][key] += 1
        for m_str, n in c.get("ores", {}).items():
            d = decode_ore(int(m_str), r.mats)
            agg["smalls" if d["small"] else "veins"][d["material"]] += n
    for v in r.villages:
        if isinstance(v, str) and "pieces" in v:
            agg["villages"] += 1
            agg["pieces"].update(PIECE_RE.findall(v))
    agg["witchery"]["structures"] += len(r.witchery) if isinstance(r.witchery, list) else 0


def collect(reports):
    """Aggregate counts + exposure denominators over an arm."""
    agg = new_agg()
    for r in reports:
        add_report(agg, r)
    return agg


def collect_per_seed(reports):
    per = {}
    for r in reports:
        add_report(per.setdefault(str(r.seed), new_agg()), r)
    return per


class Bootstrap:
    """Seed-paired, region-clustered bootstrap over the common seed list.

    Resamples SEEDS with replacement (both arms' repeats of a seed move together), recomputes
    every metric's rate ratio per replicate, and hands back percentile CIs.
    """

    def __init__(self, per_s, per_f, reps, rng):
        self.seeds = sorted(set(per_s) & set(per_f))
        self.dropped = sorted((set(per_s) | set(per_f)) - set(self.seeds))
        self.per_s, self.per_f = per_s, per_f
        n = len(self.seeds)
        # weight matrix: reps x nseeds, row = multinomial resample of the seed list
        self.W = rng.multinomial(n, np.full(n, 1.0 / n), size=reps).astype(np.float64)

    def _vec(self, per, field):
        if field in ("regions", "chests", "villages"):
            return np.array([per[s][field] for s in self.seeds], dtype=np.float64)
        raise KeyError(field)

    def cis(self, counter_field, denom_field, keys, denom_scale=1.0):
        """Return {key: (lo95, hi95, lo999, hi999)} for the fixed/stock rate ratio."""
        n = len(self.seeds)
        CS = np.array([[self.per_s[s][counter_field].get(k, 0) for k in keys] for s in self.seeds],
                      dtype=np.float64)
        CF = np.array([[self.per_f[s][counter_field].get(k, 0) for k in keys] for s in self.seeds],
                      dtype=np.float64)
        DS = self._vec(self.per_s, denom_field) * denom_scale
        DF = self._vec(self.per_f, denom_field) * denom_scale
        cs, cf = self.W @ CS, self.W @ CF          # reps x nkeys
        ns, nf = self.W @ DS, self.W @ DF          # reps
        ns = np.maximum(ns, 1e-9)[:, None]
        nf = np.maximum(nf, 1e-9)[:, None]
        ratio = ((cf + 0.5) / nf) / ((cs + 0.5) / ns)
        pct = np.percentile(ratio, [2.5, 97.5, 0.05, 99.95], axis=0)
        return {k: (pct[0, i], pct[1, i], pct[2, i], pct[3, i]) for i, k in enumerate(keys)}


def ratio_ci(c1, n1, c2, n2):
    """Log-normal 95% CI for rate ratio (c2/n2)/(c1/n1) with +0.5 continuity correction."""
    a, b = c1 + 0.5, c2 + 0.5
    lr = math.log((b / n2) / (a / n1))
    se = math.sqrt(1 / a + 1 / b)
    return math.exp(lr), math.exp(lr - 1.96 * se), math.exp(lr + 1.96 * se)


def verdict(boot, bound):
    lo95, hi95, lo999, hi999 = boot
    if 1 - bound <= lo95 and hi95 <= 1 + bound:
        return "PASS"
    if hi999 < 1 - bound or lo999 > 1 + bound:
        return "FAIL"
    return "inconclusive"


def table(name, unit, cs, ns, cf, nf, bound, boot, counter_field, denom_field, denom_scale,
          min_count=5, ci_cs=None, ci_cf=None):
    # ci_cs/ci_cf: counts for the analytic CI (e.g. stack draws); display rates still use cs/cf
    if ci_cs is None:
        ci_cs, ci_cf = cs, cf
    keys = sorted(set(cs) | set(cf), key=lambda k: -(cs.get(k, 0) + cf.get(k, 0)))
    shown_keys = [k for k in keys if ci_cs.get(k, 0) + ci_cf.get(k, 0) >= min_count]
    bcis = boot.cis(counter_field, denom_field, shown_keys, denom_scale) if shown_keys else {}
    lines = [f"\n## {name}  (rate per {unit}; exposure: stock {ns:.0f} x {unit}, fixed {nf:.0f} x {unit})",
             "", "| item | stock rate | fixed rate | ratio (fixed/stock) | bootstrap 95% CI | analytic 95% CI | verdict |",
             "|---|---|---|---|---|---|---|"]
    flagged = 0
    for k in shown_keys:
        a, b = cs.get(k, 0), cf.get(k, 0)
        da, db = ci_cs.get(k, 0), ci_cf.get(k, 0)
        r, alo, ahi = ratio_ci(da, ns, db, nf)
        if da and db:  # rescale analytic ratio to unit rates but keep draw-based CI width
            unit_r = (b / nf) / (a / ns) if a else r
            shift = unit_r / r if r else 1.0
            r, alo, ahi = r * shift, alo * shift, ahi * shift
        bci = bcis[k]
        v = verdict(bci, bound)
        flagged += v == "FAIL"
        lines.append(f"| {k} | {a/ns:.4g} | {b/nf:.4g} | {r:.3f} | [{bci[0]:.3f}, {bci[1]:.3f}] "
                     f"| [{alo:.3f}, {ahi:.3f}] | {v} |")
    lines.insert(1, f"{len(shown_keys)} entries with >= {min_count} total observations; {flagged} FAIL.")
    return lines, len(shown_keys), flagged


def main():
    stock_dir, fixed_dir = sys.argv[1], sys.argv[2]
    bound = float(sys.argv[sys.argv.index("--bound") + 1]) if "--bound" in sys.argv else 0.10
    reps = int(sys.argv[sys.argv.index("--reps") + 1]) if "--reps" in sys.argv else 4000
    stock, _ = load_arm(stock_dir)
    fixed, _ = load_arm(fixed_dir)
    S, F = collect(stock), collect(fixed)
    per_s, per_f = collect_per_seed(stock), collect_per_seed(fixed)
    boot = Bootstrap(per_s, per_f, reps, np.random.default_rng(20260723))
    specs = [
        ("Chest items", "100 chests", S["chest_items"], S["chests"] / 100,
         F["chest_items"], F["chests"] / 100, "chest_items", "chests", 1 / 100,
         S["chest_draws"], F["chest_draws"]),
        ("GT vein materials (big-ore TEs)", "region", S["veins"], S["regions"],
         F["veins"], F["regions"], "veins", "regions", 1.0, None, None),
        ("GT small ores", "region", S["smalls"], S["regions"],
         F["smalls"], F["regions"], "smalls", "regions", 1.0, None, None),
        ("Village pieces", "village", S["pieces"], max(S["villages"], 1),
         F["pieces"], max(F["villages"], 1), "pieces", "villages", 1.0, None, None),
        ("Witchery structures", "region", S["witchery"], S["regions"],
         F["witchery"], F["regions"], "witchery", "regions", 1.0, None, None),
    ]
    tables, m_total, fail_total = [], 0, 0
    for name, unit, cs, ns, cf, nf, cfield, dfield, dscale, ccs, ccf in specs:
        lines, shown, flagged = table(name, unit, cs, ns, cf, nf, bound, boot,
                                      cfield, dfield, dscale, ci_cs=ccs, ci_cf=ccf)
        tables += lines
        m_total += shown
        fail_total += flagged
    out = [
        "# Balance equivalence: stock vs determinism jar",
        f"\nSamples: stock {S['regions']} regions / {S['chests']} chests / {S['villages']} villages; "
        f"fixed {F['regions']} regions / {F['chests']} chests / {F['villages']} villages. "
        f"Equivalence bound: ±{bound:.0%} relative.",
        f"\nVerdict CI: seed-paired region-clustered bootstrap ({reps} replicates over "
        f"{len(boot.seeds)} paired seeds"
        + (f"; {len(boot.dropped)} unpaired seeds dropped from resampling" if boot.dropped else "")
        + "). PASS = 95% bootstrap CI inside the bound; FAIL requires the 99.9% bootstrap CI "
        f"entirely outside it, so across the {m_total} rows tested the expected number of false "
        f"FAILs is ~{m_total * 0.001:.1f}. The analytic log-normal draw-based CI is shown for "
        "comparison; it ignores within-region correlation and is anticonservative for clustered "
        "items.",
        "\nNOTE: 'inconclusive' means the sample is too small to bound the difference — grow the",
        "sample (more seeds) or cover that item via the direct-code Monte-Carlo tier.",
        f"\nTotal FAIL rows: {fail_total} of {m_total}.",
    ] + tables
    text = "\n".join(out)
    if "--md" in sys.argv:
        Path(sys.argv[sys.argv.index("--md") + 1]).write_text(text)
    print(text)


if __name__ == "__main__":
    main()
