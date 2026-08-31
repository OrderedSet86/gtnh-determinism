package com.gtnhspeedrun.worldgenprobe;

import net.minecraft.world.World;
import net.minecraft.world.biome.WorldChunkManager;
import net.minecraft.world.chunk.Chunk;

/**
 * Stage-0 module: the largest chunk square around the predicted spawn in which every column is a no-rain
 * biome, and its distance to the nearest high-humidity chunk.
 *
 * <p>
 * Two tiers, because neither alone is usable.
 *
 * <p>
 * <b>Tier A</b> samples {@code ChunkManagerRealistic.getBiomeGenAt} on the global 8-block lattice — the same
 * grid {@code ChunkGeneratorRealistic.getNewNoise} feeds its blend from, so a finer lattice would cost more
 * and carry information the generator never reads. It generates nothing. It is also <em>not</em> the answer:
 * the chunk manager returns a point sample of the generator's INPUT, while the stored biome array is three
 * transforms downstream — a 17x17 parabolic blur with +/-72-block support, {@code mix4} interpolation, then a
 * per-column weighted draw — and river painting on top of that. {@code WitcheryPrefilter} already records the
 * two disagreeing on RWG hard enough to flip a gate verdict.
 *
 * <p>
 * <b>Tier B</b> reads the chunk's real biome data through {@link Prefilter.VirginChunkProvider}, so it is
 * exact by construction. It costs a chunk generation (~2 ms), which is why it runs only on Tier A survivors
 * and only inside a per-seed budget.
 *
 * <p>
 * The river confound is why the two tiers exist rather than one. Every no-rain base biome the pack can
 * reach — Hot Plains 228, Hot Forest 229, Hot Desert 230 — paints its rivers with EITHER Hot River 207,
 * which is also no-rain, OR River Oasis 211, which rains and is itself maximum humidity. One river column
 * through a desert chunk therefore breaks the square and supplies its humid neighbour at the same time, and
 * Tier A cannot see it. Measured against the full generator, two seeds in twenty-four had humid chunks
 * reachable only through non-centre columns.
 *
 * <p>
 * Never reads {@code Chunk.getBiomeArray()}: EndlessIDs replaces the vanilla array and throws from that
 * accessor, which takes the run down on a 2.9/daily pack. {@code getBiomeGenForWorldCoords} takes chunk-local
 * coordinates, is what every mod uses, and resolves the 255 "unset" sentinel itself.
 */
final class BiomeRegionPrefilter {

    /** No humidity answer for this chunk. Distinct from 0, which is a real bonus a dry biome scores. */
    private static final int NO_HUM = -1;

    private BiomeRegionPrefilter() {}

    /** Per-seed answer. Every count is absolute so a sweep can be audited rather than rate-summarised. */
    static final class Result {

        int side;
        int cornerX, cornerZ;
        int humidSide;
        int humidCornerX, humidCornerZ;
        int gap = -1;
        int bestX, bestZ;
        int humidX, humidZ, humidBonus;
        /** "A" = Tier A only, "B" = Tier B confirmed, "Ab" = confirm budget ran out mid-way. */
        String tier = "A";
        int chunksConfirmed;
        String error;

        String toJson() {
            final StringBuilder sb = new StringBuilder(192);
            sb.append("{\"n\": ")
                .append(side)
                .append(", \"sq\": [")
                .append(cornerX)
                .append(", ")
                .append(cornerZ)
                .append("], \"hn\": ")
                .append(humidSide)
                .append(", \"hsq\": [")
                .append(humidCornerX)
                .append(", ")
                .append(humidCornerZ)
                .append("], \"d\": ")
                .append(gap);
            if (gap >= 0) {
                sb.append(", \"b\": [")
                    .append(bestX)
                    .append(", ")
                    .append(bestZ)
                    .append("], \"h\": [")
                    .append(humidX)
                    .append(", ")
                    .append(humidZ)
                    .append("], \"hb\": ")
                    .append(humidBonus);
            }
            sb.append(", \"t\": \"")
                .append(tier)
                .append("\", \"cg\": ")
                .append(chunksConfirmed);
            if (error != null) {
                sb.append(", \"error\": \"")
                    .append(WorldgenProbe.jsonEscape(error))
                    .append('"');
            }
            return sb.append('}')
                .toString();
        }
    }

    /**
     * Largest no-rain square and nearest humid chunk in a {@code radius}-chunk box around {@code (cx0, cz0)}.
     *
     * @param confirmBudget chunks Tier B may generate; 0 leaves the answer at Tier A and says so in
     *                      {@code tier}
     */
    static Result evaluate(World world, int cx0, int cz0, int radius, int minSide, int humidity, int confirmBudget) {
        final Result r = new Result();
        try {
            final WorldChunkManager cm = world.getWorldChunkManager();
            final int span = 2 * radius + 1;
            final boolean[][] dry = new boolean[span][span];
            final int[][] hum = new int[span][span];

            // Exact mode confirms the whole window and never consults Tier A, because Tier A cannot be
            // trusted to say no. Measured against full generation: on three seeds in twenty-four the
            // permissive lattice screen reported a largest square of 0, 0 and 2 where the truth was 13, 5 and
            // 5. The generator's parabolic blur spans +/-72 blocks, so a chunk can come out uniformly desert
            // with no desert at all among its own lattice points — the lattice is not an upper bound on the
            // output, in either direction, and any early-out built on it silently drops qualifying seeds.
            if (confirmBudget < 0) {
                for (int i = 0; i < span; i++) {
                    for (int j = 0; j < span; j++) {
                        confirmChunk(world, cm, cx0 - radius + i, cz0 - radius + j, dry, hum, i, j);
                        r.chunksConfirmed++;
                    }
                }
                score(r, dry, hum, span, cx0, cz0, radius, minSide, humidity);
                r.tier = "B";
                return r;
            }

            // --- Tier A, deliberately PERMISSIVE: one no-rain lattice point makes the chunk a candidate.
            //
            // The obvious rule — require all four points — was measured and is wrong in the fatal direction.
            // The generator blurs its input lattice through a 17x17 parabolic window, so the biome map it
            // produces is SMOOTHER than the lattice Tier A reads: a chunk can come out uniformly desert while
            // its raw lattice already carries a neighbouring biome. Against full generation, "all four" missed
            // three seeds in twenty-four outright, one of them reporting side 0 where the truth was 13, and a
            // false negative here is unrecoverable because Tier B never runs on a seed Tier A has discarded.
            // Over-reporting is free: Tier B removes it. It is still not sound as a kill — see above — so a
            // positive confirm budget trades recall for speed and should be used only when that is intended.
            for (int i = 0; i < span; i++) {
                for (int j = 0; j < span; j++) {
                    final int bx = (cx0 - radius + i) << 4;
                    final int bz = (cz0 - radius + j) << 4;
                    boolean anyDry = false;
                    int best = NO_HUM;
                    for (int ox = 0; ox < 16; ox += 8) {
                        for (int oz = 0; oz < 16; oz += 8) {
                            final int id = cm.getBiomeGenAt(bx + ox, bz + oz).biomeID;
                            if (BiomeTable.noRain(id)) anyDry = true;
                            best = Math.max(best, BiomeTable.humidity(id));
                        }
                    }
                    dry[i][j] = anyDry;
                    hum[i][j] = best;
                }
            }
            score(r, dry, hum, span, cx0, cz0, radius, minSide, humidity);

            // A permissive screen only earns a kill when it says NO. Seeds it passes still have to be
            // confirmed; seeds it rejects cannot become qualifying by generating chunks.
            if (confirmBudget == 0 || r.side < minSide) return r;

            // --- Tier B, budgeted. Targets the cells that can carry a min-side square plus one ring, so an
            // adjacent humid chunk is confirmed too, and stops when the budget runs out.
            //
            // Cells left unconfirmed keep their Tier A values rather than being cleared: dropping them would
            // shrink squares for lack of budget and then report that shrinkage as terrain. The tier field says
            // which of the two happened, so "Ab" rows are never mistaken for measurements.
            final int[][] dpA = new int[span][span];
            maxSquare(dry, dpA);
            final boolean[][] known = new boolean[span][span];
            int budget = confirmBudget;
            boolean exhausted = false;
            for (int i = 0; i < span && !exhausted; i++) {
                for (int j = 0; j < span && !exhausted; j++) {
                    if (dpA[i][j] < minSide) continue;
                    for (int di = -minSide; di <= 1 && !exhausted; di++) {
                        for (int dj = -minSide; dj <= 1 && !exhausted; dj++) {
                            final int ci = i + di, cj = j + dj;
                            if (ci < 0 || cj < 0 || ci >= span || cj >= span || known[ci][cj]) continue;
                            if (budget <= 0) {
                                exhausted = true;
                                break;
                            }
                            budget--;
                            known[ci][cj] = true;
                            confirmChunk(world, cm, cx0 - radius + ci, cz0 - radius + cj, dry, hum, ci, cj);
                            r.chunksConfirmed++;
                        }
                    }
                }
            }
            score(r, dry, hum, span, cx0, cz0, radius, minSide, humidity);
            r.tier = exhausted ? "Ab" : "B";
        } catch (Throwable t) {
            r.error = t.toString();
        }
        return r;
    }

    /**
     * Overwrite one cell's verdict with the real generated biome data.
     *
     * <p>
     * All 256 columns, not a sample: a river is a few columns wide and is exactly what decides both halves of
     * this question, so sampling would miss the case the whole module exists for.
     */
    private static void confirmChunk(World world, WorldChunkManager cm, int cx, int cz, boolean[][] dry, int[][] hum,
        int i, int j) {
        boolean allDry = true;
        int best = NO_HUM;
        final Chunk c = world.getChunkFromChunkCoords(cx, cz);
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                final int id = c.getBiomeGenForWorldCoords(lx, lz, cm).biomeID;
                if (!BiomeTable.noRain(id)) allDry = false;
                best = Math.max(best, BiomeTable.humidity(id));
            }
        }
        dry[i][j] = allDry;
        hum[i][j] = best;
    }

    /** Fill every square/gap field from a dry mask and a humidity grid. */
    private static void score(Result r, boolean[][] dry, int[][] hum, int span, int cx0, int cz0, int radius,
        int minSide, int humidity) {
        final int ox = cx0 - radius, oz = cz0 - radius;
        final int[][] dp = new int[span][span];
        final int[] big = maxSquare(dry, dp);
        r.side = big[0];
        r.cornerX = ox + big[1];
        r.cornerZ = oz + big[2];

        final boolean[][] humidMask = new boolean[span][span];
        for (int i = 0; i < span; i++) {
            for (int j = 0; j < span; j++) humidMask[i][j] = hum[i][j] >= humidity;
        }
        final int[] humBig = maxSquare(humidMask, new int[span][span]);
        r.humidSide = humBig[0];
        r.humidCornerX = ox + humBig[1];
        r.humidCornerZ = oz + humBig[2];

        r.gap = -1;
        if (r.side < minSide) return;

        // Chebyshev distance from every cell to the nearest humid chunk, plus which chunk that was. An
        // 8-connected BFS gives chebyshev distance exactly, in one pass over the grid — the naive
        // square-by-humid-chunk scan is O(span^4), which at radius 16 is 1.2M comparisons per seed and does
        // not fit a stage-0 budget.
        final int[][] dist = new int[span][span];
        final int[][] src = new int[span][span];
        final int[] queue = new int[span * span];
        int head = 0, tail = 0;
        for (int i = 0; i < span; i++) {
            for (int j = 0; j < span; j++) {
                if (humidMask[i][j]) {
                    dist[i][j] = 0;
                    src[i][j] = i * span + j;
                    queue[tail++] = i * span + j;
                } else {
                    dist[i][j] = Integer.MAX_VALUE;
                    src[i][j] = -1;
                }
            }
        }
        if (tail == 0) return;
        while (head < tail) {
            final int cur = queue[head++];
            final int ci = cur / span, cj = cur % span;
            for (int di = -1; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    final int ni = ci + di, nj = cj + dj;
                    if (ni < 0 || nj < 0 || ni >= span || nj >= span) continue;
                    if (dist[ni][nj] != Integer.MAX_VALUE) continue;
                    dist[ni][nj] = dist[ci][cj] + 1;
                    src[ni][nj] = src[ci][cj];
                    queue[tail++] = ni * span + nj;
                }
            }
        }

        // Best min-side square is the one closest to humidity, not the biggest: the largest dry region is not
        // necessarily the one beside the wet one, and the route cares about the pair.
        int best = Integer.MAX_VALUE, bi = 0, bj = 0, bsrc = -1;
        for (int i = 0; i < span; i++) {
            for (int j = 0; j < span; j++) {
                if (dp[i][j] < minSide) continue;
                final int i0 = i - minSide + 1, j0 = j - minSide + 1;
                for (int a = i0; a <= i; a++) {
                    for (int b = j0; b <= j; b++) {
                        if (dist[a][b] < best) {
                            best = dist[a][b];
                            bi = i0;
                            bj = j0;
                            bsrc = src[a][b];
                        }
                    }
                }
            }
        }
        if (bsrc < 0) return;
        r.gap = best;
        r.bestX = ox + bi;
        r.bestZ = oz + bj;
        r.humidX = ox + bsrc / span;
        r.humidZ = oz + bsrc % span;
        r.humidBonus = hum[bsrc / span][bsrc % span];
    }

    /** Largest all-true square as {@code [side, i, j]} of its min corner; fills {@code dp} for the caller. */
    private static int[] maxSquare(boolean[][] grid, int[][] dp) {
        final int n = grid.length, m = grid[0].length;
        int best = 0, bi = 0, bj = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                if (!grid[i][j]) {
                    dp[i][j] = 0;
                    continue;
                }
                dp[i][j] = (i == 0 || j == 0) ? 1
                    : 1 + Math.min(dp[i - 1][j], Math.min(dp[i][j - 1], dp[i - 1][j - 1]));
                if (dp[i][j] > best) {
                    best = dp[i][j];
                    bi = i;
                    bj = j;
                }
            }
        }
        return new int[] { best, bi - best + 1, bj - best + 1 };
    }
}
