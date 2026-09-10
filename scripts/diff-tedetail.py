#!/usr/bin/env python3
"""Compare the tedetail sections of two probe JSONs: which TE classes differ and how.

Exit 0 when two non-empty sections agree, 1 when they differ, and non-zero with a message when
either report carries no tedetail at all — a probe built without TE detail recording produces two
empty sections, which printed three empty dicts and exited 0, the same output as a clean pair.
"""
import json, sys
from collections import Counter

a = json.load(open(sys.argv[1]))
b = json.load(open(sys.argv[2]))
ta, tb = a.get("tedetail", {}), b.get("tedetail", {})
if not ta or not tb:
    empty = [p for p, t in ((sys.argv[1], ta), (sys.argv[2], tb)) if not t]
    sys.exit(f"NO COMPARISON PERFORMED: no tedetail section in {' and '.join(empty)}. "
             f"Three empty dicts is an absent measurement, not a matching one.")

only_a, only_b, changed = Counter(), Counter(), Counter()
examples = {}
for chunk in sorted(set(ta) | set(tb)):
    ca, cb = ta.get(chunk, {}), tb.get(chunk, {})
    for k in set(ca) | set(cb):
        cls = k.split("@")[0].rsplit(".", 1)[-1]
        if k not in cb:
            only_a[cls] += 1
        elif k not in ca:
            only_b[cls] += 1
        elif ca[k] != cb[k]:
            changed[cls] += 1
            examples.setdefault(cls, f"{chunk}: {k} {ca[k]} vs {cb[k]}")

print(f"TEs only in A: {dict(only_a)}")
print(f"TEs only in B: {dict(only_b)}")
print(f"TEs with differing NBT hash: {dict(changed)}")
for cls, ex in examples.items():
    print(f"  example {cls}: {ex}")
sys.exit(0 if not (only_a or only_b or changed) else 1)
