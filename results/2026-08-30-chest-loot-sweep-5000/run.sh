#!/usr/bin/env bash
# 5000-seed radius-60 stage-0 sweep, in 5 batches of 1000.
# Batched so a late failure cannot lose the whole run: each batch is a fresh JVM with bounded memory,
# and the 56 s boot it costs is 0.4% of a 46 min batch.
set -uo pipefail
REPO=/home/order/Dropbox/OrderedSetCode/cloned-gtnh/gtnh-determinism
W=$HOME/.cache/gtnh-determinism/sweep-5000
cd "$REPO"
for i in 0 1 2 3 4; do
  if [ -s "$W/out-$i.jsonl" ] && [ "$(wc -l < "$W/out-$i.jsonl")" -eq 1000 ]; then
    echo "$(date +%H:%M:%S) batch $i already complete, skipping" >> "$W/progress.txt"; continue
  fi
  echo "$(date +%H:%M:%S) batch $i start" >> "$W/progress.txt"
  PREFILTER_SERVER=$HOME/.cache/gtnh-determinism/prefilter/daily707 \
  PREFILTER_RADIUS=70 PREFILTER_TERRAIN=4 \
  PROBE_JVMFLAGS="-Dprobe.prefilter.dungeon=60 -Dprobe.prefilter.villagechests=true -Dprobe.prefilter.chunkcache=2048" \
    ./scripts/prefilter.sh @"$W/batch-$i.txt" "$W/out-$i.jsonl" > "$W/batch-$i.log" 2>&1
  echo "$(date +%H:%M:%S) batch $i done rc=$? lines=$(wc -l < "$W/out-$i.jsonl" 2>/dev/null || echo 0)" >> "$W/progress.txt"
done
cat "$W"/out-*.jsonl > "$W/prefilter-0.5-d17a685-gtnhdaily707-5000-chest-loot-r60.jsonl"
echo "$(date +%H:%M:%S) SWEEP COMPLETE $(wc -l < "$W/prefilter-0.5-d17a685-gtnhdaily707-5000-chest-loot-r60.jsonl") seeds" >> "$W/progress.txt"

# Scoring (run after the sweep):
#   seedsearch/loot-score.py results/2026-08-30-chest-loot-sweep-5000/value-table.csv \
#     ~/.cache/gtnh-determinism/sweep-5000/prefilter-0.5-d17a685-gtnhdaily707-5000-chest-loot-r60.jsonl \
#     --radius 60 --top 10 --chests 3 \
#     --seeds results/2026-08-30-chest-loot-sweep-5000/top10-seeds.txt \
#     --loot-tables ~/.cache/gtnh-determinism/gtnh-daily707-chestloot-v2.csv
