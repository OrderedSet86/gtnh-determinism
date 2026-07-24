package com.gtnhspeedrun.tcworldgenfix.mixins;

import java.util.Random;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.tcworldgenfix.TraceSeg;

import greymerk.roguelike.dungeon.IDungeonLevel;
import greymerk.roguelike.dungeon.segment.ISegment;
import greymerk.roguelike.dungeon.segment.SegmentGeneratorBase;
import greymerk.roguelike.worldgen.Cardinal;
import greymerk.roguelike.worldgen.Coord;
import greymerk.roguelike.worldgen.IWorldEditor;

/** Diagnostic only (see {@link TraceSeg}); logs which segment each grid position picked. No behavior change. */
@Mixin(value = SegmentGeneratorBase.class, remap = false)
public abstract class SegmentGeneratorTraceMixin {

    @Inject(method = "pickSegment", at = @At("RETURN"))
    private void tcfix$tracePick(IWorldEditor editor, Random rand, IDungeonLevel level, Cardinal dir, Coord pos,
        CallbackInfoReturnable<ISegment> cir) {
        if (!TraceSeg.ON) return;
        final ISegment seg = cir.getReturnValue();
        TraceSeg.LOG.info(
            "pick {} at {},{},{} dir {}",
            seg == null ? "null"
                : seg.getClass()
                    .getSimpleName(),
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            dir);
    }
}
