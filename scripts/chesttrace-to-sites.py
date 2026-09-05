#!/usr/bin/env python3
"""Merge `-Dgtnhdet.chesttrace=true` output into the measured chest-site table.

usage: chesttrace-to-sites.py <trace.log> [more.log ...] --table chest-sites.json [--write]

`chest-sites.json` is the one hand-fed input the village-chest prediction depends on, and a piece
class missing from it produces NO chest at all — indistinguishable in the output from a building that
genuinely has none. This turns a trace into table rows so the table is grown by MEASUREMENT rather
than by transcribing decompiled sources.

Each `[chesttrace]` line names the piece, its orientation (`mode`), the coordinate space the fork used
(`src`), the container size and class, the loot category, and the site's local coordinates. Those are
exactly the fields a `Site` carries, so the mapping is mechanical.

CONFLICTS ARE REPORTED, NEVER SILENTLY APPLIED. If the table already holds a row for a
(piece, mode, category, local) and the trace disagrees on size/type/countDrawn/src, that means either
the pack moved something or one of the two measurements is wrong; overwriting would destroy the
evidence that they disagree. --write applies additions only; conflicts are printed and skipped.
"""
import argparse
import json
import re
import sys
from collections import Counter

LINE = re.compile(
    r"\[chesttrace\] seed=(?P<seed>-?\d+) what=(?P<what>\w+) piece=(?P<piece>\S+) src=(?P<src>\S+) "
    r"mode=(?P<mode>-?\d+) countdrawn=(?P<countdrawn>\w+) min=(?P<min>[-\d,]+) local=(?P<local>[-\d,]+) "
    r"abs=(?P<abs>[-\d,]+) cat=(?P<cat>\S+) rolls=(?P<rolls>\d+) tmin=(?P<tmin>\d+) tmax=(?P<tmax>\d+) "
    r"size=(?P<size>\d+) itype=(?P<itype>\S+)")


def key(s):
    return (s["piece"], s["mode"], s["category"], s["lx"], s["ly"], s["lz"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("logs", nargs="+")
    ap.add_argument("--table", required=True)
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args()

    seen, skipped_none = {}, 0
    for path in args.logs:
        for line in open(path, encoding="utf-8", errors="ignore"):
            m = LINE.search(line)
            if not m:
                continue
            g = m.groupdict()
            if g["piece"] == "none":
                # No piece on the stack: an absolute-fork chest not owned by a structure component
                # (RWG decoration, Roguelike). Nothing to add to a per-piece table.
                skipped_none += 1
                continue
            lx, ly, lz = (int(v) for v in g["local"].split(","))
            site = {
                "piece": g["piece"], "mode": int(g["mode"]), "category": g["cat"],
                "countDrawn": g["countdrawn"] == "true", "src": g["src"],
                "size": int(g["size"]), "type": g["itype"],
                "lx": lx, "ly": ly, "lz": lz,
            }
            seen.setdefault(key(site), site)

    table = json.load(open(args.table, encoding="utf-8"))
    existing = {key(s): s for s in table["sites"]}
    chestless = {(e["piece"], e["mode"]) for e in table.get("chestless", [])}

    new, conflict, same = [], [], 0
    for k, s in seen.items():
        old = existing.get(k)
        if old is None:
            new.append(s)
        elif all(old.get(f) == s.get(f) for f in ("countDrawn", "src", "size", "type")):
            same += 1
        else:
            conflict.append((old, s))

    print(f"trace rows with a piece : {sum(1 for _ in seen)} distinct sites "
          f"({skipped_none} piece=none rows skipped)")
    print(f"  already in the table  : {same}")
    print(f"  NEW                   : {len(new)}")
    print(f"  CONFLICTING           : {len(conflict)}")
    for old, s in conflict:
        diffs = [f"{f}: {old.get(f)!r} -> {s.get(f)!r}"
                 for f in ("countDrawn", "src", "size", "type") if old.get(f) != s.get(f)]
        print(f"    {s['piece']} mode={s['mode']} cat={s['category']}: " + "; ".join(diffs))

    if new:
        byclass = Counter(s["piece"].rsplit("$", 1)[-1].rsplit(".", 1)[-1] for s in new)
        print("  new sites by piece class:")
        for k2, v in byclass.most_common():
            print(f"     {v:>3}  {k2}")
        # A piece the trace shows placing a chest must not also be listed as chestless — that row was
        # a measurement made where the chest happened not to spawn, and it would suppress the site.
        clash = [s for s in new if (s["piece"], s["mode"]) in chestless]
        if clash:
            print(f"  NOTE: {len(clash)} new sites are for (piece, mode) currently marked CHESTLESS; "
                  f"those chestless rows are removed by --write, since a measured chest disproves them.")

    if not args.write:
        print("\n(dry run — pass --write to apply additions)")
        return 0

    add_keys = {(s["piece"], s["mode"]) for s in new}
    table["chestless"] = [e for e in table.get("chestless", [])
                          if (e["piece"], e["mode"]) not in add_keys]
    table["sites"].extend(new)
    with open(args.table, "w", encoding="utf-8") as f:
        json.dump(table, f, indent=1)
        f.write("\n")
    print(f"\nwrote {args.table}: {len(table['sites'])} sites, {len(table['chestless'])} chestless")
    return 0


if __name__ == "__main__":
    sys.exit(main())
