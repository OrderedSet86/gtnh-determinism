#!/usr/bin/env python3
"""Rank stage-0 prefilter seeds against several independent criteria at once.

usage: multi-criteria.py <value_table.csv> <prefilter.jsonl|summary.json> [--summary PATH] [bars...]

TWO PASSES, because they cost three orders of magnitude apart. Extraction from a 2 GB sweep is ~110 s
and is threshold-independent; applying the bars is ~0.0 s and is the part that actually gets changed.
So extraction runs once into a small summary and every later re-rank reads that:

    multi-criteria.py values.csv sweep.jsonl --summary sweep-summary.json     # once, ~70 s
    multi-criteria.py values.csv sweep-summary.json --circle-within 500       # instant

The summary stores RAW distances and sizes, never pass/fail, so a bar can be moved afterwards without
re-extracting. It is tied to the value table it was built with, since the loot score and the `Min`
gate are baked in, so a different CSV needs a fresh summary.

Every criterion is reported as a DISTANCE. "No village within 200 blocks" and "nearest village is 210
blocks away" are different answers, and the second is the useful one when nothing clears every bar.

SPAWN VALIDITY. A row with `spawn_iters == 0` never completed its spawn walk and carries the default
`[0, 64, 0]`, so every distance for it would be measured from the origin rather than from spawn.
Measured on a 2 000-seed sweep: 141 rows, 7%, and the correspondence with `spawn == [0,64,0]` was
exact in both directions. Those rows are dropped and the count is reported, not absorbed.

NOT SCORED:

  oil spouts   `com.dreammaster.modfixes.oilgen.OilGeneratorFix`. Measured NOT route-stable — a whole
               deposit exists under a spiral walk and not under rows — so a predicted site would not
               be reproducible. See results/2026-09-01-gtnh-oil-route-stability.
"""
import argparse
import importlib.util
import json
import math
import pathlib
import sys
from collections import Counter

HERE = pathlib.Path(__file__).resolve().parent

# Above any terrain in the pack, below the build limit. Printed wherever stage 0 has no real height, so
# the coordinate reads as "teleport here and descend" instead of as a predicted Y. Matches verify-sheet.py.
SKY_Y = 200


def load(name, filename):
    """These modules carry hyphens, so they cannot be imported by name."""
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def dist(ax, az, bx, bz):
    return math.hypot(ax - bx, az - bz)


def nearest_village(row, piece=None):
    """Closest village start, optionally restricted to villages whose layout contains `piece`.

    The piece filter reads the layout string the prefilter already emits, so "a village with a TC
    smeltery" costs nothing extra at sweep time — but it can only be computed from RAW rows, which is
    why extract() precomputes the smeltery variant into the summary.
    """
    sx, _, sz = row["spawn"]
    best = None
    for v in row.get("village_starts") or []:
        if piece and piece not in (v.get("pieces") or ""):
            continue
        cx, cz = v["c"]
        bx, bz = cx * 16 + 8, cz * 16 + 8
        d = dist(sx, sz, bx, bz)
        if best is None or d < best[0]:
            best = (d, bx, bz)
    return best


def nearest_circle(row):
    """Closest Witchery coven — the circle — and how many containers it holds."""
    sx, _, sz = row["spawn"]
    best = None
    for c in row.get("witchery_cells") or []:
        if c.get("winner") != "WorldHandlerCoven":
            continue
        chests = c.get("chests") or []
        # The container's own position beats the cell origin: cells are 16-block aligned and the
        # structure lands somewhere inside, so the origin can be tens of blocks out.
        # Carry the container's Y too. It is a real predicted height (the replay produced the structure),
        # and dropping it forced the report to invent one — it printed a hardcoded 64, which is
        # underground at most of these coordinates and reads as a prediction rather than a placeholder.
        if chests and chests[0].get("pos"):
            bx, by, bz = chests[0]["pos"]
        else:
            bx, bz = c["cell"]
            by = None
        d = dist(sx, sz, bx, bz)
        if best is None or d < best[0]:
            best = (d, bx, bz, len(chests), by)
    return best


def nearest_enchant_dungeon(row):
    """Closest Roguelike dungeon whose generated layout contains an enchantment table, with the table's
    exact position. Requires rows from a sweep new enough to emit enchant_tables (probe >= 555df65b);
    older rows yield None everywhere, which reads as "no data", not "no dungeon"."""
    sx, _, sz = row["spawn"]
    best = None
    for d in row.get("dungeons") or []:
        for p in d.get("enchant_tables") or []:
            # Distance to the TABLE BLOCK, not to its dungeon's trigger chunk. A dungeon sprawls, so the
            # two differ by up to ~70 blocks: one seed reported "350 blocks" beside a tp for a table that
            # is actually 279 away. The prefilter's own enchant gate measures table position, so ranking
            # on the trigger also disagreed with which seeds the sweep had kept.
            dd = dist(sx, sz, p[0], p[2])
            if best is None or dd < best[0]:
                best = (dd, p[0], p[1], p[2], d.get("tower"))
    return best


def nearest_dungeon(row):
    """Closest Roguelike dungeon TRIGGER chunk to spawn, as (dist, x, z).

    The row records the trigger chunk, not the entrance block, so the position is chunk-resolution:
    the tower stands somewhere inside those 16 blocks. Good enough against a 200-block bar; not good
    enough to quote as an exact entrance coordinate.
    """
    sx, _, sz = row["spawn"]
    best = None
    for d in row.get("dungeons") or []:
        t = d.get("trigger")
        if not t:
            continue
        bx, bz = t[0] * 16 + 8, t[1] * 16 + 8
        dd = dist(sx, sz, bx, bz)
        if best is None or dd < best[0]:
            best = (dd, bx, bz)
    return best


def biome_squares(row):
    """((side, dist, centreX, centreZ, cornerCX, cornerCZ) | None) for the no-rain and humid squares.

    No size bar is applied here. The CHUNK corner is carried alongside the block centre because the
    touching test needs the extent, not just a point.
    """
    br = row.get("biomeregion") or {}
    sx, _, sz = row["spawn"]
    out = []
    for side_key, corner_key in (("n", "sq"), ("hn", "hsq")):
        side = br.get(side_key) or 0
        corner = br.get(corner_key)
        if not side or not corner:
            out.append(None)
            continue
        # Measure to the square's CENTRE: it is what a player walks to, and the only point guaranteed
        # to be inside the square whatever its side.
        cx, cz = corner[0] + side / 2.0, corner[1] + side / 2.0
        out.append((side, dist(sx, sz, cx * 16, cz * 16), int(cx * 16), int(cz * 16),
                    corner[0], corner[1]))
    return out[0], out[1]


def edge_dist(spawn, sq):
    """Distance from spawn to the NEAREST EDGE of a biome square, in blocks. 0 when spawn is inside.

    The stored per-square distance is to the CENTRE, which penalises large squares: a 19x19 whose edge
    you can see from spawn has its centre 150+ blocks in. The bar is about reaching the region, so the
    edge is the honest measure; the centre stays in the output as the /tp target because it is the only
    point guaranteed inside the square.
    """
    if not sq:
        return None
    sx, _, sz = spawn
    side, _cd, _cx, _cz, ccx, ccz = sq
    x0, z0 = ccx * 16, ccz * 16
    x1, z1 = x0 + side * 16 - 1, z0 + side * 16 - 1
    dx = max(x0 - sx, 0, sx - x1)
    dz = max(z0 - sz, 0, sz - z1)
    return math.hypot(dx, dz)


def square_gap(a, b):
    """Chunks strictly separating two squares. 0 means touching or overlapping; None if either is absent.

    CONSERVATIVE BY CONSTRUCTION. The prefilter reports the LARGEST no-rain square and the LARGEST
    humid square, found independently, so this can only answer whether THOSE two touch. A seed holding
    a smaller but still qualifying pair that touches will be rejected. False negatives are therefore
    possible; false positives are not, which is the right direction for a search filter.
    """
    if not a or not b:
        return None
    ax0, az0, an = a[4], a[5], a[0]
    bx0, bz0, bn = b[4], b[5], b[0]
    ax1, az1 = ax0 + an - 1, az0 + an - 1
    bx1, bz1 = bx0 + bn - 1, bz0 + bn - 1
    gx = max(0, bx0 - ax1 - 1, ax0 - bx1 - 1)
    gz = max(0, bz0 - az1 - 1, az0 - bz1 - 1)
    return max(gx, gz)


def shard_reach(tf, seed, spawn, window):
    """-> (farthest-of-three distance, x, z) covering all six shards, or None if a mix is missing.

    Twilight Forest is 1:1 with the overworld in X/Z, so the overworld spawn is the right anchor.

    Three GT mixes carry all six Thaumcraft shards between their primary and secondary slots. The
    distance returned is to the FARTHEST of the three nearest cells — the trip that has to be walked
    to collect every shard. No bar is applied here.
    """
    sx, _, sz = spawn
    found = tf.shard_cells(seed, 7, tf.vp.TWILIGHT_FOREST, window, sx >> 4, sz >> 4)
    if not all(found):
        return None
    picks = [tf.vp.cell_center_block(
        *min(cells, key=lambda c: math.dist((sx, sz), tf.vp.cell_center_block(*c)))) for cells in found]
    worst = max(math.dist((sx, sz), p) for p in picks)
    return (worst, int(sum(p[0] for p in picks) / 3), int(sum(p[1] for p in picks) / 3))


def extract(value_table, jsonl, radius, tf_window, want_tf):
    """One pass over the sweep. Everything computed here is threshold-independent."""
    ls = load("loot_score", "loot-score.py")
    tf = load("tf_shard_veins", "tf-shard-veins.py") if want_tf else None
    values, limits, mins, _display, _ = ls.load_values(value_table, "max")

    # ONE parse of the raw rows. Reading the file twice — once via load_stage0 and once for the raw
    # dicts — was 70 s of a 110 s extraction on a 2 GB sweep, and the second pass parsed every line
    # twice over on top of that.
    seeds = ls.load_stage0(pathlib.Path(jsonl))
    by_seed = {s.seed: s for s in seeds}
    out, dropped = [], 0
    for line in open(jsonl, encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        row = json.loads(line)
        s = by_seed.get(row["seed"])
        if s is None or s.kill:
            continue
        if not row.get("spawn_iters"):
            dropped += 1
            continue
        score, _unc, _marg, qty = ls.score_seed(s.in_scope(radius), values, limits)
        v, c = nearest_village(row), nearest_circle(row)
        vs = nearest_village(row, piece="ComponentSmeltery")
        dry, hum = biome_squares(row)
        t = shard_reach(tf, s.seed, s.spawn, tf_window) if want_tf else None
        # Best touching PAIR, when the sweep is new enough to emit it (pd/ph/pg/ps). Stored as the two
        # corner-chunk pairs plus side and gap; rank-side edge distances derive from those.
        br = row.get("biomeregion") or {}
        pair = None
        if br.get("pg") is not None:
            pair = dict(gap=br["pg"], side=br.get("ps", 5), dry=br["pd"], hum=br["ph"])
        out.append(dict(seed=s.seed, spawn=s.spawn, score=score,
                        minok=not ls.unmet_minimums(qty, mins),
                        village=v, circle=c, dry=dry, hum=hum, tf=t,
                        dungeon=nearest_dungeon(row), pair=pair, smeltery_village=vs,
                        ench=nearest_enchant_dungeon(row),
                        biome_gap=square_gap(dry, hum)))
    return {"value_table": str(value_table), "source": str(jsonl), "radius": radius,
            "dropped_no_spawn": dropped, "seeds": out}


def fmt(d, within):
    return f"{'OK' if d <= within else '  '} {d:6.0f} blocks"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("value_table")
    ap.add_argument("input", help="sweep .jsonl, or a summary .json built earlier")
    ap.add_argument("--summary", help="write the extraction here")
    ap.add_argument("--radius", type=int, default=60, help="chest scope, chunks around spawn")
    ap.add_argument("--top", type=int, default=10)
    ap.add_argument("--village-within", type=float, default=200.0)
    ap.add_argument("--village-needs-smeltery", action="store_true",
                    help="the village criterion counts only villages whose layout contains a "
                         "TinkerConstruct ComponentSmeltery")
    ap.add_argument("--circle-within", type=float, default=500.0)
    ap.add_argument("--biome-within", type=float, default=200.0)
    ap.add_argument("--biome-side", type=int, default=5)
    ap.add_argument("--tf-within", type=float, default=500.0)
    ap.add_argument("--tf-window", type=int, default=48)
    ap.add_argument("--tf", action="store_true",
                    help="include the Twilight Forest shard criterion. OFF by default on daily-707: "
                         "the GT 5.09.54 draw protocol is implemented and verified against the live "
                         "mod, but the WINNING vein depends on TF terrain (gate + in-attempt redraws) "
                         "which stage 0 cannot produce — measured 0-8%% shard precision. See "
                         "results/2026-09-03-tf-veins-gt50954. Valid on 2.8.4 with the legacy table.")
    ap.add_argument("--dungeon-within", type=float, default=200.0)
    ap.add_argument("--biome-gap", type=int, default=None,
                    help="if set, ALSO require a no-rain/humid pair at most this many chunks apart "
                         "(0 = touching). Unset = the pair criterion is not scored at all.")
    ap.add_argument("--require-min", action="store_true",
                    help="drop seeds failing the value table's Min column")
    args = ap.parse_args()

    if args.input.endswith(".json"):
        data = json.load(open(args.input))
    else:
        data = extract(args.value_table, args.input, args.radius, args.tf_window, args.tf)
        if args.summary:
            json.dump(data, open(args.summary, "w"))
            print(f"summary written to {args.summary}\n")

    rows = data["seeds"]
    if not rows:
        sys.exit("no seeds with a valid spawn")
    bars = dict(village=args.village_within, circle=args.circle_within,
                dry=args.biome_within, hum=args.biome_within, tf=args.tf_within)

    def flags(r):
        dry_ed = edge_dist(r["spawn"], r["dry"])
        hum_ed = edge_dist(r["spawn"], r["hum"])
        dry_ok = bool(r["dry"] and r["dry"][0] >= args.biome_side and dry_ed <= bars["dry"])
        hum_ok = bool(r["hum"] and r["hum"][0] >= args.biome_side and hum_ed <= bars["hum"])
        vv = r.get("smeltery_village") if args.village_needs_smeltery else r["village"]
        f = [bool(vv and vv[0] <= bars["village"]),
             bool(r["circle"] and r["circle"][0] <= bars["circle"]),
             dry_ok, hum_ok]
        if args.biome_gap is not None:
            # Optional: one base serving both regions. Prefer the sweep-computed best PAIR (exact);
            # fall back to the two largest squares for pre-pair summaries (conservative undercount).
            pair = r.get("pair")
            if pair:
                pd = (pair["side"], 0, 0, 0, pair["dry"][0], pair["dry"][1])
                ph = (pair["side"], 0, 0, 0, pair["hum"][0], pair["hum"][1])
                f.append(pair["gap"] <= args.biome_gap
                         and edge_dist(r["spawn"], pd) <= bars["dry"]
                         and edge_dist(r["spawn"], ph) <= bars["hum"])
            else:
                gap = r.get("biome_gap")
                f.append(bool(dry_ok and hum_ok and gap is not None and gap <= args.biome_gap))
        # The dungeon criterion is the ENCHANT-TABLE dungeon, not the nearest dungeon of any kind. The
        # nearest dungeon is almost always a closer one with no table, so scoring it marked seeds 5/5
        # whose actual enchant table was out of range.
        f.append(bool(r.get("ench") and r["ench"][0] <= args.dungeon_within))
        if args.tf:
            f.append(bool(r["tf"] and r["tf"][0] <= bars["tf"]))
        return f

    for r in rows:
        r["_f"] = flags(r)
        r["_met"] = sum(r["_f"])
    n = len(rows[0]["_f"])

    print(f"=== multi-criteria: {len(rows)} seeds with a valid spawn "
          f"({data['dropped_no_spawn']} dropped for spawn_iters==0) ===\n")
    print(f"bars: village {bars['village']:.0f}   circle {bars['circle']:.0f}   "
          f"biome {args.biome_side}x{args.biome_side} within {bars['dry']:.0f}   TF {bars['tf']:.0f}\n")
    met = Counter(r["_met"] for r in rows)
    print("criteria met:", {k: met[k] for k in sorted(met, reverse=True)})
    labels = (["village", "circle", "no-rain", "humid"]
              + (["touching"] if args.biome_gap is not None else [])
              + ["dungeon"] + (["TF"] if args.tf else []))[:n]
    print("individually: " + "   ".join(
        f"{labels[i]} {sum(1 for r in rows if r['_f'][i])}" for i in range(n)))
    print(f"pass loot Min gate: {sum(1 for r in rows if r['minok'])}\n")

    pool = [r for r in rows if r["minok"]] if args.require_min else rows
    pool.sort(key=lambda r: (-r["_met"], -r["score"]))
    for rank, r in enumerate(pool[:args.top], 1):
        letters = "VCNH" + ("A" if args.biome_gap is not None else "") + "D" + ("T" if args.tf else "")
        tag = "".join(x for x, f in zip(letters, r["_f"]) if f)
        s = r["spawn"]
        print(f"#{rank}  seed {r['seed']}   loot {r['score']:,}   {r['_met']}/{n} [{tag}]"
              + ("" if r["minok"] else "   MIN-FAIL"))
        # SKY_Y, not 64. Stage 0 never computes a height for spawn, villages or biome squares — the probe
        # emits a literal 64 for spawn (Prefilter.java:1517) — and 64 is underground at most of these
        # coordinates while looking exactly like a prediction. 200 is above any terrain in the pack and
        # is labelled, so it reads as "descend from here" rather than as an answer.
        sy = s[1] if r.get("spawn_y_real") else SKY_Y
        ynote = "" if r.get("spawn_y_real") else "   [Y unknown — fly down]"
        print(f"      spawn        /tp {s[0]} {sy} {s[2]}{ynote}")
        v = (r.get("smeltery_village") if args.village_needs_smeltery else r["village"])
        c, dry, hum, t = r["circle"], r["dry"], r["hum"], r.get("tf")
        vlabel = "village+smelt" if args.village_needs_smeltery else "village      "
        print(f"      {vlabel}{fmt(v[0], bars['village'])}  /tp {v[1]} {SKY_Y} {v[2]}  [Y unknown — fly down]"
              if v else f"      {vlabel}  none qualifying")
        if c:
            # Index 4 is the container Y when the witchery replay produced one; the cell origin has none.
            cy = c[4] if len(c) > 4 and c[4] is not None else None
            print(f"      coven circle {fmt(c[0], bars['circle'])}  /tp {c[1]} {cy} {c[2]}"
                  "   [Y predicted, may be 1 low]" if cy is not None
                  else f"      coven circle {fmt(c[0], bars['circle'])}  /tp {c[1]} {SKY_Y} {c[2]}"
                       "   [Y unknown — fly down]")
        else:
            print("      coven circle   none")
        if dry:
            de = edge_dist(r["spawn"], dry)
            print(f"      no-rain {dry[0]}x{dry[0]:<3}{fmt(de, bars['dry'])} to edge  "
                  f"(centre {dry[1]:.0f})  /tp {dry[2]} 200 {dry[3]}")
        else:
            print("      no-rain        none")
        if hum:
            he = edge_dist(r["spawn"], hum)
            print(f"      humid {hum[0]}x{hum[0]:<5}{fmt(he, bars['hum'])} to edge  "
                  f"(centre {hum[1]:.0f})  /tp {hum[2]} 200 {hum[3]}")
        else:
            print("      humid          none")
        dg = r.get("dungeon")
        print(f"      rl dungeon   {fmt(dg[0], args.dungeon_within)}  /tp {dg[1]} 200 {dg[2]} "
              f"(trigger chunk, +-8 blocks)" if dg else "      rl dungeon     none in window")
        # The enchant dungeon is a hard criterion, so it gets its own tp: the nearest dungeon above is
        # often a DIFFERENT, closer one without a table, and teleporting to that proves nothing. The
        # table coordinate is exact (read from the generated layout), not a trigger-chunk centre.
        en = r.get("ench")
        if en:
            print(f"      enchant tbl  {fmt(en[0], args.dungeon_within)}  /tp {en[1]} {en[2]} {en[3]}"
                  f"   ({en[4] or 'unknown'} tower, table Y is exact)")
        else:
            print("      enchant tbl    none in window")
        pair = r.get("pair")
        if pair:
            pcx = (pair["dry"][0] + pair["hum"][0] + pair["side"]) * 16 // 2
            pcz = (pair["dry"][1] + pair["hum"][1] + pair["side"]) * 16 // 2
            mark = ("OK" if r["_f"][4] else "  ") if args.biome_gap is not None else "--"
            print(f"      biome pair   {mark} {pair['gap']:6d} chunk gap, "
                  f"{pair['side']}x{pair['side']} each   /tp {pcx} 200 {pcz}   "
                  + ("" if args.biome_gap is not None else "(not scored)"))
        else:
            g = r.get("biome_gap")
            if g is not None:
                print(f"      biome gap    {'OK' if g <= args.biome_gap else '  '} {g:6d} chunks between "
                      f"the two largest squares (pre-pair sweep)")
            else:
                print("      biome gap      one of the squares is absent")
        if args.tf:
            print(f"      TF shards    {fmt(t[0], bars['tf'])}  /tp {t[1]} 40 {t[2]} (dim 7)" if t
                  else "      TF shards      not all three mixes")
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
