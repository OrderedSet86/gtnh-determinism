package com.gtnhspeedrun.determinism.worldgen;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

import gregtech.api.enums.StoneType;

/**
 * {@code StoneType.findStoneType} answered from virgin (terrain-stage, pre-population) blocks.
 *
 * <p>
 * Shared by every F4-family fix so there is exactly ONE copy of the lookup. Two of GregTech's ore-worldgen
 * decisions ask this same question from different classes —
 * {@code WorldgenGTOreLayer.executeWorldgenChunkified}'s reroll gate and
 * {@code OreManager.getOreBlockForWorldGen}'s per-block placement test — and a mixin's {@code @Redirect} is
 * scoped to the method it targets, so each needs its own redirect. They must not each carry their own
 * transcription of the loop below: a divergence between them would be silent and would present as a partial,
 * arbitrary change in vein identity, which is the exact failure signature this project has already chased
 * twice (results/2026-08-27-gt-ore-probe-pinning, results/2026-09-05-gt-ore-dryrun-virgin).
 *
 * <p>
 * {@code GtOreShape} offers the F4 fixes only on GT 5.09.54.02 and later. There
 * {@code findStoneType(World, int, int, int)} inlines the loop <em>with</em> the {@code canGenerateInWorld} dimension
 * gate, so {@link #at} applies the gate too.
 */
public final class VirginStoneType {

    private VirginStoneType() {}

    /**
     * Gated lookup, matching stock on GT 5.09.54.02 and later.
     *
     * <p>
     * The loop below copies GregTech's loop. {@code findStoneType(Block, int)} looks equivalent but skips the
     * {@code canGenerateInWorld} gate: when the same block is in two stone types and only the later type is allowed in
     * this dimension, the two-argument overload returns the earlier type, and the probe differs from stock.
     *
     * @return the stone type of the VIRGIN block at these coordinates, or null if it is air or not stone.
     */
    public static StoneType at(World world, int x, int y, int z) {
        final Block virgin = TerrainOracle.block(world, x, y, z);
        if (virgin == Blocks.air) return null;
        final int meta = TerrainOracle.meta(world, x, y, z);
        for (int i = 0, n = StoneType.STONE_TYPES.size(); i < n; i++) {
            final StoneType stoneType = StoneType.STONE_TYPES.get(i);
            if (stoneType.isEnabled() && stoneType.canGenerateInWorld(world) && stoneType.contains(virgin, meta)) {
                return stoneType;
            }
        }
        return null;
    }
}
