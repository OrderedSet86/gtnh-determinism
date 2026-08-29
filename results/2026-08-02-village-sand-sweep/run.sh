#!/usr/bin/env bash
# Coke% WR seed hunt: village essentially ON spawn, plus a big shallow sand field.
#
# VILLAGEDIST=3 (48 blocks), not the 12 the 650k sweep used — 192 blocks of walking to the village
# is not a WR route. The tight gate kills far more seeds at the cheap arithmetic stage, so the
# per-seed cost drops and 1M seeds is affordable in roughly the time 100k took at dist=12.
#
# Pieces gate is an OR (any coke%-relevant building) because it is cheap; the real
# paper AND tic AND furnace requirement is applied afterwards by coke-rank.py.
#
# Ranking target, from the 10k ungated sweep: sand runs are quantised {0,1,4,5,6} with a ceiling of
# 6, so depth is near-constant. What matters is how many run>=4 columns sit in ONE chunk near spawn
# (44 columns = all 175 sand from a standing dig); run>=5 columns are the only depth bonus available
# and turn 44 collapses into 35.
set -euo pipefail
cd "$(dirname "$0")"
REPO=../..
export PREFILTER_GATE_VILLAGEDIST=3
export PREFILTER_GATE_PIECES=VillageComponentPhotoshop,ComponentToolWorkshop,ComponentSmeltery
export PREFILTER_GATE_WATER=32
"$REPO/scripts/prefilter.sh" "random:1000000:20260802" "$PWD/sweep.jsonl" > boot-sweep.log 2>&1
echo "SWEEP COMPLETE $(date)"
