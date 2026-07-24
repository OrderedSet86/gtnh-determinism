#!/usr/bin/env bash
# CRIU checkpoint/restore probe driver: boots the GTNH server once to the probe mod's
# FMLLoadCompleteEvent barrier (mods loaded, no world, level-seed not yet parsed),
# checkpoints the frozen JVM, then restores it once per seed (~seconds instead of ~82s boot).
# Each restore resumes at the barrier, reads go.json (seed + probe params), injects the seed
# into the live PropertyManager, and continues into a completely normal cold-boot code path.
#
# Usage: criu-harness.sh <server-dir> <images-dir> checkpoint
#        criu-harness.sh <server-dir> <images-dir> run <seed> <order> <out.json> [radius]
#
# Requires: criu (root or CAP_SYS_ADMIN — typically run under sudo), worldgenprobe >= 0.3.
# Sequential restores only: CRIU restores the original PID and the bound server port, so
# parallel restores need PID/net namespaces (not implemented here).
set -euo pipefail

SERVER_DIR=$1
IMAGES_DIR=$2
CMD=$3
CTL="$SERVER_DIR/.criu-ctl"
JAVA_BIN=${PROBE_JAVA:-java}

require_criu() {
  command -v criu >/dev/null || { echo "criu not installed" >&2; exit 1; }
  criu check --unprivileged 2>/dev/null || criu check || { echo "criu check failed (need root/CAP_SYS_ADMIN?)" >&2; exit 1; }
}

case "$CMD" in
checkpoint)
  require_criu
  cd "$SERVER_DIR"
  exec 9>".probe.lock"
  flock -n 9 || { echo "another probe run holds $SERVER_DIR/.probe.lock; aborting" >&2; exit 1; }
  rm -rf World world "$CTL"
  mkdir -p "$CTL" "$IMAGES_DIR"
  if [ ! -f server.properties.bak ] && [ -f server.properties ]; then cp server.properties server.properties.bak; fi
  cat > server.properties <<EOF
allow-nether=true
level-name=World
level-seed=1
level-type=rwg
online-mode=false
snooper-enabled=false
max-tick-time=-1
motd=worldgen probe (criu)
EOF
  echo "eula=true" > eula.txt
  LAUNCH_JAR=$(ls lwjgl3ify-forgePatches.jar 2>/dev/null || ls forge-*.jar 2>/dev/null | head -1)
  JAVA_ARGS=""
  if [ -f java9args.txt ]; then JAVA_ARGS="@java9args.txt"; fi
  # -XX:-UsePerfData: no hsperfdata mmap for criu to trip on; stdin </dev/null so the
  # console-reader thread EOFs and exits before the dump. probe.order/out are placeholders —
  # every restore overrides them from go.json.
  setsid "$JAVA_BIN" $JAVA_ARGS \
    -Xmx6G -Xms6G -XX:-UsePerfData \
    -Dprobe.criu="$CTL" -Dprobe.order=rows -Dprobe.radius=4 -Dprobe.out=placeholder.json \
    -Dfml.readTimeout=180 -Dfml.queryResult=confirm \
    -jar "$LAUNCH_JAR" nogui < /dev/null > "$IMAGES_DIR/boot.log" 2>&1 &
  echo "booting to barrier (log: $IMAGES_DIR/boot.log)…"
  for i in $(seq 1 300); do
    [ -f "$CTL/ready" ] && break
    kill -0 "$!" 2>/dev/null || { echo "server died before barrier; see $IMAGES_DIR/boot.log" >&2; exit 1; }
    sleep 1
  done
  [ -f "$CTL/ready" ] || { echo "barrier never reached" >&2; exit 1; }
  PID=$(cat "$CTL/ready")
  echo "barrier reached (pid $PID), dumping…"
  criu dump -t "$PID" --images-dir "$IMAGES_DIR" --file-locks --shell-job -v1
  echo "checkpoint complete: $IMAGES_DIR"
  ;;
run)
  require_criu
  SEED=$4; ORDER=$5; OUT=$6; RADIUS=${7:-12}
  cd "$SERVER_DIR"
  exec 9>".probe.lock"
  flock -n 9 || { echo "another probe run holds $SERVER_DIR/.probe.lock; aborting" >&2; exit 1; }
  rm -rf World world
  rm -f "$OUT"
  printf '{"seed": %s, "order": "%s", "radius": %s, "out": "%s"}\n' "$SEED" "$ORDER" "$RADIUS" "$OUT" > "$CTL/go.json"
  T0=$(date +%s)
  criu restore --images-dir "$IMAGES_DIR" --file-locks --shell-job -d -v1
  echo "restored; waiting for $OUT…"
  for i in $(seq 1 600); do
    [ -f "$OUT" ] && break
    sleep 1
  done
  rm -f "$CTL/go.json"
  [ -f "$OUT" ] || { echo "probe JSON never appeared" >&2; exit 1; }
  echo "seed $SEED done in $(( $(date +%s) - T0 ))s -> $OUT"
  # the restored server shuts itself down after the probe; wait for it to release the port
  for i in $(seq 1 60); do
    kill -0 "$(cat "$CTL/ready")" 2>/dev/null || break
    sleep 1
  done
  ;;
*)
  echo "unknown command: $CMD (use checkpoint | run)" >&2; exit 1 ;;
esac
