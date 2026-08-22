package com.gtnhspeedrun.mixins.worldgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import com.emoniph.witchery.util.Config;
import com.emoniph.witchery.worldgen.BiomeManager;
import com.emoniph.witchery.worldgen.IWorldGenHandler;
import com.emoniph.witchery.worldgen.WitcheryWorldGenerator;

/**
 * Two bugs made witchery surface structures nondeterministic (GTNH speedrun determinism audit, finding F2):
 *
 * 1. generate() rolled everything off clock-seeded world.rand (fixed here by passing FML's seeded per-chunk Random).
 * 2. generateOverworld() picks WHICH structure wins a gate cell by Collections.shuffle on the SHARED handler list,
 * mutated in place on every chunk — so the list order at the gate chunk depends on how many chunks generated before
 * it (the player's path). Same spot, different structure (wicker man vs shack vs coven vs goblin hut) per approach.
 * Fixed by shuffling a sorted COPY with the seeded per-chunk Random: the winner is a pure function of the seed.
 */
@Mixin(value = WitcheryWorldGenerator.class, remap = false)
public abstract class WitcheryWorldGeneratorMixin {

    @Shadow
    private LinkedList<ChunkCoordIntPair> structuresList;

    @Shadow
    @Final
    private List<IWorldGenHandler> generators;

    @Shadow
    private int midX;

    @Shadow
    private int midZ;

    @Shadow
    protected abstract boolean nonInRange(World worldObj, int x, int z, int range);

    @Shadow
    private void generateDreamworld(World world, Random random, int chunkX, int chunkZ) {}

    /**
     * @author OrderedSet86 (GTNH speedrun determinism audit)
     * @reason Use the seeded per-chunk Random instead of clock-seeded world.rand. Original only branches on dimension
     *         and delegates.
     */
    @Overwrite
    public void generate(Random random, int chunkX, int chunkZ, World world, IChunkProvider chunkGenerator,
        IChunkProvider chunkProvider) {
        if (world.provider.dimensionId == 0) {
            this.gtnhdet$generateOverworld(world, random, chunkX * 16, chunkZ * 16);
        } else if (Config.instance().worldGenTwilightForest && world.provider.getDimensionName()
            .equals("Twilight Forest")) {
                this.gtnhdet$generateOverworld(world, random, chunkX * 16, chunkZ * 16);
            } else if (world.provider.dimensionId == Config.instance().dimensionDreamID) {
                this.generateDreamworld(world, random, chunkX * 16, chunkZ * 16);
            }
    }

    /**
     * Faithful copy of generateOverworld, except the structure choice shuffles a deterministically-sorted COPY of the
     * handler list with the seeded Random instead of mutating the shared list.
     */
    private void gtnhdet$generateOverworld(World world, Random random, int x, int z) {
        if (!BiomeManager.DISALLOWED_BIOMES
            .contains(world.getBiomeGenForCoords(x + this.midX, z + this.midZ).biomeID)) {
            final List<IWorldGenHandler> order = new ArrayList<>(this.generators);
            order.sort(
                Comparator.comparing(
                    g -> g.getClass()
                        .getName()));
            Collections.shuffle(order, random);
            for (IWorldGenHandler generator : order) {
                if (!this.nonInRange(world, x, z, generator.getRange()) || !generator.generate(world, random, x, z))
                    continue;
                this.structuresList.add(new ChunkCoordIntPair(x, z));
                break;
            }
        }
    }
}
