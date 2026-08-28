#!/usr/bin/env bash
# THE canonical builder for every jar in this repo. Every build and deploy goes through here —
# the operational rules that used to live in HANDOFF ("--no-configuration-cache clean build",
# "verify the class landed in the jar", "copy by exact name", "md5-check the copy") are enforced
# here so nobody has to remember them.
#
#   build-jar.sh <probe|fix|qol> [--deploy <server-dir> ...]
#
#     probe -> probe-build/  -> worldgenprobe-*.jar      (the measurement mod)
#     fix   -> fix-build/    -> gtnhdeterminism-*.jar    (the determinism mod)
#     qol   -> qol-build/    -> gtnhspeedrunqol-*.jar    (the client quality-of-life mod)
#
# `build-probe.sh` is a thin back-compat wrapper for `build-jar.sh probe`.
#
# Always builds clean without the Gradle configuration cache: the cache has shipped a stale jar
# (BUILD SUCCESSFUL with a newly added source file silently not compiled — cost us a voided
# experiment on 2026-07-24). After the build, the jar's contents are verified against the source
# tree: every top-level class from src/main/java must be present, plus the mixin resource jsons.
#
# Prints the built jar path + md5 on success. With --deploy, removes that project's old jars from
# each server dir's mods/ and copies the new jar by exact name, md5-verifying each copy.
#
# CRIU pool images are invalidated by ANY mods change — rebuild pool images after a --deploy.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

PROJECT=${1:-}
case "$PROJECT" in
  probe)
    SUBDIR=probe-build; JAR_PREFIX=worldgenprobe
    RESOURCES=(mixins.worldgenprobe.json mixins.worldgenprobe.late.json)
    ;;
  fix)
    SUBDIR=fix-build; JAR_PREFIX=gtnhdeterminism
    RESOURCES=(mixins.gtnhdeterminism.json mixins.gtnhdeterminism.late.json)
    ;;
  qol)
    SUBDIR=qol-build; JAR_PREFIX=gtnhspeedrunqol
    RESOURCES=(mixins.gtnhspeedrunqol.json)
    ;;
  *)
    echo "usage: build-jar.sh <probe|fix|qol> [--deploy <server-dir> ...]" >&2; exit 2
    ;;
esac
shift

BUILD_DIR=$(cd "$SCRIPT_DIR/../$SUBDIR" && pwd)

# Locate a Zulu 21 JDK for Gradle (GTNHGradle needs 17-21; system Java may be newer).
GRADLE_JDK=""
for d in "$HOME"/.gradle/jdks/*21*/; do
  [ -x "$d/bin/java" ] && GRADLE_JDK=${d%/} && break
done
[ -n "$GRADLE_JDK" ] || { echo "no JDK 21 under ~/.gradle/jdks — install one (gradle toolchain download)" >&2; exit 1; }

cd "$BUILD_DIR"
echo "building $PROJECT ($SUBDIR, clean, no configuration cache, JDK $GRADLE_JDK)…"
./gradlew --no-configuration-cache clean spotlessApply build -x test \
  -Dorg.gradle.java.home="$GRADLE_JDK" --console=plain 2>&1 | tail -3

JAR=$(ls -t build/libs/${JAR_PREFIX}-*.jar | grep -v -- '-dev\.jar$' | grep -v -- '-sources\.jar$' | head -1)
[ -n "$JAR" ] || { echo "no jar produced" >&2; exit 1; }

# Verify: every top-level class declared in src/main/java is present in the jar.
MISSING=0
JAR_LIST=$(unzip -l "$JAR")
while IFS= read -r src; do
  rel=${src#src/main/java/}
  cls=${rel%.java}.class
  if ! grep -qF "$cls" <<< "$JAR_LIST"; then
    echo "VERIFY FAIL: $cls missing from jar (source: $src)" >&2
    MISSING=1
  fi
done < <(cd "$BUILD_DIR" && find src/main/java -name '*.java')
for res in "${RESOURCES[@]}"; do
  if ! grep -qF "$res" <<< "$JAR_LIST"; then
    echo "VERIFY FAIL: resource $res missing from jar" >&2
    MISSING=1
  fi
done
[ "$MISSING" -eq 0 ] || { echo "stale/incomplete jar — build system served cached output?" >&2; exit 1; }

MD5=$(md5sum "$JAR" | awk '{print $1}')
echo "OK: $BUILD_DIR/$JAR"
echo "md5: $MD5"

if [ "${1:-}" = "--deploy" ]; then
  shift
  for SRV in "$@"; do
    [ -d "$SRV/mods" ] || { echo "DEPLOY FAIL: $SRV/mods not a directory" >&2; exit 1; }
    rm -f "$SRV/mods/"${JAR_PREFIX}-*.jar
    cp "$JAR" "$SRV/mods/"
    DM=$(md5sum "$SRV/mods/$(basename "$JAR")" | awk '{print $1}')
    [ "$DM" = "$MD5" ] || { echo "DEPLOY FAIL: md5 mismatch in $SRV" >&2; exit 1; }
    echo "deployed: $SRV/mods/$(basename "$JAR")"
  done
fi
