package com.gtnhspeedrun.tcworldgenfix;

import net.minecraft.world.World;

/** Duck interface stamped onto Roguelike's WorldEditor by WorldEditorMixin to expose the wrapped World. */
public interface WorldEditorAccess {

    World tcfix$world();
}
