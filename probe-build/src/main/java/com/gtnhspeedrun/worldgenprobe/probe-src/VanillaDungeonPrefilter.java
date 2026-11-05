package com.gtnhspeedrun.worldgenprobe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.gen.feature.WorldGenDungeons;

/** Replays only RWG2's eight pinned vanilla attempts, against disposable room writes and real virgin caves. */
final class VanillaDungeonPrefilter {
    private VanillaDungeonPrefilter() {}

    static void validateSettings(String worldType) {
        final int radius = Integer.parseInt(System.getProperty("probe.prefilter.vanilladungeon", "-1"));
        final String items = System.getProperty("probe.prefilter.requireitems");
        final String distance = System.getProperty("probe.prefilter.lootdistance");
        if (radius < -1) throw new IllegalArgumentException("vanilladungeon must be -1 or a nonnegative chunk radius");
        if (radius < 0 && items == null && distance == null) return;
        if ("RWG".equalsIgnoreCase(worldType)) {
            throw new IllegalArgumentException("Vanilla/exact-item options require the RWG2 patched probe");
        }
        if (Integer.getInteger("probe.prefilter.tier1", -1) >= 0 || System.getProperty("probe.prefilter.surfacey") != null) {
            throw new IllegalArgumentException("Vanilla/exact-item options cannot run in tier1/surfacey-only mode");
        }
        if ((items == null) != (distance == null)) {
            throw new IllegalArgumentException("requireitems and lootdistance must be supplied together");
        }
        if (items != null) {
            final ExactLoot gate = new ExactLoot(items, Integer.parseInt(distance), 0, 0,
                Integer.parseInt(System.getProperty("probe.prefilter.dungeon", "-1")) >= 0, radius >= 0);
            for (final ExactLoot.Requirement requirement : gate.requirements) {
                if (!Item.itemRegistry.containsKey(requirement.id)) {
                    throw new IllegalArgumentException("Unknown item registry ID: " + requirement.id);
                }
            }
        }
        if (radius >= 0) {
            for (final String name : new String[] { "dungeonfork", "dungeonscan", "dungeonchest", "dungeonmerge" }) {
                if ("false".equalsIgnoreCase(System.getProperty("gtnhdet." + name))) {
                    throw new IllegalArgumentException("vanilladungeon requires gtnhdet." + name + "=true");
                }
            }
        }
    }

    /** A worldless replay cannot reconstruct the shared stream before the DUNGEON event. Fail closed. */
    private static final class EventRandom extends Random {
        EventRandom() { super(0L); }
        @Override
        protected int next(int bits) {
            throw new IllegalStateException("Random-dependent DUNGEON population event requires full population");
        }
    }

    private static final Random EVENT_RANDOM = new EventRandom();

    private static boolean dungeonEvent(Prefilter.SeedProbeWorld world, int cx, int cz) {
        final long writes = world.overlayRevision;
        final net.minecraftforge.event.terraingen.PopulateChunkEvent.Populate.EventType type =
            net.minecraftforge.event.terraingen.PopulateChunkEvent.Populate.EventType.DUNGEON;
        final boolean noVillage = net.minecraftforge.event.terraingen.TerrainGen.populate(
            world.virginProvider().generator(), world, EVENT_RANDOM, cx, cz, false, type);
        final boolean village = net.minecraftforge.event.terraingen.TerrainGen.populate(
            world.virginProvider().generator(), world, EVENT_RANDOM, cx, cz, true, type);
        if (noVillage != village || writes != world.overlayRevision) {
            throw new IllegalStateException("Village-dependent or writing DUNGEON event requires full population");
        }
        return noVillage;
    }

    static final class Result {
        final List<String> rooms = new ArrayList<>();
        final List<String> chests = new ArrayList<>();
        final List<ExactLoot.Chest> exactChests = new ArrayList<>();
        int attempts, built, refills;
        boolean complete;
        String error;

        String json(int cx, int cz, int radius) {
            final StringBuilder b = new StringBuilder("{\"center_chunk\":[").append(cx).append(',').append(cz)
                .append("],\"radius_chunks\":").append(radius)
                .append(",\"window\":\"inclusive_chest_chunks\",\"attempt_halo_chunks\":1,\"attempts\":")
                .append(attempts).append(",\"rooms_built_including_halo\":").append(built)
                .append(",\"loot_refills_verified\":").append(refills)
                .append(",\"terrain\":\"virgin_provider_with_caves\",\"population\":\"vanilla_dungeons_only\",\"complete\":")
                .append(complete).append(",\"rooms\":[").append(String.join(",", rooms))
                .append("],\"chests\":[").append(String.join(",", chests)).append(']');
            if (error != null) b.append(",\"error\":").append(ExactLoot.quote(error));
            return b.append('}').toString();
        }
    }

    /** Strict inventory reads: unlike display dumps, an unreadable slot must not silently become zero. */
    static ExactLoot.Chest capture(String source, IInventory inventory, int x, int y, int z) {
        final ExactLoot.Chest chest = new ExactLoot.Chest(source, x, y, z);
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (stack == null) continue;
            if (stack.getItem() == null || stack.stackSize < 0) throw new IllegalStateException("Invalid inventory stack");
            final Object id = Item.itemRegistry.getNameForObject(stack.getItem());
            if (id == null) throw new IllegalStateException("Unregistered inventory item");
            chest.items.add(new ExactLoot.Stack(slot, id.toString(), stack.getItemDamage(), stack.stackSize));
        }
        return chest;
    }

    private static Field counter(Class<?> context, String name) throws Exception {
        final Field field = context.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static Result scan(Prefilter.SeedProbeWorld world, int cx0, int cz0, int radius) {
        final Result result = new Result();
        Map<Long, TileEntity> tiles = java.util.Collections.emptyMap();
        world.beginOverlay();
        world.vanillaOracleReads = 0;
        world.vanillaChestWrites = 0;
        try {
            if (!world.virginProvider().generator().getClass().getName().equals("rwg2.world.ChunkGeneratorRealistic")) {
                throw new IllegalArgumentException("Vanilla dungeon replay requires RWG2's pinned generator");
            }
            final Class<?> context = Class.forName("com.gtnhspeedrun.determinism.worldgen.ChestFillContext");
            final Method enter = context.getMethod("enterPopulation");
            final Method leave = context.getMethod("leaveComponent");
            final Field refilled = counter(context, "refilled"), fallbacks = counter(context, "fallbacks");
            final int refillsBefore = refilled.getInt(null), fallbacksBefore = fallbacks.getInt(null);
            final Method fork = Class.forName("com.gtnhspeedrun.determinism.worldgen.TcForkUtil")
                .getMethod("fork", long.class, int.class, int.class, long.class);
            final WorldGenDungeons generator = new WorldGenDungeons();
            // A source chunk's anchors are chunk*16+[8,23], and room/chest reach is at most four blocks.
            // Thus an adjacent source chunk can contribute inside the requested chest-chunk window.
            // Generate the entire symmetric one-chunk halo, then clip containers, never just anchors.
            for (int cx = cx0 - radius - 1; cx <= cx0 + radius + 1; cx++) {
                for (int cz = cz0 - radius - 1; cz <= cz0 + radius + 1; cz++) {
                    if (!dungeonEvent(world, cx, cz)) continue;
                    final Random attempts = (Random) fork.invoke(null, world.getSeed(), cx, cz, 40L);
                    // The shared stream only chooses cosmetic masonry/spawner mobs. Room extent/placement
                    // use installed position forks; every inventory fill is checked against F10 below.
                    final Random cosmetic = new Random(RoguelikePrefilter.chunkSeed(world.getSeed(), cx, cz));
                    enter.invoke(null);
                    try {
                        for (int index = 0; index < 8; index++) {
                            final int x = cx * 16 + attempts.nextInt(16) + 8;
                            final int y = attempts.nextInt(128);
                            final int z = cz * 16 + attempts.nextInt(16) + 8;
                            result.attempts++;
                            world.vanillaScanActive = true;
                            final boolean built;
                            try {
                                built = generator.generate(world, cosmetic, x, y, z);
                            } finally {
                                world.vanillaScanActive = false;
                            }
                            if (built) {
                                result.built++;
                                result.rooms.add("{\"source_chunk\":[" + cx + "," + cz + "],\"attempt\":" + index
                                    + ",\"pos\":[" + x + "," + y + "," + z + "]}");
                            }
                        }
                    } finally {
                        leave.invoke(null);
                    }
                }
            }
            result.refills = refilled.getInt(null) - refillsBefore;
            if (result.attempts > 0 && world.vanillaOracleReads == 0) {
                throw new IllegalStateException("Probe virgin-oracle mixin did not run");
            }
            if (fallbacks.getInt(null) != fallbacksBefore || result.refills != world.vanillaChestWrites) {
                throw new IllegalStateException("Dungeon loot not fully position-refilled: " + result.refills
                    + " refills for " + world.vanillaChestWrites + " chest writes; stock fallback is not exact loot");
            }
            result.complete = true;
        } catch (Throwable error) {
            result.error = error.toString();
        } finally {
            world.vanillaScanActive = false;
            tiles = world.endOverlay();
        }
        try {
            final List<Long> positions = new ArrayList<>(tiles.keySet());
            java.util.Collections.sort(positions);
            for (final Long key : positions) {
                final TileEntity tile = tiles.get(key);
                if (!(tile instanceof TileEntityChest)) continue;
                if (!ExactLoot.inWindow(tile.xCoord, tile.zCoord, cx0, cz0, radius)) continue;
                final TileEntityChest chest = (TileEntityChest) tile;
                result.exactChests.add(capture("vanilla", chest, tile.xCoord, tile.yCoord, tile.zCoord));
                result.chests.add(WorldgenProbe.dumpInventory(chest, tile));
            }
        } catch (Throwable error) {
            result.complete = false;
            result.error = error.toString();
        }
        return result;
    }
}
