package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.GtOreDryRun;
import com.gtnhspeedrun.determinism.worldgen.VirginStoneType;

import gregtech.api.enums.StoneType;
import gregtech.common.ores.OreManager;

/**
 * {@link OreManagerVirginStoneB1Mixin} for GregTech 5.09.53.11 through 5.09.54.01 — read that class first, the
 * reasoning is identical and only the call shape differs.
 *
 * <p>
 * At 5.09.53.11 {@link OreManager#setOreForWorldGen} switched from
 * {@code findStoneType(world.getBlock(..), world.getBlockMetadata(..))} to {@code findStoneType(world, x, y, z)},
 * and the {@code canGenerateInWorld} gate moved inside that callee. So the redirect takes a different descriptor,
 * needs no captured arguments, and must answer with the GATED lookup to stay faithful to stock.
 */
@Mixin(value = OreManager.class, remap = false)
public class OreManagerVirginStoneB2Mixin {

    @Redirect(
        method = "setOreForWorldGen",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/enums/StoneType;findStoneType(Lnet/minecraft/world/World;III)Lgregtech/api/enums/StoneType;"),
        require = 1,
        allow = 1)
    private static StoneType gtnhdet$virginHostStone(World world, int x, int y, int z) {
        if (!GtOreDryRun.active()) return StoneType.findStoneType(world, x, y, z);
        return VirginStoneType.at(world, x, y, z);
    }
}
