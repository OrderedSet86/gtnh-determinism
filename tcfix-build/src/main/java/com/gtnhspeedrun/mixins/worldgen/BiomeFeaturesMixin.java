package com.gtnhspeedrun.mixins.worldgen;

import java.util.HashMap;
import java.util.LinkedHashMap;

import net.minecraft.world.gen.feature.WorldGenerator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import biomesoplenty.api.biome.BiomeFeatures;

/**
 * BOP's weighted grass/flower tables are HashMaps keyed by WorldGenerator instances (identity hashCode), so their
 * iteration order — which decides which generator a given weighted roll lands on — changes every JVM launch. Replace
 * them with LinkedHashMaps so iteration follows the (deterministic) registration order. Paired with
 * {@link BOPBiomeDecoratorMixin}, this makes BOP flora selection a pure function of the world seed.
 * (GTNH speedrun determinism audit — BiomesO'Plenty finding.)
 */
@Mixin(value = BiomeFeatures.class, remap = false)
public class BiomeFeaturesMixin {

    @Shadow
    @Mutable
    public HashMap<WorldGenerator, Double> weightedGrassGen;

    @Shadow
    @Mutable
    public HashMap<WorldGenerator, Integer> weightedFlowerGen;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void tcfix$orderedMaps(CallbackInfo ci) {
        this.weightedGrassGen = new LinkedHashMap<>();
        this.weightedFlowerGen = new LinkedHashMap<>();
    }
}
