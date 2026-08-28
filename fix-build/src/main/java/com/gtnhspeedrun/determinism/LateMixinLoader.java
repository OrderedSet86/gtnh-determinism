package com.gtnhspeedrun.determinism;

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
            mixins.add("worldgen.ThaumcraftWorldGeneratorMixin");
            mixins.add("worldgen.WorldGenMoundMixin");
            mixins.add("worldgen.WorldGenEldritchRingMixin");
        }
        if (loadedMods.contains("TConstruct")) {
            mixins.add("worldgen.SlimeIslandGenMixin");
        }
        if (loadedMods.contains("gregtech")) {
            mixins.add("worldgen.WorldgenGTOreLayerMixin");
        }
        if (loadedMods.contains("BiomesOPlenty")) {
            mixins.add("worldgen.BiomeFeaturesMixin");
            mixins.add("worldgen.BOPBiomeDecoratorMixin");
            mixins.add("worldgen.WorldGenBOPGrassManagerMixin");
            mixins.add("worldgen.WorldGenBOPFlowerManagerMixin");
        }
        if (loadedMods.contains("witchery")) {
            mixins.add("worldgen.VillageWallMixin");
            mixins.add("worldgen.VillageWallGenTileMixin");
            mixins.add("worldgen.WitcheryWorldGeneratorMixin");
            mixins.add("worldgen.ComponentWickerManMixin");
        }
        if (loadedMods.contains("RWG")) {
            mixins.add("worldgen.RwgDecoForkMixin");
            mixins.add("worldgen.DecoBigTreeCtorMixin");
        }
        if (loadedMods.contains("ProjRed|Exploration")) {
            mixins.add("worldgen.TileLilyMixin");
        }
        if (loadedMods.contains("Forestry")) {
            mixins.add("worldgen.ComponentVillageBeeHouseMixin");
        }
        if (loadedMods.contains("lootgames")) {
            mixins.add("worldgen.LootGamesStructureGeneratorMixin");
        }
        if (loadedMods.contains("Roguelike")) {
            mixins.add("worldgen.WorldEditorMixin");
            mixins.add("worldgen.SpawnerMixin");
            mixins.add("worldgen.DungeonMixin");
            mixins.add("worldgen.TreasureChestMixin");
            mixins.add("worldgen.TreasureManagerMixin");
            mixins.add("worldgen.InventoryMixin");
            mixins.add("worldgen.MinimumSpanningTreeMixin");
            mixins.add("worldgen.RoguelikeRoomShuffleMixin");
            if (Boolean.getBoolean("gtnhdet.traceseg")) {
                mixins.add("worldgen.SegmentBaseTraceMixin");
                mixins.add("worldgen.SegmentFirePlaceTraceMixin");
                mixins.add("worldgen.SegmentGeneratorTraceMixin");
            }
        }
        return mixins;
    }
}
