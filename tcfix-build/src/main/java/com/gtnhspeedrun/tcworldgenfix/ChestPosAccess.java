package com.gtnhspeedrun.tcworldgenfix;

/** Duck interface stamped onto Roguelike's TreasureChest to expose its generation position for stable sorting. */
public interface ChestPosAccess {

    long tcfix$posKey();
}
