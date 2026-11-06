package com.gtnhspeedrun.determinism;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;
import com.gtnhspeedrun.determinism.worldgen.GtOrePin;
import com.gtnhspeedrun.determinism.worldgen.NetherPopulateRng;
import com.gtnhspeedrun.determinism.worldgen.OracleRngGuard;

@LateMixin
public class LateMixinLoader implements ILateMixinLoader {

    private static final Logger LOG = LogManager.getLogger(GtnhDeterminism.MODID);

    /**
     * F4 has two implementations because GregTech rewrote the probe that F4 corrects twice, and only the first and last
     * shapes ship in a supported pack. Up to 5.09.52.30 (pack 2.8.4) the vein reroll calls
     * {@code Block.isReplaceableOreGen}. In 5.09.52.31 GregTech replaced that call with
     * {@code StoneType.findStoneType}, and in 5.09.54.02 split {@code executeWorldgenChunkified} into overloads, so the
     * method selector changed. Pick by the installed shape. {@link GtOreShape} explains why a version-string comparison
     * cannot work.
     *
     * <p>
     * Getting this wrong has failed in both directions. It was silent first: the legacy mixin was
     * {@code require = 0}, so on newer GregTech it applied, bound nothing, and left ore veins route-dependent with
     * no log line. Then it was fatal: the 5.09.54 mixin was gated on a class that turned out to exist 300 versions
     * earlier, so GTNH 2.9.0-beta-1 was handed a mixin that could not bind and crashed at init. Every mixin below
     * now binds at a pinned count, and an unrecognised GregTech is refused loudly rather than guessed at.
     *
     * @return the mixin to apply, or null when no shape matched.
     */
    private static String gtOreMixin() {
        return switch (GtOreShape.SHAPE) {
            case VEIN_PLACEMENT -> {
                LOG.info("GT ore-vein probe: VeinPlacement shape (GT 5.09.54.02+), F4 + native F4d");
                yield "worldgen.WorldgenGTOreLayerStoneTypeMixin";
            }
            case REPLACEABLE_ORE_GEN -> {
                LOG.info("GT ore-vein probe: isReplaceableOreGen shape (GT <= 5.09.52.30), F4 only");
                yield "worldgen.WorldgenGTOreLayerMixin";
            }
            case UNSUPPORTED_FIND_STONE_TYPE -> {
                LOG.warn(
                    "gtOreProbe.unsupported shape=findStoneType band=5.09.52.31-5.09.54.01 f4=off gregtech={}",
                    GtOreShape.installedGregTech());
                yield null;
            }
            case UNKNOWN -> {
                // a mixin that cannot bind crashes the game, and one that binds partially reroutes ore veins silently.
                // Refuse
                LOG.warn("gtOreProbe.unrecognised f4=off gregtech={}", GtOreShape.installedGregTech());
                yield null;
            }
        };
    }

    @Override
    public String getMixinConfig() {
        return "mixins.gtnhdeterminism.late.json";
    }

    /** Offer each mixin only when the mod the mixin targets is loaded, and F4 only for the installed GT shape. */
    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        final List<String> mixins = new ArrayList<>();
        if (loadedMods.contains("Thaumcraft")) {
            mixins.add("worldgen.ThaumcraftWorldGeneratorMixin");
            mixins.add("worldgen.WorldGenMoundMixin");
            mixins.add("worldgen.WorldGenEldritchRingMixin");
            mixins.add("worldgen.WorldGenHilltopStonesMixin");
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
            final String oreMixin = gtOreMixin();
            if (oreMixin != null) mixins.add(oreMixin);
            // F4d: vein IDENTITY is chosen by whichever chunk triggers the region first. These pin the decision
            // to the oreseed chunk and virginise the reads it still makes. Registered UNCONDITIONALLY and at
            // require = 1 so a binding failure is loud in BOTH arms of an A/B — the behaviour is switched by
            // gtnhdet.orepin inside the handlers, not by which mixins load, so that "off" is bit-identical to
            // stock and the two arms are the same jar.
            //
            // Gated on whether GregTech provides its own dry run, probed on the classes these mixins actually
            // bind to. It used to be gated on hasClass("gregtech.common.ores.OreManager") with a comment
            // asserting that class arrived in 5.09.54; it arrived in 5.09.52.31, ~300 versions before it grew
            // the methods these target, which is how 2.9.0-beta-1 was offered six injectors that cannot bind.
            if (GtOreShape.hasNativeDryRun()) {
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
            mixins.add("worldgen.RwgDungeonAttemptMixin");
            mixins.add("worldgen.RwgPopulateReseedMixin");
            mixins.add("worldgen.RwgWorldSavedDataMixin");
            mixins.add("worldgen.DecoBigTreeCtorMixin");
        }
        if (loadedMods.contains("rwg2")) {
            mixins.add("worldgen.Rwg2DecoForkMixin");
            mixins.add("worldgen.Rwg2DungeonAttemptMixin");
            mixins.add("worldgen.Rwg2PopulateReseedMixin");
            mixins.add("worldgen.Rwg2WorldSavedDataMixin");
            if (loadedMods.contains("witchery")) mixins.add("worldgen.Rwg2WitcheryFoundationMixin");
            mixins.add("worldgen.Rwg2DecoBigTreeCtorMixin");
        }
        if (loadedMods.contains("appliedenergistics2")) {
            // Not flag-gated: this is a fix, not a diagnostic. Meteorite existence is otherwise
            // route-dependent (2 of 38 over 25 seeds, same-order floor 0).
            mixins.add("worldgen.Ae2MeteoriteSitingMixin");
        }
        // Diagnostic, not a fix: AE2 meteorites never generate in a probed world though a real client
        // on the same seed and jar does produce them. Gated on the flag as well as the mod so a jar in
        // mods/ without -Dgtnhdet.meteortrace applies nothing.
        if (Boolean.getBoolean("gtnhdet.meteortrace") && loadedMods.contains("appliedenergistics2")) {
            mixins.add("worldgen.Ae2MeteoritePlacerTraceMixin");
            mixins.add("worldgen.Ae2MeteoriteGenTraceMixin");
        }
        if (loadedMods.contains("etfuturum")) {
            mixins.add("worldgen.EtFuturumDeepslateMixin");
            mixins.add("worldgen.EtFuturumCaveVineGrowMixin");
            mixins.add("worldgen.EtFuturumCaveVineTeMixin");
            // Mesa half of the mineshaft chest pin. The vanilla half is unconditional in
            // mixins.gtnhdeterminism.json because its target is a vanilla class; this one targets an Et
            // Futurum class and so has to wait until we know the mod is present.
            //
            // OPT-IN until its binding is exercised. The ordinals are bytecode-verified against
            // etfuturum-2.6.58-GTNH (chests at 0 and 1, not vanilla's 1 and 2 — MesaCorridor moved the
            // plank roll into a helper), but MesaCorridor has never loaded in any measured run, so
            // require = 1 has never actually validated them. If they are wrong this does not degrade, it
            // kills the game at apply time the first time a mesa mineshaft loads — and gtnhdet.mineshaftpin
            // does NOT protect against that, because the flag only changes the handler's answer while the
            // injector still has to bind. Ship it off by default rather than hand a stranger that failure.
            // Flip to unconditional once a mesa-biome seed has been through the multiseed harness.
            if (Boolean.getBoolean("gtnhdet.mesapin")) {
                mixins.add("worldgen.MesaCorridorChestMixin");
            }
        }
        if (loadedMods.contains("ProjRed|Exploration")) {
            mixins.add("worldgen.TileLilyMixin");
        }
        if (loadedMods.contains("Forestry")) {
            mixins.add("worldgen.ComponentVillageBeeHouseMixin");
        }
        // Vanilla ChunkProviderHell, so no mod gate: unlike ChunkProviderGenerate and RWG's generator, its
        // populate never re-seeds hellRNG, which makes the whole Nether decoration layer a function of chunk
        // load order. Registered unconditionally and switched by gtnhdet.netherpop inside the handler, so both
        // arms of an A/B are the same jar.
        mixins.add("worldgen.ChunkProviderHellPopulateMixin");
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
            // Diagnostic for the placement escape: logs every attempt of generateNear's 50-attempt loop
            // so two arms can be diffed to see whether the LOCATION or the VERDICT diverges first.
            // Registered only under its flag so a normal run cannot pay for it or trip its require=1.
            if (Boolean.getBoolean("gtnhdet.tracegennear")) {
                mixins.add("worldgen.DungeonAttemptTraceMixin");
            }
        }
        // The determinism guarantee is only as wide as the set of mixins that actually loaded, so say what that set
        // is. Every mixin here binds with require >= 1, so anything listed either applied or brought the game down.
        LOG.info("{} worldgen mixins selected for this pack: {}", mixins.size(), mixins);
        GtOrePin.logState();
        NetherPopulateRng.logState();
        OracleRngGuard.logState();
        return mixins;
    }
}
