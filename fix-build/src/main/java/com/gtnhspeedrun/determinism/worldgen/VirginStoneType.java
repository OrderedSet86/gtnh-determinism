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
 * <b>GregTech changed this lookup's semantics mid-line, so there are two methods here and picking the wrong one
 * is silent.</b> Both answer the same question from virgin blocks; they differ only in whether the
 * {@code canGenerateInWorld} dimension gate applies, because stock differs:
 *
 * <ul>
 * <li>{@link #at} — for GT <b>5.09.53.11 and later</b>. There {@code findStoneType(World, int, int, int)} inlines
 * the loop <em>with</em> the gate, so a faithful virgin answer must apply it too.</li>
 * <li>{@link #atUngated} — for GT <b>5.09.52.31 through 5.09.53.10</b> (this includes GTNH 2.9.0-beta-1, GT
 * 5.09.52.594). There the {@code World} overload just delegates to {@code findStoneType(Block, int)}, which has
 * <em>no</em> gate.</li>
 * </ul>
 *
 * <p>
 * Using {@link #at} on the older line is not virginisation — it adds a dimension filter stock does not have, and
 * the divergence is reachable: {@code SethClay} claims {@code hardened_clay} (wildcard meta) but is restricted to
 * {@code DimNames.SETH}, so in the Overworld a mesa column's terrain-stage {@code hardened_clay} makes stock
 * return {@code SethClay} (a hit) while {@link #at} returns null (a miss). The reroll probe takes nine samples
 * and rerolls at fewer than five hits, so one flipped sample selects a DIFFERENT vein. Neither the orepin audit
 * nor {@code ASSERT_PROBE} can see it, because it is a divergence from stock rather than a route dependence.
 *
 * <p>
 * LateMixinLoader picks between the two by probing {@code StoneType} for {@code VISUAL_STONE_TYPES}, a field
 * deleted by the same commit that added the gate at 5.09.53.11. That is a co-change, not a cause — it is used
 * because a constant-pool scan cannot see which method a call lives in.
 */
public final class VirginStoneType {

    private VirginStoneType() {}

    /**
     * Gated lookup, matching stock on GT 5.09.53.11 and later.
     *
     * <p>
     * This replicates GT's own loop rather than calling {@code findStoneType(Block, int)}, which looks equivalent
     * but drops the {@code canGenerateInWorld} gate. Where two stone types claim the same block and only the later
     * one is allowed in this dimension, the two-argument overload answers with the earlier one, and the probe would
     * diverge from stock in exactly the dimension-restricted cases the gate exists for. On GT 5.09.52.31 through
     * 5.09.53.10 that reasoning inverts — see {@link #atUngated}.
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

    /**
     * Ungated lookup, matching stock on GT 5.09.52.31 through 5.09.53.10.
     *
     * <p>
     * Deliberately calls GT's own two-argument overload rather than transcribing the loop: on this line that
     * overload IS what {@code findStoneType(World, int, int, int)} delegates to, so feeding it virgin inputs is
     * exactly stock-with-virgin-reads and nothing else. This is the same shape as
     * {@code OreManagerVirginDryRunMixin.gtnhdet$virginOreInfo}, which virginises {@code getOreInfo}'s inputs
     * instead of reimplementing it.
     *
     * <p>
     * Do NOT "fix" this to apply {@code canGenerateInWorld} for symmetry with {@link #at}. The gate does not
     * exist in stock on this line, and adding it changes which vein generates. See the class javadoc.
     *
     * @return the stone type of the VIRGIN block at these coordinates, or null if it is air or not stone.
     */
    public static StoneType atUngated(World world, int x, int y, int z) {
        return StoneType.findStoneType(TerrainOracle.block(world, x, y, z), TerrainOracle.meta(world, x, y, z));
    }
}
