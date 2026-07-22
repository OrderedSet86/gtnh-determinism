#!/usr/bin/env python3
"""Compare two worldgen probe dumps (same seed, different chunk walk orders).

Usage: diff-probe.py a.json b.json

Exit 0 if the worlds are identical, 1 if any chunk differs.
"""
import json
import sys


def main() -> int:
    a_path, b_path = sys.argv[1], sys.argv[2]
    a = json.load(open(a_path))
    b = json.load(open(b_path))

    if a["seed"] != b["seed"]:
        print(f"WARNING: seeds differ ({a['seed']} vs {b['seed']}) — comparison is meaningless")

    keys = sorted(set(a["chunks"]) | set(b["chunks"]), key=lambda k: tuple(map(int, k.split(","))))
    diffs = []
    for k in keys:
        ha, hb = a["chunks"].get(k), b["chunks"].get(k)
        if ha != hb:
            diffs.append((k, ha, hb))

    total = len(keys)
    print(f"seed {a['seed']}  |  {a['order']} vs {b['order']}  |  {total} chunks compared")
    if not diffs:
        print("IDENTICAL — worldgen was deterministic across walk orders")
        return 0

    print(f"DIFFERENT — {len(diffs)}/{total} chunks differ ({100 * len(diffs) / total:.1f}%):")
    for k, ha, hb in diffs:
        print(f"  chunk {k:>9}  {ha[:12] if ha else 'missing':>12} != {hb[:12] if hb else 'missing':>12}")

    # crude clustering: bounding boxes of differing regions help find the culprit structure
    xs = [int(k.split(",")[0]) for k, _, _ in diffs]
    zs = [int(k.split(",")[1]) for k, _, _ in diffs]
    print(f"differing region bounds: x [{min(xs)}..{max(xs)}], z [{min(zs)}..{max(zs)}]  (chunk coords; multiply by 16 for blocks)")
    return 1


if __name__ == "__main__":
    sys.exit(main())
