#!/usr/bin/env bash
# Back-compat wrapper: the canonical builder is now build-jar.sh, which handles BOTH the probe
# (probe-build/worldgenprobe-*.jar) and the fix jar (tcfix-build/gtnhdeterminism-*.jar) under the
# same clean-build + jar-verification + md5-checked-deploy discipline.
#
#   build-probe.sh [--deploy <server-dir> ...]   ==   build-jar.sh probe [--deploy ...]
#
# For the determinism mod use: build-jar.sh fix [--deploy <server-dir> ...]
set -euo pipefail
exec "$(cd "$(dirname "$0")" && pwd)/build-jar.sh" probe "$@"
