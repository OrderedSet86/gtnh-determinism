# Re-verifying chest loot against the current jar

GTNH daily-707, fix jar md5 `a1da08af02c2b7e305cfab96f764d30f`, probe md5 `37d25a3e95e3a43bfa14e55798479ce7`.

F10's derivation changed three times on 2026-08-29 — the `StructureStartPartsMixin` general site hook,
the `ly = 0` correction for box-relative sites, and the `ChunkPopulateBarrierMixin` fix for chests
filled during nested chunk population. Route determinism was re-checked after each, but the standing
multi-seed and table-provenance evidence predated all of it.

## Chest determinism, 10 seeds, two JVMs

Two separate `warm-probe.sh` invocations — two processes, so identity-hash state differs between them
— radius 8, rows, same 10 seeds.

```
total chests: A=536 B=536
existence differences: 0
contents  differences: 0
NBT-only  differences: 0
VERDICT: ALL SEEDS IDENTICAL
```

**536 chests, 3,929 item stacks, 0 / 0 / 0.** Per seed: 124, 75, 75, 54, 53, 52, 50, 20, 17, 16.
Counted non-empty before trusting the verdict — a chest diff over zero chests reports success.

### What this does and does not cover

Two warm invocations *are* two JVMs, so a nondeterminism that depends on identity-hash iteration order
— constant within a process — would differ between the arms and be caught.

What it cannot catch is a source the warm harness's static reset happens to normalise: the reset forces
a canonical state per seed that a genuinely fresh boot might not have. `docs/harness-speed.md` keeps
cold JVMs as the standing requirement for **stock** launch-variance testing for that reason. With the
fix jar installed, warm is the sanctioned mode for seed and order testing, which is what this is.

## chest-sites.json, re-derived on an uncontaminated corpus

The village chest module's piece-to-chest-site table was built from a `-Dgtnhdet.chesttrace` corpus
recorded before trace scoping existed, so it contained boot-world lines it could not identify — 31 of
91 chest lines on one seed. Re-traced over the same 7 seeds with scoping active (zero boot-world
lines), the table differs by exactly two entries, **both removals**:

| | shipped | clean |
| --- | ---: | ---: |
| site rows | 81 | 80 |
| chestless entries | 135 | 134 |

- `ComponentShack` mode 2, `dungeonChest`, local `(1,1,2)` — a site only ever observed in the boot world.
- `PlainsStructures$PlainsTemple4` mode 0 — a piece only ever observed generating in the boot world.

**No row was wrong.** Both are valid measurements of piece behaviour; they were simply derived from a
world outside the analysis. The contamination only ever *added* out-of-scope samples, which is why the
module's accuracy was unaffected.

The clean table is now the shipped one. That is a provenance decision rather than a correctness one:
keeping the extra rows would give slightly wider coverage, but data from an unlabelled source is what
caused the problem in the first place. The cost is that the module will report `ComponentShack` mode 2
as an unknown piece instead of predicting its chest — under-prediction, which the run announces, rather
than a silent wrong answer. Widening the seed corpus recovers it legitimately.

### The golden result is unchanged

Village chest module against a freshly generated 7-seed corpus, clean table:

```
predicted chest positions      : 95
  present in the corpus        : 95
  predicted but ABSENT         : 0
contents identical at matched  : 95 of 95
NBT identical at those         : 95 of 95
```

## Incidental

Passing all seeds to one `warm-probe.sh` invocation instead of one per seed does the 7-seed trace in a
single boot: **158 s for 10 seeds at radius 8**, against ~50 s per seed when each pays its own boot.
Most of this session's measurement runs were made the slow way.
