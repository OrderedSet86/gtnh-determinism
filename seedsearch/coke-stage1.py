#!/usr/bin/env python3
"""Stage-1 coke% evaluation: score REAL loot/terrain from full-gen probe search reports.

Covers exactly the criteria stage 0 cannot see (loot rolls and populate-stage blocks):

  marsh    Dezil's Marshmallow chests: total count + XZ distance to nearest from spawn
           (attribution: vanilla cave dungeons + rare witchery coven dispensers)
  paper4   chests with >= 4 Paper: count + nearest distance (stage 0 only predicts the
           village Photoshop piece; dungeons carry most of the paper)
  heads    TiC Shovel/Axe Head items in chests ("<material> Shovel Head"/"Axe Head",
           patterns excluded): nearest distance + best material list
  coal     Coal items in chests: total + nearest >= --coal-min stack
  clay/gravel  real per-chunk block counts (format-2 fields) -> nearest useful chunk

Merges with the stage-0 ranking when --stage0 <finalists-r8.jsonl> is given: combined
score = stage-0 score + capped stage-1 distances (marsh + paper4 + heads), so a seed
with a perfect village but no marshmallows sinks.

Usage:
  coke-stage1.py <stage1-report-dir> [--stage0 finalists-r8.jsonl] [--top 30]
                 [--cap 512] [--surface-y 64] [--coal-min 8] [--csv out.csv]
"""
import argparse
import csv
import glob
import json
import math
import os
import re
import sys

HEAD_RE = re.compile(r'(Shovel|Axe) Head$')


def nearest(px, pz, points):
    best = None
    for (x, z, *rest) in points:
        d = math.hypot(x - px, z - pz)
        if best is None or d < best[0]:
            best = (d, x, z) + tuple(rest)
    return best


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("report_dir")
    ap.add_argument("--stage0", help="stage-0 prefilter JSONL (finalists-r8) to merge scores")
    ap.add_argument("--top", type=int, default=30)
    ap.add_argument("--cap", type=float, default=512.0)
    ap.add_argument("--surface-y", type=int, default=64)
    ap.add_argument("--coal-min", type=int, default=8)
    ap.add_argument("--clay-min", type=int, default=32,
                    help="min real clay blocks in a chunk to count as a clay source")
    ap.add_argument("--csv", help="write the full merged table here")
    args = ap.parse_args()

    stage0 = {}
    if args.stage0:
        # reuse coke-rank's scoring for the stage-0 component
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import subprocess
        out = subprocess.run(
            [sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "coke-rank.py"),
             args.stage0, "--top", "100000", "--require", "paper,tic,furnace"],
            capture_output=True, text=True).stdout
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 4 and parts[0].replace(".", "").lstrip("-").isdigit() \
                    and parts[1].lstrip("-").isdigit():
                stage0[int(parts[1])] = float(parts[0])

    rows = []
    for fp in sorted(glob.glob(os.path.join(args.report_dir, "seed-*.json"))):
        try:
            d = json.load(open(fp))
        except Exception as e:
            print(f"skip {os.path.basename(fp)}: {e}", file=sys.stderr)
            continue
        seed = int(d.get("seed", os.path.basename(fp)[5:-5]))
        search = d.get("search", {})
        spawn = search.get("spawn", [0, 0, 0])
        px, pz = spawn[0], spawn[2]

        marsh, paper4, heads, coals = [], [], [], []
        clay_chunks, gravel_chunks = [], []
        for ck, cd in search.get("chunks", {}).items():
            cx, cz = (int(v) for v in ck.split(","))
            if cd.get("clay", 0) >= args.clay_min:
                clay_chunks.append((cx * 16 + 8, cz * 16 + 8))
            if cd.get("gravel", 0) >= 64:
                gravel_chunks.append((cx * 16 + 8, cz * 16 + 8))
            for ch in cd.get("chests", []):
                x, y, z = ch.get("pos", [0, 0, 0])
                p = m = c = 0
                hd = []
                for it in ch.get("items", []):
                    n = str(it.get("name", ""))
                    cnt = it.get("n", 0)
                    if n == "Paper":
                        p += cnt
                    elif n == "Dezil's Marshmallow":
                        m += cnt
                    elif n == "Coal":
                        c += cnt
                    elif HEAD_RE.search(n) and "Pattern" not in n:
                        hd.append(n)
                if m:
                    marsh.append((x, z, y, m))
                if p >= 4:
                    paper4.append((x, z, y, p))
                if c >= args.coal_min:
                    coals.append((x, z, y, c))
                for h in hd:
                    heads.append((x, z, y, h))

        n_marsh = sum(t[3] for t in marsh)
        n_paper4 = len(paper4)
        n_coal = sum(t[3] for t in coals)
        d_marsh = nearest(px, pz, marsh)
        d_paper = nearest(px, pz, paper4)
        d_heads = nearest(px, pz, heads)
        d_coal = nearest(px, pz, coals)
        d_clay = nearest(px, pz, clay_chunks)
        d_gravel = nearest(px, pz, gravel_chunks)

        s1 = (min(d_marsh[0], args.cap) if d_marsh else args.cap) \
            + (min(d_paper[0], args.cap) if d_paper else args.cap) \
            + (min(d_heads[0], args.cap) if d_heads else args.cap)
        s0 = stage0.get(seed)
        total = s1 + (s0 if s0 is not None else 0)
        rows.append({
            "score": total, "s0": s0, "s1": s1, "seed": seed,
            "spawn": f"{px},{pz}",
            "marsh_n": n_marsh, "marsh_d": round(d_marsh[0]) if d_marsh else None,
            "paper4_n": n_paper4, "paper_d": round(d_paper[0]) if d_paper else None,
            "head_d": round(d_heads[0]) if d_heads else None,
            "head": d_heads[4] if d_heads else "",
            "coal_n": n_coal, "coal_d": round(d_coal[0]) if d_coal else None,
            "clay_d": round(d_clay[0]) if d_clay else None,
            "gravel_d": round(d_gravel[0]) if d_gravel else None,
        })

    rows.sort(key=lambda r: r["score"])
    fmt = ("{score:>7.0f}  {seed:>20}  {spawn:>13}  {marsh_n:>5} {marsh_d!s:>6}  "
           "{paper4_n:>4} {paper_d!s:>6}  {head_d!s:>6} {head:<22.22}  "
           "{coal_n:>4} {coal_d!s:>6}  {clay_d!s:>6} {gravel_d!s:>6}")
    print(f"{'score':>7}  {'seed':>20}  {'spawn x,z':>13}  {'marsh n/d':>12}  "
          f"{'p4 n/d':>11}  {'head d/type':>29}  {'coal n/d':>11}  {'clay':>6} {'gravl':>6}")
    for r in rows[:args.top]:
        print(fmt.format(**{k: ("-" if v is None else v) if k != "score" else v
                            for k, v in r.items()}))
    if args.csv:
        with open(args.csv, "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)
        print(f"\n{len(rows)} seeds -> {args.csv}", file=sys.stderr)


if __name__ == "__main__":
    main()
