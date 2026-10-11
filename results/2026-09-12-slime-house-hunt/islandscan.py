#!/usr/bin/env python3
"""Verify a slime-house prefilter hit against a generated world save.

Usage: islandscan.py <world> <islandChunkX> <islandChunkZ> <entranceX> <entranceZ>

Reports, from the persisted region files:
  1. the entrance column's blocks (named via the FML id map in level.dat), y 40..145;
  2. slime-island material (TConstruct CraftedSoil / SlimeGrass / congealed slime) in the
     island footprint chunks: block count and y extent;
  3. brick (and brick stair) extent in a 41x41 box around the entrance — the house body,
     the 5x5 shaft, the porch columns and the step curtain are all brick-family.

A hit is REAL when the entrance column carries brick from ~y60 up into the island's y band
and the island material sits at the prefilter's predicted altitude. Empty island material or
a brick top far below the island band means the coincidence did not survive full generation
(canopy-raised yCenter or populate-order overwrite) — report it as a miss, not a partial.
"""
import importlib.util
import io
import struct
import sys
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "drb", Path(__file__).resolve().parents[2] / "scripts" / "diff-region-blocks.py")
drb = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(drb)


def fml_block_names(world):
    """block id -> name from level.dat's FML ItemData (\\x01 prefix = block)."""
    import gzip
    with gzip.open(Path(world) / "level.dat") as f:
        _, root = drb.read_nbt(io.BytesIO(f.read()))
    out = {}
    fml = root.get("FML", {})
    for e in fml.get("ItemData", []):
        k, v = e["K"], e["V"]
        if isinstance(k, bytes):
            k = k.decode("utf-8", "replace")
        if k and k[0] == "\x01":
            out[v] = k[1:]
    return out


def column(chunks, x, z):
    """(y, id, meta) for all non-air blocks in column x,z."""
    cx, cz = x >> 4, z >> 4
    secs = chunks.get((cx, cz))
    if secs is None:
        return None
    i0 = ((z & 15) << 4) | (x & 15)
    out = []
    for ysec, (ids, metas) in sorted(secs.items()):
        for yy in range(16):
            i = (yy << 8) | i0
            if ids[i]:
                out.append((ysec * 16 + yy, ids[i], metas[i]))
    return out


def main():
    world, icx, icz, ex, ez = sys.argv[1], *map(int, sys.argv[2:6])
    names = fml_block_names(world)
    slime_ids = {i for i, n in names.items()
                 if n.startswith("TConstruct:") and (
                     "CraftedSoil" in n or "slime" in n.lower())}
    brick_ids = {i for i, n in names.items()
                 if n in ("minecraft:brick_block", "minecraft:brick_stairs")}
    granite_ids = {i for i, n in names.items() if "stonebricksmooth" in n or n == "minecraft:stonebrick"}
    print(f"slime block ids: {sorted((i, names[i]) for i in slime_ids)}")
    print(f"brick ids: {sorted((i, names[i]) for i in brick_ids)}")

    # window: island footprint (origin chunk + 2 east/south) plus the entrance chunk +/-2
    ecx, ecz = ex >> 4, ez >> 4
    win = (min(icx, ecx - 2), max(icx + 2, ecx + 2), min(icz, ecz - 2), max(icz + 2, ecz + 2))
    chunks = drb.world_chunks(world, win)
    print(f"chunks loaded in window {win}: {len(chunks)}")

    print(f"\n== entrance column ({ex},{ez}) ==")
    col = column(chunks, ex, ez)
    if col is None:
        sys.exit("entrance chunk NOT GENERATED — verify run did not cover the hit")
    for y, bid, meta in col:
        if 40 <= y <= 145:
            print(f"  y={y:3d}  {names.get(bid, bid)}:{meta}")

    print("\n== island material in footprint ==")
    lo, hi, n = 999, -1, 0
    for dcx in range(icx, icx + 3):
        for dcz in range(icz, icz + 3):
            secs = chunks.get((dcx, dcz))
            if not secs:
                continue
            for ysec, (ids, metas) in secs.items():
                for i, bid in enumerate(ids):
                    if bid in slime_ids:
                        y = ysec * 16 + (i >> 8)
                        lo, hi, n = min(lo, y), max(hi, y), n + 1
    print(f"  slime blocks: {n}, y range: {lo}..{hi}" if n else "  NONE FOUND")

    print("\n== brick family near entrance (41x41, y 55..145) ==")
    lo, hi, n = 999, -1, 0
    per_y = {}
    for x in range(ex - 20, ex + 21):
        for z in range(ez - 20, ez + 21):
            col = column(chunks, x, z)
            if not col:
                continue
            for y, bid, meta in col:
                if bid in brick_ids and 55 <= y <= 145:
                    lo, hi, n = min(lo, y), max(hi, y), n + 1
                    per_y[y] = per_y.get(y, 0) + 1
    print(f"  brick blocks: {n}, y range: {lo}..{hi}" if n else "  NONE FOUND")
    if n:
        band = ", ".join(f"{y}:{per_y[y]}" for y in sorted(per_y))
        print(f"  per-y: {band}")


if __name__ == "__main__":
    main()
