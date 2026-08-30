#!/usr/bin/env python3
"""Tinkers' Construct material properties for the materials a player can find in chest loot.

Usage: tc-chest-materials.py --loot <chestloot.csv> --config <pack-config-dir>
                             [--tc-src <TinkerTools.java>] [--fork <TinkersConstruct-src-root>]
                             --out <dir>

Scope: Roguelike Dungeons, plus Forge ChestGenHooks in the "post" phase. Since fix F9 the post
phase is the whole world's table (results/2026-08-29-post-only-loot/README.md), so those two
sources together are the loot a player can reach in a current world.

Outputs, written to <dir>:
  materials.csv     one row per material found as a tool part
  premade-tools.csv one row per assembled tool found in loot, with its NBT stats re-derived
  excluded.csv      every TConstruct-family loot row this report does not treat as a material
  README.md         the report

METHOD

The loot CSV's "meta" column is the Tinkers' Construct MaterialID. Three files map that number to
a material name:

  0-18        forks/TinkersConstruct/.../tools/TinkerTools.java, nested class MaterialID
  201, 202    <config>/tinkersdefense.cfg
  >=1500      <config>/TGregworks.cfg, section materials { material-id { } }

Statistics come from <config>/IguanaTinkerTweaks/MaterialDefaults.cfg, keyed by the material name
lowercased. That file is a runtime dump of the live TConstructRegistry.toolMaterials written by
IguanaTweaks (override/MaterialOverride.java createDefault), so it already carries TGregworks'
GregTech-derived materials and IguanaTweaks' own rescaling of the native ones. Reading the
Tinkers' Construct source instead would give pre-IguanaTweaks numbers, which are wrong for this
pack: IguanaTweaks rewrites the native stats in harvestlevels/TinkerMaterialTweaks.java and scales
durability by main.cfg durabilityPercentage.

COLUMN SEMANTICS

mining_speed is MaterialDefaults' miningSpeed divided by 100, the value the game displays.

shoddy carries two traits in one number: positive is Stonebound (speed rises as the tool wears),
negative is Jagged (damage rises as the tool wears). The "trait" column names it.

xp_amount multiplies the experience REQUIRED to level a tool, so a higher number is worse.

harvest_level indexes a 10-tier ladder, not vanilla's 4 tiers. Tier names are read from the
comment block that IguanaTweaks generates into MaterialOverride.cfg, which is written from the
live HarvestLevels.getHarvestLevelName. HarvestLevelNamesDefaults.cfg in the same directory
disagrees from tier 3 upward; the generated comment is preferred because it reflects the running
game.

exp_* columns are the expected number of that material's parts in one chest of that kind:
mean(rolls_min, rolls_max) * pick_chance_per_roll, summed over the material's rows in that bucket.
Chest rolls draw with replacement from the weighted pool, so this is an expected count and not a
probability. Roguelike levels are separate columns because one chest sits at one level.

ROLES

A material's worth depends on which slot its part fills, so there is no single score. Slot
behaviour is taken from ToolBuilder.buildTool and the per-tool part definitions:

  head           sets base durability, mining speed, harvest level and attack
  head-add       an accessory or extra declared durabilityType 2, which ADDS its durability and
                 attack to the head total and is then averaged with it. Large plates on the
                 hammer, excavator, cleaver and lumber axe, and the shovel head on the mattock
  handle         declared durabilityType 1, contributes handleModifier, which multiplies durability
  trait-only     declared durabilityType 0, contributes nothing but reinforced and shoddy

Reinforced and shoddy propagate from EVERY part regardless of slot: reinforced is the maximum over
the parts and shoddy is their mean. That is the reason a cheap trait-only part can still matter.
"""
import argparse
import csv
import re
import sys
from collections import defaultdict
from pathlib import Path

# --------------------------------------------------------------------------- loot row selection

# Tool parts whose damage value IS a MaterialID. Every one of these is a DynamicToolPart in
# TinkerTools.registerItems / TinkerWeaponry.
PART_ITEMS = {
    "pickaxeHead", "shovelHead", "hatchetHead", "swordBlade", "largeSwordBlade", "knifeBlade",
    "frypanHead", "signHead", "chiselHead", "excavatorHead", "hammerHead", "scytheBlade",
    "toolRod", "toughRod", "binding", "toughBinding", "heavyPlate", "wideGuard", "handGuard",
    "crossbar", "arrowhead", "BowLimbPart", "CrossbowLimbPart",
}

# Assembled tools: the materials live in NBT tags Head/Handle/Accessory/Extra, not in the damage
# value. Reported separately.
TOOL_ITEMS = {"pickaxe", "shovel", "hatchet", "broadsword"}

# Everything else in the TConstruct family that appears in scope, with the reason it is not a
# material row. fletching is the trap: its damage value indexes FletchingMaterial, a different
# registry, so reading it as a MaterialID would invent statistics.
NOT_MATERIALS = {
    "fletching": "damage value indexes FletchingMaterial, a separate registry",
    "materials": "crafting items, damage value is an item index",
    "woodPattern": "pattern shape, damage value is a part index",
    "blankPattern": "no material",
    "heartCanister": "no material",
    "slime.sapling": "no material",
    "slime_boots": "no material",
    "slimesling": "no material",
    "creativeModifier": "no material",
    "helmetWood": "armour, no tool material",
    "chestplateWood": "armour, no tool material",
    "leggingsWood": "armour, no tool material",
    "bootsWood": "armour, no tool material",
}
NOT_MATERIAL_MODS = {
    "IguanaTweaksTConstruct": "not a tool part",
    "tinkersdefense": "not a tool part",
}

# --------------------------------------------------------------------------- part slot roles

# Per-tool composition, transcribed from forks/TinkersConstruct/src/main/java/tconstruct/items/
# tools/*.java: getHeadItem / getHandleItem / getAccessoryItem / getExtraItem, the
# durabilityType* overrides, getDurabilityModifier, and the ToolCore(baseDamage) constructor
# argument. getHandleItem defaults to toolRod (ToolCore.java:471) and durabilityTypeHandle
# defaults to 1, durabilityTypeAccessory and durabilityTypeExtra to 0 (ToolCore.java:94-104).
#
# fields: head, (handle, type), (accessory, type) | None, (extra, type) | None, durability
# modifier, base damage
TOOLS = {
    "Pickaxe":    ("pickaxeHead",     ("toolRod", 1),  ("binding", 0),      None,                 1.0,  1),
    "Shovel":     ("shovelHead",      ("toolRod", 1),  None,                None,                 1.0,  2),
    "Hatchet":    ("hatchetHead",     ("toolRod", 1),  None,                None,                 1.0,  3),
    "Broadsword": ("swordBlade",      ("toolRod", 1),  ("wideGuard", 0),    None,                 1.2,  4),
    "Longsword":  ("swordBlade",      ("toolRod", 1),  ("handGuard", 0),    None,                 1.0,  4),
    "Rapier":     ("swordBlade",      ("toolRod", 1),  ("crossbar", 0),     None,                 0.7,  2),
    "Cutlass":    ("swordBlade",      ("toolRod", 1),  ("fullGuard", 1),    None,                 1.0,  4),
    "Dagger":     ("knifeBlade",      ("toolRod", 1),  ("crossbar", 0),     None,                 1.0,  1),
    "FryingPan":  ("frypanHead",      ("toolRod", 1),  None,                None,                 1.0,  2),
    "BattleSign": ("signHead",        ("toolRod", 1),  None,                None,                 1.0,  1),
    "Chisel":     ("chiselHead",      ("toolRod", 1),  None,                None,                 1.0,  0),
    "Mattock":    ("hatchetHead",     ("toolRod", 1),  ("shovelHead", 2),   None,                 1.0,  3),
    "Hammer":     ("hammerHead",      ("toughRod", 1), ("heavyPlate", 2),   ("heavyPlate", 2),    4.5,  2),
    "Excavator":  ("excavatorHead",   ("toughRod", 1), ("heavyPlate", 2),   ("toughBinding", 1),  2.75, 2),
    "Cleaver":    ("largeSwordBlade", ("toughRod", 1), ("heavyPlate", 2),   ("toughRod", 1),      2.5,  5),
    "Scythe":     ("scytheBlade",     ("toughRod", 1), ("toughBinding", 1), ("toughRod", 1),      3.0,  4),
    "LumberAxe":  ("broadAxeHead",    ("toughRod", 1), ("heavyPlate", 2),   ("toughBinding", 1),  2.5,  0),
    "Battleaxe":  ("broadAxeHead",    ("toughRod", 1), ("broadAxeHead", 2), ("toughBinding", 1),  2.5,  4),
}

# Item registry name for the tools that appear assembled in loot.
TOOL_BY_REGISTRY = {"pickaxe": "Pickaxe", "shovel": "Shovel", "hatchet": "Hatchet",
                    "broadsword": "Broadsword"}

# Bow and crossbow limbs use ToolMaterial for their part identity but draw their bow behaviour from
# a parallel BowMaterial registry that MaterialDefaults.cfg does not dump. Flagged, not scored.
BOW_PARTS = {"BowLimbPart", "CrossbowLimbPart"}

# Tier-2 parts. Crafting these needs a Tool Forge and large amounts of material, so finding one is
# worth much more than finding a tier-1 part of the same material.
TOUGH_PARTS = {"heavyPlate", "toughRod", "toughBinding", "largeSwordBlade", "excavatorHead",
               "hammerHead", "scytheBlade"}

# The subset of those that are heads, so the tough-rod durability ceiling applies to them.
TOUGH_HEADS = {"largeSwordBlade", "excavatorHead", "hammerHead", "scytheBlade"}

# --------------------------------------------------------------------------- bow materials

# BowMaterial is a registry separate from ToolMaterial, keyed by the same MaterialID and holding
# only (drawspeed, flightSpeedMax). No config file dumps it, so the native entries are transcribed
# from weaponry/TinkerWeaponry.java:363-385 and the GregTech ones are recomputed below.
#
# A material with no BowMaterial entry cannot be built into a bow or crossbow at all:
# Crossbow.buildTool returns early (weaponry/weapons/Crossbow.java:391-393) and
# WeaponryHandler.onProjectileWeaponCrafted sets Result.DENY (weaponry/WeaponryHandler.java:213-217).
# Loot generation does not check this, so unusable limbs still drop.
NATIVE_BOW_MATERIALS = {
    0: (18, 3.0), 4: (20, 3.4), 5: (38, 3.0), 8: (28, 4.2), 17: (21, 4.0), 9: (10, 1.1),
    2: (54, 5.2), 16: (60, 5.5), 18: (42, 5.2), 11: (55, 4.9), 10: (45, 5.3), 12: (50, 5.0),
    13: (40, 4.9), 14: (45, 5.1), 15: (45, 5.0), 1: (90, 1.0), 3: (90, 1.0), 6: (109, 1.0),
    7: (80, 1.0), 31: (35, 4.75),
}

# Bowstring materials, the only three that exist: (name, durabilityMod, drawspeedMod, flightMod).
# TinkerWeaponry.java:499-507 and tools/TinkerTools.java:1385-1415.
BOWSTRINGS = {
    0: ("String", 1.0, 1.0, 1.0),
    1: ("Enchanted Fabric", 1.0, 0.8, 0.9),   # also grants +1 modifier
    2: ("Flamestring", 1.2, 1.1, 1.2),
}


def gregtech_bow_material(stats):
    """Recompute a TGregworks BowMaterial from the tool stats the config dump records.

    TGregRegistry derives every stat from the same GregTech mToolQuality with multipliers that are
    all 1.0 in this pack (TGregworks.cfg global block and per-material sections):

        attack         = mToolQuality
        handleModifier = mToolQuality - 0.5
        drawspeed      = max(mToolQuality, 1) * 10
        flightSpeedMax = mToolQuality - 0.5

    So the quality is recoverable from the dump, and flightSpeedMax equals handleModifier exactly.
    Recovering it this way rather than reading GregTech's source keeps the numbers tied to the pack
    that produced the loot table: the GT5-Unofficial checkout gives Magnetic Steel quality 3, while
    this pack's dump gives 2 through both attack and handleModifier.

    Returns None when the two derivations disagree, which happens for materials TGregworks skipped
    because a native material already owned the name.
    """
    quality = stats["attack"]
    if abs(stats["handleModifier"] - (quality - 0.5)) > 1e-6:
        return None
    return max(quality, 1) * 10, quality - 0.5


def build_crossbow(bow, string_id):
    """DrawSpeed in ticks and FlightSpeed, per WeaponryHandler.java:208-243.

    Only the limb (head) and the bowstring (accessory) are read. The body and the tough binding do
    not affect either number. drawSpeed is an int, so each compound assignment truncates.
    """
    drawspeed, flight = bow
    _n, _dur, draw_mod, flight_mod = BOWSTRINGS[string_id]
    d = int(drawspeed * draw_mod)
    d = int(d * 2.5)
    d = int(d - d * 0.25)
    return d, flight * flight_mod * 1.5


# Registry item name -> the part name restrictions.cfg uses. The config file lists its own
# vocabulary in the comment above the "restricted" key ("partnames are: ..."), and the script
# checks this map against that list.
CONFIG_PART_NAME = {
    "toolRod": "rod", "pickaxeHead": "pickaxe", "shovelHead": "shovel", "hatchetHead": "axe",
    "swordBlade": "swordblade", "wideGuard": "largeguard", "handGuard": "mediumguard",
    "crossbar": "crossbar", "binding": "binding", "frypanHead": "frypan", "signHead": "sign",
    "knifeBlade": "knifeblade", "chiselHead": "chisel", "toughRod": "largerod",
    "toughBinding": "toughbinding", "heavyPlate": "largeplate", "scytheBlade": "scythe",
    "excavatorHead": "excavator", "largeSwordBlade": "largeblade", "hammerHead": "hammerhead",
    "arrowhead": "arrowhead", "BowLimbPart": "bowlimb", "CrossbowLimbPart": "crossbowlimb",
}


def part_roles():
    """Map each part item to the set of slot roles it fills across all tools."""
    roles = defaultdict(set)
    for head, handle, acc, extra, _dur, _dmg in TOOLS.values():
        roles[head].add("head")
        for slot in (handle, acc, extra):
            if slot is None:
                continue
            item, dtype = slot
            roles[item].add({0: "trait-only", 1: "handle", 2: "head-add"}[dtype])
    for p in BOW_PARTS:
        roles[p].add("bow-limb")
    roles["arrowhead"].add("head")  # Arrow.getHeadItem, weaponry
    return roles


ROLES = part_roles()

# --------------------------------------------------------------------------- material name lookup

# Localized name as the server writes it into the loot CSV's display_name, per material. Checked in
# rather than fuzzy-matched so that a pack which renames a material fails loudly instead of
# silently attaching the wrong statistics.
DISPLAY_ALIAS = {
    "Wood": "Wooden",
    "PigIron": "Pig Iron",
    "BlueSlime": "Slime",       # collides with Slime (meta 8); the damage value disambiguates
    "Aeonsteel": "Aeon Steel",
    "QueensGold": "Queen's Gold",
    "SteelMagnetic": "Magnetic Steel",
}

COLOR = re.compile("§.")


class Fail(Exception):
    pass


def read_material_ids(tc_src, cfg):
    """meta -> ToolMaterial.materialName, from the three registries that assign the numbers."""
    ids = {}

    block = re.search(r"class MaterialID \{(.*?)\n    \}", Path(tc_src).read_text(), re.S)
    if not block:
        raise Fail(f"no MaterialID class in {tc_src}")
    for name, num in re.findall(r"int (\w+) = (\d+);", block.group(1)):
        ids[int(num)] = name

    td = cfg / "tinkersdefense.cfg"
    if td.exists():
        for name, num in re.findall(r'I:"(\w+) Material ID"=(\d+)', td.read_text()):
            ids[int(num)] = name

    tg = cfg / "TGregworks.cfg"
    if tg.exists():
        sect = re.search(r"\n    material-id \{\n(.*?)\n    \}", tg.read_text(), re.S)
        if sect:
            for name, num in re.findall(r'I:"?([^=\n"]+?)"?=(\d+)', sect.group(1)):
                if int(num):  # 0 means "regenerate", not an assignment
                    ids[int(num)] = name
    return ids


def read_materials(cfg):
    """lowercased materialName -> stat dict, from the IguanaTweaks runtime dump."""
    defaults = cfg / "IguanaTinkerTweaks" / "MaterialDefaults.cfg"
    if not defaults.exists():
        raise Fail(f"missing {defaults}")
    out = {}
    for m in re.finditer(r"\n    ([a-z0-9_.\-]+) \{(.*?)\n    \}", defaults.read_text(), re.S):
        out[m.group(1)] = dict(re.findall(r"[A-Z]:(\w+)=(\S+)", m.group(2)))
    if not out:
        raise Fail(f"parsed no materials from {defaults}")

    override = cfg / "IguanaTinkerTweaks" / "MaterialOverride.cfg"
    if override.exists():
        body = re.search(r"\nmaterials \{(.*?)\n\}", override.read_text(), re.S)
        if body and body.group(1).strip():
            raise Fail(
                f"{override} has a non-empty materials block; it supersedes MaterialDefaults.cfg "
                "and this report would be wrong")
    return out


def read_harvest_names(cfg):
    """Tier index -> tier name, from the comment IguanaTweaks generates at runtime."""
    override = cfg / "IguanaTinkerTweaks" / "MaterialOverride.cfg"
    names = {}
    if override.exists():
        for num, label in re.findall(r"#\s*\t(\d+) - (.+)", override.read_text()):
            label = COLOR.sub("", label).strip()
            label = re.sub(r"^\d+-", "", label)
            names[int(num)] = label
    return names


def read_restricted(cfg):
    """Materials whose part recipes restrictions.cfg rewrites, mapped to their allowed part names.

    A material named in the "allowed" list has ALL of its part recipes cleared and only the listed
    ones restored (restriction/RestrictionConfig.java:108-172). A material absent from the list is
    left untouched, so absence means unrestricted, not banned.
    """
    f = cfg / "IguanaTinkerTweaks" / "restrictions.cfg"
    if not f.exists():
        return {}
    text = f.read_text()

    vocab = re.search(r"partnames are: (.+)", text)
    if vocab:
        known = {p.strip() for p in vocab.group(1).split(",") if p.strip()}
        unknown = set(CONFIG_PART_NAME.values()) - known
        if unknown:
            raise Fail(f"CONFIG_PART_NAME maps to part names {sorted(unknown)} that "
                       f"{f} does not list; the config vocabulary changed")

    allowed = re.search(r"S:allowed <(.*?)>", text, re.S)
    out = defaultdict(list)
    if allowed:
        for line in allowed.group(1).splitlines():
            line = line.strip()
            if ":" in line:
                mat, part = line.split(":", 1)
                out[mat].append(part)
    return out


# --------------------------------------------------------------------------- loot parsing

def in_scope(row):
    return row["source"] == "roguelike" or (row["source"] == "chestgenhooks"
                                            and row["phase"] == "post")


def bucket_of(row):
    if row["source"] == "roguelike":
        return f"rogue_{row['category'] or row['table']}_L{row['level']}"
    return row["category"]


def expected_per_chest(row):
    lo, hi = float(row["rolls_min"]), float(row["rolls_max"])
    chance = float(row["pick_chance_per_roll"] or 0)
    stack = (float(row["stack_min"]) + float(row["stack_max"])) / 2
    return (lo + hi) / 2 * chance * stack


def parse_infitool(nbt):
    tag = re.search(r"InfiTool:\{(.*?)\}", nbt, re.S)
    if not tag:
        return {}
    out = {}
    for k, v in re.findall(r"(\w+):(-?[\d.]+)[bsfLd]?", tag.group(1)):
        out[k] = float(v) if "." in v else int(v)
    return out


# --------------------------------------------------------------------------- tool arithmetic

def build_tool(tool, head, handle, accessory, extra):
    """Reimplements ToolBuilder.buildTool lines 173-265 for the stats stored in NBT.

    head/handle/accessory/extra are stat dicts or None. Returns the same keys the game writes.
    """
    _hd, (_hi, htype), acc_def, extra_def, dur_mod, base_dmg = TOOLS[tool]

    durability = head["durability"]
    heads = 1
    handles = 0
    modifier = 1.0
    attack = head["attack"]

    # ToolBuilder ASSIGNS the modifier in the handle branch and ADDS in the accessory and extra
    # branches, so a type-1 accessory on a tool whose handle is not type 1 accumulates onto the
    # initial 1.0. Mirrored exactly rather than collapsed, so the arithmetic stays right if a tool
    # with a type-2 handle is ever added to TOOLS.
    if handle is not None and htype == 2:
        heads += 1
        durability += handle["durability"]
        attack += handle["attack"]
    elif handle is not None and htype == 1:
        handles += 1
        modifier = handle["handleModifier"]

    for mat, slot_def in ((accessory, acc_def), (extra, extra_def)):
        if mat is None or slot_def is None:
            continue
        if slot_def[1] == 2:
            heads += 1
            durability += mat["durability"]
            attack += mat["attack"]
        elif slot_def[1] == 1:
            handles += 1
            modifier += mat["handleModifier"]

    if handles > 0:
        modifier *= 0.5 + handles * 0.5
        modifier /= handles

    durability = int(durability / heads * (0.5 + heads * 0.5) * modifier * dur_mod)
    attack = attack // heads + base_dmg
    if attack % heads != 0:
        attack += 1

    present = [m for m in (head, handle, accessory, extra) if m is not None]
    return {
        "BaseDurability": durability,
        "MiningSpeed": head["miningSpeed"],
        "Attack": attack,
        "HarvestLevel": head["harvestLevel"],
        "Unbreaking": max(m["reinforced"] for m in present),
        "Shoddy": round(sum(m["shoddy"] for m in present) / len(present), 4),
    }


# IguanaTweaks subtracts one harvest level from picks and hammers at craft time; the tool earns it
# back once through a separate mining-experience bar. leveling/LevelingLogic.java:112-122, gated on
# main.cfg pickaxeBoostRequired.
BOOST_TOOLS = {"Pickaxe", "Hammer"}


def pickaxe_boost_required(cfg):
    main = cfg / "IguanaTinkerTweaks" / "main.cfg"
    if not main.exists():
        return False
    return bool(re.search(r"B:pickaxeBoostRequired=true", main.read_text()))


# --------------------------------------------------------------------------- report

def num(stats):
    return {
        "harvestLevel": int(stats["harvestLevel"]),
        "durability": int(stats["durability"]),
        "miningSpeed": int(stats["miningSpeed"]),
        "attack": int(stats["attack"]),
        "handleModifier": float(stats["handleModifier"]),
        "reinforced": int(stats["reinforced"]),
        "shoddy": float(stats["shoddy"]),
        "xpAmount": float(stats.get("xpAmount", 1.0)),
    }


def trait_of(shoddy):
    if shoddy > 0:
        return f"Stonebound {shoddy:g}"
    if shoddy < 0:
        return f"Jagged {abs(shoddy):g}"
    return ""


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--loot", required=True, type=Path)
    ap.add_argument("--config", required=True, type=Path)
    ap.add_argument("--fork", type=Path,
                    default=Path(__file__).resolve().parent.parent / "forks" / "TinkersConstruct")
    ap.add_argument("--tc-src", type=Path)
    ap.add_argument("--out", required=True, type=Path)
    a = ap.parse_args()

    tc_src = a.tc_src or (a.fork / "src/main/java/tconstruct/tools/TinkerTools.java")
    cfg = a.config

    ids = read_material_ids(tc_src, cfg)
    mats_raw = read_materials(cfg)
    hnames = read_harvest_names(cfg)
    restricted = read_restricted(cfg)
    boost = pickaxe_boost_required(cfg)

    rows = [r for r in csv.DictReader(a.loot.open()) if in_scope(r)]

    part_rows, tool_rows, excluded = [], [], []
    for r in rows:
        mod, _, item = r["registry_name"].partition(":")
        if mod == "TConstruct":
            if item in PART_ITEMS:
                part_rows.append(r)
            elif item in TOOL_ITEMS:
                tool_rows.append(r)
            elif item in NOT_MATERIALS:
                excluded.append((r, NOT_MATERIALS[item]))
            else:
                raise Fail(f"unclassified TConstruct item {r['registry_name']} "
                           f"({r['display_name']}); add it to PART_ITEMS, TOOL_ITEMS or "
                           "NOT_MATERIALS rather than letting it drop silently")
        elif mod in NOT_MATERIAL_MODS:
            excluded.append((r, NOT_MATERIAL_MODS[mod]))

    # ---- resolve materials and cross-check every name against the server's own display string
    stats, seen_parts, buckets = {}, defaultdict(set), defaultdict(lambda: defaultdict(float))
    suffixes = defaultdict(set)
    problems = []
    for r in part_rows:
        meta = int(r["meta"])
        item = r["registry_name"].split(":")[1]
        name = ids.get(meta)
        if name is None:
            problems.append(f"meta {meta} ({r['display_name']}) maps to no material name")
            continue
        key = name.lower()
        if key not in mats_raw:
            problems.append(f"meta {meta} -> {name}: no '{key}' block in MaterialDefaults.cfg")
            continue

        shown = COLOR.sub("", r["display_name"]).strip()
        expect = DISPLAY_ALIAS.get(name, name)
        if not shown.startswith(expect + " "):
            problems.append(f"meta {meta} -> {name}: display name {shown!r} does not start with "
                            f"{expect!r}; add or correct a DISPLAY_ALIAS entry")
            continue
        suffixes[item].add(shown[len(expect) + 1:])

        if int(r["stack_min"]) != 1 or int(r["stack_max"]) != 1:
            problems.append(f"{r['registry_name']}#{meta} drops in stacks of "
                            f"{r['stack_min']}-{r['stack_max']}; the per-chest expectation assumes 1")

        stats[meta] = num(mats_raw[key])
        stats[meta]["name"] = name
        stats[meta]["display"] = expect
        stats[meta]["key"] = key
        seen_parts[meta].add(item)
        buckets[meta][bucket_of(r)] += expected_per_chest(r)

    for item, sfx in sorted(suffixes.items()):
        if len(sfx) != 1:
            problems.append(f"{item} display names disagree on the part suffix: {sorted(sfx)}; "
                            "one of its material prefixes is being mis-stripped")

    if problems:
        for p in problems:
            print(f"FAIL: {p}", file=sys.stderr)
        return 1

    # ---- assembled tools: re-derive the NBT the server wrote
    tool_out, mismatches = [], []
    for r in tool_rows:
        item = r["registry_name"].split(":")[1]
        tool = TOOL_BY_REGISTRY[item]
        tags = parse_infitool(r["nbt"])
        if not tags:
            problems.append(f"{r['registry_name']} in {r['category']} has no InfiTool NBT")
            continue

        slots = {}
        for tag, slot in (("Head", "head"), ("Handle", "handle"),
                          ("Accessory", "accessory"), ("Extra", "extra")):
            if tag not in tags:
                slots[slot] = None
                continue
            meta = tags[tag]
            key = (ids.get(meta) or "").lower()
            if key not in mats_raw:
                problems.append(f"{r['registry_name']} {tag} meta {meta} has no material")
                slots[slot] = None
            else:
                slots[slot] = num(mats_raw[key])
                slots[slot]["name"] = ids[meta]

        if slots["head"] is None:
            continue
        got = build_tool(tool, slots["head"], slots["handle"], slots["accessory"], slots["extra"])
        if boost and tool in BOOST_TOOLS and got["HarvestLevel"] > 0:
            got["HarvestLevel"] -= 1

        checked = ("BaseDurability", "MiningSpeed", "Attack", "HarvestLevel", "Unbreaking")
        bad = [k for k in checked if k in tags and tags[k] != got[k]]
        if bad:
            mismatches.append(f"{r['registry_name']} in {r['category']}: "
                              + ", ".join(f"{k} nbt={tags[k]} computed={got[k]}" for k in bad))

        tool_out.append({
            "registry_name": r["registry_name"], "tool": tool, "source": r["source"],
            "category": r["category"], "display_name": COLOR.sub("", r["display_name"]),
            "weight": r["weight"], "pick_chance_per_roll": r["pick_chance_per_roll"],
            "exp_per_chest": f"{expected_per_chest(r):.4f}",
            "head_meta": tags.get("Head", ""), "head_material": (slots["head"] or {}).get("name", ""),
            "handle_meta": tags.get("Handle", ""),
            "handle_material": (slots["handle"] or {}).get("name", ""),
            "accessory_meta": tags.get("Accessory", ""),
            "accessory_material": (slots["accessory"] or {}).get("name", ""),
            "nbt_durability": tags.get("BaseDurability", ""),
            "nbt_mining_speed": tags.get("MiningSpeed", ""),
            "nbt_attack": tags.get("Attack", ""),
            "nbt_harvest_level": tags.get("HarvestLevel", ""),
            "nbt_unbreaking": tags.get("Unbreaking", ""),
            "calc_durability": got["BaseDurability"], "calc_mining_speed": got["MiningSpeed"],
            "calc_attack": got["Attack"], "calc_harvest_level": got["HarvestLevel"],
            "calc_unbreaking": got["Unbreaking"],
            "matches_nbt": "no" if bad else "yes",
        })

    if problems or mismatches:
        for p in problems + mismatches:
            print(f"FAIL: {p}", file=sys.stderr)
        return 1

    # ---- derived role columns
    rod_mods = [s["handleModifier"] for m, s in stats.items() if "toolRod" in seen_parts[m]]
    tough_mods = [s["handleModifier"] for m, s in stats.items() if "toughRod" in seen_parts[m]]
    best_rod = max(rod_mods) if rod_mods else 1.0
    best_tough = max(tough_mods) if tough_mods else 1.0

    bucket_cols = sorted({b for m in buckets for b in buckets[m]})

    a.out.mkdir(parents=True, exist_ok=True)
    mat_csv = a.out / "materials.csv"
    fields = ["meta", "material", "display_name", "config_key", "harvest_level",
              "harvest_level_name", "durability", "mining_speed", "attack", "handle_modifier",
              "reinforced", "shoddy", "trait", "xp_amount", "roles", "parts_found", "n_parts",
              "as_head", "as_head_add", "as_handle", "as_trait_only", "as_bow_limb", "tough_tier",
              "durability_x_best_rod", "durability_x_best_tough_rod", "rod_gain_pct",
              "reinforced_donor", "parts_restricted", "restricted_to",
              "found_not_craftable"] + [f"exp_{b}" for b in bucket_cols]

    table = []
    for meta in sorted(stats):
        s = stats[meta]
        parts = sorted(seen_parts[meta])
        roles = sorted({role for p in parts for role in ROLES.get(p, {"unknown"})})
        rec = {
            "meta": meta, "material": s["name"], "display_name": s["display"],
            "config_key": s["key"], "harvest_level": s["harvestLevel"],
            "harvest_level_name": hnames.get(s["harvestLevel"], ""),
            "durability": s["durability"], "mining_speed": f"{s['miningSpeed'] / 100:g}",
            "attack": s["attack"], "handle_modifier": f"{s['handleModifier']:.4g}",
            "reinforced": s["reinforced"], "shoddy": f"{s['shoddy']:g}",
            "trait": trait_of(s["shoddy"]), "xp_amount": f"{s['xpAmount']:g}",
            "roles": ";".join(roles), "parts_found": ";".join(parts), "n_parts": len(parts),
            "as_head": "yes" if "head" in roles else "",
            "as_head_add": "yes" if "head-add" in roles else "",
            "as_handle": "yes" if "handle" in roles else "",
            "as_trait_only": "yes" if "trait-only" in roles else "",
            "as_bow_limb": "yes" if "bow-limb" in roles else "",
            "tough_tier": ";".join(sorted(set(parts) & TOUGH_PARTS)),
            "durability_x_best_rod": (int(s["durability"] * best_rod) if "head" in roles else ""),
            "durability_x_best_tough_rod": (int(s["durability"] * best_tough)
                                            if set(parts) & TOUGH_HEADS else ""),
            "rod_gain_pct": (f"{(s['handleModifier'] - 1) * 100:+.0f}"
                             if "handle" in roles else ""),
            "reinforced_donor": "yes" if s["reinforced"] > 0 else "",
            "parts_restricted": "yes" if s["name"] in restricted else "",
            "restricted_to": ";".join(restricted.get(s["name"], [])),
            # Parts that drop in chests but that restrictions.cfg has made uncraftable for this
            # material. Loot generation ignores the recipe restrictions, so these are obtainable
            # only by finding them.
            "found_not_craftable": ";".join(
                sorted(p for p in parts
                       if CONFIG_PART_NAME[p] not in restricted[s["name"]])
            ) if s["name"] in restricted else "",
        }
        for b in bucket_cols:
            v = buckets[meta].get(b, 0.0)
            rec[f"exp_{b}"] = f"{v:.4f}" if v else ""
        table.append(rec)

    with mat_csv.open("w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=fields)
        w.writeheader()
        w.writerows(table)

    tool_csv = a.out / "premade-tools.csv"
    with tool_csv.open("w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(tool_out[0].keys()))
        w.writeheader()
        w.writerows(sorted(tool_out, key=lambda t: (t["category"], t["tool"])))

    # ---- crossbow limbs: a separate registry, so a separate table
    bow_csv = a.out / "crossbow-limbs.csv"
    bow_fields = ["meta", "material", "buildable", "bow_drawspeed", "bow_flight_speed",
                  "draw_ticks", "draw_seconds", "flight_speed", "shots_per_min",
                  "flight_per_second", "limb_durability", "reinforced", "source_of_bow_material",
                  "limb_craftable", "notes"]
    bow_rows = []
    for meta in sorted(stats):
        if "CrossbowLimbPart" not in seen_parts[meta]:
            continue
        s = stats[meta]
        bow = NATIVE_BOW_MATERIALS.get(meta)
        origin = "TinkerWeaponry literal"
        if bow is None:
            bow = gregtech_bow_material(s)
            origin = "recomputed from GregTech tool quality" if bow else "none registered"

        # restrictions.cfg clears every recipe for a listed material and restores only what it
        # lists, so a limb is craftable unless the material is listed without "crossbowlimb".
        allowed = restricted.get(s["name"])
        craftable = "yes" if allowed is None else ("yes" if "crossbowlimb" in allowed else "no")

        rec = {"meta": meta, "material": s["display"], "reinforced": s["reinforced"],
               "limb_durability": s["durability"], "source_of_bow_material": origin,
               "limb_craftable": craftable}
        if bow is None:
            rec.update({k: "" for k in bow_fields if k not in rec})
            rec["buildable"] = "no"
            rec["notes"] = ("no BowMaterial registered; the crossbow craft is denied and the "
                            "looted limb is unusable")
        else:
            ticks, flight = build_crossbow(bow, 0)
            rec.update({
                "buildable": "yes", "bow_drawspeed": bow[0],
                "bow_flight_speed": f"{bow[1]:g}", "draw_ticks": ticks,
                "draw_seconds": f"{ticks / 20:.2f}", "flight_speed": f"{flight:g}",
                "shots_per_min": f"{1200 / ticks:.1f}",
                "flight_per_second": f"{flight / (ticks / 20):.3f}",
                "notes": "" if craftable == "yes" else "loot-only; the limb cannot be crafted",
            })
        bow_rows.append(rec)

    with bow_csv.open("w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=bow_fields)
        w.writeheader()
        w.writerows(sorted(bow_rows, key=lambda r: (r["buildable"] == "no",
                                                    -float(r["flight_per_second"] or 0))))

    exc_csv = a.out / "excluded.csv"
    with exc_csv.open("w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["registry_name", "meta", "display_name", "source", "category", "level",
                    "reason"])
        for r, why in sorted(excluded,
                             key=lambda x: (x[0]["registry_name"], int(x[0]["meta"]),
                                            x[0]["level"])):
            w.writerow([r["registry_name"], r["meta"], COLOR.sub("", r["display_name"]),
                        r["source"], r["category"] or r["table"], r["level"], why])

    print(f"{len(table)} materials, {len(tool_out)} assembled tools, "
          f"{len(excluded)} excluded rows, {len(part_rows)} part rows")
    print(f"best rod handle modifier {best_rod:g}, best tough rod {best_tough:g}")
    unusable = [r["material"] for r in bow_rows if r["buildable"] == "no"]
    print(f"{len(bow_rows)} crossbow limb materials"
          + (f", {len(unusable)} unusable ({', '.join(unusable)})" if unusable else ""))
    print(f"wrote {mat_csv}, {tool_csv}, {bow_csv}, {exc_csv}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Fail as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)
