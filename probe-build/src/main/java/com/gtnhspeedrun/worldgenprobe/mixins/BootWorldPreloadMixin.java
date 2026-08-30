package com.gtnhspeedrun.worldgenprobe.mixins;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.worldgenprobe.WorldgenProbe;

/**
 * OPT-IN, and it changes results: skip the boot world's spawn preload in warm mode.
 *
 * <h2>Do not enable this expecting a free speed-up</h2>
 *
 * The idea is tempting — in warm mode the boot world is torn down before the first requested seed generates, so
 * its 625-chunk preload looks like pure waste, and harness-speed.md A.3 lists it as an optional boot shave. It is
 * <b>not</b> free. Measured on daily-707, seed 4329705733811276668, radius 12, warm rows:
 *
 * <pre>
 * boot preload on  vs off   : 314 of 625 chunks differ (313 blocks-only)
 * control, on  vs on        :   2 of 625
 * control, off vs off       :   4 of 625
 * </pre>
 *
 * 314 against a 2-4 chunk noise floor is a real effect, so generating the boot world is load-bearing for what the
 * measured world comes out as. The mechanism is not established — some static that the reset registry does not
 * cover is presumably left in a different state when nothing has generated yet — and until it is, enabling this
 * silently changes every result.
 *
 * <h2>What it was for</h2>
 *
 * Removing a footgun: a warm run generates two worlds — the boot world and the requested seed's — and worldgen
 * instrumentation fires in both. The Witchery placement trace was 46% boot-world lines, which produced two wrong
 * findings. Not generating the second world would have made that impossible for every instrument at once.
 *
 * Since it cannot ship, the trap is closed instead by scoping: traces carry {@code seed=} and honour
 * {@code gtnhdet.tracescope}, which the probe sets to the seed it is measuring. See {@code TraceScope} in the fix
 * jar.
 */
@Mixin(MinecraftServer.class)
public abstract class BootWorldPreloadMixin {

    @Inject(method = "initialWorldChunkLoad", at = @At("HEAD"), cancellable = true, require = 1)
    private void probe$skipBootWorldPreload(CallbackInfo ci) {
        // -Dprobe.bootpreload=true restores the old behaviour, so the skip can be A/B'd with one jar rather
        // than two builds — which is how its neutrality on the measured world was established.
        if (System.getProperty("probe.seeds") != null && Boolean.getBoolean("probe.skipbootpreload")) {
            WorldgenProbe.LOG.info(
                "[probe] -Dprobe.skipbootpreload: skipping the boot world preload — THIS CHANGES THE MEASURED WORLD "
                    + "(314/625 chunks vs a 2-4 chunk noise floor); see BootWorldPreloadMixin");
            ci.cancel();
        }
    }
}
