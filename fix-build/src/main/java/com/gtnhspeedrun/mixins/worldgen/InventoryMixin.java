package com.gtnhspeedrun.mixins.worldgen;

import java.util.List;
import java.util.Random;

import net.minecraft.tileentity.TileEntityChest;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import greymerk.roguelike.treasure.Inventory;

/**
 * Chest slot layout was shuffled with the room-generation rand, so any upstream draw-count variation rearranged
 * every later chest's slots (indistinguishable from "different loot" in NBT). Shuffle with a rand derived from
 * (world seed, chest position) instead — slot layout becomes a pure position function, and the room rand no
 * longer couples into inventories. (F5.)
 */
@Mixin(value = Inventory.class, remap = false)
public abstract class InventoryMixin {

    @Shadow
    private TileEntityChest chest;

    @Redirect(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Ljava/util/Collections;shuffle(Ljava/util/List;Ljava/util/Random;)V"))
    private void gtnhdet$positionShuffle(List<?> list, Random rand) {
        Random r = rand;
        if (this.chest != null && this.chest.getWorldObj() != null) {
            final long seed = this.chest.getWorldObj()
                .getSeed();
            r = new Random(
                seed ^ this.chest.xCoord * 341873128712L
                    ^ this.chest.yCoord * 132897987541L
                    ^ this.chest.zCoord * 0x9E3779B97F4A7C15L);
        }
        java.util.Collections.shuffle(list, r);
    }
}
