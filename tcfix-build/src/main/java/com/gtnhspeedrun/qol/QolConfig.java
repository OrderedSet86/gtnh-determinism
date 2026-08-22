package com.gtnhspeedrun.qol;

import com.gtnhspeedrun.GtnhDeterminism;

/**
 * System-property switches for the client quality-of-life fixes. The mod has no config file, so it follows the
 * same convention as {@code gtnhdet.traceseg} and {@code gtnhdet.atomicdungeon}: a {@code -Dgtnhdet.*} flag,
 * read once at class load.
 *
 * <p>
 * Every switch here defaults to ON and is phrased as an opt-out, so an A/B run only needs the flag on the
 * control side.
 */
public final class QolConfig {

    /**
     * Keep block-breaking progress when the ItemStack in the held hotbar slot is replaced or mutated underneath
     * the player. Disable with {@code -Dgtnhdet.nokeepmining=true}.
     */
    public static final boolean KEEP_MINING_PROGRESS = !Boolean.getBoolean("gtnhdet.nokeepmining");

    /**
     * Log every keep-or-defer decision the mining fix makes, prefixed {@code [miningtrace]}. Enable with
     * {@code -Dgtnhdet.tracemining=true}. Off by default, and every call site is guarded by this constant, so a
     * shipped jar pays nothing.
     */
    public static final boolean TRACE_MINING = Boolean.getBoolean("gtnhdet.tracemining");

    public static void traceMining(String fmt, Object... args) {
        if (TRACE_MINING) GtnhDeterminism.LOG.info("[miningtrace] " + fmt, args);
    }

    private QolConfig() {}
}
