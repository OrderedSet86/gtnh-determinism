#!/usr/bin/env bash
# Overnight coke% stage-0 sweep: 650k random seeds sized for ~5.3 h at 34 seeds/s
# (started ~02:20, target done by ~07:40), then rank, then re-digest the finalists
# at terrain radius 8 for honest sand/clay/water distances out to ~136 blocks.
set -euo pipefail
cd "$(dirname "$0")"
REPO=../..
export PREFILTER_GATE_VILLAGEDIST=12
export PREFILTER_GATE_PIECES=VillageComponentPhotoshop,ComponentToolWorkshop,ComponentSmeltery
export PREFILTER_GATE_WATER=32
"$REPO/scripts/prefilter.sh" "random:650000:45" "$PWD/sweep.jsonl" > boot-sweep.log 2>&1
python3 "$REPO/seedsearch/coke-rank.py" sweep.jsonl --require paper,tic,furnace \
  --seeds-out finalists.txt > rank-pass1.txt 2>&1 || true
if [ -s finalists.txt ]; then
  PREFILTER_TERRAIN=8 "$REPO/scripts/prefilter.sh" "@$PWD/finalists.txt" "$PWD/finalists-r8.jsonl" \
    > boot-finalists.log 2>&1
  python3 "$REPO/seedsearch/coke-rank.py" finalists-r8.jsonl --require paper,tic,furnace \
    --top 40 --seeds-out finalists-ranked.txt > rank-final.txt 2>&1 || true
fi
echo "SWEEP PIPELINE COMPLETE $(date)"
