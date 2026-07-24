package com.gtnhspeedrun.worldgenprobe.mixins;

import java.util.Random;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import rwg.util.PerlinNoise;

/**
 * Harness fast path (-Dprobe.fastnoise=true): backports RWG PR #17's gradient-table flattening
 * (alpha-1.5.2) onto the shipped alpha-1.5.0 PerlinNoise — float[][] g2/g3 become flat float[] with
 * identical values at identical logical positions, killing one pointer chase + bounds check per
 * gradient fetch in the hottest worldgen function.
 *
 * Bit-exactness: the constructor draw sequence from `rand` is unchanged (g1, 2x g2 + normalize,
 * 3x g3 + normalize per i — verified against both tags), and every arithmetic expression in
 * noise2/noise3 is the same operations in the same order, only the array indexing changes
 * (q[0]/q[1] -> g2[b*2]/g2[b*2+1]). Upstream's own diff; re-verified here via block-hash A/B.
 *
 * The stock 2D g2/g3 fields stay null (initPerlin1 is overwritten to fill only the flat arrays);
 * noise2/noise3 are the only readers and are overwritten to match. Loader-gated: without the
 * property this mixin never applies.
 */
@Mixin(value = PerlinNoise.class, remap = false)
public abstract class PerlinNoiseMixin {

    // Constants mirrored from the target (private final int, javac-inlined at target call sites
    // anyway; shadowing adds nothing).
    @Unique
    private static final int PROBE$B = 0x1000;
    @Unique
    private static final int PROBE$BM = 0xff;
    @Unique
    private static final int PROBE$N = 0x1000;

    @Shadow(remap = false)
    private Random rand;

    @Shadow(remap = false)
    private int[] p;

    @Shadow(remap = false)
    private float[] g1;

    @Unique
    private float[] probe$g2;

    @Unique
    private float[] probe$g3;

    @Unique
    private static float probe$sCurve(float t) {
        return (t * t * (3 - 2 * t));
    }

    @Unique
    private static float probe$lerp(float t, float a, float b) {
        return a + t * (b - a);
    }

    @Unique
    private void probe$normalize2(float[] v, int off) {
        float s = (float) (1 / Math.sqrt(v[off] * v[off] + v[off + 1] * v[off + 1]));
        v[off] *= s;
        v[off + 1] *= s;
    }

    @Unique
    private void probe$normalize3(float[] v, int off) {
        float s = (float) (1 / Math.sqrt(v[off] * v[off] + v[off + 1] * v[off + 1] + v[off + 2] * v[off + 2]));
        v[off] *= s;
        v[off + 1] *= s;
        v[off + 2] *= s;
    }

    /**
     * @author gtnh-determinism harness
     * @reason fill flattened gradient tables (identical rand draw order and values; see class doc)
     */
    @Overwrite(remap = false)
    private void initPerlin1() {
        p = new int[PROBE$B + PROBE$B + 2];
        probe$g3 = new float[(PROBE$B + PROBE$B + 2) * 3];
        probe$g2 = new float[(PROBE$B + PROBE$B + 2) * 2];
        g1 = new float[PROBE$B + PROBE$B + 2];
        int i, j, k;

        for (i = 0; i < PROBE$B; i++) {
            p[i] = i;

            g1[i] = (float) (((rand.nextDouble() * Integer.MAX_VALUE) % (PROBE$B + PROBE$B)) - PROBE$B) / PROBE$B;

            for (j = 0; j < 2; j++) {
                probe$g2[i * 2
                    + j] = (float) (((rand.nextDouble() * Integer.MAX_VALUE) % (PROBE$B + PROBE$B)) - PROBE$B)
                        / PROBE$B;
            }
            probe$normalize2(probe$g2, i * 2);

            for (j = 0; j < 3; j++) {
                probe$g3[i * 3
                    + j] = (float) (((rand.nextDouble() * Integer.MAX_VALUE) % (PROBE$B + PROBE$B)) - PROBE$B)
                        / PROBE$B;
            }
            probe$normalize3(probe$g3, i * 3);
        }

        while (--i > 0) {
            k = p[i];
            j = (int) ((rand.nextDouble() * Integer.MAX_VALUE) % PROBE$B);
            p[i] = p[j];
            p[j] = k;
        }

        for (i = 0; i < PROBE$B + 2; i++) {
            p[PROBE$B + i] = p[i];
            g1[PROBE$B + i] = g1[i];
            for (j = 0; j < 2; j++) probe$g2[(PROBE$B + i) * 2 + j] = probe$g2[i * 2 + j];
            for (j = 0; j < 3; j++) probe$g3[(PROBE$B + i) * 3 + j] = probe$g3[i * 3 + j];
        }
    }

    /**
     * @author gtnh-determinism harness
     * @reason flattened-table noise2 — same operations, same order (see class doc)
     */
    @Overwrite(remap = false)
    public float noise2(float x, float y) {
        float t = x + PROBE$N;
        t = Math.abs(t);
        int bx0 = ((int) t) & PROBE$BM;
        int bx1 = (bx0 + 1) & PROBE$BM;
        float rx0 = t - (int) t;
        float rx1 = rx0 - 1;

        t = y + PROBE$N;
        t = Math.abs(t);
        int by0 = ((int) t) & PROBE$BM;
        int by1 = (by0 + 1) & PROBE$BM;
        float ry0 = t - (int) t;
        float ry1 = ry0 - 1;

        int i = p[bx0];
        int j = p[bx1];

        int b00 = p[i + by0];
        int b10 = p[j + by0];
        int b01 = p[i + by1];
        int b11 = p[j + by1];

        float sx = probe$sCurve(rx0);
        float sy = probe$sCurve(ry0);

        float u = rx0 * probe$g2[b00 * 2] + ry0 * probe$g2[b00 * 2 + 1];
        float v = rx1 * probe$g2[b10 * 2] + ry0 * probe$g2[b10 * 2 + 1];
        float a = probe$lerp(sx, u, v);

        u = rx0 * probe$g2[b01 * 2] + ry1 * probe$g2[b01 * 2 + 1];
        v = rx1 * probe$g2[b11 * 2] + ry1 * probe$g2[b11 * 2 + 1];
        float b = probe$lerp(sx, u, v);

        return probe$lerp(sy, a, b);
    }

    /**
     * @author gtnh-determinism harness
     * @reason flattened-table noise3 — same operations, same order (see class doc)
     */
    @Overwrite(remap = false)
    public float noise3(float x, float y, float z) {
        float t = x + (float) PROBE$N;
        t = Math.abs(t);
        int bx0 = ((int) t) & PROBE$BM;
        int bx1 = (bx0 + 1) & PROBE$BM;
        float rx0 = (float) (t - (int) t);
        float rx1 = rx0 - 1;

        t = y + (float) PROBE$N;
        t = Math.abs(t);
        int by0 = ((int) t) & PROBE$BM;
        int by1 = (by0 + 1) & PROBE$BM;
        float ry0 = (float) (t - (int) t);
        float ry1 = ry0 - 1;

        t = z + (float) PROBE$N;
        t = Math.abs(t);
        int bz0 = ((int) t) & PROBE$BM;
        int bz1 = (bz0 + 1) & PROBE$BM;
        float rz0 = (float) (t - (int) t);
        float rz1 = rz0 - 1;

        int i = p[bx0];
        int j = p[bx1];

        int b00 = p[i + by0];
        int b10 = p[j + by0];
        int b01 = p[i + by1];
        int b11 = p[j + by1];

        t = probe$sCurve(rx0);
        float sy = probe$sCurve(ry0);
        float sz = probe$sCurve(rz0);

        int gi;
        gi = (b00 + bz0) * 3;
        float u = (rx0 * probe$g3[gi] + ry0 * probe$g3[gi + 1] + rz0 * probe$g3[gi + 2]);
        gi = (b10 + bz0) * 3;
        float v = (rx1 * probe$g3[gi] + ry0 * probe$g3[gi + 1] + rz0 * probe$g3[gi + 2]);
        float a = probe$lerp(t, u, v);

        gi = (b01 + bz0) * 3;
        u = (rx0 * probe$g3[gi] + ry1 * probe$g3[gi + 1] + rz0 * probe$g3[gi + 2]);
        gi = (b11 + bz0) * 3;
        v = (rx1 * probe$g3[gi] + ry1 * probe$g3[gi + 1] + rz0 * probe$g3[gi + 2]);
        float b = probe$lerp(t, u, v);

        float c = probe$lerp(sy, a, b);

        gi = (b00 + bz1) * 3;
        u = (rx0 * probe$g3[gi] + ry0 * probe$g3[gi + 1] + rz1 * probe$g3[gi + 2]);
        gi = (b10 + bz1) * 3;
        v = (rx1 * probe$g3[gi] + ry0 * probe$g3[gi + 1] + rz1 * probe$g3[gi + 2]);
        a = probe$lerp(t, u, v);

        gi = (b01 + bz1) * 3;
        u = (rx0 * probe$g3[gi] + ry1 * probe$g3[gi + 1] + rz1 * probe$g3[gi + 2]);
        gi = (b11 + bz1) * 3;
        v = (rx1 * probe$g3[gi] + ry1 * probe$g3[gi + 1] + rz1 * probe$g3[gi + 2]);
        b = probe$lerp(t, u, v);

        float d = probe$lerp(sy, a, b);

        return probe$lerp(sz, c, d);
    }
}
