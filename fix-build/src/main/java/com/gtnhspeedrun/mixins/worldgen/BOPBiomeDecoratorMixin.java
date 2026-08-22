package com.gtnhspeedrun.mixins.worldgen;

import java.util.Random;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.worldgen.BopRandHolder;

import biomesoplenty.api.biome.BOPBiomeDecorator;

/**
 * BOP picks each weighted grass/flower generator with {@code Math.random()} — clock randomness in the middle of
 * world generation. Because the chosen generator then consumes a different number of draws from the SHARED decoration
 * Random, this one call desyncs the rest of the chunk's decoration stream too (measured as relocating dirt/gravel
 * patches). Redirect it to the seeded decoration Random captured by the feature-manager mixins.
 * (GTNH speedrun determinism audit — BiomesO'Plenty finding.)
 */
@Mixin(value = BOPBiomeDecorator.class, remap = false)
public class BOPBiomeDecoratorMixin {

    @Redirect(
        method = "getRandomWeightedWorldGenerator",
        at = @At(value = "INVOKE", target = "Ljava/lang/Math;random()D"))
    private static double gtnhdet$seededRoll() {
        final Random r = BopRandHolder.RAND.get();
        return r != null ? r.nextDouble() : Math.random();
    }
}
