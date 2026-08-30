package com.gtnhspeedrun.worldgenprobe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.minecraft.world.World;

/**
 * Stage-0 Witchery module: which chunks are candidate structure cells, whether each passes the biome gate, and in
 * what order the handlers will be tried there — all without generating a chunk.
 *
 * <h2>Why this is worldless</h2>
 *
 * Every input is settled before terrain exists, which was established by disassembling the shipped
 * {@code witchery-1.7.10-0.24.1} jar rather than assumed:
 *
 * <ul>
 * <li><b>Candidate cell.</b> {@code WitcheryWorldGenerator.nonInRange} is the vanilla scattered-feature region
 * formula: divide to a region of {@code field_82665_g} chunks, seed a {@code Random} from
 * {@code World.setRandomSeed(regionX, regionZ, 10387312)}, and accept the chunk only if it is the one that region
 * chose. It reads no blocks. Its {@code range} parameter is <em>not used at all</em> — every handler shares one
 * region grid.</li>
 * <li><b>Biome gate.</b> {@code BiomeManager.DISALLOWED_BIOMES} against the biome at
 * {@code (x + midX, z + midZ)}, which the chunk manager answers without a chunk.</li>
 * <li><b>Handler order.</b> With F2 in place the mixin shuffles a name-sorted <em>copy</em> using FML's per-chunk
 * {@code Random}, and that shuffle is the first thing to draw from it — so the order is
 * {@code shuffle(sorted, new Random(chunkSeed(seed, cx, cz)))}.</li>
 * </ul>
 *
 * <p>
 * {@code structuresList} plays no part: it is written and cleared, never read for gating. That is why the placement
 * trace found Witchery perfectly route-stable, and it means these predictions carry no order dependence.
 *
 * <h2>What this deliberately does not predict</h2>
 *
 * The <b>winner</b>. Choosing it means calling {@code IWorldGenHandler.generate}, which reads terrain <em>and
 * writes blocks</em> — and writes would trip {@code SeedProbeWorld}'s guards or corrupt the virgin-terrain oracle
 * they protect. So this emits the cell, the gate verdict and the try-order, and stops. Everything here is
 * checkable against {@code -Dgtnhdet.witchtrace=true} output, which is the point: each field it emits is one the
 * trace also records.
 *
 * <p>
 * Reflection by PLAIN names throughout — Witchery is a mod and is not SRG-renamed.
 */
final class WitcheryPrefilter {

    /** Vanilla scattered-feature salt, read off nonInRange's bytecode. */
    private static final int SALT = 10387312;

    private static Object generator;
    private static Field fMaxDistance;
    private static Field fMinDistance;
    private static Field fMidX;
    private static Field fMidZ;
    private static Field fGenerators;
    /** A Collection, not a Set: Witchery declares DISALLOWED_BIOMES as an ArrayList. */
    private static java.util.Collection<?> disallowedBiomes;
    private static boolean resolved;
    private static String unavailable;

    private WitcheryPrefilter() {}

    /** Null when Witchery is absent or its shape changed; the caller reports the reason rather than a blank. */
    static synchronized String resolve() {
        if (resolved) return unavailable;
        resolved = true;
        try {
            generator = registeredGenerator("WitcheryWorldGenerator");
            if (generator == null) {
                unavailable = "witchery generator not registered";
                return unavailable;
            }
            final Class<?> c = generator.getClass();
            fMaxDistance = field(c, "field_82665_g");
            fMinDistance = field(c, "field_82666_h");
            fMidX = field(c, "midX");
            fMidZ = field(c, "midZ");
            fGenerators = field(c, "generators");
            final Field db = Class.forName("com.emoniph.witchery.worldgen.BiomeManager")
                .getDeclaredField("DISALLOWED_BIOMES");
            db.setAccessible(true);
            disallowedBiomes = (java.util.Collection<?>) db.get(null);
            if (fMaxDistance == null || fMinDistance == null || fMidX == null || fMidZ == null || fGenerators == null) {
                unavailable = "witchery generator fields not found";
            }
        } catch (Throwable t) {
            unavailable = "witchery unavailable: " + t;
        }
        return unavailable;
    }

    private static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                final Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static Object registeredGenerator(String suffix) throws Exception {
        final Class<?> gr = Class.forName("cpw.mods.fml.common.registry.GameRegistry");
        for (final Field f : gr.getDeclaredFields()) {
            if (!Set.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            final Object v = f.get(null);
            if (!(v instanceof Set)) continue;
            for (final Object g : (Set<?>) v) {
                if (g.getClass()
                    .getName()
                    .endsWith(suffix)) return g;
            }
        }
        return null;
    }

    /**
     * {@code nonInRange}, replicated. Deliberately evaluated per chunk rather than per region: the mod's own
     * negative-coordinate adjustment is easy to get subtly wrong when inverted into "which chunk does this region
     * pick", and a per-chunk replica of the real predicate cannot drift from it.
     */
    private static boolean isCandidate(World world, int cx, int cz) throws Exception {
        final int max = fMaxDistance.getInt(generator);
        final int min = fMinDistance.getInt(generator);
        if (max <= 0 || max - min <= 0) return false;
        int rx = cx, rz = cz;
        if (rx < 0) rx -= max - 1;
        if (rz < 0) rz -= max - 1;
        int regionX = rx / max;
        int regionZ = rz / max;
        final Random r = world.setRandomSeed(regionX, regionZ, SALT);
        regionX *= max;
        regionZ *= max;
        regionX += r.nextInt(max - min);
        regionZ += r.nextInt(max - min);
        return cx == regionX && cz == regionZ;
    }

    /** One candidate cell, in the same terms the witchtrace records so the two can be diffed directly. */
    static final class Cell {

        long chunkSeed;
        int x;
        int z;
        int biome;
        boolean allowed;
        List<String> order;
    }

    static List<Cell> candidates(World world, int cx0, int cz0, int radius) throws Exception {
        final List<Cell> out = new ArrayList<>();
        if (unavailable != null) return out;
        final int midX = fMidX.getInt(generator);
        final int midZ = fMidZ.getInt(generator);
        final List<?> handlers = (List<?>) fGenerators.get(generator);
        for (int cx = cx0 - radius; cx <= cx0 + radius; cx++) {
            for (int cz = cz0 - radius; cz <= cz0 + radius; cz++) {
                if (!isCandidate(world, cx, cz)) continue;
                final Cell cell = new Cell();
                cell.x = cx * 16;
                cell.z = cz * 16;
                // The mod samples the biome at the cell's block origin plus the generator's mid offsets, through
                // World.getBiomeGenForCoords. For a chunk that exists that reads the CHUNK's stored biome array
                // (Chunk.getBiomeGenForWorldCoords), not the raw chunk manager — and on RWG the two disagree.
                // Measured: asking the chunk manager gave 87 and 70 where the real run saw 211 and 230, which is
                // enough to flip the gate verdict. Ask the chunk, which VirginChunkProvider can now supply.
                final int bx = cell.x + midX, bz = cell.z + midZ;
                cell.biome = world.getChunkFromChunkCoords(bx >> 4, bz >> 4)
                    .getBiomeGenForWorldCoords(bx & 15, bz & 15, world.getWorldChunkManager()).biomeID;
                cell.allowed = !disallowedBiomes.contains(cell.biome);
                cell.order = new ArrayList<>();
                if (cell.allowed) {
                    final List<String> names = new ArrayList<>();
                    for (final Object h : handlers) names.add(
                        h.getClass()
                            .getName());
                    Collections.sort(names, Comparator.naturalOrder());
                    // Same Random the mixin shuffles with, at the same point in its life: the shuffle is the
                    // first draw generateOverworld takes, so a fresh Random(chunkSeed) reproduces it exactly.
                    cell.chunkSeed = RoguelikePrefilter.chunkSeed(world.getSeed(), cx, cz);
                    Collections.shuffle(names, new Random(cell.chunkSeed));
                    for (final String n : names) out(cell.order, n);
                }
                out.add(cell);
            }
        }
        return out;
    }

    /** Short names, matching what the witchtrace prints, so a comparison needs no name mangling on either side. */
    private static void out(List<String> into, String className) {
        final int dot = className.lastIndexOf('.');
        into.add(dot < 0 ? className : className.substring(dot + 1));
    }
}
