package hunt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import rwg.terrain.TerrainBase;

/**
 * Verbatim port of ChunkGeneratorRealistic.getNewNoise — the parabolic biome blend that the single-biome
 * proxy skipped, and whose absence made proxy heights lie by +-40 blocks at valley centers (calibration:
 * the champion's real bench at 108 proxied as a wet 61.8). Chunk-granular like the original: sampleSize=8
 * biome grid, hugeRender/smallRender mix4 cascade, per-column sum over contributing biomes of
 * calculateRiver(rNoise(..., border=weight, river+1)) * weight, ocean from the ported getOceanValue.
 * The output IS the game's pre-cave, pre-replaceBlocks column height ((int)h == generateTerrain's stone
 * top), so the paraboloid integral over it is the digest volume without chunkgen.
 *
 * Biome identity: bucket indices must merge equal biomes across band slots (weights are passed to rNoise
 * as the border parameter — splitting one biome across two buckets would halve border and misfire the
 * mesa/canyon terrace gates), so ids key on the dumped biome class name.
 */
public final class BlendedHeight {

    private final Screen screen; // band selection, river, ocean share its noise instances
    private final TerrainBase[] terrainById;
    private final int[] snowIds, coldIds, hotIds, wetIds, smallIds;
    private final boolean wetEnabled, smallEnabled;

    private static final int SAMPLE_SIZE = 8;
    private static final int SAMPLE_ARRAY = SAMPLE_SIZE * 2 + 5;
    private final float[] parabolicField;
    private float parabolicTotal;

    private final Map<Long, float[]> chunkCache = new HashMap<>();

    public BlendedHeight(Screen screen, Dump dump) {
        this.screen = screen;
        wetEnabled = dump.wetEnabled;
        smallEnabled = dump.smallEnabled;
        final Map<String, Integer> idByName = new HashMap<>();
        final TerrainBase[] tmp = new TerrainBase[256];
        snowIds = ids(dump.snow, dump.snowNames, idByName, tmp);
        coldIds = ids(dump.cold, dump.coldNames, idByName, tmp);
        hotIds = ids(dump.hot, dump.hotNames, idByName, tmp);
        wetIds = ids(dump.wet, dump.wetNames, idByName, tmp);
        smallIds = ids(dump.small, dump.smallNames, idByName, tmp);
        terrainById = tmp;
        parabolicField = new float[(SAMPLE_SIZE * 2 + 1) * (SAMPLE_SIZE * 2 + 1)];
        for (int j = -SAMPLE_SIZE; j <= SAMPLE_SIZE; ++j) {
            for (int k = -SAMPLE_SIZE; k <= SAMPLE_SIZE; ++k) {
                final float f = 0.445f / net.sqrt(j * j + k * k + 0.3f);
                parabolicField[j + SAMPLE_SIZE + (k + SAMPLE_SIZE) * (SAMPLE_SIZE * 2 + 1)] = f;
                parabolicTotal += f;
            }
        }
    }

    /** sqrt helper namespace kept tiny to keep the parabolic line readable. */
    private static final class net {

        static float sqrt(float v) {
            return (float) Math.sqrt(v);
        }
    }

    private static int[] ids(List<TerrainBase> band, List<String> names, Map<String, Integer> idByName,
        TerrainBase[] byId) {
        final int[] out = new int[band.size()];
        for (int i = 0; i < band.size(); i++) {
            final String name = names.get(i);
            Integer id = idByName.get(name);
            if (id == null) {
                id = idByName.size();
                idByName.put(name, id);
                byId[id] = band.get(i);
            }
            out[i] = id;
        }
        return out;
    }

    /** Screen.terrainAt's band selection, returning the deduped global biome id. */
    private int biomeIdAt(int x, int z) {
        final float[] pick = screen.bandPick(x, z);
        final int band = (int) pick[0];
        final float h = pick[1];
        final int[] list = band == 0 ? snowIds : band == 1 ? coldIds : band == 2 ? hotIds : band == 3 ? wetIds : smallIds;
        return list[(int) (h * list.length)];
    }

    private final Map<Long, float[][]> weightCache = new HashMap<>();

    /**
     * The weight cascade alone (biome sampling + parabolic accumulation + mix4 pyramid) — no rNoise. Costs
     * ~1k noise evals per chunk against ~5k for a full chunk of heights; single-column reads ride on it,
     * which is what makes the blended RING pre-gate ~10x cheaper than the full disc.
     */
    private float[][] weightsFor(int cx, int cz) {
        final long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        float[][] cached = weightCache.get(key);
        if (cached != null) return cached;
        final float[][] smallRender = cascade(cx * 16, cz * 16);
        if (weightCache.size() > 16384) weightCache.clear();
        weightCache.put(key, smallRender);
        return smallRender;
    }

    /** One blended column via the cached weight cascade — the ring pre-gate's unit. */
    public float columnHeight(int wx, int wz) {
        final int cx = Math.floorDiv(wx, 16), cz = Math.floorDiv(wz, 16);
        final int i = wx - cx * 16, j = wz - cz * 16;
        final float[] weights = weightsFor(cx, cz)[(i + 4) * 25 + (j + 4)];
        final float ocean = screen.oceanValue(wx, wz);
        final float river = screen.riverStrength(wx, wz);
        float th = 0f;
        for (int k = 0; k < 256; k++) {
            if (weights[k] > 0f) {
                th += screen.calculateRiver(
                    wx,
                    wz,
                    river,
                    terrainById[k].generateNoise(screen.perlin(), screen.cellNoise(), wx, wz, ocean, weights[k], river + 1f))
                    * weights[k];
            }
        }
        return th;
    }

    /**
     * Blended ring relief: center + 16 bearings x {40,64,88}, single columns. Pre-gates the full-disc
     * volume — a candidate whose BLENDED ring relief is small cannot integrate to a big carve, whatever
     * its unblended proxy said.
     */
    public int ringRelief(int x, int z) {
        final float center = columnHeight(x, z);
        float max = center;
        for (int s = 0; s < 16; s++) {
            final double a = s * Math.PI / 8;
            for (final int r : new int[] { 40, 64, 88 }) {
                final float h = columnHeight(x + (int) Math.round(r * Math.cos(a)), z + (int) Math.round(r * Math.sin(a)));
                if (h > max) max = h;
            }
        }
        return (int) (max - center);
    }

    /** The weight cascade, verbatim getNewNoise shapes. */
    private float[][] cascade(int x, int y) {
        final int[] biomeData = new int[SAMPLE_ARRAY * SAMPLE_ARRAY];
        final float[][] hugeRender = new float[9 * 9][];
        final float[][] smallRender = new float[25 * 25][];
        int i, j, k, l;
        for (i = -SAMPLE_SIZE; i < SAMPLE_SIZE + 5; i++) {
            for (j = -SAMPLE_SIZE; j < SAMPLE_SIZE + 5; j++) {
                biomeData[(i + SAMPLE_SIZE) * SAMPLE_ARRAY + (j + SAMPLE_SIZE)] = biomeIdAt(
                    x + ((i * 8) - 8),
                    y + ((j * 8) - 8));
            }
        }
        for (i = -1; i < 4; i++) {
            for (j = -1; j < 4; j++) {
                final float[] cell = new float[256];
                hugeRender[(i * 2 + 2) * 9 + (j * 2 + 2)] = cell;
                for (k = -SAMPLE_SIZE; k <= SAMPLE_SIZE; k++) {
                    for (l = -SAMPLE_SIZE; l <= SAMPLE_SIZE; l++) {
                        cell[biomeData[(i + k + SAMPLE_SIZE + 1) * SAMPLE_ARRAY + (j + l + SAMPLE_SIZE + 1)]] +=
                            parabolicField[k + SAMPLE_SIZE + (l + SAMPLE_SIZE) * (SAMPLE_SIZE * 2 + 1)]
                                / parabolicTotal;
                    }
                }
            }
        }
        for (i = 0; i < 4; i++) {
            for (j = 0; j < 4; j++) {
                hugeRender[(i * 2 + 1) * 9 + (j * 2 + 1)] = mix4(
                    hugeRender[(i * 2) * 9 + (j * 2)],
                    hugeRender[(i * 2 + 2) * 9 + (j * 2)],
                    hugeRender[(i * 2) * 9 + (j * 2 + 2)],
                    hugeRender[(i * 2 + 2) * 9 + (j * 2 + 2)]);
            }
        }
        for (i = 0; i < 7; i++) {
            for (j = 0; j < 7; j++) {
                if (!(i % 2 == 0 && j % 2 == 0) && !(i % 2 != 0 && j % 2 != 0)) {
                    smallRender[(i * 4) * 25 + (j * 4)] = mix4(
                        hugeRender[(i) * 9 + (j + 1)],
                        hugeRender[(i + 1) * 9 + (j)],
                        hugeRender[(i + 1) * 9 + (j + 2)],
                        hugeRender[(i + 2) * 9 + (j + 1)]);
                } else {
                    smallRender[(i * 4) * 25 + (j * 4)] = hugeRender[(i + 1) * 9 + (j + 1)];
                }
            }
        }
        for (i = 0; i < 6; i++) {
            for (j = 0; j < 6; j++) {
                smallRender[(i * 4 + 2) * 25 + (j * 4 + 2)] = mix4(
                    smallRender[(i * 4) * 25 + (j * 4)],
                    smallRender[(i * 4 + 4) * 25 + (j * 4)],
                    smallRender[(i * 4) * 25 + (j * 4 + 4)],
                    smallRender[(i * 4 + 4) * 25 + (j * 4 + 4)]);
            }
        }
        for (i = 0; i < 11; i++) {
            for (j = 0; j < 11; j++) {
                if (!(i % 2 == 0 && j % 2 == 0) && !(i % 2 != 0 && j % 2 != 0)) {
                    smallRender[(i * 2 + 2) * 25 + (j * 2 + 2)] = mix4(
                        smallRender[(i * 2) * 25 + (j * 2 + 2)],
                        smallRender[(i * 2 + 2) * 25 + (j * 2)],
                        smallRender[(i * 2 + 2) * 25 + (j * 2 + 4)],
                        smallRender[(i * 2 + 4) * 25 + (j * 2 + 2)]);
                }
            }
        }
        for (i = 0; i < 9; i++) {
            for (j = 0; j < 9; j++) {
                smallRender[(i * 2 + 3) * 25 + (j * 2 + 3)] = mix4(
                    smallRender[(i * 2 + 2) * 25 + (j * 2 + 2)],
                    smallRender[(i * 2 + 4) * 25 + (j * 2 + 2)],
                    smallRender[(i * 2 + 2) * 25 + (j * 2 + 4)],
                    smallRender[(i * 2 + 4) * 25 + (j * 2 + 4)]);
            }
        }
        for (i = 0; i < 16; i++) {
            for (j = 0; j < 16; j++) {
                if (!(i % 2 == 0 && j % 2 == 0) && !(i % 2 != 0 && j % 2 != 0)) {
                    smallRender[(i + 4) * 25 + (j + 4)] = mix4(
                        smallRender[(i + 3) * 25 + (j + 4)],
                        smallRender[(i + 4) * 25 + (j + 3)],
                        smallRender[(i + 4) * 25 + (j + 5)],
                        smallRender[(i + 5) * 25 + (j + 4)]);
                }
            }
        }
        return smallRender;
    }

    /** getNewNoise, verbatim; returns testHeight[i*16+j] for column (x+i, z+j), chunk-cached. */
    public float[] chunk(int cx, int cz) {
        final long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        float[] cached = chunkCache.get(key);
        if (cached != null) return cached;
        final int x = cx * 16, y = cz * 16;
        final float[][] smallRender = weightsFor(cx, cz);
        final float[] testHeight = new float[256];
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                testHeight[i * 16 + j] = 0f;
            }
        }
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                final float ocean = screen.oceanValue(x + i, y + j);
                final int l = ((i + 4) * 25 + (j + 4));
                final float river = screen.riverStrength(x + i, y + j);
                float th = 0f;
                final float[] weights = smallRender[l];
                for (int k = 0; k < 256; k++) {
                    if (weights[k] > 0f) {
                        th += screen.calculateRiver(
                            x + i,
                            y + j,
                            river,
                            terrainById[k].generateNoise(screen.perlin(), screen.cellNoise(), x + i, y + j, ocean, weights[k], river + 1f))
                            * weights[k];
                    }
                }
                testHeight[i * 16 + j] = th;
            }
        }
        chunkCache.put(key, testHeight);
        if (chunkCache.size() > 8192) chunkCache.clear();
        return testHeight;
    }

    private static float[] mix4(float[] a, float[] b, float[] c, float[] d) {
        final float[] result = new float[256];
        final float[][] ing = { a, b, c, d };
        for (int i = 0; i < 256; i++) {
            for (int j = 0; j < 4; j++) {
                if (ing[j][i] > 0f) result[i] += ing[j][i] / 4f;
            }
        }
        return result;
    }

    /** Blended column height — the game's pre-cave stone top. */
    public float height(int wx, int wz) {
        final int cx = Math.floorDiv(wx, 16), cz = Math.floorDiv(wz, 16);
        return chunk(cx, cz)[(wx - cx * 16) * 16 + (wz - cz * 16)];
    }

    /** skyMode's horizontal reach: min blended surface within ~15 blocks (center + 8 @ 10 + 8 @ 15). */
    public int minNear(int x, int z) {
        float min = height(x, z);
        for (int s = 0; s < 8; s++) {
            final double a = s * Math.PI / 4;
            for (final int r : new int[] { 10, 15 }) {
                final float h = height(x + (int) Math.round(r * Math.cos(a)), z + (int) Math.round(r * Math.sin(a)));
                if (h < min) min = h;
            }
        }
        return (int) min;
    }

    /** tryMeteorite's descent lattice on blended heights: first -15 rung at or below the surface.
     * Rung block validity (BoP surface blocks etc.) is stage-2's job; this is the arithmetic layer. */
    public int landing(int startDepth, int surface) {
        if (startDepth <= surface) return startDepth;
        return startDepth - 15 * (int) Math.ceil((startDepth - surface) / 15.0);
    }

    /** Set by volumeAt/volumeH: of 16 compass sectors over the 24..volRadius ring, how many reach
     * surface+30 — the game prefilter's enclosure, on blended heights. 14+ = ring crater. */
    public int lastEnclosure = -1;

    /** Exact pre-cave carve volume with the crater floor at the LANDING (burial deepens h and grows the
     * carve — unmodeled burial was the calibration ratio wobble). */
    public long volumeAt(int x, int z, int landingY, double size, int volRadius) {
        final double h0 = landingY - size + 1;
        return volumeH(x, z, h0, volRadius);
    }

    /** Exact pre-cave carve volume of a size-`size` meteor at (x,z), surface-floored. */
    public long volume(int x, int z, double size, int volRadius) {
        final double h0 = height(x, z) - size + 1;
        return volumeH(x, z, h0, volRadius);
    }

    private long volumeH(int x, int z, double h0, int volRadius) {
        final long r2 = (long) volRadius * volRadius;
        final float surface = height(x, z);
        final float[] sectorMax = new float[16];
        long vol = 0;
        for (int dx = -volRadius; dx <= volRadius; dx++) {
            for (int dz = -volRadius; dz <= volRadius; dz++) {
                final long d2 = (long) dx * dx + (long) dz * dz;
                if (d2 > r2) continue;
                final double cut = h0 + 0.02 * d2;
                final float top = height(x + dx, z + dz);
                if (top > cut) vol += (long) (top - cut);
                if (d2 >= 24 * 24) {
                    final int sector = ((int) Math.floor((Math.atan2(dz, dx) + Math.PI) / (Math.PI / 8))) & 15;
                    if (top > sectorMax[sector]) sectorMax[sector] = top;
                }
            }
        }
        int enclosed = 0;
        for (final float m : sectorMax) if (m >= surface + 30) enclosed++;
        lastEnclosure = enclosed;
        return vol;
    }

    /** Dry check on BLENDED heights (the proxy version wrongly wet-flagged the champion's bench). */
    public boolean dry(int x, int z) {
        if (height(x, z) < 63f) return false;
        for (int s = 0; s < 8; s++) {
            final double a = s * Math.PI / 4;
            if (height(x + (int) Math.round(12 * Math.cos(a)), z + (int) Math.round(12 * Math.sin(a))) < 63f) {
                return false;
            }
        }
        return true;
    }
}
