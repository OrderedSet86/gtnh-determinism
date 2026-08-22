package com.gtnhspeedrun.mixins.worldgen;

import java.util.List;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.gen.structure.StructureVillagePieces;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.emoniph.witchery.worldgen.WorldHandlerVillageDistrict;
import com.emoniph.witchery.worldgen.WorldHandlerVillageDistrict.Wall.BlockVillageWallGen.TileEntityVillageWallGen;

/**
 * Builds Witchery village walls SYNCHRONOUSLY at generation time instead of 40+ ticks later: the moment the wall
 * piece hands this gen-tile its structure data, run placeWalls (whose reads/writes {@link VillageWallMixin} makes
 * virgin/sliced), clear the pending data so the stock tick path no-ops, and remove the gen block — nothing is
 * left to race the player's route or the tick scheduler. The 40-tick delay existed to let the village finish
 * building; the sliced build doesn't need it (heights are virgin-terrain, writes apply per chunk after that
 * chunk's own decoration).
 */
@Mixin(value = TileEntityVillageWallGen.class, remap = false)
public abstract class VillageWallGenTileMixin extends TileEntity {

    @Shadow
    private List bb;
    @Shadow
    private BiomeGenBase biome;
    @Shadow
    private boolean desert;

    @Inject(method = "setStructure", at = @At("TAIL"))
    private void gtnhdet$buildNow(List pieces, StructureVillagePieces.Start start, CallbackInfo ci) {
        if (this.bb == null || this.worldObj == null || this.worldObj.isRemote) return;
        WorldHandlerVillageDistrict.Wall
            .placeWalls(this.worldObj, this.bb, this.xCoord, this.yCoord, this.zCoord, this.biome, this.desert);
        this.bb = null;
        this.worldObj.setBlockToAir(this.xCoord, this.yCoord, this.zCoord);
    }
}
