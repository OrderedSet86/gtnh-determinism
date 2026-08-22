package com.gtnhspeedrun.mixins.worldgen;

import java.util.Random;

import net.minecraft.block.material.Material;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.worldgen.TraceSeg;

import greymerk.roguelike.dungeon.IDungeonLevel;
import greymerk.roguelike.dungeon.segment.part.SegmentFirePlace;
import greymerk.roguelike.theme.ITheme;
import greymerk.roguelike.worldgen.Cardinal;
import greymerk.roguelike.worldgen.Coord;
import greymerk.roguelike.worldgen.IWorldEditor;

/** Diagnostic only (see {@link TraceSeg}); logs the firebox shell solidity gate reads. No behavior change. */
@Mixin(value = SegmentFirePlace.class, remap = false)
public abstract class SegmentFirePlaceTraceMixin {

    @Inject(method = "genWall", at = @At("HEAD"))
    private void gtnhdet$traceHead(IWorldEditor editor, Random rand, IDungeonLevel level, Cardinal dir, ITheme theme,
        Coord origin, CallbackInfo ci) {
        if (!TraceSeg.ON) return;
        TraceSeg.LOG.info("fireplace genWall origin {},{},{} dir {}", origin.getX(), origin.getY(), origin.getZ(), dir);
    }

    @Redirect(
        method = "genWall",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/block/material/Material;func_76220_a()Z"))
    private boolean gtnhdet$traceShellRead(Material material) {
        final boolean solid = material.isSolid();
        if (TraceSeg.ON) {
            TraceSeg.LOG.info("fireplace shellRead solid={} material={}", solid, material);
        }
        return solid;
    }
}
