#!/usr/bin/env python3
"""Golden-test the stage-0 prefilter against full-generation search reports.

Compares a prefilter JSONL (scripts/prefilter.sh output) with a corpus directory of
probe.search=true JSONs for the same seeds, per module:

  villages  corpus village -> matching prefilter start (shared piece name+XZ or centroid
            within --match-dist blocks); reports recall.
  pieces    within matched villages: piece multiset match on (name, x1,z1,x2,z2) - Y is
            EXPECTED to differ (prefilter boxes carry pre-terrain placeholder Y; real gen
            only offsets Y). Corpus may be missing pieces the window never built or that
            failed the ground check; those count as corpus-only, listed separately.
  spawn     exact XZ match expected (worldless walk vs real createSpawnPosition); any
            mismatch prints the delta - a nonzero rate means live-population flipped a
            grass check (or a mod hooks CreateSpawnPosition) and the caveat is real.
  biomes    -Dprobe.prefilter.biomeregion output vs the corpus per-chunk biomeCounts
            census: largest no-rain square, largest humid square, and the gap between
            them. Exact match expected in the default confirm=-1 mode, which reads the
            same generated biome data the corpus does. Rows marked tier "A" or "Ab" were
            answered wholly or partly from the lattice screen and are reported separately,
            because that screen is NOT an upper bound and drops qualifying seeds.
            Needs a corpus at report format 7 or later and a biomes.json sidecar.

Usage:
  prefilter-judge.py <prefilter.jsonl> <corpus-dir> [--match-dist 100]
                     [--min-side 5] [--biome-radius 15] [--humidity 14]
"""
import argparse
import json
import math
import re
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
from searchlib import load_dir  # noqa: E402

PIECE_RE = re.compile(r'([A-Za-z0-9_$]+)@(-?\d+),(-?\d+),(-?\d+)\.\.(-?\d+),(-?\d+),(-?\d+)')


def parse_pieces(s):
    """{(name, x1, z1, x2, z2): count} from an 'N pieces: ...' string (Y dropped)."""
    out = {}
    for m in PIECE_RE.finditer(str(s)):
        name = m.group(1)
        x1, _, z1, x2, _, z2 = (int(g) for g in m.groups()[1:])
        key = (name, x1, z1, x2, z2)
        out[key] = out.get(key, 0) + 1
    return out


def centroid(pieces):
    if not pieces:
        return None
    xs = [(k[1] + k[3]) / 2 for k in pieces]
    zs = [(k[2] + k[4]) / 2 for k in pieces]
    return (sum(xs) / len(xs), sum(zs) / len(zs))


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("jsonl")
    ap.add_argument("corpus_dir")
    ap.add_argument("--match-dist", type=float, default=100.0)
    ap.add_argument("--min-side", type=int, default=5)
    ap.add_argument("--biome-radius", type=int, default=15)
    ap.add_argument("--humidity", type=int, default=14)
    args = ap.parse_args()

    pre = {}
    with open(args.jsonl) as f:
        for line in f:
            line = line.strip()
            if line:
                d = json.loads(line)
                pre[int(d["seed"])] = d

    corpus = {int(r.seed): r for r in load_dir(args.corpus_dir)}
    common = sorted(set(pre) & set(corpus))
    if not common:
        sys.exit("no common seeds between prefilter output and corpus")
    skipped = len(set(corpus) - set(pre))
    if skipped:
        print(f"note: {skipped} corpus seeds absent from prefilter output (not judged)")

    v_total = v_matched = 0
    piece_exact_v = piece_partial_v = 0
    spawn_exact = 0
    spawn_deltas = []
    spawn_missing = 0
    b_total = b_side = b_hside = b_gap = b_verdict = 0
    b_missing = b_degraded = 0

    for seed in common:
        p, c = pre[seed], corpus[seed]
        pre_villages = []
        for st in p.get("village_starts", []):
            if "pieces" in st:
                pieces = parse_pieces(st["pieces"])
                pre_villages.append((st, pieces, centroid(pieces)))

        lines = []
        for cv in c.villages:
            cpieces = parse_pieces(cv)
            if not cpieces:
                continue
            v_total += 1
            cc = centroid(cpieces)
            best = None
            for st, ppieces, pc in pre_villages:
                shared = sum(min(n, ppieces.get(k, 0)) for k, n in cpieces.items())
                dist = math.hypot(cc[0] - pc[0], cc[1] - pc[1]) if pc else 1e9
                if shared > 0 or dist <= args.match_dist:
                    score = (shared, -dist)
                    if best is None or score > best[0]:
                        best = (score, st, ppieces)
            if best is None:
                lines.append(f"  MISS corpus village at ~({cc[0]:.0f},{cc[1]:.0f}), "
                             f"{sum(cpieces.values())} pieces - no prefilter match")
                continue
            v_matched += 1
            _, st, ppieces = best
            corpus_only = {k: n - ppieces.get(k, 0) for k, n in cpieces.items()
                           if n > ppieces.get(k, 0)}
            pre_only = {k: n - cpieces.get(k, 0) for k, n in ppieces.items()
                        if n > cpieces.get(k, 0)}
            # corpus ⊆ prefilter is the expected relation (window clipping / ground-fail
            # prunes remove corpus pieces); corpus-only pieces are the real red flags
            if not corpus_only:
                piece_exact_v += 1
                if pre_only:
                    piece_partial_v += 1
            else:
                sample = list(corpus_only)[:3]
                lines.append(f"  PIECE-MISS village ~({cc[0]:.0f},{cc[1]:.0f}): "
                             f"{sum(corpus_only.values())} corpus pieces absent from "
                             f"prefilter, e.g. {sample}")

        cspawn = getattr(c, "spawn", None)
        pspawn = p.get("spawn")
        if cspawn and pspawn:
            dx, dz = pspawn[0] - cspawn[0], pspawn[2] - cspawn[2]
            if dx == 0 and dz == 0:
                spawn_exact += 1
            else:
                spawn_deltas.append(math.hypot(dx, dz))
                lines.append(f"  SPAWN corpus ({cspawn[0]},{cspawn[2]}) vs "
                             f"prefilter ({pspawn[0]},{pspawn[2]})  d={math.hypot(dx, dz):.0f}")
        else:
            spawn_missing += 1

        # --- biomes. Compared field by field rather than as a single verdict: the gate decision can
        # agree while the underlying squares disagree, and reporting only the verdict would hide that.
        pb = p.get("biomeregion")
        if pb is None:
            b_missing += 1
        else:
            cb = c.biome_regions(args.min_side, args.biome_radius, args.humidity)
            if cb is None:
                b_missing += 1
            else:
                b_total += 1
                if pb.get("t") != "B":
                    b_degraded += 1
                se = pb["n"] == cb["side"]
                he = pb["hn"] == cb["humidSide"]
                ge = pb["d"] == cb["gap"]
                b_side += se
                b_hside += he
                b_gap += ge
                b_verdict += (pb["n"] >= args.min_side) == (cb["side"] >= args.min_side)
                if not (se and he and ge):
                    lines.append(
                        f"  BIOME tier={pb.get('t')} cg={pb.get('cg')}  "
                        f"side {pb['n']}/{cb['side']}  humidSide {pb['hn']}/{cb['humidSide']}  "
                        f"gap {pb['d']}/{cb['gap']}   (prefilter/corpus)")
                if cb["columns"] != "all":
                    lines.append("  BIOME corpus lacks per-column biomeCounts; comparison is against "
                                 "the chunk-centre proxy, not ground truth")

        if lines:
            print(f"seed {seed}:")
            print("\n".join(lines))

    n = len(common)
    print(f"\n=== prefilter-judge: {n} seeds ===")
    if v_total:
        print(f"villages: {v_matched}/{v_total} corpus villages matched "
              f"({100 * v_matched / max(v_total, 1):.1f}% recall)")
        print(f"pieces:   {piece_exact_v}/{v_matched} matched villages with corpus subset of "
              f"prefilter pieces ({piece_partial_v} of those had extra prefilter-only pieces "
              f"= window clipping / ground prunes, expected)")
    ns = n - spawn_missing
    if ns:
        med = statistics.median(spawn_deltas) if spawn_deltas else 0.0
        print(f"spawn:    {spawn_exact}/{ns} exact ({100 * spawn_exact / ns:.1f}%); "
              f"{len(spawn_deltas)} mismatches, median delta {med:.0f} blocks")
    if spawn_missing:
        print(f"spawn:    {spawn_missing} seeds lacked spawn on one side")
    if b_total:
        # Absolute mismatch counts, not rates alone: the target is zero, and a percentage
        # rounds a surviving residual out of sight.
        print(f"biomes:   {b_total} seeds compared (min-side {args.min_side}, radius "
              f"{args.biome_radius}, humidity {args.humidity})")
        print(f"          no-rain square side {b_side}/{b_total} exact, "
              f"{b_total - b_side} mismatched")
        print(f"          humid square side   {b_hside}/{b_total} exact, "
              f"{b_total - b_hside} mismatched")
        print(f"          gap                 {b_gap}/{b_total} exact, "
              f"{b_total - b_gap} mismatched")
        print(f"          >={args.min_side}x{args.min_side} verdict     {b_verdict}/{b_total} agree, "
              f"{b_total - b_verdict} disagreed")
        if b_degraded:
            print(f"          WARNING: {b_degraded} rows were tier A/Ab (lattice screen, not "
                  f"confirmed) - those are not measurements of the generator")
    if b_missing:
        print(f"biomes:   {b_missing} seeds had no biomeregion output or no biomes.json sidecar")


if __name__ == "__main__":
    main()
