package com.gtnhspeedrun.tcworldgenfix.mixins;

import java.util.Random;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.tcworldgenfix.TcForkUtil;

import eu.usrv.legacylootgames.StructureGenerator;
import ru.timeconqueror.timecore.api.util.RandHelper;

/**
 * Determinism fix for LootGames puzzle micro-dungeons (GTNH speedrun determinism audit, 0.4).
 *
 * {@code StructureGenerator.doGenDungeon} rolls every wall/floor/ceiling "cracked" variant and every broken
 * lamp through {@code RandHelper.chance(10, ...)}, which draws from {@code RandHelper.RAND} — a static
 * clock-seeded {@code new Random()}. The room's cosmetic block metas therefore differ every JVM launch
 * (ground truth: seed -777 telc worlds, ~500 persisted-block diffs across the 3 in-range rooms,
 * DungeonWall meta 0↔3 / 1↔4 / 2↔5, DungeonLight 0↔1). Placement itself (canSpawnInChunk_v3) was already
 * seed-pure.
 *
 * Fix: fork a rand from (world seed, room center chunk, salt 101) at doGenDungeon HEAD and redirect the
 * four chance() calls to draw from it. Loop order is fixed, so the variant pattern becomes a pure function of
 * the seed. Draw count and probability (10%) are unchanged — same crack density, pinned pattern.
 */
@Mixin(value = StructureGenerator.class, remap = false)
public abstract class LootGamesStructureGeneratorMixin {

    @Unique
    private static final long TCFIX$SALT = 101L;

    @Unique
    private final ThreadLocal<Random> tcfix$rand = new ThreadLocal<>();

    @Inject(method = "doGenDungeon", at = @At("HEAD"), remap = false)
    private void tcfix$seedRand(World world, int x, int z, CallbackInfoReturnable<Boolean> cir) {
        this.tcfix$rand.set(TcForkUtil.fork(world, x >> 4, z >> 4, TCFIX$SALT));
    }

    @Redirect(
        method = "doGenDungeon",
        at = @At(
            value = "INVOKE",
            target = "Lru/timeconqueror/timecore/api/util/RandHelper;chance(ILjava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
        remap = false)
    private Object tcfix$seededChance(int chance, Object a, Object b) {
        final Random r = this.tcfix$rand.get();
        return r != null ? RandHelper.chance(r, chance, a, b) : RandHelper.chance(chance, a, b);
    }
}
