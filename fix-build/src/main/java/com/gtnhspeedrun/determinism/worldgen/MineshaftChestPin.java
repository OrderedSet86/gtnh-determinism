package com.gtnhspeedrun.determinism.worldgen;

import java.util.HashMap;
import java.util.Random;

import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureBoundingBox;

/**
 * The mineshaft corridor chest-existence pin, shared by the vanilla and mesa corridor mixins.
 *
 * <p>
 * Two different piece classes roll the same decision: vanilla
 * {@code StructureMineshaftPieces.Corridor} and Et Futurum's
 * {@code StructureMesaMineshaftPieces$MesaCorridor}. They do not share a supertype that carries the
 * draw — MesaCorridor extends Et Futurum's own {@code Piece}, not the vanilla class — so each needs
 * its own {@code @Redirect}. The DERIVATION must not be transcribed twice: two copies of a splitmix
 * that are meant to agree and silently drift is the failure mode {@link VirginStoneType} documents,
 * and the mineshaft mixin's own javadoc lists three past mixins that got a duplicated draw counter
 * wrong. So the mixins hold only the injection points, and everything that decides a value lives here.
 *
 * <p>
 * Each draw is answered from an independent function of (worldSeed, piece box anchor, draw index,
 * salt) instead of from the shared populate stream, whose position at that point depends on every
 * {@code PopulateChunkEvent.Pre} handler's draws. The stock draw is still consumed by the caller, so
 * populate's stream position is unchanged and nothing downstream of the draw moves.
 *
 * <p>
 * The two piece types take DIFFERENT salts. They can never occupy the same box anchor in practice, so
 * a shared salt would also be correct, but keeping them independent means a future change to one
 * cannot move the other's chests — and that independence is what lets the mesa pin ship without
 * reopening the vanilla pin's measured result (results/2026-09-17-mineshaft-multiseed).
 *
 * <p>
 * One switch governs both: {@code -Dgtnhdet.mineshaftpin=false} restores stock draws everywhere, so an
 * A/B arm is true stock for the whole feature rather than half of it.
 */
public final class MineshaftChestPin {

    private MineshaftChestPin() {}

    /** Salt for vanilla {@code StructureMineshaftPieces.Corridor} — "mshc". Do not change: measured. */
    public static final long SALT_VANILLA = 0x6D736863L;

    /** Salt for Et Futurum {@code MesaCorridor} — "mmsh". */
    public static final long SALT_MESA = 0x6D6D7368L;

    public static final boolean ENABLED = !"false".equals(System.getProperty("gtnhdet.mineshaftpin"));

    /** Draw counts per piece anchor. ThreadLocal + keyed: population nests and pieces interleave. */
    private static final ThreadLocal<HashMap<Long, Integer>> DRAWS = ThreadLocal.withInitial(HashMap::new);

    /** 26 bits X / 26 bits Z / 12 bits Y — the exact packing {@code WorldGenDungeonsScanMixin} uses. */
    private static long pieceKey(StructureBoundingBox bb) {
        return ((bb.minX & 0x3FFFFFFL) << 38) | ((bb.minZ & 0x3FFFFFFL) << 12) | (bb.minY & 0xFFFL);
    }

    /** Reset this piece's draw counter. Called at the HEAD of every {@code addComponentParts}. */
    public static void resetDraws(StructureBoundingBox bb) {
        final HashMap<Long, Integer> m = DRAWS.get();
        // Entries are never individually retired; bounded rather than left to grow for the thread's life.
        if (m.size() > 4096) m.clear();
        m.put(pieceKey(bb), 0);
    }

    /** Draw {@code n} for this piece: an independent function of (seed, piece anchor, n, salt). */
    private static long drawSeed(World world, StructureBoundingBox bb, int n, long salt) {
        long h = world.getSeed() * 6364136223846793005L + (bb.minX * 3129871L + bb.minY * 116129781L + bb.minZ) + salt;
        h += 0x9E3779B97F4A7C15L * (n + 1);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    /**
     * Answer a chest-existence draw from the pinned stream, given the stock draw the caller already took.
     *
     * <p>
     * The caller makes the stock draw unconditionally and first, including when the pin is off: that is
     * the stream-preservation contract, and making it conditional would move every later draw in the
     * populate window. The caller makes it, rather than this method, because the draw has two call shapes:
     * a plain {@code rand.nextInt(bound)}, or Mobs-Info's rewritten
     * {@code RandomComparison.nextInt(rand, bound, constant, op)}, which must go through Mobs-Info's own
     * {@code original} to keep that mod's behaviour. See {@link com.gtnhspeedrun.determinism.RandomDrawPoint}.
     */
    public static int pinnedDraw(int stock, int bound, World world, StructureBoundingBox bb, long salt) {
        if (!ENABLED || world == null) return stock;
        final HashMap<Long, Integer> m = DRAWS.get();
        final long k = pieceKey(bb);
        final Integer prev = m.get(k);
        final int n = prev == null ? 0 : prev;
        m.put(k, n + 1);
        return new Random(drawSeed(world, bb, n, salt)).nextInt(bound);
    }
}
