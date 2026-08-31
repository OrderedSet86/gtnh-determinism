#!/usr/bin/env bash
# Two-tier sharded stage-0 sweep.
#
#   prefilter-sweep.sh <seed-file> <out-dir> [tier1-shards] [tier2-shards] [cut-fraction]
#
# Tier 1 scores every seed by Roguelike trigger count around the predicted spawn — no village starts, no
# chest prediction, no terrain digest, no dungeon construction. Measured 118 ms/seed against the full
# pipeline's 1466 ms. Tier 2 then runs the UNCHANGED full pipeline over the top `cut` fraction, so its rows
# are byte-identical to a single-process run and every downstream tool reads them as before.
#
# Recall is a measured property, not an assumption. Over the 5000-seed corpus in
# results/2026-08-30-chest-loot-sweep-5000, ranking by tier-1 trigger count retains, of the true top N by
# exact score:
#     cut  2%  -> top-1 1/1, top-5 3/5, top-10  4/10, top-50 27/50
#     cut  7.5% -> top-1 1/1, top-5 5/5, top-10  9/10, top-50 45/50
#     cut 15%  -> top-1 1/1, top-5 5/5, top-10 10/10, top-50 50/50
# Choose the cut from the N you actually care about. The default 0.15 is the one that keeps the top 50.
#
# Sharding is by process, not thread: Prefilter holds a single static MapGenVillage whose world is rebound
# per seed, and PendingSlices.atomicDepth is a single static counter, so in-process threading needs those
# made thread-local first. Processes sidestep both. Each shard gets its own server dir (prefilter.sh's
# flock is per-dir) and its own port and OUTPUT DIR — shards sharing an output dir race on the biomes.json
# sidecar.
#
# Width is bounded by BOTH RAM (standing policy reserves >=20G; tier 2 runs -Xmx6G) and CPU.
#
# Env: CPU_MAX_PCT (default 70) — share of the whole machine this sweep may use. Unlike criu-pool.sh,
#        which gates dispatch on a 1s busy sample, sweep shards are long-lived: a gate would pass once at
#        launch and never fire again. So the budget is applied twice instead — it caps the derived shard
#        count, AND it is enforced by a cgroup v2 cpu.max quota so a wrong derivation cannot overshoot.
#      CPU_PER_SHARD_PCT (default 190) — CPU one shard actually draws, as a percentage of one hardware
#        thread. Measured, not assumed: six tier-1 shards on a 16-thread box drew 1125% total, i.e. ~190%
#        each, because ParallelGC threads run alongside the worker. Sizing shards at 100% each — the
#        obvious guess — oversubscribes by ~1.9x and is what pushed this box to load average 23.
set -euo pipefail

SEEDS=${1:?usage: prefilter-sweep.sh <seed-file> <out-dir> [t1-shards] [t2-shards] [cut]}
OUT=${2:?}
T1N=${3:-6}
T2N=${4:-4}
CUT=${5:-0.15}

CPU_MAX_PCT=${CPU_MAX_PCT:-70}
CPU_PER_SHARD_PCT=${CPU_PER_SHARD_PCT:-190}
NPROC=$(nproc)
BUDGET_PCT=$((NPROC * CPU_MAX_PCT))                 # e.g. 16 threads x 70 = 1120%
MAX_SHARDS=$((BUDGET_PCT / CPU_PER_SHARD_PCT))
[ "$MAX_SHARDS" -lt 1 ] && MAX_SHARDS=1
if [ "$T1N" -gt "$MAX_SHARDS" ]; then
  echo "CPU budget ${CPU_MAX_PCT}% of ${NPROC} threads allows ${MAX_SHARDS} shards — reducing tier 1 from $T1N" >&2
  T1N=$MAX_SHARDS
fi
if [ "$T2N" -gt "$MAX_SHARDS" ]; then
  echo "CPU budget allows ${MAX_SHARDS} shards — reducing tier 2 from $T2N" >&2
  T2N=$MAX_SHARDS
fi

# Hard ceiling, so the estimate above only has to be roughly right. Everything this script spawns is born
# inside the slice because the driver moves ITSELF in first and children inherit the cgroup. Degrades to
# advisory (derived shard count only) where the user cgroup is not delegated.
# The budget is a share of the WHOLE machine, so it has to account for what is already running. A quota
# sized against an idle box is what let a 70% cap coexist with a 99% meter: the cap governed this sweep's
# processes and nothing else. Sample current busy% (criu-pool.sh's sampler) and spend only the headroom.
cpu_busy_pct() {
  local i1 t1 i2 t2 dt
  read -r i1 t1 < <(awk '/^cpu /{i=$5+$6; t=0; for(f=2;f<=NF;f++)t+=$f; print i, t}' /proc/stat)
  sleep 1
  read -r i2 t2 < <(awk '/^cpu /{i=$5+$6; t=0; for(f=2;f<=NF;f++)t+=$f; print i, t}' /proc/stat)
  dt=$((t2 - t1)); [ "$dt" -le 0 ] && { echo 0; return; }
  echo $(( 100 * (dt - (i2 - i1)) / dt ))
}
BUSY=$(cpu_busy_pct)
HEADROOM=$((CPU_MAX_PCT - BUSY))
if [ "$HEADROOM" -lt 10 ]; then
  echo "machine already ${BUSY}% busy against a ${CPU_MAX_PCT}% budget — less than 10% headroom, aborting." >&2
  echo "  raise CPU_MAX_PCT or wait for the other load to finish." >&2
  exit 1
fi
if [ "$HEADROOM" -lt "$CPU_MAX_PCT" ]; then
  echo "machine ${BUSY}% busy — spending ${HEADROOM}% headroom, not the full ${CPU_MAX_PCT}%" >&2
  BUDGET_PCT=$((NPROC * HEADROOM))
  MAX_SHARDS=$((BUDGET_PCT / CPU_PER_SHARD_PCT)); [ "$MAX_SHARDS" -lt 1 ] && MAX_SHARDS=1
  [ "$T1N" -gt "$MAX_SHARDS" ] && T1N=$MAX_SHARDS
  [ "$T2N" -gt "$MAX_SHARDS" ] && T2N=$MAX_SHARDS
fi

CGROOT=/sys/fs/cgroup/user.slice/user-$(id -u).slice/user@$(id -u).service
SLICE="$CGROOT/prefilter.slice"
SLICE_OK=0
if [ -w "$CGROOT/cgroup.subtree_control" ] && mkdir -p "$SLICE" 2>/dev/null; then
  grep -q cpu "$CGROOT/cgroup.subtree_control" || echo "+cpu" > "$CGROOT/cgroup.subtree_control" 2>/dev/null || true
  # Enrol the driver BEFORE anything is launched, so every shard is born inside the cap by inheritance.
  if echo "$((BUDGET_PCT * 1000)) 100000" > "$SLICE/cpu.max" 2>/dev/null \
     && echo $$ > "$SLICE/cgroup.procs" 2>/dev/null; then
    SLICE_OK=1
    echo "CPU capped at ${BUDGET_PCT}% (of ${NPROC}00%) via ${SLICE}; ${T1N}/${T2N} shards" >&2
  fi
fi
[ "$SLICE_OK" -eq 1 ] || echo "WARNING: no cgroup cap — shard count is the only limit" >&2

# prefilter.sh ends in `exec java`, so a shard's JVM inherits the subshell's pid and is NOT a child of this
# script by the time it matters. Killing the driver therefore ORPHANS every JVM, which is exactly what
# happened when a `timeout` wrapper SIGKILLed a verification run and left six JVMs generating chunks with
# no parent. Kill by cgroup membership, which is exact, and fall back to the recorded pids.
SHARD_PIDS=()
cleanup() {
  local rc=$?
  trap - EXIT INT TERM
  if [ "$SLICE_OK" -eq 1 ] && [ -r "$SLICE/cgroup.procs" ]; then
    local me=$$ p
    while read -r p; do [ "$p" != "$me" ] && kill -9 "$p" 2>/dev/null; done < "$SLICE/cgroup.procs"
  fi
  for p in "${SHARD_PIDS[@]:-}"; do
    [ -n "$p" ] && { kill -9 "$p" 2>/dev/null; pkill -9 -P "$p" 2>/dev/null; }
  done
  pkill -9 -f "lwjgl3ify-forgePatches.*${SHARDROOT:-__none__}" 2>/dev/null
  exit $rc
}
trap cleanup EXIT INT TERM HUP

# Interactive work should always preempt this sweep regardless of the quota.
renice -n "${SWEEP_NICE:-19}" -p $$ >/dev/null 2>&1 || true

REPO=$(cd "$(dirname "$0")/.." && pwd)
TEMPLATE=${PREFILTER_TEMPLATE:-$HOME/.cache/gtnh-determinism/prefilter/daily707}
SHARDROOT=${SHARD_ROOT:-$HOME/.cache/gtnh-determinism/shards}
RADIUS=${SWEEP_RADIUS:-60}
mkdir -p "$OUT" "$SHARDROOT"

# Clone shard server dirs from the template. cp -al hardlinks the read-only payload; config/ is real-copied
# because Forge rewrites it in place, and mods/OpenSecurity because it rewrites loose files at boot.
clone_shard() {
  local d="$SHARDROOT/s$1"
  [ -d "$d" ] && return 0
  echo "cloning shard $1 from $TEMPLATE…" >&2
  mkdir -p "$d"
  for sub in mods libraries; do cp -al "$TEMPLATE/$sub" "$d/$sub"; done
  if [ -d "$TEMPLATE/mods/OpenSecurity" ]; then
    rm -rf "$d/mods/OpenSecurity"; cp -a "$TEMPLATE/mods/OpenSecurity" "$d/mods/OpenSecurity"
  fi
  for f in lwjgl3ify-forgePatches.jar forge-*.jar minecraft_server.1.7.10.jar java9args.txt; do
    cp -al "$TEMPLATE"/$f "$d/" 2>/dev/null || true
  done
  cp -a "$TEMPLATE/config" "$d/config"
  for extra in serverutilities GregTech.lang coretweaks falsepattern; do
    [ -e "$TEMPLATE/$extra" ] && cp -a "$TEMPLATE/$extra" "$d/$extra" || true
  done
  echo "eula=true" > "$d/eula.txt"
}

# The probe jar must match across shards or their predictions diverge silently.
sync_jars() {
  local d="$SHARDROOT/s$1"
  rm -f "$d"/mods/worldgenprobe-*.jar
  cp -al "$TEMPLATE"/mods/worldgenprobe-*.jar "$d/mods/" 2>/dev/null || \
    cp "$TEMPLATE"/mods/worldgenprobe-*.jar "$d/mods/"
}

run_shards() {                      # run_shards <n> <phase> <flags> <heap>
  local n=$1 phase=$2 flags=$3 heap=$4 i pids=()
  for ((i = 0; i < n; i++)); do
    clone_shard "$i"; sync_jars "$i"
    mkdir -p "$OUT/$phase/s$i"
    ( PREFILTER_SERVER="$SHARDROOT/s$i" PROBE_PORT=$((25700 + i)) \
      PREFILTER_RADIUS=${PF_RADIUS:-0} PREFILTER_TERRAIN=${PF_TERRAIN:--1} \
      PROBE_JVMFLAGS="$flags -Xmx$heap -Xms$heap" \
      "$REPO/scripts/prefilter.sh" "@$OUT/$phase/shard-$i.txt" "$OUT/$phase/s$i/out.jsonl" \
      > "$OUT/$phase/s$i/log" 2>&1 ) &
    pids+=($!)
    SHARD_PIDS+=($!)
    # Belt and braces: inheritance should already have placed it, but an explicit write costs nothing and
    # covers the case where the driver was enrolled late.
    [ "$SLICE_OK" -eq 1 ] && echo $! > "$SLICE/cgroup.procs" 2>/dev/null || true
  done
  local rc=0
  for p in "${pids[@]}"; do wait "$p" || rc=1; done
  cat "$OUT/$phase"/s*/out.jsonl > "$OUT/$phase.jsonl"
  return $rc
}

split_seeds() {                     # split_seeds <file> <n> <phase>
  python3 - "$1" "$2" "$OUT/$3" <<'PY'
import sys, os
seeds = [l.strip() for l in open(sys.argv[1]) if l.strip()]
n, out = int(sys.argv[2]), sys.argv[3]
os.makedirs(out, exist_ok=True)
for i in range(n):
    open(f"{out}/shard-{i}.txt", "w").write("\n".join(seeds[i::n]) + "\n")
print(f"{len(seeds)} seeds over {n} shards", file=sys.stderr)
PY
}

echo "=== tier 1: $T1N shards, trigger count only ===" >&2
split_seeds "$SEEDS" "$T1N" tier1
PF_RADIUS=0 PF_TERRAIN=-1 \
  run_shards "$T1N" tier1 "-Dprobe.prefilter.tier1=$RADIUS -Dprobe.prefilter.chunkcache=256 -Dprobe.prefilter.timing=true" 2G

echo "=== selecting the top $CUT by trigger count ===" >&2
python3 - "$OUT/tier1.jsonl" "$CUT" "$OUT/tier2-seeds.txt" <<'PY'
import json, sys
rows = []
for line in open(sys.argv[1]):
    d = json.loads(line)
    if "triggers" in d: rows.append((d["triggers"], d["seed"]))
rows.sort(reverse=True)
k = max(1, int(len(rows) * float(sys.argv[2])))
open(sys.argv[3], "w").write("\n".join(str(s) for _, s in rows[:k]) + "\n")
print(f"tier1 scored {len(rows)} seeds; tier2 will run {k}", file=sys.stderr)
PY

echo "=== tier 2: $T2N shards, full pipeline (byte-identical rows) ===" >&2
split_seeds "$OUT/tier2-seeds.txt" "$T2N" tier2
PF_RADIUS=$((RADIUS + 10)) PF_TERRAIN=4 \
  run_shards "$T2N" tier2 \
    "-Dprobe.prefilter.dungeon=$RADIUS -Dprobe.prefilter.villagechests=true -Dprobe.prefilter.chunkcache=2048" 6G

echo "done: $OUT/tier1.jsonl ($(wc -l < "$OUT/tier1.jsonl") seeds), $OUT/tier2.jsonl ($(wc -l < "$OUT/tier2.jsonl") seeds)" >&2
