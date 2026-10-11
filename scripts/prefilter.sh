#!/usr/bin/env bash
# Stage-0 seed prefilter runner (seed-search-speed-plan.md §3.1): boots the probe server ONCE and
# evaluates a whole seed batch worldlessly (-Dprobe.prefilter) — villages + full piece layouts +
# biome histogram at ~hundreds of seeds/s. One ~90 s boot amortizes over the batch.
#
#   prefilter.sh <seedspec> <out.jsonl>
#
#   seedspec: @seeds.txt | seed1,seed2,... | random:COUNT[:RNGSEED]
#
# Env knobs (defaults are the operational rules — override only deliberately):
#   PREFILTER_SERVER   server dir (default ~/.cache/gtnh-determinism/prefilter/server,
#                      auto-cloned from PREFILTER_TEMPLATE on first use — same hardlink recipe as
#                      probe-farm.sh incl. the OpenSecurity real-copy)
#   PREFILTER_TEMPLATE template server dir (default ~/.cache/gtnh-determinism/template-2.8.4)
#   PREFILTER_RADIUS   village cell scan radius in chunks around origin (default 64)
#   PREFILTER_PIECES   emit full village piece layouts (default true)
#   PREFILTER_TERRAIN  terrain digest radius in chunks around predicted spawn (-1 disables; default 4)
#   Staged kill gates (each stage runs only for survivors of the previous one; killed seeds
#   emit {"seed":..,"kill":"village|pieces|water|biomeregion"} for accounting):
#   PREFILTER_GATE_VILLAGEDIST  require a village cell within N chunks of origin (chebyshev)
#   PREFILTER_GATE_PIECES       comma list; require some village layout to contain one of these
#                               piece classes (e.g. ComponentToolWorkshop,VillageComponentPhotoshop)
#   PREFILTER_GATE_WATER        require >= N water columns in the terrain digest
#   PREFILTER_GATE_BIOMEREGION  SIDE[,MAXGAP]; require a SIDE x SIDE all-no-rain chunk square near the
#                               predicted spawn, with a high-humidity chunk within MAXGAP chunks of it.
#                               Needs -Dprobe.prefilter.biomeregion=N via PROBE_JVMFLAGS to enable the
#                               stage; the gate alone does nothing.
#   PROBE_JAVA         JVM to use; auto-detects a 17-21 JDK under ~/.gradle/jdks if unset
#   PROBE_PORT         server port (default 25597 — off the probe/farm default to avoid collisions)
#   PROBE_JVMFLAGS     extra -D flags, appended LAST so they override the ones set above. Until
#                      2026-08-29 this variable was accepted by every other probe script and silently
#                      ignored here, which makes an A/B look like it ran and report no difference.
#                      (warm-probe.sh has the mirror-image trap: it places PROBE_JVMFLAGS BEFORE its
#                      own -Dprobe.search, so PROBE_JVMFLAGS="-Dprobe.search=true" is overridden —
#                      use PROBE_SEARCH=true there.)
#
# The probe jar must already be deployed: scripts/build-probe.sh --deploy <server-dir>.
set -euo pipefail

[ $# -eq 2 ] || { sed -n '2,20p' "$0" >&2; exit 1; }
SEEDSPEC=$1
OUT=$2

CACHE="$HOME/.cache/gtnh-determinism"
SERVER_DIR=${PREFILTER_SERVER:-$CACHE/prefilter/server}
TEMPLATE=${PREFILTER_TEMPLATE:-$CACHE/template-2.8.4}
RADIUS=${PREFILTER_RADIUS:-64}
PIECES=${PREFILTER_PIECES:-true}

# Absolutize file paths before we cd into the server dir.
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT" ;; esac
case "$SEEDSPEC" in
  @/*) ;;
  @*) SEEDSPEC="@$PWD/${SEEDSPEC#@}" ;;
esac

# First use: clone the template (hardlink the read-only payload, real-copy what boots mutate —
# identical recipe to probe-farm.sh; OpenSecurity rewrites loose files in mods/ at boot).
if [ ! -d "$SERVER_DIR" ]; then
  [ -d "$TEMPLATE" ] || { echo "template $TEMPLATE missing" >&2; exit 1; }
  echo "cloning prefilter server from $TEMPLATE…"
  mkdir -p "$SERVER_DIR"
  for d in mods libraries; do cp -al "$TEMPLATE/$d" "$SERVER_DIR/$d"; done
  # never inherit the template's probe jar — a stale hardlinked jar is the classic silent-stale
  # artifact; force an explicit build-probe.sh --deploy into this dir instead
  rm -f "$SERVER_DIR"/mods/worldgenprobe-*.jar
  if [ -d "$TEMPLATE/mods/OpenSecurity" ]; then
    rm -rf "$SERVER_DIR/mods/OpenSecurity"
    cp -a "$TEMPLATE/mods/OpenSecurity" "$SERVER_DIR/mods/OpenSecurity"
  fi
  for f in lwjgl3ify-forgePatches.jar forge-*.jar minecraft_server.1.7.10.jar java9args.txt; do
    [ -e "$TEMPLATE"/$f ] && cp -al "$TEMPLATE"/$f "$SERVER_DIR/" || true
  done
  cp -a "$TEMPLATE/config" "$SERVER_DIR/config"
  for d in serverutilities GregTech.lang coretweaks; do
    [ -e "$TEMPLATE/$d" ] && cp -a "$TEMPLATE/$d" "$SERVER_DIR/$d" || true
  done
  echo "eula=true" > "$SERVER_DIR/eula.txt"
fi

ls "$SERVER_DIR"/mods/worldgenprobe-*.jar >/dev/null 2>&1 \
  || { echo "no worldgenprobe jar in $SERVER_DIR/mods — run scripts/build-probe.sh --deploy $SERVER_DIR" >&2; exit 1; }

# System Java is too new for 1.7.10/lwjgl3ify boot (class file v69 crash) — pick a 17-21 JDK.
JAVA_BIN=${PROBE_JAVA:-}
if [ -z "$JAVA_BIN" ]; then
  for d in "$HOME"/.gradle/jdks/*17*/ "$HOME"/.gradle/jdks/*21*/; do
    [ -x "$d/bin/java" ] && JAVA_BIN="$d/bin/java" && break
  done
fi
[ -n "$JAVA_BIN" ] || { echo "no 17-21 JDK under ~/.gradle/jdks and PROBE_JAVA unset" >&2; exit 1; }

cd "$SERVER_DIR"
exec 9>".prefilter.lock"
flock -w 600 9 || { echo "prefilter server dir locked; aborting" >&2; exit 1; }

rm -rf World world
cat > server.properties <<EOF
allow-nether=true
level-name=World
level-seed=1
level-type=rwg
online-mode=false
snooper-enabled=false
max-tick-time=-1
server-port=${PROBE_PORT:-25597}
motd=seed prefilter
EOF
echo "eula=true" > eula.txt

LAUNCH_JAR=$(ls lwjgl3ify-forgePatches.jar 2>/dev/null || ls forge-*.jar 2>/dev/null | head -1)
[ -n "$LAUNCH_JAR" ] || { echo "no launch jar in $SERVER_DIR" >&2; exit 1; }
JAVA_ARGS=""
[ -f java9args.txt ] && JAVA_ARGS="@java9args.txt"

GATES=""
[ -n "${PREFILTER_GATE_VILLAGEDIST:-}" ] && GATES="$GATES -Dprobe.prefilter.gate.villagedist=$PREFILTER_GATE_VILLAGEDIST"
[ -n "${PREFILTER_GATE_PIECES:-}" ] && GATES="$GATES -Dprobe.prefilter.gate.pieces=$PREFILTER_GATE_PIECES"
[ -n "${PREFILTER_GATE_WATER:-}" ] && GATES="$GATES -Dprobe.prefilter.gate.water=$PREFILTER_GATE_WATER"
[ -n "${PREFILTER_GATE_BIOMEREGION:-}" ] && GATES="$GATES -Dprobe.prefilter.gate.biomeregion=$PREFILTER_GATE_BIOMEREGION"
#   PREFILTER_GATE_VILLAGEPIECE  PIECE,DIST: kill unless a village whose layout contains PIECE starts
#                                within DIST blocks of spawn (e.g. ComponentSmeltery,300)
#   PREFILTER_GATE_WITCHCIRCLE   DIST: kill unless a coven circle lands within DIST blocks of spawn.
#                                Needs -Dprobe.prefilter.witchery.replay=true or the gate skips itself.
#   PREFILTER_GATE_ENCHANT       DIST: kill unless a Roguelike enchant table generates within DIST
#                                blocks of spawn. Near dungeons generate first so the kill is cheap.
[ -n "${PREFILTER_GATE_VILLAGEPIECE:-}" ] && GATES="$GATES -Dprobe.prefilter.gate.villagepiece=$PREFILTER_GATE_VILLAGEPIECE"
[ -n "${PREFILTER_GATE_WITCHCIRCLE:-}" ] && GATES="$GATES -Dprobe.prefilter.gate.witcherycircle=$PREFILTER_GATE_WITCHCIRCLE"
[ -n "${PREFILTER_GATE_ENCHANT:-}" ] && GATES="$GATES -Dprobe.prefilter.gate.enchant=$PREFILTER_GATE_ENCHANT"
#   Hammer%-hunt knobs (2026-09-13):
#   PREFILTER_MINESHAFT          N: mineshaft trigger/start scan radius in chunks around origin (-1 off)
#   PREFILTER_MINESHAFTLOOT      true: replay corridor minecart-chest loot (needs PREFILTER_MINESHAFT)
#   PREFILTER_GATE_MINESHAFTTRIG Rg: kill unless a mineshaft trigger lies within Rg chunks of origin
#   PREFILTER_GATE_ROGUETRIG     Rg: kill unless a Roguelike trigger lies within Rg chunks of origin
#   PREFILTER_GATE_STEEL         N[,DIST]: kill unless blacksmith+minecart Block of Steel within DIST
#                                blocks of spawn totals >= N (default DIST 320)
#   PREFILTER_GATE_TOOLLOOT      alumite=A,plate=P,hammer=H,rod=R[,dist=D]: kill unless the Roguelike
#                                TOOLS-chest aggregate within D blocks of spawn meets every minimum
[ -n "${PREFILTER_MINESHAFT:-}" ] && GATES="$GATES -Dprobe.prefilter.mineshaft=$PREFILTER_MINESHAFT"
[ -n "${PREFILTER_MINESHAFTLOOT:-}" ] && GATES="$GATES -Dprobe.prefilter.mineshaftloot=$PREFILTER_MINESHAFTLOOT"
[ -n "${PREFILTER_GATE_MINESHAFTTRIG:-}" ] && GATES="$GATES -Dprobe.prefilter.gate.mineshafttrig=$PREFILTER_GATE_MINESHAFTTRIG"
[ -n "${PREFILTER_GATE_ROGUETRIG:-}" ] && GATES="$GATES -Dprobe.prefilter.gate.roguetrig=$PREFILTER_GATE_ROGUETRIG"
[ -n "${PREFILTER_GATE_STEEL:-}" ] && GATES="$GATES -Dprobe.prefilter.gate.steel=$PREFILTER_GATE_STEEL"
[ -n "${PREFILTER_GATE_TOOLLOOT:-}" ] && GATES="$GATES -Dprobe.prefilter.gate.toolloot=$PREFILTER_GATE_TOOLLOOT"
#   Meteor-crater-hunt knobs (2026-09-13):
#   PREFILTER_METEOR             C: AE2 meteorite scan radius in 707-block grid cells around origin (-1 off)
#   PREFILTER_GATE_METEORSIZE    S: candidate filter — skip terrain for size rolls below S (default 7.8)
#   PREFILTER_GATE_METEORRELIEF  H: mark candidates with < H blocks of 3x3-chunk relief reject=flat (default 20)
#   PREFILTER_GATE_METEORVOL     V: kill seeds whose best predicted carved volume is below V
[ -n "${PREFILTER_METEOR:-}" ] && GATES="$GATES -Dprobe.prefilter.meteor=$PREFILTER_METEOR"
[ -n "${PREFILTER_GATE_METEORSIZE:-}" ] && GATES="$GATES -Dprobe.prefilter.gate.meteorsize=$PREFILTER_GATE_METEORSIZE"
[ -n "${PREFILTER_GATE_METEORRELIEF:-}" ] && GATES="$GATES -Dprobe.prefilter.gate.meteorrelief=$PREFILTER_GATE_METEORRELIEF"
[ -n "${PREFILTER_GATE_METEORVOL:-}" ] && GATES="$GATES -Dprobe.prefilter.gate.meteorvol=$PREFILTER_GATE_METEORVOL"

"$JAVA_BIN" $JAVA_ARGS \
  -Xmx6G -Xms6G \
  -Dprobe.prefilter="$SEEDSPEC" \
  -Dprobe.prefilter.out="$OUT" \
  -Dprobe.prefilter.radius="$RADIUS" \
  -Dprobe.prefilter.pieces="$PIECES" \
  -Dprobe.prefilter.terrain="${PREFILTER_TERRAIN:-4}" \
  $GATES \
  -Dfml.readTimeout=180 -Dfml.queryResult=confirm \
  ${PROBE_JVMFLAGS:-} \
  -jar "$LAUNCH_JAR" nogui < /dev/null
