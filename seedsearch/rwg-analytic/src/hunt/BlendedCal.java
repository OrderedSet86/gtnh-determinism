package hunt;

/**
 * Calibration driver for BlendedHeight: per verified site, blended center vs the game digest's surface
 * (tolerance: caves + replaceBlocksForBiome move topSolid a block or two), blended dry, and the exact
 * pre-cave carve volume for rank comparison against daemon-verified volumes.
 *
 * Usage: java hunt.BlendedCal <dump.json> seed:x:z:size:gameSurface:realVol[,...]
 */
public final class BlendedCal {

    public static void main(String[] args) throws Exception {
        final Dump dump = new Dump(args[0]);
        System.out.printf("%22s %14s %8s %8s %5s %12s %10s%n", "seed", "impact", "blended", "gameSurf", "dry", "blendedVol", "realVol");
        for (final String spec : args[1].split(",")) {
            final String[] p = spec.split(":");
            final long seed = Long.parseLong(p[0]);
            final int x = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
            final double size = Double.parseDouble(p[3]);
            final BlendedHeight bh = new BlendedHeight(new Screen(seed, dump), dump);
            System.out.printf(
                "%22d (%5d,%5d) %8.1f %8s %5b %12d %10s%n",
                seed,
                x,
                z,
                bh.height(x, z),
                p[4],
                bh.dry(x, z),
                bh.volume(x, z, size, 96),
                p[5]);
        }
    }
}
