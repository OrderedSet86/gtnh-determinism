package com.gtnhspeedrun.villagedeterminism;

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
 * Makes modded village building layouts deterministic for a given world seed.
 *
 * FML's VillagerRegistry keeps village creation handlers in a HashMap keyed by Class. Class hashes by identity, so
 * handler iteration order — which decides both the order handlers consume draws from the village layout RNG and the
 * order of the weighted building list vanilla walks — is different on every JVM launch. With several
 * handler-registering
 * mods installed (Witchery, VillageNames, TinkersConstruct, Railcraft, Forestry, WitchingGadgets in GTNH), village
 * layouts scramble across game restarts even on a fixed seed.
 *
 * This mod replaces the map with a TreeMap sorted by class name after all mods have registered. Lookup semantics are
 * unchanged; iteration becomes stable. (GTNH speedrun determinism audit, finding F1.)
 */
@Mod(
    modid = VillageDeterminism.MODID,
    version = Tags.VERSION,
    name = "VillageDeterminism",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*")
public class VillageDeterminism {

    public static final String MODID = "villagedeterminism";
    public static final Logger LOG = LogManager.getLogger(MODID);

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
            LOG.info(
                "Village creation handler order is now deterministic ({} handlers): {}",
                sorted.size(),
                sorted.keySet());
        } catch (Exception e) {
            LOG.error("Could not sort village creation handlers — village layouts will vary per launch", e);
        }
    }
}
