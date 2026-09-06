package com.gtnhspeedrun.determinism.worldgen;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import net.minecraftforge.common.ChestGenHooks;

import com.gtnhspeedrun.determinism.GtnhDeterminism;

/**
 * F9: applies TooMuchLoot's category rewrites BEFORE the first chunk of a world is generated, so that one loot
 * table serves the whole world.
 *
 * <p>
 * Stock order on a dedicated server is {@code handleServerAboutToStart} then {@code loadAllWorlds} — which
 * constructs the {@code WorldServer}, searches for a spawn point and preloads a 25x25-chunk region — and only
 * then {@code FMLServerStartingEvent}, where TooMuchLoot replaces whole {@code ChestGenHooks} categories. In
 * 1.7.10 a chest is filled at the moment it is placed, so every chest inside the spawn preload keeps the
 * pre-rewrite table forever while every later chest uses the post-rewrite one. Ten categories differ;
 * {@code villageBlacksmith} goes from 60 entries rolling 3-9 stacks to 118 entries rolling 4-11.
 *
 * <p>
 * That split is an accident of boot ordering, not a design. TooMuchLoot's XML is what the pack intends, so the
 * fix is to make it the only table rather than to model two of them. Note the consequence: HungerOverhaul's
 * runtime food injection lands in the pre-rewrite table and TooMuchLoot replaces the category wholesale, so the
 * 1-32 marshmallow stacks that only existed inside the spawn preload no longer occur anywhere.
 *
 * <p>
 * {@link #apply()} mirrors {@code TooMuchLoot.serverStarting} exactly — snapshot the current table into
 * {@code lootTableCache}, publish {@code chestGenCategories}, then {@code ChestLootLoader.loadFiles} — minus the
 * {@code /chestloot} command registration, which needs the event object and is done by the mixin that suppresses
 * the duplicate run. Suppression is required rather than cosmetic: {@code loadFiles} is idempotent for
 * {@code OVERRIDE} groups (it {@code putAll}s fresh {@code ChestGenHooks} objects) but not for {@code ADD} ones,
 * which call {@code getInfo(category).addItem} against the live table and would duplicate every entry on a second
 * pass. GTNH ships 16 groups, all {@code OVERRIDE}, but a user's own XML need not be.
 *
 * <p>
 * {@code bonusChest} is deliberately exempt. It is not part of the preload split: the bonus chest is filled during
 * {@code WorldServer} construction ({@code createSpawnPosition} to {@code createBonusChest}), which stock reaches
 * before {@code FMLServerStartingEvent} on both sides, so stock always rolls it from the pre-rewrite table. Moving
 * the rewrite to the head of {@code loadAllWorlds} puts it ahead of that constructor too, which would hand a
 * one-shot world-creation gift TooMuchLoot's nerfed category — a balance change stock never made and F9 was never
 * meant to make. So the category is put back to its pre-rewrite state here and TooMuchLoot's version is installed
 * later, at the exact point its own handler would have run. Both the item list and the roll count travel with it.
 *
 * <p>
 * All access is reflective and every failure leaves {@link #consumeApplied()} false, which lets TooMuchLoot's own
 * handler run unchanged. The worst outcome of a break here is the stock split, not a crash.
 */
public final class EarlyLootTables {

    private static final String TML_MAIN = "dmillerw.tml.TooMuchLoot";
    private static final String TML_LOADER = "dmillerw.tml.data.chest.ChestLootLoader";

    /** Set by {@link #apply()}, consumed by the mixin on TooMuchLoot's own handler. Reset per server start. */
    private static boolean applied;

    /** TooMuchLoot's {@code bonusChest}, held back until its own handler would have installed it. */
    private static ChestGenHooks deferredBonusChest;

    private EarlyLootTables() {}

    /**
     * True exactly once per successful {@link #apply()}. Consuming resets the flag, so a later server start that
     * fails to apply early cannot accidentally suppress TooMuchLoot's own run.
     */
    public static synchronized boolean consumeApplied() {
        final boolean was = applied;
        applied = false;
        return was;
    }

    /**
     * Installs TooMuchLoot's {@code bonusChest} category, which {@link #apply()} held back so that the bonus chest
     * itself could be filled from the pre-rewrite table as stock does. Called from the mixin on TooMuchLoot's own
     * handler, so the category changes over at exactly the point stock changes it — anything reading
     * {@code bonusChest} after server start, such as {@code /chestloot}, still sees the pack's table.
     *
     * <p>
     * Consuming resets the held value, so a server start that never deferred cannot install a stale table from an
     * earlier one. A failure here leaves the pre-rewrite category in place, which is the stock-preserving
     * direction.
     */
    public static synchronized void installDeferredBonusChest() {
        final ChestGenHooks held = deferredBonusChest;
        deferredBonusChest = null;
        if (held == null) return;
        try {
            liveTable(Class.forName(TML_MAIN)).put(ChestGenHooks.BONUS_CHEST, held);
            GtnhDeterminism.LOG
                .info("bonusChest handed over to TooMuchLoot's table (rolls {}-{})", held.getMin(), held.getMax());
        } catch (Throwable t) {
            GtnhDeterminism.LOG.warn("Could not install TooMuchLoot's bonusChest: {}", t.toString());
        }
    }

    /** Called from the {@code MinecraftServer.loadAllWorlds} head injector; safe when TooMuchLoot is absent. */
    public static synchronized void apply() {
        applied = false;
        deferredBonusChest = null;
        final Class<?> main;
        try {
            main = Class.forName(TML_MAIN);
        } catch (ClassNotFoundException notInstalled) {
            return; // no TooMuchLoot: there is no split to close
        }
        try {
            if (getStaticBoolean(main, "failed")) {
                GtnhDeterminism.LOG
                    .warn("TooMuchLoot reports failed=true; leaving its loot tables alone (spawn-region split stays)");
                return;
            }
            final File lootFolder = (File) getStatic(main, "lootFolder");
            if (lootFolder == null || !lootFolder.isDirectory()) {
                GtnhDeterminism.LOG.warn("TooMuchLoot loot folder missing — spawn-region loot split not closed");
                return;
            }
            final File[] entries = lootFolder.listFiles();
            if (entries == null || entries.length == 0) {
                // First run: TooMuchLoot writes default XML from chestGenCategories before loading. Let its own
                // handler do that; this boot keeps the stock split and the next one will not.
                GtnhDeterminism.LOG.warn(
                    "TooMuchLoot loot folder is empty (first run) — deferring to its own handler, "
                        + "spawn-region loot split stays for this boot");
                return;
            }

            final Class<?> loader = Class.forName(TML_LOADER);
            final Map<String, ChestGenHooks> live = liveTable(main);

            final ChestGenHooks bonusBefore = live.get(ChestGenHooks.BONUS_CHEST);

            final Method copy = loader.getMethod("copyLootTable", Map.class);
            final Object cache = copy.invoke(null, live);
            setStatic(main, "lootTableCache", cache);
            @SuppressWarnings("unchecked")
            final HashMap<String, ChestGenHooks> cacheMap = (HashMap<String, ChestGenHooks>) cache;
            setStatic(
                main,
                "chestGenCategories",
                cacheMap.keySet()
                    .toArray(new String[0]));

            loader.getMethod("loadFiles", File.class)
                .invoke(null, lootFolder);

            // The bonus chest is filled inside the WorldServer constructor, which is downstream of this injection
            // point but upstream of where TooMuchLoot itself applies, so stock rolls it pre-rewrite. Put the
            // pre-rewrite category back and hand TooMuchLoot's version to the mixin that runs at stock's timing.
            //
            // Prefer the original object over copyLootTable's deep copy. An OVERRIDE group putAlls a fresh
            // ChestGenHooks, leaving the one that was there untouched, so restoring it is byte-identical to stock;
            // the copy is not, because round-tripping an ItemStack's NBT through a new compound reorders its keys.
            // ADD and REMOVE mutate the live object in place instead of replacing it, and are detectable by the
            // reference being unchanged — only then is the copy the one true pre-rewrite snapshot.
            final ChestGenHooks bonusAfter = live.get(ChestGenHooks.BONUS_CHEST);
            if (bonusBefore != null && bonusAfter != null) {
                final boolean replaced = bonusAfter != bonusBefore;
                final ChestGenHooks preBonus = replaced ? bonusBefore : cacheMap.get(ChestGenHooks.BONUS_CHEST);
                if (preBonus != null) {
                    deferredBonusChest = bonusAfter;
                    live.put(ChestGenHooks.BONUS_CHEST, preBonus);
                    GtnhDeterminism.LOG.info(
                        "bonusChest held at its pre-rewrite table ({} entries rolling {}-{}, not {} rolling {}-{}) "
                            + "— stock fills the bonus chest during WorldServer construction, before TooMuchLoot "
                            + "applies{}",
                        entryCount(main, preBonus),
                        preBonus.getMin(),
                        preBonus.getMax(),
                        entryCount(main, bonusAfter),
                        bonusAfter.getMin(),
                        bonusAfter.getMax(),
                        replaced ? "" : " (via copy: TooMuchLoot mutated the category in place)");
                }
            }

            applied = true;

            final ChestGenHooks blacksmith = live.get("villageBlacksmith");
            GtnhDeterminism.LOG.info(
                "TooMuchLoot applied before world load — one loot table for the whole world "
                    + "({} categories cached; villageBlacksmith now rolls {}-{})",
                cacheMap.size(),
                blacksmith == null ? -1 : blacksmith.getMin(),
                blacksmith == null ? -1 : blacksmith.getMax());
        } catch (Throwable t) {
            applied = false;
            GtnhDeterminism.LOG
                .error("Could not apply TooMuchLoot before world load — spawn-region loot split not closed", t);
        }
    }

    /**
     * Entry count of one category, read through the {@code ChestGenHooks.contents} handle TooMuchLoot already made
     * accessible. For a log line only — {@code getItems} would need a {@code Random} and is not worth the risk.
     */
    private static int entryCount(Class<?> main, ChestGenHooks hooks) {
        try {
            return ((java.util.List<?>) ((Field) getStatic(main, "contents")).get(hooks)).size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Forge's own {@code ChestGenHooks.chestInfo} map, reached through the {@code Field} handle TooMuchLoot already
     * made accessible in its {@code preInit}. This is the live registry every {@code getInfo} call reads.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, ChestGenHooks> liveTable(Class<?> main) throws Exception {
        final Field chestInfoF = (Field) getStatic(main, "chestInfo");
        return (Map<String, ChestGenHooks>) chestInfoF.get(ChestGenHooks.class);
    }

    private static Object getStatic(Class<?> cls, String name) throws Exception {
        final Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static boolean getStaticBoolean(Class<?> cls, String name) throws Exception {
        return Boolean.TRUE.equals(getStatic(cls, name));
    }

    private static void setStatic(Class<?> cls, String name, Object value) throws Exception {
        final Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }
}
