package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;

import com.gtnhspeedrun.determinism.worldgen.RwgPopulateRng;
import com.gtnhspeedrun.determinism.worldgen.WorldPosition;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import rwg.deco.DecoBlob;
import rwg.deco.DecoCacti;
import rwg.deco.DecoClay;
import rwg.deco.DecoFlowers;
import rwg.deco.DecoGrass;
import rwg.deco.DecoIceSpike;
import rwg.deco.DecoJungleCane;
import rwg.deco.DecoLog;
import rwg.deco.DecoWaterGrass;
import rwg.deco.DecoWildWheat;
import rwg.deco.trees.DecoBigTree;
import rwg.deco.trees.DecoBirch;
import rwg.deco.trees.DecoDeadDesertTrees;
import rwg.deco.trees.DecoEuroPine;
import rwg.deco.trees.DecoJungleFat;
import rwg.deco.trees.DecoJungleSmall;
import rwg.deco.trees.DecoJungleTall;
import rwg.deco.trees.DecoLargePine;
import rwg.deco.trees.DecoMangrove;
import rwg.deco.trees.DecoPalm;
import rwg.deco.trees.DecoPineTree;
import rwg.deco.trees.DecoRedWood;
import rwg.deco.trees.DecoSavannah;
import rwg.deco.trees.DecoShrub;
import rwg.deco.trees.DecoSmallCocoa;
import rwg.deco.trees.DecoSmallJungle;
import rwg.deco.trees.DecoSmallPine;
import rwg.deco.trees.DecoSmallSpruce;
import rwg.deco.trees.DecoWillow;

/**
 * RWG decorators draw from the chunk's shared populate {@code Random} behind terrain-dependent gates. For example,
 * {@code DecoLargePine} returns before any draw unless the ground block is grass or dirt. Without this mixin, the draws
 * of every later decoration depend on terrain at chunk borders. Each call gets a {@code Random} from
 * {@link RwgPopulateRng#createDecoratorRandom}, and no draws are taken from the shared stream. Audit item F6.
 */
@Mixin(
    value = { DecoBlob.class, DecoCacti.class, DecoClay.class, DecoFlowers.class, DecoGrass.class, DecoIceSpike.class,
        DecoJungleCane.class, DecoLog.class, DecoWaterGrass.class, DecoWildWheat.class, DecoBigTree.class,
        DecoBirch.class, DecoDeadDesertTrees.class, DecoEuroPine.class, DecoJungleFat.class, DecoJungleSmall.class,
        DecoJungleTall.class, DecoLargePine.class, DecoMangrove.class, DecoPalm.class, DecoPineTree.class,
        DecoRedWood.class, DecoSavannah.class, DecoShrub.class, DecoSmallCocoa.class, DecoSmallJungle.class,
        DecoSmallPine.class, DecoSmallSpruce.class, DecoWillow.class },
    remap = false)
public class RwgDecoForkMixin {

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
