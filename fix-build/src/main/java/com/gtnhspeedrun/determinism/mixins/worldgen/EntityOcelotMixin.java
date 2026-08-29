package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityOcelot;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.EntitySpawnRng;

/**
 * Whether an ocelot arrives with two kittens is clock-random.
 * <p>
 * {@code EntityOcelot.onSpawnWithEgg} gates the pair on {@code this.worldObj.rand.nextInt(7) == 0}, so the same
 * seed yields a different jungle population per launch — and, because each kitten is a further
 * {@code spawnEntityInWorld}, a different entity count. Redirect onto a fork of (world seed, spawn position), the
 * same treatment {@link EntitySheepMixin} gives fleece colour.
 */
@Mixin(EntityOcelot.class)
public abstract class EntityOcelotMixin {

    @Redirect(
        method = "onSpawnWithEgg",
        at = @At(value = "FIELD", target = "Lnet/minecraft/world/World;rand:Ljava/util/Random;", opcode = 180))
    private Random gtnhdet$kittenRng(World world) {
        return EntitySpawnRng.forEntity((Entity) (Object) this);
    }
}
