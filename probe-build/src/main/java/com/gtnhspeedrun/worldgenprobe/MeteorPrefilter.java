package com.gtnhspeedrun.worldgenprobe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.init.Blocks;

/**
 * Stage-0 AE2 meteorite module: per grid cell, the candidate's position, size roll and predicted crater —
 * ranked by how many blocks the crater paraboloid would delete from virgin terrain. Exists for the
 * biggest-crater hunt: the size roll caps just under 8 everywhere, so the visual ceiling is set by tall
 * terrain adjacent to the impact, and the score is literally the carved volume.
 *
 * <h2>Why the RNG replica is exact</h2>
 *
 * AE2-GTNH meteorite siting is grid-seeded and seed-pure (the GTNH fork's own determinism work —
 * {@code Platform.seedFromGrid}, one candidate per {@code minMeteoriteDistance} cell). This module calls the
 * REAL {@code Platform.seedFromGrid} reflectively on a scratch {@code java.util.Random} — static and
 * worldless, so there is nothing to drift from — then consumes the exact draw sequence of
 * {@code MeteoriteWorldGen.generate}: the spawn-chance loop (one {@code nextDouble} per matching config
 * entry, replicated with the same double-branch quirk), {@code nextInt(cell - 2*margin)} twice for X/Z,
 * {@code nextInt(20)} for the surface start depth, {@code nextLong} for the placer seed (redrawn while 0).
 * The 0.3 roll does NOT gate existence — nothing guards the {@code addCallable} — it only picks the start
 * depth, {@code 180+n} against {@code 128} (0.13.0 changelog correction). Size is then the placer's first
 * draw, {@code new Random(meteorSeed).nextDouble()*6+2}; the second, {@code nextFloat() > 0.9}, is the
 * crater lava flag.
 *
 * <h2>What the terrain model replicates, and what it approximates</h2>
 *
 * Landing y replays {@code tryMeteorite}'s descent for real: each −15 rung tests the actual virgin block
 * against the placer's actual {@code validSpawn} set (both read reflectively after invoking
 * {@code initializeSpawnLists}, so config whitelists are honoured), then both {@code spawnMeteoriteCenter}
 * box scans — the 12³ realValidBlocks > 80 count and the 30³ scan with its invalidSpawn abort and
 * validBlocks > 200 count — via {@code world.getBlock}, which the {@link Prefilter.VirginChunkProvider}
 * answers with virgin terrain: exactly what the fix jar's {@code Ae2MeteoriteSitingMixin} routes those same
 * three call sites to in-game. This is why the golden seed's Redwood Forest candidate lands buried at 68
 * here and in-game, not at the naive lattice rung 83 — the BoP surface block is not in validSpawn.
 *
 * <p>
 * The crater gate is the one remaining approximation: in-game it is {@code skyMode > 10} computed on LIVE
 * terrain (not covered by the siting mixin). Stage 0 uses the burial proxy — crater iff burial <= 10 and the
 * virgin column {@code y-15..y-2} is solid (the constructor's {@code solid} check, which zeroes skyMode over
 * a cave). 0.13.0 measured skyMode never landing in 1..10 across 50 observations, so the proxy only misses
 * where populate MOVES the surface — e.g. the golden seed's (1084,363), where a lake cut the hill from 97
 * to 88 and a crater exists that virgin terrain says is buried. That direction is a missed hit, not a false
 * one; the full-probe verification pass owns it.
 *
 * <p>
 * The carve itself is {@code placeCrater}: air wherever {@code j > h + 0.02*d²},
 * {@code h = y − size + 1 + adjustCrater}, from y−5 up to 255 over a ±200 box. Volume here integrates
 * {@code topSolid − cut} over columns within the scan disc — blocks above the paraboloid that virgin terrain
 * actually has. Fallout type (the +2 {@code adjustCrater} of sand/snow) is taken from the block the center
 * lands IN: only a surface landing reads the digest's top block, a buried center is stone and gets the
 * default 0.
 */
final class MeteorPrefilter {

    private static java.lang.reflect.Method SEED_FROM_GRID;
    private static java.util.Collection<?> VALID_SPAWN;
    private static java.util.Collection<?> INVALID_SPAWN;
    private static int cellSize = 707;
    private static int cellMargin = 70;
    private static String[] spawnChance = { "0=0.3" };
    private static String unavailable;
    private static boolean resolved;

    private MeteorPrefilter() {}

    /** Null when the module is usable; otherwise the reason, reported instead of a silent blank. */
    static synchronized String resolve() {
        if (resolved) return unavailable;
        resolved = true;
        try {
            SEED_FROM_GRID = Class.forName("appeng.util.Platform")
                .getMethod("seedFromGrid", Random.class, long.class, long.class, long.class);
            // The pack's live values, parsed the way MeteoriteWorldGen.generate parses them (dim 0 with the
            // "0=" fallback; Math.max(8, cell)). The shipped GTNH cfg entries are the pre-per-dimension
            // scalar format, which Forge discards on type mismatch, so these normally come out at the AE2
            // code defaults 707/0.3 — read live anyway so a config change cannot silently split the replica
            // from the game.
            final Object cfg = Class.forName("appeng.core.AEConfig")
                .getField("instance")
                .get(null);
            final Field fDist = cfg.getClass()
                .getField("minMeteoriteDistance");
            final Field fChance = cfg.getClass()
                .getField("meteoriteSpawnChance");
            final String[] dist = (String[]) fDist.get(cfg);
            spawnChance = ((String[]) fChance.get(cfg)).clone();
            int cell = -1;
            for (final String entry : dist) {
                final String[] split = entry.split("=");
                if (split[0].equals("0")) cell = (int) Double.parseDouble(split[1]);
            }
            if (cell < 0) {
                unavailable = "no 0= entry in minMeteoriteDistance";
                return unavailable;
            }
            cellSize = Math.max(8, cell);
            cellMargin = Math.max(1, cellSize / 10);
            // The placer's REAL spawn lists (statics, lazily filled — force the fill so config
            // whitelists are honoured). The descent replay tests membership against these.
            final Class<?> placer = Class.forName("appeng.worldgen.MeteoritePlacer");
            final java.lang.reflect.Method init = placer.getDeclaredMethod("initializeSpawnLists");
            init.setAccessible(true);
            init.invoke(null);
            final Field fValid = placer.getDeclaredField("validSpawn");
            final Field fInvalid = placer.getDeclaredField("invalidSpawn");
            fValid.setAccessible(true);
            fInvalid.setAccessible(true);
            VALID_SPAWN = (java.util.Collection<?>) fValid.get(null);
            INVALID_SPAWN = (java.util.Collection<?>) fInvalid.get(null);
            if (VALID_SPAWN.isEmpty()) {
                unavailable = "validSpawn empty after initializeSpawnLists";
            }
        } catch (Throwable t) {
            unavailable = t.toString();
        }
        return unavailable;
    }

    static int cellSize() {
        return cellSize;
    }

    /** One per grid cell; terrain fields are filled by {@link #site} and {@link #score}. */
    static final class Candidate {

        int gx, gz; // grid cell
        int x, z; // impact column
        int startDepth; // 180..199 surface branch, 128 deep branch
        boolean surfaceBranch;
        long meteorSeed;
        double size; // 2..8
        boolean lava;
        // terrain (site/score)
        int surface = -1; // virgin topSolid at (x, z)
        int y = -1; // predicted landing (descent lattice)
        int burial = -1; // surface - y
        int relief = -1; // max topSolid over the 5x5 peek minus surface
        long volume; // carved blocks within the scan disc
        int maxbite; // deepest single-column carve
        int enclosure = -1; // of 16 compass sectors (ring 24..volradius), how many reach surface+30
        String biome; // base biome at impact (chunk-manager lookup, no chunkgen) — for gate tuning
        int proxyRelief = -1; // analytic screen: max ring rNoise minus impact rNoise, no chunkgen
        int proxyOpposed = -1; // sectors 90+ above impact whose OPPOSITE (+-1) sector is also 90+ — both-flank walls
        String reject; // water | deep | buried | flat — emitted, not silently dropped
    }

    /**
     * Pure-arithmetic candidate per grid cell in the ±{@code cells} square around the origin cell. No world,
     * no chunk generation — the whole scan is microseconds against the terrain stages that follow.
     */
    static List<Candidate> candidates(long worldSeed, int cells) throws Exception {
        final List<Candidate> out = new ArrayList<>();
        final Random rng = new Random();
        final int bound = cellSize - 2 * cellMargin;
        for (int gx = -cells; gx <= cells; gx++) {
            for (int gz = -cells; gz <= cells; gz++) {
                SEED_FROM_GRID.invoke(null, rng, worldSeed, (long) gx, (long) gz);
                // MeteoriteWorldGen's spawn-chance loop, verbatim for dim 0 including the fallback branch
                // that can only fire when no "0=" entry was seen yet — the draw COUNT is the contract here.
                int check = -3124;
                for (final String entry : spawnChance) {
                    final String[] split = entry.split("=");
                    if (split[0].equals("0")) {
                        check = (rng.nextDouble() < Double.parseDouble(split[1])) ? 1 : 0;
                    }
                    if (split[0].equals("0") && check == -3124) {
                        check = (rng.nextDouble() < Double.parseDouble(split[1])) ? 1 : 0;
                    }
                }
                final Candidate c = new Candidate();
                c.gx = gx;
                c.gz = gz;
                c.surfaceBranch = check == 1;
                c.x = gx * cellSize + rng.nextInt(bound) + cellMargin;
                c.z = gz * cellSize + rng.nextInt(bound) + cellMargin;
                final int meteorDepth = 180 + rng.nextInt(20); // drawn unconditionally, used on one branch
                c.startDepth = c.surfaceBranch ? meteorDepth : 128;
                long meteorSeed = rng.nextLong();
                while (meteorSeed == 0) meteorSeed = rng.nextLong();
                c.meteorSeed = meteorSeed;
                final Random placer = new Random(meteorSeed);
                c.size = placer.nextDouble() * 6.0 + 2;
                c.lava = placer.nextFloat() > 0.9F;
                out.add(c);
            }
        }
        return out;
    }

    /**
     * The terrain peek: relief over 3x3 chunks (digest), then tryMeteorite's descent replayed for real —
     * every rung's center block and both spawnMeteoriteCenter box scans against the placer's real spawn
     * lists, all through {@code world.getBlock} = virgin terrain. Relief killing happens in the caller
     * BEFORE the volume disc pays ~10x the chunks; the descent's 3x3-chunk reads are the same chunks the
     * peek already digested.
     */
    static void site(Candidate c, Prefilter.RwgTerrain terra, net.minecraft.world.World world, int gateRelief)
        throws Exception {
        final int icx = Math.floorDiv(c.x, 16), icz = Math.floorDiv(c.z, 16);
        c.surface = topSolidAt(terra, c.x, c.z);
        // Three-stage peek: 1 chunk -> 3x3 -> 5x5 ring. The relief gate must SEE a massif whose wall is
        // 50-80 blocks out — a ravine floor inside a mountain complex is exactly the highest-volume
        // geometry, and a small peek calls it flat — but chunkgen is the whole cost of this module (a full
        // 5x5 for every size-passing candidate measured 0.5 seeds/s), and most candidates sit on plains.
        // Stage 0 is the impact chunk alone: any massif within peek reach shows >= 5 blocks of slope inside
        // that one chunk on RWG terrain, so a dead-flat impact chunk kills the candidate for one chunkgen
        // instead of nine. The later stages widen only while relief keeps appearing (>= 8 to leave 3x3). A
        // candidate rejected flat also skips the descent.
        int maxTop = 0;
        {
            final Prefilter.RwgTerrain.Cols cols = terra.columns(icx, icz);
            for (int col = 0; col < 256; col++) {
                if (cols.topSolid[col] > maxTop) maxTop = cols.topSolid[col];
            }
        }
        // staging cuts scale with the gate: a verification run at gateRelief=0 must reach the descent
        // and the volume disc even for walls the near peeks cannot see (measured: two 300k+ sites whose
        // walls sit 70-96 blocks out were false-rejected by the hardcoded <5 cut)
        if (maxTop - c.surface < Math.min(5, gateRelief)) {
            c.relief = maxTop - c.surface;
            c.reject = "flat";
            return;
        }
        for (int cx = icx - 1; cx <= icx + 1; cx++) {
            for (int cz = icz - 1; cz <= icz + 1; cz++) {
                if (cx == icx && cz == icz) continue;
                final Prefilter.RwgTerrain.Cols cols = terra.columns(cx, cz);
                for (int col = 0; col < 256; col++) {
                    if (cols.topSolid[col] > maxTop) maxTop = cols.topSolid[col];
                }
            }
        }
        if (maxTop - c.surface >= Math.min(8, gateRelief)) {
            for (int cx = icx - 2; cx <= icx + 2; cx++) {
                for (int cz = icz - 2; cz <= icz + 2; cz++) {
                    if (cx > icx - 2 && cx < icx + 2 && cz > icz - 2 && cz < icz + 2) continue; // ring only
                    final Prefilter.RwgTerrain.Cols cols = terra.columns(cx, cz);
                    for (int col = 0; col < 256; col++) {
                        if (cols.topSolid[col] > maxTop) maxTop = cols.topSolid[col];
                    }
                }
            }
        }
        c.relief = maxTop - c.surface;
        if (c.relief < gateRelief) {
            c.reject = "flat";
            return;
        }
        // tryMeteorite: 20 tries stepping -15, give up below 40 (the 40-floor always binds first). A rung
        // fails on center block, on the 12-cube realValidBlocks count, or on the 30-cube invalid/valid scan
        // — any failure descends one more rung, exactly like spawnMeteoriteCenter returning false.
        int landed = -1;
        for (int depth = c.startDepth; depth >= 40; depth -= 15) {
            if (!VALID_SPAWN.contains(world.getBlock(c.x, depth, c.z))) continue;
            if (!boxesPass(world, c.x, depth, c.z)) continue;
            landed = depth;
            break;
        }
        if (landed < 0) {
            c.reject = "nosite"; // no meteorite at all in-game
            return;
        }
        c.y = landed;
        c.burial = c.surface - landed;
        // skyMode proxy, slope-aware: the constructor's box spans +-15 horizontally and reaches y+10, so
        // "any part of the box reaches open air" is landing+10 clearing the MIN surface in the box, not
        // the center column. Field-verified: a cliff-foot site with center burial 14 carves in-game — the
        // downhill half of the box is open sky (seed 5550213969531321496, (-376,186)).
        int minTop = Integer.MAX_VALUE;
        for (int cx = icx - 1; cx <= icx + 1; cx++) {
            for (int cz = icz - 1; cz <= icz + 1; cz++) {
                final Prefilter.RwgTerrain.Cols cols = terra.columns(cx, cz);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        final int wx = (cx << 4) + lx, wz = (cz << 4) + lz;
                        if (Math.abs(wx - c.x) <= 15 && Math.abs(wz - c.z) <= 15
                            && cols.topSolid[(lx << 4) | lz] < minTop) {
                            minTop = cols.topSolid[(lx << 4) | lz];
                        }
                    }
                }
            }
        }
        if (landed + 10 < minTop) {
            c.reject = "buried"; // no sky anywhere in the box
        } else {
            // the constructor's solid check: air anywhere in the column y-15..y-2 zeroes skyMode outright
            for (int j = landed - 15; j < landed - 1; j++) {
                if (world.getBlock(c.x, j, c.z) == net.minecraft.init.Blocks.air) {
                    c.reject = "cave";
                    break;
                }
            }
        }
    }

    /** spawnMeteoriteCenter's two box scans, virgin. Bounds are AE2's own half-open ±6 / ±15 loops. */
    private static boolean boxesPass(net.minecraft.world.World world, int x, int y, int z) {
        int realValid = 0;
        for (int i = x - 6; i < x + 6; i++) {
            for (int j = y - 6; j < y + 6; j++) {
                for (int k = z - 6; k < z + 6; k++) {
                    if (VALID_SPAWN.contains(world.getBlock(i, j, k))) realValid++;
                }
            }
        }
        if (realValid <= 80) return false;
        int valid = 0;
        for (int i = x - 15; i < x + 15; i++) {
            for (int j = y - 15; j < y + 15; j++) {
                for (int k = z - 15; k < z + 15; k++) {
                    final net.minecraft.block.Block b = world.getBlock(i, j, k);
                    if (INVALID_SPAWN.contains(b)) return false;
                    if (VALID_SPAWN.contains(b)) valid++;
                }
            }
        }
        return valid > 200;
    }

    /**
     * Carved-volume integral over the scan disc. {@code cut} is the carve floor at distance d; a column
     * contributes every block strictly above it up to its virgin surface. adjustCrater +2 only applies when
     * the center actually lands in the digest's top block and it is sand/ice/snow (FalloutSand/FalloutSnow);
     * a buried center is stone, default Fallout, 0.
     */
    static void score(Candidate c, Prefilter.RwgTerrain terra, int volRadius) throws Exception {
        int adjust = 0;
        if (c.burial == 0) {
            final net.minecraft.block.Block top = topBlockAt(terra, c.x, c.z);
            if (top == Blocks.sand || top == Blocks.ice || top == Blocks.snow) adjust = 2;
        }
        final double h = c.y - c.size + 1 + adjust;
        final long r2 = (long) volRadius * volRadius;
        final int icx = Math.floorDiv(c.x, 16), icz = Math.floorDiv(c.z, 16);
        final int chunkR = (volRadius >> 4) + 1;
        long volume = 0;
        int maxbite = 0;
        // Enclosure rides the same loop for free: 16 compass sectors over the 24..volRadius ring, each
        // sector's max surface. A crater in a ravine THROUGH a massif is ringed by high ground on most
        // bearings; a mountain-edge site only on a few. The paraboloid carves per column, so azimuthal
        // coverage multiplies the volume — this is the number that separates the two.
        final int[] sectorHigh = new int[16];
        for (int cx = icx - chunkR; cx <= icx + chunkR; cx++) {
            for (int cz = icz - chunkR; cz <= icz + chunkR; cz++) {
                final Prefilter.RwgTerrain.Cols cols = terra.columns(cx, cz);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        final int wx = (cx << 4) + lx, wz = (cz << 4) + lz;
                        final long dx = wx - c.x, dz = wz - c.z;
                        final long d2 = dx * dx + dz * dz;
                        if (d2 > r2) continue;
                        final int top = cols.topSolid[(lx << 4) | lz];
                        if (d2 >= 24 * 24) {
                            final int sector = ((int) Math.floor((Math.atan2(dz, dx) + Math.PI) / (Math.PI / 8))) & 15;
                            if (top > sectorHigh[sector]) sectorHigh[sector] = top;
                        }
                        // first j strictly above the paraboloid at this distance
                        final int lvl = (int) Math.floor(h + 0.02 * d2) + 1;
                        if (top >= lvl) {
                            final int carved = top - lvl + 1;
                            volume += carved;
                            if (carved > maxbite) maxbite = carved;
                        }
                    }
                }
            }
        }
        c.volume = volume;
        c.maxbite = maxbite;
        int enclosed = 0;
        for (final int high : sectorHigh) {
            if (high >= c.surface + 30) enclosed++;
        }
        c.enclosure = enclosed;
    }

    /**
     * Analytic terrain screen: RWG's height is pure noise math, so evaluate the biome's own rNoise formula
     * (real PerlinNoise/CellNoise instances read off the reflected generator, real getRiverStrength /
     * calculateRiver) at the impact and 16x3 ring samples — ~50 noise calls, no chunk generation. The proxy
     * skips only the biome BLEND kernel (border=1, ocean=1), so it overshoots walls and undershoots
     * blended slopes; it is a SCREEN, not a measurement — the staged chunkgen funnel stays as the exact
     * check behind it, so a threshold calibrated to pass every known 300k+ site can only lose candidates
     * the funnel would also have to see. This is the ~1000x lever: a chunkgen peek costs ~20 ms, this
     * costs ~50 us.
     */
    static final class AnalyticScreen {

        private final Object cmr;
        private final Object perlin, cellN;
        private final java.lang.reflect.Method mBiomeData, mRiver, mCarve, mRNoise;

        AnalyticScreen(net.minecraft.world.World world, Prefilter.RwgTerrain terra) throws Exception {
            cmr = world.getWorldChunkManager();
            final Object gen = terra.generator();
            Object p = null, cn = null;
            for (final Field f : gen.getClass()
                .getDeclaredFields()) {
                if (f.getType()
                    .getName()
                    .toLowerCase()
                    .contains("noise")) {
                    f.setAccessible(true);
                    if ("perlin".equals(f.getName())) p = f.get(gen);
                    if ("cell".equals(f.getName())) cn = f.get(gen);
                }
            }
            if (p == null || cn == null) throw new NoSuchFieldException("rwg generator perlin/cell");
            perlin = p;
            cellN = cn;
            mBiomeData = Prefilter.findMethod(cmr.getClass(), new String[] { "getBiomeDataAt" }, int.class, int.class);
            mRiver = Prefilter.findMethod(cmr.getClass(), new String[] { "getRiverStrength" }, int.class, int.class);
            mCarve = Prefilter.findMethod(
                cmr.getClass(),
                new String[] { "calculateRiver" },
                int.class,
                int.class,
                float.class,
                float.class);
            // resolve on the BASE class: resolving on the concrete (0,0) biome and invoking on a sibling
            // subclass throws "object is not an instance of declaring class"
            java.lang.reflect.Method rn = null;
            for (final java.lang.reflect.Method m : Class.forName("rwg.biomes.realistic.RealisticBiomeBase")
                .getMethods()) {
                if ("rNoise".equals(m.getName())) rn = m;
            }
            if (rn == null) throw new NoSuchMethodException("RealisticBiomeBase.rNoise");
            mRNoise = rn;
        }

        /** Single-biome river-carved height proxy at a column, no chunks. */
        float height(int x, int z) throws Exception {
            final float river = ((Number) mRiver.invoke(cmr, x, z)).floatValue();
            final Object rb = mBiomeData.invoke(cmr, x, z);
            final float h = ((Number) mRNoise.invoke(rb, perlin, cellN, x, z, 1f, 1f, river + 1f)).floatValue();
            return ((Number) mCarve.invoke(cmr, x, z, river, h)).floatValue();
        }

        /**
         * Fills proxyRelief (max over 16 bearings x radii {40,64,88} minus the impact column) and
         * proxyOpposed — the count of sectors >= impact+90 whose OPPOSITE sector (+-1 for the domain warp)
         * is also >= impact+90. Opposed pairs are the both-flanks signature: a river channel cut BETWEEN
         * two mountain-class biomes keeps near-full walls on both sides because the blend kernel averages
         * two TALL neighbours, where the one-sided massif (every champion so far) blends tall against low
         * and loses wall height. This is "mountain-flanked river edge" reduced to the ring samples the
         * screen already pays for.
         */
        void screen(Candidate c) throws Exception {
            final float center = height(c.x, c.z);
            final float[] sector = new float[16];
            float max = center;
            for (int s = 0; s < 16; s++) {
                final double a = s * Math.PI / 8;
                sector[s] = center;
                for (final int r : new int[] { 40, 64, 88 }) {
                    final float h = height(
                        c.x + (int) Math.round(r * Math.cos(a)),
                        c.z + (int) Math.round(r * Math.sin(a)));
                    if (h > sector[s]) sector[s] = h;
                    if (h > max) max = h;
                }
            }
            c.proxyRelief = (int) (max - center);
            int opposed = 0;
            for (int s = 0; s < 16; s++) {
                if (sector[s] - center < 90) continue;
                final int o = s + 8;
                if (sector[o & 15] - center >= 90 || sector[(o + 1) & 15] - center >= 90
                    || sector[(o - 1) & 15] - center >= 90) {
                    opposed++;
                }
            }
            c.proxyOpposed = opposed;
        }
    }

    /**
     * One-time RWG wiring dump for the standalone analytic hunter (-Dprobe.prefilter.meteor.dumprwg=FILE):
     * the chunk manager's five biome band lists in order (biome class, base biome name, terrain class,
     * every numeric/boolean terrain field walked up the hierarchy), the wet/small flags, and the runtime
     * noise classes. The standalone replays biome selection and rNoise from THIS file instead of
     * hand-transcribed wiring — the BoP-biome surprise, made structural.
     */
    static void dumpRwg(net.minecraft.world.World world, Prefilter.RwgTerrain terra, String path) throws Exception {
        final Object cmr = world.getWorldChunkManager();
        final StringBuilder sb = new StringBuilder(16384);
        sb.append("{\"noise_perlin\": \"");
        for (final Field f : terra.generator()
            .getClass()
            .getDeclaredFields()) {
            if ("perlin".equals(f.getName()) || "cell".equals(f.getName())) {
                f.setAccessible(true);
                sb.append(
                    f.get(terra.generator())
                        .getClass()
                        .getName())
                    .append("perlin".equals(f.getName()) ? "\", \"noise_cell\": \"" : "\"");
            }
        }
        for (final String flag : new String[] { "wetEnabled", "smallEnabled" }) {
            final Field f = Prefilter.findField(cmr.getClass(), flag);
            sb.append(", \"")
                .append(flag)
                .append("\": ")
                .append(f.getBoolean(cmr));
        }
        for (final String band : new String[] { "biomes_snow", "biomes_cold", "biomes_hot", "biomes_wet",
            "biomes_small" }) {
            final Field f = Prefilter.findField(cmr.getClass(), band);
            final java.util.List<?> list = (java.util.List<?>) f.get(cmr);
            sb.append(", \"")
                .append(band)
                .append("\": [");
            for (int i = 0; i < list.size(); i++) {
                final Object biome = list.get(i);
                Object terrain = null;
                for (Class<?> c = biome.getClass(); c != null && terrain == null; c = c.getSuperclass()) {
                    for (final Field bf : c.getDeclaredFields()) {
                        if (bf.getType()
                            .getSimpleName()
                            .equals("TerrainBase")) {
                            bf.setAccessible(true);
                            terrain = bf.get(biome);
                            break;
                        }
                    }
                }
                final Object base = Prefilter.findField(biome.getClass(), "baseBiome")
                    .get(biome);
                sb.append(i > 0 ? ", {" : "{")
                    .append("\"biome\": \"")
                    .append(
                        biome.getClass()
                            .getSimpleName())
                    .append("\", \"base\": \"")
                    .append(((net.minecraft.world.biome.BiomeGenBase) base).biomeName.replace("\"", "'"))
                    .append("\", \"terrain\": \"")
                    .append(
                        terrain == null ? "null"
                            : terrain.getClass()
                                .getSimpleName())
                    .append("\", \"fields\": {");
                boolean first = true;
                for (Class<?> c = terrain == null ? null : terrain.getClass(); c != null
                    && !"java.lang.Object".equals(c.getName()); c = c.getSuperclass()) {
                    for (final Field tf : c.getDeclaredFields()) {
                        final Class<?> t = tf.getType();
                        final boolean scalar = t == float.class || t == int.class
                            || t == double.class
                            || t == boolean.class;
                        // arrays too: TerrainCanyon's terrace stack lives in a float[] built by the
                        // args-ctor, which the standalone's no-arg + injection path never runs
                        if (!scalar && t != float[].class && t != int[].class) continue;
                        tf.setAccessible(true);
                        if (!first) sb.append(", ");
                        first = false;
                        sb.append("\"")
                            .append(c.getSimpleName())
                            .append(".")
                            .append(tf.getName())
                            .append("\": ");
                        if (scalar) sb.append(tf.get(terrain));
                        else if (t == float[].class) sb.append(java.util.Arrays.toString((float[]) tf.get(terrain)));
                        else sb.append(java.util.Arrays.toString((int[]) tf.get(terrain)));
                    }
                }
                sb.append("}}");
            }
            sb.append("]");
        }
        sb.append("}\n");
        try (java.io.FileWriter w = new java.io.FileWriter(path)) {
            w.write(sb.toString());
        }
        WorldgenProbe.LOG.info("[meteor] RWG wiring dumped to {}", path);
    }

    /**
     * Worldless biome gate: impact plus 8 ring samples at 56 blocks, matched against lowercase
     * alphanumeric-normalized tokens (robust to "rwg_coldPlains" vs "Cold Plains" naming). Costs
     * microseconds against the chunkgen it spares — the whole point is that the tall-wall terrain classes
     * (clamp 150/155) live on a handful of base biomes and everything else cannot beat the current
     * champion no matter what the dice say. Over-inclusion is fine; a miss is not — tokens are matched
     * against the candidate row's emitted biome so the list is tunable from kill data.
     */
    static boolean biomePass(net.minecraft.world.World world, Candidate c, String[] tokens) {
        for (int s = 0; s < 9; s++) {
            final int sx = s == 0 ? c.x : c.x + (int) Math.round(56 * Math.cos(s * Math.PI / 4));
            final int sz = s == 0 ? c.z : c.z + (int) Math.round(56 * Math.sin(s * Math.PI / 4));
            final net.minecraft.world.biome.BiomeGenBase b = world.getWorldChunkManager()
                .getBiomeGenAt(sx, sz);
            if (b == null) continue;
            final String norm = b.biomeName.toLowerCase()
                .replaceAll("[^a-z0-9]", "");
            if (s == 0) c.biome = b.biomeName;
            for (final String t : tokens) {
                if (!t.isEmpty() && norm.contains(t)) return true;
            }
        }
        return false;
    }

    private static int topSolidAt(Prefilter.RwgTerrain terra, int x, int z) throws Exception {
        final Prefilter.RwgTerrain.Cols cols = terra.columns(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        return cols.topSolid[((x & 15) << 4) | (z & 15)];
    }

    private static net.minecraft.block.Block topBlockAt(Prefilter.RwgTerrain terra, int x, int z) throws Exception {
        final Prefilter.RwgTerrain.Cols cols = terra.columns(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        return cols.top[((x & 15) << 4) | (z & 15)];
    }
}
