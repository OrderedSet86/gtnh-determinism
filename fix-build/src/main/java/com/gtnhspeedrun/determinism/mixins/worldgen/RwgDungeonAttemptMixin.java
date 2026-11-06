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
 * Pins RWG's vanilla dungeon attempts to the chunk, and traces them with {@code -Dgtnhdet.dungeontrace=true}.
 *
 * <p>
 * RWG runs eight dungeon attempts per chunk from the shared populate {@code Random}
 * ({@code ChunkGeneratorRealistic.populate}, the loop after the two {@code WorldGenLakes} calls):
 *
 * <pre>
 * for (int k1 = 0; k1 &lt; 8 &amp;&amp; gen; k1++) {
 *     int j5 = x + rand.nextInt(16) + 8;
 *     int k8 = rand.nextInt(128);
 *     int j11 = y + rand.nextInt(16) + 8;
 *     gen_dungeons.generate(worldObj, rand, j5, k8, j11);
 * }
 * </pre>
 *
 * <p>
 * There are three mechanisms behind a dungeon existing under one walk order and not another, and they
 * call for different fixes:
 *
 * <ul>
 * <li><b>Draw shift.</b> {@code WorldGenLakes.generate} can {@code return false} on a live world read
 * <em>before</em> consuming any draws, so an upstream block flip changes how many draws the shared
 * stream has taken and every attempt coordinate moves. Fix: fork the stream.</li>
 * <li><b>Scan verdict.</b> {@code WorldGenDungeons.generate} scans a box that crosses chunk borders
 * and refuses unless the air-opening count is in [1,5]. Same coordinates, different answer. Fix:
 * virgin terrain.</li>
 * <li><b>Room extents.</b> Two {@code nextInt(2)} draws at the top of {@code generate}, off the shared
 * stream, set the box the scan uses. Fixed in {@link WorldGenDungeonsScanMixin}.</li>
 * </ul>
 *
 * <p>
 * Logging the attempt coordinates and the verdict separates them: identical coordinates with
 * differing verdicts is the scan; differing coordinates is the draw shift. The repo has assumed the
 * draw shift dominates since 2026-08-27 without measuring which.
 *
 * <p>
 * The fix is on by default. {@code -Dgtnhdet.dungeonfork=false} turns the fix off, for A/B measurement. The vanilla
 * method has the SRG name here: mod classes call vanilla methods by SRG name at production runtime.
 * {@code Rwg2DungeonAttemptMixin} calls the same {@link RwgDungeonAttempts}.
 */
@Mixin(targets = "rwg.world.ChunkGeneratorRealistic", remap = false)
public class RwgDungeonAttemptMixin {

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
