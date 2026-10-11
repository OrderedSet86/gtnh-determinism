package hunt;

import java.io.FileWriter;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stage-0 game-free meteor-crater hunter. Replicates the AE2 candidate RNG (Platform.seedFromGrid's
 * `>> 2 + 1L` == `>> 3` precedence quirk included) and the prefilter's analytic screen, at millions of
 * seeds per minute. Survivors go to the in-game prefilter (exact chunk scoring), then the warm daemon.
 *
 * Usage: java hunt.Main <dump.json> <count> <salt> <cells> <minProxy> <minOpposed> <out.jsonl> [threads] [startIndex] [minBlendedVol] [ringMinVol]
 *
 * ringMinVol > 0 adds a second survivor rule for the pretty ones: enclosure >= 15 AND blended >= ringMinVol
 * (ring craters score below champions on volume but the user likes looking at them).
 *
 * With minBlendedVol > 0, stage-0b runs on 0a survivors: BlendedHeight dry check + exact pre-cave carve
 * volume (calibrated: real ~= blended / 0.77), emitted as blendedvol. 0a stays a pure recall bar.
 *
 * Output is APPEND-mode and startIndex resumes a killed run partway through the salt-derived seed
 * stream — a relaunch must never redo or truncate finished work.
 *
 * Seeds are drawn exactly like the prefilter's random:COUNT:SALT spec would NOT — this tool uses
 * new Random(salt).nextLong() per seed; record the salt, the survivors file carries explicit seeds.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        final String dumpPath = args[0];
        final long count = Long.parseLong(args[1]);
        final long salt = Long.parseLong(args[2]);
        final int cells = Integer.parseInt(args[3]);
        final int minProxy = Integer.parseInt(args[4]);
        final int minOpposed = Integer.parseInt(args[5]);
        final String out = args[6];
        final int threads = args.length > 7 ? Integer.parseInt(args[7]) : Runtime.getRuntime()
            .availableProcessors() - 2;

        final Dump dump = new Dump(dumpPath);
        final long[] seeds = new long[(int) count];
        final Random saltRng = new Random(salt);
        for (int i = 0; i < count; i++) seeds[i] = saltRng.nextLong();

        final AtomicLong next = new AtomicLong(); // set to startIndex below
        final AtomicLong done = new AtomicLong();
        final long t0 = System.nanoTime();
        final long startIndex = args.length > 8 ? Long.parseLong(args[8]) : 0;
        final long minBvol = args.length > 9 ? Long.parseLong(args[9]) : 0;
        final long ringMinVol = args.length > 10 ? Long.parseLong(args[10]) : 0;
        final FileWriter w = new FileWriter(out, true);
        next.set(startIndex);
        done.set(startIndex);
        final Thread[] pool = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            pool[t] = new Thread(() -> {
                try {
                    final StringBuilder sb = new StringBuilder(256);
                    for (long i; (i = next.getAndIncrement()) < count;) { // begins at startIndex
                        final long seed = seeds[(int) i];
                        Screen screen = null; // lazy: most seeds have no size-passing candidate near max
                        BlendedHeight bh = null; // per-seed: weight-cascade cache shared across candidates
                        for (int gx = -cells; gx <= cells; gx++) {
                            for (int gz = -cells; gz <= cells; gz++) {
                                final long[] cand = Ae2.candidate(seed, gx, gz);
                                final double size = Double.longBitsToDouble(cand[2]);
                                if (size < 6.5) continue;
                                if (screen == null) screen = new Screen(seed, dump);
                                final int[] r = screen.screen((int) cand[0], (int) cand[1]);
                                if (r[0] < minProxy || r[1] < minOpposed) continue;
                                long bvol = -1;
                                int burial = -1;
                                if (minBvol > 0) {
                                    // stage-0b: blended ring pre-gate (cascade + 49 columns, ~10x cheaper
                                    // than the disc), then dry + burial lattice, then the carve integral
                                    if (bh == null) bh = new BlendedHeight(screen, dump);
                                    if (bh.ringRelief((int) cand[0], (int) cand[1]) < 100) continue;
                                    if (!bh.dry((int) cand[0], (int) cand[1])) continue;
                                    final int surface = (int) bh.height((int) cand[0], (int) cand[1]);
                                    final int landing = bh.landing((int) cand[3], surface);
                                    burial = surface - landing;
                                    // slope-aware skyMode proxy: crater iff landing+10 clears the MIN
                                    // nearby surface (cliff-foot sites carve at center burial 14)
                                    if (landing + 10 < bh.minNear((int) cand[0], (int) cand[1])
                                        || landing < 40) continue;
                                    bvol = bh.volumeAt((int) cand[0], (int) cand[1], landing, size, 96);
                                    final boolean champion = bvol >= minBvol;
                                    final boolean ring = ringMinVol > 0 && bh.lastEnclosure >= 15 && bvol >= ringMinVol;
                                    // bridge rule: the 500k+ AND 15/16 target lives between the other two
                                    // rules — surface its frontier (big near-rings) instead of hiding it
                                    final boolean bigNearRing = bh.lastEnclosure >= 12 && bvol >= 300000;
                                    if (!champion && !ring && !bigNearRing) continue;
                                }
                                sb.setLength(0);
                                sb.append("{\"seed\": ")
                                    .append(seed)
                                    .append(", \"x\": ")
                                    .append(cand[0])
                                    .append(", \"z\": ")
                                    .append(cand[1])
                                    .append(", \"size\": ")
                                    .append(Math.round(size * 1000) / 1000.0)
                                    .append(", \"proxyrelief\": ")
                                    .append(r[0])
                                    .append(", \"proxyopposed\": ")
                                    .append(r[1])
                                    .append(", \"blendedvol\": ")
                                    .append(bvol)
                                    .append(", \"burial\": ")
                                    .append(burial)
                                    .append(", \"enclosure\": ")
                                    .append(bh == null ? -1 : bh.lastEnclosure)
                                    .append("}\n");
                                synchronized (w) {
                                    w.write(sb.toString());
                                    w.flush();
                                }
                            }
                        }
                        final long d = done.incrementAndGet();
                        if (d % 1000000 == 0) {
                            final double sps = d / ((System.nanoTime() - t0) / 1e9);
                            System.err.printf("%d/%d (%.0f seeds/s)%n", d, count, sps);
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            pool[t].start();
        }
        for (final Thread th : pool) th.join();
        w.close();
        final double secs = (System.nanoTime() - t0) / 1e9;
        System.err.printf("done: %d seeds in %.1f s (%.0f seeds/s)%n", count, secs, count / secs);
    }
}
