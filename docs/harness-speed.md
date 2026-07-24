# Harness speed: designs for amortizing the 82 s boot

Measured baseline (2026-07-23, 16 cores / 60 GB, server on tmpfs, CoreTweaks transformer
cache active): **~90 s per probe run — ~7 s JVM+LaunchWrapper, ~75 s FML mod load,
~8 s "Preparing level" (spawn chunks), ~2 s probe generation (121 chunks), ~3 s shutdown.**
Boot:work is 40:1, so throughput work targets the boot, not the generation.

Scope decision (2026-07-23): build **B (warm multi-seed JVM)** and **D (CRIU
checkpoint/restore)** now; **A (parallel fleet)** and **C (Amidst-style prefilter)** are
designed below but deferred. Seed-search requirements that shaped this: spawn-area
features, GT veins, **complete chest inventories out to ~15 chunks** (tier-skip loot),
nearby water + clay — chest contents need real chunk generation, so per-seed full gen is
unavoidable and biome-only prefiltering cannot answer the main question.

Standing constraint: stock **launch-variance** tests need fresh cold JVMs (identity-hash
state is constant within a JVM — and within a CRIU image). Warm/CRIU modes are for seed
search and order tests **with the fix jar installed**.

All MC/Forge line references below are the decompiled Forge 10.13.4.1614 tree at
`probe-build/build/rfg/minecraft-src/java/` ("MCSRC").

---

## A. Parallel run driver (deferred)

### A.1 Port binding: mandatory, unique per instance
`DedicatedServer.startServer()` (MCSRC `DedicatedServer.java:166-192`) unconditionally
binds via `NetworkSystem.addLanEndpoint` and returns false on bind failure — world load
never happens. No vanilla skip flag.
- Each clone gets `server-port=<25600+idx>`, `server-ip=127.0.0.1`.
- Worth one smoke test: `server-port=0` may give an OS-assigned ephemeral port (nothing in
  `startServer` validates beyond `< 0`); if it works, port management disappears. Indexed
  scheme is the provably safe default.
- `enable-query`/`enable-rcon` off (default) — no other listeners.

### A.2 tmpfs clones with `cp -al`, and the truncate-in-place trap
Template `server-template/` (extracted pack + probe/fix jars, ~608 M); clone with
`cp -al` (<1 s, ~0 bytes). **A hardlink shares the inode: any in-place truncate+write in
one clone corrupts the template and every clone.** Known in-place writers: our own
`cat > server.properties` / `echo > eula.txt` (must `rm -f` first — rm breaks the link),
and Forge `Configuration.save()` (writes configs in place).

Per-clone layout:
- hardlinked (read-only at runtime): `mods/`, `libraries/`, `lwjgl3ify-forgePatches.jar`,
  `java9args.txt`;
- real copies: `config/`, `serverutilities/` and similar small mutable dirs;
- fresh per job: `World/`, `logs/`, `crash-reports/`, `server.properties`, `eula.txt`,
  `usercache.json`, `banned-*.json`, `ops.json`;
- template ships without `logs/`, `World/`, `server.properties`, `eula.txt`.

One-off audit after the first clone run: `touch stamp; <run>; find server-template -newer
stamp -type f` — any hit names a writer that must move to the copy set.

### A.3 Heap sizing
Boot + 625 spawn chunks + 729 probe chunks is far smaller than gameplay load; expect 4 G
to work but benchmark {4, 4.5, 5, 6} G with `-Xlog:gc` before fixing N. Per-instance RSS ≈
Xmx + ~1.2-1.5 G (metaspace for 209 mods, code cache, GC/JIT/native, Netty). Starting
point: 5 instances × `-Xmx4G -Xms4G -XX:MaxMetaspaceSize=768M`, N configurable; per
instance `-XX:ActiveProcessorCount=4 -XX:ParallelGCThreads=4 -XX:ConcGCThreads=1` on 16
cores. Optional boot shave: mixin no-oping `MinecraftServer.initialWorldChunkLoad`
(MCSRC `MinecraftServer.java:282-314`) when probing (~10 s/run; probe re-walks its own
square regardless).

### A.4 Driver
Jobs file (`seed<TAB>order[<TAB>radius]`), `flock`-cursor dispatch for resume support.
Worker i owns `inst-$i` for its lifetime; between jobs `rm -rf World logs crash-reports;
rm -f server.properties eula.txt` then regenerate. Launch under `setsid timeout -k 30 900`
with `-Dprobe.instance=$i` as a cmdline marker. Success = exit 0 + JSON exists + parses +
(2r+1)^2 entries (reuse diff-probe.py loader as `--validate`); append to `jobs.done`.
Failure: archive logs to `failed/`, wipe world, retry once, then `jobs.failed`.

### A.5 Per-clone guard (replaces the global pgrep gate in run-probe.sh)
1. `exec 9>"inst-$i/.guard"; flock -n 9 || abort` (kernel-released on crash);
2. orphan sweep at worker start: `pgrep -f "probe.instance=$i"` → kill (only remaining
   pgrep, instance-scoped);
3. `setsid` + EXIT trap `kill -- -$pgid` so Ctrl-C never leaks servers.

### A.6 Risks
Hardlink corruption via unanticipated writer (audit + config copy); memory overcommit →
OOM-killer (N=5 default + abort dispatch if MemAvailable < 4 G); parallel runs
individually ~20-40 % slower (throughput still ~5-6×).

---

## B. Warm-JVM multi-seed mode (built)

### B.1 Why a new WorldServer is a complete worldgen reset
`MinecraftServer.loadAllWorlds` (MCSRC `MinecraftServer.java:240-280`) just constructs
objects: fresh `AnvilSaveHandler` → `loadWorldInfo()` null on empty dir → `WorldSettings`
→ `new WorldServer(...)` (ctor calls `DimensionManager.setWorld(0, this)`,
`WorldServer.java:137`) → per static dim a `WorldServerMulti` → `WorldEvent.Load` →
`setPlayerManager`. The seed lives only in `WorldInfo`; RWG creates
`ChunkManagerRealistic`/`ChunkGeneratorRealistic` per world from `world.getSeed()`.

Dies with the world (no action needed): structure maps (fields on the RWG generator,
`ChunkGeneratorRealistic.java:58-60,123-125`), ForgeChunkManager tickets (weak-keyed +
cleared on Unload via `ForgeInternalHandler:74-79`), VillageCollection / scoreboard /
mapStorage (keyed by save-handler identity, `World.java:334-346`), `worldTickTimes`
(handled in `DimensionManager.setWorld`).

Rejected alternative — registering a throwaway dimension that mimics the overworld:
pack worldgen is riddled with `dimensionId == 0` gates (TC blacklist, GT
`DimensionDef`/oregen-pattern, Witchery, most IWorldGenerators). Dim-0 recycling reuses
the exact vanilla path; AmidstGTNH does live dim-0 teardown the same way
(`ForgeAmidst.java:111-121`).

### B.2 Per-seed cycle (server thread, inside the FMLServerStartedEvent handler)
1. Teardown = `stopServer()` minus saving: per world post `WorldEvent.Unload`,
   `flush()`, `DimensionManager.setWorld(dim, null)`; drain
   `ThreadedFileIOBase.threadedIOInstance.waitForFinish()` (`ThreadedFileIOBase.java:81`);
   assert Forge `ChunkIOExecutor` queue empty (probe loads synchronously); delete the save
   dir from the old handler's `getWorldDirectory()`.
2. Run the `StaticResetRegistry` (B.3).
3. Recreate: replicate the `loadAllWorlds` body (it's protected; ~15 lines — see B.1),
   `WorldType.parseWorldType("rwg")`, **all** `DimensionManager.getStaticDimensionIDs()`
   recreated (else `WorldServerMulti` parents to the dead overworld's map storage).
   Skip `initialWorldChunkLoad()`; note the `WorldServer` ctor still runs
   `createSpawnPosition` — required for parity and for spawn-pos search.
4. Probe + JSON per seed; `initiateShutdown()` after the last.

### B.3 Static reset registry
Reflection-based, class-presence-gated, logs every action, hard-fails if a listed field is
missing (schema drift must be loud).

| Target | Action |
|---|---|
| TC `MazeHandler` (global maze map, feeds `mazesInRange`) | clear all static collection fields |
| TC `ThaumcraftWorldGenerator.structureNode` (node dedup) | find instance via GameRegistry worldGenerators walk, clear |
| Witchery `WitcheryWorldGenerator.structuresList` | clear on registered instance |
| GT `GTWorldgenerator.validOreveins` (static, `GTWorldgenerator.java:75`) | clear; assert `PENDING_TASKS` empty at teardown |
| RWG `RwgWorldSavedData.INSTANCE` | null |
| fix jar `TerrainOracle` (`CACHE` + `cacheWorld`) | clear/null (self-invalidates on world change but pins the old world until first use) |
| GT `OregenPatternSavedData` | no-op: WorldEvent.Load handler reloads; fresh world ⇒ `EQUAL_SPACING`, same as cold boot (asserted) |
| Forge `VillagerRegistry` map | no-op: F1 fix makes it name-sorted/stable |
| fix-jar ThreadLocals (BopRandHolder etc.) | no-op: scoped per generate call |

### B.4 Verification gate (all byte-identical via diff-probe.py, incl. villages/witchery)
1. fresh(A) vs warm[A] slot 1; 2. fresh(B) vs warm[A→B] slot 2; 3. fresh(A) vs
warm[B→A] slot 2; 4. warm[A→A] slot 1 vs 2; 5. repeat for ≥3 pairs incl. 88888888 and
1234567890; 6. leak check after 20 cycles (`WorldServer` heap histogram +
`DimensionManager.leakedWorlds` stays at #staticDims).
On mismatch: bisect by disabling reset actions one at a time. Known risk: a mod caching
the overworld at `FMLServerStartedEvent` (shows as rows 2-3 diffs).

Expected: teardown ~1 s + world construct/spawn search ~2-8 s + gen ~2 s ≈ **5-12 s/seed**.

---

## C. Amidst-style seed prefilter (deferred)

### C.1 RWG chunk manager without a WorldServer
`ChunkManagerRealistic(World)` (`Realistic-World-Gen/.../ChunkManagerRealistic.java:55-62`)
uses the world for exactly one thing: `getSeed()`. No World reference retained; all
queries are seed-pure noise + a per-instance cache. Instantiate per seed via
`SeedProbeWorld extends World` using the **server-side** ctor
(`World.java:246` — the lighter ctor at :206 is `@SideOnly(CLIENT)`, stripped):
minimal ISaveHandler (null worldInfo → fresh `WorldInfo(settings, name)`), a
`WorldProviderSurface` with dim 0, `WorldSettings` carrying worldtype rwg;
`createChunkProvider()` → null. Caveat: `World.java:255` swaps the static
`s_mapStorage`/`s_savehandler` pair — save/restore around construction. Assert RWG
`NoiseSelector` `useOpenSimplex == false` before each batch.

### C.2 Terrain-free predicates (call real mod classes in-JVM — no reimplementation drift)
- Biome/ocean/river: `getBiomeDataAt`/`getOceanValue`/`getRiverStrength` on a grid.
- RWG villages, fully prefilterable: `MapGenVillage(size=0, distance=24)`
  (`ChunkGeneratorRealistic.java:120-123`); cell rand =
  `world.setRandomSeed(i1, j1, 10387312)` (`World.java:4235-4240`), site = cell*24 +
  nextInt(16); biome gate is RWG's noise-only `areBiomesViable`
  (`ChunkManagerRealistic.java:433-458`). Existence AND chunk position exact.
- GT vein try-order: ore chunks `floorMod(x,3)==1 && floorMod(z,3)==1` (EQUAL_SPACING for
  new worlds); `oreveinSeed = (worldSeed<<16) ^ (dim<<56 | oreseedX<<28 | oreseedZ)`;
  `new XSTR(seed).nextInt(100) < chance`; attempt i layer via
  `WorldgenQuery.veins().findRandom(rng seeded Fnv1a64(oreveinSeed, i))`
  (`GTWorldgenerator.java:309-402`). Terrain reroll gate not evaluable ⇒ "attempt-1 vein
  (probable) + fallback order".
- Roguelike trigger grid: `Dungeon.canSpawnInChunk` (`Dungeon.java:112-144`), same
  setRandomSeed salt; read `RogueConfig` live.
- Eldritch ring candidates: fix jar `EldritchRingLottery.candidates(worldSeed, rm, rn)`
  (pure). Winner needs terrain.

### C.3 Interface + throughput
`SeedPredicate { id(); Verdict test(SeedContext) }` with lazy `SeedContext` (biomes(),
villages(r), veinTryOrder(x,z), ringCandidates(rm,rn), dungeonTriggers(r)); spec.json of
ordered stages, cheap first, short-circuit; JSONL out. `ChunkManagerRealistic` is not
thread-safe — one per worker thread. Estimate 50-200 seeds/s/thread ⇒ **10⁵-10⁶
seeds/hour** in one warm JVM vs ~40/hour full-gen.

### C.4 Cannot prefilter (finalists get full runs)
Chest contents, vein terrain rerolls + Y, village piece layout/Y, ring winners (virgin
5-column test), Roguelike validLocation/loot, TC nodes/trees, decoration-level anything.
Golden test: for 3 seeds assert predictions appear in full-gen probe JSON.

---

## D. CRIU checkpoint/restore (designed + coded, UNVERIFIED — skipped 2026-07-23, user decision: criu needs sudo to install/run and warm mode's ~16× sufficed. The probe's `-Dprobe.criu` barrier and `scripts/criu-harness.sh` are in place if this is picked up later.)

Pay the boot once; each restore is a pristine memory image (no reliance on B's reset
registry — B and D validate each other).

1. Checkpoint point: probe handler on `FMLLoadCompleteEvent` (mods loaded; in 1.7.10
   dedicated servers mod load happens inside `startServer()` **before** port bind, seed
   parse and `loadAllWorlds`) — active with `-Dprobe.criu=<control-dir>`: write
   `ready` (with PID), block polling for `go.json`.
2. Harness `scripts/criu-harness.sh`: `criu check` first (needs root/CAP_SYS_ADMIN;
   criu was not installed on this box as of 2026-07-23). Launch with `-XX:-UsePerfData`,
   stdin `</dev/null`, no JMX/JFR. On `ready`: `criu dump -t PID --images-dir img
   --file-locks`. Restore loop per seed: wipe `World/`, write `go.json`
   ({seed, order, radius, out, serverPort}), `criu restore -d`, wait for JSON.
3. Post-restore: the woken handler injects `level-seed` (+ unique `server-port`) into the
   live `PropertyManager` (`DedicatedServer.settings`) reflectively before returning;
   `startServer` continues → bind → parse (our) seed → `loadAllWorlds` → probe as a cold
   boot.
4. MVP sequential; parallel restores later need per-restore PID + mount namespaces
   (CRIU restores the original PID and paths).

Verification: restore(X) byte-identical to fresh-boot(X) for ≥3 seeds incl. 88888888;
restore(X) twice identical; villages/witchery sections match.

---

## Benchmark results (measured 2026-07-24, radius 8)

| Path | s/seed | Notes |
|---|---|---|
| Cold boot (baseline) | ~90-95 | boot ~82 s dominates |
| Warm multi-seed batch | ~13 | incl. replicated spawn preload; + one 82 s boot per batch |
| Warm queue daemon | ~10-11.5 | measured per-job status millis (incl. search+teraw overhead); one boot per daemon lifetime |
| Warm daemon + dim0only | ~7.7 | byte-identical to full recreate (verified same-JVM); default for seed-search.sh |
| CRIU restore | — | designed, skipped (user decision; needs sudo) |

Leak note (20-cycle jmap:live check, 2026-07-24): each full recreate pins ~12 WorldServerMulti +
1 WorldServer (mod dimension bookkeeping holds dead worlds; GC-family suspects). Tolerable for
short batches; `probe.dim0only=true` (job flag / PROBE_DIM0ONLY env) skips the non-overworld dims
entirely — output byte-identical, ~30% faster, and removes 12/13 of the pinned objects per slot.
Use it for all search batches; keep full recreate only if a future probe targets other dims.

## Warm cross-JVM-history residual (characterized 2026-07-24)

Warm worlds are byte-identical to each other given the same JVM history (self-tests), and
match cold boots on ALL of: blocks (hash-time), chest contents, village layouts, witchery
structures, biomes, clay. A JVM-history-dependent residual persists in GT ore TILE ENTITY
bookkeeping only (per 289-chunk region, seed 88888888 measurements):
- ~128 chunks: host-stone thousands-digit of TileEntityOres `m` (cosmetic; e.g. small Coal
  16535↔21535 — same material, different recorded host rock);
- ~19 chunks: small-ore TE presence deltas (mostly Coal smalls, ~83 TEs) — minor
  early-game prospecting signal;
- water block-count scans ±~50 blocks/region (0.05%) via post-hash TE conversion side
  effects; block hashes at hash time remain identical.
Root cause: a value-carrying, first-write-wins store somewhere outside every static we
reset (TC maze/nodes, Witchery list, GT validOreveins/mList/ProcChunks/mIsGenerating,
BartWorks mGenerated, CoFH populatingChunks, GT++ everglades, RWG saveddata, TerrainOracle)
— generation/populate ORDER was ruled out by trace (order jitters without changing output).
Hunt tooling if resumed: probe `-Dprobe.staticsweep=all` (2-level static scan incl.
singleton descent — extend to trove/fastutil-primitive types and vanilla/Forge jars),
popseq/tracestacks trace + scripts/diff-popseq.py.
Practical guidance: warm batches are valid for seed SEARCH (all filter signals clean;
verify finalists with a fresh JVM); use cold runs for upstream-grade effects statistics
(effects-ab.sh does, both arms) and launch-variance tests.

## Multi-server farm (TESTED 2026-07-24 evening — production-ready)

`scripts/probe-farm.sh start <template> <farm-dir> <N>` — N probe daemons on hardlink-cloned
server dirs (mods/libraries hardlinked; config copied — Forge rewrites configs in place),
unique ports (25600+i via PROBE_PORT support in probe-queue.sh), one global queue + a
dispatcher feeding the emptiest instance. PROBE_XMX sizes heaps: 18 GB budget → N=2 @ 6G
(proven config) or N=3 @ 4G (benchmark a 4G boot before trusting). MemAvailable guard
refuses instances under heap+2G. Same-instance jobs share JVM history (warm residual
caveat); paired fresh-JVM experiments stay on probe-queue.sh with dedicated daemons.
Expected throughput at N=2: ~4 s/seed effective (2 × ~7.7 s dim0only jobs in parallel).
Acceptance test (2 instances @6G, 4 jobs): dispatcher balanced 2/2 across instances; the
SAME seed run on both instances produced BYTE-IDENTICAL probe JSONs (clone + isolation
correct); jobs ~8.4-10 s each; both ports bound; clean stop. Hardlink audit caught ONE
runtime writer inside mods/: OpenSecurity rewrites its loose .ogg sound files at boot —
the farm now real-copies mods/OpenSecurity per clone (fix in probe-farm.sh). Clones also
carry the CoreTweaks transformer cache so boots stay fast. Effective throughput at N=2:
~4-5 s/seed.
