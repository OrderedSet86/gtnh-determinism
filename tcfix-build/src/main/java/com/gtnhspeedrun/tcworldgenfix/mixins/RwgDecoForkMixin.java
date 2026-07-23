package com.gtnhspeedrun.tcworldgenfix.mixins;

import java.util.Random;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * RWG decoration generators consume draws from the SHARED chunk decoration Random behind terrain-dependent gates
 * (e.g. DecoLargePine returns before any draw unless the ground block is grass/dirt), so world-state differences at
 * chunk borders shift every later decoration's rolls. Fork a private Random with exactly ONE draw from the shared
 * stream at each generator's entry, making every decoration consume a fixed draw shape regardless of terrain gates.
 * Mirrors the source fix on the Realistic-World-Gen determinism-fixes branch. (Audit finding F6.)
 */
@Mixin(
    value = { rwg.deco.DecoBlob.class, rwg.deco.DecoCacti.class, rwg.deco.DecoClay.class, rwg.deco.DecoFlowers.class,
        rwg.deco.DecoGrass.class, rwg.deco.DecoIceSpike.class, rwg.deco.DecoJungleCane.class, rwg.deco.DecoLog.class,
        rwg.deco.DecoWaterGrass.class, rwg.deco.DecoWildWheat.class, rwg.deco.trees.DecoBigTree.class,
        rwg.deco.trees.DecoBirch.class, rwg.deco.trees.DecoDeadDesertTrees.class, rwg.deco.trees.DecoEuroPine.class,
        rwg.deco.trees.DecoJungleFat.class, rwg.deco.trees.DecoJungleSmall.class, rwg.deco.trees.DecoJungleTall.class,
        rwg.deco.trees.DecoLargePine.class, rwg.deco.trees.DecoMangrove.class, rwg.deco.trees.DecoPalm.class,
        rwg.deco.trees.DecoPineTree.class, rwg.deco.trees.DecoRedWood.class, rwg.deco.trees.DecoSavannah.class,
        rwg.deco.trees.DecoShrub.class, rwg.deco.trees.DecoSmallCocoa.class, rwg.deco.trees.DecoSmallJungle.class,
        rwg.deco.trees.DecoSmallPine.class, rwg.deco.trees.DecoSmallSpruce.class, rwg.deco.trees.DecoWillow.class },
    remap = false)
public class RwgDecoForkMixin {

    @ModifyVariable(method = "generate", at = @At("HEAD"), argsOnly = true, index = 2)
    private Random tcfix$forkRand(Random shared) {
        return new Random(shared.nextLong());
    }
}
