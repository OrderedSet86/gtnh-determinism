package com.gtnhspeedrun.tcworldgenfix.mixins;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * GT ore vein placement probes a live world block on the no-overlap path: if the column at the triggering chunk's
 * center isn't stone-like (cave, ravine, exposed surface), the vein REROLLS to the next type — and the result is
 * cached for the whole 3x3-chunk vein region by whichever chunk generates first. Any block-level nondeterminism near
 * a probe column therefore avalanches into whole-vein rerolls (measured: 1300+ relocated ore tile entities per launch
 * on some seeds). Force the probe to accept, making vein identity a pure function of the seed.
 *
 * Targets GT 5.09.50.x (pack 2.7.4). All isReplaceableOreGen calls inside executeWorldgenChunkified are the probe —
 * ore placement itself uses other paths. The equivalent fix for GT master is on the GT5-Unofficial determinism-fixes
 * branch. (GTNH speedrun determinism audit, finding F4.)
 */
@Mixin(value = gregtech.common.WorldgenGTOreLayer.class, remap = false)
public class WorldgenGTOreLayerMixin {

    @Redirect(
        method = "executeWorldgenChunkified",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/block/Block;isReplaceableOreGen(Lnet/minecraft/world/World;IIILnet/minecraft/block/Block;)Z"),
        require = 0)
    private boolean tcfix$deterministicProbe(Block block, World world, int x, int y, int z, Block target) {
        return true;
    }
}
