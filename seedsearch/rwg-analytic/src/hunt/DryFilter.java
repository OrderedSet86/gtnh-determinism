package hunt;

import java.io.BufferedReader;
import java.io.FileReader;

/** Retro-applies the dry veto to survivor rows collected before it existed. Args: dump.json rows.jsonl */
public final class DryFilter {

    public static void main(String[] args) throws Exception {
        final Dump dump = new Dump(args[0]);
        try (BufferedReader r = new BufferedReader(new FileReader(args[1]))) {
            String line;
            while ((line = r.readLine()) != null) {
                final long seed = Long.parseLong(line.replaceAll(".*\"seed\": (-?\\d+).*", "$1"));
                final int x = Integer.parseInt(line.replaceAll(".*\"x\": (-?\\d+).*", "$1"));
                final int z = Integer.parseInt(line.replaceAll(".*\"z\": (-?\\d+).*", "$1"));
                if (new Screen(seed, dump).dry(x, z)) System.out.println("DRY " + line);
            }
        }
    }
}
