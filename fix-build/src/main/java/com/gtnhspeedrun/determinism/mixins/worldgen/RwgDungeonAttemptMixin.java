package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.world.World;
import net.minecraft.world.gen.feature.WorldGenDungeons;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.determinism.GtnhDeterminism;

/**
 * Diagnostic for vanilla dungeon route-dependence ({@code -Dgtnhdet.dungeontrace=true}).
 *
 * <p>
 * RWG runs eight dungeon attempts per chunk from the shared populate {@code Random}
 * ({@code ChunkGeneratorRealistic.populate}, the loop after the two {@code WorldGenLakes} calls):
 *
 * <pre>
 * for (int k1 = 0; k1 &lt; 8 &amp;&amp; gen; k1++) {
 *     int j5 = x + rand.nextInt(16) + 8;
 *     int k8 = rand.nextInt(128);
 *     int j11 = y + rand.nextInt(16) + 8;
 *     gen_dungeons.generate(worldObj, rand, j5, k8, j11);
 * }
 * </pre>
 *
 * <p>
 * There are three mechanisms behind a dungeon existing under one walk order and not another, and they
 * call for different fixes:
 *
 * <ul>
 * <li><b>Draw shift.</b> {@code WorldGenLakes.generate} can {@code return false} on a live world read
 * <em>before</em> consuming any draws, so an upstream block flip changes how many draws the shared
 * stream has taken and every attempt coordinate moves. Fix: fork the stream.</li>
 * <li><b>Scan verdict.</b> {@code WorldGenDungeons.generate} scans a box that crosses chunk borders
 * and refuses unless the air-opening count is in [1,5]. Same coordinates, different answer. Fix:
 * virgin terrain.</li>
 * <li><b>Room extents.</b> Two {@code nextInt(2)} draws at the top of {@code generate}, off the shared
 * stream, set the box the scan uses. Fixed in {@link WorldGenDungeonsScanMixin}.</li>
 * </ul>
 *
 * <p>
 * Logging the attempt coordinates and the verdict separates them: identical coordinates with
 * differing verdicts is the scan; differing coordinates is the draw shift. The repo has assumed the
 * draw shift dominates since 2026-08-27 without measuring which.
 *
 * <p>
 * Inert unless the flag is set. The vanilla method is referenced by its SRG name because this late
 * mixin applies to a mod class at production runtime.
 */
@Mixin(targets = "rwg.world.ChunkGeneratorRealistic", remap = false)
public abstract class RwgDungeonAttemptMixin {

    private static final boolean gtnhdet$TRACE = Boolean.getBoolean("gtnhdet.dungeontrace");

    /** {@code -Dgtnhdet.dungeonfork=false} restores stock attempt coordinates, for A/B measurement. */
    private static final boolean gtnhdet$FORK = !"false".equals(System.getProperty("gtnhdet.dungeonfork"));

    /** Salt for the per-chunk dungeon stream; distinct from every Thaumcraft feature id. */
    private static final long gtnhdet$SALT = 40L;

    /**
     * Attempt index within the current call to populate.
     *
     * <p>
     * An earlier version recovered this from a static "last chunk seen" counter. That was never
     * observed to fail — a warm arm appeared to show chunks running sixteen attempts, but the extra
     * eight belonged to warm's own boot world (level-seed=1) and had been misattributed by a trace
     * that keyed on log position rather than on the world seed. Cold runs always read a clean eight
     * per chunk.
     *
     * <p>
     * Resetting at the HEAD of populate is exact rather than inferred, but the counter must be keyed
     * PER CHUNK, not held in one slot. Population nests — a structure writing across a chunk border
     * triggers the neighbour's population inside its own call, which is the hazard
     * {@code ChunkPopulateBarrierMixin} exists for — so a single slot lets the inner chunk's reset
     * clobber the outer chunk's index, and how often that happens depends on walk order. Measured
     * with a single slot: 4 of 24 seeds differed rows-vs-spiral by exactly one room, every one of
     * them in a chunk BOTH walks populated. ThreadLocal additionally keeps concurrent populations
     * apart.
     */
    private static final ThreadLocal<java.util.HashMap<Long, Integer>> gtnhdet$attemptIndex = ThreadLocal
        .withInitial(java.util.HashMap::new);

    private static long gtnhdet$key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    @Inject(method = { "func_73153_a", "populate" }, at = @At("HEAD"), require = 1)
    private void gtnhdet$resetAttemptIndex(net.minecraft.world.chunk.IChunkProvider provider, int chunkX, int chunkZ,
        CallbackInfo ci) {
        gtnhdet$attemptIndex.get()
            .put(gtnhdet$key(chunkX, chunkZ), 0);
    }

    @Redirect(
        method = { "func_73153_a", "populate" },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/gen/feature/WorldGenDungeons;func_76484_a(Lnet/minecraft/world/World;Ljava/util/Random;III)Z"))
    private boolean gtnhdet$traceDungeonAttempt(WorldGenDungeons self, World world, Random rand, int x, int y, int z) {
        int ax = x, ay = y, az = z;
        if (gtnhdet$FORK) {
            // Recover the chunk from the passed coordinate. Stock computes x = chunkX*16 + nextInt(16) + 8
            // with nextInt in [0,15], so (x - 8) >> 4 is chunkX whatever the draw was — which is what makes
            // this safe even when the coordinate arrived already skewed.
            final int cx = (x - 8) >> 4;
            final int cz = (z - 8) >> 4;
            final java.util.HashMap<Long, Integer> counters = gtnhdet$attemptIndex.get();
            final long ckey = gtnhdet$key(cx, cz);
            final Integer prev = counters.get(ckey);
            final int idx = prev == null ? 0 : prev;
            counters.put(ckey, idx + 1);
            final Random f = com.gtnhspeedrun.determinism.worldgen.TcForkUtil.fork(world, cx, cz, gtnhdet$SALT);
            // Replay the loop's draw pattern up to this attempt so attempt N is a pure function of
            // (seed, chunk, N) rather than of how many draws the lakes above happened to consume.
            for (int i = 0; i < idx; i++) {
                f.nextInt(16);
                f.nextInt(128);
                f.nextInt(16);
            }
            ax = cx * 16 + f.nextInt(16) + 8;
            ay = f.nextInt(128);
            az = cz * 16 + f.nextInt(16) + 8;
        }
        // The shared rand is still passed through, so the number of draws populate takes is unchanged
        // and nothing downstream in the chunk moves because of this fix.
        final boolean built = self.generate(world, rand, ax, ay, az);
        if (gtnhdet$TRACE) {
            // Carry the world seed: warm generates its own boot world before the first requested seed, and
            // this trace does not go through TraceScope the way chesttrace does, so positional
            // attribution silently folds boot-world attempts into slot 1.
            GtnhDeterminism.LOG
                .info("[dungeonattempt] seed={} x={} y={} z={} built={}", world.getSeed(), ax, ay, az, built);
        }
        return built;
    }
}
