#!/usr/bin/env bash
# Bit-exactness check for seedsearch/vein_predict.py against a real JVM running GT's own XSTR,
# across every dimension GT generates ore veins in (Overworld, Nether, TheEnd, Twilight Forest).
#
# Nothing else in the repo exercises dim != 0, so this is the only test that can catch a sign or
# shift error in the dimension byte of orevein_seed, which occupies bits 56-63 of the hash. Run it
# before quoting any non-overworld number.
#
# XSTR.java is copied verbatim from forks/GT5-Unofficial (package line blanked). No Guava needed:
# the mix table is read from the same JSON the Python side reads, so this measures the RNG walk.
set -euo pipefail
cd "$(dirname "$0")"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
javac -d "$tmp" XSTR.java VeinSweep.java
java -cp "$tmp" VeinSweep ../data/oremixes-gtnh-daily707.json > "$tmp/sweep.txt"
PYTHONPATH=.. python3 - "$tmp/sweep.txt" <<'PY'
import sys
import vein_predict as vp

bad = n = none_rows = 0
by_dim = {}
for line in open(sys.argv[1]):
    p = line.split()
    seed, dim, ox, oz = int(p[0]), int(p[1]), int(p[2]), int(p[3])
    token = vp.dim_token(dim)
    mix, attempt = vp.predict(seed, ox, oz, dim=dim, token=token)
    if p[4] == "NONE":
        none_rows += 1
        if mix is not None:
            bad += 1
            print("MISMATCH (JVM found none, python did):", line.strip())
        continue
    n += 1
    by_dim[dim] = by_dim.get(dim, 0) + 1
    if mix is None or mix["name"] != p[4] or attempt != int(p[5]):
        bad += 1
        if bad <= 5:
            got = "none" if mix is None else f"{mix['name']}@{attempt}"
            print(f"MISMATCH identity: {line.strip()} -> python {got}")
        continue
    g = vp.vein_geometry(mix, ox, oz, dim=dim, world_seed=seed)
    want = (int(p[6]), int(p[7]), int(p[8]), int(p[9]), int(p[10]))
    got = (g["tMinY"], g["bbox"][0], g["bbox"][2], g["bbox"][1], g["bbox"][3])
    if got != want:
        bad += 1
        if bad <= 5:
            print(f"MISMATCH geometry: {line.strip()} -> python {got}")

print(f"{n-bad}/{n} match the JVM reference ({none_rows} cells drew no eligible mix in 64 attempts)")
for dim in sorted(by_dim):
    print(f"  dim {dim:>3} ({vp.dim_token(dim)}): {by_dim[dim]} cells")
sys.exit(1 if bad else 0)
PY
