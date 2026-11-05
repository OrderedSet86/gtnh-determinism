package com.gtnhspeedrun.worldgenprobe.mixins;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.worldgenprobe.Prefilter;

/** Harness-only: the gameplay oracle's non-WorldServer fallback must not read our room overlay. */
@Pseudo
@Mixin(targets = "com.gtnhspeedrun.determinism.worldgen.TerrainOracle", remap = false)
public abstract class TerrainOracleMixin {
    @Inject(method = "block", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private static void gtnhprobe$virginDungeonRead(World world, int x, int y, int z,
        CallbackInfoReturnable<Block> cir) {
        if (world instanceof Prefilter.SeedProbeWorld) {
            final Prefilter.SeedProbeWorld probe = (Prefilter.SeedProbeWorld) world;
            if (probe.vanillaScanActive) cir.setReturnValue(probe.vanillaVirginBlock(x, y, z));
        }
    }
}
