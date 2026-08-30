package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraft.world.gen.structure.StructureComponent;
import net.minecraft.world.gen.structure.StructureStart;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.ChestFillContext;

/**
 * F10 site half, general case: names the structure piece that is currently building, for every piece.
 *
 * <p>
 * The companion {@link StructureComponentChestMixin} hooks
 * {@code StructureComponent.generateStructureChestContents}, and the assumption was that every village, mineshaft,
 * stronghold and pyramid chest goes through it. Measured on daily-707 with {@code -Dgtnhdet.chesttrace=true}, that
 * is false for most village chests. They are filled by pieces that reach
 * {@code WeightedRandomChestContent.generateChestContents} by other routes:
 *
 * <ul>
 * <li>{@code astrotibs.villagenames.village.biomestructures.*} fill their chests inside {@code addComponentParts}
 * — this is where the vanilla-category {@code villageBlacksmith} chest now comes from, since VillageNames replaces
 * the vanilla pieces</li>
 * <li>{@code tconstruct.world.village.ComponentToolWorkshop} declares its own
 * {@code generateStructurePatternChestContents} and {@code generateStructureCraftingStationContents}</li>
 * <li>{@code mods.railcraft.common.worldgen.ComponentWorkshop.placeChest}</li>
 * <li>{@code com.emoniph.witchery.worldgen} declares a same-named {@code generateStructureChestContents} on its own
 * base class, which shadows the vanilla one rather than calling it, plus {@code setDispenser}</li>
 * </ul>
 *
 * <p>
 * Those chests were falling through to the absolute-position fork. That is still deterministic, so this is not a
 * determinism defect — but it costs the two properties the component-relative fork exists for: contents survive a
 * terrain shift, and contents are computable from a structure layout alone, which is what the seed-search
 * prefilter needs.
 *
 * <p>
 * Rather than chase each mod's own filler, wrap the one vanilla call site that drives all of them.
 * {@code StructureStart.generateStructure} is the only place {@code addComponentParts} is invoked, so every piece
 * of every structure passes through here exactly once per intersecting chunk box.
 */
@Mixin(StructureStart.class)
public abstract class StructureStartPartsMixin {

    @Redirect(
        method = "generateStructure",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/gen/structure/StructureComponent;addComponentParts(Lnet/minecraft/world/World;Ljava/util/Random;Lnet/minecraft/world/gen/structure/StructureBoundingBox;)Z"),
        require = 1)
    private boolean gtnhdet$siteAroundParts(StructureComponent comp, World world, Random rand,
        StructureBoundingBox box) {
        ChestFillContext.enterComponentBox(
            comp.getClass()
                .getName(),
            comp.getBoundingBox(),
            comp,
            world.getSeed());
        try {
            return comp.addComponentParts(world, rand, box);
        } finally {
            // finally, not a plain call after: a piece that throws must not leave its site on the stack for
            // whatever generates next.
            ChestFillContext.leaveComponent();
        }
    }
}
