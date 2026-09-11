#!/usr/bin/env python3
"""Recover the fix jar's build stamp from an arm's probe logs, and refuse mismatched comparisons.

Usage: build-stamp.py <armA> [armB]

``GtnhDeterminism.gtnhdet$logBuildStamp`` emits one line per server start::

    [gtnhdet] version=v0.8-main.7+d5ef7e8e3f-dirty sha=d5ef7e8e3f dirty=true overrides={gtnhdet.chestbatch=false}

so every probe log says which jar generated the worlds in it and which levers were set. This module
reads that back and is imported by ``diff-chests.py`` and ``diff-dungeons.py``, the same way
``diff-region-blocks.py`` imports ``probe-provenance.py``.

Why this exists
---------------
``run-probe.sh`` stamps ``World/.probe-provenance.json`` with jar md5s and ``diff-region-blocks.py``
prints mismatches, but the chest and dungeon diffs consume search reports and ``[chesttrace]`` /
``[dungeonattempt]`` logs, which carried no jar identity at all. So a number copied out of one arm
could not be distinguished from a number copied out of an arm built before a fix — which is exactly
what went wrong on 2026-09-10, when the residual table in
``results/2026-09-10-multifill-vanilla-parity/`` was read as current after
``results/2026-09-10-dungeon-chest-placement/``, committed alongside it, had already superseded it
(128 existence differences and 125 vanilla-dungeon against a shipped 51 and 13). The repo had also
already shipped an A/B that silently compared two different jars.

Where it looks
--------------
An arm is named either by its log glob (``diff-dungeons.py``) or by a directory of JSON search
reports (``diff-chests.py``), and the second has no logs of its own. ``warm-shard.sh`` writes reports
to ``<out>/<order>/seed-*.json`` and logs to the SIBLING ``<out>/<order>-shard*.log``, so `find`
tries, in order: the path's own glob or ``*.log`` inside it, ``*.log`` beside it, and
``<parent>/<name>-shard*.log``. First location that yields a stamp wins.

Policy
------
Unknown is loud but not fatal — every corpus under ``~/.cache/gtnh-determinism/`` predates stamping,
and making those un-diffable would be a worse outcome than an honest warning. A MISMATCH is fatal,
because a cross-jar comparison does not measure what it claims to and there is no reading of its
output that is worth having. Mixed stamps *within* one arm are fatal for the same reason, more so:
half the seeds came from a different jar and the totals are a blend of two worlds.
"""
import re
import sys
from pathlib import Path

STAMP = re.compile(r"\[gtnhdet\] (version=\S+ sha=\S+ dirty=\S+ overrides=\{[^}]*\})")

UNKNOWN_NOTE = ("UNKNOWN — no [gtnhdet] line; these logs predate build stamping, "
                "so this arm's jar cannot be identified")


def _log_candidates(spec):
    """Every place an arm's logs might be, most specific first.

    Yields lists rather than a flat sequence so that a location which exists but holds no stamp does
    not stop the search: a report directory can legitimately contain unrelated logs.
    """
    p = Path(spec)
    if p.is_dir():
        yield sorted(p.glob("*.log"))
    else:
        # A glob like out/'rows-shard*.log' — resolve it the way diff-dungeons.py's logs_for does.
        yield sorted(Path(p.parent or ".").glob(p.name))
    parent = p.parent if p.parent != Path("") else Path(".")
    # warm-shard.sh: reports in <out>/<order>/, logs in <out>/<order>-shard<i>.log beside it.
    yield sorted(parent.glob(f"{p.name}-shard*.log"))
    yield sorted(parent.glob("*.log"))


def find(spec):
    """-> set of distinct stamp strings for one arm. Empty means no stamp was found anywhere."""
    seen = set()
    for logs in _log_candidates(spec):
        for f in logs:
            try:
                with f.open(errors="replace") as fh:
                    for ln in fh:
                        m = STAMP.search(ln)
                        if m:
                            seen.add(m.group(1))
            except OSError:
                continue
        if seen:
            return seen
    return seen


def check(specA, specB, labelA="A", labelB="B", allow_mismatch=False, out=print):
    """Print both arms' stamps and exit unless they agree.

    Returns None. Calls ``sys.exit`` with a message on a mismatch, so callers get the repo's usual
    behaviour: a comparison that cannot be trusted never reaches the point of printing a verdict.
    """
    a, b = find(specA), find(specB)

    def render(label, stamps):
        if not stamps:
            return f"  {label}: {UNKNOWN_NOTE}"
        if len(stamps) == 1:
            return f"  {label}: {next(iter(stamps))}"
        return f"  {label}: {len(stamps)} DIFFERENT stamps:\n" + "\n".join(f"      {s}" for s in sorted(stamps))

    out("build stamp:")
    out(render(labelA, a))
    out(render(labelB, b))

    mixed = [lbl for lbl, s in ((labelA, a), (labelB, b)) if len(s) > 1]
    if mixed and not allow_mismatch:
        sys.exit(f"NO COMPARISON PERFORMED: arm {' and '.join(mixed)} contains logs from more than one jar "
                 f"build. Its totals would be a blend of two different worlds. Re-run the arm against a "
                 f"single jar, or pass --allow-jar-mismatch if you know why they differ.")
    if a and b and a != b and not allow_mismatch:
        sys.exit(f"NO COMPARISON PERFORMED: {labelA} and {labelB} were generated by different jar builds or "
                 f"with different gtnhdet.* levers. A cross-jar diff does not measure route dependence — it "
                 f"measures the jars. Re-run one arm, or pass --allow-jar-mismatch if the difference IS the "
                 f"lever under test.")
    if not a or not b:
        out("  (at least one arm is unstamped — do not quote this run's numbers without naming the jar "
            "some other way)")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        sys.exit(__doc__.splitlines()[2])
    if len(args) == 1:
        stamps = find(args[0])
        if not stamps:
            print(UNKNOWN_NOTE)
            return 1
        for s in sorted(stamps):
            print(s)
        return 0
    check(args[0], args[1], allow_mismatch="--allow-jar-mismatch" in sys.argv)
    return 0


if __name__ == "__main__":
    sys.exit(main())
