#!/usr/bin/env python3
"""Find seeds whose near-spawn villages contain specific structure pieces.

Works on any directory of probe.search=true JSONs (extracted seedlib tarball or
results/<batch>/). Village pieces come from the probe's villages dump
("Name@x1,y1,z1..x2,y2,z2" per piece).

Default piece filter = Tinker's Construct houses: ComponentToolWorkshop (tool
station house) + ComponentSmeltery (smeltery house). NOTE: Railcraft's
ComponentWorkshop is NOT TiC despite the similar name.

Distance = spawn to the nearest block of the nearest matching-village piece
(what a runner actually walks); village centroid is printed for orientation.

Usage:
  village-hunt.py <report-dir> [--max-dist 200] [--min-pieces 2]
                  [--pieces ComponentToolWorkshop,ComponentSmeltery] [--any-village]
"""
import argparse
import math
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
from searchlib import load_dir  # noqa: E402

PIECE_RE = re.compile(r'([A-Za-z0-9_]+)@(-?\d+),(-?\d+),(-?\d+)\.\.(-?\d+),(-?\d+),(-?\d+)')
TIC_PIECES = ("ComponentToolWorkshop", "ComponentSmeltery")


def parse_villages(r):
    """[(pieces, centroid_xz)] with pieces = [(name, box)] per village."""
    out = []
    for v in r.villages:
        pieces = [(m.group(1), tuple(int(g) for g in m.groups()[1:]))
                  for m in PIECE_RE.finditer(str(v))]
        if pieces:
            cx = sum((b[0] + b[3]) / 2 for _, b in pieces) / len(pieces)
            cz = sum((b[2] + b[5]) / 2 for _, b in pieces) / len(pieces)
            out.append((pieces, (cx, cz)))
    return out


def box_dist_xz(px, pz, box):
    x1, _, z1, x2, _, z2 = box
    dx = max(min(x1, x2) - px, 0, px - max(x1, x2))
    dz = max(min(z1, z2) - pz, 0, pz - max(z1, z2))
    return math.hypot(dx, dz)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("report_dir")
    ap.add_argument("--max-dist", type=float, default=200,
                    help="max blocks from spawn to nearest matching piece (default 200)")
    ap.add_argument("--min-pieces", type=int, default=2,
                    help="min matching pieces in ONE village (default 2)")
    ap.add_argument("--pieces", default=",".join(TIC_PIECES),
                    help="comma list of piece names (default: TiC houses)")
    ap.add_argument("--any-village", action="store_true",
                    help="ignore piece filter; list all villages in range")
    args = ap.parse_args()
    want = tuple(p.strip() for p in args.pieces.split(","))

    hits = []
    for r in load_dir(args.report_dir):
        sx, _, sz = r.spawn
        for pieces, (cx, cz) in parse_villages(r):
            matching = pieces if args.any_village else [p for p in pieces if p[0] in want]
            if len(matching) < (1 if args.any_village else args.min_pieces):
                continue
            d = min(box_dist_xz(sx, sz, b) for _, b in matching)
            if d <= args.max_dist:
                hits.append((d, r.seed, (sx, sz), (cx, cz), len(pieces), matching))

    hits.sort()
    if not hits:
        print("no matching villages in range")
        return
    print(f"{'dist':>5}  {'seed':>21}  {'spawn':>12}  {'village~':>12}  pieces  matching")
    for d, seed, (sx, sz), (cx, cz), np, matching in hits:
        names = "; ".join(f"{n}@{b[0]},{b[1]},{b[2]}" for n, b in matching)
        print(f"{d:5.0f}  {seed:>21}  {sx:>5},{sz:>5}  {cx:5.0f},{cz:5.0f}  {np:>6}  {names}")


if __name__ == "__main__":
    main()
