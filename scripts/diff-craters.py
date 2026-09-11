#!/usr/bin/env python3
"""Compare AE2 meteorite CRATER decisions between two walk orders, from probe logs.

Usage: diff-craters.py <run-dir> [--a rows] [--b spiral] [--show N]

Why not a terrain diff
----------------------
The obvious way to ask "is the crater route-dependent" is to diff blocks around each meteorite. It
does not work: blocks in this pack are roughly 75% route-dependent chunk-wise, and a crater is a few
thousand blocks against that background. The signal drowns.

So diff the DECISION instead of its consequences. ``MeteoritePlacer`` reduces the whole question to
one integer, ``skyMode``, computed in the constructor:

    skyMode = count of blocks in x-15..x+14, y-15..y+10, z-15..z+14 with canBlockSeeTheSky
    if any block in the column y-15..y-2 is air:  skyMode = 0     # the `solid` check

    skyMode > 10  ->  placeCrater
    skyMode >  3  ->  decay (the collapse/scatter pass)
    otherwise     ->  body and chest placed, NO crater, NO fallout

``Ae2MeteoritePlacerTraceMixin`` logs it per attempt under ``-Dgtnhdet.meteortrace=true``. One
integer per meteorite, immune to the block noise.

This matters because BOTH inputs to skyMode are live-world reads taken in the constructor, and
``Ae2MeteoriteSitingMixin`` redirects ``spawnMeteoriteCenter`` only — so nothing in the determinism
fix covers crater carving. That is what this measures.

Keying
------
Never key on log position. A warm batch runs every seed in one JVM and boots its own world on
level-seed 1 first, so traces from several worlds land in one file interleaved with the batch. Lines
are attributed to the most recent ``[probe] seed=`` marker above them; anything before the first
marker is boot-world output and is dropped.

``spawnMeteoriteCenter`` is called repeatedly per meteorite — the 20-step descent — so most lines are
rejected attempts. Only the ``-> true`` line is a placed meteorite.
"""
import argparse
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

SEED_RE = re.compile(r"\[probe\] seed=(-?\d+) dim=(-?\d+) order=(\w+)")
TRACE_RE = re.compile(
    r"\[meteortrace\] spawnMeteoriteCenter at (-?\d+),(-?\d+),(-?\d+) -> (\w+)"
    r" skyMode=(-?\d+) crater=(\w+) decay=(\w+) size=([\d.]+)"
)
# The warm driver generates a throwaway world on this seed before the batch; its traces share the
# log file. Excluding it is not cosmetic -- it appears in every shard and would be counted N times.
BOOT_SEED = 1


def load(run_dir: Path, order: str):
    """-> {seed: {(x,y,z): dict}} for PLACED meteorites, plus a Counter of attempts."""
    placed = defaultdict(dict)
    attempts = Counter()
    logs = sorted(run_dir.glob(f"{order}-shard*.log"))
    if not logs:
        sys.exit(f"no {order}-shard*.log under {run_dir}")
    for log in logs:
        seed = None
        for line in log.read_text(errors="replace").splitlines():
            m = SEED_RE.search(line)
            if m:
                seed = int(m.group(1)) if m.group(2) == "0" else None
                continue
            t = TRACE_RE.search(line)
            if not t or seed is None or seed == BOOT_SEED:
                continue
            x, y, z = int(t.group(1)), int(t.group(2)), int(t.group(3))
            attempts[seed] += 1
            if t.group(4) != "true":
                continue
            placed[seed][(x, y, z)] = {
                "skyMode": int(t.group(5)),
                "crater": t.group(6) == "true",
                "decay": t.group(7) == "true",
                "size": float(t.group(8)),
            }
    return placed, attempts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run_dir", type=Path)
    ap.add_argument("--a", default="rows")
    ap.add_argument("--b", default="spiral")
    ap.add_argument("--show", type=int, default=12)
    args = ap.parse_args()

    A, atA = load(args.run_dir, args.a)
    B, atB = load(args.run_dir, args.b)
    seeds = sorted(set(A) & set(B))
    if not seeds:
        # Comparing nothing must never read as a pass.
        sys.exit(f"no seed in both arms ({len(A)} vs {len(B)}) -- nothing compared")

    tot = Counter()
    diffs = []
    for s in seeds:
        a, b = A[s], B[s]
        tot["A"] += len(a)
        tot["B"] += len(b)
        for p in sorted(set(a) & set(b)):
            tot["shared"] += 1
            ra, rb = a[p], b[p]
            if ra["crater"] != rb["crater"]:
                tot["crater_differs"] += 1
                if len(diffs) < args.show:
                    diffs.append((s, p, "CRATER", ra, rb))
            elif ra["decay"] != rb["decay"]:
                tot["decay_differs"] += 1
                if len(diffs) < args.show:
                    diffs.append((s, p, "decay", ra, rb))
            elif ra["skyMode"] != rb["skyMode"]:
                # Same verdict either side of the threshold, different count. Not a visible
                # difference on its own, but it proves the input is route-dependent.
                tot["skymode_differs"] += 1
                if len(diffs) < args.show:
                    diffs.append((s, p, "skyMode", ra, rb))
        tot["only_A"] += len(set(a) - set(b))
        tot["only_B"] += len(set(b) - set(a))

    print(f"=== diff-craters: {len(seeds)} seeds, {args.a} vs {args.b} ===")
    print(f"placed meteorites: {args.a}={tot['A']}  {args.b}={tot['B']}  at an identical (x,y,z)={tot['shared']}")
    print(f"present in only one arm: {tot['only_A']} only-{args.a} + {tot['only_B']} only-{args.b}")
    print(f"\nof the {tot['shared']} shared meteorites:")
    print(f"  CRATER decision differs : {tot['crater_differs']}")
    print(f"  decay decision differs  : {tot['decay_differs']}")
    print(f"  skyMode value differs   : {tot['skymode_differs']}  (same side of both thresholds)")
    for s, p, kind, ra, rb in diffs:
        print(f"  {kind:>7} seed {s:>21} {p}: "
              f"skyMode {ra['skyMode']}->{rb['skyMode']} crater {ra['crater']}->{rb['crater']}")

    # Rate of buried-with-no-crater. Reported PER ARM, never pooled: the two arms are the same seeds
    # walked in a different order, so they are near-duplicates of each other. Pooling would double n
    # while adding almost no information and would quote a confidence interval roughly sqrt(2) too
    # tight. If the arms disagree, that disagreement is the route-dependence result above, not extra
    # sample.
    for tag, d in ((args.a, A), (args.b, B)):
        vals = [v for s in seeds for v in d[s].values()]
        if not vals:
            continue
        n = len(vals)
        nc = sum(1 for v in vals if not v["crater"])
        # Wilson score interval -- the normal approximation is unusable at these counts.
        p, z = nc / n, 1.96
        den = 1 + z * z / n
        mid = (p + z * z / (2 * n)) / den
        half = z / den * ((p * (1 - p) / n + z * z / (4 * n * n)) ** 0.5)
        print(f"\n{tag}: no-crater (skyMode <= 10) {nc}/{n} = {100 * p:.1f}%"
              f"  95% CI [{100 * max(0.0, mid - half):.1f}%, {100 * min(1.0, mid + half):.1f}%]")
        sm = [v["skyMode"] for v in vals]
        print(f"  skyMode: {sum(1 for v in sm if v == 0)} exactly 0, "
              f"{sum(1 for v in sm if 0 < v <= 10)} in 1..10, {sum(1 for v in sm if v > 10)} above 10")
        # A count landing between the two thresholds is the only way a crater can be lost without the
        # meteorite being fully buried. Measured so far: it never happens -- skyMode is 0 or large --
        # so AE2's `> 10` is effectively "is there any sky at all", and the `solid` cave check plus
        # total burial are what actually decide. Worth re-checking rather than assuming.
        near = sorted(v for v in sm if 0 < v <= 40)
        if near:
            print(f"  marginal (1..40, near the >10 cut): {near}")
    print(f"\nattempts logged: {args.a}={sum(atA.values())}  {args.b}={sum(atB.values())}")

    bad = tot["crater_differs"] + tot["decay_differs"] + tot["only_A"] + tot["only_B"]
    print(f"\nVERDICT: {'IDENTICAL' if bad == 0 else str(bad) + ' differences'} over {len(seeds)} seeds")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
