#!/usr/bin/env python3
"""Print an in-game verification sheet for chosen seeds: every criterion, with a /tp and what to expect.

usage: verify-sheet.py <value_table.csv> <rows.jsonl> [--chests N] [--radius N]

The ranking output says a seed passed. This says WHERE to stand and WHAT should be there, which is a
different job: a criterion that passed for the wrong reason looks identical in a ranking table.

Y CONFIDENCE IS NOT UNIFORM, and the sheet labels it per line rather than printing a number that looks
equally trustworthy everywhere:

  exact     Roguelike dungeon chests, stronghold chests. Predicted Y is the real Y.
  nominal   Village-piece chests. The fork does not use Y, so the module never predicts it — X and Z
            are exact, Y is the piece box origin. Measured 17/17 correct in XZ, 0/17 on Y.
  approx    Witchery covens. The mod generates AFTER chunk decoration and stage 0 has only virgin
            terrain, so where decoration raised the column the structure sits one block higher than
            predicted. Contents survive this; the coordinate may be one low.
  surface   Biome squares. These are region centres, not blocks; Y is whatever the ground is.
  dim 7     Twilight Forest vein cells. Overworld X/Z map 1:1, so build the portal at that X/Z.

Y IS NEVER INVENTED. Where stage 0 knows the height it is printed. Where it does not — villages and
biome squares, whose Y is terrain-dependent and never predicted — the sheet emits SKY_Y (200) and says
so, rather than a plausible-looking 64 that is underground on most of these coordinates and reads as
though it were a prediction.
"""
import argparse
import importlib.util
import json
import math
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


# High enough to clear any terrain in the pack, low enough to stay inside the build limit. Teleporting
# here and descending is the reliable way to reach a coordinate whose ground height is unknown.
SKY_Y = 200


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("value_table")
    ap.add_argument("rows")
    ap.add_argument("--chests", type=int, default=4)
    ap.add_argument("--radius", type=int, default=60)
    args = ap.parse_args()

    mc = load("multi_criteria", "multi-criteria.py")
    ls = load("loot_score", "loot-score.py")
    values, limits, mins, display, _ = ls.load_values(args.value_table, "max")
    seeds = {s.seed: s for s in ls.load_stage0(pathlib.Path(args.rows))}

    for line in open(args.rows, encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        row = json.loads(line)
        s = seeds[row["seed"]]
        sx, sy, sz = s.spawn
        scoped = s.in_scope(args.radius)
        score, _u, marginals, _q = ls.score_seed(scoped, values, limits)

        print("=" * 78)
        print(f"seed {row['seed']}      loot {score:,}      spawn_iters {row.get('spawn_iters')}")
        print("=" * 78)
        print(f"  SPAWN            /tp {sx} {sy} {sz}")
        print()

        v = mc.nearest_village(row)
        if v:
            print(f"  VILLAGE          /tp {v[1]} {SKY_Y} {v[2]}       {v[0]:.0f} blocks   "
                  f"[Y unknown — fly down]")
            # Name the pieces so "is this the right village" is answerable on arrival.
            for st in row.get("village_starts") or []:
                if st["c"][0] * 16 + 8 == v[1] and st["c"][1] * 16 + 8 == v[2]:
                    pieces = st.get("pieces", "")
                    n = pieces.split(" pieces:")[0] if " pieces:" in pieces else "?"
                    print(f"                   expect {n} pieces, {len(st.get('chests') or [])} predicted chests")
                    break
        c = mc.nearest_circle(row)
        if c:
            # The coven's container carries a real predicted Y, so this one coordinate is usable as-is
            # rather than needing a sky drop. It can be one block low: the mod generates after chunk
            # decoration and stage 0 sees only virgin terrain.
            cy, citems, ctype = None, None, None
            for cell in row.get("witchery_cells") or []:
                if cell.get("winner") != "WorldHandlerCoven":
                    continue
                for ch in cell.get("chests") or []:
                    p = ch["pos"]
                    if (p[0], p[2]) == (c[1], c[2]):
                        cy, ctype = p[1], ch.get("type")
                        citems = ", ".join(f"{i.get('name', i['id'])} x{i['n']}" for i in ch.get("items", []))
            if cy is not None:
                print(f"  COVEN CIRCLE     /tp {c[1]} {cy} {c[2]}         {c[0]:.0f} blocks   "
                      f"[Y predicted, may be 1 low]")
                print(f"                   {ctype} here: {citems}")
            else:
                print(f"  COVEN CIRCLE     /tp {c[1]} {SKY_Y} {c[2]}       {c[0]:.0f} blocks   "
                      f"[Y unknown — fly down]")
        dry, hum = mc.biome_squares(row)
        if dry:
            print(f"  NO-RAIN {dry[0]}x{dry[0]}      /tp {dry[2]} {SKY_Y} {dry[3]}       {dry[1]:.0f} blocks   "
                  f"[square CENTRE, {dry[0]*16} across; Y unknown — fly down]")
        if hum:
            print(f"  HUMID {hum[0]}x{hum[0]}        /tp {hum[2]} {SKY_Y} {hum[3]}       {hum[1]:.0f} blocks   "
                  f"[square CENTRE, {hum[0]*16} across; Y unknown — fly down]")
        # TF shard veins are NOT printed: the predictor is invalid on daily-707 (GT 5.09.54 moved vein
        # placement into saved OregenPattern world data; measured 0% shard precision over 64 real cells).
        # A /tp to a confidently wrong vein is worse than no line.
        br = row.get("biomeregion") or {}
        if br.get("pg") is not None:
            ps = br.get("ps", 5)
            pdx, pdz = br["pd"]; phx, phz = br["ph"]
            # centre of each square of the TOUCHING PAIR — the pair is what the touching criterion
            # scores, and it is usually NOT the largest square of either kind shown above.
            print(f"  BIOME PAIR       no-rain {ps}x{ps} centre /tp {(pdx*16 + ps*8)} {SKY_Y} {(pdz*16 + ps*8)}   "
                  f"humid {ps}x{ps} centre /tp {(phx*16 + ps*8)} {SKY_Y} {(phz*16 + ps*8)}   gap {br['pg']} chunks")
        e = mc.nearest_enchant_dungeon(row)
        if e:
            print(f"  ENCHANT TABLE    /tp {e[1]} {e[2]} {e[3]}       {e[0]:.0f} blocks   "
                  f"[table block EXACT; {e[4]} dungeon]")
        d = mc.nearest_dungeon(row)
        if d:
            print(f"  RL DUNGEON       /tp {d[1]} {SKY_Y} {d[2]}       {d[0]:.0f} blocks   "
                  f"[trigger chunk, +-8 blocks; entrance tower on the surface]")
        print()
        # Every chest holding a Min-column item, regardless of score rank. The Min gate is a REQUIREMENT
        # (the seed is disqualified without these), so their locations are verification targets in their
        # own right — and the top-N list routinely hides them: a 10k chest shows its first five slots
        # while the qualifying plate sits in slot 14.
        real_mins = {k: v for k, v in mins.items() if v}
        if real_mins:
            print("  MIN-GATE ITEMS")
            for key, need in real_mins.items():
                total = 0
                holders = []
                for ch in scoped:
                    n = sum(i["n"] for i in ch.items if "name" in i and ls.norm(i["name"]) == key)
                    if n:
                        total += n
                        holders.append((ch, n))
                name = display.get(key, key)
                print(f"    {name}: need >= {need}, have {total}")
                for ch, n in holders:
                    ynote = "Y nominal" if getattr(ch, "y_nominal", False) else "Y exact"
                    print(f"      /tp {ch.pos[0]} {ch.pos[1] + 1} {ch.pos[2]}   x{n}   {ch.source:10s} [{ynote}]")
            print()
        print(f"  TOP {args.chests} LOOT CHESTS")
        # score_seed yields (earned, raw, chest); rank on EARNED, which is what the chest is worth
        # after the seed-level Limit cap, not on its raw value.
        top = sorted(marginals, key=lambda m: -m[0])[:args.chests]
        for marg, _raw, ch in top:
            src = ch.source
            ynote = "Y nominal" if getattr(ch, "y_nominal", False) else "Y exact"
            print(f"    /tp {ch.pos[0]} {ch.pos[1] + 1} {ch.pos[2]}   {src:10s} {marg:>7,} pts   [{ynote}]")
            # Sorted by what the stack is WORTH, not by slot. Slot order buried a 20k Alumite plate at
            # slot 14 behind four stacks of gravel. Items with no row in the value table print as "-"
            # rather than 0: unscored is not worthless, and collapsing the two hides table gaps.
            scored, unscored = [], []
            for i in ch.items:
                nm = i.get("name", str(i["id"]))
                unit = values.get(ls.norm(nm)) if "name" in i else None
                if unit:
                    scored.append((unit * i["n"], nm, i["n"], unit))
                else:
                    unscored.append((nm, i["n"]))
            for pts, nm, n, unit in sorted(scored, reverse=True):
                print(f"       {pts:>8,}  {nm} x{n}  ({unit:,}/ea)")
            if unscored:
                print(f"              -  {', '.join(f'{nm} x{n}' for nm, n in unscored)}")
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
