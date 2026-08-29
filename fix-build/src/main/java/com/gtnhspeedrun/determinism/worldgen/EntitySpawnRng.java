package com.gtnhspeedrun.determinism.worldgen;

import java.util.Random;

import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;

/**
 * A seed-pure Random for decisions an entity makes once, at spawn.
 * <p>
 * Several vanilla mobs finish their own setup in {@code onSpawnWithEgg} by drawing from {@code worldObj.rand} — a
 * bare {@code new Random()} on {@link net.minecraft.world.World}, clock-seeded, and left alone by BugTorch
 * ({@code replaceRandomInWorld=false} in both pack lines). Sheep fleece colour is the one that matters for routing.
 * Those draws happen after {@code setLocationAndAngles}, so the entity's position is already final and can seed the
 * roll instead.
 * <p>
 * Position seeding rather than a threaded-through parameter is deliberate: {@code onSpawnWithEgg} never receives
 * the populate Random, and forking from (world seed, block position) makes the result independent of the route, the
 * launch, and the spawn path — worldgen, a later natural spawn and a spawner all agree.
 * <p>
 * Known artifact, measured: two mobs sharing a block get the same roll, where stock would roll independently.
 * On seed 42 that was 5 of 95 worldgen horses — 90 distinct blocks, 5 blocks holding a pair — so roughly 5%.
 * Mixing in an entity id or a raw counter would reintroduce exactly the spawn-order dependence this removes. The
 * clean way to close it, now that {@code SpawnerAnimals.performWorldGenSpawning} draws entirely from the
 * populate-seeded Random, is to thread that method's own per-call spawn ordinal in as a tie-breaker: it is
 * deterministic for the same reason the positions are. Not done.
 */
public final class EntitySpawnRng {

    private EntitySpawnRng() {}

    /** Fork for {@code e} at its current position. Multipliers are the vanilla chunk-decoration constants. */
    public static Random forEntity(Entity e) {
        final long worldSeed = e.worldObj == null ? 0L : e.worldObj.getSeed();
        final long x = MathHelper.floor_double(e.posX);
        final long y = MathHelper.floor_double(e.posY);
        final long z = MathHelper.floor_double(e.posZ);
        long h = worldSeed;
        h = h * 6364136223846793005L + x * 341873128712L;
        h = h * 6364136223846793005L + z * 132897987541L;
        h = h * 6364136223846793005L + y;
        return new Random(h);
    }
}
