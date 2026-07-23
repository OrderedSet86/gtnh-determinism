package com.gtnhspeedrun.tcworldgenfix.mixins;

import java.util.HashMap;
import java.util.Random;

import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.MapGenScatteredFeature;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import thaumcraft.common.config.Config;
import thaumcraft.common.lib.world.ThaumcraftWorldGenerator;
import thaumcraft.common.lib.world.WorldGenEldritchRing;
import thaumcraft.common.lib.world.WorldGenHilltopStones;
import thaumcraft.common.lib.world.WorldGenMound;
import thaumcraft.common.lib.world.dim.MazeThread;

/**
 * Determinism fix for Thaumcraft 4.2.3.5 overworld generation (GTNH speedrun determinism audit, finding F3).
 *
 * Stock TC feeds every surface feature (silverwood/greatwood trees, ores, wild nodes, totems, barrows, eldritch
 * rings) from ONE shared per-chunk Random, with draws gated on live terrain reads (getHeightValue/getBlock at
 * chunk-border columns that neighboring chunks' decoration may or may not have modified yet). One flipped gate shifts
 * every later draw — so node types, positions, totems and barrows differ run-to-run and route-to-route on the same
 * seed. It also claims scattered-feature bonus nodes via a first-chunk-wins in-memory map, and generates eldritch
 * ring mazes on a background thread that races the server thread.
 *
 * This mixin rebuilds the two orchestrating methods so that:
 * <ul>
 * <li>every feature draws from its OWN Random, forked deterministically from (worldSeed, chunk coords, feature id) —
 * a terrain-gated feature can no longer skew its siblings;</li>
 * <li>the scattered-feature bonus node is claimed by the chunk that CONTAINS the feature, not by whichever chunk
 * happens to populate first;</li>
 * <li>the eldritch ring maze is generated synchronously with a seeded RNG (no thread race).</li>
 * </ul>
 * Residual (documented, not fixed here): features still read live terrain to pick Y/validity, so block-level variance
 * near chunk borders can remain; barrow/ring INTERIOR loot is fixed separately in {@link WorldGenMoundMixin}.
 * The nether path (generateNether) is untouched in this draft.
 */
@Mixin(value = ThaumcraftWorldGenerator.class, remap = false)
public abstract class ThaumcraftWorldGeneratorMixin {

    @Shadow
    HashMap<Integer, Boolean> structureNode;

    @Shadow
    private boolean generateTotem(World world, Random random, int chunkX, int chunkZ, boolean auraGen, boolean newGen) {
        return false;
    }

    @Shadow
    private boolean generateWildNodes(World world, Random random, int chunkX, int chunkZ, boolean auraGen,
        boolean newGen) {
        return false;
    }

    @Shadow
    private void generateOres(World world, Random random, int chunkX, int chunkZ, boolean newGen) {}

    /**
     * Deterministic per-(chunk, feature) RNG — vanilla-style coordinate seeding plus a feature salt.
     * Delegates to {@link com.gtnhspeedrun.tcworldgenfix.TcForkUtil} so EldritchRingLottery can replay the
     * exact same streams for remote chunks.
     */
    @Unique
    private static Random tcfix$fork(World world, int chunkX, int chunkZ, long salt) {
        return com.gtnhspeedrun.tcworldgenfix.TcForkUtil.fork(world, chunkX, chunkZ, salt);
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason Give every surface feature an independent, deterministically forked RNG; claim scattered-feature bonus
     *         nodes by position instead of first-populated-chunk; run maze generation synchronously. Faithful
     *         restructuring of the original control flow otherwise.
     */
    @Overwrite
    private void generateSurface(World world, Random random, int chunkX, int chunkZ, boolean newGen) {
        boolean auraGen = false;
        final int blacklist = ThaumcraftWorldGenerator.getDimBlacklist(world.provider.dimensionId);
        final boolean flat = world.getWorldInfo()
            .getTerrainType()
            .getWorldTypeName()
            .startsWith("flat");

        if (blacklist == -1 && Config.genTrees && !flat && (newGen || Config.regenTrees)) {
            this.generateVegetation(world, tcfix$fork(world, chunkX, chunkZ, 1), chunkX, chunkZ, newGen);
        }

        if (blacklist != 0 && blacklist != 2) {
            this.generateOres(world, tcfix$fork(world, chunkX, chunkZ, 2), chunkX, chunkZ, newGen);
        }

        if (blacklist != 0 && blacklist != 2 && Config.genAura && (newGen || Config.regenAura)) {
            final ChunkPosition feature = new MapGenScatteredFeature().func_151545_a(
                world,
                chunkX * 16 + 8,
                world.getHeightValue(chunkX * 16 + 8, chunkZ * 16 + 8),
                chunkZ * 16 + 8);
            // Deterministic claim: only the chunk that contains the feature places its bonus node.
            if (feature != null && feature.chunkPosX >> 4 == chunkX && feature.chunkPosZ >> 4 == chunkZ) {
                auraGen = true;
                this.structureNode.put(feature.hashCode(), Boolean.TRUE);
                ThaumcraftWorldGenerator.createRandomNodeAt(
                    world,
                    feature.chunkPosX,
                    world.getHeightValue(feature.chunkPosX, feature.chunkPosZ) + 3,
                    feature.chunkPosZ,
                    tcfix$fork(world, chunkX, chunkZ, 3),
                    false,
                    false,
                    false);
            }

            auraGen = this
                .generateWildNodes(world, tcfix$fork(world, chunkX, chunkZ, 4), chunkX, chunkZ, auraGen, newGen);
        }

        if (blacklist == -1 && Config.genStructure
            && world.provider.dimensionId == 0
            && !flat
            && (newGen || Config.regenStructure)) {
            final Random srand = tcfix$fork(world, chunkX, chunkZ, 5);
            final int randPosX = chunkX * 16 + srand.nextInt(16);
            final int randPosZ = chunkZ * 16 + srand.nextInt(16);
            int randPosY = world.getHeightValue(randPosX, randPosZ) - 9;
            if (randPosY < world.getActualHeight()) {
                world.getChunkFromBlockCoords(randPosX, randPosZ);
                int[] tcfix$ringSite;
                if (srand.nextInt(150) == 0) {
                    final WorldGenMound mound = new WorldGenMound();
                    if (mound.generate(world, srand, randPosX, randPosY, randPosZ)) {
                        auraGen = true;
                        srand.nextInt(200); // preserved draw (unused 'value' in original)
                        ThaumcraftWorldGenerator.createRandomNodeAt(
                            world,
                            randPosX + 9,
                            randPosY + 8,
                            randPosZ + 9,
                            srand,
                            false,
                            true,
                            false);
                    }
                } else if ((tcfix$ringSite = com.gtnhspeedrun.tcworldgenfix.EldritchRingLottery
                    .designatedSite(world, world.getSeed(), chunkX, chunkZ)) != null) {
                        // v2 (0.3): region-grid siting at stock density — see EldritchRingLottery. Replaces the
                        // stock per-chunk 1/66 roll + first-populated-wins maze race (order-dependent, 0.2 note).
                        final WorldGenEldritchRing stonering = new WorldGenEldritchRing();
                        final int rx = chunkX * 16 + tcfix$ringSite[0];
                        final int rz = chunkZ * 16 + tcfix$ringSite[1];
                        final int ry = com.gtnhspeedrun.tcworldgenfix.EldritchRingLottery.surfaceY(world, rx, rz);
                        final int w = tcfix$ringSite[2];
                        final int h = tcfix$ringSite[3];
                        stonering.chunkX = chunkX;
                        stonering.chunkZ = chunkZ;
                        stonering.width = w;
                        stonering.height = h;
                        // Own forks: the ring's cosmetic draws consume a live-condition-dependent COUNT of rolls,
                        // so nothing downstream may share its stream (node and maze each get their own fork).
                        if (ry > 0 && stonering.generate(world, tcfix$fork(world, chunkX, chunkZ, 9), rx, ry, rz)) {
                            auraGen = true;
                            ThaumcraftWorldGenerator.createRandomNodeAt(
                                world,
                                rx,
                                ry + 2,
                                rz,
                                tcfix$fork(world, chunkX, chunkZ, 10),
                                false,
                                true,
                                false);
                            // Synchronous, seeded — original spawned a background thread here.
                            new MazeThread(chunkX, chunkZ, w, h, tcfix$fork(world, chunkX, chunkZ, 11).nextLong())
                                .run();
                        }
                    } else if (srand.nextInt(40) == 0) {
                        randPosY = randPosY + 9;
                        final WorldGenHilltopStones hilltopStones = new WorldGenHilltopStones();
                        if (hilltopStones.generate(world, srand, randPosX, randPosY, randPosZ)) {
                            auraGen = true;
                            ThaumcraftWorldGenerator
                                .createRandomNodeAt(world, randPosX, randPosY + 5, randPosZ, srand, false, true, false);
                        }
                    }
            }

            this.generateTotem(world, tcfix$fork(world, chunkX, chunkZ, 6), chunkX, chunkZ, auraGen, newGen);
        }
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason Independent forked RNG per vegetation feature (silverwood, greatwood, flowers) so a terrain-gated tree
     *         cannot shift the draws of the next feature. Probabilities unchanged.
     */
    @Overwrite
    private void generateVegetation(World world, Random random, int chunkX, int chunkZ, boolean newGen) {
        final net.minecraft.world.biome.BiomeGenBase bgb = world.getBiomeGenForCoords(chunkX * 16 + 8, chunkZ * 16 + 8);
        if (ThaumcraftWorldGenerator.getBiomeBlacklist(bgb.biomeID) == -1) {
            final Random silver = tcfix$fork(world, chunkX, chunkZ, 11);
            if (silver.nextInt(60) == 3) {
                ThaumcraftWorldGenerator.generateSilverwood(world, silver, chunkX, chunkZ);
            }

            final Random great = tcfix$fork(world, chunkX, chunkZ, 12);
            if (great.nextInt(25) == 7) {
                ThaumcraftWorldGenerator.generateGreatwood(world, great, chunkX, chunkZ);
            }

            final Random flower = tcfix$fork(world, chunkX, chunkZ, 13);
            final int randPosX = chunkX * 16 + flower.nextInt(16);
            final int randPosZ = chunkZ * 16 + flower.nextInt(16);
            final int randPosY = world.getHeightValue(randPosX, randPosZ);
            if (randPosY <= world.getActualHeight()) {
                if (world.getBiomeGenForCoords(randPosX, randPosZ).topBlock == net.minecraft.init.Blocks.sand
                    && world.getBiomeGenForCoords(randPosX, randPosZ).temperature > 1.0F
                    && flower.nextInt(30) == 0) {
                    ThaumcraftWorldGenerator.generateFlowers(world, flower, randPosX, randPosY, randPosZ, 3);
                }
            }
        }
    }
}
