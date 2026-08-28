package com.gtnhspeedrun.determinism.worldgen;

/**
 * Duck interface stamped onto Roguelike's TreasureChest: exposes the generation position for stable sorting, and
 * whether the chest block still exists in the world (dungeon carving overwrites some placed chests; the stranded
 * ITreasureChest entries must not participate in loot distribution or they shift picks per launch).
 */
public interface ChestPosAccess {

    long gtnhdet$posKey();

    boolean gtnhdet$isLive();
}
