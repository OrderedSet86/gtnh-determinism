package com.gtnhspeedrun.tcworldgenfix;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
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
 * </ul>
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
     * F1: FML keeps village creation handlers in a HashMap keyed by Class (identity hash), so handler iteration —
     * which decides both RNG draw order and the weighted building-list order — reshuffles every JVM launch. Replace
     * the map with a TreeMap sorted by class name after all mods have registered.
     */
    @Mod.EventHandler
    public void loadComplete(FMLLoadCompleteEvent event) {
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
