package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.GtOrePin;
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
 * Targets GT up to and including 5.09.52.30 (pack 2.8.4). Every {@code isReplaceableOreGen} call in
 * {@code executeWorldgenChunkified} is the probe. Ore placement uses other calls. Audit item F4.
 *
 * <p>
 * In 5.09.52.31 GregTech deleted {@code isReplaceableOreGen} and moved the probe to {@code StoneType.findStoneType}.
 * {@link WorldgenGTOreLayerStoneTypeMixin} is the mixin for the rewrite at 5.09.54.02. {@code LateMixinLoader} selects
 * between the two by probing bytecode, never by version string. {@code GtOreShape} explains why: the boundary
 * interleaves by tag number.
 *
 * <p>
 * {@code require = 10} is the exact number of probe call sites, two five-call short-circuit chains, verified on
 * every tagged version in this band. It is pinned at the exact count rather than at 1 because binding a SUBSET of
 * them would be a silent partial fix, which is the failure mode this project has already reverted twice. This
 * mixin was {@code require = 0} until 0.14.0, so a misselection bound nothing at all and said nothing.
 *
 * <p>
 * <b>Scope: {@link GtOrePin#appliesTo(World)}.</b> The handler answers from virgin terrain only where the pin applies,
 * so {@code -Dgtnhdet.orepin=false} restores stock.
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
        require = 10,
        allow = 10)
    private boolean gtnhdet$virginProbe(Block block, World world, int x, int y, int z, Block target) {
        if (!GtOrePin.appliesTo(world)) return block.isReplaceableOreGen(world, x, y, z, target);
        final Block virgin = TerrainOracle.block(world, x, y, z);
        return virgin.isReplaceableOreGen(world, x, y, z, target);
    }
}
