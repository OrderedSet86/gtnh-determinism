#!/usr/bin/env python3
"""Split GT ore route-differences into vein IDENTITY vs PLACEMENT, the two failure modes.

Usage: ore-route-report.py <worldA> <worldB> [minCX maxCX minCZ maxCZ]

The F4 mixins target vein identity: which ore mix a region resolves to. They do not target
per-block placement, which reads the live world at every write (see the "What is actually wrong"
section of results/2026-08-27-gt-ore-probe-pinning/README.md). Those two need separate numbers,
because a headline ore-diff count moves for either reason and cannot tell you which.

Buckets, from the GT 5.09.54.x metadata encoding (GTOreAdapter.getOreInfo):
  material changed   - m %% 1000 differs. Vein identity flipped. THIS is what F4 must keep at zero.
  host stone changed - same material, different stone type. Placement read different live rock.
  ore <-> non-ore    - ore in one world, terrain in the other. Placement decided differently.
"""
import gzip
import importlib.util
import io
import sys
from collections import Counter
from pathlib import Path

_spec = importlib.util.spec_from_file_location("drb", str(Path(__file__).with_name("diff-region-blocks.py")))
drb = importlib.util.module_from_spec(_spec)
try:
    _spec.loader.exec_module(drb)
except SystemExit:
    pass

SMALL_ORE_META_OFFSET = 16000
NATURAL_ORE_META_OFFSET = 8000
STONE_NAMES = {0: "stone", 1: "netherrack", 2: "endstone", 3: "blackgranite",
               4: "redgranite", 5: "marble", 6: "basalt"}
ORE_PREFIXES = ("gregtech:gt.blockores", "bartworks:gt.bwMetaGenerated")


def decode(m):
    base = m % SMALL_ORE_META_OFFSET
    return {"material": m % 1000,
            "stone": STONE_NAMES.get((base % NATURAL_ORE_META_OFFSET) // 1000,
                                     f"v{(base % NATURAL_ORE_META_OFFSET) // 1000}"),
            "small": m >= SMALL_ORE_META_OFFSET,
            "natural": base >= NATURAL_ORE_META_OFFSET}


def registry(world):
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


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    a_dir, b_dir = args[0], args[1]
    window = tuple(map(int, args[2:6])) if len(args) >= 6 else None
    A = drb.world_chunks(a_dir, window)
    B = drb.world_chunks(b_dir, window)
    names = registry(a_dir)
    is_ore = lambda i: names.get(i, "").startswith(ORE_PREFIXES)

    buckets = Counter()
    mats_changed = Counter()
    stones_changed = Counter()
    appeared = Counter()
    for k in sorted(set(A) & set(B)):
        sa, sb = A[k], B[k]
        for y in sorted(set(sa) & set(sb)):
            (ia, ma), (ib, mb) = sa[y], sb[y]
            if ia == ib and ma == mb:
                continue
            for i in range(4096):
                if ia[i] == ib[i] and ma[i] == mb[i]:
                    continue
                oa, ob = is_ore(ia[i]), is_ore(ib[i])
                if not oa and not ob:
                    continue
                if oa and ob:
                    da, db = decode(ma[i]), decode(mb[i])
                    if da["material"] != db["material"]:
                        buckets["material changed (VEIN IDENTITY)"] += 1
                        mats_changed[(da["material"], db["material"])] += 1
                    elif da["stone"] != db["stone"]:
                        buckets["host stone changed (placement)"] += 1
                        stones_changed[(da["stone"], db["stone"])] += 1
                    elif da["small"] != db["small"]:
                        buckets["small/big flipped (placement)"] += 1
                    else:
                        buckets["ore meta differs, other (placement)"] += 1
                else:
                    buckets["ore <-> non-ore (placement)"] += 1
                    present = names.get(ib[i] if ob else ia[i], "?")
                    absent = names.get(ia[i] if ob else ib[i], "?")
                    appeared[(absent, present)] += 1

    total = sum(buckets.values())
    print(f"GT/BW ore-involved differing blocks: {total}\n")
    if not total:
        print("none")
        return
    for k, n in buckets.most_common():
        print(f"  {k:42s} {n:7d}  {100.0*n/total:6.2f}%")
    ident = buckets["material changed (VEIN IDENTITY)"]
    print(f"\nVEIN IDENTITY differing blocks: {ident}"
          f"  ({'CLEAN — F4 holds' if ident == 0 else 'NOT CLEAN — F4 is not fully closing identity'})")
    if mats_changed:
        print("  material transitions (materialId -> materialId):")
        for (x, y), n in mats_changed.most_common(15):
            print(f"    {n:7d}  {x} -> {y}")
    if stones_changed:
        print("\n  host-stone transitions:")
        for (x, y), n in stones_changed.most_common(10):
            print(f"    {n:7d}  {x} -> {y}")
    if appeared:
        print("\n  ore appeared/vanished against:")
        for (x, y), n in appeared.most_common(10):
            print(f"    {n:7d}  {x} <-> {y}")


if __name__ == "__main__":
    main()
