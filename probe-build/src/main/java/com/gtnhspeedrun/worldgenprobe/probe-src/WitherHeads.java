package com.gtnhspeedrun.worldgenprobe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Placed skulls only; inventory skull items never enter this collector. */
final class WitherHeads {
    final int required;
    private final Map<String, int[]> skulls = new LinkedHashMap<>();
    private boolean complete;
    private String error;
    private int dungeons;

    WitherHeads(int required) {
        if (required < 1) throw new IllegalArgumentException("witherheads must be a positive integer");
        this.required = required;
    }

    void add(List<int[]> positions) {
        dungeons++;
        for (final int[] p : positions) {
            if (p.length != 4 || p[3] < 0 || p[3] > 5) {
                fail("Invalid captured skull type/position");
                continue;
            }
            final String key = p[0] + "," + p[1] + "," + p[2];
            final int[] previous = skulls.putIfAbsent(key, p);
            if (previous != null && previous[3] != p[3]) fail("Conflicting placed skull types at " + key);
        }
    }

    void finish() { complete = true; }
    void fail(String why) { if (error == null) error = why; }
    boolean complete() { return complete && error == null; }
    int found() {
        int count = 0;
        for (final int[] p : skulls.values()) if (p[3] == 1) count++;
        return count;
    }
    boolean passed() { return complete() && found() >= required; }

    String json() {
        final StringBuilder b = new StringBuilder("{\"required\":").append(required)
            .append(",\"found\":").append(found())
            .append(",\"source\":\"roguelike\",\"scope\":\"enabled_trigger_scan\",\"dungeons_scanned\":")
            .append(dungeons).append(",\"positions\":[");
        boolean first = true;
        for (final int[] p : skulls.values()) {
            if (p[3] != 1) continue;
            if (!first) b.append(',');
            first = false;
            b.append("{\"position\":[").append(p[0]).append(',').append(p[1]).append(',').append(p[2])
                .append("],\"type\":1}");
        }
        b.append("],\"complete\":").append(complete()).append(",\"passed\":").append(passed());
        if (!complete()) b.append(",\"error\":").append(ExactLoot.quote(error == null ? "Roguelike scan incomplete" : error));
        return b.append('}').toString();
    }
}
