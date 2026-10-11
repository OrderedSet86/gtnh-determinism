package hunt;

/**
 * Analytic carve-volume calibration: integrates the crater paraboloid over PROXY heights (single-biome
 * rNoise, river-carved, the exact math the golden-checked screen uses) on an 8-block sample grid over the
 * 96-block disc, h = centerHeight - size + 1. Compares against the daemon-verified real volumes to decide
 * whether proxy-volume rank-orders reality well enough to drive a sweep.
 *
 * Usage: java hunt.ProxyVol <dump.json> seed:x:z:size[,seed:x:z:size...]
 */
public final class ProxyVol {

    public static void main(String[] args) throws Exception {
        final Dump dump = new Dump(args[0]);
        for (final String spec : args[1].split(",")) {
            final String[] p = spec.split(":");
            final long seed = Long.parseLong(p[0]);
            final int x = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
            final double size = Double.parseDouble(p[3]);
            final Screen s = new Screen(seed, dump);
            final float center = s.height(x, z);
            final double h = center - size + 1;
            final int step = 8;
            long vol = 0;
            for (int dx = -96; dx <= 96; dx += step) {
                for (int dz = -96; dz <= 96; dz += step) {
                    final long d2 = (long) dx * dx + (long) dz * dz;
                    if (d2 > 96 * 96) continue;
                    final double cut = h + 0.02 * d2;
                    final float top = s.height(x + dx, z + dz);
                    if (top > cut) vol += (long) ((top - cut) * step * step);
                }
            }
            System.out.printf("%d (%d,%d) size=%.3f center=%.1f dry=%b proxyvol=%d%n", seed, x, z, size, center, s.dry(x, z), vol);
        }
    }
}
