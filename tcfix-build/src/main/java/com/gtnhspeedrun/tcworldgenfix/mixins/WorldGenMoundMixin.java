package com.gtnhspeedrun.tcworldgenfix.mixins;

import java.util.Random;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import thaumcraft.common.lib.world.WorldGenMound;

/**
 * Barrow (mound) loot containers are chosen with {@code world.rand} — the World's clock-seeded global Random — so
 * whether each loot spot is a crate or an urn differs every run even though the barrow itself is seed-placed.
 * (GTNH speedrun determinism audit, finding F3.)
 *
 * Fix: capture a Random forked off the generation RNG at the entry of generate()/generate2() and redirect the
 * {@code world.rand} reads inside generate2() to it.
 *
 * Note: the FIELD target below uses the SRG name (field_73012_v) because this late mixin is applied to a mod class at
 * production runtime; in a deobfuscated dev environment the redirect will not match (harmless for the shipped jar).
 */
@Mixin(value = WorldGenMound.class, remap = false)
public class WorldGenMoundMixin {

    @Unique
    private static final ThreadLocal<Random> tcfix$moundRand = new ThreadLocal<>();

    @Inject(method = "generate", at = @At("HEAD"))
    private void tcfix$captureRand(World world, Random rand, int i, int j, int k, CallbackInfoReturnable<Boolean> cir) {
        tcfix$moundRand.set(new Random(rand.nextLong()));
    }

    @Inject(method = "generate2", at = @At("HEAD"))
    private void tcfix$captureRand2(World world, Random rand, int i, int j, int k,
        CallbackInfoReturnable<Boolean> cir) {
        tcfix$moundRand.set(new Random(rand.nextLong()));
    }

    @Redirect(
        method = "generate2",
        at = @At(value = "FIELD", target = "Lnet/minecraft/world/World;field_73012_v:Ljava/util/Random;", opcode = 180))
    private Random tcfix$deterministicRand(World world) {
        final Random r = tcfix$moundRand.get();
        return r != null ? r : world.rand;
    }
}
