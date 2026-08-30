package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.util.WeightedRandomChestContent;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraft.world.gen.structure.StructureComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.determinism.worldgen.ChestFillContext;

/**
 * F10 site half: tells {@link ChestFillContext} which structure piece a chest belongs to, and where in that piece
 * it sits.
 *
 * <p>
 * This is what lets the fork use the component's XZ origin and the chest's component-local coordinates instead of
 * its absolute position. The component's XZ origin and class are decided before terrain exists — the seed-search
 * prefilter already produces them without generating a chunk — while its Y anchor is not fixed until the first
 * chunk box that intersects it averages the ground. Deriving from the former makes village chest contents
 * computable from a layout alone.
 *
 * <p>
 * This hook supplies the <em>precise</em> site: the caller hands over true component-local coordinates, Y
 * included. It is not, however, the common path — that assumption was made here and measured false. On daily-707
 * with {@code -Dgtnhdet.chesttrace=true}, only 10 of 71 chests on one seed arrived through these two methods;
 * most village pieces fill their chests by other routes and are covered by {@link StructureStartPartsMixin}
 * instead, which knows the piece but only its bounding box. Chests outside any component —
 * {@code WorldGenDungeons} rooms, minecart chests — leave the site unset and fall back to absolute position.
 * See results/2026-08-29-chest-site-coverage.
 */
@Mixin(StructureComponent.class)
public abstract class StructureComponentChestMixin {

    @Shadow
    protected StructureBoundingBox boundingBox;

    @Inject(method = "generateStructureChestContents", at = @At("HEAD"), require = 1)
    private void gtnhdet$chestSite(World world, StructureBoundingBox box, Random rand, int x, int y, int z,
        WeightedRandomChestContent[] items, int count, CallbackInfoReturnable<Boolean> cir) {
        ChestFillContext.enterComponent(
            this.getClass()
                .getName(),
            this.boundingBox,
            x,
            y,
            z,
            this);
    }

    @Inject(method = "generateStructureChestContents", at = @At("RETURN"), require = 1)
    private void gtnhdet$chestSiteDone(World world, StructureBoundingBox box, Random rand, int x, int y, int z,
        WeightedRandomChestContent[] items, int count, CallbackInfoReturnable<Boolean> cir) {
        ChestFillContext.leaveComponent();
    }

    @Inject(method = "generateStructureDispenserContents", at = @At("HEAD"), require = 1)
    private void gtnhdet$dispenserSite(World world, StructureBoundingBox box, Random rand, int x, int y, int z,
        int facing, WeightedRandomChestContent[] items, int count, CallbackInfoReturnable<Boolean> cir) {
        ChestFillContext.enterComponent(
            this.getClass()
                .getName(),
            this.boundingBox,
            x,
            y,
            z,
            this);
    }

    @Inject(method = "generateStructureDispenserContents", at = @At("RETURN"), require = 1)
    private void gtnhdet$dispenserSiteDone(World world, StructureBoundingBox box, Random rand, int x, int y, int z,
        int facing, WeightedRandomChestContent[] items, int count, CallbackInfoReturnable<Boolean> cir) {
        ChestFillContext.leaveComponent();
    }
}
