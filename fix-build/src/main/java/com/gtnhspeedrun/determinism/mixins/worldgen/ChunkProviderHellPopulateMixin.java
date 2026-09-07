package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderHell;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.determinism.worldgen.NetherPopulateRng;

/**
 * Give the Nether the per-chunk populate re-seed every other dimension already has.
 *
 * <p>
 * <b>DOES NOT WORK. DO NOT QUOTE {@code gtnhdet.netherpop} AS A FIX.</b> The mixin binds — the log shows it
 * applied to {@code net.minecraft.world.gen.ChunkProviderHell} and the {@code require = 1} injector prepared
 * without error — but {@link NetherPopulateRng#reseed} is never invoked at runtime: its one-time INFO line
 * never appears in any arm, and a same-order A/B differing ONLY in {@code gtnhdet.netherpop} moved 15,297
 * blocks against a 9,422-block same-order floor on the same jar, i.e. no effect distinguishable from noise.
 * Root cause not yet found. Candidates ruled out: nothing subclasses or replaces the provider (ArchaicFix and
 * VillageNames mix into it but add no fields and do not overwrite {@code populate});
 * {@code ChunkProviderServer.populate:313} does call {@code currentChunkProvider.populate}; the flag reads
 * true; and no exception is logged. Left in place, disabled in effect, because the DEFECT it describes is real
 * and worth keeping written down — see the class javadoc below and
 * {@code docs/populate-stream-census.md} "Stream C".
 *
 * <p>
 * This is deliberately NOT part of the Nether ore-vein fix, which is the coordinate pin plus the virgin reads
 * and is measured independently of this mixin.
 *
 * <p>
 * <b>The defect is vanilla's, not a mod's.</b> {@code ChunkProviderGenerate.populate} opens with
 *
 * <pre>
 * rand.setSeed(worldObj.getSeed());
 * long i1 = rand.nextLong() / 2L * 2L + 1L;
 * long j1 = rand.nextLong() / 2L * 2L + 1L;
 * rand.setSeed((long) chunkX * i1 + (long) chunkZ * j1 ^ worldObj.getSeed());
 * </pre>
 *
 * so each chunk decorates from a stream determined by {@code (seed, chunkX, chunkZ)} alone. RWG's
 * {@code ChunkGeneratorRealistic.populate} does the same. {@code ChunkProviderHell.populate} does <b>not</b>: it
 * posts {@code PopulateChunkEvent.Pre} and then runs the fortress, lava lakes, fire, both glowstone passes,
 * mushrooms, nether quartz and the closed-lava pass straight off {@code hellRNG}, which was last touched by
 * whichever chunk most recently ran {@code provideChunk} or {@code populate}.
 *
 * <p>
 * {@code hellRNG} is therefore ONE continuous stream across the whole dimension, and its state when a given
 * chunk populates is the accumulated history of every chunk generated before it. Chunk load order is the
 * player's route. So in stock Minecraft the entire Nether decoration layer is route-dependent — the Nether's
 * analogue of stream A in {@code docs/populate-stream-census.md}, but with no per-chunk re-seed at all, which
 * makes it strictly worse than the overworld case that census documents.
 *
 * <p>
 * <b>Why HEAD.</b> The injection lands before the {@code PopulateChunkEvent.Pre} post, so mod handlers draw off
 * the re-seeded stream too. {@code provideChunk} already re-seeds {@code hellRNG} from the chunk coordinates on
 * its own first line, so terrain is untouched and only decoration moves.
 *
 * <p>
 * <b>This changes Nether decoration layout versus stock for a given seed.</b> Glowstone, quartz, lava, fire and
 * mushrooms land in different places; the amount of each per chunk is drawn the same way, so their totals should
 * not move. That distributional claim is measured, not assumed — a total that shifts would mean the re-seed
 * changed how many draws a consumer takes, not merely which values it got. The trade (determinism over matching
 * stock) is the project's standing one, and the same one F4d made for vein identity.
 *
 * <p>
 * Flag {@code -Dgtnhdet.netherpop=false} restores stock. The mixin loads unconditionally and branches inside the
 * handler, so an A/B is one property on one jar rather than two builds.
 */
@Mixin(ChunkProviderHell.class)
public class ChunkProviderHellPopulateMixin {

    /**
     * The re-seed itself lives in {@link NetherPopulateRng} because {@code hellRNG} and {@code worldObj} are
     * private fields of a VANILLA class: {@code @Shadow} would have to survive MCP-to-SRG remapping, the
     * annotation processor emits no refmap entries for shadowed fields in this build, and nothing else in this
     * project shadows a vanilla field to serve as precedent. See that class for the type-matched lookup used
     * instead, and why it throws rather than guessing.
     */
    @Inject(method = "populate", at = @At("HEAD"), require = 1)
    private void gtnhdet$reseedHellRng(IChunkProvider provider, int chunkX, int chunkZ, CallbackInfo ci) {
        NetherPopulateRng.reseed(this, chunkX, chunkZ);
    }
}
