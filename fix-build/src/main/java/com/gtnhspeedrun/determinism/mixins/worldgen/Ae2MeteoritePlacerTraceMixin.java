package com.gtnhspeedrun.determinism.mixins.worldgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.determinism.GtnhDeterminism;

/**
 * Diagnostic for AE2 meteorites never generating in a probed world
 * ({@code -Dgtnhdet.meteortrace=true}). Read-only: it observes the verdict and never changes it.
 *
 * <h2>What is established</h2>
 *
 * 25 seeds, warm radius 30: <b>zero {@code TileSkyChest}</b>, against <b>32 over 25 seeds</b> once the
 * callable queue is drained, and {@code World/AE2/spawndata} empty afterwards — AE2 writes a record
 * there for every placement. An expectation is load-bearing here: {@code MeteoriteWorldGen} seeds ONE
 * candidate per {@code minMeteoriteDistance} grid cell (707 blocks in this pack), so a single
 * radius-60 window holds only a couple and zero there proves nothing on its own.
 *
 * <p>
 * Correcting an earlier version of this comment, which said {@code meteoriteSpawnChance 0=0.3} gates
 * whether a meteorite exists and put the expectation at ~13.8 with p ~ 7e-08. It does not gate
 * existence — nothing guards the {@code addCallable}. The roll only picks the STARTING DEPTH,
 * {@code spawnSurfaceMeteor ? 180 + rng.nextInt(20) : 128}. The real rate is higher than that model
 * gave, so the conclusion holds, but the number was wrong.
 *
 * <p>
 * A real client on the same seed and the same jar DOES produce a meteorite — reported under the
 * ENIKO tower on {@code -1636594104014467454} — so this is environmental to the headless probe.
 *
 * <h2>What has been ruled out</h2>
 *
 * <ul>
 * <li><b>Not collection.</b> {@code TileSkyChest extends AEBaseInvTile implements ISidedInventory},
 * so the probe's {@code IInventory} branch would collect it. The chests are not uncollected, the
 * meteorites are not there.</li>
 * <li><b>Not config.</b> {@code MeteoriteWorldGen=true}, {@code SpawnPressesInMeteorites=true},
 * dim 0 whitelisted, chance 0.3, distance 707.</li>
 * <li><b>Not Galacticraft.</b> {@code WorldUtil.otherModPreventGenerate} finds AE2 (falling back from
 * {@code appeng.hooks.} to {@code appeng.worldgen.}) but {@code findRegisteredWorldGenerator} only
 * iterates FML's set and returns a reference — it never removes.</li>
 * <li><b>Not the valid-block list.</b> {@code meteoriteValidBlocks} is ADDED to a {@code validSpawn}
 * collection already seeded with vanilla blocks. Additive, not a whitelist.</li>
 * <li><b>Not registration, and not AE2's own gate.</b> Measured with the probe's
 * {@code -Dprobe.wgdump} and {@code -Dprobe.meteortrace}:
 * {@code appeng.worldgen.MeteoriteWorldGen} is registered with FML, and
 * {@code isWorldGenEnabled(Meteorites, world)} returns {@code true} for dim 0 — despite the provider
 * being {@code WorldProviderSurfaceBOP} rather than vanilla, which was the obvious
 * {@code badProviders} suspect.</li>
 * </ul>
 *
 * <h2>Why this hook</h2>
 *
 * That leaves the placer. {@code spawnMeteoriteCenter()} returns the boolean verdict on whether a
 * meteorite may exist at the chosen spot, and it is the last thing between "AE2 decided to try here"
 * and "a meteorite is in the world". Logging its verdict with the position separates the two
 * remaining possibilities: if it is never called, the refusal is upstream in {@code generate} — the
 * 0.3 roll or the minimum-distance check against {@code spawndata}, which is empty and so should
 * never reject. If it is called and returns false, the placer is refusing the terrain, and
 * {@code validSpawn}/{@code invalidSpawn} against RWG's surface blocks is the next thing to dump.
 *
 * <p>
 * Inert unless the flag is set, and the mixin only applies when AE2 is installed — see
 * {@code LateMixinLoader}.
 */
@Mixin(targets = "appeng.worldgen.MeteoritePlacer", remap = false)
public abstract class Ae2MeteoritePlacerTraceMixin {

    private static final boolean gtnhdet$TRACE = Boolean.getBoolean("gtnhdet.meteortrace");

    /**
     * A flood guard, not a per-world budget. This is static and never reset, and a WARM batch runs
     * every seed in one JVM — at 64 it went quiet partway through a 25-seed batch and the tail of the
     * run looked meteorite-free when it was not. Sized for a whole batch instead, and it says so when
     * it trips rather than just stopping.
     */
    private static final int gtnhdet$CAP = 4096;
    private static int gtnhdet$seen;

    @Inject(method = "spawnMeteoriteCenter", at = @At("RETURN"), require = 1)
    private void gtnhdet$traceCenter(CallbackInfoReturnable<Boolean> cir) {
        if (!gtnhdet$TRACE) return;
        if (gtnhdet$seen == gtnhdet$CAP) {
            gtnhdet$seen++;
            GtnhDeterminism.LOG.warn("[meteortrace] cap {} reached; further verdicts NOT logged", gtnhdet$CAP);
            return;
        }
        if (gtnhdet$seen > gtnhdet$CAP) return;
        gtnhdet$seen++;
        // Read the placer's own fields reflectively rather than @Shadow-ing private finals: this is a
        // diagnostic and must not fail to apply if AE2 renames one.
        String pos = "?";
        String sky = "?";
        String size = "?";
        try {
            final Class<?> c = this.getClass();
            pos = gtnhdet$field(c, "x") + "," + gtnhdet$field(c, "y") + "," + gtnhdet$field(c, "z");
            sky = gtnhdet$field(c, "skyMode");
            size = gtnhdet$field(c, "meteoriteSize");
        } catch (Throwable ignored) {}
        // skyMode is what decides SURFACE EXPRESSION, and it is computed in the constructor from LIVE
        // reads (canBlockSeeTheSky over a 30x26x30 box, plus a `solid` scan of y-15..y-2 that zeroes it
        // outright on finding air). Neither is covered by Ae2MeteoriteSitingMixin, which redirects
        // spawnMeteoriteCenter only. Logging the verdict AND skyMode is what makes crater presence
        // diffable across walk orders without diffing terrain, where the ~75% block route-dependence
        // would bury the signal. Thresholds are AE2's: >10 carves the crater, >3 runs decay.
        int sm;
        try {
            sm = Integer.parseInt(sky);
        } catch (NumberFormatException e) {
            sm = -1;
        }
        GtnhDeterminism.LOG.info(
            "[meteortrace] spawnMeteoriteCenter at {} -> {} skyMode={} crater={} decay={} size={}",
            pos,
            cir.getReturnValue(),
            sky,
            sm > 10,
            sm > 3,
            size);
    }

    private String gtnhdet$field(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                final java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return String.valueOf(f.get(this));
            } catch (NoSuchFieldException e) {
                // keep walking up
            } catch (Throwable t) {
                return "?";
            }
        }
        return "?";
    }
}
