#!/usr/bin/env python3
"""Render the README launch-variance filmstrip from four probe worlds.

Four cold-boot worlds of one seed -- two stock, two with the determinism jar -- become four
stacked rows. Each row is five side-by-side crops at a fixed absolute world window, so the same
village sits in the same column in every row and you watch it re-lay-out between launches. Rows 2
and 4 carry a red overlay marking every surface column that differs from the row directly above.

Two things about how the crops are taken, both load-bearing:

* Windows are absolute world coordinates, sliced against each scan's own `x0`/`z0`. The obvious
  alternative -- `build_world_bundle.render_blocks` -- ends in `_crop_to_content(alpha)`, a
  bounding box of non-transparent pixels, so a one-chunk difference in any run's cascade ring
  would silently shift that row's crop and every column would misalign by a few blocks. That
  failure looks exactly like the non-determinism the picture is about.
* Every window asserts `have.all()`. A window that reaches past the walk radius would otherwise
  render as blank terrain that reads as "nothing generated here", which in a determinism image is
  the worst possible ambiguity.

Block rendering is `gtnh-seedlib/tools/worldrender.py` -- a JourneyMap-grade Anvil reader with
biome tints and hillshade, no client and no mod. The deepslate column needs a horizontal slice
rather than a surface, which worldrender does not do, so `scan_slice` below reuses its NBT and
section decoders for that one case.

Usage:
    splash-strips.py --world stock-r1=DIR --world stock-r2=DIR \\
                     --world fixed-r1=DIR --world fixed-r2=DIR --out docs/img/launch-variance.png
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SEEDLIB = Path("/home/order/Dropbox/OrderedSetCode/cloned-gtnh/gtnh-seedlib")
sys.path.insert(0, str(SEEDLIB / "tools"))
import worldrender as W  # noqa: E402

PALETTE = SEEDLIB / "worlds/-1636594104014467454/palette.json"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_R = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

# Slime island blocks are absent from worldrender's vanilla-only palette, so they currently fall
# through to `biome_colour * 0.92` -- the single most eye-catching feature in the image would
# render as slightly-darker grass. Patched in before build_lookup so all four renders share it.
EXTRA_BLOCKS = {
    "TConstruct:slime.grass": (120, 210, 90),
    "TConstruct:slime.grass.tall": (135, 220, 100),
    "TConstruct:slime.leaves": (150, 235, 120),
    "TConstruct:liquid.slime": (70, 190, 110),
    "TConstruct:CraftedSoil": (120, 95, 70),
}

# Palette for the y-slice column. Underground at y=21 is a handful of block types and none of them
# are in a surface palette, so this is its own small table rather than a reuse of BLOCKS.
SLICE_BLOCKS = {
    "minecraft:air": (16, 16, 20),
    "minecraft:stone": (128, 128, 128),
    "etfuturum:deepslate": (74, 74, 80),
    "etfuturum:tuff": (108, 109, 102),
    "etfuturum:calcite": (223, 224, 220),
    "minecraft:water": (60, 90, 180),
    "minecraft:flowing_water": (60, 90, 180),
    "minecraft:lava": (210, 110, 30),
    "minecraft:flowing_lava": (210, 110, 30),
    "minecraft:gravel": (150, 145, 140),
    "minecraft:dirt": (134, 96, 67),
    "minecraft:bedrock": (40, 40, 40),
    "gregtech:gt.blockgranites": (150, 120, 110),
}
# Ores and anything else modded. Warm, so GregTech veins read as veins against the grey band
# instead of vanishing into it — measured composition of the chosen window at y=21 is 46% stone,
# 43% deepslate, the rest granite/tuff/gravel/dirt and ~1.5% ore.
SLICE_FALLBACK = (196, 150, 60)

RED = np.array([220, 32, 32], np.float32)
RED_ALPHA = 0.70

TILE_W, TILE_H = 44, 50  # blocks
SCALE = 4  # px per block -> 176 x 200 px tiles
GAP = 8
LABEL_H = 18
# Two lines: title, then subtitle. One line clipped every subtitle against the next column, and a
# header that reads "witchery structures 3x3 ce" is worse than no header.
HEADER_H = 34

# Column windows come from --columns as JSON, one object per column:
#   {"key": "VA", "title": "village", "subtitle": "Highland", "mode": "surface", "x": 76, "z": -359}
#   {"key": "DS", "title": "deepslate band", "mode": "slice", "y": 21, "x": 2, "z": 431}
# They live in results/2026-09-08-launch-variance-splash/columns.json rather than here, because
# re-framing must never require touching this file or re-running a probe.

# ---------------------------------------------------------------------------------------
# scanning
# ---------------------------------------------------------------------------------------


def scan_surface(world: Path, palette: dict):
    names = W.block_registry(world / "level.dat")
    lookup = W.build_lookup(names, palette)
    scan = W.scan_world(world / "region", names, lookup, log=lambda m: None)
    rgb, _alpha, _unknown = W.colourise(scan, lookup, palette)
    return scan, rgb


def scan_slice(world: Path, names: dict[int, str], y: int) -> dict:
    """One horizontal plane of block ids and metadata, same grid convention as scan_world.

    worldrender only ever wants the topmost opaque block, so it has no notion of a fixed y. The
    deepslate band lives at y16..22 and is invisible to a heightmap, hence this.
    """
    region_dir = world / "region"
    files = sorted(region_dir.glob("r.*.mca"))
    if not files:
        raise SystemExit(f"no region files in {region_dir}")
    coords = [tuple(int(v) for v in f.name.split(".")[1:3]) for f in files]
    rx0, rx1 = min(c[0] for c in coords), max(c[0] for c in coords)
    rz0, rz1 = min(c[1] for c in coords), max(c[1] for c in coords)
    cx0, cz0 = rx0 * W.REGION_CHUNKS, rz0 * W.REGION_CHUNKS
    w = (rx1 - rx0 + 1) * W.REGION_CHUNKS * W.CHUNK_SIDE
    h = (rz1 - rz0 + 1) * W.REGION_CHUNKS * W.CHUNK_SIDE

    ids = np.zeros((h, w), np.uint16)
    meta = np.zeros((h, w), np.uint8)
    have = np.zeros((h, w), bool)
    for f in files:
        for level in W.region_chunks(f):
            blocks = W._sections(level)
            if blocks is None:
                continue
            ix = (level["xPos"] - cx0) * W.CHUNK_SIDE
            iz = (level["zPos"] - cz0) * W.CHUNK_SIDE
            if not (0 <= ix < w and 0 <= iz < h):
                continue
            ids[iz : iz + 16, ix : ix + 16] = blocks[y]
            meta[iz : iz + 16, ix : ix + 16] = W._meta(level)[y]
            have[iz : iz + 16, ix : ix + 16] = True
    return {
        "surf_id": ids,
        "surf_meta": meta,
        "height": np.zeros((h, w), np.uint8),
        "water_top": np.zeros((h, w), np.uint8),
        "have": have,
        "x0": cx0 * W.CHUNK_SIDE,
        "z0": cz0 * W.CHUNK_SIDE,
        "w": w,
        "h": h,
        "names": names,
    }


def colourise_slice(scan: dict) -> np.ndarray:
    names = scan["names"]
    tbl = np.zeros((4096, 3), np.uint8)
    tbl[:] = SLICE_FALLBACK
    for bid, name in names.items():
        if 0 <= bid < 4096 and name in SLICE_BLOCKS:
            tbl[bid] = SLICE_BLOCKS[name]
    return tbl[np.clip(scan["surf_id"], 0, 4095)]


# ---------------------------------------------------------------------------------------
# cropping and diffing
# ---------------------------------------------------------------------------------------


def window_slice(scan: dict, col: dict):
    """Absolute world window -> numpy slice, validated against this scan's own origin.

    Each world has its own region-file extent, so the same window lands at a different array index
    in each. Resolving against `x0`/`z0` every time is what keeps the four rows in register."""
    ix, iz = col["x"] - scan["x0"], col["z"] - scan["z0"]
    if not (0 <= ix and ix + TILE_W <= scan["w"] and 0 <= iz and iz + TILE_H <= scan["h"]):
        raise SystemExit(f"column {col['key']}: window {col['x']},{col['z']} outside the scanned area")
    sl = (slice(iz, iz + TILE_H), slice(ix, ix + TILE_W))
    if not scan["have"][sl].all():
        raise SystemExit(
            f"column {col['key']}: window is only {scan['have'][sl].mean()*100:.0f}% generated — "
            "raise the probe radius or move the plot")
    return sl


def diff_mask(a: dict, b: dict, sl_a, sl_b) -> np.ndarray:
    """Per-column difference. `height` is in it deliberately: a tree one block taller keeps the
    same surface block id but changes its silhouette and its hillshade, and dropping it would
    undercount exactly the RWG Math.random() tree-sizing bug the ambient terrain is showing."""
    return (
        (a["surf_id"][sl_a] != b["surf_id"][sl_b])
        | (a["surf_meta"][sl_a] != b["surf_meta"][sl_b])
        | (a["height"][sl_a] != b["height"][sl_b])
        | (a["water_top"][sl_a] != b["water_top"][sl_b])
        | (a["have"][sl_a] != b["have"][sl_b]))


def upscale(tile: np.ndarray, scale: int) -> np.ndarray:
    return np.kron(tile, np.ones((scale, scale, 1), np.uint8))


def paint(tile: np.ndarray, mask: np.ndarray, scale: int, dilate: int) -> np.ndarray:
    out = upscale(tile, scale).astype(np.float32)
    if not mask.any():
        return out.astype(np.uint8)
    m = np.kron(mask, np.ones((scale, scale), bool))
    if dilate:
        img = Image.fromarray((m * 255).astype(np.uint8), "L")
        for _ in range(dilate):
            img = img.filter(ImageFilter.MaxFilter(3))
        m = np.array(img) > 0
    out[m] = (1 - RED_ALPHA) * out[m] + RED_ALPHA * RED
    return out.astype(np.uint8)


# ---------------------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------------------


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--world", action="append", required=True, metavar="LABEL=DIR",
                    help="repeat four times, in row order")
    ap.add_argument("--columns", type=Path, required=True, help="JSON list of column windows")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--scale", type=int, default=SCALE)
    ap.add_argument("--dilate", type=int, default=0,
                    help="grow the diff mask by N px; report the UNDILATED percentage if used")
    ap.add_argument("--row-label", action="append", default=None)
    args = ap.parse_args()

    cols = json.loads(args.columns.read_text())
    palette = json.loads(PALETTE.read_text())
    W.BLOCKS.update(EXTRA_BLOCKS)

    worlds = []
    for spec in args.world:
        label, _, d = spec.partition("=")
        worlds.append((label, Path(d)))
    if len(worlds) != 4:
        raise SystemExit("expected exactly four --world arguments")

    # scan once per world; both a surface scan and (if any column needs it) a y-slice
    scans = []
    for label, d in worlds:
        print(f"scanning {label} …", flush=True)
        surf, rgb = scan_surface(d, palette)
        entry = {"surface": (surf, rgb)}
        for y in sorted({c["y"] for c in cols if c["mode"] == "slice"}):
            s = scan_slice(d, surf["names"], y)
            entry[("slice", y)] = (s, colourise_slice(s))
        scans.append((label, entry))

    # crop every (row, column) and compute the two diff rows
    tiles: list[list[np.ndarray]] = []
    masks: list[list[np.ndarray]] = []
    for r, (label, entry) in enumerate(scans):
        row_t, row_m = [], []
        for col in cols:
            key = "surface" if col["mode"] == "surface" else ("slice", col["y"])
            scan, rgb = entry[key]
            sl = window_slice(scan, col)
            row_t.append(rgb[sl])
            if r in (1, 3):
                pscan, _ = scans[r - 1][1][key]
                row_m.append(diff_mask(scan, pscan, sl, window_slice(pscan, col)))
            else:
                row_m.append(np.zeros((TILE_H, TILE_W), bool))
        tiles.append(row_t)
        masks.append(row_m)

    s = args.scale
    tw, th = TILE_W * s, TILE_H * s
    width = len(cols) * tw + (len(cols) - 1) * GAP
    height = HEADER_H + 4 * (LABEL_H + th) + 3 * GAP
    canvas = Image.new("RGB", (width, height), (18, 18, 20))
    draw = ImageDraw.Draw(canvas)
    fb = ImageFont.truetype(FONT, 13)
    fs = ImageFont.truetype(FONT_R, 11)

    def fit(text: str, font, limit: int) -> str:
        while text and draw.textlength(text, font=font) > limit:
            text = text[:-1]
        return text

    for c, col in enumerate(cols):
        x = c * (tw + GAP) + 2
        draw.text((x, 3), fit(col["title"], fb, tw - 4), font=fb, fill=(226, 226, 230))
        if col.get("subtitle"):
            draw.text((x, 19), fit(col["subtitle"], fs, tw - 4), font=fs, fill=(142, 142, 152))

    stats = []
    for r, ((label, _), row_t, row_m) in enumerate(zip(scans, tiles, masks)):
        y0 = HEADER_H + r * (LABEL_H + th + GAP)
        total = sum(int(m.sum()) for m in row_m)
        cells = len(cols) * TILE_W * TILE_H
        stats.append((label, total, cells))
        if r in (1, 3):
            txt = (f"{label}  ·  {total:,} of {cells:,} surface columns differ from the row above "
                   f"({total / cells * 100:.1f}%)")
            fill = (240, 120, 120) if total else (120, 220, 140)
        else:
            txt = f"{label}  ·  reference launch"
            fill = (200, 200, 208)
        draw.text((2, y0 + 3), txt, font=fb, fill=fill)
        for c, (tile, mask) in enumerate(zip(row_t, row_m)):
            px = paint(tile, mask, s, args.dilate)
            canvas.paste(Image.fromarray(px), (c * (tw + GAP), y0 + LABEL_H))
            if r in (1, 3):
                pct = mask.mean() * 100
                box = f"{pct:.0f}%"
                bx, by = c * (tw + GAP) + 4, y0 + LABEL_H + 4
                wtxt = draw.textlength(box, font=fs)
                draw.rectangle([bx - 2, by - 1, bx + wtxt + 2, by + 13], fill=(18, 18, 20))
                draw.text((bx, by), box, font=fs,
                          fill=(240, 120, 120) if pct else (120, 220, 140))

    args.out.parent.mkdir(parents=True, exist_ok=True)
    # dither=NONE: Floyd-Steinberg speckle in a picture whose subject is speckle would be read as
    # part of the diff mask.
    canvas.quantize(colors=255, method=Image.MEDIANCUT, dither=Image.NONE).save(
        args.out, optimize=True)
    print(f"\nwrote {args.out}  ({args.out.stat().st_size / 1024:.0f} KB, {width}x{height})")
    for label, total, cells in stats:
        print(f"  {label:12s} {total:7,} / {cells:,}")


if __name__ == "__main__":
    main()
