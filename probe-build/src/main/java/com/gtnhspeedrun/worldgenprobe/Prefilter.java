package com.gtnhspeedrun.worldgenprobe;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.entity.Entity;
import net.minecraft.profiler.Profiler;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.structure.MapGenVillage;
import net.minecraft.world.storage.ISaveHandler;

/**
 * Stage-0 seed prefilter (seed-search-speed-plan.md §3.1, harness-speed.md §C): evaluate thousands of seeds per
 * second WITHOUT creating a WorldServer or generating chunks, by calling the real mod classes against a
 * throwaway seed-bearing World. RWG's ChunkManagerRealistic uses the world for exactly one thing — getSeed() —
 * so biome noise, ocean/river values, and vanilla-formula structure cells are all evaluable from arithmetic plus
 * real noise calls. No reimplementation drift: every value comes from the same code the full generator runs, and
 * every predicate is golden-testable against full-gen search reports.
 *
 * Prototype predicates (hardcoded; spec-file staging comes later):
 * - villages: exact RWG village site cells (distance=24, separation 8, salt 10387312 — the vanilla
 * canSpawnStructureAtCoords formula inverted to iterate cells) with the real areBiomesViable gate.
 * - biomes: name histogram on a 16-block grid over the origin window (spawn proxy — real spawn is
 * terrain-dependent and lands within a few hundred blocks of origin on RWG).
 * - ocean/river strength at origin.
 *
 * Usage: -Dprobe.prefilter=@seeds.txt | seed1,seed2,... | random:COUNT[:RNGSEED]
 * -Dprobe.prefilter.out=out.jsonl [-Dprobe.prefilter.radius=64 (chunks around origin for village cells)]
 */
public final class Prefilter {

    /** Minimal seed-bearing World: enough construction to satisfy RWG's chunk manager and world type wiring. */
    static final class SeedProbeWorld extends World {

        SeedProbeWorld(long seed, WorldType type) {
            super(
                new NullSaveHandler(),
                "prefilter",
                new WorldSettings(seed, WorldSettings.GameType.SURVIVAL, true, false, type),
                new WorldProviderSurface(),
                new Profiler());
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return null;
        }

        @Override
        protected int func_152379_p() {
            return 0;
        }

        @Override
        public Entity getEntityByID(int id) {
            return null;
        }
    }

    static final class NullSaveHandler implements ISaveHandler {

        public net.minecraft.world.storage.WorldInfo loadWorldInfo() {
            return null;
        }

        public void checkSessionLock() {}

        public net.minecraft.world.chunk.storage.IChunkLoader getChunkLoader(
            net.minecraft.world.WorldProvider provider) {
            return null;
        }

        public void saveWorldInfoWithPlayer(net.minecraft.world.storage.WorldInfo info,
            net.minecraft.nbt.NBTTagCompound tag) {}

        public void saveWorldInfo(net.minecraft.world.storage.WorldInfo info) {}

        public net.minecraft.world.storage.IPlayerFileData getSaveHandler() {
            return null;
        }

        public void flush() {}

        public File getWorldDirectory() {
            return new File("prefilter-null");
        }

        public File getMapFileFromName(String name) {
            return null;
        }

        public String getWorldDirectoryName() {
            return "prefilter-null";
        }
    }

    private static final int VILLAGE_DIST = 24; // RWG: MapGenVillage distance=24
    private static final int VILLAGE_SEP = 8; // vanilla field_82666_h
    private static final int VILLAGE_SALT = 10387312;

    private static Object VILLAGE_GEN;
    private static java.lang.reflect.Method CAN_SPAWN;
    private static Field WORLD_OBJ;

    /**
     * The generator the pack actually uses: vanilla MapGenVillage(size=0,distance=24 — RWG's config) run
     * through the real InitMapGenEvent dispatch so mod replacements (VillageNames) apply. Built once.
     */
    private static synchronized Object villageGenerator() throws Exception {
        if (VILLAGE_GEN == null) {
            final Map<String, String> m = new java.util.HashMap<>();
            m.put("size", "0");
            m.put("distance", String.valueOf(VILLAGE_DIST));
            VILLAGE_GEN = net.minecraftforge.event.terraingen.TerrainGen.getModdedMapGen(
                new MapGenVillage(m),
                net.minecraftforge.event.terraingen.InitMapGenEvent.EventType.VILLAGE);
            // runtime classes carry SRG names; MCP names are the dev-environment fallback
            for (final String name : new String[] { "func_75047_a", "canSpawnStructureAtCoords" }) {
                for (Class<?> c = VILLAGE_GEN.getClass(); CAN_SPAWN == null && c != null; c = c.getSuperclass()) {
                    try {
                        CAN_SPAWN = c.getDeclaredMethod(name, int.class, int.class);
                    } catch (NoSuchMethodException ignored) {}
                }
                if (CAN_SPAWN != null) break;
            }
            CAN_SPAWN.setAccessible(true);
            for (final String name : new String[] { "field_75039_c", "worldObj" }) {
                for (Class<?> c = VILLAGE_GEN.getClass(); WORLD_OBJ == null && c != null; c = c.getSuperclass()) {
                    try {
                        WORLD_OBJ = c.getDeclaredField(name);
                    } catch (NoSuchFieldException ignored) {}
                }
                if (WORLD_OBJ != null) break;
            }
            WORLD_OBJ.setAccessible(true);
            WorldgenProbe.LOG.info(
                "[prefilter] village generator class: {}",
                VILLAGE_GEN.getClass()
                    .getName());
        }
        return VILLAGE_GEN;
    }

    public static void run(String spec, String outPath) throws Exception {
        final WorldType rwg = findWorldType("rwg");
        final int radiusChunks = Integer.getInteger("probe.prefilter.radius", 64);
        final List<Long> seeds = parseSpec(spec);
        WorldgenProbe.LOG
            .info("[prefilter] {} seeds, village radius {} chunks, out {}", seeds.size(), radiusChunks, outPath);

        // World construction swaps Forge's static (s_savehandler, s_mapStorage) cache — save/restore so the
        // running server's future getMapStorage calls are unaffected (harness-speed.md C.1).
        final Field fSave = World.class.getDeclaredField("s_savehandler");
        final Field fStore = World.class.getDeclaredField("s_mapStorage");
        fSave.setAccessible(true);
        fStore.setAccessible(true);
        final Object savedHandler = fSave.get(null);
        final Object savedStorage = fStore.get(null);

        final long t0 = System.nanoTime();
        int done = 0;
        try (FileWriter w = new FileWriter(outPath)) {
            for (final long seed : seeds) {
                final SeedProbeWorld world = new SeedProbeWorld(seed, rwg);
                w.write(evaluate(world, seed, radiusChunks));
                w.write("\n");
                done++;
                if (done % 500 == 0) {
                    final double sps = done / ((System.nanoTime() - t0) / 1e9);
                    WorldgenProbe.LOG.info("[prefilter] {}/{} ({} seeds/s)", done, seeds.size(), (int) sps);
                }
            }
        } finally {
            fSave.set(null, savedHandler);
            fStore.set(null, savedStorage);
        }
        final double secs = (System.nanoTime() - t0) / 1e9;
        WorldgenProbe.LOG.info(
            "[prefilter] done: {} seeds in {} s ({} seeds/s)",
            done,
            String.format("%.1f", secs),
            (int) (done / Math.max(secs, 1e-9)));
    }

    @SuppressWarnings("unchecked")
    private static String evaluate(World world, long seed, int radiusChunks) {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("{\"seed\": ")
            .append(seed);

        // --- villages: ask the REAL village generator (incl. InitMapGenEvent replacements like
        // VillageNames' MapGenVillageVN) — zero reimplementation drift; spacing/salt/biome gates are whatever
        // the pack actually runs.
        final List<int[]> villages = new ArrayList<>();
        try {
            final Object gen = villageGenerator();
            WORLD_OBJ.set(gen, world);
            for (int cx2 = -radiusChunks; cx2 <= radiusChunks; cx2++) {
                for (int cz2 = -radiusChunks; cz2 <= radiusChunks; cz2++) {
                    if ((Boolean) CAN_SPAWN.invoke(gen, cx2, cz2)) {
                        villages.add(new int[] { cx2, cz2 });
                    }
                }
            }
        } catch (Exception e) {
            WorldgenProbe.LOG.warn("[prefilter] village eval failed for {}: {}", seed, e.toString());
        }
        sb.append(", \"villages\": [");
        for (int i = 0; i < villages.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("[")
                .append(villages.get(i)[0])
                .append(", ")
                .append(villages.get(i)[1])
                .append("]");
        }
        sb.append("]");

        // --- biome histogram on a 16-block grid over the origin window (31x31 samples = radius-15-chunk proxy)
        final Map<String, Integer> biomes = new LinkedHashMap<>();
        for (int bx = -240; bx <= 240; bx += 16) {
            for (int bz = -240; bz <= 240; bz += 16) {
                final net.minecraft.world.biome.BiomeGenBase b = world.getWorldChunkManager()
                    .getBiomeGenAt(bx, bz);
                if (b != null) biomes.merge(b.biomeName, 1, Integer::sum);
            }
        }
        sb.append(", \"biomes\": {");
        boolean first = true;
        for (final Map.Entry<String, Integer> e : biomes.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append("\"")
                .append(
                    e.getKey()
                        .replace("\"", ""))
                .append("\": ")
                .append(e.getValue());
        }
        sb.append("}}");
        return sb.toString();
    }

    private static WorldType findWorldType(String name) {
        for (final WorldType t : WorldType.worldTypes) {
            if (t != null && name.equalsIgnoreCase(t.getWorldTypeName())) return t;
        }
        throw new IllegalStateException("worldtype '" + name + "' not registered");
    }

    private static List<Long> parseSpec(String spec) throws Exception {
        final List<Long> out = new ArrayList<>();
        for (final String tok : spec.split(",")) {
            final String t = tok.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("@")) {
                try (BufferedReader br = new BufferedReader(new FileReader(t.substring(1)))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        for (final String w : line.trim()
                            .split("[,\\s]+")) {
                            if (!w.isEmpty()) out.add(Long.parseLong(w));
                        }
                    }
                }
            } else if (t.startsWith("random:")) {
                final String[] parts = t.split(":");
                final int n = Integer.parseInt(parts[1]);
                final Random rng = parts.length > 2 ? new Random(Long.parseLong(parts[2])) : new Random();
                for (int i = 0; i < n; i++) out.add(rng.nextLong());
            } else {
                out.add(Long.parseLong(t));
            }
        }
        return out;
    }

    private Prefilter() {}
}
