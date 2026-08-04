#!/usr/bin/env bash
# Bit-exactness check for seedsearch/uo_oil.py against a real JVM running GT's own XSTR.
# XSTR.java here is copied verbatim from forks/GT5-Unofficial (package line blanked).
# Guava supplies HashBiMap, whose ITERATION ORDER decides which fluid a field gets --
# verified identical under guava 14.0.1 and the 17.0 that Forge ships for 1.7.10.
set -euo pipefail
cd "$(dirname "$0")"
GUAVA=${GUAVA:-$(find "$HOME/Dropbox/OrderedSetCode/cloned-gtnh/all-gtnh/DreamAssemblerXXL" \
  -name 'guava-17.0.jar' 2>/dev/null | head -1)}
[ -n "$GUAVA" ] || { echo "no guava-17.0.jar found; set GUAVA=/path/to/guava.jar"; exit 1; }
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
javac -cp "$GUAVA" -d "$tmp" XSTR.java Sweep.java
java -cp "$tmp:$GUAVA" Sweep > "$tmp/sweep.txt"
PYTHONPATH=.. python3 - "$tmp/sweep.txt" <<'PY'
import sys; from uo_oil import oil_at_chunk
bad = n = 0
for line in open(sys.argv[1]):
    p = line.split()
    if p[3] == "NULL": continue
    n += 1
    if oil_at_chunk(int(p[0]), int(p[1]), int(p[2])) != (p[3], int(p[4]), int(p[5])):
        bad += 1
        if bad <= 5: print("MISMATCH:", line.strip())
print(f"{n-bad}/{n} match the JVM reference")
sys.exit(1 if bad else 0)
PY
