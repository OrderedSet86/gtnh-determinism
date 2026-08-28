package com.gtnhspeedrun.qol;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;

/**
 * GTNH Speedrun QoL — client-side quality-of-life fixes for GTNH speedrunning.
 *
 * <p>
 * Scope rule: a fix belongs here only if it removes interface friction. Nothing in this mod may change what the
 * game simulates — no world generation, no recipes, no drops, no player capabilities — because a run using it
 * must stay comparable to a run without it. Worldgen determinism work lives in the separate
 * {@code gtnhdeterminism} jar.
 *
 * <p>
 * Fixes carried:
 * <ul>
 * <li>Block-breaking progress survives the held ItemStack being replaced or mutated in place, so an item pickup
 * mid-dig no longer restarts the block. See {@code mixins.PlayerControllerMPMixin}.</li>
 * </ul>
 *
 * <p>
 * The mod is client-only in effect. Its single mixin targets {@code PlayerControllerMP}, a class that does not
 * exist on a dedicated server, so the config lists it under {@code client} and a server simply never applies it.
 */
@Mod(
    modid = GtnhSpeedrunQol.MODID,
    version = Tags.VERSION,
    name = "GTNH Speedrun QoL",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*")
public class GtnhSpeedrunQol {

    public static final String MODID = "gtnhspeedrunqol";
    public static final Logger LOG = LogManager.getLogger(MODID);
}
