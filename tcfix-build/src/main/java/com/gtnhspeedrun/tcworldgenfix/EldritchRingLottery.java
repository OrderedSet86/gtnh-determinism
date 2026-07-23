package com.gtnhspeedrun.tcworldgenfix;

import java.util.Random;

/**
 * Seed-pure replacement for the eldritch ring (obelisk) exclusion race.
 *
 * Stock Thaumcraft suppresses a ring if {@code MazeHandler.mazesInRange} finds ANY maze cell of a previously
 * generated ring inside a (2w+1)x(2h+1)-chunk window (w,h = 11..21). The maze map fills in population order, so
 * whichever candidate the player's route reaches first claims the whole neighborhood — ring presence at a given
 * site was a per-route lottery (~25+ competing candidates per window at the 1/66-per-chunk candidate rate).
 *
 * With the seeded structure stream from {@code ThaumcraftWorldGeneratorMixin}, ring candidacy (roll, w, h) is a
 * pure function of (worldSeed, chunk). That lets us decide the race statically: a ring yields iff some OTHER
 * candidate with higher seeded priority would place maze cells inside this ring's scan window. Matérn-II style
 * thinning — slightly sparser than the stock sequential race, but order-independent, and no two generated mazes
 * can overlap (cell rects are strict subsets of scan windows, so mutual conflict always suppresses the loser).
 */
public final class EldritchRingLottery {

    /** Salt of the structure stream in ThaumcraftWorldGeneratorMixin — candidacy replay must match it exactly. */
    private static final long STRUCTURE_SALT = 5L;
    /** Salt reserved for ring priority; must stay unused by every other fork call. */
    private static final long PRIORITY_SALT = 7L;
    /** Max scan half-width (w=21) + max maze cell half-extent (21/2 + 1 = 11). */
    private static final int SEARCH_RADIUS = 32;

    private EldritchRingLottery() {}

    /**
     * Replays the salt-5 structure stream: returns {w, h} if this chunk rolls an eldritch ring candidate,
     * null otherwise (no roll, or the barrow mound branch won).
     */
    public static int[] candidate(long worldSeed, int cx, int cz) {
        final Random r = TcForkUtil.fork(worldSeed, cx, cz, STRUCTURE_SALT);
        r.nextInt(16); // randPosX
        r.nextInt(16); // randPosZ
        if (r.nextInt(150) == 0) return null; // barrow mound branch takes precedence over the ring roll
        if (r.nextInt(66) != 0) return null;
        return new int[] { 11 + r.nextInt(6) * 2, 11 + r.nextInt(6) * 2 };
    }

    private static long priority(long worldSeed, int cx, int cz) {
        return TcForkUtil.fork(worldSeed, cx, cz, PRIORITY_SALT)
            .nextLong();
    }

    /**
     * True if the ring at (cx, cz) must yield to a higher-priority candidate whose maze cells would fall inside
     * this ring's scan window. Pure function of the world seed — population order is irrelevant.
     */
    public static boolean suppressed(long worldSeed, int cx, int cz, int w, int h) {
        final long own = priority(worldSeed, cx, cz);
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                if (dx == 0 && dz == 0) continue;
                final int ox = cx + dx;
                final int oz = cz + dz;
                final int[] wh = candidate(worldSeed, ox, oz);
                if (wh == null) continue;
                // Maze cell rect the other ring would occupy (mirrors MazeThread's grid anchor).
                final int col = ox - (1 + wh[0] / 2);
                final int row = oz - (1 + wh[1] / 2);
                if (col + wh[0] - 1 < cx - w || col > cx + w || row + wh[1] - 1 < cz - h || row > cz + h) {
                    continue;
                }
                final long other = priority(worldSeed, ox, oz);
                if (other > own || (other == own && (ox < cx || (ox == cx && oz < cz)))) {
                    return true;
                }
            }
        }
        return false;
    }
}
