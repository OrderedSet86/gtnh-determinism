#!/usr/bin/env bash
# Sand-depth sweep, first use of the format-3 per-column metrics.
#
# Why: the corpus rankings built on sandY span/footprint were wrong — a 4-block sand blanket on a
# 13-block hillside reads as a "9-deep pit". Ground truth from the -3013484044701601670 world save
# (max sand run 4, gravel 9-11 blocks buried) forced per-column measurement instead of inference.
# Prefilter now emits sand5/sand7/sandMax/gravelMinBurial per chunk; the full probe emits per-column
# sandRun/gravelBurial/clayBurial.
#
# Ungated on purpose: a 300-seed pilot showed sandMax is quantised {0,1,4,5,6} with a ceiling of 6,
# so this sweep is measuring how rare the 5-6 tail is rather than hunting a village.
set -euo pipefail
cd "$(dirname "$0")"
REPO=../..
"$REPO/scripts/prefilter.sh" "random:10000:20260802" "$PWD/sweep.jsonl" > boot-sweep.log 2>&1
echo "SWEEP COMPLETE $(date)"

# COMPLETED 10000/10000. Ungated on purpose: this run measured the sand-depth
# distribution rather than hunting a village. Result: runs are quantised {0,1,4,5,6},
# ceiling 6, so depth is near-constant and farmable-column count is the real variable.
# NOTE: rows carry a 13th field (gravelMinBurial, always 255) removed afterwards as
# useless-and-costly in stage-0; later sweeps emit 12 fields.
