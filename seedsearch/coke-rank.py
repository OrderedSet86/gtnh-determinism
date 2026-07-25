#!/usr/bin/env python3
"""Rank stage-0 prefilter output (scripts/prefilter.sh JSONL) for coke% routing.

Scores each surviving seed by spawn-relative distance to the stage-0-predictable
coke% ingredients (all distances XZ blocks, capped at --cap so one missing criterion
doesn't drown the rest; lower total = better):

  paper    nearest VillageComponentPhotoshop piece (22/25 such chests carry >=4 paper
           in the 2.8.4 corpus - the only surface-predictable paper source)
  tic      nearest ComponentToolWorkshop / ComponentSmeltery (tool station + smeltery)
  furnace  nearest House2 (blacksmith - the village furnace piece)
  water    nearest terrain-digest chunk with >= --water-cols water columns
           (shallow water is also the DecoClay candidate area = clay proxy)

NOT scored here (needs stage 1 = small-window probe): marshmallows / dungeon loot -
dungeon placement is populate-dependent (see seedsearch/README.md attribution).

Usage:
  coke-rank.py prefilter.jsonl [--top 30] [--cap 512] [--water-cols 16]
               [--require paper,tic] [--seeds-out survivors.txt]
"""
import argparse
import json
import math
import re
import sys

PIECE_RE = re.compile(r'([A-Za-z0-9_$]+)@(-?\d+),(-?\d+),(-?\d+)\.\.(-?\d+),(-?\d+),(-?\d+)')

CRITERIA = {
    "paper": ("VillageComponentPhotoshop",),
    "tic": ("ComponentToolWorkshop", "ComponentSmeltery"),
    "furnace": ("House2",),
}


def box_dist_xz(px, pz, x1, z1, x2, z2):
    dx = max(min(x1, x2) - px, 0, px - max(x1, x2))
    dz = max(min(z1, z2) - pz, 0, pz - max(z1, z2))
    return math.hypot(dx, dz)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("jsonl")
    ap.add_argument("--top", type=int, default=30)
    ap.add_argument("--cap", type=float, default=512.0)
    ap.add_argument("--water-cols", type=int, default=16,
                    help="min water columns for a chunk to count as a water source")
    ap.add_argument("--require", default="",
                    help="comma list of criteria that must be under --cap (e.g. paper,tic)")
    ap.add_argument("--seeds-out", help="write ranked seed list (one per line) here")
    args = ap.parse_args()
    required = [r for r in args.require.split(",") if r]

    rows = []
    killed = {}
    for line in open(args.jsonl):
        line = line.strip()
        if not line:
            continue
        d = json.loads(line)
        if "kill" in d:
            killed[d["kill"]] = killed.get(d["kill"], 0) + 1
            continue
        spawn = d.get("spawn")
        if not spawn:
            continue
        px, pz = spawn[0], spawn[2]

        dists = {}
        for crit, names in CRITERIA.items():
            best = args.cap
            for st in d.get("village_starts", []):
                for m in PIECE_RE.finditer(st.get("pieces", "")):
                    if m.group(1) in names:
                        x1, _, z1, x2, _, z2 = (int(g) for g in m.groups()[1:])
                        best = min(best, box_dist_xz(px, pz, x1, z1, x2, z2))
            dists[crit] = best

        best_w = args.cap
        for row in d.get("terrain", []):
            cx, cz, water = row[0], row[1], row[2]
            if water >= args.water_cols:
                best_w = min(best_w, math.hypot(cx * 16 + 8 - px, cz * 16 + 8 - pz))
        dists["water"] = best_w

        if any(dists[r] >= args.cap for r in required):
            killed["require:" + "+".join(r for r in required if dists[r] >= args.cap)] = \
                killed.get("require:" + "+".join(r for r in required if dists[r] >= args.cap), 0) + 1
            continue

        score = sum(dists.values())
        rows.append((score, d["seed"], spawn, dists))

    rows.sort()
    if killed:
        print("killed:", ", ".join(f"{k}={v}" for k, v in sorted(killed.items())), file=sys.stderr)
    print(f"{'score':>7}  {'seed':>20}  {'spawn':>14}  "
          f"{'paper':>6} {'tic':>6} {'furn':>6} {'water':>6}")
    for score, seed, spawn, dists in rows[:args.top]:
        print(f"{score:7.0f}  {seed:>20}  {spawn[0]:>6},{spawn[2]:<6}  "
              f"{dists['paper']:6.0f} {dists['tic']:6.0f} {dists['furnace']:6.0f} "
              f"{dists['water']:6.0f}")
    if args.seeds_out:
        with open(args.seeds_out, "w") as f:
            for _, seed, _, _ in rows:
                f.write(f"{seed}\n")
        print(f"\n{len(rows)} ranked seeds -> {args.seeds_out}", file=sys.stderr)


if __name__ == "__main__":
    main()
