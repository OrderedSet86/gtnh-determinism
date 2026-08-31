#!/usr/bin/env bash
# Radius-60 stage-0 chest-loot sweep. No full-generation pass.
#
# PREFILTER_TERRAIN must stay >= 0. Prefilter only computes the spawn point when the terrain digest
# is enabled (Prefilter.java:1194); with -1 the Roguelike scan and the scoring window both centre on
# the origin instead, silently.
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$REPO/results/2026-08-30-chest-loot-radius60"
SERVER=~/.cache/gtnh-determinism/prefilter/daily707
SEEDS=${1:-$OUT/seeds-10.txt}
cd "$REPO"

./scripts/build-jar.sh probe --deploy "$SERVER"

# PREFILTER_RADIUS is the village cell scan radius around the ORIGIN and must exceed the scoring
# radius, because spawn is offset from it. chunkcache defaults to 256, too small for an r=60 scan.
PREFILTER_SERVER="$SERVER" \
PREFILTER_RADIUS=70 PREFILTER_TERRAIN=4 \
PROBE_JVMFLAGS="-Dprobe.prefilter.dungeon=60 -Dprobe.prefilter.villagechests=true -Dprobe.prefilter.chunkcache=2048" \
  ./scripts/prefilter.sh @"$SEEDS" "$OUT/prefilter-0.5-d17a685-gtnhdaily707-10-chest-loot-r60.jsonl"

seedsearch/loot-score.py "$OUT/value-table.csv" "$OUT/prefilter-0.5-d17a685-gtnhdaily707-10-chest-loot-r60.jsonl" \
  --radius 60 --top 10 --chests 2 \
  --loot-tables ~/.cache/gtnh-determinism/gtnh-daily707-chestloot-v2.csv \
  | tee "$OUT/score-10.txt"
