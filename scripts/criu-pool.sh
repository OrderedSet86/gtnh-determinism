#!/usr/bin/env bash
# RAM-aware elastic CRIU seed-search pool.
#
# N pre-checkpointed probe images (one boot each, distinct ports => distinct baked PIDs, so all N
# restore concurrently in the init namespaces). Each job is an independent ~30s restore process:
# the pool holds ZERO RAM while idle, and the dispatcher gates every dispatch on MemAvailable
# staying above a reserve — if the user starts something heavy, dispatching pauses and the
# in-flight jobs drain within ~30s. Scale-down is organic; no daemons to manage.
#
#   criu-pool.sh build-images <template-server-dir> <pool-dir> <N>
#   criu-pool.sh run-batch    <pool-dir> <seed-file> <out-dir> [radius]
#   criu-pool.sh status       <pool-dir>
#   criu-pool.sh stop         <pool-dir>          # stop dispatching; in-flight jobs finish
#
# Env: RESERVE_GB (default 24) — MemAvailable floor the pool must never dip below by dispatching.
#      PER_JOB_MB (default heap+2048) — per-restore RSS estimate used in the gate.
#      POOL_RADIUS default 15; PROBE_JAVA as usual.
#
# Heap sizing is automatic: image 0 is checkpointed at 4G and smoke-tested; on failure or a
# >40s run it is rebuilt at 6G and the whole pool uses 6G. (The remembered "4G problem" was the
# warm multi-slot leak — pool restores are one seed per fresh image, so 4G is plausible; tested,
# not assumed.) The chosen heap is recorded in <pool-dir>/heap.
#
# Correctness: restores are byte-identical to true cold boots (certified 2026-07-24: 961/961
# spawn-window chunks, 0 block + 0 chest diffs). Images are invalidated by ANY mods/config
# change — rebuild images after `build-probe.sh --deploy`.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
CMD=${1:?command}

clone_server() { # template -> instdir/server  (probe-farm recipe; hardlink same-device, copy otherwise)
  local TEMPLATE=$1 DST=$2 LINK=-al
  [ -d "$DST" ] && return 0
  mkdir -p "$DST"
  if [ "$(stat -c %d "$TEMPLATE")" != "$(stat -c %d "$DST")" ]; then LINK=-a; fi
  for d in mods libraries; do cp $LINK "$TEMPLATE/$d" "$DST/$d"; done
  if [ -d "$TEMPLATE/mods/OpenSecurity" ]; then
    rm -rf "$DST/mods/OpenSecurity"
    cp -a "$TEMPLATE/mods/OpenSecurity" "$DST/mods/OpenSecurity"
  fi
  for f in lwjgl3ify-forgePatches.jar forge-*.jar minecraft_server.1.7.10.jar java9args.txt; do
    [ -e "$TEMPLATE"/$f ] && cp $LINK "$TEMPLATE"/$f "$DST/" || true
  done
  cp -a "$TEMPLATE/config" "$DST/config"
  for d in serverutilities GregTech.lang coretweaks; do
    [ -e "$TEMPLATE/$d" ] && cp -a "$TEMPLATE/$d" "$DST/$d" || true
  done
  echo "eula=true" > "$DST/eula.txt"
}

checkpoint_inst() { # instdir port heap
  local INST=$1 PORT=$2 HEAP=$3
  rm -rf "$INST/images"
  PROBE_PORT=$PORT PROBE_XMX=$HEAP "$SCRIPT_DIR/criu-harness.sh" "$INST/server" "$INST/images" checkpoint
}

smoke_inst() { # instdir seed out -> prints seconds, rc 1 on failure
  local INST=$1 SEED=$2 OUT=$3 T0 T1
  T0=$(date +%s)
  PROBE_SEARCH=true PROBE_NOHASH=true \
    "$SCRIPT_DIR/criu-harness.sh" "$INST/server" "$INST/images" run "$SEED" rows "$OUT" 15 >/dev/null 2>&1 || return 1
  [ -f "$OUT" ] || return 1
  T1=$(date +%s)
  echo $((T1 - T0))
}

case "$CMD" in
build-images)
  TEMPLATE=$2; POOL=$3; N=$4
  mkdir -p "$POOL"; POOL=$(cd "$POOL" && pwd)
  SMOKE_OUT=$POOL/smoke-seed.json

  build_all() { # heap — all N clone+checkpoints in PARALLEL (boots are independent; needs
                # roughly N x (heap+2G) MemAvailable during the build)
    local HEAP=$1 i rc=0
    local pids=()
    for i in $(seq 0 $((N - 1))); do
      clone_server "$TEMPLATE" "$POOL/inst-$i/server"
    done
    for i in $(seq 0 $((N - 1))); do
      (checkpoint_inst "$POOL/inst-$i" $((25700 + i)) "$HEAP" > "$POOL/inst-$i/checkpoint.log" 2>&1) &
      pids+=($!)
      sleep 3 # stagger boot IO bursts
    done
    for i in "${!pids[@]}"; do
      wait "${pids[$i]}" || { echo "instance $i checkpoint FAILED — see $POOL/inst-$i/checkpoint.log" >&2; rc=1; }
    done
    return $rc
  }

  # 4G first (single-seed restores don't accumulate — the old "needs 6G+" issue was the warm
  # multi-slot leak); automatic 6G fallback if the smoke test fails or crawls.
  HEAP=4G
  echo "== building $N images in parallel at $HEAP"
  if ! build_all "$HEAP"; then echo "parallel build had failures at $HEAP" >&2; exit 1; fi
  rm -f "$SMOKE_OUT"
  if SECS=$(smoke_inst "$POOL/inst-0" 987654 "$SMOKE_OUT") && [ "$SECS" -le 40 ]; then
    echo "== 4G smoke OK (${SECS}s) — pool heap = 4G"
  else
    echo "== 4G smoke FAILED or slow (${SECS:-fail}s) — rebuilding all at 6G"
    HEAP=6G
    build_all "$HEAP" || { echo "6G build failed" >&2; exit 1; }
    rm -f "$SMOKE_OUT"
    SECS=$(smoke_inst "$POOL/inst-0" 987654 "$SMOKE_OUT") || { echo "6G smoke failed too — see $POOL/inst-0/images/restore.log" >&2; exit 1; }
    echo "== 6G smoke OK (${SECS}s) — pool heap = 6G"
  fi
  echo "$HEAP" > "$POOL/heap"
  rm -f "$SMOKE_OUT"
  echo "pool ready: $N images at $HEAP under $POOL"
  ;;

run-batch)
  POOL=$2; SEED_FILE=$3; OUT_DIR=$4; RADIUS=${5:-${POOL_RADIUS:-15}}
  POOL=$(cd "$POOL" && pwd)
  mkdir -p "$OUT_DIR"; OUT_DIR=$(cd "$OUT_DIR" && pwd)
  HEAP=$(cat "$POOL/heap" 2>/dev/null || echo 6G)
  HEAP_MB=$(( ${HEAP%G} * 1024 ))
  # User policy (2026-07-24): the reserve on this system is never below 20G — clamp, don't trust flags.
  RGB=${RESERVE_GB:-24}
  if [ "$RGB" -lt 20 ]; then echo "RESERVE_GB=$RGB below the 20G floor — clamping to 20" >&2; RGB=20; fi
  RESERVE_MB=$(( RGB * 1024 ))
  JOB_MB=${PER_JOB_MB:-$((HEAP_MB + 2048))}
  PROG=$OUT_DIR/pool-progress.txt
  rm -f "$POOL/stop"

  mapfile -t PENDING < <(tr ', ' '\n\n' < "$SEED_FILE" | grep -vE '^\s*$' | while read -r s; do
    [ -f "$OUT_DIR/seed-$s.json" ] || echo "$s"
  done)
  echo "$(date +%H:%M:%S) ${#PENDING[@]} seeds pending; reserve ${RESERVE_MB}M, per-job ${JOB_MB}M, heap $HEAP" | tee -a "$PROG"
  [ "${#PENDING[@]}" -eq 0 ] && exit 0

  mem_avail_mb() { awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo; }
  free_inst() { # first instance without a live busy marker
    for d in "$POOL"/inst-*; do
      local pidf=$d/busy.pid
      if [ -f "$pidf" ] && kill -0 "$(cat "$pidf")" 2>/dev/null; then continue; fi
      rm -f "$pidf"
      echo "$d"; return 0
    done
    return 1
  }

  T0=$(date +%s)
  PAUSED=0
  for SEED in "${PENDING[@]}"; do
    while :; do
      [ -f "$POOL/stop" ] && { echo "$(date +%H:%M:%S) STOP requested — no further dispatch" | tee -a "$PROG"; break 2; }
      INST=$(free_inst) || { sleep 2; continue; }
      AVAIL=$(mem_avail_mb)
      if [ $((AVAIL - RESERVE_MB)) -lt "$JOB_MB" ]; then
        if [ "$PAUSED" -eq 0 ]; then
          echo "$(date +%H:%M:%S) PAUSED: MemAvailable ${AVAIL}M - reserve ${RESERVE_MB}M < job ${JOB_MB}M" | tee -a "$PROG"
          PAUSED=1
        fi
        sleep 3; continue
      fi
      if [ "$PAUSED" -eq 1 ]; then
        echo "$(date +%H:%M:%S) RESUMED: MemAvailable ${AVAIL}M" | tee -a "$PROG"
        PAUSED=0
      fi
      break
    done
    (
      if PROBE_SEARCH=true PROBE_NOHASH=true \
        "$SCRIPT_DIR/criu-harness.sh" "$INST/server" "$INST/images" run "$SEED" rows \
        "$OUT_DIR/seed-$SEED.json" "$RADIUS" > "$INST/last-run.log" 2>&1; then
        echo "$(date +%H:%M:%S) done  $SEED  ($(basename "$INST"))" >> "$PROG"
      else
        echo "$(date +%H:%M:%S) FAILED $SEED  ($(basename "$INST")) — see $INST/last-run.log + images/restore.log" >> "$PROG"
      fi
      rm -f "$INST/busy.pid"
    ) &
    echo $! > "$INST/busy.pid"
    echo "$(date +%H:%M:%S) dispatch $SEED -> $(basename "$INST") (MemAvailable $(mem_avail_mb)M)" >> "$PROG"
    sleep 5  # let the restore fault its pages in before the next gate reading
  done
  wait
  DONE=$(ls "$OUT_DIR"/seed-*.json 2>/dev/null | wc -l)
  ELAPSED=$(( $(date +%s) - T0 ))
  echo "$(date +%H:%M:%S) BATCH COMPLETE: $DONE reports, ${ELAPSED}s wall" | tee -a "$PROG"
  ;;

status)
  POOL=$(cd "$2" && pwd)
  awk '/MemAvailable/{printf "MemAvailable: %dM\n", int($2/1024)}' /proc/meminfo
  echo "heap: $(cat "$POOL/heap" 2>/dev/null || echo '?')  reserve: ${RESERVE_GB:-24}G"
  for d in "$POOL"/inst-*; do
    s=idle
    [ -f "$d/busy.pid" ] && kill -0 "$(cat "$d/busy.pid")" 2>/dev/null && s=busy
    echo "$(basename "$d"): $s"
  done
  ;;

stop)
  POOL=$(cd "$2" && pwd)
  touch "$POOL/stop"
  echo "stop requested — dispatcher halts before the next dispatch; in-flight jobs finish"
  ;;

*)
  echo "unknown command: $CMD (build-images | run-batch | status | stop)" >&2; exit 1 ;;
esac
