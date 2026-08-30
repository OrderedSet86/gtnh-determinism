#!/usr/bin/env python3
"""Golden-test the gate-less vein predictor against real Twilight Forest probe reports.

Every prior accuracy number in this repo is for the overworld. This is the same measurement for a
dimension where the terrain reroll gate behaves differently, and it exists to answer one question
before anyone sizes a seed search on predicted shard veins: how often is the layer-1 prediction the
mix that actually generated.

WHY THE GATE MATTERS MORE HERE. The Twilight Forest ground level is 30
(WorldProviderTwilightForest.getAverageGroundLevel). WorldgenGTOreLayer probes
world.getBlock(chunkX+7, tMinY, chunkZ+9) and bails with NO_OVERLAP_AIR_BLOCK when that is not stone,
so a mix whose tMinY rolls above 30 usually fails. Those rerolls advance `i` on the same oreveinRNG,
so the cell falls through to a later dimension-eligible draw. 53% of layer-1 TF draws are mixes with
minY >= 40, which is why the accuracy split by Y band rather than averaging into one number.

READING THE OUTPUT. Absolute counters, no headline percentage, and every failure mode counted
separately so a good number cannot hide a bad one. In particular identity_flip is reported as TWO
numbers that must never be summed:

  lost_to_reroll        predicted a shard mix, corpus holds something else. A real miss.
  reroll_beneficiary    predicted a non-shard mix, corpus holds a shard mix. The cell GAINED a shard
                        vein because an earlier high-band pick was rejected. Counting this as an error
                        would understate the predictor; counting it as a success would overstate it.

Usage:
  tf-vein-judge.py <corpus-dir> [--min-count 30] [--material-floor 20] [--dim 7]
"""
import argparse
import collections
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import vein_predict as vp  # noqa: E402
from searchlib import load_dir, identify_mix  # noqa: E402

SHARD_MIXES = ("ore.mix.aquaignis", "ore.mix.terraaer", "ore.mix.perditioordo")

# Mix identity from the material set. A vein places at most its four materials, and a window-clipped
# vein places a subset, so a cell matches a mix when the materials present are a SUBSET of that mix's
# four. Ambiguity is real and must not be resolved by guessing: coal and lignite have identical
# material sets, and any single-material cell matches several mixes. Those land in `unidentified`.
BY_MATS = collections.defaultdict(list)
for _m in vp.MIXES:
    BY_MATS[frozenset(vp.materials_of(_m))].append(_m["name"])


def actual_mix(counter, floor):
    """-> (mix name, reason). None with a reason when the census cannot name exactly one mix."""
    present = {k for k, v in counter.items() if v >= floor}
    if not present:
        return None, "below-floor"
    candidates = {names[0] for mats, names in BY_MATS.items()
                  if present <= mats and len(names) == 1}
    ambiguous = [names for mats, names in BY_MATS.items() if present <= mats and len(names) > 1]
    if len(candidates) == 1 and not ambiguous:
        return candidates.pop(), "exact"
    if not candidates and not ambiguous:
        return None, "no-matching-mix"
    return None, "ambiguous"


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("corpus_dir")
    ap.add_argument("--min-count", type=int, default=30,
                    help="minimum big-ore blocks in a cell before it counts as a vein census")
    ap.add_argument("--material-floor", type=int, default=20,
                    help="minimum blocks of a material before it counts as present in the cell")
    ap.add_argument("--dim", type=int, default=7)
    ap.add_argument("--ground", type=int, default=30,
                    help="ground level of the probed dimension; the gate tests one block at tMinY, "
                         "so a vein rolling above this probes air and rerolls (TF is 30)")
    args = ap.parse_args()

    dims = vp.load_dims(args.corpus_dir, quiet=True)
    token = vp.dim_token(args.dim, dims)
    minY = {m["name"]: m["minY"] for m in vp.MIXES}

    reports = [r for r in load_dir(args.corpus_dir)]
    if not reports:
        print(f"no seed-*.json under {args.corpus_dir}", file=sys.stderr)
        return 1
    if not any(r.has_ore_census() for r in reports):
        print("FATAL: not one report in this corpus contains an ore tile entity.\n"
              "On GT 5.09.54.x worldgen ores are plain blocks with no TEs, so the census comes back\n"
              "EMPTY rather than zero and every number below would be wrong rather than negative.\n"
              "Use scripts/ore-route-report.py on region files for that GT line.", file=sys.stderr)
        return 2

    c = collections.Counter()
    band = {"low": collections.Counter(), "high": collections.Counter()}
    per_mix = collections.defaultdict(collections.Counter)
    shard_unknown = 0
    wrong_dim = 0

    for r in reports:
        if r.dim != args.dim:
            wrong_dim += 1
            continue
        c["seeds"] += 1
        cells = r.vein_cells(min_count=args.min_count)
        for cell, counter in cells.items():
            if cell is None:
                c["ambiguous_boundary_blocks"] += sum(counter.values())
                continue
            c["corpus_cells"] += 1
            actual, reason = actual_mix(counter, args.material_floor)
            pred, _ = vp.predict(r.seed, cell[0], cell[1], dim=args.dim, token=token)
            predname = pred["name"] if pred else None
            if predname is None:
                c["no_eligible_draw"] += 1
                continue
            c["predicted_cells"] += 1

            if actual is None:
                c[f"unidentified_{reason}"] += 1
                _, shard_conf = identify_mix(counter)
                if shard_conf == "shard-unknown":
                    shard_unknown += 1
                continue

            # Band on the ROLLED tMinY, not on the mix's minY. The gate probes a single block at
            # tMinY, so what decides it is where this vein actually landed: ore.mix.gold has minY 30
            # but rolls tMinY up to 54, and lumping it with minY-10 mixes blurs the split that is the
            # whole finding. TF_GROUND is WorldProviderTwilightForest.getAverageGroundLevel().
            t_min_y = vp.vein_geometry(pred, cell[0], cell[1], dim=args.dim,
                                       world_seed=r.seed)["tMinY"]
            b = "low" if t_min_y <= args.ground else "high"
            hit = predname == actual
            c["matched" if hit else "identity_flip"] += 1
            band[b]["n"] += 1
            band[b]["hit"] += hit
            per_mix[predname]["predicted"] += 1
            per_mix[predname]["hit"] += hit
            per_mix[actual]["actual"] += 1
            if not hit:
                if predname in SHARD_MIXES:
                    c["lost_to_reroll"] += 1
                if actual in SHARD_MIXES:
                    c["reroll_beneficiary"] += 1

    print(f"corpus: {args.corpus_dir}   dim {args.dim} ({token})")
    if wrong_dim:
        print(f"  ! {wrong_dim} report(s) skipped: wrong dimension for this judge")
    print(f"  seeds {c['seeds']}   corpus cells {c['corpus_cells']}   with a prediction {c['predicted_cells']}")
    print()
    print("identity")
    print(f"  matched                 {c['matched']}")
    print(f"  identity_flip           {c['identity_flip']}")
    print(f"    lost_to_reroll        {c['lost_to_reroll']}   (predicted a shard mix, corpus has another)")
    print(f"    reroll_beneficiary    {c['reroll_beneficiary']}   (corpus GAINED a shard mix)")
    print("    ^ these two count different things and must never be summed")
    for k in sorted(k for k in c if k.startswith("unidentified_")):
        print(f"  {k:<23} {c[k]}")
    print(f"  shard_unknown           {shard_unknown}   (only the shared Amber/Cinnabar materials)")
    print(f"  ambiguous_boundary      {c['ambiguous_boundary_blocks']} blocks in chunks spanning two cells")
    print(f"  no_eligible_draw        {c['no_eligible_draw']}")
    print()
    print("by predicted Y band  (TF ground level is 30, so high-band veins probe air and reroll)")
    for name, label in (("low", "tMinY <= ground"), ("high", "tMinY >  ground")):
        b = band[name]
        pct = f"{100.0 * b['hit'] / b['n']:.1f}%" if b["n"] else "n/a"
        print(f"  {label}   {b['hit']}/{b['n']}   {pct}")
    print()
    print("the three shard mixes")
    tot_p = tot_h = tot_a = 0
    for name in SHARD_MIXES:
        m = per_mix[name]
        prec = f"{100.0 * m['hit'] / m['predicted']:.1f}%" if m["predicted"] else "n/a"
        rec = f"{100.0 * m['hit'] / m['actual']:.1f}%" if m["actual"] else "n/a"
        print(f"  {name:<24} predicted {m['predicted']:>4}  correct {m['hit']:>4}  "
              f"in corpus {m['actual']:>4}   precision {prec:>6}  recall {rec:>6}")
        tot_p += m["predicted"]; tot_h += m["hit"]; tot_a += m["actual"]
    prec = f"{100.0 * tot_h / tot_p:.1f}%" if tot_p else "n/a"
    rec = f"{100.0 * tot_h / tot_a:.1f}%" if tot_a else "n/a"
    print(f"  {'ALL THREE':<24} predicted {tot_p:>4}  correct {tot_h:>4}  "
          f"in corpus {tot_a:>4}   precision {prec:>6}  recall {rec:>6}")
    print()
    print("full Twilight Forest pool, by predicted mix (first measurement outside the overworld)")
    for name in sorted(per_mix, key=lambda n: -per_mix[n]["predicted"]):
        m = per_mix[name]
        if not m["predicted"]:
            continue
        prec = f"{100.0 * m['hit'] / m['predicted']:.1f}%"
        print(f"  {name:<24} minY {minY[name]:>3}  predicted {m['predicted']:>4}  "
              f"correct {m['hit']:>4}  precision {prec:>6}")
    print()
    if tot_p:
        verdict = "PASS" if 100.0 * tot_h / tot_p >= 85.0 else "FAIL"
        print(f"shard-mix precision criterion (>= 85%): {verdict}")
        if verdict == "FAIL":
            print("  the gate-less layer is wrong about the Twilight Forest. The answer is a gated")
            print("  predictor, not a correction factor applied to this one.")
    else:
        print("no shard mix was predicted anywhere in this corpus — widen it before drawing conclusions")
    return 0


if __name__ == "__main__":
    sys.exit(main())
