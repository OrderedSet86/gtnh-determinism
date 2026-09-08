package com.gtnhspeedrun.determinism.worldgen;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Emitter for {@code DungeonAttemptTraceMixin} ({@code -Dgtnhdet.tracegennear=true}).
 *
 * <p>
 * One line per placement attempt, formatted so two arms diff directly:
 *
 * <pre>
 * [gennear] trigger=-664,840 attempt=0 loc=-711,776
 * [gennear] trigger=-664,840 attempt=0 valid=-711,776 -&gt; false
 * </pre>
 *
 * <p>
 * The attempt counter is keyed to the trigger, not global, so a cascade that starts a second
 * dungeon inside the first does not renumber the outer one's attempts. The trigger is captured from
 * the first {@code getNearbyCoord} of a run because {@code generateNear}'s own {@code (x,z)} is the
 * only identifier available at the call site.
 *
 * <p>
 * Deliberately NOT position-sorted like {@link SliceTrace}: attempt ORDER is the thing under test,
 * so these lines must be compared in emission order.
 */
public final class DungeonAttemptTrace {

    public static final boolean ON = Boolean.getBoolean("gtnhdet.tracegennear");

    private static final Logger LOG = LogManager.getLogger("gtnhdeterminism");

    /** Per-trigger attempt counter. Not thread-safe by design: worldgen is single-threaded here. */
    private static int trigX = Integer.MIN_VALUE;
    private static int trigZ = Integer.MIN_VALUE;
    private static int attempt = -1;

    private DungeonAttemptTrace() {}

    /** A candidate location was rolled from the attempt Random. */
    public static void coord(int fromX, int fromZ, int x, int z) {
        if (!ON) return;
        if (fromX != trigX || fromZ != trigZ) {
            trigX = fromX;
            trigZ = fromZ;
            attempt = 0;
        } else {
            attempt++;
        }
        LOG.info("[gennear] trigger={},{} attempt={} loc={},{}", trigX, trigZ, attempt, x, z);
    }

    /** The validity gate's verdict for that location. */
    public static void valid(int x, int z, boolean ok) {
        if (!ON) return;
        LOG.info("[gennear] trigger={},{} attempt={} valid={},{} -> {}", trigX, trigZ, attempt, x, z, ok);
    }
}
