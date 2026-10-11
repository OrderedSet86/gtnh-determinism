package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.GtOreShape;
import com.gtnhspeedrun.determinism.worldgen.GtOrePin;
import com.gtnhspeedrun.determinism.worldgen.VirginStoneType;

import gregtech.api.enums.StoneType;

/**
 * F4 for GregTech 5.09.52.31 through 5.09.54.01 — the band that includes GTNH 2.9.0-beta-1 (GT 5.09.52.594).
 *
 * <p>
 * Same fix and same reasoning as {@link WorldgenGTOreLayerMixin}; read that class's javadoc for why the reroll is
 * kept and only its input is corrected. This band asks {@link StoneType#findStoneType(World, int, int, int)} for
 * the probe, like 5.09.54.x does, but {@code executeWorldgenChunkified} has not yet been split into overloads —
 * so {@link WorldgenGTOreLayerStoneTypeMixin}'s spelled-out descriptor matches nothing here and that mixin fails
 * to bind. Until 0.14.0 the loader offered it anyway and beta-1 crashed at init.
 *
 * <p>
 * <b>The bare method name is deliberate and is the only selector that works across this band.</b> There is
 * exactly one {@code executeWorldgenChunkified} declaration on every tagged version in it, so the name is
 * unambiguous, and spelling out the descriptor would pin the mixin to one version's argument list. The
 * superclass {@code GTWorldgen} declares a method of the same name with a byte-identical descriptor, so javac
 * emits no bridge, and Mixin selectors search only the target class's own methods in any case.
 *
 * <p>
 * {@code require = 2, allow = 2} because both call sites are the reroll probe's two clipping branches. Pinning
 * the maximum as well as the minimum means a future GregTech that adds a third call site fails loudly instead of
 * quietly redirecting something this fix was never measured against.
 *
 * <p>
 * <b>No dry-run capture.</b> {@link WorldgenGTOreLayerStoneTypeMixin} carries an {@code @Inject} that forwards
 * {@code dryRun} to {@code GtOreColumnProbe}; this band has no such parameter, because the accept test is fused
 * with the real placement. {@code GtOreColumnProbe.inDryRun()} defaults false, so the {@code ASSERT_PROBE} path
 * degrades to reporting nothing rather than reporting wrongly. The corresponding pin for this band is
 * {@code GTWorldGenContainerDryRunMixin}, which reconstructs the dry run GregTech does not provide here.
 *
 * <p>
 * <b>Scope: {@link GtOrePin#appliesTo(World)}</b> — the same dimension whitelist and the same
 * {@code gtnhdet.orepin} master switch as the rest of the F4 family, so that "off" is bit-identical to stock and
 * an A/B can be run from one jar.
 */
@Mixin(value = gregtech.common.WorldgenGTOreLayer.class, remap = false)
public class WorldgenGTOreLayerFindStoneTypeMixin {

    @Redirect(
        method = "executeWorldgenChunkified",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/enums/StoneType;findStoneType(Lnet/minecraft/world/World;III)Lgregtech/api/enums/StoneType;"),
        require = 2,
        allow = 2)
    private static StoneType gtnhdet$virginStoneType(World world, int x, int y, int z) {
        if (!GtOrePin.appliesTo(world)) return StoneType.findStoneType(world, x, y, z);
        // Which virgin lookup is faithful depends on whether stock gates on canGenerateInWorld, and that changed
        // at 5.09.53.11 inside this band. Answering with the wrong one silently reroutes veins; see
        // VirginStoneType's javadoc.
        return GtOreShape.FIND_STONE_TYPE_UNGATED ? VirginStoneType.atUngated(world, x, y, z)
            : VirginStoneType.at(world, x, y, z);
    }
}
