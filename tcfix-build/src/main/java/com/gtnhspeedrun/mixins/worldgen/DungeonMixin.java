package com.gtnhspeedrun.mixins.worldgen;

import java.util.Arrays;
import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.BiomeDictionary.Type;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.gtnhspeedrun.worldgen.TerrainOracle;
import com.gtnhspeedrun.worldgen.WorldEditorAccess;

import greymerk.roguelike.config.RogueConfig;
import greymerk.roguelike.dungeon.Dungeon;
import greymerk.roguelike.worldgen.Coord;
import greymerk.roguelike.worldgen.IWorldEditor;

/**
 * Determinism fix for Roguelike Dungeons placement (GTNH speedrun determinism audit, F5).
 *
 * Stock {@code Dungeon.generateNear} makes up to 50 attempts, each rolling a spot 40-100 blocks from the trigger
 * chunk and gating on {@code validLocation}'s LIVE world reads (air at upperLimit, downward ground scan, water,
 * 9x9 clearance and under-surface tests). Blocks that far away are raw terrain, decorated, or generated-on-demand
 * depending on the player's route, so which attempt wins — and with it the dungeon's position, settings and the
 * whole downstream RNG stream (loot rand is position-derived) — wobbled per approach. Measured on seed 88888888:
 * the region's dungeon relocated ~50 blocks and re-rolled its size (133 vs 156 spawners) between two walk orders.
 *
 * This overwrite evaluates the same checks against VIRGIN terrain from {@link TerrainOracle} — a pure function of
 * the seed — so every attempt sequence resolves identically on any route. Semantic deltas vs stock (all in favor
 * of determinism): tree canopies at the upperLimit probe no longer veto an attempt (that veto was route-random),
 * and population-stage lake water no longer rejects (terrain-stage water still does; river/ocean/beach biomes are
 * rejected as before). The trigger-region grid was already seed-pure and is untouched.
 */
@Mixin(value = Dungeon.class, remap = false)
public abstract class DungeonMixin {

    @Shadow
    private IWorldEditor editor;

    /**
     * The "Statistics" starter book embeds the editor's block-placement counters, which count every transient
     * placement (later carved over) and therefore vary per launch even when the final dungeon is byte-identical.
     * Pin them to zero so the book NBT is deterministic; pure flavor text, no gameplay value.
     */
    @org.spongepowered.asm.mixin.injection.Redirect(
        method = "generate(Lgreymerk/roguelike/dungeon/settings/ISettings;II)V",
        at = @org.spongepowered.asm.mixin.injection.At(
            value = "INVOKE",
            target = "Lgreymerk/roguelike/worldgen/IWorldEditor;getStat(Lnet/minecraft/block/Block;)I"))
    private int tcfix$fixedStats(IWorldEditor ed, Block block) {
        return 0;
    }

    /**
     * F5 fourth pass: hold {@link com.gtnhspeedrun.worldgen.PendingSlices}' atomic window for the whole of
     * Dungeon.generate — construction AND the loot-rule pass that follows it. Without this, a neighbour chunk
     * force-loaded by the dungeon's own writes runs its mod-worldgen (and therefore the slice applier)
     * mid-construction, which both snapshots still-unlooted chests into the world and flips the dungeon's
     * remaining writes from buffered to live. Which neighbours that hits is a pure function of the player's
     * route, so it made chest existence and chest contents route-dependent.
     */
    @org.spongepowered.asm.mixin.injection.Inject(
        method = "generate(Lgreymerk/roguelike/dungeon/settings/ISettings;II)V",
        at = @org.spongepowered.asm.mixin.injection.At("HEAD"))
    private void tcfix$openWindow(greymerk.roguelike.dungeon.settings.ISettings settings, int inX, int inZ,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        com.gtnhspeedrun.worldgen.SliceTrace.log("dungeon BEGIN at {},{}", inX, inZ);
        com.gtnhspeedrun.worldgen.PendingSlices.beginAtomic();
    }

    @org.spongepowered.asm.mixin.injection.Inject(
        method = "generate(Lgreymerk/roguelike/dungeon/settings/ISettings;II)V",
        at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private void tcfix$closeWindow(greymerk.roguelike.dungeon.settings.ISettings settings, int inX, int inZ,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        com.gtnhspeedrun.worldgen.PendingSlices.endAtomic();
        com.gtnhspeedrun.worldgen.SliceTrace.log("dungeon END at {},{}", inX, inZ);
    }

    /**
     * @author GTNH speedrun determinism audit
     * @reason Replace live-world validity probes (route-dependent) with virgin-terrain reads (seed-pure) so
     *         dungeon position, settings and loot become functions of the seed. Check structure preserved.
     */
    @Overwrite
    public boolean validLocation(Random rand, int x, int z) {
        final BiomeGenBase biome = this.editor.getBiome(new Coord(x, 0, z));
        final Type[] invalidBiomes = new Type[] { BiomeDictionary.Type.RIVER, BiomeDictionary.Type.BEACH,
            BiomeDictionary.Type.MUSHROOM, BiomeDictionary.Type.OCEAN };
        final Type[] biomeType = BiomeDictionary.getTypesForBiome(biome);
        for (final Type type : invalidBiomes) {
            if (Arrays.asList(biomeType)
                .contains(type)) return false;
        }

        final World world = this.editor instanceof WorldEditorAccess ? ((WorldEditorAccess) this.editor).tcfix$world()
            : null;
        if (world == null) return false;

        final int upperLimit = RogueConfig.getInt(RogueConfig.UPPERLIMIT);
        final int lowerLimit = RogueConfig.getInt(RogueConfig.LOWERLIMIT);

        // Mountain reject: solid virgin ground at the upper limit (stock rejected on ANY non-air, incl. leaves).
        if (tcfix$ground(TerrainOracle.block(world, x, upperLimit, z))) return false;

        int y = upperLimit;
        Block b;
        while (!tcfix$ground(b = TerrainOracle.block(world, x, y, z))) {
            if (b.getMaterial() == Material.water) return false; // terrain-stage water (oceans/rivers/ponds)
            y--;
            if (y < lowerLimit) return false;
        }

        // 9x9 clearance 4 above the surface: any virgin ground there = cliff/overhang, reject (stock).
        for (int cx = x - 4; cx <= x + 4; cx++) {
            for (int cz = z - 4; cz <= z + 4; cz++) {
                if (tcfix$ground(TerrainOracle.block(world, cx, y + 4, cz))) return false;
            }
        }

        // 9x9 solidity 3 below the surface: too much air = cave roof, reject (stock threshold).
        int airCount = 0;
        for (int cx = x - 4; cx <= x + 4; cx++) {
            for (int cz = z - 4; cz <= z + 4; cz++) {
                if (!tcfix$ground(TerrainOracle.block(world, cx, y - 3, cz))) airCount++;
                if (airCount > 8) return false;
            }
        }

        return true;
    }

    /** Roguelike WorldEditor.validGroundBlock's material rules, applied to a virgin block. */
    @Unique
    private static boolean tcfix$ground(Block b) {
        if (b == Blocks.air) return false;
        final Material m = b.getMaterial();
        return m != Material.wood && m != Material.water
            && m != Material.cactus
            && m != Material.snow
            && m != Material.grass
            && m != Material.gourd
            && m != Material.leaves
            && m != Material.plants;
    }
}
