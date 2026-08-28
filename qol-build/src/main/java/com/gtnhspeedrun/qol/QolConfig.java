package com.gtnhspeedrun.qol;

/**
 * System-property switches for the quality-of-life fixes. The mod has no config file, so each switch is a
 * {@code -Dgtnhqol.*} flag read once at class load.
 *
 * <p>
 * Every switch defaults to ON and is phrased as an opt-out, so an A/B run only needs the flag on the control
 * side.
 */
public final class QolConfig {

    /**
     * Keep block-breaking progress when the ItemStack in the held hotbar slot is replaced or mutated underneath
     * the player. Disable with {@code -Dgtnhqol.nokeepmining=true}.
     */
    public static final boolean KEEP_MINING_PROGRESS = !Boolean.getBoolean("gtnhqol.nokeepmining");

    /**
     * Log every keep-or-defer decision the mining fix makes, prefixed {@code [miningtrace]}. Enable with
     * {@code -Dgtnhqol.tracemining=true}. Off by default, and every call site is guarded by this constant, so a
     * shipped jar pays nothing.
     */
    public static final boolean TRACE_MINING = Boolean.getBoolean("gtnhqol.tracemining");

    public static void traceMining(String fmt, Object... args) {
        if (TRACE_MINING) GtnhSpeedrunQol.LOG.info("[miningtrace] " + fmt, args);
    }

    private QolConfig() {}
}
