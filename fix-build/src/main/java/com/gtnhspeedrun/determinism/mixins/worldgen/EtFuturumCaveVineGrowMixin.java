package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.world.World;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import ganymedes01.etfuturum.blocks.BlockCaveVines;

/**
 * {@code BlockCaveVines.growVine} rolls the glow-berry state from {@code world.rand} — {@code World.field_73012_v},
 * the shared clock-seeded live world RNG. Worldgen calls it while building each vine, so a vine's berries are not a
 * function of the world seed. Measured on seed -1501259159663517643: two IDENTICAL cold runs disagreed on 66
 * {@code etfuturum:cave_vine} / {@code cave_vine_plant} metadata blocks, which together with the tile entity's
 * {@code maxLength} (see {@link EtFuturumCaveVineTeMixin}) was the entire remaining block-level launch noise after
 * the deepslate band was fixed.
 *
 * <p>
 * Fix: answer those draws from a rand seeded on (world seed, x, y, z), so berries are a pure function of the vine's
 * position. Same idiom as the chest-slot fork in {@code InventoryMixin}.
 *
 * <p>
 * Deliberately NOT touched: {@code func_149674_a} (the vanilla random tick) uses the Random that Minecraft passes
 * it. That is live gameplay growth, not worldgen, and it is supposed to be unpredictable.
 */
@Mixin(value = BlockCaveVines.class, remap = false)
public class EtFuturumCaveVineGrowMixin {

    @Redirect(
        method = "growVine",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/world/World;field_73012_v:Ljava/util/Random;",
            opcode = Opcodes.GETFIELD),
        require = 1)
    private Random gtnhdet$berryRand(World owner, World world, int x, int y, int z, boolean flag) {
        return new Random(world.getSeed() ^ x * 341873128712L ^ y * 132897987541L ^ z * 0x9E3779B97F4A7C15L);
    }
}
