#!/usr/bin/env python3
"""Rank biggest-crater seeds from stage-0 prefilter JSONL (-Dprobe.prefilter.meteor), and tune the
gate bars from its kill rows.

A row's "meteorites" array carries every candidate that passed the size gate, terrain-annotated:
predicted landing y (descent lattice), burial, local relief, and — for candidates that also cleared
the relief gate — the carved-volume integral of the crater paraboloid over virgin terrain
(volume/maxbite). Rejected candidates stay in the row with a reject reason (water|deep|buried|flat)
so the funnel is visible per seed rather than averaged away.

This tool re-derives the derived geometry from the RAW row fields (h from y and size, the flat-rim
width, the volume bound) rather than trusting the Java fields blindly — a disagreement prints
instead of surviving in both. The volume itself cannot be recomputed here (it needs the terrain
digest); what is checked is its geometric ceiling: volume <= pi*R^2 * (255 - h) and
maxbite <= 255 - floor(h) - hard bounds a counting bug would cross.

Kill rows ({"kill": "meteorvol", best_size, best_vol, candidates}) are first-class input: their
best_vol distribution is how the next sweep's bar is chosen without re-sweeping.

Usage:
  meteor-hunt.py <out.jsonl...> [--top 20] [--min-size 0] [--min-vol 0] [--max-dist 1e9]
                 [--volradius 96]
"""
import argparse
import json
import math
import sys
from collections import Counter


def rim_width(size, y, surface, burial):
    """Flat-ground crater width at the impact surface: 2*sqrt(50*(surface - h)), h = y - size + 1.
    The +2 sand/snow adjustCrater is not visible in the row; this is the default-Fallout figure."""
    h = y - size + 1
    if surface <= h:
        return 0.0
    return 2 * math.sqrt(50 * (surface - h))


def check_bounds(seed, m, volradius):
    """Hard geometric ceilings a Java counting bug would cross. Returns a list of complaints."""
    bad = []
    h = m["y"] - m["size"] + 1
    ceiling = math.pi * volradius * volradius * max(0, 255 - h)
    if m.get("volume", 0) > ceiling:
        bad.append(f"seed {seed}: volume {m['volume']} exceeds disc ceiling {ceiling:.0f}")
    if m.get("maxbite", 0) > 255 - math.floor(h):
        bad.append(f"seed {seed}: maxbite {m['maxbite']} exceeds 255 - h ({255 - math.floor(h)})")
    if m.get("burial", -1) >= 0 and m["burial"] != m["surface"] - m["y"]:
        bad.append(f"seed {seed}: burial {m['burial']} != surface - y ({m['surface'] - m['y']})")
    return bad


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("jsonl", nargs="+")
    ap.add_argument("--top", type=int, default=20)
    ap.add_argument("--min-size", type=float, default=0.0)
    ap.add_argument("--min-vol", type=int, default=0)
    ap.add_argument("--min-enclosure", type=int, default=0,
                    help="of 16 ring sectors reaching surface+30; 12+ = ravine through a massif")
    ap.add_argument("--max-dist", type=float, default=1e9)
    ap.add_argument("--volradius", type=int, default=96,
                    help="the -Dprobe.prefilter.meteor.volradius the sweep ran with (bound check)")
    args = ap.parse_args()

    kills = Counter()
    reject_hist = Counter()
    vol_hist = Counter()      # best_vol on kill:meteorvol rows, bucketed by 1000
    rows = disagree = 0
    hits = []                 # (volume, maxbite, seed, m)
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
                if k == "meteorvol":
                    vol_hist[int(row.get("best_vol", -1)) // 1000 * 1000] += 1
                    for m in row.get("candidates") or []:
                        reject_hist[m.get("reject", "scored")] += 1
                continue
            for m in row.get("meteorites") or []:
                reject_hist[m.get("reject", "scored")] += 1
                if m.get("reject"):
                    continue
                for msg in check_bounds(row["seed"], m, args.volradius):
                    disagree += 1
                    print(f"  ! {msg}", file=sys.stderr)
                if (m["size"] >= args.min_size and m["volume"] >= args.min_vol
                        and m.get("enclosure", 0) >= args.min_enclosure
                        and m["dist"] <= args.max_dist):
                    hits.append((m["volume"], m["maxbite"], row["seed"], m))

    print(f"{rows} rows; kills: {dict(kills)}; candidates by outcome: {dict(reject_hist)}"
          + (f"; {disagree} BOUND VIOLATIONS" if disagree else ""))
    if vol_hist:
        print("best_vol distribution on kill:meteorvol rows (bar-tuning input, buckets of 1000):")
        for v in sorted(vol_hist):
            print(f"  {v:8d}: {vol_hist[v]}")

    hits.sort(key=lambda h: (-h[0], -h[1]))
    print(f"\n{len(hits)} craters meet every bar "
          f"(size>={args.min_size}, vol>={args.min_vol}, dist<={args.max_dist:.0f})")
    for vol, bite, seed, m in hits[:args.top]:
        rim = rim_width(m["size"], m["y"], m["surface"], m["burial"])
        print(f"\nseed {seed}  impact {m['x']},{m['z']}  ({m['dist']} blocks from origin)")
        print(f"  size={m['size']}  y={m['y']} surface={m['surface']} burial={m['burial']}"
              f"  relief={m['relief']}  volume={vol}  maxbite={bite}"
              f"  enclosure={m.get('enclosure', '?')}/16"
              f"  flat-rim~{rim:.0f} wide{'  LAVA' if m.get('lava') else ''}")
        print(f"  /tp {m['x']} {m['surface'] + 20} {m['z']}")


if __name__ == "__main__":
    main()
