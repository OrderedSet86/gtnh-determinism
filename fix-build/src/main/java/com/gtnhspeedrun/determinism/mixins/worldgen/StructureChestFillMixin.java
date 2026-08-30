package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntityDispenser;
import net.minecraft.util.WeightedRandomChestContent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.determinism.worldgen.ChestFillContext;

/**
 * F10 fill half: re-rolls a structure chest from a position-derived {@code Random} once stock has finished with it.
 *
 * <p>
 * Injecting at RETURN rather than swapping the {@code Random} at HEAD is deliberate. The stock body still runs, so
 * every draw it would have taken from the chunk's populate stream is still taken, and nothing downstream of the
 * chest moves — the rest of the chunk generates exactly as it did before. Only the chest's contents change. See
 * {@link ChestFillContext} for what the fork is derived from and why it is component-relative.
 *
 * <p>
 * The refill re-enters this same method with the derived rand. That re-entry is what makes the fix independent of
 * injector ordering: {@link ChestAmuletVisMixin} also injects at RETURN here, and whichever of the two runs first,
 * the amulet pass ends up seeing the final inventory — either on the nested call, or on the outer one where its
 * position-derived charge is idempotent.
 */
@Mixin(value = WeightedRandomChestContent.class, priority = 900)
public class StructureChestFillMixin {

    @Inject(method = "generateChestContents", at = @At("RETURN"), require = 1)
    private static void gtnhdet$positionChest(Random rand, WeightedRandomChestContent[] items, IInventory inv,
        int count, CallbackInfo ci) {
        ChestFillContext.refillChest(items, inv, count);
    }

    @Inject(method = "generateDispenserContents", at = @At("RETURN"), require = 1)
    private static void gtnhdet$positionDispenser(Random rand, WeightedRandomChestContent[] items,
        TileEntityDispenser inv, int count, CallbackInfo ci) {
        ChestFillContext.refillDispenser(items, inv, count);
    }
}
