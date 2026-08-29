package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.EntitySpawnRng;

/**
 * Sheep fleece colour is clock-random.
 * <p>
 * {@code EntitySheep.onSpawnWithEgg} ends with {@code setFleeceColor(getRandomFleeceColor(this.worldObj.rand))}.
 * {@code World.rand} is a bare {@code new Random()}, so the wool a seed offers differs on every launch even once
 * {@link SpawnerAnimalsMixin} has pinned which animals spawn and where. Redirect the draw onto a fork of
 * (world seed, spawn position).
 */
@Mixin(EntitySheep.class)
public abstract class EntitySheepMixin {

    @Redirect(
        method = "onSpawnWithEgg",
        at = @At(value = "FIELD", target = "Lnet/minecraft/world/World;rand:Ljava/util/Random;", opcode = 180))
    private Random gtnhdet$fleeceRng(World world) {
        return EntitySpawnRng.forEntity((Entity) (Object) this);
    }
}
