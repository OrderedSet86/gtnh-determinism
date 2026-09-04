#!/usr/bin/env python3
"""Resolve the NBT on chest-loot table entries into readable item descriptions.

Reads a ChestLootExport `chestloot.csv` and turns each entry's canonical NBT blob into the sort of
description the in-game tooltip shows — enchantments by name, Tinkers' tool stats and materials,
Forestry genomes, Thaumcraft vis charges — so the loot tables can be read without launching the
game.

This describes the LOOT TABLES, not any particular world. An entry says what an item looks like
whenever that table rolls it; nothing here is per-seed.

Inputs, all produced by the probe with `-Dprobe.lootcsv=<dir>`:
  chestloot.csv     the loot tables, one row per entry, with an `nbt` column
  enchantments.csv  id -> name, required because ids are assigned at registration and are
                    pack-specific: daily-707 uses 43 distinct ids and only about half are vanilla
Optional:
  materials.csv     TiC material meta -> name (results/2026-08-29-tc-chest-materials/materials.csv),
                    which turns InfiTool Head/Handle/Accessory numbers into material names

usage:
  loot-nbt-report.py <export-dir> [--materials materials.csv] [--md out.md] [--csv out.csv]
"""
import argparse
import csv
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROMAN = ["", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"]
SECTION = re.compile("\xa7.")


def roman(n):
    return ROMAN[n] if 0 < n < len(ROMAN) else str(n)


def parse_nbt(text):
    """Canonical NBT string -> Python. Not a general SNBT parser: it handles exactly what
    WorldgenProbe.canonicalNbt emits — `{k:v,...}`, `[0:{...},1:{...}]` indexed lists, quoted
    strings, and numeric type suffixes (b s L f d).

    Written rather than reached for because the format is not JSON (unquoted keys, indexed list
    entries, type suffixes) and the alternative is a regex soup that silently mis-reads nesting.
    """
    s, i = text.strip(), 0

    def skip():
        nonlocal i
        while i < len(s) and s[i] in " \t\n":
            i += 1

    def value():
        nonlocal i
        skip()
        if i >= len(s):
            return None
        if s[i] == "{":
            i += 1
            out = {}
            skip()
            if i < len(s) and s[i] == "}":
                i += 1
                return out
            while i < len(s):
                skip()
                k = key()
                skip()
                if i < len(s) and s[i] == ":":
                    i += 1
                out[k] = value()
                skip()
                if i < len(s) and s[i] == ",":
                    i += 1
                    continue
                if i < len(s) and s[i] == "}":
                    i += 1
                break
            return out
        if s[i] == "[":
            i += 1
            out = []
            skip()
            if i < len(s) and s[i] == "]":
                i += 1
                return out
            while i < len(s):
                skip()
                # indexed entries look like `0:{...}`; the index carries no information
                j = i
                while j < len(s) and s[j].isdigit():
                    j += 1
                if j > i and j < len(s) and s[j] == ":":
                    i = j + 1
                out.append(value())
                skip()
                if i < len(s) and s[i] == ",":
                    i += 1
                    continue
                if i < len(s) and s[i] == "]":
                    i += 1
                break
            return out
        if s[i] == '"':
            i += 1
            buf = ""
            while i < len(s) and s[i] != '"':
                if s[i] == "\\" and i + 1 < len(s):
                    i += 1
                buf += s[i]
                i += 1
            i += 1
            return buf
        j = i
        while j < len(s) and s[j] not in ",}]":
            j += 1
        raw = s[i:j].strip()
        i = j
        m = re.fullmatch(r"(-?[0-9.]+)([bsLlFfDd])?", raw)
        if m:
            num, suf = m.group(1), (m.group(2) or "").lower()
            try:
                return float(num) if (suf in ("f", "d") or "." in num) else int(num)
            except ValueError:
                return raw
        return raw

    def key():
        nonlocal i
        skip()
        if i < len(s) and s[i] == '"':
            return value()
        j = i
        while j < len(s) and s[j] != ":":
            j += 1
        k = s[i:j].strip()
        i = j
        return k

    try:
        return value()
    except Exception:
        return None


def load_enchantments(path):
    return {int(r["id"]): r["name"] for r in csv.DictReader(open(path, encoding="utf-8"))}


def load_materials(path):
    """TiC material meta -> (name, harvest level name, durability, mining speed, attack, trait)."""
    out = {}
    if not path or not Path(path).is_file():
        return out
    for r in csv.DictReader(open(path, encoding="utf-8")):
        try:
            out[int(r["meta"])] = r
        except (TypeError, ValueError):
            continue
    return out


def fmt_ench(entries, ench):
    parts = []
    for e in entries or []:
        if not isinstance(e, dict):
            continue
        eid, lvl = e.get("id"), e.get("lvl", 1)
        name = ench.get(eid, f"enchantment #{eid}")
        parts.append(f"{name} {roman(int(lvl))}".strip())
    return parts


# Damage enchantments, matched on translation key rather than id so modded ids cannot shift the
# meaning. 1.7.10 EnchantmentDamage.func_152376_a: type 0 is level * 1.25 against everything, types
# 1 and 2 are level * 2.5 but only against undead and arthropods respectively — so they are reported
# as separate conditional columns rather than folded into one number.
SHARPNESS = "enchantment.damage.all"
SMITE = "enchantment.damage.undead"
BANE = "enchantment.damage.arthropods"


# Armour protection. 1.7.10 EnchantmentProtection.calcModifierDamage: EPF = floor((6 + level^2)/3 *
# mult). Only Protection applies to every damage source; the other four are conditional, so they are
# reported separately rather than summed into one misleading number.
PROT = {"enchantment.protect.all": ("all damage", 0.75),
        "enchantment.protect.fire": ("fire", 1.25),
        "enchantment.protect.fall": ("fall", 2.5),
        "enchantment.protect.explosion": ("explosions", 1.5),
        "enchantment.protect.projectile": ("projectiles", 1.5)}
SLOTS = {0: "helmet", 1: "chestplate", 2: "leggings", 3: "boots"}


def mitigation(armor, epf):
    """-> (expected damage multiplier, reduction %) for a piece worn on its own.

    1.7.10 EntityLivingBase, two independent multipliers:
      applyArmorCalculations       damage *= (25 - totalArmorValue) / 25
      applyPotionDamageCalculations damage *= (25 - i) / 25

    where `i` is NOT the raw EPF. EnchantmentHelper.getEnchantmentModifierDamage caps EPF at 25 then
    returns `ceil(epf/2) + rand(0 .. floor(epf/2))`, and the caller clamps that to 20 — so the
    enchantment half is random per hit and its expectation is taken exactly over the roll rather than
    by substituting a mean, because the clamp makes it non-linear above EPF 20.

    Armour points and EPF are SET totals in game. Scoring a single piece as if it were the whole set
    is a convention: it is monotonic in both inputs, which is what makes it a usable ranking, but the
    absolute percentage is only reached when the rest of the set contributes nothing.
    """
    armor = max(0, min(int(armor or 0), 25))
    epf = max(0, min(int(epf or 0), 25))
    lo, span = (epf + 1) >> 1, (epf >> 1) + 1
    ench = sum((25 - min(lo + r, 20)) / 25.0 for r in range(span)) / span
    mult = (25 - armor) / 25.0 * ench
    return mult, round(100 * (1 - mult), 1)


def load_attributes(path):
    """(registry, meta) -> row of attack damage, armour points, slot and durability."""
    out = {}
    if not path or not Path(path).is_file():
        return out
    for r in csv.DictReader(open(path, encoding="utf-8")):
        try:
            out[(r["registry_name"], int(r["meta"]))] = r
        except (TypeError, ValueError, KeyError):
            continue
    return out


def _num(row, col):
    try:
        return float(row[col]) if row and str(row.get(col, "")).strip() else None
    except (TypeError, ValueError):
        return None


def armour_stats(d, row, attrs, ench_keys):
    """-> dict of armour figures, or None when the item is not armour."""
    a = attrs.get((row["registry_name"], int(row["meta"]))) if row.get("meta", "").strip() else None
    points = _num(a, "armor")
    if not points:
        return None
    slot = _num(a, "armor_slot")
    epf = {}
    for e in (d.get("ench") or []) if isinstance(d, dict) else []:
        if not isinstance(e, dict):
            continue
        key = ench_keys.get(e.get("id"))
        if key in PROT:
            lvl = int(e.get("lvl", 1))
            label, mult = PROT[key]
            epf[label] = epf.get(label, 0) + int((6 + lvl * lvl) / 3.0 * mult)
    epf_all = epf.get("all damage", 0)
    _, reduction = mitigation(points, epf_all)
    return {
        "damage_reduction_pct": reduction,
        "armor_points": int(points),
        "armor_icons": points / 2.0,
        "slot": SLOTS.get(int(slot), "") if slot is not None else "",
        "durability": int(_num(a, "max_damage") or 0) or "",
        "epf_all": epf.get("all damage", 0),
        "epf_conditional": ", ".join(f"{v} vs {k}" for k, v in sorted(epf.items())
                                     if k != "all damage"),
    }


def weapon_damage(d, row, attrs, ench_keys):
    """-> dict of damage figures, or None when the item deals no melee damage.

    Base comes from the item's attackDamage attribute, except for Tinkers' tools which carry their
    own Attack in NBT. Hearts are health points halved.
    """
    base, source = None, ""
    it = d.get("InfiTool") if isinstance(d, dict) else None
    if isinstance(it, dict) and it.get("Attack") is not None:
        base, source = float(it["Attack"]), "InfiTool.Attack"
    else:
        try:
            base = _num(attrs.get((row["registry_name"], int(row["meta"]))), "attack_damage")
        except (TypeError, ValueError):
            base = None
        source = "attackDamage attribute" if base is not None else ""
    if not base:
        return None

    bonus = {SHARPNESS: 0.0, SMITE: 0.0, BANE: 0.0}
    for e in (d.get("ench") or []) if isinstance(d, dict) else []:
        if not isinstance(e, dict):
            continue
        key = ench_keys.get(e.get("id"))
        lvl = int(e.get("lvl", 1))
        if key == SHARPNESS:
            bonus[SHARPNESS] += lvl * 1.25
        elif key == SMITE:
            bonus[SMITE] += lvl * 2.5
        elif key == BANE:
            bonus[BANE] += lvl * 2.5
    always = base + bonus[SHARPNESS]
    return {
        "base_hp": round(base, 2),
        "hp": round(always, 2),
        "hearts": round(always / 2.0, 2),
        "hearts_vs_undead": round((always + bonus[SMITE]) / 2.0, 2),
        "hearts_vs_arthropod": round((always + bonus[BANE]) / 2.0, 2),
        "source": source,
    }


def item_name(row, nbt):
    """Best available name. The exporter writes a placeholder when it cannot resolve the registry
    name against the running build — 92 of 443 NBT rows here, almost all Roguelike's bare
    `enchanted_book` (no modid, so the lookup fails) plus two mod armour pieces this pack does not
    ship. The stored display name is a better answer than the placeholder when there is one.
    """
    name = (row.get("display_name") or "").strip()
    if not name.startswith("<not a plain item"):
        return SECTION.sub("", name)
    d = parse_nbt(nbt)
    if isinstance(d, dict):
        disp = d.get("display")
        if isinstance(disp, dict) and disp.get("Name"):
            return SECTION.sub("", str(disp["Name"]))
    reg = (row.get("registry_name") or "").strip()
    if reg == "enchanted_book":
        return "Enchanted Book"
    return reg or "(unnamed)"


def describe(nbt, ench, mats):
    """-> (kind, [lines]). Empty lines means the NBT carried nothing worth showing."""
    d = parse_nbt(nbt)
    if not isinstance(d, dict):
        return "unparsed", []
    lines, kind = [], "other"

    if "display" in d and isinstance(d["display"], dict):
        disp = d["display"]
        if disp.get("Name"):
            lines.append(f'named "{SECTION.sub("", str(disp["Name"]))}"')
        lore = disp.get("Lore")
        if isinstance(lore, list) and lore:
            lines.append("lore: " + " / ".join(SECTION.sub("", str(x)) for x in lore))

    for tag in ("ench", "StoredEnchantments"):
        if tag in d:
            got = fmt_ench(d[tag], ench)
            if got:
                kind = "enchanted book" if tag == "StoredEnchantments" else "enchanted"
                label = "stores" if tag == "StoredEnchantments" else "enchanted"
                lines.append(f"{label}: " + ", ".join(got))

    it = d.get("InfiTool")
    if isinstance(it, dict):
        kind = "Tinkers' tool"

        def matname(v):
            m = mats.get(v)
            return m["display_name"] if m else (f"material {v}" if v is not None else None)

        parts = [f"{role} {matname(it[k])}"
                 for k, role in (("Head", "head"), ("Handle", "handle"), ("Accessory", "accessory"))
                 if k in it and matname(it[k])]
        if parts:
            lines.append("built from " + ", ".join(parts))
        dur, total = it.get("Damage", 0), it.get("TotalDurability")
        if total is not None:
            lines.append(f"durability {int(total) - int(dur or 0)} / {int(total)}")
        stat = []
        if it.get("Attack") is not None:
            stat.append(f"attack {it['Attack']}")
        if it.get("MiningSpeed") is not None:
            stat.append(f"mining speed {it['MiningSpeed']}")
        if it.get("HarvestLevel") is not None:
            hl = mats.get(it.get("Head", -1))
            lvl = hl["harvest_level_name"] if hl and hl.get("harvest_level_name") else it["HarvestLevel"]
            stat.append(f"harvest level {lvl}")
        if stat:
            lines.append(", ".join(stat))
        mods = it.get("Modifiers")
        if mods:
            lines.append(f"{int(mods)} modifier slot{'s' if int(mods) != 1 else ''} free")
        if it.get("Unbreaking"):
            lines.append(f"reinforced {it['Unbreaking']}")
        if it.get("Broken"):
            lines.append("**broken**")

    gt = d.get("GT.ToolStats")
    if isinstance(gt, dict):
        kind = "GT tool"
        bits = []
        if gt.get("PrimaryMaterial"):
            bits.append(f"head {gt['PrimaryMaterial']}")
        if gt.get("SecondaryMaterial"):
            bits.append(f"handle {gt['SecondaryMaterial']}")
        if bits:
            lines.append("built from " + ", ".join(bits))
        if gt.get("MaxDamage") is not None:
            lines.append(f"durability {int(gt['MaxDamage'])}")

    if "GT.ItemCharge" in d:
        c = int(d["GT.ItemCharge"])
        kind = "charged"
        lines.append(f"charge {c:,} EU" + (" (full)" if c == 2147483647 else ""))

    gen = d.get("Genome")
    if isinstance(gen, dict):
        kind = "Forestry genome"
        chrom = gen.get("Chromosomes") or []
        traits = []
        for c in chrom:
            if not isinstance(c, dict):
                continue
            a, b = c.get("UID0"), c.get("UID1")
            if not a:
                continue
            short = str(a).split(".")[-1]
            traits.append(short if a == b else f"{short}/{str(b).split('.')[-1]}")
        if traits:
            lines.append("species " + traits[0])
            if len(traits) > 1:
                lines.append("traits: " + ", ".join(traits[1:]))
        lines.append("unanalysed" if not d.get("IsAnalyzed") else "analysed")
        if d.get("MaxH") is not None:
            lines.append(f"health {d.get('Health')} / {d.get('MaxH')}")

    aspects = [k for k in ("aer", "aqua", "ignis", "terra", "ordo", "perditio") if k in d]
    if aspects:
        kind = "vis amulet"
        charged = [f"{k} {int(d[k])}" for k in aspects if d[k]]
        lines.append("vis: " + (", ".join(charged) if charged else "empty"))

    if "pts" in d:
        kind = "knowledge note"
        lines.append(f"{int(d['pts'])} knowledge points")
    if "colour" in d:
        lines.append(f"colour #{int(d['colour']):06X}")
    if "track" in d:
        lines.append(f"track type {d['track']}")
    if "T" in d:
        lines.append(f"chipset tier {d['T']}")
    if "FarmBlock" in d:
        lines.append(f"farm block type {d['FarmBlock']}")
    if "RepairCost" in d:
        lines.append(f"repair cost {int(d['RepairCost'])}")
    for empty in ("jetpackData", "wearableData"):
        if empty in d:
            lines.append(f"{empty}: empty (populated on use)")
    return kind, lines


def main():
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("export_dir")
    ap.add_argument("--materials")
    ap.add_argument("--attributes")
    ap.add_argument("--md")
    ap.add_argument("--csv", dest="csv_out")
    ap.add_argument("-h", "--help", action="store_true")
    args = ap.parse_args()
    if args.help:
        print(__doc__)
        return 0

    d = Path(args.export_dir)
    ench = load_enchantments(d / "enchantments.csv")
    mats = load_materials(args.materials)
    attrs = load_attributes(args.attributes or (d / "item-attributes.csv"))
    ench_keys = {int(r["id"]): r["translation_key"]
                 for r in csv.DictReader(open(d / "enchantments.csv", encoding="utf-8"))}
    rows = list(csv.DictReader(open(d / "chestloot.csv", encoding="utf-8")))

    # Collapse to one row per (item, resolved description, categories). Two things get merged, for
    # different reasons: the same enchanted sword appears in many categories, so categories are
    # collected onto one line rather than repeating the description; and bonusChest genuinely lists
    # some tools twice, differing only by a §f colour code in the stored name, which resolves to the
    # same description and is reported as a variant count rather than as two identical rows.
    seen = {}
    for r in rows:
        nbt = (r.get("nbt") or "").strip()
        if not nbt:
            continue
        kind, lines = describe(nbt, ench, mats)
        parsed = parse_nbt(nbt)
        dmg = weapon_damage(parsed, r, attrs, ench_keys)
        arm = armour_stats(parsed, r, attrs, ench_keys)
        if arm:
            bits = [f"{arm['damage_reduction_pct']:g}% damage reduction",
                    f"{arm['armor_points']} armour ({arm['armor_icons']:g} icons)"
                    + (f", {arm['slot']}" if arm["slot"] else "")]
            if arm["epf_all"]:
                bits.append(f"+{arm['epf_all']} EPF vs all damage")
            if arm["epf_conditional"]:
                bits.append("+" + arm["epf_conditional"] + " EPF")
            lines.insert(0, " · ".join(bits))
        if dmg:
            hearts = f"{dmg['hearts']:g} hearts ({dmg['hp']:g} HP)"
            extra = []
            if dmg["hearts_vs_undead"] != dmg["hearts"]:
                extra.append(f"{dmg['hearts_vs_undead']:g} vs undead")
            if dmg["hearts_vs_arthropod"] != dmg["hearts"]:
                extra.append(f"{dmg['hearts_vs_arthropod']:g} vs arthropods")
            lines.insert(0, hearts + (" · " + ", ".join(extra) if extra else ""))
        desc = " · ".join(lines) if lines else "(nothing in the NBT to show)"
        key = (item_name(r, nbt), r["registry_name"], r["meta"], kind, desc)
        entry = seen.setdefault(key, {"row": r, "where": Counter(), "nbts": set(),
                                      "dmg": dmg, "arm": arm})
        entry["where"][f"{r['source']}/{r['category'] or r['table']}"] += 1
        entry["nbts"].add(nbt)

    out, kinds, unparsed = [], Counter(), []
    for (name, reg, meta, kind, desc), info in seen.items():
        kinds[kind] += 1
        if kind == "unparsed":
            unparsed.append((name, next(iter(info["nbts"]))))
        dmg = info.get("dmg") or {}
        arm = info.get("arm") or {}
        out.append({"item": name, "kind": kind, "registry": reg, "meta": meta,
                    "description": desc,
                    "hearts": dmg.get("hearts", ""),
                    "hearts_vs_undead": dmg.get("hearts_vs_undead", ""),
                    "hearts_vs_arthropod": dmg.get("hearts_vs_arthropod", ""),
                    "attack_hp": dmg.get("hp", ""),
                    "base_attack_hp": dmg.get("base_hp", ""),
                    "damage_reduction_pct": arm.get("damage_reduction_pct", ""),
                    "armor_points": arm.get("armor_points", ""),
                    "armor_icons": arm.get("armor_icons", ""),
                    "slot": arm.get("slot", ""),
                    "durability": arm.get("durability", ""),
                    "epf_all": arm.get("epf_all", ""),
                    "epf_conditional": arm.get("epf_conditional", ""),
                    "found in": ", ".join(sorted(info["where"])),
                    "variants": len(info["nbts"]),
                    "enchant_level": info["row"].get("enchant_level") or "",
                    "nbt": sorted(info["nbts"])[0]})
    out.sort(key=lambda r: (r["kind"], r["item"]))

    print(f"{len(rows)} table entries, {sum(1 for r in rows if (r.get('nbt') or '').strip())} with NBT, "
          f"{len(out)} distinct (item, NBT) combinations")
    print("by kind: " + ", ".join(f"{k} {v}" for k, v in kinds.most_common()))
    if unparsed:
        print(f"\nUNPARSED ({len(unparsed)}) — these resolved to nothing and are shown raw:")
        for n, s in unparsed[:10]:
            print(f"  {n}: {s[:100]}")

    weapons = [r for r in out if r["hearts"] != ""]
    if weapons:
        best = max(weapons, key=lambda r: r["hearts"])
        print(f"\n{len(weapons)} entries deal melee damage; hardest hitting: "
              f"{best['item']} at {best['hearts']:g} hearts ({best['attack_hp']:g} HP)")
        print("  base damage from the item's attackDamage attribute (InfiTool.Attack for TiC tools); "
              "Sharpness adds level x1.25, Smite and Bane level x2.5 but only vs undead/arthropods")

    armour = [r for r in out if r["armor_points"] != ""]
    if armour:
        best = max(armour, key=lambda r: r["damage_reduction_pct"])
        top_raw = max(armour, key=lambda r: r["armor_points"])
        print(f"{len(armour)} entries are armour; best mitigation: {best['item']} at "
              f"{best['damage_reduction_pct']:g}% ({best['armor_points']} armour, "
              f"{best['epf_all']} EPF)")
        if top_raw["item"] != best["item"]:
            print(f"  highest RAW armour is {top_raw['item']} at {top_raw['armor_points']} points, "
                  f"but only {top_raw['damage_reduction_pct']:g}% — armour and EPF multiply, so "
                  "points alone is the wrong ranking")

    enchanted = [r for r in out if "enchanted" in r["kind"]]
    if enchanted:
        print(f"\nEnchantment names resolved from {len(ench)} registered enchantments; "
              f"{sum(1 for r in enchanted if 'enchantment #' in r['description'])} unresolved ids")

    if args.csv_out:
        with open(args.csv_out, "w", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=list(out[0]))
            w.writeheader()
            w.writerows(out)
        print(f"\nwrote {args.csv_out}")

    if args.md:
        by_kind = defaultdict(list)
        for r in out:
            by_kind[r["kind"]].append(r)
        with open(args.md, "w", encoding="utf-8") as fh:
            fh.write("# Chest loot: item descriptions resolved from NBT\n\n")
            fh.write(f"{len(out)} distinct (item, NBT) combinations across "
                     f"{sum(1 for r in rows if (r.get('nbt') or '').strip())} table entries. "
                     "Describes the loot *tables*, not any particular world.\n\n")
            for kind in sorted(by_kind, key=lambda k: -len(by_kind[k])):
                fh.write(f"## {kind} ({len(by_kind[kind])})\n\n")
                fh.write("| item | description | found in |\n| --- | --- | --- |\n")
                for r in by_kind[kind]:
                    v = f" _(×{r['variants']} variants, identical once colour codes are stripped)_" \
                        if r["variants"] > 1 else ""
                    fh.write(f"| {r['item']} | {r['description']}{v} | {r['found in']} |\n")
                fh.write("\n")
        print(f"wrote {args.md}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
