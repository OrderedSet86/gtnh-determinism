#!/usr/bin/env python3
"""Choose the two village crop windows, and write columns.json.

The village columns are the only ones whose framing is not fixed in advance: the slime island and
the Witchery cluster are forced to known chunks, but villages are left where the seed put them.

Selection is two-stage, and the second stage is a framing choice that has to be disclosed:

1. Reject any window that is not solidly inside the village in ALL FOUR worlds (>= FLOOR structure
   blocks). Picking on one run alone would frame a building that exists in only one row, which
   would be an artefact rather than evidence.
2. Among survivors, take the window where the two STOCK launches differ most.

Stage 2 is choosing where to point the camera, and the README caption says so. It is fair because
the fixed pair is then cropped at the identical coordinates and gets no such help — the comparison
the image makes is between rows at one fixed place, not between differently-chosen crops. The
unselected whole-world figures are the honest headline and belong in the caption next to it: over
the full radius-48 walk, the two stock launches placed 5,718 and 4,512 village structure blocks;
the two fixed launches placed 5,291 and 5,293.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

SEEDLIB = Path("/home/order/Dropbox/OrderedSetCode/cloned-gtnh/gtnh-seedlib")
sys.path.insert(0, str(SEEDLIB / "tools"))
import worldrender as W  # noqa: E402

TILE_W, TILE_H = 44, 50

# Blocks a village places and RWG terrain does not. Cobblestone and gravel are deliberately absent:
# both occur naturally all over this seed, and including them made a 240x240 window read as
# "1513 village blocks" when there was no village in it at all.
STRUCTURE = [
    "minecraft:planks", "etfuturum:grass_path", "minecraft:oak_stairs", "minecraft:fence",
    "minecraft:log", "minecraft:cobblestone_wall", "minecraft:glass_pane", "minecraft:torch",
    "minecraft:crafting_table", "minecraft:furnace", "minecraft:wooden_door", "minecraft:stonebrick",
    "minecraft:spruce_stairs", "minecraft:birch_stairs", "minecraft:double_stone_slab",
    "minecraft:stone_slab", "minecraft:wool", "minecraft:hay_block", "minecraft:farmland",
]


def masks(worlds: list[Path]):
    out = []
    palette = json.loads((SEEDLIB / "worlds/-1636594104014467454/palette.json").read_text())
    for w in worlds:
        names = W.block_registry(w / "level.dat")
        lookup = W.build_lookup(names, palette)
        scan = W.scan_world(w / "region", names, lookup, log=lambda m: None)
        inv = {v: k for k, v in names.items()}
        ids = [inv[n] for n in STRUCTURE if n in inv]
        out.append((scan, np.isin(scan["surf_id"], ids)))
        print(f"  {w.name}: {out[-1][1].sum()} structure blocks", flush=True)
    return out


def integral(m: np.ndarray) -> np.ndarray:
    return np.pad(m.astype(np.int32).cumsum(0).cumsum(1), ((1, 0), (1, 0)))


FLOOR = 400  # structure blocks required in every world, out of a 44x50 = 2200-cell window


def surface_diff(a: dict, b: dict) -> np.ndarray:
    """Same predicate splash-strips.py paints red, computed over the whole overlapping grid."""
    assert (a["x0"], a["z0"], a["w"], a["h"]) == (b["x0"], b["z0"], b["w"], b["h"])
    return ((a["surf_id"] != b["surf_id"]) | (a["surf_meta"] != b["surf_meta"])
            | (a["height"] != b["height"]) | (a["water_top"] != b["water_top"])
            | (a["have"] != b["have"]))


def best_windows(scanned, regions):
    ii = [integral(m) for _, m in scanned]
    # scanned is ordered stock-r1, stock-r2, fixed-r1, fixed-r2
    dmap = integral(surface_diff(scanned[0][0], scanned[1][0]))
    results = []
    for (rx0, rz0, rx1, rz1) in regions:
        best = None
        for z in range(rz0, rz1 - TILE_H):
            for x in range(rx0, rx1 - TILE_W):
                per, ok = [], True
                for (scan, _), I in zip(scanned, ii):
                    ix, iz = x - scan["x0"], z - scan["z0"]
                    if not (0 <= ix and ix + TILE_W <= scan["w"] and 0 <= iz and iz + TILE_H <= scan["h"]):
                        ok = False
                        break
                    if not scan["have"][iz:iz + TILE_H, ix:ix + TILE_W].all():
                        ok = False
                        break
                    per.append(int(I[iz + TILE_H, ix + TILE_W] - I[iz, ix + TILE_W]
                                   - I[iz + TILE_H, ix] + I[iz, ix]))
                if not ok or min(per) < FLOOR:
                    continue
                s0 = scanned[0][0]
                ix, iz = x - s0["x0"], z - s0["z0"]
                changed = int(dmap[iz + TILE_H, ix + TILE_W] - dmap[iz, ix + TILE_W]
                              - dmap[iz + TILE_H, ix] + dmap[iz, ix])
                if best is None or changed > best[0]:
                    best = (changed, x, z, per)
        results.append(best)
    return results


DEEPSLATE_Y = 21  # inside the shatter band: deepslateMaxY=22 minus world.rand.nextInt(4)

# Chunks the gtnhsplash amplifier forces, as block boxes (x0, z0, x1, z1), inclusive.
# The deepslate window must avoid them. Witchery's generate() draws from World.rand rather than the
# Random FML hands it, so a forced cell consumes draws an unforced cell would not and shifts every
# later World.rand consumer in that chunk -- including Et Futurum's per-block shatter roll. A
# deepslate window inside a forced chunk would be showing variance this jar manufactured, which is
# exactly the thing the picture must not do. The first rock-only search picked such a window.
FORCED_BOXES = [
    (-416, -384, -369, -337),   # witchery 3x3, chunks (-26..-24, -24..-22)
    (-400, 160, -385, 175),     # slime island, chunk (-25, 10)
]

# Stay well inside the walk. run-probe.sh walks radius+1 = +-49 chunks and hashes +-48, so the
# outermost ring is half-populated cascade and differs run to run for reasons that have nothing to
# do with any mod's RNG. The second rock-only search picked a window at chunk -49; this is the
# guard against reporting the walk boundary as a determinism defect.
INNER = 44 * 16


def best_slice_window(worlds: list[Path], names: dict[int, str], y: int):
    """Same two-stage idea as the villages, for the y-slice column.

    Stage 1 here is only "all four worlds have this chunk"; stage 2 maximises the stock-pair
    difference. Reported alongside is the FIXED pair's count in the same window and, in the
    caption, over the whole slice -- because the whole-slice fixed residual is 430 blocks, not
    zero, and a window where it happens to be zero must not be allowed to imply otherwise.
    """
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "ss", Path(__file__).resolve().parents[2] / "scripts/splash-strips.py")
    ss = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ss)
    s = [ss.scan_slice(w, names, y) for w in worlds]

    def dm(a, b):
        return (a["surf_id"] != b["surf_id"]) | (a["surf_meta"] != b["surf_meta"]) | (a["have"] != b["have"])

    ds, df = dm(s[0], s[1]), dm(s[2], s[3])
    print(f"\n  y={y} whole slice: stock pair {ds.sum():,} differ, fixed pair {df.sum():,} differ")
    I, F = integral(ds), integral(df)

    # The window must be undisturbed rock in all four worlds. Without this the search does not find
    # the deepslate band at all: the densest stock-pair difference at y=21 is a Roguelike dungeon
    # (921 air + cobblestone + stonebrick in a 2200-cell window) that sits at that spot in the stock
    # runs and not the fixed ones. That is a real defect, and it is not the one this column claims
    # to show, so labelling it "deepslate" would have been a false caption.
    names_inv = {v: k for k, v in names.items()}
    rock_ids = [names_inv[n] for n in (
        "minecraft:stone", "etfuturum:deepslate", "etfuturum:tuff", "etfuturum:calcite",
        "minecraft:gravel", "minecraft:dirt", "gregtech:gt.blockgranites",
    ) if n in names_inv]
    rock = np.ones(ds.shape, bool)
    for w in s:
        rock &= np.isin(w["surf_id"], rock_ids)
    R = integral(rock)
    H = integral(s[0]["have"] & s[1]["have"] & s[2]["have"] & s[3]["have"])
    # 90%, not 100%: a single ore block or a one-block cave pocket would otherwise veto a window,
    # and at y=21 that rejects everything.
    rock_min = int(0.90 * TILE_W * TILE_H)

    def box(J, x, z):
        return int(J[z + TILE_H, x + TILE_W] - J[z, x + TILE_W] - J[z + TILE_H, x] + J[z, x])

    best = None
    h, w = ds.shape
    for z in range(0, h - TILE_H, 2):
        for x in range(0, w - TILE_W, 2):
            if box(H, x, z) != TILE_W * TILE_H or box(R, x, z) < rock_min:
                continue
            wx, wz = x + s[0]["x0"], z + s[0]["z0"]
            if not (-INNER <= wx and wx + TILE_W - 1 <= INNER
                    and -INNER <= wz and wz + TILE_H - 1 <= INNER):
                continue
            if any(wx <= bx1 and wx + TILE_W - 1 >= bx0 and wz <= bz1 and wz + TILE_H - 1 >= bz0
                   for bx0, bz0, bx1, bz1 in FORCED_BOXES):
                continue
            c = box(I, x, z)
            if best is None or c > best[0]:
                best = (c, x, z, box(F, x, z))
    if best is None:
        raise SystemExit(f"no rock-only window at y={y}")
    c, x, z, fc = best
    return c, fc, x + s[0]["x0"], z + s[0]["z0"], int(ds.sum()), int(df.sum())


def main() -> None:
    out_root = Path(sys.argv[1])
    worlds = [out_root / f"world-{a}-r{r}" for a in ("stock", "fixed") for r in (1, 2)]
    for w in worlds:
        if not w.is_dir():
            raise SystemExit(f"missing {w}")
    print("scanning:")
    scanned = masks(worlds)

    # Two village neighbourhoods, from the grass_path/planks clustering in the verification run.
    regions = [(-16, -32, 176, 128), (160, -608, 352, -416)]
    found = best_windows(scanned, regions)
    if any(f is None for f in found):
        raise SystemExit("no viable village window in one of the regions")
    (d1, x1, z1, p1), (d2, x2, z2, p2) = found
    cells = TILE_W * TILE_H
    print(f"\nvillage A: window x{x1}..{x1+TILE_W-1} z{z1}..{z1+TILE_H-1}"
          f"  structure blocks per world {p1}"
          f"  stock-pair diff {d1}/{cells} ({d1/cells*100:.0f}%)")
    print(f"village B: window x{x2}..{x2+TILE_W-1} z{z2}..{z2+TILE_H-1}"
          f"  structure blocks per world {p2}"
          f"  stock-pair diff {d2}/{cells} ({d2/cells*100:.0f}%)")

    names = W.block_registry(worlds[0] / "level.dat")
    dc, dfc, dx, dz, dtot, dftot = best_slice_window(worlds, names, DEEPSLATE_Y)
    print(f"deepslate: window x{dx}..{dx+TILE_W-1} z{dz}..{dz+TILE_H-1}"
          f"  stock-pair diff {dc}/{cells} ({dc/cells*100:.0f}%), fixed-pair {dfc}/{cells}")

    cols = [
        {"key": "VA", "title": "village", "subtitle": "vanilla + VillageNames", "mode": "surface",
         "x": x1, "z": z1},
        {"key": "VB", "title": "village", "subtitle": "second site", "mode": "surface",
         "x": x2, "z": z2},
        # slime island: forced at chunk (-25,10); the island is anchored at the chunk's NW corner
        # (-400,160) and only extends +X/+Z, up to 32 blocks plus ~3 of slime-tree overhang.
        {"key": "SL", "title": "slime island", "subtitle": "TinkersConstruct", "mode": "surface",
         "x": -406, "z": 151},
        # witchery: forced 3x3 cluster of chunks centred on (-25,-23) = blocks (-416..-369, -384..-337)
        {"key": "WI", "title": "witchery", "subtitle": "3x3 forced cells", "mode": "surface",
         "x": -414, "z": -385},
        {"key": "DS", "title": "deepslate", "subtitle": f"slice at y={DEEPSLATE_Y}", "mode": "slice",
         "y": DEEPSLATE_Y, "x": dx, "z": dz},
    ]
    dst = Path(__file__).with_name("columns.json")
    dst.write_text(json.dumps(cols, indent=2) + "\n")
    print(f"\nwrote {dst}")


if __name__ == "__main__":
    main()
