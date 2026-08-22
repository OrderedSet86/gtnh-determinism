package com.gtnhspeedrun.mixins.worldgen;

import java.util.Random;

import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import mrtjp.core.math.MathLib$;
import mrtjp.projectred.exploration.TileLily;
import scala.collection.Traversable;

/**
 * ProjectRed water lilies placed by worldgen roll their flower color (= dye yield) in TileLily.validate() through
 * MathLib.weightedRandom's DEFAULT Random — clock-seeded, so lily colors differ every launch on the same seed. Dyes
 * are run-relevant. Redirect the weighted pick to a Random derived from world seed + position: same lily, same color,
 * every launch. (GTNH speedrun determinism audit.)
 */
@Mixin(value = TileLily.class, remap = false)
public class TileLilyMixin {

    @Redirect(
        method = { "func_145829_t", "validate" },
        at = @At(
            value = "INVOKE",
            target = "Lmrtjp/core/math/MathLib$;weightedRandom(Lscala/collection/Traversable;Ljava/util/Random;)Ljava/lang/Object;"),
        require = 0)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private Object tcfix$seededColor(MathLib$ mathLib, Traversable weights, Random ignored) {
        final TileEntity self = (TileEntity) (Object) this;
        long seed = self.getWorldObj()
            .getSeed();
        seed = seed * 6364136223846793005L + (self.xCoord * 341873128712L + self.yCoord * 132897987541L + self.zCoord);
        return mathLib.weightedRandom(weights, new Random(seed));
    }
}
