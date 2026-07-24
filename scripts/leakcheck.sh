#!/usr/bin/env bash
# 20-cycle warm-mode leak check: run a 20-seed warm batch at small radius and sample
# jmap -histo early (slot ~4) and late (slot ~18); WorldServer/WorldServerMulti instance
# counts must not grow (dead worlds must be collectible).
set -u
SCRIPTS=~/Dropbox/OrderedSetCode/cloned-gtnh/gtnh-determinism/scripts
S=${PROBE_SERVER:?set PROBE_SERVER}
OUT=${LEAK_OUT:?set LEAK_OUT}
JDK=~/.gradle/jdks/azul_systems__inc_-17-amd64-linux.2/bin
export PROBE_JAVA=$JDK/java
mkdir -p "$OUT"
SEEDS=$(seq 1000 1019 | paste -sd,)
"$SCRIPTS/warm-probe.sh" "$S" "$SEEDS" rows "$OUT/leak.json" 2 > "$OUT/leak.log" 2>&1 &
DRIVER=$!

sample() { # label slot-marker
  local label=$1 marker=$2
  until grep -q "warm slot $marker" "$OUT/leak.log" 2>/dev/null; do
    kill -0 $DRIVER 2>/dev/null || return 1
    sleep 2
  done
  local pid=$(pgrep -f "probe.seeds=1000," | head -1)
  [ -n "$pid" ] && $JDK/jmap -histo:live "$pid" 2>/dev/null \
    | grep -E "net.minecraft.world.WorldServer" > "$OUT/histo-$label.txt"
  echo "sampled $label:"; cat "$OUT/histo-$label.txt" 2>/dev/null
}
sample early "5/20"
# force a GC before the late sample so only truly-pinned worlds remain
sample late "19/20"
wait $DRIVER
echo "batch done; early vs late WorldServer counts:"
paste <(awk '{print $2, $4}' "$OUT/histo-early.txt") <(awk '{print $2, $4}' "$OUT/histo-late.txt")
