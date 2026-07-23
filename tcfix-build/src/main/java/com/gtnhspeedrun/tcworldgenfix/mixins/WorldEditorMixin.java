package com.gtnhspeedrun.tcworldgenfix.mixins;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.tcworldgenfix.TerrainOracle;
import com.gtnhspeedrun.tcworldgenfix.WorldEditorAccess;

import greymerk.roguelike.worldgen.Coord;
import greymerk.roguelike.worldgen.MetaBlock;
import greymerk.roguelike.worldgen.WorldEditor;

/**
 * Turns Roguelike's per-dungeon WorldEditor into an order-independent OVERLAY view (F5, second pass):
 * block reads return the dungeon's OWN writes where it has written, and VIRGIN terrain ({@link TerrainOracle})
 * everywhere else. Every placement decision in dungeon generation funnels through getBlock/isAirBlock here
 * (canPlace, validGroundBlock, fillDown, spiral stairs, room/secret-room validity, setBlock's own fill gates), so
 * with this view the whole dungeon becomes a function of (seed, trigger region) — it can no longer see
 * route-dependent state (population-stage lava lakes, ores, decoration, other structures), which was still
 * relocating ~12 deep rooms and re-rolling loot between walk orders after the position fix.
 *
 * One WorldEditor is created per dungeon (DungeonGenerator.generate), so the write-set lifetime matches the
 * dungeon's. Stock getBlock already discards metadata, so the virgin MetaBlock matches stock semantics.
 * getTileEntity stays live (used to fill chests the dungeon just placed).
 */
@Mixin(value = WorldEditor.class, remap = false)
public abstract class WorldEditorMixin implements WorldEditorAccess {

    @Shadow
    World world;

    @Shadow
    private Map<Block, Integer> stats;

    @Unique
    private final Set<Long> tcfix$written = new HashSet<>();

    @Override
    public World tcfix$world() {
        return this.world;
    }

    @Unique
    private static long tcfix$key(Coord pos) {
        return ((long) (pos.getX() + 30_000_000) << 36) | ((long) (pos.getZ() + 30_000_000) << 8) | (pos.getY() & 0xFF);
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason Overlay read: own writes live, everything else virgin — placement decisions become route-independent.
     *         Stock discarded metadata here too.
     */
    @Overwrite
    public MetaBlock getBlock(Coord pos) {
        if (tcfix$written.contains(tcfix$key(pos))) {
            return new MetaBlock(world.getBlock(pos.getX(), pos.getY(), pos.getZ()));
        }
        return new MetaBlock(TerrainOracle.block(world, pos.getX(), pos.getY(), pos.getZ()));
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason Overlay read (see getBlock).
     */
    @Overwrite
    public boolean isAirBlock(Coord pos) {
        if (tcfix$written.contains(tcfix$key(pos))) {
            return world.isAirBlock(pos.getX(), pos.getY(), pos.getZ());
        }
        return TerrainOracle.block(world, pos.getX(), pos.getY(), pos.getZ()) == Blocks.air;
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason The fill gates (fillAir/replaceSolid) and the chest/spawner guard must evaluate against the overlay,
     *         not live state, or carving itself picks up route noise. Faithful to stock otherwise; records actual
     *         writes so later reads at those positions see them.
     */
    @Overwrite
    public boolean setBlock(Coord pos, MetaBlock block, boolean fillAir, boolean replaceSolid) {
        final MetaBlock currentBlock = this.getBlock(pos);

        if (currentBlock.getBlock() == Blocks.chest) return false;
        if (currentBlock.getBlock() == Blocks.trapped_chest) return false;
        if (currentBlock.getBlock() == Blocks.mob_spawner) return false;

        final boolean isAir = this.isAirBlock(pos);

        if (!fillAir && isAir) return false;
        if (!replaceSolid && !isAir) return false;

        try {
            world.setBlock(pos.getX(), pos.getY(), pos.getZ(), block.getBlock(), block.getMeta(), block.getFlag());
        } catch (NullPointerException npe) {
            // ignore it. (stock behavior)
        }
        tcfix$written.add(tcfix$key(pos));

        final Block type = block.getBlock();
        final Integer count = stats.get(type);
        stats.put(type, count == null ? 1 : count + 1);

        return true;
    }

    @Inject(method = "setBlockMetadata", at = @At("RETURN"))
    private void tcfix$recordMetaWrite(Coord pos, int meta, CallbackInfo ci) {
        tcfix$written.add(tcfix$key(pos));
    }
}
