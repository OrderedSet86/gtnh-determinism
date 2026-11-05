package com.gtnhspeedrun.worldgenprobe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact item/count gate shared by the two dungeon sources; no Minecraft dependency. */
final class ExactLoot {
    private static final Pattern SELECTOR = Pattern.compile(
        "(?:(roguelike|vanilla)/)?([A-Za-z0-9_.-]+:[A-Za-z0-9_./-]+)(?:@([0-9]+))?(?:=([0-9]+))?");

    static final class Requirement {
        final String source, id;
        final int meta, count;

        Requirement(String spec) {
            final Matcher m = SELECTOR.matcher(spec);
            if (!m.matches()) throw new IllegalArgumentException("Invalid exact item selector: " + spec);
            source = m.group(1);
            id = m.group(2);
            meta = Integer.parseInt(m.group(3) == null ? "0" : m.group(3));
            count = Integer.parseInt(m.group(4) == null ? "1" : m.group(4));
            if (meta > 32767 || count < 1) throw new IllegalArgumentException("Invalid metadata/count: " + spec);
        }

        String key() { return (source == null ? "" : source + "/") + id + "@" + meta; }
    }

    static final class Stack {
        final int slot, meta, count;
        final String id;
        Stack(int slot, String id, int meta, int count) {
            if (id == null || count < 0) throw new IllegalArgumentException("Invalid captured item");
            this.slot = slot;
            this.id = id;
            this.meta = meta;
            this.count = count;
        }
        String signature() { return slot + ":" + id + "@" + meta + "=" + count; }
    }

    static final class Chest {
        final String source;
        final int x, y, z;
        final List<Stack> items = new ArrayList<>();
        Chest(String source, int x, int y, int z) {
            this.source = source;
            this.x = x;
            this.y = y;
            this.z = z;
        }
        String key() { return x + "," + y + "," + z; }
        String signature() {
            final StringBuilder b = new StringBuilder(source);
            for (final Stack s : items) b.append('|').append(s.signature());
            return b.toString();
        }
    }

    final List<Requirement> requirements = new ArrayList<>();
    private final Map<String, Chest> chests = new LinkedHashMap<>();
    private final Map<String, Boolean> complete = new LinkedHashMap<>();
    private final int spawnX, spawnZ, distance;
    private String error;

    ExactLoot(String spec, int distance, int spawnX, int spawnZ, boolean rogue, boolean vanilla) {
        if (distance < 0) throw new IllegalArgumentException("Exact items require nonnegative lootdistance blocks");
        this.distance = distance;
        this.spawnX = spawnX;
        this.spawnZ = spawnZ;
        if (rogue) complete.put("roguelike", false);
        if (vanilla) complete.put("vanilla", false);
        for (final String value : spec.split(";", -1)) {
            final Requirement r = new Requirement(value);
            if (r.source != null && !complete.containsKey(r.source)) {
                throw new IllegalArgumentException(r.source + "/ requirement has no enabled source");
            }
            for (final Requirement previous : requirements) {
                if (r.key().equals(previous.key())) throw new IllegalArgumentException("Duplicate exact selector: " + r.key());
            }
            requirements.add(r);
        }
        if (complete.isEmpty()) throw new IllegalArgumentException("Exact items require an enabled dungeon source");
    }

    static boolean inWindow(int x, int z, int centerChunkX, int centerChunkZ, int radius) {
        return (long) (x >> 4) >= (long) centerChunkX - radius
            && (long) (x >> 4) <= (long) centerChunkX + radius
            && (long) (z >> 4) >= (long) centerChunkZ - radius
            && (long) (z >> 4) <= (long) centerChunkZ + radius;
    }

    static boolean withinDistance(int x, int z, int referenceX, int referenceZ, int blocks) {
        final long dx = (long) x - referenceX, dz = (long) z - referenceZ;
        // Minecraft's world coordinate bound keeps the squared sum safely within long.
        return dx * dx + dz * dz <= (long) blocks * blocks;
    }

    void add(Chest chest) {
        if (!complete.containsKey(chest.source)) throw new IllegalArgumentException("Disabled loot source: " + chest.source);
        final java.util.HashSet<Integer> slots = new java.util.HashSet<>();
        for (final Stack stack : chest.items) {
            if (!slots.add(stack.slot)) {
                fail("Duplicate inventory slot at " + chest.key());
                return;
            }
        }
        final Chest previous = chests.get(chest.key());
        if (previous == null) chests.put(chest.key(), chest);
        else if (!previous.signature().equals(chest.signature())) {
            fail("Conflicting containers at " + chest.key() + "; populated-world verification required");
        }
    }

    void finish(String source) {
        if (!complete.containsKey(source)) throw new IllegalArgumentException("Disabled loot source: " + source);
        complete.put(source, true);
    }

    void fail(String why) { if (error == null) error = why; }

    private long count(Requirement r) {
        long count = 0;
        for (final Chest chest : chests.values()) {
            if (r.source != null && !r.source.equals(chest.source)) continue;
            if (!withinDistance(chest.x, chest.z, spawnX, spawnZ, distance)) continue;
            for (final Stack s : chest.items) {
                if (r.id.equals(s.id) && r.meta == s.meta) count += s.count;
            }
        }
        return count;
    }

    /** Only reject a miss when none of the still-pending sources could supply that selector. */
    boolean provenMissing() {
        if (error != null) return false;
        for (final Requirement r : requirements) {
            if (count(r) >= r.count) continue;
            boolean pending = false;
            for (final Map.Entry<String, Boolean> source : complete.entrySet()) {
                if (!source.getValue() && (r.source == null || r.source.equals(source.getKey()))) pending = true;
            }
            if (!pending) return true;
        }
        return false;
    }

    boolean passed() {
        if (error != null || complete.containsValue(false)) return false;
        for (final Requirement r : requirements) if (count(r) < r.count) return false;
        return true;
    }

    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    String json() {
        final StringBuilder b = new StringBuilder("{\"distance_blocks\":").append(distance)
            .append(",\"metric\":\"horizontal_euclidean_inclusive\",\"reference\":{\"kind\":\"predicted_spawn\",\"verified\":false,\"x\":")
            .append(spawnX).append(",\"z\":").append(spawnZ).append("},\"sources\":{");
        boolean first = true;
        for (final Map.Entry<String, Boolean> source : complete.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append(quote(source.getKey())).append(":{\"complete\":").append(source.getValue()).append('}');
        }
        b.append("},\"requirements\":[");
        first = true;
        for (final Requirement r : requirements) {
            if (!first) b.append(',');
            first = false;
            final long found = count(r);
            b.append("{\"id\":").append(quote(r.id)).append(",\"meta\":").append(r.meta)
                .append(",\"source\":").append(r.source == null ? "null" : quote(r.source))
                .append(",\"required\":").append(r.count).append(",\"found\":").append(found)
                .append(",\"met\":").append(found >= r.count).append('}');
        }
        b.append("],\"matches\":[");
        first = true;
        for (final Chest chest : chests.values()) {
            if (!withinDistance(chest.x, chest.z, spawnX, spawnZ, distance)) continue;
            final List<String> matches = new ArrayList<>();
            for (final Stack s : chest.items) {
                for (final Requirement r : requirements) {
                    if (r.id.equals(s.id) && r.meta == s.meta && (r.source == null || r.source.equals(chest.source))) {
                        matches.add("{\"slot\":" + s.slot + ",\"id\":" + quote(s.id) + ",\"meta\":" + s.meta + ",\"count\":" + s.count + "}");
                        break;
                    }
                }
            }
            if (matches.isEmpty()) continue;
            if (!first) b.append(',');
            first = false;
            b.append("{\"source\":").append(quote(chest.source)).append(",\"pos\":[")
                .append(chest.x).append(',').append(chest.y).append(',').append(chest.z)
                .append("],\"distance_blocks\":").append(Math.hypot((double) chest.x - spawnX, (double) chest.z - spawnZ))
                .append(",\"items\":[").append(String.join(",", matches)).append("]}");
        }
        b.append("],\"complete\":").append(!complete.containsValue(false) && error == null)
            .append(",\"passed\":").append(passed());
        if (error != null) b.append(",\"error\":").append(quote(error));
        return b.append('}').toString();
    }
}
