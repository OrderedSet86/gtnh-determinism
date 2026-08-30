package com.gtnhspeedrun.determinism;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraftforge.common.ChestGenHooks;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnhspeedrun.determinism.worldgen.PendingSlices;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.registry.VillagerRegistry;
import cpw.mods.fml.common.registry.VillagerRegistry.IVillageCreationHandler;

/**
 * GTNH Worldgen Determinism — single testing jar for the GTNH speedrun community. Makes world generation a pure
 * function of the world seed. Fixes carried (see the audit for mechanisms):
 * <ul>
 * <li>F1 — FML VillagerRegistry HashMap handler order (reflection fix below): village layouts identical per seed</li>
 * <li>F2 — Witchery worldgen clock RNG (mixin): covens/wicker men/shacks/goblin huts seed-stable</li>
 * <li>F3 — Thaumcraft draw skew, clock loot, gen thread (mixins): nodes/totems/barrows/rings seed-stable</li>
 * <li>F4 — GT ore vein live-terrain probe (mixin): vein identity seed-pure</li>
 * <li>F6 — RWG decoration draw skew + Math.random tree sizing (mixins)</li>
 * <li>TiC slime island clock-RNG shadowing bug (mixin)</li>
 * <li>BOP Math.random weighted flora roll + identity-HashMap walk (mixins)</li>
 * <li>ProjectRed lily colors rolled clock-random at worldgen (mixin)</li>
 * <li>Forestry village bee house world.rand flowers/frames/bee species (mixin)</li>
 * <li>F7 — loot-table session drift (below): only the FIRST world created per client session generated its spawn
 * region from pristine loot tables; FMLServerStartingEvent mutators (TooMuchLoot category rewrites) persist in the
 * static ChestGenHooks registry, so every later world creation in the same session rolled different spawn-window
 * chest loot from the same seed. Snapshot the tables at load-complete, restore before every server start.</li>
 * <li>F9 — spawn-preload loot split ({@link com.gtnhspeedrun.determinism.worldgen.EarlyLootTables}): a cold boot
 * generates its spawn region inside loadAllWorlds, before FMLServerStartingEvent, so chests there kept the
 * pre-TooMuchLoot table permanently while every later chest used the post-rewrite one. TooMuchLoot's XML is what
 * the pack intends, so it is applied before the first chunk exists and its own later run is suppressed.</li>
 * </ul>
 *
 * <p>
 * F7 and F9 compose: F7 restores the load-complete tables before each server start, F9 then re-applies
 * TooMuchLoot at loadAllWorlds, so every world in a session — first or fiftieth — generates against the same
 * post-rewrite table.
 */
@Mod(
    modid = GtnhDeterminism.MODID,
    version = Tags.VERSION,
    name = "GTNH Worldgen Determinism",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*")
public class GtnhDeterminism {

    public static final String MODID = "gtnhdeterminism";
    public static final Logger LOG = LogManager.getLogger(MODID);

    /**
     * F7: ChestGenHooks is a static registry that lives for the whole client session, but cold worlds generate
     * their spawn region during loadAllWorlds, BEFORE FMLServerStartingEvent mutates the tables (TooMuchLoot's
     * category rewrites). World #2+ of a session therefore preloaded its spawn region with the post-mutation
     * tables — same seed, different chests (field-confirmed 2026-07-24: 'coke 2' save vs corpus, 14-19 divergent
     * chests/world; the fresh-session 'demo world' save matched the probe byte-exact). Restoring the load-complete
     * snapshot before EVERY server start makes each world roll like the first of a cold session, which is also
     * what dedicated servers and the seedlib corpora produce.
     *
     * <p>
     * A category is three fields, not one. {@code countMin}/{@code countMax} decide how many stacks a chest draws,
     * and TooMuchLoot moves them: {@code villageBlacksmith} is 3-9 before its rewrite and 4-11 after. An earlier
     * version of this handler restored only {@code contents}, which rebuilt a table that never existed anywhere —
     * the pristine item pool with the mutated roll count — and the extra {@code generateChestContents} iterations
     * then shifted every later draw in that chunk. Snapshot and restore all three.
     */
    private Map<String, LootSnap> lootSnapshot;

    /** One category's full restorable state. {@code contents} is the live list's elements, copied out. */
    private static final class LootSnap {

        final List<Object> contents;
        final int min;
        final int max;

        LootSnap(List<Object> contents, int min, int max) {
            this.contents = contents;
            this.min = min;
            this.max = max;
        }
    }

    private static Field lootContentsField() throws Exception {
        final Field f = ChestGenHooks.class.getDeclaredField("contents");
        f.setAccessible(true);
        return f;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ChestGenHooks> chestInfo() throws Exception {
        final Field f = ChestGenHooks.class.getDeclaredField("chestInfo");
        f.setAccessible(true);
        return (Map<String, ChestGenHooks>) f.get(null);
    }

    @Mod.EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        // A worldgen crash can leave a dungeon's atomic slice window open; never let that outlive the world.
        PendingSlices.resetAtomicWindow();
        if (lootSnapshot == null) {
            LOG.warn("No load-complete loot snapshot — spawn-region loot may drift across worlds this session");
            return;
        }
        try {
            final Field contentsF = lootContentsField();
            int restored = 0;
            for (Map.Entry<String, ChestGenHooks> e : chestInfo().entrySet()) {
                final LootSnap want = lootSnapshot.get(e.getKey());
                if (want == null) continue; // category registered after load-complete; leave untouched
                final ChestGenHooks hooks = e.getValue();
                @SuppressWarnings("unchecked")
                final List<Object> live = (List<Object>) contentsF.get(hooks);
                if (live.size() != want.contents.size() || !live.equals(want.contents)
                    || hooks.getMin() != want.min
                    || hooks.getMax() != want.max) {
                    restored++;
                }
                live.clear();
                live.addAll(want.contents);
                hooks.setMin(want.min);
                hooks.setMax(want.max);
            }
            LOG.info("Loot tables reset to load-complete state for this world ({} categories were mutated)", restored);
        } catch (Exception e) {
            LOG.error("Could not restore loot tables — world-order loot drift not fixed this session", e);
        }
    }

    /**
     * F1: FML keeps village creation handlers in a HashMap keyed by Class (identity hash), so handler iteration —
     * which decides both RNG draw order and the weighted building-list order — reshuffles every JVM launch. Replace
     * the map with a TreeMap sorted by class name after all mods have registered. (Also captures the F7 loot
     * snapshot — load-complete is the last moment before server-lifecycle mutators run.)
     */
    @Mod.EventHandler
    public void loadComplete(FMLLoadCompleteEvent event) {
        // F5 third pass: per-chunk dungeon-slice applier — runs LAST in every chunk's mod-worldgen phase
        // (max weight) and consumes zero RNG draws, so it cannot shift any other generator's stream.
        cpw.mods.fml.common.registry.GameRegistry
            .registerWorldGenerator(new PendingSlices.SliceApplier(), Integer.MAX_VALUE);
        try {
            final Field contentsF = lootContentsField();
            final Map<String, LootSnap> snap = new HashMap<>();
            for (Map.Entry<String, ChestGenHooks> e : chestInfo().entrySet()) {
                final ChestGenHooks hooks = e.getValue();
                @SuppressWarnings("unchecked")
                final List<Object> contents = (List<Object>) contentsF.get(hooks);
                snap.put(e.getKey(), new LootSnap(new ArrayList<>(contents), hooks.getMin(), hooks.getMax()));
            }
            lootSnapshot = snap;
            LOG.info("Loot-table snapshot captured ({} categories)", snap.size());
        } catch (Exception e) {
            LOG.error("Could not snapshot loot tables — world-order loot drift not fixed", e);
        }
        try {
            final Field f = VillagerRegistry.class.getDeclaredField("villageCreationHandlers");
            f.setAccessible(true);
            final VillagerRegistry registry = VillagerRegistry.instance();
            @SuppressWarnings("unchecked")
            final Map<Class<?>, IVillageCreationHandler> old = (Map<Class<?>, IVillageCreationHandler>) f.get(registry);
            if (old instanceof TreeMap) return;
            final TreeMap<Class<?>, IVillageCreationHandler> sorted = new TreeMap<>(
                Comparator.comparing(Class::getName));
            sorted.putAll(old);
            f.set(registry, sorted);
            LOG.info("Village creation handler order is now deterministic ({} handlers)", sorted.size());
        } catch (Exception e) {
            LOG.error("Could not sort village creation handlers — village layouts will vary per launch", e);
        }
    }
}
