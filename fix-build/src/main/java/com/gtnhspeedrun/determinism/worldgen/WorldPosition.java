package com.gtnhspeedrun.determinism.worldgen;

import net.minecraft.world.World;

/** A block position in one world. */
public record WorldPosition(World world, int x, int y, int z) {}
