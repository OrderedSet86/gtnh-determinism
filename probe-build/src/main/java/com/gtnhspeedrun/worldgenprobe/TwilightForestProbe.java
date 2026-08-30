package com.gtnhspeedrun.worldgenprobe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.DimensionManager;

/**
 * Twilight Forest feature map: which major structure sits in each 16-chunk region, and where its centre is.
 *
 * <p>
 * Reached purely by reflection, with no {@code compileOnly} dependency on the mod. That matches how the probe
 * reaches every mod it only <em>reads</em> — GT, Thaumcraft, Witchery, CoFH, BartWorks. Roguelike is the one
 * {@code compileOnly} entry because {@link RoguelikePrefilter} constructs and drives its objects; nothing here
 * does. Reflection also enumerates the feature and treasure statics rather than naming them one by one, so the
 * output adapts when a TF version adds a feature.
 *
 * <p>
 * Enabled by {@code -Dprobe.tffeatures=<regionRadius>} (absent or -1 disables), <em>independently of
 * probe.dim</em>. The scan calls only {@code World.getBiomeGenForCoords}, and
 * {@code World.getBiomeGenForCoordsBody} falls through to the chunk manager whenever {@code blockExists} is
 * false — which it is for every never-generated chunk. So a full region scan generates zero chunks, and an
 * ordinary dim-0 seed-search run can emit the whole map for free provided the TF world was created (that is,
 * {@code -Dprobe.dim0only} did not skip it).
 *
 * <p>
 * The one thing it does need is for dim 7 to <em>exist</em>: {@code TFFeature.getFeatureDirectlyAt} hard-returns
 * {@code nothing} unless {@code world.getWorldChunkManager()} is a {@code TFWorldChunkManager}, so the overworld
 * world cannot stand in.
 *
 * <p>
 * Two coordinate traps, both verified against the shipped 2.7.13 bytecode:
 * <ul>
 * <li>All four {@code TFFeature} lookups take <em>chunk</em> coordinates, not block coordinates.</li>
 * <li>{@code getNearestCenter} returns a chunk-<em>relative</em> block offset ({@code dx*16+8} with dx in
 * [-3,3]), not a position. Only {@code getNearestCenterXYZ} returns absolute block coordinates, so that is what
 * this class uses.</li>
 * </ul>
 *
 * <p>
 * Note that a feature's centre is seed-independent: {@code getNearestCenterXYZ} derives its jitter from
 * {@code regionX*3129871 ^ regionZ*116129781} alone. Only <em>which</em> feature occupies the region varies with
 * the seed, through {@code generateFeatureFor1Point7}'s {@code new Random(worldSeed + x*25117 + z*151121)} and
 * the TF biome at the region centre. Both facts are worth having in the report: the biome is the input that
 * drove the pick, so a consumer can re-derive and validate the map without re-running the probe.
 */
final class TwilightForestProbe {

    /** Chunks per feature-grid region. TF snaps region indices with Math.round(chunk/16f). */
    private static final int GRID_CHUNKS = 16;

    /**
     * Regions of margin added around the walk window. Features reach up to {@code size} chunks from their
     * centre, so a centre one region outside the window can still place blocks inside it.
     */
    private static final int MARGIN_REGIONS = 1;

    private static Boolean present;
    private static Class<?> modClass;
    private static Class<?> featureClass;
    private static Method getFeatureForRegion;
    private static Method getNearestCenterXYZ;
    private static Field fName;
    private static Field fFeatureId;
    private static Field fSize;

    private TwilightForestProbe() {}

    /** Region radius from -Dprobe.tffeatures; negative means the section is off. */
    static int regionRadius() {
        return Integer.getInteger("probe.tffeatures", -1);
    }

    /** {@code twilightforest.TwilightForestMod}, or null when the mod is absent. */
    static Class<?> modClass() {
        resolve();
        return modClass;
    }

    /** TF's configured dimension id, or Integer.MIN_VALUE when the mod is absent. */
    static int dimensionId() {
        if (modClass() == null) return Integer.MIN_VALUE;
        try {
            return modClass.getField("dimensionID")
                .getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not read TwilightForestMod.dimensionID", e);
        }
    }

    /**
     * Resolves the mod's classes and members once. Absent mod is a supported state (null, callers no-op); a
     * present mod with a member missing is not, and propagates — a silently empty feature map would read as
     * "this seed has no structures".
     */
    private static void resolve() {
        if (present != null) return;
        try {
            final ClassLoader cl = TwilightForestProbe.class.getClassLoader();
            // initialize=true: the TFFeature statics are built in <clinit>, and every lookup reads them.
            modClass = Class.forName("twilightforest.TwilightForestMod", true, cl);
            featureClass = Class.forName("twilightforest.TFFeature", true, cl);
        } catch (ClassNotFoundException absent) {
            present = Boolean.FALSE;
            return;
        }
        try {
            getFeatureForRegion = featureClass.getMethod("getFeatureForRegion", int.class, int.class, World.class);
            getNearestCenterXYZ = featureClass.getMethod("getNearestCenterXYZ", int.class, int.class, World.class);
            fName = featureClass.getField("name");
            fFeatureId = featureClass.getField("featureID");
            fSize = featureClass.getField("size");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Twilight Forest is loaded but its feature API does not match", e);
        }
        present = Boolean.TRUE;
    }

    /** The live TF world, or null when the mod is absent or -Dprobe.dim0only skipped its dimension. */
    private static WorldServer tfWorld() {
        resolve();
        if (!Boolean.TRUE.equals(present)) return null;
        return DimensionManager.getWorld(dimensionId());
    }

    /**
     * One entry per feature-grid region covering the walk window, or null when the section is disabled, the mod
     * is absent, or its dimension was not created.
     *
     * <p>
     * Regions whose feature is {@code nothing} are emitted too: absence is the denominator for any density
     * statistic, and omitting it would make "no labyrinth here" indistinguishable from "not scanned".
     */
    static String buildFeatureMap(int radius, int cx, int cz) {
        final int extra = regionRadius();
        if (extra < 0) return null;
        final WorldServer w = tfWorld();
        if (w == null) {
            WorldgenProbe.LOG.warn(
                "[probe] -Dprobe.tffeatures is set but the Twilight Forest world is absent "
                    + "(mod not loaded, or -Dprobe.dim0only skipped dim {}) — section omitted",
                modClass() == null ? "?" : Integer.toString(dimensionId()));
            return null;
        }
        final int margin = Math.max(MARGIN_REGIONS, extra);
        final int gxMin = region(cx - radius) - margin, gxMax = region(cx + radius) + margin;
        final int gzMin = region(cz - radius) - margin, gzMax = region(cz + radius) + margin;

        final StringBuilder sb = new StringBuilder("{\n    \"dim\": ").append(dimensionId())
            .append(",\n    \"gridChunks\": ")
            .append(GRID_CHUNKS)
            .append(",\n    \"regions\": {\n");
        boolean first = true;
        int withFeature = 0;
        // Sorted by (gx, gz) so the section is independent of any iteration order.
        for (int gx = gxMin; gx <= gxMax; gx++) {
            for (int gz = gzMin; gz <= gzMax; gz++) {
                final int rcx = gx * GRID_CHUNKS, rcz = gz * GRID_CHUNKS;
                final Object feature = featureForRegion(w, rcx, rcz);
                final ChunkCoordinates center = nearestCenterXYZ(w, rcx, rcz);
                final BiomeGenBase biome = w.getBiomeGenForCoords((rcx << 4) + 8, (rcz << 4) + 8);
                final String name = name(feature);
                if (!"nothing".equals(name)) withFeature++;
                if (!first) sb.append(",\n");
                first = false;
                sb.append("      \"")
                    .append(gx)
                    .append(",")
                    .append(gz)
                    .append("\": {\"feature\": \"")
                    .append(WorldgenProbe.jsonEscape(name))
                    .append("\", \"id\": ")
                    .append(featureId(feature))
                    .append(", \"size\": ")
                    .append(size(feature))
                    .append(", \"center\": [")
                    .append(center.posX)
                    .append(", ")
                    .append(center.posZ)
                    .append("], \"centerChunk\": [")
                    .append(center.posX >> 4)
                    .append(", ")
                    .append(center.posZ >> 4)
                    .append("], \"biome\": \"")
                    .append(WorldgenProbe.jsonEscape(biome.biomeName))
                    .append("\", \"biomeId\": ")
                    .append(biome.biomeID)
                    .append("}");
            }
        }
        sb.append("\n    }\n  }");
        final int total = (gxMax - gxMin + 1) * (gzMax - gzMin + 1);
        WorldgenProbe.LOG.info("[probe] TF feature map: {} regions, {} with a feature", total, withFeature);
        return sb.toString();
    }

    /**
     * The feature whose footprint covers this chunk, as a JSON fragment to splice into the chunk's search-report
     * object, or "" when the chunk is not inside one.
     *
     * <p>
     * One {@code getNearestCenterXYZ} call rather than the mod's expanding 1..3 ring scan. The two agree: the
     * jitter keeps a centre within ±3 chunks of its grid node while nodes are {@value #GRID_CHUNKS} chunks
     * apart, so no neighbouring region's centre can be within {@code size} of this chunk and the nearest centre
     * is always the right one.
     */
    static String chunkFeatureJson(WorldServer w, int chunkX, int chunkZ) {
        resolve();
        if (!Boolean.TRUE.equals(present) || w == null) return "";
        if (w.provider.dimensionId != dimensionId()) return "";
        final Object feature = featureForRegion(w, chunkX, chunkZ);
        final int size = size(feature);
        final String name = name(feature);
        if (size <= 0 || "nothing".equals(name)) return "";
        final ChunkCoordinates c = nearestCenterXYZ(w, chunkX, chunkZ);
        final int ccx = c.posX >> 4, ccz = c.posZ >> 4;
        if (Math.max(Math.abs(chunkX - ccx), Math.abs(chunkZ - ccz)) > size) return "";
        return ", \"tffeature\": \"" + WorldgenProbe.jsonEscape(name)
            + "\", \"tffeatureId\": "
            + featureId(feature)
            + ", \"tffeatureCenter\": ["
            + c.posX
            + ", "
            + c.posZ
            + "]";
    }

    /** Region index for a chunk coordinate, matching TF's Math.round(chunk/16f). */
    private static int region(int chunkCoord) {
        return Math.round(chunkCoord / (float) GRID_CHUNKS);
    }

    private static Object featureForRegion(World w, int chunkX, int chunkZ) {
        try {
            return getFeatureForRegion.invoke(null, chunkX, chunkZ, w);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TFFeature.getFeatureForRegion failed", e);
        }
    }

    private static ChunkCoordinates nearestCenterXYZ(World w, int chunkX, int chunkZ) {
        try {
            return (ChunkCoordinates) getNearestCenterXYZ.invoke(null, chunkX, chunkZ, w);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TFFeature.getNearestCenterXYZ failed", e);
        }
    }

    private static String name(Object feature) {
        try {
            return (String) fName.get(feature);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TFFeature.name unreadable", e);
        }
    }

    private static int featureId(Object feature) {
        try {
            return fFeatureId.getInt(feature);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TFFeature.featureID unreadable", e);
        }
    }

    private static int size(Object feature) {
        try {
            return fSize.getInt(feature);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TFFeature.size unreadable", e);
        }
    }

    /**
     * The 21 static {@code TFTreasure} tables, as {@code table -> category -> list of TFTreasureItem}.
     *
     * <p>
     * Sorted by field name so the CSV diffs cleanly, and enumerated by type rather than by name so a TF version
     * that adds a table is picked up without a code change. Returns null when the mod is absent.
     */
    static Map<String, Map<String, Object>> treasureTables() {
        resolve();
        if (!Boolean.TRUE.equals(present)) return null;
        final Class<?> treasure;
        try {
            treasure = Class.forName("twilightforest.TFTreasure", true, TwilightForestProbe.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Twilight Forest is loaded but TFTreasure is missing", e);
        }
        final Map<String, Map<String, Object>> out = new TreeMap<>();
        try {
            for (Field f : treasure.getDeclaredFields()) {
                if (f.getType() != treasure || !java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                final Object table = f.get(null);
                if (table == null) continue;
                final Map<String, Object> byCategory = new TreeMap<>();
                for (String cat : TREASURE_CATEGORIES) {
                    final Field cf = treasure.getField(cat);
                    cf.setAccessible(true);
                    final Object pool = cf.get(table);
                    if (pool != null) byCategory.put(cat, pool);
                }
                out.put(f.getName(), byCategory);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TFTreasure table layout does not match", e);
        }
        return out;
    }

    /**
     * The five pools inside a {@code TFTreasure}, in the order {@code TFTreasure.generate} consults them.
     *
     * <p>
     * Not enumerated reflectively: their names carry the roll counts and the selection probabilities that
     * {@link ChestLootExport} has to attach, so an unrecognised sixth pool must be a loud failure rather than a
     * row with invented odds.
     */
    static final String[] TREASURE_CATEGORIES = { "useless", "common", "uncommon", "rare", "ultrarare" };

    /** The raw {@code ItemStack} templates in a {@code TFTreasureTable}, in insertion order. */
    static java.util.List<?> treasureItems(Object treasureTable) {
        try {
            final Field list = treasureTable.getClass()
                .getDeclaredField("list");
            list.setAccessible(true);
            return (java.util.List<?>) list.get(treasureTable);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TFTreasureTable.list unreadable", e);
        }
    }

    /**
     * Reads one {@code TFTreasureItem} as {@code {stack, rarity, randomEnchantmentLevel}}.
     *
     * <p>
     * The raw {@code itemStack} field, not {@code getItemStack(Random)}: that method copies the stack, rolls
     * {@code nextInt(stackSize)+1} into the copy's size and can enchant it, so it answers "one draw" where a
     * static table export needs "the template".
     */
    static Object[] treasureItem(Object item) {
        try {
            final Class<?> c = item.getClass();
            final Field stack = c.getDeclaredField("itemStack");
            final Field rarity = c.getDeclaredField("rarity");
            final Field ench = c.getDeclaredField("randomEnchantmentLevel");
            stack.setAccessible(true);
            rarity.setAccessible(true);
            ench.setAccessible(true);
            return new Object[] { stack.get(item), rarity.getInt(item), ench.getInt(item) };
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TFTreasureItem layout does not match", e);
        }
    }
}
