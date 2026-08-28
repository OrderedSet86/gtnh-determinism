package com.gtnhspeedrun.determinism.worldgen;

import com.gtnhspeedrun.determinism.GtnhDeterminism;

/**
 * Property-gated worldgen decision trace (the "which decision flipped" tool — diff two walk orders' logs rather
 * than reading the code and guessing). Off unless {@code -Dgtnhdet.traceslices=true}, and every call site is
 * guarded by the constant so a shipped jar pays nothing.
 *
 * <p>
 * Lines are prefixed {@code [slicetrace]} and are deliberately position-keyed and order-insensitive in content, so
 * two runs' logs can be diffed after a plain {@code grep + sort}. Use it like:
 *
 * <pre>
 * grep -o '\[slicetrace\].*' rows.log | sort &gt; a
 * grep -o '\[slicetrace\].*' spiral.log | sort &gt; b
 * diff a b
 * </pre>
 */
public final class SliceTrace {

    public static final boolean ON = Boolean.getBoolean("gtnhdet.traceslices");

    public static void log(String fmt, Object... args) {
        if (ON) GtnhDeterminism.LOG.info("[slicetrace] " + fmt, args);
    }

    private SliceTrace() {}
}
