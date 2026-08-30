package com.gtnhspeedrun.worldgenprobe;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;
import net.minecraftforge.common.BiomeDictionary;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/**
 * One-shot sidecar describing every registered biome: climate fields, BiomeDictionary types, the crop humidity
 * bonus, and which biome IDs RWG can actually reach. Seed-independent, so it is written once per report
 * directory as {@code biomes.json}, following the {@code gtmats.json} precedent.
 *
 * <p>
 * This exists because the two properties a no-rain/high-humidity search needs are both invisible from outside
 * the running JVM:
 *
 * <ul>
 * <li><b>Rain.</b> {@code BiomeGenBase.enableRain} is {@code protected}, and the public
 * {@code canSpawnLightningBolt()} is not a substitute: it returns false for every snowy biome, so it conflates
 * "does not rain" with "snows instead". Only reflection separates them.</li>
 * <li><b>Humidity.</b> The mechanic is a different mod on each pack line, and neither is derivable from the
 * biome alone. See {@link Humidity}.</li>
 * </ul>
 *
 * <p>
 * The bonus is never recomputed here — the owning mod's own method is called, so a mod or config change shows up
 * in the sidecar instead of silently changing what a sweep means.
 *
 * <p>
 * {@code rwgBuckets} and {@code rwgRivers} are read off the live chunk manager because source reading gives the
 * wrong answer. {@code rwg.support.Support.addBiome} is a {@code switch} with no {@code break} statements, so a
 * biome declared in one climate category falls through into every later one, and the categories a mod declares
 * are not the categories RWG ends up searching.
 */
final class BiomeTable {

    /** Chunk-manager bucket fields, in the order {@code getBiomeDataAt} selects between them. */
    private static final String[] BUCKETS = { "snow", "cold", "hot", "wet", "small" };

    private static boolean dumped;

    /** Per-biome-id classification, resolved once per JVM. Indexed by biome id; ids are bytes in chunk data. */
    private static boolean[] noRainById;
    private static int[] humById;

    private BiomeTable() {}

    /**
     * Build the id-indexed classification the region search runs on.
     *
     * <p>
     * Both defaults are the conservative direction. An id whose rain flag cannot be read stays
     * {@code false} (treated as raining, so it cannot extend a no-rain square) and an id with no humidity
     * answer stays {@code -1} (never counts as a humid neighbour). Getting either wrong the other way would
     * manufacture a qualifying seed that the full generator does not agree with.
     */
    private static synchronized void ensureClassification() {
        if (noRainById != null) return;
        final boolean[] noRain = new boolean[256];
        final int[] hum = new int[256];
        java.util.Arrays.fill(hum, -1);
        final Humidity h = Humidity.resolve();
        Field rainField = null;
        try {
            rainField = Prefilter.findField(BiomeGenBase.class, "enableRain", "field_76765_S");
        } catch (Exception e) {
            WorldgenProbe.LOG.warn("[biometable] enableRain unreadable, no biome will count as no-rain: {}", e);
        }
        for (final BiomeGenBase b : BiomeGenBase.getBiomeGenArray()) {
            if (b == null || b.biomeID < 0 || b.biomeID > 255) continue;
            if (rainField != null) {
                try {
                    noRain[b.biomeID] = !rainField.getBoolean(b);
                } catch (Exception ignored) {}
            }
            final Integer v = h.of(b);
            if (v != null) hum[b.biomeID] = v;
        }
        humById = hum;
        noRainById = noRain;
    }

    /** True when this biome has enableRain == false. Unknown ids answer false. */
    static boolean noRain(int biomeId) {
        ensureClassification();
        return biomeId >= 0 && biomeId < 256 && noRainById[biomeId];
    }

    /** Crop humidity bonus for this biome, or -1 when no humidity mechanic could be resolved. */
    static int humidity(int biomeId) {
        ensureClassification();
        return biomeId >= 0 && biomeId < 256 ? humById[biomeId] : -1;
    }

    /**
     * Write {@code biomes.json} into {@code dir}, once per process. Never throws: the sidecar is diagnostic, and
     * failing to write it must not take down a sweep that is otherwise producing valid rows.
     *
     * @param cm the live chunk manager, used only for the reachability section; may be null
     */
    static synchronized void dumpOnce(File dir, WorldChunkManager cm) {
        if (dumped || dir == null) return;
        dumped = true;
        try {
            final File out = new File(dir, "biomes.json");
            final Humidity hum = Humidity.resolve();
            final StringBuilder sb = new StringBuilder(65536);
            sb.append("{\"format\": 1");
            sb.append(", \"mods\": ")
                .append(modVersions("cropsnh", "berriespp", "IC2", "BiomesOPlenty", "RWG"));
            sb.append(", \"humiditySource\": \"")
                .append(WorldgenProbe.jsonEscape(hum.describe()))
                .append('"');
            sb.append(", \"humidityConstants\": ")
                .append(hum.constantsJson());
            sb.append(", \"rwgBuckets\": ")
                .append(bucketsJson(cm));
            sb.append(", \"rwgRivers\": ")
                .append(riversJson(cm));
            sb.append(", \"biomes\": [");
            int n = 0;
            for (final BiomeGenBase b : BiomeGenBase.getBiomeGenArray()) {
                if (b == null) continue;
                if (n++ > 0) sb.append(", ");
                appendBiome(sb, b, hum);
            }
            sb.append("]}");
            try (FileWriter w = new FileWriter(out)) {
                w.write(sb.toString());
            }
            WorldgenProbe.LOG.info("[biometable] {} biomes -> {} (humidity: {})", n, out, hum.describe());
        } catch (Throwable t) {
            WorldgenProbe.LOG.warn("[biometable] could not write biomes.json: {}", t.toString());
        }
    }

    private static void appendBiome(StringBuilder sb, BiomeGenBase b, Humidity hum) {
        sb.append("{\"id\": ")
            .append(b.biomeID)
            .append(", \"name\": \"")
            .append(WorldgenProbe.jsonEscape(b.biomeName == null ? "" : b.biomeName))
            .append("\", \"temperature\": ")
            .append(b.temperature)
            .append(", \"rainfall\": ")
            .append(b.rainfall);

        // enableRain is protected and canSpawnLightningBolt() folds enableSnow into it, so the flag has to come
        // off the field. A biome whose flag cannot be read is reported as null, never guessed.
        Boolean rainEnabled = null;
        try {
            rainEnabled = Prefilter.findField(BiomeGenBase.class, "enableRain", "field_76765_S")
                .getBoolean(b);
        } catch (Exception ignored) {}
        sb.append(", \"rainEnabled\": ")
            .append(rainEnabled == null ? "null" : rainEnabled.toString());
        sb.append(", \"snow\": ")
            .append(b.getEnableSnow());

        sb.append(", \"types\": [");
        try {
            final BiomeDictionary.Type[] types = BiomeDictionary.getTypesForBiome(b);
            for (int i = 0; i < types.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append('"')
                    .append(WorldgenProbe.jsonEscape(types[i].name()))
                    .append('"');
            }
        } catch (Throwable ignored) {}
        sb.append(']');

        final Integer h = hum.of(b);
        sb.append(", \"hum\": ")
            .append(h == null ? "null" : h.toString());
        sb.append('}');
    }

    // ---------------------------------------------------------------- humidity

    /**
     * The crop humidity bonus, dispatched to whichever mod owns it on this pack.
     *
     * <p>
     * <b>GTNH daily / 2.9</b> ships CropsNH, whose bonus is a continuous ramp on {@code biome.rainfall}:
     * {@code (int) (clamp(0,1, (rainfall - 0.5f) / 0.3f) * 14f)}, saturating at rainfall 0.8. It is read here by
     * calling {@code getNutrientsPerCycle(0, rainfall, false, 0, 0)} and subtracting {@code BASE_NUTRIENT_VALUE}:
     * with zero liked-biome tags, no sky access and empty water and fertilizer storage, every other term of that
     * sum is zero, so the difference is the humidity term alone.
     *
     * <p>
     * <b>GTNH 2.8.4</b> ships CropsPP, which fills IC2's biome humidity table with BiomeDictionary type weights;
     * the bonus is then the maximum over the biome's types. Stock IC2 does not fill that table at all — it calls
     * {@code addBiomehumidityBonus} zero times — so on a pack without CropsPP the IC2 path correctly reports 0
     * for every biome. CropsNH is therefore tried first, and the winner is recorded in {@code humiditySource} so
     * a sidecar from one pack line is never mistaken for the other.
     */
    private static final class Humidity {

        private static final String CROPSNH = "com.gtnewhorizon.cropsnh.tileentity.TileEntityCropSticks";

        String source = "none";
        String reason = "";
        Method cropsNh;
        int base;
        Object ic2Crops;
        Method ic2Bonus;
        final Map<String, String> constants = new LinkedHashMap<>();

        static Humidity resolve() {
            final Humidity h = new Humidity();
            try {
                final Class<?> sticks = Class.forName(CROPSNH);
                h.cropsNh = sticks
                    .getMethod("getNutrientsPerCycle", int.class, float.class, boolean.class, int.class, int.class);
                h.cropsNh.setAccessible(true);
                h.base = sticks.getField("BASE_NUTRIENT_VALUE")
                    .getInt(null);
                for (final String name : new String[] { "LOW_HUMIDITY_THRESHOLD", "HIGH_HUMIDITY_THRESHOLD",
                    "HIGH_HUMIDITY_BONUS", "BASE_NUTRIENT_VALUE", "LIKED_BIOME_BONUS", "MAX_LIKED_BIOME_TAG_COUNT" }) {
                    try {
                        h.constants.put(
                            name,
                            String.valueOf(
                                sticks.getField(name)
                                    .get(null)));
                    } catch (Exception ignored) {}
                }
                h.source = "cropsnh";
                return h;
            } catch (Throwable notCropsNh) {
                h.reason = notCropsNh.toString();
            }
            try {
                final Class<?> crops = Class.forName("ic2.api.crops.Crops");
                h.ic2Crops = crops.getField("instance")
                    .get(null);
                h.ic2Bonus = crops.getMethod("getHumidityBiomeBonus", BiomeGenBase.class);
                h.ic2Bonus.setAccessible(true);
                if (h.ic2Crops != null) {
                    h.source = "ic2";
                    h.reason = "";
                    return h;
                }
                h.reason = "ic2.api.crops.Crops.instance is null";
            } catch (Throwable notIc2) {
                h.reason = h.reason + "; " + notIc2;
            }
            return h;
        }

        Integer of(BiomeGenBase b) {
            try {
                if ("cropsnh".equals(source)) {
                    final int total = (Integer) cropsNh.invoke(null, 0, b.rainfall, false, 0, 0);
                    return total - base;
                }
                if ("ic2".equals(source)) return (Integer) ic2Bonus.invoke(ic2Crops, b);
            } catch (Throwable ignored) {}
            return null;
        }

        String describe() {
            return "none".equals(source) ? "none: " + reason : source;
        }

        String constantsJson() {
            if (constants.isEmpty()) return "{}";
            final StringBuilder sb = new StringBuilder("{");
            int i = 0;
            for (final Map.Entry<String, String> e : constants.entrySet()) {
                if (i++ > 0) sb.append(", ");
                sb.append('"')
                    .append(WorldgenProbe.jsonEscape(e.getKey()))
                    .append("\": \"")
                    .append(WorldgenProbe.jsonEscape(e.getValue()))
                    .append('"');
            }
            return sb.append('}')
                .toString();
        }
    }

    // ------------------------------------------------------------ reachability

    /** Biome IDs in each of RWG's climate buckets, read off the live chunk manager. */
    private static String bucketsJson(WorldChunkManager cm) {
        if (cm == null) return "null";
        final StringBuilder sb = new StringBuilder("{");
        int written = 0;
        for (final String bucket : BUCKETS) {
            final List<?> entries = bucketEntries(cm, bucket);
            if (entries == null) continue;
            final TreeSet<Integer> ids = new TreeSet<>();
            for (final Object entry : entries) {
                final BiomeGenBase base = biomeField(entry, "baseBiome");
                if (base != null) ids.add(base.biomeID);
            }
            if (written++ > 0) sb.append(", ");
            sb.append('"')
                .append(bucket)
                .append("\": ")
                .append(ids);
        }
        return sb.append('}')
            .toString();
    }

    /**
     * Base biome ID to the river biome IDs painted over it.
     *
     * <p>
     * This is the single most load-bearing row for a no-rain region search. {@code replaceBlocksForBiome} writes
     * {@code riverBiome} over columns where river strength is high, and the desert family's river is River Oasis,
     * which has rain <em>enabled</em>. One river column therefore breaks a no-rain chunk, and the same column is
     * a high-humidity neighbour. A base biome reachable through more than one realistic biome can carry more than
     * one river, so this is a set, not a scalar.
     */
    private static String riversJson(WorldChunkManager cm) {
        if (cm == null) return "null";
        final Map<Integer, TreeSet<Integer>> rivers = new TreeMap<>();
        for (final String bucket : BUCKETS) {
            final List<?> entries = bucketEntries(cm, bucket);
            if (entries == null) continue;
            for (final Object entry : entries) {
                final BiomeGenBase base = biomeField(entry, "baseBiome");
                final BiomeGenBase river = biomeField(entry, "riverBiome");
                if (base == null || river == null) continue;
                rivers.computeIfAbsent(base.biomeID, k -> new TreeSet<>())
                    .add(river.biomeID);
            }
        }
        final StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (final Map.Entry<Integer, TreeSet<Integer>> e : rivers.entrySet()) {
            if (i++ > 0) sb.append(", ");
            sb.append('"')
                .append(e.getKey())
                .append("\": ")
                .append(e.getValue());
        }
        return sb.append('}')
            .toString();
    }

    private static List<?> bucketEntries(WorldChunkManager cm, String bucket) {
        try {
            final Field f = Prefilter.findField(cm.getClass(), "biomes_" + bucket);
            final Object v = f.get(cm);
            return v instanceof List ? (List<?>) v : null;
        } catch (Throwable notRwg) {
            return null;
        }
    }

    private static BiomeGenBase biomeField(Object realisticBiome, String name) {
        if (realisticBiome == null) return null;
        try {
            final Object v = Prefilter.findField(realisticBiome.getClass(), name)
                .get(realisticBiome);
            return v instanceof BiomeGenBase ? (BiomeGenBase) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------- misc

    /** Versions of the mods that decide what this table means, so two sidecars can be told apart. */
    private static String modVersions(String... modIds) {
        final StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (final String id : modIds) {
            String version = null;
            try {
                if (Loader.isModLoaded(id)) {
                    final ModContainer mc = Loader.instance()
                        .getIndexedModList()
                        .get(id);
                    if (mc != null) version = mc.getVersion();
                }
            } catch (Throwable ignored) {}
            if (i++ > 0) sb.append(", ");
            sb.append('"')
                .append(WorldgenProbe.jsonEscape(id))
                .append("\": ")
                .append(version == null ? "null" : "\"" + WorldgenProbe.jsonEscape(version) + "\"");
        }
        return sb.append('}')
            .toString();
    }
}
