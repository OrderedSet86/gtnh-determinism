#!/usr/bin/env python3
"""Loot CSV for one seed from a FULL-GENERATION search report, in loot-csv.py's schema.

usage: fullgen-loot-csv.py <value_table.csv> <search-report.json> [-o out.csv] [--radius N]
                           [--trace LOG]

`loot-csv.py` builds the same CSV from a stage-0 prefilter record, which is the right source when the
point is to answer a seed without generating it. It cannot describe chests stage 0 does not predict —
Thaumcraft hilltop circles and barrows have no entry in `chest-sites.json`, and vanilla
`WorldGenDungeons` rooms were never stage-0 computable. Those are exactly the chests the routemap was
missing.

This reads the probe's own `search.chunks[*].chests`, so every chest that actually generated is
present with the contents it actually has, at its real Y. The cost is that it needs a generated world:
one cold radius-60 run, versus stage 0 answering thousands of seeds a second. Use whichever matches
the question.

`--trace` takes a probe log carrying `[chesttrace]`, `[moundtrace]` and `[dungeonattempt]` lines and
uses them to attribute each chest to the generator that filled it, which is what puts `hilltop`,
`barrow` and `vanilla-dungeon` in the `source` column instead of an undifferentiated `dungeonChest`.
Without it every non-village chest reads as `fullgen`.

Y is always `exact` here: the chest was observed, not predicted.
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


def _load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


# The value table reader and item-name normaliser live in loot-score.py; every sibling script
# borrows them the same way, so the scoring here matches loot-csv.py exactly.
ls = _load("loot_score", "loot-score.py")

CHEST = re.compile(r"\[chesttrace\] seed=(-?\d+) .*?abs=(-?\d+),(-?\d+),(-?\d+).*?cat=(\S+).*?caller=(\S+)")
MOUND = re.compile(r"\[moundtrace\] seed=(-?\d+) x=(-?\d+) y=(-?\d+) z=(-?\d+)")
DUNGEON = re.compile(r"\[dungeonattempt\] seed=(-?\d+) x=(-?\d+) y=(-?\d+) z=(-?\d+) built=true")


def load_trace(path, seed):
    """-> {(x,y,z): source}. Barrow and vanilla-dungeon markers are structure ANCHORS, not chest
    positions, so they are returned separately for proximity attribution."""
    chest_src, anchors = {}, []
    for line in open(path, errors="replace"):
        m = CHEST.search(line)
        if m and int(m.group(1)) == seed:
            caller = m.group(6)
            pos = (int(m.group(2)), int(m.group(3)), int(m.group(4)))
            if "WorldGenHilltopStones" in caller:
                chest_src[pos] = "hilltop"
            elif "villagenames" in caller or "village" in caller.lower():
                chest_src[pos] = "village"
            elif "witchery" in caller.lower():
                chest_src[pos] = "witchery"
            elif "ChunkGeneratorRealistic" in caller and m.group(5) == "mineshaftCorridor":
                chest_src[pos] = "mineshaft"
            continue
        m = MOUND.search(line)
        if m and int(m.group(1)) == seed:
            anchors.append(("barrow", (int(m.group(2)), int(m.group(3)), int(m.group(4)))))
            continue
        m = DUNGEON.search(line)
        if m and int(m.group(1)) == seed:
            anchors.append(("vanilla-dungeon", (int(m.group(2)), int(m.group(3)), int(m.group(4)))))
    return chest_src, anchors


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("value_table")
    ap.add_argument("report")
    ap.add_argument("-o", "--out", default=None)
    ap.add_argument("--radius", type=int, default=60,
                    help="Chebyshev CHUNK radius around the walk centre, matching how the probe "
                         "corpus and prefilter-judge-chests define their window. A Euclidean circle "
                         "here silently dropped 507 in-window inventories sitting in the square's "
                         "corners, which then read as missing loot")
    ap.add_argument("--trace", default=None)
    ap.add_argument("--prefilter", default=None,
                    help="stage-0 record for the SAME pack; labels the sources stage 0 does know "
                         "(roguelike, stronghold, witchery) so they do not fall through to `fullgen`. "
                         "Positions come from the report either way — this only supplies the label")
    args = ap.parse_args()

    rep = json.load(open(args.report))
    seed = rep["seed"]
    search = rep.get("search") or {}
    spawn = search.get("spawn") or [0, 64, 0]
    sx, sz = spawn[0], spawn[2]
    values, _limits, mins, _display, _ = ls.load_values(args.value_table, "max")
    mingate = {k for k, v in mins.items() if v}

    chest_src, anchors = load_trace(args.trace, seed) if args.trace else ({}, [])

    # Roguelike chests never reach ChestGenHooks (the mod has its own loot system), so no chesttrace
    # line names them. Stage 0 does enumerate them, so borrow the label — matched on XZ, since stage 0
    # predicts roguelike Y exactly but village Y only nominally.
    pf_src = {}
    if args.prefilter:
        for line in open(args.prefilter):
            d = json.loads(line)
            if d.get("seed") != seed:
                continue
            for key, label in (("dungeons", "roguelike"), ("strongholds", "stronghold"),
                               ("witchery_cells", "witchery")):
                for st in d.get(key) or []:
                    for c in st.get("chests") or []:
                        q = c.get("chest", c).get("pos") or c.get("pos")
                        if q:
                            pf_src[(q[0], q[2])] = label
            for st in d.get("village_starts") or []:
                for c in (st.get("chests") or []) + (st.get("chests_unpredicted") or []):
                    q = c.get("chest", c).get("pos") or c.get("pos")
                    if q:
                        pf_src[(q[0], q[2])] = "village"
            break

    def source_of(pos):
        # Order matters. An exact position match — from the trace, then from stage 0 — always beats
        # the anchor proximity sweep below. Running the sweep first mislabelled 11 roguelike chests as
        # vanilla-dungeon because a dungeon happened to generate within 12 blocks of them, and their
        # contents matched the roguelike prediction item for item.
        s = chest_src.get(tuple(pos))
        if s:
            return s
        s = pf_src.get((pos[0], pos[2]))
        if s:
            return s
        # A barrow or dungeon marker is the structure's anchor, not a chest position; its chests sit
        # within a few blocks. Only reached when nothing knows this position exactly.
        for kind, a in anchors:
            if abs(a[0] - pos[0]) <= 12 and abs(a[2] - pos[2]) <= 12 and abs(a[1] - pos[1]) <= 8:
                return kind
        return "fullgen"

    centre = rep.get("center") or [sx >> 4, sz >> 4]
    rows = []
    for ch in (search.get("chunks") or {}).values():
        for c in ch.get("chests") or []:
            p = c["pos"]
            if max(abs((p[0] >> 4) - centre[0]), abs((p[2] >> 4) - centre[1])) > args.radius:
                continue
            rows.append((source_of(p), p, c.get("items") or [], c.get("type", "")))
    rows.sort(key=lambda r: (r[1][0], r[1][2], r[1][1]))

    out = args.out or f"loot-fullgen-{seed}.csv"
    n_items = 0
    with open(out, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["seed", "chest_id", "source", "structure", "category",
                    "x", "y", "z", "tp", "y_note", "dist_from_spawn", "structure_tp",
                    "chest_value", "chest_stacks", "predicted", "contents_confidence", "note",
                    "slot", "item", "item_id", "meta", "count", "unit_value", "stack_value",
                    "min_gate_item"])
        for cid, (src, pos, items, itype) in enumerate(rows, 1):
            x, y, z = pos
            cval = sum(values.get(ls.norm(i["name"]), 0) * i["n"] for i in items if "name" in i)
            d = round(math.hypot(x - sx, z - sz))
            # +1 so the player does not land inside the chest.
            tp = f"/tp {x} {y + 1} {z}"
            for i in items:
                nm = i.get("name", "")
                unit = values.get(ls.norm(nm), 0)
                w.writerow([seed, cid, src, itype, "", x, y, z, tp, "exact", d, "",
                            cval, len(items), "observed", "full-generation", "",
                            i.get("s", ""), nm, i.get("id", ""), i.get("d", ""), i.get("n", ""),
                            unit, unit * i.get("n", 0),
                            "yes" if ls.norm(nm) in mingate else ""])
                n_items += 1
            if not items:
                w.writerow([seed, cid, src, itype, "", x, y, z, tp, "exact", d, "",
                            0, 0, "observed", "full-generation", "empty",
                            "", "", "", "", "", "", "", ""])
    import collections
    by = collections.Counter(r[0] for r in rows)
    print(f"{out}: {len(rows)} chests, {n_items} item rows, radius {args.radius}")
    for k, v in by.most_common():
        print(f"   {v:5d}  {k}")


if __name__ == "__main__":
    sys.exit(main())
