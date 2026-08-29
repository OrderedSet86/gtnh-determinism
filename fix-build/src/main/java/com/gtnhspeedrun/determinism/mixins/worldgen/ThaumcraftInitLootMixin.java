package com.gtnhspeedrun.determinism.mixins.worldgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import thaumcraft.common.config.Config;

/**
 * Thaumcraft rolls the vis charge of the loot Vis Amulet off the wall clock, once, at mod init:
 *
 * <pre>
 * public static void initLoot() {
 *     Random random = new Random(System.currentTimeMillis());
 *     ItemStack stack = new ItemStack(ConfigItems.itemAmuletVis, 1, 0);
 *     for (Aspect a : Aspect.getPrimalAspects()) amulet.addVis(stack, a, random.nextInt(5), true);
 *     ...                       // that ONE stack is then copied into ChestGenHooks entries
 * }
 * </pre>
 *
 * The stack is built once and copied into both {@code ChestGenHooks} and Thaumcraft's loot-bag
 * tables, so every amulet in a JVM session carries the same charge and every launch carries a
 * different one: each primal aspect gets {@code nextInt(5)} vis, stored as 0/100/200/300/400
 * centi-vis. Field-confirmed on the daily pack at chest (4, 39, 233), which held
 * {@code aer:300 aqua:300 ignis:400 ordo:400 perditio:400 terra:400} in one cold run and
 * {@code aer:200 aqua:200 ignis:0 ordo:400 perditio:200 terra:300} in the next.
 *
 * <p>
 * This sits UPSTREAM of the F7 loot-table snapshot in {@link com.gtnhspeedrun.determinism.GtnhDeterminism}:
 * F7 captures {@code ChestGenHooks.contents} at load-complete, by which point the clock-random NBT is
 * already baked into the entry. That is why the charge is stable within a session and varies across
 * launches — F7 preserves it faithfully, including the part that should not have been random.
 *
 * <p>
 * Pinning the seed can only make the charge a fixed value: {@code initLoot} runs at mod init, before
 * any world exists, so there is no seed to derive from. The resulting charge is one of the outcomes
 * stock could roll, so nothing moves outside the stock distribution.
 *
 * <p>
 * For chest-placed amulets that constant is then replaced per chest by {@link ChestAmuletVisMixin},
 * which has coordinates to work with. This mixin still matters for the other destination the same
 * stack is copied into: Thaumcraft's loot bags. Note the limit of that — it fixes the charge an
 * amulet carries, NOT whether a bag yields one. {@code ItemLootBag} picks its reward from
 * {@code world.rand} when the player right-clicks, which is gameplay-time randomness and out of
 * scope here, exactly as LootGames' minigame rewards are.
 *
 * <p>
 * Thaumcraft's {@code Config.class} is byte-identical (md5 f6cf23b7d3f2967b93b9338cecb95348) between
 * the 4.2.3.5a shipped in 2.7.4 and the 4.2.3.5 shipped on daily, so {@code require = 1} is safe
 * across the whole supported range. {@code initLoot} calls {@code currentTimeMillis} exactly once.
 */
@Mixin(value = Config.class, remap = false)
public class ThaumcraftInitLootMixin {

    /**
     * Arbitrary but fixed. Any constant works; this one is recorded so the amulet charge it produces
     * can be reproduced and recognised in a seed corpus.
     */
    private static final long GTNHDET$LOOT_SEED = 0x9E3779B97F4A7C15L;

    @Redirect(
        method = "initLoot",
        at = @At(value = "INVOKE", target = "Ljava/lang/System;currentTimeMillis()J"),
        require = 1)
    private static long gtnhdet$fixedLootSeed() {
        return GTNHDET$LOOT_SEED;
    }
}
