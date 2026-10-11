#!/usr/bin/env python3
"""Multiseed check: MeteorPrefilter predictions vs a warm meteortrace probe log.

Reads the warm batch log (seed markers + [meteortrace] spawnMeteoriteCenter lines) and the
prefilter JSONL for the same seeds, and diffs candidates whose impact column lies inside the probe
window (|x|,|z| <= --window blocks around the origin): existence, landing y, size (exact string
match on the printed digits), and the crater verdict (prefilter reject==absent vs trace
crater=true on the accepted rung).

Craters the trace carves where the prefilter said buried are counted separately as
"crater_missed" — the known one-way populate-moves-the-surface approximation (0.13.0 knew it as
the (1084,363) lake). The reverse direction, a predicted crater the game does not carve, is a
model failure and exits nonzero.

Usage: compare-ms25.py <warm.log> <prefilter.jsonl> [--window 480]
"""
import argparse
import json
import re
import sys

SEED_RE = re.compile(r'\[probe\] (?:seed=|warm slot \d+/\d+: seed )(-?\d+)')
TRACE_RE = re.compile(
    r'\[meteortrace\] spawnMeteoriteCenter at (-?\d+),(-?\d+),(-?\d+) -> (true|false) '
    r'skyMode=(\d+) crater=(true|false) decay=\w+ size=([\d.]+)')


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("log")
    ap.add_argument("jsonl")
    ap.add_argument("--window", type=int, default=480)
    args = ap.parse_args()

    # truth[seed][(x,z)] = (y, crater, size_str) for accepted spawns; attempted[seed] = {(x,z)}
    truth, attempted, seed = {}, {}, None
    for line in open(args.log, errors="replace"):
        m = SEED_RE.search(line)
        if m:
            seed = int(m.group(1))
            truth.setdefault(seed, {})
            attempted.setdefault(seed, set())
            continue
        m = TRACE_RE.search(line)
        if m and seed is not None:
            x, y, z = int(m.group(1)), int(m.group(2)), int(m.group(3))
            attempted[seed].add((x, z))
            if m.group(4) == "true":
                truth[seed][(x, z)] = (y, m.group(6) == "true", m.group(7))

    n = exist_ok = exist_bad = y_ok = y_bad = size_ok = size_bad = 0
    crater_ok = crater_missed = crater_false = 0
    for line in open(args.jsonl):
        row = json.loads(line)
        s = row["seed"]
        if s not in truth:
            print(f"seed {s}: not in probe log, skipped")
            continue
        for m in row.get("meteorites") or []:
            if abs(m["x"]) > args.window or abs(m["z"]) > args.window:
                continue
            key = (m["x"], m["z"])
            n += 1
            hit = truth[s].get(key)
            predicted_exists = m.get("reject") != "nosite"
            if key not in attempted[s]:
                # window edge: the probe never generated the impact chunk; not comparable
                print(f"seed {s} {key}: never attempted in probe window, skipped")
                n -= 1
                continue
            if predicted_exists != (hit is not None):
                exist_bad += 1
                print(f"seed {s} {key}: existence MISMATCH pred={predicted_exists} probe={hit is not None}")
                continue
            exist_ok += 1
            if hit is None:
                continue
            py, (ty, tcrater, tsize) = m["y"], hit
            if py == ty:
                y_ok += 1
            else:
                y_bad += 1
                print(f"seed {s} {key}: landing y MISMATCH pred={py} probe={ty}")
            if abs(m["size"] - float(tsize)) < 0.001:
                size_ok += 1
            else:
                size_bad += 1
                print(f"seed {s} {key}: size MISMATCH pred={m['size']} probe={tsize}")
            pcrater = m.get("reject") is None
            if pcrater == tcrater:
                crater_ok += 1
            elif tcrater:
                crater_missed += 1
                print(f"seed {s} {key}: crater missed (pred {m.get('reject')}, probe carves) — known direction")
            else:
                crater_false += 1
                print(f"seed {s} {key}: FALSE crater (pred crater, probe does not carve)")

    print(f"\n{n} in-window candidates over {len(truth)} seeds")
    print(f"existence {exist_ok}/{exist_ok + exist_bad}   landing-y {y_ok}/{y_ok + y_bad}   "
          f"size {size_ok}/{size_ok + size_bad}")
    print(f"crater verdict: {crater_ok} match, {crater_missed} missed (known direction), "
          f"{crater_false} FALSE positives")
    sys.exit(1 if (exist_bad or y_bad or size_bad or crater_false) else 0)


if __name__ == "__main__":
    main()
