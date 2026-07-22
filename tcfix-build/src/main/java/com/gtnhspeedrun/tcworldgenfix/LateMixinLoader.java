package com.gtnhspeedrun.tcworldgenfix;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

@LateMixin
public class LateMixinLoader implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.tcworldgendeterminism.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        final List<String> mixins = new ArrayList<>();
        if (loadedMods.contains("Thaumcraft")) {
            mixins.add("ThaumcraftWorldGeneratorMixin");
            mixins.add("WorldGenMoundMixin");
        }
        if (loadedMods.contains("TConstruct")) {
            mixins.add("SlimeIslandGenMixin");
        }
        if (loadedMods.contains("gregtech")) {
            mixins.add("WorldgenGTOreLayerMixin");
        }
        if (loadedMods.contains("BiomesOPlenty")) {
            mixins.add("BiomeFeaturesMixin");
            mixins.add("BOPBiomeDecoratorMixin");
            mixins.add("WorldGenBOPGrassManagerMixin");
            mixins.add("WorldGenBOPFlowerManagerMixin");
        }
        return mixins;
    }
}
