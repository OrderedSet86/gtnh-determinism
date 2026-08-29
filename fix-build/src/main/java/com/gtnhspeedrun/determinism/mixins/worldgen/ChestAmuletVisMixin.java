package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.entity.Entity;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.util.WeightedRandomChestContent;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import thaumcraft.api.aspects.Aspect;
import thaumcraft.common.items.baubles.ItemAmuletVis;

/**
 * Gives every loot Vis Amulet a charge derived from the chest it lands in, rather than one shared charge.
 *
 * <p>
 * {@link ThaumcraftInitLootMixin} stops the charge changing per launch, but it can only make it a constant: the roll
 * happens once at mod init, before any world exists, and Thaumcraft copies that single stack into every chest and
 * loot-bag entry. So stock gives EVERY amulet in a session the same six aspect values, and pinning the seed keeps
 * that — deterministic, but uniform in a way the item's design clearly did not intend.
 *
 * <p>
 * This hook re-rolls the amulet from {@code (world seed, chest x, y, z)} at the moment a chest is filled, using the
 * same mixing constants as {@link TileLilyMixin}. Result: seed-stable, route-stable, and different from chest to
 * chest. Each aspect is drawn as {@code nextInt(5)} and applied through Thaumcraft's own
 * {@code addVis(stack, aspect, n, true)} — the exact call and argument domain {@code Config.initLoot} uses — so the
 * per-amulet distribution is stock's. What changes is the correlation BETWEEN amulets: stock made two amulets in one
 * session identical, this makes them independent. Marginal balance is unaffected; a player looting two amulets now
 * gets two different charges instead of two copies.
 *
 * <p>
 * Hooked on the vanilla chest filler rather than on Thaumcraft, because that is the only point where the item and
 * the destination coordinates are both in scope. Dungeon, mineshaft, stronghold, pyramid and village chests all
 * route through it, as do minecart chests, which arrive as an {@link Entity} instead of a {@link TileEntity}.
 * Anything with no world or no position is left with the pinned constant from the init-time fix, which is still
 * deterministic.
 */
@Mixin(value = WeightedRandomChestContent.class, remap = false)
public class ChestAmuletVisMixin {

    @Inject(method = "func_76293_a", at = @At("RETURN"), require = 1)
    private static void gtnhdet$positionAmuletVis(Random random, WeightedRandomChestContent[] items, IInventory inv,
        int count, CallbackInfo ci) {
        final World world;
        final int x, y, z;
        if (inv instanceof TileEntity) {
            final TileEntity te = (TileEntity) inv;
            world = te.getWorldObj();
            x = te.xCoord;
            y = te.yCoord;
            z = te.zCoord;
        } else if (inv instanceof Entity) {
            final Entity e = (Entity) inv;
            world = e.worldObj;
            x = MathHelper.floor_double(e.posX);
            y = MathHelper.floor_double(e.posY);
            z = MathHelper.floor_double(e.posZ);
        } else {
            return;
        }
        if (world == null) return;

        for (int slot = 0; slot < inv.getSizeInventory(); slot++) {
            final ItemStack st = inv.getStackInSlot(slot);
            if (st == null || !(st.getItem() instanceof ItemAmuletVis)) continue;
            final ItemAmuletVis amulet = (ItemAmuletVis) st.getItem();
            // Slot is in the mix so two amulets in one chest cannot come out identical.
            long s = world.getSeed() * 6364136223846793005L
                + (x * 341873128712L + y * 132897987541L + z + slot * 4987142L);
            final Random rand = new Random(s);
            for (Aspect a : Aspect.getPrimalAspects()) {
                amulet.storeVis(st, a, 0); // addVis accumulates; clear the init-time template first
                amulet.addVis(st, a, rand.nextInt(5), true);
            }
        }
    }
}
