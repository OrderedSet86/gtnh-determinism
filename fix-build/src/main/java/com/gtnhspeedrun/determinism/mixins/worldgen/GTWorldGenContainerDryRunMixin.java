package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.GtOreDryRun;
import com.gtnhspeedrun.determinism.worldgen.GtOreFusedWorldgen;
import com.gtnhspeedrun.determinism.worldgen.GtOrePin;

import gregtech.api.objects.XSTR;
import gregtech.api.world.GTWorldgen;
import gregtech.common.WorldgenGTOreLayer;

/**
 * F4d for GregTech 5.09.52.31 .. 5.09.54.01 — the band that has no dry run, so one is reconstructed here.
 *
 * <p>
 * <b>The bug.</b> Vein IDENTITY is decided once per oreseed region by whichever chunk happens to trigger it
 * first, and cached in {@code validOreveins} for the whole region. {@code generateVein} passes
 * {@code this.mX * 16, this.mZ * 16} — the TRIGGERING chunk — as {@code chunkX/chunkZ}, and three things inside
 * {@code executeWorldgenChunkified} key off that: the clipping window {@code [chunkX+2, chunkX+2+16)}, the
 * nine-sample probe column at {@code (chunkX+7, chunkZ+9)}, and {@code localDensity} via {@code dx/dz}. When the
 * clipping test rejects, the return is {@code NO_OVERLAP_AIR_BLOCK}, which bumps {@code placementAttempts} and
 * rolls a DIFFERENT vein. So the player's route picks the ore.
 *
 * <p>
 * <b>Why it cannot be fixed the way 5.09.54.02+ is.</b> There, GregTech had already split the work into
 * {@code resolveVeinPlacement} (geometry) / {@code testWorldgenChunkified} (accept, writes nothing) / placement
 * with {@code resetRng}, so {@code GTWorldGenContainerOrePinMixin} only has to swap two arguments with
 * {@code @ModifyArg}. Here all three jobs live in ONE call that also writes the ore, so simply pinning its
 * coordinates would relocate the actual ore write up to three chunks away, into chunks Forge has not asked for.
 * The split has to be reconstructed first.
 *
 * <p>
 * <b>The reconstruction.</b> This redirect replaces the selection-loop call with the same two-phase sequence
 * GregTech itself adopted in 5.09.54.02:
 *
 * <ol>
 * <li><b>Decide.</b> Call pinned to the oreseed ({@code chunkX/chunkZ := seedX/seedZ}) inside a
 * {@link GtOreDryRun} scope, so {@link OreManagerDryRunSuppressMixin} suppresses the writes and
 * {@code OreManagerVirginStoneB1Mixin}/{@code B2} answer its terrain reads from virgin blocks. The verdict is now
 * a pure function of the oreseed.</li>
 * <li><b>Place.</b> If the verdict accepted the vein, rewind the RNG and call again with the TRUE
 * {@code chunkX/chunkZ} and writes enabled, which puts this chunk's slice of ore exactly where stock would.</li>
 * <li>Return the DECIDE verdict, so GregTech's switch caches the oreseed-pure choice.</li>
 * </ol>
 *
 * <p>
 * <b>Why the rewind is exact.</b> The two phases consume different numbers of draws, because their clipping
 * windows and {@code localDensity} differ — so the second call must start from the first call's initial state or
 * the geometry would differ. {@link XSTR#getSeed()} and {@link XSTR#setSeed(long)} are a plain read and a plain
 * assignment of the generator's whole state, with no scrambling, so saving and restoring round-trips exactly.
 * This is the same thing GregTech does at 5.09.54.02+ with {@code resetRng} and a stored
 * {@code generationSeed()}; that record does not exist here, but the loop re-seeds {@code veinRNG} immediately
 * before this call, so the pre-call state IS the generation seed.
 *
 * <p>
 * <b>Ordinal 1 is load-bearing.</b> {@code generateVein} calls this method twice with the same descriptor: the
 * cached path at ordinal 0, which serves every later chunk of an already-decided region, and the selection loop
 * at ordinal 1. Only the selection loop may be pinned — the cached path IS the legitimate per-chunk write, and
 * pinning it would move ore.
 *
 * <p>
 * <b>Consequence worth knowing.</b> The {@code VeinGenerateEvent} that {@code generateVein} posts after this call
 * now carries the pinned verdict rather than the triggering chunk's. That is the self-consistent choice — it is
 * the verdict that gets cached in {@code validOreveins} — but it is a visible change for listeners such as
 * VisualProspecting, and it is why the A/B check compares vein identity rather than only block output.
 *
 * <p>
 * <b>Scope: {@link GtOrePin#appliesTo(World)}</b>, and it degrades to stock rather than guessing: if the RNG is
 * not an {@link XSTR} it cannot be rewound, so the original single unpinned call runs exactly as stock. With
 * {@code gtnhdet.orepin=false} the dry-run scope is never opened and the jar is bit-identical to stock. Note that
 * a redirect cannot fall back further than that — it has REPLACED GregTech's call, so if the fused overload
 * cannot be resolved at all {@link GtOreFusedWorldgen} throws rather than inventing a verdict.
 */
@Mixin(targets = "gregtech.common.GTWorldgenerator$WorldGenContainer", remap = false)
public class GTWorldGenContainerDryRunMixin {

    private static final String FUSED = "Lgregtech/common/WorldgenGTOreLayer;executeWorldgenChunkified("
        + "Lnet/minecraft/world/World;Ljava/util/Random;Ljava/lang/String;IIII"
        + "Lnet/minecraft/world/chunk/IChunkProvider;Lnet/minecraft/world/chunk/IChunkProvider;)I";

    @Redirect(
        method = "generateVein(II)V",
        at = @At(value = "INVOKE", target = FUSED, ordinal = 1),
        require = 1,
        allow = 1)
    // The receiver MUST be declared as the concrete owner of the redirected call: Mixin validates the handler
    // signature against the call site and rejects Object with "Found unexpected argument type java.lang.Object at
    // index 0". Naming the class is fine — it exists at the compile pin; what does not exist there is the fused
    // OVERLOAD, which is why the body goes through GtOreFusedWorldgen instead of calling it directly.
    private int gtnhdet$decideThenPlace(WorldgenGTOreLayer layer, World world, Random rng, String biome, int chunkX,
        int chunkZ, int seedX, int seedZ, IChunkProvider chunkGenerator, IChunkProvider chunkProvider) {
        // rng instanceof XSTR is the rewind precondition: without the exact state save/restore the second pass
        // would start mid-stream and place a different vein's geometry. Anything else degrades to stock.
        final boolean canPin = GtOrePin.appliesTo(world) && rng instanceof XSTR;
        if (!canPin) {
            return GtOreFusedWorldgen
                .call(layer, world, rng, biome, chunkX, chunkZ, seedX, seedZ, chunkGenerator, chunkProvider);
        }

        final XSTR xstr = (XSTR) rng;
        final long generationSeed = xstr.getSeed();

        final int verdict;
        GtOreDryRun.open();
        try {
            verdict = GtOreFusedWorldgen
                .call(layer, world, rng, biome, seedX, seedZ, seedX, seedZ, chunkGenerator, chunkProvider);
        } finally {
            // finally, not after the call: generateVein swallows exceptions, so a throw that left the scope open
            // would make every subsequent REAL placement on this thread write nothing at all.
            GtOreDryRun.close();
        }

        if (verdict == GTWorldgen.ORE_PLACED || verdict == GTWorldgen.NO_OVERLAP) {
            xstr.setSeed(generationSeed);
            GtOreFusedWorldgen
                .call(layer, world, rng, biome, chunkX, chunkZ, seedX, seedZ, chunkGenerator, chunkProvider);
        }

        return verdict;
    }
}
