package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.GtOreDryRun;

import gregtech.common.ores.OreManager;

/**
 * Half of the dry run GregTech 5.09.52.31 .. 5.09.54.01 does not provide: makes ore placement decide without
 * writing.
 *
 * <p>
 * On that band {@code WorldgenGTOreLayer.executeWorldgenChunkified} is a single fused method — it resolves the
 * vein geometry, tests whether the vein reaches this chunk, AND writes the ore, all in one call, and its return
 * value is what {@code GTWorldgenerator.generateVein} uses to accept or reject the candidate. GregTech split
 * those three jobs apart in 5.09.54.02 (into {@code resolveVeinPlacement} / {@code testWorldgenChunkified} /
 * placement with {@code resetRng}), which is what makes F4d four {@code @ModifyArg}s on that line. Here the split
 * has to be reconstructed, and this class provides the "test without writing" half.
 *
 * <p>
 * {@code LayerGenerator.generateLayer} reaches the world only through
 * {@link OreManager#setOreForWorldGen}, and that method writes through exactly one {@code World.setBlock}, whose
 * return value it ignores — it reports success with its own {@code return true} immediately after. So suppressing
 * that single call leaves the accept/reject verdict, and the {@code placed} flag it feeds, bit-identical while
 * placing nothing.
 *
 * <p>
 * <b>Why this cannot shift the RNG.</b> Every {@code rng.nextInt} in {@code generateLayer} is evaluated in the
 * {@code if} that guards the {@code setOreForWorldGen} call, i.e. strictly before it. Suppressing the write
 * consumes no draws and skips none, so the dry run and the real pass see the same stream for the same geometry.
 * That is what makes the rewind in {@link GTWorldGenContainerDryRunMixin} exact rather than approximate.
 *
 * <p>
 * Scoped by {@link GtOreDryRun}, which is opened only around the pinned test call and closed in a finally. When
 * {@code gtnhdet.orepin=false} the scope is never opened, this redirect always writes, and the jar is
 * bit-identical to stock.
 *
 * <p>
 * The SRG name is spelled out because the class-level {@code remap = false} needed for the GregTech target also
 * disables remapping of the vanilla member; this is the same convention as {@code VillageWallMixin}.
 * {@code func_147465_d} is {@code World.setBlock(int, int, int, Block, int, int)}.
 */
@Mixin(value = OreManager.class, remap = false)
public class OreManagerDryRunSuppressMixin {

    @Redirect(
        method = "setOreForWorldGen",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;func_147465_d(IIILnet/minecraft/block/Block;II)Z"),
        require = 1,
        allow = 1)
    private static boolean gtnhdet$suppressWriteInDryRun(World world, int x, int y, int z, Block block, int meta,
        int flags) {
        // Report the write as having succeeded without performing it: the caller's own `return true` is what
        // signals "placed" upstream, so the verdict is unchanged and no block moves.
        if (GtOreDryRun.active()) return true;
        return world.setBlock(x, y, z, block, meta, flags);
    }
}
