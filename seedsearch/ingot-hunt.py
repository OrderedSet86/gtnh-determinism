#!/usr/bin/env python3
"""Rank seeds/chests/clusters by chest-loot ingot content in probe search reports.

Works on any directory of probe.search=true JSONs (e.g. an extracted seedlib tarball,
or results/<batch>/). Item names resolve against the report dir's gtmats.json
(GT ingot = gt.metaitem.01 damage 11000+matId); raw ids like minecraft:iron_ingot:0
are accepted too.

Facts baked into the defaults (established from seedlib-0.4-60seeds):
  - Village chests contain NO GT ingots (241 chests, 60 seeds, zero hits) — villages
    are a spawn anchor, not an ingot source.
  - Chest steel/bronze come from Roguelike dungeons (mostly y<50) and surface ruins
    (iridium-shard loot profile, mostly y>50). --y-min 50 approximates "ruins only";
    --ruins-only classifies by loot profile instead and catches sunken ruins.

Usage:
  ingot-hunt.py totals   <report-dir> [opts]   # per-seed totals, ranked
  ingot-hunt.py chests   <report-dir> [opts]   # top individual chests
  ingot-hunt.py clusters <report-dir> [opts]   # best chest cluster per seed
  ingot-hunt.py villages <report-dir> [opts]   # clusters + nearest-village context

Options:
  --items steel,bronze,...   ingots to count (default steel,stainlesssteel,aluminium,bronze;
                             names from gtmats.json, case-insensitive, or raw id:dmg)
  --rank steel,bronze        sort key = sum of these items only (default: all --items)
  --y-min N / --y-max N      chest altitude filter (e.g. --y-min 50 for surface)
  --ruins-only               keep only chests whose loot matches the ruins profile
  --radius N                 cluster anchor radius in blocks (default 100)
  --top N                    rows to print (default 10)
"""
import argparse
import json
import math
import re
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
from searchlib import load_dir  # noqa: E402

PIECE_RE = re.compile(r'@(-?\d+),(-?\d+),(-?\d+)\.\.(-?\d+),(-?\d+),(-?\d+)')
# Items that (in the 2.7.4 corpus) appear in ruins chests but never in Roguelike loot.
RUINS_MARKERS = ("itemShardIridium", "itemOreIridium", "standardBindingAgent",
                 "disposeItemTurret")


def resolve_items(names, report_dir):
    """name -> "full_id:dmg" key as used in probe chest items."""
    mats_file = Path(report_dir) / "gtmats.json"
    mats = json.load(open(mats_file)) if mats_file.exists() else {}
    rev = {v.lower(): int(k) for k, v in mats.items()}
    out = {}
    for name in names:
        n = name.strip()
        if n.lower() in rev:
            out[n] = f"gregtech:gt.metaitem.01:{11000 + rev[n.lower()]}"
        elif n.count(":") >= 2:
            out[n] = n
        else:
            sys.exit(f"unknown item {n!r}: not in gtmats.json and not a raw id:dmg")
    return out


def is_ruins_chest(items):
    return any(any(m in it["id"] for m in RUINS_MARKERS) for it in items)


def iter_chests(r, args, keys):
    """Yield (pos, Counter(name->count), items) for chests passing filters w/ any hit."""
    for _, c in r.chunks.items():
        for chest in c.get("chests", []):
            y = chest["pos"][1]
            if args.y_min is not None and y < args.y_min:
                continue
            if args.y_max is not None and y > args.y_max:
                continue
            if args.ruins_only and not is_ruins_chest(chest["items"]):
                continue
            counts = Counter()
            for it in chest["items"]:
                k = f'{it["id"]}:{it["d"]}'
                for name, key in keys.items():
                    if k == key:
                        counts[name] += it["n"]
            if counts:
                yield tuple(chest["pos"]), counts, chest["items"]


def fmt_counts(counts, order):
    return "  ".join(f"{name} {counts.get(name, 0)}" for name in order)


def rank_value(counts, rank_items):
    return sum(counts.get(n, 0) for n in rank_items)


def best_cluster(chests, radius, rank_items):
    """Anchor-ball clustering: best (total, members, span) over all anchors."""
    best = None
    for anchor, _, _ in chests:
        mem = [c for c in chests if math.dist(anchor, c[0]) <= radius]
        tot = rank_value(sum((c[1] for c in mem), Counter()), rank_items)
        if best is None or tot > best[0]:
            span = max((math.dist(a[0], b[0]) for a in mem for b in mem), default=0)
            best = (tot, mem, span)
    return best


def villages_of(r):
    """[(cx, cz, n_pieces)] per village (centroid of piece bounding boxes)."""
    out = []
    for v in r.villages:
        boxes = [[int(g) for g in m.groups()] for m in PIECE_RE.finditer(str(v))]
        if boxes:
            out.append((sum((b[0] + b[3]) / 2 for b in boxes) / len(boxes),
                        sum((b[2] + b[5]) / 2 for b in boxes) / len(boxes),
                        len(boxes)))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["totals", "chests", "clusters", "villages"])
    ap.add_argument("report_dir")
    ap.add_argument("--items", default="steel,stainlesssteel,aluminium,bronze")
    ap.add_argument("--rank", default=None,
                    help="comma list of --items names to sort by (default: all)")
    ap.add_argument("--y-min", type=int, default=None)
    ap.add_argument("--y-max", type=int, default=None)
    ap.add_argument("--ruins-only", action="store_true")
    ap.add_argument("--radius", type=float, default=100)
    ap.add_argument("--top", type=int, default=10)
    args = ap.parse_args()

    names = [n.strip() for n in args.items.split(",")]
    keys = resolve_items(names, args.report_dir)
    rank_items = ([n.strip() for n in args.rank.split(",")] if args.rank else names)
    for n in rank_items:
        if n not in keys:
            sys.exit(f"--rank item {n!r} not in --items")

    if args.cmd == "totals":
        rows = []
        for r in load_dir(args.report_dir):
            tot = sum((c for _, c, _ in iter_chests(r, args, keys)), Counter())
            rows.append((rank_value(tot, rank_items), r.seed, r.spawn, tot))
        rows.sort(key=lambda t: -t[0])
        for val, seed, spawn, tot in rows[:args.top]:
            print(f"{val:4d}  {seed:>22}  spawn {spawn}  [{fmt_counts(tot, names)}]")

    elif args.cmd == "chests":
        rows = []
        for r in load_dir(args.report_dir):
            for pos, counts, items in iter_chests(r, args, keys):
                rows.append((rank_value(counts, rank_items), r.seed, pos, counts,
                             len(items)))
        rows.sort(key=lambda t: -t[0])
        for val, seed, pos, counts, nstacks in rows[:args.top]:
            print(f"{val:4d}  {seed:>22}  @ {list(pos)}  ({nstacks} stacks)  "
                  f"[{fmt_counts(counts, names)}]")

    elif args.cmd in ("clusters", "villages"):
        rows = []
        for r in load_dir(args.report_dir):
            chests = list(iter_chests(r, args, keys))
            best = best_cluster(chests, args.radius, rank_items)
            if best:
                rows.append((best[0], r, best[1], best[2]))
        rows.sort(key=lambda t: -t[0])
        for val, r, mem, span in rows[:args.top]:
            tot = sum((c[1] for c in mem), Counter())
            anchor = mem[0][0]
            sd = math.dist(r.spawn, anchor)
            line = (f"{val:4d}  {r.seed:>22}  {len(mem)} chests, span {span:.0f}, "
                    f"{sd:.0f} blk from spawn {r.spawn}  [{fmt_counts(tot, names)}]")
            if args.cmd == "villages":
                vill = villages_of(r)
                if vill:
                    vd, vp = min((math.dist((r.spawn[0], r.spawn[2]), (cx, cz)), n)
                                 for cx, cz, n in vill)
                    line += f"  village {vd:.0f} blk ({vp} pieces)"
                else:
                    line += "  NO village in window"
            print(line)
            for pos, counts, _ in sorted(mem, key=lambda c: -rank_value(c[1], rank_items)):
                print(f"          {list(pos)}  [{fmt_counts(counts, names)}]")


if __name__ == "__main__":
    main()
