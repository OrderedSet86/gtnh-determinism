package com.gtnhspeedrun.determinism.worldgen;

import net.minecraft.block.Block;
import net.minecraft.world.World;

/**
 * Virgin-terrain siting for Thaumcraft barrows ({@code WorldGenMound}).
 *
 * <p>
 * Same defect and same fix as {@link HilltopSiting}. {@code func_76484_a} gates on five
 * {@code LocationIsValidSpawn} probes reading LIVE blocks — at (x+9,y+9,z+9), (x,y+9,z),
 * (x+18,y+9,z), (x+18,y+9,z+18), (x,y+9,z+18), an 18x18 footprint sampled at surface level, since the
 * caller passes {@code getHeightValue() - 9} — and the caller's anchor is itself a live
 * {@code getHeightValue}. Both are read from {@link TerrainOracle} here.
 *
 * <p>
 * Unlike the hilltop predicate, the mound's has <b>no minimum-Y floor</b>: its
 * {@code LocationIsValidSpawn} begins at {@code d = 0} with no y check. Otherwise the two are
 * byte-identical, which is why the body lives in {@link TcSiting} rather than being copied.
 *
 * <p>
 * The valid-base list is captured from the generator, never transcribed — see {@link TcSiting}.
 */
public final class MoundSiting {

    private MoundSiting() {}

    /** {@code -Dgtnhdet.moundvirgin=false} restores stock live-terrain siting, for A/B measurement. */
    public static final boolean ENABLED = !"false".equals(System.getProperty("gtnhdet.moundvirgin"));

    /** {@code -Dgtnhdet.mounddiff=true}: log every column where this disagrees with the real method. */
    public static final boolean DIFF = Boolean.getBoolean("gtnhdet.mounddiff");

    private static volatile Block[] validBases = TcSiting.defaultBases();

    public static void captureValidBases(Block[] blocks) {
        if (blocks != null && blocks.length > 0) validBases = blocks;
    }

    /** The mound's anchor: stock passes {@code getHeightValue(x, z) - 9}. */
    public static int anchorY(World world, int x, int z) {
        final int a = TcSiting.anchorY(world, x, z);
        return a < 0 ? a : a - 9;
    }

    public static boolean virginValid(World world, int x, int y, int z) {
        return TcSiting.valid(world, x, y, z, true, TcSiting.NO_FLOOR, validBases);
    }

    public static boolean liveValid(World world, int x, int y, int z) {
        return TcSiting.valid(world, x, y, z, false, TcSiting.NO_FLOOR, validBases);
    }

    public static String reason(World world, int x, int y, int z, boolean virgin) {
        return TcSiting.reason(world, x, y, z, virgin, TcSiting.NO_FLOOR, validBases);
    }
}
