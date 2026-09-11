#!/usr/bin/env python3
"""Compare AE2 meteorite placement between two probe runs (e.g. two walk orders).

Usage: diff-meteorites.py <dirA> <dirB> [--show N]

Each directory holds per-seed search reports from a run with ``PROBE_SEARCH=true``. Meteorites are
identified by container type: AE2 places a ``TileSkyChest`` at the crater, and since it never routes
through ``ChestGenHooks`` the type is the only marker — no ``chesttrace`` line will ever name one.

Why this needs its own tool
---------------------------
Meteorites are not placed during worldgen. ``MeteoriteWorldGen.generate`` only queues an
``IWorldCallable`` on AE2's ``TickHandler``; ``MeteoritePlacer`` runs later, on a server tick, and
reads the world as it stands at that moment. So the *decision* is seed-pure — it comes from
``Platform.seedFromGrid(rand, seed, gridX, gridZ)`` — while the *placement* is not obviously so.

That split is what this separates, the same way ``diff-dungeons.py`` separates a room that moved from
a room that stayed and put its chests elsewhere:

  1. EXISTENCE  - a meteorite in one run and not the other
  2. DRIFT      - both runs placed one nearby, at different coordinates

A position-keyed diff alone reports drift as two existence differences and doubles the count.

Caveat worth carrying
---------------------
The probe drains the callable queue at ONE point, after the walk and before the snapshot
(``probe.drainticks``), so every meteorite is placed against a fully generated world. A real client
fires those callables interleaved with generation. A clean result here therefore shows meteorites are
stable under the probe's drain discipline; it does NOT by itself show they are stable in normal play.
"""
import argparse
import json
import sys
from collections import Counter
from pathlib import Path

# A meteorite that "moved" is the same crater placed at a different spot; anything further apart than
# this is a different meteorite. AE2's grid cells are 707 blocks, so the gap between distinct
# meteorites is large and this bound is not delicate.
DRIFT = 64


def load(d):
    """-> {seed: ({(x,y,z)}, {chunk keys})}"""
    out = {}
    for f in sorted(Path(d).glob("seed-*.json")):
        if "veincache" in f.name:
            continue
        rep = json.loads(f.read_text())
        met, chunks = set(), set()
        for key, v in (rep.get("search") or {}).get("chunks", {}).items():
            chunks.add(key)
            for c in v.get("chests") or []:
                if c.get("type") == "TileSkyChest":
                    met.add(tuple(c["pos"]))
        out[rep["seed"]] = (met, chunks)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dirA")
    ap.add_argument("dirB")
    ap.add_argument("--show", type=int, default=10)
    args = ap.parse_args()

    A, B = load(args.dirA), load(args.dirB)
    seeds = sorted(set(A) & set(B))
    if not seeds:
        # Comparing nothing must never read as a pass.
        sys.exit(f"no seed in both runs ({len(A)} vs {len(B)}) — nothing compared")

    tot = Counter()
    drifted, only = [], []
    for s in seeds:
        (a, ca), (b, cb) = A[s], B[s]
        shared = ca & cb
        inwin = lambda p: f"{p[0] >> 4},{p[2] >> 4}" in shared
        a = {p for p in a if inwin(p)}
        b = {p for p in b if inwin(p)}
        tot["A"] += len(a)
        tot["B"] += len(b)
        tot["same"] += len(a & b)
        onlyA, onlyB = a - b, b - a
        used = set()
        for p in sorted(onlyA):
            near = [q for q in sorted(onlyB)
                    if q not in used and abs(q[0] - p[0]) <= DRIFT and abs(q[2] - p[2]) <= DRIFT]
            if near:
                used.add(near[0])
                tot["drift"] += 1
                if len(drifted) < args.show:
                    drifted.append((s, p, near[0]))
            else:
                tot["only_A"] += 1
                if len(only) < args.show:
                    only.append((s, p, "only-A"))
        for q in sorted(onlyB):
            if q not in used:
                tot["only_B"] += 1
                if len(only) < args.show:
                    only.append((s, q, "only-B"))

    print(f"=== diff-meteorites: {len(seeds)} seeds ===")
    print(f"meteorites: A={tot['A']}  B={tot['B']}  identical position={tot['same']}")
    print(f"DRIFTED (same crater, moved <= {DRIFT} blocks): {tot['drift']}")
    print(f"EXISTENCE differences: {tot['only_A']} only-A + {tot['only_B']} only-B")
    for s, p, q in drifted:
        print(f"  drift  seed {s:>21} {p} -> {q}")
    for s, p, side in only:
        print(f"  {side:>7} seed {s:>21} {p}")
    bad = tot["drift"] + tot["only_A"] + tot["only_B"]
    print(f"\nVERDICT: {'IDENTICAL' if bad == 0 else str(bad) + ' differences'} over {len(seeds)} seeds")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
