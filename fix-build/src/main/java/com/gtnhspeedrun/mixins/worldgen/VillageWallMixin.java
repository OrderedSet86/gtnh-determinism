package com.gtnhspeedrun.mixins.worldgen;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.emoniph.witchery.worldgen.WorldHandlerVillageDistrict;
import com.gtnhspeedrun.worldgen.PendingSlices;
import com.gtnhspeedrun.worldgen.TerrainOracle;

/**
 * Witchery village walls, order-independent (F2 follow-up, user-approved slicing pattern). Stock builds walls
 * from a gen-block tile entity 40+ ticks after generation, probing LIVE terrain per column — wall path was
 * deterministic (piece bounds, F1-pinned) but the ground heights and the build moment depended on the player's
 * route (and in headless/idle worlds the 1000-tick timeout could skip walls entirely).
 *
 * With {@link VillageWallGenTileMixin} triggering the build synchronously at generation, this mixin makes the
 * build itself pure: terrain reads (the 3x3 descending ground scan and the replaceability check) come from
 * {@link TerrainOracle} virgin terrain, and block writes route through {@link PendingSlices} — live into
 * already-applied chunks, buffered into everything else, applied at each chunk's own mod-worldgen tail. Guard
 * spawns stay live (entities can't be chunk-sliced); their positions become deterministic because the heights
 * are. SRG call targets — shipped Witchery is reobf.
 */
@Mixin(value = WorldHandlerVillageDistrict.Wall.class, remap = false)
public class VillageWallMixin {

    @Redirect(
        method = { "placeWalls", "setBlock" },
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;func_147439_a(III)Lnet/minecraft/block/Block;"),
        require = 0)
    private static Block gtnhdet$virginRead(World world, int x, int y, int z) {
        return TerrainOracle.block(world, x, y, z);
    }

    @Redirect(
        method = { "placeWalls", "setBlock" },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;func_147465_d(IIILnet/minecraft/block/Block;II)Z"),
        require = 0)
    private static boolean gtnhdet$routedWrite(World world, int x, int y, int z, Block block, int meta, int flag) {
        return PendingSlices.routeSetBlock(world, x, y, z, block, meta, flag);
    }

    @Redirect(
        method = "placeWalls",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;func_147449_b(IIILnet/minecraft/block/Block;)Z"),
        require = 0)
    private static boolean gtnhdet$routedWriteDefault(World world, int x, int y, int z, Block block) {
        return PendingSlices.routeSetBlock(world, x, y, z, block, 0, 3);
    }
}
