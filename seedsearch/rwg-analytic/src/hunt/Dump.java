package hunt;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rwg.terrain.TerrainBase;

/**
 * Loads the game-side RWG wiring dump (rwg-dump.json, written by the probe's
 * -Dprobe.prefilter.meteor.dumprwg) and reconstructs the five biome band lists as terrain instances:
 * ported terrain class by simple name, then every dumped "DeclaringClass.field" value injected
 * reflectively. Instantiation is via the added no-arg ctors — generateNoise only reads fields, so
 * injection reproduces the game instance exactly. Refuses unknown terrain classes rather than skipping:
 * a silently shorter band list would shift every biome index after it (the band pick is index
 * arithmetic).
 */
public final class Dump {

    public final boolean wetEnabled, smallEnabled;
    public final List<TerrainBase> snow, cold, hot, wet, small;
    public final java.util.List<String> snowNames = new ArrayList<>(), coldNames = new ArrayList<>(),
        hotNames = new ArrayList<>(), wetNames = new ArrayList<>(), smallNames = new ArrayList<>();
    public final String noisePerlin;

    private static final Pattern ENTRY = Pattern
        .compile("\\{\"biome\": \"(.*?)\", \"base\": \"(.*?)\", \"terrain\": \"(.*?)\", \"fields\": \\{(.*?)\\}\\}");
    private static final Pattern FIELD = Pattern
        .compile("\"(\\w+)\\.(\\w+)\": (\\[[^\\]]*\\]|[-0-9.Etruefalse]+|null)");

    public Dump(String path) throws Exception {
        final String json = new String(Files.readAllBytes(Paths.get(path)));
        noisePerlin = extract(json, "noise_perlin");
        wetEnabled = json.contains("\"wetEnabled\": true");
        smallEnabled = json.contains("\"smallEnabled\": true");
        snow = band(json, "biomes_snow", snowNames);
        cold = band(json, "biomes_cold", coldNames);
        hot = band(json, "biomes_hot", hotNames);
        wet = band(json, "biomes_wet", wetNames);
        small = band(json, "biomes_small", smallNames);
    }

    private static String extract(String json, String key) {
        final Matcher m = Pattern.compile("\"" + key + "\": \"(.*?)\"")
            .matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static List<TerrainBase> band(String json, String name, List<String> namesOut) throws Exception {
        final int start = json.indexOf("\"" + name + "\": [");
        // band ends at the "}}]" close sequence — a bare indexOf("]") lands inside the first
        // array-valued terrain field (TerrainCanyon.height) and silently truncates the band,
        // shifting every biome index after it
        final int end = json.indexOf("}}]", start) + 2;
        final List<TerrainBase> out = new ArrayList<>();
        final Matcher m = ENTRY.matcher(json.substring(start, end + 1));
        while (m.find()) {
            namesOut.add(m.group(1) + "/" + m.group(2)); // class alone collides: RealisticBiomeSupport hosts ~40 distinct BoP-wired biomes
            final String terrainCls = m.group(3);
            final Class<?> cls = Class.forName("rwg.terrain." + terrainCls);
            final TerrainBase t = (TerrainBase) cls.getDeclaredConstructor()
                .newInstance();
            final Matcher fm = FIELD.matcher(m.group(4));
            while (fm.find()) {
                final Field f = fieldIn(cls, fm.group(1), fm.group(2));
                if (f == null) continue; // dumped superclass field the port lacks — tolerated, logged by golden
                f.setAccessible(true);
                final String v = fm.group(3);
                if ("null".equals(v)) continue;
                if (f.getType() == float.class) f.setFloat(t, Float.parseFloat(v));
                else if (f.getType() == int.class) f.setInt(t, (int) Float.parseFloat(v));
                else if (f.getType() == double.class) f.setDouble(t, Double.parseDouble(v));
                else if (f.getType() == boolean.class) f.setBoolean(t, Boolean.parseBoolean(v));
                else if (f.getType() == float[].class || f.getType() == int[].class) {
                    final String body = v.substring(1, v.length() - 1)
                        .trim();
                    final String[] parts = body.isEmpty() ? new String[0] : body.split(",\\s*");
                    if (f.getType() == float[].class) {
                        final float[] a = new float[parts.length];
                        for (int k = 0; k < parts.length; k++) a[k] = Float.parseFloat(parts[k]);
                        f.set(t, a);
                    } else {
                        final int[] a = new int[parts.length];
                        for (int k = 0; k < parts.length; k++) a[k] = Integer.parseInt(parts[k]);
                        f.set(t, a);
                    }
                }
            }
            out.add(t);
        }
        if (out.isEmpty()) throw new IllegalStateException("empty band " + name);
        return out;
    }

    private static Field fieldIn(Class<?> cls, String declaringSimple, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            if (!c.getSimpleName()
                .equals(declaringSimple)) continue;
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                return null;
            }
        }
        return null;
    }
}
