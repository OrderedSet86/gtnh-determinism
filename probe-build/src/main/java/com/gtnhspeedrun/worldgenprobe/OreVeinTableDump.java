package com.gtnhspeedrun.worldgenprobe;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Random;

/**
 * Dumps GregTech's LIVE ore-vein registry, and a set of picks made by GregTech's OWN weighted query,
 * so a worldless predictor can be validated against the real thing instead of against a bytecode read.
 *
 * <p>
 * Motivated by a measured failure. GT 5.09.54 rewrote vein selection (per-attempt FNV-1a-64 reseeding,
 * dimension-filtered weighted draw), and a Python replication built from disassembly scored 0/64
 * against a real Twilight Forest world even after its FNV and XSTR arithmetic were verified bit-exact
 * in isolation. The remaining unknowns are exactly what this dump captures:
 *
 * <ul>
 * <li><b>the list</b> — {@code WorldgenGTOreLayer.sList} content, ORDER, per-layer weight and
 * per-dimension eligibility, as configured in this pack at runtime, not as parsed from the enum;</li>
 * <li><b>the draw</b> — for a grid of (worldSeed, cell) inputs, the name the real
 * {@code WorldgenQuery.veins().inDimension(dim).findRandom(rng)} returns per attempt.</li>
 * </ul>
 *
 * A predictor that matches the picks section end-to-end is right for the only reason that counts:
 * the mod itself said so. Everything here is reflection; the probe has no compile dependency on GT.
 */
final class OreVeinTableDump {

    private static boolean dumped;

    private OreVeinTableDump() {}

    /** The dims a layer's eligibility is reported for. Order is part of the file format. */
    private static final String[] DIMS = { "Overworld", "Nether", "TheEnd", "Twilight Forest" };

    /**
     * Raises the GregTech loggers to DEBUG so {@code GTValues.debugOrevein} output actually reaches the
     * log. The config flag alone is not enough: {@code GT_FML_LOGGER.debug(...)} is filtered by log4j's
     * default INFO level, which is why a debugOrevein=true run produced zero lines — a silent no-op that
     * looks exactly like "no veins generated".
     */
    static void enableGtDebugLogging() {
        // 1.7.10 ships log4j 2.0-beta9, whose Configurator has no setLevel(String, Level) — the modern
        // one-liner throws NoSuchMethodException (measured). beta9 needs the long way round: the core
        // LoggerContext's Configuration, a fresh LoggerConfig per logger name, then updateLoggers().
        try {
            final Class<?> lvl = Class.forName("org.apache.logging.log4j.Level");
            final Object debug = lvl.getField("DEBUG")
                .get(null);
            final Class<?> lm = Class.forName("org.apache.logging.log4j.LogManager");
            final Object ctx = lm.getMethod("getContext", boolean.class)
                .invoke(null, false);
            final Object config = ctx.getClass()
                .getMethod("getConfiguration")
                .invoke(ctx);
            final Class<?> lcCls = Class.forName("org.apache.logging.log4j.core.config.LoggerConfig");
            for (final String name : new String[] { "GregTech", "gregtech", "GT_FML" }) {
                final Object lc = lcCls.getConstructor(String.class, lvl, boolean.class)
                    .newInstance(name, debug, true);
                config.getClass()
                    .getMethod("addLogger", String.class, lcCls)
                    .invoke(config, name, lc);
            }
            ctx.getClass()
                .getMethod("updateLoggers")
                .invoke(ctx);
            WorldgenProbe.LOG.info("[oreveindump] GregTech loggers raised to DEBUG (beta9 path)");
        } catch (Throwable t) {
            WorldgenProbe.LOG.warn("[oreveindump] could not raise GT log level: {}", t.toString());
        }
    }

    static synchronized void dumpOnce(File dir, long worldSeed) {
        if (dumped || dir == null) return;
        dumped = true;
        if (Boolean.getBoolean("probe.gtdebug")) enableGtDebugLogging();
        try {
            final Class<?> layerCls = Class.forName("gregtech.common.WorldgenGTOreLayer");
            final List<?> sList = (List<?>) layerCls.getField("sList")
                .get(null);
            final Method getWeight = findAny(layerCls, "getWeight");
            final Method canGen = findAny(layerCls, "canGenerateIn", String.class);
            final Method getMinY = findAny(layerCls, "getMinY", String.class);
            final Method getMaxY = findAny(layerCls, "getMaxY", String.class);

            final StringBuilder sb = new StringBuilder(1 << 16);
            sb.append("{\n \"layers\": [");
            boolean first = true;
            for (final Object layer : sList) {
                final String name = String.valueOf(
                    layerCls.getField("mWorldGenName")
                        .get(layer));
                final Object primary = layerCls.getField("mPrimary")
                    .get(layer);
                int primaryId = -1;
                if (primary != null) {
                    primaryId = (Integer) primary.getClass()
                        .getMethod("getId")
                        .invoke(primary);
                }
                if (!first) sb.append(",");
                first = false;
                sb.append("\n  {\"name\": \"")
                    .append(name)
                    .append("\", \"weight\": ")
                    .append(getWeight.invoke(layer))
                    .append(", \"primaryId\": ")
                    .append(primaryId);
                for (final String d : DIMS) {
                    sb.append(", \"")
                        .append("Twilight Forest".equals(d) ? "tf" : d.toLowerCase())
                        .append("\": ")
                        .append((Boolean) canGen.invoke(layer, d) ? 1 : 0);
                }
                sb.append(", \"minY\": ")
                    .append(getMinY.invoke(layer, "Twilight Forest"))
                    .append(", \"maxY\": ")
                    .append(getMaxY.invoke(layer, "Twilight Forest"))
                    .append("}");
            }
            sb.append("\n ],\n \"picks\": [");

            // GT's own query, driven exactly as WorldGenContainer.generateVein drives it: per attempt,
            // seed = FNV1a64(basis, oreveinSeed) folded with the attempt index, then findRandom on the
            // dimension-filtered query. The XSTR and Fnv1a64 used here are the mod's own classes.
            final Class<?> queryCls = Class.forName("gregtech.common.worldgen.WorldgenQuery");
            final Method veins = queryCls.getMethod("veins");
            final Method inDimension = queryCls.getMethod("inDimension", String.class);
            final Method findRandom = queryCls.getMethod("findRandom", Random.class);
            final Class<?> fnv = Class.forName("com.gtnewhorizon.gtnhlib.hash.Fnv1a64");
            final Method fnvInit = fnv.getMethod("initialState");
            final Method fnvLong = fnv.getMethod("hashStep", long.class, long.class);
            final Method fnvInt = fnv.getMethod("hashStep", long.class, int.class);
            final Class<?> xstrCls = Class.forName("gregtech.api.objects.XSTR");
            final var xstrCtor = xstrCls.getConstructor(long.class);

            first = true;
            for (final String dimName : new String[] { "Overworld", "Twilight Forest" }) {
                final int dimId = "Overworld".equals(dimName) ? 0 : 7;
                for (int cell = 0; cell < 12; cell++) {
                    final int osX = -17 + cell * 3, osZ = 1 + cell * 3; // both lattices, both signs
                    final long oreveinSeed = (worldSeed << 16)
                        ^ (((long) dimId & 255L) << 56 | ((long) osX & 0xFFFFFFFL) << 28 | ((long) osZ & 0xFFFFFFFL));
                    for (int attempt = 0; attempt < 4; attempt++) {
                        long h = (Long) fnvInit.invoke(null);
                        h = (Long) fnvLong.invoke(null, h, oreveinSeed);
                        h = (Long) fnvInt.invoke(null, h, attempt);
                        final Object rng = xstrCtor.newInstance(h);
                        final Object query = inDimension.invoke(veins.invoke(null), dimName);
                        final Object win = findRandom.invoke(query, rng);
                        final String name = win == null ? null
                            : String.valueOf(
                                layerCls.getField("mWorldGenName")
                                    .get(win));
                        if (!first) sb.append(",");
                        first = false;
                        sb.append("\n  {\"dim\": ")
                            .append(dimId)
                            .append(", \"os\": [")
                            .append(osX)
                            .append(", ")
                            .append(osZ)
                            .append("], \"a\": ")
                            .append(attempt)
                            .append(", \"seed\": ")
                            .append(oreveinSeed)
                            .append(", \"h\": ")
                            .append(h)
                            .append(", \"pick\": ")
                            .append(name == null ? "null" : "\"" + name + "\"")
                            .append("}");
                    }
                }
            }
            sb.append("\n ],\n \"worldSeed\": ")
                .append(worldSeed)
                .append("\n}\n");
            final File out = new File(dir, "oreveins-runtime.json");
            try (FileWriter w = new FileWriter(out)) {
                w.write(sb.toString());
            }
            WorldgenProbe.LOG.info("[oreveindump] {} layers + {} picks -> {}", sList.size(), 96, out);
        } catch (Throwable t) {
            // Diagnostic sidecar: failing to write it must not take down a sweep producing valid rows.
            WorldgenProbe.LOG.warn("[oreveindump] failed: {}", t.toString());
        }
    }

    /**
     * Dumps {@code GTWorldgenerator.validOreveins} — the live selection cache, one entry per vein region
     * the REAL path decided this session — as (oreveinSeed, layerName, veinMinY). This is the ground
     * truth log4j kept refusing to print: reading the cache needs no logger, no debug flag and no
     * bytecode interpretation, and an EMPTY map after a generation walk is itself the answer that
     * {@code generateVein} never ran.
     */
    static void dumpVeinCache(File out) {
        try {
            final Class<?> gtw = Class.forName("gregtech.common.GTWorldgenerator");
            final java.util.Map<?, ?> cache = (java.util.Map<?, ?>) gtw.getField("validOreveins")
                .get(null);
            final StringBuilder sb = new StringBuilder(1 << 14);
            sb.append("[");
            boolean first = true;
            for (final java.util.Map.Entry<?, ?> e : cache.entrySet()) {
                final Object cached = e.getValue();
                String layer = null;
                Integer minY = null;
                boolean isNull = cached == null;
                if (!isNull) {
                    // Two shapes seen in the wild, so probe rather than assume: the map value is either the
                    // layer itself (has mWorldGenName — measured on this pack) or a CachedOreVein record
                    // wrapping it (bytecode of generateVein checkcasts one). Assuming the record shape
                    // produced NoSuchMethodException: WorldgenGTOreLayer.layer() on the live server.
                    Object lv = cached;
                    try {
                        cached.getClass()
                            .getField("mWorldGenName");
                    } catch (NoSuchFieldException notALayer) {
                        lv = cached.getClass()
                            .getMethod("layer")
                            .invoke(cached);
                        final Object pv = cached.getClass()
                            .getMethod("placement")
                            .invoke(cached);
                        if (pv != null) {
                            minY = (Integer) pv.getClass()
                                .getMethod("veinMinY")
                                .invoke(pv);
                        }
                    }
                    if (lv != null) {
                        layer = String.valueOf(
                            lv.getClass()
                                .getField("mWorldGenName")
                                .get(lv));
                    }
                }
                if (!first) sb.append(",");
                first = false;
                sb.append("\n {\"seed\": ")
                    .append(e.getKey())
                    .append(", \"null\": ")
                    .append(isNull)
                    .append(", \"layer\": ")
                    .append(layer == null ? "null" : "\"" + layer + "\"")
                    .append(", \"minY\": ")
                    .append(minY)
                    .append("}");
            }
            sb.append("\n]\n");
            try (FileWriter w = new FileWriter(out)) {
                w.write(sb.toString());
            }
            WorldgenProbe.LOG.info("[oreveindump] vein cache: {} entries -> {}", cache.size(), out);
        } catch (Throwable t) {
            WorldgenProbe.LOG.warn("[oreveindump] vein cache dump failed: {}", t.toString());
        }
    }

    private static Method findAny(Class<?> cls, String name, Class<?>... params) throws NoSuchMethodException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                final Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        // Interface default/inherited methods land here; getMethod covers public interface methods.
        return cls.getMethod(name, params);
    }
}
