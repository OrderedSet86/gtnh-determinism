package hunt;

import java.util.List;

import rwg.terrain.TerrainBase;
import rwg.util.CellNoise;
import rwg.util.NoiseGenerator;
import rwg.util.OpenSimplexNoise;
import rwg.util.PerlinNoise;

/**
 * Game-free replica of the prefilter's AnalyticScreen: biome band selection, river strength/carve and the
 * biome's own terrain formula, evaluated with PerlinNoise/CellNoise constructed straight from the world
 * seed — the same math the shipped RWG-alpha-1.5.2 jar runs (its NoiseGeneratorWrapper delegates to
 * PerlinNoise; both its cell instances are distance-mode). Biome wiring comes from the game's own dump
 * (rwg-dump.json via -Dprobe.prefilter.meteor.dumprwg), never hand-transcribed. Verbatim ports:
 * getBiomeDataAt's band selection, getRiverStrength, calculateRiver (ChunkManagerRealistic.java:243-291,
 * 393-411).
 *
 * This is stage 0 of 3: survivors go to the in-game prefilter for exact chunk scoring, then the warm
 * probe daemon for verification. A golden check against the game screen's numbers on the calibration
 * seeds is mandatory before any sweep counts.
 */
public final class Screen {

    private final NoiseGenerator perlin;
    private final CellNoise cell; // distance-mode: terrain + river use this
    private final CellNoise biomecell; // NOT distance-mode: biome band selection
    private final Dump dump;

    public Screen(long seed, Dump dump) {
        this.dump = dump;
        perlin = "opensimplex".equals(System.getProperty("hunt.noise")) ? new OpenSimplexNoise(seed) : new PerlinNoise(seed);
        cell = new CellNoise(seed, (short) 0);
        cell.setUseDistance(true);
        biomecell = new CellNoise(seed, (short) 0);
    }

    /**
     * ChunkManagerRealistic.getBiomeDataAt band selection, verbatim (ocean-independent). Returns
     * {bandIndex 0..4 (snow,cold,hot,wet,small), normalizedPick} so BlendedHeight can dedup biome ids.
     */
    float[] bandPick(int x, int z) {
        float b = (biomecell.noise((x + 4000f) / 1200D, z / 1200D, 1D) * 0.5f) + 0.5f;
        b = b < 0f ? 0f : b >= 0.9999999f ? 0.9999999f : b;
        float s = dump.smallEnabled ? (biomecell.noise(x / 140D, z / 140D, 1D) * 0.5f) + 0.5f : 0f;
        int band;
        float h;
        if (dump.smallEnabled && s > 0.975f) {
            h = (s - 0.975f) * 40f;
            band = 4;
        } else {
            h = (biomecell.noise(x / 450D, z / 450D, 1D) * 0.5f) + 0.5f;
            if ((dump.wetEnabled && b < 0.25f) || (!dump.wetEnabled && b < 0.33f)) band = 0;
            else if ((dump.wetEnabled && b < 0.50f) || (!dump.wetEnabled && b < 0.66f)) band = 1;
            else if ((dump.wetEnabled && b < 0.75f) || (!dump.wetEnabled && b < 1f)) band = 2;
            else if (dump.wetEnabled) band = 3;
            else band = 2;
        }
        h = h < 0f ? 0f : h >= 0.9999999f ? 0.9999999f : h;
        return new float[] { band, h };
    }

    TerrainBase terrainAt(int x, int z) {
        final float[] pick = bandPick(x, z);
        final List<TerrainBase> band = switch ((int) pick[0]) {
            case 0 -> dump.snow;
            case 1 -> dump.cold;
            case 2 -> dump.hot;
            case 3 -> dump.wet;
            default -> dump.small;
        };
        return band.get((int) (pick[1] * band.size()));
    }

    /** ChunkManagerRealistic.getOceanValue + diff, verbatim. */
    float oceanValue(int x, int y) {
        float base = -(-0f);
        float sample1 = perlin.noise2(x / 1200f, y / 1200f) + base;
        float sample2 = 0f, sa = 0f, highest = 0f;
        if (sample1 == 0f) highest = 1f;
        if (diff(sample1, sample2 = perlin.noise2((x - 100f) / 1200f, y / 1200f) + base, base)) {
            sa = sample1 * (1 / Math.abs(sample1 - sample2));
            highest = 1f - Math.abs(sa) > highest ? 1f - Math.abs(sa) : highest;
        } else if (diff(sample1, sample2 = perlin.noise2((x + 100f) / 1200f, y / 1200f) + base, base)) {
            sa = sample1 * (1 / Math.abs(sample1 - sample2));
            highest = 1f - Math.abs(sa) > highest ? 1f - Math.abs(sa) : highest;
        }
        if (diff(sample1, sample2 = perlin.noise2(x / 1200f, (y + 100f) / 1200f) + base, base)) {
            sa = sample1 * (1 / Math.abs(sample1 - sample2));
            highest = 1f - Math.abs(sa) > highest ? 1f - Math.abs(sa) : highest;
        } else if (diff(sample1, sample2 = perlin.noise2(x / 1200f, (y - 100f) / 1200f) + base, base)) {
            sa = sample1 * (1 / Math.abs(sample1 - sample2));
            highest = 1f - Math.abs(sa) > highest ? 1f - Math.abs(sa) : highest;
        }
        if (sample1 > 0f) highest = 2f - highest;
        return highest;
    }

    private static boolean diff(float sample1, float sample2, float base) {
        return (sample1 < base && sample2 > base) || (sample1 > base && sample2 < base);
    }

    rwg.util.NoiseGenerator perlin() {
        return perlin;
    }

    CellNoise cellNoise() {
        return cell;
    }

    /** ChunkManagerRealistic.getRiverStrength, verbatim. */
    float riverStrength(int x, int z) {
        return cell.border(
            (x + (perlin.noise1(z / 240f) * 220f)) / 1250D,
            (z + (perlin.noise1(x / 240f) * 220f)) / 1250D,
            50D / 300D,
            1f);
    }

    /** ChunkManagerRealistic.calculateRiver, verbatim. */
    float calculateRiver(int x, int z, float st, float biomeHeight) {
        if (st < 0f && biomeHeight > 59f) {
            float pX = x + (perlin.noise1(z / 240f) * 220f);
            float pY = z + (perlin.noise1(x / 240f) * 220f);
            float r = cell.border(pX / 1250D, pY / 1250D, 50D / 1300D, 1f);
            return (biomeHeight * (r + 1f))
                + ((59f + perlin.noise2(x / 12f, z / 12f) * 2f + perlin.noise2(x / 8f, z / 8f) * 1.5f) * (-r));
        }
        return biomeHeight;
    }

    /** The prefilter screen's height proxy: single-biome rNoise (border=1, ocean=1), river-carved. */
    public float height(int x, int z) {
        final float river = riverStrength(x, z);
        final float h = terrainAt(x, z).generateNoise(perlin, cell, x, z, 1f, 1f, river + 1f);
        return calculateRiver(x, z, river, h);
    }

    /**
     * Meteor viability: water is in AE2's invalidSpawn and every below-63 RWG channel floods, so an impact
     * column in or beside water never spawns (field-verified: the proxy-291/15 site at (1711,1660) on seed
     * -3888455423101392440 is a river — perfect walls, no meteorite). Dry = center and a 12-block ring all
     * at or above water line.
     */
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

    /** Identical sampling and sector logic to AnalyticScreen.screen(). Returns {proxyRelief, proxyOpposed}. */
    public int[] screen(int x, int z) {
        final float center = height(x, z);
        final float[] sector = new float[16];
        float max = center;
        for (int s = 0; s < 16; s++) {
            final double a = s * Math.PI / 8;
            sector[s] = center;
            for (final int r : new int[] { 40, 64, 88 }) {
                final float h = height(x + (int) Math.round(r * Math.cos(a)), z + (int) Math.round(r * Math.sin(a)));
                if (h > sector[s]) sector[s] = h;
                if (h > max) max = h;
            }
        }
        int opposed = 0;
        for (int s = 0; s < 16; s++) {
            if (sector[s] - center < 90) continue;
            final int o = s + 8;
            if (sector[o & 15] - center >= 90 || sector[(o + 1) & 15] - center >= 90
                || sector[(o - 1) & 15] - center >= 90) {
                opposed++;
            }
        }
        return new int[] { (int) (max - center), opposed };
    }
}
