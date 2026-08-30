package com.gtnhspeedrun.worldgenprobe;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

/**
 * A bare {@link IInventory} of a chosen slot count, for rolling a structure chest's contents outside a world.
 *
 * <p>
 * The slot count is the point. {@code WeightedRandomChestContent.generateChestContents} places every stack at
 * {@code rand.nextInt(inv.getSizeInventory())}, so filling a 27-slot {@code TileEntityChest} when the real chest
 * is a 30-slot {@code PatternChestLogic} changes the slot of every item and the stream after it — the contents
 * come out wrong in a way that looks like a broken RNG fork rather than a wrong container.
 *
 * <p>
 * Only the methods that filler touches do anything; the rest satisfy the interface.
 */
final class SizedInventory implements IInventory {

    private final ItemStack[] slots;

    SizedInventory(int size) {
        this.slots = new ItemStack[Math.max(1, size)];
    }

    @Override
    public int getSizeInventory() {
        return slots.length;
    }

    @Override
    public ItemStack getStackInSlot(int i) {
        return i < 0 || i >= slots.length ? null : slots[i];
    }

    @Override
    public void setInventorySlotContents(int i, ItemStack stack) {
        if (i >= 0 && i < slots.length) slots[i] = stack;
    }

    @Override
    public ItemStack decrStackSize(int i, int n) {
        return null;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int i) {
        return null;
    }

    @Override
    public String getInventoryName() {
        return "prefilter";
    }

    @Override
    public boolean hasCustomInventoryName() {
        return false;
    }

    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public void markDirty() {}

    @Override
    public boolean isUseableByPlayer(EntityPlayer p) {
        return false;
    }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    @Override
    public boolean isItemValidForSlot(int i, ItemStack stack) {
        return true;
    }
}
