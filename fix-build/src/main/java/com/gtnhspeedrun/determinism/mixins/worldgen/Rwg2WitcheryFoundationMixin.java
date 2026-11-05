package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.TerrainOracle;

/**
 * RWG2 2.0.6 adds a foundation veto before Witchery builds. Keep its height-variation/depth thresholds and
 * landmark checks, but ask virgin terrain rather than whichever trees and structures have populated first.
 */
@Mixin(targets = "rwg2.support.WitcheryWorldgenCompat", remap = false)
public abstract class Rwg2WitcheryFoundationMixin {

    @Redirect(
        method = "hasUnsuitableFoundation",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;func_72825_h(II)I"),
        require = 1,
        allow = 1)
    private static int gtnhdet$virginTop(World world, int x, int z) {
        return TerrainOracle.topSolidOrLiquidBlock(world, x, z);
    }

    @Redirect(
        method = "hasUnsuitableFoundation",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;func_147439_a(III)Lnet/minecraft/block/Block;"),
        require = 1,
        allow = 1)
    private static Block gtnhdet$virginFoundation(World world, int x, int y, int z) {
        return TerrainOracle.block(world, x, y, z);
    }
}
