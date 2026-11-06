package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.feature.WorldGenDungeons;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.determinism.worldgen.RwgDungeonAttempts;
import com.gtnhspeedrun.determinism.worldgen.WorldPosition;

/**
 * {@link RwgDungeonAttemptMixin} for RWG2. RWG2 2.0.6 runs the same eight-attempt dungeon loop on the shared populate
 * {@code Random}, after the generator's water and lava lakes.
 */
@Mixin(targets = "rwg2.world.ChunkGeneratorRealistic", remap = false)
public class Rwg2DungeonAttemptMixin {

    /** Start the chunk's attempt count at 0. */
    @Inject(method = { "func_73153_a", "populate" }, at = @At("HEAD"), require = 1)
    private void gtnhdet$resetAttemptIndex(IChunkProvider provider, int chunkX, int chunkZ, CallbackInfo ci) { // noqa:
                                                                                                               // NAR130,
                                                                                                               // NAR131
        RwgDungeonAttempts.resetAttemptIndex(chunkX, chunkZ);
    }

    /** Drop the chunk's attempt count when populate returns. */
    @Inject(method = { "func_73153_a", "populate" }, at = @At("RETURN"), require = 1)
    private void gtnhdet$finishAttempts(IChunkProvider provider, int chunkX, int chunkZ, CallbackInfo ci) { // noqa:
                                                                                                            // NAR130,
                                                                                                            // NAR131
        RwgDungeonAttempts.finishAttempts(chunkX, chunkZ);
    }

    /** Move the attempt to coordinates forked from world seed and chunk. */
    @Redirect(
        method = { "func_73153_a", "populate" },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/gen/feature/WorldGenDungeons;func_76484_a(Lnet/minecraft/world/World;Ljava/util/Random;III)Z"),
        require = 1)
    private boolean gtnhdet$forkDungeonAttempt(WorldGenDungeons dungeons, World world, Random rand, int x, int y, // noqa:
                                                                                                                  // NAR130,
                                                                                                                  // NAR131
        int z) {
        return RwgDungeonAttempts.generateAttempt(dungeons, rand, new WorldPosition(world, x, y, z));
    }
}
