package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import rwg.world.RwgWorldSavedData;

/**
 * Drops RWG's cached saved data when the overworld changes. {@code RwgWorldSavedData} caches the overworld's
 * {@code rwg_data} in a static field on first use, and {@code rwg_data} stores the noise implementation. Without the
 * drop, a world opened later in the same game session generates with the first world's noise setting.
 */
@Mixin(value = RwgWorldSavedData.class, remap = false)
public class RwgWorldSavedDataMixin {

    // the mod's cache of rwg_data, set to null below when the overworld changes
    @Shadow
    private static RwgWorldSavedData INSTANCE; // noqa: NAR130

    // the overworld that INSTANCE was loaded from
    @Unique
    private static WorldServer gtnhdet$owner; // noqa: NAR130

    @Inject(method = "loadInstance", at = @At("HEAD"), require = 1)
    private static void gtnhdet$dropOtherWorldsData(CallbackInfo ci) { // noqa: NAR130
        final WorldServer overworld = DimensionManager.getWorld(0);
        if (gtnhdet$owner != overworld) {
            INSTANCE = null;
            gtnhdet$owner = overworld;
        }
    }
}
