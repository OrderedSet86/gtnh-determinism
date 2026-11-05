package com.gtnhspeedrun.determinism.worldgen;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

/**
 * Order-independent terrain reads: regenerates a chunk's VIRGIN (terrain-stage, pre-population) blocks straight
 * from the world's chunk generator. Terrain generation is a pure function of (seed, chunk), so unlike live world
 * reads these never depend on which neighboring chunks have been populated — the key to making structure validity
 * tests route-independent (Roguelike dungeon placement, eldritch ring siting).
 *
 * The regenerated chunk is DETACHED — never registered with the world, so no populate cascade. Its provider is
 * created by the actual world's WorldProvider, preserving dimensions, world types and generator options. It is
 * private to the oracle: RWG2 keeps mutable terrain arrays as well as RNGs on its provider, so swapping only the
 * live provider's Random fields cannot isolate a nested terrain read. A small LRU bounds detached chunks.
 */
public final class TerrainOracle {

    private static final int CACHE_MAX = 256;
    private static final LinkedHashMap<Long, Chunk> CACHE = new LinkedHashMap<Long, Chunk>(64, 0.75f, true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Chunk> eldest) {
            return size() > CACHE_MAX;
        }
    };
    private static World cacheWorld;
    private static IChunkProvider virginGenerator;

    private TerrainOracle() {}

    public static synchronized Chunk virginChunk(World world, int cx, int cz) {
        if (cacheWorld != world) { // world switched (dimension/reload) — stale entries would alias coords
            CACHE.clear();
            cacheWorld = world;
            virginGenerator = world.provider.createChunkGenerator();
        }
        final long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        Chunk c = CACHE.get(key);
        if (c == null) {
            c = virginGenerator.provideChunk(cx, cz);
            CACHE.put(key, c);
        }
        return c;
    }

    /** Vanilla/Forge top-solid predicate, evaluated on the same detached chunk as the block oracle. */
    public static int topSolidOrLiquidBlock(World world, int x, int z) {
        final Chunk chunk = virginChunk(world, x >> 4, z >> 4);
        for (int y = chunk.getTopFilledSegment() + 15; y > 0; y--) {
            final Block block = chunk.getBlock(x & 15, y, z & 15);
            if (block.getMaterial().blocksMovement() && block.getMaterial() != Material.leaves
                && !block.isFoliage(world, x, y, z)) return y + 1;
        }
        return -1;
    }

    /** Virgin (pre-population) block at world coords; air outside the build height. */
    public static Block block(World world, int x, int y, int z) {
        if (y < 0 || y > 255) return Blocks.air;
        if (!(world instanceof WorldServer)) return world.getBlock(x, y, z); // client fallback: not expected in
                                                                             // worldgen
        return virginChunk(world, x >> 4, z >> 4).getBlock(x & 15, y, z & 15);
    }

    /**
     * Virgin (pre-population) block metadata at world coords; 0 outside the build height. Callers that need to
     * identify a block variant — GT's stone types, for one — must read this from the same virgin chunk as
     * {@link #block}, or the pair can straddle a cache eviction and describe two different worlds.
     */
    public static int meta(World world, int x, int y, int z) {
        if (y < 0 || y > 255) return 0;
        if (!(world instanceof WorldServer)) return world.getBlockMetadata(x, y, z);
        return virginChunk(world, x >> 4, z >> 4).getBlockMetadata(x & 15, y, z & 15);
    }
}
