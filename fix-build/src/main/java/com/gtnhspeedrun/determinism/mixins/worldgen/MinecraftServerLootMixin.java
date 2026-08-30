package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.determinism.worldgen.EarlyLootTables;

/**
 * F9 entry point: the last instruction before any world exists.
 *
 * <p>
 * {@code loadAllWorlds} constructs the {@code WorldServer}, which searches for a spawn point and therefore
 * generates chunks, and then preloads a 25x25-chunk region around it. Both happen before
 * {@code FMLServerStartingEvent}, so both roll pre-rewrite loot tables. Hooking the head of this method — rather
 * than an {@code FMLServerAboutToStartEvent} handler, which carries no priority and could be overtaken by another
 * mod's handler — puts the rewrite ahead of every chunk this server will ever generate.
 *
 * <p>
 * See {@link EarlyLootTables} for what is applied and why suppressing TooMuchLoot's own later run is required.
 */
@Mixin(MinecraftServer.class)
public class MinecraftServerLootMixin {

    @Inject(method = "loadAllWorlds", at = @At("HEAD"), require = 1)
    private void gtnhdet$lootTablesBeforeAnyChunk(String saveName, String worldName, long seed,
        net.minecraft.world.WorldType type, String generatorOptions, CallbackInfo ci) {
        EarlyLootTables.apply();
    }
}
