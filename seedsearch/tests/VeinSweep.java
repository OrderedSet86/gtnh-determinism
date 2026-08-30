import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * JVM reference for seedsearch/vein_predict.py, over every dimension GT generates ore veins in.
 *
 * Transcribes GTWorldgenerator.worldGenFindVein's selection loop and WorldgenGTOreLayer's first draws,
 * running on the same XSTR the game uses. Nothing here is a reimplementation of the mix table: it reads
 * the same data/oremixes-gtnh-2.8.4.json the Python side reads, so a mismatch can only mean the RNG
 * walk diverged, never that the two disagree about what a mix is. Extractor correctness is a separate
 * check (re-run data/extract_oremixes_from_jar.py against the shipped jar and diff).
 *
 * Emits: seed dim ox oz mixName attemptIdx tMinY wXVein eXVein nZVein sZVein
 * or:    seed dim ox oz NONE
 */
public class VeinSweep {

    static final int OREVEIN_ATTEMPTS = 64; // GregTech.cfg I:oreveinAttempts
    // GTWorldgen.isGenerationAllowed hard-rejects any provider name outside these four, so these are
    // the only dimensions a GT ore vein can occupy however many a pack registers.
    static final int[] DIMS = { 0, -1, 1, 7 };
    static final String[] TOKENS = { "Overworld", "Nether", "TheEnd", "Twilight Forest" };

    record Mix(String name, int weight, int minY, int maxY, int size, int primaryMeta, List<String> dims) {}

    static List<Mix> mixes = new ArrayList<>();
    static int sWeight = 0;

    public static void main(String[] args) throws Exception {
        load(args.length > 0 ? args[0] : "../data/oremixes-gtnh-2.8.4.json");
        final Random meta = new Random(4242);
        for (int t = 0; t < 20000; t++) {
            final long seed = meta.nextLong();
            final int d = meta.nextInt(DIMS.length);
            final int dim = DIMS[d];
            final String token = TOKENS[d];
            // oreseed cells only: EQUAL_SPACING is floorMod(chunk,3)==1 on both axes.
            final int ox = Math.floorDiv(meta.nextInt(4001) - 2000, 3) * 3 + 1;
            final int oz = Math.floorDiv(meta.nextInt(4001) - 2000, 3) * 3 + 1;

            final long oreveinSeed = (seed << 16)
                ^ (((dim & 0xffL) << 56) | (((long) ox & 0x0fffffffL) << 28) | ((long) oz & 0x0fffffffL));
            final XSTR rng = new XSTR(oreveinSeed);
            rng.nextInt(100); // oreveinPercentageRoll; oreveinPercentage=100 so it always passes
            Mix hit = null;
            int attempt = -1;
            for (int i = 0; i < OREVEIN_ATTEMPTS && hit == null; i++) {
                int w = rng.nextInt(sWeight);
                for (Mix m : mixes) {
                    w -= m.weight();
                    if (w <= 0) {
                        if (m.dims()
                            .contains(token)) {
                            hit = m;
                            attempt = i;
                        }
                        break;
                    }
                }
            }
            if (hit == null) {
                System.out.println(seed + " " + dim + " " + ox + " " + oz + " NONE");
                continue;
            }
            // Per-vein stream: a separate XSTR, so rejected attempts never perturb it.
            final XSTR vr = new XSTR(oreveinSeed ^ hit.primaryMeta());
            final int tMinY = hit.minY() + vr.nextInt(hit.maxY() - hit.minY() - 5);
            final int seedX = ox * 16, seedZ = oz * 16;
            final int wX = seedX - vr.nextInt(hit.size());
            final int eX = seedX + 16 + vr.nextInt(hit.size());
            final int nZ = seedZ - vr.nextInt(hit.size());
            final int sZ = seedZ + 16 + vr.nextInt(hit.size());
            System.out.println(
                seed + " " + dim + " " + ox + " " + oz + " " + hit.name() + " " + attempt + " " + tMinY + " " + wX
                    + " " + eX + " " + nZ + " " + sZ);
        }
    }

    /** Minimal reader for the flat array of flat objects the extractor writes — no JSON dependency. */
    static void load(String path) throws Exception {
        final StringBuilder all = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = r.readLine()) != null) all.append(line);
        }
        final String body = all.toString();
        final List<Mix> parsed = new ArrayList<>();
        int i = 0;
        while ((i = body.indexOf("\"enumIndex\"", i)) >= 0) {
            final int end = body.indexOf('}', i);
            final String obj = body.substring(i, end < 0 ? body.length() : end);
            parsed.add(
                new Mix(
                    str(obj, "name"),
                    num(obj, "weight"),
                    num(obj, "minY"),
                    num(obj, "maxY"),
                    num(obj, "size"),
                    num(obj, "primaryMeta"),
                    strList(obj, "dims")));
            i = end < 0 ? body.length() : end;
        }
        // The file is written in enumIndex order and GT's sList walk depends on that order.
        mixes = parsed;
        for (Mix m : mixes) sWeight += m.weight();
        System.err.println("loaded " + mixes.size() + " mixes, sWeight=" + sWeight);
    }

    static String str(String obj, String field) {
        final int k = obj.indexOf("\"" + field + "\"");
        final int a = obj.indexOf('"', obj.indexOf(':', k) + 1);
        return obj.substring(a + 1, obj.indexOf('"', a + 1));
    }

    static int num(String obj, String field) {
        final int k = obj.indexOf("\"" + field + "\"");
        int a = obj.indexOf(':', k) + 1;
        while (a < obj.length() && (obj.charAt(a) == ' ' || obj.charAt(a) == '\t')) a++;
        int b = a;
        while (b < obj.length() && (Character.isDigit(obj.charAt(b)) || obj.charAt(b) == '-')) b++;
        return Integer.parseInt(obj.substring(a, b));
    }

    static List<String> strList(String obj, String field) {
        final List<String> out = new ArrayList<>();
        final int k = obj.indexOf("\"" + field + "\"");
        final int open = obj.indexOf('[', k);
        final int close = obj.indexOf(']', open);
        final String inner = obj.substring(open + 1, close);
        int a = inner.indexOf('"');
        while (a >= 0) {
            final int b = inner.indexOf('"', a + 1);
            out.add(inner.substring(a + 1, b));
            a = inner.indexOf('"', b + 1);
        }
        return out;
    }
}
