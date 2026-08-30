# Stage 1: virgin-terrain chunk provider for the stage-0 prefilter

GTNH 2.8.4 template, `~/.cache/gtnh-determinism/prefilter/server`, fix jar `gtnhdeterminism-0.5pre`,
probe `worldgenprobe-v0.5-main.25+884548e365-dirty` (md5 `f0725a287df144eee70f969e91b0fc3a`).
200 seeds, `random:200:7`, village radius 16 chunks, terrain digest radius 4.

## What changed

`Prefilter.SeedProbeWorld.createChunkProvider()` returned `null`, so every `world.getBlock` threw. It
now returns `VirginChunkProvider`, which caches real `Chunk` objects from
`ChunkGeneratorRealistic.provideChunk` — the same method `TerrainOracle` calls in a full run, built the
same way `WorldTypeRealistic.getChunkGenerator` builds it.

This is the enabler, not a feature. `TerrainOracle.block(world, x, y, z)` falls through to
`world.getBlock` whenever the world is not a `WorldServer`, so with a provider in place the fix jar's
virgin-terrain reads — Roguelike's `validLocation`, `WorldEditorMixin`'s overlay, the GT vein reroll
gate — work inside the prefilter unchanged.

Supporting pieces:

- `chunkExists()` always false. That is the truth, and it is load-bearing: `PendingSlices.shouldBuffer`
  short-circuits on it, so a dungeon generated against this world buffers every write rather than
  reaching `world.setBlock`.
- Write guards on `SeedProbeWorld`: `setBlock`, `setBlockMetadataWithNotify` and `setTileEntity` throw
  (`-Dprobe.prefilter.strictworld=false` downgrades to a logged error). The cached chunks *are* the
  virgin-terrain oracle; a live write would edit it in place and every later read would answer from a
  world that no longer matches the seed — deterministically, and so invisibly.
- Lighting and render notifications no-op, so a read cannot drag neighbouring chunks into the cache.
- Re-entrancy guard: a generator that reads the world for the chunk it is building throws with its
  coordinates instead of recursing to a stack overflow.
- `chunks_generated` / `chunks_regenerated` in the JSONL. A nonzero regeneration count means the LRU
  evicted a chunk that was needed again, i.e. the seed paid full terrain cost twice — a throughput bug
  with no other trace. It is 0 across all 200 seeds at the default 256-chunk cache, even for the seed
  that generated 848.

## The provider serves real terrain — measured non-circularly

`-Dprobe.prefilter.selftest=true` walks four columns in every digest-window chunk down from y=255 using
`world.getBlock`, and compares the resulting top-solid height against the digest. Run with
`-Dprobe.prefilter.digestviaprovider=false`, the digest comes from the original hand-rolled
`generateTerrain → replaceBlocksForBiome → caves` subsequence while the reads come from the provider,
so the two sides are independent.

```
seeds with selftest: 200   columns checked: 64800
world.getBlock (provider) vs hand-rolled digest MISMATCHES: 0
```

## Switching the digest to the provider changes no output

| | |
| --- | ---: |
| terrain chunks compared | 16,200 |
| chunk digests differing | **0** |
| predicted spawns differing | **0** |
| seeds affected | **0** |

The hand-rolled path skipped the per-biome `generateMapGen`, which writes blocks, and I expected that
to show up here. It does not: `generateMapGen` fires only where `mapGenBiomes[k] > 0`, which
`generateTerrain` sets only for biome 312 (tropical island volcanics, mid-ocean), and no spawn window
in these 200 seeds contains one. The original code comment claiming it was safe to skip is correct, and
now measured rather than argued.

## It costs 26% on the terrain digest

| digest source | 200 seeds |
| --- | ---: |
| hand-rolled, run 1 | 43.9 s |
| hand-rolled, run 2 | 44.1 s |
| via provider | 55.3 s |

**+26%, ~55 ms/seed at radius 4.** Full `provideChunk` does more than the terrain subsequence: the
three structure map-gens, the `Chunk` construction, the biome array and `generateSkylightMap`. My first
attempt at this comparison reported "no cost"; it was wrong, because the flag was not reaching the JVM
at all — see below.

Kept anyway, and the default is the provider. Two code paths computing one fact is how this project has
produced silent divergence before, and the paths are not equivalent at the edges: the provider's terrain
includes `generateMapGen` and the hand-rolled one does not, so in a tropical-island chunk the digest and
the block reads would describe different worlds. One source of truth is worth 55 ms on a stage that the
funnel already restricts to gate survivors — at the 650k sweep's ~7% survival that is roughly +3 ms on a
26 ms/seed average. `-Dprobe.prefilter.digestviaprovider=false` restores the old path if that judgement
turns out wrong.

## Trap: `prefilter.sh` ignored `PROBE_JVMFLAGS` entirely

The first A/B reported 16,200 chunks and 0 differences. It was comparing the provider against itself:
`scripts/prefilter.sh` never forwarded `PROBE_JVMFLAGS`, so both runs used the default. Every other
probe script accepts that variable, which is exactly what makes it dangerous — the run succeeds, the
output looks right, and the comparison is vacuous.

This is the second instance of the same shape in one session. `warm-probe.sh` has the mirror image: it
*does* forward `PROBE_JVMFLAGS`, but places it **before** its own `-Dprobe.search=false`, so
`PROBE_JVMFLAGS="-Dprobe.search=true"` is silently overridden and the report comes back with no `search`
section — at which point `diff-chests.py` compares zero chests and prints `ALL SEEDS IDENTICAL`.

`prefilter.sh` now appends `PROBE_JVMFLAGS` last, so it overrides, and both traps are documented in its
header. The general lesson: **when a flag is supposed to change behaviour, verify it changed behaviour
before trusting the comparison.** Here the check was the `chunks_generated` counter, which separates the
two paths unambiguously:

| run | chunks_generated per seed |
| --- | --- |
| provider (`true`) | median 90, min 81, max 848 — digest and spawn walk both read the provider |
| hand-rolled (`false`) + selftest | exactly 81 every seed — only the self-test reads it |

## No regression in the modules that were already golden-tested

`seedsearch/prefilter-judge.py` against `results/2026-07-24-seedlib-2.8.4-fmt2-100c`, 99 seeds, with the
provider serving both the digest and the spawn walk:

```
villages: 79/80 corpus villages matched (98.8% recall)
pieces:   79/79 matched villages with corpus subset of prefilter pieces (0 extra prefilter-only)
spawn:    99/99 exact (100.0%); 0 mismatches, median delta 0 blocks
```

The single missed village sits at `(740, 1115)`, about 70 chunks out and therefore outside the
64-chunk scan; it is a radius limit, not a miss. This supersedes the previously recorded golden of
"8/8 corpus villages piece-exact" — same result, ten times the sample.

Run it at the default `PREFILTER_RADIUS=64`. At 16 the same command reports 31.2% recall, because the
corpus records villages out to ±1100 blocks while a 16-chunk scan reaches 256; every "MISS" line is
then a village the run never looked for.

## Next

The provider is what Stage 2 needs. Nothing yet runs Roguelike against it; that is the next step, and it
is the first consumer that will exercise the write guards, the re-entrancy guard and the LRU under a
real access pattern rather than a 9×9 digest window.
