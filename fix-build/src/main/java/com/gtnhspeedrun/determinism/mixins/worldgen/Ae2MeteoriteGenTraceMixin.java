package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.determinism.GtnhDeterminism;

/**
 * Companion to {@link Ae2MeteoritePlacerTraceMixin} ({@code -Dgtnhdet.meteortrace=true}).
 *
 * <p>
 * The placer trace answered its question with a silence: over a full radius-60 walk,
 * {@code MeteoritePlacer.spawnMeteoriteCenter} was called <b>zero</b> times, while the probe's own
 * gate check reported {@code generatorRegistered=true} and
 * {@code isWorldGenEnabled(Meteorites, dim 0)=true}. So AE2 is registered, AE2 permits meteorites
 * here, and yet nothing ever reaches the placer.
 *
 * <p>
 * That leaves two possibilities and they need separating before anything else is worth reading:
 *
 * <ul>
 * <li>{@code generate} is never invoked at all — registration is not invocation, and this counts
 * calls to prove which.</li>
 * <li>{@code generate} is invoked but returns early. Between the gate and the placer it parses
 * {@code minMeteoriteDistance} (707 here, and a missing dim 0 entry throws rather than returns),
 * derives a grid via {@code Math.floorDiv} plus {@code Platform.seedFromGrid}, rolls against
 * {@code meteoriteSpawnChance} (0.3), then checks existing spawns for the minimum distance —
 * and {@code World/AE2/spawndata} is empty, so that last check has nothing to reject against.</li>
 * </ul>
 *
 * <p>
 * Counting invocations distinguishes them in one run. A radius-60 walk covers ~7.4 cells of the
 * 707-block grid, so if {@code generate} is being called per chunk the count will be in the
 * thousands; zero means FML never calls it despite the registry listing it.
 *
 * <p>
 * Read-only, inert without the flag, and applied only when AE2 is installed.
 */
@Mixin(targets = "appeng.worldgen.MeteoriteWorldGen", remap = false)
public abstract class Ae2MeteoriteGenTraceMixin {

    private static final boolean gtnhdet$TRACE = Boolean.getBoolean("gtnhdet.meteortrace");

    private static int gtnhdet$calls;

    @Inject(method = "generate", at = @At("HEAD"), require = 1)
    private void gtnhdet$traceGenerate(Random random, int chunkX, int chunkZ, World world,
        IChunkProvider chunkGenerator, IChunkProvider chunkProvider, CallbackInfo ci) {
        if (!gtnhdet$TRACE) return;
        gtnhdet$calls++;
        // First few carry the detail; after that only the running total matters, logged sparsely so a
        // radius-60 walk does not add 15k lines.
        if (gtnhdet$calls <= 5 || gtnhdet$calls % 2000 == 0) {
            GtnhDeterminism.LOG.info(
                "[meteortrace] MeteoriteWorldGen.generate call #{} dim={} chunk={},{}",
                gtnhdet$calls,
                world.provider.dimensionId,
                chunkX,
                chunkZ);
        }
    }

    /**
     * The grid seeding, captured by passing the call through rather than by capturing locals.
     *
     * <p>
     * {@code generate} derives a cell with two {@code Math.floorDiv}s on the chunk coordinates and the
     * parsed {@code minMeteoriteDistance}, then seeds the shared {@code Random} from
     * (world seed, cell) so that every chunk in one 707-block cell reaches the SAME decision. This
     * logs the three longs it is handed. The argument order is not assumed — the three are reported
     * positionally, because which of them is the world seed and which the cell coordinates is exactly
     * the sort of thing that reads one way in bytecode and turns out to be another.
     */
    @Redirect(
        method = "generate",
        at = @At(value = "INVOKE", target = "Lappeng/util/Platform;seedFromGrid(Ljava/util/Random;JJJ)V"),
        require = 1)
    private void gtnhdet$traceSeedFromGrid(Random rand, long a, long b, long c) {
        appeng.util.Platform.seedFromGrid(rand, a, b, c);
        if (gtnhdet$TRACE && gtnhdet$grids < 10) {
            gtnhdet$grids++;
            GtnhDeterminism.LOG.info("[meteortrace] seedFromGrid(arg1={}, arg2={}, arg3={})", a, b, c);
        }
    }

    private static int gtnhdet$grids;

    private static int gtnhdet$rolls;

    private static double gtnhdet$rollMin = Double.MAX_VALUE;

    private static double gtnhdet$rollMax = -1;

    /**
     * The spawn-chance roll. {@code meteoriteSpawnChance} is {@code 0=0.3} in this pack, so a uniform
     * roll should clear it about three times in ten and a radius-60 walk should place ~2 meteorites.
     * Zero placements over 25 seeds says otherwise, and this is the value that decides it.
     *
     * <p>
     * Min and max are tracked as well as the first few values: a stuck seed shows up as a single
     * repeated number, an exhausted or mis-seeded stream as a range that never dips below the
     * threshold, and a healthy stream as a spread across [0,1) with the placer still never called —
     * which would move the fault past this point entirely.
     */
    @Redirect(method = "generate", at = @At(value = "INVOKE", target = "Ljava/util/Random;nextDouble()D"), require = 1)
    private double gtnhdet$traceRoll(Random rand) {
        final double v = rand.nextDouble();
        if (gtnhdet$TRACE) {
            gtnhdet$rolls++;
            if (v < gtnhdet$rollMin) gtnhdet$rollMin = v;
            if (v > gtnhdet$rollMax) gtnhdet$rollMax = v;
            if (gtnhdet$rolls <= 10 || gtnhdet$rolls % 2000 == 0) {
                GtnhDeterminism.LOG.info(
                    "[meteortrace] spawn-chance roll #{} = {} (min {} max {} so far)",
                    gtnhdet$rolls,
                    v,
                    gtnhdet$rollMin,
                    gtnhdet$rollMax);
            }
        }
        return v;
    }
}
