#!/usr/bin/env python3
"""Export a seed's points of interest as CSV and as JourneyMap waypoints.

usage: poi-export.py <sweep.jsonl> <seed> [-o outdir] [--radius N] [--surfacey FILE]
                     [--values CSV] [--top-chests N]

Emits:
  poi-<seed>.csv                  one row per POI, with a /tp and a Y-confidence note
  journeymap-<seed>/*.json        one waypoint file per POI, JourneyMap 5.x format

JOURNEYMAP INSTALL: copy the .json files into
  <instance>/journeymap/data/sp/<WorldName>/waypoints/
then restart, or reload waypoints in-game. Dimension is set per waypoint (0 overworld, 7 Twilight
Forest), and colour is keyed by POI type so categories are distinguishable on the map.

Y CONFIDENCE varies by POI type and is carried per row rather than flattened, because a coordinate
that is exact and one that is a fly-down hint look identical once written as three numbers:
  exact     Roguelike chests, enchanting tables, stronghold chests — the predicted Y is the real Y.
  surface   Resolved virgin top-solid +1 (needs --surfacey). Exact on bare ground; reads up to ~4
            low where a building or canopy sits on the column, per in-game measurement.
  approx    Witchery covens: the mod generates after decoration, so the container may be one low.
  sky       No height is predicted at all. Y is 200 — teleport and descend. Village centres and
            biome squares, whose Y is terrain-dependent and never computed by stage 0.

ROGUELIKE POIs carry the dungeon TRIGGER as a separate waypoint, because the dungeon does not exist
until that chunk populates: visiting a chest coordinate first can generate the chunk with no dungeon
in it. The trigger waypoint is named "<name> TRIGGER (go here first)".
"""
import argparse
import csv
import importlib.util
import json
import math
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
SKY_Y = 200

# r,g,b per POI kind, so the map legend is readable at a glance
COLOUR = {
    "spawn": (255, 255, 255),
    "village": (255, 215, 0),
    "smeltery-village": (255, 140, 0),
    "dungeon-trigger": (200, 40, 40),
    "enchant-table": (160, 60, 255),
    "top-chest": (60, 200, 255),
    "min-gate-chest": (0, 255, 128),
    "stronghold": (140, 140, 140),
    "coven-circle": (255, 60, 200),
    "no-rain": (120, 200, 120),
    "humid": (60, 160, 255),
}


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("sweep")
    ap.add_argument("seed", type=int)
    ap.add_argument("-o", "--outdir", default=".")
    ap.add_argument("--radius", type=int, default=60)
    ap.add_argument("--surfacey", default=None)
    ap.add_argument("--values", default=None, help="value table; enables top-chest and min-gate POIs")
    ap.add_argument("--top-chests", type=int, default=15)
    args = ap.parse_args()

    mc = load("mc", "multi-criteria.py")
    ls = load("loot_score", "loot-score.py")
    values = mins = None
    if args.values:
        values, _l, mins, _d, _ = ls.load_values(args.values, "max")

    row = None
    for line in open(args.sweep, encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        d = json.loads(line)
        if d.get("seed") == args.seed:
            row = d
            break
    if row is None:
        sys.exit(f"seed {args.seed} not in {args.sweep}")
    if "kill" in row:
        sys.exit(f"seed {args.seed} was killed at the {row['kill']} gate — no POI data")

    surf = {}
    if args.surfacey:
        for line in open(args.surfacey, encoding="utf-8"):
            s = json.loads(line)
            if s.get("seed") == args.seed:
                for x, y, z in s.get("y", []):
                    surf[(x, z)] = y

    sx, _sy, sz = row["spawn"]
    pois = []   # (kind, name, x, y, z, ynote, dim, note)

    def surface(x, z):
        y = surf.get((x, z))
        return (y + 1, "surface") if y is not None else (SKY_Y, "sky")

    y, yn = surface(sx, sz)
    pois.append(("spawn", "SPAWN", sx, y, sz, yn, 0, ""))

    # villages — smeltery-bearing ones called out separately, that being the search criterion
    for i, st in enumerate(row.get("village_starts", []), 1):
        c = st.get("c")
        if not c:
            continue
        x, z = c[0] * 16 + 8, c[1] * 16 + 8
        if math.hypot(x - sx, z - sz) > args.radius * 16:
            continue
        smelt = "ComponentSmeltery" in (st.get("pieces") or "")
        y, yn = surface(x, z)
        npieces = (st.get("pieces") or "").split(" ")[0]
        pois.append(("smeltery-village" if smelt else "village",
                     f"Village {i}{' +SMELTERY' if smelt else ''}", x, y, z, yn, 0,
                     f"{npieces} pieces, {len(st.get('chests') or [])} predicted chests"))

    # roguelike dungeons: the trigger is the actionable coordinate
    for dg in row.get("dungeons", []):
        t = dg.get("trigger")
        if not t:
            continue
        x, z = t[0] * 16 + 8, t[1] * 16 + 8
        if math.hypot(x - sx, z - sz) > args.radius * 16:
            continue
        tow = dg.get("tower") or "?"
        nch = len(dg.get("chests") or [])
        pois.append(("dungeon-trigger", f"Dungeon {tow} TRIGGER (go here first)", x, 100, z,
                     "sky", 0, f"{tow} tower, {nch} chests"))
        for p in dg.get("enchant_tables") or []:
            pois.append(("enchant-table", f"Enchanting Table ({tow})", p[0], p[1] + 1, p[2],
                         "exact", 0, "dungeon must be triggered first"))

    for i, sh in enumerate(row.get("strongholds", []), 1):
        c = sh.get("c")
        if not c:
            continue
        x, z = c[0] * 16 + 8, c[1] * 16 + 8
        if math.hypot(x - sx, z - sz) > args.radius * 16:
            continue
        pois.append(("stronghold", f"Stronghold {i}", x, SKY_Y, z, "sky", 0,
                     f"{len(sh.get('chests') or [])} predicted chests"))

    for cell in row.get("witchery_cells", []):
        if cell.get("winner") != "WorldHandlerCoven":
            continue
        ch = cell.get("chests") or []
        if ch:
            p = ch[0]["pos"]
            x, y, z, yn = p[0], p[1] + 1, p[2], "approx"
        else:
            cl = cell.get("cell")
            if not cl:
                continue
            x, z = cl
            y, yn = surface(x, z)
        if math.hypot(x - sx, z - sz) > args.radius * 16:
            continue
        pois.append(("coven-circle", "Coven Circle", x, y, z, yn, 0,
                     f"{len(ch)} container(s)"))

    dry, hum = mc.biome_squares(row)
    for t, kind, label in ((dry, "no-rain", "No-Rain"), (hum, "humid", "Humid")):
        if not t:
            continue
        y, yn = surface(t[2], t[3])
        pois.append((kind, f"{label} {t[0]}x{t[0]} centre", t[2], y, t[3], yn, 0,
                     f"{t[0]}x{t[0]} chunk square"))

    # chest POIs, if a value table was supplied
    if values is not None:
        mingate = {k for k, v in mins.items() if v}
        chests = []
        for dg in row.get("dungeons", []):
            t = dg.get("trigger")
            ttp = f"/tp {t[0]*16+8} 100 {t[1]*16+8}" if t else ""
            for c in dg.get("chests", []):
                chests.append(("roguelike", c["pos"], c.get("items", []), ttp))
        for st in row.get("village_starts", []):
            for e in st.get("chests", []):
                chests.append(("village", e["chest"]["pos"], e["chest"].get("items", []), ""))
        for sh in row.get("strongholds", []):
            for e in sh.get("chests", []):
                chests.append(("stronghold", e["chest"]["pos"], e["chest"].get("items", []), ""))
        scored = []
        for src, pos, items, ttp in chests:
            if math.hypot(pos[0] - sx, pos[2] - sz) > args.radius * 16:
                continue
            v = sum(values.get(ls.norm(i["name"]), 0) * i["n"] for i in items if "name" in i)
            mg = [i["name"] for i in items if "name" in i and ls.norm(i["name"]) in mingate]
            scored.append((v, src, pos, items, ttp, mg))
        scored.sort(key=lambda s: -s[0])
        for v, src, pos, items, ttp, mg in scored[:args.top_chests]:
            yn = "exact" if src in ("roguelike", "stronghold") else "sky"
            yy = pos[1] + 1 if yn == "exact" else SKY_Y
            pois.append(("top-chest", f"Chest {v:,} pts ({src})", pos[0], yy, pos[2], yn, 0,
                         (ttp + " first; " if ttp else "") +
                         ", ".join(f"{i.get('name','?')} x{i['n']}" for i in items[:4])))
        for v, src, pos, items, ttp, mg in scored:
            if not mg:
                continue
            yn = "exact" if src in ("roguelike", "stronghold") else "sky"
            yy = pos[1] + 1 if yn == "exact" else SKY_Y
            pois.append(("min-gate-chest", f"MIN-GATE: {', '.join(sorted(set(mg)))}",
                         pos[0], yy, pos[2], yn, 0,
                         (ttp + " first; " if ttp else "") + f"{v:,} pts"))

    outdir = pathlib.Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)
    csv_path = outdir / f"poi-{args.seed}.csv"
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["seed", "kind", "name", "x", "y", "z", "tp", "y_confidence",
                    "dist_from_spawn", "dimension", "note"])
        for kind, name, x, y, z, yn, dim, note in pois:
            w.writerow([args.seed, kind, name, x, y, z, f"/tp {x} {y} {z}", yn,
                        round(math.hypot(x - sx, z - sz)), dim, note])

    wp = outdir / f"journeymap-{args.seed}"
    wp.mkdir(parents=True, exist_ok=True)
    for old in wp.glob("*.json"):
        old.unlink()
    for kind, name, x, y, z, yn, dim, note in pois:
        r, g, b = COLOUR.get(kind, (255, 255, 255))
        safe = re.sub(r"[^A-Za-z0-9_-]+", "_", name).strip("_")
        wid = f"{safe}_{x}_{y}_{z}"
        obj = {
            "id": wid, "name": name, "icon": "waypoint-normal.png",
            "x": x, "y": y, "z": z, "r": r, "g": g, "b": b,
            "enable": True, "type": "Normal", "origin": "gtnh-seedsearch",
            "dimensions": [dim], "persistent": True,
        }
        (wp / f"{wid}.json").write_text(json.dumps(obj, indent=2))

    print(f"{csv_path}: {len(pois)} POIs")
    print(f"{wp}/: {len(pois)} JourneyMap waypoints")
    sky = sum(1 for p in pois if p[5] == "sky")
    print(f"  {sky} POIs have NO predicted height (y=200, descend); "
          f"{sum(1 for p in pois if p[5]=='exact')} exact, "
          f"{sum(1 for p in pois if p[5]=='surface')} resolved surface, "
          f"{sum(1 for p in pois if p[5]=='approx')} approximate")
    return 0


if __name__ == "__main__":
    sys.exit(main())
