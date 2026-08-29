#!/usr/bin/env python3
"""Compare chest sets between two probe search reports.

Usage: cmpchests.py A.json B.json [--region x0 x1 z0 z1]
Reports: chests only in A, only in B, and same-position content diffs.
Content key is (slot, id, damage, count) per the corpus README's comparison rule.
"""
import json
import sys


def chests(path):
    d = json.load(open(path))
    out = {}
    for c in d["search"]["chunks"].values():
        for ct in c.get("chests", []):
            out[tuple(ct["pos"])] = ct
    return out


def content(ct):
    return sorted((i["s"], i["id"], i["d"], i["n"]) for i in ct["items"])


def names(ct):
    return sorted("%dx %s" % (i["n"], i["name"]) for i in ct["items"])


a, b = chests(sys.argv[1]), chests(sys.argv[2])
box = None
if "--region" in sys.argv:
    i = sys.argv.index("--region")
    box = list(map(int, sys.argv[i + 1:i + 5]))


def inbox(p):
    return box is None or (box[0] <= p[0] <= box[1] and box[2] <= p[2] <= box[3])


ka = {p for p in a if inbox(p)}
kb = {p for p in b if inbox(p)}
print(f"A={sys.argv[1]}  {len(ka)} chests")
print(f"B={sys.argv[2]}  {len(kb)} chests")
print()
onlya, onlyb = sorted(ka - kb), sorted(kb - ka)
print(f"--- only in A ({len(onlya)}) ---")
for p in onlya:
    print(" ", p, names(a[p])[:5])
print(f"--- only in B ({len(onlyb)}) ---")
for p in onlyb:
    print(" ", p, names(b[p])[:5])

diffs = [p for p in sorted(ka & kb) if content(a[p]) != content(b[p])]
print(f"--- same position, different contents ({len(diffs)} of {len(ka & kb)} shared) ---")
for p in diffs:
    na, nb = names(a[p]), names(b[p])
    print(" ", p)
    print("    A:", na)
    print("    B:", nb)
