package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.determinism.worldgen.GtOreColumnProbe;
import com.gtnhspeedrun.determinism.worldgen.GtOrePin;
import com.gtnhspeedrun.determinism.worldgen.VirginStoneType;

import gregtech.api.enums.StoneType;
import gregtech.api.objects.XSTR;

/**
 * F4 for GregTech 5.09.54.x and later. Same fix as {@link WorldgenGTOreLayerMixin}, same reasoning — read that
 * class's javadoc for why the reroll is kept and only its input is corrected — retargeted onto the probe GT moved.
 *
 * <p>
 * 5.09.54.x rewrote the ore API and deleted {@code Block.isReplaceableOreGen}, which the pre-54 mixin redirects. The
 * vein-reroll probe survived the rewrite unchanged in shape: {@code executeWorldgenChunkified} still runs nine
 * {@code veinMinY + i} samples at the chunk-centre column and still rerolls the vein when fewer than five are stone,
 * but it now asks {@link StoneType#findStoneType(World, int, int, int)} instead. That call reads the live world, so
 * the vein identity it decides still depends on which neighbouring chunks the player's route had already generated
 * and populated. Answering it from virgin terrain makes it a pure function of the seed while leaving GT's reroll
 * design intact.
 *
 * <p>
 * The handler replicates GT's own loop rather than calling {@code findStoneType(Block, int)}, which looks equivalent
 * but drops the {@code canGenerateInWorld} gate. Where two stone types claim the same block and only the later one
 * is allowed in this dimension, the two-argument overload answers with the earlier one, and the probe would diverge
 * from stock in exactly the dimension-restricted cases the gate exists for.
 *
 * <p>
 * Applied only when {@code gregtech.api.enums.StoneType} is on the classpath; see LateMixinLoader. The class does
 * not exist before 5.09.54.x, so on older packs this mixin cannot be loaded at all and the pre-54 one runs instead.
 *
 * <p>
 * <b>Scope: {@link GtOrePin#appliesTo(World)}</b> — the same dimension whitelist and the same
 * {@code gtnhdet.orepin} master switch as the rest of the F4 family. Until 2026-09-07 this handler carried no
 * gate at all: it rewrote the probe in EVERY dimension at EVERY flag setting, so the Nether and every
 * GalacticGreg body were running virgin terrain on no evidence, and {@code -Dgtnhdet.orepin=false} did not
 * restore stock. That made a stock arm unbuildable, which is why the whitelist now governs all four handlers in
 * the family rather than only the coordinate pin. See results/2026-09-05-gt-ore-canonical-trigger's correction
 * note.
 *
 * <p>
 * Where the pin applies this handler is very nearly inert, which is not obvious and is worth writing down.
 * Under the pin {@code chunkX == seedX}, and {@code resolveVeinPlacement} guarantees
 * {@code veinWestX <= seedX} and {@code veinEastX >= seedX + 16}, so the clipping test
 * {@code limitWestX >= limitEastX} in {@code executeWorldgenChunkified} can never be true and the nine-sample
 * branch below is unreachable in the DRY RUN. It still runs on {@code generateCachedVein}'s real call, which is
 * not pinned — but there the return feeds only a debug line and {@code VeinGenerateEvent.placementResult}, so it
 * can move what a listener records and never a block. The reachability claim is asserted at runtime by
 * {@code -Dgtnhdet.orepin.assertprobe}; see {@link GtOrePin#ASSERT_PROBE}.
 */
@Mixin(value = gregtech.common.WorldgenGTOreLayer.class, remap = false)
public class WorldgenGTOreLayerStoneTypeMixin {

    // The descriptor is spelled out because 5.09.54.x split executeWorldgenChunkified into two overloads and only
    // this one, the private ten-argument body, holds the probe. The pre-54 mixin gets away with a bare name because
    // the method was unique there; here a bare name matches nothing and the mixin fails to bind.
    @Redirect(
        method = "executeWorldgenChunkified(Lnet/minecraft/world/World;Lgregtech/api/objects/XSTR;Ljava/lang/String;IIIILgregtech/common/WorldgenGTOreLayer$VeinPlacement;ZZ)I",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/enums/StoneType;findStoneType(Lnet/minecraft/world/World;III)Lgregtech/api/enums/StoneType;"),
        require = 2)
    private static StoneType gtnhdet$virginStoneType(World world, int x, int y, int z) {
        if (!GtOrePin.appliesTo(world)) return StoneType.findStoneType(world, x, y, z);
        // N0: this redirect binds at require = 2, i.e. ONLY the two clipping branches, so reaching it from a
        // pinned dry run is exactly the event that would falsify the unreachability argument.
        if (GtOrePin.ASSERT_PROBE) {
            GtOrePin.noteColumnProbeSample(x, y, z); // positive control: proves this redirect runs at all
            if (GtOreColumnProbe.inDryRun()) GtOrePin.reportColumnProbeReached(x, y, z);
        }
        return VirginStoneType.at(world, x, y, z);
    }

    /**
     * Carry {@code dryRun} down to the column probe for {@link GtOrePin#ASSERT_PROBE}.
     *
     * <p>
     * {@code @Coerce Object} on the {@code VeinPlacement} parameter is not stylistic: that record is
     * package-private in {@code gregtech.common}, and a handler that names it generates a {@code CHECKCAST}
     * against an inaccessible type, which is the {@code IllegalAccessError} documented on
     * {@link GTWorldGenContainerOrePinMixin}. The parameter is never touched here — only its slot has to line up.
     */
    @Inject(
        method = "executeWorldgenChunkified(Lnet/minecraft/world/World;Lgregtech/api/objects/XSTR;Ljava/lang/String;IIIILgregtech/common/WorldgenGTOreLayer$VeinPlacement;ZZ)I",
        at = @At("HEAD"),
        require = 1)
    private void gtnhdet$captureDryRun(World world, XSTR rng, String biome, int chunkX, int chunkZ, int seedX,
        int seedZ, @Coerce Object placement, boolean dryRun, boolean resetRng, CallbackInfoReturnable<Integer> cir) {
        if (GtOrePin.ASSERT_PROBE) GtOreColumnProbe.enter(dryRun);
    }
}
