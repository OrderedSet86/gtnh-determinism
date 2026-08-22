package com.gtnhspeedrun.mixins.worldgen;

import java.util.Random;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import tconstruct.world.gen.SlimeIslandGen;

/**
 * SlimeIslandGen.generateIsland takes the seeded worldgen Random as parameter 'rand' but its body reads 'random' —
 * the instance field initialized as {@code new Random()} (clock-seeded). Island size, altitude, shape, decoration
 * randomness, and slime tree placement therefore differ every game launch for the same seed. Redirect the field reads
 * inside generateIsland to the seeded parameter. (GTNH speedrun determinism audit; upstream fix on the
 * TinkersConstruct determinism-fixes branch removes the field entirely.)
 */
@Mixin(value = SlimeIslandGen.class, remap = false)
public class SlimeIslandGenMixin {

    @Redirect(
        method = "generateIsland",
        at = @At(
            value = "FIELD",
            target = "Ltconstruct/world/gen/SlimeIslandGen;random:Ljava/util/Random;",
            opcode = 180))
    private Random tcfix$useSeededRand(SlimeIslandGen self, World world, Random rand, int xChunk, int zChunk) {
        return rand;
    }
}
