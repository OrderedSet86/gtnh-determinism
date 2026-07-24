#!/usr/bin/env bash
# Multi-server warm job farm: N probe daemons on hardlink-cloned server dirs, one global
# queue, a dispatcher that feeds the emptiest instance. DRAFT — untested pending free server.
#
#   probe-farm.sh start  <template-server-dir> <farm-dir> <N>     clone + boot N daemons
#   probe-farm.sh submit <farm-dir> <seed> <out.json> [k=v ...]   same job args as probe-queue
#   probe-farm.sh wait   <farm-dir>                               until global+instance queues drain
#   probe-farm.sh status <farm-dir>
#   probe-farm.sh stop   <farm-dir>
#
# Memory: instances run at PROBE_XMX (default 6G) ≈ heap+1.5G RSS each.
#   18 GB budget -> N=2 at 6G (proven), or N=3 with PROBE_XMX=4G (benchmark a 4G boot first).
# The start command refuses an instance if MemAvailable < heap+2G.
#
# Semantics to remember:
#   - Jobs on the SAME instance share JVM history (documented warm residual: ore-TE
#     bookkeeping/small-ore noise). Fine for seed search; paired fresh-JVM experiments
#     should keep using probe-queue.sh directly with dedicated daemons.
#   - Clones hardlink mods/ + libraries/ (read-only at runtime); config/ is a REAL copy
#     (Forge rewrites configs in place — a hardlinked config would corrupt the template).
#     Never redirect-write onto a hardlinked file: rm first (rm breaks the link).
set -euo pipefail

CMD=$1
FARM=${2:?farm-dir}
JQ_GLOBAL="$FARM/global-queue"

instances() { ls -d "$FARM"/inst-* 2>/dev/null; }

case "$CMD" in
start)
  TEMPLATE=$2; FARM=$3; N=$4
  mkdir -p "$FARM"; FARM=$(cd "$FARM" && pwd)
  mkdir -p "$FARM/global-queue"
  XMX=${PROBE_XMX:-6G}
  HEAP_MB=$(( ${XMX%G} * 1024 ))
  for i in $(seq 0 $((N-1))); do
    INST="$FARM/inst-$i"
    if [ ! -d "$INST/server" ]; then
      echo "cloning instance $i…"
      mkdir -p "$INST/server"
      # hardlink the big read-only payload, copy the mutable dirs
      for d in mods libraries; do cp -al "$TEMPLATE/$d" "$INST/server/$d"; done
      # OpenSecurity rewrites its loose sound files inside mods/ at boot (caught by the hardlink
      # audit) — real-copy that subtree so instances can't cross-write through shared inodes
      if [ -d "$TEMPLATE/mods/OpenSecurity" ]; then
        rm -rf "$INST/server/mods/OpenSecurity"
        cp -a "$TEMPLATE/mods/OpenSecurity" "$INST/server/mods/OpenSecurity"
      fi
      for f in lwjgl3ify-forgePatches.jar forge-*.jar minecraft_server.1.7.10.jar java9args.txt; do
        [ -e "$TEMPLATE"/$f ] && cp -al "$TEMPLATE"/$f "$INST/server/" || true
      done
      cp -a "$TEMPLATE/config" "$INST/server/config"
      for d in serverutilities GregTech.lang coretweaks; do
        [ -e "$TEMPLATE/$d" ] && cp -a "$TEMPLATE/$d" "$INST/server/$d" || true
      done
      echo "eula=true" > "$INST/server/eula.txt"
    fi
    AVAIL_MB=$(( $(grep MemAvailable /proc/meminfo | awk '{print $2}') / 1024 ))
    if [ "$AVAIL_MB" -lt $((HEAP_MB + 2048)) ]; then
      echo "instance $i NOT started: MemAvailable ${AVAIL_MB}M < heap+2G" >&2
      continue
    fi
    PROBE_PORT=$((25600 + i)) PROBE_XMX=$XMX \
      "$(dirname "$0")/probe-queue.sh" start "$INST/server" "$INST/ctl"
  done
  # dispatcher: move global jobs to the instance with the shortest queue
  nohup bash -c '
    FARM="'"$FARM"'"
    while [ ! -f "$FARM/stop-dispatcher" ]; do
      for j in "$FARM"/global-queue/*.json; do
        [ -e "$j" ] || break
        best=""; bestn=999999
        for c in "$FARM"/inst-*/ctl; do
          [ -f "$c/ready" ] && kill -0 "$(cat "$c/ready")" 2>/dev/null || continue
          n=$(ls "$c/queue" 2>/dev/null | grep -c "\.json$" || true)
          if [ "$n" -lt "$bestn" ]; then bestn=$n; best=$c; fi
        done
        [ -n "$best" ] && mv "$j" "$best/queue/" || sleep 2
      done
      sleep 1
    done
    rm -f "$FARM/stop-dispatcher"
  ' > "$FARM/dispatcher.log" 2>&1 &
  echo $! > "$FARM/dispatcher.pid"
  echo "farm up: $(instances | wc -l) instances + dispatcher"
  ;;
submit)
  SEED=$3; OUT=$4; shift 4
  ID="$(date +%s%N)-$SEED"
  {
    printf '{"seed": %s, "out": "%s"' "$SEED" "$OUT"
    for kv in "$@"; do printf ', "%s": "%s"' "${kv%%=*}" "${kv#*=}"; done
    printf '}\n'
  } > "$JQ_GLOBAL/.$ID.tmp"
  mv "$JQ_GLOBAL/.$ID.tmp" "$JQ_GLOBAL/$ID.json"
  echo "$ID"
  ;;
wait)
  while true; do
    n=$(ls "$JQ_GLOBAL" 2>/dev/null | grep -c '\.json$' || true)
    for c in "$FARM"/inst-*/ctl; do
      n=$((n + $(ls "$c/queue" 2>/dev/null | grep -c '\.json$' || true)))
    done
    [ "$n" -eq 0 ] && break
    sleep 2
  done
  FAILS=$(ls "$FARM"/inst-*/ctl/failed 2>/dev/null | grep -c '\.json$' || true)
  [ "$FAILS" -gt 0 ] && { echo "$FAILS failed jobs (see inst-*/ctl/failed)" >&2; exit 1; }
  ;;
status)
  echo "global queue: $(ls "$JQ_GLOBAL" 2>/dev/null | grep -c '\.json$' || true)"
  for c in "$FARM"/inst-*/ctl; do
    i=$(basename "$(dirname "$c")")
    alive=dead
    [ -f "$c/ready" ] && kill -0 "$(cat "$c/ready")" 2>/dev/null && alive=alive
    echo "$i [$alive] queue=$(ls "$c/queue" 2>/dev/null | grep -c '\.json$' || true) done=$(ls "$c/done" 2>/dev/null | grep -c '\.json$' || true) failed=$(ls "$c/failed" 2>/dev/null | grep -c '\.json$' || true)"
  done
  ;;
stop)
  touch "$FARM/stop-dispatcher"
  [ -f "$FARM/dispatcher.pid" ] && kill "$(cat "$FARM/dispatcher.pid")" 2>/dev/null || true
  for c in "$FARM"/inst-*/ctl; do
    "$(dirname "$0")/probe-queue.sh" stop "$c" || true
  done
  ;;
*)
  echo "unknown command: $CMD" >&2; exit 1 ;;
esac
