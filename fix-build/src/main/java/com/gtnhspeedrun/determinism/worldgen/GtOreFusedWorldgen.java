package com.gtnhspeedrun.determinism.worldgen;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Random;

import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reflective access to the fused {@code WorldgenGTOreLayer.executeWorldgenChunkified} of GregTech
 * 5.09.52.31 .. 5.09.54.01.
 *
 * <p>
 * {@link GTWorldGenContainerDryRunMixin} has to call that method twice — once pinned and suppressed to decide the
 * vein, once unpinned to place it — but the jar this mod compiles against is 5.09.54.115, where the method has a
 * different signature and the {@code GTWorldgen} superclass no longer declares it at all. There is no compile-time
 * type that names the overload we need, so it is resolved by reflection instead. The alternative, pinning the
 * build to an older GregTech, would break every 5.09.54+ mixin.
 *
 * <p>
 * Cost is irrelevant here: this runs a handful of times per oreseed region, not per block, and every call places
 * or tests thousands of blocks.
 *
 * <p>
 * <b>Exceptions are rethrown unwrapped, deliberately.</b> {@code GTWorldgenerator.generateVein} wraps the call in
 * {@code catch (Exception)} and leaves {@code placementResult} at 0, which matches no case in its switch — so a
 * throw silently costs one candidate and selects a DIFFERENT vein, with no log line anywhere. If reflection
 * repackaged the original throwable as an {@code InvocationTargetException}, GregTech would still catch it, but
 * this mod would have changed which exception types reach that catch. Unwrapping keeps the failure mode
 * bit-identical to stock.
 */
public final class GtOreFusedWorldgen {

    private static final Logger LOG = LogManager.getLogger("gtnhdeterminism");

    private static volatile Method execute;
    private static volatile boolean resolved;

    private GtOreFusedWorldgen() {}

    /**
     * Resolve from the vein instance we were handed, never by name.
     *
     * <p>
     * {@code Class.forName("gregtech.common.WorldgenGTOreLayer")} would DEFINE that class, and defining it before
     * its mixins apply makes those mixins silently fail to apply — the hazard {@code ClassShape} exists to avoid.
     * Today this code only runs during worldgen, long after the class is loaded, so a name lookup would happen to
     * be safe; deriving the class from the instance means it stays safe no matter who calls this next.
     */
    private static Method resolve(Object layer) {
        try {
            final Method m = layer.getClass()
                .getMethod(
                    "executeWorldgenChunkified",
                    World.class,
                    Random.class,
                    String.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    IChunkProvider.class,
                    IChunkProvider.class);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            // Logged here and rethrown by handle(): this class is only reachable on the GregTech band that has
            // this exact overload, so failing to find it means the shape probe and reality disagree. The warning
            // carries the stack trace that the IllegalStateException cannot.
            LOG.warn(
                "GT ore-vein pin: could not resolve the fused executeWorldgenChunkified on {}; "
                    + "the F4d backport cannot run and worldgen will fail loudly",
                layer.getClass()
                    .getName(),
                t);
            return null;
        }
    }

    private static Method handle(Object layer) {
        if (!resolved) {
            synchronized (GtOreFusedWorldgen.class) {
                if (!resolved) {
                    execute = resolve(layer);
                    resolved = true;
                }
            }
        }
        if (execute == null) {
            // Unreachable by construction: this class is only loaded when GtOreShape probed the installed
            // bytecode and found the fused overload. Reaching it means the probe and reality disagree, which is
            // a bug worth a hard failure — the caller REPLACED GregTech's call, so there is no stock behaviour
            // left to fall back to and silently returning a made-up verdict would reroute ore veins.
            throw new IllegalStateException(
                "gtnhdeterminism: the fused executeWorldgenChunkified is missing from " + layer.getClass()
                    .getName()
                    + "; GtOreShape selected the 5.09.52.31-5.09.54.01 mixin set for a GregTech that does not "
                    + "match it. Please report this with your GregTech version.");
        }
        return execute;
    }

    /** Invoke the fused overload, propagating any throwable exactly as a direct call would. */
    public static int call(Object layer, World world, Random rng, String biome, int chunkX, int chunkZ, int seedX,
        int seedZ, IChunkProvider chunkGenerator, IChunkProvider chunkProvider) {
        try {
            return (Integer) handle(layer)
                .invoke(layer, world, rng, biome, chunkX, chunkZ, seedX, seedZ, chunkGenerator, chunkProvider);
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
