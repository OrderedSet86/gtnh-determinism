#!/usr/bin/env bash
# Reproduces the chest-loot seed scoring run. See README.md for the pinned jar md5s.
#
# Stage 0 ranks 100 seeds on Roguelike dungeon and village piece chests. The top 10 are then
# regenerated under full generation, which adds every other chest source and supplies real Y for the
# /tp commands. Both scores are reported; the gap between them is the size of the stage-0 blind set.
set -euo pipefail

REPO=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$REPO/results/2026-08-30-chest-loot-scoring"
SERVER=~/.cache/gtnh-determinism/prefilter/daily707
FULLGEN_SERVER=~/.cache/gtnh-determinism/daily-707

cd "$REPO"

# 1. The probe jar must be current: Prefilter.java changes are silent until redeployed.
./scripts/build-jar.sh probe --deploy "$SERVER"

# 2. Stage 0. PREFILTER_RADIUS is the village cell scan radius in chunks; probe.prefilter.dungeon is
#    the Roguelike trigger scan radius around the predicted spawn. PROBE_JVMFLAGS is appended last by
#    prefilter.sh, so these override.
PREFILTER_SERVER="$SERVER" \
PREFILTER_RADIUS=16 PREFILTER_TERRAIN=4 \
PROBE_JVMFLAGS="-Dprobe.prefilter.dungeon=15 -Dprobe.prefilter.villagechests=true" \
  ./scripts/prefilter.sh @"$OUT/seeds-100.txt" "$OUT/prefilter-0.5-d17a685-gtnhdaily707-100-chest-loot-r15.jsonl"

# 3. Score, and emit the winners for step 4.
seedsearch/loot-score.py "$OUT/value-table.csv" "$OUT/prefilter-0.5-d17a685-gtnhdaily707-100-chest-loot-r15.jsonl" \
  --radius 15 --top 10 --chests 3 --seeds "$OUT/top10-seeds.txt" \
  | tee "$OUT/stage0-score.txt"

# 4. Full generation for the winners only, radius 15, batches of 10.
./scripts/seed-search.sh "$FULLGEN_SERVER" "$OUT/top10-seeds.txt" "$OUT/fullgen" 15 10

# 5. Did stage 0 predict what full generation actually produced?
python3 seedsearch/prefilter-judge-chests.py "$OUT/prefilter-0.5-d17a685-gtnhdaily707-100-chest-loot-r15.jsonl" "$OUT/fullgen" \
  | tee "$OUT/judge-chests.txt"

# 6. Re-score the winners over every chest source.
seedsearch/loot-score.py "$OUT/value-table.csv" "$OUT/fullgen" \
  --radius 15 --top 10 --chests 3 \
  | tee "$OUT/fullgen-score.txt"
