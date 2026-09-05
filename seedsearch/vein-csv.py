#!/usr/bin/env python3
"""Turn a probe vein-cache dump into a CSV and JourneyMap waypoints.

usage: vein-csv.py <veincache.json> [--dim N] [--spawn X Z] [--radius N] [--out-prefix P]
                   [--mixes-only] [--compare OTHER.json]

The input is `<probe-out>.veincache.json`, written by OreVeinTableDump after a full-generation walk:
GregTech's own `GTWorldgenerator.validOreveins`, i.e. the vein each region ACTUALLY got in that run,
not a prediction. Each entry carries the layer name and the placement bounding box
(veinWestX/EastX, veinNorthZ/SouthZ, veinMinY).

ROUTE DEPENDENCE. Vein IDENTITY is route-stable in the overworld and Twilight Forest as of F4d
(fix jar >= v0.8, `gtnhdet.orepin`, default on): rows vs spiral is 0 differing regions in both, against
a zero same-order floor. It is NOT stable without that fix (7.95% overworld, 13.2% TF), NOT stable in
dimensions outside the pin's whitelist — the Nether measures 21.9% — and per-BLOCK ore placement
remains route-dependent everywhere by design.

So `--compare` is still the thing to run, and its output is still the authority: a dump describes the
veins produced by the walk that generated it, and only a clean compare proves otherwise. Expect
"0 differ" on dim 0 and dim 7 with a current jar; anything else means the pin is off, the dimension is
outside the whitelist, or something regressed.
"""
import argparse
import csv
import json
import math
import pathlib
import re
import sys

SKY_Y = 200


def load(path):
    return json.load(open(path, encoding="utf-8"))


def key(e):
    p = e.get("placement") or {}
    return (e.get("layer"), p.get("veinWestX"), p.get("veinNorthZ"))


MASK64 = (1 << 64) - 1


def dim_of(cache_key, world_seed):
    """Dimension the vein region belongs to, decoded from the oreveinSeed.

    `oreveinSeed = (worldSeed << 16) ^ (dim << 56 | osX << 28 | osZ)`, so XORing the base back out
    recovers the dimension byte. The cache is GLOBAL across dimensions — a dim-7 walk still boots the
    server and touches the overworld — so filtering on this is required. Splitting by "which run
    produced the file" would silently mix 102 overworld regions into the Twilight Forest list.
    """
    x = ((cache_key & MASK64) ^ ((world_seed << 16) & MASK64))
    d = (x >> 56) & 0xFF
    return d - 0x100 if d >= 0x80 else d


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("veincache")
    ap.add_argument("--dim", type=int, default=0)
    ap.add_argument("--spawn", nargs=2, type=int, default=[0, 0], metavar=("X", "Z"))
    ap.add_argument("--radius", type=int, default=60, help="chunks from spawn; veins outside are dropped")
    ap.add_argument("--out-prefix", default="veins")
    ap.add_argument("--compare", default=None, help="second veincache.json from a different walk order")
    ap.add_argument("--seed", type=int, required=True, help="world seed, to decode the dimension")
    args = ap.parse_args()

    raw = load(args.veincache)
    entries = [e for e in raw
               if e.get("layer") and e.get("placement") and dim_of(e["seed"], args.seed) == args.dim]
    sx, sz = args.spawn
    lim = args.radius * 16

    rows = []
    for e in entries:
        p = e["placement"]
        cx = (p["veinWestX"] + p["veinEastX"]) // 2
        cz = (p["veinNorthZ"] + p["veinSouthZ"]) // 2
        dist = math.hypot(cx - sx, cz - sz)
        if dist > lim:
            continue
        rows.append({
            "layer": e["layer"],
            "ore": e["layer"].replace("ore.mix.", ""),
            "centre_x": cx, "centre_z": cz,
            "min_y": p.get("veinMinY"),
            "west_x": p["veinWestX"], "east_x": p["veinEastX"],
            "north_z": p["veinNorthZ"], "south_z": p["veinSouthZ"],
            "size_x": p["veinEastX"] - p["veinWestX"],
            "size_z": p["veinSouthZ"] - p["veinNorthZ"],
            "dist_from_spawn": round(dist),
            "dimension": args.dim,
            "tp": f"/tp {cx} {p.get('veinMinY', SKY_Y)} {cz}",
        })
    rows.sort(key=lambda r: r["dist_from_spawn"])

    stable = None
    if args.compare:
        other = {key(e) for e in load(args.compare)
                 if e.get("layer") and e.get("placement") and dim_of(e["seed"], args.seed) == args.dim}
        mine = {key(e) for e in entries}
        stable = mine & other
        for r in rows:
            r["route_stable"] = "yes" if (r["layer"], r["west_x"], r["north_z"]) in stable else "NO"

    out_csv = f"{args.out_prefix}.csv"
    cols = ["ore", "layer", "centre_x", "min_y", "centre_z", "tp", "dist_from_spawn",
            "west_x", "east_x", "north_z", "south_z", "size_x", "size_z", "dimension"]
    if stable is not None:
        cols.append("route_stable")
    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)

    wpdir = pathlib.Path(f"{args.out_prefix}-journeymap")
    wpdir.mkdir(parents=True, exist_ok=True)
    for old in wpdir.glob("*.json"):
        old.unlink()
    for r in rows:
        name = f"{r['ore']} y{r['min_y']}"
        safe = re.sub(r"[^A-Za-z0-9_-]+", "_", name).strip("_")
        wid = f"vein_{safe}_{r['centre_x']}_{r['centre_z']}"
        (wpdir / f"{wid}.json").write_text(json.dumps({
            "id": wid, "name": name, "icon": "waypoint-normal.png",
            "x": r["centre_x"], "y": r["min_y"], "z": r["centre_z"],
            "r": 255, "g": 170, "b": 0,
            "enable": True, "type": "Normal", "origin": "gtnh-seedsearch",
            "dimensions": [args.dim], "persistent": True,
        }, indent=2))

    from collections import Counter
    c = Counter(r["ore"] for r in rows)
    print(f"{out_csv}: {len(rows)} veins within {args.radius} chunks (dim {args.dim}) "
          f"of {len(entries)} in dim {args.dim}, {len(raw)} total in the dump")
    print(f"{wpdir}/: {len(rows)} waypoints")
    print("  top mixes: " + ", ".join(f"{k} x{v}" for k, v in c.most_common(10)))
    if stable is not None:
        ns = sum(1 for r in rows if r["route_stable"] == "NO")
        if ns:
            print(f"  ROUTE CHECK vs {args.compare}: {len(rows)-ns}/{len(rows)} identical, {ns} DIFFER "
                  f"— those coordinates are specific to this walk order; filter route_stable=yes "
                  f"before publishing them")
        else:
            print(f"  ROUTE CHECK vs {args.compare}: {len(rows)}/{len(rows)} identical, 0 differ "
                  f"— route-stable, safe to publish as-is")
    return 0


if __name__ == "__main__":
    sys.exit(main())
