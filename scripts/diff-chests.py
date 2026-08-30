#!/usr/bin/env python3
"""Compare chest EXISTENCE and CONTENTS between two sets of probe search reports.

Usage: diff-chests.py <dirA> <dirB> [--verbose]

Each directory holds per-seed search reports written by a probe batch run with PROBE_SEARCH=true.
Reports are matched by the seed recorded inside them, so the batches may differ in filename or
order. Chests live at search.chunks[<cx,cz>].chests, each entry
{"pos":[x,y,z], "type":..., "items":[{"s":slot,"id":...,"d":damage,"n":count,"tag":...}]}.

Answers three questions separately, because one "chests differ" number conflates causes that have
nothing to do with each other:

  1. existence - a chest in one world and not the other, keyed by block position
  2. contents  - same position, different item list (id / damage / count / slot)
  3. NBT       - same position, same item list, different tag payload

NBT-only differences are reported as a failure, not a footnote. Chest gameplay state lives in tags —
enchantments, charge levels, aspect fills, bee genomes — and treating an NBT-only diff as cosmetic
has hidden real defects in this project before.
"""
import json
import sys
from collections import Counter
from pathlib import Path


# Sidecars the probe writes beside the reports. Skipped by name rather than by "has no seed key", so a
# future sidecar that happens to carry one cannot be mistaken for a report.
SIDECARS = {"gtmats.json", "gtdims.json", "biomes.json"}


def load(d):
    """"seed@dim" -> {(x,y,z) -> chest dict} across every report in the directory.

    Keyed by dimension as well as seed since report format 6. One seed can now have both an overworld
    and a Twilight Forest report in a directory; keying on the seed alone let the second silently
    overwrite the first, so the tool compared OW-against-OW or TF-against-TF depending on glob order
    and printed ALL SEEDS IDENTICAL either way.
    """
    out = {}
    for p in sorted(Path(d).glob("*.json")):
        if p.name in SIDECARS:
            continue
        try:
            r = json.loads(p.read_text())
        except Exception as e:
            print(f"  ! unreadable {p.name}: {e}", file=sys.stderr)
            continue
        seed = r.get("seed")
        if seed is None:
            continue
        key = f"{seed}@{r.get('dim', 0)}"
        if key in out:
            print(f"  ! {p.name}: duplicate seed/dim {key} — earlier report ignored", file=sys.stderr)
        chunks = (r.get("search") or {}).get("chunks") or {}
        chests = {}
        for cv in chunks.values():
            if not isinstance(cv, dict):
                continue
            for c in cv.get("chests") or []:
                chests[tuple(c.get("pos", []))] = c
        out[key] = chests
    return out


def items_no_tag(chest):
    return [(i.get("s"), i.get("id"), i.get("d"), i.get("n")) for i in chest.get("items", [])]


def items_with_tag(chest):
    return [(i.get("s"), i.get("id"), i.get("d"), i.get("n"), i.get("tag")) for i in chest.get("items", [])]


def main():
    verbose = "--verbose" in sys.argv
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    A, B = load(args[0]), load(args[1])

    def sort_key(k):
        seed, _, dim = k.partition("@")
        return (int(dim or 0), int(seed))

    seeds = sorted(set(A) & set(B), key=sort_key)
    print(f"batch A: {len(A)} seeds   batch B: {len(B)} seeds   compared: {len(seeds)}")
    for s in sorted(set(A) ^ set(B)):
        print(f"  ! seed {s} present in only one batch")

    t = Counter()
    bad = []
    for s in seeds:
        a, b = A[s], B[s]
        only_a, only_b = set(a) - set(b), set(b) - set(a)
        common = set(a) & set(b)
        contents = {k for k in common if items_no_tag(a[k]) != items_no_tag(b[k])}
        nbt = {k for k in common if items_with_tag(a[k]) != items_with_tag(b[k])} - contents
        n = len(only_a) + len(only_b) + len(contents) + len(nbt)
        t["A"] += len(a)
        t["B"] += len(b)
        t["existence"] += len(only_a) + len(only_b)
        t["contents"] += len(contents)
        t["nbt"] += len(nbt)
        print(f"  {'OK ' if n == 0 else 'DIFF'} seed {s:>22}  chests A={len(a):4d} B={len(b):4d}"
              f"  existence={len(only_a) + len(only_b)}  contents={len(contents)}  nbt={len(nbt)}")
        if n:
            bad.append(s)
            if verbose:
                for k in sorted(only_a)[:5]:
                    print(f"        only-A {k} {a[k].get('type')}")
                for k in sorted(only_b)[:5]:
                    print(f"        only-B {k} {b[k].get('type')}")
                for k in sorted(contents | nbt)[:5]:
                    print(f"        CHANGED {k}")
                    print(f"          A: {str(items_with_tag(a[k]))[:300]}")
                    print(f"          B: {str(items_with_tag(b[k]))[:300]}")

    print(f"\ntotal chests: A={t['A']} B={t['B']}")
    print(f"existence differences: {t['existence']}")
    print(f"contents  differences: {t['contents']}")
    print(f"NBT-only  differences: {t['nbt']}   <- a failure, not a footnote")
    print("\nVERDICT: " + ("ALL SEEDS IDENTICAL" if not bad
                           else f"{len(bad)}/{len(seeds)} seeds differ: {', '.join(bad)}"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
