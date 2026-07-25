# Seed search speed: where the time goes, and the plan to get 1000× more seeds

*2026-07-24. Companion to `harness-speed.md` (designs A–D); this consolidates measured reality
post-0.5 (slicing, F7) and ranks the remaining levers. Machine baseline: 16 cores, 62 GiB RAM,
user policy ≥20 GiB reserved for interactive work.*

## 1. Anatomy of one seed today

A full-fidelity seed evaluation (CRIU pool, radius 15, dim0only, nohash) spends its time in
four places:

| Phase | Cost | Load-bearing? |
|---|---|---|
| JVM + 200-mod boot | ~82 s → **0 s** (CRIU image, amortized) | eliminated |
| Restore + world create + statics reset | ~3-6 s | required (teardown, seed injection, loot-table restore) |
| Spawn preload + chunk walk (~1,250 chunks incl. cascade) | **~15-20 s, ≈85% of cycle** | the product itself: RWG terrain + populate is what we're measuring |
| TE materialization + search report | ~1-2 s | partially trimmable (see §3.3) |

Terrain+populate dominates and is JFR-certified irreducible per chunk (two independent noise
optimizations were built, proven bit-exact, and measured to win nothing — fastnoise flat-table
and parallel column noise, both kept OFF). **You cannot make a chunk cheaper; you can only
generate fewer chunks, generate them on more cores, or not generate them at all.**

## 2. The warm-up, step by step (what's needed and what isn't)

- **Mod boot**: eliminated by the CRIU checkpoint at `FMLLoadCompleteEvent`. Nothing left.
- **Non-overworld dims** (Nether/End/GC): skipped via `dim0only` — certified byte-identical,
  ~30% faster, default everywhere. Nothing left.
- **Spawn preload (±12 chunks)**: those 625 chunks are inside the radius-15 window anyway —
  chunks generate once, so replicating the preload costs *order*, not chunks. No time to save.
  (Post-slicing it may no longer be needed for parity at all — worth one A/B for simplification,
  not speed.)
- **Loot-table snapshot/restore, static resets**: microseconds. Required for correctness.
- **Block hashing**: already off for search (`nohash`).
- **Lighting**: 11.6% of cycle, measured parity-UNSAFE to skip (light-gated decoration). Closed.
- **Warm vs CRIU**: warm batches are ~25% faster per seed but carry the small-ore host-stone
  residual — policy stands: warm for search filtering, CRIU/cold for anything published.

Conclusion: the warm-up is already stripped to the bone. The remaining wins are architectural.

## 3. The levers, ranked

### 3.1 The funnel (biggest win: ~1000×, design already exists)

This is how the vanilla-Minecraft seed-hunting community searches 2⁴⁸ structure seeds
(cubiomes and friends): **layered filters, cheapest first, full simulation only for
finalists.** Our analog is designed in `harness-speed.md` §C and is exact rather than
reimplemented — it calls the real mod classes in-JVM with no WorldServer:

- **Stage 0 — pure-math prefilter, ~50-200 seeds/s/thread (10⁵-10⁶ seeds/hour)**:
  `ChunkManagerRealistic` needs only `getSeed()` → spawn-area biomes, ocean/river checks;
  RWG village existence AND chunk position are exactly predictable; GT vein try-order
  (attempt-1 vein probable); Roguelike trigger grid; eldritch ring candidates
  (`EldritchRingLottery` is pure). Filter examples: "village ≤150 blocks of spawn, no ocean
  spawn, TiC-viable biome" — kills >95% of random seeds for ~5 ms each.
- **Stage 1 — cheap probe, radius 8 (~2.3× fewer chunks ≈ 4-5 s/seed warm)**: for survivors,
  a small-window run answering chest/village-piece questions the math can't (loot, piece
  layout, TiC houses). Coarse thresholds only — the window edge caveat applies.
- **Stage 2 — full radius-15 format-2 report (current pipeline)**: finalists only, CRIU/cold,
  publishable into gtnh-seedlib.

Napkin: today 700 random seeds ≈ 2 h of machine time and yields ~10 coke%-interesting seeds.
With stage 0 at 10⁵-10⁶/h feeding stage 1 at ~800/h feeding stage 2 at ~150/h, the same 2 h
surfaces finalists from a pool of **millions** of candidate seeds — and every stage-0 predicate
is verifiable against stage-2 output (golden tests) because it's the same code.
Estimated effort: the design is written; ~1-2 days to build `SeedProbeWorld` + predicate
harness + spec runner, plus per-category predicates as needed.
**Caveat**: funneled samples are biased by construction — keep random corpora for statistics
(balance evidence, loot-table facts) and funneled corpora for routing candidates, labeled as
such in gtnh-seedlib provenance.

### 3.2 Parallel width (bounded by the RAM policy: ~1.6× from here)

Envelope on this box: 62 GiB − 20 reserved = 42 GiB usable → at ~6.5 GiB/instance
(6G heap + native) the pool can run **N=6** instances against today's typical 3-4
(CPU is not the binding constraint at 16 cores; the elastic RAM/CPU gates already handle
contention). Cheap config change; do it for the regen. Beyond that: other machines. The
determinism property makes **community-distributed search trivially verifiable** — any two
machines must produce byte-identical reports for the same seed+jar (md5 the report), so
Discord volunteers could run pinned-jar batches with 5% random re-verification locally.
Social/infra effort, not engineering; worth considering if search demand outgrows one box.

### 3.3 Report-time trims (~5-15%, measure first)

TE materialization exists only so GT ore TEs can be counted. The material id is also
recoverable from block+extended metadata (NEID `Data1High`/`Data2`, already decoded in
`diff-region-blocks.py`) — reading meta directly would skip creating thousands of TileEntities
per seed *and* sidestep the lazy-TE bookkeeping noise class entirely. One probe change + one
byte-equivalence A/B on the ore histograms.

### 3.4 Restore-ahead (latency hiding, small)

The pool restores an instance, runs the seed, kills it. Keeping one instance restored and
parked at the go.json barrier while others work would hide the ~3-5 s restore under compute
at the cost of one instance's RAM. Only worth it if N is RAM-capped below CPU capacity.

## 4. Dead ends (measured, do not revisit without new evidence)

- Noise-level optimization: fastnoise backport and parallel column noise — both bit-exact,
  both zero-or-negative wins. The RWG cost is scattered per-decorator noise in a sequential
  stream, not a hot kernel.
- Lighting skip: parity-unsafe (light-gated deco).
- Warm mode for published corpora: host-stone residual (user ruling: backlog, warm stays
  search-only).
- Bigger heaps / GC tuning: leak fixed; ParallelGC already default; heap is not the bottleneck.

## 5. Recommended sequence

1. **Now**: regen the 700-seed format-2 corpus on the current CRIU pool at **N=6** (~1-1.5 h).
   Postprocessing: old-vs-new chest counts + water totals (slicing impact), obelisk density
   (lottery-v2 validation), provenance READMEs.
2. **Next session**: build §3.1 stage 0 (SeedProbeWorld + predicates) with golden tests
   against three regen seeds; wire `spec.json` staged filtering; then a first
   million-seed coke% sweep (paper-village + water + clay biome predicates at stage 0,
   marshmallow/heads at stage 1-2).
3. **Opportunistic**: §3.3 ore-from-metadata (also kills a noise class), §3.4 if profiling
   shows restore gaps, §2's preload-replication A/B for harness simplification.
