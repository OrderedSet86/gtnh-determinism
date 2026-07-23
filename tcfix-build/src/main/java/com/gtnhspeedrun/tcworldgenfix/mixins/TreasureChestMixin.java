package com.gtnhspeedrun.tcworldgenfix.mixins;

import java.util.Random;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.tcworldgenfix.ChestPosAccess;

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

    @Override
    public long tcfix$posKey() {
        return tcfix$posKey;
    }
}
