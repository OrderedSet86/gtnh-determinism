package com.gtnhspeedrun.mixins.worldgen;

import java.util.Random;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.worldgen.BopRandHolder;

import biomesoplenty.common.world.features.managers.WorldGenBOPGrassManager;

/** Captures the seeded decoration Random for {@link BOPBiomeDecoratorMixin}. */
@Mixin(value = WorldGenBOPGrassManager.class, remap = false)
public class WorldGenBOPGrassManagerMixin {

    @Inject(method = "func_76484_a", at = @At("HEAD"))
    private void tcfix$captureRand(World world, Random random, int x, int y, int z,
        CallbackInfoReturnable<Boolean> cir) {
        BopRandHolder.RAND.set(random);
    }
}
