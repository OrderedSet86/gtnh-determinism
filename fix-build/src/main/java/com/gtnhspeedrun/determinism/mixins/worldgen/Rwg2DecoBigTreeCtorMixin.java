package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import rwg2.deco.trees.DecoBigTree;

/**
 * {@link DecoBigTreeCtorMixin} for RWG2. The RWG2 2.0.6 {@code DecoBigTree} source is the RWG alpha-1.5.3 source with
 * only the package renamed.
 */
@Mixin(value = DecoBigTree.class, remap = false)
public class Rwg2DecoBigTreeCtorMixin {

    @Shadow
    private int size;
    @Shadow
    private Random rand;

    @Redirect(method = "<init>()V", at = @At(value = "INVOKE", target = "Ljava/lang/Math;random()D"), require = 1)
    private static double gtnhdet$sentinelRoll() { // noqa: NAR130
        return -7.5 / 7.0; // 7 + floor(this * 7) == -1, the sentinel that gtnhdet$resolveSize replaces
    }

    // func_76484_a is the only name in the shipped jars. With remap = false the refmap has no entry, so list both names
    @Redirect(
        method = { "func_76484_a", "generate" },
        at = @At(value = "FIELD", target = "Lrwg2/deco/trees/DecoBigTree;size:I", opcode = Opcodes.GETFIELD),
        require = 1)
    private int gtnhdet$resolveSize(DecoBigTree self) { // noqa: NAR130
        return this.size >= 0 ? this.size : 7 + this.rand.nextInt(7);
    }
}
