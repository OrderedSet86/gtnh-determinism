#!/usr/bin/env bash
# Launch-test pairs (rows walk, fresh JVM each) across a seed list with the full fix set installed.
#
# Configuration is via environment variables (or a .env file next to this script):
#   PROBE_SERVER  path to the GTNH server install (required)
#   PROBE_OUT     directory for result JSONs/logs        (default: ./probe-results)
#   PROBE_JAVA    Java 17-21 binary                      (default: java)
#   PROBE_RADIUS  probe radius in chunks                 (default: 8)
#   SEEDS         space-separated seed list              (default: the 10-seed sample below)
#
# Example: PROBE_SERVER=~/gtnh-server PROBE_JAVA=~/jdks/zulu17/bin/java scripts/multiseed-driver.sh
set -u

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
[ -f "$SCRIPT_DIR/.env" ] && . "$SCRIPT_DIR/.env"

: "${PROBE_SERVER:?set PROBE_SERVER to the GTNH server directory}"
OUT=${PROBE_OUT:-./probe-results}
RADIUS=${PROBE_RADIUS:-8}
export PROBE_JAVA=${PROBE_JAVA:-java}
RUN="$SCRIPT_DIR/run-probe.sh"

DEFAULT_SEEDS="1234567890 -987654321012345678 42 2026072214 -777 314159265358979 -123456789 8675309 55555555555 -4200000000000000001"
read -r -a SEEDLIST <<< "${SEEDS:-$DEFAULT_SEEDS}"

mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)

for seed in "${SEEDLIST[@]}"; do
  for run in r1 r2; do
    if [ -f "$OUT/ten-$seed-$run.json" ]; then continue; fi
    echo "$(date +%H:%M:%S) starting seed=$seed $run" >> "$OUT/tenseed-progress.txt"
    "$RUN" "$PROBE_SERVER" "$seed" rows "$OUT/ten-$seed-$run.json" "$RADIUS" > "$OUT/ten-$seed-$run.log" 2>&1
    if [ ! -f "$OUT/ten-$seed-$run.json" ]; then
      echo "$(date +%H:%M:%S) FAILED seed=$seed $run" >> "$OUT/tenseed-progress.txt"
    fi
  done
done
echo "$(date +%H:%M:%S) ALL_DONE" >> "$OUT/tenseed-progress.txt"
