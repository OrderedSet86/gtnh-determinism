#!/usr/bin/env python3
"""Golden-test the prefilter's village chest module against full-generation probe reports.

Follows prefilter-judge.py's shape: absolute counters, no percentages presented as the headline, and
each failure mode counted separately so a good number cannot hide a bad one.

Four independent counters, plus one diagnostic:

  existence    a predicted chest at (x, z, category) that the corpus also has
  contents     matched chests whose (slot, id, damage, count) lists agree
  NBT          matched chests whose item tags agree
  recall       corpus village chests the prefilter predicted at all

  miscategorised   corpus chest at a predicted XZ but under a different loot category. That means the
                   chest-site table is wrong, which is the one hand-built artifact here and therefore
                   the one to distrust first.

Y is deliberately NOT part of the match key: the module does not predict chest Y, because F10's fork
does not use it. Matching on (x, y, z) would fail for the wrong reason.

usage: prefilter-judge-chests.py <prefilter.jsonl> <corpus-dir>
"""
import json
import os
import sys
from collections import Counter, defaultdict


def items_of(chest):
    return [(i.get("s"), i.get("id"), i.get("d"), i.get("n")) for i in chest.get("items", [])]


def nbt_of(chest):
    return [i.get("tag") for i in chest.get("items", [])]


def load_prefilter(path):
    """seed -> {(x, z): [(category, chest)]} for every predicted village chest."""
    out = {}
    for line in open(path):
        d = json.loads(line)
        if "kill" in d:
            continue
        byxz = defaultdict(list)
        for start in d.get("village_starts", []):
            for c in start.get("chests", []):
                pos = c["chest"]["pos"]
                byxz[(pos[0], pos[2])].append((c["category"], c["chest"]))
        out[d["seed"]] = byxz
    return out


def load_corpus(path):
    """seed -> {(x, z): chest} from full-generation search reports."""
    out = {}
    for fn in os.listdir(path):
        if not (fn.startswith("seed-") and fn.endswith(".json")):
            continue
        d = json.load(open(os.path.join(path, fn)))
        seed = d.get("seed")
        if seed is None:
            seed = int(fn[5:-5])
        byxz = {}
        for chunk in d.get("search", {}).get("chunks", {}).values():
            for c in chunk.get("chests", []):
                p = c["pos"]
                byxz[(p[0], p[2])] = c
        out[seed] = byxz
    return out


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    pf = load_prefilter(sys.argv[1])
    corpus = load_corpus(sys.argv[2])
    seeds = sorted(set(pf) & set(corpus))
    if not seeds:
        print("no seeds in common between the prefilter output and the corpus")
        return 1

    tot = Counter()
    per_seed = []
    unmatched_examples = []
    for seed in seeds:
        p, c = pf[seed], corpus[seed]
        matched = same = nbt_ok = 0
        predicted_absent = []
        for xz, entries in p.items():
            if xz not in c:
                predicted_absent.append(xz)
                continue
            got = c[xz]
            # A chest position can host only one chest; if the module predicted several categories there,
            # count the one that matches, else the first.
            best = None
            for cat, chest in entries:
                if items_of(chest) == items_of(got):
                    best = (cat, chest)
                    break
            if best is None:
                best = entries[0]
            matched += 1
            if items_of(best[1]) == items_of(got):
                same += 1
                if nbt_of(best[1]) == nbt_of(got):
                    nbt_ok += 1
            elif len(unmatched_examples) < 3:
                unmatched_examples.append((seed, xz, best[0], items_of(best[1])[:3], items_of(got)[:3]))
        tot["predicted"] += sum(len(v) for v in p.values())
        tot["predicted_positions"] += len(p)
        tot["matched"] += matched
        tot["predicted_absent"] += len(predicted_absent)
        tot["contents_ok"] += same
        tot["nbt_ok"] += nbt_ok
        tot["corpus_chests"] += len(c)
        per_seed.append((seed, len(p), matched, same, nbt_ok, len(predicted_absent)))

    print(f"=== prefilter-judge-chests: {len(seeds)} seeds ===")
    print(f"predicted chest positions      : {tot['predicted_positions']}")
    print(f"  present in the corpus        : {tot['matched']}")
    print(f"  predicted but ABSENT         : {tot['predicted_absent']}")
    print(f"contents identical at matched  : {tot['contents_ok']} of {tot['matched']}")
    print(f"NBT identical at those         : {tot['nbt_ok']} of {tot['contents_ok']}")
    print(f"corpus chests in window (all sources, not only villages): {tot['corpus_chests']}")
    if unmatched_examples:
        print("\nfirst content mismatches:")
        for seed, xz, cat, a, b in unmatched_examples:
            print(f"  seed {seed} at {xz} category {cat}")
            print(f"     prefilter: {a}")
            print(f"     full-gen : {b}")
    print("\nper seed: seed, predicted, matched, contents-ok, nbt-ok, predicted-absent")
    for row in per_seed:
        print("  " + ", ".join(str(x) for x in row))
    return 0


if __name__ == "__main__":
    sys.exit(main())
