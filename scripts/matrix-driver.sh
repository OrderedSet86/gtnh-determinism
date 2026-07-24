#!/usr/bin/env bash
# Warm-mode verification matrix: 6 cold boots + 7 warm batches, then diff-probe checks.
set -u
SCRIPTS=~/Dropbox/OrderedSetCode/cloned-gtnh/gtnh-determinism/scripts
S=${PROBE_SERVER:?set PROBE_SERVER}
OUT=${MATRIX_OUT:?set MATRIX_OUT}
export PROBE_JAVA=~/.gradle/jdks/azul_systems__inc_-17-amd64-linux.2/bin/java
R=8
mkdir -p "$OUT"
P="$OUT/progress.txt"
note() { echo "$(date +%H:%M:%S) $1" >> "$P"; }

cold() { # seed
  [ -f "$OUT/cold-$1.json" ] && { note "SKIP cold $1"; return; }
  note "COLD $1 start"
  "$SCRIPTS/run-probe.sh" "$S" "$1" rows "$OUT/cold-$1.json" $R > "$OUT/cold-$1.log" 2>&1
  [ -f "$OUT/cold-$1.json" ] && note "COLD $1 done" || note "COLD $1 FAILED"
}
warm() { # tag seedlist
  local tag=$1 seeds=$2
  [ -f "$OUT/$tag.done" ] && { note "SKIP warm $tag"; return; }
  note "WARM $tag ($seeds) start"
  "$SCRIPTS/warm-probe.sh" "$S" "$seeds" rows "$OUT/$tag.json" $R > "$OUT/$tag.log" 2>&1
  touch "$OUT/$tag.done"
  note "WARM $tag done"
}

cold 88888888
cold 1234567890
cold 42
cold -777
cold 314159265358979
cold -987654321012345678

warm w1  "88888888,1234567890"
warm w1r "1234567890,88888888"
warm w2  "42,-777"
warm w2r "-777,42"
warm w3  "314159265358979,-987654321012345678"
warm w3r "-987654321012345678,314159265358979"
warm wself "88888888,88888888"

note "RUNS COMPLETE, diffing"
DP="$SCRIPTS/diff-probe.py"
check() { # label a b
  if python3 "$DP" "$2" "$3" > "$OUT/diff-$1.txt" 2>&1; then
    echo "PASS $1" >> "$OUT/results.txt"
  else
    echo "FAIL $1 (see diff-$1.txt)" >> "$OUT/results.txt"
  fi
}
rm -f "$OUT/results.txt"
check p1-slot1-sanity   "$OUT/cold-88888888.json"            "$OUT/w1-88888888.json"
check p1-forward        "$OUT/cold-1234567890.json"          "$OUT/w1-1234567890.json"
check p1-reverse        "$OUT/cold-88888888.json"            "$OUT/w1r-88888888.json"
check p1r-slot1-sanity  "$OUT/cold-1234567890.json"          "$OUT/w1r-1234567890.json"
check p2-slot1-sanity   "$OUT/cold-42.json"                  "$OUT/w2-42.json"
check p2-forward        "$OUT/cold--777.json"                "$OUT/w2--777.json"
check p2-reverse        "$OUT/cold-42.json"                  "$OUT/w2r-42.json"
check p2r-slot1-sanity  "$OUT/cold--777.json"                "$OUT/w2r--777.json"
check p3-slot1-sanity   "$OUT/cold-314159265358979.json"     "$OUT/w3-314159265358979.json"
check p3-forward        "$OUT/cold--987654321012345678.json" "$OUT/w3--987654321012345678.json"
check p3-reverse        "$OUT/cold-314159265358979.json"     "$OUT/w3r-314159265358979.json"
check p3r-slot1-sanity  "$OUT/cold--987654321012345678.json" "$OUT/w3r--987654321012345678.json"
check self-88888888     "$OUT/wself-88888888.json"           "$OUT/wself-88888888.json.slot2"
note "MATRIX COMPLETE"
cat "$OUT/results.txt" >> "$P"
