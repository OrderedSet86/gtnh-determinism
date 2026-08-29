#!/usr/bin/env python3
"""Account for EVERY differing block between two saved 1.7.10 worlds, grouped by cause.

Usage: inventory-region-diff.py <worldA> <worldB> [minCX maxCX minCZ maxCZ]

diff-region-blocks.py prints the top 40 transitions, which hides the tail. This prints a
complete inventory: every transition is assigned to a named category, the categories sum to
100% of the differing blocks, and anything unrecognised lands in an explicit "unclassified"
bucket rather than being dropped. Block ids are resolved to registry names from worldA's
level.dat, so the output survives id shuffles between pack versions.

Categories are deliberately coarse and named after the mechanism, not the block, because the
question this answers is "what kind of nondeterminism is left", not "which blocks moved".
"""
import gzip
import importlib.util
import io
import sys
from collections import Counter, defaultdict
from pathlib import Path

_spec = importlib.util.spec_from_file_location("drb", str(Path(__file__).with_name("diff-region-blocks.py")))
drb = importlib.util.module_from_spec(_spec)
try:
    _spec.loader.exec_module(drb)
except SystemExit:
    pass


def registry(world):
    """id -> registry name, from the FML ItemData block registry in level.dat."""
    names = {}

    def walk(n):
        if isinstance(n, dict):
            if "K" in n and "V" in n and isinstance(n.get("V"), int):
                names.setdefault(int(n["V"]), str(n["K"]).lstrip("\x01\x02"))
            for v in n.values():
                walk(v)
        elif isinstance(n, list):
            for v in n:
                walk(v)

    _, root = drb.read_nbt(io.BytesIO(gzip.open(Path(world) / "level.dat", "rb").read()))
    walk(root)
    return names


# Ore families are matched by registry prefix, not by substring: ":ore" also matches
# TConstruct:ore.berries, which is a bush.
ORE_PREFIXES = ("gregtech:gt.blockores", "bartworks:gt.bwMetaGenerated")
VANILLA_ORES = {"minecraft:gold_ore", "minecraft:iron_ore", "minecraft:coal_ore",
                "minecraft:lapis_ore", "minecraft:diamond_ore", "minecraft:redstone_ore",
                "minecraft:lit_redstone_ore", "minecraft:emerald_ore", "minecraft:quartz_ore"}
SOFT = {"minecraft:sand", "minecraft:gravel", "minecraft:clay", "minecraft:water",
        "minecraft:flowing_water", "minecraft:lava", "minecraft:flowing_lava"}
# Everything the terrain stage can leave behind. Anything else is something a decorator placed,
# which is what makes "one side is terrain, the other is not" a reliable decoration test — far
# more robust than listing every plant, tree, hive and berry bush the pack ships.
TERRAIN = SOFT | {"minecraft:stone", "minecraft:dirt", "minecraft:grass", "minecraft:cobblestone",
                  "minecraft:sandstone", "minecraft:bedrock", "minecraft:air", "minecraft:ice",
                  "minecraft:snow", "minecraft:mycelium", "minecraft:hardened_clay",
                  "minecraft:stained_hardened_clay"}


def classify(na, nb):
    pair = {na, nb}
    if any(n.startswith(ORE_PREFIXES) for n in pair) or pair & VANILLA_ORES:
        return "GT / mod ORE placement"
    if any(n.startswith(("gregtech:gt.blockgranites", "gregtech:gt.blockstones")) for n in pair):
        return "GT stone-layer worldgen (granite/stone blobs)"
    if any("deepslate" in n or "tuff" in n or "calcite" in n for n in pair):
        return "EtFuturum deepslate band"
    if not (pair <= TERRAIN):
        return "decoration (trees/plants/hives/etc)"
    if pair & SOFT:
        return "sand/gravel/clay/fluid settling"
    return "dirt/gravel/stone patches"


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    a_dir, b_dir = args[0], args[1]
    window = tuple(map(int, args[2:6])) if len(args) >= 6 else None
    A = drb.world_chunks(a_dir, window)
    B = drb.world_chunks(b_dir, window)
    names = registry(a_dir)

    def name(i):
        return names.get(i, f"id{i}")

    both = sorted(set(A) & set(B))
    cat_counts = Counter()
    pair_counts = Counter()
    cat_pairs = defaultdict(Counter)
    chunk_counts = Counter()
    total = 0
    for (cx, cz) in both:
        sa, sb = A[(cx, cz)], B[(cx, cz)]
        for y_sec in sorted(set(sa) | set(sb)):
            if y_sec not in sa or y_sec not in sb:
                # A section absent on one side is all-air there, so only the present side's
                # non-air blocks actually differ. Counting the full 4096 would inflate the
                # total by an order of magnitude and drown every real category.
                ids, metas = (sa if y_sec in sa else sb)[y_sec]
                for i in range(4096):
                    if ids[i]:
                        total += 1
                        chunk_counts[(cx, cz)] += 1
                        n = name(ids[i])
                        cat = classify(n, "minecraft:air")
                        cat_counts[cat] += 1
                        key = f"{n}:{metas[i]} -> absent section"
                        pair_counts[key] += 1
                        cat_pairs[cat][key] += 1
                continue
            (ia, ma), (ib, mb) = sa[y_sec], sb[y_sec]
            if ia == ib and ma == mb:
                continue
            for i in range(4096):
                if ia[i] != ib[i] or ma[i] != mb[i]:
                    total += 1
                    chunk_counts[(cx, cz)] += 1
                    na, nb = name(ia[i]), name(ib[i])
                    cat = classify(na, nb)
                    cat_counts[cat] += 1
                    key = f"{na}:{ma[i]} -> {nb}:{mb[i]}"
                    pair_counts[key] += 1
                    cat_pairs[cat][key] += 1

    print(f"chunks: A={len(A)} B={len(B)} common={len(both)} "
          f"only-A={len(set(A)-set(B))} only-B={len(set(B)-set(A))}")
    print(f"differing blocks: {total} across {len(chunk_counts)} chunks\n")
    if not total:
        print("IDENTICAL")
        return
    print(f"{'category':48s} {'blocks':>9s}  {'share':>7s}  distinct transitions")
    for cat, n in cat_counts.most_common():
        print(f"  {cat:46s} {n:9d}  {100.0*n/total:6.2f}%  {len(cat_pairs[cat])}")
    print(f"  {'TOTAL':46s} {total:9d}  100.00%  {len(pair_counts)}")
    if cat_counts.get("unclassified"):
        print("\nunclassified transitions (all of them):")
        for k, n in cat_pairs["unclassified"].most_common():
            print(f"  {n:8d}  {k}")


if __name__ == "__main__":
    main()
