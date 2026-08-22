package com.gtnhspeedrun.mixins.worldgen;

import java.util.Random;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.worldgen.TraceSeg;

import greymerk.roguelike.dungeon.IDungeonLevel;
import greymerk.roguelike.dungeon.segment.part.SegmentBase;
import greymerk.roguelike.theme.ITheme;
import greymerk.roguelike.worldgen.Cardinal;
import greymerk.roguelike.worldgen.Coord;
import greymerk.roguelike.worldgen.IWorldEditor;

/** Diagnostic only (see {@link TraceSeg}); no behavior change. */
@Mixin(value = SegmentBase.class, remap = false)
public abstract class SegmentBaseTraceMixin {

    @Inject(method = "generate", at = @At("HEAD"))
    private void gtnhdet$traceGenerate(IWorldEditor editor, Random rand, IDungeonLevel level, Cardinal dir,
        ITheme theme, Coord pos, CallbackInfo ci) {
        if (!TraceSeg.ON) return;
        TraceSeg.LOG.info(
            "seg {} pos {},{},{} dir {}",
            this.getClass()
                .getSimpleName(),
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            dir);
    }

    @Inject(method = "isValidWall", at = @At("RETURN"))
    private void gtnhdet$traceValidWall(IWorldEditor editor, Cardinal dir, Coord pos,
        CallbackInfoReturnable<Boolean> cir) {
        if (!TraceSeg.ON) return;
        TraceSeg.LOG.info(
            "validWall {} pos {},{},{} dir {} -> {}",
            this.getClass()
                .getSimpleName(),
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            dir,
            cir.getReturnValue());
    }
}
