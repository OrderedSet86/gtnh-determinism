package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.DungeonAttemptTrace;

import greymerk.roguelike.dungeon.Dungeon;
import greymerk.roguelike.worldgen.Coord;

/**
 * Diagnostic only, zero behaviour change: log every attempt of {@code Dungeon.generateNear}'s
 * 50-attempt placement loop.
 *
 * <p>
 * Purpose is to discriminate between the three candidate inputs that could make a dungeon's
 * construction ORIGIN route-dependent (see results/2026-09-07-roguelike-placement-escape, where the
 * same seed and trigger chunk produced {@code dungeon BEGIN} at (-622,835) under one walk and
 * (-623,749) under another):
 *
 * <ol>
 * <li>attempt <i>i</i> yields the SAME location in both arms but a different {@code validLocation}
 * verdict &rarr; the terrain probe is impure, i.e. {@code DungeonMixin}'s virgin
 * {@code @Overwrite} is not holding;</li>
 * <li>attempt <i>i</i> yields a DIFFERENT location &rarr; the {@code Random} reaching
 * {@code spawnInChunk} is impure, and everything downstream of it is noise;</li>
 * <li>locations and verdicts agree but the chosen settings differ &rarr;
 * {@code SettingsResolver.getSettings}, which no current mixin covers.</li>
 * </ol>
 *
 * <p>
 * Redirecting the CALL SITES inside {@code generateNear} rather than injecting into
 * {@code validLocation} itself is deliberate: {@code DungeonMixin} already {@code @Overwrite}s that
 * method, and a second mixin injecting into an overwritten body is fragile. A call-site redirect is
 * a different injection point and composes cleanly — it observes exactly what the loop observes,
 * including the value {@code DungeonMixin}'s replacement returns.
 *
 * <p>
 * Gated on {@code -Dgtnhdet.tracegennear=true}; every handler returns the real value unchanged, so
 * with the flag off this costs one static boolean read per attempt.
 */
@Mixin(value = Dungeon.class, remap = false)
public abstract class DungeonAttemptTraceMixin {

    @Redirect(
        method = "generateNear",
        at = @At(
            value = "INVOKE",
            target = "Lgreymerk/roguelike/dungeon/Dungeon;getNearbyCoord(Ljava/util/Random;IIII)"
                + "Lgreymerk/roguelike/worldgen/Coord;"),
        require = 1)
    private static Coord gtnhdet$traceCoord(Random rand, int x, int z, int min, int max) {
        final Coord c = Dungeon.getNearbyCoord(rand, x, z, min, max);
        DungeonAttemptTrace.coord(x, z, c.getX(), c.getZ());
        return c;
    }

    @Redirect(
        method = "generateNear",
        at = @At(value = "INVOKE", target = "Lgreymerk/roguelike/dungeon/Dungeon;validLocation(Ljava/util/Random;II)Z"),
        require = 1)
    private boolean gtnhdet$traceValid(Dungeon self, Random rand, int x, int z) {
        final boolean ok = self.validLocation(rand, x, z);
        DungeonAttemptTrace.valid(x, z, ok);
        return ok;
    }
}
