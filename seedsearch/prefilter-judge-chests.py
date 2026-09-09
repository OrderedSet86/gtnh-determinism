#!/usr/bin/env python3
"""Golden-test the prefilter's chest modules against full-generation probe reports.

Follows prefilter-judge.py's shape: absolute counters, no percentages presented as the headline, and
each failure mode counted separately so a good number cannot hide a bad one.

Two directions, measured separately, because they fail for different reasons:

  PRECISION  predicted -> corpus. Did the thing we predicted actually generate, with the contents and
             NBT we said it would?

    existence    a predicted chest position the corpus also has
    contents     matched chests whose (slot, id, damage, count) lists agree
    NBT          matched chests whose item tags agree
    miscategorised   corpus chest at a predicted XZ where no predicted category reproduces its
                     contents. That means the chest-site table is wrong, which is the one hand-built
                     artifact here and therefore the one to distrust first.

  RECALL     corpus -> predicted. Of the chests that actually generated inside the window, how many
             did the prefilter account for at all? Everything it did not is listed, not just counted:
             a bare count of unexplained chests cannot be acted on.

Both directions are needed. Precision alone is the trap this script fell into for its first life: it
iterated predictions only, so a corpus chest with no prediction was never visited and the gap was
invisible. Measured on beta-3 seed -1636594104014467454 at radius 24, precision read clean while 127
of 229 corpus chests were unaccounted for.

The corpus carries chests from EVERY source (mineshaft, vanilla dungeon, Thaumcraft hilltop circles,
...), while the prefilter only ever claims villages, roguelike dungeons, strongholds and Witchery
cells. So a low recall number is expected and is not by itself a defect — see SOURCE_GROUPS in
loot-score.py for what stage 0 declares it cannot see. What the number is for is spotting the chests
that fall outside BOTH the predictions and the declared blind spots. Use chest-attribution.py to
split the unexplained list; this script's job is to produce it honestly.

Y is deliberately NOT part of the match key: the module does not predict chest Y, because F10's fork
does not use it. Matching on (x, y, z) would fail for the wrong reason. The corpus side is still
INDEXED by (x, y, z) and only grouped by (x, z), because two chests can share an XZ at different Y
(a mineshaft under a village), and a flat dict silently dropped one of them.

RADIUS matters, and leaving it off inflates the absent count. The prefilter scans village cells around
the ORIGIN out to its own radius, while a corpus covers a window around its walk CENTRE. A chest the
prefilter found beyond that window is scored "predicted but ABSENT" even though the corpus never
looked there. Measured on the 2026-08-30 scoring run: unfiltered reads 39 predicted / 17 absent, and
all 17 sit at chunk distance 16 to 26. The same data at `--radius 15` reads 19 predicted / 0 absent.
Pass the radius the corpus was generated with.

usage: prefilter-judge-chests.py <prefilter.jsonl> <corpus-dir-or-file> [--radius N] [--show N]

The corpus argument accepts either a directory of full probe reports (`seed-*.json`, chests read from
search.chunks[].chests) or a single `*.chests.json` extract as committed under results/. The extract
carries its own `center` and `radius`, which are used in preference to --radius and to the spawn
point, because the window the corpus actually looked at is what an absence has to be judged against.
"""
import json
import os
import sys
from collections import Counter, defaultdict


def items_of(chest):
    return [(i.get("s"), i.get("id"), i.get("d"), i.get("n")) for i in chest.get("items", [])]


def nbt_of(chest):
    return [i.get("tag") for i in chest.get("items", [])]


def sig_of(chest):
    """Short human-readable item signature for the unexplained listing."""
    it = chest.get("items") or []
    if not it:
        return "empty"
    head = ", ".join(f"{i.get('n')}x{i.get('id')}" for i in it[:3])
    return head + (f", +{len(it) - 3} more" if len(it) > 3 else "")


# Every section of a prefilter record that carries chests. The judge previously read only the first
# of these while scoring against a corpus containing all of them, which made the two sides
# incomparable populations. Mirrors the section list in loot-csv.py.
def load_prefilter(path):
    """seed -> {(x, z): [(source, category, chest_or_None, reason)]} for every predicted chest."""
    out = {}
    for line in open(path):
        d = json.loads(line)
        if "kill" in d:
            continue
        byxz = defaultdict(list)
        for start in d.get("village_starts", []):
            for c in start.get("chests", []):
                pos = c["chest"]["pos"]
                byxz[(pos[0], pos[2])].append(("village", c.get("category"), c["chest"], None))
            # Position known, contents refused. Counts for recall (the chest is accounted for) but
            # can never satisfy a contents check, so it is kept distinguishable.
            for u in start.get("chests_unpredicted", []):
                pos = u["pos"]
                byxz[(pos[0], pos[2])].append(
                    ("village-unpredicted", u.get("category"), None, u.get("reason")))
        for key, source in (("dungeons", "roguelike"), ("strongholds", "stronghold"),
                            ("witchery_cells", "witchery")):
            for st in d.get(key) or []:
                for c in st.get("chests") or []:
                    chest = c.get("chest", c)
                    pos = chest.get("pos") or c.get("pos")
                    if pos is None:
                        continue
                    byxz[(pos[0], pos[2])].append(
                        (source, c.get("category"), chest if chest.get("items") is not None else None, None))
        out[d["seed"]] = byxz
    return out


def _corpus_from_report(d):
    """Full probe report -> (chests, centre_xz_chunks, radius or None)."""
    search = d.get("search", {})
    chests = [c for chunk in search.get("chunks", {}).values() for c in chunk.get("chests", [])]
    spawn = search.get("spawn", [0, 0, 0])
    centre = d.get("center") or [spawn[0] >> 4, spawn[2] >> 4]
    return chests, (centre[0], centre[1]), d.get("radius")


def _corpus_from_extract(d):
    """`*.chests.json` extract -> (chests, centre_xz_chunks, radius or None)."""
    centre = d.get("center") or [0, 0]
    return d.get("chests") or [], (centre[0], centre[1]), d.get("radius")


def load_corpus(path):
    """seed -> (byxz, centre_chunk, radius). byxz maps (x, z) -> [chest], never overwriting."""
    if os.path.isdir(path):
        files = [os.path.join(path, fn) for fn in sorted(os.listdir(path))
                 if fn.startswith("seed-") and fn.endswith(".json") and not fn.endswith(".veincache.json")]
    else:
        files = [path]
    out = {}
    for fn in files:
        d = json.load(open(fn))
        seed = d.get("seed")
        if seed is None:
            base = os.path.basename(fn)
            seed = int(base[5:-5])
        chests, centre, radius = (_corpus_from_extract(d) if "chests" in d else _corpus_from_report(d))
        byxz = defaultdict(list)
        for c in chests:
            p = c["pos"]
            byxz[(p[0], p[2])].append(c)
        out[seed] = (byxz, centre, radius)
    return out


def within(xz, centre_chunk, radius):
    """Chebyshev chunk distance from the corpus walk centre."""
    if radius is None:
        return True
    return max(abs((xz[0] >> 4) - centre_chunk[0]), abs((xz[1] >> 4) - centre_chunk[1])) <= radius


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    radius = None
    show = 40
    argv = sys.argv
    for i, a in enumerate(argv):
        if a == "--radius":
            radius = int(argv[i + 1])
            args = [x for x in args if x != argv[i + 1]]
        elif a.startswith("--radius="):
            radius = int(a.split("=", 1)[1])
        elif a == "--show":
            show = int(argv[i + 1])
            args = [x for x in args if x != argv[i + 1]]
        elif a.startswith("--show="):
            show = int(a.split("=", 1)[1])
    if len(args) != 2:
        print(__doc__)
        return 2
    pf = load_prefilter(args[0])
    corpus = load_corpus(args[1])
    seeds = sorted(set(pf) & set(corpus))
    if not seeds:
        print("no seeds in common between the prefilter output and the corpus")
        return 1

    tot = Counter()
    per_seed = []
    unmatched_examples = []
    unexplained = []
    for seed in seeds:
        p, (c, centre, corpus_radius) = pf[seed], corpus[seed]
        # The corpus's own radius wins: it is the window that was actually looked at, and an absence
        # outside it proves nothing about the module.
        r = corpus_radius if corpus_radius is not None else radius
        # Out-of-window predictions are dropped rather than counted absent.
        p_in = {xz: e for xz, e in p.items() if within(xz, centre, r)}
        tot["out_of_window"] += len(p) - len(p_in)
        c_in = {xz: v for xz, v in c.items() if within(xz, centre, r)}

        matched = same = nbt_ok = miscat = 0
        predicted_absent = []
        for xz, entries in p_in.items():
            if xz not in c_in:
                predicted_absent.append(xz)
                continue
            # A prediction is checked against whichever corpus chest at this XZ it best explains;
            # the rest of the stack is left for the recall pass.
            got = None
            best = None
            for cand in c_in[xz]:
                for source, cat, chest, _reason in entries:
                    if chest is not None and items_of(chest) == items_of(cand):
                        got, best = cand, (source, cat, chest)
                        break
                if got is not None:
                    break
            if got is None:
                got = c_in[xz][0]
                best = next(((s, cat, ch) for s, cat, ch, _ in entries if ch is not None),
                            (entries[0][0], entries[0][1], None))
            matched += 1
            if best[2] is None:
                # Position predicted, contents refused on purpose. Not a contents pass and not a
                # miscategorisation — measured-and-refused is a real answer.
                tot["contents_refused"] += 1
                continue
            if items_of(best[2]) == items_of(got):
                same += 1
                if nbt_of(best[2]) == nbt_of(got):
                    nbt_ok += 1
            else:
                miscat += 1
                if len(unmatched_examples) < 3:
                    unmatched_examples.append((seed, xz, best[1], items_of(best[2])[:3], items_of(got)[:3]))

        # --- recall: every corpus chest in the window, matched against any prediction at its XZ
        corpus_in = sum(len(v) for v in c_in.values())
        stacked = sum(1 for v in c_in.values() if len(v) > 1)
        explained = 0
        for xz, stack in c_in.items():
            hit = xz in p_in
            for cand in stack:
                if hit:
                    explained += 1
                else:
                    unexplained.append((seed, cand))

        tot["predicted"] += sum(len(v) for v in p_in.values())
        tot["predicted_positions"] += len(p_in)
        tot["matched"] += matched
        tot["predicted_absent"] += len(predicted_absent)
        tot["contents_ok"] += same
        tot["nbt_ok"] += nbt_ok
        tot["miscategorised"] += miscat
        tot["corpus_chests"] += corpus_in
        tot["corpus_explained"] += explained
        tot["stacked_xz"] += stacked
        per_seed.append((seed, len(p_in), matched, same, nbt_ok, len(predicted_absent),
                         corpus_in, explained, corpus_in - explained))

    eff_radius = {corpus[s][2] for s in seeds}
    scope = (f"radius {sorted(x for x in eff_radius if x is not None)} chunks around the walk centre"
             if any(x is not None for x in eff_radius) else "NO radius filter")
    print(f"=== prefilter-judge-chests: {len(seeds)} seeds, {scope} ===")
    if all(x is None for x in eff_radius) and radius is None and tot["predicted_absent"]:
        print("  WARNING: without --radius, predictions outside the corpus window count as ABSENT")
    print("--- precision: did what we predicted generate? ---")
    print(f"predicted chest positions      : {tot['predicted_positions']}")
    print(f"  present in the corpus        : {tot['matched']}")
    print(f"  predicted but ABSENT         : {tot['predicted_absent']}")
    print(f"  dropped, outside the window  : {tot['out_of_window']}")
    print(f"contents identical at matched  : {tot['contents_ok']} of {tot['matched']}")
    print(f"  contents refused on purpose  : {tot['contents_refused']}")
    print(f"  MISCATEGORISED               : {tot['miscategorised']}")
    print(f"NBT identical at those         : {tot['nbt_ok']} of {tot['contents_ok']}")
    print("--- recall: did we account for what generated? ---")
    print(f"corpus chests in window        : {tot['corpus_chests']}")
    print(f"  accounted for by a prediction: {tot['corpus_explained']}")
    print(f"  UNEXPLAINED                  : {tot['corpus_chests'] - tot['corpus_explained']}")
    print(f"  XZ hosting >1 chest          : {tot['stacked_xz']}  (a flat dict would have dropped these)")
    print("  NOTE: the corpus carries every loot source; the prefilter claims only villages,")
    print("        roguelike, strongholds and Witchery. See SOURCE_GROUPS in loot-score.py, and")
    print("        run chest-attribution.py to split the list below.")
    if unmatched_examples:
        print("\nfirst content mismatches:")
        for seed, xz, cat, a, b in unmatched_examples:
            print(f"  seed {seed} at {xz} category {cat}")
            print(f"     prefilter: {a}")
            print(f"     full-gen : {b}")
    if unexplained:
        print(f"\nunexplained corpus chests (showing {min(show, len(unexplained))} of {len(unexplained)}):")
        for seed, c in unexplained[:show]:
            print(f"  {c['pos']!s:22s} {c.get('type', '?'):22s} {sig_of(c)}")
        if len(unexplained) > show:
            print(f"  ... {len(unexplained) - show} more (raise --show to see them)")
    print("\nper seed: seed, predicted, matched, contents-ok, nbt-ok, predicted-absent,"
          " corpus, explained, unexplained")
    for row in per_seed:
        print("  " + ", ".join(str(x) for x in row))
    return 0


if __name__ == "__main__":
    sys.exit(main())
