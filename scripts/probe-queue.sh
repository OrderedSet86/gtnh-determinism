#!/usr/bin/env bash
# Warm job-queue client for the probe daemon: boot the GTNH server ONCE, then submit
# worldgen jobs that each cost seconds instead of an 82s boot.
#
#   probe-queue.sh start  <server-dir> <control-dir>        boot server in daemon mode (backgrounded)
#   probe-queue.sh submit <control-dir> <seed> <out.json> [order=rows] [radius=8] [search=true]
#                         [tedetail=true] [teraw=cx,cz] [cx=N] [cz=N]     -> prints job id
#   probe-queue.sh wait   <control-dir> <job-id|all>        block until done/failed; exit 1 on failure
#   probe-queue.sh status <control-dir>                     queue/done/failed counts
#   probe-queue.sh stop   <control-dir>                     graceful daemon shutdown
#
# Same validity rules as warm-probe.sh: needs the gtnhdeterminism fix jar; cold boots are
# still required for launch-variance tests and cold-parity baselines.
set -euo pipefail

CMD=$1
case "$CMD" in
start)
  SERVER_DIR=$2; CTL=$(mkdir -p "$3" && cd "$3" && pwd)
  cd "$SERVER_DIR"
  exec 9>".probe.lock"
  flock -n 9 || { echo "another probe run holds $SERVER_DIR/.probe.lock; aborting" >&2; exit 1; }
  rm -rf World world "$CTL/ready" "$CTL/stop"
  if [ ! -f server.properties.bak ] && [ -f server.properties ]; then cp server.properties server.properties.bak; fi
  cat > server.properties <<EOF
allow-nether=true
level-name=World
level-seed=1
level-type=rwg
online-mode=false
snooper-enabled=false
max-tick-time=-1
server-port=${PROBE_PORT:-25565}
server-ip=127.0.0.1
motd=worldgen probe (daemon)
EOF
  echo "eula=true" > eula.txt
  LAUNCH_JAR=$(ls lwjgl3ify-forgePatches.jar 2>/dev/null || ls forge-*.jar 2>/dev/null | head -1)
  JAVA_ARGS=""
  if [ -f java9args.txt ]; then JAVA_ARGS="@java9args.txt"; fi
  # flock fd 9 must survive in the daemon: run java with the fd inherited via setsid+nohup
  nohup setsid "${PROBE_JAVA:-java}" $JAVA_ARGS \
    -Xmx"${PROBE_XMX:-6G}" -Xms"${PROBE_XMX:-6G}" ${PROBE_JVMFLAGS:-} \
    -Dprobe.daemon="$CTL" \
    -Dfml.readTimeout=180 -Dfml.queryResult=confirm \
    -jar "$LAUNCH_JAR" nogui > "$CTL/daemon.log" 2>&1 9>&- &
  echo "booting daemon (log: $CTL/daemon.log)…"
  for i in $(seq 1 300); do
    [ -f "$CTL/ready" ] && { echo "daemon ready (pid $(cat "$CTL/ready"))"; exit 0; }
    kill -0 $! 2>/dev/null || { echo "server died during boot; see $CTL/daemon.log" >&2; exit 1; }
    sleep 1
  done
  echo "daemon never became ready" >&2; exit 1
  ;;
submit)
  CTL=$2; SEED=$3; OUT=$4; shift 4
  mkdir -p "$CTL/queue"
  ID="$(date +%s%N)-$SEED"
  {
    printf '{"seed": %s, "out": "%s"' "$SEED" "$OUT"
    for kv in "$@"; do printf ', "%s": "%s"' "${kv%%=*}" "${kv#*=}"; done
    printf '}\n'
  } > "$CTL/queue/.$ID.tmp"
  mv "$CTL/queue/.$ID.tmp" "$CTL/queue/$ID.json"
  echo "$ID"
  ;;
wait)
  CTL=$2; ID=$3
  while true; do
    if [ "$ID" = "all" ]; then
      n_q=$(ls "$CTL/queue" 2>/dev/null | grep -c '\.json$' || true)
      [ "$n_q" -eq 0 ] && break
    else
      [ -f "$CTL/done/$ID.json.status" ] && break
      [ -f "$CTL/failed/$ID.json.status" ] && { cat "$CTL/failed/$ID.json.status" >&2; exit 1; }
    fi
    # daemon alive?
    if [ -f "$CTL/ready" ] && ! kill -0 "$(cat "$CTL/ready")" 2>/dev/null; then
      echo "daemon is dead; see $CTL/daemon.log" >&2; exit 1
    fi
    sleep 1
  done
  if [ "$ID" = "all" ] && [ -n "$(ls "$CTL/failed" 2>/dev/null | grep '\.json$' || true)" ]; then
    echo "some jobs failed: $(ls "$CTL/failed" | grep '\.json$')" >&2; exit 1
  fi
  ;;
status)
  CTL=$2
  for d in queue done failed; do printf '%s: %s\n' "$d" "$(ls "$CTL/$d" 2>/dev/null | grep -c '\.json$' || true)"; done
  ;;
stop)
  CTL=$2
  touch "$CTL/stop"
  if [ -f "$CTL/ready" ]; then
    PID=$(cat "$CTL/ready")
    for i in $(seq 1 120); do kill -0 "$PID" 2>/dev/null || { echo "daemon stopped"; exit 0; }; sleep 1; done
    echo "daemon did not exit; kill $PID manually" >&2; exit 1
  fi
  ;;
*)
  echo "unknown command: $CMD" >&2; exit 1 ;;
esac
