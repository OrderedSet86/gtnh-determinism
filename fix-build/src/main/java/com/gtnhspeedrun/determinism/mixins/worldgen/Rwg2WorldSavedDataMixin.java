package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import rwg2.world.RwgWorldSavedData;

/** RWG2's saved noise choice belongs to an overworld, not to the lifetime of the client or warm-probe JVM. */
@Mixin(value = RwgWorldSavedData.class, remap = false)
public abstract class Rwg2WorldSavedDataMixin {

    @Shadow
    private static RwgWorldSavedData INSTANCE;

    @Unique
    private static WorldServer gtnhdet$owner;

    @Inject(method = "loadInstance", at = @At("HEAD"), require = 1)
    private static void gtnhdet$selectWorld(CallbackInfo ci) {
        final WorldServer world = DimensionManager.getWorld(0);
        if (gtnhdet$owner != world) {
            INSTANCE = null;
            gtnhdet$owner = world;
        }
    }
}
