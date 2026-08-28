package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.gtnhspeedrun.determinism.worldgen.PendingSlices;
import com.gtnhspeedrun.determinism.worldgen.TerrainOracle;
import com.gtnhspeedrun.determinism.worldgen.WorldEditorAccess;

import greymerk.roguelike.worldgen.Coord;
import greymerk.roguelike.worldgen.MetaBlock;
import greymerk.roguelike.worldgen.WorldEditor;

/**
 * Turns Roguelike's per-dungeon WorldEditor into an order-independent OVERLAY (F5): READS return the dungeon's
 * own writes where it has written and VIRGIN terrain ({@link TerrainOracle}) everywhere else, so placement
 * decisions cannot see route-dependent population state. WRITES are routed through {@link PendingSlices} (F5
 * third pass): chunks whose slice-applier already ran get live writes, everything else is buffered and applied
 * at the END of the target chunk's own mod-worldgen phase — both orderings converge on "dungeon wins over that
 * chunk's decoration", killing the cross-population write race (launch-/route-dependent deep-chest existence)
 * with zero pop-in.
 *
 * <p>
 * One WorldEditor is created per dungeon (DungeonGenerator.generate), so the write-set lifetime matches the
 * dungeon's; visibility of OTHER dungeons' buffered writes is intentionally not provided (same per-editor
 * semantics the 0.3 overlay shipped). getTileEntity returns DETACHED tile entities for buffered container
 * writes — generation code (chest fill, spawner/skull config) mutates the detached instance and the applier
 * transplants its NBT when the chunk is ready.
 */
@Mixin(value = WorldEditor.class, remap = false)
public abstract class WorldEditorMixin implements WorldEditorAccess {

    @Shadow
    World world;

    @Shadow
    private Map<Block, Integer> stats;

    /**
     * Positions this editor wrote LIVE, mapped to the block it wrote. The block is recorded rather than re-read
     * from the world so that a live write and a buffered write answer reads identically: {@link PendingSlices}
     * routes a write live or buffered by chunk-applier state, which is a function of chunk generation order, and
     * {@code world.getBlock} would additionally report anything a later writer put on top.
     */
    @Unique
    private final Map<Long, Block> gtnhdet$written = new HashMap<>();

    @Unique
    private final Map<Long, PendingSlices.Write> gtnhdet$buffered = new HashMap<>();

    @Override
    public World gtnhdet$world() {
        return this.world;
    }

    @Unique
    private static long gtnhdet$key(Coord pos) {
        return ((long) (pos.getX() + 30_000_000) << 36) | ((long) (pos.getZ() + 30_000_000) << 8) | (pos.getY() & 0xFF);
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason Overlay read: own writes (buffered or live), everything else virgin. Stock discarded metadata too.
     */
    @Overwrite
    public MetaBlock getBlock(Coord pos) {
        final long key = gtnhdet$key(pos);
        final PendingSlices.Write wr = gtnhdet$buffered.get(key);
        if (wr != null && wr.block != null) {
            return new MetaBlock(wr.block);
        }
        final Block own = gtnhdet$written.get(key);
        if (own != null) {
            return new MetaBlock(own);
        }
        return new MetaBlock(TerrainOracle.block(world, pos.getX(), pos.getY(), pos.getZ()));
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason Overlay read (see getBlock).
     */
    @Overwrite
    public boolean isAirBlock(Coord pos) {
        final long key = gtnhdet$key(pos);
        final PendingSlices.Write wr = gtnhdet$buffered.get(key);
        if (wr != null && wr.block != null) {
            return wr.block == Blocks.air;
        }
        final Block own = gtnhdet$written.get(key);
        if (own != null) {
            return own == Blocks.air;
        }
        return TerrainOracle.block(world, pos.getX(), pos.getY(), pos.getZ()) == Blocks.air;
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason Fill gates evaluate against the overlay; the write itself is routed live-or-buffered by chunk
     *         applier state (PendingSlices) so the dungeon-vs-decoration contest is order-independent.
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

        if (PendingSlices.shouldBuffer(world, pos.getX(), pos.getZ())) {
            final PendingSlices.Write wr = PendingSlices
                .buffer(world, pos.getX(), pos.getY(), pos.getZ(), block.getBlock(), block.getMeta(), block.getFlag());
            gtnhdet$buffered.put(gtnhdet$key(pos), wr);
        } else {
            try {
                world.setBlock(pos.getX(), pos.getY(), pos.getZ(), block.getBlock(), block.getMeta(), block.getFlag());
            } catch (NullPointerException npe) {
                // ignore it. (stock behavior)
            }
            gtnhdet$written.put(gtnhdet$key(pos), block.getBlock());
        }

        final Block type = block.getBlock();
        final Integer count = stats.get(type);
        stats.put(type, count == null ? 1 : count + 1);

        return true;
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason Metadata writes follow the same routing: update a buffered write in place, buffer a metadata-only
     *         write for unapplied chunks, else write live.
     */
    @Overwrite
    public void setBlockMetadata(Coord pos, int meta) {
        final PendingSlices.Write wr = gtnhdet$buffered.get(gtnhdet$key(pos));
        if (wr != null) {
            wr.meta = meta;
            return;
        }
        if (PendingSlices.shouldBuffer(world, pos.getX(), pos.getZ())) {
            gtnhdet$buffered
                .put(gtnhdet$key(pos), PendingSlices.buffer(world, pos.getX(), pos.getY(), pos.getZ(), null, meta, 0));
            return;
        }
        world.setBlockMetadataWithNotify(pos.getX(), pos.getY(), pos.getZ(), meta, 2);
        // a metadata write leaves the block id alone; record whatever is there so later reads stay on the
        // own-write path rather than falling through to virgin terrain
        final long key = gtnhdet$key(pos);
        if (!gtnhdet$written.containsKey(key)) {
            gtnhdet$written.put(key, world.getBlock(pos.getX(), pos.getY(), pos.getZ()));
        }
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason Buffered container writes have no world TE yet: hand generation code a detached instance whose NBT
     *         the applier transplants. Live positions keep stock behavior.
     */
    @Overwrite
    public TileEntity getTileEntity(Coord pos) {
        final PendingSlices.Write wr = gtnhdet$buffered.get(gtnhdet$key(pos));
        if (wr != null && wr.block != null && wr.block.hasTileEntity(wr.meta)) {
            return PendingSlices.tileEntityFor(world, wr);
        }
        return world.getTileEntity(pos.getX(), pos.getY(), pos.getZ());
    }
}
