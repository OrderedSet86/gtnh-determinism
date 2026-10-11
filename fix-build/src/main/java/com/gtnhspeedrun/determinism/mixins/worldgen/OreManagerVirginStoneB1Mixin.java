package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.GtOreDryRun;
import com.gtnhspeedrun.determinism.worldgen.VirginStoneType;

import gregtech.api.enums.StoneType;
import gregtech.api.interfaces.IOreMaterial;
import gregtech.api.interfaces.IStoneType;
import gregtech.common.ores.OreManager;

/**
 * The other half of the reconstructed dry run: makes the suppressed test read VIRGIN terrain, for GregTech
 * 5.09.52.31 through 5.09.53.10.
 *
 * <p>
 * Suppressing the write alone is not enough. {@link OreManager#setOreForWorldGen} decides whether a block can
 * host ore by reading the LIVE world, so a test pinned to the oreseed would still answer differently depending on
 * which neighbouring chunks the player's route had already generated and populated — the decision would be pinned
 * but not deterministic. Answering from terrain-stage blocks makes it a pure function of the seed, which is the
 * same correction {@code OreManagerVirginDryRunMixin} applies on 5.09.54.02+.
 *
 * <p>
 * <b>Why this band needs its own class.</b> At 5.09.53.11 GregTech rewrote this line from
 * {@code findStoneType(world.getBlock(..), world.getBlockMetadata(..))} to {@code findStoneType(world, x, y, z)},
 * moving the {@code canGenerateInWorld} gate inside the callee. The two spellings need different
 * {@code @Redirect} descriptors, and writing both in one class would force {@code require = 0} on each — the
 * silent-binding hole this project has twice been bitten by. {@link OreManagerVirginStoneB2Mixin} covers the
 * later half; LateMixinLoader offers exactly one of the two, and each binds at {@code require = 1}.
 *
 * <p>
 * {@link VirginStoneType#atUngated} rather than {@code at}: stock here calls the two-argument overload, which has
 * no dimension gate, and the gate is applied separately by {@code setOreForWorldGen} itself on the result. Using
 * the gated lookup would double-apply it and silently reject host blocks stock accepts.
 *
 * <p>
 * The handler captures {@code setOreForWorldGen}'s own parameters after the redirected call's arguments, because
 * the two-argument overload is handed a block and a meta with no coordinates attached, and the coordinates are
 * what the virgin lookup needs.
 */
@Mixin(value = OreManager.class, remap = false)
public class OreManagerVirginStoneB1Mixin {

    @Redirect(
        method = "setOreForWorldGen",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/enums/StoneType;findStoneType(Lnet/minecraft/block/Block;I)Lgregtech/api/enums/StoneType;"),
        require = 1,
        allow = 1)
    private static StoneType gtnhdet$virginHostStone(Block block, int meta, World world, int x, int y, int z,
        IStoneType defaultStone, IOreMaterial material, boolean small) {
        if (!GtOreDryRun.active()) return StoneType.findStoneType(block, meta);
        return VirginStoneType.atUngated(world, x, y, z);
    }
}
