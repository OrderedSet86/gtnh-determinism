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
        return "mixins.gtnhdeterminism.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        final List<String> mixins = new ArrayList<>();
        if (loadedMods.contains("Thaumcraft")) {
            mixins.add("ThaumcraftWorldGeneratorMixin");
            mixins.add("WorldGenMoundMixin");
            mixins.add("WorldGenEldritchRingMixin");
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
        if (loadedMods.contains("witchery")) {
            mixins.add("WitcheryWorldGeneratorMixin");
            mixins.add("ComponentWickerManMixin");
        }
        if (loadedMods.contains("RWG")) {
            mixins.add("RwgDecoForkMixin");
            mixins.add("DecoBigTreeCtorMixin");
        }
        if (loadedMods.contains("ProjRed|Exploration")) {
            mixins.add("TileLilyMixin");
        }
        if (loadedMods.contains("Forestry")) {
            mixins.add("ComponentVillageBeeHouseMixin");
        }
        if (loadedMods.contains("lootgames")) {
            mixins.add("LootGamesStructureGeneratorMixin");
        }
        if (loadedMods.contains("Roguelike")) {
            mixins.add("WorldEditorMixin");
            mixins.add("DungeonMixin");
            mixins.add("TreasureChestMixin");
            mixins.add("TreasureManagerMixin");
            mixins.add("InventoryMixin");
            mixins.add("MinimumSpanningTreeMixin");
            mixins.add("RoguelikeRoomShuffleMixin");
            if (Boolean.getBoolean("gtnhdet.traceseg")) {
                mixins.add("SegmentBaseTraceMixin");
                mixins.add("SegmentFirePlaceTraceMixin");
                mixins.add("SegmentGeneratorTraceMixin");
            }
        }
        return mixins;
    }
}
