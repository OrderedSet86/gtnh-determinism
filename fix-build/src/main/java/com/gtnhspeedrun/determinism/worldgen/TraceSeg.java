package com.gtnhspeedrun.determinism.worldgen;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Diagnostic trace switch for Roguelike segment generation (-Dgtnhdet.traceseg=true). Off by default;
 * used to pin down launch-varying segment placement by diffing logs of two runs.
 */
public final class TraceSeg {

    public static final boolean ON = Boolean.getBoolean("gtnhdet.traceseg");
    public static final Logger LOG = LogManager.getLogger("gtnhdet-trace");

    private TraceSeg() {}
}
