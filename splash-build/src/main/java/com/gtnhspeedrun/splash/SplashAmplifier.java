package com.gtnhspeedrun.splash;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

/**
 * Documentation harness. NOT a shipping mod, and never in a release workflow.
 *
 * <p>
 * The README carries a launch-variance image: four cold boots of one seed, same walk order, two stock and two with
 * the determinism jar, rendered as a filmstrip of fixed crop windows. Launch-varying worldgen bugs are the subject —
 * clock-seeded RNG and identity-hash iteration order — but at stock generation rates the features that carry them
 * are far too sparse to frame. A slime island is one chunk in 8000; two villages within a radius-28 walk is luck.
 *
 * <p>
 * So this jar forces three generators to attempt at fixed known chunks (see {@link Plots}). It changes only
 * <em>whether</em> a generator is invited to try at a given chunk. Every structure is then built by the real mod's
 * real generator, carrying its real bug. Three rules keep that true:
 *
 * <ol>
 * <li><b>Inject, never overwrite.</b> Each hooked method reseeds or draws from {@code World.rand}; running the stock
 * body and overriding only the verdict leaves the RNG stream bit-identical to an unforced run.
 * <li><b>Go through the stock call site.</b> Notably the slime island is forced by making its rarity roll come up
 * zero, not by calling {@code generateIsland} directly — calling it directly would substitute this jar's
 * determinism for the bug being photographed.
 * <li><b>Hook something both arms execute.</b> The fix jar {@code @Overwrite}s
 * {@code WitcheryWorldGenerator.generate}, so the stock {@code generateOverworld} is unreachable whenever it is
 * installed. See {@link com.gtnhspeedrun.splash.mixins.WitcheryForceMixin}.
 * </ol>
 *
 * <p>
 * Inert unless {@code -Dsplash.enable=true}, so a single jar serves both A/B arms and neither arm can differ from
 * the other by jar md5 — the same discipline the fix jar uses for {@code gtnhdet.orepin}.
 */
@Mod(
    modid = SplashAmplifier.MODID,
    version = Tags.VERSION,
    name = "GTNH Worldgen Splash Amplifier",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*")
public class SplashAmplifier {

    public static final String MODID = "gtnhsplash";

    private static final Logger LOG = LogManager.getLogger("gtnhsplash");

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        Plots.dump();
        if (Plots.isEnabled() && Boolean.getBoolean("splash.witchery.anybiome")) {
            Scout.clearWitcheryBiomeBlacklist();
        }
    }

    /**
     * Scouting runs at server start because RWG's viability test needs a {@code World} to reach its
     * {@code ChunkManagerRealistic}. It generates no chunks — the test is pure terrain noise — and forces nothing.
     */
    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        if (Boolean.getBoolean("splash.scout")) {
            try {
                Scout.run(event.getServer());
            } catch (Throwable t) {
                LOG.error("[splash] scout failed", t);
            }
        }
    }
}
