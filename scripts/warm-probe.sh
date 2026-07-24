#!/usr/bin/env bash
# Warm multi-seed probe: boots the GTNH server ONCE, then generates+hashes a world per seed
# inside the same JVM (probe mod recycles dim 0 with a fresh save handler between seeds).
#
# Usage: warm-probe.sh <server-dir> <seeds> <order> <out-template> [radius]
#   seeds:        comma-separated list, or @/path/to/file (whitespace/comma separated)
#   order:        rows | cols | rows-reverse | spiral
#   out-template: /path/foo.json -> /path/foo-<seed>.json per seed ({seed} placeholder also works)
#
# ONLY valid with the gtnhdeterminism fix jar installed: identity-hash state is constant
# within a JVM, so warm runs cannot stand in for cold-launch variance tests on stock jars.
# Requires Java 17-21 as $PROBE_JAVA (or java), worldgenprobe >= 0.3 in <server-dir>/mods.
set -euo pipefail

SERVER_DIR=$1
SEEDS=$2
ORDER=$3
OUT=$4
RADIUS=${5:-12}
JAVA_BIN=${PROBE_JAVA:-java}

cd "$SERVER_DIR"

# Per-server-dir guard: kernel-released on crash, no stale-lock cleanup, no pgrep
# (pgrep -f matches the invoking shell's own wrapper cmdline and false-positives).
exec 9>".probe.lock"
flock -n 9 || { echo "another probe run holds $SERVER_DIR/.probe.lock; aborting" >&2; exit 1; }

rm -rf World world
if [ ! -f server.properties.bak ] && [ -f server.properties ]; then cp server.properties server.properties.bak; fi
# level-seed is irrelevant: the boot world is discarded before the first warm slot.
cat > server.properties <<EOF
allow-nether=true
level-name=World
level-seed=1
level-type=rwg
online-mode=false
snooper-enabled=false
max-tick-time=-1
motd=worldgen probe (warm)
EOF
echo "eula=true" > eula.txt

LAUNCH_JAR=$(ls lwjgl3ify-forgePatches.jar 2>/dev/null || ls forge-*.jar 2>/dev/null | head -1)
if [ -z "$LAUNCH_JAR" ]; then echo "No launch jar found in $SERVER_DIR" >&2; exit 1; fi
JAVA_ARGS=""
if [ -f java9args.txt ]; then JAVA_ARGS="@java9args.txt"; fi

"$JAVA_BIN" $JAVA_ARGS \
  -Xmx6G -Xms6G \
  -Dprobe.order="$ORDER" -Dprobe.radius="$RADIUS" -Dprobe.out="$OUT" -Dprobe.seeds="$SEEDS" \
  -Dprobe.tedetail="${PROBE_TEDETAIL:-false}" -Dprobe.search="${PROBE_SEARCH:-false}" -Dprobe.dim0only="${PROBE_DIM0ONLY:-false}" ${PROBE_DUMP:+-Dprobe.dump=$PROBE_DUMP} ${PROBE_TERAW:+-Dprobe.teraw=$PROBE_TERAW} ${PROBE_STATICSWEEP:+-Dprobe.staticsweep=$PROBE_STATICSWEEP} ${PROBE_CX:+-Dprobe.cx=$PROBE_CX} ${PROBE_CZ:+-Dprobe.cz=$PROBE_CZ} \
  -Dfml.readTimeout=180 -Dfml.queryResult=confirm \
  -jar "$LAUNCH_JAR" nogui < /dev/null

echo "warm batch complete: template $OUT seeds $SEEDS"
