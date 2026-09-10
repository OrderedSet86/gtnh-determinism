package com.gtnhspeedrun.splash.mixins;

import java.util.Random;

import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.splash.Plots;

import tconstruct.world.gen.SlimeIslandGen;

/**
 * Forces a slime island to be attempted at the plot chunks.
 *
 * <p>
 * Stock: {@code if (random.nextInt(PHConstruct.islandRarity) == 0) generateIsland(...)}. GTNH ships
 * {@code Slime Island Rarity = 8000}, i.e. one chunk in eight thousand — roughly two islands in the entire
 * radius-28 walk, landing wherever. Unusable for a fixed crop window.
 *
 * <p>
 * The forcing goes through the stock call site rather than calling {@code generateIsland} directly, and that is the
 * whole point. In the stock arm {@code generateIsland} must still read the clock-seeded instance field
 * {@code random} — that shadowing bug is precisely what this column photographs: island size
 * ({@code nextInt(13) + 20}), altitude ({@code 50 + getHeightValue + nextInt(50)}), erosion silhouette and the three
 * slime trees all re-roll every launch. In the fixed arm it must still receive
 * {@code SlimeIslandGenMixin}'s field redirect. Calling {@code generateIsland} ourselves would substitute this jar's
 * determinism for the bug under test.
 *
 * <p>
 * The stock draw is taken and discarded rather than skipped, so {@code fmlRandom}'s position after this generator is
 * identical to an unforced run, on plot chunks and off them alike.
 *
 * <p>
 * No collision with {@code SlimeIslandGenMixin}: that one redirects the {@code random} FIELD read inside
 * {@code generateIsland}; this redirects a {@code Random.nextInt} INVOKE inside {@code generate}. There is exactly
 * one such invoke in {@code generate}, so no ordinal is needed.
 *
 * <p>
 * Note {@code generate}'s parameter is itself named {@code random} and shadows the instance field, so the rarity
 * roll comes off FML's seeded per-generator Random in both the stock and the fixed jar. Only
 * {@code generateIsland} sees the field.
 */
@Mixin(value = SlimeIslandGen.class, remap = false)
public class SlimeIslandForceMixin {

    @Redirect(method = "generate", at = @At(value = "INVOKE", target = "Ljava/util/Random;nextInt(I)I"))
    private int splash$force(Random receiver, int bound, Random random, int chunkX, int chunkZ, World world,
        IChunkProvider chunkGenerator, IChunkProvider chunkProvider) {
        final int stock = receiver.nextInt(bound);
        return Plots.forceSlime(chunkX, chunkZ) ? 0 : stock;
    }
}
