#!/usr/bin/env python3
"""Compare population-order traces of two probe JSONs; show first divergence with context.

Exit 0 only when two non-empty sequences match; non-zero when they diverge and non-zero, with an
explicit message on stderr, when either trace is empty. A probe run
without popseq recording, or one that died before populating anything, yields two empty lists that
compare equal — which printed SEQUENCES IDENTICAL and exited 0 over no data at all.
"""
import json
import sys

a = json.load(open(sys.argv[1]))
b = json.load(open(sys.argv[2]))
sa, sb = a.get("popseq", []), b.get("popseq", [])
print(f"A: {len(sa)} populations, B: {len(sb)} populations")
if not sa or not sb:
    empty = [p for p, s in ((sys.argv[1], sa), (sys.argv[2], sb)) if not s]
    sys.exit(f"NO COMPARISON PERFORMED: no popseq trace in {' and '.join(empty)} — the probe was "
             f"not recording population order, or the run died first. Not a pass.")
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
# The sequences differ if control reached here; say so in the exit status too. This script used to
# fall off the end and exit 0 on every path, so any caller gating on it saw a pass regardless.
sys.exit(1)
