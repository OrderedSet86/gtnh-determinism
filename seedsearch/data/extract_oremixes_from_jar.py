#!/usr/bin/env python3
"""Extract the OreMixes table from `javap -c -p gregtech.api.enums.OreMixes` output.

usage: extract_oremixes_from_jar.py <javap-output.txt> <out.json> [--metas-from <reference.json>]

WHY THIS MUST BE RE-RUN PER PACK. `vein_predict` draws `rand.nextInt(sWeight)` and then walks the mix
list in enum order, so BOTH the total weight and the ordering are part of the algorithm. A table from
another GregTech build does not merely lose the mixes it never had — it shifts every draw, and the
predictor returns confident nonsense rather than failing.

Measured, GTNH daily-707 (gregtech-5.09.54.115) against the shipped 2.8.4 table:

    mixes    79  ->  123
    sWeight  3568 -> 4770

and three Twilight Forest shard predictions checked in-game came back 0/3, two of them naming mixes
that are not shard mixes at all.

TWO BYTECODE SHAPES. GT 5.09.51 built each enum constant with a long positional constructor; 5.09.54
uses a fluent `OreMixBuilder` chain. This parses the builder chain and falls back to reporting what it
could not read, rather than emitting a short table that looks complete.
"""
import json
import re
import sys

INT = re.compile(r"\b(?:bipush|sipush)\s+(-?\d+)|\biconst_(m?\d)\b|\bldc\w*\s+#\d+\s+//\s+int\s+(-?\d+)")
STR = re.compile(r'ldc\w*\s+#\d+\s+//\s+String\s+(.*?)\s*$')
MAT = re.compile(r"getstatic\s+#\d+\s+//\s+Field gregtech/api/enums/Materials\.(\w+):")
DIMDEF = re.compile(r"getstatic\s+#\d+\s+//\s+Field galacticgreg/api/enums/DimensionDef\.(\w+):")
CALL = re.compile(r"invokevirtual\s+#\d+\s+//\s+Method gregtech/common/OreMixBuilder\.(\w+):\(([^)]*)\)")


def ints_before(lines, i, count):
    """The `count` integer pushes immediately preceding line i, in source order."""
    got = []
    j = i - 1
    while j >= 0 and len(got) < count:
        m = INT.search(lines[j])
        if m:
            v = m.group(1) or m.group(3)
            if v is None:
                g = m.group(2)
                v = -1 if g == "m1" else g
            got.append(int(v))
        elif "invokevirtual" in lines[j] or "invokespecial" in lines[j]:
            break
        j -= 1
    return list(reversed(got))


def strings_before(lines, i):
    """String constants pushed into the array argument immediately preceding line i."""
    out = []
    j = i - 1
    while j >= 0:
        if "anewarray" in lines[j]:
            break
        m = STR.search(lines[j])
        if m:
            out.append(m.group(1))
        j -= 1
    return list(reversed(out))


def parse(path):
    lines = open(path).read().splitlines()
    starts = [i for i, l in enumerate(lines)
              if re.search(r"new\s+#\d+\s+//\s+class gregtech/api/enums/OreMixes\b", l)]
    mixes, problems = [], []
    for n, start in enumerate(starts):
        end = starts[n + 1] if n + 1 < len(starts) else len(lines)
        blk = lines[start:end]
        mix = {"dims": [], "spaceDims": [], "enabledByDefault": True}

        # enum constant name, then its ordinal, are the first two pushes in the block
        for k, l in enumerate(blk):
            m = STR.search(l)
            if m:
                mix["enumConstant"] = m.group(1)
                idx = ints_before(blk, k + 2, 1) or [None]
                nxt = INT.search(blk[k + 1]) if k + 1 < len(blk) else None
                if nxt:
                    v = nxt.group(1) or nxt.group(3) or nxt.group(2)
                    mix["enumIndex"] = int(-1 if v == "m1" else v)
                break

        for k, l in enumerate(blk):
            c = CALL.search(l)
            if not c:
                continue
            fn, sig = c.group(1), c.group(2)
            if fn == "name":
                s = [STR.search(x) for x in blk[max(0, k - 3):k]]
                s = [x.group(1) for x in s if x]
                if s:
                    mix["name"] = s[-1]
            elif fn == "heightRange":
                v = ints_before(blk, k, 2)
                if len(v) == 2:
                    mix["minY"], mix["maxY"] = v
            elif fn in ("weight", "density", "size"):
                v = ints_before(blk, k, 1)
                if v:
                    mix[fn] = v[0]
            elif fn == "enableInDim":
                if "java/lang/String" in sig:
                    mix["dims"].extend(strings_before(blk, k))
                else:
                    for j in range(k - 1, max(0, k - 40), -1):
                        if "anewarray" in blk[j]:
                            break
                        d = DIMDEF.search(blk[j])
                        if d:
                            mix["spaceDims"].append(d.group(1))
            elif fn in ("primary", "secondary", "inBetween", "sporadic"):
                for j in range(k - 1, max(0, k - 4), -1):
                    d = MAT.search(blk[j])
                    if d:
                        key = {"inBetween": "between"}.get(fn, fn)
                        mix[key] = d.group(1)
                        break
            elif fn in ("disabledByDefault", "setEnabledByDefault"):
                mix["enabledByDefault"] = False

        missing = [f for f in ("name", "enumIndex", "weight", "minY", "maxY", "size") if f not in mix]
        if missing:
            problems.append((mix.get("enumConstant", f"block@{start}"), missing))
            continue
        mix.setdefault("density", 0)
        mix["overworld"] = "Overworld" in mix["dims"]
        mixes.append(mix)
    return mixes, problems


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    mixes, problems = parse(sys.argv[1])

    # Material META IDs cannot be read from this class: newer GT populates `Materials` static fields at
    # runtime rather than declaring them as an enum with literal ids. They are only needed to IDENTIFY an
    # observed vein from its ore metas (the judge path), never to predict one, so they are carried across
    # by material NAME from a reference table and simply left absent where that table does not know the
    # material. Absent is honest; a guessed meta would mis-identify a vein.
    if "--metas-from" in sys.argv:
        ref = json.load(open(sys.argv[sys.argv.index("--metas-from") + 1]))
        name2meta = {}
        for r in ref:
            for slot in ("primary", "secondary", "between", "sporadic"):
                if r.get(slot) and r.get(slot + "Meta") is not None:
                    name2meta[r[slot]] = r[slot + "Meta"]
        hit = miss = 0
        for m in mixes:
            for slot in ("primary", "secondary", "between", "sporadic"):
                mat = m.get(slot)
                if mat is None:
                    continue
                if mat in name2meta:
                    m[slot + "Meta"] = name2meta[mat]
                    hit += 1
                else:
                    miss += 1
        print(f"metas carried across by name: {hit} filled, {miss} unknown")
    mixes.sort(key=lambda m: m["enumIndex"])

    # cumWeightAfter is what the linear walk consumes; recompute rather than carrying it over, since a
    # stale value would reproduce exactly the bug this rewrite exists to fix.
    run = 0
    for m in mixes:
        run += m["weight"]
        m["cumWeightAfter"] = run

    # Same key ORDER as the original table. The Java side of the parity test hand-parses this file by
    # scanning forward from each key name, so a reordered file is not merely cosmetic to it.
    order = ["enumIndex", "name", "enabledByDefault", "dims", "spaceDims", "minY", "maxY", "weight",
             "density", "size", "primary", "secondary", "between", "sporadic", "localize",
             "primaryMeta", "secondaryMeta", "betweenMeta", "sporadicMeta", "overworld",
             "cumWeightAfter", "enumConstant"]
    ordered = [{k: m[k] for k in order if k in m} for m in mixes]
    json.dump(ordered, open(sys.argv[2], "w"), indent=1)
    tf = [m for m in mixes if "Twilight Forest" in m["dims"]]
    print(f"{len(mixes)} mixes, sWeight {sum(m['weight'] for m in mixes)}")
    print(f"  Twilight Forest: {len(tf)} mixes, weight {sum(m['weight'] for m in tf)}")
    print(f"  Overworld:       {sum(1 for m in mixes if m['overworld'])} mixes")
    if problems:
        # Loudly, because a silently short table is the failure mode that produced 0/3 in-game.
        print(f"\n{len(problems)} block(s) could not be parsed — the table is INCOMPLETE:")
        for name, miss in problems:
            print(f"    {name}: missing {', '.join(miss)}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
