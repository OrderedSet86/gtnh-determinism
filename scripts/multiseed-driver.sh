#!/usr/bin/env bash
# Launch-test pairs (rows walk, fresh JVM each) on 10 seeds with the full fix set installed.
set -u
S=/tmp/claude-1000/-home-order-Dropbox-OrderedSetCode-cloned-gtnh-all-gtnh/b4f22f7c-3a90-4302-aa6b-88ea092d2100/scratchpad
R=/home/order/Dropbox/OrderedSetCode/cloned-gtnh/gtnh-determinism/scripts/run-probe.sh
export PROBE_JAVA=/home/order/.gradle/jdks/azul_systems__inc_-17-amd64-linux.2/bin/java
cd "$S/server"

SEEDS=(1234567890 -987654321012345678 42 2026072214 -777 314159265358979 -123456789 8675309 55555555555 -4200000000000000001)

for seed in "${SEEDS[@]}"; do
  for run in r1 r2; do
    if [ -f "$S/ten-$seed-$run.json" ]; then continue; fi
    echo "$(date +%H:%M:%S) starting seed=$seed $run" >> "$S/tenseed-progress.txt"
    "$R" . "$seed" rows "$S/ten-$seed-$run.json" 8 > "$S/ten-$seed-$run.log" 2>&1
    if [ ! -f "$S/ten-$seed-$run.json" ]; then
      echo "$(date +%H:%M:%S) FAILED seed=$seed $run" >> "$S/tenseed-progress.txt"
    fi
  done
done
echo "$(date +%H:%M:%S) ALL_DONE" >> "$S/tenseed-progress.txt"
