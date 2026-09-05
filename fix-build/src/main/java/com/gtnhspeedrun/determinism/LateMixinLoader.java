package com.gtnhspeedrun.determinism;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;
import com.gtnhspeedrun.determinism.worldgen.GtOrePin;

@LateMixin
public class LateMixinLoader implements ILateMixinLoader {

    private static final Logger LOG = LogManager.getLogger(GtnhDeterminism.MODID);

    /**
     * F4 has two implementations because GregTech moved the probe it corrects. Up to 5.09.51.482 (packs 2.7.4
     * through 2.8.4) the vein reroll asks {@code Block.isReplaceableOreGen}; 5.09.54.x deleted that call and asks
     * {@code StoneType.findStoneType} instead. Pick by which shape is actually installed, not by a version string,
     * so a pack that lands between the two known lines still gets whichever mixin can bind.
     *
     * <p>
     * Getting this wrong used to be silent: the pre-54 mixin was {@code require = 0}, so on 5.09.54.x it applied,
     * bound nothing, and left ore veins route-dependent with no log line. Both mixins are {@code require = 1} now
     * and this method is what keeps that safe.
     */
    private static String gtOreMixin() {
        if (ClassShape.hasClass("gregtech.api.enums.StoneType")) {
            LOG.info("GT ore-vein probe: StoneType variant (GT 5.09.54.x or later)");
            return "worldgen.WorldgenGTOreLayerStoneTypeMixin";
        }
        LOG.info("GT ore-vein probe: isReplaceableOreGen variant (GT 5.09.51.482 or earlier)");
        return "worldgen.WorldgenGTOreLayerMixin";
    }

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
            mixins.add("worldgen.ThaumcraftInitLootMixin");
            mixins.add("worldgen.ChestAmuletVisMixin");
        }
        if (loadedMods.contains("TConstruct")) {
            mixins.add("worldgen.SlimeIslandGenMixin");
        }
        if (loadedMods.contains("TML")) {
            // F9's second half. The first half is MinecraftServerLootMixin, which is unconditional because
            // MinecraftServer loads long before late mixins run; it no-ops when TooMuchLoot is absent.
            mixins.add("worldgen.TooMuchLootServerStartingMixin");
        }
        if (loadedMods.contains("gregtech")) {
            mixins.add(gtOreMixin());
            // F4d: vein IDENTITY is chosen by whichever chunk triggers the region first. These pin the decision
            // to the oreseed chunk and virginise the reads it still makes. Registered UNCONDITIONALLY and at
            // require = 1 so a binding failure is loud in BOTH arms of an A/B — the behaviour is switched by
            // gtnhdet.orepin inside the handlers, not by which mixins load, so that "off" is bit-identical to
            // stock and the two arms are the same jar. Gated only on the 5.09.54+ class shape, same reasoning
            // as gtOreMixin(): OreManager does not exist before then and a mixin that cannot bind must not be
            // offered at all.
            if (ClassShape.hasClass("gregtech.common.ores.OreManager")) {
                mixins.add("worldgen.GTWorldGenContainerOrePinMixin");
                mixins.add("worldgen.OreManagerVirginDryRunMixin");
            }
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
        if (loadedMods.contains("etfuturum")) {
            mixins.add("worldgen.EtFuturumDeepslateMixin");
            mixins.add("worldgen.EtFuturumCaveVineGrowMixin");
            mixins.add("worldgen.EtFuturumCaveVineTeMixin");
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
        // The determinism guarantee is only as wide as the set of mixins that actually loaded, so say what that set
        // is. Every mixin here binds with require >= 1, so anything listed either applied or brought the game down.
        LOG.info("{} worldgen mixins selected for this pack: {}", mixins.size(), mixins);
        GtOrePin.logState();
        return mixins;
    }
}
