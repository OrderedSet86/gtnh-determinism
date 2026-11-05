package com.gtnhspeedrun.determinism.mixins.worldgen;

import java.util.HashMap;
import java.util.Random;

import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.feature.WorldGenDungeons;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.determinism.GtnhDeterminism;
import com.gtnhspeedrun.determinism.worldgen.TcForkUtil;

/**
 * RWG2 2.0.6 independently retains eight dungeon attempts after its terrain-gated lakes. Its source and production
 * bytecode use x/z = chunk * 16 + nextInt(16) + 8 and y = nextInt(128), so the legacy salt/draw shape applies,
 * but the mixin targets RWG2's own provider and its verified SRG call site. Vanilla room scans, chest placement
 * and loot continue through the existing provider-independent fixes.
 */
@Mixin(targets = "rwg2.world.ChunkGeneratorRealistic", remap = false)
public abstract class Rwg2DungeonAttemptMixin {

    private static final boolean gtnhdet$TRACE = Boolean.getBoolean("gtnhdet.dungeontrace");
    private static final boolean gtnhdet$FORK = !"false".equals(System.getProperty("gtnhdet.dungeonfork"));
    private static final long gtnhdet$SALT = 40L;
    private static final ThreadLocal<HashMap<Long, Integer>> gtnhdet$attemptIndex = ThreadLocal
        .withInitial(HashMap::new);

    private static long gtnhdet$key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    // Population nests when block writes load neighbouring chunks. Each active chunk needs its own index.
    @Inject(method = { "populate", "func_73153_a" }, at = @At("HEAD"), require = 1)
    private void gtnhdet$beginAttempts(IChunkProvider provider, int chunkX, int chunkZ, CallbackInfo ci) {
        gtnhdet$attemptIndex.get()
            .put(gtnhdet$key(chunkX, chunkZ), 0);
    }

    @Inject(method = { "populate", "func_73153_a" }, at = @At("RETURN"), require = 1)
    private void gtnhdet$endAttempts(IChunkProvider provider, int chunkX, int chunkZ, CallbackInfo ci) {
        gtnhdet$attemptIndex.get()
            .remove(gtnhdet$key(chunkX, chunkZ));
    }

    @Redirect(
        method = { "populate", "func_73153_a" },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/gen/feature/WorldGenDungeons;func_76484_a(Lnet/minecraft/world/World;Ljava/util/Random;III)Z"),
        require = 1,
        allow = 1)
    private boolean gtnhdet$pinDungeonAttempt(WorldGenDungeons dungeon, World world, Random rand, int x, int y, int z) {
        int ax = x, ay = y, az = z;
        if (gtnhdet$FORK) {
            final int cx = (x - 8) >> 4;
            final int cz = (z - 8) >> 4;
            final HashMap<Long, Integer> counters = gtnhdet$attemptIndex.get();
            final long key = gtnhdet$key(cx, cz);
            final Integer index = counters.get(key);
            if (index == null) throw new IllegalStateException("RWG2 dungeon attempt outside its population chunk");
            counters.put(key, index + 1);
            final Random fork = TcForkUtil.fork(world, cx, cz, gtnhdet$SALT);
            for (int i = 0; i < index; i++) {
                fork.nextInt(16);
                fork.nextInt(128);
                fork.nextInt(16);
            }
            ax = cx * 16 + fork.nextInt(16) + 8;
            ay = fork.nextInt(128);
            az = cz * 16 + fork.nextInt(16) + 8;
        }
        final boolean built = dungeon.generate(world, rand, ax, ay, az);
        if (gtnhdet$TRACE) {
            GtnhDeterminism.LOG
                .info("[dungeonattempt] seed={} x={} y={} z={} built={}", world.getSeed(), ax, ay, az, built);
        }
        return built;
    }
}
