package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.determinism.worldgen.BopRandHolder;

import biomesoplenty.common.world.features.managers.WorldGenBOPFlowerManager;

/** Captures the seeded decoration Random for {@link BOPBiomeDecoratorMixin}. */
@Mixin(value = WorldGenBOPFlowerManager.class, remap = false)
public class WorldGenBOPFlowerManagerMixin {

    @Inject(method = "func_76484_a", at = @At("HEAD"))
    private void gtnhdet$captureRand(World world, Random random, int x, int y, int z,
        CallbackInfoReturnable<Boolean> cir) {
        BopRandHolder.RAND.set(random);
    }
}
