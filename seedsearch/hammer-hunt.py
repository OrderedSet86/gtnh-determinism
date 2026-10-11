#!/usr/bin/env python3
"""Rank hammer% seeds from stage-0 prefilter JSONL, and tune the gate bars from its kill rows.

Hammer% needs, within a radius of the predicted spawn (default 320 blocks = 20 chunks):

  * a village with the TC smeltery piece (ComponentSmeltery)
  * 4x Block of Steel        - predicted village chests + mineshaft corridor minecarts
  * 2x Alumite Large Plate   - Roguelike TOOLS chests, level 3 only (GTNH shows "Obzinite";
                               the SERVER string is Alumite - match on Alumite, always)
  * 4x any Large Plate total - the 2 extra may be any material, Alumite included
  * 1x any Hammer Head, 1x any Tough Rod - Roguelike TOOLS chests

This tool re-derives every aggregate from the RAW row fields (village chest item lists, mineshaft
minecart dumps, dungeon TOOLS chests) rather than trusting the Java gate's own counters -- it is the
independent check on gate.steel / gate.toolloot, so a counting bug in either side shows up as a
disagreement instead of surviving in both.

Kill rows are first-class input: `kill: steel` rows carry blacksmith/mineshaft/steel_total and
`kill: toolloot` rows carry the tools_agg counts, so this prints their distributions and a
survivors-vs-bar table. That is how the next sweep's bars are chosen from data without re-sweeping.

Usage:
  hammer-hunt.py <tier2.jsonl...> [--dist 320] [--steel 4] [--alumite 2] [--plate 4]
                 [--hammer 1] [--rod 1] [--top 20] [--no-require-smeltery]

Y in /tp lines: chest Y is exact for mineshaft minecarts and dungeon chests; village chest Y is
nominal (never predicted), printed as Y=200 sky-drop per the poi-export convention.
"""
import argparse
import json
import math
import re
import sys
from collections import Counter

PIECE_RE = re.compile(r'([A-Za-z0-9_]+)@(-?\d+),(-?\d+),(-?\d+)\.\.(-?\d+),(-?\d+),(-?\d+)')
SKY_Y = 200


def classify(name):
    """LootCounts.java's rules, mirrored: (steel, alumite_plate, any_plate, hammer_head, tough_rod)."""
    if name is None:
        return (0, 0, 0, 0, 0)
    name = re.sub(r'§.', '', name)
    return (
        1 if name == "Block of Steel" else 0,
        1 if name == "Alumite Large Plate" else 0,
        1 if name.endswith(" Large Plate") else 0,
        1 if name.endswith(" Hammer Head") else 0,
        1 if name.endswith(" Tough Rod") else 0,
    )


def chest_counts(chest):
    tot = [0, 0, 0, 0, 0]
    for it in chest.get("items") or []:
        c = classify(it.get("name"))
        n = it.get("n", 1)
        for i in range(5):
            tot[i] += c[i] * n
    return tot


def dist(ax, az, bx, bz):
    return math.hypot(ax - bx, az - bz)


def box_dist_xz(px, pz, box):
    x1, _, z1, x2, _, z2 = box
    dx = max(min(x1, x2) - px, 0, px - max(x1, x2))
    dz = max(min(z1, z2) - pz, 0, pz - max(z1, z2))
    return math.hypot(dx, dz)


def measure(row, d):
    """Bar-independent aggregates within d blocks of spawn; None when the row has no spawn."""
    spawn = row.get("spawn")
    if not spawn:
        return None
    sx, sz = spawn[0], spawn[2]

    # Smeltery village: nearest ComponentSmeltery piece box, from the same pieces string
    # village-hunt.py parses. Village chest steel rides the same starts.
    smeltery = None  # (dist, x, z)
    steel_sources = []  # (dist, x, y, z, n, "blacksmith"|"minecart")
    for v in row.get("village_starts") or []:
        for m in PIECE_RE.finditer(v.get("pieces") or ""):
            if m.group(1) != "ComponentSmeltery":
                continue
            box = tuple(int(g) for g in m.groups()[1:])
            dd = box_dist_xz(sx, sz, box)
            cx, cz = (box[0] + box[3]) / 2, (box[2] + box[5]) / 2
            if smeltery is None or dd < smeltery[0]:
                smeltery = (dd, int(cx), int(cz))
        for ch in v.get("chests") or []:
            chest = ch.get("chest")
            if not chest:
                continue
            n = chest_counts(chest)[0]
            if n == 0:
                continue
            x, y, z = chest.get("pos", (0, 0, 0))
            steel_sources.append((dist(sx, sz, x, z), x, y, z, n, "blacksmith"))

    for ms in row.get("mineshafts") or []:
        for chest in ms.get("chests") or []:
            n = chest_counts(chest)[0]
            if n == 0:
                continue
            x, y, z = chest.get("pos", (0, 0, 0))
            steel_sources.append((dist(sx, sz, x, z), x, y, z, n, "minecart"))

    steel_in = [s for s in steel_sources if s[0] <= d]
    steel_total = sum(s[4] for s in steel_in)

    # Tool parts: TOOLS chests over every dungeon in the row.
    part_chests = []  # (dist, x, y, z, counts, items_desc)
    for dg in row.get("dungeons") or []:
        for chest in dg.get("chests") or []:
            if chest.get("ttype") != "TOOLS":
                continue
            c = chest_counts(chest)
            if not any(c[1:]):
                continue
            x, y, z = chest.get("pos", (0, 0, 0))
            names = [f"{it.get('n', 1)}x {it.get('name')}" for it in chest.get("items") or []
                     if any(classify(it.get("name"))[1:])]
            part_chests.append((dist(sx, sz, x, z), x, y, z, c, "; ".join(names)))
    parts_in = [p for p in part_chests if p[0] <= d]
    agg = [0, 0, 0, 0, 0]
    for p in parts_in:
        for i in range(5):
            agg[i] += p[4][i]

    pois = ([s[0] for s in steel_in] + [p[0] for p in parts_in]
            + ([smeltery[0]] if smeltery else []))
    return {
        "seed": row["seed"], "spawn": (sx, sz), "smeltery": smeltery,
        "steel_total": steel_total, "steel": steel_in, "agg": agg, "parts": parts_in,
        "span": max(pois) if pois else 0.0,
    }


def passes(h, args):
    ok = (h["steel_total"] >= args.steel and h["agg"][1] >= args.alumite
          and h["agg"][2] >= args.plate and h["agg"][3] >= args.hammer
          and h["agg"][4] >= args.rod)
    if args.require_smeltery:
        ok = ok and h["smeltery"] is not None and h["smeltery"][0] <= args.dist
    return ok


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("jsonl", nargs="+")
    ap.add_argument("--dist", type=float, default=320)
    ap.add_argument("--steel", type=int, default=4)
    ap.add_argument("--alumite", type=int, default=2)
    ap.add_argument("--plate", type=int, default=4)
    ap.add_argument("--hammer", type=int, default=1)
    ap.add_argument("--rod", type=int, default=1)
    ap.add_argument("--top", type=int, default=20)
    ap.add_argument("--no-require-smeltery", dest="require_smeltery", action="store_false")
    args = ap.parse_args()

    kills = Counter()
    steel_hist = Counter()   # steel_total on kill:steel rows
    tool_hist = {k: Counter() for k in ("alumite_plate", "plate", "hammer_head", "tough_rod")}
    rows = disagree = 0
    hits = []
    for path in args.jsonl:
        for line in open(path):
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as e:
                print(f"  ! bad row in {path}: {e}", file=sys.stderr)
                continue
            rows += 1
            k = row.get("kill")
            if k:
                kills[k] += 1
                if k == "steel":
                    steel_hist[row.get("steel_total", 0)] += 1
                if k == "toolloot":
                    for key in tool_hist:
                        tool_hist[key][row.get(key, 0)] += 1
                continue
            h = measure(row, args.dist)
            if h is None:
                continue
            # Independent recount vs the Java gate's own counter, comparable only when this run uses
            # the same distance the Java gate defaulted to.
            if "steel_total" in row and args.dist == 320 and h["steel_total"] != row["steel_total"]:
                disagree += 1
                print(f"  ! seed {row['seed']}: python steel recount {h['steel_total']} != "
                      f"Java steel_total {row['steel_total']}", file=sys.stderr)
            if passes(h, args):
                hits.append(h)

    print(f"{rows} rows; kills: {dict(kills)}" + (f"; {disagree} JAVA/PYTHON DISAGREEMENTS" if disagree else ""))
    if steel_hist:
        print("steel_total distribution on kill:steel rows (bar-tuning input):")
        for v in sorted(steel_hist):
            print(f"  {v:3d}: {steel_hist[v]}")
    for key, hist in tool_hist.items():
        if hist:
            print(f"{key} distribution on kill:toolloot rows:")
            for v in sorted(hist):
                print(f"  {v:3d}: {hist[v]}")

    hits.sort(key=lambda h: (-h["steel_total"], -h["agg"][1], h["span"]))
    print(f"\n{len(hits)} seeds meet every bar "
          f"(dist<={args.dist:.0f}, steel>={args.steel}, alumite>={args.alumite}, "
          f"plate>={args.plate}, hammer>={args.hammer}, rod>={args.rod})")
    for h in hits[:args.top]:
        sx, sz = h["spawn"]
        print(f"\nseed {h['seed']}  spawn {sx},{sz}  steel={h['steel_total']}  "
              f"alumite={h['agg'][1]} plate={h['agg'][2]} hammer={h['agg'][3]} rod={h['agg'][4]}  "
              f"span={h['span']:.0f}")
        if h["smeltery"]:
            print(f"  smeltery {h['smeltery'][0]:.0f} blocks   /tp {h['smeltery'][1]} {SKY_Y} {h['smeltery'][2]}")
        for dd, x, y, z, n, src in sorted(h["steel"]):
            yy = SKY_Y if src == "blacksmith" else y + 1  # village chest Y is nominal, minecart Y exact
            print(f"  {n}x Block of Steel ({src}, {dd:.0f} blocks)   /tp {x} {yy} {z}")
        for dd, x, y, z, c, desc in sorted(h["parts"]):
            print(f"  TOOLS chest ({dd:.0f} blocks): {desc}   /tp {x} {y + 1} {z}")


if __name__ == "__main__":
    main()
