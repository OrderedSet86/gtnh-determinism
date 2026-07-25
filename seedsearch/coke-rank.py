#!/usr/bin/env python3
"""Rank stage-0 prefilter output (scripts/prefilter.sh JSONL) for coke% routing.

Scores each surviving seed by spawn-relative distance to the stage-0-predictable
coke% ingredients, with all PIECE criteria required from ONE village (a runner works
a single village; the best village wins, its start-well block coords are printed in
the "village x,z" column). Distances are XZ blocks from spawn, capped at --cap so a
missing criterion reads as --cap rather than drowning the rest; lower total = better:

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
    ap.add_argument("--max-village-dist", type=float, default=100.0,
                    help="village is only eligible if its nearest piece is within this many "
                         "blocks of spawn (user ruling 2026-07-25: coke%% WR pace ~9:30, a "
                         "village farther than 100 blocks is not worth considering)")
    ap.add_argument("--water-cols", type=int, default=16,
                    help="min water columns for a chunk to count as a water source")
    ap.add_argument("--sand-cols", type=int, default=4,
                    help="min deep-sand columns (vertical run >= 3, the draconic-place "
                         "technique) for a chunk to count as a sand source; sweeps recorded "
                         "before the sand field score sand as --cap")
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

        # score each VILLAGE separately (all piece criteria must come from one village —
        # a runner works a single village, not the best piece from each of several),
        # then the seed's score = its best village + global water distance
        best_village = None
        for st in d.get("village_starts", []):
            vdists = {crit: args.cap for crit in CRITERIA}
            edge = None  # spawn -> nearest piece of this village = the actual walk to reach it
            for m in PIECE_RE.finditer(st.get("pieces", "")):
                x1, _, z1, x2, _, z2 = (int(g) for g in m.groups()[1:])
                dist = box_dist_xz(px, pz, x1, z1, x2, z2)
                edge = dist if edge is None else min(edge, dist)
                for crit, names in CRITERIA.items():
                    if m.group(1) in names:
                        vdists[crit] = min(vdists[crit], dist)
            if edge is None or edge > args.max_village_dist:
                continue
            vscore = sum(vdists.values())
            cx, cz = st.get("c", [0, 0])
            well = (cx * 16 + 2, cz * 16 + 2)  # village start well, block coords
            if best_village is None or vscore < best_village[0]:
                best_village = (vscore, well, vdists)
        if best_village is None:
            killed["village>maxdist"] = killed.get("village>maxdist", 0) + 1
            continue
        vscore, well, dists = best_village

        best_w = args.cap
        best_s = args.cap
        for row in d.get("terrain", []):
            cx, cz, water = row[0], row[1], row[2]
            center = math.hypot(cx * 16 + 8 - px, cz * 16 + 8 - pz)
            if water >= args.water_cols:
                best_w = min(best_w, center)
            if len(row) >= 7 and row[5] >= args.sand_cols:
                best_s = min(best_s, center)
        dists["water"] = best_w
        dists["sand"] = best_s

        if any(dists[r] >= args.cap for r in required):
            killed["require:" + "+".join(r for r in required if dists[r] >= args.cap)] = \
                killed.get("require:" + "+".join(r for r in required if dists[r] >= args.cap), 0) + 1
            continue

        score = vscore + best_w + best_s
        rows.append((score, d["seed"], spawn, well, dists))

    rows.sort()
    if killed:
        print("killed:", ", ".join(f"{k}={v}" for k, v in sorted(killed.items())), file=sys.stderr)
    print(f"{'score':>7}  {'seed':>20}  {'spawn x,z':>13}  {'village x,z':>13}  "
          f"{'paper':>6} {'tic':>6} {'furn':>6} {'water':>6} {'sand':>6}")
    for score, seed, spawn, well, dists in rows[:args.top]:
        print(f"{score:7.0f}  {seed:>20}  {spawn[0]:>6},{spawn[2]:<6}  {well[0]:>6},{well[1]:<6}  "
              f"{dists['paper']:6.0f} {dists['tic']:6.0f} {dists['furnace']:6.0f} "
              f"{dists['water']:6.0f} {dists['sand']:6.0f}")
    if args.seeds_out:
        with open(args.seeds_out, "w") as f:
            for _, seed, _, _, _ in rows:
                f.write(f"{seed}\n")
        print(f"\n{len(rows)} ranked seeds -> {args.seeds_out}", file=sys.stderr)


if __name__ == "__main__":
    main()
