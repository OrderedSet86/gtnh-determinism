#!/usr/bin/env python3
"""Compare two worldgen probe dumps (same seed, different chunk walk orders or launches).

Handles v1 dumps (chunk -> hash string) and v3 dumps (chunk -> {"b": blocks, "t": te, "s": [16 section hashes]}).
For v3, classifies differences (blocks vs tile entities) and shows the Y-section histogram of block noise.

Usage: diff-probe.py a.json b.json
Exit 0 if identical, 1 otherwise.
"""
import json
import sys
from collections import Counter


def main() -> int:
    a_path, b_path = sys.argv[1], sys.argv[2]
    a = json.load(open(a_path))
    b = json.load(open(b_path))

    if a["seed"] != b["seed"]:
        print(f"WARNING: seeds differ ({a['seed']} vs {b['seed']}) — comparison is meaningless")

    # "dim" arrived in format 6; absent means the report is an overworld walk. Without this check, diffing a
    # Twilight Forest report against an overworld one of the same seed prints "100% of chunks differ" and gives
    # no hint why.
    if a.get("dim", 0) != b.get("dim", 0):
        print(
            f"WARNING: dimensions differ ({a.get('dim', 0)} vs {b.get('dim', 0)}) — comparison is meaningless"
        )

    # merge the always-generated spawn-region extra hashes when both runs carry them
    if a.get("spawnextra") and b.get("spawnextra"):
        a["chunks"] = {**a["chunks"], **a["spawnextra"]}
        b["chunks"] = {**b["chunks"], **b["spawnextra"]}
        print(f"(including {len(a['spawnextra'])} spawn-region chunks outside the main window)")

    keys = sorted(set(a["chunks"]) | set(b["chunks"]), key=lambda k: tuple(map(int, k.split(","))))
    v3 = keys and isinstance(a["chunks"][keys[0]], dict)

    diffs, blocks_only, te_only, both = [], 0, 0, 0
    section_hist = Counter()
    for k in keys:
        ca, cb = a["chunks"].get(k), b["chunks"].get(k)
        if ca == cb:
            continue
        diffs.append(k)
        if v3 and ca and cb:
            bd = ca["b"] != cb["b"]
            td = ca["t"] != cb["t"]
            blocks_only += bd and not td
            te_only += td and not bd
            both += bd and td
            for i, (sa, sb_) in enumerate(zip(ca["s"], cb["s"])):
                if sa != sb_:
                    section_hist[i] += 1

    total = len(keys)
    print(f"seed {a['seed']}  |  {a['order']} vs {b['order']}  |  {total} chunks compared")
    if not diffs:
        print("IDENTICAL — worldgen was deterministic")
        return 0

    print(f"DIFFERENT — {len(diffs)}/{total} chunks differ ({100 * len(diffs) / total:.1f}%)")
    if v3:
        print(f"  blocks-only: {blocks_only}   te-only: {te_only}   both: {both}")
        if section_hist:
            print("  block noise by Y-section (section i = y in [16i, 16i+15]):")
            for i in range(16):
                n = section_hist.get(i, 0)
                if n:
                    print(f"    y {i * 16:>3}-{i * 16 + 15:<3}  {'#' * min(n, 60)} {n}")
    xs = [int(k.split(",")[0]) for k in diffs]
    zs = [int(k.split(",")[1]) for k in diffs]
    print(f"differing region bounds: x [{min(xs)}..{max(xs)}], z [{min(zs)}..{max(zs)}] (chunk coords)")
    print("differing chunks:", " ".join(diffs[:40]) + (" …" if len(diffs) > 40 else ""))
    return 1


if __name__ == "__main__":
    sys.exit(main())
