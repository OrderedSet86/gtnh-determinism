package com.gtnhspeedrun.mixins.worldgen;

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

import com.gtnhspeedrun.worldgen.ChestPosAccess;

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
    private void gtnhdet$keepSorted(ITreasureChest toAdd, CallbackInfo ci) {
        this.chests.sort(
            Comparator.comparingLong(c -> c instanceof ChestPosAccess ? ((ChestPosAccess) c).gtnhdet$posKey() : 0L));
    }

    @Unique
    private static Random gtnhdet$chestRand(long base, ITreasureChest chest) {
        final long key = chest instanceof ChestPosAccess ? ((ChestPosAccess) chest).gtnhdet$posKey() : 0L;
        return new Random(base ^ key * 0x9E3779B97F4A7C15L);
    }

    @Unique
    private static boolean gtnhdet$live(ITreasureChest chest) {
        return !(chest instanceof ChestPosAccess) || ((ChestPosAccess) chest).gtnhdet$isLive();
    }

    @Unique
    private static List<ITreasureChest> gtnhdet$liveOnly(List<ITreasureChest> in) {
        final java.util.ArrayList<ITreasureChest> out = new java.util.ArrayList<>(in.size());
        for (final ITreasureChest c : in) if (gtnhdet$live(c)) out.add(c);
        return out;
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason Per-chest position-forked loot rand (see class doc). Selection semantics unchanged.
     */
    @Overwrite
    public void addItemToAll(Random rand, Treasure type, int level, IWeighted<ItemStack> item, int amount) {
        final long base = rand.nextLong();
        for (final ITreasureChest chest : this.getChests(type, level)) {
            if (!gtnhdet$live(chest)) continue; // carved-over chest: no block left, must not shift loot
            final Random cr = gtnhdet$chestRand(base, chest);
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
            if (!gtnhdet$live(chest)) continue;
            final Random cr = gtnhdet$chestRand(base, chest);
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
            if (!gtnhdet$live(chest)) continue;
            final Random cr = gtnhdet$chestRand(base, chest);
            for (int i = 0; i < amount; ++i) {
                chest.setRandomEmptySlot(item.get(cr));
            }
        }
    }

    /**
     * Membership-robust single-chest delivery: pick over the FULL placed-chest list (placement is deterministic;
     * the live subset is not — chunk-load order decides which deep chests later population carves over, and a
     * live-set-indexed pick would redistribute a floor's rule items whenever one chest dies). Items destined for a
     * dead chest are dropped — exactly stock's semantics, where those picks landed in carved-over chests and were
     * lost. Draw count is constant regardless of liveness, so no other chest ever shifts.
     */
    @Unique
    private void gtnhdet$addItemStable(Random rand, List<ITreasureChest> list, IWeighted<ItemStack> item, int amount) {
        if (list.isEmpty()) return;
        for (int i = 0; i < amount; ++i) {
            final ITreasureChest chest = list.get(rand.nextInt(list.size()));
            final ItemStack stack = item.get(gtnhdet$chestRand(rand.nextLong(), chest));
            if (gtnhdet$live(chest)) chest.setRandomEmptySlot(stack);
            com.gtnhspeedrun.worldgen.SliceTrace.log(
                "loot-single listSize={} pickKey={} live={} item={}",
                list.size(),
                chest instanceof ChestPosAccess ? ((ChestPosAccess) chest).gtnhdet$posKey() : 0L,
                gtnhdet$live(chest),
                stack == null ? "null" : stack.getUnlocalizedName());
        }
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason Route-independent pick over the full placed-chest list; dead-chest picks are dropped (stock loss
     *         semantics). Items come from the picked chest's position fork so they cannot shift other chests.
     */
    @Overwrite
    public void addItem(Random rand, int level, IWeighted<ItemStack> item, int amount) {
        gtnhdet$addItemStable(rand, getChests(level), item, amount);
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason See addItem(Random, int, IWeighted, int).
     */
    @Overwrite
    public void addItem(Random rand, Treasure type, IWeighted<ItemStack> item, int amount) {
        gtnhdet$addItemStable(rand, getChests(type), item, amount);
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason The (type, level) variant is the main typed single-chest rule path (LootRule with toEach=false) and
     *         was previously NOT overwritten: it picked and drew everything from the shared stream. Same
     *         stable-pick treatment as the other addItem variants.
     */
    @Overwrite
    public void addItem(Random rand, Treasure type, int level, IWeighted<ItemStack> item, int amount) {
        gtnhdet$addItemStable(rand, getChests(type, level), item, amount);
    }
}
