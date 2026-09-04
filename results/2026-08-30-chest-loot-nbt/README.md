# Chest loot: item descriptions resolved from NBT

Turns the `nbt` column of the chest-loot tables into readable item descriptions — enchantments by
name, Tinkers' tool stats and materials, Forestry genomes, Thaumcraft vis charges — so the tables can
be read outside the game. Result is [nbt-items.md](nbt-items.md), same data as
[nbt-items.csv](nbt-items.csv).

**This describes the loot tables, not any world.** An entry says what the item looks like whenever
that table rolls it. Nothing here is per-seed.

GTNH daily-707, repo `d17a685`, probe `c3963d761f7ba80ea0e4e633441c72ff`.

```sh
PROBE_JVMFLAGS="-Dprobe.lootcsv=$PWD/results/2026-08-30-chest-loot-nbt" \
  ./scripts/warm-probe.sh ~/.cache/gtnh-determinism/daily-707 1 rows /tmp/out-{seed}.json 1

seedsearch/loot-nbt-report.py results/2026-08-30-chest-loot-nbt \
  --materials results/2026-08-29-tc-chest-materials/materials.csv \
  --md results/2026-08-30-chest-loot-nbt/nbt-items.md \
  --csv results/2026-08-30-chest-loot-nbt/nbt-items.csv
```

## Coverage

7024 table entries, **443 carry NBT**, collapsing to **185 distinct (item, description) rows**.
Everything resolves — no unparsed blobs, no unresolved enchantment ids, no unnamed items.

| kind | rows | what gets shown |
| --- | ---: | --- |
| enchanted | 80 | enchantments by name and level, custom name, lore |
| enchanted book | 44 | stored enchantments |
| other | 20 | circuit tiers, track types, farm parts, candle colour, named leather armour |
| Forestry genome | 14 | species and every trait chromosome, analysed flag, health |
| GT tool | 14 | head/handle material, durability, enchantments |
| Tinkers' tool | 6 | part materials, durability, attack, mining speed, harvest level, reinforced |
| knowledge note | 4 | knowledge points |
| vis amulet | 2 | per-aspect charge |
| charged | 1 | stored EU |

Example, matching the in-game tooltip:

> **Diamond Sword** — named "Gambler's Grave" · enchanted: XP Boost III, Looting III — `roguelike/WEAPONS`
>
> **Hylian Shield** — built from head Cobalt, handle Cobalt, accessory Cobalt · durability 3937 / 3937 ·
> attack 4, mining speed 1100, harvest level Manyullyn · reinforced 2 — `roguelike/ARMOUR`

## Weapons and armour

`item-attributes.csv` carries the base numbers the NBT does not: attack damage, armour points,
armour slot and durability, for every (registry name, meta) the tables can roll — 121 items. Read
from the running game (`attackDamage` attribute modifier, `ItemArmor.damageReduceAmount`) rather
than a hardcoded vanilla table, because modded gear would otherwise be silently wrong.

**43 entries deal melee damage.** Hearts are health points halved, matching the tooltip. Sharpness
adds `level × 1.25` against everything; Smite and Bane of Arthropods add `level × 2.5` but only
against undead and arthropods, so they are separate columns. 9 of the 43 carry a conditional bonus —
one Golden Sword is a 2-heart weapon that hits undead for 10.75.

**42 entries are armour, ranked by damage reduction rather than by armour points.** The two
mitigations multiply, so points alone gets the order wrong:

| item | reduction | armour | EPF |
| --- | ---: | ---: | ---: |
| Diamond Leggings | **40.7%** | 6 | 7 |
| Steel Chestplate | 40.7% | 6 | 7 |
| Ruby Chestplate | 38.8% | **8** | 3 |
| Sapphire Chestplate | 32.0% | **8** | 0 |

`EntityLivingBase` applies `damage × (25 − armour)/25`, then `damage × (25 − i)/25`. `i` is not the
raw EPF: `EnchantmentHelper.getEnchantmentModifierDamage` caps EPF at 25 and returns
`ceil(EPF/2) + rand(0 … floor(EPF/2))`, which the caller clamps to 20 — so the enchantment half is
random per hit, and the reported figure is its exact expectation over the roll rather than a mean
substituted into the formula (the clamp makes it non-linear above EPF 20). Checks out against known
values: a full vanilla diamond set (20 armour, 0 EPF) gives 80%, and with EPF 25 gives 94.3%.

Armour points and EPF are **set totals** in game. Scoring one piece as if it were the whole set is a
convention — monotonic in both inputs, so the ranking is sound, but the absolute percentage is only
reached when nothing else is worn. Protection is the only enchantment applying to every damage
source; Fire, Fall, Blast and Projectile are conditional and kept in a separate column. Forge
`ISpecialArmor` items compute protection at damage time and would be understated here — none of this
pack's loot armour is one, but that is a per-pack fact, not a guarantee.

## The enchantment map had to come from the game

`enchantments.csv` (101 entries: id, name, translation key, max level, weight, type) is new — added to
`ChestLootExport` for this report. Enchantment ids are assigned at registration and are pack- and
version-specific: the daily-707 tables use **43 distinct ids and only about half are vanilla**
(`68 Capitalist`, `70 Educational`, `98 Elder Wisdom`, `99 Eldritch Bane`, `100 Magic Touched`,
`126 Bash Weightless`, …). A hardcoded vanilla table would have silently mislabelled the modded half,
so the map is dumped from `Enchantment.enchantmentsList` on a running server. All 43 resolve.

## Two things worth knowing

**11 enchantments appear above their own maximum level.** Loot is not bound by the enchanting-table
cap, so the tables carry Sharpness VII (max 5), Protection VII (max 4), Smite VII (max 5),
Feather Falling VI (max 4), Unbreaking V (max 3), Knockback V (max 2) and five more. If you are
valuing enchanted gear, these are worth more than the craftable ceiling.

Most common in the tables: Sharpness 81 entries, Fortune 30, Unbreaking 28, Protection 21,
Knockback 14, Blast Protection 11, Fire Aspect 11.

**Some entries are listed twice, differing only by a `§f` colour code** in the stored name —
`bonusChest` does this for Starter Hatchet and Starter Pickaxe. They resolve to identical
descriptions, so the report merges them and marks the row `×2 variants` rather than printing the
same line twice.

## What could not be resolved, and why it does not matter

92 of the 443 NBT rows carry the exporter's `<not a plain item, or id not in this build>`
placeholder instead of a display name: 90 are Roguelike's bare `enchanted_book` (no modid, so the
registry lookup fails) and 2 are mod armour pieces this pack does not ship
(`Natura:natura.armor.immpjerkin`, `tinkersdefense:Heater`). The report falls back to the name stored
in the NBT, then to a fixed label for enchanted books, then to the registry name — so every row ends
up named.

TiC part materials come from `results/2026-08-29-tc-chest-materials/materials.csv`; without
`--materials` they degrade to `material 13` rather than `Bronze`, and nothing else changes.

## Files

| file | what |
| --- | --- |
| `nbt-items.md` | the report, grouped by kind |
| `nbt-items.csv` | same rows, plus the raw NBT and variant count |
| `enchantments.csv` | id → name map dumped from the running game |
| `chestloot.csv` | the loot tables this run exported |
| `lootbags.csv` | EnhancedLootBags groups (separate schema, not NBT-resolved) |

`export.log` is gitignored.
