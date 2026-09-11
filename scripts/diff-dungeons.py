#!/usr/bin/env python3
"""Compare vanilla WorldGenDungeons ROOMS between two probe runs, and the chests inside them.

Usage: diff-dungeons.py <armA> <armB> [--verbose] [--show N] [--allow-jar-mismatch]

Each arm is a directory of probe logs, or a glob when both arms share one directory — which is the
warm-shard.sh layout, where every order lands in the same output dir as ``<order>-shard<i>.log``::

    diff-dungeons.py out/'rows-shard*.log' out/'spiral-shard*.log'

Quote the globs so the shell does not expand them. Logs carry ``[dungeonattempt] seed= x= y= z=
built=`` lines and optionally ``[chesttrace]`` lines. Runs are matched by the seed each line carries,
never by log position — warm generates its own boot world on ``level-seed=1`` before the batch and
those traces are interleaved into the same file.

Why this exists
---------------
``results/2026-09-08-vanilla-dungeon-determinism/README.md`` reports the three-mechanism dungeon fix
reaching 0 existence differences and "24 of 24 seeds identical", measured attempt-level. Measured
chest-level on 25 seeds, dungeon chests differ on 24 of 25. Those two statements were produced by
different, ad-hoc scripts, and only one of them was ever committed — so the disagreement could not be
audited. This tool is the committed version of the attempt-level half.

It separates the two things a raw chest diff conflates:

  1. ROOM existence   - a ``built=true`` anchor present in one run and not the other
  2. CHEST placement  - the same room in both runs, holding chests at different positions

**This tool measures what the generator DID, not what the world HOLDS.** Its chest set comes from
``[chesttrace]``, so a chest that was filled and then destroyed still counts as placed. That is the
right unit for judging the placement code and the wrong one for judging the world: measured
2026-09-10, room existence and chest placement were both identical across walk orders (0 differences)
while **13 vanilla dungeon chest positions still differed in the probe's search report**, because
overlapping rooms carve through each other's chests and which room runs second depends on chunk
order. Use
``scripts/diff-chests.py`` against the search reports for the world-level question, and do not quote
this tool's 0 as if it answered that.

Vanilla places up to TWO chests per room, each with three tries::

    for (int l2 = 0; l2 < 2; ++l2)
        for (int i3 = 0; i3 < 3; ++i3) {
            int j3 = x + rand.nextInt(l * 2 + 1) - l;
            int k3 = z + rand.nextInt(i1 * 2 + 1) - i1;
            if (world.isAirBlock(j3, l1, k3)) { ...if exactly one adjacent solid... setBlock(chest); break; }
        }

so one identical room can yield 0, 1 or 2 chests depending on live block reads and on where the
shared populate stream happens to be. A per-seed chest total of 118 against 119 is therefore equally
consistent with "one extra room" and with "same rooms, one placement slot failed". Only the anchors
tell them apart, which is the whole point of reading them separately here.
"""
import argparse
import importlib.util
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

# Which jar wrote these logs. Imported by path because the filename is not a valid module identifier —
# the same idiom diff-region-blocks.py uses for probe-provenance.py.
_bspec = importlib.util.spec_from_file_location(
    "build_stamp", Path(__file__).resolve().parent / "build-stamp.py")
stamp = importlib.util.module_from_spec(_bspec)
_bspec.loader.exec_module(stamp)

ATTEMPT = re.compile(r"\[dungeonattempt\] seed=(-?\d+) x=(-?\d+) y=(-?\d+) z=(-?\d+) built=(\w+)")
CHEST = re.compile(r"\[chesttrace\] seed=(-?\d+) .*?piece=(\S+) .*?abs=(-?\d+),(-?\d+),(-?\d+) cat=(\S+)")

# A room's chests sit within the half-extents (2..3 each way) at the floor, so a generous XZ box and a
# short Y window attributes a chest to its room without needing the extents replayed.
ROOM_DX = 5
ROOM_DY = 4


def logs_for(spec):
    """Log files for one arm, from a directory OR a glob.

    Both are needed because the two arms are not always in separate directories: warm-shard.sh writes
    every arm into one output dir as ``<order>-shard<i>.log``, so pointing this tool at that directory
    would silently merge rows and spiral into a single set and report them as identical. Pass
    ``.../multi/'rows-shard*.log'`` and ``.../multi/'spiral-shard*.log'`` in that layout.
    """
    p = Path(spec)
    if p.is_dir():
        return sorted(p.glob("*.log"))
    return sorted(Path(p.parent or ".").glob(p.name))


# The server generates its own world on level-seed=1 before the first requested seed, and
# [dungeonattempt] does not go through TraceScope the way [chesttrace] does, so those rooms land in
# the same log. Left in, they inflate the room totals and add a phantom seed to the comparison —
# measured on a 25-seed run: 14 boot-world rooms reported as a 26th seed.
BOOT_SEED = 1


def scan(d, keep_boot=False):
    """-> (built, chests, tried) keyed by seed.

    ``tried`` is the set of CHUNKS the arm made any dungeon attempt in, built or not. It bounds the
    comparison so that a walk order which reached one chunk further than the other does not have its
    extra rooms scored as existence differences. On the 2026-09-10 runs it drops nothing, so it is a
    guard rather than a correction — but the alternative is reporting a window boundary as
    nondeterminism, which is not a mistake worth making twice.

    With the recovery below correct, every chunk shows exactly 8 attempts in both walk orders and no
    chunk's count differs — there is no repeat population and no attempt-count divergence. An earlier
    reading claimed otherwise; it was an artifact of ``x >> 4``.
    """
    built, chests, tried = defaultdict(set), defaultdict(set), defaultdict(set)
    logs = logs_for(d)
    if not logs:
        sys.exit(f"no log files matched {d} — nothing to compare")
    for f in logs:
        with f.open(errors="replace") as fh:
            for ln in fh:
                m = ATTEMPT.search(ln)
                if m:
                    s = int(m.group(1))
                    if keep_boot or s != BOOT_SEED:
                        x, z = int(m.group(2)), int(m.group(4))
                        # (x - 8) >> 4, NOT x >> 4. Stock computes x = chunkX*16 + nextInt(16) + 8 with
                        # the draw in [0,15], so the coordinate spans chunkX*16+8 .. chunkX*16+23 and a
                        # plain x >> 4 splits one chunk's eight attempts across two apparent chunks.
                        # Measured with the wrong form: an attempts-per-chunk histogram spread over
                        # 1..20 that looked like widespread repeat population, and a phantom
                        # "3 attempts vs 5" divergence built from two different chunks' attempts.
                        # With the right form every chunk shows exactly 8, in both walk orders.
                        tried[s].add(((x - 8) >> 4, (z - 8) >> 4))
                        if m.group(5) == "true":
                            built[s].add((x, int(m.group(3)), z))
                    continue
                m = CHEST.search(ln)
                # piece=none + dungeonChest is the vanilla room; Thaumcraft hilltop shares that pair, so
                # it is filtered out later by whether it sits near a built anchor.
                if m and m.group(2).endswith("none") and m.group(6) == "dungeonChest":
                    chests[int(m.group(1))].add((int(m.group(3)), int(m.group(4)), int(m.group(5))))
    return built, chests, tried


def chests_of(anchor, chests):
    x, y, z = anchor
    return {c for c in chests
            if abs(c[0] - x) <= ROOM_DX and abs(c[2] - z) <= ROOM_DX and 0 <= c[1] - y <= ROOM_DY}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dirA")
    ap.add_argument("dirB")
    ap.add_argument("--show", type=int, default=6, help="example differing rooms to print per class")
    ap.add_argument("--keep-boot", action="store_true",
                    help=f"include the warm boot world (level-seed={BOOT_SEED}); excluded by default")
    ap.add_argument("--allow-jar-mismatch", action="store_true",
                    help="compare arms built from different jars or run with different gtnhdet.* levers")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    # Before anything is read, so a cross-jar comparison never reaches the point of printing a verdict.
    stamp.check(args.dirA, args.dirB, Path(args.dirA).name, Path(args.dirB).name,
                allow_mismatch=args.allow_jar_mismatch)

    ba, ca, ta = scan(args.dirA, args.keep_boot)
    bb, cb, tb = scan(args.dirB, args.keep_boot)
    seeds = sorted(set(ba) & set(bb))
    if not seeds:
        # An empty comparison must never read as a pass. diff-chests.py printed "ALL SEEDS IDENTICAL"
        # over zero seeds once, and it looked exactly like a green run.
        sys.exit(f"no seed appears in both runs ({len(ba)} vs {len(bb)} seeds) — nothing was compared")

    tot = Counter()
    room_ex, chest_moved = [], []
    for s in seeds:
        # Only chunks BOTH arms attempted in. Outside that, one walk simply went further.
        shared = ta[s] & tb[s]
        inwin = lambda r: ((r[0] - 8) >> 4, (r[2] - 8) >> 4) in shared
        A = {r for r in ba[s] if inwin(r)}
        B = {r for r in bb[s] if inwin(r)}
        tot["rooms_outside_window"] += len(ba[s]) - len(A) + len(bb[s]) - len(B)
        onlyA, onlyB = A - B, B - A
        tot["rooms_A"] += len(A)
        tot["rooms_B"] += len(B)
        tot["room_only_A"] += len(onlyA)
        tot["room_only_B"] += len(onlyB)
        tot["seeds"] += 1
        if onlyA or onlyB:
            tot["seeds_with_room_diff"] += 1
            for r in list(onlyA)[:2]:
                room_ex.append((s, "only-" + Path(args.dirA).name, r))
            for r in list(onlyB)[:2]:
                room_ex.append((s, "only-" + Path(args.dirB).name, r))
        # Chests inside rooms BOTH runs built: any difference there is placement, not existence.
        for r in A & B:
            xa, xb = chests_of(r, ca[s]), chests_of(r, cb[s])
            if xa != xb:
                tot["rooms_with_chest_diff"] += 1
                tot["chest_pos_diffs"] += len(xa ^ xb)
                if len(xa) != len(xb):
                    tot["rooms_with_chest_COUNT_diff"] += 1
                if len(chest_moved) < args.show:
                    chest_moved.append((s, r, sorted(xa), sorted(xb)))

    print(f"=== diff-dungeons: {tot['seeds']} seeds compared ===")
    print(f"rooms built            : A={tot['rooms_A']}  B={tot['rooms_B']}"
          f"   ({tot['rooms_outside_window']} dropped: chunk reached by only one walk)")
    print(f"ROOM existence diffs   : {tot['room_only_A']} only-A + {tot['room_only_B']} only-B"
          f"   ({tot['seeds_with_room_diff']} of {tot['seeds']} seeds affected)")
    print(f"rooms in BOTH runs with differing chests : {tot['rooms_with_chest_diff']}"
          f"  ({tot['chest_pos_diffs']} chest positions)")
    print(f"  of those, a different NUMBER of chests : {tot['rooms_with_chest_COUNT_diff']}")

    if room_ex:
        print(f"\nexample rooms present in only one run (showing {min(len(room_ex), args.show)}):")
        for s, side, r in room_ex[:args.show]:
            print(f"  seed {s:>21} {side:>14} {r}")
    if chest_moved:
        print(f"\nexample rooms in both runs whose chests moved (showing {len(chest_moved)}):")
        for s, r, xa, xb in chest_moved:
            print(f"  seed {s:>21} room {r}\n      A: {xa}\n      B: {xb}")

    bad = tot["room_only_A"] + tot["room_only_B"] + tot["chest_pos_diffs"]
    print(f"\nVERDICT: {'IDENTICAL' if bad == 0 else str(bad) + ' differences'}"
          f" over {tot['seeds']} seeds")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
