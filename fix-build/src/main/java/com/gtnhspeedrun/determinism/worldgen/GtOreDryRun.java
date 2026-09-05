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
 * The scope is opened by a {@code @Redirect} wrapping the inner {@code canSetOreForWorldGen} call inside
 * {@code OreManager.canSetOreForWorldGenOrAlreadySet}, in a try/finally. That site is chosen deliberately: it
 * has exactly ONE caller in the whole GregTech tree ({@code WorldgenGTOreLayer.placeOre}, inside its
 * {@code if (dryRun)} branch), so the scope is structurally incapable of leaking into the write path, and the
 * finally closes it on any throw. An earlier revision opened the scope with an {@code @Inject} at the head of
 * {@code testWorldgenChunkified} and closed it at RETURN — which does not fire when the method throws, and
 * {@code generateVein} swallows exceptions, so a single throw would have left every subsequent REAL placement
 * on that thread reading virgin terrain, silently.
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
