package com.gtnhspeedrun.worldgen;

import net.minecraft.world.World;

/** Duck interface stamped onto Roguelike's WorldEditor by WorldEditorMixin to expose the wrapped World. */
public interface WorldEditorAccess {

    World gtnhdet$world();
}
