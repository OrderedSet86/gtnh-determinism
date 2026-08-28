package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.List;
import java.util.Random;

import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.PendingSlices;

import greymerk.roguelike.treasure.Inventory;

/**
 * Chest slot layout was shuffled with the room-generation rand, so any upstream draw-count variation rearranged
 * every later chest's slots (indistinguishable from "different loot" in NBT). Shuffle with a rand derived from
 * (world seed, chest position) instead — slot layout becomes a pure position function, and the room rand no
 * longer couples into inventories. (F5.)
 *
 * <p>
 * The fork must be UNCONDITIONAL. Buffered chest writes are handed a detached tile entity, whose
 * {@code getWorldObj()} is null; an earlier version fell back to the room rand in that case, so a detached chest
 * spent 26 draws on the shared stream and an attached one spent none. Detachment is decided by
 * {@link PendingSlices#shouldBuffer}, i.e. by chunk generation order, so that fallback desynchronised the shared
 * stream from the first chest whose attachment differed and moved every later room, segment and chest decision in
 * the dungeon (measured: one dungeon emitted 105 chests under a rows walk and 111 under spiral). The world seed
 * comes from {@link PendingSlices#worldSeed()} when there is no world to ask.
 */
@Mixin(value = Inventory.class, remap = false)
public abstract class InventoryMixin {

    @Shadow
    private TileEntityChest chest;

    @Redirect(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Ljava/util/Collections;shuffle(Ljava/util/List;Ljava/util/Random;)V"))
    private void gtnhdet$positionShuffle(List<?> list, Random rand) {
        if (this.chest == null) {
            // no tile entity => getInventorySize() was 0 => empty list, and shuffling one consumes no draws
            java.util.Collections.shuffle(list, rand);
            return;
        }
        final World world = this.chest.getWorldObj();
        final long seed = world != null ? world.getSeed() : PendingSlices.worldSeed();
        java.util.Collections.shuffle(
            list,
            new Random(
                seed ^ this.chest.xCoord * 341873128712L
                    ^ this.chest.yCoord * 132897987541L
                    ^ this.chest.zCoord * 0x9E3779B97F4A7C15L));
    }
}
