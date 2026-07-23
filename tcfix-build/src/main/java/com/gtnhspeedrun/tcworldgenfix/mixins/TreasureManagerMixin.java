package com.gtnhspeedrun.tcworldgenfix.mixins;

import java.util.Comparator;
import java.util.List;
import java.util.Random;

import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.tcworldgenfix.ChestPosAccess;

import greymerk.roguelike.treasure.ITreasureChest;
import greymerk.roguelike.treasure.Treasure;
import greymerk.roguelike.treasure.TreasureManager;
import greymerk.roguelike.util.IWeighted;

/**
 * Roguelike assigned loot by walking the chest list in placement order with ONE shared Random — any variation in
 * chest membership or list order re-rolled every later chest in the dungeon (measured: ~90 same-position chests
 * with different contents between walk orders). Now each loot rule draws one base value from the shared rand
 * (constant draw count regardless of membership), and every chest's actual items come from a Random forked from
 * (base, chest position) — loot is a pure function of (seed, dungeon position, chest position). (F5.)
 */
@Mixin(value = TreasureManager.class, remap = false)
public abstract class TreasureManagerMixin {

    @Shadow
    List<ITreasureChest> chests;

    @Shadow
    public abstract List<ITreasureChest> getChests(Treasure type, int level);

    @Shadow
    public abstract List<ITreasureChest> getChests(int level);

    @Shadow
    public abstract List<ITreasureChest> getChests(Treasure type);

    @Inject(method = "add", at = @At("RETURN"))
    private void tcfix$keepSorted(ITreasureChest toAdd, CallbackInfo ci) {
        this.chests.sort(
            Comparator.comparingLong(c -> c instanceof ChestPosAccess ? ((ChestPosAccess) c).tcfix$posKey() : 0L));
    }

    @Unique
    private static Random tcfix$chestRand(long base, ITreasureChest chest) {
        final long key = chest instanceof ChestPosAccess ? ((ChestPosAccess) chest).tcfix$posKey() : 0L;
        return new Random(base ^ key * 0x9E3779B97F4A7C15L);
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason Per-chest position-forked loot rand (see class doc). Selection semantics unchanged.
     */
    @Overwrite
    public void addItemToAll(Random rand, Treasure type, int level, IWeighted<ItemStack> item, int amount) {
        final long base = rand.nextLong();
        for (final ITreasureChest chest : this.getChests(type, level)) {
            final Random cr = tcfix$chestRand(base, chest);
            for (int i = 0; i < amount; ++i) {
                chest.setRandomEmptySlot(item.get(cr));
            }
        }
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason Per-chest position-forked loot rand (see class doc). Selection semantics unchanged.
     */
    @Overwrite
    public void addItemToAll(Random rand, int level, IWeighted<ItemStack> item, int amount) {
        final long base = rand.nextLong();
        for (final ITreasureChest chest : this.getChests(level)) {
            final Random cr = tcfix$chestRand(base, chest);
            for (int i = 0; i < amount; ++i) {
                chest.setRandomEmptySlot(item.get(cr));
            }
        }
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason Per-chest position-forked loot rand (see class doc). Selection semantics unchanged.
     */
    @Overwrite
    public void addItemToAll(Random rand, Treasure type, IWeighted<ItemStack> item, int amount) {
        final long base = rand.nextLong();
        for (final ITreasureChest chest : this.getChests(type)) {
            final Random cr = tcfix$chestRand(base, chest);
            for (int i = 0; i < amount; ++i) {
                chest.setRandomEmptySlot(item.get(cr));
            }
        }
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason Chest pick stays index-based (membership-local by nature); the ITEMS drawn for the picked chest come
     *         from its position fork so they cannot shift other chests.
     */
    @Overwrite
    public void addItem(Random rand, int level, IWeighted<ItemStack> item, int amount) {
        final List<ITreasureChest> list = getChests(level);
        if (list.isEmpty()) return;
        for (int i = 0; i < amount; ++i) {
            final ITreasureChest chest = list.get(rand.nextInt(list.size()));
            chest.setRandomEmptySlot(item.get(tcfix$chestRand(rand.nextLong(), chest)));
        }
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason See addItem(Random, int, IWeighted, int).
     */
    @Overwrite
    public void addItem(Random rand, Treasure type, IWeighted<ItemStack> item, int amount) {
        final List<ITreasureChest> list = getChests(type);
        if (list.isEmpty()) return;
        for (int i = 0; i < amount; ++i) {
            final ITreasureChest chest = list.get(rand.nextInt(list.size()));
            chest.setRandomEmptySlot(item.get(tcfix$chestRand(rand.nextLong(), chest)));
        }
    }
}
