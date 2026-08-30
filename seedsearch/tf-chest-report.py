#!/usr/bin/env python3
"""Summarize Twilight Forest chest loot from probe reports.

Answers the four questions a runner actually has about a Twilight Forest trip: what spawns, how often,
in what stack sizes, and whether the trip pays for the diamond that opens the portal.

WHAT THE PROBE GIVES US. TF treasure chests are vanilla Blocks.chest with a TileEntityChest —
TFTreasure.generate places the block and fills it directly, never touching ChestGenHooks — so the
probe's existing IInventory sweep captures them unchanged and the "type" field reads TileEntityChest.
The loot table a chest was filled from is NOT recoverable from the chest itself, so attribution comes
from position, via the per-chunk "tffeature" the probe writes. That attribution is taken from the
probe rather than recomputed here on purpose: the TF region grid is reproducible in Python, and
reimplementing a mod's placement maths is exactly how self-consistent but real-divergent answers get
produced.

DENOMINATORS. Feature instances are counted per 16x16-chunk REGION, not per chunk. TF places at most
one major feature per region, so a per-chunk rate is meaningless — a large hollow hill spanning 25
chunks is one feature, not 25.

DETERMINISM. TF chest contents are a pure function of position and world seed:
TFTreasure.generate re-seeds with treasureRNG.setSeed(world.getSeed() * x + y ^ z) before every draw,
with no read of the chunk populate stream. So two runs of a seed give identical chests, and the
determinism jar's WeightedRandomChestContent fix neither applies to them nor needs to.

Usage:
  tf-chest-report.py <report-dir> [--top 25] [--feature NAME] [--targets]
"""
import argparse
import collections
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
from searchlib import load_dir  # noqa: E402

# Worth the trip, or worth routing for. GT ingots resolve by damage value (11000 + material id) so they
# are matched on display name instead, same as ingot-hunt.py does.
TARGETS = ("Steeleaf", "Ironwood", "Fiery", "Mazebreaker", "Knightly", "Phantom", "Naga",
           "Magic Map", "Trophy", "Diamond", "Emerald", "Gold Ingot", "Iron Ingot",
           "Ore Magnet", "Thaumium", "Charm", "Crumble Horn", "Peacock", "Moonworm")

# Heading for chests that belong to no major feature. Its "instances" are chunks, not structures, so
# its chests/instance row is not comparable with the others — it is a loot bucket, not a site.
NO_FEATURE = "(outside any major feature)"


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("report_dir")
    ap.add_argument("--top", type=int, default=25)
    ap.add_argument("--feature", help="restrict every section to one feature name")
    ap.add_argument("--targets", action="store_true", help="only show the route-relevant item filter")
    args = ap.parse_args()

    reports = [r for r in load_dir(args.report_dir)]
    if not reports:
        print(f"no seed-*.json under {args.report_dir}", file=sys.stderr)
        return 1

    tf = [r for r in reports if r.dim != 0]
    skipped = len(reports) - len(tf)
    if not tf:
        print("every report in this directory is dimension 0 — no Twilight Forest data to summarize",
              file=sys.stderr)
        return 1

    items_by_feature = collections.defaultdict(collections.Counter)
    stacks_by_item = collections.defaultdict(list)
    chests_per_instance = collections.defaultdict(list)
    regions_seen = collections.Counter()
    instances_seen = collections.Counter()
    chunks_walked = 0
    chests_total = 0
    unattributed = 0

    for r in tf:
        chunks_walked += len(r.chunks)
        # Region-level denominator: one entry per feature-grid region the probe mapped.
        for region in (r.tffeatures.get("regions") or {}).values():
            regions_seen["_total"] += 1
            if region["feature"] != "nothing":
                regions_seen[region["feature"]] += 1

        # Chests grouped by the instance they belong to, keyed by feature centre so two hills in one
        # window stay separate.
        by_instance = collections.defaultdict(list)
        for key, c in r.chunks.items():
            feature = c.get("tffeature")
            for chest in c.get("chests", []):
                chests_total += 1
                if not feature:
                    # Not a defect and not a missing flag: TF places plenty of loot outside the
                    # major-feature grid. Hollow-tree leaf caches (MapGenTFHollowTree ->
                    # ComponentTFHollowTreeLeafDungeon, TFTreasure.tree_cache) are the bulk of them,
                    # and MapGenTFHollowTree is not a TFFeature. Keep them under their own heading
                    # rather than dropping them — they are most of the chests in a typical window,
                    # and a report that silently omits them reads as "there is nothing here".
                    unattributed += 1
                    by_instance[(NO_FEATURE, key)].append(chest)
                    continue
                by_instance[(feature, tuple(c.get("tffeatureCenter", ())))].append(chest)

        for (feature, _centre), chests in by_instance.items():
            if args.feature and feature != args.feature:
                continue
            instances_seen[feature] += 1
            chests_per_instance[feature].append(len(chests))
            for chest in chests:
                for it in chest["items"]:
                    items_by_feature[feature][it["name"]] += it["n"]
                    stacks_by_item[(feature, it["name"])].append(it["n"])

    print(f"{len(tf)} Twilight Forest report(s), {chunks_walked} chunks walked, {chests_total} chests")
    if skipped:
        print(f"  ({skipped} dimension-0 report(s) in this directory were skipped)")
    if unattributed:
        print(f"  {unattributed} chest(s) outside any major feature — reported under "
              f"\"{NO_FEATURE}\" below.")
        print("  Mostly hollow-tree leaf caches: MapGenTFHollowTree is not a TFFeature, so those")
        print("  chests are correctly outside the feature grid rather than mis-attributed.")
    if not any(r.tffeatures for r in tf):
        print("  ! no report carries a feature map — rerun with -Dprobe.tffeatures=N for attribution")
    print()

    if args.targets:
        print("route-relevant items")
        hits = collections.Counter()
        for feature, counter in items_by_feature.items():
            for name, n in counter.items():
                if any(t.lower() in name.lower() for t in TARGETS):
                    hits[(feature, name)] += n
        if not hits:
            print("  none found in this corpus")
        for (feature, name), n in hits.most_common(args.top):
            print(f"  {feature:<24} {name:<34} {n}")
        return 0

    print("how often  (a region holds at most one major feature, so regions are the denominator)")
    total_regions = regions_seen["_total"]
    for feature in sorted(regions_seen, key=lambda f: -regions_seen[f]):
        if feature == "_total":
            continue
        n = regions_seen[feature]
        share = f"{100.0 * n / total_regions:.1f}%" if total_regions else "n/a"
        walked = instances_seen.get(feature, 0)
        counts = chests_per_instance.get(feature, [])
        chest_note = ""
        if counts:
            chest_note = (f"   chests/instance min {min(counts)} median "
                          f"{statistics.median(counts):g} max {max(counts)}")
        print(f"  {feature:<24} {n:>4} of {total_regions} regions ({share:>5})"
              f"   walked instances {walked}{chest_note}")
    print()

    print("what spawns, per feature")
    for feature in sorted(items_by_feature, key=lambda f: -sum(items_by_feature[f].values())):
        counter = items_by_feature[feature]
        n_inst = instances_seen[feature]
        print(f"  {feature}  ({n_inst} instance(s), {sum(counter.values())} items)")
        for name, n in counter.most_common(args.top):
            sizes = stacks_by_item[(feature, name)]
            per = f"{n / n_inst:.2f}/instance" if n_inst else ""
            print(f"    {name:<34} {n:>5}  {per:>16}   stack median "
                  f"{statistics.median(sizes):g} max {max(sizes)}")
        print()

    if not instances_seen:
        print("no chest was attributed to a feature — was the walk centred on one?")
    return 0


if __name__ == "__main__":
    sys.exit(main())
