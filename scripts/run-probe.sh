#!/usr/bin/env bash
# Run the worldgen determinism probe against a GTNH server install.
#
# Usage: run-probe.sh <server-dir> <seed> <order> <out.json> [radius]
#   order: rows | cols | rows-reverse | spiral
#
# Each invocation deletes the world folder, sets the seed, boots the server headless,
# generates (2r+3)^2 chunks in the given order, writes per-chunk SHA-256 hashes to
# <out.json>, and shuts down. Compare two runs with diff-probe.py.
#
# Requires: Java 17-21 on PATH as $PROBE_JAVA (or java), the probe jar in <server-dir>/mods.
set -euo pipefail

SERVER_DIR=$1
SEED=$2
ORDER=$3
OUT=$4
RADIUS=${5:-12}
JAVA_BIN=${PROBE_JAVA:-java}

cd "$SERVER_DIR"

# Serialize against other users of THIS server dir only (probe-queue daemons take the same
# lock). The old global pgrep guard falsely serialized parallel instances in separate dirs
# and aborted when an unrelated server ran anywhere on the machine.
exec 9>".probe.lock"
if ! flock -w 600 9; then
  echo "Server dir $SERVER_DIR is locked by another probe run; aborting" >&2
  exit 1
fi

rm -rf World world
# server.properties: force seed, offline, no spawn protection surprises
if [ ! -f server.properties.bak ] && [ -f server.properties ]; then cp server.properties server.properties.bak; fi
cat > server.properties <<EOF
allow-nether=true
level-name=World
level-seed=$SEED
level-type=rwg
online-mode=false
snooper-enabled=false
max-tick-time=-1
server-port=${PROBE_PORT:-25565}
motd=worldgen probe
EOF
echo "eula=true" > eula.txt

# Find the forge/launcher jar the pack ships (lwjgl3ify packs use a startserver script; prefer its java args)
LAUNCH_JAR=$(ls lwjgl3ify-forgePatches.jar 2>/dev/null || ls forge-*.jar 2>/dev/null | head -1)
if [ -z "$LAUNCH_JAR" ]; then echo "No launch jar found in $SERVER_DIR" >&2; exit 1; fi

JAVA_ARGS=""
if [ -f java9args.txt ]; then JAVA_ARGS="@java9args.txt"; fi

# PROBE_EXTRA_ARGS: extra flags passed verbatim to the JVM, for fix-jar A/B levers and traces, e.g.
#   PROBE_EXTRA_ARGS="-Dgtnhdet.atomicdungeon=false -Dgtnhdet.traceslices=true"
"$JAVA_BIN" $JAVA_ARGS \
  -Xmx6G -Xms6G \
  ${PROBE_EXTRA_ARGS:-} \
  -Dprobe.order="$ORDER" -Dprobe.radius="$RADIUS" -Dprobe.out="$OUT" -Dprobe.tedetail="${PROBE_TEDETAIL:-false}" -Dprobe.search="${PROBE_SEARCH:-false}" -Dprobe.entities="${PROBE_ENTITIES:-false}" ${PROBE_DIM:+-Dprobe.dim=$PROBE_DIM} ${PROBE_TFFEATURES:+-Dprobe.tffeatures=$PROBE_TFFEATURES} ${PROBE_DUMP:+-Dprobe.dump=$PROBE_DUMP} ${PROBE_TERAW:+-Dprobe.teraw=$PROBE_TERAW} ${PROBE_CX:+-Dprobe.cx=$PROBE_CX} ${PROBE_CZ:+-Dprobe.cz=$PROBE_CZ} \
  -Dfml.readTimeout=180 -Dfml.queryResult=confirm \
  -jar "$LAUNCH_JAR" nogui < /dev/null

echo "probe run complete: $OUT"
