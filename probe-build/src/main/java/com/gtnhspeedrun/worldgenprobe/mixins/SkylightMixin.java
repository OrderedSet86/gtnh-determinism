package com.gtnhspeedrun.worldgenprobe.mixins;

import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.worldgenprobe.Prefilter;

/**
 * Times, and optionally skips, {@code Chunk.generateSkylightMap} for prefilter chunks only.
 *
 * <p>
 * The stage-0 prefilter reads blocks, never light: {@code SeedProbeWorld} no-ops {@code updateLightByType},
 * {@code markBlockForUpdate} and {@code notifyBlockChange}. Yet every chunk it generates pays for a full
 * skylight pass — two walks over 16x16 columns from the top filled segment down, calling
 * {@code getBlockLightOpacity} per block. Chunk generation is 61.7% of the radius-60 per-seed cost
 * (measured: 904 ms of 1466 ms, 538 chunks at ~1.68 ms), so any fixed share of a chunk is worth knowing.
 *
 * <p>
 * This is the cheapest available test of whether a chunk can be made cheaper at all — the question two
 * earlier experiments were taken to have closed, both of which measured the full-generation probe (where
 * a decoration stream the prefilter does not have dominates) on an RWG version this pack no longer ships.
 *
 * <p>
 * <b>The skip is not obviously safe and must not be assumed so.</b> {@code generateSkylightMap} also fills
 * {@code heightMap} / {@code heightMapMinimum} / {@code precipitationHeightMap}, and
 * {@code Chunk.getHeightValue} reads the first. Nothing in the prefilter is known to call it — the world's
 * own {@code getHeightValue} short-circuits to 0 because {@code VirginChunkProvider.chunkExists} is false —
 * but "known" is not "verified". Hence: default OFF, and the acceptance test is a byte-diff of the JSONL
 * over a seed set, not an argument about what light can affect.
 *
 * <p>
 * Timing-only by default, so the RWG 1.5.0-vs-1.5.2 version gate that disables the noise mixins does not
 * apply here: this injects into vanilla {@code Chunk}, and changes no behaviour unless
 * {@code -Dprobe.prefilter.skipskylight=true} is set.
 */
@Mixin(Chunk.class)
public abstract class SkylightMixin {

    @Inject(method = "generateSkylightMap", at = @At("HEAD"), cancellable = true, require = 1)
    private void gtnhprobe$timeOrSkipSkylight(CallbackInfo ci) {
        final Chunk self = (Chunk) (Object) this;
        if (!(self.worldObj instanceof Prefilter.SeedProbeWorld)) return;
        if (Prefilter.SKIP_SKYLIGHT) {
            Prefilter.Timing.hit("skylight.skipped");
            ci.cancel();
            return;
        }
        Prefilter.Timing.hit("skylight.ran");
        gtnhprobe$t.set(Prefilter.Timing.start());
    }

    @Inject(method = "generateSkylightMap", at = @At("RETURN"), require = 1)
    private void gtnhprobe$endSkylight(CallbackInfo ci) {
        final Chunk self = (Chunk) (Object) this;
        if (!(self.worldObj instanceof Prefilter.SeedProbeWorld)) return;
        final Long t = gtnhprobe$t.get();
        if (t != null) Prefilter.Timing.add("skylight", t);
    }

    /**
     * A ThreadLocal rather than an instance field: mixin-added instance state on {@code Chunk} would cost
     * a field on every chunk in every world, and this only ever wraps one call at a time per thread.
     */
    private static final ThreadLocal<Long> gtnhprobe$t = new ThreadLocal<>();
}
