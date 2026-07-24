#!/usr/bin/env python3
"""Compare population-order traces of two probe JSONs; show first divergence with context."""
import json
import sys

a = json.load(open(sys.argv[1]))
b = json.load(open(sys.argv[2]))
sa, sb = a.get("popseq", []), b.get("popseq", [])
print(f"A: {len(sa)} populations, B: {len(sb)} populations")
if sa == sb:
    print("SEQUENCES IDENTICAL — order is not the divergence mechanism")
    sys.exit(0)
n = min(len(sa), len(sb))
for i in range(n):
    if sa[i] != sb[i]:
        lo = max(0, i - 6)
        print(f"first divergence at index {i}:")
        print("  A:", " ".join(sa[lo:i]), "| >>", " ".join(sa[i:i+8]))
        print("  B:", " ".join(sb[lo:i]), "| >>", " ".join(sb[i:i+8]))
        break
else:
    print(f"common prefix identical; lengths differ ({len(sa)} vs {len(sb)})")
    print("  A tail:", " ".join(sa[n:n+10]))
    print("  B tail:", " ".join(sb[n:n+10]))
sa_set, sb_set = set(sa), set(sb)
print("only in A:", sorted(sa_set - sb_set)[:12])
print("only in B:", sorted(sb_set - sa_set)[:12])
