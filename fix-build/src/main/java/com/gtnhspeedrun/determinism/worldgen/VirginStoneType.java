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
 * This replicates GT's own loop rather than calling {@code findStoneType(Block, int)}, which looks equivalent
 * but drops the {@code canGenerateInWorld} gate. Where two stone types claim the same block and only the later
 * one is allowed in this dimension, the two-argument overload answers with the earlier one, and the probe would
 * diverge from stock in exactly the dimension-restricted cases the gate exists for.
 */
public final class VirginStoneType {

    private VirginStoneType() {}

    /** @return the stone type of the VIRGIN block at these coordinates, or null if it is air or not stone. */
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
