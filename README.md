# gtnh-determinism

Candidate fixes + verification harness for same-seed worldgen nondeterminism in GT: New Horizons (1.7.10).
Companion to the audit report (docs/gtnh-determinism-audit.html) and the `#run-determinism` Discord archive.

## Layout

- `forks/` — working clones of the affected mods, branch `determinism-fixes`, pushed to github.com/OrderedSet86 forks (gitignored here; they are their own repos)
- `jars/` — built candidate jars, ready to drop into a pack instance (gitignored; rebuild via `./gradlew build` in each fork)
- `probe-build/` — **WorldgenProbe**, a tiny server-side mod for headless determinism testing
- `scripts/` — headless runner + diff tool
- `docs/` — audit report

## The fixes (branch `determinism-fixes` in each fork)

| Fork | Finding | Change |
|---|---|---|
| Hodgepodge | F1 | Mixin `MixinVillagerRegistry_DeterministicOrder`: FML's village creation handlers move from identity-hash `HashMap` order (reshuffles every JVM launch → scrambled village layouts) to a class-name-sorted `TreeMap`. Config: `fixVillageHandlerOrder`. |
| WitcheryExtras | F2 | Mixin `WitcheryWorldGeneratorMixin`: Witchery worldgen uses the seeded per-chunk Random instead of clock-seeded `world.rand` (covens, wicker men, shacks, goblin huts + a `Collections.shuffle`). Village walls (tick-time TE build) not yet addressed. |
| GT5-Unofficial | F4 | Vein placement no longer probes live world blocks on the no-overlap path (`NO_OVERLAP_AIR_BLOCK` retry), so vein identity/height can't depend on which chunk of the vein region generates first. |
| Realistic-World-Gen | F6 | Every `rwg.deco` generator forks a private RNG with exactly one draw from the shared decoration stream (terrain-gated early returns can no longer skew later decorations); `DecoBigTree` no longer sizes trees with `Math.random()`. |

Not yet implemented: F3 (Thaumcraft — stage via Salis-Arcana), F5 (Roguelike Dungeons `validLocation` world probes), Witchery walls.

## Headless before/after verification

**WorldgenProbe** activates only when `-Dprobe.order=...` is set. It force-generates a square of chunks around
the origin in a controlled order, writes per-chunk SHA-256 hashes (blocks + metadata + canonicalized tile-entity
NBT) to JSON, and shuts the server down. Same seed + different walk order ⇒ identical JSON iff worldgen is
chunk-order independent. Relaunching the JVM between runs additionally catches per-launch bugs (F1).

```bash
# in a GTNH server install with mods/worldgenprobe-*.jar present:
PROBE_JAVA=/path/to/java17 scripts/run-probe.sh <server-dir> <seed> rows  baseline-rows.json 8
PROBE_JAVA=/path/to/java17 scripts/run-probe.sh <server-dir> <seed> cols  baseline-cols.json 8
scripts/diff-probe.py baseline-rows.json baseline-cols.json
# → swap in jars/ fixes, repeat, compare the diff counts
```

Walk orders: `rows`, `cols`, `rows-reverse`, `spiral`. The world folder is deleted before each run; the seed is
forced via `server.properties` (`level-type=rwg`).

## Caveats

- Fix jars are built from current master branches; pack 2.7.4 ships slightly older versions. Hodgepodge / RWG /
  WitcheryExtras are safe drop-ins; the GT5U jar replaces a fast-moving mod — test it last and separately.
- Even with all four fixes, some block-level variance remains expected (vanilla dungeon edge checks, TF
  `getHeightValue` reads, Thaumcraft F3 unfixed) — the metric is the *reduction* in differing chunks, and
  specifically whether village/structure chunks stabilize.
