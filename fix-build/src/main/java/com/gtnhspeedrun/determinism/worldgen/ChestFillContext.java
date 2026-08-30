package com.gtnhspeedrun.determinism.worldgen;

import java.util.Random;

import net.minecraft.entity.Entity;
import net.minecraft.inventory.IInventory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.util.WeightedRandomChestContent;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraftforge.common.ChestGenHooks;

import com.gtnhspeedrun.determinism.GtnhDeterminism;

/**
 * F10: makes the contents of every vanilla-style structure chest a pure function of where the chest is, instead of
 * a function of how far the shared populate {@code Random} had advanced when the chest was reached.
 *
 * <p>
 * {@code WeightedRandomChestContent.generateChestContents} is the single filler for village, mineshaft,
 * stronghold, pyramid, vanilla-dungeon, Witchery-component and minecart chests. Stock draws every item from the
 * chunk's populate {@code Random}, so a chest's contents depend on everything that consumed draws earlier in that
 * chunk — mod handlers on {@code PopulateChunkEvent.Pre}, an intersecting mineshaft, the components of its own
 * village that were built first. Each of those is itself only as stable as the terrain reads behind it.
 *
 * <p>
 * The fix refills the chest from a derived {@code Random} <em>after</em> the stock body has run. Running stock
 * first matters: every draw it would have made is still made on the populate stream, so nothing downstream of the
 * chest moves and the rest of the chunk generates exactly as before. Only the chest's contents change.
 *
 * <h2>What the fork is derived from</h2>
 *
 * When the chest belongs to a {@code StructureComponent} — every village, mineshaft, stronghold and pyramid chest
 * — the fork uses the component's XZ origin, its class name and the chest's <em>component-local</em> coordinates
 * rather than the chest's absolute position:
 *
 * <pre>
 * seed * 6364136223846793005 + (minX * 341873128712 + minZ * 132897987541
 *     + piece.hashCode() * 4987142
 *     + localX * 3129871
 *     + localY * 116129781
 *     + localZ)
 * </pre>
 *
 * Two reasons. First, a component's XZ origin and class are settled before any terrain exists, while its Y anchor
 * is only fixed when the first chunk box that intersects it calls {@code getAverageGroundLevel}; deriving from XZ
 * therefore makes the contents computable from a structure layout alone, which is what lets the seed-search
 * prefilter answer village loot without generating a single chunk. Second, it means a pack update that shifts
 * terrain height under a village no longer re-rolls its chests.
 *
 * <p>
 * Which chests get that treatment is decided by {@code StructureStartPartsMixin} and
 * {@code StructureComponentChestMixin} together, and there are two grades of it. A piece that calls vanilla's
 * {@code generateStructureChestContents} hands over genuine component-local coordinates, Y included. A piece that
 * fills its chests some other way — most of this pack's village pieces do — is only known by its bounding box, so
 * the local coordinates are {@code abs - box.min} and Y is deliberately excluded, because a mod piece's
 * {@code box.minY} is a nominal value rather than a ground anchor. See {@link #fork}.
 *
 * <p>
 * Chests with no component — {@code WorldGenDungeons} rooms, minecart chests — fall back to absolute position,
 * the same shape {@link com.gtnhspeedrun.determinism.mixins.worldgen.ChestAmuletVisMixin} and
 * {@code TileLilyMixin} use. Mixing constants are shared with those.
 *
 * <h2>Roll count and item pool</h2>
 *
 * Both are computed by the caller before the filler is entered — {@code ChestGenHooks.getItems(rand)} and
 * {@code getCount(rand)} — so both would otherwise stay stream-derived and the prefilter would still need the
 * populate prefix. They are captured here and recomputed from the fork. Their stock draws still happen; only the
 * values are overridden, so stream preservation is exact.
 *
 * <p>
 * The capture is a {@link ThreadLocal} because those two calls sit in the same argument list as the filler call —
 * verified in {@code StructureComponent.generateStructureChestContents} and {@code WorldGenDungeons.generate}. If
 * a caller does something else (a literal count, a hand-built item array), the recorded count will not match the
 * one that arrives at the filler and the chest is left exactly as stock rolled it. That fallback is counted and
 * logged once, so "we quietly did nothing" cannot pass for "we handled it".
 *
 * <h2>Balance</h2>
 *
 * The pool, the weights, the roll range and the weighted-draw algorithm are untouched — only the RNG source moves.
 * Per-chest distribution is stock's; what changes is that two chests no longer share a stream position.
 */
public final class ChestFillContext {

    /** The ChestGenHooks the caller drew its item array and count from, and the count it got. */
    private static final class Table {

        ChestGenHooks hooks;
        boolean itemsSeen;
        boolean countSeen;
        int count;
    }

    /** The structure component the chest belongs to, in the coordinates that exist before terrain does. */
    private static final class Site {

        String piece;
        int minX;
        int minY;
        int minZ;
        int maxX;
        int maxY;
        int maxZ;
        int localX;
        int localY;
        int localZ;
        /**
         * True when the caller handed us component-local coordinates directly. False when we only know the
         * component's box and have to subtract it from the chest's absolute position — see {@link #fork}.
         */
        boolean explicitLocal;
        /**
         * The piece itself, kept only so the trace can report its orientation. Never read by {@link #fork} — the
         * orientation is already baked into the local coordinates it derives.
         */
        Object component;
        /**
         * A population barrier rather than a real site. Chests filled above one belong to chunk population, not
         * to whatever piece is generating further up the stack — see ChunkPopulateBarrierMixin.
         */
        boolean barrier;
    }

    private static final ThreadLocal<Table> TABLE = new ThreadLocal<>();
    /**
     * A stack, not a single value: the piece-wide site pushed around {@code addComponentParts} stays live while
     * an inner {@code generateStructureChestContents} pushes its own more precise one, and popping the inner must
     * restore the outer rather than clear it.
     */
    private static final ThreadLocal<java.util.ArrayDeque<Site>> SITE = ThreadLocal
        .withInitial(java.util.ArrayDeque::new);
    /** Guards the nested filler call this class makes, so it does not re-enter its own refill. */
    private static final ThreadLocal<Boolean> REFILLING = new ThreadLocal<>();

    private static int fallbacks;
    private static int refilled;

    private ChestFillContext() {}

    // ------------------------------------------------------------------ capture

    public static void notedItems(ChestGenHooks hooks) {
        Table t = TABLE.get();
        if (t == null || t.hooks != hooks) {
            t = new Table();
            t.hooks = hooks;
            TABLE.set(t);
        }
        t.itemsSeen = true;
    }

    public static void notedCount(ChestGenHooks hooks, int count) {
        Table t = TABLE.get();
        if (t == null || t.hooks != hooks) {
            t = new Table();
            t.hooks = hooks;
            TABLE.set(t);
        }
        t.countSeen = true;
        t.count = count;
    }

    public static void enterComponent(String pieceClass, StructureBoundingBox box, int localX, int localY, int localZ,
        Object component) {
        final Site s = new Site();
        s.component = component;
        s.piece = pieceClass;
        s.minX = box == null ? 0 : box.minX;
        s.minY = box == null ? 0 : box.minY;
        s.minZ = box == null ? 0 : box.minZ;
        s.maxX = box == null ? 0 : box.maxX;
        s.maxY = box == null ? 0 : box.maxY;
        s.maxZ = box == null ? 0 : box.maxZ;
        s.localX = localX;
        s.localY = localY;
        s.localZ = localZ;
        s.explicitLocal = true;
        SITE.get()
            .push(s);
    }

    /**
     * The piece currently running {@code addComponentParts}, without knowing where inside it a chest will land.
     *
     * <p>
     * This is the general case. {@code StructureComponent.generateStructureChestContents} is <em>not</em> the
     * common path it was assumed to be: measured on daily-707, most village chests are filled by pieces that call
     * {@code WeightedRandomChestContent.generateChestContents} themselves — VillageNames' biome structures,
     * TinkerConstruct's {@code generateStructurePatternChestContents}, Railcraft's {@code placeChest}, Witchery's
     * same-named override that does not call super. Those chests were falling back to an absolute-position fork,
     * which is deterministic but moves when terrain moves. Wrapping the one vanilla call site that drives every
     * component covers all of them at once.
     */
    /**
     * Piece classes that have been seen generating, whether or not they filled a chest.
     *
     * <p>
     * The chest trace alone cannot tell "this piece has no chests" from "this piece was never measured", and a
     * consumer that treats the two alike will silently predict no chest for a blacksmith it has never seen. This
     * set closes that gap: a class present here with no chest rows is known-chestless.
     */
    private static final java.util.Set<String> SEEN_PIECES = java.util.Collections
        .synchronizedSet(new java.util.HashSet<>());

    public static void enterComponentBox(String pieceClass, StructureBoundingBox box, Object component,
        long worldSeed) {
        if (TRACE && TraceScope.emits(worldSeed)) {
            // Seed-scoped, for the same reason the chest trace is: a piece first seen in another world would
            // otherwise be recorded once and never attributed to the seed that actually contains it.
            final String k = worldSeed + "#" + pieceClass + "#" + coordBaseMode(component);
            if (SEEN_PIECES.add(k)) {
                GtnhDeterminism.LOG
                    .info("[piecetrace] seed={} piece={} mode={}", worldSeed, pieceClass, coordBaseMode(component));
            }
        }
        final Site s = new Site();
        s.component = component;
        s.piece = pieceClass;
        s.minX = box == null ? 0 : box.minX;
        s.minY = box == null ? 0 : box.minY;
        s.minZ = box == null ? 0 : box.minZ;
        s.maxX = box == null ? 0 : box.maxX;
        s.maxY = box == null ? 0 : box.maxY;
        s.maxZ = box == null ? 0 : box.maxZ;
        s.explicitLocal = false;
        SITE.get()
            .push(s);
    }

    /**
     * Marks the start of chunk population. Everything filled from here on has no structure component unless one
     * pushes its own site deeper in. Paired with {@link #leaveComponent()}.
     */
    public static void enterPopulation() {
        final Site s = new Site();
        s.barrier = true;
        SITE.get()
            .push(s);
    }

    public static void leaveComponent() {
        final java.util.ArrayDeque<Site> stack = SITE.get();
        if (!stack.isEmpty()) stack.pop();
        if (stack.isEmpty()) SITE.remove();
    }

    // ------------------------------------------------------------------ refill

    /** Chest filler hook. Returns without touching anything when the capture did not line up. */
    public static void refillChest(WeightedRandomChestContent[] items, IInventory inv, int count) {
        if (Boolean.TRUE.equals(REFILLING.get())) return;
        final Table t = consumeTable(count, "chest");
        if (t == null) return;
        final long fork = fork(inv);
        if (fork == 0L) return; // no world or no position: leave stock's roll, which is still deterministic
        final Random rand = new Random(fork);
        // A count the caller drew off the populate stream is re-derived; a literal it chose itself is already a
        // constant and is kept, so the two cases differ only in whether the fork spends a draw on the count.
        final int rolls = t.countSeen ? rollCount(t.hooks, rand) : count;
        final WeightedRandomChestContent[] pool = t.hooks.getItems(rand);
        REFILLING.set(Boolean.TRUE);
        try {
            clear(inv);
            // Re-enter the real filler rather than reimplementing it: any other mixin on this method (the Vis
            // Amulet position fix) then runs against the final inventory, whatever order the injectors ended up in.
            WeightedRandomChestContent.generateChestContents(rand, pool, inv, rolls);
        } finally {
            REFILLING.remove();
        }
        trace("chest", inv, rolls, t.hooks, t.countSeen);
        noteRefill("chest");
    }

    /** Dispenser filler hook — Witchery covens and jungle temples. */
    public static void refillDispenser(WeightedRandomChestContent[] items,
        net.minecraft.tileentity.TileEntityDispenser inv, int count) {
        if (Boolean.TRUE.equals(REFILLING.get())) return;
        final Table t = consumeTable(count, "dispenser");
        if (t == null) return;
        final long fork = fork(inv);
        if (fork == 0L) return;
        final Random rand = new Random(fork);
        // A count the caller drew off the populate stream is re-derived; a literal it chose itself is already a
        // constant and is kept, so the two cases differ only in whether the fork spends a draw on the count.
        final int rolls = t.countSeen ? rollCount(t.hooks, rand) : count;
        final WeightedRandomChestContent[] pool = t.hooks.getItems(rand);
        REFILLING.set(Boolean.TRUE);
        try {
            clear(inv);
            WeightedRandomChestContent.generateDispenserContents(rand, pool, inv, rolls);
        } finally {
            REFILLING.remove();
        }
        trace("dispenser", inv, rolls, t.hooks, t.countSeen);
        noteRefill("dispenser");
    }

    /**
     * A silent fix is indistinguishable from an absent one, so say once that the hook is live and then keep a
     * count. Both counters are read back by the verification runs via {@link #stats()}.
     */
    private static void noteRefill(String what) {
        if (refilled++ == 0) {
            GtnhDeterminism.LOG.info("Structure {} contents are now derived from position (F10 active)", what);
        }
    }

    /**
     * Accepts when the item array demonstrably came from a {@link ChestGenHooks} we saw.
     *
     * <p>
     * The roll count is allowed to be absent. Not every caller draws it: Witchery's {@code ComponentShack} passes a
     * literal 1 alongside {@code ChestGenHooks.getItems(dungeonChest, rand)}. A literal is already a constant, so
     * there is nothing to make deterministic and the contents can still be re-derived — refusing those chests would
     * leave them stream-derived for no gain. A count that WAS drawn but does not match the one that arrived means
     * the caller did something between the two calls, and that chest is left alone.
     */
    private static Table consumeTable(int count, String what) {
        final Table t = TABLE.get();
        TABLE.remove();
        if (t != null && t.itemsSeen && (!t.countSeen || t.count == count)) return t;
        if (fallbacks++ == 0) {
            // Name the caller. A fallback is the one path where this fix does nothing, and "did nothing" is
            // indistinguishable from "worked" unless the log says which chest it was and who filled it.
            GtnhDeterminism.LOG.warn(
                "Chest fill for a {} did not come from ChestGenHooks.getItems/getCount — leaving stock's roll. "
                    + "count={} captured=[items={} count={} value={}] category={} caller={}. "
                    + "Further occurrences are counted, not logged.",
                what,
                count,
                t != null && t.itemsSeen,
                t != null && t.countSeen,
                t == null ? -1 : t.count,
                t == null ? "none" : categoryOf(t.hooks),
                caller());
        }
        return null;
    }

    private static java.lang.reflect.Field categoryField;

    private static String categoryOf(ChestGenHooks hooks) {
        try {
            if (categoryField == null) {
                categoryField = ChestGenHooks.class.getDeclaredField("category");
                categoryField.setAccessible(true);
            }
            return String.valueOf(categoryField.get(hooks));
        } catch (Exception e) {
            return "?";
        }
    }

    /** First frame outside vanilla, Forge and this jar — i.e. the mod that placed the chest. */
    private static String caller() {
        for (StackTraceElement fr : new Throwable().getStackTrace()) {
            final String c = fr.getClassName();
            if (c.startsWith("net.minecraft") || c.startsWith("net.minecraftforge")
                || c.startsWith("cpw.")
                || c.startsWith("com.gtnhspeedrun")
                || c.startsWith("java.")) continue;
            return c + "." + fr.getMethodName() + ":" + fr.getLineNumber();
        }
        return "vanilla";
    }

    /** Stock's own formula, off the derived rand. Consumes a draw only when the range is non-empty, as stock does. */
    private static int rollCount(ChestGenHooks hooks, Random rand) {
        final int min = hooks.getMin(), max = hooks.getMax();
        return min < max ? min + rand.nextInt(max - min) : min;
    }

    private static void clear(IInventory inv) {
        for (int i = 0; i < inv.getSizeInventory(); i++) inv.setInventorySlotContents(i, null);
    }

    /**
     * 0 means "cannot derive" — no world, or an inventory that is neither a tile entity nor an entity. Callers
     * leave the chest alone in that case.
     */
    private static long fork(IInventory inv) {
        final World world;
        final int x, y, z;
        if (inv instanceof TileEntity) {
            final TileEntity te = (TileEntity) inv;
            world = te.getWorldObj();
            x = te.xCoord;
            y = te.yCoord;
            z = te.zCoord;
        } else if (inv instanceof Entity) {
            final Entity e = (Entity) inv;
            world = e.worldObj;
            x = MathHelper.floor_double(e.posX);
            y = MathHelper.floor_double(e.posY);
            z = MathHelper.floor_double(e.posZ);
        } else {
            return 0L;
        }
        if (world == null) return 0L;

        Site top = SITE.get()
            .peek();
        if (top != null && top.barrier) top = null; // population, not a component
        final Site s = top;
        // Component-local coordinates either way, but Y is handled differently in the two cases, and the reason
        // is measured rather than assumed.
        //
        // Caller-local: the piece handed us its own pre-offset local coordinates. Those are true structure
        // coordinates and Y among them is terrain-free, so it is used.
        //
        // Box-relative: we only know the piece's declared bounding box. Its XZ is settled at layout time, but its
        // minY is NOT a ground anchor for mod pieces — measured on daily-707, one PlainsWeaponsmith1 yields
        // localY 1, 49 and 14 in different villages, and PlainsStable2 yields -16, i.e. the chest sits below the
        // box origin. y - minY therefore just re-encodes terrain height, which would leave these chests exactly
        // as un-computable-from-a-layout as the absolute fork they replaced. Y is dropped instead.
        //
        // Dropping it is safe because no two distinct chests in one piece instance share a local XZ: every
        // apparent collision in the trace is one chest refilled once per intersecting chunk box, at identical
        // absolute coordinates, which the fork makes idempotent.
        final int lx = s == null ? 0 : s.explicitLocal ? s.localX : x - s.minX;
        final int ly = s == null ? 0 : s.explicitLocal ? s.localY : 0;
        final int lz = s == null ? 0 : s.explicitLocal ? s.localZ : z - s.minZ;
        final long local = s == null ? (x * 341873128712L + y * 132897987541L + z)
            : (s.minX * 341873128712L + s.minZ * 132897987541L
                + s.piece.hashCode() * 4987142L
                + lx * 3129871L
                + ly * 116129781L
                + lz);
        final long fork = world.getSeed() * 6364136223846793005L + local;
        return fork == 0L ? 1L : fork; // 0 is the sentinel; a real collision must not disable the fix
    }

    /** Counters for the verification runs: how many chests were re-derived, and how many fell back to stock. */
    public static String stats() {
        return refilled + " refilled, " + fallbacks + " fell back to stock";
    }

    // ------------------------------------------------------------------ chest-site trace

    /**
     * {@code -Dgtnhdet.chesttrace=true}: emit one line per refilled inventory giving the component-local site the
     * fork is derived from.
     *
     * <p>
     * This exists to build the seed-search prefilter's piece-to-chest-site table by <em>measurement</em>. The
     * prefilter already knows every village component's class and XZ box from the structure layout alone, so if
     * it also knows where each class puts its chests in component-local coordinates it can compute the same fork
     * and roll the same contents without generating a chunk. Transcribing those offsets out of decompiled sources
     * would make the table a second, drifting statement of what the code does; reading them off a real run
     * cannot drift.
     *
     * <p>
     * Inert unless the flag is set, and the flag is read once — this sits inside chunk population.
     */
    private static final boolean TRACE = Boolean.getBoolean("gtnhdet.chesttrace");

    /** The seed of the world this inventory lives in, or 0 when it has none (detached tile entity). */
    private static long worldSeedOf(IInventory inv) {
        try {
            final World w;
            if (inv instanceof TileEntity) {
                w = ((TileEntity) inv).getWorldObj();
            } else if (inv instanceof Entity) {
                w = ((Entity) inv).worldObj;
            } else {
                return 0L;
            }
            return w == null ? 0L : w.getSeed();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static int sizeOf(IInventory inv) {
        try {
            return inv.getSizeInventory();
        } catch (Exception e) {
            return -1;
        }
    }

    private static java.lang.reflect.Field coordBaseModeField;

    /**
     * The piece's orientation. Local coordinates are rotated by it, so the prefilter's chest-site table has to be
     * keyed by it — the same piece class yields different local XZ in each of its four orientations.
     */
    private static int coordBaseMode(Object component) {
        if (component == null) return -1;
        try {
            if (coordBaseModeField == null) {
                for (Class<?> c = component.getClass(); c != null; c = c.getSuperclass()) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (f.getType() == int.class
                            && ("coordBaseMode".equals(f.getName()) || "field_74885_f".equals(f.getName()))) {
                            f.setAccessible(true);
                            coordBaseModeField = f;
                            break;
                        }
                    }
                    if (coordBaseModeField != null) break;
                }
            }
            return coordBaseModeField == null ? -1 : coordBaseModeField.getInt(component);
        } catch (Exception e) {
            return -1;
        }
    }

    private static void trace(String what, IInventory inv, int rolls, ChestGenHooks hooks, boolean countSeen) {
        if (!TRACE) return;
        final long worldSeed = worldSeedOf(inv);
        if (!TraceScope.emits(worldSeed)) return;
        Site top = SITE.get()
            .peek();
        if (top != null && top.barrier) top = null; // population, not a component
        final Site s = top;
        // Both branches, matching fork(): a mineshaft corridor's chest is an EntityMinecartChest, not a tile
        // entity, and reporting a sentinel position for it would put nonsense local coordinates into the table
        // the prefilter is built from.
        final int x, y, z;
        if (inv instanceof TileEntity) {
            final TileEntity te = (TileEntity) inv;
            x = te.xCoord;
            y = te.yCoord;
            z = te.zCoord;
        } else if (inv instanceof Entity) {
            final Entity e = (Entity) inv;
            x = MathHelper.floor_double(e.posX);
            y = MathHelper.floor_double(e.posY);
            z = MathHelper.floor_double(e.posZ);
        } else {
            x = y = z = Integer.MIN_VALUE;
        }
        // Absolute XZ is emitted too, so the consumer can verify the piece box really contains the chest rather
        // than trust that enterComponent was paired with the chest that followed it.
        GtnhDeterminism.LOG.info(
            "[chesttrace] seed={} what={} piece={} src={} mode={} countdrawn={} min={},{},{} local={},{},{} "
                + "abs={},{},{} cat={} rolls={} tmin={} tmax={} size={} itype={} depth={} inbox={} caller={}",
            // A warm-probe run generates the server's own boot world before the requested seed's, and chests are
            // filled in both. Without this, the two are indistinguishable in the log and any per-seed analysis
            // silently mixes them — which is exactly what happened to the Witchery trace.
            worldSeed,
            what,
            s == null ? "none" : s.piece,
            s == null ? "absolute" : s.explicitLocal ? "caller-local" : "box-relative",
            s == null ? -1 : coordBaseMode(s.component),
            countSeen,
            s == null ? 0 : s.minX,
            s == null ? 0 : s.minY,
            s == null ? 0 : s.minZ,
            s == null ? 0 : s.explicitLocal ? s.localX : x - s.minX,
            // Matches what fork() actually mixes in: 0 for box-relative, since Y there is terrain, not structure.
            s == null ? 0 : s.explicitLocal ? s.localY : 0,
            s == null ? 0 : s.explicitLocal ? s.localZ : z - s.minZ,
            x,
            y,
            z,
            categoryOf(hooks),
            rolls,
            // The table's roll range AT GENERATION TIME. The prefilter reads the same range from a live
            // ChestGenHooks in a process that never generated this world; if the two ever disagree, every roll it
            // derives is off by a draw and the contents are wrong for a reason no chest diff would explain.
            hooks.getMin(),
            hooks.getMax(),
            // The inventory's slot count. generateChestContents places every stack at
            // rand.nextInt(getSizeInventory()), so a consumer that rolls into a differently sized inventory gets
            // a different slot for every item AND a different stream after it. Not all of these are 27-slot
            // chests: TinkerConstruct's PatternChestLogic has 30 and CraftingStationLogic 10.
            sizeOf(inv),
            // Container class, so the prefilter can emit the same "type" a full-gen report does and a diff
            // compares like with like rather than flagging every TiC chest as a type mismatch.
            inv.getClass()
                .getSimpleName(),
            // Site-stack depth, and the caller unconditionally. A site is supposed to be pushed and popped
            // around exactly one piece; if a leak ever leaves one on the stack, an unrelated chest filled later
            // inherits it and is silently attributed to the wrong piece. Depth plus caller makes that visible
            // instead of leaving it to be inferred from an implausible local coordinate.
            SITE.get()
                .size(),
            s == null ? "-" : (x >= s.minX && x <= s.maxX && y >= s.minY && y <= s.maxY && z >= s.minZ && z <= s.maxZ),
            caller());
    }
}
