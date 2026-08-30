package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.determinism.worldgen.ChestFillContext;

/**
 * F10 site half, third piece: stops a chest placed by <em>chunk population</em> from inheriting the structure
 * piece that happens to be generating further up the stack.
 *
 * <p>
 * {@link StructureStartPartsMixin} makes the piece running {@code addComponentParts} the current site. That is
 * right until a piece writes into a chunk that has not been populated yet, which cascades straight into that
 * chunk's population — <em>inside</em> the outer piece's call. Measured on daily-707: a
 * {@code PlainsStable2} pushes its site, its own block writes trigger a neighbouring chunk's population, and
 * {@code WorldGenDungeons} then fills two dungeon chests 16 blocks underground while the stable's site is still
 * on the stack. Those chests were being derived from the stable's bounding box — deterministic, but wrong, and it
 * would move a dungeon's loot if the village moved.
 *
 * <p>
 * A barrier around population makes the rule exact: everything filled during population sees "no component"
 * unless a component pushes its own site deeper in. Nesting resolves correctly because the barrier is pushed
 * again for each nested population.
 *
 * <p>
 * The obvious cheaper test — "is the chest inside the piece's bounding box?" — was tried and rejected on
 * measurement. 11 of 17 out-of-box fills are legitimate: {@code ComponentToolWorkshop},
 * {@code PlainsWeaponsmith1} and others really do place their chests outside their declared box, and filtering
 * on containment would have discarded them.
 *
 * <p>
 * {@code ChunkProviderServer.populate} is the single funnel — it delegates to whatever generator the world type
 * installed, so one hook covers RWG and vanilla alike.
 */
@Mixin(ChunkProviderServer.class)
public abstract class ChunkPopulateBarrierMixin {

    @Inject(method = "populate", at = @At("HEAD"), require = 1)
    private void gtnhdet$populateBarrier(IChunkProvider provider, int cx, int cz, CallbackInfo ci) {
        ChestFillContext.enterPopulation();
    }

    @Inject(method = "populate", at = @At("RETURN"), require = 1)
    private void gtnhdet$populateBarrierDone(IChunkProvider provider, int cx, int cz, CallbackInfo ci) {
        ChestFillContext.leaveComponent();
    }
}
