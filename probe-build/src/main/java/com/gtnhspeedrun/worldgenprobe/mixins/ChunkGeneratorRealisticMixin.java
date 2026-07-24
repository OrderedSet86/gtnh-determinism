package com.gtnhspeedrun.worldgenprobe.mixins;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import rwg.biomes.realistic.RealisticBiomeBase;
import rwg.util.CellNoise;
import rwg.util.NoiseGenerator;
import rwg.world.ChunkGeneratorRealistic;
import rwg.world.ChunkManagerRealistic;

/**
 * Harness-only fast path (-Dprobe.parallelnoise=true, applied by the probe's late mixin loader only when that
 * property is set): parallelizes the per-column height-noise loop of getNewNoise across a fixed thread pool.
 *
 * The method body replicates SHIPPED RWG alpha-1.5.0 (git tag; all 2.7.x-2.8.x packs ship 1.5.0), NOT the
 * alpha-1.5.2 dev artifact this compiles against — 1.5.2's "Optimizations" PR (#17) changed the blend stages
 * (in-place mix4, riverStrength cache, no parabolicFieldTotal divisor). parabolicFieldTotal only exists at
 * runtime, so it is read via a cached reflective Field.
 *
 * Bit-exactness argument: every column's output (testHeight, biomes[], and the mapGenBiomes writes, which fire
 * only for the single column with l==312) is written to column-distinct indices, and is a pure function of
 * read-only state — the perlin/cell tables, the finished smallRender weights, and the cmr ocean/river helpers,
 * all verified write-free on their evaluation paths (terrain classes hold no mutable fields). Float operation
 * ORDER within each column is byte-for-byte the stock loop body, so scheduling cannot change results. The
 * biome-blend stages before the column loop keep stock sequential code.
 */
@Mixin(value = ChunkGeneratorRealistic.class, remap = false)
public abstract class ChunkGeneratorRealisticMixin {

    @Shadow(remap = false)
    private NoiseGenerator perlin;

    @Shadow(remap = false)
    private CellNoise cell;

    @Shadow(remap = false)
    private int sampleSize;

    @Shadow(remap = false)
    private int sampleArraySize;

    @Shadow(remap = false)
    private int parabolicSize;

    @Shadow(remap = false)
    private int parabolicArraySize;

    @Shadow(remap = false)
    private float[] parabolicField;

    @Shadow(remap = false)
    private int[] biomeData;

    @Shadow(remap = false)
    private float[][] hugeRender;

    @Shadow(remap = false)
    private float[][] smallRender;

    @Shadow(remap = false)
    private float[] testHeight;

    @Shadow(remap = false)
    private float[] mapGenBiomes;

    /** 1.5.0-only field, absent from the 1.5.2 compile classpath — reflective access, cached. */
    private static volatile java.lang.reflect.Field probe$pftField;

    private float probe$parabolicFieldTotal() {
        try {
            java.lang.reflect.Field f = probe$pftField;
            if (f == null) {
                f = ChunkGeneratorRealistic.class.getDeclaredField("parabolicFieldTotal");
                f.setAccessible(true);
                probe$pftField = f;
            }
            return f.getFloat(this);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "parabolicFieldTotal missing — RWG is not the expected alpha-1.5.0; disable probe.parallelnoise",
                e);
        }
    }

    /** Verbatim RWG alpha-1.5.0 mix4 (renamed: the target class already has a mix4 with this descriptor). */
    private static float[] probe$mix4(float[][] ingredients) {
        float[] result = new float[256];
        int i, j;
        for (i = 0; i < 256; i++) {
            for (j = 0; j < 4; j++) {
                if (ingredients[j][i] > 0f) {
                    result[i] += ingredients[j][i] / 4f;
                }
            }
        }
        return result;
    }

    private static final ExecutorService PROBE$POOL;
    static {
        final int n = Integer.getInteger(
            "probe.noisethreads",
            Math.max(
                2,
                Math.min(
                    8,
                    Runtime.getRuntime()
                        .availableProcessors() - 2)));
        final AtomicInteger id = new AtomicInteger();
        PROBE$POOL = Executors.newFixedThreadPool(n, r -> {
            final Thread t = new Thread(r, "probe-noise-" + id.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * @author gtnh-determinism harness
     * @reason parallelize the per-column noise loop (~39% of warm seed-search time); bit-exact, see class doc.
     */
    @Overwrite(remap = false)
    public float[] getNewNoise(final ChunkManagerRealistic cmr, final int x, final int y,
        final RealisticBiomeBase[] biomes) {
        int i, j, k, l;
        final float pft = probe$parabolicFieldTotal();

        for (i = -sampleSize; i < sampleSize + 5; i++) {
            for (j = -sampleSize; j < sampleSize + 5; j++) {
                biomeData[(i + sampleSize) * sampleArraySize + (j + sampleSize)] = cmr
                    .getBiomeDataAt(x + ((i * 8) - 8), y + ((j * 8) - 8)).biomeID;
            }
        }

        for (i = -1; i < 4; i++) {
            for (j = -1; j < 4; j++) {
                hugeRender[(i * 2 + 2) * 9 + (j * 2 + 2)] = new float[256];
                for (k = -parabolicSize; k <= parabolicSize; k++) {
                    for (l = -parabolicSize; l <= parabolicSize; l++) {
                        hugeRender[(i * 2 + 2) * 9 + (j * 2 + 2)][biomeData[(i + k + sampleSize + 1) * sampleArraySize
                            + (j + l + sampleSize + 1)]] += parabolicField[k + parabolicSize
                                + (l + parabolicSize) * parabolicArraySize] / pft;
                    }
                }
            }
        }

        // MAIN BIOME CHECK
        RealisticBiomeBase b = null;
        for (i = 0; i < 256; i++) {
            if (hugeRender[4 * 9 + 4][i] > 0.95f) {
                b = RealisticBiomeBase.getBiome(i);
            }
        }

        // RENDER HUGE 1
        for (i = 0; i < 4; i++) {
            for (j = 0; j < 4; j++) {
                hugeRender[(i * 2 + 1) * 9 + (j * 2 + 1)] = probe$mix4(
                    new float[][] { hugeRender[(i * 2) * 9 + (j * 2)], hugeRender[(i * 2 + 2) * 9 + (j * 2)],
                        hugeRender[(i * 2) * 9 + (j * 2 + 2)], hugeRender[(i * 2 + 2) * 9 + (j * 2 + 2)] });
            }
        }

        // RENDER HUGE 2
        for (i = 0; i < 7; i++) {
            for (j = 0; j < 7; j++) {
                if (!(i % 2 == 0 && j % 2 == 0) && !(i % 2 != 0 && j % 2 != 0)) {
                    smallRender[(i * 4) * 25 + (j * 4)] = probe$mix4(
                        new float[][] { hugeRender[(i) * 9 + (j + 1)], hugeRender[(i + 1) * 9 + (j)],
                            hugeRender[(i + 1) * 9 + (j + 2)], hugeRender[(i + 2) * 9 + (j + 1)] });
                } else {
                    smallRender[(i * 4) * 25 + (j * 4)] = hugeRender[(i + 1) * 9 + (j + 1)];
                }
            }
        }

        // RENDER SMALL 1
        for (i = 0; i < 6; i++) {
            for (j = 0; j < 6; j++) {
                smallRender[(i * 4 + 2) * 25 + (j * 4 + 2)] = probe$mix4(
                    new float[][] { smallRender[(i * 4) * 25 + (j * 4)], smallRender[(i * 4 + 4) * 25 + (j * 4)],
                        smallRender[(i * 4) * 25 + (j * 4 + 4)], smallRender[(i * 4 + 4) * 25 + (j * 4 + 4)] });
            }
        }

        // RENDER SMALL 2
        for (i = 0; i < 11; i++) {
            for (j = 0; j < 11; j++) {
                if (!(i % 2 == 0 && j % 2 == 0) && !(i % 2 != 0 && j % 2 != 0)) {
                    smallRender[(i * 2 + 2) * 25 + (j * 2 + 2)] = probe$mix4(
                        new float[][] { smallRender[(i * 2) * 25 + (j * 2 + 2)],
                            smallRender[(i * 2 + 2) * 25 + (j * 2)], smallRender[(i * 2 + 2) * 25 + (j * 2 + 4)],
                            smallRender[(i * 2 + 4) * 25 + (j * 2 + 2)] });
                }
            }
        }

        // RENDER SMALL 3
        for (i = 0; i < 9; i++) {
            for (j = 0; j < 9; j++) {
                smallRender[(i * 2 + 3) * 25 + (j * 2 + 3)] = probe$mix4(
                    new float[][] { smallRender[(i * 2 + 2) * 25 + (j * 2 + 2)],
                        smallRender[(i * 2 + 4) * 25 + (j * 2 + 2)], smallRender[(i * 2 + 2) * 25 + (j * 2 + 4)],
                        smallRender[(i * 2 + 4) * 25 + (j * 2 + 4)] });
            }
        }

        // RENDER SMALL 4
        for (i = 0; i < 16; i++) {
            for (j = 0; j < 16; j++) {
                if (!(i % 2 == 0 && j % 2 == 0) && !(i % 2 != 0 && j % 2 != 0)) {
                    smallRender[(i + 4) * 25 + (j + 4)] = probe$mix4(
                        new float[][] { smallRender[(i + 3) * 25 + (j + 4)], smallRender[(i + 4) * 25 + (j + 3)],
                            smallRender[(i + 4) * 25 + (j + 5)], smallRender[(i + 5) * 25 + (j + 4)] });
                }
            }
        }

        // CREATE BIOMES ARRAY
        final boolean randBiome;
        if (b != null) {
            randBiome = false;
            for (i = 0; i < 256; i++) {
                biomes[i] = b;
            }
        } else {
            randBiome = true;
        }

        // Per-column height noise, parallel: each task owns one i-row (16 columns), stock 1.5.0 loop body.
        final List<Callable<Void>> tasks = new ArrayList<>(16);
        for (int rowFinal = 0; rowFinal < 16; rowFinal++) {
            final int row = rowFinal;
            tasks.add(() -> {
                probe$columnRow(cmr, x, y, biomes, randBiome, row);
                return null;
            });
        }
        try {
            final List<Future<Void>> fs = PROBE$POOL.invokeAll(tasks);
            for (Future<Void> f : fs) f.get(); // propagate any worker exception
        } catch (Exception e) {
            throw new RuntimeException("parallel noise failed", e);
        }

        return testHeight;
    }

    private void probe$columnRow(final ChunkManagerRealistic cmr, final int x, final int y,
        final RealisticBiomeBase[] biomes, final boolean randBiome, final int i) {
        float bCount, bRand;
        float river, ocean;
        int l, k;
        for (int j = 0; j < 16; j++) {
            bCount = 0f;
            bRand = 0f;
            if (randBiome) {
                bRand = 0.5f + perlin.noise2((float) (x + i) / 15f, (float) (y + j) / 15f);
                bRand = bRand < 0f ? 0f : bRand > 0.99999f ? 0.99999f : bRand;
            }

            ocean = cmr.getOceanValue(x + i, y + j);
            l = ((int) (i + 4) * 25 + (j + 4));

            testHeight[i * 16 + j] = 0f;

            river = cmr.getRiverStrength(x + i, y + j);

            if (l == 312) {
                mapGenBiomes[256] = ocean;
                mapGenBiomes[257] = river;
            }

            for (k = 0; k < 256; k++) {
                if (smallRender[l][k] > 0f) {
                    if (randBiome && bCount <= 1f) {
                        bCount += smallRender[l][k];
                        if (bCount > bRand) {
                            biomes[j * 16 + i] = RealisticBiomeBase.getBiome(k);
                            bCount = 2f;
                        }
                    }

                    if (l == 312) {
                        mapGenBiomes[k] = smallRender[312][k];
                    }

                    testHeight[i * 16 + j] += cmr.calculateRiver(
                        x + i,
                        y + j,
                        river,
                        RealisticBiomeBase.getBiome(k)
                            .rNoise(perlin, cell, x + i, y + j, ocean, smallRender[l][k], river + 1f))
                        * smallRender[l][k];
                }
            }
        }
    }
}
