package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraft.world.gen.structure.StructureComponent;
import net.minecraft.world.gen.structure.StructureMineshaftPieces;

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
 * Pins mineshaft corridor chest EXISTENCE to the piece, the way the 2026-09-10 fix pinned vanilla dungeon
 * chest placement.
 *
 * <p>
 * {@code Corridor.addComponentParts} decides each of its two chest slots per section with
 * {@code rand.nextInt(100) == 0} off the shared populate {@code Random} — a stream whose position at that point
 * depends on every {@code PopulateChunkEvent.Pre} handler's draws (BuildCraft oil draws there, measured
 * route-unstable) and on everything earlier in the window. Chest CONTENTS and roll count are already
 * route-pure: F10 ({@code ChestFillContext}) refills the {@code EntityMinecartChest} from the box-relative
 * piece fork. Existence was the last stream-derived term.
 *
 * <p>
 * Each draw is answered from an independent function of (worldSeed, piece box anchor, draw index) instead of
 * from the stream. The stock draw is STILL consumed, so populate's stream position is unchanged and nothing
 * downstream of the draw moves — the same stream-preservation contract every other pin in this jar keeps.
 *
 * <p>
 * The draw counter is KEYED per piece anchor in a ThreadLocal map and reset at the HEAD of every
 * {@code addComponentParts} invocation — the keyed form, not a single slot; the single-slot version of this
 * counter has shipped wrong before ({@code RwgDungeonAttemptMixin}, {@code ChestFillContext.gtnhdet$fillIndex},
 * {@code WorldGenDungeonsScanMixin.gtnhdet$chestDraws} all record the same failure). Within one invocation the
 * section loop runs over the FULL piece regardless of the window box (only writes are clipped), so draw index n
 * maps 1:1 onto (section, side) and every window that visits the piece computes the same verdict for the same
 * chest slot; the {@code isVecInside} guard inside {@code generateStructureChestContents} then lets exactly one
 * window place it.
 *
 * <p>
 * NOT pinned here: {@code isLiquidInStructureBoundingBox} (window-scoped live-terrain read at the method head,
 * which can remove the component in one walk order before another window builds it) and the chest cell's air
 * check. Both are measured by the mineshaft multiseed validation before any lever is added for them —
 * the vanilla-dungeon fix earned each of its levers from a measured residual, and this one starts the same way.
 *
 * <p>
 * {@code -Dgtnhdet.mineshaftpin=false} restores stock draws, so an A/B arm can be true stock.
 */
@Mixin(StructureMineshaftPieces.Corridor.class)
public abstract class MineshaftCorridorChestMixin {

    // The derivation, the draw counter and the on/off switch live in MineshaftChestPin, because Et Futurum's
    // MesaCorridor rolls the same decision from a class with no shared supertype and needs the identical
    // answer shape. Salt and constants are unchanged from the measured 2026-09-17 run.

    @Inject(method = "addComponentParts", at = @At("HEAD"), require = 1)
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
            MineshaftChestPin.SALT_VANILLA);
    }

    /**
     * The two chest-existence draws. In stock bytecode the direct {@code Random.nextInt} sites in
     * {@code addComponentParts} are, in order: the {@code nextInt(4)} plank-gap roll (ordinal 0), chest slot A
     * {@code nextInt(100)} (ordinal 1), chest slot B {@code nextInt(100)} (ordinal 2), and the spawner offset
     * {@code nextInt(3)} (ordinal 3). The web and torch rolls happen inside {@code randomlyFillWithBlocks} and
     * {@code func_151552_a}, and the loot pool and count draws happen inside {@code ChestGenHooks}.
     *
     * <p>
     * Both chest draws are {@code nextInt(100) == 0}, so Mobs-Info 0.6.0+ rewrites them, and the plank-gap roll
     * before them, into {@code RandomComparison.nextInt} calls. That left one direct site, and ordinal 1
     * crashed at init on GTNH 2.9.0-RC-1. Each draw is therefore selected by stock ordinal through
     * {@link com.gtnhspeedrun.determinism.RandomDrawPoint}, in both call shapes, with a group that requires
     * exactly one of the two to bind. The comparison shape takes its stock draw through Mobs-Info's
     * {@code original}, so the stream advances exactly as it would without this mixin.
     */
    @Group(name = "gtnhdet$mineshaftChestA", min = 1, max = 1)
    @Redirect(
        method = "addComponentParts",
        at = @At(value = "com.gtnhspeedrun.determinism.RandomDrawPoint", args = "shape=virtual", ordinal = 1))
    private int gtnhdet$chestDrawA(Random rand, int bound, World world, Random rand2, StructureBoundingBox box) {
        return gtnhdet$pinnedDraw(rand.nextInt(bound), bound, world);
    }

    @Group(name = "gtnhdet$mineshaftChestA", min = 1, max = 1)
    @WrapOperation(
        method = "addComponentParts",
        at = @At(value = "com.gtnhspeedrun.determinism.RandomDrawPoint", args = "shape=comparison", ordinal = 1))
    private int gtnhdet$chestDrawAComparison(Random rand, int bound, int value, int op, Operation<Integer> original,
        World world, Random rand2, StructureBoundingBox box) {
        return gtnhdet$pinnedDraw(original.call(rand, bound, value, op), bound, world);
    }

    @Group(name = "gtnhdet$mineshaftChestB", min = 1, max = 1)
    @Redirect(
        method = "addComponentParts",
        at = @At(value = "com.gtnhspeedrun.determinism.RandomDrawPoint", args = "shape=virtual", ordinal = 2))
    private int gtnhdet$chestDrawB(Random rand, int bound, World world, Random rand2, StructureBoundingBox box) {
        return gtnhdet$pinnedDraw(rand.nextInt(bound), bound, world);
    }

    @Group(name = "gtnhdet$mineshaftChestB", min = 1, max = 1)
    @WrapOperation(
        method = "addComponentParts",
        at = @At(value = "com.gtnhspeedrun.determinism.RandomDrawPoint", args = "shape=comparison", ordinal = 2))
    private int gtnhdet$chestDrawBComparison(Random rand, int bound, int value, int op, Operation<Integer> original,
        World world, Random rand2, StructureBoundingBox box) {
        return gtnhdet$pinnedDraw(original.call(rand, bound, value, op), bound, world);
    }
}
