package com.gtnhspeedrun.tcworldgenfix;

import java.util.Random;

import net.minecraft.world.World;

/**
 * Deterministic per-(chunk, feature) RNG forks shared by the Thaumcraft mixins — vanilla-style coordinate seeding
 * plus a feature salt. Must stay bit-identical between all users: {@code EldritchRingLottery} re-derives another
 * chunk's structure stream with this exact function.
 */
public final class TcForkUtil {

    private TcForkUtil() {}

    public static Random fork(World world, int chunkX, int chunkZ, long salt) {
        return fork(world.getSeed(), chunkX, chunkZ, salt);
    }

    public static Random fork(long worldSeed, int chunkX, int chunkZ, long salt) {
        final Random r = new Random(worldSeed + salt * 0x9E3779B97F4A7C15L);
        final long a = r.nextLong() / 2L * 2L + 1L;
        final long b = r.nextLong() / 2L * 2L + 1L;
        r.setSeed(chunkX * a + chunkZ * b ^ worldSeed ^ salt);
        return r;
    }
}
