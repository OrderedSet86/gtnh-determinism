package com.gtnhspeedrun.tcworldgenfix;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
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
 * The regenerated chunk is DETACHED — never registered with the world, so no populate cascade. Structure map-gens
 * invoked inside provideChunk (villages etc.) are idempotent for already-known chunks (structureMap key check).
 * Cost is one terrain-noise pass per uncached chunk (~ms); a small LRU keeps repeat probes cheap.
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

    private TerrainOracle() {}

    public static synchronized Chunk virginChunk(World world, int cx, int cz) {
        if (cacheWorld != world) { // world switched (dimension/reload) — stale entries would alias coords
            CACHE.clear();
            cacheWorld = world;
        }
        final long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        Chunk c = CACHE.get(key);
        if (c == null) {
            final IChunkProvider generator = ((WorldServer) world).theChunkProviderServer.currentChunkProvider;
            c = generator.provideChunk(cx, cz);
            CACHE.put(key, c);
        }
        return c;
    }

    /** Virgin (pre-population) block at world coords; air outside the build height. */
    public static Block block(World world, int x, int y, int z) {
        if (y < 0 || y > 255) return Blocks.air;
        if (!(world instanceof WorldServer)) return world.getBlock(x, y, z); // client fallback: not expected in
                                                                             // worldgen
        return virginChunk(world, x >> 4, z >> 4).getBlock(x & 15, y, z & 15);
    }
}
