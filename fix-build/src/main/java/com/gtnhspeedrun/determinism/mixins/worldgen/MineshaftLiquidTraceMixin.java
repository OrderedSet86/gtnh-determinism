package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraft.world.gen.structure.StructureComponent;
import net.minecraft.world.gen.structure.StructureMineshaftPieces;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.determinism.worldgen.TerrainOracle;

/**
 * Diagnostic for the mineshaft corridor residual. Writes nothing and binds nothing unless
 * {@code -Dgtnhdet.mineshafttrace=true}.
 *
 * <p>
 * results/2026-09-17-mineshaft-multiseed left one chest difference across 25 seeds, and the block dumps showed
 * it is a corridor COMPONENT that exists in one walk order and not the other. Two mechanisms inside
 * {@code isLiquidInStructureBoundingBox} could do that and they need different fixes:
 *
 * <ol>
 * <li><b>Live reads.</b> The scan calls {@code world.getBlock}, so it sees liquid that a neighbouring chunk's
 * population already placed. Fixable by answering from {@link TerrainOracle}.</li>
 * <li><b>Window clipping.</b> Every bound is {@code Math.max(pieceBox.min - 1, window.min)} — the scanned
 * shell is the piece box INTERSECTED with the populate window currently building it, so two windows scan
 * different sub-volumes of the same piece and can disagree from identical terrain. Virgin reads do not touch
 * this.</li>
 * </ol>
 *
 * <p>
 * The final block dumps cannot separate them: they show liquid identical in both arms, which argues for (2),
 * but the scan runs mid-population and transient liquid is invisible afterwards. So this logs the inputs and
 * evaluates the shell scan four ways per call — {clipped, full} x {live, virgin} — and a diff of two walks
 * answers it directly:
 *
 * <ul>
 * <li>stock verdicts differ while {@code full+live} agrees -&gt; mechanism (2), clipping</li>
 * <li>stock verdicts differ while {@code clipped+virgin} agrees -&gt; mechanism (1), live reads</li>
 * <li>only {@code full+virgin} agrees -&gt; both, and the fix needs both halves</li>
 * </ul>
 *
 * <p>
 * The scan is transcribed from vanilla {@code StructureComponent.isLiquidInStructureBoundingBox}: the six faces
 * of the box expanded by one. Transcribed rather than called so the clipped and full variants can share one
 * implementation; {@code CLIPPED} reproduces stock exactly and is the control for that transcription — if the
 * logged {@code clipped+live} ever disagrees with the component's own behaviour, the transcription is wrong and
 * nothing else in the line is trustworthy.
 */
@Mixin(StructureMineshaftPieces.Corridor.class)
public abstract class MineshaftLiquidTraceMixin {

    private static final Logger LOG = LogManager.getLogger("gtnhdeterminism");

    private static boolean gtnhdet$liquidAt(World world, int x, int y, int z, boolean virgin) {
        return (virgin ? TerrainOracle.block(world, x, y, z) : world.getBlock(x, y, z)).getMaterial()
            .isLiquid();
    }

    /** Vanilla's six-face shell scan. {@code window} null = do not clip (the full piece box). */
    private static boolean gtnhdet$scan(World world, StructureBoundingBox bb, StructureBoundingBox window,
        boolean virgin) {
        final int i = window == null ? bb.minX - 1 : Math.max(bb.minX - 1, window.minX);
        final int j = window == null ? bb.minY - 1 : Math.max(bb.minY - 1, window.minY);
        final int k = window == null ? bb.minZ - 1 : Math.max(bb.minZ - 1, window.minZ);
        final int l = window == null ? bb.maxX + 1 : Math.min(bb.maxX + 1, window.maxX);
        final int i1 = window == null ? bb.maxY + 1 : Math.min(bb.maxY + 1, window.maxY);
        final int j1 = window == null ? bb.maxZ + 1 : Math.min(bb.maxZ + 1, window.maxZ);
        for (int a = i; a <= l; ++a) {
            for (int b = k; b <= j1; ++b) {
                if (gtnhdet$liquidAt(world, a, j, b, virgin)) return true;
                if (gtnhdet$liquidAt(world, a, i1, b, virgin)) return true;
            }
        }
        for (int a = i; a <= l; ++a) {
            for (int b = j; b <= i1; ++b) {
                if (gtnhdet$liquidAt(world, a, b, k, virgin)) return true;
                if (gtnhdet$liquidAt(world, a, b, j1, virgin)) return true;
            }
        }
        for (int a = k; a <= j1; ++a) {
            for (int b = j; b <= i1; ++b) {
                if (gtnhdet$liquidAt(world, i, b, a, virgin)) return true;
                if (gtnhdet$liquidAt(world, l, b, a, virgin)) return true;
            }
        }
        return false;
    }

    private static String gtnhdet$box(StructureBoundingBox b) {
        return b.minX + "," + b.minY + "," + b.minZ + ".." + b.maxX + "," + b.maxY + "," + b.maxZ;
    }

    @Inject(method = "addComponentParts", at = @At("HEAD"), require = 1)
    private void gtnhdet$traceLiquid(World world, Random rand, StructureBoundingBox window,
        CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.getBoolean("gtnhdet.mineshafttrace")) return;
        final StructureBoundingBox bb = ((StructureComponent) (Object) this).getBoundingBox();
        try {
            LOG.info(
                "[mshtrace] piece={} window={} clipped_live={} full_live={} clipped_virgin={} full_virgin={}",
                gtnhdet$box(bb),
                gtnhdet$box(window),
                gtnhdet$scan(world, bb, window, false),
                gtnhdet$scan(world, bb, null, false),
                gtnhdet$scan(world, bb, window, true),
                gtnhdet$scan(world, bb, null, true));
        } catch (Throwable t) {
            // A trace must never change the run it is measuring.
            LOG.warn("[mshtrace] scan failed for piece {}", gtnhdet$box(bb), t);
        }
    }
}
