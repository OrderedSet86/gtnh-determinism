package com.gtnhspeedrun.splash;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The plot table: which chunks each generator is forced to attempt at.
 *
 * <p>
 * The README splash image is a filmstrip of fixed crop windows, one per generator, rendered from four cold-boot
 * worlds of one seed. That only works if each feature lands at the same coordinates in all four runs, which in turn
 * only works if its siting is forced. Forcing the siting is not a thumb on the scale — it is what makes the picture
 * an experiment instead of an anecdote. With placement pinned, the only thing left that can differ between two
 * launches is each structure's own internal non-determinism, which is exactly what the image is about.
 *
 * <p>
 * Everything here is inert unless {@code -Dsplash.enable=true}. Same discipline as the fix jar's
 * {@code gtnhdet.orepin}: one jar, both A/B arms, switched by a property, so an arm can never differ by jar md5.
 */
public final class Plots {

    private static final Logger LOG = LogManager.getLogger("gtnhsplash");

    public enum Kind {
        VILLAGE,
        SLIME,
        WITCHERY
    }

    /**
     * The lattice, chosen from a {@code -Dsplash.scout} survey of chunks -30..30 on seed
     * -1636594104014467454 rather than guessed.
     *
     * <ul>
     * <li>A slime island is anchored at its chunk's NW corner and only ever extends +X/+Z, at most 32 blocks plus
     * ~3 of tree overhang. One plot only, never adjacent chunks, which would mush two islands into one mass. Sited
     * in Redwood Forest so a bright green island sits over dark canopy.
     * <li>All nine Witchery chunks pass {@code BiomeManager.DISALLOWED_BIOMES}, which is checked before
     * {@code nonInRange} and so cannot be forced past — the pack blacklists 73 biome ids. Structures are
     * chunk-centred and at most 11 blocks wide (coven, 11x11), so the 3x3 cluster packs nine of them with zero
     * overlap and the tile shows nine independent one-in-four flips at once.
     * <li>The two plots are 30 chunks apart, so nothing can interact with anything.
     * </ul>
     *
     * <p>
     * <b>Villages are deliberately not forced.</b> The first attempt mixed
     * {@code MapGenVillage.canSpawnStructureAtCoords} and had no effect at all: VillageNames replaces the village
     * generator through {@code InitMapGenEvent} with {@code astrotibs.villagenames.village.MapGenVillageVN}, which
     * extends {@code MapGenVillage} and overrides both {@code func_75047_a} and {@code func_75049_b}, so an
     * injection into the superclass never runs. Rather than chase it into VillageNames, the image uses villages
     * where the seed already put them: siting is a pure function of the seed (verified — {@code World.setRandomSeed}
     * overwrites {@code World.rand} from the seed, and RWG's {@code areBiomesViable} is a seed-pure noise test), so
     * the crop windows still line up across all four runs, and the picture gets to say the villages are ordinary
     * ones at stock density.
     *
     * <p>
     * The image's fifth column is a y=21 horizontal slice through the Et Futurum deepslate band and needs no
     * forcing either, so it is not in this table.
     *
     * <p>
     * Override wholesale with {@code -Dsplash.plots=cx,cz,kind;...} to retune without a rebuild.
     */
    private static final String DEFAULT_PLOTS = String.join(
        ";",
        // slime island: Redwood Forest
        "-25,10,slime",
        // witchery: 3x3 cluster centred on chunk (-25,-23), Hot Plains
        "-26,-24,witchery",
        "-26,-23,witchery",
        "-26,-22,witchery",
        "-25,-24,witchery",
        "-25,-23,witchery",
        "-25,-22,witchery",
        "-24,-24,witchery",
        "-24,-23,witchery",
        "-24,-22,witchery");

    private static final boolean ENABLED = Boolean.getBoolean("splash.enable");

    private static final Set<Long> VILLAGE = new LinkedHashSet<>();
    private static final Set<Long> SLIME = new LinkedHashSet<>();
    private static final Set<Long> WITCHERY = new LinkedHashSet<>();

    static {
        if (ENABLED) {
            final Map<Kind, Set<Long>> sink = new LinkedHashMap<>();
            sink.put(Kind.VILLAGE, enabled("splash.village") ? VILLAGE : null);
            sink.put(Kind.SLIME, enabled("splash.slime") ? SLIME : null);
            sink.put(Kind.WITCHERY, enabled("splash.witchery") ? WITCHERY : null);
            parse(System.getProperty("splash.plots", DEFAULT_PLOTS), sink);
        }
    }

    private Plots() {}

    /** Per-generator switches default to on once the master switch is on. */
    private static boolean enabled(String key) {
        return !"false".equalsIgnoreCase(System.getProperty(key, "true"));
    }

    private static void parse(String spec, Map<Kind, Set<Long>> sink) {
        for (String entry : spec.split(";")) {
            final String s = entry.trim();
            if (s.isEmpty()) continue;
            final String[] f = s.split(",");
            if (f.length != 3)
                throw new IllegalArgumentException("splash.plots entry '" + s + "': expected cx,cz,kind");
            final Kind kind;
            try {
                kind = Kind.valueOf(
                    f[2].trim()
                        .toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "splash.plots entry '" + s + "': unknown kind '" + f[2].trim() + "'");
            }
            final Set<Long> target = sink.get(kind);
            // A disabled generator still has to parse its own entries, so a typo in a switched-off plot is still
            // caught rather than surfacing three runs later as a missing column.
            if (target != null) target.add(key(Integer.parseInt(f[0].trim()), Integer.parseInt(f[1].trim())));
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static boolean forceVillage(int chunkX, int chunkZ) {
        return !VILLAGE.isEmpty() && VILLAGE.contains(key(chunkX, chunkZ));
    }

    public static boolean forceSlime(int chunkX, int chunkZ) {
        return !SLIME.isEmpty() && SLIME.contains(key(chunkX, chunkZ));
    }

    /**
     * Takes BLOCK coordinates, because that is what {@code WitcheryWorldGenerator.nonInRange} receives.
     *
     * <p>
     * Witchery's own body derives its chunk with {@code blockX / 16} — integer division, truncating toward zero,
     * not an arithmetic shift, so for negative coordinates it disagrees with {@code >> 4} by one. That never bites
     * here: {@code generate} always calls {@code generateOverworld(world, world.rand, chunkX * 16, chunkZ * 16)}
     * (verified by disassembly), so the argument is always an exact multiple of 16 and all three spellings agree.
     * The division is written out anyway to match the code being hooked.
     */
    public static boolean forceWitchery(int blockX, int blockZ) {
        return !WITCHERY.isEmpty() && WITCHERY.contains(key(blockX / 16, blockZ / 16));
    }

    /** Logged once at init so every run's log records the lattice it actually used, not the one in the source. */
    public static void dump() {
        if (!ENABLED) {
            LOG.info("[splash] disabled (-Dsplash.enable not set); this jar is inert");
            return;
        }
        LOG.info(
            "[splash] plots: {} village {}, {} slime {}, {} witchery {}",
            VILLAGE.size(),
            render(VILLAGE),
            SLIME.size(),
            render(SLIME),
            WITCHERY.size(),
            render(WITCHERY));
    }

    private static String render(Set<Long> plots) {
        if (plots.isEmpty()) return "[]";
        final StringBuilder sb = new StringBuilder("[");
        for (long k : Collections.unmodifiableSet(plots)) {
            if (sb.length() > 1) sb.append(' ');
            sb.append('(')
                .append((int) (k >> 32))
                .append(',')
                .append((int) k)
                .append(')');
        }
        return sb.append(']')
            .toString();
    }
}
