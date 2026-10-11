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
 * [-Dprobe.prefilter.slimehouse=N (TiC slime island x Roguelike tower coincidence scan, radius in chunks
 * around the ORIGIN; -1 off. .band=128 caps the estimated island yCenter; .rarity=8000 is the fallback
 * when TCon's PHConstruct is absent)]
 * [-Dprobe.prefilter.gate.slimehouse=band|house (kill seeds without a band island / without a HOUSE
 * tower entrance under an island ellipse; kill rows carry the counts and any non-HOUSE hits)]
 * [-Dprobe.prefilter.cliffhouse=R (Roguelike HOUSE towers on virgin plateau edges: place-only dungeons for
 * triggers in R chunks around origin, score = entrance floor minus min topSolid within +/-16 blocks)]
 * [-Dprobe.prefilter.gate.cliffhouse=D (kill seeds whose best HOUSE drop is below D)]
 * [-Dprobe.prefilter.gate.cliffstair=L (kill seeds whose best HOUSE front-staircase length is below L)]
 * [-Dprobe.prefilter.gate.cliffvis=V (kill seeds whose best staircase has fewer than V visible brick blocks —
 * the 3-wide curtain above the virgin surface, i.e. staircase-off-a-cliff, not hugging a slope)]
 * [-Dprobe.prefilter.witchery=N (Witchery candidate-cell scan radius in chunks around predicted spawn; -1 off)]
 * [-Dprobe.prefilter.stronghold=N (stronghold radius in chunks around predicted spawn; -1 off)]
 * [-Dprobe.prefilter.oil=N (BuildCraft oil scan radius in chunks around predicted spawn; -1 off)]
 * [-Dprobe.prefilter.chests=true (predict chest contents; alias -Dprobe.prefilter.villagechests)]
 * [-Dprobe.prefilter.biomeregion=N (no-rain region scan radius in chunks around predicted spawn; -1 off)]
 * [-Dprobe.prefilter.biomeregion.min=5 (minimum square side, in chunks)]
 * [-Dprobe.prefilter.biomeregion.humidity=14 (crop humidity bonus a chunk needs to count as humid)]
 * [-Dprobe.prefilter.biomeregion.confirm=-1 (Tier B chunk budget per seed; -1 = whole window/exact, 0 = off)]
 * [-Dprobe.prefilter.gate.biomeregion=SIDE[,MAXGAP] (kill seeds without such a region)]
 *
 * Hammer%-hunt modules (2026-09-13):
 * [-Dprobe.prefilter.mineshaft=N (mineshaft trigger scan radius in chunks around the ORIGIN; -1 off.
 * Triggers are pure arithmetic; starts are dispatched pre-chunkgen like villages)]
 * [-Dprobe.prefilter.gate.mineshafttrig=Rg (kill seeds with no mineshaft trigger within Rg chunks of origin)]
 * [-Dprobe.prefilter.gate.roguetrig=Rg (kill seeds with no Roguelike trigger within Rg chunks of origin —
 * the worldless pre-gate; the spawn-centred dungeon stage still re-checks exactly)]
 * [-Dprobe.prefilter.mineshaftloot=true (replay corridor minecart-chest loot per populate window against the
 * overlay; needs mineshaft radius >= 0. Chest contents/counts are exact under the fix jar's F10 fork;
 * existence rides the MineshaftCorridorChestMixin pin — see MineshaftPrefilter)]
 * [-Dprobe.prefilter.gate.steel=N[,DIST] (kill seeds whose Block of Steel total — predicted village chests
 * plus mineshaft minecarts within DIST blocks of spawn, default 320 — is below N. Kill rows carry the
 * counts so bars tune without re-sweeping)]
 * [-Dprobe.prefilter.gate.toolloot=alumite=A,plate=P,hammer=H,rod=R[,dist=D] (kill seeds whose Roguelike
 * TOOLS-chest aggregate within D blocks of spawn, default 320, misses any minimum: Alumite Large Plates,
 * total Large Plates, Hammer Heads, Tough Rods. Alumite = Obzinite; server strings say Alumite)]
 *
 * Meteor-crater hunt module (2026-09-13):
 * [-Dprobe.prefilter.meteor=C (AE2 meteorite scan, C grid cells — minMeteoriteDistance, 707 here — around
 * the origin cell; -1 off. Candidates are pure (seed, cell) arithmetic through the real
 * Platform.seedFromGrid; terrain runs only past the size gate — see MeteorPrefilter)]
 * [-Dprobe.prefilter.gate.meteorsize=7.8 (candidate filter, not a seed kill: skip terrain for size rolls
 * below this; 0 emits every cell)]
 * [-Dprobe.prefilter.gate.meteorrelief=20 (mark candidates whose 3x3-chunk relief above the impact surface
 * is below this as reject=flat, skipping the volume disc)]
 * [-Dprobe.prefilter.meteor.volradius=96 (volume-scoring disc radius in blocks around the impact)]
 * [-Dprobe.prefilter.gate.meteorproxy=P (analytic noise screen: reject candidates whose rNoise ring proxy
 * relief is below P, BEFORE any chunk generates. 0 = annotate only. Calibrate on known hits first —
 * the proxy skips biome blending, so its scale is not the digest relief scale)]
 * [-Dprobe.prefilter.gate.meteorbiome=tok1,tok2 (worldless ring gate: reject candidates unless the impact
 * or an 8-point ring at 56 blocks lies in a biome whose normalized name contains a token — e.g.
 * snowforest,coldplains,coldforest,temperateforest for RWG's clamp-150/155 wall terrain; empty = off)]
 * [-Dprobe.prefilter.gate.meteorvol=V (kill seeds whose best carved volume is below V; kill rows carry the
 * candidates so bars tune without re-sweeping)]
 *
 * Block reads (2026-08-29): {@link VirginChunkProvider} backs the world, so {@code world.getBlock} and
 * {@code getBlockMetadata} answer virgin terrain. That is what {@code TerrainOracle} falls through to for a
 * non-WorldServer, so the fix jar's virgin-terrain consumers — Roguelike validLocation, the GT vein reroll gate —
 * become evaluable here. Verified 64800 columns / 200 seeds with zero mismatches against the independently
 * computed digest; see results/2026-08-29-virgin-chunk-provider.
 */
public final class Prefilter {

    /** Minimal seed-bearing World: enough construction to satisfy RWG's chunk manager and world type wiring. */
    /**
     * Per-stage wall-clock accumulators, enabled with {@code -Dprobe.prefilter.timing=true}.
     *
     * <p>
     * There was no per-stage timing anywhere before this: the only clock reads were the batch start and end, and
     * the {@code seeds/s} line truncates to an int, so it prints "0 seeds/s" for any run slower than 1 s/seed.
     * Every cost attribution in the docs was therefore inferred by differencing whole runs, which is how the
     * claim "dungeons generate no extra chunks" survived — {@code chunks_generated} is emitted before the dungeon
     * stage, so differencing two runs on it silently compares the wrong thing.
     *
     * <p>
     * Off by default and branch-only when off, so a normal sweep pays a predictable-branch per call.
     */
    /** Skip the skylight pass on prefilter chunks. Off by default — see SkylightMixin for why. */
    public static final boolean SKIP_SKYLIGHT = Boolean.getBoolean("probe.prefilter.skipskylight");

    public static final class Timing {

        public static final boolean ON = Boolean.getBoolean("probe.prefilter.timing");
        private static final Map<String, long[]> BUCKETS = new java.util.LinkedHashMap<>();

        private Timing() {}

        public static long start() {
            return ON ? System.nanoTime() : 0L;
        }

        /** Accumulate nanos since {@code t} and count one call. */
        public static synchronized void add(String name, long t) {
            if (!ON) return;
            final long[] b = BUCKETS.computeIfAbsent(name, k -> new long[2]);
            b[0] += System.nanoTime() - t;
            b[1]++;
        }

        /** Add a precomputed duration (for attributing nested time to an outer stage). */
        public static synchronized void addRaw(String name, long nanos) {
            if (!ON) return;
            final long[] b = BUCKETS.computeIfAbsent(name, k -> new long[2]);
            b[0] += nanos;
            b[1]++;
        }

        /** Count an event with no duration (cache hits, iteration counts). */
        public static synchronized void hit(String name) {
            if (!ON) return;
            BUCKETS.computeIfAbsent(name, k -> new long[2])[1]++;
        }

        /** Nanos accumulated in a bucket so far — lets a stage attribute nested time to itself. */
        public static synchronized long nanos(String name) {
            final long[] b = BUCKETS.get(name);
            return b == null ? 0L : b[0];
        }

        static synchronized void report(int seeds, double totalSecs) {
            if (!ON || BUCKETS.isEmpty()) return;
            WorldgenProbe.LOG.info("[prefilter][timing] {} seeds, {} s wall", seeds, String.format("%.1f", totalSecs));
            final double perSeedMs = totalSecs * 1000.0 / Math.max(seeds, 1);
            WorldgenProbe.LOG.info("[prefilter][timing] {} ms/seed measured", String.format("%.1f", perSeedMs));
            for (final Map.Entry<String, long[]> e : BUCKETS.entrySet()) {
                final long nanos = e.getValue()[0];
                final long calls = e.getValue()[1];
                final double ms = nanos / 1e6;
                WorldgenProbe.LOG.info(
                    "[prefilter][timing]   {} : {} ms total, {} ms/seed, {}% of wall, {} calls, {} us/call",
                    String.format("%-22s", e.getKey()),
                    String.format("%9.1f", ms),
                    String.format("%7.2f", ms / Math.max(seeds, 1)),
                    String.format("%5.1f", 100.0 * ms / Math.max(totalSecs * 1000.0, 1e-9)),
                    String.format("%9d", calls),
                    calls == 0 ? "-" : String.format("%.1f", nanos / 1000.0 / calls));
            }
        }
    }

    public static final class SeedProbeWorld extends World {

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
            if (overlay == null) return refuseWrite("setBlock", x, y, z);
            final long k = okey(x, y, z);
            overlay.put(k, new int[] { net.minecraft.block.Block.getIdFromBlock(block), meta });
            // The real World builds the tile entity as part of the write; generation code then asks for it back
            // and fills it (this is how a dispenser gets its loot), so an overlay that skipped this would hand
            // back null and the structure would silently place an empty container.
            if (block != null && block.hasTileEntity(meta)) {
                final net.minecraft.tileentity.TileEntity te = block.createTileEntity(this, meta);
                if (te != null) {
                    te.setWorldObj(this);
                    te.xCoord = x;
                    te.yCoord = y;
                    te.zCoord = z;
                    te.blockType = block;
                    te.blockMetadata = meta;
                    overlayTe.put(k, te);
                }
            } else {
                overlayTe.remove(k);
            }
            return true;
        }

        @Override
        public boolean setBlockMetadataWithNotify(int x, int y, int z, int meta, int flags) {
            if (overlay == null) return refuseWrite("setBlockMetadataWithNotify", x, y, z);
            final int[] w = overlay.get(okey(x, y, z));
            if (w == null) return false;
            w[1] = meta;
            return true;
        }

        @Override
        public void setTileEntity(int x, int y, int z, net.minecraft.tileentity.TileEntity te) {
            if (overlay == null) {
                refuseWrite("setTileEntity", x, y, z);
                return;
            }
            if (te != null) {
                te.setWorldObj(this);
                te.xCoord = x;
                te.yCoord = y;
                te.zCoord = z;
            }
            overlayTe.put(okey(x, y, z), te);
        }

        // ------------------------------------------------------------------ scratch overlay
        //
        // Witchery's surface structures decide whether they place by CALLING generate(), which reads terrain and
        // then writes blocks. The read half is exactly what this world already answers; only the write half is
        // forbidden, because the cached chunks ARE the virgin-terrain oracle.
        //
        // So writes are diverted into a discardable overlay instead of being refused: reads answer own-writes
        // first and virgin terrain everywhere else, which is the same overlay semantics WorldEditorMixin gives
        // Roguelike (F5). The oracle is never touched, and the structure sees a coherent world.
        //
        // Scoped, not global: outside beginOverlay()/endOverlay() every write is refused exactly as before, so
        // no other consumer of this world can be affected by code that never runs for it.

        private Map<Long, int[]> overlay;
        private Map<Long, net.minecraft.tileentity.TileEntity> overlayTe;
        private List<Entity> overlayEntities;

        private static long okey(int x, int y, int z) {
            return ((long) (x + 30_000_000) << 36) | ((long) (z + 30_000_000) << 8) | (y & 0xFF);
        }

        void beginOverlay() {
            overlay = new java.util.HashMap<>();
            overlayTe = new java.util.HashMap<>();
            overlayEntities = new ArrayList<>();
        }

        /**
         * Entities spawned while the overlay was live, in spawn order. Read BEFORE {@link #endOverlay()}. This
         * is how mineshaft corridor loot becomes visible at all: it lives in {@code EntityMinecartChest}, not a
         * tile entity, and {@link #spawnEntityInWorld} used to drop it on the floor.
         */
        List<Entity> overlayEntities() {
            return overlayEntities == null ? java.util.Collections.emptyList() : overlayEntities;
        }

        /** The block writes the overlay has captured so far. Read BEFORE {@link #endOverlay()}. */
        Map<Long, int[]> overlayBlocks() {
            return overlay == null ? java.util.Collections.emptyMap() : overlay;
        }

        /** Decodes a key from {@link #overlayBlocks()} back into world coordinates. */
        static int[] okeyToPos(long k) {
            final int y = (int) (k & 0xFF);
            final int z = (int) ((k >> 8) & 0xFFFFFFFL) - 30_000_000;
            final int x = (int) ((k >> 36) & 0xFFFFFFFL) - 30_000_000;
            return new int[] { x, y, z };
        }

        /** Everything the overlay captured, then discards it. */
        Map<Long, net.minecraft.tileentity.TileEntity> endOverlay() {
            final Map<Long, net.minecraft.tileentity.TileEntity> te = overlayTe;
            overlay = null;
            overlayTe = null;
            overlayEntities = null;
            return te == null ? java.util.Collections.emptyMap() : te;
        }

        @Override
        public net.minecraft.block.Block getBlock(int x, int y, int z) {
            if (overlay != null) {
                final int[] w = overlay.get(okey(x, y, z));
                if (w != null) return net.minecraft.block.Block.getBlockById(w[0]);
            }
            return super.getBlock(x, y, z);
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            if (overlay != null) {
                final int[] w = overlay.get(okey(x, y, z));
                if (w != null) return w[1];
            }
            return super.getBlockMetadata(x, y, z);
        }

        @Override
        public net.minecraft.tileentity.TileEntity getTileEntity(int x, int y, int z) {
            if (overlay != null) return overlayTe.get(okey(x, y, z));
            return super.getTileEntity(x, y, z);
        }

        @Override
        public void removeTileEntity(int x, int y, int z) {
            if (overlay != null) {
                overlayTe.remove(okey(x, y, z));
                return;
            }
            super.removeTileEntity(x, y, z);
        }

        @Override
        public boolean spawnEntityInWorld(Entity e) {
            // During an overlay the entity is CAPTURED, not dropped: mineshaft corridor loot is an
            // EntityMinecartChest, and swallowing it silently was exactly how ~8% of lootable containers stayed
            // invisible to every prefilter row (see the KNOWN GAP note in WorldgenProbe.buildSearchReport).
            // Outside an overlay the old refusal stands — a worldless World maintains no entity lists.
            if (overlay != null) {
                overlayEntities.add(e);
                return true;
            }
            return super.spawnEntityInWorld(e);
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

        /** Package-private: {@link BiomeRegionPrefilter} calls getNewNoise on this same instance. */
        IChunkProvider generator() {
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
            final long t = Timing.start();
            try {
                final Chunk c = generator().provideChunk(cx, cz);
                cache.put(k, c);
                generated++;
                if (!seen.add(k)) regenerated++;
                return c;
            } finally {
                generating.remove(k);
                Timing.add("chunkgen", t);
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

        /** The reflected ChunkGeneratorRealistic — MeteorPrefilter's analytic screen reads its noise fields. */
        Object generator() {
            return gen;
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

    /** Package-private: {@link BiomeRegionPrefilter} reaches RWG's noise methods through it. */
    static java.lang.reflect.Method findMethod(Class<?> cls, String[] names, Class<?>... params)
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

    /**
     * Master switch for chest-contents prediction (Stage 4). It drives every structure that predicts through
     * {@link VillageChestPrefilter} — villages and strongholds — so the original village-specific name is kept
     * only as an alias for existing scripts.
     */
    private static final boolean CHESTS_ENABLED = Boolean.getBoolean("probe.prefilter.chests")
        || Boolean.getBoolean("probe.prefilter.villagechests");

    /**
     * One chest as JSON. A refused chest carries {@code "reason"} and no {@code "chest"} payload, so a consumer
     * that only reads {@code "chest"} cannot mistake a known position for known contents.
     */
    private static String chestJson(VillageChestPrefilter.Predicted p) {
        final StringBuilder b = new StringBuilder();
        b.append("{\"piece\": \"")
            .append(p.piece)
            .append("\", ");
        // A null category means the site's recorded table is not trustworthy, not that none was recorded.
        if (p.category != null) {
            b.append("\"category\": \"")
                .append(p.category)
                .append("\", ");
        }
        if (p.reason == null) {
            b.append("\"chest\": ")
                .append(p.itemsJson);
        } else {
            b.append("\"pos\": [")
                .append(p.x)
                .append(", ")
                .append(p.y)
                .append(", ")
                .append(p.z)
                .append("], \"reason\": \"")
                .append(WorldgenProbe.jsonEscape(p.reason))
                .append("\"");
        }
        return b.append("}")
            .toString();
    }

    /**
     * One dungeon construction with the stage's timing attribution, shared by the near-first gate
     * pass and the fill pass so the two cannot drift in what they measure.
     */
    // A cheap "can this trigger hold an enchanting table?" biome gate was implemented here and REMOVED as
    // unsound. The static reasoning is correct — only DungeonsEnchant writes BlockType.ENCHANTING_TABLE,
    // and DungeonRoom.ENCHANT is reachable only from dungeon_mountain.json (criteria biomeTypes
    // [MOUNTAIN, HILLS]) and the builtin SettingsMountainTheme (Type.MOUNTAIN) — but the biome LOOKUP is
    // not answerable before generation. SpawnCriteria reads World.getBiomeGenForCoords, which returns the
    // chunk's stored biome array once the chunk exists and falls back to the WorldChunkManager when it
    // does not; under RWG those disagree, because RWG decides the block-level biome while generating.
    // Measured: the gate killed 9 of 31 seeds (29%) that demonstrably hold a table within 300 blocks.
    // Both biome entry points (bulk getBiomeGenAt and per-block getBiomeGenForCoords) gave the identical
    // wrong answer, so this is structural, not an API slip. It is the same shape as the TF vein winner:
    // the predicate depends on terrain the worldless prefilter has not produced yet. Reviving it needs
    // the biome to be settled pre-generation, not a wider margin.
    /**
     * Columns to resolve per seed in surface-Y mode, loaded from {@code -Dprobe.prefilter.surfacey=<file>}.
     * File format is one {@code seed x z} triple per line; output is one row per seed carrying every
     * requested column's virgin top-solid height.
     *
     * <p>
     * This exists because the sweep emits a LITERAL 64 for spawn and nothing at all for villages and biome
     * squares, so those coordinates teleport underground. It is a separate mode rather than a change to
     * {@code evaluate} on purpose: the 415k corpus stays byte-comparable, and annotating it costs a pass
     * over the ~2.5k survivors instead of a re-sweep, since a column's height is a function of
     * {@code (seed, x, z)} alone and independent of everything else the sweep computed.
     *
     * <p>
     * The height is the VIRGIN TOP-SOLID BLOCK, so a caller who wants a coordinate to STAND on must add
     * 1. Reporting the raw value as a teleport target puts the player inside the ground.
     *
     * <p>
     * Accuracy, measured in-game against seed -1636594104014467454 (user field report 2026-09-04), with
     * the +1 applied:
     * <ul>
     * <li>bare terrain — EXACT: spawn 65, coven circle 65, enchanting table 41, no-rain square centre 82;
     * <li>decorated or built-on columns — 3 to 4 LOW: village centre predicted 69, actual 73 (the village
     * grades its own floor and paths above virgin ground); humid square centre predicted 60, actual 63
     * (canopy/snow above the virgin surface).
     * </ul>
     * So this is exact where nothing has been built or grown on the column and reads a few blocks low
     * where something has. That is a floor, not an estimate: decoration only ever adds. Do not quote the
     * older "1-2 blocks" figure — it was a guess and the measurement is worse than it.
     */
    private static final Map<Long, List<int[]>> SURFACE_Y = loadSurfaceYRequests();

    private static Map<Long, List<int[]>> loadSurfaceYRequests() {
        final String path = System.getProperty("probe.prefilter.surfacey");
        if (path == null || path.isEmpty()) return null;
        final Map<Long, List<int[]>> out = new java.util.LinkedHashMap<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                final String[] f = line.split("\\s+");
                out.computeIfAbsent(Long.parseLong(f[0]), k -> new ArrayList<>())
                    .add(new int[] { Integer.parseInt(f[1]), Integer.parseInt(f[2]) });
            }
        } catch (Exception e) {
            throw new RuntimeException("cannot read probe.prefilter.surfacey file " + path, e);
        }
        WorldgenProbe.LOG.info("[prefilter] SURFACE-Y mode: {} seeds requested from {}", out.size(), path);
        return out;
    }

    private static String evaluateSurfaceY(World world, long seed) {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("{\"seed\": ")
            .append(seed);
        final List<int[]> want = SURFACE_Y.get(seed);
        if (want == null || want.isEmpty()) {
            return sb.append(", \"y\": []}")
                .toString();
        }
        try {
            final RwgTerrain terra = new RwgTerrain(world);
            sb.append(", \"y\": [");
            boolean first = true;
            for (final int[] c : want) {
                final RwgTerrain.Cols cols = terra.columns(c[0] >> 4, c[1] >> 4);
                final int y = cols.topSolid[((c[0] & 15) << 4) | (c[1] & 15)];
                if (!first) sb.append(", ");
                first = false;
                sb.append("[")
                    .append(c[0])
                    .append(", ")
                    .append(y)
                    .append(", ")
                    .append(c[1])
                    .append("]");
            }
            sb.append("]");
        } catch (Throwable t) {
            WorldgenProbe.LOG.warn("[prefilter] surface-Y failed for {}: {}", seed, t.toString());
            sb.append(", \"error\": \"")
                .append(
                    t.toString()
                        .replace("\"", "'"))
                .append("\"");
        }
        return sb.append("}")
            .toString();
    }

    /** Roguelike's {@code WorldEditor.validGroundBlock} material rules, applied to a virgin block. */
    private static boolean gtnhdet$roguelikeGround(net.minecraft.block.Block b) {
        if (b == null || b == net.minecraft.init.Blocks.air) return false;
        final net.minecraft.block.material.Material m = b.getMaterial();
        return m != net.minecraft.block.material.Material.grass && m != net.minecraft.block.material.Material.snow
            && m != net.minecraft.block.material.Material.plants
            && m != net.minecraft.block.material.Material.leaves
            && m != net.minecraft.block.material.Material.wood
            && m != net.minecraft.block.material.Material.cactus
            && m != net.minecraft.block.material.Material.gourd
            && m != net.minecraft.block.material.Material.water;
    }

    /**
     * TCon's live {@code PHConstruct.islandRarity} (config-loaded: GTNH ships 8000, TCon default 1450),
     * falling back to {@code -Dprobe.prefilter.slimehouse.rarity} when TCon is absent from the pack.
     */
    private static int slimeIslandRarity() {
        try {
            return Class.forName("tconstruct.util.config.PHConstruct")
                .getField("islandRarity")
                .getInt(null);
        } catch (Throwable absent) {
            return Integer.getInteger("probe.prefilter.slimehouse.rarity", 8000);
        }
    }

    private static RoguelikePrefilter.Result generateDungeonTimed(World world, long seed, int[] t) {
        final long tGen = Timing.start();
        final long cg0 = Timing.nanos("chunkgen");
        final RoguelikePrefilter.Result r = RoguelikePrefilter.generate(world, seed, t[0], t[1]);
        Timing.add("dungeon.generate", tGen);
        if (Timing.ON) Timing.addRaw("dungeon.chunkgen", Timing.nanos("chunkgen") - cg0);
        return r;
    }

    private static List<String> villageStarts(Object gen, World world, List<int[]> cells, List<int[]> steelOut)
        throws Exception {
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
                final List<String> chestsUnpredicted = new ArrayList<>();
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
                            (p.reason == null ? chests : chestsUnpredicted).add(chestJson(p));
                            // Steel for the hammer% gate, collected while the rolled counts are in hand.
                            // Chest XZ is exact (Y is not, and the gate never uses it).
                            if (steelOut != null && p.counts != null && p.counts[LootCounts.STEEL] > 0) {
                                steelOut.add(new int[] { p.x, p.z, p.counts[LootCounts.STEEL] });
                            }
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
                        .append("], \"chests_unpredicted\": [")
                        .append(String.join(", ", chestsUnpredicted))
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

    private static final java.util.Set<String> WARNED_ONCE = new java.util.HashSet<>();

    /** A misconfiguration that silently degrades a gate must be said once, not per seed and not never. */
    private static synchronized void warnOnce(String msg) {
        if (WARNED_ONCE.add(msg)) WorldgenProbe.LOG.warn("[prefilter] {}", msg);
    }

    private static boolean biomeRegionCostWarned;

    /**
     * Say out loud when the biome region stage is about to pay for chunks twice.
     *
     * <p>
     * The stage reads generated chunks, so its cost is entirely whether those chunks are already in
     * {@link VirginChunkProvider}'s LRU. Measured on 24 seeds: with the window inside the terrain radius and
     * the cache big enough to hold it, the stage costs 4 ms/seed and regenerates nothing. Widen the window
     * past the terrain radius, or leave the cache smaller than the window, and the same configuration costs
     * 1.6 s/seed — a 400x swing with no error and no warning, which is exactly the kind of silent cost this
     * has to announce rather than absorb.
     */
    private static void warnBiomeRegionCost(int biomeRadius) {
        if (biomeRegionCostWarned) return;
        biomeRegionCostWarned = true;
        final int terrainRadius = Integer.getInteger("probe.prefilter.terrain", 4);
        final int cache = Integer.getInteger("probe.prefilter.chunkcache", 256);
        final int window = (2 * biomeRadius + 1) * (2 * biomeRadius + 1);
        if (window > cache) {
            WorldgenProbe.LOG.warn(
                "[biomeregion] radius {} needs {} chunks but probe.prefilter.chunkcache is {} — the window will "
                    + "thrash the LRU and regenerate chunks. Raise -Dprobe.prefilter.chunkcache to >= {}.",
                biomeRadius,
                window,
                cache,
                window);
        }
        if (terrainRadius >= 0 && biomeRadius > terrainRadius) {
            WorldgenProbe.LOG.warn(
                "[biomeregion] radius {} is wider than the terrain radius {}, so {} chunks are generated for this "
                    + "stage alone (~2 ms each). Keeping it <= the terrain radius makes the stage almost free.",
                biomeRadius,
                terrainRadius,
                window - (2 * terrainRadius + 1) * (2 * terrainRadius + 1));
        }
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
            final int tier1 = Integer.getInteger("probe.prefilter.tier1", -1);
            if (tier1 >= 0) {
                WorldgenProbe.LOG.info(
                    "[prefilter] TIER 1: Roguelike trigger count only, {} chunks around the ORIGIN, "
                        + "no chunk generation",
                    tier1);
            }
            for (final long seed : seeds) {
                final SeedProbeWorld world = new SeedProbeWorld(seed, rwg);
                if (tier1 < 0) BiomeTable.dumpOnce(tableDir, world.getWorldChunkManager());
                if (tier1 < 0) OreVeinTableDump.dumpOnce(tableDir, seed);
                final long tSeed = Timing.start();
                w.write(
                    SURFACE_Y != null ? evaluateSurfaceY(world, seed)
                        : tier1 >= 0 ? evaluateTier1(world, seed, tier1) : evaluate(world, seed, radiusChunks));
                Timing.add("seed.total", tSeed);
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
        Timing.report(done, secs);
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

        // --- Hammer% worldless pre-gates (-Dprobe.prefilter.mineshaft=R chunks around the ORIGIN; -1 off).
        // Mineshaft and Roguelike triggers are pure (seed, chunk) arithmetic — no chunk generation, no
        // generator state — so they sit before everything and kill for effectively nothing. Origin-centred
        // with slack, exactly like tier 1: spawn is median ~104 blocks from origin (p90 478), and the gated
        // stages below re-check every distance against the predicted spawn exactly.
        final int mineshaftRadius = Integer.getInteger("probe.prefilter.mineshaft", -1);
        List<int[]> mineshaftTriggers = java.util.Collections.emptyList();
        if (mineshaftRadius >= 0 && MineshaftPrefilter.resolve() == null && MineshaftPrefilter.arithmetic()) {
            final long tMsTrig = Timing.start();
            mineshaftTriggers = MineshaftPrefilter.triggers(seed, 0, 0, mineshaftRadius);
            Timing.add("mineshaft.trig", tMsTrig);
            final int gateMsTrig = Integer.getInteger("probe.prefilter.gate.mineshafttrig", -1);
            if (gateMsTrig >= 0) {
                boolean ok = false;
                for (final int[] t : mineshaftTriggers) {
                    if (Math.max(Math.abs(t[0]), Math.abs(t[1])) <= gateMsTrig) {
                        ok = true;
                        break;
                    }
                }
                if (!ok) return "{\"seed\": " + seed + ", \"kill\": \"mineshafttrig\"}";
            }
        }
        final int gateRogueTrig = Integer.getInteger("probe.prefilter.gate.roguetrig", -1);
        if (gateRogueTrig >= 0 && RoguelikePrefilter.available()) {
            final long tRogueTrig = Timing.start();
            final boolean ok = !RoguelikePrefilter.triggers(world, 0, 0, gateRogueTrig)
                .isEmpty();
            Timing.add("roguetrig", tRogueTrig);
            if (!ok) return "{\"seed\": " + seed + ", \"kill\": \"roguetrig\"}";
        }

        // --- villages: ask the REAL village generator (incl. InitMapGenEvent replacements like
        // VillageNames' MapGenVillageVN) — zero reimplementation drift; spacing/salt/biome gates are whatever
        // the pack actually runs.
        final List<int[]> villages = new ArrayList<>();
        final List<String> starts = new ArrayList<>();
        // Predicted village chests holding Block of Steel: {chestX, chestZ, steel}. Filled by villageStarts
        // while the Predicted objects are in hand, distance-filtered by the steel gate once spawn is known.
        final List<int[]> villageSteel = new ArrayList<>();
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
            final long tCells = Timing.start();
            for (int cx2 = -radiusChunks; cx2 <= radiusChunks; cx2++) {
                for (int cz2 = -radiusChunks; cz2 <= radiusChunks; cz2++) {
                    if ((Boolean) CAN_SPAWN.invoke(gen, cx2, cz2)) {
                        villages.add(new int[] { cx2, cz2 });
                    }
                }
            }
            Timing.add("village.cellscan", tCells);
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
                final long tStarts = Timing.start();
                starts.addAll(villageStarts(gen, world, villages, villageSteel));
                Timing.add("villageStarts", tStarts);
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

        // --- Mineshaft starts. Must run BEFORE any chunk generation for the same reason the village pass
        // does: provideChunk runs RWG's OWN mineshaft mapgen, which registers starts into the shared
        // "Mineshaft" per-world storage that func_143027_a would reload into this pass's structureMap.
        // Start construction is rand-only (StructureMineshaftStart / markAvailableHeight make no world
        // reads), so this generates zero chunks. The loot sim runs later, once spawn is known.
        List<MineshaftPrefilter.Start> msStarts = java.util.Collections.emptyList();
        if (mineshaftRadius >= 0 && MineshaftPrefilter.resolve() == null) {
            try {
                final long tMsStarts = Timing.start();
                final List<int[]> msCells = MineshaftPrefilter.arithmetic() ? mineshaftTriggers
                    : MineshaftPrefilter.latticeCells(0, 0, mineshaftRadius);
                msStarts = MineshaftPrefilter.starts(world, seed, msCells);
                Timing.add("mineshaft.starts", tMsStarts);
                sb.append(", \"mineshaft_triggers\": [");
                for (int i = 0; i < mineshaftTriggers.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("[")
                        .append(mineshaftTriggers.get(i)[0])
                        .append(", ")
                        .append(mineshaftTriggers.get(i)[1])
                        .append("]");
                }
                sb.append("], \"mineshaft_starts\": [");
                for (int i = 0; i < msStarts.size(); i++) {
                    final MineshaftPrefilter.Start s = msStarts.get(i);
                    if (i > 0) sb.append(", ");
                    sb.append("{\"c\": [")
                        .append(s.cx)
                        .append(", ")
                        .append(s.cz)
                        .append("], \"bbox\": [")
                        .append(s.bbox[0])
                        .append(", ")
                        .append(s.bbox[1])
                        .append(", ")
                        .append(s.bbox[2])
                        .append(", ")
                        .append(s.bbox[3])
                        .append(", ")
                        .append(s.bbox[4])
                        .append(", ")
                        .append(s.bbox[5])
                        .append("], \"pieces\": ")
                        .append(s.pieces)
                        .append(", \"corridors\": ")
                        .append(s.corridors)
                        .append(", \"chest_candidates\": ")
                        .append(s.chestCandidates)
                        .append("}");
                }
                sb.append("]");
            } catch (Throwable t) {
                WorldgenProbe.LOG.warn("[prefilter] mineshaft starts failed for {}: {}", seed, t.toString());
                sb.append(", \"mineshaft_error\": \"")
                    .append(
                        t.toString()
                            .replace("\"", "'"))
                    .append("\"");
            }
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

        // --- Slime-island x Roguelike-tower coincidence (-Dprobe.prefilter.slimehouse=R chunks around the
        // origin; -1 off). Finds TiC slime islands whose body dips into Tower.getBaseCoord's 128->60 live
        // ground scan AND a Roguelike dungeon entrance column under the island ellipse — the "brick house on
        // a slime island" shot (HOUSE tower = forest settings; ThemeHouse primary walls are BRICK; slime dirt
        // is Material.ground, so the tower's base scan accepts it while the fix jar's virgin validLocation
        // vetted the real terrain 50+ blocks below). Island rolls are pure (seed, chunk) arithmetic: FML
        // reseeds per IWorldGenerator (RoguelikePrefilter.chunkSeed), and with the fix jar's
        // SlimeIslandGenMixin the generateIsland draws are xRange=nextInt(13)+20,
        // yCenter=50+getHeightValue+nextInt(50), zRange=nextInt(13)+20, after generate()'s nextInt(rarity)
        // gate. Live getHeightValue is approximated by virgin topSolid+1 — a floor, since decoration only
        // adds — so the yCenter estimate reads low and the band keep is conservative; full-gen verification
        // culls canopy-raised false keeps. Placed after the village pass (RwgTerrain and dungeon
        // construction generate chunks, which would poison the village cell scan) and before the terrain
        // stage so the kill gates spare everything downstream. Dungeon writes land in prefilter chunks like
        // the late dungeon stage's do; a sweep normally runs this stage with terrain=-1.
        final int slimeRadius = Integer.getInteger("probe.prefilter.slimehouse", -1);
        if (slimeRadius >= 0 && RoguelikePrefilter.available()) {
            final long tSlime = Timing.start();
            try {
                final int rarity = slimeIslandRarity();
                final int bandMax = Integer.getInteger("probe.prefilter.slimehouse.band", 128);
                final String slimeGate = System.getProperty("probe.prefilter.gate.slimehouse", "");
                // fmlRandom prologue hoisted per seed; `>> 2 + 1L` is Forge's precedence quirk (= >> 3),
                // written Forge's way so nobody "corrects" it into a different world.
                final Random fml = new Random(seed);
                final long fmlXSeed = fml.nextLong() >> 2 + 1L;
                final long fmlZSeed = fml.nextLong() >> 2 + 1L;
                final Random islRoll = new Random(0);
                final List<int[]> islands = new ArrayList<>(); // {cx, cz, xRange, zRange, r50}
                for (int cx2 = -slimeRadius; cx2 <= slimeRadius; cx2++) {
                    for (int cz2 = -slimeRadius; cz2 <= slimeRadius; cz2++) {
                        islRoll.setSeed((fmlXSeed * cx2 + fmlZSeed * cz2) ^ seed);
                        if (islRoll.nextInt(rarity) != 0) continue;
                        final int xr = islRoll.nextInt(13) + 20;
                        final int r50 = islRoll.nextInt(50);
                        final int zr = islRoll.nextInt(13) + 20;
                        islands.add(new int[] { cx2, cz2, xr, zr, r50 });
                    }
                }
                // Y-band: island body spans [yCenter, yCenter+12]; the tower scan starts at 128, so any
                // yCenter <= bandMax(128) puts slime dirt in the scan's path (on top of the island for
                // yCenter < 116, carved into its top above that).
                RwgTerrain terraSlime = null;
                final List<int[]> band = new ArrayList<>(); // {island..., h, yEst}
                for (final int[] isl : islands) {
                    // r50 pre-cut before paying a chunk gen for the height: dungeon-viable land sits at
                    // h >= ~55 (validLocation's ground scan floors at lowerLimit 60; 5 blocks of slack for
                    // island-corner vs tower-column relief), so islands whose 50+55+1+r50 already exceeds
                    // the band can never yield a hit. Cuts ~half the RwgTerrain reads.
                    if (50 + 55 + 1 + isl[4] > bandMax) continue;
                    if (terraSlime == null) terraSlime = new RwgTerrain(world);
                    final int h = terraSlime.columns(isl[0], isl[1]).topSolid[0];
                    final int yEst = 50 + h + 1 + isl[4];
                    if (yEst <= bandMax) band.add(new int[] { isl[0], isl[1], isl[2], isl[3], h, yEst });
                }
                sb.append(", \"slime_islands\": ")
                    .append(islands.size())
                    .append(", \"slime_band\": ")
                    .append(band.size());
                // band island coords for arithmetic golden-checks and near-miss calibration:
                // [chunkX, chunkZ, xRange, zRange, virginTopSolid, yCenterEst]
                sb.append(", \"slime_band_islands\": [");
                for (int i = 0; i < band.size(); i++) {
                    final int[] b2 = band.get(i);
                    if (i > 0) sb.append(", ");
                    sb.append("[")
                        .append(b2[0])
                        .append(", ")
                        .append(b2[1])
                        .append(", ")
                        .append(b2[2])
                        .append(", ")
                        .append(b2[3])
                        .append(", ")
                        .append(b2[4])
                        .append(", ")
                        .append(b2[5])
                        .append("]");
                }
                sb.append("]");
                if (band.isEmpty() && !slimeGate.isEmpty()) {
                    return "{\"seed\": " + seed + ", \"kill\": \"slimeband\", \"islands\": " + islands.size() + "}";
                }
                // Triggers near each band island; construct each at most once; entrance vs ellipse.
                final Map<Long, RoguelikePrefilter.Result> built = new LinkedHashMap<>();
                final List<String> towerHits = new ArrayList<>();
                int houseHits = 0;
                for (final int[] bi : band) {
                    final int x0 = bi[0] * 16, z0 = bi[1] * 16;
                    final double exc = x0 + bi[2] / 2.0, ezc = z0 + bi[3] / 2.0;
                    // generateNear reach (100) + island semi-axis + slack for the 16-block trigger cell
                    final double reach = 100 + Math.max(bi[2], bi[3]) / 2.0 + 12;
                    for (final int[] t : RoguelikePrefilter.triggers(world, bi[0], bi[1], 9)) {
                        if (Math.hypot(t[0] * 16 + 4 - exc, t[1] * 16 + 4 - ezc) > reach) continue;
                        final Long tk = ((long) t[0] << 32) | (t[1] & 0xFFFFFFFFL);
                        RoguelikePrefilter.Result r = built.get(tk);
                        if (r == null) {
                            // place-only: siting + settings resolution without the ~260 ms room/loot build
                            final long tGen = Timing.start();
                            r = RoguelikePrefilter.generate(world, seed, t[0], t[1], true);
                            Timing.add("slimehouse.place", tGen);
                            built.put(tk, r);
                        }
                        if (r.entranceX == Integer.MIN_VALUE || r.tower == null) continue;
                        // Ellipse2D(0,0,xr,zr).contains(dx,dz) normalized: q < 1 is inside; the bottom-erosion
                        // pass thins the rim, so prefer low q when ranking hits.
                        final double nx = (r.entranceX - x0) / (double) bi[2] - 0.5;
                        final double nz = (r.entranceZ - z0) / (double) bi[3] - 0.5;
                        final double q = 4 * (nx * nx + nz * nz);
                        if (q > 1.0) continue;
                        if ("HOUSE".equals(r.tower)) houseHits++;
                        towerHits.add(
                            "{\"tower\": \"" + r.tower
                                + "\", \"entrance\": ["
                                + r.entranceX
                                + ", "
                                + r.entranceZ
                                + "], \"island_chunk\": ["
                                + bi[0]
                                + ", "
                                + bi[1]
                                + "], \"island_wh\": ["
                                + bi[2]
                                + ", "
                                + bi[3]
                                + "], \"h\": "
                                + bi[4]
                                + ", \"ycenter_est\": "
                                + bi[5]
                                + ", \"q\": "
                                + (Math.round(q * 100) / 100.0)
                                + ", \"trigger\": ["
                                + t[0]
                                + ", "
                                + t[1]
                                + "]}");
                    }
                }
                sb.append(", \"slime_tower_hits\": [")
                    .append(String.join(", ", towerHits))
                    .append("]");
                if ("house".equals(slimeGate) && houseHits == 0) {
                    return "{\"seed\": " + seed
                        + ", \"kill\": \"slimehouse\", \"islands\": "
                        + islands.size()
                        + ", \"band\": "
                        + band.size()
                        + ", \"hits\": ["
                        + String.join(", ", towerHits)
                        + "]}";
                }
            } catch (Throwable t) {
                WorldgenProbe.LOG.warn("[prefilter] slimehouse eval failed for {}: {}", seed, t.toString());
                sb.append(", \"slimehouse_error\": \"")
                    .append(
                        t.toString()
                            .replace("\"", "'"))
                    .append("\"");
            } finally {
                Timing.add("slimehouse", tSlime);
            }
        }

        // --- AE2 meteor crater scan (-Dprobe.prefilter.meteor=C grid cells around the origin cell; -1 off).
        // The candidate lattice is pure (seed, cell) arithmetic through the real Platform.seedFromGrid, so
        // the size gate costs nothing; terrain only runs for near-max rolls, and the volume disc only for
        // rolls that also cleared the relief gate. Sits with the other chunk-generating stages, after the
        // village and mineshaft start passes for the same poisoning reason they document.
        final int meteorCells = Integer.getInteger("probe.prefilter.meteor", -1);
        if (meteorCells >= 0) {
            final long tMeteor = Timing.start();
            try {
                final String why = MeteorPrefilter.resolve();
                if (why != null) {
                    sb.append(", \"meteor_error\": \"")
                        .append(why.replace("\"", "'"))
                        .append("\"");
                } else {
                    final double gateSize = Double
                        .parseDouble(System.getProperty("probe.prefilter.gate.meteorsize", "7.8"));
                    final int gateRelief = Integer.getInteger("probe.prefilter.gate.meteorrelief", 20);
                    final long gateVol = Long.getLong("probe.prefilter.gate.meteorvol", -1L);
                    final int volRadius = Integer.getInteger("probe.prefilter.meteor.volradius", 96);
                    final List<MeteorPrefilter.Candidate> cands = MeteorPrefilter.candidates(seed, meteorCells);
                    RwgTerrain terraMeteor = null;
                    final List<String> rows = new ArrayList<>();
                    double bestSize = 0;
                    long bestVol = -1;
                    final String biomeGate = System.getProperty("probe.prefilter.gate.meteorbiome", "");
                    final String[] biomeTokens = biomeGate.toLowerCase()
                        .replaceAll("[^a-z0-9,]", "")
                        .split(",");
                    // analytic noise screen: 0 = annotate only (calibration), >0 kills below the proxy bar
                    final int gateProxy = Integer.getInteger("probe.prefilter.gate.meteorproxy", 0);
                    MeteorPrefilter.AnalyticScreen screenMeteor = null;
                    for (final MeteorPrefilter.Candidate c : cands) {
                        if (c.size > bestSize) bestSize = c.size;
                        if (c.size < gateSize) continue;
                        if (terraMeteor == null) terraMeteor = new RwgTerrain(world);
                        if (screenMeteor == null) {
                            screenMeteor = new MeteorPrefilter.AnalyticScreen(world, terraMeteor);
                            final String dump = System.getProperty("probe.prefilter.meteor.dumprwg");
                            if (dump != null && !new java.io.File(dump).exists()) {
                                MeteorPrefilter.dumpRwg(world, terraMeteor, dump);
                            }
                        }
                        screenMeteor.screen(c);
                        if (gateProxy > 0 && c.proxyRelief < gateProxy) c.reject = "proxy";
                        // both-flanks gate: walls on opposing bearings (mountain-flanked channel class)
                        final int gateOpposed = Integer.getInteger("probe.prefilter.gate.meteoropposed", 0);
                        if (c.reject == null && gateOpposed > 0 && c.proxyOpposed < gateOpposed) {
                            c.reject = "onesided";
                        }
                        // worldless biome ring gate before any chunkgen; also stamps c.biome for tuning
                        if (c.reject == null && !MeteorPrefilter.biomePass(world, c, biomeTokens)
                            && !biomeGate.isEmpty()) {
                            c.reject = "biome";
                        }
                        if (c.reject == null) {
                            MeteorPrefilter.site(c, terraMeteor, world, gateRelief);
                        }
                        if (c.reject == null) {
                            MeteorPrefilter.score(c, terraMeteor, volRadius);
                            if (c.volume > bestVol) bestVol = c.volume;
                        }
                        rows.add(
                            "{\"cell\": [" + c.gx
                                + ", "
                                + c.gz
                                + "], \"x\": "
                                + c.x
                                + ", \"z\": "
                                + c.z
                                + ", \"dist\": "
                                + (int) Math.hypot(c.x, c.z)
                                + ", \"size\": "
                                + (Math.round(c.size * 1000) / 1000.0)
                                + ", \"y\": "
                                + c.y
                                + ", \"surface\": "
                                + c.surface
                                + ", \"burial\": "
                                + c.burial
                                + ", \"relief\": "
                                + c.relief
                                + ", \"volume\": "
                                + c.volume
                                + ", \"maxbite\": "
                                + c.maxbite
                                + ", \"enclosure\": "
                                + c.enclosure
                                + ", \"proxyrelief\": "
                                + c.proxyRelief
                                + ", \"proxyopposed\": "
                                + c.proxyOpposed
                                + (c.biome == null ? "" : ", \"biome\": \"" + c.biome.replace("\"", "'") + "\"")
                                + ", \"lava\": "
                                + c.lava
                                + ", \"surface_branch\": "
                                + c.surfaceBranch
                                + (c.reject == null ? "" : ", \"reject\": \"" + c.reject + "\"")
                                + "}");
                    }
                    sb.append(", \"meteor_cells\": ")
                        .append(cands.size())
                        .append(", \"meteor_grid\": ")
                        .append(MeteorPrefilter.cellSize())
                        .append(", \"meteorites\": [")
                        .append(String.join(", ", rows))
                        .append("]");
                    if (gateVol >= 0 && bestVol < gateVol) {
                        return "{\"seed\": " + seed
                            + ", \"kill\": \"meteorvol\", \"best_size\": "
                            + (Math.round(bestSize * 1000) / 1000.0)
                            + ", \"best_vol\": "
                            + bestVol
                            + ", \"candidates\": ["
                            + String.join(", ", rows)
                            + "]}";
                    }
                }
            } catch (Throwable t) {
                WorldgenProbe.LOG.warn("[prefilter] meteor eval failed for {}: {}", seed, t.toString());
                sb.append(", \"meteor_error\": \"")
                    .append(
                        t.toString()
                            .replace("\"", "'"))
                    .append("\"");
            } finally {
                Timing.add("meteor", tMeteor);
            }
        }

        // --- Cliff-house scan (-Dprobe.prefilter.cliffhouse=R chunks around origin; -1 off). Finds Roguelike
        // HOUSE towers (brick house, forest settings) whose entrance sits on a plateau edge over a deep drop —
        // the seed-pure "tallest brick house": the fix jar's virgin overlay means the tower footing is a
        // function of terrain alone, and HouseTower pours brick porch columns and a recursive brick step
        // curtain down to y=60 plus granite supports fillDown'd to the first opaque block, all exposed on a
        // cliff face. validLocation forces a locally flat 9x9 pad, so the drop lives 5-16 blocks out from the
        // entrance; score = est. floor (virgin topSolid+1 at entrance) minus min topSolid within +/-16 blocks.
        // Placement is place-only (settings + entrance, no construction). Triggers whose provider biome shows
        // no FOREST type within 64 blocks are skipped — HOUSE comes from SettingsForestTheme, and the
        // provider lookup is the same one the fix jar's WorldEditorMixin.getBiome uses.
        // Gate: -Dprobe.prefilter.gate.cliffhouse=D kills seeds whose best HOUSE drop is below D.
        final int cliffRadius = Integer.getInteger("probe.prefilter.cliffhouse", -1);
        if (cliffRadius >= 0 && RoguelikePrefilter.available()) {
            final long tCliff = Timing.start();
            try {
                final int gateDrop = Integer.getInteger("probe.prefilter.gate.cliffhouse", -1);
                final int gateStair = Integer.getInteger("probe.prefilter.gate.cliffstair", -1);
                final int gateVis = Integer.getInteger("probe.prefilter.gate.cliffvis", -1);
                final List<String> houses = new ArrayList<>();
                int bestDrop = Integer.MIN_VALUE;
                int bestStair = Integer.MIN_VALUE;
                int bestStairVis = Integer.MIN_VALUE;
                RwgTerrain terraCliff = null;
                for (final int[] t : RoguelikePrefilter.triggers(world, 0, 0, cliffRadius)) {
                    final int tx = t[0] * 16 + 4, tz = t[1] * 16 + 4;
                    boolean forestNear = false;
                    for (final int[] d : new int[][] { { 0, 0 }, { 64, 0 }, { -64, 0 }, { 0, 64 }, { 0, -64 } }) {
                        final net.minecraft.world.biome.BiomeGenBase b = world.getWorldChunkManager()
                            .getBiomeGenAt(tx + d[0], tz + d[1]);
                        if (b != null
                            && java.util.Arrays.asList(net.minecraftforge.common.BiomeDictionary.getTypesForBiome(b))
                                .contains(net.minecraftforge.common.BiomeDictionary.Type.FOREST)) {
                            forestNear = true;
                            break;
                        }
                    }
                    if (!forestNear) continue;
                    final long tPlace = Timing.start();
                    final RoguelikePrefilter.Result r = RoguelikePrefilter.generate(world, seed, t[0], t[1], true);
                    Timing.add("cliffhouse.place", tPlace);
                    if (!"HOUSE".equals(r.tower) || r.entranceX == Integer.MIN_VALUE) continue;
                    if (terraCliff == null) terraCliff = new RwgTerrain(world);
                    final int ex = r.entranceX, ez = r.entranceZ;
                    // min over all columns AND min over DRY columns. Sea level is ~63, so the deepest
                    // drops are river/ocean floors — and that is fine for the "most bricks" metric:
                    // porch/step columns pour through water to y=60 and the granite supports fillDown to
                    // the seabed, so a wet drop is strictly more structure. drop stays wet-inclusive;
                    // min_dry is emitted for anyone ranking dry cliff faces instead.
                    int floorEst = Integer.MIN_VALUE, minSurf = Integer.MAX_VALUE, minDry = Integer.MAX_VALUE;
                    for (int cx2 = (ex - 16) >> 4; cx2 <= (ex + 16) >> 4; cx2++) {
                        for (int cz2 = (ez - 16) >> 4; cz2 <= (ez + 16) >> 4; cz2++) {
                            final RwgTerrain.Cols cols = terraCliff.columns(cx2, cz2);
                            for (int lx = 0; lx < 16; lx++) {
                                for (int lz = 0; lz < 16; lz++) {
                                    final int wx = cx2 * 16 + lx, wz = cz2 * 16 + lz;
                                    if (Math.abs(wx - ex) > 16 || Math.abs(wz - ez) > 16) continue;
                                    final int idx = (lx << 4) | lz;
                                    final int s = cols.topSolid[idx];
                                    if (wx == ex && wz == ez) floorEst = s + 1;
                                    if (s < minSurf) minSurf = s;
                                    if (!cols.water[idx] && s < minDry) minDry = s;
                                }
                            }
                        }
                    }
                    // Exposed front staircase, replicating HouseTower geometry against virgin terrain —
                    // the iconic look people hunt for. dir = Cardinal.directions[(base+2)%4] where base is
                    // getBaseCoord's Y (adjusted ground + 1, min 64); the door sits at entrance +
                    // left(dir)*6 + reverse(dir)*6 (ground-truthed on seed 226905126988615045: door found
                    // at the predicted column). step() builds step k at door + reverse(dir)*(2+k),
                    // y = floor-1-k, and stops when the probe one ahead and below (reverse*(3+k),
                    // floor-2-k) hits validGroundBlock or y <= 60. validGroundBlock skips grass/plant/
                    // snow/water materials, so probes descend through water to the seabed.
                    // Exact Tower.getBaseCoord replica against the virgin world (world.getBlock IS virgin
                    // terrain here, the same blocks TerrainOracle serves the fix jar): scan 128->60 for the
                    // first validGroundBlock, +1, floor 14 above the dungeon (y=50) at minimum. The earlier
                    // digest-based estimate mis-derived base by 1 on some surfaces, and base%4 picks the
                    // door DIRECTION, so +-1 swings the staircase 90 degrees (measured: seed
                    // -7347640923000673639 door predicted W, generated S).
                    int scanY = 128;
                    while (scanY > 60 && !gtnhdet$roguelikeGround(world.getBlock(ex, scanY, ez))) scanY--;
                    final int base = Math.max(scanY + 1, 64);
                    final int floorY = base + 1;
                    final int drop = floorY - minSurf;
                    if (drop > bestDrop) bestDrop = drop;
                    final int dirIdx = (base + 2) % 4;
                    final int[][] VEC = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } }; // N E S W
                    final int[] rev = { -VEC[dirIdx][0], -VEC[dirIdx][1] };
                    final int[] left = VEC[(dirIdx + 3) % 4];
                    final int doorX = ex + left[0] * 6 + rev[0] * 6, doorZ = ez + left[1] * 6 + rev[1] * 6;
                    int stairLen = 0;
                    int stairVis = 0;
                    for (int k = 0; k < 45; k++) {
                        final int py = floorY - 2 - k;
                        if (py <= 60) break;
                        final int px = doorX + rev[0] * (3 + k), pz = doorZ + rev[1] * (3 + k);
                        if (gtnhdet$roguelikeGround(world.getBlock(px, py, pz))) break;
                        stairLen = k + 1;
                        // visible curtain: the built step sits one block back (rev*(2+k)) at y=floorY-1-k,
                        // 3 wide, on a brick column poured down to y=60. Count the blocks above the raw
                        // virgin surface (grass top included — buried brick is invisible; water is
                        // see-through, so seabed is the floor of visibility).
                        final int sx = doorX + rev[0] * (2 + k), sz = doorZ + rev[1] * (2 + k);
                        final RwgTerrain.Cols sc = terraCliff.columns(sx >> 4, sz >> 4);
                        final int sts = sc.topSolid[((sx & 15) << 4) | (sz & 15)];
                        final int visTop = floorY - 1 - k;
                        final int visBottom = Math.max(60, sts + 1);
                        if (visTop >= visBottom) stairVis += 3 * (visTop - visBottom + 1);
                    }
                    if (stairLen > bestStair) bestStair = stairLen;
                    if (stairVis > bestStairVis) bestStairVis = stairVis;
                    houses.add(
                        "{\"entrance\": [" + ex
                            + ", "
                            + ez
                            + "], \"floor_est\": "
                            + floorY
                            + ", \"min_surf\": "
                            + minSurf
                            + ", \"min_dry\": "
                            + (minDry == Integer.MAX_VALUE ? -1 : minDry)
                            + ", \"drop\": "
                            + drop
                            + ", \"stair_len\": "
                            + stairLen
                            + ", \"stair_vis\": "
                            + stairVis
                            + ", \"stair_dir\": \""
                            + new String[] { "N", "E", "S", "W" }[(dirIdx + 2) % 4]
                            + "\", \"door\": ["
                            + doorX
                            + ", "
                            + doorZ
                            + "], \"trigger\": ["
                            + t[0]
                            + ", "
                            + t[1]
                            + "]}");
                }
                sb.append(", \"cliff_houses\": [")
                    .append(String.join(", ", houses))
                    .append("]");
                if ((gateDrop >= 0 && bestDrop < gateDrop) || (gateStair >= 0 && bestStair < gateStair)
                    || (gateVis >= 0 && bestStairVis < gateVis)) {
                    return "{\"seed\": " + seed
                        + ", \"kill\": \"cliffhouse\", \"best_drop\": "
                        + (bestDrop == Integer.MIN_VALUE ? -999 : bestDrop)
                        + ", \"best_stair\": "
                        + (bestStair == Integer.MIN_VALUE ? -999 : bestStair)
                        + ", \"best_vis\": "
                        + (bestStairVis == Integer.MIN_VALUE ? -999 : bestStairVis)
                        + ", \"houses\": "
                        + houses.size()
                        + "}";
                }
            } catch (Throwable t) {
                WorldgenProbe.LOG.warn("[prefilter] cliffhouse eval failed for {}: {}", seed, t.toString());
                sb.append(", \"cliffhouse_error\": \"")
                    .append(
                        t.toString()
                            .replace("\"", "'"))
                    .append("\"");
            } finally {
                Timing.add("cliffhouse", tCliff);
            }
        }

        // --- terrain columns + spawn prediction (worldless RWG; -Dprobe.prefilter.terrain=-1 disables,
        // N = digest radius in chunks around the predicted spawn; default 4)
        final int terrainRadius = Integer.getInteger("probe.prefilter.terrain", 4);
        // Hoisted: the dungeon stage below scans around the predicted spawn, and defaults to the origin when
        // the terrain stage is disabled or failed rather than silently scanning somewhere arbitrary.
        int spawnX = 0, spawnZ = 0;
        // Hoisted for the DEFERRED terrain digest. The digest was 155 ms/seed — 37% of a 416 ms seed — and
        // it ran before every kill gate despite being a pure output field: nothing downstream reads it, so
        // 99.4% of seeds paid for a `terrain` array that was thrown away with the row. It now runs after the
        // last gate. `terrainAt` records where its JSON belonged so the finished row is inserted back at the
        // original offset and field order stays byte-identical to the corpora already on disk.
        RwgTerrain terra = null;
        int scx0 = 0, scz0 = 0, terrainAt = -1;
        if (terrainRadius >= 0) {
            try {
                final long tTerra = Timing.start();
                terra = new RwgTerrain(world);
                Timing.add("RwgTerrain.ctor", tTerra);
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
                final long tSpawn = Timing.start();
                while (!bopCanSpawn(world, terra, sx, sz)) {
                    sx += spawnRand.nextInt(64) - spawnRand.nextInt(64);
                    sz += spawnRand.nextInt(64) - spawnRand.nextInt(64);
                    if (++iters == 1000) break;
                }
                Timing.add("spawn.walk", tSpawn);
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
                final int gateWaterCols = Integer.getInteger("probe.prefilter.gate.water", -1);

                // Route-local water gate, evaluated BEFORE the wide digest.
                //
                // The water gate counts columns over the whole terrain window, so its meaning changes with
                // the radius: 32 columns inside 81 chunks is "water at spawn", 32 inside 961 is "water
                // somewhere within 240 blocks", which almost every seed satisfies. At terrain radius 15 the
                // gate therefore stops killing anything and every village survivor pays all 961 chunks.
                //
                // Setting gate.waterradius pins the gate to a route-local window while the digest still
                // covers the full radius for ranking. Seeds that fail it are killed after generating
                // (2r+1)^2 chunks instead of (2R+1)^2. Because the inner window is a subset of the outer
                // one, local >= threshold implies total >= threshold, so the wide gate below can never fire
                // on a seed this one passed — the two are consistent, not competing.
                //
                // Default is terrainRadius, which is exactly the previous behaviour.
                final int gateRadius = Math
                    .min(terrainRadius, Integer.getInteger("probe.prefilter.gate.waterradius", terrainRadius));
                if (gateWaterCols >= 0 && gateRadius < terrainRadius) {
                    int localWater = 0;
                    for (int cx2 = scx - gateRadius; cx2 <= scx + gateRadius; cx2++) {
                        for (int cz2 = scz - gateRadius; cz2 <= scz + gateRadius; cz2++) {
                            final RwgTerrain.Cols c = terra.columns(cx2, cz2);
                            for (int col = 0; col < 256; col++) {
                                if (c.water[col]) localWater++;
                            }
                        }
                    }
                    if (localWater < gateWaterCols) {
                        return "{\"seed\": " + seed + ", \"kill\": \"water\"}";
                    }
                }

                scx0 = scx;
                scz0 = scz;
                terrainAt = sb.length();
            } catch (Throwable t) {
                WorldgenProbe.LOG.warn("[prefilter] terrain/spawn eval failed for {}: {}", seed, t.toString());
                terra = null;
                sb.append(", \"terrain_error\": \"")
                    .append(
                        t.toString()
                            .replace("\"", "'"))
                    .append("\"");
            }
        }

        // ===== deferred terrain digest, invoked below every gate. Kept as a local class so the body is
        // ===== unchanged from when it ran inline; only its call site moved.
        final RwgTerrain terraF = terra;
        final int scxF = scx0, sczF = scz0;
        final class TerrainDigest {

            String killReason;

            String build(long seed) {
                try {
                    return body();
                } catch (Throwable t) {
                    // Same contract as when this ran inline: a terrain failure annotates the row, it does
                    // not kill the seed and does not propagate.
                    WorldgenProbe.LOG.warn("[prefilter] terrain digest failed for {}: {}", seed, t.toString());
                    return ", \"terrain_error\": \"" + t.toString()
                        .replace("\"", "'") + "\"";
                }
            }

            private String body() throws Exception {
                final StringBuilder sb = new StringBuilder(256);
                final int scx = scxF, scz = sczF;
                final int gateWaterCols = Integer.getInteger("probe.prefilter.gate.water", -1);
                final RwgTerrain terra = terraF;
                final List<String> digest = new ArrayList<>();
                int waterTotal = 0;
                final long tDigest = Timing.start();
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
                Timing.add("terrain.digest", tDigest);
                if (gateWaterCols >= 0 && waterTotal < gateWaterCols) {
                    // The wide water gate now fires after the funnel rather than before it. It kills the
                    // same seeds — it is a function of the digest alone — just later, and it is off by
                    // default (-1). Reported through killReason so the caller still emits a "water" row.
                    killReason = "water";
                    return null;
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
                // Now counts the WHOLE seed rather than everything-up-to-the-digest, because the digest is
                // last: strictly more informative, but not comparable with pre-deferral corpora.
                if (world instanceof SeedProbeWorld) {
                    final VirginChunkProvider vp = ((SeedProbeWorld) world).virginProvider();
                    if (vp != null) {
                        sb.append(", \"chunks_generated\": ")
                            .append(vp.generatedCount())
                            .append(", \"chunks_regenerated\": ")
                            .append(vp.regeneratedCount());
                    }
                }
                return sb.toString();
            }
        }

        // --- Village-piece distance gate: kill unless some village whose layout contains the named
        // piece starts within N blocks of the predicted spawn. Runs on data the village pass already
        // produced, so it costs a string scan — which is why it sits FIRST in the gate funnel.
        final String pieceGate = System.getProperty("probe.prefilter.gate.villagepiece");
        if (pieceGate != null && !pieceGate.isEmpty() && !"false".equals(pieceGate)) {
            final String[] pg = pieceGate.split(",");
            final String pieceName = pg[0].trim();
            final int pieceDist = Integer.parseInt(pg[1].trim());
            boolean ok = false;
            for (final String st : starts) {
                if (!st.contains(pieceName)) continue;
                final int ci = st.indexOf("\"c\": [");
                if (ci < 0) continue;
                try {
                    final String[] cc = st.substring(ci + 6, st.indexOf(']', ci))
                        .split(",");
                    final int vx = Integer.parseInt(cc[0].trim()) * 16 + 8;
                    final int vz = Integer.parseInt(cc[1].trim()) * 16 + 8;
                    if (Math.hypot(vx - spawnX, vz - spawnZ) <= pieceDist) {
                        ok = true;
                        break;
                    }
                } catch (Exception ignored) {}
            }
            if (!ok) return "{\"seed\": " + seed + ", \"kill\": \"villagepiece\"}";
        }

        // --- Hammer% steel stage: Block of Steel aggregated over predicted village chests and mineshaft
        // corridor minecarts within DIST blocks of the predicted spawn. The sim pays terrain only for starts
        // whose bbox comes within range, and the kill row carries every count so the gate bar can be tuned
        // from sweep output without re-sweeping (the slimehouse kill-row trick).
        final boolean mineshaftLoot = Boolean.getBoolean("probe.prefilter.mineshaftloot");
        final String steelGateSpec = System.getProperty("probe.prefilter.gate.steel");
        if (mineshaftLoot || (steelGateSpec != null && !steelGateSpec.isEmpty() && !"false".equals(steelGateSpec))) {
            final long tSteel = Timing.start();
            int gateSteelN = -1;
            int steelDist = 320;
            if (steelGateSpec != null && !steelGateSpec.isEmpty() && !"false".equals(steelGateSpec)) {
                final String[] p = steelGateSpec.split(",");
                gateSteelN = Integer.parseInt(p[0].trim());
                if (p.length > 1) steelDist = Integer.parseInt(p[1].trim());
            }
            if (gateSteelN >= 0 && !CHESTS_ENABLED) {
                warnOnce(
                    "gate.steel runs without -Dprobe.prefilter.chests=true — village chests are not predicted, "
                        + "so the blacksmith half of the total is silently zero");
            }
            if (gateSteelN >= 0 && !mineshaftLoot) {
                warnOnce(
                    "gate.steel runs without -Dprobe.prefilter.mineshaftloot=true — corridor minecarts are not "
                        + "simulated, so the mineshaft half of the total is silently zero");
            }
            int blacksmithSteel = 0;
            for (final int[] c : villageSteel) {
                if (Math.hypot(c[0] - spawnX, c[1] - spawnZ) <= steelDist) blacksmithSteel += c[2];
            }
            int mineshaftSteel = 0;
            final List<String> msJson = new ArrayList<>();
            if (mineshaftLoot && world instanceof SeedProbeWorld) {
                final long cg0 = Timing.nanos("chunkgen");
                for (final MineshaftPrefilter.Start s : msStarts) {
                    // Chests live inside the start bbox, so a bbox whose XZ rectangle is entirely beyond the
                    // distance cannot contribute; skipping it skips its whole terrain bill. Applied whether or
                    // not the gate is set: an ungated calibration run that simulated every start in the r45
                    // scan window measured 2.6 s/seed of chunkgen for loot the hunt never reads.
                    final double dx = Math.max(0, Math.max(s.bbox[0] - spawnX, spawnX - s.bbox[3]));
                    final double dz = Math.max(0, Math.max(s.bbox[2] - spawnZ, spawnZ - s.bbox[5]));
                    if (Math.hypot(dx, dz) > steelDist) continue;
                    final MineshaftPrefilter.SimResult r = MineshaftPrefilter.simulate((SeedProbeWorld) world, seed, s);
                    int startSteel = 0;
                    for (final int[] cs : r.chestSteel) {
                        if (Math.hypot(cs[0] - spawnX, cs[2] - spawnZ) <= steelDist) mineshaftSteel += cs[3];
                        startSteel += cs[3];
                    }
                    final StringBuilder m = new StringBuilder(128);
                    m.append("{\"c\": [")
                        .append(s.cx)
                        .append(", ")
                        .append(s.cz)
                        .append("], \"windows\": ")
                        .append(r.windows)
                        .append(", \"removed\": ")
                        .append(r.componentsRemoved)
                        .append(", \"steel\": ")
                        .append(startSteel)
                        .append(", \"chests\": [")
                        .append(String.join(", ", r.chests))
                        .append("]");
                    if (r.error != null) {
                        m.append(", \"error\": \"")
                            .append(r.error.replace("\"", "'"))
                            .append("\"");
                    }
                    m.append("}");
                    msJson.add(m.toString());
                }
                if (Timing.ON) Timing.addRaw("mineshaft.chunkgen", Timing.nanos("chunkgen") - cg0);
            }
            final int steelTotal = blacksmithSteel + mineshaftSteel;
            sb.append(", \"mineshafts\": [")
                .append(String.join(", ", msJson))
                .append("], \"blacksmith_steel\": ")
                .append(blacksmithSteel)
                .append(", \"mineshaft_steel\": ")
                .append(mineshaftSteel)
                .append(", \"steel_total\": ")
                .append(steelTotal);
            Timing.add("mineshaft.sim", tSteel);
            if (gateSteelN >= 0 && steelTotal < gateSteelN) {
                return "{\"seed\": " + seed
                    + ", \"kill\": \"steel\", \"blacksmith_steel\": "
                    + blacksmithSteel
                    + ", \"mineshaft_steel\": "
                    + mineshaftSteel
                    + ", \"steel_total\": "
                    + steelTotal
                    + ", \"mineshaft_starts\": "
                    + msStarts.size()
                    + "}";
            }
        }

        // --- Witchery candidate cells. Pure arithmetic plus a biome lookup: the region formula reads no blocks
        // and the handler shuffle is a function of the FML chunk seed, so this costs nothing terrain-wise.
        final int witcheryRadius = Integer.getInteger("probe.prefilter.witchery", -1);
        if (witcheryRadius >= 0) {
            final long tWitch = Timing.start();
            final String why = WitcheryPrefilter.resolve();
            if (why != null) {
                sb.append(", \"witchery_error\": \"")
                    .append(why.replace("\"", "'"))
                    .append("\"");
            } else {
                try {
                    final List<String> cells = new ArrayList<>();
                    double nearestCoven = Double.MAX_VALUE;
                    for (final WitcheryPrefilter.Cell c : WitcheryPrefilter
                        .candidates(world, spawnX >> 4, spawnZ >> 4, witcheryRadius)) {
                        if ("WorldHandlerCoven".equals(c.winner)) {
                            // Container position when the replay produced one (exact); cell origin
                            // otherwise. The origin can be ~20 blocks short of the container, which at
                            // a hundreds-of-blocks gate is noise on the safe side.
                            int gx = c.x, gz = c.z;
                            if (!c.chests.isEmpty()) {
                                final String ch = c.chests.get(0);
                                final int p = ch.indexOf("\"pos\": [");
                                if (p >= 0) {
                                    try {
                                        final String[] xyz = ch.substring(p + 8, ch.indexOf(']', p))
                                            .split(",");
                                        gx = Integer.parseInt(xyz[0].trim());
                                        gz = Integer.parseInt(xyz[2].trim());
                                    } catch (Exception ignored) {}
                                }
                            }
                            nearestCoven = Math.min(nearestCoven, Math.hypot(gx - spawnX, gz - spawnZ));
                        }
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
                                + "\"]"
                                + (c.winner == null ? ""
                                    : ", \"winner\": \"" + c.winner
                                        + "\", \"chests\": ["
                                        + String.join(", ", c.chests)
                                        + "]")
                                + "}");
                    }
                    sb.append(", \"witchery_cells\": [")
                        .append(String.join(", ", cells))
                        .append("]");
                    // Gate: nearest coven circle within N blocks of spawn. Meaningless without the
                    // replay (no winners exist), so it deliberately refuses to run half-blind.
                    final String circleGate = System.getProperty("probe.prefilter.gate.witcherycircle");
                    if (circleGate != null && !circleGate.isEmpty() && !"false".equals(circleGate)) {
                        if (!WitcheryPrefilter.REPLAY) {
                            WorldgenProbe.LOG.warn(
                                "[prefilter] gate.witcherycircle needs -Dprobe.prefilter.witchery.replay=true; gate skipped");
                        } else if (nearestCoven > Integer.parseInt(circleGate.trim())) {
                            return "{\"seed\": " + seed + ", \"kill\": \"witcherycircle\"}";
                        }
                    }
                } catch (Throwable t) {
                    WorldgenProbe.LOG.warn("[prefilter] witchery eval failed for {}: {}", seed, t.toString());
                    sb.append(", \"witchery_error\": \"")
                        .append(
                            t.toString()
                                .replace("\"", "'"))
                        .append("\"");
                }
            }
            Timing.add("witchery", tWitch);
        }

        // --- Roguelike dungeons: trigger scan is free arithmetic and acts as its own kill gate; construction is
        // the expensive terminal stage and only runs for seeds that have a trigger in range.
        // -Dprobe.prefilter.dungeon=N enables it, N = scan radius in chunks around the predicted spawn.
        final int dungeonRadius = Integer.getInteger("probe.prefilter.dungeon", -1);
        if (dungeonRadius >= 0 && RoguelikePrefilter.available()) {
            try {
                final int scx = spawnX >> 4, scz = spawnZ >> 4;
                final long tTrig = Timing.start();
                final List<int[]> triggers = RoguelikePrefilter.triggers(world, scx, scz, dungeonRadius);
                Timing.add("dungeon.triggers", tTrig);
                sb.append(", \"dungeon_triggers\": ")
                    .append(triggers.size());
                if (triggers.isEmpty() && Boolean.getBoolean("probe.prefilter.gate.dungeon")) {
                    return "{\"seed\": " + seed + ", \"kill\": \"dungeon\"}";
                }
                // Near-first: when the enchant gate is set, dungeons close to spawn generate FIRST and
                // the seed dies before the far ones (and before every later stage) if none holds an
                // enchantment table in range. The 160-block margin covers how far generateNear can move
                // a dungeon from its trigger; gating on the trigger alone would silently miss a table
                // that wandered outward.
                final java.util.LinkedHashMap<Long, RoguelikePrefilter.Result> ready = new java.util.LinkedHashMap<>();
                final String enchGate = System.getProperty("probe.prefilter.gate.enchant");
                if (enchGate != null && !enchGate.isEmpty() && !"false".equals(enchGate)) {
                    final int want = Integer.parseInt(enchGate.trim());
                    boolean pass = false;
                    for (final int[] t : triggers) {
                        if (Math.hypot(t[0] * 16 + 8 - spawnX, t[1] * 16 + 8 - spawnZ) > want + 160) continue;
                        final RoguelikePrefilter.Result r = generateDungeonTimed(world, seed, t);
                        ready.put(((long) t[0] << 32) | (t[1] & 0xFFFFFFFFL), r);
                        for (final int[] p : r.enchantTables) {
                            if (Math.hypot(p[0] - spawnX, p[2] - spawnZ) <= want) pass = true;
                        }
                        if (pass) break;
                    }
                    if (!pass) return "{\"seed\": " + seed + ", \"kill\": \"enchant\"}";
                }
                // --- Hammer% tool-loot gate: TOOLS-chest category counts aggregated over EVERY dungeon whose
                // loot can sit within dist of spawn (trigger within dist+160 — generateNear's reach), then one
                // verdict. Unlike the enchant gate there is no early exit on the first hit: the criteria sum
                // across dungeons, so all in-range ones must generate first. Constructed Results go into
                // `ready` so the emission loop below never generates the same dungeon twice.
                final String toolGateSpec = System.getProperty("probe.prefilter.gate.toolloot");
                if (toolGateSpec != null && !toolGateSpec.isEmpty() && !"false".equals(toolGateSpec)) {
                    int wantAlumite = 0, wantPlate = 0, wantHammer = 0, wantRod = 0, toolDist = 320;
                    for (final String kv : toolGateSpec.split(",")) {
                        final String[] p = kv.split("=");
                        if (p.length != 2) continue;
                        final int v = Integer.parseInt(p[1].trim());
                        switch (p[0].trim()) {
                            case "alumite":
                                wantAlumite = v;
                                break;
                            case "plate":
                                wantPlate = v;
                                break;
                            case "hammer":
                                wantHammer = v;
                                break;
                            case "rod":
                                wantRod = v;
                                break;
                            case "dist":
                                toolDist = v;
                                break;
                            default:
                                warnOnce("gate.toolloot: unknown key '" + p[0].trim() + "' ignored");
                        }
                    }
                    final int[] agg = new int[LootCounts.N];
                    int dungeonsBuilt = 0;
                    for (final int[] t : triggers) {
                        if (Math.hypot(t[0] * 16 + 8 - spawnX, t[1] * 16 + 8 - spawnZ) > toolDist + 160) continue;
                        final Long tk = ((long) t[0] << 32) | (t[1] & 0xFFFFFFFFL);
                        RoguelikePrefilter.Result r = ready.get(tk);
                        if (r == null) {
                            r = generateDungeonTimed(world, seed, t);
                            ready.put(tk, r);
                        }
                        dungeonsBuilt++;
                        for (final int[] tc : r.toolChests) {
                            if (Math.hypot(tc[0] - spawnX, tc[2] - spawnZ) > toolDist) continue;
                            agg[LootCounts.ALUMITE_PLATE] += tc[3];
                            agg[LootCounts.ANY_PLATE] += tc[4];
                            agg[LootCounts.HAMMER_HEAD] += tc[5];
                            agg[LootCounts.TOUGH_ROD] += tc[6];
                        }
                    }
                    sb.append(", \"tools_agg\": {\"alumite_plate\": ")
                        .append(agg[LootCounts.ALUMITE_PLATE])
                        .append(", \"plate\": ")
                        .append(agg[LootCounts.ANY_PLATE])
                        .append(", \"hammer_head\": ")
                        .append(agg[LootCounts.HAMMER_HEAD])
                        .append(", \"tough_rod\": ")
                        .append(agg[LootCounts.TOUGH_ROD])
                        .append("}");
                    if (agg[LootCounts.ALUMITE_PLATE] < wantAlumite || agg[LootCounts.ANY_PLATE] < wantPlate
                        || agg[LootCounts.HAMMER_HEAD] < wantHammer
                        || agg[LootCounts.TOUGH_ROD] < wantRod) {
                        return "{\"seed\": " + seed
                            + ", \"kill\": \"toolloot\", \"alumite_plate\": "
                            + agg[LootCounts.ALUMITE_PLATE]
                            + ", \"plate\": "
                            + agg[LootCounts.ANY_PLATE]
                            + ", \"hammer_head\": "
                            + agg[LootCounts.HAMMER_HEAD]
                            + ", \"tough_rod\": "
                            + agg[LootCounts.TOUGH_ROD]
                            + ", \"dungeons\": "
                            + dungeonsBuilt
                            + "}";
                    }
                }
                final List<String> dungeons = new ArrayList<>();
                for (final int[] t : triggers) {
                    final Long tk = ((long) t[0] << 32) | (t[1] & 0xFFFFFFFFL);
                    final RoguelikePrefilter.Result r = ready.containsKey(tk) ? ready.get(tk)
                        : generateDungeonTimed(world, seed, t);
                    final StringBuilder d = new StringBuilder(128);
                    d.append("{\"trigger\": [")
                        .append(r.triggerX)
                        .append(", ")
                        .append(r.triggerZ)
                        .append("], \"chests\": [")
                        .append(String.join(", ", r.chests))
                        .append("]");
                    if (r.tower != null) {
                        d.append(", \"tower\": \"")
                            .append(r.tower)
                            .append("\"");
                    }
                    // Whether GTNH's loot_all.json LOOTRULES suppression reached this dungeon; false means
                    // the built-in JUNK rule ran, which is the only route to vanilla ChestGenHooks loot
                    // (and so to the level-4 pyramidJungleChest / pyramidDesertChest draw).
                    if (r.lootRulesSuppressed != null) {
                        d.append(", \"lootrules_suppressed\": ")
                            .append(r.lootRulesSuppressed.booleanValue());
                    }
                    if (!r.enchantTables.isEmpty()) {
                        final List<String> ts = new ArrayList<>();
                        for (final int[] p : r.enchantTables) ts.add("[" + p[0] + ", " + p[1] + ", " + p[2] + "]");
                        d.append(", \"enchant_tables\": [")
                            .append(String.join(", ", ts))
                            .append("]");
                    }
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

        // --- No-rain region + humid neighbour. Deliberately the LAST gated stage: it is the most
        // expensive one (whole-window Tier B chunk generation), so it runs only for seeds the cheap
        // gates — smeltery village, coven circle, enchant dungeon — already kept. The original ordering
        // constraints still hold and still bind: it must follow the village pass (Tier B chunk
        // generation would register village starts the pass never asked for) and the terrain digest
        // (a wide lattice scan first would clear ChunkManagerRealistic's biomeDataMap).
        final int biomeRadius = Integer.getInteger("probe.prefilter.biomeregion", -1);
        if (biomeRadius >= 0) {
            final long tBiome = Timing.start();
            final int minSide = Integer.getInteger("probe.prefilter.biomeregion.min", 5);
            final int humidity = Integer.getInteger("probe.prefilter.biomeregion.humidity", 14);
            // Default -1 = confirm the whole window. Correctness first: this stage runs last, so it only ever
            // pays on seeds the cheap gates already kept, and a wrong biome verdict is not worth the seconds
            // it would save. A positive budget trades exactness for speed and marks the rows it degraded.
            final int confirm = Integer.getInteger("probe.prefilter.biomeregion.confirm", -1);
            warnBiomeRegionCost(biomeRadius);
            final BiomeRegionPrefilter.Result br = BiomeRegionPrefilter
                .evaluate(world, spawnX >> 4, spawnZ >> 4, biomeRadius, minSide, humidity, confirm);
            sb.append(", \"biomeregion\": ")
                .append(br.toJson());
            // Recorded BEFORE the kill gate: a gated run returns out of this block for killed seeds, and their
            // evaluate() cost is exactly the number this timer exists to expose.
            Timing.add("biomeregion", tBiome);
            // Gate: SIDE[,MAXGAP]. A seed is killed when it has no square of at least SIDE, or when the
            // nearest humid chunk is farther than MAXGAP. Reported as its own kill label so a sweep run with
            // this gate is never pooled with one run without it.
            final String gate = System.getProperty("probe.prefilter.gate.biomeregion");
            if (gate != null && !gate.isEmpty() && !"false".equals(gate)) {
                final String[] parts = gate.split(",");
                final int wantSide = Integer.parseInt(parts[0].trim());
                final int maxGap = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : -1;
                final boolean ok = br.side >= wantSide && br.gap >= 0 && (maxGap < 0 || br.gap <= maxGap);
                if (!ok) return "{\"seed\": " + seed + ", \"kill\": \"biomeregion\"}";
            }
        }

        // --- BuildCraft oil. Runs the mod's real generator against the scratch overlay; the Random is
        // free here because PopulateChunkEvent.Pre fires on the line after RWG reseeds it from
        // (worldSeed, cx, cz), so nothing has to be replayed to reach it. See OilPrefilter.
        final int oilRadius = Integer.getInteger("probe.prefilter.oil", -1);
        if (oilRadius >= 0) {
            final long tOil = Timing.start();
            final String why = OilPrefilter.resolve();
            if (why != null) {
                sb.append(", \"oil_error\": \"")
                    .append(WorldgenProbe.jsonEscape(why))
                    .append("\"");
            } else {
                try {
                    final List<String> sites = new ArrayList<>();
                    for (final OilPrefilter.Site o : OilPrefilter
                        .scan((SeedProbeWorld) world, spawnX >> 4, spawnZ >> 4, oilRadius)) {
                        sites.add(
                            "{\"c\": [" + o.cx
                                + ", "
                                + o.cz
                                + "], \"pos\": ["
                                + o.x
                                + ", "
                                + o.maxY
                                + ", "
                                + o.z
                                + "], \"blocks\": "
                                + o.blocks
                                + ", \"h\": "
                                + o.height()
                                + "}");
                    }
                    sb.append(", \"oil\": [")
                        .append(String.join(", ", sites))
                        .append("]");
                } catch (Throwable t) {
                    WorldgenProbe.LOG.warn("[prefilter] oil eval failed for {}: {}", seed, t.toString());
                    sb.append(", \"oil_error\": \"")
                        .append(
                            t.toString()
                                .replace("\"", "'"))
                        .append("\"");
                }
            }
            Timing.add("oil", tOil);
        }

        // --- strongholds: 3 per world, ring positions are pure arithmetic (see strongholdStarts).
        // -Dprobe.prefilter.stronghold=N enables it, N = radius in chunks around the predicted spawn.
        final int shRadius = Integer.getInteger("probe.prefilter.stronghold", -1);
        if (shRadius >= 0) {
            final long tSh = Timing.start();
            try {
                final List<String> sh = strongholdStarts(world, spawnX >> 4, spawnZ >> 4, shRadius);
                sb.append(", \"strongholds\": [")
                    .append(String.join(", ", sh))
                    .append("]");
            } catch (Throwable t) {
                WorldgenProbe.LOG.warn("[prefilter] stronghold eval failed for {}: {}", seed, t.toString());
                sb.append(", \"stronghold_error\": \"")
                    .append(
                        t.toString()
                            .replace("\"", "'"))
                    .append("\"");
            }
            Timing.add("stronghold", tSh);
        }

        // Every gate passed: now, and only now, pay for the terrain digest. Inserted at the offset it
        // would have occupied had it run inline, so the row is byte-identical to a pre-deferral one.
        if (terraF != null && terrainAt >= 0) {
            final TerrainDigest td = new TerrainDigest();
            final String terrainJson = td.build(seed);
            if (terrainJson == null) {
                return "{\"seed\": " + seed + ", \"kill\": \"" + td.killReason + "\"}";
            }
            sb.insert(terrainAt, terrainJson);
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * Tier 1: Roguelike trigger count and nothing else, as a ranking proxy for the full loot score.
     *
     * <p>
     * Generates <b>zero chunks</b>. {@code Dungeon.canSpawnInChunk} is pure arithmetic —
     * {@code editor.getSeededRandom(cx, cz, salt)} seeds the world's {@code Random} and takes two draws — so a
     * whole seed costs world construction plus the scan. Measured against the full pipeline's 1466 ms/seed, of
     * which 904 ms is chunk generation, this is the "or not generate them at all" branch rather than an attempt
     * to make a chunk cheaper.
     *
     * <p>
     * The scan is centred on the <b>origin</b>, not on the predicted spawn, because computing spawn costs
     * ~107 ms/seed: {@code createSpawnPosition} probes candidates until BOP's accept test passes and each probe
     * materialises a whole chunk to read one column's top block. Spawn is median ~104 blocks from the origin
     * (p90 478, max 1926), so an origin-centred scan at {@code radius + slack} is a superset of the
     * spawn-centred one for all but the most displaced spawns. That makes this a <i>different</i> proxy from the
     * tier-2 trigger count, not an approximation of it — its recall against true score is measured, not assumed,
     * and tier 2 recomputes everything exactly for the survivors.
     */
    private static String evaluateTier1(World world, long seed, int radiusChunks) {
        final StringBuilder sb = new StringBuilder(64);
        sb.append("{\"seed\": ")
            .append(seed);
        try {
            // Spawn is NOT skippable, though it is 96% of this tier's cost. Measured on 5000 seeds: an
            // origin-centred trigger count correlates 0.375 with true score at its best radius and retains
            // 3 of the true top 10; the spawn-centred count correlates 0.70 and retains 10 of 10. The scoring
            // window is centred on spawn, so dungeons are only worth counting relative to it.
            final long tSpawn = Timing.start();
            final RwgTerrain terra = new RwgTerrain(world);
            final Random spawnRand = new Random(seed);
            final net.minecraft.world.ChunkPosition cp = world.getWorldChunkManager()
                .findBiomePosition(
                    0,
                    0,
                    256,
                    world.getWorldChunkManager()
                        .getBiomesToSpawnIn(),
                    spawnRand);
            int sx = cp != null ? cp.chunkPosX : 0, sz = cp != null ? cp.chunkPosZ : 0;
            int iters = 0;
            while (!bopCanSpawn(world, terra, sx, sz)) {
                sx += spawnRand.nextInt(64) - spawnRand.nextInt(64);
                sz += spawnRand.nextInt(64) - spawnRand.nextInt(64);
                if (++iters == 1000) break;
            }
            Timing.add("tier1.spawn", tSpawn);
            sb.append(", \"spawn\": [")
                .append(sx)
                .append(", 64, ")
                .append(sz)
                .append("], \"spawn_iters\": ")
                .append(iters);
            final long t = Timing.start();
            final List<int[]> triggers = RoguelikePrefilter.triggers(world, sx >> 4, sz >> 4, radiusChunks);
            Timing.add("tier1.triggers", t);
            // Ring counts by chebyshev distance from spawn. These answered "which radius carries the
            // signal", and the answer is in: r60. Kept because they are free from the same scan and because
            // the negative results are worth being able to re-derive, not because a caller needs them —
            // t60 equals `triggers` at the production radius. Measured over 5000 seeds against exact scores:
            // spawn-centred r60 correlates 0.707; the same scan centred on the ORIGIN manages only 0.375 at
            // its best radius and 0.064 at r90, where every seed has 9-16 triggers and the signal is gone.
            // Distance weighting does not help either — raw t60 (0.711) beats every weighted combination and
            // the inner rings are NEGATIVELY correlated (t45 -0.049, t30 -0.043). Count within the scoring
            // radius is the signal; position inside it carries none.
            final int[] rings = { 15, 30, 45, 60 };
            final int[] counts = new int[rings.length];
            for (final int[] c : triggers) {
                final int d = Math.max(Math.abs(c[0] - (sx >> 4)), Math.abs(c[1] - (sz >> 4)));
                for (int i = 0; i < rings.length; i++) {
                    if (d <= rings[i]) counts[i]++;
                }
            }
            sb.append(", \"triggers\": ")
                .append(triggers.size());
            for (int i = 0; i < rings.length; i++) {
                sb.append(", \"t")
                    .append(rings[i])
                    .append("\": ")
                    .append(counts[i]);
            }
        } catch (Throwable t) {
            sb.append(", \"error\": \"")
                .append(
                    t.toString()
                        .replace("\"", "'"))
                .append("\"");
        }
        return sb.append("}")
            .toString();
    }

    // ------------------------------------------------------------------ strongholds

    private static Object STRONGHOLD_GEN;
    private static Field SH_WORLD_OBJ;
    private static Field SH_STRUCTURE_MAP;
    private static Field SH_STRUCTURE_DATA;
    private static Field SH_RAN_BIOME_CHECK; // field_75056_f — computed ONCE per generator, see below
    private static Field SH_COORDS; // the 3 precomputed ring positions
    // CAN_SPAWN and GENERATE are bound to the VILLAGE generator's class, and Method.invoke rejects a receiver
    // that is not an instance of the declaring class. Resolve both again against this one.
    private static java.lang.reflect.Method SH_CAN_SPAWN;
    private static java.lang.reflect.Method SH_GENERATE;

    /**
     * The pack's own stronghold generator, built exactly as {@code ChunkGeneratorRealistic} builds it so any
     * mod replacement applies. Dispatch, don't reimplement.
     */
    private static synchronized Object strongholdGenerator() throws Exception {
        if (STRONGHOLD_GEN == null) {
            final Object gen = net.minecraftforge.event.terraingen.TerrainGen.getModdedMapGen(
                new net.minecraft.world.gen.structure.MapGenStronghold(),
                net.minecraftforge.event.terraingen.InitMapGenEvent.EventType.STRONGHOLD);
            SH_WORLD_OBJ = findField(gen.getClass(), "field_75039_c", "worldObj");
            SH_STRUCTURE_MAP = findField(gen.getClass(), "field_75053_d", "structureMap");
            SH_STRUCTURE_DATA = findField(gen.getClass(), "field_143029_e");
            SH_RAN_BIOME_CHECK = findField(gen.getClass(), "field_75056_f", "ranBiomeCheck");
            SH_COORDS = findField(gen.getClass(), "field_75057_g", "structureCoords");
            SH_CAN_SPAWN = findMethod(
                gen.getClass(),
                new String[] { "func_75047_a", "canSpawnStructureAtCoords" },
                int.class,
                int.class);
            SH_GENERATE = findMethod(
                gen.getClass(),
                new String[] { "func_151539_a", "generate" },
                net.minecraft.world.chunk.IChunkProvider.class,
                World.class,
                int.class,
                int.class,
                net.minecraft.block.Block[].class);
            STRONGHOLD_GEN = gen;
            WorldgenProbe.LOG.info(
                "[prefilter] stronghold generator class: {}",
                gen.getClass()
                    .getName());
        }
        return STRONGHOLD_GEN;
    }

    /**
     * Stronghold starts within {@code radiusChunks} of (scx, scz), with their piece layout.
     *
     * <p>
     * Cheap and exact: {@code MapGenStronghold.canSpawnStructureAtCoords} derives all three ring positions from
     * {@code new Random(worldSeed)} alone, and RWG's {@code ChunkManagerRealistic.findBiomePosition} returns
     * null, so the biome-viability relocation never runs and the raw arithmetic positions stand. No terrain is
     * read and no chunk is generated — the positions are read straight out of {@code structureCoords}, so there
     * is no cell scan either.
     *
     * <p>
     * <b>{@code ranBiomeCheck} must be reset per seed.</b> It is an instance flag latched the first time the
     * ring positions are computed; a generator reused across seeds would hand every later seed the FIRST
     * seed's stronghold positions, silently and with no error — the same shape as the village generator's
     * {@code structureMap}/{@code field_143029_e} carry-over that {@link #villageStarts} resets.
     */
    private static List<String> strongholdStarts(World world, int scx, int scz, int radiusChunks) throws Exception {
        final Object gen = strongholdGenerator();
        SH_WORLD_OBJ.set(gen, world);
        SH_RAN_BIOME_CHECK.setBoolean(gen, false);
        ((Map<?, ?>) SH_STRUCTURE_MAP.get(gen)).clear();
        SH_STRUCTURE_DATA.set(gen, null);

        // Triggering the gate at any coordinate populates structureCoords for the whole world.
        SH_CAN_SPAWN.invoke(gen, scx, scz);
        final Object[] coords = (Object[]) SH_COORDS.get(gen);

        final List<String> out = new ArrayList<>();
        for (final Object cc : coords) {
            if (cc == null) continue;
            final net.minecraft.world.ChunkCoordIntPair p = (net.minecraft.world.ChunkCoordIntPair) cc;
            final int d = Math.max(Math.abs(p.chunkXPos - scx), Math.abs(p.chunkZPos - scz));
            if (d > radiusChunks) continue;
            SH_GENERATE.invoke(gen, null, world, p.chunkXPos, p.chunkZPos, null);
        }
        for (final Map.Entry<?, ?> e : ((Map<?, ?>) SH_STRUCTURE_MAP.get(gen)).entrySet()) {
            final Object start = e.getValue();
            if (START_COMPONENTS == null) {
                START_COMPONENTS = findField(start.getClass(), "field_75075_a", "components");
            }
            final List<?> comps = (List<?>) START_COMPONENTS.get(start);
            final List<String> parts = new ArrayList<>();
            for (final Object comp : comps) {
                parts.add(
                    comp.getClass()
                        .getSimpleName() + "@"
                        + WorldgenProbe.bboxOf(comp));
            }
            java.util.Collections.sort(parts);
            // Chest contents, through the same table and the same fork the village module uses. The stronghold
            // rows in chest-sites.json were measured by a -Dgtnhdet.chesttrace run centred on a stronghold this
            // enumeration located, which is how the coverage gap was closed: the earlier trace corpus ran at
            // radius 8-15 around spawn and the nearest ring sits 640-1150 blocks out, so it never saw one.
            final List<String> chests = new ArrayList<>();
            final List<String> chestsUnpredicted = new ArrayList<>();
            if (CHESTS_ENABLED) {
                for (final Object comp : comps) {
                    final net.minecraft.world.gen.structure.StructureBoundingBox cbb = bboxField(comp);
                    if (cbb == null) continue;
                    for (final VillageChestPrefilter.Predicted pr : VillageChestPrefilter.predict(
                        world.getSeed(),
                        comp.getClass()
                            .getName(),
                        coordBaseMode(comp),
                        cbb,
                        cbb.minY,
                        comp)) {
                        (pr.reason == null ? chests : chestsUnpredicted).add(chestJson(pr));
                    }
                }
            }
            final long key = ((Number) e.getKey()).longValue();
            final int cx = (int) key, cz = (int) (key >> 32);
            out.add(
                "{\"c\": [" + cx
                    + ", "
                    + cz
                    + "], \"chests\": ["
                    + String.join(", ", chests)
                    + "], \"chests_unpredicted\": ["
                    + String.join(", ", chestsUnpredicted)
                    + "], \"pieces\": \""
                    + WorldgenProbe.jsonEscape(String.join("; ", parts))
                    + "\", \"n\": "
                    + comps.size()
                    + "}");
        }
        return out;
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
