package com.gtnhspeedrun.splash;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

/**
 * Mod-targeting mixins, registered only when their mod is present.
 *
 * <p>
 * Unlike the fix jar these are <em>not</em> gated on {@code splash.enable}: the mixins load unconditionally and
 * branch on {@link Plots} at runtime. That is deliberate and is the same reason {@code gtnhdet.orepin} works the
 * way it does — both A/B arms then run the same jar with the same md5, so a difference between arms can never be
 * confounded with a difference in build. The cost is one predictable {@code Set.contains} per chunk per hook when
 * the amplifier is off, which is why {@link Plots} short-circuits on an empty set.
 *
 * <p>
 * {@code require} is left at Mixin's default of 1 per injector, so a target that moved fails the launch loudly
 * instead of silently producing a world with one missing column.
 */
@LateMixin
public class LateMixinLoader implements ILateMixinLoader {

    private static final Logger LOG = LogManager.getLogger("gtnhsplash");

    @Override
    public String getMixinConfig() {
        return "mixins.gtnhsplash.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        final List<String> mixins = new ArrayList<>();
        if (loadedMods.contains("TConstruct")) mixins.add("SlimeIslandForceMixin");
        if (loadedMods.contains("witchery")) mixins.add("WitcheryForceMixin");
        LOG.info("[splash] late mixins: {}", mixins);
        return mixins;
    }
}
