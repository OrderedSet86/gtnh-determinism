package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import ganymedes01.etfuturum.tileentities.TileEntityCaveVines;

/**
 * {@code TileEntityCaveVines}'s constructor rolls the vine's maximum length from a bare clock-seeded Random:
 *
 * <pre>
 * 
 * public TileEntityCaveVines() {
 *     this.tipSheared = false;
 *     this.maxLength = new Random().nextInt(26) + 2;
 * }
 * </pre>
 *
 * {@code WorldGenCaveVines} never calls {@code setMaxLength}, so that value survives into the saved NBT. Measured
 * on seed -1501259159663517643: 105 {@code etfuturum.cave_vines} tile entities differed between two IDENTICAL cold
 * runs — the whole remaining tile-entity launch noise after the deepslate band was fixed.
 *
 * <p>
 * The constructor has no world and no position, so the value cannot be seeded there. Instead the roll is deferred:
 * the constructor's value is treated as provisional and replaced, from a rand seeded on (world seed, x, y, z), the
 * first time anything reads or persists it — by which point the tile entity has been placed and has both. A value
 * restored from NBT, or set explicitly through {@code setMaxLength}, wins and is never overwritten.
 *
 * <p>
 * Same bug class as F2 (Witchery clock RNG), the TiC slime island fix, and the BOP {@code Math.random()} flora roll.
 */
@Mixin(value = TileEntityCaveVines.class, remap = false)
public abstract class EtFuturumCaveVineTeMixin {

    @Shadow
    private int maxLength;

    /** False until the length is either loaded from NBT, set explicitly, or derived from this vine's position. */
    @Unique
    private boolean gtnhdet$lengthPinned;

    @Unique
    private void gtnhdet$pinLength() {
        if (gtnhdet$lengthPinned) return;
        final TileEntity te = (TileEntity) (Object) this;
        if (te.getWorldObj() == null) return; // not placed yet — stay provisional
        final Random r = new Random(
            te.getWorldObj()
                .getSeed() ^ te.xCoord * 341873128712L
                ^ te.yCoord * 132897987541L
                ^ te.zCoord * 0x9E3779B97F4A7C15L);
        maxLength = r.nextInt(26) + 2; // same distribution as stock
        gtnhdet$lengthPinned = true;
    }

    @Inject(method = "getMaxLength", at = @At("HEAD"), require = 1)
    private void gtnhdet$pinOnRead(CallbackInfoReturnable<Integer> cir) {
        gtnhdet$pinLength();
    }

    @Inject(method = "func_145841_b", at = @At("HEAD"), require = 1)
    private void gtnhdet$pinOnWrite(NBTTagCompound tag, CallbackInfo ci) {
        gtnhdet$pinLength();
    }

    /** A length restored from disk is authoritative — never re-roll it. */
    @Inject(method = "func_145839_a", at = @At("RETURN"), require = 1)
    private void gtnhdet$acceptLoaded(NBTTagCompound tag, CallbackInfo ci) {
        gtnhdet$lengthPinned = true;
    }

    /** An explicit set is authoritative too (shearing, growth). */
    @Inject(method = "setMaxLength", at = @At("RETURN"), require = 1)
    private void gtnhdet$acceptExplicit(int length, CallbackInfo ci) {
        gtnhdet$lengthPinned = true;
    }
}
