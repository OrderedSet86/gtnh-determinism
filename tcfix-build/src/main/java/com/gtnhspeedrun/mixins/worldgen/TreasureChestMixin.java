package com.gtnhspeedrun.mixins.worldgen;

import java.util.Random;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.worldgen.ChestPosAccess;

import greymerk.roguelike.treasure.ITreasureChest;
import greymerk.roguelike.treasure.TreasureChest;
import greymerk.roguelike.worldgen.Coord;
import greymerk.roguelike.worldgen.IWorldEditor;

/** Captures the chest's generation position so TreasureManagerMixin can keep the chest list position-sorted. */
@Mixin(value = TreasureChest.class, remap = false)
public abstract class TreasureChestMixin implements ChestPosAccess {

    @Unique
    private long tcfix$posKey;

    @Inject(method = "generate", at = @At("HEAD"))
    private void tcfix$capturePos(IWorldEditor editor, Random rand, Coord pos, int level, boolean trapped,
        CallbackInfoReturnable<ITreasureChest> cir) {
        // pack y,x,z into one comparable key (y major so levels group; offsets keep terms non-negative)
        tcfix$posKey = ((long) (pos.getY() + 512) << 44) | ((long) (pos.getX() + 30000000 & 0x3FFFFF) << 22)
            | (pos.getZ() + 30000000 & 0x3FFFFF);
    }

    @Unique
    private net.minecraft.tileentity.TileEntityChest tcfix$te;

    @Inject(method = "generate", at = @At("RETURN"))
    private void tcfix$captureTe(IWorldEditor editor, Random rand, Coord pos, int level, boolean trapped,
        CallbackInfoReturnable<ITreasureChest> cir) {
        if (cir.getReturnValue() != null) {
            final Object te = editor.getTileEntity(pos);
            if (te instanceof net.minecraft.tileentity.TileEntityChest)
                tcfix$te = (net.minecraft.tileentity.TileEntityChest) te;
        }
        com.gtnhspeedrun.worldgen.SliceTrace.log(
            "chest-generate pos={},{},{} level={} placed={} detached={}",
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            level,
            cir.getReturnValue() != null,
            tcfix$te != null && tcfix$te.getWorldObj() == null);
    }

    @Override
    public long tcfix$posKey() {
        return tcfix$posKey;
    }

    /**
     * True iff the chest BLOCK still exists at this chest's position. Dungeon carving overwrites some placed
     * chests; the leftover TE can linger in the chunk map un-invalidated, so the block is the only reliable
     * witness. (F5 follow-up: stranded chests shifted membership-indexed loot picks per launch.)
     */
    @Override
    public boolean tcfix$isLive() {
        if (tcfix$te == null) return false;
        final net.minecraft.world.World w = tcfix$te.getWorldObj();
        if (w == null) return true; // no world to ask — do not over-filter
        return w.getBlock(tcfix$te.xCoord, tcfix$te.yCoord, tcfix$te.zCoord) instanceof net.minecraft.block.BlockChest;
    }
}
