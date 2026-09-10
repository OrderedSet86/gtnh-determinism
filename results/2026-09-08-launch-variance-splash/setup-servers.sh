#!/usr/bin/env bash
# Clone two private server dirs for the README launch-variance image.
#
#   splash-fixed  = beta3 clone + gtnhsplash + worldgenprobe + gtnhdeterminism
#   splash-stock  = the same clone, gtnhdeterminism removed
#
# splash-stock is cloned FROM splash-fixed rather than from the template, so "both arms have
# identical config and identical amplifier" is a property of `cp -a config`, not of this script
# running the same steps twice. The only difference between the two dirs is one jar.
#
# Private dirs because ~/.cache/gtnh-determinism/beta3 is shared and currently has a warm daemon
# live in it; a config or mods edit in a shared dir is the contamination class
# results/2026-07-24-contamination-forensics was written about.
#
# Clone recipe copied from scripts/probe-farm.sh: hardlink the big read-only payload, REAL-copy
# anything a boot rewrites in place (Forge rewrites config/ on shutdown; OpenSecurity rewrites its
# own loose sound files under mods/).
set -euo pipefail

C=$HOME/.cache/gtnh-determinism
TEMPLATE=${1:-$C/beta3}
FIXED=$C/splash-fixed
STOCK=$C/splash-stock

clone() {  # clone <src> <dst>
  local SRC=$1 DST=$2
  [ -d "$DST" ] && { echo "$DST exists, leaving it alone"; return 0; }
  echo "cloning $SRC -> $DST"
  mkdir -p "$DST"
  for d in mods libraries; do cp -al "$SRC/$d" "$DST/$d"; done
  if [ -d "$SRC/mods/OpenSecurity" ]; then
    rm -rf "$DST/mods/OpenSecurity"
    cp -a "$SRC/mods/OpenSecurity" "$DST/mods/OpenSecurity"
  fi
  for f in lwjgl3ify-forgePatches.jar forge-*.jar minecraft_server.1.7.10.jar java9args.txt; do
    # shellcheck disable=SC2086
    [ -e "$SRC"/$f ] && cp -al "$SRC"/$f "$DST/" || true
  done
  cp -a "$SRC/config" "$DST/config"
  for d in serverutilities GregTech.lang coretweaks nestedmods; do
    [ -e "$SRC/$d" ] && cp -a "$SRC/$d" "$DST/$d" || true
  done
  echo "eula=true" > "$DST/eula.txt"
}

clone "$TEMPLATE" "$FIXED"

# The amplifier is never inherited through a hardlink — a stale hardlinked jar is the classic
# silent-stale failure (see scripts/prefilter.sh). Always deploy it fresh.
rm -f "$FIXED"/mods/gtnhsplash-*.jar
# -sources/-dev artifacts sit in the same directory and sort newer than the real jar often enough
# that an unfiltered `ls -t` picks one; a sources jar in mods/ loads no classes and fails silently.
SPLASH=$(ls -t "$(dirname "$0")"/../../splash-build/build/libs/gtnhsplash-*.jar \
  | grep -vE -- '-(sources|dev|javadoc)\.jar$' | head -1)
[ -n "$SPLASH" ] || { echo "no gtnhsplash jar built — run scripts/build-jar.sh splash" >&2; exit 1; }
unzip -l "$SPLASH" | grep -q 'com/gtnhspeedrun/splash/Plots.class' \
  || { echo "$SPLASH has no compiled classes" >&2; exit 1; }
cp "$SPLASH" "$FIXED/mods/"
echo "deployed $(basename "$SPLASH")"

clone "$FIXED" "$STOCK"

# The one and only difference between the arms.
rm -f "$STOCK"/mods/gtnhdeterminism-*.jar

echo
for d in "$FIXED" "$STOCK"; do
  echo "== $d"
  ( cd "$d/mods" && md5sum gtnhsplash-*.jar worldgenprobe-*.jar gtnhdeterminism-*.jar 2>&1 | sed 's/^/   /' )
done
