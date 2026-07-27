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
#      CPU_MAX_PCT (default 80) — skip dispatching while whole-system CPU busy% (1s sample)
#        is above this, so restores never crowd out interactive work (user req 2026-07-24).
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

jar_fingerprint() { # dir -> md5 over the determinism-relevant jars in dir/mods
  (cd "$1/mods" && md5sum *probe*.jar *determinism*.jar 2>/dev/null | awk '{print $1}' | sort | md5sum | cut -d' ' -f1)
}

# CRIU images do not survive a REBOOT. A dumped thread blocked in a timed wait carries an absolute
# CLOCK_MONOTONIC deadline; this restore takes no time namespace (timens_offsets stays 0 0), so once a
# reboot resets monotonic to ~0 the restored probe re-arms that deadline in the OLD boot's timeline and
# sleeps out the difference — hours to days, silently, parked at the go.json barrier with the images
# and jars all perfectly valid. Cost a 2.5h no-op batch on 2026-07-25 (images from the 2026-07-24 23:00
# boot, box rebooted 11:10). Images are stamped with the boot that produced them and refused in any other.
boot_id() { cat /proc/sys/kernel/random/boot_id; }

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
  # run-batch dispatches to every inst-* it finds, so a rebuild at a SMALLER N must delete the
  # leftovers — otherwise the pool silently keeps serving jobs from images this build never refreshed.
  for d in "$POOL"/inst-*; do
    [ -d "$d" ] || continue
    i=${d##*/inst-}
    [ "$i" -ge "$N" ] 2>/dev/null && { echo "removing orphan $(basename "$d") (pool is now $N wide)"; rm -rf "$d"; }
  done

  build_all() { # heap — all N clone+checkpoints in PARALLEL (boots are independent; needs
                # roughly N x (heap+2G) MemAvailable during the build)
    local HEAP=$1 i rc=0
    local pids=()
    for i in $(seq 0 $((N - 1))); do
      # ALWAYS re-clone from the template: a surviving instance dir silently pins the OLD jars
      # (bit us 2026-07-24 — rebuilt images ran a stale probe). Clone is cheap (hardlinks).
      rm -rf "$POOL/inst-$i/server"
      clone_server "$TEMPLATE" "$POOL/inst-$i/server"
      [ "$(jar_fingerprint "$TEMPLATE")" = "$(jar_fingerprint "$POOL/inst-$i/server")" ] \
        || { echo "inst-$i mods/ out of sync with template after clone" >&2; return 1; }
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
  boot_id > "$POOL/boot-id"
  rm -f "$SMOKE_OUT"
  echo "pool ready: $N images at $HEAP under $POOL (boot $(boot_id))"
  ;;

run-batch)
  POOL=$2; SEED_FILE=$3; OUT_DIR=$4; RADIUS=${5:-${POOL_RADIUS:-15}}
  POOL=$(cd "$POOL" && pwd)
  mkdir -p "$OUT_DIR"; OUT_DIR=$(cd "$OUT_DIR" && pwd)
  HEAP=$(cat "$POOL/heap" 2>/dev/null || echo 6G)
  HEAP_MB=$(( ${HEAP%G} * 1024 ))
  # Stale-image guard (see boot_id above): a post-reboot restore hangs at the barrier instead of failing.
  STAMP=$(cat "$POOL/boot-id" 2>/dev/null || echo "")
  if [ "$STAMP" != "$(boot_id)" ]; then
    echo "pool images are from another boot (stamp '${STAMP:-none}', now $(boot_id)) — restores would hang at the" >&2
    echo "go.json barrier for hours. Rebuild first:  criu-pool.sh build-images <template-server-dir> $POOL <N>" >&2
    exit 1
  fi
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
  TOTAL=$(tr ', ' '\n\n' < "$SEED_FILE" | grep -cvE '^\s*$')
  done_count() { find "$OUT_DIR" -maxdepth 1 -name 'seed-*.json' 2>/dev/null | wc -l; }
  echo "$(date +%H:%M:%S) ${#PENDING[@]} seeds pending ($(done_count)/$TOTAL done); reserve ${RESERVE_MB}M, per-job ${JOB_MB}M, heap $HEAP" | tee -a "$PROG"
  [ "${#PENDING[@]}" -eq 0 ] && exit 0

  mem_avail_mb() { awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo; }
  CPU_MAX=${CPU_MAX_PCT:-80}
  cpu_busy_pct() { # whole-system non-idle % over a 1s sample
    local i1 t1 i2 t2 dt
    read -r i1 t1 < <(awk '/^cpu /{i=$5+$6; t=0; for(f=2;f<=NF;f++)t+=$f; print i, t}' /proc/stat)
    sleep 1
    read -r i2 t2 < <(awk '/^cpu /{i=$5+$6; t=0; for(f=2;f<=NF;f++)t+=$f; print i, t}' /proc/stat)
    dt=$((t2 - t1)); [ "$dt" -le 0 ] && { echo 0; return; }
    echo $(( 100 * (dt - (i2 - i1)) / dt ))
  }
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
      CPU=$(cpu_busy_pct)
      if [ "$CPU" -gt "$CPU_MAX" ]; then
        if [ "$PAUSED" -eq 0 ]; then
          echo "$(date +%H:%M:%S) PAUSED: CPU ${CPU}% > ${CPU_MAX}% (CPU_MAX_PCT)" | tee -a "$PROG"
          PAUSED=1
        fi
        sleep 3; continue
      fi
      if [ "$PAUSED" -eq 1 ]; then
        echo "$(date +%H:%M:%S) RESUMED: MemAvailable ${AVAIL}M, CPU ${CPU}%" | tee -a "$PROG"
        PAUSED=0
      fi
      break
    done
    (
      # Watchdog: a wedged restore holds ~heap+2G of RAM doing nothing, which drags MemAvailable under
      # the reserve and PAUSES the dispatcher indefinitely — one hung job silently stalls the whole
      # batch (2026-07-25: 4 hung jobs, 0 of 300 seeds in 2.5h). Seeds take ~10s; anything past
      # JOB_TIMEOUT is wedged, so kill the tree and record a FAILED the run log can be grepped for.
      if PROBE_SEARCH=true PROBE_NOHASH=true \
        timeout -k 10 "${JOB_TIMEOUT:-300}" \
        "$SCRIPT_DIR/criu-harness.sh" "$INST/server" "$INST/images" run "$SEED" rows \
        "$OUT_DIR/seed-$SEED.json" "$RADIUS" > "$INST/last-run.log" 2>&1; then
        echo "$(date +%H:%M:%S) done  $SEED  ($(basename "$INST"))  [$(done_count)/$TOTAL]" | tee -a "$PROG"
      else
        RC=$?
        if [ "$RC" -eq 124 ]; then
          # timeout only signals criu-harness.sh; the criu restore and the restored JVM are separate
          # processes that would outlive it. Kill everything bound to this instance dir by path.
          pkill -9 -f "criu-harness.sh $INST/server" 2>/dev/null || true
          pkill -9 -f "images-dir $INST/images" 2>/dev/null || true
          pkill -9 -f "probe.criu=$INST/server/.criu-ctl" 2>/dev/null || true
          echo "$(date +%H:%M:%S) FAILED $SEED  ($(basename "$INST"))  [$(done_count)/$TOTAL] — TIMEOUT after ${JOB_TIMEOUT:-300}s, instance killed" | tee -a "$PROG"
        else
          echo "$(date +%H:%M:%S) FAILED $SEED  ($(basename "$INST"))  [$(done_count)/$TOTAL] — see $INST/last-run.log + images/restore.log" | tee -a "$PROG"
        fi
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
