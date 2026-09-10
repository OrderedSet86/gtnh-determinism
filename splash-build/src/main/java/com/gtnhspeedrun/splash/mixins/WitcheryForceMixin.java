package com.gtnhspeedrun.splash.mixins;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.emoniph.witchery.worldgen.WitcheryWorldGenerator;
import com.gtnhspeedrun.splash.Plots;

/**
 * Forces a Witchery surface structure to be attempted at the plot chunks.
 *
 * <p>
 * This column photographs a categorical bug rather than a positional one. {@code generateOverworld} picks the winner
 * with {@code Collections.shuffle(this.generators, random)} on a <em>shared, fixed-size</em> list, mutated in place
 * and carried across chunks; every handler is constructed with {@code chance = 1.0}, so the first element whose
 * config toggle is on always wins. The result is a clean one-in-four coin flip per launch: the same cell yields a
 * wicker man, a coven stone circle, a shack or a hobgoblin hut. Structures are chunk-centred and at most eleven
 * blocks wide, so nine forced chunks pack into a 3x3 cluster with no overlap and the tile shows nine independent
 * flips at once.
 *
 * <p>
 * <b>The hook must be {@code nonInRange} and nothing else.</b> The determinism jar's
 * {@code WitcheryWorldGeneratorMixin} {@code @Overwrite}s {@code generate} and routes to its own private
 * {@code gtnhdet$generateOverworld}, so the stock {@code generateOverworld} is never called whenever that jar is
 * installed. An injection there would force structures in the two stock rows and be silently dead in the two fixed
 * rows — the filmstrip columns would misalign and the image would be a lie. The fix jar's copy still calls
 * {@code this.nonInRange(world, x, z, generator.getRange())}, verified by source, so this is the one hook live in
 * both arms.
 *
 * <p>
 * Injected at RETURN, never {@code @Overwrite}: the stock body calls {@code world.setRandomSeed(...)} and takes two
 * draws off {@code World.rand}, and stock already evaluates it for every overworld chunk that passes the biome gate.
 * Running the body and overriding only the verdict keeps that reseed cadence bit-identical.
 *
 * <p>
 * Witchery's own biome gate still applies — {@code generateOverworld} returns early if
 * {@code BiomeManager.DISALLOWED_BIOMES} contains the cell's biome, which happens before {@code nonInRange} is ever
 * reached. Plot chunks must be chosen from the scout output, or {@code -Dsplash.witchery.anybiome=true} used.
 */
@Mixin(value = WitcheryWorldGenerator.class, remap = false)
public class WitcheryForceMixin {

    @Inject(method = "nonInRange", at = @At("RETURN"), cancellable = true, remap = false)
    private void splash$force(World world, int blockX, int blockZ, int range, CallbackInfoReturnable<Boolean> cir) {
        if (Plots.forceWitchery(blockX, blockZ)) cir.setReturnValue(Boolean.TRUE);
    }
}
