package com.gtnhspeedrun.determinism.worldgen;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

/**
 * The Thaumcraft {@code LocationIsValidSpawn} predicate, shared by hilltop stone circles and barrows.
 *
 * <p>
 * {@code WorldGenHilltopStones} and {@code WorldGenMound} carry byte-identical copies of this method,
 * differing only in whether a minimum-Y floor applies (hilltop refuses below 85; the mound has no
 * floor). Both read LIVE blocks, which is what made their placement a function of chunk load order
 * rather than of the seed. This class states the predicate once and lets it be evaluated against
 * either terrain source.
 *
 * <p>
 * <b>The valid-base list is never transcribed.</b> Unpatched Thaumcraft's
 * {@code GetValidSpawnBlocks()} returns {stone, grass, dirt}, and hardcoding that produced a fix that
 * rejected every sand site — 43 disagreements against the real method on one seed, every one of them
 * {@code base=minecraft:sand}, because GTNH patches the list at runtime to include sand, gravel and
 * packed ice. Callers pass the array they read from the live generator instead.
 */
public final class TcSiting {

    private TcSiting() {}

    /** No minimum-Y floor (the mound's case). */
    public static final int NO_FLOOR = Integer.MIN_VALUE;

    /** Block read from either terrain source, so one predicate serves both. */
    private static Block src(World world, int x, int y, int z, boolean virgin) {
        return virgin ? TerrainOracle.block(world, x, y, z) : world.getBlock(x, y, z);
    }

    /**
     * Virgin equivalent of {@code world.getHeightValue(x, z)}: one above the top non-air block.
     * Returns -1 for a water-topped column or no terrain in range. Stock cannot express that, but it
     * rejects such columns anyway — the base would be water, which is not in any valid list.
     */
    public static int anchorY(World world, int x, int z) {
        for (int y = 200; y > 40; y--) {
            final Block b = TerrainOracle.block(world, x, y, z);
            if (b == Blocks.air) continue;
            if (b.getMaterial() == Material.water) return -1;
            return y + 1;
        }
        return -1;
    }

    /**
     * Stock's body, from bytecode: optional y floor; climb while the column is solid; refuse if that
     * climb exceeded 2 (too steep); take {@code base} at {@code y + d - 1}; require air above it;
     * accept when {@code base} is in {@code validBases}, or when it is a snow layer or tall grass
     * sitting directly on one of those.
     *
     * @return {@code "ok"}, or a short reason — so a rejection can be attributed to a clause rather
     *         than guessed at.
     */
    public static String reason(World world, int x, int y, int z, boolean virgin, int minY, Block[] validBases) {
        if (minY != NO_FLOOR && y < minY) return "y<" + minY;
        int d = 0;
        while (d <= 3 && src(world, x, y + d, z, virgin) != Blocks.air) d++;
        if (d > 2) return "steep(d=" + d + ")";
        final int top = y + d - 1;
        final Block base = src(world, x, top, z, virgin);
        final Block above = src(world, x, top + 1, z, virgin);
        final Block below = src(world, x, top - 1, z, virgin);
        if (above != Blocks.air) return "above=" + name(above);
        for (final Block valid : validBases) {
            if (base == valid) return "ok";
            if ((base == Blocks.snow_layer || base == Blocks.tallgrass) && below == valid) return "ok";
        }
        return "base=" + name(base);
    }

    public static boolean valid(World world, int x, int y, int z, boolean virgin, int minY, Block[] validBases) {
        return "ok".equals(reason(world, x, y, z, virgin, minY, validBases));
    }

    private static String name(Block b) {
        final Object n = Block.blockRegistry.getNameForObject(b);
        return n == null ? String.valueOf(b) : String.valueOf(n);
    }

    /**
     * Fallback valid-base list: GTNH's patched value, used only until the real array has been captured
     * from the generator. Kept as the patched list rather than Thaumcraft's default so that a missed
     * capture fails toward the pack's behaviour instead of silently reverting to the bug.
     */
    public static Block[] defaultBases() {
        return new Block[] { Blocks.stone, Blocks.sand, Blocks.packed_ice, Blocks.grass, Blocks.gravel, Blocks.dirt };
    }
}
