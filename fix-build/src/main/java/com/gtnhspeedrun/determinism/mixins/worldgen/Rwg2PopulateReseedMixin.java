package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.world.World;
import net.minecraftforge.event.terraingen.DecorateBiomeEvent;
import net.minecraftforge.event.terraingen.OreGenEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.RwgPopulateRng;

/**
 * {@link RwgPopulateReseedMixin} for RWG2. {@code populate} in RWG2 2.0.6 posts the same two events.
 */
@Mixin(targets = "rwg2.world.ChunkGeneratorRealistic", remap = false)
public class Rwg2PopulateReseedMixin {

    /** Re-seed the populate {@code Random} before the ore pass, which starts with this event. */
    @Redirect(
        method = { "func_73153_a", "populate" },
        at = @At(value = "NEW", target = "net/minecraftforge/event/terraingen/OreGenEvent$Pre"),
        require = 1)
    private OreGenEvent.Pre gtnhdet$reseedBeforeOres(World world, Random rand, int worldX, int worldZ) { // noqa:
                                                                                                         // NAR130,
                                                                                                         // NAR131
        RwgPopulateRng.Stage.ORES.reseed(rand, new RwgPopulateRng.PopulateChunk(world, worldX >> 4, worldZ >> 4));
        return new OreGenEvent.Pre(world, rand, worldX, worldZ);
    }

    /** Re-seed the populate {@code Random} before decoration, which starts with this event. */
    @Redirect(
        method = { "func_73153_a", "populate" },
        at = @At(value = "NEW", target = "net/minecraftforge/event/terraingen/DecorateBiomeEvent$Pre"),
        require = 1)
    private DecorateBiomeEvent.Pre gtnhdet$reseedBeforeDecoration(World world, Random rand, int worldX, int worldZ) { // noqa:
                                                                                                                      // NAR130,
                                                                                                                      // NAR131
        RwgPopulateRng.Stage.DECORATION.reseed(rand, new RwgPopulateRng.PopulateChunk(world, worldX >> 4, worldZ >> 4));
        return new DecorateBiomeEvent.Pre(world, rand, worldX, worldZ);
    }
}
