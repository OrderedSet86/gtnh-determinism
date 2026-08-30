# Cross-version check: daily-707 against template-2.8.4

The portability run required by the report's verification section. Same loot CSV, same script, the
other pack's config directory:

```bash
./scripts/tc-chest-materials.py \
  --loot results/2026-08-29-post-only-loot/chestloot-f9.csv \
  --config ~/.cache/gtnh-determinism/template-2.8.4/config \
  --out <scratch>
```

The run completes and every verification gate passes on 2.8.4 as well, including the arithmetic
check against all 6 assembled tools. No path or identifier in the script is specific to daily-707.

## Material statistics

**No statistic differs.** Both packs resolve the same 21 metadata values to the same materials, and
`harvest_level`, `durability`, `mining_speed`, `attack`, `handle_modifier`, `reinforced`, `shoddy`
and `xp_amount` are identical for every one of them. The material table in the report therefore
applies to both pack versions.

This holds despite the two packs shipping different mod versions — TConstruct 1.14.104 against
1.13.57, IguanaTweaks 2.7.12 against 2.6.6, TGregworks 1.0.33 against 1.0.28. The metadata
assignments in `TGregworks.cfg` and the stat rescaling in IguanaTweaks both landed on the same
numbers.

## Craftability

One difference, in `restrictions.cfg` rather than in any statistic:

| material | daily-707 | template-2.8.4 |
| --- | --- | --- |
| Bone | binding is craftable | binding is **not** craftable |

Bone bindings drop from `TinkerHouse` chests in both packs, because loot generation ignores the
recipe restrictions. On 2.8.4 the chest is the only way to get one; on daily-707 it is a convenience.
Nothing else in the `allowed` lists differs.

## Scope of this check

This compares the two packs' config files against the same loot table. It does not re-derive the loot
table for 2.8.4, so it does not establish that 2.8.4 drops the same parts in the same places. A
routing decision that depends on drop rates rather than on material statistics needs a
`chestloot.csv` exported from the 2.8.4 server.
