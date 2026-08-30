package com.gtnhspeedrun.worldgenprobe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.World;

import greymerk.roguelike.dungeon.Dungeon;
import greymerk.roguelike.treasure.ITreasureChest;
import greymerk.roguelike.worldgen.IWorldEditor;
import greymerk.roguelike.worldgen.WorldEditor;

/**
 * Stage-0 Roguelike Dungeons module: generates a dungeon headless against {@link Prefilter.SeedProbeWorld} and
 * reads its chests, so a seed's dungeon loot is answerable without creating a world.
 *
 * <p>
 * Nothing here reimplements Roguelike. It builds the real {@code WorldEditor} and {@code Dungeon} and calls
 * {@code spawnInChunk}, exactly as the mod's own {@code IWorldGenerator} does — its whole body is
 * {@code new WorldEditor(world); new Dungeon(editor); dungeon.spawnInChunk(rand, cx, cz);}. That is deliberate:
 * a hand-rolled replication of a rand protocol is what produced self-consistent but real-divergent village
 * layouts before (see {@code Prefilter.villageStarts}).
 *
 * <p>
 * Three properties make this possible at all, all verified by disassembling the shipped 1.6.6-GTNH jar:
 * <ul>
 * <li>The mod touches {@code net.minecraft.world.World} from exactly one class, {@code WorldEditor}, through
 * eight methods — all served by {@link Prefilter.VirginChunkProvider}.</li>
 * <li>Layout, room-graph generation and loot assignment make zero world reads. Level Y is hard-coded, so terrain
 * height never enters the layout.</li>
 * <li>{@code Dungeon.getRandom(editor, x, z)} is {@code new Random(seed * x * z)} — a pure function.</li>
 * </ul>
 *
 * <p>
 * The trigger {@code Random} needs no registry introspection. {@code GameRegistry.generateWorld} calls
 * {@code fmlRandom.setSeed(chunkSeed)} before <em>each</em> generator, so what Roguelike receives is
 * {@code new Random(chunkSeed)} regardless of how many generators ran first or what they drew — see
 * {@link #chunkSeed}.
 *
 * <p>
 * Writes never reach the world: {@code VirginChunkProvider.chunkExists} is always false, which short-circuits
 * the fix jar's {@code PendingSlices.shouldBuffer} to always-buffer, and the slice applier that would later
 * materialise those writes is an {@code IWorldGenerator} that the prefilter never invokes. The chunk cache
 * therefore stays virgin and the chest tile entities stay detached, which is the same path a buffered chunk
 * takes in a full run.
 */
final class RoguelikePrefilter {

    private static Boolean present;
    private static Field chestInventoryField;
    private static Field inventoryChestField;

    private RoguelikePrefilter() {}

    static boolean available() {
        if (present == null) {
            try {
                Class.forName("greymerk.roguelike.dungeon.Dungeon");
                present = Boolean.TRUE;
            } catch (ClassNotFoundException absent) {
                present = Boolean.FALSE;
            }
        }
        return present;
    }

    /**
     * Forge's per-chunk generator seed, reproduced verbatim from {@code GameRegistry.generateWorld}.
     *
     * <p>
     * The {@code >> 2 + 1L} is Forge's own operator-precedence quirk — it parses as {@code >> 3}, not
     * {@code (>> 2) + 1}. Written the way Forge writes it so nobody "corrects" it into a different world.
     */
    static long chunkSeed(long worldSeed, int chunkX, int chunkZ) {
        final Random fmlRandom = new Random(worldSeed);
        final long xSeed = fmlRandom.nextLong() >> 2 + 1L;
        final long zSeed = fmlRandom.nextLong() >> 2 + 1L;
        return (xSeed * chunkX + zSeed * chunkZ) ^ worldSeed;
    }

    /** One dungeon's chests, plus enough about the dungeon to tell a miss from a mismatch. */
    static final class Result {

        int triggerX;
        int triggerZ;
        final List<String> chests = new ArrayList<>();
        String error;
    }

    /**
     * Trigger chunks in a square of {@code radius} chunks around {@code (cx0, cz0)}. Pure arithmetic — the grid
     * is {@code editor.getSeededRandom} over a region derived from {@code RogueConfig}'s spawn frequency, with
     * no terrain involved — so this doubles as a free kill gate: a seed with no trigger cannot have a dungeon
     * and never has to pay for terrain.
     */
    static List<int[]> triggers(World world, int cx0, int cz0, int radius) {
        final IWorldEditor editor = new WorldEditor(world);
        final List<int[]> out = new ArrayList<>();
        for (int cx = cx0 - radius; cx <= cx0 + radius; cx++) {
            for (int cz = cz0 - radius; cz <= cz0 + radius; cz++) {
                if (Dungeon.canSpawnInChunk(cx, cz, editor)) out.add(new int[] { cx, cz });
            }
        }
        return out;
    }

    /**
     * Generate the dungeon triggered by one chunk and return its chests.
     *
     * <p>
     * A fresh {@code WorldEditor} per trigger, matching the mod: its {@code IWorldGenerator} constructs one per
     * chunk, and the editor owns the {@code TreasureManager} the chests register into.
     */
    static Result generate(World world, long seed, int cx, int cz) {
        final Result r = new Result();
        r.triggerX = cx;
        r.triggerZ = cz;
        try {
            final IWorldEditor editor = new WorldEditor(world);
            final Dungeon dungeon = new Dungeon(editor);
            dungeon.spawnInChunk(new Random(chunkSeed(seed, cx, cz)), cx, cz);
            for (final ITreasureChest chest : dungeon.getChests()) {
                final TileEntityChest te = tileEntityOf(chest);
                if (te == null) continue;
                r.chests.add(WorldgenProbe.dumpInventory(te, te));
            }
        } catch (Throwable t) {
            r.error = t.toString();
        }
        return r;
    }

    /**
     * The chest's tile entity, via {@code TreasureChest.inventory -> Inventory.chest}.
     *
     * <p>
     * Deliberately not routed through the fix jar's {@code ChestPosAccess.gtnhdet$posKey()}: that packs and
     * masks coordinates, and it only exists when the determinism jar is loaded, which would make a
     * with-jar/without-jar A/B impossible.
     */
    private static TileEntityChest tileEntityOf(ITreasureChest chest) throws Exception {
        if (chestInventoryField == null) {
            chestInventoryField = Class.forName("greymerk.roguelike.treasure.TreasureChest")
                .getDeclaredField("inventory");
            chestInventoryField.setAccessible(true);
            inventoryChestField = Class.forName("greymerk.roguelike.treasure.Inventory")
                .getDeclaredField("chest");
            inventoryChestField.setAccessible(true);
        }
        final Object inv = chestInventoryField.get(chest);
        return inv == null ? null : (TileEntityChest) inventoryChestField.get(inv);
    }

    /**
     * Release the fix jar's atomic slice window if a dungeon threw partway through.
     * {@code PendingSlices.atomicDepth} is a static; leaving it above zero would defer every later seed's writes
     * for the rest of the process. No-op without the determinism jar.
     */
    static void resetSliceWindow() {
        try {
            Class.forName("com.gtnhspeedrun.determinism.worldgen.PendingSlices")
                .getMethod("resetAtomicWindow")
                .invoke(null);
        } catch (ClassNotFoundException | NoSuchMethodException absent) {
            // determinism jar not installed
        } catch (Exception e) {
            WorldgenProbe.LOG.warn("[prefilter] could not reset the slice window: {}", e.toString());
        }
    }
}
