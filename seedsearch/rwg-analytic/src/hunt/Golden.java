package hunt;

/**
 * Golden check driver: prints every size>=6.5 candidate's screen values for explicit seeds, for diffing
 * against the in-game prefilter's rows (opposedcal.jsonl). Run before trusting any sweep.
 *
 * Usage: java hunt.Golden <dump.json> <seed1,seed2,...> [cells=2]
 */
public final class Golden {

    public static void main(String[] args) throws Exception {
        final Dump dump = new Dump(args[0]);
        final int cells = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        for (final String s : args[1].split(",")) {
            final long seed = Long.parseLong(s.trim());
            final Screen screen = new Screen(seed, dump);
            for (int gx = -cells; gx <= cells; gx++) {
                for (int gz = -cells; gz <= cells; gz++) {
                    final long[] cand = Ae2.candidate(seed, gx, gz);
                    final double size = Double.longBitsToDouble(cand[2]);
                    if (size < 6.5) continue;
                    final int[] r = screen.screen((int) cand[0], (int) cand[1]);
                    System.out.printf(
                        "%d (%d,%d) size=%.3f proxy=%d opposed=%d%n",
                        seed,
                        cand[0],
                        cand[1],
                        size,
                        r[0],
                        r[1]);
                }
            }
        }
    }
}
