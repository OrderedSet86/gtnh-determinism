#!/usr/bin/env python3
"""Stock-vs-fix worldgen effects report (upstreaming evidence).

Usage: effects-report.py <stock-dir> <fixed-dir> [--md out.md]

Reads probe search reports from both arms (stock: seed-<s>.r<n>.json repeats;
fixed: seed-<s>.json), and answers, per metric:
  1. Does stock vary launch-to-launch at all? (within-stock spread)
  2. Is the fixed output inside the stock distribution? (fixed vs stock repeats)
Metrics: village presence/piece counts/building types, witchery structure counts,
chest loot item totals & presence, big-ore vein materials, biome histogram (sanity),
water/clay (sanity).
"""
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from searchlib import SeedReport  # noqa: E402

PIECE_RE = re.compile(r"(\w+)@")


def load_arm(d):
    d = Path(d)
    mats_f = d / "gtmats.json"
    mats = json.load(open(mats_f)) if mats_f.exists() else {}
    arm = defaultdict(list)  # seed -> [SeedReport per repeat]
    for p in sorted(d.glob("seed-*.json")):
        r = SeedReport(p, mats)
        arm[r.seed].append(r)
    return arm


def metrics(r):
    piece_types = Counter()
    total_pieces = 0
    for v in r.villages:
        if not isinstance(v, str) or "pieces" not in v:
            continue
        total_pieces += int(v.split(" pieces", 1)[0])
        piece_types.update(PIECE_RE.findall(v))
    return {
        "villages": r.village_count(),
        "village_pieces": total_pieces,
        "village_piece_types": piece_types,
        "witchery_structures": len(r.witchery) if isinstance(r.witchery, list) else 0,
        "chest_items": r.chest_items(),
        "chest_item_total": sum(r.chest_items().values()),
        "vein_materials": r.vein_materials(),
        "biomes": r.biomes(),
        "water": r.water(),
        "clay": r.clay(),
    }


def spread(vals):
    return f"{min(vals)}..{max(vals)}" if len(set(vals)) > 1 else str(vals[0])


def counter_diff(a, b):
    out = {}
    for k in set(a) | set(b):
        if a.get(k, 0) != b.get(k, 0):
            out[k] = (a.get(k, 0), b.get(k, 0))
    return out


def main():
    stock = load_arm(sys.argv[1])
    fixed = load_arm(sys.argv[2])
    lines = ["# Stock vs fix-jar worldgen effects", ""]
    scalar_keys = ["villages", "village_pieces", "witchery_structures",
                   "chest_item_total", "water", "clay"]
    counter_keys = ["village_piece_types", "chest_items", "vein_materials", "biomes"]

    agg_flags = Counter()
    for seed in sorted(set(stock) & set(fixed), key=str):
        sm = [metrics(r) for r in stock[seed]]
        fm = [metrics(r) for r in fixed[seed]]
        lines.append(f"## seed {seed}  (stock n={len(sm)}, fixed n={len(fm)})")
        # fixed determinism regression: all fixed repeats must agree exactly
        if len(fm) > 1:
            same = all(metrics_equal(fm[0], m) for m in fm[1:])
            lines.append(f"- fixed self-consistency: {'IDENTICAL' if same else '**DIVERGES — regression!**'}")
            agg_flags["fixed_regression" if not same else "fixed_ok"] += 1
        for k in scalar_keys:
            sv = [m[k] for m in sm]
            fv = fm[0][k]
            in_range = min(sv) <= fv <= max(sv)
            flag = "" if fv in sv else (" (within stock range)" if in_range else " **[OUTSIDE stock range]**")
            agg_flags[f"{k}_outside" if not in_range else f"{k}_ok"] += 1
            lines.append(f"- {k}: stock {spread(sv)} | fixed {fv}{flag}")
        for k in counter_keys:
            stock_union = Counter()
            for m in sm:
                stock_union |= m[k]
            d = counter_diff(stock_union, fm[0][k])
            if d:
                top = dict(sorted(d.items(), key=lambda kv: -abs(kv[1][0] - kv[1][1]))[:8])
                lines.append(f"- {k} diffs (stock-max vs fixed): {top}")
        lines.append("")

    lines.append("## Aggregate")
    for k, v in sorted(agg_flags.items()):
        lines.append(f"- {k}: {v}")
    out = "\n".join(lines)
    if "--md" in sys.argv:
        Path(sys.argv[sys.argv.index("--md") + 1]).write_text(out)
    print(out)


def metrics_equal(a, b):
    return all(a[k] == b[k] for k in a)


if __name__ == "__main__":
    main()
