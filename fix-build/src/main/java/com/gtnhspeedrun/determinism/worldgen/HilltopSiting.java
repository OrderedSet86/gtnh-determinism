package com.gtnhspeedrun.determinism.worldgen;

import net.minecraft.block.Block;
import net.minecraft.world.World;

/**
 * Virgin-terrain siting for Thaumcraft hilltop stone circles ({@code WorldGenHilltopStones}).
 *
 * <p>
 * Stock decides whether a circle exists by probing five columns of LIVE terrain
 * ({@code World.getBlock}) from inside {@code func_76484_a}. Live terrain at population time depends
 * on how much of the neighbourhood has already been decorated, so circle existence was a function of
 * chunk load order rather than of the seed. Measured on beta-3 seed -1636594104014467454 at radius
 * 60: <b>12 circles walking {@code rows} vs 14 walking {@code spiral}</b>, with one circle unique to
 * {@code rows} and three unique to {@code spiral}.
 *
 * <p>
 * This is the same defect {@link EldritchRingLottery} exists to fix for eldritch rings, and the fix
 * is the same shape: evaluate the identical test against {@link TerrainOracle}'s virgin chunks, which
 * are a pure function of the seed and can be evaluated identically from any chunk at any time.
 *
 * <p>
 * Anchor convention matters and differs from {@link EldritchRingLottery#surfaceY}. Stock anchors the
 * test at {@code world.getHeightValue(x, z)}, which is the first AIR block above the column — one
 * above the top solid block — and then walks up from there. {@link #anchorY} reproduces that, so the
 * {@code d}/{@code y + d - 1} arithmetic below lines up with stock exactly. Verified against a real
 * circle at (36, 97, 278): virgin top solid is grass at y=94, anchor 95, chest lands at 97.
 */
public final class HilltopSiting {

    private HilltopSiting() {}

    /** Hilltop's y-floor: {@code LocationIsValidSpawn} refuses anything below this outright. */
    private static final int MIN_Y = 85;

    /** {@code -Dgtnhdet.hilltopvirgin=false} restores stock live-terrain siting, for A/B measurement. */
    public static final boolean ENABLED = !"false".equals(System.getProperty("gtnhdet.hilltopvirgin"));

    /**
     * The pack's actual valid-base list, captured from {@code GetValidSpawnBlocks()} rather than
     * transcribed. Unpatched Thaumcraft returns {stone, grass, dirt}; GTNH patches it at runtime to
     * also accept sand, gravel and packed ice. Hardcoding the bytecode list rejected every sand site
     * — 43 disagreements on one seed, all {@code base=minecraft:sand}.
     */
    private static volatile Block[] validBases = TcSiting.defaultBases();

    public static void captureValidBases(Block[] blocks) {
        if (blocks != null && blocks.length > 0) validBases = blocks;
    }

    /** Virgin equivalent of {@code world.getHeightValue(x, z)}; -1 for water-topped columns. */
    public static int anchorY(World world, int x, int z) {
        return TcSiting.anchorY(world, x, z);
    }

    public static boolean virginValid(World world, int x, int y, int z) {
        return TcSiting.valid(world, x, y, z, true, MIN_Y, validBases);
    }

    /** Stock's own predicate, on live terrain. Diagnostics only. */
    public static boolean liveValid(World world, int x, int y, int z) {
        return TcSiting.valid(world, x, y, z, false, MIN_Y, validBases);
    }

    public static String reason(World world, int x, int y, int z, boolean virgin) {
        return TcSiting.reason(world, x, y, z, virgin, MIN_Y, validBases);
    }

    /** Per-column reasons for the five-column gate, in probe order. */
    public static String siteReason(World world, int x, int y, int z, boolean virgin) {
        return reason(world, x - 2, y, z - 2, virgin) + "|"
            + reason(world, x, y, z, virgin)
            + "|"
            + reason(world, x + 2, y, z, virgin)
            + "|"
            + reason(world, x + 2, y, z + 2, virgin)
            + "|"
            + reason(world, x, y, z + 2, virgin);
    }
}
