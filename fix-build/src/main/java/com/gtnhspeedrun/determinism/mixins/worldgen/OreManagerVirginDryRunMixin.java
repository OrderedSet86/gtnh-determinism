package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.GtOreDryRun;
import com.gtnhspeedrun.determinism.worldgen.GtOrePin;
import com.gtnhspeedrun.determinism.worldgen.TerrainOracle;
import com.gtnhspeedrun.determinism.worldgen.VirginStoneType;

import gregtech.api.enums.StoneType;
import gregtech.api.interfaces.IOreMaterial;
import gregtech.api.interfaces.IStoneType;
import gregtech.common.ores.OreInfo;
import gregtech.common.ores.OreManager;

/**
 * F4d part B: answer the vein dry run's world reads from virgin terrain.
 *
 * <p>
 * Pinning the decision to the oreseed chunk ({@link GTWorldGenContainerOrePinMixin}) removes the trigger chunk
 * from the ARGUMENTS, but the dry run still asks the live world what is at those pinned coordinates — and
 * whether a given block has been generated yet depends on the route. Both halves are needed; neither works
 * alone, which is what the two reverted attempts established.
 *
 * <p>
 * After the pin, the dry run's complete live-world surface is exactly two calls, both reached through
 * {@code OreManager}:
 * <ul>
 * <li>{@code StoneType.findStoneType} in {@code getOreBlockForWorldGen} — "is this replaceable stone". Shared
 * with the REAL write path, so it must be gated on {@link GtOreDryRun}.</li>
 * <li>{@code getOreInfo} in {@code canSetOreForWorldGenOrAlreadySet} — "is one of my ore blocks already here".
 * On virgin terrain the answer is always no, because GT ore is written during population; answering from the
 * oracle stops the test inheriting whichever neighbouring vein happened to run first. That call site is
 * dry-run-only, so it needs no gate.</li>
 * </ul>
 * Everything else it touches is pure per dimension: {@code world.getActualHeight()},
 * {@code DimensionDef.getDimensionName}, and the ore adapters, which take no World.
 *
 * <p>
 * The gate is opened around the inner {@code canSetOreForWorldGen} call rather than around
 * {@code testWorldgenChunkified}, because {@code canSetOreForWorldGenOrAlreadySet} has exactly one caller in the
 * entire GregTech tree — {@code WorldgenGTOreLayer.placeOre}, inside {@code if (dryRun)} — so the scope cannot
 * reach the write path even in principle, and the try/finally closes it on any throw.
 *
 * <p>
 * {@code getOreBlockForWorldGen} is deliberately NOT reimplemented here: {@code ORE_ADAPTERS} is private, and
 * the public {@code getAdapter} filters on {@code adapter.supports(info)} whereas the real method filters on
 * {@code adapter.getBlock(info) != null}. A transcription would look right and be subtly wrong.
 */
@Mixin(value = OreManager.class, remap = false)
public class OreManagerVirginDryRunMixin {

    @Redirect(
        method = "canSetOreForWorldGenOrAlreadySet",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/common/ores/OreManager;canSetOreForWorldGen(Lnet/minecraft/world/World;III"
                + "Lgregtech/api/interfaces/IStoneType;Lgregtech/api/interfaces/IOreMaterial;Z)Z"),
        require = 1)
    private static boolean gtnhdet$scopedDryRun(World world, int x, int y, int z, IStoneType defaultStone,
        IOreMaterial material, boolean small) {
        if (!GtOrePin.ON) return OreManager.canSetOreForWorldGen(world, x, y, z, defaultStone, material, small);
        GtOreDryRun.open();
        try {
            return OreManager.canSetOreForWorldGen(world, x, y, z, defaultStone, material, small);
        } finally {
            GtOreDryRun.close();
        }
    }

    @Redirect(
        method = "getOreBlockForWorldGen",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/enums/StoneType;findStoneType(Lnet/minecraft/world/World;III)"
                + "Lgregtech/api/enums/StoneType;"),
        require = 1)
    private static StoneType gtnhdet$virginStone(World world, int x, int y, int z) {
        if (!GtOrePin.ON || !GtOreDryRun.active()) return StoneType.findStoneType(world, x, y, z);
        try {
            return VirginStoneType.at(world, x, y, z);
        } catch (Throwable t) {
            // Must not be swallowed: GTWorldgenerator catches Exception around the dry run and leaves the
            // result at a value matching no case, which silently selects a different vein.
            GtOrePin.reportOnce("findStoneType", x, y, z, t);
            throw t;
        }
    }

    @Redirect(
        method = "canSetOreForWorldGenOrAlreadySet",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/common/ores/OreManager;getOreInfo(Lnet/minecraft/world/IBlockAccess;III)"
                + "Lgregtech/common/ores/OreInfo;"),
        require = 1)
    private static OreInfo<IOreMaterial> gtnhdet$virginOreInfo(IBlockAccess access, int x, int y, int z) {
        if (!GtOrePin.ON || !(access instanceof World)) return OreManager.getOreInfo(access, x, y, z);
        final World world = (World) access;
        try {
            // GT's own (Block, meta) overload, fed virgin inputs — not a reimplementation of the adapter walk.
            return OreManager.getOreInfo(TerrainOracle.block(world, x, y, z), TerrainOracle.meta(world, x, y, z));
        } catch (Throwable t) {
            GtOrePin.reportOnce("getOreInfo", x, y, z, t);
            throw t;
        }
    }
}
