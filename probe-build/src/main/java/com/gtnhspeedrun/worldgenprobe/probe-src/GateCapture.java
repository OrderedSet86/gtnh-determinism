package com.gtnhspeedrun.worldgenprobe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntitySkull;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraft.world.gen.structure.StructureComponent;

/** Read-only harness captures: no gameplay generator or RNG protocol is changed. */
public final class GateCapture {
    private GateCapture() {}

    static boolean partsRequested() { return Boolean.getBoolean("probe.prefilter.gate.obsidianparts"); }
    static int headsRequired() { return Integer.parseInt(System.getProperty("probe.prefilter.gate.witherheads", "0")); }

    static void validateSettings(String worldType) {
        final String heads = System.getProperty("probe.prefilter.gate.witherheads");
        if (heads != null && headsRequired() < 1) throw new IllegalArgumentException("witherheads must be positive");
        if (heads == null && !partsRequested()) return;
        if ("RWG".equalsIgnoreCase(worldType)) throw new IllegalArgumentException("Wither/obsidian gates require RWG2");
        if (Integer.getInteger("probe.prefilter.tier1", -1) >= 0 || System.getProperty("probe.prefilter.surfacey") != null) {
            throw new IllegalArgumentException("Wither/obsidian gates cannot run in tier1/surfacey-only mode");
        }
        if (heads != null && Integer.getInteger("probe.prefilter.dungeon", -1) < 0) {
            throw new IllegalArgumentException("witherheads requires an enabled Roguelike dungeon scan");
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        final Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Read the FINAL buffered write, not block metadata (skull metadata is orientation, not skull type). */
    static List<int[]> skulls(Object editor) throws Exception {
        final Map<?, ?> live = (Map<?, ?>) field(editor.getClass(), "gtnhdet$written").get(editor);
        final Map<?, ?> buffered = (Map<?, ?>) field(editor.getClass(), "gtnhdet$buffered").get(editor);
        if (!live.isEmpty()) throw new IllegalStateException("Worldless skull capture unexpectedly used live writes");
        final List<int[]> out = new ArrayList<>();
        final List<Long> keys = new ArrayList<>();
        for (final Object key : buffered.keySet()) keys.add((Long) key);
        java.util.Collections.sort(keys);
        for (final Long key : keys) {
            final Object write = buffered.get(key);
            final Block block = (Block) write.getClass().getField("block").get(write);
            if (block != Blocks.skull) continue;
            final Object detached = write.getClass().getField("detached").get(write);
            if (!(detached instanceof TileEntitySkull)) {
                throw new IllegalStateException("Placed skull has no configured captured TileEntitySkull at " + key);
            }
            final int[] p = Prefilter.SeedProbeWorld.okeyToPos(key);
            final TileEntitySkull skull = (TileEntitySkull) detached;
            if (skull.xCoord != p[0] || skull.yCoord != p[1] || skull.zCoord != p[2]) {
                throw new IllegalStateException("Captured skull tile position mismatch");
            }
            out.add(new int[] { p[0], p[1], p[2], skull.func_145904_a() });
        }
        return out;
    }

    private static java.util.IdentityHashMap<IInventory, Boolean> verified;
    private static int[] refillCounts = new int[4];
    private static int refillDepth;

    /** Harness mixin reports actual per-inventory F10 success, including CraftingStationLogic. */
    public static void refillStart(int count) {
        if (verified == null) return;
        if (refillDepth == refillCounts.length) refillCounts = java.util.Arrays.copyOf(refillCounts, refillDepth * 2);
        refillCounts[refillDepth++] = count;
    }
    public static void refillEnd(IInventory inventory, int count) {
        if (verified == null) return;
        if (refillDepth == 0) throw new IllegalStateException("Unbalanced inventory refill capture");
        if (count > refillCounts[--refillDepth]) verified.put(inventory, Boolean.TRUE);
    }

    /**
     * Replay actual cloned ToolWorkshop components in vanilla population windows ([chunk*16+8,+23]).
     * Keep component flags and own writes across calls, with a separate F10 scope for each call.
     * The pinned ComponentToolWorkshop uses its passed RNG only for three stock inventory fills:
     * ground anchoring, door placement and spawnVillagers consume none. Each filled inventory is
     * verified against the actual F10 refill hook, so the unavailable population RNG prefix is irrelevant.
     * Arbitrary village-building loot and crop decoration are deliberately outside this source's scope.
     */
    static void village(Prefilter.SeedProbeWorld world, List<?> components, ObsidianParts parts) {
        try {
            final Class<?> context = Class.forName("com.gtnhspeedrun.determinism.worldgen.ChestFillContext");
            final Method enter = context.getMethod("enterComponentBox", String.class, StructureBoundingBox.class,
                Object.class, long.class);
            final Method leave = context.getMethod("leaveComponent");
            final Field fallback = field(context, "fallbacks");
            if (!lootCaptureInstalled) throw new IllegalStateException("Per-inventory loot verification mixin did not run");
            for (final Object value : components) {
                if (!value.getClass().getName().equals("tconstruct.world.village.ComponentToolWorkshop")) continue;
                world.beginOverlay();
                verified = new java.util.IdentityHashMap<>();
                refillDepth = 0;
                try {
                    final StructureComponent original = (StructureComponent) value;
                    final StructureComponent component = (StructureComponent) original.getClass().getDeclaredConstructor().newInstance();
                    component.func_143009_a(world, original.func_143010_b());
                    final StructureBoundingBox box = component.getBoundingBox();
                    final int minCX = Math.floorDiv(box.minX - 8, 16), maxCX = Math.floorDiv(box.maxX - 8, 16);
                    final int minCZ = Math.floorDiv(box.minZ - 8, 16), maxCZ = Math.floorDiv(box.maxZ - 8, 16);
                    final int beforeFallback = fallback.getInt(null);
                    final Random discardedStockDraws = new Random(0L);
                    boolean retained = true;
                    for (int cx = minCX; cx <= maxCX && retained; cx++) {
                        for (int cz = minCZ; cz <= maxCZ && retained; cz++) {
                            final StructureBoundingBox window = new StructureBoundingBox(cx * 16 + 8, 0, cz * 16 + 8,
                                cx * 16 + 23, 255, cz * 16 + 23);
                            if (!component.getBoundingBox().intersectsWith(window)) continue;
                            enter.invoke(null, component.getClass().getName(), component.getBoundingBox(), component, world.getSeed());
                            try {
                                retained = component.addComponentParts(world, discardedStockDraws, window);
                            } finally {
                                leave.invoke(null);
                            }
                        }
                    }
                    if (fallback.getInt(null) != beforeFallback) {
                        throw new IllegalStateException("Village inventory used stock/unresolved loot instead of a verified position fork");
                    }
                    final Map<Long, int[]> blocks = world.overlayBlocks();
                    final List<Long> keys = new ArrayList<>(blocks.keySet());
                    java.util.Collections.sort(keys);
                    final List<ExactLoot.Chest> inventories = new ArrayList<>();
                    int lootInventories = 0;
                    for (final Long key : keys) {
                        final int[] p = Prefilter.SeedProbeWorld.okeyToPos(key), state = blocks.get(key);
                        final Block block = Block.getBlockById(state[0]);
                        if (block == null || !block.hasTileEntity(state[1])) continue;
                        final TileEntity tile = world.getTileEntity(p[0], p[1], p[2]);
                        if (tile == null) throw new IllegalStateException("Village tile-entity capture missing at " + key);
                        if (!(tile instanceof IInventory)) continue;
                        final ExactLoot.Chest inventory = VanillaDungeonPrefilter.capture("village_workshop", (IInventory) tile, p[0], p[1], p[2]);
                        final boolean lootInventory = tile instanceof net.minecraft.tileentity.TileEntityChest
                            || tile.getClass().getName().equals("tconstruct.tools.logic.CraftingStationLogic")
                            || tile.getClass().getName().equals("tconstruct.tools.logic.PatternChestLogic");
                        if (lootInventory) lootInventories++;
                        if ((!inventory.items.isEmpty() || lootInventory) && !verified.containsKey((IInventory) tile)) {
                            throw new IllegalStateException("Workshop inventory bypassed verified position refill: " + tile.getClass().getName());
                        }
                        inventories.add(inventory);
                    }
                    if (lootInventories != 3) throw new IllegalStateException(
                        "Workshop capture did not retain its pattern chest, crafting station and chest: " + lootInventories);
                    for (final ExactLoot.Chest inventory : inventories) parts.add(inventory);
                } finally {
                    verified = null;
                    refillDepth = 0;
                    world.endOverlay();
                }
            }
        } catch (Throwable error) {
            parts.fail("Village workshop capture: " + error);
        }
    }

    private static boolean lootCaptureInstalled;
    public static void lootCaptureInstalled() { lootCaptureInstalled = true; }
}
