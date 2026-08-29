package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraft.world.gen.structure.StructureComponent;
import net.minecraft.world.gen.structure.StructureVillagePieces;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Village components spawn a route-dependent number of villagers.
 * <p>
 * A village piece is built once per populate window it intersects, and each call is clipped to that window:
 * {@code MapGenStructure.generateStructuresInChunk} passes {@code new StructureBoundingBox(k, l, k+15, l+15)} with
 * {@code k = (chunkX << 4) + 8}, so window boundaries fall at world x and z congruent to 8 mod 16. Consecutive
 * villager indices map to adjacent world columns via {@code getXWithOffset}, so a multi-villager piece can straddle
 * one.
 * <p>
 * Stock {@code spawnVillagers} handles that with a {@code break} and a single high-water counter, and it increments
 * the counter before the spawn call:
 *
 * <pre>
 * for (int i = this.villagersSpawned; i &lt; count; ++i) {
 *     if (!sbb.isVecInside(x, y, z)) break;   // leaves the loop entirely
 *     ++this.villagersSpawned;                 // persisted as "VCount"
 * </pre>
 *
 * For a two-villager piece split across windows W0 (low) and W1 (high):
 * <ul>
 * <li>W0 populates first: spawns villager 0, breaks at index 1. W1 later resumes at index 1 and spawns it. Two
 * villagers.</li>
 * <li>W1 populates first: index 0 lies outside W1, so the loop breaks immediately with the counter still 0. W0 later
 * spawns index 0 and breaks at index 1, which W1 has already passed. One villager, permanently.</li>
 * </ul>
 *
 * Which window populates first is the player's exploration route, so the same seed yields a different villager count
 * and a different set of professions depending on the approach direction. No RNG is involved. Affected vanilla pieces
 * are Hall (butcher + farmer) and House3 (two farmers); mod pieces calling the same base method are Forestry's
 * ComponentVillageBeeHouse and Railcraft's ComponentWorkshop.
 * <p>
 * The fix tracks placement per index instead of as a high-water mark, and skips rather than breaks. Each villager is
 * then placed by whichever window contains it, in any order. This is not a re-roll: the output equals stock's
 * best-case route, so no seed loses a villager it previously had.
 * <p>
 * Unlike the rest of this package, the target is a vanilla class, so {@code remap} stays at its default and the
 * mixin is registered in the early config rather than through the late loader.
 */
/*
 * Extends StructureComponent rather than @Shadow-ing getXWithOffset/getYWithOffset/getZWithOffset: those are
 * declared on the superclass, and Mixin resolves @Shadow members against the target class itself, so shadowing
 * them fails at APPLY time with "@Shadow method func_74865_a ... was not located in the target class". Declaring
 * the superclass makes the inherited protected methods callable directly. Only members actually declared on
 * Village (villagersSpawned, getVillagerType) are shadowed.
 */
@Mixin(StructureVillagePieces.Village.class)
public abstract class SpawnVillagersMixin extends StructureComponent {

    /**
     * Stock's high-water mark. Kept in sync with the bitmask so "VCount" stays truthful for vanilla and for any mod
     * that reads it.
     */
    @Shadow
    private int villagersSpawned;

    @Shadow
    protected abstract int getVillagerType(int index);

    /** One bit per villager index. 32 is far above the largest count in the pack (2). */
    @Unique
    private int gtnhdet$spawnedMask;

    @Inject(method = "spawnVillagers", at = @At("HEAD"), cancellable = true)
    private void gtnhdet$spawnPerIndex(World world, StructureBoundingBox sbb, int xOff, int yOff, int zOff, int count,
        CallbackInfo ci) {
        // An int mask cannot describe more indices than it has bits. Nothing in the pack comes close, but fall
        // through to stock behaviour rather than silently dropping villagers if some mod does.
        if (count > 32) return;
        ci.cancel();
        for (int i = 0; i < count; i++) {
            if ((this.gtnhdet$spawnedMask & (1 << i)) != 0) continue;
            final int wx = this.getXWithOffset(xOff + i, zOff);
            final int wy = this.getYWithOffset(yOff);
            final int wz = this.getZWithOffset(xOff + i, zOff);
            // Skip, do not break: a later index can be inside this window when an earlier one is not.
            if (!sbb.isVecInside(wx, wy, wz)) continue;
            this.gtnhdet$spawnedMask |= (1 << i);
            this.villagersSpawned = Integer.bitCount(this.gtnhdet$spawnedMask);
            final EntityVillager villager = new EntityVillager(world, this.getVillagerType(i));
            villager.setLocationAndAngles(wx + 0.5D, wy, wz + 0.5D, 0.0F, 0.0F);
            world.spawnEntityInWorld(villager);
        }
    }

    @Inject(method = "func_143012_a", at = @At("TAIL"))
    private void gtnhdet$writeMask(NBTTagCompound tag, CallbackInfo ci) {
        tag.setInteger("VMask", this.gtnhdet$spawnedMask);
    }

    @Inject(method = "func_143011_b", at = @At("TAIL"))
    private void gtnhdet$readMask(NBTTagCompound tag, CallbackInfo ci) {
        // Saves written before this fix carry only VCount. It was a high-water mark, so its low bits name exactly
        // the indices stock had already placed, and the old semantics are reproduced rather than re-run.
        this.gtnhdet$spawnedMask = tag.hasKey("VMask") ? tag.getInteger("VMask")
            : (this.villagersSpawned >= 32 ? -1 : (1 << this.villagersSpawned) - 1);
    }
}
