# gtnh-determinism

Same-seed world generation for GT: New Horizons (1.7.10), made reproducible.
Companion to the audit report (docs/gtnh-determinism-audit.html) and the `#run-determinism` Discord archive.

## The testing jar (for the community)

**`jars/gtnhdeterminism-0.1.jar` — drop it into `mods/` of a stock GTNH 2.7.4 instance. No other changes.**

One jar carries every fix (reflection fix for FML + 13 late mixins, each gated on its target mod being present):

| Target | What was wrong | What the jar fixes |
|---|---|---|
| Forge/FML | Village building handlers iterate in per-launch HashMap order | Village layouts (smeltery/blacksmith presence) identical per seed |
| Witchery | Worldgen rolled off clock-seeded `world.rand` + shuffle | Covens, wicker men, shacks, goblin huts seed-stable |
| Thaumcraft | Terrain-gated draw skew, `world.rand` barrow loot, gen-thread race | Nodes, totems, barrows, rings + their loot seed-stable |
| GregTech | Vein retry probed live terrain (chunk-order avalanche) | Ore vein identity/height seed-pure |
| RWG | Terrain-gated draw skew in all decorations; `Math.random()` big trees | Tree/decoration streams stable |
| TinkersConstruct | Slime islands sized/shaped by a clock-seeded field (`rand`/`random` shadowing bug) | Slime islands seed-stable |
| BiomesO'Plenty | Flora picked with `Math.random()` over an identity-ordered HashMap; desynced dirt/gravel patches | Flora + patches seed-stable |
| ProjectRed | Lily colors (dye yield!) rolled clock-random at worldgen | Lily colors derived from seed+position |
| Forestry | Village bee house rolled bee species/frames/flowers off `world.rand` | Village bees seed-stable |

Verified headless on GTNH 2.7.4 (fresh-JVM launch pairs, 289 chunks hashed per run):
seed -5093808211664363778 **IDENTICAL** with the single jar alone; across a 13-seed sample, remaining
variance is tile-entity bookkeeping jitter (no block, ore-type, or chest-content differences) plus one
rare unidentified sky-height event still under investigation.

Heads-up when adopting: the fixes change how randomness is derived, so a seed generates a *different*
(now canonical) world than stock — old seed notes reset once, then hold forever. Existing saves are safe
(only newly generated chunks are affected).

## Layout

- `tcfix-build/` — source of the `gtnhdeterminism` jar (mod id `gtnhdeterminism`)
- `probe-build/` — **WorldgenProbe**, headless determinism tester (inert without `-Dprobe.order`)
- `scripts/` — `run-probe.sh`, `diff-probe.py`, `multiseed-driver.sh`
- `forks/` — mod forks with source-level fixes for upstream PRs (branch `determinism-fixes`; pushed to github.com/OrderedSet86)
- `docs/` — audit report + user impact
- `jars/` — the testing jar + probe

## Headless verification

```bash
PROBE_JAVA=/path/to/java17 scripts/run-probe.sh <server-dir> <seed> rows a.json 8
PROBE_JAVA=/path/to/java17 scripts/run-probe.sh <server-dir> <seed> rows b.json 8   # fresh JVM = launch test
scripts/diff-probe.py a.json b.json                                                  # expect IDENTICAL
```
Walk orders `rows|cols|rows-reverse|spiral` test chunk-order dependence; same-order pairs across
launches test clock/hash-order sources. `PROBE_TEDETAIL=true` dumps per-TE hashes; `PROBE_DUMP=x,z`
dumps a chunk's block listing.
