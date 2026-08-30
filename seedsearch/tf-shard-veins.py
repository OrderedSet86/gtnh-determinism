#!/usr/bin/env python3
"""Find Twilight Forest ore veins carrying all six Thaumcraft magic shards, near a chosen site.

THE SIX SHARDS COME FROM THREE VEINS, NOT SIX. A GT ore vein has four material slots, and each of the
three Twilight-Forest shard mixes puts a shard in both its primary and its secondary slot:

    ore.mix.aquaignis     InfusedWater  + InfusedFire
    ore.mix.terraaer      InfusedEarth  + InfusedAir
    ore.mix.perditioordo  InfusedEntropy + InfusedOrder

Amber and Cinnabar fill the between and sporadic slots of all three, which is why neither identifies a
mix. All three are weight 16 of the 1053 Twilight Forest weight, minY 5, maxY 20, size 16, density 2 —
so each wins about 1.5% of oreseed cells, and neither shard of a pair is a trace amount: the secondary
occupies four layers and the primary another four.

TWO PREDICATES, VERY DIFFERENT DIFFICULTY. Measured over 150 seeds with this predictor:

    triple   -- one cell of each mix, scored by smallest enclosing circle.
                Present in EVERY seed. Median radius 87 blocks in a +/-32-chunk window, 54 in +/-64.
                This is a site ranker, not a seed filter.
    adjacent -- the three cells mutually within one grid step (48 blocks), i.e. veins that touch.
                Present in 20% of seeds within +/-64 chunks and 47% within +/-128, and the nearest one
                sits a median 587 and 1192 blocks from the origin respectively. This IS a seed filter.

Both are layer-1 numbers, so they are a floor: the terrain reroll gate raises each shard mix from
16/1053 to as much as 16/493 of TF cells, and adjacent-triple density scales as the cube of that. The
true adjacency rate could be far higher. Measure it with tf-vein-judge.py against a real corpus rather
than trusting these.

The anchor is a player choice, not a seed property. The Twilight Forest is 1:1 with the overworld in
X/Z, so a portal built at the overworld spawn arrives at the same X/Z here — that is the default
anchor, and travel distance from it is the ranking key.

Usage:
  tf-shard-veins.py predict <seeds|@file> [--dim 7] [--window-chunks 64] [--anchor X,Z]
                            [--anchor-from-report DIR] [--require adjacent|triple] [--depth 1]
                            [--max-travel N] [--jsonl OUT]
  tf-shard-veins.py corpus  <report-dir> [--min-count 8]
"""
import argparse
import json
import math
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "scripts"))
import vein_predict as vp  # noqa: E402
import searchlib  # noqa: E402

SHARD_MIXES = ("ore.mix.aquaignis", "ore.mix.terraaer", "ore.mix.perditioordo")
SHARD_INDEX = {name: i for i, name in enumerate(SHARD_MIXES)}

# One oreseed cell step. Cells sit every 3 chunks, so "adjacent" means centres within 48 blocks.
CELL_STEP_CHUNKS = 3
ADJACENT_STEP = 1  # in cells: Chebyshev distance of 1 cell = 3 chunks = 48 blocks


def parse_seeds(spec):
    if spec.startswith("@"):
        body = open(spec[1:]).read()
    else:
        body = spec
    return [int(t) for t in body.replace(",", " ").split()]


def shard_cells(seed, dim, token, window_chunks, anchor_cx, anchor_cz, depth=1):
    """-> [set of (cell_x, cell_z) for each of the three shard mixes], over the window."""
    found = [set(), set(), set()]
    for cell in vp.oreseed_cells(anchor_cx, anchor_cz, window_chunks):
        if depth <= 1:
            mix, _ = vp.predict(seed, cell[0], cell[1], dim=dim, token=token)
            hits = [mix] if mix else []
        else:
            hits = [m for m, _ in vp.predict_all(seed, cell[0], cell[1], dim=dim, token=token)[:depth]]
        for m in hits:
            idx = SHARD_INDEX.get(m["name"])
            if idx is not None:
                found[idx].add(cell)
    return found


def _enclosing_radius(points):
    """Smallest enclosing circle radius for three points: half the longest side, or the circumradius."""
    (ax, az), (bx, bz), (cx, cz) = points
    sides = [
        (math.dist((ax, az), (bx, bz)), 0, 1, 2),
        (math.dist((bx, bz), (cx, cz)), 1, 2, 0),
        (math.dist((ax, az), (cx, cz)), 0, 2, 1),
    ]
    longest, i, j, k = max(sides)
    mid = ((points[i][0] + points[j][0]) / 2, (points[i][1] + points[j][1]) / 2)
    if math.dist(mid, points[k]) <= longest / 2 + 1e-9:
        return longest / 2, mid
    a, b, c = sides[0][0], sides[1][0], sides[2][0]
    s = (a + b + c) / 2
    area = max(math.sqrt(max(s * (s - a) * (s - b) * (s - c), 0.0)), 1e-9)
    r = a * b * c / (4 * area)
    centroid = ((ax + bx + cx) / 3, (az + bz + cz) / 3)
    return r, centroid


def best_triple(found, anchor_xz):
    """Tightest one-of-each triple by enclosing radius, ties broken by distance from the anchor."""
    if not all(found):
        return None
    best = None
    for a in found[0]:
        for b in found[1]:
            for c in found[2]:
                pts = [vp.cell_center_block(*a), vp.cell_center_block(*b), vp.cell_center_block(*c)]
                r, center = _enclosing_radius(pts)
                travel = math.dist(anchor_xz, center)
                key = (round(r, 6), round(travel, 6))
                if best is None or key < best[0]:
                    best = (key, {"cells": [list(a), list(b), list(c)],
                                  "radius": round(r, 1),
                                  "center": [round(center[0]), round(center[1])],
                                  "travel": round(travel, 1)})
    return best[1]


def best_adjacent(found, anchor_xz):
    """Nearest mutually adjacent triple (all pairs within one cell step), or None."""
    best = None
    for a in found[0]:
        for b in found[1]:
            if max(abs(a[0] - b[0]), abs(a[1] - b[1])) > CELL_STEP_CHUNKS * ADJACENT_STEP:
                continue
            for c in found[2]:
                if max(abs(a[0] - c[0]), abs(a[1] - c[1]),
                       abs(b[0] - c[0]), abs(b[1] - c[1])) > CELL_STEP_CHUNKS * ADJACENT_STEP:
                    continue
                pts = [vp.cell_center_block(*a), vp.cell_center_block(*b), vp.cell_center_block(*c)]
                center = (sum(p[0] for p in pts) / 3, sum(p[1] for p in pts) / 3)
                travel = math.dist(anchor_xz, center)
                if best is None or travel < best[0]:
                    best = (travel, {"cells": [list(a), list(b), list(c)],
                                     "center": [round(center[0]), round(center[1])],
                                     "travel": round(travel, 1)})
    return best[1] if best else None


def geometry_for(seed, dim, cells):
    """Per-member vein geometry, so the output states the depth to dig to and not just an XZ position."""
    out = []
    for name, cell in zip(SHARD_MIXES, cells):
        mix = next(m for m in vp.MIXES if m["name"] == name)
        g = vp.vein_geometry(mix, cell[0], cell[1], dim=dim, world_seed=seed)
        out.append({"mix": name, "cell": list(cell), **g})
    return out


def cmd_predict(args):
    dims = vp.load_dims(args.anchor_from_report, quiet=True)
    token = vp.dim_token(args.dim, dims)
    seeds = parse_seeds(args.seeds)

    anchor = (0, 0)
    if args.anchor:
        ax, _, az = args.anchor.partition(",")
        anchor = (int(ax), int(az))
    anchor_cx, anchor_cz = anchor[0] >> 4, anchor[1] >> 4

    out = open(args.jsonl, "w") if args.jsonl else None
    n_adjacent = n_triple = 0
    for seed in seeds:
        # anchor-from-report overrides per seed: the overworld spawn projects 1:1 into the TF.
        a_cx, a_cz, a_xz = anchor_cx, anchor_cz, anchor
        if args.anchor_from_report:
            spawn = _spawn_for(args.anchor_from_report, seed)
            if spawn:
                a_xz = (spawn[0], spawn[2])
                a_cx, a_cz = spawn[0] >> 4, spawn[2] >> 4

        found = shard_cells(seed, args.dim, token, args.window_chunks, a_cx, a_cz, args.depth)
        triple = best_triple(found, a_xz)
        adjacent = best_adjacent(found, a_xz)
        if args.max_travel is not None:
            if triple and triple["travel"] > args.max_travel:
                triple = None
            if adjacent and adjacent["travel"] > args.max_travel:
                adjacent = None
        if triple:
            triple["veins"] = geometry_for(seed, args.dim, triple["cells"])
            n_triple += 1
        if adjacent:
            adjacent["veins"] = geometry_for(seed, args.dim, adjacent["cells"])
            n_adjacent += 1

        if args.require == "adjacent" and not adjacent:
            rec = {"seed": seed, "dim": args.dim, "kill": "adjacent"}
        elif args.require == "triple" and not triple:
            rec = {"seed": seed, "dim": args.dim, "kill": "triple"}
        else:
            rec = {"seed": seed, "dim": args.dim, "depth": args.depth,
                   "anchor": list(a_xz), "window_chunks": args.window_chunks,
                   "counts": {name: len(found[i]) for i, name in enumerate(SHARD_MIXES)},
                   "triple": triple, "adjacent": adjacent}
        line = json.dumps(rec)
        if out:
            out.write(line + "\n")
        else:
            print(line)
    if out:
        out.close()
    print(f"{len(seeds)} seeds: {n_triple} with a triple, {n_adjacent} with an adjacent triple",
          file=sys.stderr)
    if args.depth > 1:
        print(f"note: --depth {args.depth} assumes the terrain gate rerolled the earlier picks; these are "
              f"a recall-boosted candidate set, NOT layer-1 predictions", file=sys.stderr)
    return 0


def _spawn_for(report_dir, seed):
    from pathlib import Path
    for p in Path(report_dir).glob(f"seed-{seed}.json"):
        d = json.load(open(p))
        return d.get("search", {}).get("spawn")
    return None


def cmd_corpus(args):
    reports = list(searchlib.load_dir(args.report_dir))
    if not reports:
        print(f"no seed-*.json under {args.report_dir}", file=sys.stderr)
        return 1
    if not any(r.has_ore_census() for r in reports):
        print("FATAL: no report in this corpus contains a single ore tile entity.\n"
              "On GT 5.09.54.x worldgen ores are plain blocks with no TEs, so the census is EMPTY, not\n"
              "zero, and every vein number derived from it would be wrong rather than negative. Use a\n"
              "region-block diff (scripts/ore-route-report.py) for that GT line.", file=sys.stderr)
        return 2

    n_with_all = 0
    for r in reports:
        if r.dim == 0:
            print(f"  ! seed {r.seed}: dim 0 report in a Twilight Forest corpus — skipped", file=sys.stderr)
            continue
        cells = r.vein_cells(min_count=args.min_count)
        found = {}
        unknown = ambiguous = 0
        for cell, counter in cells.items():
            if cell is None:
                ambiguous = sum(counter.values())
                continue
            name, confidence = identify(counter)
            if name in SHARD_INDEX:
                found.setdefault(name, []).append((cell, confidence))
            elif confidence == "shard-unknown":
                unknown += 1
        have_all = len(found) == 3
        n_with_all += have_all
        print(json.dumps({"seed": r.seed, "dim": r.dim, "all_three": have_all,
                          "cells": {k: [list(c) for c, _ in v] for k, v in found.items()},
                          "shard_unknown_cells": unknown,
                          "ambiguous_boundary_blocks": ambiguous}))
    print(f"{n_with_all}/{len(reports)} reports carry all three shard mixes in the walked window",
          file=sys.stderr)
    return 0


def identify(counter):
    return searchlib.identify_mix(counter)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("predict", help="worldless stage-0 prediction (no JVM, no probe)")
    p.add_argument("seeds", help="comma/space separated, or @file")
    p.add_argument("--dim", type=int, default=7)
    p.add_argument("--window-chunks", type=int, default=64)
    p.add_argument("--anchor", help="X,Z in blocks (default 0,0)")
    p.add_argument("--anchor-from-report", metavar="DIR",
                   help="take each seed's anchor from that seed's report spawn (TF is 1:1 with the overworld)")
    p.add_argument("--depth", type=int, default=1,
                   help="how many dimension-eligible draws per cell to accept (1 = honest layer 1)")
    p.add_argument("--require", choices=("adjacent", "triple"),
                   help="kill seeds without this predicate, in the style of Prefilter's gate.* flags")
    p.add_argument("--max-travel", type=float, help="reject a result further than this from the anchor")
    p.add_argument("--jsonl", help="write JSONL here instead of stdout")
    p.set_defaults(func=cmd_predict)

    c = sub.add_parser("corpus", help="ground truth from real Twilight Forest probe reports")
    c.add_argument("report_dir")
    c.add_argument("--min-count", type=int, default=8)
    c.set_defaults(func=cmd_corpus)

    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
