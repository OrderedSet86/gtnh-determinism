package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IEntityLivingData;
import net.minecraft.entity.passive.EntityHorse;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.determinism.worldgen.EntitySpawnRng;

/**
 * A horse's speed, jump and health are clock-random, so the same seed gives a different animal every launch.
 * <p>
 * {@code EntityHorse.onSpawnWithEgg} decides everything about the animal from {@code this.rand} — the type
 * (horse, donkey, mule, skeletal, zombie), the coat variant, and via three helpers the three stats that actually
 * matter for routing:
 *
 * <pre>
 * func_110267_cL() = 15 + rand.nextInt(8) + rand.nextInt(9)                          // max health, 15..30
 * func_110245_cM() = 0.40 + 3 x (rand.nextDouble() * 0.2)                            // jump strength, 0.40..1.00
 * func_110203_cN() = (0.45 + 3 x (rand.nextDouble() * 0.3)) * 0.25                   // speed, 0.1125..0.3375
 * </pre>
 *
 * {@code Entity.rand} is a bare {@code new Random()}, redirected by BugTorch to a {@code nanoTime}-seeded Xoshiro
 * ({@code replaceRandomInEntity=true} in both pack lines). Movement speed therefore varies threefold on the same
 * seed between launches, and donkey-versus-horse is a coin flip — the difference between a route having portable
 * storage and fast travel, or not.
 * <p>
 * Two details drive the shape of this fix:
 * <ul>
 * <li>The three stat rolls live in separate private methods, so a redirect scoped to {@code onSpawnWithEgg} alone
 * would miss them.</li>
 * <li>Those same private methods are also called by {@code createChild} (:1548-1552) to breed a foal. Redirecting
 * them unconditionally would make bred foals position-seeded too, which is a gameplay change well outside
 * worldgen determinism. So the fork is armed only for the duration of {@code onSpawnWithEgg}; every other caller
 * keeps the stock RNG.</li>
 * </ul>
 * A single fork is created per horse and advanced across all the draws, rather than one fork per call — otherwise
 * every draw would restart the same stream and return the same number.
 * <p>
 * {@code getRNG()} is used instead of shadowing {@code Entity.rand}: the field is inherited, and Mixin resolves
 * {@code @Shadow} members against the target class only.
 */
@Mixin(EntityHorse.class)
public abstract class EntityHorseMixin {

    /** Non-null only while onSpawnWithEgg is on the stack. Null for breeding and every other caller. */
    @Unique
    private Random gtnhdet$spawnFork;

    @Unique
    private Random gtnhdet$rng() {
        return this.gtnhdet$spawnFork != null ? this.gtnhdet$spawnFork : ((EntityLivingBase) (Object) this).getRNG();
    }

    @Inject(method = "onSpawnWithEgg", at = @At("HEAD"))
    private void gtnhdet$armSpawnFork(IEntityLivingData data, CallbackInfoReturnable<IEntityLivingData> cir) {
        this.gtnhdet$spawnFork = EntitySpawnRng.forEntity((Entity) (Object) this);
    }

    @Inject(method = "onSpawnWithEgg", at = @At("RETURN"))
    private void gtnhdet$disarmSpawnFork(IEntityLivingData data, CallbackInfoReturnable<IEntityLivingData> cir) {
        this.gtnhdet$spawnFork = null;
    }

    /** Type and variant in onSpawnWithEgg; max health in func_110267_cL. */
    @Redirect(
        method = { "onSpawnWithEgg", "func_110267_cL" },
        at = @At(value = "INVOKE", target = "Ljava/util/Random;nextInt(I)I"))
    private int gtnhdet$nextInt(Random stock, int bound) {
        return this.gtnhdet$rng()
            .nextInt(bound);
    }

    /** Jump strength (func_110245_cM) and movement speed (func_110203_cN). */
    @Redirect(
        method = { "func_110245_cM", "func_110203_cN" },
        at = @At(value = "INVOKE", target = "Ljava/util/Random;nextDouble()D"))
    private double gtnhdet$nextDouble(Random stock) {
        return this.gtnhdet$rng()
            .nextDouble();
    }
}
