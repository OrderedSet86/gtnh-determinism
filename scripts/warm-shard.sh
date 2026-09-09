#!/usr/bin/env bash
# Shard a warm multi-seed probe across N server copies running concurrently.
#
#   warm-shard.sh <seedfile> <out-dir> <radius> <order[,order2...]> [jvmflags]
#
# Chunk generation is single-threaded inside one JVM — measured, one probe pins exactly one core —
# so a 16-core box running a single warm batch is ~94% idle. This splits the seed list across
# SHARD_DIRS server copies and runs them at once. Measured on 24 seeds at radius 30: ~15 min per
# walk order sequentially, ~4 min sharded five ways.
#
# Each shard runs every requested walk order back-to-back on ITS OWN seeds, so comparing orders
# never crosses a shard boundary and one JVM covers both arms of a determinism check.
#
# Env:
#   SHARD_DIRS   space-separated server dirs (default: ~/.cache/gtnh-determinism/beta3-s{1..5})
#   PROBE_XMX    heap per shard (default 6G). N * (XMX + 1.5G) must fit in MemAvailable.
#   PROBE_PORT0  first port; shard i uses PROBE_PORT0 + i (default 25700)
#   PROBE_JAVA   JVM (default: first JDK under ~/.gradle/jdks)
#
# Output: <out-dir>/<order>-shard<i>.log per shard, plus per-seed json under <out-dir>/<order>/.
# Analysis must key on the seed each trace line carries, never on log position — warm boots its own
# world on level-seed=1 before the batch and those traces are interleaved into the same file.
set -euo pipefail

[ $# -ge 4 ] || { sed -n '2,22p' "$0" >&2; exit 1; }
SEEDFILE=$1; OUT=$2; RADIUS=$3; ORDERS=$4; JVMFLAGS=${5:-}

DIRS=(${SHARD_DIRS:-$HOME/.cache/gtnh-determinism/beta3-s1 $HOME/.cache/gtnh-determinism/beta3-s2 \
  $HOME/.cache/gtnh-determinism/beta3-s3 $HOME/.cache/gtnh-determinism/beta3-s4 \
  $HOME/.cache/gtnh-determinism/beta3-s5})
N=${#DIRS[@]}
PORT0=${PROBE_PORT0:-25700}
JAVA_BIN=${PROBE_JAVA:-$(ls -d "$HOME"/.gradle/jdks/*/bin/java 2>/dev/null | head -1)}
[ -x "$JAVA_BIN" ] || { echo "no JDK found; set PROBE_JAVA" >&2; exit 1; }

# Refuse to start if the shards cannot fit — a swapping box is slower than not sharding at all.
NEED=$(( N * 8 ))
AVAIL=$(free -g | awk '/Mem:/{print $7}')
[ "$AVAIL" -ge "$NEED" ] || { echo "need ~${NEED}G MemAvailable for $N shards, have ${AVAIL}G" >&2; exit 1; }

mkdir -p "$OUT"
# Round-robin so a slow seed cannot pile onto one shard.
awk 'NF' "$SEEDFILE" | awk -v n="$N" '{print (NR-1)%n, $1}' > "$OUT/.assign"
for i in $(seq 0 $((N-1))); do
  awk -v i="$i" '$1==i {print $2}' "$OUT/.assign" | paste -sd, - > "$OUT/.seeds$i"
done

echo "sharding $(awk 'NF' "$SEEDFILE" | wc -l) seeds over $N shards, orders: $ORDERS, radius $RADIUS"
pids=()
for i in $(seq 0 $((N-1))); do
  SEEDS=$(cat "$OUT/.seeds$i")
  [ -n "$SEEDS" ] || continue
  (
    for ord in ${ORDERS//,/ }; do
      mkdir -p "$OUT/$ord"
      PROBE_SEARCH=${PROBE_SEARCH:-false} PROBE_NOHASH=${PROBE_NOHASH:-true} \
      PROBE_CX=${PROBE_CX:-0} PROBE_CZ=${PROBE_CZ:-0} \
      PROBE_PORT=$((PORT0 + i)) PROBE_JAVA="$JAVA_BIN" PROBE_XMX=${PROBE_XMX:-6G} \
      PROBE_JVMFLAGS="$JVMFLAGS" \
        nice -n 19 "$(dirname "$0")/warm-probe.sh" "${DIRS[$i]}" "$SEEDS" "$ord" \
          "$OUT/$ord/seed-{seed}.json" "$RADIUS" > "$OUT/$ord-shard$i.log" 2>&1
    done
  ) &
  pids+=($!)
done

fail=0
for p in "${pids[@]}"; do wait "$p" || fail=1; done
[ "$fail" -eq 0 ] || { echo "at least one shard failed — check $OUT/*-shard*.log" >&2; exit 1; }
echo "done: $OUT"
