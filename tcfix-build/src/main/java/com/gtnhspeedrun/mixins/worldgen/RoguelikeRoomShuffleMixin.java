package com.gtnhspeedrun.mixins.worldgen;

import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import greymerk.roguelike.dungeon.rooms.DungeonEniko;
import greymerk.roguelike.dungeon.rooms.DungeonMess;
import greymerk.roguelike.dungeon.rooms.DungeonStorage;
import greymerk.roguelike.dungeon.settings.LevelSettings;
import greymerk.roguelike.worldgen.Cardinal;
import greymerk.roguelike.worldgen.Coord;
import greymerk.roguelike.worldgen.IWorldEditor;

/**
 * Determinism fix for Roguelike room-feature shuffles (GTNH speedrun determinism audit, 0.4).
 *
 * Three rooms shuffle placement lists with {@code Collections.shuffle(List)} — the overload that draws from a
 * CLOCK-seeded global Random — so their outcome differs every JVM launch while consuming zero draws from the
 * dungeon's seeded stream (no downstream drift, just the local flip):
 * <ul>
 * <li>{@code DungeonMess}: which non-door walls get the fireplace / supplies / side table (ground truth: seed
 * -777 mess hall fireplace flipped between 3 walls across fresh-JVM launch pairs, ~34-94 persisted blocks);</li>
 * <li>{@code DungeonStorage}: which two alcoves receive the SUPPLIES and BLOCKS chests — routing-relevant;</li>
 * <li>{@code DungeonEniko}: which candidate position receives the room's chest — routing-relevant.</li>
 * </ul>
 *
 * Fix: redirect to {@code Collections.shuffle(List, Random)} with the generate() method's own seeded rand
 * (position-derived via {@code Dungeon.getRandom}). Adds exactly the draws java.util's shuffle makes, taken from
 * the level stream at a fixed point — deterministic and identical across launches.
 */
@Mixin(value = { DungeonMess.class, DungeonStorage.class, DungeonEniko.class }, remap = false)
public abstract class RoguelikeRoomShuffleMixin {

    @Redirect(
        method = "generate",
        at = @At(value = "INVOKE", target = "Ljava/util/Collections;shuffle(Ljava/util/List;)V"),
        remap = false)
    private void tcfix$seededShuffle(List<?> list, IWorldEditor editor, Random rand, LevelSettings settings,
        Cardinal[] entrances, Coord origin) {
        Collections.shuffle(list, rand);
    }
}
