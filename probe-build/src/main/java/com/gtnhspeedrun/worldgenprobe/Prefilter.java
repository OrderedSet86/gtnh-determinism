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
 * Coke%-floor modules (2026-07-25):
 * - village piece layouts: dispatch the generator's real func_151539_a per village cell and read its own
 * structureMap — full piece class+bbox list per village (Y nominal, XZ exact), same "N pieces: Name@box; …"
 * string format as the full-gen villages dump so village-hunt.py runs on prefilter output unchanged.
 * Golden: 8/8 corpus villages piece-exact on the fmt2 corpus.
 * - terrain columns + spawn: worldless ChunkGeneratorRealistic terrain path (generateTerrain →
 * replaceBlocksForBiome → caves, exact provideChunk order) + WorldServer.createSpawnPosition walk with
 * BiomesOPlenty's WorldProviderSurfaceBOP accept test (sand/stone top — NOT vanilla grass) → predicted spawn
 * block, per-chunk water-column counts (clay proxy) and surface heights near spawn. Surface heights verified
 * exact vs corpus surf heightmaps.
 * - staged kill gates (-Dprobe.prefilter.gate.villagedist/pieces/water) make one pass behave as the funnel:
 * later modules only run for seeds the earlier gates kept.
 *
 * Usage: -Dprobe.prefilter=@seeds.txt | seed1,seed2,... | random:COUNT[:RNGSEED]
 * -Dprobe.prefilter.out=out.jsonl
 * [-Dprobe.prefilter.radius=64 (chunks around origin for village cells)]
 * [-Dprobe.prefilter.pieces=true (full village piece layouts)]
 * [-Dprobe.prefilter.terrain=4 (digest radius in chunks around predicted spawn; -1 disables terrain+spawn)]
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

        /**
         * MapGenCaves' Forge-patched digBlock asks the world for the biome; the vanilla path first checks
         * blockExists → NPE on our null chunk provider. Answer straight from the chunk manager — for
         * never-generated chunks that is exactly what the vanilla fallback does anyway.
         */
        @Override
        public net.minecraft.world.biome.BiomeGenBase getBiomeGenForCoords(int x, int z) {
            return getWorldChunkManager().getBiomeGenAt(x, z);
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
    private static java.lang.reflect.Method GENERATE; // func_151539_a — the REAL per-chunk entry point
    private static java.lang.reflect.Method IS_SIZEABLE; // func_75069_d — vanilla's >2-non-road-pieces gate
    private static Field WORLD_OBJ;
    private static Field STRUCTURE_MAP; // field_75053_d — Long(chunkXZ2Int) → StructureStart
    private static Field STRUCTURE_DATA; // field_143029_e — per-world NBT cache, must reset per seed
    private static Field START_COMPONENTS; // field_75075_a

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
            CAN_SPAWN = findMethod(
                VILLAGE_GEN.getClass(),
                new String[] { "func_75047_a", "canSpawnStructureAtCoords" },
                int.class,
                int.class);
            GENERATE = findMethod(
                VILLAGE_GEN.getClass(),
                new String[] { "func_151539_a", "generate" },
                net.minecraft.world.chunk.IChunkProvider.class,
                World.class,
                int.class,
                int.class,
                net.minecraft.block.Block[].class);
            WORLD_OBJ = findField(VILLAGE_GEN.getClass(), "field_75039_c", "worldObj");
            STRUCTURE_MAP = findField(VILLAGE_GEN.getClass(), "field_75053_d", "structureMap");
            STRUCTURE_DATA = findField(VILLAGE_GEN.getClass(), "field_143029_e");
            WorldgenProbe.LOG.info(
                "[prefilter] village generator class: {}",
                VILLAGE_GEN.getClass()
                    .getName());
        }
        return VILLAGE_GEN;
    }

    /**
     * Worldless RWG terrain columns + vanilla spawn walk. ChunkGeneratorRealistic's terrain path
     * (generateTerrain → replaceBlocksForBiome) touches the world only via getSeed()/getWorldChunkManager(),
     * both of which SeedProbeWorld serves; we replicate provideChunk's exact sequence — per-chunk
     * rand.setSeed(cx*0x4f9939f508 + cz*0x1ef1565bd5), generateTerrain, baseBiome fill, replaceBlocksForBiome —
     * and skip only the per-biome generateMapGen hook (sole override: tropical-island volcanics, mid-ocean, never
     * spawnable) and the structure/lighting tail. Reflection by PLAIN names: mod classes are not SRG-renamed.
     *
     * Spawn = WorldServer.createSpawnPosition replica: RWG's chunk manager overrides findBiomePosition to null
     * and getBiomesToSpawnIn to empty (zero RNG draws, bytecode-verified on the shipped 1.5.0 jar), so the walk
     * is new Random(worldSeed) stepping i += nextInt(64)-nextInt(64) (then z likewise) from (0,0), ≤1000 iters,
     * y=64 — with the ACCEPT TEST of BiomesOPlenty's WorldProviderSurfaceBOP (which replaces the dim-0
     * provider): top block must be sand or stone (see bopCanSpawn). Top-block replica: climb from y=63 while
     * the block above is air-material, exactly like the provider's getTopBlockCoord.
     * KNOWN APPROXIMATION: the real walk reads live chunks, which can already be populated when the walk
     * revisits a completed 2x2 neighborhood (decoration on the column can flip the test) — the golden test
     * quantifies the miss rate vs corpus spawns.
     */
    static final class RwgTerrain {

        private final Object gen;
        private final Object cmr;
        private final java.lang.reflect.Method mGenTerrain;
        private final java.lang.reflect.Method mReplace;
        private final Field fRand;
        private final Field fTestHeight;
        private final Field fBaseBiome;
        private final Class<?> biomeArrayType;

        /** per-chunk digest: top block per column, top-solid y per column, water-at-62 flags, sand run depth */
        static final class Cols {

            net.minecraft.block.Block[] top = new net.minecraft.block.Block[256];
            short[] topSolid = new short[256];
            boolean[] water = new boolean[256];
            /**
             * consecutive sand blocks from the top-solid surface downward (deep sand = the coke%
             * draconic-place technique wants runs of 3-4)
             */
            byte[] sandRun = new byte[256];
            int sandTotal; // all sand blocks in the chunk, for golden comparison vs corpus "sand"
            /**
             * water column whose floor is sand/gravel/dirt/grass — exactly the blocks DecoClay
             * replaces at populate; the candidate area for clay even though the clay itself rolls later
             */
            boolean[] clayCand = new boolean[256];
            /** gravel at the top-solid surface (flint source — best surface head pickup per corpus) */
            boolean[] gravelTop = new boolean[256];
            // No gravel-burial field here on purpose. Stage-0 runs on pre-population terrain and underground
            // gravel pockets are a BiomeDecorator WorldGenMinable roll at populate, so a burial scan reads
            // "nothing" over ordinary ground — verified against the -3013484044701601670 save, where it found
            // no gravel in chunks the populated world has gravel 9-11 blocks down. Scanning 64 deep per column
            // to learn that cost ~30% of the terrain digest (0.166 s -> 0.22 s/seed). gravelTop above already
            // captures the only gravel stage-0 can legitimately see; real burial comes from the full probe's
            // "gravelBurial" (format 3).
        }

        private final Map<Long, Cols> cache = new java.util.HashMap<>();

        private final World world;
        private final net.minecraft.world.gen.MapGenBase caves;

        RwgTerrain(World world) throws Exception {
            this.world = world;
            final Class<?> genCls = Class.forName("rwg.world.ChunkGeneratorRealistic");
            gen = genCls.getConstructor(World.class, long.class)
                .newInstance(world, world.getSeed());
            cmr = world.getWorldChunkManager();
            // provideChunk runs the caves pass on the raw block array BEFORE the chunk is built — cave mouths
            // carve away surface grass, which flips getTopBlock and therefore the spawn walk. Same dispatch RWG
            // uses; the IChunkProvider param is unused by vanilla caves.
            caves = net.minecraftforge.event.terraingen.TerrainGen.getModdedMapGen(
                new net.minecraft.world.gen.MapGenCaves(),
                net.minecraftforge.event.terraingen.InitMapGenEvent.EventType.CAVE);
            java.lang.reflect.Method gt = null, rp = null;
            for (final java.lang.reflect.Method m : genCls.getMethods()) {
                if ("generateTerrain".equals(m.getName())) gt = m;
                if ("replaceBlocksForBiome".equals(m.getName())) rp = m;
            }
            if (gt == null || rp == null) throw new NoSuchMethodException("rwg generateTerrain/replaceBlocksForBiome");
            mGenTerrain = gt;
            mReplace = rp;
            biomeArrayType = gt.getParameterTypes()[5].getComponentType();
            fRand = findField(genCls, "rand");
            fTestHeight = findField(genCls, "testHeight");
            fBaseBiome = findField(biomeArrayType, "baseBiome");
        }

        Cols columns(int cx, int cz) throws Exception {
            final long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
            Cols c = cache.get(key);
            if (c != null) return c;
            final Random rnd = (Random) fRand.get(gen);
            rnd.setSeed((long) cx * 341873128712L + (long) cz * 132897987541L);
            final net.minecraft.block.Block[] blocks = new net.minecraft.block.Block[65536];
            final byte[] meta = new byte[65536];
            final Object biomes = java.lang.reflect.Array.newInstance(biomeArrayType, 256);
            // shipped 1.5.0: generateTerrain(cmr,cx,cz,blocks,meta,biomes,float[256] noise-out) and the same
            // local noise buffer feeds replaceBlocksForBiome; dev fork: 6-arg + instance testHeight field
            final float[] noise = new float[256];
            final Object noiseArg;
            if (mGenTerrain.getParameterTypes().length == 7) {
                mGenTerrain.invoke(gen, cmr, cx, cz, blocks, meta, biomes, noise);
                noiseArg = noise;
            } else {
                mGenTerrain.invoke(gen, cmr, cx, cz, blocks, meta, biomes);
                noiseArg = fTestHeight.get(gen);
            }
            final net.minecraft.world.biome.BiomeGenBase[] base = new net.minecraft.world.biome.BiomeGenBase[256];
            for (int k = 0; k < 256; k++) {
                final Object rb = java.lang.reflect.Array.get(biomes, k);
                base[k] = rb == null ? null : (net.minecraft.world.biome.BiomeGenBase) fBaseBiome.get(rb);
            }
            mReplace.invoke(gen, cx, cz, blocks, meta, biomes, base, noiseArg);
            caves.func_151539_a(null, world, cx, cz, blocks);
            // digest to ~3 KB before caching (a full Block[] chunk is 512 KB)
            c = new Cols();
            for (int col = 0; col < 256; col++) {
                final int off = col << 8; // col = x*16+z, matching RWG's (j*16+i)*256+k layout
                int k = 63;
                while (k < 255 && !isAirLike(blocks[off + k + 1])) k++;
                c.top[col] = blocks[off + k];
                int ts = 255;
                while (ts > 0 && (isAirLike(blocks[off + ts]) || blocks[off + ts] == net.minecraft.init.Blocks.water)) {
                    ts--;
                }
                c.topSolid[col] = (short) ts;
                c.water[col] = blocks[off + 62] == net.minecraft.init.Blocks.water;
                int run = 0;
                while (run < ts && blocks[off + ts - run] == net.minecraft.init.Blocks.sand && run < 127) run++;
                c.sandRun[col] = (byte) run;
                final net.minecraft.block.Block floor = blocks[off + ts];
                c.gravelTop[col] = floor == net.minecraft.init.Blocks.gravel;
                c.clayCand[col] = c.water[col]
                    && (floor == net.minecraft.init.Blocks.sand || floor == net.minecraft.init.Blocks.gravel
                        || floor == net.minecraft.init.Blocks.dirt
                        || floor == net.minecraft.init.Blocks.grass);
            }
            int sandTotal = 0;
            for (int i = 0; i < 65536; i++) {
                if (blocks[i] == net.minecraft.init.Blocks.sand) sandTotal++;
            }
            c.sandTotal = sandTotal;
            cache.put(key, c);
            return c;
        }

        private static boolean isAirLike(net.minecraft.block.Block b) {
            return b == null || b.getMaterial() == net.minecraft.block.material.Material.air;
        }

        net.minecraft.block.Block topBlock(int x, int z) throws Exception {
            final Cols c = columns(x >> 4, z >> 4);
            return c.top[((x & 15) << 4) | (z & 15)];
        }

        int chunksGenerated() {
            return cache.size();
        }
    }

    /** WorldProviderSurfaceBOP.canCoordinateBeSpawn, replicated over worldless columns (see call site). */
    private static boolean bopCanSpawn(World world, RwgTerrain terra, int x, int z) throws Exception {
        final net.minecraft.block.Block top = terra.topBlock(x, z);
        if (top == net.minecraft.init.Blocks.sand || top == net.minecraft.init.Blocks.stone) return true;
        return top == net.minecraft.init.Blocks.snow_layer && world.getWorldChunkManager()
            .getBiomesToSpawnIn()
            .contains(world.getBiomeGenForCoords(x, z));
    }

    private static java.lang.reflect.Method findMethod(Class<?> cls, String[] names, Class<?>... params)
        throws NoSuchMethodException {
        for (final String name : names) {
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                try {
                    final java.lang.reflect.Method m = c.getDeclaredMethod(name, params);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {}
            }
        }
        throw new NoSuchMethodException(cls.getName() + "." + String.join("/", names));
    }

    private static Field findField(Class<?> cls, String... names) throws NoSuchFieldException {
        for (final String name : names) {
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                try {
                    final Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {}
            }
        }
        throw new NoSuchFieldException(cls.getName() + "." + String.join("/", names));
    }

    /**
     * Full village piece layouts via the REAL entry point: reset the generator's per-world caches, then call
     * func_151539_a (MapGenBase.generate) once per village cell — the exact rand protocol, canSpawn gate and
     * getStructureStart the pack runs, zero reimplementation — and read the resulting StructureStarts out of the
     * generator's own structureMap. (A manual replication of the rand protocol produced self-stable but
     * real-divergent layouts — dispatch, don't reimplement.) The Start ctor touches the world only via
     * getWorldChunkManager(), so the recursion is exact against SeedProbeWorld. Y anchors in the emitted boxes
     * are pre-terrain placeholders — XZ and piece classes are final; real gen only offsets Y and prunes pieces
     * whose ground check fails at build time.
     */
    private static List<String> villageStarts(Object gen, World world, List<int[]> cells) throws Exception {
        final Map<?, ?> structureMap = (Map<?, ?>) STRUCTURE_MAP.get(gen);
        structureMap.clear();
        STRUCTURE_DATA.set(gen, null); // else func_143027_a reuses the previous seed's NBT cache
        for (final int[] cell : cells) {
            GENERATE.invoke(gen, null, world, cell[0], cell[1], null);
        }
        final List<String> out = new ArrayList<>();
        for (final Map.Entry<?, ?> e : ((Map<?, ?>) STRUCTURE_MAP.get(gen)).entrySet()) {
            final long key = ((Number) e.getKey()).longValue(); // ChunkCoordIntPair.chunkXZ2Int
            final int cx = (int) key, cz = (int) (key >> 32);
            final Object start = e.getValue();
            try {
                if (IS_SIZEABLE == null) {
                    IS_SIZEABLE = findMethod(start.getClass(), new String[] { "func_75069_d", "isSizeableStructure" });
                }
                if (START_COMPONENTS == null) {
                    START_COMPONENTS = findField(start.getClass(), "field_75075_a", "components");
                }
                final boolean sizeable = (Boolean) IS_SIZEABLE.invoke(start);
                final List<?> comps = (List<?>) START_COMPONENTS.get(start);
                final List<String> parts = new ArrayList<>();
                for (final Object comp : comps) {
                    parts.add(
                        comp.getClass()
                            .getSimpleName() + "@"
                            + WorldgenProbe.bboxOf(comp));
                }
                java.util.Collections.sort(parts); // same canonical order as the full-gen villages dump
                final StringBuilder sb = new StringBuilder(64 + 64 * parts.size());
                sb.append("{\"c\": [")
                    .append(cx)
                    .append(", ")
                    .append(cz)
                    .append("], \"sizeable\": ")
                    .append(sizeable)
                    .append(", \"pieces\": \"")
                    .append(parts.size())
                    .append(" pieces: ")
                    .append(String.join("; ", parts))
                    .append("\"}");
                out.add(sb.toString());
            } catch (Exception ex) {
                out.add(
                    "{\"c\": [" + cx
                        + ", "
                        + cz
                        + "], \"error\": \""
                        + ex.toString()
                            .replace("\"", "'")
                        + "\"}");
            }
        }
        return out;
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
        final List<String> starts = new ArrayList<>();
        final boolean pieces = !"false".equals(System.getProperty("probe.prefilter.pieces"));
        // staged kill gates: each stage only runs if the previous one kept the seed, so a gated
        // million-seed sweep pays pieces/terrain cost only for survivors (the funnel in one pass)
        final int gateVillageDist = Integer.getInteger("probe.prefilter.gate.villagedist", -1);
        final String gatePieces = System.getProperty("probe.prefilter.gate.pieces");
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
            if (gateVillageDist >= 0) {
                boolean ok = false;
                for (final int[] v : villages) {
                    if (Math.max(Math.abs(v[0]), Math.abs(v[1])) <= gateVillageDist) {
                        ok = true;
                        break;
                    }
                }
                if (!ok) return "{\"seed\": " + seed + ", \"kill\": \"village\"}";
            }
            if (pieces && !villages.isEmpty()) {
                starts.addAll(villageStarts(gen, world, villages));
            }
            if (gatePieces != null) {
                boolean ok = false;
                for (final String st : starts) {
                    for (final String name : gatePieces.split(",")) {
                        if (!name.isEmpty() && st.contains(name.trim() + "@")) {
                            ok = true;
                            break;
                        }
                    }
                    if (ok) break;
                }
                if (!ok) return "{\"seed\": " + seed + ", \"kill\": \"pieces\"}";
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
        if (pieces) {
            sb.append(", \"village_starts\": [")
                .append(String.join(", ", starts))
                .append("]");
        }

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
        sb.append("}");

        // --- terrain columns + spawn prediction (worldless RWG; -Dprobe.prefilter.terrain=-1 disables,
        // N = digest radius in chunks around the predicted spawn; default 4)
        final int terrainRadius = Integer.getInteger("probe.prefilter.terrain", 4);
        if (terrainRadius >= 0) {
            try {
                final RwgTerrain terra = new RwgTerrain(world);
                // vanilla WorldServer.createSpawnPosition replica (see RwgTerrain javadoc). The
                // findBiomePosition call is the REAL one for draw-parity insurance — RWG returns null
                // without consuming the rand, but if that ever changes the golden test stays honest.
                final Random spawnRand = new Random(seed);
                final net.minecraft.world.ChunkPosition cp = world.getWorldChunkManager()
                    .findBiomePosition(
                        0,
                        0,
                        256,
                        world.getWorldChunkManager()
                            .getBiomesToSpawnIn(),
                        spawnRand);
                int sx = 0, sz = 0;
                if (cp != null) {
                    sx = cp.chunkPosX;
                    sz = cp.chunkPosZ;
                }
                // the accept test is NOT vanilla's grass check: BiomesOPlenty replaces the dim-0 provider
                // (WorldProviderSurfaceBOP, bytecode-verified from the shipped 2.1.0.2308 jar) — top block must
                // be sand or stone, or snow_layer in a getBiomesToSpawnIn biome (empty list on RWG, so never);
                // its extra "water nearby" loop re-reads the same top block 45 times, i.e. a no-op given the
                // top-block climb can only end on a non-air block. Hence: beach/desert/bare-rock spawns.
                int iters = 0;
                while (!bopCanSpawn(world, terra, sx, sz)) {
                    sx += spawnRand.nextInt(64) - spawnRand.nextInt(64);
                    sz += spawnRand.nextInt(64) - spawnRand.nextInt(64);
                    if (++iters == 1000) break;
                }
                sb.append(", \"spawn\": [")
                    .append(sx)
                    .append(", 64, ")
                    .append(sz)
                    .append("], \"spawn_iters\": ")
                    .append(iters);
                // per-chunk digest around spawn: water columns at y62 (DecoClay only fires under water →
                // shallow-water columns are the clay candidates) + top-solid min/avg (burial/flatness)
                final int scx = sx >> 4, scz = sz >> 4;
                final List<String> digest = new ArrayList<>();
                int waterTotal = 0;
                for (int cx2 = scx - terrainRadius; cx2 <= scx + terrainRadius; cx2++) {
                    for (int cz2 = scz - terrainRadius; cz2 <= scz + terrainRadius; cz2++) {
                        final RwgTerrain.Cols c = terra.columns(cx2, cz2);
                        int water = 0, surfMin = 255, surfSum = 0, deepSand = 0, clayCand = 0, gravelTop = 0;
                        int sand5 = 0, sand7 = 0, sandMax = 0;
                        for (int col = 0; col < 256; col++) {
                            if (c.water[col]) water++;
                            final int sr = c.sandRun[col];
                            if (sr >= 3) deepSand++;
                            if (sr >= 5) sand5++;
                            if (sr >= 7) sand7++;
                            if (sr > sandMax) sandMax = sr;
                            if (c.clayCand[col]) clayCand++;
                            if (c.gravelTop[col]) gravelTop++;
                            final int ts = c.topSolid[col];
                            if (ts < surfMin) surfMin = ts;
                            surfSum += ts;
                        }
                        waterTotal += water;
                        // row: [cx, cz, waterCols, surfMin, surfAvg, deepSandCols(run>=3), sandBlocksTotal,
                        // clayCandCols(water floor DecoClay-replaceable), gravelTopCols,
                        // sand5Cols(run>=5), sand7Cols(run>=7), sandMaxRun]
                        // Fields 9+ are appended, never inserted: index 0-8 stays wire-compatible with the
                        // corpora already on disk. The run>=3 count alone cannot rank depth — a 4-deep blanket
                        // and a 10-deep pit both score 256 — which is the whole reason for sand5/sand7/sandMax.
                        digest.add(
                            "[" + cx2
                                + ", "
                                + cz2
                                + ", "
                                + water
                                + ", "
                                + surfMin
                                + ", "
                                + (surfSum / 256)
                                + ", "
                                + deepSand
                                + ", "
                                + c.sandTotal
                                + ", "
                                + clayCand
                                + ", "
                                + gravelTop
                                + ", "
                                + sand5
                                + ", "
                                + sand7
                                + ", "
                                + sandMax
                                + "]");
                    }
                }
                final int gateWater2 = Integer.getInteger("probe.prefilter.gate.water", -1);
                if (gateWater2 >= 0 && waterTotal < gateWater2) {
                    return "{\"seed\": " + seed + ", \"kill\": \"water\"}";
                }
                sb.append(", \"terrain\": [")
                    .append(String.join(", ", digest))
                    .append("], \"water_total\": ")
                    .append(waterTotal)
                    .append(", \"terrain_chunks\": ")
                    .append(terra.chunksGenerated());
            } catch (Throwable t) {
                WorldgenProbe.LOG.warn("[prefilter] terrain/spawn eval failed for {}: {}", seed, t.toString());
                sb.append(", \"terrain_error\": \"")
                    .append(
                        t.toString()
                            .replace("\"", "'"))
                    .append("\"");
            }
        }

        sb.append("}");
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
