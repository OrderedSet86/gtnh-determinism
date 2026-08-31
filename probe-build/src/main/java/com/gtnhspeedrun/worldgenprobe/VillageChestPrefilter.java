package com.gtnhspeedrun.worldgenprobe;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.minecraft.util.WeightedRandomChestContent;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraftforge.common.ChestGenHooks;

/**
 * Stage-0 village chest module: predicts the contents of a village's chests from its structure layout, without
 * generating a chunk.
 *
 * <p>
 * This is only possible because F10 derives a structure chest's contents from the piece it sits in rather than
 * from the chest's absolute position. The inputs to that fork — the piece's class, its bounding-box XZ origin, its
 * orientation, and the chest's piece-local XZ — are all settled at structure-layout time, which
 * {@code Prefilter.villageStarts()} already produces. Terrain height never enters it.
 *
 * <h2>The one hand-built input</h2>
 *
 * Where each piece class puts its chests is not derivable from the layout; it lives in each mod's
 * {@code addComponentParts}. That mapping is {@code chest-sites.json}, <em>measured</em> by running real worlds
 * under {@code -Dgtnhdet.chesttrace=true} rather than transcribed from decompiled sources. It is therefore the
 * one artifact here that can silently go stale — a pack update that moves a chest inside a piece, or adds a piece
 * class, invalidates rows without any error. Two guards follow from that:
 *
 * <ul>
 * <li>Piece classes absent from the table are <em>counted and reported</em>, never silently treated as
 * chestless. An unknown piece is the difference between "this village has no blacksmith chest" and "we do not
 * know", and those must not look alike in the output.</li>
 * <li>The table is keyed by orientation. A piece's local coordinates are rotated by {@code coordBaseMode}, so the
 * same class yields different local XZ in each of its four orientations.</li>
 * </ul>
 *
 * <h2>Known divergences, not yet quantified</h2>
 *
 * <ul>
 * <li><b>Y is not predicted.</b> The fork does not use it, so contents are right, but the emitted position
 * carries the piece's nominal box Y. Existence must be matched on {@code (x, z, piece, category)}.</li>
 * <li><b>Vis Amulet NBT.</b> {@code ChestAmuletVisMixin} derives an amulet's charge from the chest's absolute
 * position including Y, which this module does not know. Any amulet it predicts will carry the wrong charge.</li>
 * <li><b>Pieces pruned at build time.</b> A piece whose ground check fails is never built, and nothing here can
 * know that — the same residual the village piece module already carries.</li>
 * </ul>
 */
final class VillageChestPrefilter {

    /** One measured chest site inside a piece class at one orientation. */
    private static final class Site {

        String category;
        boolean countDrawn;
        /** "box-relative" (local = abs - box origin) or "caller-local" (pre-rotation structure coordinates). */
        String src;
        /**
         * Slot count of the real container. Not always 27 — generateChestContents draws
         * rand.nextInt(getSizeInventory()) per stack, so this decides both the slots and the stream after them.
         */
        int size;
        /** Container class name, emitted as the chest's "type" so a corpus diff compares like with like. */
        String type;
        int lx;
        int ly;
        int lz;
        /**
         * Optional guard: the site exists only when this holds. Empty means unconditional.
         *
         * <p>
         * Some pieces place a chest only in one variant — {@code StructureStrongholdPieces$Library} places its
         * second chest only in the tall variant, and {@code $RoomCrossing} places one only for room type 2. A
         * table entry without the guard would emit a chest that is not there, which is worse than emitting
         * nothing, so an unrecognised guard REFUSES the site instead of passing it.
         */
        String cond;
    }

    private static Map<String, List<Site>> table;
    /**
     * (piece, orientation) pairs observed generating that place no chest. Without this, "measured and chestless"
     * and "never measured" both look like an empty lookup, and the module would quietly under-predict.
     */
    private static Set<String> chestless;
    private static final Set<String> UNKNOWN = new LinkedHashSet<>();
    private static final Set<String> RANGES = new LinkedHashSet<>();
    /**
     * Chest sites this module deliberately refuses to predict, with the reason. Distinct from {@link #UNKNOWN}:
     * these are measured and understood, and known to be underivable from a structure layout.
     */
    private static final Set<String> UNPREDICTABLE = new LinkedHashSet<>();

    private VillageChestPrefilter() {}

    private static String key(String piece, int mode) {
        return piece + "#" + mode;
    }

    /**
     * Loads {@code chest-sites.json} from the probe jar. Hand-parsed: the probe has no JSON library on its
     * compile classpath and this file is generated by our own script, so its shape is known exactly.
     */
    private static synchronized Map<String, List<Site>> table() {
        if (table != null) return table;
        final Map<String, List<Site>> t = new HashMap<>();
        final Set<String> cl = new java.util.HashSet<>();
        chestless = cl;
        try (InputStream in = VillageChestPrefilter.class.getResourceAsStream("/chest-sites.json")) {
            if (in == null) {
                WorldgenProbe.LOG.warn("[prefilter] chest-sites.json missing from the probe jar");
                table = t;
                return t;
            }
            final StringBuilder sb = new StringBuilder();
            final char[] buf = new char[8192];
            try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                for (int n; (n = r.read(buf)) > 0;) sb.append(buf, 0, n);
            }
            final String all = sb.toString();
            final int split = all.indexOf("\"chestless\"");
            for (final String obj : (split < 0 ? all : all.substring(0, split)).split("\\}")) {
                if (!obj.contains("\"piece\"")) continue;
                final Site s = new Site();
                final String piece = str(obj, "piece");
                final int mode = (int) num(obj, "mode");
                s.category = str(obj, "category");
                s.countDrawn = obj.contains("\"countDrawn\": true");
                s.src = str(obj, "src");
                s.size = (int) num(obj, "size");
                s.type = str(obj, "type");
                s.lx = (int) num(obj, "lx");
                s.ly = (int) num(obj, "ly");
                s.lz = (int) num(obj, "lz");
                s.cond = str(obj, "cond");
                t.computeIfAbsent(key(piece, mode), k -> new ArrayList<>())
                    .add(s);
            }
            if (split >= 0) {
                for (final String obj : all.substring(split)
                    .split("\\}")) {
                    if (!obj.contains("\"piece\"")) continue;
                    cl.add(key(str(obj, "piece"), (int) num(obj, "mode")));
                }
            }
        } catch (Exception e) {
            WorldgenProbe.LOG.warn("[prefilter] could not read chest-sites.json: {}", e.toString());
        }
        WorldgenProbe.LOG.info(
            "[prefilter] chest-site table: {} (piece, orientation) keys with chests, {} known chestless",
            t.size(),
            cl.size());
        table = t;
        return t;
    }

    /** piece class name -> {min, max, } inclusive roll range plus the static array field, from the shared file. */
    private static Map<String, Object[]> noHooks;

    /**
     * The other half of F10's no-hooks rule, read from the SAME {@code chest-nohooks.json} the fix jar loads.
     *
     * <p>
     * These pieces pass their own compile-time {@code WeightedRandomChestContent[]} and their own roll count, so
     * there is no {@link ChestGenHooks} category to look up. F10 redraws the count from the chest's position fork
     * over the range recorded here; this predicts the same draw. The pool is read straight off the piece class's
     * static field, so it is the mod's real array and not a transcription of it.
     */
    private static synchronized Map<String, Object[]> noHooks() {
        if (noHooks != null) return noHooks;
        final Map<String, Object[]> m = new HashMap<>();
        try (InputStream in = VillageChestPrefilter.class.getResourceAsStream("/chest-nohooks.json")) {
            if (in == null) {
                WorldgenProbe.LOG.warn("[prefilter] chest-nohooks.json missing from the probe jar");
            } else {
                final StringBuilder sb = new StringBuilder();
                final char[] buf = new char[8192];
                try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    for (int n; (n = r.read(buf)) > 0;) sb.append(buf, 0, n);
                }
                final String all = sb.toString();
                for (final String obj : all.substring(Math.max(0, all.indexOf("\"sites\"")))
                    .split("\\}")) {
                    if (!obj.contains("\"piece\"")) continue;
                    final String piece = str(obj, "piece"), field = str(obj, "field");
                    final int min = (int) num(obj, "min"), max = (int) num(obj, "max");
                    if (!piece.isEmpty() && !field.isEmpty() && max >= min) {
                        m.put(piece, new Object[] { min, max, field });
                    }
                }
            }
        } catch (Exception e) {
            WorldgenProbe.LOG.warn("[prefilter] could not read chest-nohooks.json: {}", e.toString());
        }
        WorldgenProbe.LOG.info("[prefilter] no-hooks chest table: {} piece classes", m.size());
        noHooks = m;
        return m;
    }

    /** The mod's own loot array, off the piece class's static field. Null when it cannot be reached. */
    private static WeightedRandomChestContent[] noHooksPool(String piece, String field) {
        try {
            final java.lang.reflect.Field f = Class.forName(piece)
                .getDeclaredField(field);
            f.setAccessible(true);
            final Object v = f.get(null);
            return v instanceof WeightedRandomChestContent[] ? (WeightedRandomChestContent[]) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(String obj, String field) {
        final int i = obj.indexOf("\"" + field + "\":");
        if (i < 0) return "";
        final int a = obj.indexOf('"', i + field.length() + 3);
        final int b = obj.indexOf('"', a + 1);
        return a < 0 || b < 0 ? "" : obj.substring(a + 1, b);
    }

    private static long num(String obj, String field) {
        final int i = obj.indexOf("\"" + field + "\":");
        if (i < 0) return 0;
        int a = i + field.length() + 3;
        while (a < obj.length() && (obj.charAt(a) == ' ' || obj.charAt(a) == ':')) a++;
        int b = a;
        while (b < obj.length() && (Character.isDigit(obj.charAt(b)) || obj.charAt(b) == '-')) b++;
        return b > a ? Long.parseLong(obj.substring(a, b)) : 0;
    }

    /**
     * F10's fork, reproduced from {@code ChestFillContext.fork}. Box-relative sites carry {@code ly = 0} in the
     * table because the fix excludes Y for them, so this one expression covers both site grades.
     */
    static long fork(long seed, String piece, int minX, int minZ, int lx, int ly, int lz) {
        final long local = minX * 341873128712L + minZ * 132897987541L
            + piece.hashCode() * 4987142L
            + lx * 3129871L
            + ly * 116129781L
            + lz;
        final long f = seed * 6364136223846793005L + local;
        return f == 0L ? 1L : f;
    }

    /** A predicted chest: where it is in XZ, which piece and table it came from, and what is in it. */
    static final class Predicted {

        int x;
        int y;
        int z;
        String piece;
        String category;
        String itemsJson;
        /**
         * Null when {@link #itemsJson} is a real prediction. Otherwise the chest EXISTS at this position and its
         * contents are refused, with this as the reason.
         *
         * <p>
         * Position and contents fail independently, so collapsing them loses information the caller wants. A
         * Witchery village chest is the standing example: its site is measured and its world position follows
         * from the piece box, but its roll count comes off the populate stream, so the position is knowable and
         * the contents are not. Dropping the whole site would under-report where chests are; emitting a guessed
         * item list would be worse. The caller keeps these in a separate output field.
         */
        String reason;
    }

    /**
     * Predict every chest of one structure piece.
     *
     * @param nominalY the piece box's Y, emitted as-is — this module does not predict chest Y (see class javadoc)
     */
    static List<Predicted> predict(long seed, String piece, int mode, StructureBoundingBox box, int nominalY) {
        return predict(seed, piece, mode, box, nominalY, null);
    }

    /**
     * As above, with the piece instance available so conditional sites ({@link Site#cond}) can be evaluated.
     */
    static List<Predicted> predict(long seed, String piece, int mode, StructureBoundingBox box, int nominalY,
        Object component) {
        final List<Predicted> out = new ArrayList<>();
        final List<Site> sites = table().get(key(piece, mode));
        if (sites == null) {
            // Measured and chestless is a real answer; never measured is not. Only the latter is reported.
            if (!chestless.contains(key(piece, mode))) {
                UNKNOWN.add(hasPieceAtAnyMode(piece) ? piece + " (orientation " + mode + ")" : piece);
            }
            return out;
        }
        for (final Site s : sites) {
            if (s.cond != null && !s.cond.isEmpty() && !condHolds(s.cond, box, component)) continue;
            // Two coordinate conventions, and using the wrong one manufactures chests that do not exist.
            //
            // box-relative: the local values were derived as (absolute - box origin), so they are already
            // oriented and adding the origin back is exact.
            //
            // caller-local: the values are the piece's own PRE-rotation structure coordinates, the same ones
            // vanilla feeds to getXWithOffset/getZWithOffset. Adding the box origin to those was wrong and put
            // 14 phantom TinkerHouse chests in the output. The fork is unaffected either way — F10 mixes the raw
            // locals — so only the emitted position changes.
            final boolean boxRelative = "box-relative".equals(s.src);
            if (!boxRelative && (mode < 0 || mode > 3)) {
                // Vanilla passes the local coordinate straight through here, which is not a world position.
                UNPREDICTABLE.add(piece + " -> " + s.category + " (caller-local site, orientation undecided)");
                continue;
            }
            final int wx = boxRelative ? box.minX + s.lx : Prefilter.xWithOffset(box, mode, s.lx, s.lz);
            final int wz = boxRelative ? box.minZ + s.lz : Prefilter.zWithOffset(box, mode, s.lx, s.lz);
            final int wy = boxRelative ? nominalY + s.ly : Prefilter.yWithOffset(box, mode, s.ly);
            // A caller that did not draw its roll count through ChestGenHooks.getCount drew it somewhere else,
            // and measurement says that "somewhere else" is the populate stream: ComponentVillageBeeHouse fills
            // naturalistChest with 5, 7, 9 and 10 items in different villages. F10 keeps such a count as-is —
            // correctly, since re-deriving it would move the stream — so the chest's item count is not a function
            // of the layout and this module cannot predict it. Refuse rather than emit a plausible wrong chest.
            if (!s.countDrawn) {
                // F10 redraws this grade of chest from its position fork over the mod's own range; mirror it.
                final Object[] nh = noHooks().get(piece);
                if (nh != null && s.size > 0) {
                    final WeightedRandomChestContent[] pool = noHooksPool(piece, (String) nh[2]);
                    if (pool != null && pool.length > 0) {
                        final Random nr = new Random(fork(seed, piece, box.minX, box.minZ, s.lx, s.ly, s.lz));
                        final int min = (Integer) nh[0], max = (Integer) nh[1];
                        final int nrolls = min + nr.nextInt(max - min + 1);
                        final SizedInventory nchest = new SizedInventory(s.size);
                        try {
                            WeightedRandomChestContent.generateChestContents(nr, pool, nchest, nrolls);
                            final Predicted np = new Predicted();
                            np.x = wx;
                            np.y = wy;
                            np.z = wz;
                            np.piece = piece;
                            np.category = "(no-hooks)";
                            np.itemsJson = WorldgenProbe.dumpInventory(
                                nchest,
                                wx,
                                wy,
                                wz,
                                s.type == null || s.type.isEmpty() ? "TileEntityChest" : s.type);
                            out.add(np);
                            continue;
                        } catch (Throwable ignored) {}
                    }
                }
                // The recorded category is NOT emitted here, because for this grade of site it is not real. F10
                // used to refill these chests from whatever ChestGenHooks table was left in a ThreadLocal, and
                // which one that was depended on chunk generation order, so the trace that built the table
                // recorded a different category per run. Measured: ComponentVillageApothecary at one orientation
                // and one local coordinate appears as both WG:PHOTOWORKSHOP and naturalistChest.
                //
                // What these pieces actually do is pass their own compile-time WeightedRandomChestContent[] to
                // generateStructureChestContents. The pool is a constant; the ROLL COUNT is the blocker, drawn
                // mid-addComponentParts off that method's own Random, which stage 0 never runs.
                UNPREDICTABLE.add(piece + " (roll count drawn inside addComponentParts)");
                out.add(unpredicted(wx, wy, wz, piece, null, "roll count drawn inside addComponentParts"));
                continue;
            }
            final ChestGenHooks hooks = ChestGenHooks.getInfo(s.category);
            if (hooks == null) {
                UNPREDICTABLE.add(piece + " -> " + s.category + " (loot table not registered in this process)");
                out.add(unpredicted(wx, wy, wz, piece, s.category, "loot table not registered in this process"));
                continue;
            }
            // A category whose live range is 0..0 is almost certainly not registered in this process yet —
            // measured: towerChestContents reads 4..9 during real generation and 0..0 here. Rolling it would
            // emit a confidently empty chest.
            if (hooks.getMin() == 0 && hooks.getMax() == 0) {
                UNPREDICTABLE.add(s.category + " (loot table reads 0..0 in this process)");
                out.add(unpredicted(wx, wy, wz, piece, s.category, "loot table reads 0..0 in this process"));
                continue;
            }
            if (RANGES.add(s.category + " min=" + hooks.getMin() + " max=" + hooks.getMax())) {
                // Printed so it can be diffed against the tmin/tmax the chest trace recorded during real
                // generation. A silent disagreement here shifts every draw and looks like a fork bug.
                WorldgenProbe.LOG
                    .info("[prefilter] loot table {} min={} max={}", s.category, hooks.getMin(), hooks.getMax());
            }
            if (s.size <= 0) {
                // No measured slot count means no way to roll into the right container. Rolling into a guessed
                // one produces confident nonsense, so refuse.
                UNPREDICTABLE.add(piece + " -> " + s.category + " (no measured inventory size)");
                out.add(unpredicted(wx, wy, wz, piece, s.category, "no measured inventory size"));
                continue;
            }
            final Random rand = new Random(fork(seed, piece, box.minX, box.minZ, s.lx, s.ly, s.lz));
            // Exactly ChestFillContext.refillChest's order: the count draw happens first, and only when the
            // original caller drew one. Getting this backwards shifts every item in the chest.
            final int rolls = s.countDrawn ? rollCount(hooks, rand) : hooks.getMin();
            final WeightedRandomChestContent[] pool = hooks.getItems(rand);
            final SizedInventory chest = new SizedInventory(s.size);
            try {
                WeightedRandomChestContent.generateChestContents(rand, pool, chest, rolls);
            } catch (Throwable t) {
                continue;
            }
            final Predicted p = new Predicted();
            p.x = wx;
            // Y is emitted, never predicted: the fork does not use it. box-relative locals already had Y dropped
            // to 0 upstream; a caller-local Y is a real structure coordinate and takes vanilla's getYWithOffset.
            p.y = wy;
            p.z = wz;
            p.piece = piece;
            p.category = s.category;
            p.itemsJson = WorldgenProbe
                .dumpInventory(chest, wx, p.y, wz, s.type == null || s.type.isEmpty() ? "TileEntityChest" : s.type);
            out.add(p);
        }
        return out;
    }

    /**
     * Evaluates a {@link Site#cond} guard. Two forms are understood, both taken from the piece's own construction
     * rather than from generation state, so they answer before any terrain exists:
     *
     * <ul>
     * <li>{@code ysize>N} — the piece box's Y extent, which is how {@code Library} decides {@code isLargeRoom}.
     * <li>{@code field==N} — an int field on the piece, which is how {@code RoomCrossing} decides
     * {@code roomType}. The name may be either the SRG or the MCP form.
     * </ul>
     *
     * Anything else, or a field that cannot be read, returns false and is recorded: a guard that is not
     * understood must suppress its chest, never wave it through.
     */
    private static boolean condHolds(String cond, StructureBoundingBox box, Object component) {
        try {
            final int gt = cond.indexOf('>');
            if (cond.startsWith("ysize") && gt > 0) {
                return box.getYSize() > Integer.parseInt(
                    cond.substring(gt + 1)
                        .trim());
            }
            final int eq = cond.indexOf("==");
            if (eq > 0 && component != null) {
                final String[] names = cond.substring(0, eq)
                    .trim()
                    .split("/");
                final int want = Integer.parseInt(
                    cond.substring(eq + 2)
                        .trim());
                return Prefilter.findField(component.getClass(), names)
                    .getInt(component) == want;
            }
        } catch (Throwable ignored) {}
        UNPREDICTABLE.add("condition \"" + cond + "\" could not be evaluated");
        return false;
    }

    /** A chest whose position is known and whose contents this module refuses to guess. */
    private static Predicted unpredicted(int x, int y, int z, String piece, String category, String reason) {
        final Predicted p = new Predicted();
        p.x = x;
        p.y = y;
        p.z = z;
        p.piece = piece;
        p.category = category;
        p.reason = reason;
        return p;
    }

    private static boolean hasPieceAtAnyMode(String piece) {
        for (int m = -1; m < 4; m++) {
            if (table().containsKey(key(piece, m))) return true;
        }
        return false;
    }

    /** Stock's own count formula, off the derived rand — same as {@code ChestFillContext.rollCount}. */
    private static int rollCount(ChestGenHooks hooks, Random rand) {
        final int min = hooks.getMin(), max = hooks.getMax();
        return min < max ? min + rand.nextInt(max - min) : min;
    }

    /**
     * Piece classes encountered that the table does not cover. A caller that does not report these is claiming
     * coverage it does not have.
     */
    static Set<String> unknownPieces() {
        return UNKNOWN;
    }

    /** Sites skipped on purpose, and why. Reported so "predicted nothing" never reads as "there is nothing". */
    static Set<String> unpredictableSites() {
        return UNPREDICTABLE;
    }
}
