package com.gtnhspeedrun.worldgenprobe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecartChest;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.MapGenMineshaft;
import net.minecraft.world.gen.structure.StructureBoundingBox;

/**
 * Stage-0 mineshaft module: trigger cells, real structure starts, and — the part that exists for the hammer%
 * hunt — corridor minecart-chest loot, all without creating a WorldServer.
 *
 * <p>
 * Nothing here reimplements the mineshaft. The generator is the pack's own ({@code TerrainGen.getModdedMapGen},
 * exactly how {@code ChunkGeneratorRealistic} builds it — RWG passes no config map, so the vanilla 0.004 chance
 * stands unless a mod replaced the class). Starts come from dispatching the real {@code func_151539_a}; loot
 * comes from replaying the real {@code StructureStart.generateStructure} per populate window against the
 * {@link Prefilter.SeedProbeWorld} overlay, the same pattern the Witchery replay uses.
 *
 * <h2>Why the trigger replica is exact</h2>
 *
 * {@code MapGenBase.func_151539_a} seeds per cell — {@code setSeed(seed); l=nextLong(); m=nextLong();} then
 * {@code setSeed(cx*l ^ cz*m ^ seed)} — and {@code MapGenStructure.func_151538_a} takes ONE warm-up
 * {@code nextInt()} before {@code canSpawnStructureAtCoords}, which for a mineshaft is
 * {@code nextDouble() < chance && nextInt(80) < max(|cx|,|cz|)}. Two silent-miss traps, both hit and both now
 * guarded: the warm-up draw (a replica without it produces a self-consistent wrong lattice), and the RANDOM
 * IMPLEMENTATION — on GTNH, Hodgepodge's FAST_RANDOM swaps {@code MapGenBase.rand} for {@code StdLCG}, whose
 * raw {@code setSeed} changes every draw after a reseed (see {@link RawSeedRandom}). The live {@code rand}
 * field's class picks the semantics, and {@link #goldenCheck} cross-checks the whole protocol once per process
 * against a real dispatch pass; an unrecognised generator or Random enumerates by dispatch instead.
 *
 * <p>
 * Near-origin suppression is vanilla: {@code nextInt(80) < max(|cx|,|cz|)} means the per-chunk rate within 20
 * chunks of origin is at most {@code 0.004 * 20/80} — mineshafts near spawn are rare by design, which is exactly
 * why the trigger scan doubles as a cheap kill gate for the hammer% funnel.
 *
 * <h2>What the loot sim reproduces, and what it approximates</h2>
 *
 * Corridor chest CONTENTS and roll count are exact under the fix jar: {@code Corridor.generateStructureChestContents}
 * fills an {@link EntityMinecartChest} through {@code WeightedRandomChestContent.generateChestContents}, whose
 * F10 refill ({@code ChestFillContext}) re-derives both from the box-relative piece fork — a pure function of
 * (worldSeed, piece class, box XZ origin, minecart-local XZ). The fix jar is loaded in the prefilter process, and
 * {@code StructureStartPartsMixin} wraps the very {@code addComponentParts} call this sim dispatches, so the
 * refill happens here exactly as in a full run.
 *
 * <p>
 * Chest EXISTENCE is two {@code nextInt(100)} draws per corridor section off the populate stream, plus a liquid
 * check and an air check against the live world. This sim reseeds each window's Random with RWG's populate
 * formula and zero {@code PopulateChunkEvent.Pre} draws, replays windows in ascending (cx, cz) order, and reads
 * terrain overlay-first-then-virgin — a documented approximation whose in-game error the fix jar's
 * {@code MineshaftCorridorChestMixin} pin closes for the draws and the multiseed validation measures for the
 * rest. Per-start diagnostics (windows run, components removed) are emitted so residual route terms are visible
 * per seed rather than averaged away.
 *
 * <h2>The Et Futurum mesa variant</h2>
 *
 * GTNH's registered generator is {@code ganymedes01.etfuturum.world.structure.MapGenMesaMineshaft}
 * (bytecode-checked on etfuturum-2.6.2.25-GTNH): it overrides ONLY {@code getStructureStart} — mesa-biome cells
 * get {@code StructureMesaMineshaftStart} with its own pieces, everything else gets the vanilla start — and
 * inherits the trigger formula, so the arithmetic replica stays valid. Two consequences for mesa-biome starts:
 * the biome test reads {@code World.getBiomeGenForCoords}, which this worldless pass answers from the chunk
 * manager while a real run answers from the generated chunk (the RWG disagreement the Witchery module measured),
 * so a start on a mesa BORDER can resolve to different pieces here than in-game; and
 * {@code MineshaftCorridorChestMixin} pins only the vanilla {@code Corridor}, so MesaCorridor chest existence
 * stays stream-derived. Mesa near spawn is rare on this pack; a hunt should treat a mesa-biome mineshaft as
 * unpredicted rather than trusted.
 */
final class MineshaftPrefilter {

    private static Object GEN;
    private static java.lang.reflect.Method GENERATE; // func_151539_a — the real per-chunk entry point
    private static Field WORLD_OBJ;
    private static Field STRUCTURE_MAP; // field_75053_d
    private static Field STRUCTURE_DATA; // field_143029_e — must reset per seed
    private static java.lang.reflect.Method START_GENERATE; // StructureStart.generateStructure
    private static Field START_COMPONENTS; // field_75075_a
    private static Field SECTION_COUNT; // Corridor.sectionCount
    private static double chance = 0.004;
    private static boolean vanillaClass;
    private static boolean rawSetSeed; // Hodgepodge StdLCG semantics on MapGenBase.rand — see triggers()
    private static boolean goldenDone;
    private static String unavailable;
    private static boolean resolved;

    /**
     * Hodgepodge's {@code StdLCG} semantics, for the trigger replica when the pack's FAST_RANDOM speedup is on
     * ({@code SpeedupsConfig.fastRandom} swaps {@code MapGenBase.rand} for
     * {@code com.mitchej123.hodgepodge.util.StdLCG} — Hodgepodge sources in all-gtnh). Same LCG constants as
     * {@code java.util.Random}, one load-bearing difference: {@code setSeed} stores the seed RAW, without the
     * {@code ^ 0x5DEECE66D} scramble — so every per-cell reseed in the trigger protocol lands in a different
     * state than a stock Random, and a stock replica produces a complete, self-consistent, WRONG lattice
     * (measured: 8 replica cells, 0 dispatched starts at them, seed 6638590251101633602). All the derived draws
     * (nextInt/nextLong/nextDouble) route through {@link #next}, exactly as StdLCG relies on.
     */
    private static final class RawSeedRandom extends Random {

        private long s;

        @Override
        public synchronized void setSeed(long seed) {
            this.s = seed;
        }

        @Override
        protected int next(int bits) {
            s = (s * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
            return (int) (s >>> (48 - bits));
        }
    }

    private MineshaftPrefilter() {}

    /** Null when the module is usable; otherwise the reason, reported instead of a silent blank. */
    static synchronized String resolve() {
        if (resolved) return unavailable;
        resolved = true;
        try {
            GEN = net.minecraftforge.event.terraingen.TerrainGen.getModdedMapGen(
                new MapGenMineshaft(),
                net.minecraftforge.event.terraingen.InitMapGenEvent.EventType.MINESHAFT);
            GENERATE = Prefilter.findMethod(
                GEN.getClass(),
                new String[] { "func_151539_a", "generate" },
                net.minecraft.world.chunk.IChunkProvider.class,
                World.class,
                int.class,
                int.class,
                net.minecraft.block.Block[].class);
            WORLD_OBJ = Prefilter.findField(GEN.getClass(), "field_75039_c", "worldObj");
            STRUCTURE_MAP = Prefilter.findField(GEN.getClass(), "field_75053_d", "structureMap");
            STRUCTURE_DATA = Prefilter.findField(GEN.getClass(), "field_143029_e");
            // The replica is valid iff canSpawnStructureAtCoords is VANILLA's — the class itself need not be.
            // GTNH runs Et Futurum's MapGenMesaMineshaft, which overrides only getStructureStart (mesa-biome
            // pieces; bytecode-checked on etfuturum-2.6.2.25-GTNH) and inherits the trigger formula unchanged.
            // An exact-class test here silently downgraded the whole funnel front to dispatch-only.
            Class<?> canSpawnDeclarer = null;
            outer: for (Class<?> c = GEN.getClass(); c != null; c = c.getSuperclass()) {
                for (final String name : new String[] { "func_75047_a", "canSpawnStructureAtCoords" }) {
                    try {
                        c.getDeclaredMethod(name, int.class, int.class);
                        canSpawnDeclarer = c;
                        break outer;
                    } catch (NoSuchMethodException ignored) {}
                }
            }
            vanillaClass = canSpawnDeclarer == MapGenMineshaft.class;
            String randClass = "?";
            if (vanillaClass) {
                // Read the LIVE chance, never assume 0.004: a config map or a reflective tweak would change it
                // and the replica would silently scan a different lattice than the pack generates.
                final Field f = Prefilter.findField(GEN.getClass(), "field_82673_e");
                chance = f.getDouble(GEN);
                // Which Random the loaded generator actually draws from decides the replica's setSeed
                // semantics. Detected from the live field, not assumed from configs: Hodgepodge's FAST_RANDOM
                // lever swaps it for StdLCG (raw setSeed), stock is java.util.Random, and anything else means
                // an unknown stream — dispatch-only rather than a plausible wrong lattice.
                final Object rnd = Prefilter.findField(GEN.getClass(), "field_75038_b", "rand")
                    .get(GEN);
                randClass = rnd.getClass()
                    .getName();
                if (rnd.getClass() == Random.class) {
                    rawSetSeed = false;
                } else if (randClass.endsWith(".StdLCG")) {
                    rawSetSeed = true;
                } else {
                    vanillaClass = false;
                }
            }
            WorldgenProbe.LOG.info(
                "[prefilter] mineshaft generator class: {}{}",
                GEN.getClass()
                    .getName(),
                vanillaClass
                    ? " (vanilla canSpawn, chance " + chance
                        + ", arithmetic triggers, rand "
                        + randClass
                        + (rawSetSeed ? " raw-seed" : " scrambled-seed")
                        + ")"
                    : " (canSpawn " + (canSpawnDeclarer == null ? "?" : "by " + canSpawnDeclarer.getName())
                        + ", rand "
                        + randClass
                        + ": dispatch-only)");
        } catch (Throwable t) {
            unavailable = "mineshaft generator unavailable: " + t;
            WorldgenProbe.LOG.warn("[prefilter] {}", unavailable);
        }
        return unavailable;
    }

    /** Whether the arithmetic trigger replica is valid for the resolved generator (vanilla class, no drift). */
    static boolean arithmetic() {
        return vanillaClass;
    }

    /**
     * Dispatch lattice for a non-vanilla generator: {@code func_151539_a} scans a {@code ±range} (8)
     * neighbourhood around its cell, so centres every 17 cells cover the window completely and every start
     * within it gets registered without a per-cell dispatch.
     */
    static List<int[]> latticeCells(int cx0, int cz0, int radius) {
        final List<int[]> out = new ArrayList<>();
        for (int cx = cx0 - radius; cx <= cx0 + radius + 16; cx += 17) {
            for (int cz = cz0 - radius; cz <= cz0 + radius + 16; cz += 17) {
                out.add(new int[] { cx, cz });
            }
        }
        return out;
    }

    /**
     * Trigger cells in a square of {@code radius} chunks around {@code (cx0, cz0)}, by pure arithmetic. See the
     * class javadoc for the protocol; the warm-up {@code nextInt()} is load-bearing.
     */
    static List<int[]> triggers(long seed, int cx0, int cz0, int radius) {
        final List<int[]> out = new ArrayList<>();
        final Random r = rawSetSeed ? new RawSeedRandom() : new Random();
        r.setSeed(seed);
        final long l = r.nextLong();
        final long m = r.nextLong();
        for (int cx = cx0 - radius; cx <= cx0 + radius; cx++) {
            for (int cz = cz0 - radius; cz <= cz0 + radius; cz++) {
                r.setSeed((long) cx * l ^ (long) cz * m ^ seed);
                r.nextInt(); // func_151538_a's warm-up draw
                if (r.nextDouble() < chance && r.nextInt(80) < Math.max(Math.abs(cx), Math.abs(cz))) {
                    out.add(new int[] { cx, cz });
                }
            }
        }
        return out;
    }

    /** One real structure start, with enough shape summary to gate on before paying for loot. */
    static final class Start {

        int cx;
        int cz;
        Object start; // the real StructureStart — generateStructure mutates it, so simulate() runs once per start
        int[] bbox; // {minX, minY, minZ, maxX, maxY, maxZ}
        int pieces;
        int corridors;
        /** Static upper bound on corridor chests: two existence draws per section. */
        int chestCandidates;
    }

    /**
     * Real starts for the given trigger cells, via dispatch — {@code getStructureStart} builds the whole piece
     * graph from the cell's Random alone ({@code StructureMineshaftStart}/{@code markAvailableHeight} make zero
     * world reads, verified on the RFG sources), so this generates no chunks and MUST run before any chunk
     * generation: {@code func_143027_a} reloads from the shared per-world "Mineshaft" storage that RWG's own
     * mineshaft mapgen populates during terrain gen, which would hand this pass starts it never asked about
     * (same leak shape as {@code Prefilter.villageStarts}).
     */
    static List<Start> starts(World world, long seed, List<int[]> cells) throws Exception {
        WORLD_OBJ.set(GEN, world);
        final Map<?, ?> structureMap = (Map<?, ?>) STRUCTURE_MAP.get(GEN);
        structureMap.clear();
        STRUCTURE_DATA.set(GEN, null);
        for (final int[] c : cells) {
            GENERATE.invoke(GEN, null, world, c[0], c[1], null);
        }
        if (!goldenDone && vanillaClass && !cells.isEmpty()) {
            goldenDone = true;
            goldenCheck(seed, cells, structureMap);
        }
        final List<Start> out = new ArrayList<>();
        for (final Map.Entry<?, ?> e : structureMap.entrySet()) {
            final long key = ((Number) e.getKey()).longValue(); // ChunkCoordIntPair.chunkXZ2Int
            final Start s = new Start();
            s.cx = (int) key;
            s.cz = (int) (key >> 32);
            s.start = e.getValue();
            if (START_COMPONENTS == null) {
                START_COMPONENTS = Prefilter.findField(s.start.getClass(), "field_75075_a", "components");
            }
            final StructureBoundingBox bb = (StructureBoundingBox) Prefilter
                .findMethod(s.start.getClass(), new String[] { "func_75071_a", "getBoundingBox" })
                .invoke(s.start);
            s.bbox = new int[] { bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ };
            final List<?> comps = (List<?>) START_COMPONENTS.get(s.start);
            s.pieces = comps.size();
            for (final Object comp : comps) {
                if (!comp.getClass()
                    .getSimpleName()
                    .equals("Corridor")) continue;
                s.corridors++;
                try {
                    if (SECTION_COUNT == null) {
                        SECTION_COUNT = Prefilter.findField(comp.getClass(), "field_74960_a", "sectionCount");
                    }
                    s.chestCandidates += 2 * SECTION_COUNT.getInt(comp);
                } catch (Throwable ignored) {
                    // section count is a diagnostic; a rename costs the count, not the sim
                }
            }
            out.add(s);
        }
        return out;
    }

    /**
     * One-time drift guard for the arithmetic replica: over the dispatched cells, every replica hit must have a
     * start and every start at a dispatched cell must be a replica hit. A mismatch is announced loudly and
     * downgrades the module to dispatch-only for the rest of the process — a wrong lattice that kept running
     * would kill seeds that have mineshafts, silently.
     */
    private static void goldenCheck(long seed, List<int[]> cells, Map<?, ?> structureMap) {
        final java.util.Set<Long> replica = new java.util.HashSet<>();
        for (final int[] c : cells) replica.add(((long) c[1] << 32) | (c[0] & 0xFFFFFFFFL));
        // Dispatch scanned a ±range neighbourhood around every cell, so starts OUTSIDE the replica's cell list
        // are expected; only disagreement AT a dispatched cell is drift.
        int missing = 0;
        for (final Long k : replica) {
            final long mapKey = net.minecraft.world.ChunkCoordIntPair
                .chunkXZ2Int((int) (k & 0xFFFFFFFFL), (int) (k >> 32));
            if (!structureMap.containsKey(mapKey)) missing++;
        }
        if (missing > 0) {
            vanillaClass = false; // downgrade: dispatch-only from here on
            // Both lattices, so the drift's SHAPE is visible in the log: a constant offset, a different
            // stride, or an unrelated set each point at a different culprit.
            final StringBuilder rep = new StringBuilder();
            for (final int[] c : cells) rep.append('(')
                .append(c[0])
                .append(',')
                .append(c[1])
                .append(") ");
            final StringBuilder got = new StringBuilder();
            for (final Object k : structureMap.keySet()) {
                final long kk = ((Number) k).longValue();
                got.append('(')
                    .append((int) kk)
                    .append(',')
                    .append((int) (kk >> 32))
                    .append(") ");
            }
            WorldgenProbe.LOG.error("[prefilter] replica cells: {}", rep);
            WorldgenProbe.LOG.error("[prefilter] dispatched starts: {}", got);
            WorldgenProbe.LOG.error(
                "[prefilter] mineshaft trigger replica DRIFTED: {} of {} replica cells have no dispatched start "
                    + "(seed {}). Falling back to dispatch-only enumeration.",
                missing,
                replica.size(),
                seed);
        } else {
            WorldgenProbe.LOG
                .info("[prefilter] mineshaft trigger replica golden-checked: {} cells, 0 drift", replica.size());
        }
    }

    /** Loot and diagnostics from replaying one start's populate windows. */
    static final class SimResult {

        int windows;
        int componentsRemoved;
        /** dumpInventory JSON per captured minecart chest — same serializer as everything else. */
        final List<String> chests = new ArrayList<>();
        /** {x, y, z, steel} per captured minecart, for distance-aware gating without JSON re-parsing. */
        final List<int[]> chestSteel = new ArrayList<>();
        final int[] counts = new int[LootCounts.N];
        String error;
    }

    /**
     * Replays the real {@code generateStructure} over every populate window intersecting the start's bbox, in
     * canonical ascending (cx, cz) order, each window fed a Random reseeded with RWG's populate formula
     * ({@code setSeed(seed); i1=nextLong()/2*2+1; j1=nextLong()/2*2+1; setSeed(cx*i1 + cz*j1 ^ seed)}).
     *
     * <p>
     * Writes land in the {@link Prefilter.SeedProbeWorld} overlay (the Witchery/Oil pattern — mineshafts write
     * through {@code World}, not {@code IWorldEditor}); the corridor's own carve is visible to its later air
     * checks because reads answer overlay-first. Minecart chests are captured from the overlay's entity list
     * with their final contents: the F10 refill runs inside {@code generateChestContents}, before
     * {@code spawnEntityInWorld}.
     *
     * <p>
     * MUTATES the start: {@code generateStructure} removes components whose liquid check fails, exactly as a
     * real run does. Call once per start.
     */
    static SimResult simulate(Prefilter.SeedProbeWorld world, long seed, Start s) {
        final SimResult r = new SimResult();
        try {
            if (START_GENERATE == null) {
                START_GENERATE = Prefilter.findMethod(
                    s.start.getClass(),
                    new String[] { "func_75068_a", "generateStructure" },
                    World.class,
                    Random.class,
                    StructureBoundingBox.class);
            }
            final int piecesBefore = ((List<?>) START_COMPONENTS.get(s.start)).size();
            final Random popRand = new Random();
            final List<Entity> carts;
            world.beginOverlay();
            try {
                // Window (k,l) populates box [16k+8, 16l+8, 16k+23, 16l+23]; iterate every window whose box
                // intersects the start bbox, ascending — the sim's canonical order.
                final int k0 = (s.bbox[0] - 23) >> 4, k1 = (s.bbox[3] - 8 + 15) >> 4;
                final int l0 = (s.bbox[2] - 23) >> 4, l1 = (s.bbox[5] - 8 + 15) >> 4;
                for (int k = k0; k <= k1; k++) {
                    for (int l = l0; l <= l1; l++) {
                        final int bx0 = k * 16 + 8, bz0 = l * 16 + 8;
                        if (bx0 + 15 < s.bbox[0] || bx0 > s.bbox[3] || bz0 + 15 < s.bbox[2] || bz0 > s.bbox[5]) {
                            continue;
                        }
                        popRand.setSeed(seed);
                        final long i1 = popRand.nextLong() / 2L * 2L + 1L;
                        final long j1 = popRand.nextLong() / 2L * 2L + 1L;
                        popRand.setSeed((long) k * i1 + (long) l * j1 ^ seed);
                        START_GENERATE
                            .invoke(s.start, world, popRand, new StructureBoundingBox(bx0, bz0, bx0 + 15, bz0 + 15));
                        r.windows++;
                    }
                }
            } finally {
                carts = world.overlayEntities();
                world.endOverlay();
            }
            r.componentsRemoved = piecesBefore - ((List<?>) START_COMPONENTS.get(s.start)).size();
            for (final Entity e : carts) {
                if (!(e instanceof EntityMinecartChest)) continue;
                final EntityMinecartChest cart = (EntityMinecartChest) e;
                final int x = net.minecraft.util.MathHelper.floor_double(cart.posX);
                final int y = net.minecraft.util.MathHelper.floor_double(cart.posY);
                final int z = net.minecraft.util.MathHelper.floor_double(cart.posZ);
                r.chests.add(WorldgenProbe.dumpInventory(cart, x, y, z, "EntityMinecartChest"));
                final int[] c = LootCounts.count(cart);
                LootCounts.addInto(r.counts, c);
                r.chestSteel.add(new int[] { x, y, z, c[LootCounts.STEEL] });
            }
        } catch (Throwable t) {
            r.error = t.toString();
        }
        return r;
    }
}
