package com.gtnhspeedrun.worldgenprobe.mixins;

import net.minecraft.inventory.IInventory;
import net.minecraft.util.WeightedRandomChestContent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.worldgenprobe.GateCapture;

/** Probe-only evidence: distinguish a real per-inventory refill from an unrelated chest counter. */
@Pseudo
@Mixin(targets = "com.gtnhspeedrun.determinism.worldgen.ChestFillContext", remap = false)
public abstract class PartInventoryMixin {
    @Shadow private static int refilled;

    @Inject(method = "<clinit>", at = @At("RETURN"), require = 1, remap = false)
    private static void gtnhprobe$installed(CallbackInfo ci) {
        GateCapture.lootCaptureInstalled();
    }

    @Inject(method = "refillChest", at = @At("HEAD"), require = 1, remap = false)
    private static void gtnhprobe$beforeRefill(WeightedRandomChestContent[] items, IInventory inventory,
        int count, CallbackInfo ci) {
        GateCapture.refillStart(refilled);
    }

    @Inject(method = "refillChest", at = @At("RETURN"), require = 1, remap = false)
    private static void gtnhprobe$afterRefill(WeightedRandomChestContent[] items, IInventory inventory,
        int count, CallbackInfo ci) {
        GateCapture.refillEnd(inventory, refilled);
    }
}
