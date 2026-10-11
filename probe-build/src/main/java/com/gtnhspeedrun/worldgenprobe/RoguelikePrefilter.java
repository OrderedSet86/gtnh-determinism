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
        /**
         * Dungeon entrance column ({@code Dungeon.pos}) — the X/Z the surface tower generates at.
         * {@code Integer.MIN_VALUE} when the trigger rolled but no valid location was found.
         */
        int entranceX = Integer.MIN_VALUE;
        int entranceZ = Integer.MIN_VALUE;
        final List<String> chests = new ArrayList<>();
        final List<int[]> enchantTables = new ArrayList<>();
        /**
         * Per TOOLS-type chest: {x, y, z, alumitePlate, anyPlate, hammerHead, toughRod} — the hammer%
         * categories counted on the ItemStacks at capture time ({@link LootCounts}), TOOLS only because all
         * tough-tier TC parts live exclusively in {@code loot_tools} chests (results/2026-08-29-tc-chest-materials).
         */
        final List<int[]> toolChests = new ArrayList<>();
        String tower;
        /**
         * Whether the resolved settings carry {@code SettingsType.LOOTRULES} in their override list, i.e.
         * whether GTNH's {@code loot_all.json} suppression reached this dungeon.
         *
         * <p>
         * This is the switch that decides whether vanilla {@code ChestGenHooks} loot can appear at all.
         * {@code SettingsLootRules} adds a type-{@code null}, to-each, 6-draw JUNK rule, and
         * {@code ItemJunk.getLootItem} is the mod's ONLY {@code ChestGenHooks} caller — including a
         * {@code level == 4} branch that draws from {@code pyramidJungleChest} / {@code pyramidDesertChest}.
         * GTNH's eight {@code dungeon_*.json} inherit {@code "overrides": ["LOOTRULES"]} from
         * {@code loot_all.json}, so a dungeon whose biome matches a custom setting never runs that path;
         * one that falls through to {@code SettingsResolver.base} does.
         *
         * <p>
         * Recorded rather than derived from the biome on purpose: the block-level biome is not settled
         * before generation under RWG, which is why the enchanting-table biome gate was removed as unsound
         * (see the note above {@code Prefilter.SURFACE_Y_COLUMNS}). The resolved settings object is the
         * gate itself, so reading it sidesteps the question entirely.
         */
        Boolean lootRulesSuppressed;
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
    /**
     * Positions of enchanting tables this editor wrote, decoded from the fix jar's WorldEditorMixin
     * capture maps ({@code gtnhdet$written}: pos -> Block, {@code gtnhdet$buffered}: pos ->
     * PendingSlices.Write). Reflection, because the maps are mixin-added and the probe does not compile
     * against the fix jar. Key packing mirrors WorldEditorMixin.gtnhdet$key:
     * {@code (x+30M)<<36 | (z+30M)<<8 | (y&0xFF)}.
     */
    private static List<int[]> enchantTables(IWorldEditor editor) {
        final List<int[]> out = new ArrayList<>();
        try {
            final net.minecraft.block.Block table = net.minecraft.init.Blocks.enchanting_table;
            for (final String fname : new String[] { "gtnhdet$written", "gtnhdet$buffered" }) {
                final java.lang.reflect.Field f;
                try {
                    f = editor.getClass()
                        .getDeclaredField(fname);
                } catch (NoSuchFieldException absent) {
                    continue; // fix jar absent or older: no capture, no signal — absence, not zero
                }
                f.setAccessible(true);
                final java.util.Map<?, ?> m = (java.util.Map<?, ?>) f.get(editor);
                for (final java.util.Map.Entry<?, ?> e : m.entrySet()) {
                    Object v = e.getValue();
                    if (v != null && !(v instanceof net.minecraft.block.Block)) {
                        // PendingSlices.Write carries a public `block` field
                        v = v.getClass()
                            .getField("block")
                            .get(v);
                    }
                    if (v != table) continue;
                    final long k = (Long) e.getKey();
                    final int y = (int) (k & 0xFF);
                    final int z = (int) ((k >> 8) & 0xFFFFFFFL) - 30_000_000;
                    final int x = (int) ((k >> 36) & 0xFFFFFFFL) - 30_000_000;
                    out.add(new int[] { x, y, z });
                }
            }
        } catch (Throwable t) {
            WorldgenProbe.LOG.warn("[prefilter] enchant-table scan failed: {}", t.toString());
        }
        return out;
    }

    /**
     * Captures the resolved {@code ISettings} on its way into {@code generate}. A subclass rather than a
     * replica of {@code spawnInChunk}'s resolution: the resolver call sits mid-way through a retry loop
     * with its own rand protocol, and re-running it here would consume different draws — the exact
     * "self-stable but real-divergent" failure the villageStarts comment records. The virtual call is
     * the one seam the mod hands us for free.
     */
    private static final class CapturingDungeon extends Dungeon {

        greymerk.roguelike.dungeon.settings.ISettings captured;
        int capturedX;
        int capturedZ;
        boolean placed;
        /**
         * Place-only mode: record the resolved settings and entrance and SKIP construction. Siting
         * (generateNear's attempt loop + validLocation) and settings resolution have already happened by
         * the time generate() is entered, and they are all a coincidence scan needs — the ~260 ms room/
         * loot build would only be thrown away. Chests and enchant tables are not populated in this mode.
         */
        final boolean placeOnly;

        CapturingDungeon(IWorldEditor editor, boolean placeOnly) {
            super(editor);
            this.placeOnly = placeOnly;
        }

        @Override
        public void generate(greymerk.roguelike.dungeon.settings.ISettings settings, int x, int z) {
            captured = settings;
            capturedX = x;
            capturedZ = z;
            placed = true;
            if (!placeOnly) super.generate(settings, x, z);
        }
    }

    static Result generate(World world, long seed, int cx, int cz) {
        return generate(world, seed, cx, cz, false);
    }

    static Result generate(World world, long seed, int cx, int cz, boolean placeOnly) {
        final Result r = new Result();
        r.triggerX = cx;
        r.triggerZ = cz;
        try {
            final IWorldEditor editor = new WorldEditor(world);
            final CapturingDungeon dungeon = new CapturingDungeon(editor, placeOnly);
            dungeon.spawnInChunk(new Random(chunkSeed(seed, cx, cz)), cx, cz);
            if (dungeon.placed) {
                // generate()'s x/z args ARE Dungeon.pos (it sets pos = (inX, 50, inZ)); captured here so
                // place-only mode, which never runs the body, still reports the entrance column.
                r.entranceX = dungeon.capturedX;
                r.entranceZ = dungeon.capturedZ;
            }
            if (dungeon.captured != null) {
                final java.util.List<greymerk.roguelike.dungeon.settings.SettingsType> ov = dungeon.captured
                    .getOverrides();
                r.lootRulesSuppressed = ov != null
                    && ov.contains(greymerk.roguelike.dungeon.settings.SettingsType.LOOTRULES);
            }
            if (placeOnly) {
                if (dungeon.captured != null && dungeon.captured.getTower() != null) {
                    final greymerk.roguelike.dungeon.towers.Tower tw = dungeon.captured.getTower()
                        .getTower();
                    r.tower = tw == null ? null : tw.name();
                }
                return r;
            }
            if (dungeon.captured != null && dungeon.captured.getTower() != null) {
                final greymerk.roguelike.dungeon.towers.Tower tw = dungeon.captured.getTower()
                    .getTower();
                r.tower = tw == null ? null : tw.name();
            }
            for (final ITreasureChest chest : dungeon.getChests()) {
                final TileEntityChest te = tileEntityOf(chest);
                if (te == null) continue;
                // The treasure TYPE distinguishes rooms the item list cannot: an ENCHANTING chest is
                // stocked by exactly two rooms (DungeonsEnchant, DungeonsNetherBrickFortress), and only
                // the first also places an enchantment table.
                final String dump = WorldgenProbe.dumpInventory(te, te);
                final String typed = "{\"ttype\": \"" + chest.getType()
                    .name() + "\", " + dump.substring(dump.indexOf('{') + 1);
                r.chests.add(typed);
                if ("TOOLS".equals(
                    chest.getType()
                        .name())) {
                    final int[] c = LootCounts.count(te);
                    r.toolChests.add(
                        new int[] { te.xCoord, te.yCoord, te.zCoord, c[LootCounts.ALUMITE_PLATE],
                            c[LootCounts.ANY_PLATE], c[LootCounts.HAMMER_HEAD], c[LootCounts.TOUGH_ROD] });
                }
            }
            // Exact enchant-room signal: the ENCHANTING_TABLE blocks the dungeon actually wrote, read
            // back from the F5 editor overlay's capture maps (live writes + buffered writes). Chest
            // type alone over-matches: the fortress room stocks an ENCHANTING chest with no table.
            r.enchantTables.addAll(enchantTables(editor));
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
