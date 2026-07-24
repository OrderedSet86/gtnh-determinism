#!/usr/bin/env bash
# Stock-vs-fix worldgen effects harness.
#
# Runs the same seed list twice: arm "stock" (gtnhdeterminism jar REMOVED, cold boot per run,
# R repeats per seed to capture stock launch-to-launch variance) and arm "fixed" (jar installed,
# warm batches, 2 repeats of the first seed as a determinism regression check).
# Then run effects-report.py <out>/stock <out>/fixed for the comparison report.
#
# Usage: effects-ab.sh <server-dir> <seed-file> <out-root> [radius] [stock-repeats]
# Cold runs cost ~90 s each: N seeds × R repeats; budget accordingly.
set -euo pipefail

SERVER_DIR=$1
SEED_FILE=$2
OUT=$3
RADIUS=${4:-15}
REPEATS=${5:-2}
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
FIXJAR_GLOB="gtnhdeterminism-*.jar"

mkdir -p "$OUT/stock" "$OUT/fixed" "$OUT/hold"
OUT=$(cd "$OUT" && pwd)
P="$OUT/ab-progress.txt"
SEEDS=$(tr ', ' '\n\n' < "$SEED_FILE" | grep -vE '^\s*$')

restore_jar() {
  if ls "$OUT"/hold/$FIXJAR_GLOB >/dev/null 2>&1; then mv "$OUT"/hold/$FIXJAR_GLOB "$SERVER_DIR/mods/"; fi
}
trap restore_jar EXIT

# ---- stock arm: jar out, cold boots, R repeats per seed
if ! ls "$SERVER_DIR"/mods/$FIXJAR_GLOB >/dev/null 2>&1 && ! ls "$OUT"/hold/$FIXJAR_GLOB >/dev/null 2>&1; then
  echo "WARNING: no $FIXJAR_GLOB found in mods/ or hold/ — is this really the fixed pack?" | tee -a "$P"
fi
if ls "$SERVER_DIR"/mods/$FIXJAR_GLOB >/dev/null 2>&1; then
  mv "$SERVER_DIR"/mods/$FIXJAR_GLOB "$OUT/hold/"
fi
echo "$(date +%H:%M:%S) STOCK ARM (jar removed, $REPEATS repeats/seed)" | tee -a "$P"
for s in $SEEDS; do
  for r in $(seq 1 "$REPEATS"); do
    f="$OUT/stock/seed-$s.r$r.json"
    [ -f "$f" ] && continue
    echo "$(date +%H:%M:%S) stock $s r$r" >> "$P"
    PROBE_SEARCH=true "$SCRIPT_DIR/run-probe.sh" "$SERVER_DIR" "$s" rows "$f" "$RADIUS" \
      > "$OUT/stock/seed-$s.r$r.log" 2>&1 || echo "$(date +%H:%M:%S) FAILED stock $s r$r" >> "$P"
  done
done

# ---- fixed arm: jar back in, cold boots too (symmetric evidence; warm-batch small-ore
# bookkeeping noise documented in docs/harness-speed.md would otherwise bias smalls stats).
# 2 repeats double as the fix-jar determinism regression (must be byte-identical).
restore_jar
echo "$(date +%H:%M:%S) FIXED ARM (jar installed, cold, $REPEATS repeats/seed)" | tee -a "$P"
for s in $SEEDS; do
  for r in $(seq 1 "$REPEATS"); do
    f="$OUT/fixed/seed-$s.r$r.json"
    [ -f "$f" ] && continue
    echo "$(date +%H:%M:%S) fixed $s r$r" >> "$P"
    PROBE_SEARCH=true "$SCRIPT_DIR/run-probe.sh" "$SERVER_DIR" "$s" rows "$f" "$RADIUS" \
      > "$OUT/fixed/seed-$s.r$r.log" 2>&1 || echo "$(date +%H:%M:%S) FAILED fixed $s r$r" >> "$P"
  done
done
echo "$(date +%H:%M:%S) AB RUNS COMPLETE" | tee -a "$P"
