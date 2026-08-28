package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.Random;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.determinism.worldgen.TcForkUtil;

/**
 * Et Futurum's deepslate LAYER draws its transition band from {@code world.rand} — the shared, clock-seeded live
 * world RNG that mob spawning, block ticks, weather and every other mod also advance. {@code doDeepslateGen} walks
 * every column of the chunk and, for the four levels below {@code deepslateMaxY}, keeps stone instead of deepslate
 * when {@code y > deepslateMaxY - world.rand.nextInt(4)}:
 *
 * <pre>
 * if (deepslateMaxY &gt;= 255 || whitelisted || y &lt; deepslateMaxY - 4
 *     || y &lt;= deepslateMaxY - world.rand.nextInt(4)) replaceBlockInChunk(...);
 * </pre>
 *
 * So the top surface of the deepslate layer is not a function of the world seed at all. Measured on seed
 * -1501259159663517643: two IDENTICAL cold runs (same jar, same walk order) disagreed on 9,937 blocks across 62
 * chunks, 91% of them {@code etfuturum:deepslate <-> minecraft:stone} at y~20, which is this band. That noise is
 * large enough to swamp block-level measurement of anything else — it is why dirt/gravel and GT stone could not be
 * evaluated at all until now.
 *
 * <p>
 * Fix: seed a fork from (world seed, chunk x, chunk z) at the head of each {@code doDeepslateGen} call and answer
 * the band's draws from it, so the layer boundary is a pure function of the seed and the chunk. Re-seeding per call
 * is deliberate — {@code EtFuturumLateWorldGenerator} can replay a chunk from its static {@code deepslateRedoCache},
 * and re-seeding makes a replay produce the identical boundary rather than continuing a stream.
 *
 * <p>
 * Same bug class as F2 (Witchery clock RNG) and the TiC slime island fix: worldgen reading {@code world.rand}.
 * Targeted by name so the fix jar needs no Et Futurum compile dependency; the handler signatures are vanilla types.
 *
 * <p>
 * NOT fixed here: {@code deepslateRedoCache} is a static cross-dimension map whose {@code entrySet} is iterated and
 * whose deferred chunks are force-loaded via {@code getChunkFromChunkCoords}. That is a separate order-dependence
 * with the same shape as GregTech's {@code mList} queue, and it is not what the launch-noise measurement above
 * caught.
 */
@Mixin(targets = "ganymedes01.etfuturum.world.EtFuturumLateWorldGenerator", remap = false)
public class EtFuturumDeepslateMixin {

    @Unique
    private Random gtnhdet$bandRand;

    @Inject(method = "doDeepslateGen", at = @At("HEAD"), require = 1)
    private void gtnhdet$seedBand(Chunk chunk, CallbackInfo ci) {
        gtnhdet$bandRand = TcForkUtil.fork(chunk.worldObj, chunk.xPosition, chunk.zPosition, 0xDEE95147L);
    }

    @Redirect(
        method = "doDeepslateGen",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/world/World;field_73012_v:Ljava/util/Random;",
            opcode = Opcodes.GETFIELD),
        require = 1)
    private Random gtnhdet$bandRandFor(World world) {
        return gtnhdet$bandRand;
    }
}
