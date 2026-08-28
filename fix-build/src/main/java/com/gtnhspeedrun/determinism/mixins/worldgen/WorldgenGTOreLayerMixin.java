package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.TerrainOracle;

/**
 * GT ore vein placement probes a world block on the no-overlap path: if the column at the probe point isn't
 * stone-like at the vein's Y-band, the vein REROLLS to the next type — and the result is cached for the whole
 * 3x3-chunk vein region by whichever chunk generates first. This reroll is intentional logic (a high-band vein
 * like upper cassiterite on low ground would otherwise generate into air = a no-op chunk; mountains hosting the
 * high veins is a designed prospecting incentive), so it must be KEPT — but stock evaluated it against the LIVE
 * world, where the probed block depends on which neighboring chunks the player's route had already generated and
 * populated (another vein's ore, a dungeon wall, decoration). Any such noise avalanched into whole-vein identity
 * rerolls (measured: 1300+ relocated ore tile entities per launch on some seeds).
 *
 * v1 (jar 0.1-0.2) forced the probe to accept — deterministic, but it disabled the reroll: high veins silently
 * no-op'd on low terrain and the mountain incentive was lost. v2 (0.3) answers the SAME probe from VIRGIN
 * (terrain-stage, pre-population) blocks via {@link TerrainOracle}: caves/air/low ground still reroll exactly as
 * designed, but the answer is a pure function of the seed — population-order noise can no longer flip vein
 * identity, and any chunk of the vein region computes the same cached result.
 *
 * Targets GT 5.09.50.x (pack 2.7.4). All isReplaceableOreGen calls inside executeWorldgenChunkified are the probe —
 * ore placement itself uses other paths. (GTNH speedrun determinism audit, finding F4.)
 *
 * <p>
 * NOT covered: {@code setOreBlock}'s per-block live-world read still decides WHERE ore lands, and the
 * {@code NO_ORE_IN_BOTTOM_LAYER} gate still decides identity from trigger-chunk state. Together those leave ore
 * worldgen order-dependent — see results/2026-08-27-gt-ore-probe-pinning/README.md.
 */
@Mixin(value = gregtech.common.WorldgenGTOreLayer.class, remap = false)
public class WorldgenGTOreLayerMixin {

    @Redirect(
        method = "executeWorldgenChunkified",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/block/Block;isReplaceableOreGen(Lnet/minecraft/world/World;IIILnet/minecraft/block/Block;)Z"),
        require = 0)
    private boolean gtnhdet$virginProbe(Block block, World world, int x, int y, int z, Block target) {
        final Block virgin = TerrainOracle.block(world, x, y, z);
        return virgin.isReplaceableOreGen(world, x, y, z, target);
    }
}
