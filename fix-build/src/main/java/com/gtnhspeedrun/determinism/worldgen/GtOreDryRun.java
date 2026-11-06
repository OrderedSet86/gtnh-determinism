package com.gtnhspeedrun.determinism.worldgen;

/**
 * Marks the window in which GregTech is DRY-RUNNING a candidate ore vein.
 *
 * <p>
 * {@code OreManager.getOreBlockForWorldGen} serves both the dry run, which decides vein IDENTITY, and the real
 * placement, which writes blocks. Only the former needs virgin answers; redirecting the latter would change
 * where ore physically lands, a far larger behavioural change than this fix intends.
 *
 * <p>
 * The scope is opened by a {@code @Redirect} around the inner {@code canSetOreForWorldGen} call in
 * {@code OreManager.canSetOreForWorldGenOrAlreadySet}, in a try/finally. That call site has one caller in GregTech,
 * {@code WorldgenGTOreLayer.placeOre}, inside the {@code if (dryRun)} branch. No write runs inside the scope, and the
 * finally closes the scope on any throw. An {@code @Inject} at the head and RETURN of {@code testWorldgenChunkified}
 * would not close on a throw: RETURN does not fire when the method throws, and {@code generateVein} swallows
 * exceptions, so one throw would leave every later REAL placement on that thread reading virgin terrain.
 *
 * <p>
 * A depth counter rather than a boolean: the guarded region is entered once per candidate block, and nesting
 * must not be closed by the first inner exit.
 */
public final class GtOreDryRun {

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private GtOreDryRun() {}

    public static void open() {
        DEPTH.get()[0]++;
    }

    public static void close() {
        final int[] d = DEPTH.get();
        if (d[0] > 0) d[0]--;
    }

    public static boolean active() {
        return DEPTH.get()[0] > 0;
    }
}
