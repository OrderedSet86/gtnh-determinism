package com.gtnhspeedrun.mixins.worldgen;

import java.util.Random;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.emoniph.witchery.worldgen.ComponentWickerMan;

/**
 * The wicker man's SPAWNER mob type is chosen with world.rand even though addComponentParts already receives the
 * seeded structure Random — spawner contents differed per launch/approach on the same seed. Route the roll to the
 * seeded parameter. (GTNH speedrun determinism audit, finding F2 follow-up.)
 */
@Mixin(value = ComponentWickerMan.class, remap = false)
public class ComponentWickerManMixin {

    @Redirect(
        method = "addComponentParts",
        at = @At(value = "FIELD", target = "Lnet/minecraft/world/World;field_73012_v:Ljava/util/Random;", opcode = 180),
        require = 0)
    private Random tcfix$seededSpawnerRoll(World world, World worldArg, Random random) {
        return random;
    }
}
