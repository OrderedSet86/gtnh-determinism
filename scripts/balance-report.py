#!/usr/bin/env python3
"""Upstream balance-equivalence report: stock vs fix-jar frequency comparison.

Usage: balance-report.py <stock-dir> <fixed-dir> [--md out.md] [--bound 0.10]

Input: probe search reports (seed-*.json, any number of repeats per seed) from both arms.
Output: per-metric rate tables with Wilson/normal 95% CIs on the relative difference and an
equivalence verdict: PASS (CI within ±bound), FAIL (CI excludes ±bound), INCONCLUSIVE
(CI straddles the bound — need more samples). Sample units:
  chest items   -> occurrences per 100 chests (item identity = id:damage)
  vein material -> big-ore TEs per probed region
  small ores    -> small-ore TEs per probed region
  village piece -> pieces per village
  witchery      -> structures per region
Region = one report (fixed radius assumed uniform across runs).
"""
import json
import math
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from searchlib import SeedReport, decode_ore  # noqa: E402

import re
PIECE_RE = re.compile(r"(\w+)@")


def load_arm(d):
    d = Path(d)
    mats_f = d / "gtmats.json"
    mats = json.load(open(mats_f)) if mats_f.exists() else {}
    return [SeedReport(p, mats) for p in sorted(d.glob("seed-*.json"))], mats


def collect(reports):
    """Aggregate counts + exposure denominators over an arm."""
    agg = {
        "regions": len(reports),
        "chests": 0,
        "villages": 0,
        "chest_items": Counter(),   # id:d -> total item units (stack-weighted)
        "chest_draws": Counter(),   # id:d -> number of stacks (statistical unit)
        "veins": Counter(),         # material -> big-ore TE count
        "smalls": Counter(),        # material -> small-ore TE count
        "pieces": Counter(),        # village piece type -> count
        "witchery": Counter(),      # structure entry count (type detail unavailable -> total)
    }
    for r in reports:
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
    return agg


def ratio_ci(c1, n1, c2, n2):
    """Log-normal 95% CI for rate ratio (c2/n2)/(c1/n1) with +0.5 continuity correction."""
    a, b = c1 + 0.5, c2 + 0.5
    lr = math.log((b / n2) / (a / n1))
    se = math.sqrt(1 / a + 1 / b)
    return math.exp(lr), math.exp(lr - 1.96 * se), math.exp(lr + 1.96 * se)


def verdict(lo, hi, bound):
    if 1 - bound <= lo and hi <= 1 + bound:
        return "PASS"
    if hi < 1 - bound or lo > 1 + bound:
        return "FAIL"
    return "inconclusive"


def table(name, unit, cs, ns, cf, nf, bound, min_count=5, ci_cs=None, ci_cf=None):
    # ci_cs/ci_cf: counts to base the CI on (e.g. stack draws); display rates still use cs/cf
    if ci_cs is None: ci_cs, ci_cf = cs, cf
    lines = [f"\n## {name}  (rate per {unit}; denominators: stock {ns:.0f}, fixed {nf:.0f} {unit}s; CI from draw counts)",
             "", "| item | stock rate | fixed rate | ratio (fixed/stock) | 95% CI | verdict |",
             "|---|---|---|---|---|---|"]
    keys = sorted(set(cs) | set(cf), key=lambda k: -(cs.get(k, 0) + cf.get(k, 0)))
    shown = flagged = 0
    for k in keys:
        a, b = cs.get(k, 0), cf.get(k, 0)
        da, db = ci_cs.get(k, 0), ci_cf.get(k, 0)
        if da + db < min_count:
            continue
        r, lo, hi = ratio_ci(da, ns, db, nf)
        if da and db:  # rescale ratio to unit rates but keep draw-based CI width
            unit_r = (b / nf) / (a / ns) if a else r
            shift = unit_r / r if r else 1.0
            r, lo, hi = r * shift, lo * shift, hi * shift
        v = verdict(lo, hi, bound)
        shown += 1
        flagged += v == "FAIL"
        lines.append(f"| {k} | {a/ns:.4g} | {b/nf:.4g} | {r:.3f} | [{lo:.3f}, {hi:.3f}] | {v} |")
    lines.insert(1, f"{shown} entries with >= {min_count} total observations; {flagged} FAIL.")
    return lines


def main():
    stock_dir, fixed_dir = sys.argv[1], sys.argv[2]
    bound = float(sys.argv[sys.argv.index("--bound") + 1]) if "--bound" in sys.argv else 0.10
    stock, _ = load_arm(stock_dir)
    fixed, _ = load_arm(fixed_dir)
    S, F = collect(stock), collect(fixed)
    out = [
        "# Balance equivalence: stock vs determinism jar",
        f"\nSamples: stock {S['regions']} regions / {S['chests']} chests / {S['villages']} villages; "
        f"fixed {F['regions']} regions / {F['chests']} chests / {F['villages']} villages. "
        f"Equivalence bound: ±{bound:.0%} relative. CIs: log-normal rate-ratio, 95%.",
        "\nNOTE: 'inconclusive' means the sample is too small to bound the difference — grow the",
        "sample (more seeds) or cover that item via the direct-code Monte-Carlo tier.",
    ]
    out += table("Chest items", "100 chests", S["chest_items"], S["chests"] / 100,
                 F["chest_items"], F["chests"] / 100, bound,
                 ci_cs=S["chest_draws"], ci_cf=F["chest_draws"])
    out += table("GT vein materials (big-ore TEs)", "region", S["veins"], S["regions"],
                 F["veins"], F["regions"], bound)
    out += table("GT small ores", "region", S["smalls"], S["regions"],
                 F["smalls"], F["regions"], bound)
    out += table("Village pieces", "village", S["pieces"], max(S["villages"], 1),
                 F["pieces"], max(F["villages"], 1), bound)
    out += table("Witchery structures", "region", S["witchery"], S["regions"],
                 F["witchery"], F["regions"], bound)
    text = "\n".join(out)
    if "--md" in sys.argv:
        Path(sys.argv[sys.argv.index("--md") + 1]).write_text(text)
    print(text)


if __name__ == "__main__":
    main()
