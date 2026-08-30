package com.gtnhspeedrun.determinism.worldgen;

/**
 * Which world's worldgen the diagnostic traces are allowed to report on.
 *
 * <h2>The problem this closes</h2>
 *
 * A warm multi-seed probe run generates more than one world: the server's own boot world at startup, and then the
 * world for each requested seed. Worldgen instrumentation fires in all of them, and a trace line carries no
 * inherent clue as to which — so every consumer has to remember to filter, and one that forgets silently analyses
 * two worlds as if they were one.
 *
 * <p>
 * That is not a hypothetical. The Witchery placement trace was 46% boot-world lines, and it produced two wrong
 * findings before the chunk seeds were printed side by side and disagreed: a non-existent discrepancy in the
 * probe's own structure dump, and inflated cell and placement counts.
 *
 * <p>
 * The obvious structural fix — stop generating the boot world at all — was tried and <b>rejected on measurement</b>:
 * it changes the measured world by 314 of 625 chunks against a 2-4 chunk noise floor. See
 * {@code BootWorldPreloadMixin} in the probe.
 *
 * <h2>How it works</h2>
 *
 * The harness sets the system property {@code gtnhdet.tracescope} to the seed it is measuring; traces then emit
 * only for that world. It is a system property rather than an API call so the probe needs no compile-time
 * dependency on this jar, and a stale value cannot outlive the process.
 *
 * <p>
 * Unset means "trace everything", which is the right default: a cold run generates exactly one world, and anyone
 * using the trace outside the harness wants all of it. The scope is an opt-in narrowing by a caller that knows it
 * is generating more than one world.
 *
 * <p>
 * The special value {@code none} denies everything. {@code warm-probe.sh} passes it at launch, because the boot
 * world generates before the probe gets a chance to name a seed — so warm mode is deny-by-default until the probe
 * declares which world it is measuring, and a future instrument gets that protection without knowing it exists.
 */
public final class TraceScope {

    private static final String PROP = "gtnhdet.tracescope";

    private TraceScope() {}

    /**
     * True when a trace line for this world should be emitted.
     *
     * <p>
     * Read live rather than cached: the harness moves the scope from seed to seed within one process, so a value
     * captured at class-init would pin the trace to whichever world happened to generate first — the exact failure
     * this exists to prevent.
     */
    public static boolean emits(long worldSeed) {
        final String scope = System.getProperty(PROP);
        if (scope == null || scope.isEmpty()) return true;
        // "none" is the harness's default-deny. A warm run generates the boot world before the probe can name a
        // seed, so the launch script sets this and the probe replaces it per seed; anything generated before that
        // point is, by construction, not the world under measurement.
        if ("none".equals(scope.trim())) return false;
        try {
            return Long.parseLong(scope.trim()) == worldSeed;
        } catch (NumberFormatException malformed) {
            // A scope nobody can parse must not silently discard every line.
            return true;
        }
    }
}
