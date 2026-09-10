#!/usr/bin/env bash
# Four cold probes for the README launch-variance image.
#
#   stock r1, stock r2   -- gtnhdeterminism absent
#   fixed r1, fixed r2   -- gtnhdeterminism present
#
# One seed, one walk order, four separate JVMs. The pairs (r1,r2) within an arm are the whole
# experiment: same seed, same route, so anything that differs between them is launch variance.
#
# WARM MODE IS FORBIDDEN HERE, not merely slower. Identity-hash iteration order — F1's mechanism,
# and FML's generator dispatch order — is constant within a JVM, so a warm batch would erase the
# exact signal rows 1 and 2 exist to show. See probe-build WorldgenProbe.java:793.
#
# run-probe.sh does `rm -rf World` at the start of every run, so each world is copied out
# immediately after its probe returns. The server saves on shutdown; the lost-region-write hazard
# that motivates the daemon's save=true job is warm-path only.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
C=$HOME/.cache/gtnh-determinism
OUT=${1:-$C/splash-out}

SEED=-1636594104014467454
ORDER=rows
RADIUS=48          # villages reach +-7.5 chunks from their start; the farthest is at chunk z=-36
PORT=25820

export PROBE_JAVA=${PROBE_JAVA:-$HOME/.gradle/jdks/azul_systems__inc_-17-amd64-linux.2/bin/java}
mkdir -p "$OUT"

for arm in stock fixed; do
  SRV=$C/splash-$arm
  for r in 1 2; do
    tag=$arm-r$r
    [ -d "$OUT/world-$tag" ] && { echo "skip $tag (world already copied)"; continue; }
    echo "$(date +%H:%M:%S) $tag"
    PROBE_CX=0 PROBE_CZ=0 PROBE_PORT=$((PORT++)) \
    PROBE_JVMFLAGS="-Dsplash.enable=true" \
      "$REPO/scripts/run-probe.sh" "$SRV" "$SEED" "$ORDER" "$OUT/$tag.json" "$RADIUS" \
      > "$OUT/$tag.log" 2>&1
    cp -a "$SRV/World" "$OUT/world-$tag"
  done
done

echo
echo "$(date +%H:%M:%S) done. provenance:"
for d in "$OUT"/world-*/; do
  echo "== $(basename "$d")"
  python3 -c "
import json,sys
p=json.load(open('$d/.probe-provenance.json'))
print('   seed',p.get('seed'),'order',p.get('order'),'radius',p.get('radius'),'level',p.get('level_type'))
print('   jars:', ' '.join(sorted(k for k in (p.get('jars') or {}))))" 2>/dev/null || echo "   (no provenance)"
done
