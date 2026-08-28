#!/usr/bin/env bash
# Back-compat wrapper: the canonical builder is now build-jar.sh, which handles every jar in the
# repo — probe-build/worldgenprobe-*.jar, fix-build/gtnhdeterminism-*.jar and
# qol-build/gtnhspeedrunqol-*.jar — under the same clean-build + jar-verification +
# md5-checked-deploy discipline.
#
#   build-probe.sh [--deploy <server-dir> ...]   ==   build-jar.sh probe [--deploy ...]
#
# For the other jars use: build-jar.sh <fix|qol> [--deploy <server-dir> ...]
set -euo pipefail
exec "$(cd "$(dirname "$0")" && pwd)/build-jar.sh" probe "$@"
