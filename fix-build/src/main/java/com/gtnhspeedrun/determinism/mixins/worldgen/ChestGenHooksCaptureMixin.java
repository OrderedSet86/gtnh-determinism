package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.util.WeightedRandomChestContent;
import net.minecraftforge.common.ChestGenHooks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.determinism.worldgen.ChestFillContext;

/**
 * F10 capture half: records which table a chest fill is about to draw from, and how many stacks it rolled.
 *
 * <p>
 * Both values are produced in the caller's argument list, immediately before the filler is entered — see
 * {@code StructureComponent.generateStructureChestContents} and {@code WorldGenDungeons.generate} — so a
 * thread-local handoff reaches {@link ChestFillContext} with no ambiguity on the single worldgen thread.
 *
 * <p>
 * These are the <em>instance</em> methods, not the {@code (String, Random)} statics. TiC's
 * {@code ComponentToolWorkshop} calls {@code TinkerWorld.tinkerHousePatterns.getItems(rand)} directly on its own
 * static {@code ChestGenHooks} fields, which are not registered in {@code ChestGenHooks.chestInfo} at all. The
 * statics delegate here, so hooking the instance covers both call styles and reaches the mod-held tables too.
 *
 * <p>
 * Neither injector changes a value or a draw; they only observe.
 */
@Mixin(value = ChestGenHooks.class, remap = false)
public class ChestGenHooksCaptureMixin {

    // Full descriptors, because ChestGenHooks carries a static (String, Random) overload of each name that
    // delegates here. A bare method name matches the static one and Mixin then refuses a non-static callback.
    @Inject(
        method = "getItems(Ljava/util/Random;)[Lnet/minecraft/util/WeightedRandomChestContent;",
        at = @At("RETURN"),
        require = 1)
    private void gtnhdet$noteItems(Random rnd, CallbackInfoReturnable<WeightedRandomChestContent[]> cir) {
        ChestFillContext.notedItems((ChestGenHooks) (Object) this, cir.getReturnValue());
    }

    @Inject(method = "getCount(Ljava/util/Random;)I", at = @At("RETURN"), require = 1)
    private void gtnhdet$noteCount(Random rand, CallbackInfoReturnable<Integer> cir) {
        ChestFillContext.notedCount((ChestGenHooks) (Object) this, cir.getReturnValue());
    }
}
