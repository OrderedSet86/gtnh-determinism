package com.gtnhspeedrun.determinism.worldgen;

import java.util.Random;

/** Carries the seeded decoration Random from BOP's feature managers into its weighted-generator selection. */
public final class BopRandHolder {

    public static final ThreadLocal<Random> RAND = new ThreadLocal<>();

    private BopRandHolder() {}
}
