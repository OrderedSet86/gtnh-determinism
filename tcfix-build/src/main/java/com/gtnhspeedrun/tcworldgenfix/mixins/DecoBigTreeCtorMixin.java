package com.gtnhspeedrun.tcworldgenfix.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import rwg.deco.trees.DecoBigTree;

/**
 * DecoBigTree's no-arg constructor sizes the tree with Math.random() — clock-random geometry every launch. Pin the
 * roll so the size is constant (the upstream source fix derives it from the seeded per-tree RNG instead; a constant
 * keeps the testing jar simple and deterministic). (Audit finding F6.)
 */
@Mixin(value = DecoBigTree.class, remap = false)
public class DecoBigTreeCtorMixin {

    @Redirect(method = "<init>()V", at = @At(value = "INVOKE", target = "Ljava/lang/Math;random()D"), require = 0)
    private static double tcfix$fixedSizeRoll() {
        return 0.5;
    }
}
