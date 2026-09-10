#!/usr/bin/env python3
"""Reduce N `-Dprobe.gencensus` dumps to the two questions they exist to answer.

    census-report.py <gencensus.json> [<gencensus.json> ...] [--scan consumers.json]

  1. Is the FML `IWorldGenerator` dispatch order stable? Those generators each get a freshly reseeded
     Random, so their order cannot skew anyone's draws — but every one of them writes blocks, and
     first-writer-wins makes the order decide who keeps the block. The order is a stable sort over an
     `ArrayList` copy of a `HashSet`, so equal weights tie-break in identity-hash order.
  2. Is the terraingen event dispatch order stable? Those handlers DO share the chunk's populate
     Random, so a reorder there moves every draw after it.

Both are reported per pair of arms, never as a single number, because "the order changed" means
nothing without a same-JVM same-jar pair to say what the floor is. Pass at least two arms from ONE
JVM version built from ONE jar set, or the comparison cannot separate a JVM effect from launch noise.
Identity-hash offsets move when the jar set changes, so arms built from different jars are not
comparable and the report refuses to pretend otherwise — check the md5s yourself before quoting it.

With `--scan`, also reconciles the source enumeration against what actually registered. The three
cardinalities and a named reason for every gap are the completeness argument; a bare list is not.
"""
import argparse
import itertools
import re
import json
from collections import defaultdict
from pathlib import Path


def load(paths):
    out = {}
    for p in paths:
        d = json.loads(Path(p).read_text())
        # Arms are keyed by filename stem, so two dumps with the same stem from different directories
        # would collapse into one arm and quietly shrink the comparison.
        if Path(p).stem in out:
            raise SystemExit(f"two dumps share the stem {Path(p).stem!r} ({p}); rename one — "
                             f"arms are keyed by stem and the second would replace the first")
        out[Path(p).stem] = d
    return out


def gen_order(d):
    return [g["class"] for g in d["generators"]]


def listener_sig(d, ev):
    # Strip the `@hashcode` off each handler's toString: the identity hash is exactly what varies and
    # comparing it would report every arm as different for a reason that is not the ordering.
    return [(e["kind"], e["who"].split("@")[0]) for e in d["listeners"][ev]["dispatch"]]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dumps", nargs="+")
    ap.add_argument("--scan", help="scan-populate-consumers.py --json output, for the reconciliation")
    args = ap.parse_args()

    arms = load(args.dumps)
    names = list(arms)
    # Cross-arm agreement needs two arms. With one, itertools.combinations yields no pairs and each
    # listener signature is compared only against itself, so the report ends with "every terraingen
    # dispatch order identical across all 1 arms: True" — a claim made over no comparison at all.
    if len(arms) < 2:
        raise SystemExit(f"NO COMPARISON PERFORMED: {len(arms)} arm(s) loaded. This report compares "
                         f"arms against each other; a single arm cannot disagree with itself. Pass at "
                         f"least two dumps from one JVM version and one jar set.")
    print(f"{len(arms)} arms\n")
    for n, d in arms.items():
        print(f"  {n:<16} jvm={d['jvm']:<26} hashCode={d['hashCodeMode']:<8} generators={len(d['generators'])}")

    # --- stream B: generator dispatch order -------------------------------------------------
    print("\n=== FML IWorldGenerator dispatch order (stream B: own Random, write order only) ===")
    print(f"{'pair':<26} identical  positions differing")
    for a, b in itertools.combinations(names, 2):
        A, B = gen_order(arms[a]), gen_order(arms[b])
        diff = sum(1 for x, y in zip(A, B) if x != y) + abs(len(A) - len(B))
        print(f"  {a} vs {b:<14} {str(A == B):<10} {diff}/{max(len(A), len(B))}")

    # A tie block is the unit that can actually move: distinct weights are pinned by the sort.
    weights = defaultdict(list)
    for g in arms[names[0]]["generators"]:
        weights[g["weight"]].append(g["class"])
    print("\n  per weight tie-block, order across arms:")
    for w in sorted(weights, key=lambda x: (x is None, x)):
        members = weights[w]
        seen = set()
        for n in names:
            blk = tuple(g["class"] for g in arms[n]["generators"] if g["weight"] == w)
            seen.add(blk)
        tag = "PINNED" if len(members) == 1 else ("STABLE" if len(seen) == 1 else f"{len(seen)} DISTINCT ORDERS")
        print(f"    weight {str(w):<12} {len(members):>2} member(s)  {tag}")
        if len(seen) > 1:
            for blk in sorted(seen):
                print("        " + " -> ".join(c.split(".")[-1] for c in blk))

    # --- stream A: event dispatch order ------------------------------------------------------
    print("\n=== terraingen event dispatch order (stream A: SHARED populate Random) ===")
    allsame = True
    # An arm with no recorded listeners leaves allsame at its initial True and prints a green line
    # under an empty table. No events recorded is a broken dump, not a stable one.
    if not arms[names[0]]["listeners"]:
        raise SystemExit(f"NO COMPARISON PERFORMED: arm {names[0]} recorded no terraingen listeners. "
                         f"An empty dispatch table cannot be stable or unstable.")
    for ev in arms[names[0]]["listeners"]:
        sigs = {tuple(listener_sig(arms[n], ev)) for n in names}
        n_entries = len(arms[names[0]]["listeners"][ev]["dispatch"])
        ok = len(sigs) == 1
        allsame &= ok
        bus = arms[names[0]]["listeners"][ev]["bus"]
        print(f"  {ev:<30} {bus:<16} entries={n_entries:<3} stable={ok}")
    print(f"\n  every terraingen dispatch order identical across all {len(arms)} arms: {allsame}")

    if not args.scan:
        return

    # --- reconciliation ----------------------------------------------------------------------
    scan = json.loads(Path(args.scan).read_text())
    # Source names a registration site; runtime names the instance's class. They are different
    # strings (`new QuartzWorldGen()` vs `appeng.worldgen.QuartzWorldGen`), so reconcile on the
    # simple class name and say so, rather than pretending to an exact match that does not exist.
    src_gen = set()
    for repo, r in scan.items():
        for g in r["generators"]:
            src_gen.add(g["class"])
        # Union in the registration sites too. A generator declared as `extends SomeBase` rather than
        # `implements IWorldGenerator` never appears in `generators` — EtFuturumLateWorldGenerator is
        # one — and reconciling on `generators` alone reports it as an unexplained runtime-only entry.
        for g in r["registrations"]:
            expr = g["generator"]
            m = re.search(r"new\s+([A-Z]\w*)", expr) or re.search(r"([A-Z]\w*)\.INSTANCE", expr)
            if m:
                src_gen.add(m.group(1))
    run_gen = {c.split(".")[-1].rstrip("$") for c in gen_order(arms[names[0]])}
    print("\n=== reconciliation: source enumeration vs what registered ===")
    print(f"  source IWorldGenerator classes : {len(src_gen)}")
    print(f"  registered at runtime          : {len(run_gen)}")
    only_src = sorted(src_gen - run_gen)
    only_run = sorted(run_gen - src_gen)
    print(f"\n  in SOURCE but not registered ({len(only_src)}) — each needs a named reason:")
    for c in only_src:
        print(f"    {c}")
    print(f"\n  registered but not in the source scan ({len(only_run)}) — closed-source, or a scan miss:")
    for c in only_run:
        print(f"    {c}")


if __name__ == "__main__":
    main()
