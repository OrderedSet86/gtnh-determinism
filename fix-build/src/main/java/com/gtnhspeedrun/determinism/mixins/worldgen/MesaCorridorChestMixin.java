package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraft.world.gen.structure.StructureComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.determinism.worldgen.MineshaftChestPin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/**
 * {@link MineshaftCorridorChestMixin} for Et Futurum's MESA mineshafts. Read that class first — the defect,
 * the derivation and the stream-preservation contract are identical, and both go through
 * {@link MineshaftChestPin}.
 *
 * <p>
 * The pack's registered generator is Et Futurum's {@code MapGenMesaMineshaft}, and in a mesa biome it builds
 * {@code MesaCorridor} pieces instead of vanilla ones. {@code MesaCorridor extends
 * StructureMesaMineshaftPieces$Piece} — Et Futurum's own base, with no relation to
 * {@code StructureMineshaftPieces.Corridor} — and carries its OWN {@code addComponentParts}, its own two
 * {@code nextInt(100) == 0} chest-existence draws and its own {@code generateStructureChestContents} override
 * that spawns the {@code EntityMinecartChest}. So there is no inheritance path by which the vanilla mixin could
 * reach it: until this class existed, mesa mineshaft chests were entirely unpinned, which
 * results/2026-09-17-mineshaft-multiseed lists as an open gap.
 *
 * <p>
 * <b>The ordinals are NOT the same as the vanilla mixin's, and that is the whole hazard here.</b> Vanilla
 * {@code Corridor.addComponentParts} rolls its plank-gap {@code nextInt(4)} inline, so its chest draws sit at
 * ordinals 1 and 2. {@code MesaCorridor} moved that roll into the private {@code buildWoodenParts} helper, so
 * the only direct {@code Random.nextInt} sites left in the method are chest A, chest B and the spawner offset —
 * putting the chests at ordinals <b>0 and 1</b>. Verified by disassembling the jar the pack actually ships
 * (etfuturum-2.6.58-GTNH): {@code func_74875_a} contains exactly three {@code Random.nextInt(I)I} call sites, at
 * bytecode offsets 268, 303 and 362, and the two {@code func_74879_a} chest-contents calls follow the first two
 * at offsets 296 and 331. Copying ordinals 1 and 2 across from the vanilla mixin would have pinned chest B and
 * the spawner while leaving chest A on the shared stream — a silent half-fix of exactly the kind the vanilla
 * mixin's javadoc lists three prior instances of.
 *
 * <p>
 * {@code remap = false} with the SRG name spelled out, matching {@code EtFuturumCaveVineTeMixin}: the target is
 * a mod class, so the class-level remap is off, but {@code addComponentParts} is a vanilla-inherited override
 * and therefore ships as {@code func_74875_a}. {@code Random.nextInt} is a JDK method and is never remapped.
 *
 * <p>
 * Each chest draw's two injectors share a {@code @Group} with {@code min = max = 1}: if a future Et Futurum
 * reshapes the method, fail loudly at apply time rather than pin the wrong draw. The ordinals are STOCK
 * ordinals, counted by {@link com.gtnhspeedrun.determinism.RandomDrawPoint} across both of Mobs-Info's call
 * shapes.
 *
 * <p>
 * <b>Opt-in via {@code -Dgtnhdet.mesapin=true} until a mesa seed has exercised it.</b> The ordinals above are
 * verified against shipped bytecode but have never been validated at runtime, because no measured run has ever
 * loaded {@code MesaCorridor}. Note that {@code gtnhdet.mineshaftpin=false} is NOT a safety net here: it changes
 * what the handler returns, while the injector groups still have to bind. A wrong ordinal is therefore a crash at
 * apply time, not a degraded result, and that is not a failure mode to ship on by default.
 *
 * <p>
 * NOT pinned, exactly as in the vanilla case: {@code isLiquidInStructureBoundingBox} at the method head and the
 * chest cell's air check. On the vanilla piece those are the measured residual — a corridor component that
 * exists in one walk order and not another — and nothing here changes that; a mesa corridor can still vanish
 * the same way. This mixin closes the DRAW, not the terrain verdict.
 */
@Mixin(targets = "ganymedes01.etfuturum.world.structure.StructureMesaMineshaftPieces$MesaCorridor", remap = false)
public abstract class MesaCorridorChestMixin {

    @Inject(method = "func_74875_a", at = @At("HEAD"), require = 1)
    private void gtnhdet$resetDraws(World world, Random rand, StructureBoundingBox box,
        CallbackInfoReturnable<Boolean> cir) {
        MineshaftChestPin.resetDraws(((StructureComponent) (Object) this).getBoundingBox());
    }

    private int gtnhdet$pinnedDraw(int stock, int bound, World world) {
        return MineshaftChestPin.pinnedDraw(
            stock,
            bound,
            world,
            ((StructureComponent) (Object) this).getBoundingBox(),
            MineshaftChestPin.SALT_MESA);
    }

    /**
     * Chest slots A and B, stock ordinals 0 and 1. Both are {@code nextInt(100) == 0}, so Mobs-Info 0.6.0+
     * rewrites both into {@code RandomComparison.nextInt} calls, and a plain ordinal-0/1 selector then lands on
     * the spawner offset or on nothing. Each draw is taken in both call shapes, exactly as in
     * {@link MineshaftCorridorChestMixin}; read that class for why.
     */
    @Group(name = "gtnhdet$mesaChestA", min = 1, max = 1)
    @Redirect(
        method = "func_74875_a",
        at = @At(value = "com.gtnhspeedrun.determinism.RandomDrawPoint", args = "shape=virtual", ordinal = 0))
    private int gtnhdet$chestDrawA(Random rand, int bound, World world, Random rand2, StructureBoundingBox box) {
        return gtnhdet$pinnedDraw(rand.nextInt(bound), bound, world);
    }

    @Group(name = "gtnhdet$mesaChestA", min = 1, max = 1)
    @WrapOperation(
        method = "func_74875_a",
        at = @At(value = "com.gtnhspeedrun.determinism.RandomDrawPoint", args = "shape=comparison", ordinal = 0))
    private int gtnhdet$chestDrawAComparison(Random rand, int bound, int value, int op, Operation<Integer> original,
        World world, Random rand2, StructureBoundingBox box) {
        return gtnhdet$pinnedDraw(original.call(rand, bound, value, op), bound, world);
    }

    @Group(name = "gtnhdet$mesaChestB", min = 1, max = 1)
    @Redirect(
        method = "func_74875_a",
        at = @At(value = "com.gtnhspeedrun.determinism.RandomDrawPoint", args = "shape=virtual", ordinal = 1))
    private int gtnhdet$chestDrawB(Random rand, int bound, World world, Random rand2, StructureBoundingBox box) {
        return gtnhdet$pinnedDraw(rand.nextInt(bound), bound, world);
    }

    @Group(name = "gtnhdet$mesaChestB", min = 1, max = 1)
    @WrapOperation(
        method = "func_74875_a",
        at = @At(value = "com.gtnhspeedrun.determinism.RandomDrawPoint", args = "shape=comparison", ordinal = 1))
    private int gtnhdet$chestDrawBComparison(Random rand, int bound, int value, int op, Operation<Integer> original,
        World world, Random rand2, StructureBoundingBox box) {
        return gtnhdet$pinnedDraw(original.call(rand, bound, value, op), bound, world);
    }
}
