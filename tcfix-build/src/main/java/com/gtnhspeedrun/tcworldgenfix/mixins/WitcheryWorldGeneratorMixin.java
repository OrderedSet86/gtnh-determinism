package com.gtnhspeedrun.tcworldgenfix.mixins;

import java.util.Random;

import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import com.emoniph.witchery.util.Config;
import com.emoniph.witchery.worldgen.WitcheryWorldGenerator;

/**
 * Witchery's IWorldGenerator discards the seeded per-chunk Random that FML hands it and instead rolls all overworld
 * structure generation (covens, wicker men, shacks, goblin huts — including a Collections.shuffle of the generator
 * list) off {@code world.rand}, which is seeded from the clock at world load. Result: witchery surface structures are
 * different on every run of the same seed.
 *
 * Each structure has exactly one triggering chunk (the grid-cell gate in nonInRange is already world-seed derived), so
 * simply using the seeded per-chunk Random makes structure existence, type, and layout a pure function of the world
 * seed. (GTNH speedrun determinism audit, finding F2.)
 */

@Mixin(value = WitcheryWorldGenerator.class, remap = false)
public class WitcheryWorldGeneratorMixin {

    @Shadow
    private void generateOverworld(World world, Random random, int x, int z) {}

    @Shadow
    private void generateDreamworld(World world, Random random, int x, int z) {}

    /**
     * @author OrderedSet86 (GTNH speedrun determinism audit)
     * @reason Replace clock-seeded world.rand with the seeded per-chunk Random so worldgen is deterministic per seed.
     *         The original method only branches on dimension and delegates; this is a faithful copy with the RNG
     *         swapped.
     */
    @Overwrite
    public void generate(Random random, int chunkX, int chunkZ, World world, IChunkProvider chunkGenerator,
        IChunkProvider chunkProvider) {
        if (world.provider.dimensionId == 0) {
            this.generateOverworld(world, random, chunkX * 16, chunkZ * 16);
        } else if (Config.instance().worldGenTwilightForest && world.provider.getDimensionName()
            .equals("Twilight Forest")) {
                this.generateOverworld(world, random, chunkX * 16, chunkZ * 16);
            } else if (world.provider.dimensionId == Config.instance().dimensionDreamID) {
                this.generateDreamworld(world, random, chunkX * 16, chunkZ * 16);
            }
    }
}
