package com.gtnhspeedrun.determinism.worldgen;

import java.util.Random;

import net.minecraft.world.World;

/**
 * Seeds for the RWG and RWG2 populate stages, so that ores and decoration are a pure function of world seed and
 * position.
 *
 * <p>
 * {@code ChunkGeneratorRealistic.populate} seeds one {@code Random} per chunk, and structure pieces, lakes, dungeons,
 * the ore pass, decoration and worldgen animals all draw from that {@code Random}. Lakes, village houses and many
 * decorators return before their draws when a test of the live terrain fails, so each later stage of the chunk reads a
 * stream position that depends on what was already generated. Two fixes remove that dependence:
 *
 * <ul>
 * <li>{@link Stage#reseed} re-seeds the populate {@code Random} from world seed, chunk and stage right before the ore
 * pass and right before decoration. A draw earlier in the chunk then cannot move what comes after.</li>
 * <li>{@link #createDecoratorRandom} gives each decorator call a {@code Random} seeded from world seed, position and
 * decorator class. A decorator draws nothing from the shared stream, so a decorator that stops early on terrain does
 * not move the positions rolled for later decorators.</li>
 * </ul>
 *
 * <p>
 * {@code -Dgtnhdet.rwgdecoseed=false} turns both off, for A/B measurement.
 */
public final class RwgPopulateRng {

    public static final boolean ON = !"false".equals(System.getProperty("gtnhdet.rwgdecoseed"));

    private RwgPopulateRng() {}

    /**
     * The {@code Random} one decorator call draws from. The decorator's class is part of the seed, so two decorators
     * called at one position do not mirror each other.
     */
    public static Random createDecoratorRandom(WorldPosition position, Object decorator) {
        long seed = position.world()
            .getSeed()
            ^ decorator.getClass()
                .getName()
                .hashCode() * 0x9E3779B97F4A7C15L;
        seed = mixBits(seed + position.x() * 0xC2B2AE3D27D4EB4FL);
        seed = mixBits(seed + position.y() * 0x165667B19E3779F9L);
        seed = mixBits(seed + position.z() * 0xD6E8FEB86659FD93L);
        return new Random(seed);
    }

    // SplitMix64 finaliser
    private static long mixBits(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    /** A populate stage, with one seed salt per stage. */
    public enum Stage {

        // other TcForkUtil callers use small integers, 101 and 0xDEE95147. In ASCII these salts are RWOR and RWDC
        ORES(0x52574F52L),
        DECORATION(0x52574443L);

        private final long salt;

        Stage(long salt) {
            this.salt = salt;
        }

        /** Re-seed the generator's populate {@code Random} for this stage of one chunk. */
        public void reseed(Random populateRandom, PopulateChunk chunk) {
            if (!ON) return;
            populateRandom.setSeed(
                TcForkUtil.fork(chunk.world(), chunk.chunkX(), chunk.chunkZ(), salt)
                    .nextLong());
        }
    }

    /** The chunk that {@code populate} decorates. */
    public record PopulateChunk(World world, int chunkX, int chunkZ) {}
}
