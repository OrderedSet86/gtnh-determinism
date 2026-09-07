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
#
# A/B levers, in increasing order of blast radius:
#   PROBE_EXTRA_ARGS / PROBE_JVMFLAGS  JVM flags, e.g. -Dgtnhdet.orepin=false
#   PROBE_CONFIG                       mod config keys, e.g.
#                                        config/GregTech/WorldGeneration.cfg:generateUndergroundDirtGen=false
#                                      A key matching 0 or >1 lines aborts the run.
#
# Every run stamps World/.probe-provenance.json with the seed, order, jar md5s and any config
# override, and the diff tools print it. That is how a confounded A/B announces itself.
set -euo pipefail

SERVER_DIR=$1
SEED=$2
ORDER=$3
OUT=$4
RADIUS=${5:-12}
JAVA_BIN=${PROBE_JAVA:-java}

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)   # resolve before the cd below

cd "$SERVER_DIR"

# Serialize against other users of THIS server dir only (probe-queue daemons take the same
# lock). The old global pgrep guard falsely serialized parallel instances in separate dirs
# and aborted when an unrelated server ran anywhere on the machine.
exec 9>".probe.lock"
if ! flock -w 600 9; then
  echo "Server dir $SERVER_DIR is locked by another probe run; aborting" >&2
  exit 1
fi

# PROBE_CONFIG: run an arm that differs by MOD CONFIG rather than by a system property.
#   PROBE_CONFIG="config/GregTech/WorldGeneration.cfg:generateUndergroundDirtGen=false
#                 config/GregTech/WorldGeneration.cfg:generateUndergroundGravelGen=false"
# Entries are <path-relative-to-server-dir>:<Key>=<Value>, whitespace or newline separated.
#
# Every other A/B lever here is a -D flag. A config arm used to mean running sed by hand, and a sed
# that matches nothing does not fail — it leaves the default in place and yields a clean-looking
# number for the arm you thought you were not running. So a key that matches 0 lines, or matches in
# two sections, kills the run.
CFG_TOUCHED=()
CFG_SPECS=()
restore_cfgs() {
  local f
  [ ${#CFG_TOUCHED[@]} -eq 0 ] && return 0
  for f in "${CFG_TOUCHED[@]}"; do
    [ -f "$f.probecfgbak" ] && mv -f "$f.probecfgbak" "$f"
  done
  return 0
}
# Restore even on crash: this server dir is shared with other runs and other people.
trap restore_cfgs EXIT

for spec in ${PROBE_CONFIG:-}; do
  cfg_file=${spec%%:*}; cfg_kv=${spec#*:}; cfg_key=${cfg_kv%%=*}; cfg_val=${cfg_kv#*=}
  if [ ! -f "$cfg_file" ]; then
    echo "PROBE_CONFIG: no such file: $cfg_file (relative to $SERVER_DIR)" >&2; exit 1
  fi
  # The key is user text and lands in a regex; Forge keys contain dots, spaces and quotes.
  cfg_key_re=$(printf '%s' "$cfg_key" | sed 's/[][\.*^$(){}?+|\/]/\\&/g')
  cfg_val_esc=$(printf '%s' "$cfg_val" | sed 's/[\\&|]/\\&/g')
  n=$(grep -cE "^[[:space:]]*[BISDN]:${cfg_key_re}=" "$cfg_file" || true)
  if [ "$n" -ne 1 ]; then
    echo "PROBE_CONFIG: key '$cfg_key' matched $n lines in $cfg_file; need exactly 1." >&2
    echo "  0 means wrong key or wrong file. >1 means the key exists in two sections." >&2
    exit 1
  fi
  [ -f "$cfg_file.probecfgbak" ] || cp "$cfg_file" "$cfg_file.probecfgbak"
  CFG_TOUCHED+=("$cfg_file")
  # Keep the indent and the B:/I:/S: type prefix; rewrite only the value.
  sed -i -E "s|^([[:space:]]*)([BISDN]:${cfg_key_re})=.*|\1\2=${cfg_val_esc}|" "$cfg_file"
  if ! grep -qE "^[[:space:]]*[BISDN]:${cfg_key_re}=${cfg_val_esc}[[:space:]]*$" "$cfg_file"; then
    echo "PROBE_CONFIG: failed to set $cfg_key=$cfg_val in $cfg_file" >&2; exit 1
  fi
  CFG_SPECS+=("$spec")
  echo "PROBE_CONFIG: $cfg_file  $cfg_key=$cfg_val"
done

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
# PROBE_JVMFLAGS is honoured too. It used to be silently dropped here while warm-probe.sh,
# probe-queue.sh and prefilter.sh all honoured it, so writeups that set it here ran an unflagged arm
# and read the result as a pass (results/2026-09-05-f9-block-impact used _JAVA_OPTIONS to work around
# it). Same failure class as a config edit that matches nothing.
"$JAVA_BIN" $JAVA_ARGS \
  -Xmx6G -Xms6G \
  ${PROBE_JVMFLAGS:-} \
  ${PROBE_EXTRA_ARGS:-} \
  -Dprobe.order="$ORDER" -Dprobe.radius="$RADIUS" -Dprobe.out="$OUT" -Dprobe.tedetail="${PROBE_TEDETAIL:-false}" -Dprobe.search="${PROBE_SEARCH:-false}" -Dprobe.entities="${PROBE_ENTITIES:-false}" ${PROBE_DIM:+-Dprobe.dim=$PROBE_DIM} ${PROBE_TFFEATURES:+-Dprobe.tffeatures=$PROBE_TFFEATURES} ${PROBE_DUMP:+-Dprobe.dump=$PROBE_DUMP} ${PROBE_TERAW:+-Dprobe.teraw=$PROBE_TERAW} ${PROBE_CX:+-Dprobe.cx=$PROBE_CX} ${PROBE_CZ:+-Dprobe.cz=$PROBE_CZ} \
  -Dfml.readTimeout=180 -Dfml.queryResult=confirm \
  -jar "$LAUNCH_JAR" nogui < /dev/null

# Forge rewrites config files in place on shutdown (probe-farm.sh real-copies config/ per shard for
# exactly this reason). Assert the arm still held while the world was generated, BEFORE the EXIT trap
# restores the originals. A value that moved mid-run means the world does not match the arm label, and
# a wrong number is worse than no number.
for spec in ${CFG_SPECS[@]+"${CFG_SPECS[@]}"}; do
  cfg_file=${spec%%:*}; cfg_kv=${spec#*:}; cfg_key=${cfg_kv%%=*}; cfg_val=${cfg_kv#*=}
  cfg_key_re=$(printf '%s' "$cfg_key" | sed 's/[][\.*^$(){}?+|\/]/\\&/g')
  cfg_val_esc=$(printf '%s' "$cfg_val" | sed 's/[\\&|]/\\&/g')
  if ! grep -qE "^[[:space:]]*[BISDN]:${cfg_key_re}=${cfg_val_esc}[[:space:]]*$" "$cfg_file"; then
    echo "PROBE_CONFIG: $cfg_key no longer reads $cfg_val in $cfg_file after the run." >&2
    echo "  The generated world does not match the arm it is labelled with. Run voided." >&2
    exit 1
  fi
done

# Stamp provenance INSIDE the world, so it survives the cp -a that every measurement does and gets
# printed by the diff tools without anyone having to remember it exists.
if [ -d World ]; then
  PROV=(--server-dir . --param "seed=$SEED" --param "order=$ORDER" --param "radius=$RADIUS"
        --param "level_type=rwg")
  if [ -n "${PROBE_CX:-}" ]; then PROV+=(--param "cx=$PROBE_CX"); fi
  if [ -n "${PROBE_CZ:-}" ]; then PROV+=(--param "cz=$PROBE_CZ"); fi
  if [ -n "${PROBE_DIM:-}" ]; then PROV+=(--param "dim=$PROBE_DIM"); fi
  if [ -n "${PROBE_SEARCH:-}" ]; then PROV+=(--param "search=$PROBE_SEARCH"); fi
  if [ -n "${PROBE_EXTRA_ARGS:-}" ]; then PROV+=(--param "extra_args=$PROBE_EXTRA_ARGS"); fi
  if [ -n "${PROBE_JVMFLAGS:-}" ]; then PROV+=(--param "jvmflags=$PROBE_JVMFLAGS"); fi
  for spec in ${CFG_SPECS[@]+"${CFG_SPECS[@]}"}; do PROV+=(--config-set "$spec"); done
  python3 "$SCRIPT_DIR/probe-provenance.py" write World "${PROV[@]}" || \
    echo "warning: provenance stamp failed; this world will read as UNKNOWN in diffs" >&2
fi

echo "probe run complete: $OUT"
