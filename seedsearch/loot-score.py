#!/usr/bin/env python3
"""Rank seeds by chest loot against a speedrun item-value table.

Reads either a stage-0 prefilter JSONL (`scripts/prefilter.sh`) or a directory of full-generation
probe search reports, so the same scoring produces both the stage-0 ranking and the full-gen
confirmation of its winners. The two numbers are not interchangeable: stage 0 sees Roguelike dungeon
chests and village piece chests, and nothing else. Every run prints what it could not see.

Value table format: CSV with columns `Item`, `Value`, `Limit`, `Min`. `Limit` is a hard cap on the
quantity of that item that counts toward one seed's score; blank means uncapped. `Min` is the
opposite and is a filter rather than a score term — a seed holding fewer than `Min` of that item is
dropped from the ranking, checked against the raw quantity rather than the capped one. Item names
are matched against the probe's `name` field, which is the in-game display name.

usage:
  loot-score.py <value-table.csv> <prefilter.jsonl | fullgen-dir> [options]

options:
  --radius N       chunks from the spawn chunk that count (default 15, chebyshev)
  --top N          seeds to report (default 10)
  --chests N       representative chests per seed (default 3)
  --dup MODE       duplicate value rows: max (default), first, error
  --loot-tables F  ChestLootExport chestloot.csv; adds EV per chest and flags table
                   entries whose only loot source cannot reach a worldgen chest
  --seeds FILE     write the top-N seeds, one per line, for a follow-up full-gen run
  --quiet          ranking only, suppress the diagnostics block
"""
import argparse
import csv
import json
import math
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

# Loot sources a stage-0 run cannot see, and why. Printed on every stage-0 run: a score that does not
# say what it could not see reads as a complete score, and it is not one.
#
# Expected value per chest is deliberately NOT hardcoded here. It is a function of the value table,
# so any baked-in figure is wrong the moment the table changes. Pass --loot-tables to compute it.
SOURCE_GROUPS = [
    # Must precede the generic roguelike entry, which would otherwise swallow it.
    ("roguelike REWARD", lambda s, c: s == "roguelike" and c == "REWARD",
     "DEAD in this pack. The REWARD loot table is registered (SettingsLootRules maps Treasure.REWARD"
     " -> Loot.REWARD, and config/roguelike_dungeons/settings/loot_reward.json defines 5 levels), but"
     " the only room that places a REWARD chest, DungeonRoom.REWARD, is in no room pool this pack"
     " uses: not the mod's builtin SettingsRooms (15 rooms, no REWARD) and not any of GTNH's six"
     " rooms_*.json. Measured: 0 of ~2.7M roguelike chests over 5000 seeds."),
    ("roguelike", lambda s, c: s == "roguelike", "stage 0 predicts these exactly, including real Y"),
    ("village pieces", lambda s, c: c.startswith("vn_") or c in {
        "villageBlacksmith", "TinkerHouse", "TinkerPatterns", "railcraft:workshop",
        "naturalistChest", "composting", "towerChestContents"},
     "stage 0 predicts contents exactly; Y is the piece's nominal ground level"),
    ("chest1-4", lambda s, c: c in {"chest1", "chest2", "chest3", "chest4"},
     "LootGames minigame rewards, not worldgen chests. Rolled from an unseeded static Random when a"
     " player wins, so not a function of the seed at all."),
    ("stronghold", lambda s, c: c.startswith("stronghold"),
     "generates (3 per world, no biome gate under RWG) but the first ring sits ~640-1150 blocks out."),
    ("vanilla WorldGenDungeons", lambda s, c: c == "dungeonChest",
     "existence is route-dependent, held pending the GregTech ore live-terrain read."),
    ("mineshaft", lambda s, c: c.startswith("mineshaft"),
     "chest presence is a 1-in-100 draw inside addComponentParts; needs a replay against a"
     " write-absorbing world."),
    ("pyramid / igloo", lambda s, c: c.startswith("pyramid") or c.startswith("igloo"),
     "DEAD in this pack: RWG never constructs MapGenScatteredFeature."),
]

# Groups nothing in the overworld can ever put in a chest, so a value-table entry reachable only from
# these is unscoreable by any seed search, not merely by stage 0.
UNREACHABLE_GROUPS = {"chest1-4", "pyramid / igloo", "roguelike REWARD"}


def analyse_loot_tables(path, values):
    """-> (ev_by_group, sources_by_item) from a ChestLootExport chestloot.csv.

    ev_per_roll = sum over entries of (pick_chance_per_roll * mean_stack * item_value), times mean
    rolls. Uses the `post` phase for chestgenhooks, because F9 leaves one table for the whole world,
    and all roguelike rows. Roguelike is summed across its levels, so its figure is inflated relative
    to a single chest; the ordering between groups is the usable signal.
    """
    def num(x, default=0.0):
        try:
            return float(x)
        except (TypeError, ValueError):
            return default

    per_cat, sources = defaultdict(lambda: [0.0, 0.0]), defaultdict(set)
    with open(path, newline="", encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            source, phase = row["source"], row["phase"]
            if source == "chestgenhooks" and phase != "post":
                continue
            if source not in ("chestgenhooks", "roguelike"):
                continue
            cat = row["category"] or row["table"]
            key = norm(row["display_name"])
            if key in values:
                sources[key].add((source, cat))
            slot = per_cat[(source, cat)]
            slot[0] += (num(row["pick_chance_per_roll"])
                        * (num(row["stack_min"], 1) + num(row["stack_max"], 1)) / 2.0
                        * values.get(key, 0))
            slot[1] = max(slot[1], (num(row["rolls_min"]) + num(row["rolls_max"])) / 2.0)

    ev = defaultdict(float)
    for (source, cat), (per_roll, rolls) in per_cat.items():
        for name, match, _ in SOURCE_GROUPS:
            if match(source, cat):
                ev[name] += per_roll * (rolls if rolls > 0 else 1.0)
                break
    return ev, sources


SECTION = re.compile("\xa7.")


def norm(name):
    """Display name to match key. Section-sign colour codes appear on both sides of the join."""
    return SECTION.sub("", name or "").strip().casefold()


def load_values(path, dup_mode):
    """-> (values, limits, mins, display, duplicates). Keyed by normalised display name; `display`
    keeps the table's own spelling so output never shows the casefolded match key.

    `Min` is a requirement, not a score term: a seed holding fewer than Min of that item is dropped
    from the ranking. Checked against the raw quantity, not the capped one.
    """
    values, limits, mins, display, seen = {}, {}, {}, {}, defaultdict(list)
    with open(path, newline="", encoding="utf-8") as fh:
        for row_no, row in enumerate(csv.DictReader(fh), start=2):
            item = (row.get("Item") or "").strip()
            if not item:
                continue
            # Floats are accepted because a rarity-normalised table (value / mean appearances per
            # seed) spans eight orders of magnitude — Redstone lands near 0.002 and no integer scale
            # holds both ends.
            try:
                value = float((row.get("Value") or "").strip())
            except ValueError:
                continue
            if value == int(value):
                value = int(value)
            key = norm(item)
            raw_limit = (row.get("Limit") or "").strip()
            limit = int(raw_limit) if raw_limit else None
            raw_min = (row.get("Min") or "").strip()
            minimum = int(float(raw_min)) if raw_min else None
            if minimum is not None and minimum <= 0:
                minimum = None
            seen[key].append((row_no, item, value, limit, minimum))

    duplicates = {k: v for k, v in seen.items() if len(v) > 1}
    if duplicates and dup_mode == "error":
        for key, rows in duplicates.items():
            print(f"duplicate value row: {key} at lines {[r[0] for r in rows]}", file=sys.stderr)
        sys.exit("duplicate rows in the value table and --dup error was given")

    for key, rows in seen.items():
        chosen = max(rows, key=lambda r: r[2]) if dup_mode == "max" else rows[0]
        values[key] = chosen[2]
        limits[key] = chosen[3]
        mins[key] = chosen[4]
        display[key] = SECTION.sub("", chosen[1])
    return values, limits, mins, display, duplicates


def unmet_minimums(qty, mins):
    """-> [(key, required, found)] for every minimum this seed fails. Empty means it qualifies."""
    return [(k, need, qty.get(k, 0)) for k, need in mins.items()
            if need and qty.get(k, 0) < need]


class Chest:
    __slots__ = ("source", "category", "pos", "items", "y_nominal")

    def __init__(self, source, category, pos, items, y_nominal=False):
        self.source = source
        self.category = category
        self.pos = pos
        self.items = items
        self.y_nominal = y_nominal


class Seed:
    def __init__(self, seed, spawn, chests, kill=None):
        self.seed = seed
        self.spawn = spawn
        self.chests = chests
        self.kill = kill

    def in_scope(self, radius):
        """Chests within `radius` chunks of the spawn chunk, chebyshev — matches searchlib._near."""
        scx, scz = self.spawn[0] >> 4, self.spawn[2] >> 4
        return [c for c in self.chests
                if max(abs((c.pos[0] >> 4) - scx), abs((c.pos[2] >> 4) - scz)) <= radius]


def load_stage0(path):
    """Stage-0 prefilter JSONL. Village chests carry a nominal Y; Roguelike chest Y is real.

    A record with no `spawn` key means the run had `PREFILTER_TERRAIN=-1`: Prefilter only computes the
    spawn point when the terrain digest is enabled, and otherwise leaves it at the origin. Both the
    dungeon scan and this scorer's window are then centred on (0, 0) instead of on spawn, which is a
    wrong answer rather than a missing one. Refuse it.
    """
    out, missing_spawn = [], 0
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        d = json.loads(line)
        if "kill" in d:
            out.append(Seed(d["seed"], [0, 0, 0], [], kill=d["kill"]))
            continue
        if "spawn" not in d:
            missing_spawn += 1
        chests = []
        for start in d.get("village_starts", []):
            for entry in start.get("chests", []):
                ch = entry["chest"]
                chests.append(Chest("village", entry.get("category", ""), ch["pos"],
                                    ch.get("items", []), y_nominal=True))
        for dungeon in d.get("dungeons", []):
            for ch in dungeon.get("chests", []):
                chests.append(Chest("roguelike", "", ch["pos"], ch.get("items", [])))
        out.append(Seed(d["seed"], d.get("spawn", [0, 0, 0]), chests))
    if missing_spawn:
        sys.exit(f"{missing_spawn} of {len(out)} records carry no spawn point, so the scoring window "
                 f"would be centred on the origin instead. Re-run the prefilter with "
                 f"PREFILTER_TERRAIN >= 0 (see Prefilter.java:1194).")
    return out


def load_fullgen(path):
    """Full-generation probe search reports. Every chest source is present; category is not recorded,
    so chests are labelled by tile-entity type rather than guessed at."""
    out = []
    for p in sorted(Path(path).glob("seed-*.json")):
        try:
            d = json.load(open(p, encoding="utf-8"))
        except Exception as e:  # a truncated report must not silently drop a seed
            print(f"WARN: {p.name}: {e}", file=sys.stderr)
            continue
        search = d.get("search", {})
        chests = [Chest("fullgen", ch.get("type", ""), ch["pos"], ch.get("items", []))
                  for chunk in search.get("chunks", {}).values()
                  for ch in chunk.get("chests", [])]
        out.append(Seed(d["seed"], search.get("spawn", [0, 0, 0]), chests))
    return out


def score_seed(chests, values, limits):
    """-> (score, uncapped, per-chest marginals, item quantities).

    The cap is allocated greedily over chests ranked by their own uncapped value, so each chest's
    marginal is what it actually earns given everything richer was taken first. Ranking on raw chest
    value instead would promote a chest whose every item was capped away at seed level. The marginals
    sum to the seed score by construction: each item's takes total min(quantity, limit).
    """
    def raw(chest):
        return sum(values.get(norm(i.get("name")), 0) * i["n"] for i in chest.items)

    qty = Counter()
    for chest in chests:
        for item in chest.items:
            key = norm(item.get("name"))
            if key in values:
                qty[key] += item["n"]

    uncapped = sum(values[k] * n for k, n in qty.items())
    remaining = {k: (math.inf if limits.get(k) is None else limits[k]) for k in qty}

    marginals = []
    for chest in sorted(chests, key=raw, reverse=True):
        earned = 0
        for item in chest.items:
            key = norm(item.get("name"))
            if key not in values:
                continue
            take = min(item["n"], remaining[key])
            if take > 0:
                earned += values[key] * take
                remaining[key] -= take
        marginals.append((earned, raw(chest), chest))
    return sum(m[0] for m in marginals), uncapped, marginals, qty


def fmt_items(chest, values, display, limit=6):
    """Valued contents of one chest, richest first. Stacks of the same item are summed: a chest holding
    three slots of Redstone is one line, not three."""
    totals = Counter()
    for item in chest.items:
        key = norm(item.get("name"))
        if key in values:
            totals[key] += item["n"]
    ranked = sorted(totals.items(), key=lambda kv: values[kv[0]] * kv[1], reverse=True)
    parts = [f"{display.get(k, k)} x{n} ({values[k] * n})" for k, n in ranked[:limit]]
    if len(ranked) > limit:
        parts.append(f"+{len(ranked) - limit} more")
    return ", ".join(parts) if parts else "(nothing valued)"


def main():
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("value_table")
    ap.add_argument("input")
    ap.add_argument("--radius", type=int, default=15)
    ap.add_argument("--top", type=int, default=10)
    ap.add_argument("--chests", type=int, default=3)
    ap.add_argument("--dup", choices=("max", "first", "error"), default="max")
    ap.add_argument("--loot-tables", help="ChestLootExport chestloot.csv, for EV per chest")
    ap.add_argument("--seeds")
    ap.add_argument("--quiet", action="store_true")
    ap.add_argument("-h", "--help", action="store_true")
    args = ap.parse_args()
    if args.help:
        print(__doc__)
        return 0

    values, limits, mins, display, duplicates = load_values(args.value_table, args.dup)
    src = Path(args.input)
    seeds = load_fullgen(src) if src.is_dir() else load_stage0(src)
    mode = "full-generation" if src.is_dir() else "stage-0 prefilter"
    if not seeds:
        sys.exit(f"no seeds loaded from {src}")

    ranked, all_qty, unvalued, nameless, killed = [], Counter(), Counter(), 0, 0
    rejected = Counter()
    total_stacks = matched_stacks = 0
    for s in seeds:
        if s.kill:
            killed += 1
            continue
        scoped = s.in_scope(args.radius)
        for chest in scoped:
            for item in chest.items:
                total_stacks += 1
                if "name" not in item:
                    nameless += 1
                    continue
                key = norm(item["name"])
                if key in values:
                    matched_stacks += 1
                    all_qty[key] += item["n"]
                else:
                    unvalued[SECTION.sub("", item["name"])] += item["n"]
        score, uncapped, marginals, qty = score_seed(scoped, values, limits)
        unmet = unmet_minimums(qty, mins)
        if unmet:
            for key, need, _got in unmet:
                rejected[f"{display.get(key, key)} >= {need}"] += 1
            continue
        ranked.append((score, uncapped, s, scoped, marginals, qty))

    ranked.sort(key=lambda r: r[0], reverse=True)
    if rejected:
        # Counted per requirement so an empty ranking says which bar was too high, rather than
        # looking like a corpus with nothing in it.
        print(f"held back by Min requirements ({len(seeds) - killed - len(ranked)} of "
              f"{len(seeds) - killed} seeds):")
        for req, n in rejected.most_common():
            print(f"  {req}: {n} seeds short")
        print()

    print(f"=== loot-score: {len(seeds)} seeds, {mode}, radius {args.radius} chunks ===\n")
    for rank, (score, uncapped, s, scoped, marginals, qty) in enumerate(ranked[:args.top], 1):
        by_source = Counter(c.source for c in scoped)
        sources = ", ".join(f"{n} {k}" for k, n in sorted(by_source.items()))
        print(f"#{rank:<3} seed {s.seed}   score {score}   "
              f"(uncapped {uncapped}, capped away {uncapped - score})")
        print(f"     spawn {s.spawn}      chests in scope: {sources or 'none'}")
        # Show the CAPPED contribution. Printing value x quantity would advertise points the seed
        # does not score: one seed here holds 3 Alumite Large Plates against a limit of 2.
        def counted(key, n):
            lim = limits.get(key)
            return n if lim is None else min(n, lim)

        top_items = sorted(qty.items(), key=lambda kv: values[kv[0]] * counted(*kv), reverse=True)[:5]
        parts = []
        for k, n in top_items:
            c = counted(k, n)
            over = f", {n} found, capped at {c}" if c != n else ""
            parts.append(f"{display.get(k, k)} x{c} ({values[k] * c}{over})")
        print("     top items: " + (", ".join(parts) or "(nothing valued)"))
        shown = [m for m in marginals if m[0] > 0][:args.chests]
        if shown:
            print("     representative chests")
        for i, (earned, rawv, chest) in enumerate(shown, 1):
            x, y, z = chest.pos
            dist = round(math.dist((x, z), (s.spawn[0], s.spawn[2])))
            label = f"{chest.source}" + (f" {chest.category}" if chest.category else "")
            flag = "   Y NOMINAL" if chest.y_nominal else ""
            print(f"       [{i}] {label}   ({x}, {y}, {z}){flag}   {dist} blocks from spawn"
                  f"   marginal {earned} (raw {rawv})")
            print(f"           /tp {x} {y + 1} {z}")
            print(f"           {fmt_items(chest, values, display)}")
        print()

    if args.seeds:
        Path(args.seeds).write_text("\n".join(str(r[2].seed) for r in ranked[:args.top]) + "\n")
        print(f"wrote top {min(args.top, len(ranked))} seeds to {args.seeds}\n")

    if args.quiet:
        return 0

    print("=== diagnostics ===")
    print(f"item stacks in scope        : {total_stacks}")
    print(f"  matched by the value table: {matched_stacks}")
    print(f"  unvalued                  : {total_stacks - matched_stacks - nameless}")
    print(f"  no display name           : {nameless}"
          + ("   <-- these are silently unscored" if nameless else ""))
    print(f"seeds killed by a gate      : {killed}")

    never = sorted(((v, k) for k, v in values.items() if k not in all_qty), reverse=True)
    print(f"\nvalue-table entries that appeared ZERO times: {len(never)} of {len(values)}")
    for value, key in never[:30]:
        print(f"  {value:6d}  {display.get(key, key)}")
    if len(never) > 30:
        print(f"  … and {len(never) - 30} more")

    print("\nmost frequent items carrying NO value (candidates for the table):")
    for name, count in unvalued.most_common(25):
        print(f"  {count:6d}  {name}")

    if duplicates:
        print(f"\nduplicate value rows ({args.dup} applied):")
        for key, rows in duplicates.items():
            picked = max(rows, key=lambda r: r[2]) if args.dup == "max" else rows[0]
            others = ", ".join(f"{r[2]} (line {r[0]})" for r in rows if r is not picked)
            print(f"  {key}: kept {picked[2]} (line {picked[0]}), dropped {others}")

    ev, item_sources = ({}, {})
    if args.loot_tables:
        ev, item_sources = analyse_loot_tables(args.loot_tables, values)

    print("\nloot sources and what this run saw:")
    if src.is_dir():
        print("  full generation covers every chest source in the overworld window.")
    for name, _, why in SOURCE_GROUPS:
        seen = "seen" if (src.is_dir() or name in ("roguelike", "village pieces")) else "NOT SEEN"
        cost = f" EV/chest {ev[name]:8.0f}" if name in ev else ""
        print(f"  {name:26s} {seen:9s}{cost}  — {why}")
    if not ev:
        print("  (pass --loot-tables <chestloot.csv> for EV per chest against this value table)")

    if item_sources:
        stuck = defaultdict(list)
        for key, pairs in item_sources.items():
            groups = set()
            for source, cat in pairs:
                for name, match, _ in SOURCE_GROUPS:
                    if match(source, cat):
                        groups.add(name)
                        break
            # Never assert "unreachable" for something this run actually saw. The loot-table export
            # under-represents roguelike contents (its ORE/REWARD providers resolve at runtime, not
            # in the settings JSON), so the table analysis is a model and the corpus is evidence.
            # Measured: of 27 items the model called unreachable, 2 — Platinum Ingot and Zero Point
            # Module — turned up in real roguelike chests.
            if groups and groups <= UNREACHABLE_GROUPS and key not in all_qty:
                stuck[frozenset(groups)].append((values[key], display.get(key, key)))
        if stuck:
            print("\nvalue-table entries NO seed search can score — their only source cannot put an"
                  "\nitem in a worldgen chest:")
            for groups, items in stuck.items():
                for value, name in sorted(items, reverse=True):
                    print(f"  {value:6d}  {name:32s} only from {', '.join(sorted(groups))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
