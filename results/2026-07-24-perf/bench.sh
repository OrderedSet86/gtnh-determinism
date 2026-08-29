#!/usr/bin/env bash
set -euo pipefail
SC=$1; R=$2
export PROBE_JAVA=$HOME/.gradle/jdks/azul_systems__inc_-17-amd64-linux.2/bin/java
export PROBE_PORT=25585 PROBE_SEARCH=true PROBE_DIM0ONLY=true PROBE_XMX=10G
SEEDS="201,202,203,204,205"

run() { # name jar nohash jvmflags
  local name=$1 jar=$2 nohash=$3 flags=$4
  rm -f "$SC/server-2.8.4/mods/"worldgenprobe-*.jar
  cp "$jar" "$SC/server-2.8.4/mods/"
  local t0=$(date +%s)
  PROBE_NOHASH=$nohash PROBE_JVMFLAGS="$flags" \
    "$R/scripts/warm-probe.sh" "$SC/server-2.8.4" "$SEEDS" rows "$SC/perf/$name.json" 15 \
    > "$SC/perf/$name.log" 2>&1
  local t1=$(date +%s)
  # per-seed = time from slot1 world-ready start to last write, /5. Extract from log:
  echo "$name TOTAL_WALL=$((t1-t0))s"
  grep -E "warm slot|wrote 961|wrote 0" "$SC/perf/$name.log" | grep -oE "^\[[0-9:]+\]|slot [0-9]+/5|in [0-9]+ ms" | paste - - - 2>/dev/null | head -20 || true
}

OLD=$R/jars/worldgenprobe-0.6.jar
NEW=$R/probe-build/build/libs/worldgenprobe-2e6b9b2-main+2e6b9b23cf-dirty.jar
run b0-baseline      "$OLD" false ""
run b1-fastscan-hash "$NEW" false ""
run b2-fastscan-nohash "$NEW" true ""
run b3-parallelgc    "$NEW" true "-XX:+UseParallelGC"
echo BENCH_DONE
