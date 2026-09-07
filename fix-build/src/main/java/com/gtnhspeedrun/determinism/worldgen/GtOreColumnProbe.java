package com.gtnhspeedrun.determinism.worldgen;

/**
 * Carries {@code executeWorldgenChunkified}'s {@code dryRun} argument down to the nine-sample column probe, so
 * {@link GtOrePin#ASSERT_PROBE} can tell a dry-run hit from a real-path one.
 *
 * <p>
 * The probe itself is reached through a {@code @Redirect} on {@code StoneType.findStoneType}, which binds at
 * {@code require = 2} — exactly the two clipping branches — but a redirect sees only the coordinates, not which
 * kind of call it is serving. Under the pin the dry-run path cannot reach those branches at all and the real
 * path still can, so a counter without {@code dryRun} would be permanently nonzero and would assert nothing.
 *
 * <p>
 * <b>Last-value-wins, not a depth counter, and deliberately so.</b> {@code GtOreDryRun} needs a depth counter
 * because its scope nests once per candidate block and must survive a throw. This one does not nest: the
 * eight-argument {@code executeWorldgenChunkified} delegates to the ten-argument body and nothing re-enters it
 * on the same thread, and the column probe runs early in that body with nothing between the head and the probe
 * that can throw. So a plain per-thread slot overwritten at each entry is correct, and unlike a try/finally
 * scope it cannot be left open by an exception — which matters, because {@code GTWorldgenerator} swallows
 * exceptions around the dry run.
 */
public final class GtOreColumnProbe {

    private static final ThreadLocal<boolean[]> DRY_RUN = ThreadLocal.withInitial(() -> new boolean[1]);

    private GtOreColumnProbe() {}

    /** Record the {@code dryRun} argument of the {@code executeWorldgenChunkified} body now being entered. */
    public static void enter(boolean dryRun) {
        DRY_RUN.get()[0] = dryRun;
    }

    /** True when the innermost {@code executeWorldgenChunkified} on this thread is a dry run. */
    public static boolean inDryRun() {
        return DRY_RUN.get()[0];
    }
}
