package com.gtnhspeedrun.determinism.worldgen;

import com.gtnhspeedrun.determinism.GtnhDeterminism;

/**
 * {@code -Dgtnhdet.witchtrace=true}: one line per Witchery gate cell, recording the decision that cell made.
 *
 * <p>
 * This exists because a block diff cannot answer whether Witchery placement is route-stable. Its surface
 * structures — covens, wicker men, shacks, goblin huts — are built almost entirely from vanilla blocks, so
 * filtering a persisted-world diff by Witchery block id finds nothing (measured: one such block in a whole
 * radius-15 world), and an unfiltered window around a known site cannot be told apart from the endemic
 * decoration residual (measured: site windows 435/4,641/25,213 differing blocks against control windows running
 * 0 to 8,340). See results/2026-08-29-witchery-route-stability.
 *
 * <p>
 * Tracing the generator's own decisions sidesteps that entirely. Each line carries the cell, the biome verdict,
 * the shuffled handler order and the outcome of every handler tried, so two route arms can be compared on what
 * the generator actually decided rather than on what the terrain around it looks like afterwards.
 *
 * <p>
 * Inert unless the flag is set, and the flag is read once — this sits inside chunk population. Nothing here
 * consumes randomness or changes a decision.
 */
public final class WitcheryTrace {

    public static final boolean TRACE = Boolean.getBoolean("gtnhdet.witchtrace");

    private WitcheryTrace() {}

    /**
     * @param cell   block coordinates of the gate cell (the generator works in block space, not chunk space)
     * @param detail biome verdict, shuffled order, per-handler outcomes and the winner
     */
    public static void cell(net.minecraft.world.World world, int x, int z, String detail) {
        if (!TRACE || !TraceScope.emits(world.getSeed())) return;
        // The world seed is on every line because a warm-probe run generates TWO worlds: the server's own boot
        // world and then the requested seed's. Without this the two are indistinguishable in the log, and a
        // comparison against a seed-specific prediction silently mixes them.
        GtnhDeterminism.LOG.info("[witchtrace] seed={} cell={},{} {}", world.getSeed(), x, z, detail);
    }

    /**
     * The handler order a fresh {@code Random} seeded with FML's per-chunk seed would produce.
     *
     * <p>
     * The stage-0 module predicts the order that way, on the assumption that the shuffle is the first draw taken
     * from the Random that FML hands the generator. This exists to test that assumption against the real run
     * instead of trusting it.
     */
    public static String predictOrder(java.util.List<?> handlers, long worldSeed, int cx, int cz) {
        final java.util.Random fml = new java.util.Random(worldSeed);
        final long xs = fml.nextLong() >> 2 + 1L;
        final long zs = fml.nextLong() >> 2 + 1L;
        final long chunkSeed = (xs * cx + zs * cz) ^ worldSeed;
        final java.util.List<String> names = new java.util.ArrayList<>();
        for (final Object h : handlers) names.add(
            h.getClass()
                .getName());
        java.util.Collections.sort(names);
        java.util.Collections.shuffle(names, new java.util.Random(chunkSeed));
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(' ');
            final String n = names.get(i);
            sb.append(n.substring(n.lastIndexOf('.') + 1));
        }
        return sb.toString();
    }

    /** Short class name, so a line stays readable and diffable. */
    public static String name(Object handler) {
        final String n = handler.getClass()
            .getName();
        final int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(dot + 1);
    }
}
