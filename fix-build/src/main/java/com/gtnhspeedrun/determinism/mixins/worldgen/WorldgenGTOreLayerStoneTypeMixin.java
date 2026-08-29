package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.TerrainOracle;

import gregtech.api.enums.StoneType;

/**
 * F4 for GregTech 5.09.54.x and later. Same fix as {@link WorldgenGTOreLayerMixin}, same reasoning — read that
 * class's javadoc for why the reroll is kept and only its input is corrected — retargeted onto the probe GT moved.
 *
 * <p>
 * 5.09.54.x rewrote the ore API and deleted {@code Block.isReplaceableOreGen}, which the pre-54 mixin redirects. The
 * vein-reroll probe survived the rewrite unchanged in shape: {@code executeWorldgenChunkified} still runs nine
 * {@code veinMinY + i} samples at the chunk-centre column and still rerolls the vein when fewer than five are stone,
 * but it now asks {@link StoneType#findStoneType(World, int, int, int)} instead. That call reads the live world, so
 * the vein identity it decides still depends on which neighbouring chunks the player's route had already generated
 * and populated. Answering it from virgin terrain makes it a pure function of the seed while leaving GT's reroll
 * design intact.
 *
 * <p>
 * The handler replicates GT's own loop rather than calling {@code findStoneType(Block, int)}, which looks equivalent
 * but drops the {@code canGenerateInWorld} gate. Where two stone types claim the same block and only the later one
 * is allowed in this dimension, the two-argument overload answers with the earlier one, and the probe would diverge
 * from stock in exactly the dimension-restricted cases the gate exists for.
 *
 * <p>
 * Applied only when {@code gregtech.api.enums.StoneType} is on the classpath; see LateMixinLoader. The class does
 * not exist before 5.09.54.x, so on older packs this mixin cannot be loaded at all and the pre-54 one runs instead.
 */
@Mixin(value = gregtech.common.WorldgenGTOreLayer.class, remap = false)
public class WorldgenGTOreLayerStoneTypeMixin {

    // The descriptor is spelled out because 5.09.54.x split executeWorldgenChunkified into two overloads and only
    // this one, the private ten-argument body, holds the probe. The pre-54 mixin gets away with a bare name because
    // the method was unique there; here a bare name matches nothing and the mixin fails to bind.
    @Redirect(
        method = "executeWorldgenChunkified(Lnet/minecraft/world/World;Lgregtech/api/objects/XSTR;Ljava/lang/String;IIIILgregtech/common/WorldgenGTOreLayer$VeinPlacement;ZZ)I",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/enums/StoneType;findStoneType(Lnet/minecraft/world/World;III)Lgregtech/api/enums/StoneType;"),
        require = 2)
    private static StoneType gtnhdet$virginStoneType(World world, int x, int y, int z) {
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
