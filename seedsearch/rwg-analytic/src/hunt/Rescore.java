package hunt;

/**
 * Current-pipeline stage-0b rescore of explicit sites: blended surface, landing lattice, slope-aware
 * crater verdict, buried-h0 volume — the exact numbers the sweep would emit for these seeds. Used to set
 * survivor bars relative to the verified frontier.
 *
 * Usage: java hunt.Rescore <dump.json> seed:x:z:size:startDepthUnknownUseGx? — no: seed:gx:gz per site.
 * Args: dump.json seed:gx:gz[,...] (gx,gz = the candidate's grid cell; RNG regenerates the rest)
 */
public final class Rescore {

    public static void main(String[] args) throws Exception {
        final Dump dump = new Dump(args[0]);
        for (final String spec : args[1].split(",")) {
            final String[] p = spec.split(":");
            final long seed = Long.parseLong(p[0]);
            final int gx = Integer.parseInt(p[1]), gz = Integer.parseInt(p[2]);
            final long[] cand = Ae2.candidate(seed, gx, gz);
            final double size = Double.longBitsToDouble(cand[2]);
            final Screen screen = new Screen(seed, dump);
            final BlendedHeight bh = new BlendedHeight(screen, dump);
            final int x = (int) cand[0], z = (int) cand[1];
            final int surface = (int) bh.height(x, z);
            final int landing = bh.landing((int) cand[3], surface);
            final int minNear = bh.minNear(x, z);
            final long vol = bh.volumeAt(x, z, landing, size, 96);
            System.out.printf(
                "%d (%d,%d) size=%.3f start=%d surface=%d landing=%d burial=%d minNear=%d crater=%b blendedVol=%d%n",
                seed, x, z, size, cand[3], surface, landing, surface - landing, minNear, landing + 10 >= minNear, vol);
        }
    }
}
