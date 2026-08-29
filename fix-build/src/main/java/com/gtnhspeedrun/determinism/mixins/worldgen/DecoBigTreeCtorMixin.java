package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import rwg.deco.trees.DecoBigTree;

/**
 * DecoBigTree's no-arg constructor sizes the tree with Math.random() — clock-random geometry every launch (audit
 * finding F6). Restore the stock size VARIETY deterministically: the ctor redirect turns the clock roll into the
 * fork build's -1 sentinel (stock: size = 7 + floor(roll*7); -7.5/7 lands floor at -8, so size = -1), and the
 * single `size` field read in generate() resolves the sentinel to 7 + rand.nextInt(7) — the same uniform 7..13 as
 * stock, drawn as the first draw from the per-tree seeded stream, byte-matching the forked-RWG source fix.
 * Explicit-size call sites (DecoBigTree(int, int)) pass size >= 0 and keep their value.
 *
 * <p>
 * These are the only injectors left at {@code require = 0}. Every other soft injector was tightened to
 * {@code require = 1} once its target was confirmed byte-identical from the 2.7.4-era version through the daily
 * one, but RWG is the exception: packs up to 2.8.4 ship alpha-1.5.0 and 2.9 onwards ship alpha-1.5.2, and the
 * alpha-1.5.0 artifact is purged from the GTNH maven, so the two cannot be diffed. Crashing every 2.7.4 user over
 * an unverifiable assumption is worse than losing tree-size determinism, so this one stays soft.
 */
@Mixin(value = DecoBigTree.class, remap = false)
public class DecoBigTreeCtorMixin {

    @Shadow
    int size;
    @Shadow
    Random rand;

    @Redirect(method = "<init>()V", at = @At(value = "INVOKE", target = "Ljava/lang/Math;random()D"), require = 0)
    private static double gtnhdet$sentinelRoll() {
        return -7.5 / 7.0; // 7 + floor(this * 7) == -1, the fork build's "roll in generate()" sentinel
    }

    @Redirect(
        method = "generate",
        at = @At(value = "FIELD", target = "Lrwg/deco/trees/DecoBigTree;size:I", opcode = Opcodes.GETFIELD),
        require = 0)
    private int gtnhdet$resolveSize(DecoBigTree self) {
        return this.size >= 0 ? this.size : 7 + this.rand.nextInt(7);
    }
}
