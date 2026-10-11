package com.gtnhspeedrun.worldgenprobe;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

/**
 * Hammer%-hunt item-category counter, applied to a rolled inventory at capture time.
 *
 * <p>
 * Counting on the {@link ItemStack}s rather than re-parsing emitted JSON keeps the gate exact and cheap: the
 * display name is the same string {@code WorldgenProbe.dumpInventory} serializes, so a gate verdict computed here
 * and a post-hoc recount over the JSONL row cannot disagree.
 *
 * <p>
 * Names are matched on {@code getDisplayName()} with {@code §} formatting codes stripped. GTNH's lang file renames
 * the material Alumite to Obzinite CLIENT-side only ({@code GregTech.lang}); the dedicated server writes
 * "Alumite", so every match here uses the server string (results/2026-08-29-chestloot README, the
 * Obzinite/Alumite gotcha).
 */
final class LootCounts {

    static final int STEEL = 0; // "Block of Steel" (Railcraft:cube meta 2)
    static final int ALUMITE_PLATE = 1; // "Alumite Large Plate" (TConstruct:heavyPlate meta 15) = Obzinite
    static final int ANY_PLATE = 2; // any "* Large Plate" (includes Alumite)
    static final int HAMMER_HEAD = 3; // any "* Hammer Head"
    static final int TOUGH_ROD = 4; // any "* Tough Rod"
    static final int N = 5;

    private LootCounts() {}

    /** Category counts over every stack, weighted by stack size. */
    static int[] count(IInventory inv) {
        final int[] out = new int[N];
        int size;
        try {
            size = inv.getSizeInventory();
        } catch (Exception e) {
            return out;
        }
        for (int i = 0; i < size; i++) {
            ItemStack st;
            try {
                st = inv.getStackInSlot(i);
            } catch (Exception e) {
                continue;
            }
            if (st == null || st.getItem() == null) continue;
            final String name = strip(displayName(st));
            if (name == null) continue;
            final int n = st.stackSize;
            if ("Block of Steel".equals(name)) out[STEEL] += n;
            if (name.endsWith(" Large Plate")) {
                out[ANY_PLATE] += n;
                if ("Alumite Large Plate".equals(name)) out[ALUMITE_PLATE] += n;
            }
            if (name.endsWith(" Hammer Head")) out[HAMMER_HEAD] += n;
            if (name.endsWith(" Tough Rod")) out[TOUGH_ROD] += n;
        }
        return out;
    }

    static void addInto(int[] acc, int[] add) {
        for (int i = 0; i < N; i++) acc[i] += add[i];
    }

    static boolean any(int[] c) {
        for (final int v : c) {
            if (v != 0) return true;
        }
        return false;
    }

    private static String displayName(ItemStack st) {
        try {
            return st.getDisplayName();
        } catch (Exception e) {
            return null;
        }
    }

    private static String strip(String s) {
        if (s == null || s.indexOf('§') < 0) return s;
        final StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '§') {
                i++; // skip the format code character too
                continue;
            }
            b.append(c);
        }
        return b.toString();
    }
}
