package com.gtnhspeedrun.tcworldgenfix.mixins;

import java.util.Random;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.tcworldgenfix.EldritchRingLottery;

import thaumcraft.common.lib.world.WorldGenEldritchRing;

/**
 * Determinism fix for the eldritch ring (obelisk) exclusion race (GTNH speedrun determinism audit, F3 follow-up).
 *
 * Stock {@code WorldGenEldritchRing.generate} aborts if {@code MazeHandler.mazesInRange} finds cells of any
 * PREVIOUSLY generated ring in a up-to-43x43-chunk window. The maze map fills in chunk population order, so ring
 * presence at a given site depended on the player's route (observed in the field: obelisk at block -787,460 on
 * seed 88888888 present or absent depending on approach). This redirect replaces the live-map lookup with
 * {@link EldritchRingLottery}: a seed-pure priority contest over the same window — same-seed worlds get the same
 * obelisks regardless of route. The real maze map is still written by the (synchronous) MazeThread afterwards, so
 * runtime consumers (TileEldritchAltar portal opening) see consistent state.
 */
@Mixin(value = WorldGenEldritchRing.class, remap = false)
public abstract class WorldGenEldritchRingMixin {

    @Redirect(
        method = { "func_76484_a", "generate" },
        at = @At(value = "INVOKE", target = "Lthaumcraft/common/lib/world/dim/MazeHandler;mazesInRange(IIII)Z"))
    private boolean tcfix$deterministicMazeRace(int chunkX, int chunkZ, int w, int h, World world, Random rand, int i,
        int j, int k) {
        return EldritchRingLottery.suppressed(world.getSeed(), chunkX, chunkZ, w, h);
    }
}
