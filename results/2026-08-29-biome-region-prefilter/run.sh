#!/usr/bin/env bash
# Stage-0 no-rain-region module: golden test against full generation, then base rates.
#
# The question is "a no rain biome near a high humidity biome, the no rain one at least 5x5
# chunks". Both halves turn on per-column biome data — RWG draws each column from a blended
# distribution and then paints river biomes over it — so the ground truth is the generated
# biome array, and the stage-0 module has to reproduce it rather than approximate it.
#
# Two runs, in this order, because the second is only meaningful if the first is exact:
#   1. 24 random seeds through the full probe at radius 15 (report format 7 carries the
#      per-chunk biomeCounts census this needs), giving ground truth.
#   2. the same 24 seeds through stage 0, compared field by field by prefilter-judge.py.
#
# Sizing: 24 seeds is enough to establish exactness (the module is deterministic and either
# reads the same data or does not) but NOT enough for a base rate with error bars — the
# percentages below are indicative and are labelled as such in the README.
set -euo pipefail
cd "$(dirname "$0")"
REPO=../..
SERVER=~/.cache/gtnh-determinism/prefilter/daily707

python3 -c "
import random
r = random.Random(20260829)
print('\n'.join(str(r.randint(-2**63, 2**63-1)) for _ in range(24)))" > rand24.txt

# 1. ground truth. PROBE_XMX=10G because 2.8.4+ warm slots leak ~0.5 G each.
PROBE_XMX=10G "$REPO/scripts/seed-search.sh" "$SERVER" rand24.txt truth 15 12 > boot-truth.log 2>&1

# 2. stage 0. confirm defaults to -1 (whole window, exact). chunkcache is raised because the
# window is 961 chunks and the default LRU is 256; a single pass generates each chunk once
# either way, but a larger cache keeps the terrain stage's chunks resident.
PREFILTER_SERVER="$SERVER" PREFILTER_RADIUS=8 PREFILTER_TERRAIN=4 \
PROBE_JVMFLAGS="-Dprobe.prefilter.biomeregion=15 -Dprobe.prefilter.chunkcache=1200" \
  "$REPO/scripts/prefilter.sh" @rand24.txt "$PWD/stage0.jsonl" > boot-stage0.log 2>&1

python3 "$REPO/seedsearch/prefilter-judge.py" stage0.jsonl truth > judge.txt 2>&1 || true
python3 "$REPO/scripts/searchlib.py" biomeregions truth > regions.txt 2> regions-summary.txt || true

# 3. cost A/B on the configuration a sweep actually runs. Gated, so most seeds never reach the
# terrain digest and the biome stage only pays on survivors. Quoting an UNGATED per-seed number as
# a sweep cost overstates it by 3-22x, which is how the first writeup of this stage got it wrong.
export PREFILTER_SERVER="$SERVER" PREFILTER_RADIUS=64 PREFILTER_TERRAIN=4
export PREFILTER_GATE_VILLAGEDIST=12
export PREFILTER_GATE_PIECES=VillageComponentPhotoshop,ComponentToolWorkshop,ComponentSmeltery
export PREFILTER_GATE_WATER=32
{
  echo -n "gated, no biome stage : "
  "$REPO/scripts/prefilter.sh" random:2000:7 "$PWD/sweep-nobiome.jsonl" 2>&1 | grep -o 'done: .*'
  echo -n "gated, biomeregion=4  : "
  PROBE_JVMFLAGS="-Dprobe.prefilter.biomeregion=4" \
    "$REPO/scripts/prefilter.sh" random:2000:7 "$PWD/sweep-biome.jsonl" 2>&1 | grep -o 'done: .*'
} > sweep-cost.txt 2>&1

echo "BIOME REGION PIPELINE COMPLETE $(date)"
