package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.determinism.worldgen.GtOrePin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * F4d part A: decide a vein's identity from the ORESEED chunk instead of whichever chunk happened to trigger it.
 *
 * <p>
 * {@code generateVein} walks candidate mixes and accepts the first whose dry run approves, but it passes
 * {@code this.mX * 16, this.mZ * 16} — the chunk Forge is populating right now, i.e. the player's route — as the
 * chunkX/chunkZ arguments to both {@code resolveVeinPlacement} and {@code testWorldgenChunkified}. Those
 * coordinates then steer the accept/reject test three separate ways:
 *
 * <ol>
 * <li>the clipping window {@code [chunkX+2, chunkX+18)}, which decides whether the dry run takes the early
 * {@code NO_OVERLAP} / {@code NO_OVERLAP_AIR_BLOCK} return or falls through to real placement — the dominant
 * channel;</li>
 * <li>{@code localDensity = mDensity / sqrt(2 + dx^2 + dz^2)}, whose {@code dx}/{@code dz} are the trigger-to-
 * oreseed chunk offset, feeding the per-cell placement chances;</li>
 * <li>the probe column {@code (chunkX+7, chunkZ+9)}, which sits inside the trigger chunk.</li>
 * </ol>
 *
 * A {@code NO_OVERLAP_AIR_BLOCK} verdict burns a placement attempt and rolls a DIFFERENT mix, so the trigger
 * chunk does not merely perturb the vein — it selects it. Two earlier fixes each closed channel 3 alone and each
 * moved single digits; see results/2026-08-27-gt-ore-probe-pinning and results/2026-09-05-gt-ore-dryrun-virgin.
 * Pinning the arguments closes all three at once.
 *
 * <p>
 * Pinning {@code resolveVeinPlacement} is a no-op in the overworld and Twilight Forest — that method reads
 * chunkX/chunkZ only inside its {@code !respectsOreVeinHeights()} branch, which only The End takes — so the entire
 * measured effect flows through {@code testWorldgenChunkified}. The End is excluded outright by
 * {@code gtnhdet$pin}; see that field for why pinning it would be both unsafe and insufficient.
 *
 * <p>
 * Applies to every other dimension, with no per-dimension code: Twilight Forest is covered by the same handlers
 * and measured at 0 of 1728 regions differing between a rows and a spiral walk, down from 225 of 1702.
 *
 * <p>
 * <b>Why {@code @ModifyArg} and not {@code @ModifyArgs}.</b> {@code @ModifyArgs} generates a synthetic
 * {@code Args} subclass in {@code org.spongepowered.asm.synthetic.args} whose getters {@code CHECKCAST} every
 * argument type — including {@code WorldgenGTOreLayer$VeinPlacement}, which is a package-private record. That
 * resolves to {@code IllegalAccessError} the first time a vein generates. {@code @ModifyArg} in single-argument
 * mode stores the trailing arguments in locals of the TARGET method, which lives in {@code gregtech.common} and
 * needs no access check, and the handler signature is {@code (I)I} so no inaccessible type is ever named.
 * {@code @Redirect} has the same naming problem and is only a fallback (via {@code @Coerce}).
 */
@Mixin(targets = "gregtech.common.GTWorldgenerator$WorldGenContainer", remap = false)
public class GTWorldGenContainerOrePinMixin {

    private static final String RESOLVE = "Lgregtech/common/WorldgenGTOreLayer;resolveVeinPlacement("
        + "Lnet/minecraft/world/World;Lgregtech/api/objects/XSTR;IIII)"
        + "Lgregtech/common/WorldgenGTOreLayer$VeinPlacement;";

    private static final String TEST = "Lgregtech/common/WorldgenGTOreLayer;testWorldgenChunkified("
        + "Lnet/minecraft/world/World;Lgregtech/api/objects/XSTR;Ljava/lang/String;IIII"
        + "Lgregtech/common/WorldgenGTOreLayer$VeinPlacement;)I";

    // The oreseed is a parameter of generateVein, not a field, so a single-argument @ModifyArg handler cannot
    // see it. Stash it at HEAD. Per-instance, and WorldGenContainer is constructed fresh per chunk.
    @Shadow
    @Final
    public World mWorld;

    @Unique
    private int gtnhdet$oreseedX;
    @Unique
    private int gtnhdet$oreseedZ;
    /**
     * Whether to pin for THIS container, from {@link GtOrePin#appliesTo} — a dimension WHITELIST, default
     * overworld and Twilight Forest, the only two with measured evidence. Every other dimension keeps stock
     * behaviour until someone measures it; see that field for why a blacklist here was the wrong shape.
     */
    @Unique
    private boolean gtnhdet$pin;

    @Inject(method = "generateVein(II)V", at = @At("HEAD"), require = 1)
    private void gtnhdet$captureOreseed(int oreseedX, int oreseedZ, CallbackInfo ci) {
        this.gtnhdet$oreseedX = oreseedX;
        this.gtnhdet$oreseedZ = oreseedZ;
        this.gtnhdet$pin = this.mWorld != null && this.mWorld.provider != null
            && GtOrePin.appliesTo(this.mWorld.provider.dimensionId);
    }

    @ModifyArg(method = "generateVein(II)V", at = @At(value = "INVOKE", target = RESOLVE), index = 2, require = 1)
    private int gtnhdet$resolveChunkX(int chunkX) {
        return this.gtnhdet$pin ? this.gtnhdet$oreseedX * 16 : chunkX;
    }

    @ModifyArg(method = "generateVein(II)V", at = @At(value = "INVOKE", target = RESOLVE), index = 3, require = 1)
    private int gtnhdet$resolveChunkZ(int chunkZ) {
        return this.gtnhdet$pin ? this.gtnhdet$oreseedZ * 16 : chunkZ;
    }

    @ModifyArg(method = "generateVein(II)V", at = @At(value = "INVOKE", target = TEST), index = 3, require = 1)
    private int gtnhdet$testChunkX(int chunkX) {
        return this.gtnhdet$pin ? this.gtnhdet$oreseedX * 16 : chunkX;
    }

    @ModifyArg(method = "generateVein(II)V", at = @At(value = "INVOKE", target = TEST), index = 4, require = 1)
    private int gtnhdet$testChunkZ(int chunkZ) {
        return this.gtnhdet$pin ? this.gtnhdet$oreseedZ * 16 : chunkZ;
    }

    // ---- totality audit (-Dgtnhdet.orepin.audit=true) ----------------------------------------------------
    //
    // Under the pin every chunk of the 5x5 box feeds the decision identical arguments, so if the decision is
    // total then every chunk must reach the same answer. Suppressing the cache lookup makes each chunk redo the
    // decision, and comparing at the put() reports any disagreement. That turns totality into a property a
    // SINGLE walk can prove, and names the offending oreseeds — where a two-walk comparison only yields "N
    // regions differ" with no pointer to the cause.
    //
    // Audit mode is slower (no memoisation) and must never be on for a measurement run: the recompute changes
    // how many times the live world is read, which is the very thing under test.

    @Redirect(
        method = "generateVein(II)V",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;containsKey(J)Z"),
        require = 1)
    private boolean gtnhdet$auditBypassCache(Long2ObjectOpenHashMap<?> map, long oreveinSeed) {
        if (GtOrePin.AUDIT) return false;
        return map.containsKey(oreveinSeed);
    }

    @Redirect(
        method = "generateVein(II)V",
        at = @At(
            value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;put(JLjava/lang/Object;)"
                + "Ljava/lang/Object;"),
        require = 1)
    private Object gtnhdet$auditComparePut(Long2ObjectOpenHashMap<Object> map, long oreveinSeed, Object decided) {
        if (GtOrePin.AUDIT) {
            final Object previous = map.get(oreveinSeed);
            // CachedOreVein is a public record, so equals() covers layer, placementSeed and placement without
            // this class ever naming the package-private VeinPlacement.
            if (previous != null && !previous.equals(decided)) {
                GtOrePin.reportAuditMismatch(oreveinSeed, previous, decided);
            }
        }
        return map.put(oreveinSeed, decided);
    }
}
