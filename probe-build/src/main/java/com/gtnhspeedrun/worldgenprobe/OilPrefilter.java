package com.gtnhspeedrun.worldgenprobe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.world.World;

/**
 * Stage-0 module: BuildCraft oil lakes and spouts, by running the mod's own generator.
 *
 * <p>
 * Oil is <b>not</b> GregTech. It is {@code buildcraft.energy.worldgen.OilPopulate}, a listener on
 * {@code PopulateChunkEvent.Pre}, and that placement is the friendliest one this prefilter has met:
 *
 * <ul>
 * <li><b>The Random is free.</b> RWG reseeds the populate Random from {@code (worldSeed, cx, cz)} and
 * posts {@code PopulateChunkEvent.Pre} on the very next line, so {@code OilPopulate} is the FIRST
 * consumer of a freshly position-seeded stream. Nothing has to be replayed to reach it.</li>
 * <li><b>The terrain is right.</b> Pre fires before decoration, so {@code generateOil} reads exactly
 * the virgin terrain {@link Prefilter.VirginChunkProvider} serves. This avoids the one-block
 * ground-height bound the Witchery replay carries, where the mod runs after decoration and the
 * prefilter cannot.</li>
 * <li><b>The gate is all reads.</b> Two {@code nextInt} draws for the in-chunk position, then the
 * biome and the excluded/surface-deposit sets, before anything is written.</li>
 * </ul>
 *
 * <p>
 * So this dispatches rather than reimplementing: seed a Random exactly as RWG does, call the real
 * {@code generateOil} against {@link Prefilter.SeedProbeWorld}'s scratch overlay, and read the oil out
 * of the overlay. Placement rules, biome sets and the lake/spout shapes are the mod's own.
 *
 * <p>
 * <b>One fidelity gap, stated rather than hidden.</b> The real listener first calls
 * {@code TerrainGen.populate(..., EVENT_TYPE)}, which posts an event any mod may veto. This calls
 * {@code generateOil} directly and therefore assumes nobody vetoes oil. Nothing in the pack is known
 * to, but "known" is not "verified", and a veto would show up as predicted oil that is not there.
 */
final class OilPrefilter {

    private static Object instance;
    private static Method generateOil;
    private static Object oilBlock;
    private static Object oilBlockFlowing;
    private static boolean resolved;
    private static String unavailable;

    private OilPrefilter() {}

    /** Null when BuildCraft is present and usable; otherwise the reason, reported rather than blanked. */
    static synchronized String resolve() {
        if (resolved) return unavailable;
        resolved = true;
        try {
            final Class<?> cls = Class.forName("buildcraft.energy.worldgen.OilPopulate");
            final Field inst = cls.getDeclaredField("INSTANCE");
            inst.setAccessible(true);
            instance = inst.get(null);
            generateOil = cls.getMethod("generateOil", World.class, Random.class, int.class, int.class);
            final Class<?> bce = Class.forName("buildcraft.BuildCraftEnergy");
            oilBlock = staticField(bce, "blockOil");
            oilBlockFlowing = staticField(bce, "blockOilFlowing", "blockFlowOil");
            if (instance == null) {
                unavailable = "BuildCraft OilPopulate.INSTANCE is null";
                return unavailable;
            }
            // An empty result and a disabled generator are different answers, and returning [] for both
            // would report "no oil here" for a pack that cannot produce BuildCraft oil anywhere.
            // daily-707 sets oilWellGenerationRate=0 and spawnOilSprings=false, and generates oil through
            // com.dreammaster.modfixes.oilgen.OilGeneratorFix in the GTNH core mod instead. That one is a
            // PopulateChunkEvent.Post handler, so its Random has already been through every lake, ore and
            // decoration draw of the chunk — there is no cheap position-derived entry to it the way there
            // is for a Pre handler — and its OilDepostMinDistance gate consults previously placed
            // deposits, which is order-dependent state. This module does not read it.
            final Object scalar = staticField(Class.forName("buildcraft.BuildCraftEnergy"), "oilWellScalar");
            if (scalar instanceof Number && ((Number) scalar).doubleValue() == 0.0) {
                unavailable = "BuildCraft oil generation is disabled in this pack (oilWellScalar=0); "
                    + "GTNH generates oil via com.dreammaster.modfixes.oilgen.OilGeneratorFix, "
                    + "which this module does not read";
            }
        } catch (Throwable t) {
            unavailable = "buildcraft oil unavailable: " + t;
        }
        return unavailable;
    }

    private static Object staticField(Class<?> cls, String... names) {
        for (final String n : names) {
            try {
                final Field f = cls.getDeclaredField(n);
                f.setAccessible(true);
                return f.get(null);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** One chunk that produced oil. */
    static final class Site {

        int cx, cz;
        int blocks;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int x, z; // a representative oil column

        /**
         * A LARGE well is the visible spout: BuildCraft raises an oil column well above the surface,
         * while a lake is flat. Height is the only discriminator available from the block set alone,
         * and it is reported rather than thresholded here so the consumer can pick its own bar.
         */
        int height() {
            return maxY - minY + 1;
        }
    }

    /**
     * Runs the real oil generator over a chunk window. Costs a chunk generation only where the biome
     * gate passes, because everything before that is a biome lookup and two draws.
     */
    static List<Site> scan(Prefilter.SeedProbeWorld world, int cx0, int cz0, int radius) throws Exception {
        final List<Site> out = new ArrayList<>();
        if (unavailable != null) return out;
        final long worldSeed = world.getSeed();
        final Random seeder = new Random();
        for (int cx = cx0 - radius; cx <= cx0 + radius; cx++) {
            for (int cz = cz0 - radius; cz <= cz0 + radius; cz++) {
                // Exactly ChunkGeneratorRealistic.populate's prologue. Getting this wrong would not
                // throw; it would quietly place oil somewhere else.
                seeder.setSeed(worldSeed);
                final long i1 = seeder.nextLong() / 2L * 2L + 1L;
                final long j1 = seeder.nextLong() / 2L * 2L + 1L;
                final Random rand = new Random((long) cx * i1 + (long) cz * j1 ^ worldSeed);

                world.beginOverlay();
                try {
                    generateOil.invoke(instance, world, rand, cx, cz);
                } catch (Throwable t) {
                    world.endOverlay();
                    continue;
                }
                final Map<Long, int[]> blocks = world.overlayBlocks();
                Site site = null;
                for (final Map.Entry<Long, int[]> e : blocks.entrySet()) {
                    if (!isOil(e.getValue()[0])) continue;
                    final int[] p = Prefilter.SeedProbeWorld.okeyToPos(e.getKey());
                    if (site == null) {
                        site = new Site();
                        site.cx = cx;
                        site.cz = cz;
                        site.x = p[0];
                        site.z = p[2];
                    }
                    site.blocks++;
                    if (p[1] < site.minY) site.minY = p[1];
                    if (p[1] > site.maxY) {
                        site.maxY = p[1];
                        site.x = p[0];
                        site.z = p[2];
                    }
                }
                world.endOverlay();
                if (site != null) out.add(site);
            }
        }
        return out;
    }

    private static boolean isOil(int blockId) {
        final net.minecraft.block.Block b = net.minecraft.block.Block.getBlockById(blockId);
        return b != null && (b == oilBlock || b == oilBlockFlowing);
    }
}
