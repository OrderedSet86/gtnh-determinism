package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * RWG2 2.0.6 still lets terrain-gated decorations consume its shared population stream. These are RWG2's own
 * generate(World, Random, int, int, int) overrides, audited independently of the legacy RWG targets. A fixed
 * one-long draw at entry isolates each decoration's variable draw count, including early terrain refusals.
 */
@Mixin(
    value = { rwg2.deco.DecoBlob.class, rwg2.deco.DecoCacti.class, rwg2.deco.DecoClay.class,
        rwg2.deco.DecoFlowers.class, rwg2.deco.DecoGrass.class, rwg2.deco.DecoIceSpike.class,
        rwg2.deco.DecoJungleCane.class, rwg2.deco.DecoLog.class, rwg2.deco.DecoWaterGrass.class,
        rwg2.deco.DecoWildWheat.class, rwg2.deco.trees.DecoBigTree.class, rwg2.deco.trees.DecoBirch.class,
        rwg2.deco.trees.DecoDeadDesertTrees.class, rwg2.deco.trees.DecoEuroPine.class,
        rwg2.deco.trees.DecoJungleFat.class, rwg2.deco.trees.DecoJungleSmall.class,
        rwg2.deco.trees.DecoJungleTall.class, rwg2.deco.trees.DecoLargePine.class,
        rwg2.deco.trees.DecoMangrove.class, rwg2.deco.trees.DecoPalm.class, rwg2.deco.trees.DecoPineTree.class,
        rwg2.deco.trees.DecoRedWood.class, rwg2.deco.trees.DecoSavannah.class, rwg2.deco.trees.DecoShrub.class,
        rwg2.deco.trees.DecoSmallCocoa.class, rwg2.deco.trees.DecoSmallJungle.class,
        rwg2.deco.trees.DecoSmallPine.class, rwg2.deco.trees.DecoSmallSpruce.class, rwg2.deco.trees.DecoWillow.class },
    remap = false)
public class Rwg2DecoForkMixin {

    @ModifyVariable(method = { "generate", "func_76484_a" }, at = @At("HEAD"), argsOnly = true, index = 2, require = 1)
    private Random gtnhdet$forkRand(Random shared) {
        return new Random(shared.nextLong());
    }
}
