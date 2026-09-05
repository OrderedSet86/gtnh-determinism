#!/usr/bin/env python3
"""Export every predicted chest of one seed as a flat CSV, one row per item stack.

usage: loot-csv.py <value_table.csv> <sweep.jsonl> <seed> [-o out.csv] [--radius N]

One row per STACK, with the chest's columns repeated, so a spreadsheet can pivot by item, by
structure, or by distance without further munging. Chests whose contents stage 0 cannot predict get a
row too, with `predicted=no` and the reason — omitting them would make the sheet read as a complete
inventory when it is not.

TELEPORTING, three things the coordinates alone do not tell you:

  Roguelike     `structure_tp` is the dungeon's TRIGGER chunk and you must visit it FIRST. The mod
                builds the entire dungeon when that one chunk populates, and chests sit a median 128
                blocks away (max 235), so teleporting straight to a chest can land in a chunk that
                generates with no dungeon in it. Field-confirmed: the chest looks absent when it is
                merely unbuilt.
  Y confidence  `y_note` says how much to trust `y`. Roguelike and stronghold chest Y is exact.
                Witchery is predicted from the replay and can be one low. VILLAGE chest Y is NOMINAL
                — the piece box origin, measured 0/17 correct — so those rows carry SKY_Y and you
                descend. Never read a village `y` as a prediction.
  Stand-on      `tp` targets the block ABOVE the chest, which is where you want to be standing.

VALUES are raw stack value (unit price x count) from the table, NOT the marginal value used to rank
seeds: marginal value depends on what else the seed holds and is not comparable between chests. Items
with no row in the table get an empty `unit_value` rather than 0 — unscored is not worthless, and
collapsing the two hides gaps in the table.
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


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("value_table")
    ap.add_argument("sweep")
    ap.add_argument("seed", type=int)
    ap.add_argument("-o", "--out", default=None)
    ap.add_argument("--radius", type=int, default=60, help="chunks around spawn; chests outside are skipped")
    ap.add_argument("--surfacey", default=None, help="optional surface-Y jsonl to resolve village chest heights")
    ap.add_argument("--chest-sites", default=str(HERE.parent / "probe-build/src/main/resources/chest-sites.json"),
                    help="the measured piece->chest-site table, to report pieces it does not cover")
    args = ap.parse_args()

    ls = load("loot_score", "loot-score.py")
    values, _limits, mins, _display, _ = ls.load_values(args.value_table, "max")
    mingate = {k for k, v in mins.items() if v}

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
        sys.exit(f"seed {args.seed} not found in {args.sweep}")
    if "kill" in row:
        sys.exit(f"seed {args.seed} was killed by the {row['kill']} gate, so it has no chest data")

    surf = {}
    if args.surfacey:
        for line in open(args.surfacey, encoding="utf-8"):
            s = json.loads(line)
            if s.get("seed") == args.seed:
                for x, y, z in s.get("y", []):
                    surf[(x, z)] = y

    sx, _sy, sz = row["spawn"]

    # Categories whose contents are known NOT to reproduce across environments. `villageBlacksmith` is
    # the vanilla ChestGenHooks table that mods mutate at runtime; its roll COUNT is drawn inside
    # addComponentParts from the structure RNG, which F10 does not fork, so the number of stacks — and
    # therefore everything in the chest — varies between a probe server and a client even on the same
    # seed and jar. Measured on -1636594104014467454 (43,63,27): 3 stacks predicted, 7 in the user's
    # world, while 67 of the 68 chests in the surrounding window matched exactly. See HANDOFF item 12.
    ENV_DEPENDENT = {"villageBlacksmith"}

    # Piece classes the chest-site table covers, from BOTH lists: a piece in `chestless` is a measured
    # "this one places nothing", which is a different statement from absence.
    covered = set()
    try:
        tbl = json.load(open(args.chest_sites, encoding="utf-8"))
        for grp in ("sites", "chestless"):
            for e in tbl.get(grp, []):
                covered.add(e["piece"].rsplit("$", 1)[-1].rsplit(".", 1)[-1])
    except Exception as exc:
        print(f"warning: could not read {args.chest_sites} ({exc}); "
              f"coverage of the chest-site table will not be reported", file=sys.stderr)

    # A piece class the table has never seen contributes NO chest row at all — not even an unpredicted
    # one — so an incomplete sheet is indistinguishable from a village with no chests. Measured on this
    # corpus: 83 of the 97 piece classes around one seed were uncovered. Emit one row per uncovered
    # piece INSTANCE so the gap is visible in the spreadsheet instead of silent.
    uncovered = []
    for st in row.get("village_starts", []):
        c = st.get("c")
        vtp = f"/tp {c[0]*16+8} {SKY_Y} {c[1]*16+8}" if c else ""
        for m in re.finditer(r"(\w+)@(-?\d+),(-?\d+),(-?\d+)\.\.(-?\d+),(-?\d+),(-?\d+)",
                             st.get("pieces") or ""):
            name = m.group(1)
            if not covered or name in covered:
                continue
            x1, y1, z1, x2, y2, z2 = (int(g) for g in m.groups()[1:])
            uncovered.append((name, (x1 + x2) // 2, (y1 + y2) // 2, (z1 + z2) // 2, vtp))

    # (source, piece, category, pos, items, structure_tp, y_note, predicted, reason)
    chests = []
    for st in row.get("village_starts", []):
        c = st.get("c")
        vtp = f"/tp {c[0]*16+8} {SKY_Y} {c[1]*16+8}" if c else ""
        for e in st.get("chests", []):
            ch = e["chest"]
            chests.append(("village", e.get("piece", ""), e.get("category", ""), ch["pos"],
                           ch.get("items", []), vtp, "nominal - fly down, Y is the piece box origin",
                           "yes", ""))
        for u in st.get("chests_unpredicted", []):
            chests.append(("village", u.get("piece", ""), "", u.get("pos"), None, vtp,
                           "nominal - fly down", "no", u.get("reason", "")))
    for dg in row.get("dungeons", []):
        t = dg.get("trigger")
        ttp = f"/tp {t[0]*16+8} 100 {t[1]*16+8}" if t else ""
        tow = dg.get("tower") or ""
        for ch in dg.get("chests", []):
            chests.append(("roguelike", tow, "", ch["pos"], ch.get("items", []), ttp,
                           "exact", "yes", ""))
    for sh in row.get("strongholds", []):
        c = sh.get("c")
        stp = f"/tp {c[0]*16+8} {SKY_Y} {c[1]*16+8}" if c else ""
        for e in sh.get("chests", []):
            ch = e["chest"]
            chests.append(("stronghold", e.get("piece", "").split("$")[-1], e.get("category", ""),
                           ch["pos"], ch.get("items", []), stp, "exact", "yes", ""))
    for cell in row.get("witchery_cells", []):
        if not cell.get("chests"):
            continue
        cl = cell.get("cell")
        ctp = f"/tp {cl[0]} {SKY_Y} {cl[1]}" if cl else ""
        for ch in cell.get("chests", []):
            chests.append(("witchery", cell.get("winner", ""), ch.get("type", ""), ch["pos"],
                           ch.get("items", []), ctp, "approx - may be one low", "yes", ""))

    scx, scz = sx >> 4, sz >> 4
    out = args.out or f"loot-{args.seed}.csv"
    n_rows = n_chests = 0
    with open(out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["seed", "chest_id", "source", "structure", "category",
                    "x", "y", "z", "tp", "y_note", "dist_from_spawn", "structure_tp",
                    "chest_value", "chest_stacks", "predicted", "contents_confidence", "note",
                    "slot", "item", "item_id", "meta", "count", "unit_value", "stack_value",
                    "min_gate_item"])
        cid = 0
        for src, piece, cat, pos, items, stp, ynote, pred, reason in sorted(
                chests, key=lambda c: -sum((values.get(ls.norm(i["name"]), 0) * i["n"])
                                           for i in (c[4] or []) if "name" in i)):
            if pos is None:
                continue
            if max(abs((pos[0] >> 4) - scx), abs((pos[2] >> 4) - scz)) > args.radius:
                continue
            cid += 1
            n_chests += 1
            x, y, z = pos
            # Village Y is not a prediction; send the player to the sky unless a resolved surface
            # height is available, and never print the nominal value as if it were usable.
            if src == "village":
                ty = (surf.get((x, z), None))
                ty = ty + 1 if ty is not None else SKY_Y
                if (x, z) in surf:
                    ynote = "surface Y resolved (virgin, exact on bare ground, up to 4 low under a building)"
            else:
                ty = y + 1
            dist = round(math.hypot(x - sx, z - sz))
            cval = sum(values.get(ls.norm(i["name"]), 0) * i["n"] for i in (items or []) if "name" in i)
            nstacks = len(items) if items is not None else ""
            conf = ("ENV-DEPENDENT: stack count varies between environments"
                    if cat in ENV_DEPENDENT else
                    "cross-env verified" if src in ("roguelike", "stronghold") else "predicted")
            base = [args.seed, cid, src, piece, cat, x, y, z, f"/tp {x} {ty} {z}", ynote,
                    dist, stp, cval, nstacks, pred, conf, reason]
            if not items:
                w.writerow(base + ["", "", "", "", "", "", "", ""])
                n_rows += 1
                continue
            def val(i):
                return values.get(ls.norm(i["name"]), 0) * i["n"] if "name" in i else 0
            for i in sorted(items, key=lambda i: -val(i)):
                nm = i.get("name", "")
                unit = values.get(ls.norm(nm)) if nm else None
                w.writerow(base + [i.get("s", ""), nm, i.get("id", ""), i.get("d", ""), i.get("n", ""),
                                   unit if unit is not None else "",
                                   unit * i["n"] if unit is not None else "",
                                   "yes" if nm and ls.norm(nm) in mingate else ""])
                n_rows += 1
        # One row per uncovered piece instance, so the gap is a line in the sheet, not a silence.
        for name, ux, uy, uz, vtp in uncovered:
            cid += 1
            w.writerow([args.seed, cid, "village-UNKNOWN-PIECE", name, "", ux, uy, uz,
                        f"/tp {ux} {SKY_Y} {uz}", "sky - piece centre, not a chest position",
                        round(math.hypot(ux - sx, uz - sz)), vtp, "", "", "no", "unknown",
                        "piece class absent from chest-site table: any chest it holds is NOT in this sheet",
                        "", "", "", "", "", "", "", ""])
            n_rows += 1
    n_uncovered = len(uncovered)
    print(f"{out}: {n_rows:,} rows over {n_chests:,} chests within {args.radius} chunks of spawn")
    unpred = sum(1 for c in chests if c[7] == "no")
    if unpred:
        print(f"  {unpred} chests are known to exist but their contents are NOT predicted "
              f"(predicted=no) — the sheet is not a complete inventory")
    if n_uncovered:
        import collections as _c
        top = _c.Counter(u[0] for u in uncovered).most_common(5)
        print(f"  {n_uncovered} village piece instances ({len(set(u[0] for u in uncovered))} classes) are "
              f"NOT in the chest-site table: source=village-UNKNOWN-PIECE rows.")
        print(f"    any chests they hold are ABSENT from this sheet, not merely unpredicted — "
              f"the chest count above is a LOWER BOUND.")
        print(f"    most common: " + ", ".join(f"{k} x{v}" for k, v in top))
    return 0


if __name__ == "__main__":
    sys.exit(main())
