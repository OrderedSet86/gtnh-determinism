package com.gtnhspeedrun.mixins.worldgen;

import java.util.Random;

import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureBoundingBox;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import forestry.apiculture.worldgen.ComponentVillageBeeHouse;

/**
 * Forestry's village bee house rolls its flower garden, apiary frames, and — most run-relevant — the VILLAGE BEE
 * SPECIES inside the apiary off {@code world.rand} (clock-seeded), while the building itself is placed from the
 * seeded structure Random. Capture the structure Random at addComponentParts and redirect the world.rand reads to a
 * fork of it, so village bees/frames/flowers are a pure function of the seed. (GTNH speedrun determinism audit —
 * also covers the "TileSwarm presence varies per launch" observation.)
 *
 * Note: the FIELD target uses the SRG name (field_73012_v) and the method target the SRG name (func_74875_a) since
 * this late mixin applies at production runtime.
 */
@Mixin(value = ComponentVillageBeeHouse.class, remap = false)
public class ComponentVillageBeeHouseMixin {

    @Unique
    private static final ThreadLocal<Random> tcfix$beeRand = new ThreadLocal<>();

    @Inject(method = "func_74875_a", at = @At("HEAD"), require = 0)
    private void tcfix$captureRand(World world, Random rand, StructureBoundingBox box,
        CallbackInfoReturnable<Boolean> cir) {
        tcfix$beeRand.set(new Random(rand.nextLong()));
    }

    @Redirect(
        method = { "plantFlowerGarden", "populateApiary" },
        at = @At(value = "FIELD", target = "Lnet/minecraft/world/World;field_73012_v:Ljava/util/Random;", opcode = 180),
        require = 0)
    private Random tcfix$seededRand(World world) {
        final Random r = tcfix$beeRand.get();
        return r != null ? r : world.rand;
    }

    @Redirect(
        method = "getRandomVillageBee",
        at = @At(value = "FIELD", target = "Lnet/minecraft/world/World;field_73012_v:Ljava/util/Random;", opcode = 180),
        require = 0)
    private static Random tcfix$seededRandStatic(World world) {
        final Random r = tcfix$beeRand.get();
        return r != null ? r : world.rand;
    }
}
