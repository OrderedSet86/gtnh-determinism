package com.gtnhspeedrun.tcworldgenfix;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

/**
 * Seed-pure eldritch ring (obelisk) siting, v2 — region grid at stock density.
 *
 * v1 (jar 0.2) replaced Thaumcraft's population-order maze race with a Matérn-II priority contest over the stock
 * per-chunk 1/66 candidacy rolls. That was order-independent but gave a losing region no fallback when its winner
 * failed the terrain test — measured ~30x fewer obelisks than stock. v2 restores stock density per the community
 * requirement (structure frequency must not change) while leaving the maze SYSTEM itself untouched:
 *
 * <ul>
 * <li>One obelisk site per {@code REGION}x{@code REGION}-chunk region (25x25 ~ stock's effective ~1 ring per
 * ~600 chunks once its race and terrain rejections are accounted for).</li>
 * <li>Each region gets 9 seeded candidate slots confined to its central 3x3 chunks — worst-case spacing between
 * neighboring regions' sites is 23 chunks, and maze cell rects extend at most ±11 chunks, so generated mazes can
 * never overlap (the invariant the stock mazesInRange race existed to protect).</li>
 * <li>The winning site is the FIRST candidate (seeded order) whose 5-column validity test passes on VIRGIN
 * terrain via {@link TerrainOracle} — evaluable identically from any chunk at any time, so the outcome cannot
 * depend on the player's route. Deterministic retry restores the stock race's "keep trying until one fits"
 * density (~1 obelisk per region on land; ocean/cliff regions stay empty, as stock).</li>
 * </ul>
 */
public final class EldritchRingLottery {

    /** Region edge in chunks; 1/625 chunks ~ stock effective ring density. */
    private static final int REGION = 25;
    /** Candidate slots live in region-local chunks [MARGIN, MARGIN+2]^2 — guarantees maze non-overlap. */
    private static final int MARGIN = 11;
    /** Fork salt for the region candidate stream (5 = TC structure stream, kept; 7 = retired v1 priority). */
    private static final long REGION_SALT = 8L;

    private EldritchRingLottery() {}

    /** Candidate: {chunkX, chunkZ, x16, z16, w, h} in seeded try-order. */
    public static List<int[]> candidates(long worldSeed, int rm, int rn) {
        final Random r = TcForkUtil.fork(worldSeed, rm, rn, REGION_SALT);
        final int[] slots = { 0, 1, 2, 3, 4, 5, 6, 7, 8 };
        for (int i = slots.length - 1; i > 0; i--) { // Fisher-Yates with the seeded fork
            final int j = r.nextInt(i + 1);
            final int t = slots[i];
            slots[i] = slots[j];
            slots[j] = t;
        }
        final List<int[]> out = new ArrayList<>(9);
        for (final int slot : slots) {
            final int cx = rm * REGION + MARGIN + slot % 3;
            final int cz = rn * REGION + MARGIN + slot / 3;
            out.add(new int[] { cx, cz, r.nextInt(16), r.nextInt(16), 11 + r.nextInt(6) * 2, 11 + r.nextInt(6) * 2 });
        }
        return out;
    }

    /**
     * If (cx, cz) is its region's winning site, returns {x16, z16, w, h}; otherwise null. Winner = first candidate
     * in seeded order that passes the virgin-terrain validity test. Pure function of (seed, region, terrain).
     */
    public static int[] designatedSite(World world, long worldSeed, int cx, int cz) {
        final int rm = Math.floorDiv(cx, REGION);
        final int rn = Math.floorDiv(cz, REGION);
        final int lx = cx - rm * REGION;
        final int lz = cz - rn * REGION;
        if (lx < MARGIN || lx > MARGIN + 2 || lz < MARGIN || lz > MARGIN + 2) return null; // fast reject
        for (final int[] c : candidates(worldSeed, rm, rn)) {
            if (virginValid(world, c)) {
                return c[0] == cx && c[1] == cz ? new int[] { c[2], c[3], c[4], c[5] } : null;
            }
        }
        return null;
    }

    /** Virgin-terrain surface (top solid, non-water) at block column; -1 if water surface or none in range. */
    public static int surfaceY(World world, int x, int z) {
        for (int y = 200; y > 40; y--) {
            final Block b = TerrainOracle.block(world, x, y, z);
            if (b == Blocks.air) continue;
            if (b.getMaterial() == Material.water) return -1;
            return y;
        }
        return -1;
    }

    /** Stock WorldGenEldritchRing.LocationIsValidSpawn (5 columns, ±2 flatness, base whitelist) on virgin terrain. */
    private static boolean virginValid(World world, int[] c) {
        final int bx = c[0] * 16 + c[2];
        final int bz = c[1] * 16 + c[3];
        final int j = surfaceY(world, bx, bz);
        if (j < 0) return false;
        return columnValid(world, bx - 3, j, bz - 3) && columnValid(world, bx, j, bz)
            && columnValid(world, bx + 3, j, bz)
            && columnValid(world, bx + 3, j, bz + 3)
            && columnValid(world, bx, j, bz + 3);
    }

    private static boolean columnValid(World world, int x, int j, int z) {
        int d = 0;
        while (d <= 2 && TerrainOracle.block(world, x, j + d, z) != Blocks.air) d++;
        if (d > 2) return false; // surface more than 2 above the anchor — too steep
        final int y = j + d - 1;
        final Block base = TerrainOracle.block(world, x, y, z);
        // Stock valid-base list (== GTNH's WitchingGadgets config value); virgin terrain has no snow/tallgrass tops.
        return base == Blocks.stone || base == Blocks.sand
            || base == Blocks.packed_ice
            || base == Blocks.grass
            || base == Blocks.gravel
            || base == Blocks.dirt;
    }
}
