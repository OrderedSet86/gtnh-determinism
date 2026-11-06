package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.TerrainOracle;

/**
 * RWG2 vetoes a Witchery structure when the ground under the structure is uneven or unsupported.
 * {@code hasUnsuitableFoundation} samples the top block, and the blocks below the top block, across the structure's
 * footprint. The footprint crosses chunk borders, so on the live world the verdict depends on which neighbouring chunks
 * were already populated. Both reads go to virgin terrain. RWG2's thresholds and landmark checks are unchanged.
 * {@code -Dgtnhdet.rwg2witchery=false} restores the live reads.
 */
@Mixin(targets = "rwg2.support.WitcheryWorldgenCompat", remap = false)
public class Rwg2WitcheryFoundationMixin {

    private static final boolean gtnhdet$ON = !"false".equals(System.getProperty("gtnhdet.rwg2witchery")); // noqa:
                                                                                                           // NAR130

    @Redirect(
        method = "hasUnsuitableFoundation",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;func_72825_h(II)I"),
        require = 1)
    private static int gtnhdet$virginTop(World world, int x, int z) { // noqa: NAR130
        if (!gtnhdet$ON) return world.getTopSolidOrLiquidBlock(x, z);
        return TerrainOracle.getTopSolidOrLiquidBlock(world, x, z);
    }

    /** Read the foundation block from virgin terrain. */
    @Redirect(
        method = "hasUnsuitableFoundation",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;func_147439_a(III)Lnet/minecraft/block/Block;"),
        require = 1)
    private static Block gtnhdet$virginFoundation(World world, int x, int y, int z) { // noqa: NAR130, NAR131
        if (!gtnhdet$ON) return world.getBlock(x, y, z);
        return TerrainOracle.block(world, x, y, z);
    }
}
