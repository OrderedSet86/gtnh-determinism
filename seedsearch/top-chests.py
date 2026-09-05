#!/usr/bin/env python3
"""Rank individual chests across a whole sweep, rather than ranking seeds.

usage: top-chests.py <value_table.csv> <sweep.jsonl> [--top N] [--radius N] [--source S] [--per-seed]

A seed's loot score is a sum over its chests, so a seed can rank highly on breadth alone. This answers
the different question "where is the single best chest in the corpus", which is what matters when the
plan is to make one trip rather than to settle nearby.

SCOPE WARNING, stated because it is easy to misread this as a survey of the whole sweep: only seeds
that SURVIVED the kill funnel carry chest data at all. A seed killed at the village-piece gate returns
before dungeons are ever generated, so it contributes no chests. This ranks the chests of the
survivors, not the chests of the sweep. A killed seed may well hold a better chest; nothing here
would show it.

Value is RAW stack value (unit price x count), not the marginal value used for seed ranking. Marginal
value depends on what else the seed holds — the same chest is worth less in a seed that already caps
the item — so it is not comparable ACROSS seeds. Raw value is.
"""
import argparse
import collections
import importlib.util
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("value_table")
    ap.add_argument("sweep")
    ap.add_argument("--top", type=int, default=15)
    ap.add_argument("--radius", type=int, default=60, help="chunks around spawn; chests outside are skipped")
    ap.add_argument("--source", default=None, help="restrict to one source, e.g. roguelike or village")
    args = ap.parse_args()

    ls = load("loot_score", "loot-score.py")
    values, _limits, _mins, _display, _ = ls.load_values(args.value_table, "max")

    best = []
    seeds_with_chests = killed = nochest = 0
    chest_total = 0
    unscored = collections.Counter()

    for line in open(args.sweep, encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        try:
            d = json.loads(line)
        except ValueError:
            continue
        if "kill" in d:
            killed += 1
            continue
        spawn = d.get("spawn")
        if not spawn or not d.get("spawn_iters"):
            continue  # origin-defaulted spawn: the radius window would be centred wrong
        scx, scz = spawn[0] >> 4, spawn[2] >> 4
        found = False
        chests = []
        for start in d.get("village_starts", []):
            for entry in start.get("chests", []):
                ch = entry["chest"]
                chests.append(("village", ch["pos"], ch.get("items", []), None))
        for dungeon in d.get("dungeons", []):
            # Carry the TRIGGER. Roguelike builds the whole dungeon when its trigger chunk is populated,
            # and chests sit a median 128 blocks from it (p99 195). Teleporting straight to a chest can
            # therefore land in a chunk that generates with no dungeon in it, because the trigger never
            # populated — the loot looks absent when it is merely unbuilt. Always visit the trigger first.
            t = dungeon.get("trigger")
            trig = (t[0] * 16 + 8, t[1] * 16 + 8) if t else None
            for ch in dungeon.get("chests", []):
                chests.append(("roguelike", ch["pos"], ch.get("items", []), trig))
        for src, pos, items, trig in chests:
            if args.source and src != args.source:
                continue
            if max(abs((pos[0] >> 4) - scx), abs((pos[2] >> 4) - scz)) > args.radius:
                continue
            found = True
            chest_total += 1
            pts = 0
            for i in items:
                if "name" not in i:
                    continue
                unit = values.get(ls.norm(i["name"]))
                if unit:
                    pts += unit * i["n"]
                else:
                    unscored[i["name"]] += 1
            if pts:
                best.append((pts, d["seed"], src, tuple(pos), tuple(spawn), trig,
                             tuple(sorted(((values.get(ls.norm(i["name"]), 0) * i["n"], i["name"], i["n"])
                                           for i in items if "name" in i), reverse=True))))
        if found:
            seeds_with_chests += 1
        else:
            nochest += 1

    best.sort(key=lambda b: -b[0])
    print(f"=== {chest_total:,} chests from {seeds_with_chests:,} seeds with chest data "
          f"({killed:,} killed by a gate carry none, {nochest:,} survivors had none in radius) ===\n")

    seen = collections.Counter()
    shown = 0
    for pts, seed, src, pos, spawn, trig, items in best:
        if shown >= args.top:
            break
        shown += 1
        dx, dz = pos[0] - spawn[0], pos[2] - spawn[2]
        dist = (dx * dx + dz * dz) ** 0.5
        seen[seed] += 1
        print(f"#{shown:<3} {pts:>8,} pts   /tp {pos[0]} {pos[1] + 1} {pos[2]}   {src}   "
              f"{dist:.0f} blocks from spawn")
        print(f"       seed {seed}   spawn /tp {spawn[0]} 64 {spawn[2]}")
        if trig:
            print(f"       GO HERE FIRST: /tp {trig[0]} 100 {trig[1]}  "
                  f"(trigger chunk — the dungeon does not exist until this populates)")
        for v, nm, n in items[:6]:
            print(f"         {v:>7,}  {nm} x{n}" if v else f"               -  {nm} x{n}")
        print()

    if unscored:
        print(f"{len(unscored)} distinct item types had no row in the value table and scored 0. "
              f"Most frequent:")
        for nm, c in unscored.most_common(8):
            print(f"    {c:>7,}x  {nm}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
