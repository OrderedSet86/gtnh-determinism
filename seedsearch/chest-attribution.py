#!/usr/bin/env python3
"""Split a corpus's chests into "the prefilter predicted this" and, for the rest, WHY it did not.

prefilter-judge-chests.py answers how many corpus chests are unexplained. That number is useless on
its own, because most of it is loot sources stage 0 already declares it cannot see (see SOURCE_GROUPS
in loot-score.py). This script says which is which, so the actionable residual can be read off
directly instead of inferred from a total.

Buckets, applied in order — first match wins:

  predicted            the prefilter has a chest at this XZ. Not this script's problem.
  village-piece        inside a village piece bounding box the prefilter DID know about, but with no
                       chest predicted there. A recall failure in the chest-site table, and the most
                       actionable bucket: the prefilter had every input it needed and still missed.
  thaumcraft-hilltop   WorldGenHilltopStones. Exact block fingerprint, see HILLTOP_FINGERPRINT.
  thaumcraft-barrow    WorldGenMound. blockLootCrate / blockLootUrn nearby.
  near-prediction      within NEAR_BLOCKS of a predicted chest. Ambiguous: probably the same
                       structure with an offset, which would be a position bug rather than a missing
                       source. Reported separately so it cannot hide in either direction.
  deep-blind-spot      below DEEP_Y and far from anything predicted: mineshaft corridors and vanilla
                       WorldGenDungeons. Both are declared unseeable at stage 0. Known, not benign.
  unattributed         everything else. THIS IS THE HEADLINE. Target is zero.

A bucket label is not an explanation. Every bucket except `predicted` is a chest the prefilter does
not predict; the buckets exist to rank the work, not to retire it. The summary prints the actionable
subtotal (village-piece + thaumcraft-* + unattributed) separately from the declared blind spots for
exactly that reason.

Block data: the hilltop fingerprint needs blocks, which a search report does not carry. Pass
`--dump <file>` one or more times with the `<out>.dump-<cx>_<cz>.txt` listings written by
`-Dprobe.dump=cx,cz`, or `--thaumcraft <file>` with a report carrying `search.thaumcraft`. Without
either, hilltop chests fall through to `unattributed` and the script says so rather than guessing.

usage: chest-attribution.py <corpus.chests.json|corpus-dir> <prefilter.jsonl>
                            [--dump F]... [--thaumcraft F] [--show N]
"""
import json
import os
import re
import sys
from collections import Counter, defaultdict

# The judge owns the corpus/prefilter readers; import them rather than restating the section list,
# so the two scripts can never disagree about what counts as a prediction. Its filename has a dash,
# so it is not importable by name.
import importlib.util

_spec = importlib.util.spec_from_file_location(
    "prefilter_judge_chests",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "prefilter-judge-chests.py"))
_judge = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_judge)
load_prefilter, load_corpus, within, sig_of = (
    _judge.load_prefilter, _judge.load_corpus, _judge.within, _judge.sig_of)

# WorldGenHilltopStones.func_76484_a places, at the ring's exact centre column only:
#   y   mob spawner        (Blocks.field_150474_ac)
#   y+1 obsidian pedestal  (ConfigBlocks.blockCosmeticSolid meta 1)
#   y+2 the chest          (Blocks.field_150486_ae)
# and ThaumcraftWorldGeneratorMixin then puts an aura node (Thaumcraft:blockAiry) at y+5.
# Verified against beta-3 seed -1636594104014467454, chest (36, 97, 278):
#   4,95,6 minecraft:mob_spawner / 4,96,6 Thaumcraft:blockCosmeticSolid:1 / 4,97,6 minecraft:chest:3
#   4,100,6 Thaumcraft:blockAiry
HILLTOP_FINGERPRINT = [(-1, "Thaumcraft:blockCosmeticSolid", 1), (-2, "minecraft:mob_spawner", None)]
BARROW_BLOCKS = ("Thaumcraft:blockLootCrate", "Thaumcraft:blockLootUrn")
NEAR_BLOCKS = 16
# Vanilla WorldGenDungeons is a declared stage-0 blind spot and is NOT confined to low Y: measured on
# beta-3 seed -1636594104014467454, its chests run from Y41 to Y58 inside the spawn window, in pairs a
# few blocks apart. A cutoff of 40 pushed 29 of them into `unattributed` and made the headline read
# four times worse than it was. 64 is sea level: below it, "deep" is a defensible reading of
# mineshaft/dungeon territory; above it, a chest wants a real explanation.
DEEP_Y = 64

PIECE_RE = re.compile(r"(\w+)@(-?\d+),(-?\d+),(-?\d+)\.\.(-?\d+),(-?\d+),(-?\d+)")


def load_village_boxes(path):
    """seed -> [(name, x0, y0, z0, x1, y1, z1)] for every village piece the prefilter laid out."""
    out = defaultdict(list)
    for line in open(path):
        d = json.loads(line)
        if "kill" in d:
            continue
        for start in d.get("village_starts", []):
            for m in PIECE_RE.finditer(start.get("pieces") or ""):
                out[d["seed"]].append((m.group(1), *(int(v) for v in m.groups()[1:])))
    return out


# `-Dgtnhdet.chesttrace=true` emits one line per refilled inventory carrying the absolute position and
# the generator frame that filled it. That is ground truth for attribution: no heuristic beats the
# caller's own class name. It only covers chests that went through a ChestGenHooks table, so hoppers,
# furnaces, apiaries and directly-written inventories are still unattributed by it.
TRACE_RE = re.compile(r"abs=(-?\d+),(-?\d+),(-?\d+)\b.*?\bcat=(\S+).*?\bcaller=(\S+)")

# Structures generated straight out of chunk population (vanilla WorldGenDungeons, Roguelike) leave no
# frame of their own on the stack — the trace sees only the populate entry point. For those the loot
# table name is the discriminator that the caller cannot be.
GENERIC_CALLERS = ("ChunkGeneratorRealistic",)
CATEGORY_BUCKET = {
    "dungeonChest": "vanilla-dungeon-or-roguelike",
    "mineshaftCorridor": "mineshaft",
    "strongholdLibrary": "stronghold",
    "strongholdCorridor": "stronghold",
    "strongholdCrossing": "stronghold",
}


def load_trace(path):
    """-> ({(x,y,z): (caller, category)}, {(x,y,z): fill_count})"""
    caller, fills = {}, Counter()
    for line in open(path, errors="replace"):
        if "[chesttrace]" not in line:
            continue
        m = TRACE_RE.search(line)
        if not m:
            continue
        pos = (int(m.group(1)), int(m.group(2)), int(m.group(3)))
        caller[pos] = (m.group(5).rsplit(":", 1)[0], m.group(4))
        fills[pos] += 1
    return caller, fills


def bucket_for_caller(c, cat=None):
    """Map a generator frame to a loot source. Unrecognised frames are named, never bucketed away."""
    if any(g in c for g in GENERIC_CALLERS):
        return CATEGORY_BUCKET.get(cat)
    if "WorldGenHilltopStones" in c:
        return "thaumcraft-hilltop"
    if "WorldGenMound" in c:
        return "thaumcraft-barrow"
    if "villagenames" in c or "village" in c.lower():
        return "village-piece"
    if "StructureMineshaftPieces" in c:
        return "mineshaft"
    if "ComponentStronghold" in c or "StructureStrongholdPieces" in c:
        return "stronghold"
    if "WorldGenDungeons" in c:
        return "vanilla-dungeon"
    if "greymerk" in c or "roguelike" in c.lower():
        return "roguelike"
    return None


def load_dumps(paths):
    """-> {(x, y, z): (blockname, meta)} from -Dprobe.dump chunk listings."""
    blocks = {}
    for p in paths:
        m = re.search(r"\.dump-(-?\d+)_(-?\d+)\.txt$", p)
        if not m:
            print(f"  WARNING: cannot read chunk coords from {p!r}; expected .dump-<cx>_<cz>.txt", file=sys.stderr)
            continue
        cx, cz = int(m.group(1)), int(m.group(2))
        for line in open(p):
            pos, _, name = line.strip().partition(" ")
            if not name:
                continue
            lx, y, lz = (int(v) for v in pos.split(","))
            bn, _, meta = name.rpartition(":")
            blocks[(cx * 16 + lx, y, cz * 16 + lz)] = (bn, int(meta))
    return blocks


def in_box(c, boxes):
    x, y, z = c["pos"]
    for name, x0, y0, z0, x1, y1, z1 in boxes:
        if x0 - 1 <= x <= x1 + 1 and z0 - 1 <= z <= z1 + 1 and y0 - 2 <= y <= y1 + 4:
            return name
    return None


def is_hilltop(c, blocks):
    x, y, z = c["pos"]
    for dy, want, meta in HILLTOP_FINGERPRINT:
        got = blocks.get((x, y + dy, z))
        if got is None or got[0] != want or (meta is not None and got[1] != meta):
            return False
    return True


def near_barrow(c, blocks):
    x, y, z = c["pos"]
    for (bx, by, bz), (name, _m) in blocks.items():
        if name in BARROW_BLOCKS and abs(bx - x) <= 16 and abs(bz - z) <= 16 and abs(by - y) <= 16:
            return True
    return False


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dumps, thaum, show, trace = [], None, 30, None
    argv = sys.argv
    skip = set()
    for i, a in enumerate(argv):
        if a in ("--dump", "--thaumcraft", "--show", "--trace") and i + 1 < len(argv):
            skip.add(argv[i + 1])
            if a == "--dump":
                dumps.append(argv[i + 1])
            elif a == "--thaumcraft":
                thaum = argv[i + 1]
            elif a == "--trace":
                trace = argv[i + 1]
            else:
                show = int(argv[i + 1])
    args = [a for a in args if a not in skip]
    if len(args) != 2:
        print(__doc__)
        return 2

    corpus = load_corpus(args[0])
    # Splitting an empty corpus prints no per-seed section and no totals, so "0 unattributed" and
    # "nothing to attribute" produce the same output — and this tool exists to size a residual.
    if not corpus or not any(byxz for byxz, _c, _r in corpus.values()):
        raise SystemExit(f"NOTHING TO ATTRIBUTE: {args[0]} yielded no chests "
                         f"({len(corpus)} seed(s) loaded). An empty attribution is not a complete "
                         f"one — check the reports carry a chest search.")
    pf = load_prefilter(args[1])
    boxes_by_seed = load_village_boxes(args[1])
    blocks = load_dumps(dumps)
    tr_caller, tr_fills = load_trace(trace) if trace else ({}, Counter())
    if tr_caller:
        print(f"chesttrace: {len(tr_caller)} filled positions, "
              f"{sum(1 for p in tr_fills if tr_fills[p] > 1)} of them filled more than once\n")
    unknown_callers = Counter()
    hilltop_hint = set()
    if thaum:
        d = json.load(open(thaum))
        for entry in d.get("search", {}).get("thaumcraft", {}).get("hilltop", []) or []:
            hilltop_hint.add(tuple(entry) if isinstance(entry, list) else entry)

    if not blocks and not hilltop_hint:
        print("NOTE: no --dump or --thaumcraft given, so hilltop/barrow chests cannot be identified")
        print("      and will fall through to `unattributed`. That residual is an upper bound.\n")

    grand = Counter()
    for seed in sorted(corpus):
        byxz, centre, radius = corpus[seed]
        pred = pf.get(seed, {})
        boxes = boxes_by_seed.get(seed, [])
        pred_xz = [xz for xz in pred if within(xz, centre, radius)]

        buckets = defaultdict(list)
        for xz, stack in byxz.items():
            if not within(xz, centre, radius):
                continue
            for c in stack:
                x, y, z = c["pos"]
                if xz in pred:
                    buckets["predicted"].append(c)
                    continue
                # Ground truth first: if the trace saw this chest filled, its caller settles the
                # source outright and no heuristic below gets a say.
                ent = tr_caller.get((x, y, z))
                if ent:
                    cl, cat = ent
                    b = bucket_for_caller(cl, cat)
                    if b:
                        # Strip the method name, then take the simple class (keeps Outer$Inner).
                        # `cl.rsplit(".", 1)[-1]` yields the obfuscated method (func_74875_a), not
                        # the piece, which made the listing useless.
                        simple = cl.rsplit(".", 1)[0].split(".")[-1]
                        buckets[b].append((c, simple) if b == "village-piece" else c)
                        continue
                    unknown_callers[f"{cl} cat={cat}"] += 1
                    buckets["traced-unrecognised-caller"].append(c)
                    continue
                name = in_box(c, boxes)
                if name:
                    # An EMPTY inventory inside a village building is furniture — a Tinkers' casting
                    # table, a barrel, a furnace — not loot the prefilter failed to predict. Counting
                    # those as recall failures inflated this bucket from 5 to 29 on beta-3 at radius
                    # 60. Split them, and keep the loot-bearing ones loud.
                    key = "village-piece" if (c.get("items") or []) else "village-piece-empty"
                    buckets[key].append((c, name))
                    continue
                if (x, y, z) in hilltop_hint or (blocks and is_hilltop(c, blocks)):
                    buckets["thaumcraft-hilltop"].append(c)
                    continue
                if blocks and near_barrow(c, blocks):
                    buckets["thaumcraft-barrow"].append(c)
                    continue
                dmin = min((max(abs(x - px), abs(z - pz)) for px, pz in pred_xz), default=10 ** 9)
                if dmin <= NEAR_BLOCKS:
                    buckets["near-prediction"].append((c, dmin))
                elif y < DEEP_Y:
                    buckets["deep-blind-spot"].append(c)
                else:
                    buckets["unattributed"].append(c)

        total = sum(len(v) for v in buckets.values())
        actionable = (len(buckets["village-piece"]) + len(buckets["thaumcraft-hilltop"])
                      + len(buckets["thaumcraft-barrow"]) + len(buckets["unattributed"]))
        print(f"=== seed {seed}: {total} corpus chests, centre {centre}, radius {radius} ===")
        for k in ("predicted", "village-piece", "thaumcraft-hilltop", "thaumcraft-barrow",
                  "mineshaft", "stronghold", "vanilla-dungeon-or-roguelike",
                  "village-piece-empty", "traced-unrecognised-caller", "near-prediction", "deep-blind-spot",
                  "unattributed"):
            print(f"  {len(buckets[k]):5d}  {k}")
            grand[k] += len(buckets[k])
        print(f"  -----")
        print(f"  {total - len(buckets['predicted']):5d}  UNEXPLAINED (everything but `predicted`)")
        print(f"  {actionable:5d}  of which ACTIONABLE (excludes declared blind spots and ambiguous)")

        if buckets["village-piece"]:
            print(f"\n  village-piece recall failures ({len(buckets['village-piece'])}):")
            for c, name in buckets["village-piece"][:show]:
                print(f"    {c['pos']!s:20s} {name:28s} {sig_of(c)}")
        if buckets["thaumcraft-hilltop"]:
            print(f"\n  thaumcraft hilltop circles ({len(buckets['thaumcraft-hilltop'])}):")
            for c in buckets["thaumcraft-hilltop"][:show]:
                print(f"    {c['pos']!s:20s} stacks={len(c.get('items') or []):3d}  {sig_of(c)}")
        # This bucket is a Y heuristic, not an identification, so it prints its own evidence. A
        # bucket label that hides its basis is how a blind spot turns into a "benign" number.
        if buckets["deep-blind-spot"]:
            ys = sorted(c["pos"][1] for c in buckets["deep-blind-spot"])
            print(f"\n  deep-blind-spot is a Y<{DEEP_Y} heuristic, NOT an identification:"
                  f" {len(ys)} chests, Y {ys[0]}..{ys[-1]}, median {ys[len(ys) // 2]}")
        if buckets["unattributed"]:
            print(f"\n  UNATTRIBUTED ({len(buckets['unattributed'])}), showing {min(show, len(buckets['unattributed']))}:")
            for c in buckets["unattributed"][:show]:
                print(f"    {c['pos']!s:20s} {c.get('type', '?'):22s} {sig_of(c)}")
        print()

    if unknown_callers:
        print("traced callers with no bucket mapping (add them to bucket_for_caller):")
        for c, n in unknown_callers.most_common(12):
            print(f"  {n:5d}  {c}")
        print()
    if len(corpus) > 1:
        print("=== totals ===")
        for k, v in grand.most_common():
            print(f"  {v:5d}  {k}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
