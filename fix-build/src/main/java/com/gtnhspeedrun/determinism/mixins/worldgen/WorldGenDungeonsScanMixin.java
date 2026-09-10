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

    // ------------------------------------------------------------------ overlapping rooms

    /** {@code -Dgtnhdet.dungeonmerge=false} restores stock's destroy-on-overlap. */
    private static final boolean gtnhdet$MERGE = !"false".equals(System.getProperty("gtnhdet.dungeonmerge"));

    /**
     * A chest or spawner already standing where this room wants to write.
     *
     * <p>
     * Rooms overlap at about 0.7 pairs per 100 rooms, and that rate is stock — measured with
     * {@code -Dgtnhdet.dungeonscan=false}, 15 overlapping pairs against 15, so the virginised scan did
     * not create them. What is not stock is the outcome: stock's second room carves its interior
     * straight through the first room's chest, and which room is second depends on the order their
     * chunks populated. Measured: 15 overlapping pairs against 13 chests that exist in one walk order
     * and not the other, near enough 1:1.
     *
     * <p>
     * Refusing to overwrite the loot makes the result order-independent without any knowledge of the
     * neighbouring room — whichever runs second simply leaves the other's chest and spawner alone, and
     * the union is the same either way. The rooms still merge into one space, which is the intended
     * outcome; they just both keep their contents.
     */
    private static boolean gtnhdet$isLoot(World world, int x, int y, int z) {
        final net.minecraft.block.Block b = world.getBlock(x, y, z);
        return b == net.minecraft.init.Blocks.chest || b == net.minecraft.init.Blocks.mob_spawner;
    }

    @Redirect(
        method = "generate",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;setBlockToAir(III)Z", ordinal = 0),
        require = 1)
    private boolean gtnhdet$carveKeepsLoot0(World world, int x, int y, int z) {
        if (gtnhdet$MERGE && gtnhdet$isLoot(world, x, y, z)) return false;
        return world.setBlockToAir(x, y, z);
    }

    @Redirect(
        method = "generate",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;setBlockToAir(III)Z", ordinal = 1),
        require = 1)
    private boolean gtnhdet$carveKeepsLoot1(World world, int x, int y, int z) {
        if (gtnhdet$MERGE && gtnhdet$isLoot(world, x, y, z)) return false;
        return world.setBlockToAir(x, y, z);
    }

    /** Wall placement, mossy and plain. A wall must not brick over the other room's loot either. */
    @Redirect(
        method = "generate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;setBlock(IIILnet/minecraft/block/Block;II)Z",
            ordinal = 0),
        require = 1)
    private boolean gtnhdet$wallKeepsLoot0(World world, int x, int y, int z, net.minecraft.block.Block b, int meta,
        int flags) {
        if (gtnhdet$MERGE && gtnhdet$isLoot(world, x, y, z)) return false;
        return world.setBlock(x, y, z, b, meta, flags);
    }

    @Redirect(
        method = "generate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;setBlock(IIILnet/minecraft/block/Block;II)Z",
            ordinal = 1),
        require = 1)
    private boolean gtnhdet$wallKeepsLoot1(World world, int x, int y, int z, net.minecraft.block.Block b, int meta,
        int flags) {
        if (gtnhdet$MERGE && gtnhdet$isLoot(world, x, y, z)) return false;
        return world.setBlock(x, y, z, b, meta, flags);
    }

    // ------------------------------------------------------------------ room construction

    // -Dgtnhdet.dungeonbuild was a lever on virginising the two construction reads. It is gone rather
    // than left inert: the reads are back on the live world deliberately (see below), so a flag that
    // no longer changes anything would only invite someone to set it and believe it did.

    /**
     * The two reads that decide what each cell of the room becomes:
     *
     * <pre>
     * if (interior) setBlockToAir(k1, l1, i2);
     * else if (l1 &gt;= 0 &amp;&amp; !getBlock(k1, l1 - 1, i2).isSolid()) setBlockToAir(k1, l1, i2); // ordinal 1
     * else if (getBlock(k1, l1, i2).isSolid()) setBlock(cobblestone); // ordinal 2
     * </pre>
     *
     * <p>
     * Virginising them is coherent because neither ever reads a cell this loop has already written.
     * The loop runs {@code k1} outermost, then {@code l1} <em>descending</em>, then {@code i2}: the
     * current cell has not been touched yet, and {@code l1 - 1} is processed later because
     * {@code l1} counts down. So both reads see the world as it was before the room started building,
     * and swapping "live pre-room" for "virgin pre-room" changes the answer without changing what the
     * question means.
     *
     * <p>
     * Why it matters for chests: the chest loop's adjacent-solid count reads the walls this loop
     * places, and a wall is only placed where the block was already solid. A cave or a structure that
     * generated in one walk order and not the other therefore changed whether a wall existed, which
     * flipped the count at an identical candidate position. Measured before this: 9 rooms and 18 chest
     * positions still differed after the chest draws were pinned.
     *
     * <p>
     * This does NOT make the finished room byte-identical. Where virgin says "not solid" the generator
     * places nothing, leaving whatever the live world holds — so a live block that virgin does not
     * know about survives in place. Only the room's own decisions become seed-pure.
     */
    // NOT REDIRECTED, and the reason is the opposite of the obvious one.
    //
    // These two reads were briefly answered from TerrainOracle. That is wrong where two rooms overlap,
    // which is stock behaviour at ~0.7 pairs per 100 rooms. With virgin reads, room B places a wall
    // wherever VIRGIN terrain was solid, including cells room A has already carved to air:
    //
    // A first : A carves C to air, B then walls it (virgin says solid) -> wall
    // B first : B walls C, A then carves it -> air
    //
    // Order-dependent, and it bricks a wall through the middle of a merged room. The live read gets it
    // right for free, because carved air is not solid so no wall goes up, and both orders converge:
    //
    // A first : A carves C, B reads air -> no wall -> air
    // B first : B walls C, A carves it -> air
    //
    // The route-dependence that virginising these was meant to fix — 9 rooms whose chest acceptance
    // flipped with the wall state — is closed instead by answering the chest loop's reads from the
    // room's own model below, which needs no neighbour knowledge at all.

    // ------------------------------------------------------------------ chest placement

    /**
     * {@code -Dgtnhdet.dungeonchest=false} restores stock chest placement. Separate from
     * {@code dungeonscan} on purpose: a lever that moves two behaviours at once cannot attribute
     * whichever difference it produces.
     */
    private static final boolean gtnhdet$CHEST = !"false".equals(System.getProperty("gtnhdet.dungeonchest"));

    /**
     * How many chest-placement draws this room has taken, keyed by the room anchor.
     *
     * <p>
     * Keyed rather than held in one slot, and reset at the HEAD of {@code generate} rather than
     * inferred: the wall-building loop calls {@code setBlockToAir}/{@code setBlock} across chunk
     * borders, which can trigger a neighbour's population and therefore a nested {@code generate} on
     * this same thread before the outer room reaches its chest loop. A single slot would let the inner
     * room's draws advance the outer room's index, and how often that happens depends on walk order —
     * which is the exact failure {@code RwgDungeonAttemptMixin} documents for the attempt counter and
     * {@code ChestFillContext} repeated for the chest fill index.
     */
    private static final ThreadLocal<java.util.HashMap<Long, Integer>> gtnhdet$chestDraws = ThreadLocal
        .withInitial(java.util.HashMap::new);

    /**
     * Exact packing, not a hash. A mixed key would be smaller but two rooms colliding would share a
     * draw counter, and the only case where that bites — an outer room whose wall loop triggered a
     * nested population — is exactly the case this map exists to keep separate. 26 bits each for X and
     * Z covers +-33.5M against the world's +-30M limit, 12 for Y against a 256 height.
     */
    private static long gtnhdet$roomKey(int x, int y, int z) {
        return ((x & 0x3FFFFFFL) << 38) | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "generate", at = @At("HEAD"), require = 1)
    private void gtnhdet$resetChestDraws(World world, Random rand, int x, int y, int z,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        final java.util.HashMap<Long, Integer> m = gtnhdet$chestDraws.get();
        // Entries are never individually retired — a room that fails the scan still made one. Bounded
        // rather than left to grow for the life of the server thread.
        if (m.size() > 4096) m.clear();
        m.put(gtnhdet$roomKey(x, y, z), 0);
    }

    private static int gtnhdet$nextChestDraw(int x, int y, int z) {
        final java.util.HashMap<Long, Integer> m = gtnhdet$chestDraws.get();
        final long k = gtnhdet$roomKey(x, y, z);
        final Integer prev = m.get(k);
        final int n = prev == null ? 0 : prev;
        m.put(k, n + 1);
        return n;
    }

    /**
     * Draw {@code n} of this room's chest placement. Each draw is an independent function of (room,
     * n) rather than successive values of one stream, so it does not matter that the two redirects
     * below are reached a variable number of times or that the loop breaks early.
     */
    private static long gtnhdet$chestSeed(World world, int x, int y, int z, int n) {
        long h = world.getSeed() * 6364136223846793005L + (x * 3129871L + y * 116129781L + z) + 0x63686573L;
        h += 0x9E3779B97F4A7C15L * (n + 1);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    private static int gtnhdet$chestDraw(Random rand, int bound, World world, int x, int y, int z) {
        // Consume the stock draw as well as answering from the fork. The loop's trip count still varies
        // (it breaks as soon as a slot has exactly one solid neighbour), so populate's draw count is not
        // identical to stock — but it becomes a pure function of the room, which is what keeps everything
        // downstream in the chunk deterministic. The extent redirects above do NOT consume, and that
        // inconsistency is recorded in docs/HANDOFF.md rather than quietly changed here.
        rand.nextInt(bound);
        return new Random(gtnhdet$chestSeed(world, x, y, z, gtnhdet$nextChestDraw(x, y, z))).nextInt(bound);
    }

    /**
     * What THIS room will have put at a cell, computed from virgin terrain and the room's own
     * geometry — never from the live world, and never from a neighbouring room.
     *
     * <p>
     * The chest loop asks two questions: is the candidate air, and how many of its four horizontal
     * neighbours are solid. Both read cells this room has just written, so the live answer depends on
     * whether an overlapping room happened to run first. Recomputing the answer from the room itself
     * makes chest placement a pure function of (seed, room), which is what keeps it out of a layout
     * solver and inside a prefilter.
     *
     * <p>
     * The extents are re-derived from the same fork {@link #gtnhdet$extentFork} hands the two
     * {@code nextInt(2)} draws at the top of {@code generate}, so this cannot drift from the room that
     * was actually built. Mirrors vanilla's own branch order:
     *
     * <pre>
     * interior (not on any of the six faces)              -&gt; air
     * face, and the block below is not solid              -&gt; air
     * face, and the block here is solid                   -&gt; cobblestone
     * otherwise                                           -&gt; whatever virgin terrain holds
     * </pre>
     */
    private static Block gtnhdet$roomBlock(World world, int x, int y, int z, int rx, int ry, int rz) {
        final Random f = gtnhdet$extentFork(world, rx, ry, rz);
        final int l = f.nextInt(2) + 2;
        final int i1 = f.nextInt(2) + 2;
        final int b0 = 3;
        if (x < rx - l - 1 || x > rx + l + 1 || z < rz - i1 - 1 || z > rz + i1 + 1 || y < ry - 1 || y > ry + b0 + 1) {
            return TerrainOracle.block(world, x, y, z);
        }
        final boolean face = x == rx - l - 1 || x == rx + l + 1
            || z == rz - i1 - 1
            || z == rz + i1 + 1
            || y == ry - 1
            || y == ry + b0 + 1;
        if (!face) return net.minecraft.init.Blocks.air;
        if (y >= 0 && !TerrainOracle.block(world, x, y - 1, z)
            .getMaterial()
            .isSolid()) return net.minecraft.init.Blocks.air;
        if (TerrainOracle.block(world, x, y, z)
            .getMaterial()
            .isSolid()) return net.minecraft.init.Blocks.cobblestone;
        return TerrainOracle.block(world, x, y, z);
    }

    /**
     * The candidate cell. Always interior — the offsets span {@code +-l} and {@code +-i1} while the
     * walls sit at {@code +-(l+1)} — so the room's own model always says air. The one live thing still
     * consulted is whether another room's chest or spawner already stands there, because after
     * {@link #gtnhdet$isLoot} the carve leaves those in place and stacking a second chest on top would
     * destroy the loot this fix exists to preserve. That is order-dependent in principle; it needs an
     * overlapping room whose chest lands on this room's exact candidate, which has not been observed.
     */
    @Redirect(
        method = "generate",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;isAirBlock(III)Z", ordinal = 2),
        require = 1)
    private boolean gtnhdet$modelChestAir(World world, int x, int y, int z, World w2, Random r2, int rx, int ry,
        int rz) {
        if (!gtnhdet$CHEST) return world.isAirBlock(x, y, z);
        return !gtnhdet$isLoot(world, x, y, z)
            && gtnhdet$roomBlock(world, x, y, z, rx, ry, rz) == net.minecraft.init.Blocks.air;
    }

    /** The four adjacent solidity tests that gate the chest, answered from this room's own model. */
    @Redirect(
        method = "generate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;getBlock(III)Lnet/minecraft/block/Block;",
            ordinal = 3),
        require = 1)
    private Block gtnhdet$modelAdj0(World world, int x, int y, int z, World w2, Random r2, int rx, int ry, int rz) {
        return gtnhdet$CHEST ? gtnhdet$roomBlock(world, x, y, z, rx, ry, rz) : world.getBlock(x, y, z);
    }

    @Redirect(
        method = "generate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;getBlock(III)Lnet/minecraft/block/Block;",
            ordinal = 4),
        require = 1)
    private Block gtnhdet$modelAdj1(World world, int x, int y, int z, World w2, Random r2, int rx, int ry, int rz) {
        return gtnhdet$CHEST ? gtnhdet$roomBlock(world, x, y, z, rx, ry, rz) : world.getBlock(x, y, z);
    }

    @Redirect(
        method = "generate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;getBlock(III)Lnet/minecraft/block/Block;",
            ordinal = 5),
        require = 1)
    private Block gtnhdet$modelAdj2(World world, int x, int y, int z, World w2, Random r2, int rx, int ry, int rz) {
        return gtnhdet$CHEST ? gtnhdet$roomBlock(world, x, y, z, rx, ry, rz) : world.getBlock(x, y, z);
    }

    @Redirect(
        method = "generate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;getBlock(III)Lnet/minecraft/block/Block;",
            ordinal = 6),
        require = 1)
    private Block gtnhdet$modelAdj3(World world, int x, int y, int z, World w2, Random r2, int rx, int ry, int rz) {
        return gtnhdet$CHEST ? gtnhdet$roomBlock(world, x, y, z, rx, ry, rz) : world.getBlock(x, y, z);
    }

    /**
     * Chest X offset. Vanilla tries two chest slots, three placements each:
     *
     * <pre>
     * i2 = x + rand.nextInt(l * 2 + 1) - l;
     * j2 = z + rand.nextInt(i1 * 2 + 1) - i1;
     * </pre>
     *
     * Both come off the shared populate stream, whose position by this point depends on how many
     * mossy-cobblestone draws the wall loop took — one per solid block it found, so a function of the
     * surrounding terrain. Measured 25 seeds, warm r30, rows vs spiral, with room existence already at
     * 1 difference in 2082 rooms: <b>37 rooms built at identical anchors in both arms held their
     * chests at different positions</b>, 94 chest positions, 20 of them yielding a different NUMBER of
     * chests because a placement slot found a valid spot in one arm and not the other.
     *
     * <p>
     * The block reads inside this loop are deliberately NOT redirected. They inspect the room that was
     * just carved — air interior, cobblestone walls — so answering them from {@code TerrainOracle}
     * would test the terrain as it was before the room existed and put chests through walls.
     */
    @Redirect(
        method = "generate",
        at = @At(value = "INVOKE", target = "Ljava/util/Random;nextInt(I)I", ordinal = 3),
        require = 1)
    private int gtnhdet$forkedChestX(Random rand, int bound, World world, Random r2, int x, int y, int z) {
        return gtnhdet$CHEST ? gtnhdet$chestDraw(rand, bound, world, x, y, z) : rand.nextInt(bound);
    }

    /** Chest Z offset; ordinal 4 because the wall loop's {@code nextInt(4)} mossy roll is ordinal 2. */
    @Redirect(
        method = "generate",
        at = @At(value = "INVOKE", target = "Ljava/util/Random;nextInt(I)I", ordinal = 4),
        require = 1)
    private int gtnhdet$forkedChestZ(Random rand, int bound, World world, Random r2, int x, int y, int z) {
        return gtnhdet$CHEST ? gtnhdet$chestDraw(rand, bound, world, x, y, z) : rand.nextInt(bound);
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
