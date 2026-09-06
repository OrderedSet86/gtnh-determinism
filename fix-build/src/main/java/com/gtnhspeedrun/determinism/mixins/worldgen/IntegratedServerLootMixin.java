package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.server.integrated.IntegratedServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.determinism.worldgen.EarlyLootTables;

/**
 * F9 entry point for singleplayer. Not a duplicate of {@link MinecraftServerLootMixin} — do not delete it as one.
 *
 * <p>
 * {@code IntegratedServer} overrides {@code loadAllWorlds} and never calls {@code super}: it reimplements the whole
 * body, ending in the inherited {@code initialWorldChunkLoad()}. Mixin does not propagate an {@code @Inject} into a
 * subclass override, so the injector on {@code MinecraftServer} is dead code on this path. That failure is silent
 * rather than loud — {@code require = 1} is satisfied by finding the injection point in {@code MinecraftServer} —
 * and the {@code consumeApplied()} handshake then lets TooMuchLoot's own handler run, so a singleplayer world used
 * to get exactly the spawn-preload split F9 exists to close.
 *
 * <p>
 * The ordering F9 depends on is identical on this side: {@code startServer} runs {@code handleServerAboutToStart},
 * then {@code loadAllWorlds} (which constructs the {@code WorldServer}, so searches for a spawn point, and then
 * preloads a 25x25-chunk region), and only then {@code FMLServerStartingEvent}.
 *
 * <p>
 * {@code IntegratedServer} is client-only, so this is registered in the {@code client} block of the mixin config.
 * No double-application guard is needed: a server instance is either an {@code IntegratedServer} or a
 * {@code DedicatedServer}, so exactly one of the two injectors can fire per start.
 */
@Mixin(IntegratedServer.class)
public class IntegratedServerLootMixin {

    @Inject(method = "loadAllWorlds", at = @At("HEAD"), require = 1)
    private void gtnhdet$lootTablesBeforeAnyChunk(String saveName, String worldName, long seed,
        net.minecraft.world.WorldType type, String generatorOptions, CallbackInfo ci) {
        EarlyLootTables.apply();
    }
}
