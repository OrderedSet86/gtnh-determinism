#!/usr/bin/env python3
"""Is generated GT ore physically plausible, or is it floating in air / sitting in water?

Usage: ore-physicality.py <worldA> [worldB] [minCX maxCX minCZ maxCZ]

The ore-vein reroll gate exists partly to stop a high-band vein generating into open air, which would
produce a no-op or visibly wrong chunk. Any change to that gate has to be checked against the BLOCKS,
not just against a determinism metric, so this counts where ore actually landed:

  exposed     ore with >= 1 of its 6 neighbours air. Some exposure is normal and desirable — that is
              how you spot a vein in a cave wall or a cliff — so compare arms, do not read absolutely.
  floating    ore with >= 5 of 6 neighbours air. This is the pathological shape: a block hanging in
              open space with nothing around it.
  in_water    ore whose neighbour is water.
  above_surf  ore above the highest solid block of its own column, i.e. sticking out of the ground.

Reports per arm and, with two worlds, the delta. A fix that loosened the gate too far shows up as a
rise in floating/above_surf, not merely as more ore.
"""
import collections
import glob
import gzip
import io
import os
import pathlib
import sys
import zlib

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import importlib.util

_spec = importlib.util.spec_from_file_location("drb", HERE / "diff-region-blocks.py")
drb = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(drb)

AIR = 0
WATER = {8, 9}


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

    _, root = drb.read_nbt(io.BytesIO(gzip.open(os.path.join(world, "level.dat"), "rb").read()))
    walk(root)
    return names


def scan(world, window):
    names = registry(world)
    ore_ids = {i for i, n in names.items() if n.startswith("gregtech:gt.blockores")}
    if not ore_ids:
        sys.exit(f"{world}: no gregtech:gt.blockores* ids in the registry — wrong world or pack")
    chunks = drb.world_chunks(world, window)
    # blocks[(cx,cz)] -> dict (x,y,z)->id for the whole chunk, so neighbour lookups can cross chunks
    grid = {}
    for (cx, cz), sections in chunks.items():
        for y0, sec in sections.items():
            if sec is None:
                continue
            blocks, _metas = sec  # world_chunks already applied section_blocks -> (ids, metas)
            for i, bid in enumerate(blocks):
                if bid == AIR:
                    continue
                y = y0 * 16 + (i >> 8)
                z = (i >> 4) & 15
                x = i & 15
                grid[(cx * 16 + x, y, cz * 16 + z)] = bid
    stats = collections.Counter()
    per_mix = collections.Counter()
    top = {}
    for (x, y, z), bid in grid.items():
        c = (x, z)
        if y > top.get(c, -1):
            top[c] = y
    for (x, y, z), bid in grid.items():
        if bid not in ore_ids:
            continue
        stats["ore"] += 1
        per_mix[names.get(bid, bid)] += 1
        nb = [grid.get((x + 1, y, z), AIR), grid.get((x - 1, y, z), AIR), grid.get((x, y + 1, z), AIR),
              grid.get((x, y - 1, z), AIR), grid.get((x, y, z + 1), AIR), grid.get((x, y, z - 1), AIR)]
        air = sum(1 for b in nb if b == AIR)
        if air >= 1:
            stats["exposed"] += 1
        if air >= 5:
            stats["floating"] += 1
        if any(b in WATER for b in nb):
            stats["in_water"] += 1
        if y > top.get((x, z), -1):
            stats["above_surf"] += 1
    return stats, per_mix


def main():
    args = [a for a in sys.argv[1:]]
    worlds = [a for a in args if not a.lstrip("-").isdigit()]
    nums = [int(a) for a in args if a.lstrip("-").isdigit()]
    window = tuple(nums) if len(nums) == 4 else (-20, 20, -20, 20)
    out = []
    for w in worlds:
        s, m = scan(w, window)
        out.append((w, s, m))
        ore = max(s["ore"], 1)
        print(f"\n{w}")
        print(f"  ore blocks   {s['ore']:>9,}")
        for k in ("exposed", "floating", "in_water", "above_surf"):
            print(f"  {k:<12} {s[k]:>9,}  ({100*s[k]/ore:5.2f}% of ore)")
    if len(out) == 2:
        (_, a, _), (_, b, _) = out[0], out[1]
        print("\ndelta (second minus first):")
        for k in ("ore", "exposed", "floating", "in_water", "above_surf"):
            d = b[k] - a[k]
            base = max(a[k], 1)
            print(f"  {k:<12} {d:>+9,}  ({100*d/base:+6.2f}%)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
