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
import net.minecraft.world.chunk.Chunk;
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
 * [-Dprobe.prefilter.chunkcache=256 (LRU size of the virgin-terrain chunk cache)]
 * [-Dprobe.prefilter.strictworld=true (throw on a live world write instead of logging)]
 * [-Dprobe.prefilter.digestviaprovider=true (terrain digest source; false = the old hand-rolled path)]
 * [-Dprobe.prefilter.selftest=false (cross-check world.getBlock against the digest)]
 * [-Dprobe.prefilter.dungeon=N (Roguelike trigger scan radius in chunks around predicted spawn; -1 off)]
 * [-Dprobe.prefilter.gate.dungeon=false (kill seeds with no dungeon trigger in range)]
 * [-Dprobe.prefilter.witchery=N (Witchery candidate-cell scan radius in chunks around predicted spawn; -1 off)]
 *
 * Block reads (2026-08-29): {@link VirginChunkProvider} backs the world, so {@code world.getBlock} and
 * {@code getBlockMetadata} answer virgin terrain. That is what {@code TerrainOracle} falls through to for a
 * non-WorldServer, so the fix jar's virgin-terrain consumers — Roguelike validLocation, the GT vein reroll gate —
 * become evaluable here. Verified 64800 columns / 200 seeds with zero mismatches against the independently
 * computed digest; see results/2026-08-29-virgin-chunk-provider.
 */
public final class Prefilter {

    /** Minimal seed-bearing World: enough construction to satisfy RWG's chunk manager and world type wiring. */
    static final class SeedProbeWorld extends World {

        private VirginChunkProvider virgin;

        SeedProbeWorld(long seed, WorldType type) {
            super(
                new NullSaveHandler(),
                "prefilter",
                new WorldSettings(seed, WorldSettings.GameType.SURVIVAL, true, false, type),
                new WorldProviderSurface(),
                new Profiler());
        }

        /**
         * Virgin terrain on demand, so {@code world.getBlock} and {@code getBlockMetadata} answer worldlessly.
         * Called from {@code World}'s constructor, after {@code worldInfo} and {@code provider.registerWorld},
         * so the seed and the chunk manager are both available — but the generator is still built lazily, since
         * nothing needs it until the first read.
         */
        @Override
        protected IChunkProvider createChunkProvider() {
            virgin = new VirginChunkProvider(this);
            return virgin;
        }

        VirginChunkProvider virginProvider() {
            return virgin;
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
         * blockExists, which is false for every chunk here. Answer straight from the chunk manager — for
         * never-generated chunks that is exactly what the vanilla fallback does anyway.
         */
        @Override
        public net.minecraft.world.biome.BiomeGenBase getBiomeGenForCoords(int x, int z) {
            return getWorldChunkManager().getBiomeGenAt(x, z);
        }

        // --- Write guards. Cached chunks ARE the virgin terrain; a live write would edit the oracle in place
        // and every later read would answer from a world that no longer matches the seed — deterministically,
        // and therefore invisibly. Nothing in the intended call graph writes, so these should never fire.

        @Override
        public boolean setBlock(int x, int y, int z, net.minecraft.block.Block block, int meta, int flags) {
            return refuseWrite("setBlock", x, y, z);
        }

        @Override
        public boolean setBlockMetadataWithNotify(int x, int y, int z, int meta, int flags) {
            return refuseWrite("setBlockMetadataWithNotify", x, y, z);
        }

        @Override
        public void setTileEntity(int x, int y, int z, net.minecraft.tileentity.TileEntity te) {
            refuseWrite("setTileEntity", x, y, z);
        }

        private boolean refuseWrite(String what, int x, int y, int z) {
            final String msg = "prefilter: live world write " + what
                + " at "
                + x
                + ","
                + y
                + ","
                + z
                + " — the chunk cache is the virgin-terrain oracle and must stay read-only";
            if (Boolean.parseBoolean(System.getProperty("probe.prefilter.strictworld", "true"))) {
                throw new IllegalStateException(msg);
            }
            WorldgenProbe.LOG.error("[prefilter] {}", msg);
            return false;
        }

        // Lighting and render notifications walk neighbouring chunks, which would pull the whole window into
        // the cache for no benefit. Nothing here has a renderer or a light-sensitive consumer.

        @Override
        public void markBlockForUpdate(int x, int y, int z) {}

        @Override
        public void notifyBlockChange(int x, int y, int z, net.minecraft.block.Block block) {}

        @Override
        public void func_147479_m(int x, int y, int z) {}

        @Override
        public boolean updateLightByType(net.minecraft.world.EnumSkyBlock type, int x, int y, int z) {
            return false;
        }
    }

    /**
     * Virgin-terrain chunk provider: real {@link Chunk} objects from the pack's real chunk generator, cached.
     *
     * <p>
     * This is what turns {@code SeedProbeWorld} from "a seed carrier" into "a world you can read". Everything
     * downstream of it — the GT vein reroll gate, Roguelike's {@code validLocation}, the fix jar's
     * {@code TerrainOracle}, which falls through to {@code world.getBlock} for a non-{@code WorldServer} — needs
     * exactly one thing: block and metadata reads that are a pure function of the seed.
     *
     * <p>
     * It dispatches to {@code ChunkGeneratorRealistic.provideChunk} rather than replicating the terrain steps.
     * That is the same method {@code TerrainOracle} calls in a full run, and it is what
     * {@code WorldTypeRealistic.getChunkGenerator} builds, so parity is structural instead of tested-in. It also
     * closes an omission in the hand-rolled path: {@code RwgTerrain} ran only
     * {@code generateTerrain → replaceBlocksForBiome → caves} and skipped the per-biome {@code generateMapGen},
     * which writes blocks.
     *
     * <p>
     * {@link #chunkExists} always answers false, which is the truth — nothing here is a loaded, populated chunk —
     * and is load-bearing for the fix jar: {@code PendingSlices.shouldBuffer} short-circuits on it, so a dungeon
     * generated against this world buffers every write instead of reaching {@code world.setBlock}.
     */
    static final class VirginChunkProvider implements IChunkProvider {

        private static final int DEFAULT_CACHE = 256;

        private final World world;
        private IChunkProvider gen;
        private final Map<Long, Chunk> cache;
        /** Coords currently inside provideChunk: re-entry would recurse forever, so it fails loudly instead. */
        private final java.util.Set<Long> generating = new java.util.HashSet<>();
        /** Coords generated at least once, so an eviction followed by a re-request is visible as thrash. */
        private final java.util.Set<Long> seen = new java.util.HashSet<>();

        private int generated;
        private int regenerated;

        VirginChunkProvider(World world) {
            this.world = world;
            final int max = Integer.getInteger("probe.prefilter.chunkcache", DEFAULT_CACHE);
            this.cache = new LinkedHashMap<Long, Chunk>(64, 0.75f, true) {

                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Chunk> eldest) {
                    return size() > max;
                }
            };
        }

        private static long key(int cx, int cz) {
            return ((long) cx << 32) ^ (cz & 0xffffffffL);
        }

        private IChunkProvider generator() {
            if (gen == null) {
                try {
                    gen = (IChunkProvider) Class.forName("rwg.world.ChunkGeneratorRealistic")
                        .getConstructor(World.class, long.class)
                        .newInstance(world, world.getSeed());
                } catch (Exception e) {
                    throw new IllegalStateException("prefilter: cannot build RWG chunk generator", e);
                }
            }
            return gen;
        }

        @Override
        public Chunk provideChunk(int cx, int cz) {
            final long k = key(cx, cz);
            final Chunk hit = cache.get(k);
            if (hit != null) return hit;
            if (!generating.add(k)) {
                throw new IllegalStateException(
                    "prefilter: re-entrant chunk generation at " + cx
                        + ","
                        + cz
                        + " — a generator read the world "
                        + "for the chunk it is building");
            }
            try {
                final Chunk c = generator().provideChunk(cx, cz);
                cache.put(k, c);
                generated++;
                if (!seen.add(k)) regenerated++;
                return c;
            } finally {
                generating.remove(k);
            }
        }

        @Override
        public Chunk loadChunk(int cx, int cz) {
            return provideChunk(cx, cz);
        }

        /** Always false: these are generated-on-demand terrain snapshots, never loaded populated chunks. */
        @Override
        public boolean chunkExists(int cx, int cz) {
            return false;
        }

        @Override
        public void populate(IChunkProvider p, int cx, int cz) {}

        @Override
        public boolean saveChunks(boolean all, net.minecraft.util.IProgressUpdate progress) {
            return true;
        }

        @Override
        public boolean unloadQueuedChunks() {
            return false;
        }

        @Override
        public boolean canSave() {
            return false;
        }

        @Override
        public String makeString() {
            return "prefilter-virgin " + cache.size() + "/" + generated + " gen, " + regenerated + " regen";
        }

        @Override
        public List<net.minecraft.world.biome.BiomeGenBase.SpawnListEntry> getPossibleCreatures(
            net.minecraft.entity.EnumCreatureType type, int x, int y, int z) {
            return java.util.Collections.emptyList();
        }

        @Override
        public net.minecraft.world.ChunkPosition func_147416_a(World w, String name, int x, int y, int z) {
            return null;
        }

        @Override
        public int getLoadedChunkCount() {
            return cache.size();
        }

        @Override
        public void recreateStructures(int cx, int cz) {}

        @Override
        public void saveExtraData() {}

        int generatedCount() {
            return generated;
        }

        /**
         * How many chunks had to be generated a second time because the LRU evicted them. Nonzero means the
         * cache is too small for the access pattern and the run is paying full terrain cost repeatedly — a
         * throughput bug that would otherwise be invisible.
         */
        int regeneratedCount() {
            return regenerated;
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

        /**
         * Migration switch for the digest source. True dispatches to the world's own chunk provider (which runs
         * the whole of provideChunk, including the generateMapGen pass the hand-rolled path skipped); false keeps
         * the original subsequence. Exists so the change to an already-golden-tested surface can be A/B'd on one
         * flag rather than one rebuild, and should be removed once that comparison is recorded.
         */
        private static final boolean VIA_PROVIDER = Boolean
            .parseBoolean(System.getProperty("probe.prefilter.digestviaprovider", "true"));

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
            c = VIA_PROVIDER ? digestFromProvider(cx, cz) : digestHandRolled(cx, cz);
            cache.put(key, c);
            return c;
        }

        /**
         * Digest a chunk the world's own provider generated. This is the same {@code provideChunk} the full run
         * calls through {@code TerrainOracle}, so it needs no reflection and cannot drift from it — and unlike
         * the hand-rolled path below it includes the per-biome {@code generateMapGen} pass, which writes blocks.
         */
        private Cols digestFromProvider(int cx, int cz) {
            final Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
            final net.minecraft.world.chunk.storage.ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
            final Cols c = new Cols();
            int sandTotal = 0;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    // Cols is indexed col = x*16+z, matching RWG's (j*16+i)*256+k layout.
                    final int col = (x << 4) | z;
                    int k = 63;
                    while (k < 255 && !isAirLike(blockAt(sections, x, k + 1, z))) k++;
                    c.top[col] = blockAt(sections, x, k, z);
                    int ts = 255;
                    while (ts > 0) {
                        final net.minecraft.block.Block b = blockAt(sections, x, ts, z);
                        if (!isAirLike(b) && b != net.minecraft.init.Blocks.water) break;
                        ts--;
                    }
                    c.topSolid[col] = (short) ts;
                    c.water[col] = blockAt(sections, x, 62, z) == net.minecraft.init.Blocks.water;
                    int run = 0;
                    while (run < ts && blockAt(sections, x, ts - run, z) == net.minecraft.init.Blocks.sand
                        && run < 127) {
                        run++;
                    }
                    c.sandRun[col] = (byte) run;
                    final net.minecraft.block.Block floor = blockAt(sections, x, ts, z);
                    c.gravelTop[col] = floor == net.minecraft.init.Blocks.gravel;
                    c.clayCand[col] = c.water[col]
                        && (floor == net.minecraft.init.Blocks.sand || floor == net.minecraft.init.Blocks.gravel
                            || floor == net.minecraft.init.Blocks.dirt
                            || floor == net.minecraft.init.Blocks.grass);
                }
            }
            // Full-volume sand count, skipping empty sections: RWG terrain fills 5-7 of the 16, so most of the
            // column space is a null pointer rather than 4096 reads.
            for (int s = 0; s < sections.length; s++) {
                final net.minecraft.world.chunk.storage.ExtendedBlockStorage sec = sections[s];
                if (sec == null) continue;
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            if (sec.getBlockByExtId(x, y, z) == net.minecraft.init.Blocks.sand) sandTotal++;
                        }
                    }
                }
            }
            c.sandTotal = sandTotal;
            return c;
        }

        private static net.minecraft.block.Block blockAt(
            net.minecraft.world.chunk.storage.ExtendedBlockStorage[] sections, int x, int y, int z) {
            if (y < 0 || y > 255) return net.minecraft.init.Blocks.air;
            final net.minecraft.world.chunk.storage.ExtendedBlockStorage sec = sections[y >> 4];
            return sec == null ? net.minecraft.init.Blocks.air : sec.getBlockByExtId(x, y & 15, z);
        }

        /**
         * The original hand-rolled subsequence, kept behind {@code -Dprobe.prefilter.digestviaprovider=false} so
         * the switch above can be A/B'd against the corpora it was golden-tested on. Delete once that comparison
         * is recorded.
         */
        private Cols digestHandRolled(int cx, int cz) throws Exception {
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
            final Cols c = new Cols();
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

    /** Package-private: {@link BiomeTable} reads protected biome flags and RWG's private buckets through it. */
    static Field findField(Class<?> cls, String... names) throws NoSuchFieldException {
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
    /**
     * Villagers a village piece will spawn: profession and exact X/Z, computed worldlessly.
     * <p>
     * This is free once the piece layout is known. {@code spawnVillagers} draws no random numbers — the count and
     * offsets are literals at each call site, the profession is a compile-time constant returned by
     * {@code getVillagerType}, and the position is arithmetic on the component's bounding box and
     * {@code coordBaseMode} (see {@code StructureComponent.getXWithOffset}/{@code getZWithOffset}). So a seed sweep
     * can answer "is there a blacksmith within N chunks of spawn" without generating anything.
     * <p>
     * Y is NOT emitted. {@code getYWithOffset} adds {@code boundingBox.minY}, which real generation sets from
     * {@code getAverageGroundLevel} — a mean of {@code getTopSolidOrLiquidBlock} over the footprint — so it needs
     * terrain. XZ and profession are exact; treat the list as a superset, since a piece whose ground check fails at
     * build time is pruned, exactly as the sibling {@code pieces} field already warns.
     */
    private static List<String> predictVillagers(Object comp) {
        final String cls = comp.getClass()
            .getSimpleName();
        // {xOff, yOff, zOff, count}, then professions per index. From the spawnVillagers call site in each class.
        final int[] call;
        final int[] profs;
        switch (cls) {
            case "Church":
                call = new int[] { 2, 1, 2, 1 };
                profs = new int[] { 2 };
                break;
            case "Hall":
                call = new int[] { 4, 1, 2, 2 };
                profs = new int[] { 4, 0 };
                break;
            case "House1":
                call = new int[] { 2, 1, 2, 1 };
                profs = new int[] { 1 };
                break;
            case "House2":
                call = new int[] { 7, 1, 1, 1 };
                profs = new int[] { 3 };
                break;
            case "House3":
                call = new int[] { 4, 1, 2, 2 };
                profs = new int[] { 0, 0 };
                break;
            case "House4Garden":
                call = new int[] { 1, 1, 2, 1 };
                profs = new int[] { 0 };
                break;
            case "WoodHut":
                call = new int[] { 1, 1, 2, 1 };
                profs = new int[] { 0 };
                break;
            // Mod pieces: offsets are known, professions are mod-registered ids that vary by pack, so they are
            // reported as -1 rather than guessed.
            case "ComponentVillageBeeHouse":
                call = new int[] { 7, 1, 1, 2 };
                profs = new int[] { -1, -1 };
                break;
            case "ComponentWorkshop":
                call = new int[] { 0, 0, 0, 2 };
                profs = new int[] { -1, -1 };
                break;
            case "ComponentToolWorkshop":
                call = new int[] { 3, 1, 3, 1 };
                profs = new int[] { -1 };
                break;
            default:
                return java.util.Collections.emptyList();
        }
        final List<String> out = new ArrayList<>(call[3]);
        try {
            final net.minecraft.world.gen.structure.StructureBoundingBox bb = bboxField(comp);
            if (bb == null) return out;
            final int mode = coordBaseMode(comp);
            if (mode < 0 || mode > 3) return out; // coordBaseMode -1: orientation undecided, position undefined
            for (int i = 0; i < call[3]; i++) {
                final int lx = call[0] + i, lz = call[2];
                out.add(
                    cls + ":prof"
                        + profs[i]
                        + "@"
                        + xWithOffset(bb, mode, lx, lz)
                        + ","
                        + zWithOffset(bb, mode, lx, lz));
            }
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * {@code StructureComponent.getXWithOffset}, replicated for a component we hold reflectively.
     *
     * <p>
     * Piece-local coordinates are stated before rotation; the piece's {@code coordBaseMode} decides how they map
     * onto the world. Both the villager predictor and the chest module need this, so it lives in one place —
     * writing the switch twice is how the two would eventually disagree.
     *
     * <p>
     * Callers must reject {@code mode < 0} themselves: vanilla passes the local coordinate straight through for
     * an undecided orientation, which is not a world position and must not be treated as one.
     */
    static int xWithOffset(net.minecraft.world.gen.structure.StructureBoundingBox bb, int mode, int lx, int lz) {
        switch (mode) {
            case 0:
            case 2:
                return bb.minX + lx;
            case 1:
                return bb.maxX - lz;
            case 3:
                return bb.minX + lz;
            default:
                return lx;
        }
    }

    /** {@code StructureComponent.getZWithOffset}. See {@link #xWithOffset}. */
    static int zWithOffset(net.minecraft.world.gen.structure.StructureBoundingBox bb, int mode, int lx, int lz) {
        switch (mode) {
            case 0:
                return bb.minZ + lz;
            case 1:
            case 3:
                return bb.minZ + lx;
            case 2:
                return bb.maxZ - lz;
            default:
                return lz;
        }
    }

    /** {@code StructureComponent.getYWithOffset}: local Y is relative to the box floor once oriented. */
    static int yWithOffset(net.minecraft.world.gen.structure.StructureBoundingBox bb, int mode, int ly) {
        return mode == -1 ? ly : ly + bb.minY;
    }

    private static net.minecraft.world.gen.structure.StructureBoundingBox bboxField(Object comp) throws Exception {
        for (Class<?> sc = comp.getClass(); sc != null; sc = sc.getSuperclass()) {
            for (java.lang.reflect.Field f : sc.getDeclaredFields()) {
                if (net.minecraft.world.gen.structure.StructureBoundingBox.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return (net.minecraft.world.gen.structure.StructureBoundingBox) f.get(comp);
                }
            }
        }
        return null;
    }

    private static int coordBaseMode(Object comp) throws Exception {
        for (Class<?> sc = comp.getClass(); sc != null; sc = sc.getSuperclass()) {
            for (java.lang.reflect.Field f : sc.getDeclaredFields()) {
                if (f.getType() == int.class
                    && ("coordBaseMode".equals(f.getName()) || "field_74885_f".equals(f.getName()))) {
                    f.setAccessible(true);
                    return f.getInt(comp);
                }
            }
        }
        return -1;
    }

    /** -Dprobe.prefilter.villagechests=true: predict each village piece's chest contents (Stage 4). */
    private static final boolean CHESTS_ENABLED = Boolean.getBoolean("probe.prefilter.villagechests");

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
                final List<String> villagers = new ArrayList<>();
                for (final Object comp : comps) villagers.addAll(predictVillagers(comp));
                java.util.Collections.sort(villagers);
                // Chest contents per piece. Uses exactly the inputs already gathered above — class, box and
                // orientation — because F10's fork uses no terrain term.
                final List<String> chests = new ArrayList<>();
                if (CHESTS_ENABLED) {
                    for (final Object comp : comps) {
                        final net.minecraft.world.gen.structure.StructureBoundingBox cbb = bboxField(comp);
                        if (cbb == null) continue;
                        for (final VillageChestPrefilter.Predicted p : VillageChestPrefilter.predict(
                            world.getSeed(),
                            comp.getClass()
                                .getName(),
                            coordBaseMode(comp),
                            cbb,
                            cbb.minY)) {
                            chests.add(
                                "{\"piece\": \"" + p.piece
                                    + "\", \"category\": \""
                                    + p.category
                                    + "\", \"chest\": "
                                    + p.itemsJson
                                    + "}");
                        }
                    }
                    java.util.Collections.sort(chests);
                }
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
                    .append("\", \"villagers\": \"")
                    .append(String.join("; ", villagers))
                    .append("\"");
                if (CHESTS_ENABLED) {
                    sb.append(", \"chests\": [")
                        .append(String.join(", ", chests))
                        .append("]");
                }
                sb.append("}");
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

        // Biome climate/humidity sidecar, written next to the JSONL. Needs a live chunk manager for the
        // reachability section, so it rides the first seed's world rather than constructing its own; after the
        // first call it is one boolean check per seed.
        final File tableDir = new File(outPath).getAbsoluteFile()
            .getParentFile();

        final long t0 = System.nanoTime();
        int done = 0;
        try (FileWriter w = new FileWriter(outPath)) {
            for (final long seed : seeds) {
                final SeedProbeWorld world = new SeedProbeWorld(seed, rwg);
                BiomeTable.dumpOnce(tableDir, world.getWorldChunkManager());
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
        // Coverage of the hand-built chest-site table, said out loud. A piece the table does not know produces
        // no chest, which is indistinguishable in the output from a piece that genuinely has none.
        if (CHESTS_ENABLED) {
            final java.util.Set<String> skipped = VillageChestPrefilter.unpredictableSites();
            if (!skipped.isEmpty()) {
                WorldgenProbe.LOG.warn(
                    "[prefilter] {} chest sites were deliberately NOT predicted: {}",
                    skipped.size(),
                    String.join(", ", skipped));
            }
            final java.util.Set<String> unknown = VillageChestPrefilter.unknownPieces();
            if (unknown.isEmpty()) {
                WorldgenProbe.LOG.info("[prefilter] chest-site table covered every piece class seen");
            } else {
                WorldgenProbe.LOG.warn(
                    "[prefilter] {} piece classes are NOT in the chest-site table — any chests they place were "
                        + "not predicted: {}",
                    unknown.size(),
                    String.join(", ", unknown));
            }
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
            // Village starts must be read before any chunk is generated. provideChunk runs RWG's OWN village
            // map-gen, which registers starts into the shared "Village" MapGenStructureData in
            // world.perWorldStorage — and villageStarts nulls its generator's cache field, so func_143027_a
            // would reload from that shared data and emit cells this pass never asked about. Each seed gets a
            // fresh world, so the leak cannot cross seeds; this guard catches a future reordering within one.
            if (world instanceof SeedProbeWorld) {
                final VirginChunkProvider vp = ((SeedProbeWorld) world).virginProvider();
                if (vp != null && vp.generatedCount() > 0) {
                    WorldgenProbe.LOG.warn(
                        "[prefilter] seed {}: {} chunks already generated before the village pass — village "
                            + "cells may include starts registered by terrain generation",
                        seed,
                        vp.generatedCount());
                }
            }
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
        // Hoisted: the dungeon stage below scans around the predicted spawn, and defaults to the origin when
        // the terrain stage is disabled or failed rather than silently scanning somewhere arbitrary.
        int spawnX = 0, spawnZ = 0;
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
                spawnX = sx;
                spawnZ = sz;
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
                // Self-test for the chunk provider: does world.getBlock answer the same terrain the digest
                // describes? That is the whole contract — TerrainOracle.block falls through to world.getBlock
                // for a non-WorldServer, so the fix jar's virgin-terrain reads are only as good as this.
                // Run it with -Dprobe.prefilter.digestviaprovider=false for a non-circular check: the digest is
                // then computed by the hand-rolled path and the reads come from the provider.
                if (Boolean.getBoolean("probe.prefilter.selftest")) {
                    int cols = 0, mismatch = 0;
                    for (int cx2 = scx - terrainRadius; cx2 <= scx + terrainRadius; cx2++) {
                        for (int cz2 = scz - terrainRadius; cz2 <= scz + terrainRadius; cz2++) {
                            final RwgTerrain.Cols cc = terra.columns(cx2, cz2);
                            for (int s = 0; s < 4; s++) {
                                final int lx = (s & 1) * 8 + 3, lz = (s >> 1) * 8 + 3;
                                final int wx = (cx2 << 4) + lx, wz = (cz2 << 4) + lz;
                                int ts = 255;
                                while (ts > 0) {
                                    final net.minecraft.block.Block b = world.getBlock(wx, ts, wz);
                                    if (b != net.minecraft.init.Blocks.air
                                        && b.getMaterial() != net.minecraft.block.material.Material.air
                                        && b != net.minecraft.init.Blocks.water) {
                                        break;
                                    }
                                    ts--;
                                }
                                cols++;
                                if (ts != cc.topSolid[(lx << 4) | lz]) mismatch++;
                            }
                        }
                    }
                    sb.append(", \"getblock_selftest\": {\"columns\": ")
                        .append(cols)
                        .append(", \"mismatch\": ")
                        .append(mismatch)
                        .append("}");
                }
                // Chunk-cache health. regen > 0 means the LRU evicted a chunk that was needed again, so this
                // seed paid full terrain cost for it twice — a throughput bug that leaves no other trace.
                if (world instanceof SeedProbeWorld) {
                    final VirginChunkProvider vp = ((SeedProbeWorld) world).virginProvider();
                    if (vp != null) {
                        sb.append(", \"chunks_generated\": ")
                            .append(vp.generatedCount())
                            .append(", \"chunks_regenerated\": ")
                            .append(vp.regeneratedCount());
                    }
                }
            } catch (Throwable t) {
                WorldgenProbe.LOG.warn("[prefilter] terrain/spawn eval failed for {}: {}", seed, t.toString());
                sb.append(", \"terrain_error\": \"")
                    .append(
                        t.toString()
                            .replace("\"", "'"))
                    .append("\"");
            }
        }

        // --- Witchery candidate cells. Pure arithmetic plus a biome lookup: the region formula reads no blocks
        // and the handler shuffle is a function of the FML chunk seed, so this costs nothing terrain-wise.
        final int witcheryRadius = Integer.getInteger("probe.prefilter.witchery", -1);
        if (witcheryRadius >= 0) {
            final String why = WitcheryPrefilter.resolve();
            if (why != null) {
                sb.append(", \"witchery_error\": \"")
                    .append(why.replace("\"", "'"))
                    .append("\"");
            } else {
                try {
                    final List<String> cells = new ArrayList<>();
                    for (final WitcheryPrefilter.Cell c : WitcheryPrefilter
                        .candidates(world, spawnX >> 4, spawnZ >> 4, witcheryRadius)) {
                        cells.add(
                            "{\"cell\": [" + c.x
                                + ", "
                                + c.z
                                + "], \"cs\": "
                                + c.chunkSeed
                                + ", \"biome\": "
                                + c.biome
                                + ", \"allowed\": "
                                + c.allowed
                                + ", \"order\": [\""
                                + String.join("\", \"", c.order)
                                + "\"]}");
                    }
                    sb.append(", \"witchery_cells\": [")
                        .append(String.join(", ", cells))
                        .append("]");
                } catch (Throwable t) {
                    WorldgenProbe.LOG.warn("[prefilter] witchery eval failed for {}: {}", seed, t.toString());
                    sb.append(", \"witchery_error\": \"")
                        .append(
                            t.toString()
                                .replace("\"", "'"))
                        .append("\"");
                }
            }
        }

        // --- Roguelike dungeons: trigger scan is free arithmetic and acts as its own kill gate; construction is
        // the expensive terminal stage and only runs for seeds that have a trigger in range.
        // -Dprobe.prefilter.dungeon=N enables it, N = scan radius in chunks around the predicted spawn.
        final int dungeonRadius = Integer.getInteger("probe.prefilter.dungeon", -1);
        if (dungeonRadius >= 0 && RoguelikePrefilter.available()) {
            try {
                final int scx = spawnX >> 4, scz = spawnZ >> 4;
                final List<int[]> triggers = RoguelikePrefilter.triggers(world, scx, scz, dungeonRadius);
                sb.append(", \"dungeon_triggers\": ")
                    .append(triggers.size());
                if (triggers.isEmpty() && Boolean.getBoolean("probe.prefilter.gate.dungeon")) {
                    return "{\"seed\": " + seed + ", \"kill\": \"dungeon\"}";
                }
                final List<String> dungeons = new ArrayList<>();
                for (final int[] t : triggers) {
                    final RoguelikePrefilter.Result r = RoguelikePrefilter.generate(world, seed, t[0], t[1]);
                    final StringBuilder d = new StringBuilder(128);
                    d.append("{\"trigger\": [")
                        .append(r.triggerX)
                        .append(", ")
                        .append(r.triggerZ)
                        .append("], \"chests\": [")
                        .append(String.join(", ", r.chests))
                        .append("]");
                    if (r.error != null) {
                        d.append(", \"error\": \"")
                            .append(r.error.replace("\"", "'"))
                            .append("\"");
                    }
                    d.append("}");
                    dungeons.add(d.toString());
                }
                sb.append(", \"dungeons\": [")
                    .append(String.join(", ", dungeons))
                    .append("]");
            } catch (Throwable t) {
                WorldgenProbe.LOG.warn("[prefilter] dungeon eval failed for {}: {}", seed, t.toString());
                sb.append(", \"dungeon_error\": \"")
                    .append(
                        t.toString()
                            .replace("\"", "'"))
                    .append("\"");
            } finally {
                RoguelikePrefilter.resetSliceWindow();
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
