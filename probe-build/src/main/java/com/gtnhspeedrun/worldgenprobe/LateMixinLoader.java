package com.gtnhspeedrun.worldgenprobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

/**
 * Harness-only mixins, all opt-in via system properties so a probe jar sitting in mods/ without the
 * corresponding -D flags applies NOTHING and cannot perturb a run.
 */
@LateMixin
public class LateMixinLoader implements ILateMixinLoader {

    private static final Logger LOG = LogManager.getLogger("worldgenprobe");

    /**
     * Both noise fast paths are {@code @Overwrite}s replicating RWG alpha-1.5.0. RWG PR #17 (shipped as alpha-1.5.2,
     * which packs from 2.9 onwards carry) rewrote the blend stages and deleted {@code parabolicFieldTotal}, so on
     * 1.5.2 these bodies would compute different terrain from the generator they replace. Presence of that field is
     * the version discriminator; without it the fast paths must not apply at all.
     */
    private static boolean isRwg150() {
        return ClassShape.hasMember("rwg.world.ChunkGeneratorRealistic", "parabolicFieldTotal");
    }

    @Override
    public String getMixinConfig() {
        return "mixins.worldgenprobe.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        final List<String> mixins = new ArrayList<>();
        final boolean wantParallel = Boolean.getBoolean("probe.parallelnoise");
        final boolean wantFast = Boolean.getBoolean("probe.fastnoise");
        if (!wantParallel && !wantFast) return mixins;
        if (!loadedMods.contains("RWG")) return mixins;
        if (!isRwg150()) {
            LOG.warn(
                "[probe] RWG is not alpha-1.5.0 — probe.parallelnoise/probe.fastnoise unavailable, using stock noise");
            return mixins;
        }
        if (wantParallel) mixins.add("ChunkGeneratorRealisticMixin");
        if (wantFast) mixins.add("PerlinNoiseMixin");
        return mixins;
    }
}
