#!/usr/bin/env bash
# Speedrun seed searcher: runs warm multi-seed batches with the search report enabled.
#
# Usage: seed-search.sh <server-dir> <seed-file> <out-dir> [radius] [batch-size]
#   seed-file:  one seed per line (or comma/space separated)
#   radius:     chunks around spawn-region origin to report on (default 15 — chest sweep range)
#   batch-size: seeds per JVM boot (default 25). NOTE: batch-size=1 is NOT a cold boot — every
#               batch boots on seed 1 and RECREATES per seed (warm mechanics). With probe >=0.6
#               (ChestGenHooks pre/post-server snapshot restore) warm slots are certified
#               byte-identical to true cold runs (4 seeds / 189 chests, 2.8.4); with older probe
#               jars, spawn-window structure chests roll post-ServerStarting loot tables = wrong
#               vs real worlds. Residual known noise: ore host-stone variants.
#               2.8.4 memory: slots leak ~0.5G each — use PROBE_XMX=10G with batches of ~10.
#
# Output: <out-dir>/seed-<seed>.json (+ gtmats.json once), resume-safe (existing files skipped).
#
# For shared-machine / elastic runs prefer scripts/criu-pool.sh (RAM-reserve-gated CRIU restores,
# zero idle footprint); this warm-batch path remains the max-throughput choice for dedicated boxes.
set -euo pipefail

SERVER_DIR=$1
SEED_FILE=$2
OUT_DIR=$3
RADIUS=${4:-15}
BATCH=${5:-25}
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

mkdir -p "$OUT_DIR"
OUT_DIR=$(cd "$OUT_DIR" && pwd)

# Writing per-seed JSONs + server logs straight into a Dropbox-synced dir makes the sync daemon compete
# with worldgen for CPU/IO. Stage on tmpfs and move per batch; resume checks still hit OUT_DIR.
WORK_DIR=$OUT_DIR
case "$OUT_DIR" in
*Dropbox*)
  WORK_DIR=$(mktemp -d /tmp/seed-search.XXXXXX)
  trap 'cp -n "$WORK_DIR"/*.json "$OUT_DIR"/ 2>/dev/null || true; rm -rf "$WORK_DIR"' EXIT
  echo "staging output in $WORK_DIR (Dropbox destination detected)" ;;
esac

# normalize seeds, drop ones already done
PENDING=()
for s in $(tr ', ' '\n\n' < "$SEED_FILE" | grep -vE '^\s*$'); do
  [ -f "$OUT_DIR/seed-$s.json" ] || PENDING+=("$s")
done
TOTAL=${#PENDING[@]}
echo "$(date +%H:%M:%S) $TOTAL seeds pending (radius $RADIUS, batch $BATCH)" | tee -a "$OUT_DIR/search-progress.txt"
[ "$TOTAL" -eq 0 ] && exit 0

i=0
while [ $i -lt $TOTAL ]; do
  CHUNK=("${PENDING[@]:$i:$BATCH}")
  LIST=$(IFS=,; echo "${CHUNK[*]}")
  echo "$(date +%H:%M:%S) batch: $LIST" >> "$OUT_DIR/search-progress.txt"
  PROBE_SEARCH=true PROBE_DIM0ONLY="${PROBE_DIM0ONLY:-true}" PROBE_NOHASH="${PROBE_NOHASH:-true}" \
    PROBE_JVMFLAGS="${PROBE_JVMFLAGS:--XX:+UseParallelGC}" \
    "$SCRIPT_DIR/warm-probe.sh" "$SERVER_DIR" "$LIST" rows "$WORK_DIR/seed.json" "$RADIUS" \
    > "$WORK_DIR/batch-$i.log" 2>&1 || echo "$(date +%H:%M:%S) BATCH FAILED at offset $i" >> "$OUT_DIR/search-progress.txt"
  if [ "$WORK_DIR" != "$OUT_DIR" ]; then mv -f "$WORK_DIR"/*.json "$WORK_DIR"/batch-*.log "$OUT_DIR"/ 2>/dev/null || true; fi
  # warm-probe templates seed.json -> seed-<seed>.json per slot
  n_ok=0
  for s in "${CHUNK[@]}"; do [ -f "$OUT_DIR/seed-$s.json" ] && n_ok=$((n_ok+1)); done
  echo "$(date +%H:%M:%S) batch done: $n_ok/${#CHUNK[@]} reports" >> "$OUT_DIR/search-progress.txt"
  i=$((i+BATCH))
done
echo "$(date +%H:%M:%S) SEARCH COMPLETE" | tee -a "$OUT_DIR/search-progress.txt"
