package com.gtnhspeedrun.worldgenprobe;

import java.util.LinkedHashMap;
import java.util.Map;

/** One finished metadata-6 part must yield at least one ingot; small parts never aggregate. */
final class ObsidianParts {
    private final Map<String, Boolean> sources = new LinkedHashMap<>();
    private final Map<String, ExactLoot.Chest> inventories = new LinkedHashMap<>();
    private String error;

    ObsidianParts(boolean rogue, boolean vanilla) {
        sources.put("village_workshop", false);
        if (rogue) sources.put("roguelike", false);
        if (vanilla) sources.put("vanilla", false);
    }

    // TinkersConstruct 1.14.123-GTNH: Pattern/WeaponryPattern costs * 144 / 2.
    static int melt(String registryID, int metadata) {
        if (metadata != 6) return 0;
        switch (registryID) {
            case "TConstruct:pickaxeHead": case "TConstruct:shovelHead": case "TConstruct:hatchetHead":
            case "TConstruct:swordBlade": case "TConstruct:frypanHead": case "TConstruct:signHead":
            case "TConstruct:arrowhead": return 144;
            case "TConstruct:BowLimbPart": return 216;
            case "TConstruct:toughRod": case "TConstruct:toughBinding": case "TConstruct:fullGuard": return 432;
            case "TConstruct:CrossbowLimbPart": return 576;
            case "TConstruct:CrossbowBodyPart": return 720;
            case "TConstruct:heavyPlate": case "TConstruct:broadAxeHead": case "TConstruct:scytheBlade":
            case "TConstruct:excavatorHead": case "TConstruct:largeSwordBlade": case "TConstruct:hammerHead": return 1152;
            default: return 0;
        }
    }

    void add(ExactLoot.Chest chest) {
        if (!sources.containsKey(chest.source)) throw new IllegalArgumentException("Disabled part source: " + chest.source);
        final java.util.HashSet<Integer> slots = new java.util.HashSet<>();
        for (final ExactLoot.Stack stack : chest.items) {
            if (stack.slot < 0 || !slots.add(stack.slot)) fail("Invalid/duplicate inventory slot at " + chest.key());
        }
        final String key = chest.source + ":" + chest.key();
        final ExactLoot.Chest previous = inventories.putIfAbsent(key, chest);
        if (previous != null && !previous.signature().equals(chest.signature())) fail("Conflicting inventories at " + key);
    }

    void finish(String source) {
        if (!sources.containsKey(source)) throw new IllegalArgumentException("Disabled part source: " + source);
        sources.put(source, true);
    }
    void fail(String why) { if (error == null) error = why; }
    boolean hasWitness() {
        for (final ExactLoot.Chest chest : inventories.values()) {
            for (final ExactLoot.Stack stack : chest.items) if (stack.count > 0 && melt(stack.id, stack.meta) >= 144) return true;
        }
        return false;
    }
    // An existential witness settles the decision without claiming that uninspected sources are absent.
    boolean complete() { return error == null && (hasWitness() || !sources.containsValue(false)); }
    boolean passed() { return complete() && hasWitness(); }

    String json() {
        final StringBuilder b = new StringBuilder("{\"minimum_mb_per_item\":144,\"material_metadata\":6,\"scope\":\"village_workshops_and_enabled_dungeon_scans\",\"sources\":{");
        boolean first = true;
        for (final Map.Entry<String, Boolean> source : sources.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append(ExactLoot.quote(source.getKey())).append(":{\"complete\":").append(source.getValue());
            if (!source.getValue()) b.append(",\"reason\":").append(ExactLoot.quote(
                passed() ? "not_needed_after_verified_witness" : "not_scanned_or_incomplete"));
            b.append('}');
        }
        b.append("},\"decision\":\"existential_single_item\",\"village_capture\":\"real_workshop_components_population_windows_verified_position_refills\",\"physical_route_verified\":false,\"inventories_scanned\":")
            .append(inventories.size()).append(",\"matches\":[");
        first = true;
        for (final ExactLoot.Chest chest : inventories.values()) {
            for (final ExactLoot.Stack stack : chest.items) {
                final int mb = melt(stack.id, stack.meta);
                if (stack.count <= 0 || mb < 144) continue;
                if (!first) b.append(',');
                first = false;
                b.append("{\"source\":").append(ExactLoot.quote(chest.source)).append(",\"position\":[")
                    .append(chest.x).append(',').append(chest.y).append(',').append(chest.z)
                    .append("],\"slot\":").append(stack.slot).append(",\"registryID\":").append(ExactLoot.quote(stack.id))
                    .append(",\"metadata\":").append(stack.meta).append(",\"count\":").append(stack.count)
                    .append(",\"mb_per_item\":").append(mb).append('}');
            }
        }
        b.append("],\"complete\":").append(complete()).append(",\"passed\":").append(passed());
        if (!complete()) b.append(",\"error\":").append(ExactLoot.quote(error == null ? "Enabled part source incomplete" : error));
        return b.append('}').toString();
    }
}
