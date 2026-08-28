# GTNH Worldgen Determinism

Source of the `gtnhdeterminism` jar. Minecraft 1.7.10, for GT: New Horizons 2.7.4+.

The jar makes world generation a pure function of the world seed: the same seed produces the same world on every
launch, and by every approach route. The repo root README lists all 28 fixes, the evidence behind them, and the
known remaining variance. Read it first — this file covers only how the module is built and laid out.

## What lives here

* `com.gtnhspeedrun.determinism` — `GtnhDeterminism`, the `@Mod` entry point, carrying the two reflection
  patches: the FML village-handler ordering fix and the loot-table session-drift fix. Also `LateMixinLoader`.
* `com.gtnhspeedrun.determinism.worldgen` — support classes shared by the mixins, including the virgin-terrain
  oracle and the per-chunk slice queue.
* `com.gtnhspeedrun.determinism.mixins.worldgen` — the 29 worldgen mixins.

The worldgen mixins target other mods, so they are `remap = false` and load late, through `LateMixinLoader`
gated on the mods actually present. Their names in that class are relative to the `package` field of
`mixins.gtnhdeterminism.late.json`, so moving the package needs no change to the list. The early config
`mixins.gtnhdeterminism.json` carries empty arrays and exists to anchor the manifest `MixinConfigs` entry.

Client quality-of-life fixes are deliberately not in this jar. They live in `../qol-build`
(`gtnhspeedrunqol`), because this jar re-rolls every seed and the two must be adoptable independently.

## Switches

| Property | Effect |
| --- | --- |
| `-Dgtnhdet.traceseg=true` | Add the three Roguelike segment-trace mixins and log slice activity. |
| `-Dgtnhdet.atomicdungeon=false` | Disable the atomic dungeon-slice window. |

## Building

Build through the repo script, which builds clean without the Gradle configuration cache, verifies that every
source class reached the jar, and prints the md5:

```bash
../scripts/build-jar.sh fix
```

Build clean matters here: the configuration cache has served a stale jar before, reporting BUILD SUCCESSFUL
with a newly added source file silently not compiled.
