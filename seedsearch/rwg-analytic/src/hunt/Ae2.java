package hunt;

import java.util.Random;

/**
 * AE2-GTNH meteorite candidate RNG, game-free. Mirrors Platform.seedFromGrid — including the
 * `nextLong() >> 2 + 1L` precedence quirk, which parses as `>> 3` and is reproduced here written
 * Forge's way so nobody "corrects" it into a different world — and MeteoriteWorldGen.generate's draw
 * order for overworld defaults (single "0=0.3" spawn-chance entry, cell 707, margin 70). Golden-checked
 * against the in-game prefilter rows on the calibration seeds; if the pack ever changes
 * minMeteoriteDistance or the spawn-chance list, THIS FILE is wrong until updated from the game dump.
 */
final class Ae2 {

    static final int CELL = 707, MARGIN = 70;

    /** Returns {x, z, doubleToLongBits(size), startDepth} for the cell's candidate. */
    static long[] candidate(long worldSeed, int gridX, int gridZ) {
        final Random rng = new Random();
        rng.setSeed(worldSeed);
        final long xSeed = rng.nextLong() >> 2 + 1L;
        final long zSeed = rng.nextLong() >> 2 + 1L;
        rng.setSeed((xSeed * gridX + zSeed * gridZ) ^ worldSeed);
        // the 0.3 roll picks the start depth branch (never existence — 0.13.0 correction)
        final boolean surfaceBranch = rng.nextDouble() < 0.3;
        final int x = gridX * CELL + rng.nextInt(CELL - 2 * MARGIN) + MARGIN;
        final int z = gridZ * CELL + rng.nextInt(CELL - 2 * MARGIN) + MARGIN;
        final int depthRoll = rng.nextInt(20); // drawn unconditionally, used on one branch
        final int startDepth = surfaceBranch ? 180 + depthRoll : 128;
        long meteorSeed = rng.nextLong();
        while (meteorSeed == 0) meteorSeed = rng.nextLong();
        final Random placer = new Random(meteorSeed);
        return new long[] { x, z, Double.doubleToLongBits(placer.nextDouble() * 6.0 + 2), startDepth };
    }

    private Ae2() {}
}
