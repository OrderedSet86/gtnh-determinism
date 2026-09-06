#!/usr/bin/env python3
"""Compare two probe vein-cache dumps: does the same seed resolve the same ore veins on both walks?

Usage: diff-veincache.py <A.veincache.json> <B.veincache.json> [--dim N --seed S] [--verbose]

The input is GregTech's own `GTWorldgenerator.validOreveins`, dumped by the probe's
`OreVeinTableDump` after a full-generation walk. One entry per ore-vein region:

    {"seed": <oreveinSeed>, "null": false, "layer": "ore.mix.iron", "minY": 19,
     "placement": {"generationSeed": .., "veinWestX": .., "veinEastX": ..,
                   "veinNorthZ": .., "veinSouthZ": .., "veinMinY": ..}}

`seed` is the region key — `(worldSeed << 16) ^ (dim << 56 | osX << 28 | osZ)` — so it identifies
the same region across walks regardless of the order the walk visited chunks in. Everything else is
the DECISION that region reached, and that decision is what the F4d ore pin exists to make
route-independent (see results/2026-09-05-gt-ore-canonical-trigger).

Three numbers come out, and they are not interchangeable:

  mix differs        `layer` resolved to a different ore mix. This is vein IDENTITY, the thing the
                     pin targets, and the number that must be zero.
  geometry differs   same mix, different bounding box or minY. Identity held, placement moved.
  only in one        the region exists in one walk's cache and not the other's. Counted separately
                     because it is invisible to a mix comparison over the intersection — a walk that
                     simply never decided a region would otherwise look perfectly stable.

The target is zero on all three. A nonzero result is a regression to investigate, not noise: the
measured same-order launch-pair floor for this metric is exactly 0 (results/2026-09-05-gt-ore-dryrun-virgin).

Exit status is 0 when all three are zero, 1 otherwise, so this can gate a run.

`--dim N --seed S` restricts to one dimension. The decode is `((key ^ (worldSeed << 16)) >> 56)`, the
same one vein-balance.py uses, and it was checked against the mix names rather than assumed: on seed
-1636594104014467454 an overworld walk yields 1764 dim-0 entries plus 99 that decode to dim 64, and
all 99 carry `ore.mix.ross128.*` mixes — Ross128b, a GalaxySpace planet whose dimension really is 64.
A Twilight Forest walk splits 1766 dim-7 / 102 dim-0 / 99 dim-64, and again every group's mixes match
its dimension. `validOreveins` is a static GregTech never clears, so a walk in one dimension carries
entries from every dimension the server touched — filtering is how you get a per-dimension number.

**Default to the unfiltered figure.** It compares every region the run decided, so it cannot hide a
difference in a dimension you forgot to ask about. That is how the one real finding of
results/2026-09-06-2.9.0-beta-3-compatibility surfaced: seven differing regions that a dim-0 filter
reported as a clean zero.
"""
import argparse
import json
import sys

MASK64 = (1 << 64) - 1

# Compared as a unit under "geometry". minY sits alongside the placement box because the two are one
# decision: GT resolves the vein's vertical window and its footprint together in resolveVeinPlacement.
PLACEMENT_KEYS = ("generationSeed", "veinWestX", "veinEastX", "veinNorthZ", "veinSouthZ", "veinMinY")


def decode_dim(key, world_seed):
    d = (((key & MASK64) ^ ((world_seed << 16) & MASK64)) >> 56) & 0xFF
    return d - 0x100 if d >= 0x80 else d


def load(path, dim=None, world_seed=None):
    """oreveinSeed -> entry. Duplicate keys are a bug in the dump, not something to silently last-wins."""
    with open(path) as fh:
        entries = json.load(fh)
    by_seed, dropped = {}, 0
    for e in entries:
        seed = e["seed"]
        if dim is not None and decode_dim(seed, world_seed) != dim:
            dropped += 1
            continue
        if seed in by_seed and by_seed[seed] != e:
            raise SystemExit(f"{path}: oreseed {seed} appears twice with different values — bad dump")
        by_seed[seed] = e
    return by_seed, dropped


def geometry(entry):
    p = entry.get("placement") or {}
    return (entry.get("minY"),) + tuple(p.get(k) for k in PLACEMENT_KEYS)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("a")
    ap.add_argument("b")
    ap.add_argument("--dim", type=int, help="restrict to one dimension; requires --seed")
    ap.add_argument("--seed", type=int, help="world seed, needed to decode the dimension out of the key")
    ap.add_argument("--verbose", action="store_true", help="print every differing oreseed")
    args = ap.parse_args()
    if (args.dim is None) != (args.seed is None):
        ap.error("--dim and --seed must be given together")

    (a, dropped_a), (b, dropped_b) = load(args.a, args.dim, args.seed), load(args.b, args.dim, args.seed)
    common = sorted(set(a) & set(b))
    only_a = sorted(set(a) - set(b))
    only_b = sorted(set(b) - set(a))

    mix_diff, geom_diff = [], []
    for seed in common:
        # A null vein (no mix resolved) is a real decision, not a missing entry — "null" vs a mix is
        # a mix difference like any other, so compare the layer field as-is.
        if a[seed].get("layer") != b[seed].get("layer"):
            mix_diff.append(seed)
        elif geometry(a[seed]) != geometry(b[seed]):
            geom_diff.append(seed)

    total = len(common)
    print(f"A: {args.a}  ({len(a)} regions)")
    print(f"B: {args.b}  ({len(b)} regions)")
    if args.dim is not None:
        print(f"dimension filter : dim {args.dim}, dropped {dropped_a}/{dropped_b} entries as other-dimension")
    print(f"common regions   : {total}")

    def rate(n):
        return f"{n} / {total}" + (f" ({100.0 * n / total:.2f}%)" if total else "")

    print(f"mix differs      : {rate(len(mix_diff))}")
    print(f"geometry differs : {rate(len(geom_diff))}")
    print(f"only in one      : {len(only_a) + len(only_b)}  (A-only {len(only_a)}, B-only {len(only_b)})")

    if args.verbose:
        for seed in mix_diff:
            print(f"  MIX  {seed}: {a[seed].get('layer')} -> {b[seed].get('layer')}")
        for seed in geom_diff:
            print(f"  GEOM {seed}: {geometry(a[seed])} -> {geometry(b[seed])}")
        for seed in only_a:
            print(f"  A-ONLY {seed}: {a[seed].get('layer')}")
        for seed in only_b:
            print(f"  B-ONLY {seed}: {b[seed].get('layer')}")

    return 0 if not (mix_diff or geom_diff or only_a or only_b) else 1


if __name__ == "__main__":
    sys.exit(main())
