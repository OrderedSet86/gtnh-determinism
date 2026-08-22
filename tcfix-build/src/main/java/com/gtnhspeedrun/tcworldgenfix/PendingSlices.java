package com.gtnhspeedrun.tcworldgenfix;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.chunk.IChunkProvider;

import cpw.mods.fml.common.IWorldGenerator;

/**
 * Per-chunk deferred write slices (F5, third pass — the write-race fix, user-approved option 2).
 *
 * <p>
 * Roguelike dungeons generate during their trigger chunk's population and write into neighbor chunks. Stock
 * behavior lets those writes land immediately; the neighbor's OWN later population (lakes, decoration) then
 * overwrites parts of the dungeon — so the final state depends on chunk generation order, i.e. the player's
 * route, and one deep chest can exist or not per launch. Tick-deferral was rejected (pop-in); instead every
 * dungeon write is routed here:
 *
 * <ul>
 * <li>If the target chunk's slice-applier has already run, the write goes to the live world — the dungeon
 * overwrites that chunk's finished decoration ("dungeon wins").</li>
 * <li>Otherwise the write is buffered and applied by {@link SliceApplier} at the END of the target chunk's own
 * mod-worldgen phase — after its lakes/decoration, so the dungeon wins there too. Both orderings converge on
 * the same final blocks: order-independent, and zero pop-in because application happens during the chunk's own
 * generation.</li>
 * </ul>
 *
 * <p>
 * Tile entities (chests, spawners, skulls, …) are configured by generation code on DETACHED instances created
 * by {@link #tileEntityFor}; at apply time the block is placed and the detached TE's NBT is transplanted. Chest
 * loot stays byte-identical either way because fills are position-seeded (0.3 loot decoupling). Pending slices
 * survive save/quit via {@link SliceSavedData} (a chunk that never repopulates before shutdown gets its slice on
 * next load instead of losing it — stock would have written immediately, so losing writes is never acceptable).
 *
 * <p>
 * The applier consumes ZERO draws from the mod-worldgen RNG (Forge reseeds per generator), so registering it does
 * not shift any other generator's stream.
 */
public final class PendingSlices {

    /** One deferred block write, in dungeon emission order. */
    public static final class Write {

        public final int x, y, z;
        public final Block block; // null = metadata-only write
        public int meta; // mutable: a later setBlockMetadata on a buffered position updates it in place
        public final int flag;
        public NBTTagCompound teTag; // reloaded-from-save TE data
        public TileEntity detached; // live detached TE being configured by generation code

        Write(int x, int y, int z, Block block, int meta, int flag) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.meta = meta;
            this.flag = flag;
        }
    }

    private static final Map<World, Map<Long, List<Write>>> PENDING = new WeakHashMap<>();
    private static final Map<World, Set<Long>> APPLIED = new WeakHashMap<>();

    /**
     * F5 fourth pass — the ATOMIC DUNGEON WINDOW.
     *
     * <p>
     * A dungeon builds during its trigger chunk's population, but its writes reach into neighbour chunks. Writing
     * into an ungenerated neighbour force-loads it, and that nested generation runs the neighbour's own
     * mod-worldgen phase — including {@link SliceApplier} — while the dungeon is still under construction. Two
     * things then go route-dependent, because WHICH neighbours are already loaded when the dungeon triggers is a
     * function of the player's route:
     *
     * <ul>
     * <li><b>Loot loss.</b> {@code TreasureChest} hands Roguelike's {@code Inventory} a reference to the DETACHED
     * chest tile entity, and loot rules run at the very END of {@code Dungeon.generate}. A mid-construction apply
     * transplants a <em>copy</em> of that TE (writeToNBT → createAndLoadEntity), so every item written afterwards
     * lands in an orphaned instance and is lost — the chest exists in the world but is empty.</li>
     * <li><b>Read flips.</b> Once a chunk is applied, {@link #shouldBuffer} routes the dungeon's later writes to
     * that chunk live, and the WorldEditor overlay switches that region from "read my own buffered writes" to
     * "read the live world" halfway through construction.</li>
     * </ul>
     *
     * <p>
     * While the window is held, {@link #apply} records the chunk as deferred instead of materialising it, and
     * {@link #shouldBuffer} keeps buffering for it, so a dungeon sees ONE frozen view of the world for its entire
     * construction. Everything flushes in ascending chunk order when the outermost window closes — still inside
     * the trigger chunk's population, so there is no pop-in and "dungeon wins over that chunk's decoration" is
     * unchanged. Nesting is counted: a cascade that triggers a second dungeon inside the first flushes once, at
     * the outer close.
     *
     * <p>
     * Set {@code -Dgtnhdet.atomicdungeon=false} to disable (A/B lever only — the pre-0.6 behaviour is route
     * dependent).
     */
    private static final boolean ATOMIC = !"false".equalsIgnoreCase(System.getProperty("gtnhdet.atomicdungeon"));

    private static int atomicDepth = 0;
    private static final Map<World, Set<Long>> DEFERRED = new WeakHashMap<>();

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private static Set<Long> deferred(World w) {
        Set<Long> s = DEFERRED.get(w);
        if (s == null) {
            s = new java.util.TreeSet<>(); // ascending chunk-key flush order, independent of discovery order
            DEFERRED.put(w, s);
        }
        return s;
    }

    /** Opens (or re-enters) the atomic construction window. Paired with {@link #endAtomic} by DungeonMixin. */
    public static synchronized void beginAtomic() {
        if (!ATOMIC) return;
        atomicDepth++;
        SliceTrace.log("dungeon-window OPEN depth={}", atomicDepth);
    }

    /** Closes one level; on the outermost close, flushes every chunk whose apply was deferred. */
    public static synchronized void endAtomic() {
        if (!ATOMIC) return;
        if (atomicDepth == 0) {
            GtnhDeterminism.LOG.warn("endAtomic with no open dungeon window — ignoring");
            return;
        }
        if (--atomicDepth > 0) {
            SliceTrace.log("dungeon-window CLOSE depth={}", atomicDepth);
            return;
        }
        for (final Map.Entry<World, Set<Long>> e : DEFERRED.entrySet()) {
            final World w = e.getKey();
            final Long[] keys = e.getValue()
                .toArray(new Long[0]);
            e.getValue()
                .clear();
            for (final Long key : keys) {
                final int cx = (int) (key >> 32);
                final int cz = (int) (key ^ ((long) cx << 32));
                SliceTrace.log("dungeon-window FLUSH chunk={},{}", cx, cz);
                applyNow(w, cx, cz);
            }
        }
        SliceTrace.log("dungeon-window CLOSE depth=0");
    }

    /**
     * Clears any window left open by a worldgen exception escaping {@code Dungeon.generate}, so a leak cannot
     * outlive the world that caused it. Called from FMLServerAboutToStartEvent.
     *
     * <p>
     * There is deliberately no finer-grained guard: the obvious one (a min-weight IWorldGenerator asserting the
     * window is closed) would also fire for chunks generated by cascade INSIDE a dungeon, where a non-zero depth
     * is correct, and would force-flush exactly the case this mechanism exists to protect. Within a session the
     * pairing is safe by construction, because an exception out of an IWorldGenerator is not caught by Forge's
     * GameRegistry.generateWorld — it takes the chunk populate, and the server, down with it.
     */
    public static synchronized void resetAtomicWindow() {
        if (atomicDepth != 0) {
            GtnhDeterminism.LOG.error("Dungeon window still open (depth {}) at server start — clearing", atomicDepth);
            atomicDepth = 0;
        }
        DEFERRED.clear();
    }

    private static Map<Long, List<Write>> pending(World w) {
        Map<Long, List<Write>> m = PENDING.get(w);
        if (m == null) {
            m = new HashMap<>();
            PENDING.put(w, m);
            SliceSavedData.load(w, m); // restore any slices persisted by a previous session
        }
        return m;
    }

    private static Set<Long> applied(World w) {
        Set<Long> s = APPLIED.get(w);
        if (s == null) {
            s = new HashSet<>();
            APPLIED.put(w, s);
        }
        return s;
    }

    /**
     * True if writes to this block position must be buffered: the target chunk's applier has not run this
     * session. Chunks populated in a PREVIOUS session count as applied unless they still carry a pending slice
     * (their populate completed back then; live writes are correct for them).
     */
    public static synchronized boolean shouldBuffer(World w, int x, int z) {
        final int cx = x >> 4, cz = z >> 4;
        final long key = chunkKey(cx, cz);
        if (applied(w).contains(key)) return false;
        // Inside an atomic window, a chunk whose applier we deferred must keep buffering: it is now
        // isTerrainPopulated, so the previous-session shortcut below would otherwise flip it live mid-dungeon.
        if (atomicDepth > 0 && deferred(w).contains(key)) return true;
        final Map<Long, List<Write>> pend = pending(w);
        final IChunkProvider cp = w.getChunkProvider();
        if (cp.chunkExists(cx, cz) && w.getChunkFromChunkCoords(cx, cz).isTerrainPopulated && !pend.containsKey(key)) {
            // populated in an earlier session and owed nothing — treat as applied
            applied(w).add(key);
            return false;
        }
        return true;
    }

    public static synchronized Write buffer(World w, int x, int y, int z, Block block, int meta, int flag) {
        final Write wr = new Write(x, y, z, block, meta, flag);
        final Map<Long, List<Write>> pend = pending(w);
        final long key = chunkKey(x >> 4, z >> 4);
        List<Write> list = pend.get(key);
        if (list == null) {
            list = new ArrayList<>();
            pend.put(key, list);
        }
        list.add(wr);
        SliceSavedData.markDirty(w);
        return wr;
    }

    /** Latest buffered write at a position (overlay reads must see buffered state), or null. */
    public static synchronized Write lookup(World w, int x, int y, int z) {
        final List<Write> list = pending(w).get(chunkKey(x >> 4, z >> 4));
        if (list == null) return null;
        for (int i = list.size() - 1; i >= 0; i--) {
            final Write wr = list.get(i);
            if (wr.x == x && wr.y == y && wr.z == z) return wr;
        }
        return null;
    }

    /**
     * Detached tile entity for a buffered container write: created by the block itself, coords stamped so
     * position-keyed logic (TreasureChest posKey, spawner forks) sees the real location. Generation code
     * configures this instance; apply() transplants its NBT into the world.
     */
    public static synchronized TileEntity tileEntityFor(World w, Write wr) {
        if (wr.detached == null) {
            wr.detached = wr.block.createTileEntity(w, wr.meta);
            if (wr.detached != null) {
                wr.detached.xCoord = wr.x;
                wr.detached.yCoord = wr.y;
                wr.detached.zCoord = wr.z;
            }
        }
        return wr.detached;
    }

    /**
     * Routing entry for worldgen that writes with plain world.setBlock (Witchery walls): live when the target
     * chunk's applier already ran, buffered otherwise. Mirrors WorldEditorMixin's routing.
     */
    public static boolean routeSetBlock(World w, int x, int y, int z, Block block, int meta, int flag) {
        if (shouldBuffer(w, x, z)) {
            buffer(w, x, y, z, block, meta, flag);
            return true;
        }
        return w.setBlock(x, y, z, block, meta, flag);
    }

    static synchronized void apply(World w, int cx, int cz) {
        if (atomicDepth > 0) {
            // A dungeon is mid-construction and this chunk's population just finished underneath it. Do NOT
            // materialise (that would snapshot still-unlooted chests) and do NOT mark applied (that would flip
            // the dungeon's remaining writes live). Both happen at the outermost endAtomic().
            deferred(w).add(chunkKey(cx, cz));
            SliceTrace.log("apply DEFERRED chunk={},{} depth={}", cx, cz, atomicDepth);
            return;
        }
        applyNow(w, cx, cz);
    }

    private static synchronized void applyNow(World w, int cx, int cz) {
        final long key = chunkKey(cx, cz);
        applied(w).add(key);
        final List<Write> list = pending(w).remove(key);
        if (list == null) return;
        SliceTrace.log("apply chunk={},{} writes={}", cx, cz, list.size());
        for (final Write wr : list) {
            if (wr.block == null) { // metadata-only
                w.setBlockMetadataWithNotify(wr.x, wr.y, wr.z, wr.meta, 2);
                continue;
            }
            w.setBlock(wr.x, wr.y, wr.z, wr.block, wr.meta, wr.flag == 0 ? 2 : wr.flag);
            NBTTagCompound tag = wr.teTag;
            if (wr.detached != null) {
                tag = new NBTTagCompound();
                wr.detached.writeToNBT(tag);
            }
            if (tag != null) {
                tag.setInteger("x", wr.x);
                tag.setInteger("y", wr.y);
                tag.setInteger("z", wr.z);
                final TileEntity te = TileEntity.createAndLoadEntity(tag);
                if (te != null) w.setTileEntity(wr.x, wr.y, wr.z, te);
            }
        }
        SliceSavedData.markDirty(w);
        GtnhDeterminism.LOG.debug("Applied pending dungeon slice: chunk {},{} ({} writes)", cx, cz, list.size());
    }

    /** Runs LAST in every chunk's mod-worldgen phase (max weight): applies that chunk's pending slice. */
    public static final class SliceApplier implements IWorldGenerator {

        @Override
        public void generate(Random random, int chunkX, int chunkZ, World world, IChunkProvider chunkGenerator,
            IChunkProvider chunkProvider) {
            apply(world, chunkX, chunkZ);
        }
    }

    /** Persists un-applied slices in the world save so quit-mid-generation cannot orphan a dungeon slice. */
    public static final class SliceSavedData extends WorldSavedData {

        private static final String ID = "gtnhdet_pending_slices";
        private NBTTagCompound stash = new NBTTagCompound();

        public SliceSavedData(String id) {
            super(id);
        }

        static void load(World w, Map<Long, List<Write>> into) {
            final SliceSavedData d = data(w);
            int n = 0;
            for (final Object keyObj : d.stash.func_150296_c()) { // getKeySet
                final String ck = (String) keyObj;
                final String[] parts = ck.split(",");
                final long key = chunkKey(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                final NBTTagList writes = d.stash.getTagList(ck, 10);
                final List<Write> list = new ArrayList<>();
                for (int i = 0; i < writes.tagCount(); i++) {
                    final NBTTagCompound t = writes.getCompoundTagAt(i);
                    final Block b = t.hasKey("b") ? Block.getBlockFromName(t.getString("b")) : null;
                    if (b == null && t.hasKey("b")) continue; // block gone from registry: drop write
                    final Write wr = new Write(
                        t.getInteger("x"),
                        t.getInteger("y"),
                        t.getInteger("z"),
                        b,
                        t.getInteger("m"),
                        t.getInteger("f"));
                    if (t.hasKey("te")) wr.teTag = t.getCompoundTag("te");
                    list.add(wr);
                    n++;
                }
                if (!list.isEmpty()) into.put(key, list);
            }
            if (n > 0) GtnhDeterminism.LOG.info("Restored {} pending dungeon-slice writes from save", n);
        }

        static void markDirty(World w) {
            data(w).setDirty(true);
        }

        private static SliceSavedData data(World w) {
            SliceSavedData d = (SliceSavedData) w.perWorldStorage.loadData(SliceSavedData.class, ID);
            if (d == null) {
                d = new SliceSavedData(ID);
                w.perWorldStorage.setData(ID, d);
            }
            return d;
        }

        @Override
        public void readFromNBT(NBTTagCompound nbt) {
            stash = nbt.getCompoundTag("slices");
        }

        @Override
        public void writeToNBT(NBTTagCompound nbt) {
            // serialize the CURRENT pending state of every world that maps to this storage; the stash read at
            // load time is replaced wholesale
            final NBTTagCompound out = new NBTTagCompound();
            synchronized (PendingSlices.class) {
                for (final Map.Entry<World, Map<Long, List<Write>>> we : PENDING.entrySet()) {
                    if (we.getKey().perWorldStorage.loadData(SliceSavedData.class, ID) != this) continue;
                    for (final Map.Entry<Long, List<Write>> ce : we.getValue()
                        .entrySet()) {
                        final int cx = (int) (ce.getKey() >> 32);
                        final int cz = (int) (ce.getKey() ^ ((long) cx << 32));
                        final NBTTagList writes = new NBTTagList();
                        for (final Write wr : ce.getValue()) {
                            final NBTTagCompound t = new NBTTagCompound();
                            t.setInteger("x", wr.x);
                            t.setInteger("y", wr.y);
                            t.setInteger("z", wr.z);
                            if (wr.block != null) t.setString("b", Block.blockRegistry.getNameForObject(wr.block));
                            t.setInteger("m", wr.meta);
                            t.setInteger("f", wr.flag);
                            if (wr.detached != null) {
                                final NBTTagCompound te = new NBTTagCompound();
                                wr.detached.writeToNBT(te);
                                t.setTag("te", te);
                            } else if (wr.teTag != null) {
                                t.setTag("te", wr.teTag);
                            }
                            writes.appendTag(t);
                        }
                        out.setTag(cx + "," + cz, writes);
                    }
                }
            }
            nbt.setTag("slices", out);
        }
    }

    private PendingSlices() {}
}
