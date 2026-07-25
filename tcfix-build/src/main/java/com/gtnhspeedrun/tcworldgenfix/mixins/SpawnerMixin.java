package com.gtnhspeedrun.tcworldgenfix.mixins;

import net.minecraft.tileentity.MobSpawnerBaseLogic;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import greymerk.roguelike.worldgen.Spawner;

/**
 * Spawner config calls logic.updateSpawner(), which asks getSpawnerWorld() for the closest player — NPE on the
 * DETACHED spawner TEs handed out for pending-slice (buffered) writes, aborting the rest of the dungeon (observed:
 * 109 chests lost past the first buffered spawner). Skip the update when the logic has no world: it only resets
 * the spawn delay, which initializes on world insertion anyway. SRG names — shipped Roguelike is reobf
 * (func_98278_g = updateSpawner, func_98271_a = getSpawnerWorld).
 */
@Mixin(value = Spawner.class, remap = false)
public class SpawnerMixin {

    @Redirect(
        method = { "setMeta", "setRoguelike" },
        at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/MobSpawnerBaseLogic;func_98278_g()V"),
        require = 0)
    private static void tcfix$safeUpdateSpawner(MobSpawnerBaseLogic logic) {
        if (logic.getSpawnerWorld() != null) logic.updateSpawner();
    }
}
