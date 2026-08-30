package com.gtnhspeedrun.determinism.mixins.worldgen;

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
import com.gtnhspeedrun.determinism.worldgen.WitcheryTrace;

/**
 * Two bugs made witchery surface structures nondeterministic (GTNH speedrun determinism audit, finding F2):
 *
 * 1. generate() rolled everything off clock-seeded world.rand (fixed here by passing FML's seeded per-chunk Random).
 * 2. generateOverworld() picks WHICH structure wins a gate cell by Collections.shuffle on the SHARED handler list,
 * mutated in place on every chunk — so the list order at the gate chunk depends on how many chunks generated before
 * it (the player's path). Same spot, different structure (wicker man vs shack vs coven vs goblin hut) per approach.
 * Fixed by shuffling a sorted COPY with the seeded per-chunk Random: the winner is a pure function of the seed.
 *
 * <p>
 * Verified route-stable 2026-08-29 with {@code -Dgtnhdet.witchtrace=true}: across 3 seeds and both route arms,
 * every gate cell's biome verdict, shuffled order and per-handler outcome is identical, and all 9 placements agree
 * in cell and structure type. A persisted-world block diff cannot show this — Witchery builds from vanilla blocks —
 * which is why the trace exists. See results/2026-08-29-witchery-placement-trace.
 *
 * <p>
 * Known residual: {@code nonInRange} consults {@code structuresList}, whose contents depend on what was placed
 * earlier in the run, so cell verdicts are order-dependent in principle. It did not fire on those seeds, where
 * placements are sparse; a denser seed is the case to test next.
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
        // Restructured from one compound condition into named steps so -Dgtnhdet.witchtrace can record each
        // decision. Control flow is unchanged: nonInRange is still evaluated first and still short-circuits
        // generate(), so no handler is asked to generate for a cell it is out of range for and no path takes an
        // extra draw.
        final int biomeID = world.getBiomeGenForCoords(x + this.midX, z + this.midZ).biomeID;
        if (BiomeManager.DISALLOWED_BIOMES.contains(biomeID)) {
            WitcheryTrace.cell(world, x, z, "biome=" + biomeID + " REJECTED");
            return;
        }
        final List<IWorldGenHandler> order = new ArrayList<>(this.generators);
        order.sort(
            Comparator.comparing(
                g -> g.getClass()
                    .getName()));
        Collections.shuffle(order, random);

        // Diagnostic: what a fresh Random(FML chunk seed) WOULD shuffle to. If this ever differs from the real
        // order, the Random arriving here is not fresh, and any worldless prediction of the order is wrong.
        final String predicted = WitcheryTrace.TRACE
            ? WitcheryTrace.predictOrder(this.generators, world.getSeed(), x >> 4, z >> 4)
            : null;
        final StringBuilder trace = WitcheryTrace.TRACE ? new StringBuilder("biome=" + biomeID + " order=[") : null;
        if (trace != null) {
            for (int i = 0; i < order.size(); i++) {
                if (i > 0) trace.append(' ');
                trace.append(WitcheryTrace.name(order.get(i)));
            }
            trace.append("] tried=[");
        }
        String winner = "none";
        for (IWorldGenHandler generator : order) {
            final boolean inRange = this.nonInRange(world, x, z, generator.getRange());
            final boolean placed = inRange && generator.generate(world, random, x, z);
            if (trace != null) {
                trace.append(WitcheryTrace.name(generator))
                    .append(inRange ? ":inrange" : ":outofrange")
                    .append(placed ? ":PLACED " : ":no ");
            }
            if (!placed) continue;
            this.structuresList.add(new ChunkCoordIntPair(x, z));
            winner = WitcheryTrace.name(generator);
            break;
        }
        if (trace != null) {
            WitcheryTrace.cell(
                world,
                x,
                z,
                trace.append("] winner=")
                    .append(winner)
                    .append(" predorder=[")
                    .append(predicted)
                    .append("]")
                    .toString());
        }
    }
}
