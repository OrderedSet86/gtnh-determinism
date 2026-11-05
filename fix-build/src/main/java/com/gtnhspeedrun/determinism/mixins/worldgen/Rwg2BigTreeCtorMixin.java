package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import rwg2.deco.trees.DecoBigTree;

/** RWG2 2.0.6's no-arg big-tree constructor still uses Math.random; resolve its size from the seeded tree RNG. */
@Mixin(value = DecoBigTree.class, remap = false)
public class Rwg2BigTreeCtorMixin {

    @Shadow
    int size;
    @Shadow
    Random rand;

    @Redirect(method = "<init>()V", at = @At(value = "INVOKE", target = "Ljava/lang/Math;random()D"), require = 1)
    private static double gtnhdet$sentinelRoll() {
        return -7.5 / 7.0; // 7 + floor(this * 7) == -1
    }

    @Redirect(
        method = { "generate", "func_76484_a" },
        at = @At(value = "FIELD", target = "Lrwg2/deco/trees/DecoBigTree;size:I", opcode = Opcodes.GETFIELD),
        require = 1,
        allow = 1)
    private int gtnhdet$resolveSize(DecoBigTree self) {
        return this.size >= 0 ? this.size : 7 + this.rand.nextInt(7);
    }
}
