package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.HilltopSiting;

import thaumcraft.common.lib.world.WorldGenHilltopStones;

/**
 * Determinism fix for Thaumcraft hilltop stone circle placement.
 *
 * <p>
 * {@code func_76484_a} gates construction on five {@code LocationIsValidSpawn} probes — at
 * (x-2,z-2), (x,z), (x+2,z), (x+2,z+2), (x,z+2) — each of which reads LIVE blocks via
 * {@code World.getBlock}. Live terrain at population time reflects however much of the neighbourhood
 * has already been decorated, so whether a circle existed depended on chunk load order rather than on
 * the seed. Measured on beta-3 seed -1636594104014467454, radius 60: 12 circles walking {@code rows}
 * against 14 walking {@code spiral}; one circle unique to {@code rows}, three unique to
 * {@code spiral}.
 *
 * <p>
 * Every probe is redirected to {@link HilltopSiting#virginValid}, which is the same test evaluated
 * against {@link com.gtnhspeedrun.determinism.worldgen.TerrainOracle}'s virgin chunks. Same predicate,
 * same offsets, same y-floor; only the terrain it reads changes, from "whatever is there now" to "what
 * the seed says is there". This mirrors {@link WorldGenEldritchRingMixin}, which neutralises the
 * identical live check for eldritch rings.
 *
 * <p>
 * The caller in {@code ThaumcraftWorldGeneratorMixin} additionally supplies a virgin anchor Y via
 * {@link HilltopSiting#anchorY} rather than {@code world.getHeightValue}, because the anchor is the
 * other live read in the same decision. Both halves are needed: a virgin predicate evaluated at a
 * live anchor is still load-order-dependent.
 *
 * <p>
 * This moves circle placement relative to the previous jar on every seed. That is the point — the old
 * placement was not a function of the seed alone, so it was not reproducible to move away from.
 */
@Mixin(value = WorldGenHilltopStones.class, remap = false)
public abstract class WorldGenHilltopStonesMixin {

    @Shadow
    protected abstract net.minecraft.block.Block[] GetValidSpawnBlocks();

    @Redirect(
        method = { "func_76484_a", "generate" },
        at = @At(
            value = "INVOKE",
            target = "Lthaumcraft/common/lib/world/WorldGenHilltopStones;LocationIsValidSpawn(Lnet/minecraft/world/World;III)Z"))
    private boolean gtnhdet$virginValidSpawn(WorldGenHilltopStones self, World world, int x, int y, int z) {
        // The lever calls the REAL method, not a reimplementation of it. An earlier version returned
        // HilltopSiting.liveValid here and read 1 circle where the stock jar built 12 — the lever was
        // measuring my transcription rather than stock, which is exactly the error it exists to catch.
        // This call is not itself redirected: @Redirect rewrites the call site inside func_76484_a, and
        // this handler is not that method.
        // Capture the pack's real list before evaluating: GTNH patches it, and a transcribed list is
        // exactly the drift this whole exercise was caused by.
        HilltopSiting.captureValidBases(GetValidSpawnBlocks());
        if (!HilltopSiting.ENABLED) return self.LocationIsValidSpawn(world, x, y, z);
        if (gtnhdet$DIFF) {
            final boolean stock = self.LocationIsValidSpawn(world, x, y, z);
            final boolean mine = HilltopSiting.liveValid(world, x, y, z);
            if (stock != mine) {
                com.gtnhspeedrun.determinism.GtnhDeterminism.LOG.info(
                    "[hilltopdiff] x={} y={} z={} stock={} mine={} why={}",
                    x,
                    y,
                    z,
                    stock,
                    mine,
                    HilltopSiting.reason(world, x, y, z, false));
            }
        }
        return HilltopSiting.virginValid(world, x, y, z);
    }

    /** {@code -Dgtnhdet.hilltopdiff=true}: log every column where the transcription disagrees with stock. */
    private static final boolean gtnhdet$DIFF = Boolean.getBoolean("gtnhdet.hilltopdiff");
}
