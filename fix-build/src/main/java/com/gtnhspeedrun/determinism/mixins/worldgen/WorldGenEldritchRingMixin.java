package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import thaumcraft.common.lib.world.WorldGenEldritchRing;

/**
 * Determinism fix for eldritch ring (obelisk) generation, v2 (GTNH speedrun determinism audit, F3b).
 *
 * Ring siting is decided up-front by {@code EldritchRingLottery} (seed-pure region grid at stock density; validity
 * evaluated on virgin terrain via TerrainOracle) — see {@code ThaumcraftWorldGeneratorMixin}. By the time
 * {@code generate} runs, the site has already won its region and passed the canonical validity test, so the two
 * stock in-method gates are neutralized here:
 *
 * <ul>
 * <li>{@code MazeHandler.mazesInRange} — the population-order race (up to 43x43-chunk window, first-generated ring
 * suppressed all later candidates → per-route obelisk lottery, observed at block -787,460 on seed 88888888). The
 * grid's 23-chunk minimum site spacing statically guarantees maze cell rects (≤ ±11 chunks) can never overlap,
 * which is the invariant this check existed to protect. → constant false.</li>
 * <li>{@code LocationIsValidSpawn} (5 live-terrain probes) — route-dependent when decoration or neighbors' state
 * differs; already evaluated deterministically on virgin terrain by the lottery. → constant true.</li>
 * </ul>
 *
 * The real maze map is still written (synchronously) after generation, so runtime consumers
 * (TileEldritchAltar portal opening) see consistent state. The maze/Outer Lands system itself is untouched.
 */
@Mixin(value = WorldGenEldritchRing.class, remap = false)
public abstract class WorldGenEldritchRingMixin {

    @Redirect(
        method = { "func_76484_a", "generate" },
        at = @At(value = "INVOKE", target = "Lthaumcraft/common/lib/world/dim/MazeHandler;mazesInRange(IIII)Z"))
    private boolean gtnhdet$noMazeRace(int chunkX, int chunkZ, int w, int h) {
        return false; // spacing statically guaranteed by EldritchRingLottery's region grid
    }

    @Redirect(
        method = { "func_76484_a", "generate" },
        at = @At(
            value = "INVOKE",
            target = "Lthaumcraft/common/lib/world/WorldGenEldritchRing;LocationIsValidSpawn(Lnet/minecraft/world/World;III)Z"))
    private boolean gtnhdet$skipLiveValidity(WorldGenEldritchRing self, World world, int x, int y, int z) {
        return true; // validity already established on virgin terrain by EldritchRingLottery
    }

    /**
     * The altar's spawner-type roll (nextInt(10): crimson-cult banners+spawner / crab spawner / plain) sits after a
     * live-condition-dependent COUNT of cosmetic draws (each ring column only rolls its obsidian/ancient-stone mix
     * where the existing block is replaceable), so the roll flipped with decoration state along the route (observed:
     * banners present in one walk order, absent in the other). Answer bound-10 rolls from a position-pure fork
     * instead; all other bounds (the cosmetic 1-in-4 obsidian rolls) pass through — their variance is contained to
     * the ring's cosmetic pattern.
     */
    @Redirect(
        method = { "func_76484_a", "generate" },
        at = @At(value = "INVOKE", target = "Ljava/util/Random;nextInt(I)I"))
    private int gtnhdet$positionPureSpawnerRoll(java.util.Random rand, int bound, World world, java.util.Random rand2,
        int i, int j, int k) {
        if (bound == 10) {
            return com.gtnhspeedrun.determinism.worldgen.TcForkUtil.fork(world, i >> 4, k >> 4, 12L)
                .nextInt(10);
        }
        return rand.nextInt(bound);
    }
}
