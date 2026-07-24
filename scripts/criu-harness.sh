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
# Requires: criu with `setcap cap_checkpoint_restore,cap_sys_ptrace+eip` (or root), worldgenprobe >= 0.3.
# Restores run inside fresh user+pid+net namespaces (unshare -Urpfn): the image's original PID is
# always free there, and the restored listening socket lands in a netns the user namespace owns
# (the probe never uses the network). The checkpoint aligns RLIMIT_NOFILE soft=hard first so the
# unprivileged restore never has to raise a limit. Parallel restores = one namespace set each; the
# images dir is read-only at restore time so concurrent restores can share it.
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
server-port=${PROBE_PORT:-25565}
server-ip=127.0.0.1
motd=worldgen probe (criu)
EOF
  echo "eula=true" > eula.txt
  LAUNCH_JAR=$(ls lwjgl3ify-forgePatches.jar 2>/dev/null || ls forge-*.jar 2>/dev/null | head -1)
  JAVA_ARGS=""
  if [ -f java9args.txt ]; then JAVA_ARGS="@java9args.txt"; fi
  # -XX:-UsePerfData: no hsperfdata mmap for criu to trip on; stdin </dev/null so the
  # console-reader thread EOFs and exits before the dump. probe.order/out are placeholders —
  # every restore overrides them from go.json. ulimit soft=hard: see header (unprivileged restore).
  ulimit -n "$(ulimit -Hn)"
  setsid "$JAVA_BIN" $JAVA_ARGS \
    -Xmx"${PROBE_XMX:-6G}" -Xms"${PROBE_XMX:-6G}" -XX:-UsePerfData \
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
  # Enumerate every regular file the JVM holds a WRITABLE fd on: criu validates their
  # sizes/presence at every restore, but restored runs append to them and clean exits even delete
  # .lck files — so each restore must first reset them to dump-time state. The fd table must be
  # read BEFORE the dump (criu may kill the tree at dump completion); the tar happens AFTER, when
  # the sizes are final. Derived from the live fd table, not a curated list.
  MUT_LIST="$IMAGES_DIR/mutable-files.list"
  : > "$MUT_LIST"
  for fd in /proc/$PID/fd/*; do
    tgt=$(readlink "$fd" 2>/dev/null) || continue
    [ -f "$tgt" ] || continue
    flags=$(awk '/^flags:/{print $2}' "/proc/$PID/fdinfo/$(basename "$fd")" 2>/dev/null) || continue
    [ -n "$flags" ] || continue
    case $((8#$flags & 3)) in 1|2) echo "${tgt#/}" >> "$MUT_LIST" ;; esac
  done
  # Also capture file-backed mmaps under the server dir: mods extract native libs there at boot,
  # map them, and DELETE them on clean exit (OpenComputers' libjnlua .so) — a restore then fails
  # opening the vma. mmaps don't appear in the fd table; /proc/PID/maps has them.
  SD_ABS=$(cd "$SERVER_DIR" && pwd)
  awk -v sd="$SD_ABS/" 'NF>=6 && index($6, sd) == 1 { print substr($6, 2) }' "/proc/$PID/maps" >> "$MUT_LIST"
  sort -u "$MUT_LIST" -o "$MUT_LIST"
  [ -s "$MUT_LIST" ] || { echo "fd-table scan found no writable files — aborting (would break repeat restores)" >&2; exit 1; }
  criu dump -t "$PID" --images-dir "$IMAGES_DIR" --file-locks --shell-job --unprivileged -v1
  tar -C / -cf "$IMAGES_DIR/mutable.pristine.tar" -T "$MUT_LIST"
  echo "snapshotted $(wc -l < "$MUT_LIST") mutable files"
  # this criu leaves the dumped tree STOPPED (not killed): a stopped JVM still holds .probe.lock and
  # still races restores for go.json — kill it explicitly (SIGKILL only lands after SIGCONT thaws it)
  if kill -0 "$PID" 2>/dev/null; then
    kill -9 "$PID" 2>/dev/null || true
    kill -CONT "$PID" 2>/dev/null || true
    for i in $(seq 1 10); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
    kill -0 "$PID" 2>/dev/null && { echo "dumped JVM $PID would not die" >&2; exit 1; }
  fi
  # The image holds open append-mode fds on boot.log (stdout) and the server's logs/ files, and
  # criu validates each file's SIZE at restore — but every restored run APPENDS to them, so
  # without these snapshots each image restores exactly once. Keep dump-time copies and restore
  # them before every run.
  cp "$IMAGES_DIR/boot.log" "$IMAGES_DIR/boot.log.pristine"
  tar -C "$SERVER_DIR" -cf "$IMAGES_DIR/logs.pristine.tar" logs
  echo "checkpoint complete: $IMAGES_DIR"
  ;;
run)
  require_criu
  SEED=$4; ORDER=$5; OUT=$6; RADIUS=${7:-12}
  cd "$SERVER_DIR"
  # NOT .probe.lock: the image contains the checkpoint JVM's flock on .probe.lock and criu re-acquires
  # it during restore — holding it here deadlocks the restore against ourselves. The restored JVM's own
  # lock provides the against-other-probes guarantee; this one only serializes concurrent `run`s.
  exec 9>".criu-run.lock"
  flock -n 9 || { echo "another criu run active in $SERVER_DIR; aborting" >&2; exit 1; }
  # the image holds an open fd on .probe.lock — criu reopens it BY PATH, so the file must exist
  [ -f .probe.lock ] || touch .probe.lock
  # reset all dump-time-writable files to dump-time state (restored runs append to / delete them;
  # criu validates sizes and presence at restore)
  if [ -f "$IMAGES_DIR/mutable.pristine.tar" ]; then
    tar -C / -xpf "$IMAGES_DIR/mutable.pristine.tar"
  else
    # legacy images (pre-manifest): best-effort reset of the known mutable set
    [ -f "$IMAGES_DIR/boot.log.pristine" ] && cp -f "$IMAGES_DIR/boot.log.pristine" "$IMAGES_DIR/boot.log"
    [ -f "$IMAGES_DIR/logs.pristine.tar" ] && { rm -rf logs; tar -C "$SERVER_DIR" -xf "$IMAGES_DIR/logs.pristine.tar"; }
  fi
  rm -rf World world
  rm -f "$OUT"
  printf '{"seed": %s, "order": "%s", "radius": %s, "out": "%s", "search": "%s", "dim0only": "%s", "nohash": "%s"}\n' \
    "$SEED" "$ORDER" "$RADIUS" "$OUT" "${PROBE_SEARCH:-false}" "${PROBE_DIM0ONLY:-false}" "${PROBE_NOHASH:-false}" > "$CTL/go.json"
  T0=$(date +%s)
  # Foreground restore in the INIT namespaces: the image's original PID is free because checkpoint
  # kills the dumped tree, and the file caps on criu only work fully in the init userns (namespaced
  # restores fail on rlimit/SO_*FORCE, which need init-userns CAP_SYS_RESOURCE/CAP_NET_ADMIN).
  # criu (no -d) parents the restored JVM, so this command returns when the probe run finishes and
  # the server exits. Sequential restores only (PID + port live in the shared namespaces).
  criu restore --images-dir "$IMAGES_DIR" --file-locks --shell-job --unprivileged --skip-file-rwx-check -v1 \
    --log-file "$IMAGES_DIR/restore.log" \
    || { echo "restore failed; see $IMAGES_DIR/restore.log" >&2; rm -f "$CTL/go.json"; exit 1; }
  rm -f "$CTL/go.json"
  [ -f "$OUT" ] || { echo "probe JSON never appeared (restored server exited without writing it)" >&2; exit 1; }
  echo "seed $SEED done in $(( $(date +%s) - T0 ))s -> $OUT"
  ;;
*)
  echo "unknown command: $CMD (use checkpoint | run)" >&2; exit 1 ;;
esac
