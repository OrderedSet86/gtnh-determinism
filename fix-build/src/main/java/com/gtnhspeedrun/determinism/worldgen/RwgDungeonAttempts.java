package com.gtnhspeedrun.determinism.worldgen;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import net.minecraft.world.gen.feature.WorldGenDungeons;

import com.gtnhspeedrun.determinism.GtnhDeterminism;

/**
 * Pins the coordinates of the eight dungeon attempts that RWG and RWG2 make per chunk, for
 * {@code RwgDungeonAttemptMixin} and {@code Rwg2DungeonAttemptMixin}. Both generators run the same loop on the shared
 * populate {@code Random}. The mechanisms, and why only the draw shift is fixed here, are in
 * {@code RwgDungeonAttemptMixin}.
 */
public final class RwgDungeonAttempts {

    private static final boolean TRACE = Boolean.getBoolean("gtnhdet.dungeontrace");

    /** {@code -Dgtnhdet.dungeonfork=false} restores stock attempt coordinates, for A/B measurement. */
    private static final boolean FORK = !"false".equals(System.getProperty("gtnhdet.dungeonfork"));

    /** Salt for the per-chunk dungeon stream. No Thaumcraft feature uses salt 40. */
    private static final long SALT = 40L;

    /**
     * Attempts made so far in each chunk whose populate is running, keyed by chunk.
     *
     * <p>
     * Population nests: a structure that writes across a chunk border populates the neighbour inside the outer chunk's
     * call. With one counter for all chunks, the inner chunk's reset clobbers the outer chunk's count, and how often
     * the clobbering happens depends on walk order. Measured with one counter: 4 of 24 seeds differed rows vs spiral by
     * one room. Each thread has a separate map, so concurrent populations do not share counts.
     */
    private static final ThreadLocal<Map<Long, Integer>> ATTEMPT_INDEX = ThreadLocal.withInitial(HashMap::new);

    private RwgDungeonAttempts() {}

    /** Call at the HEAD of the generator's populate. */
    public static void resetAttemptIndex(int chunkX, int chunkZ) {
        ATTEMPT_INDEX.get()
            .put(chunkKey(chunkX, chunkZ), 0);
    }

    /** Call when the generator's populate returns, so the map has entries only for chunks being populated. */
    public static void finishAttempts(int chunkX, int chunkZ) {
        ATTEMPT_INDEX.get()
            .remove(chunkKey(chunkX, chunkZ));
    }

    /**
     * Replaces the generator's {@code WorldGenDungeons.generate} call inside the attempt loop.
     *
     * @param stockAttempt the coordinates the stock loop drew for this attempt
     */
    public static boolean generateAttempt(WorldGenDungeons dungeons, Random sharedRandom, WorldPosition stockAttempt) {
        WorldPosition attempt = stockAttempt;
        if (FORK) {
            // the stock loop draws x = chunkX * 16 + nextInt(16) + 8, so (x - 8) >> 4 is chunkX for any draw
            final int chunkX = (stockAttempt.x() - 8) >> 4;
            final int chunkZ = (stockAttempt.z() - 8) >> 4;
            final Map<Long, Integer> counters = ATTEMPT_INDEX.get();
            final long key = chunkKey(chunkX, chunkZ);
            final int attemptIndex = counters.getOrDefault(key, 0);
            counters.put(key, attemptIndex + 1);
            final Random attemptRandom = TcForkUtil.fork(stockAttempt.world(), chunkX, chunkZ, SALT);
            // replay the earlier attempts' draws, so attempt N depends only on seed, chunk and N
            for (int i = 0; i < attemptIndex; i++) {
                attemptRandom.nextInt(16);
                attemptRandom.nextInt(128);
                attemptRandom.nextInt(16);
            }
            attempt = new WorldPosition(
                stockAttempt.world(),
                chunkX * 16 + attemptRandom.nextInt(16) + 8,
                attemptRandom.nextInt(128),
                chunkZ * 16 + attemptRandom.nextInt(16) + 8);
        }

        // the shared Random is passed through, so populate makes the same number of draws as stock
        final boolean built = dungeons.generate(attempt.world(), sharedRandom, attempt.x(), attempt.y(), attempt.z());
        if (TRACE) {
            // warm runs generate a boot world before the first requested seed, and this trace has no TraceScope. Log
            // the seed, so the worlds can be told apart in diff-dungeons.py
            GtnhDeterminism.LOG.info(
                "[dungeonattempt] seed={} x={} y={} z={} built={}",
                attempt.world()
                    .getSeed(),
                attempt.x(),
                attempt.y(),
                attempt.z(),
                built);
        }
        return built;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}
