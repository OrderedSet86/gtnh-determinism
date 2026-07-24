package com.gtnhspeedrun.worldgenprobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

/**
 * Harness-only mixins, all opt-in via system properties so a probe jar sitting in mods/ without the
 * corresponding -D flags applies NOTHING and cannot perturb a run.
 */
@LateMixin
public class LateMixinLoader implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.worldgenprobe.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        final List<String> mixins = new ArrayList<>();
        if (Boolean.getBoolean("probe.parallelnoise") && loadedMods.contains("RWG")) {
            mixins.add("ChunkGeneratorRealisticMixin");
        }
        if (Boolean.getBoolean("probe.fastnoise") && loadedMods.contains("RWG")) {
            mixins.add("PerlinNoiseMixin");
        }
        return mixins;
    }
}
