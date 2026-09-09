package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.WorldGenDungeons;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.TerrainOracle;

/**
 * Scan half of the vanilla dungeon determinism fix.
 *
 * <p>
 * {@code WorldGenDungeons.generate} decides whether a room exists by scanning a box that extends
 * {@code ±(l+1)} in X and {@code ±(i1+1)} in Z around the attempt, which crosses chunk borders. It
 * refuses unless the floor and ceiling are solid and the number of air openings in the wall ring is
 * in [1,5]. Every one of those reads hits the LIVE world, so the verdict depends on how much of the
 * neighbourhood had populated when this chunk ran.
 *
 * <p>
 * Measured on beta-3 seed -1636594104014467454, radius 30, rows vs spiral, with attempt coordinates
 * already pinned by {@link RwgDungeonAttemptMixin}: <b>6 rooms exist in one walk order and not the
 * other at an identical coordinate</b> — i.e. pure scan divergence, with the draw-shift path already
 * closed. Before the coordinate fix the split was 3 scan / 4 draw-shift, so the repo's long-standing
 * assumption that virginising the scan "would very likely not have changed this outcome" was wrong:
 * the two mechanisms were roughly equal and each leaves the other standing.
 *
 * <p>
 * Only the three reads that feed the verdict are redirected — the first loop's {@code getBlock} and
 * both {@code isAirBlock} calls, by ordinal. The construction phase that follows keeps reading and
 * writing the live world, so the room is still built against the terrain that is actually there; only
 * the decision to build becomes a function of the seed.
 */
@Mixin(WorldGenDungeons.class)
public abstract class WorldGenDungeonsScanMixin {

    /**
     * {@code -Dgtnhdet.dungeonscan=false} restores the live-terrain scan, so an A/B arm can be true
     * stock rather than half-fixed. Without it the "off" arm still virginises the verdict and any
     * count comparison measures the wrong thing.
     */
    private static final boolean gtnhdet$ENABLED = !"false".equals(System.getProperty("gtnhdet.dungeonscan"));

    /**
     * The room's half-extents are drawn from the SHARED populate rand at the top of generate:
     *
     * <pre>
     * 
     * int l = rand.nextInt(2) + 2;
     * int i1 = rand.nextInt(2) + 2;
     * </pre>
     *
     * They size the scan box, so pinning the attempt coordinate and virginising the blocks is not
     * enough — the box itself moved with the stream and the verdict moved with it. Missing this left a
     * residual that looked like scan nondeterminism: 2 rooms differing rows-vs-spiral on seed
     * -8622628182362538632 against a same-order noise floor of 0.
     *
     * <p>
     * Only these two draws are answered from a position-derived fork. Everything generate draws after
     * the gate — the spawner's mob, the chest roll counts — stays on the shared stream, so the number
     * of draws populate takes is unchanged and nothing downstream moves.
     */
    @Redirect(
        method = "generate",
        at = @At(value = "INVOKE", target = "Ljava/util/Random;nextInt(I)I", ordinal = 0),
        require = 1)
    private int gtnhdet$forkedExtentX(Random rand, int bound, World world, Random r2, int x, int y, int z) {
        return gtnhdet$ENABLED ? gtnhdet$extentFork(world, x, y, z).nextInt(bound) : rand.nextInt(bound);
    }

    @Redirect(
        method = "generate",
        at = @At(value = "INVOKE", target = "Ljava/util/Random;nextInt(I)I", ordinal = 1),
        require = 1)
    private int gtnhdet$forkedExtentZ(Random rand, int bound, World world, Random r2, int x, int y, int z) {
        if (!gtnhdet$ENABLED) return rand.nextInt(bound);
        final Random f = gtnhdet$extentFork(world, x, y, z);
        f.nextInt(bound); // consume the X extent so Z is the second draw of the same stream
        return f.nextInt(bound);
    }

    /** Position-derived stream for the room extents; same mixing constants as the other position forks. */
    private static Random gtnhdet$extentFork(World world, int x, int y, int z) {
        final long local = x * 3129871L + y * 116129781L + z;
        return new Random(world.getSeed() * 6364136223846793005L + local + 0x64756E67L);
    }

    @Redirect(
        method = "generate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;getBlock(III)Lnet/minecraft/block/Block;",
            ordinal = 0),
        require = 1)
    private Block gtnhdet$virginScanBlock(World world, int x, int y, int z) {
        return gtnhdet$ENABLED ? TerrainOracle.block(world, x, y, z) : world.getBlock(x, y, z);
    }

    @Redirect(
        method = "generate",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;isAirBlock(III)Z", ordinal = 0),
        require = 1)
    private boolean gtnhdet$virginScanAir0(World world, int x, int y, int z) {
        if (!gtnhdet$ENABLED) return world.isAirBlock(x, y, z);
        return TerrainOracle.block(world, x, y, z) == net.minecraft.init.Blocks.air;
    }

    @Redirect(
        method = "generate",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;isAirBlock(III)Z", ordinal = 1),
        require = 1)
    private boolean gtnhdet$virginScanAir1(World world, int x, int y, int z) {
        if (!gtnhdet$ENABLED) return world.isAirBlock(x, y, z);
        return TerrainOracle.block(world, x, y, z) == net.minecraft.init.Blocks.air;
    }
}
