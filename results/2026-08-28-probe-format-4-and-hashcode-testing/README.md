# 2026-08-28 — probe format 4, and adversarial identity-hash testing

Three probe changes plus a new launch-variance methodology that is both **cheaper and stronger** than
repeating cold runs.

## 1. Cold runs were a bad launch-variance test

The long-standing assumption — recorded in `WorldgenProbe.java:512` — was that warm runs cannot
substitute for cold ones because identity-hash order is constant within a JVM. That is true, but the
implied converse is false: **identity-hash order is also constant ACROSS JVM launches by default.**

Measured, 8 objects into a `HashMap`, printing iteration order, three separate JVM launches each:

```
mode : run1                run2                run3
  0  : 1,3,5,2,0,4,6,7     3,5,7,1,4,2,6,0     3,5,7,1,4,2,6,0     <- varies
  1  : 3,6,1,0,4,7,2,5     3,6,0,5,2,7,1,4     3,6,0,5,2,7,1,4     <- varies
  3  : 0,1,2,3,4,5,6,7     0,1,2,3,4,5,6,7     0,1,2,3,4,5,6,7     <- fixed, differs from default
  5  : 7,3,4,6,2,0,5,1     7,3,4,6,2,0,5,1     7,3,4,6,2,0,5,1     <- DEFAULT, fixed
```

Modern HotSpot's default (`-XX:hashCode=5`) is a thread-local xorshift with a fixed seed: a
deterministic sequence. What varies in a real pack is the ORDER in which objects first request a
hash, not the algorithm. So two cold runs only catch an F1-class bug if allocation order happens to
shift — hopeful, not adversarial.

**Better: vary it deliberately.** `-XX:+UnlockExperimentalVMOptions -XX:hashCode=3` (incrementing
counter) gives a guaranteed-different, fully deterministic identity-hash assignment. Confirmed
applied via `-XX:+PrintFlagsFinal`: `intx hashCode = 3 {experimental} {command line}`.

Avoid `-XX:hashCode=2` — every identity hash becomes 1, so every HashMap degenerates to a single
bucket and lookups go O(n). In this pack that is brutal.

### Validation against a known F1 bug

F1 is FML's `VillagerRegistry.villageCreationHandlers`, a `HashMap` keyed by `Class` (identity hash);
iteration order decides both RNG draw order and the weighted building list. Seed
`-3312870596887951991` (seedlib: TiC smeltery + 2 workshops 25 blocks from spawn), radius 8, `rows`.

| jar | villages, default vs hashCode=3 | chunks differing |
|---|---|---|
| **stock** (fix jar removed) | **DIFFER** — 57 pieces starting `Church@-350,66,-790` vs 38 pieces starting `ComponentBankerHome@-299,68,-775` | 245 of 289 |
| **with fix jar** | **IDENTICAL** | 59 of 289, none of them blocks |

So the method detects the bug when present and confirms the fix when applied. Two boots per seed
here because it was run cold; a warm daemon pair (`PROBE_JVMFLAGS="-XX:+UnlockExperimentalVMOptions
-XX:hashCode=3"` on one of them) costs **2 boots for N seeds**, fully preserving the warm speedup.

`scripts/probe-queue.sh start` already forwards `PROBE_JVMFLAGS`, so no script change was needed.

## 2. Format 4 probe changes

- **`"u"` — scheduled block updates. ADDED THEN REMOVED, do not re-add.** It was built on the
  reasoning that pending updates are an invisible, persisted divergence channel. Measurement showed
  the content is leaves 6353 / IC2 rubber leaves 4079 / gravel 2201 / BOP leaves 366 / fire 50, and
  only 15 water+lava entries. Since the criterion is whether BLOCK POSITIONS change — already covered
  by `"b"` — a decay tick queued at a different time, or queued in only one world, is not
  interesting. ~99.9% noise for the question being asked. If the meaningful subset is ever wanted (a
  pending FLUID update letting two identical-looking worlds diverge after load), scope it to
  water/lava only.
- **`"o"` — orphaned tile entities.** TEs whose block no longer matches used to be dropped from
  `"t"` entirely as "launch-timing jitter". But `Chunk.writeToNBT` persists them, and
  `diff-region-tes.py` (the ground truth) does not filter them, so the probe could report IDENTICAL
  while the saved worlds differed — precisely the Roguelike chest-carving case. They now go in their
  own bucket: visible as a diff, without polluting `"t"`.
- **Self-delimiting block digest.** The MSB and metadata nibble arrays were fed to SHA-256 only when
  non-null, with no marker, so a section with metadata-but-no-MSB and one with MSB-but-no-metadata
  produced identical streams — a collision by construction. One presence byte each now.
  **This changes `"b"`/`"s"` for every chunk; format 3 is not comparable.**

## 3. What the new instruments found

With the fix jar, default vs `hashCode=3`, radius 8:

| channel | chunks differing (of 289) |
|---|---|
| blocks `"b"` | **0** |
| orphan TEs `"o"` | **0** (none present in this seed) |
| tile entities `"t"` | 2 |

None of that is findable by repeating cold runs, because the default identity-hash order does not
move between launches.

### The scheduled-update differences are leaf-decay delays

Read straight out of the persisted `TileTicks` of both worlds. Block types involved:

```
minecraft:leaves            6353     BiomesOPlenty:leaves2         216
IC2:blockRubLeaves          4079     minecraft:leaves2             150
minecraft:gravel            2201     minecraft:fire                 50
minecraft:flowing_water       11     minecraft:flowing_lava          4
```

Characterised per chunk, the differences are **not** missing or extra updates:

```
chunk (-13,-41): only-default=2  only-hash3=2
  default: BiomesOPlenty:leaves2 @-193,80,-648 t=10   hash3: same position t=9
  default: BiomesOPlenty:leaves2 @-193,81,-646 t=8    hash3: same position t=11
  positions in both with different delay: 2
  positions only in one side: 0
```

**Same positions, same blocks, only the scheduled delay differs.** That is a decay/fall delay drawn
from a nondeterministic RNG at schedule time. Severity is low — leaf decay and gravel falling
converge to the same end state regardless of when the check fires — but it is genuine persisted
state that differs, so a byte-exact world comparison will always show it. Fixing it means chasing
the `scheduleBlockUpdate` delay draws in leaves/gravel, which is broad blast radius for little
gameplay gain. Recorded, not fixed.

## Reproduce

```sh
export PROBE_JAVA=~/.gradle/jdks/azul_systems__inc_-17-amd64-linux.2/bin/java
export PROBE_SEARCH=true
PROBE_EXTRA_ARGS="" PROBE_PORT=25624 \
  scripts/run-probe.sh <dir> -3312870596887951991 rows a.json 8
PROBE_EXTRA_ARGS="-XX:+UnlockExperimentalVMOptions -XX:hashCode=3" PROBE_PORT=25625 \
  scripts/run-probe.sh <dir> -3312870596887951991 rows b.json 8
python3 scripts/diff-probe.py a.json b.json
```

Warm equivalent — two daemons, one with the flag, N seeds each:

```sh
PROBE_JVMFLAGS="-XX:+UnlockExperimentalVMOptions -XX:hashCode=3" \
  scripts/probe-queue.sh start <dir> <ctl-h3>
scripts/probe-queue.sh start <dir2> <ctl-default>
```

To read persisted scheduled updates directly, pull `Level.TileTicks` from the region files —
entries are `{i: blockId, x, y, z, t: delay, p: priority}`.

## IMPORTANT: never generate a corpus under a non-default hashCode

`-XX:hashCode=3` changes worldgen output wherever residual identity-hash dependence still exists.
That is the entire point of the test — but it means the flag is a **detector only**, never a corpus
setting.

Persisted-world diff, fix jar, default vs `hashCode=3`, whole generated world (204,802 TEs):

| TE type | differing |
|---|---|
| `GT_TileEntity_Ores` | 553 |
| `etfuturum.cave_vines` | 3 |
| `forestry.Swarm` | 1 |
| `Chest` | **1** |
| projectred lily | 1 |

The chest at `(-239, 52, -698)` sits next to a `MobSpawner` — a vanilla cave dungeon. It exists in
both worlds with the **same item ids and the same NBT length (325)**; only the arrangement (slots /
counts / damage) differs. So chest LOOT is still identity-hash sensitive in at least one place.

**Protocol:**

- Generate seed-search corpora at the **default** hashCode. That is what players run, and it is
  stable across launches (measured, three launches, identical ordering).
- Use `hashCode=3` only as a second arm to compare against a default arm.
- Never publish or seed-search from a corpus generated under a non-default hashCode.

**Is the corpus wrong today?** No. A corpus built at default hashCode matches what a player at
default hashCode gets. What these 559 differences show is that the corpus is *fragile*: it depends on
allocation order staying put, which is not guaranteed across a different mod set, JVM version, or
anything that perturbs class-loading interleaving. Each difference is a bug to fix, and the ore ones
are the already-known GT ore work.

## ESCALATION: it is not fragility, it is a live bug across supported JVMs

GTNH supports Java 8 through 25. Default identity-hash ordering differs by JVM version — 8 objects
into a HashMap, three launches each:

```
  Java 8  : 3,6,7,5,0,1,2,4   (x3, stable)
  Java 17 : 6,0,5,4,7,2,1,3   (x3, stable)   <- different
  Java 21 : 3,6,7,5,0,1,2,4   (x3, stable)
  Java 25 : 2,7,6,0,1,4,3,5   (x3, stable)   <- different again
```

Three distinct orderings among four supported JVMs. The xorshift sequence is shared; the OFFSET into
it depends on how many identity hashes are requested during startup, which varies by JVM version and
equally by mod set.

Run on the real pack with the current fix jar, seed `-3312870596887951991`, r8, Java 17 vs Java 21:

| channel | differing |
|---|---|
| villages | identical |
| blocks (persisted, common chunks) | **81,156** |
| `GT_TileEntity_Ores` | 1,661 |
| `Chest` contents | **1** (dungeon chest at -239,52,-698) |
| Thaumcraft nodes / Forestry swarms | 2 / 2 |

`BiomesOPlenty:leaves2 <-> air` 23k, `stone <-> deepslate` 13k, `air <-> leaves` 13k.

**So a published corpus is only valid for the JVM that generated it**, and two players on different
supported JVMs get different worlds from the same seed.

**Mechanism caveat.** Identity-hash ordering demonstrably contributes: `-XX:hashCode=3` on a single
JVM reproduces the ore-plus-same-chest signature. But the Java 17-vs-21 block delta is much larger
than the same-JVM hashCode delta, so a second mechanism is not excluded — most plausibly JVM
floating-point or intrinsic differences in the RWG noise path. That needs its own test before
attributing all 81k blocks to identity hashing.

**Testing implication:** the adversarial pair should just be **Java 17 vs Java 21**. Free, realistic,
and exactly a user scenario — strictly better than `-XX:hashCode=3` as a regression gate.
