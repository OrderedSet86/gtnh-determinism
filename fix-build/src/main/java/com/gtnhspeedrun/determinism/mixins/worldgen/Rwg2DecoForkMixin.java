package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;

import com.gtnhspeedrun.determinism.worldgen.RwgPopulateRng;
import com.gtnhspeedrun.determinism.worldgen.WorldPosition;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import rwg2.deco.DecoBlob;
import rwg2.deco.DecoCacti;
import rwg2.deco.DecoClay;
import rwg2.deco.DecoFlowers;
import rwg2.deco.DecoGrass;
import rwg2.deco.DecoIceSpike;
import rwg2.deco.DecoJungleCane;
import rwg2.deco.DecoLog;
import rwg2.deco.DecoWaterGrass;
import rwg2.deco.DecoWildWheat;
import rwg2.deco.trees.DecoBigTree;
import rwg2.deco.trees.DecoBirch;
import rwg2.deco.trees.DecoDeadDesertTrees;
import rwg2.deco.trees.DecoEuroPine;
import rwg2.deco.trees.DecoJungleFat;
import rwg2.deco.trees.DecoJungleSmall;
import rwg2.deco.trees.DecoJungleTall;
import rwg2.deco.trees.DecoLargePine;
import rwg2.deco.trees.DecoMangrove;
import rwg2.deco.trees.DecoPalm;
import rwg2.deco.trees.DecoPineTree;
import rwg2.deco.trees.DecoRedWood;
import rwg2.deco.trees.DecoSavannah;
import rwg2.deco.trees.DecoShrub;
import rwg2.deco.trees.DecoSmallCocoa;
import rwg2.deco.trees.DecoSmallJungle;
import rwg2.deco.trees.DecoSmallPine;
import rwg2.deco.trees.DecoSmallSpruce;
import rwg2.deco.trees.DecoWillow;

/**
 * {@link RwgDecoForkMixin} for RWG2. RWG2 2.0.6 has the same 29 decorators with the same
 * {@code generate(World, Random, int, int, int)} signature, under {@code rwg2.deco}.
 */
@Mixin(
    value = { DecoBlob.class, DecoCacti.class, DecoClay.class, DecoFlowers.class, DecoGrass.class, DecoIceSpike.class,
        DecoJungleCane.class, DecoLog.class, DecoWaterGrass.class, DecoWildWheat.class, DecoBigTree.class,
        DecoBirch.class, DecoDeadDesertTrees.class, DecoEuroPine.class, DecoJungleFat.class, DecoJungleSmall.class,
        DecoJungleTall.class, DecoLargePine.class, DecoMangrove.class, DecoPalm.class, DecoPineTree.class,
        DecoRedWood.class, DecoSavannah.class, DecoShrub.class, DecoSmallCocoa.class, DecoSmallJungle.class,
        DecoSmallPine.class, DecoSmallSpruce.class, DecoWillow.class },
    remap = false)
public class Rwg2DecoForkMixin {

    /** Call the decorator with a {@code Random} seeded from the decorator's position. */
    @WrapMethod(
        // func_76484_a is the only name in the shipped jars. With remap = false the refmap has no entry, so list both
        // names
        method = { "func_76484_a", "generate" },
        require = 1)
    private boolean gtnhdet$seedFromPosition(World world, Random shared, int x, int y, int z, // noqa: NAR130, NAR131
        Operation<Boolean> original) {
        if (!RwgPopulateRng.ON) return original.call(world, shared, x, y, z);
        final Random seeded = RwgPopulateRng.createDecoratorRandom(new WorldPosition(world, x, y, z), this);
        return original.call(world, seeded, x, y, z);
    }
}
