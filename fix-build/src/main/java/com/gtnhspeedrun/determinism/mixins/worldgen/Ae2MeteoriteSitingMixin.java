package com.gtnhspeedrun.determinism.mixins.worldgen;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhspeedrun.determinism.worldgen.TerrainOracle;

import appeng.worldgen.meteorite.IMeteoriteWorld;

/**
 * Makes AE2 meteorite EXISTENCE a function of the seed instead of of the walk order.
 * {@code -Dgtnhdet.meteorsiting=false} restores stock siting.
 *
 * <h2>The defect</h2>
 *
 * Measured 25 seeds, warm radius 30, rows against spiral, with a same-order floor of <b>0</b>:
 * <b>2 meteorites of 38 exist under one walk order and not the other</b>, and <b>0 drift</b> — where a
 * meteorite exists in both, it is at the identical block. That split is the whole diagnosis. Every
 * input to the decision is seed-pure except one:
 *
 * <ul>
 * <li>WHERE to try: {@code Platform.seedFromGrid(rand, worldSeed, gridX, gridZ)}, one candidate per
 * {@code minMeteoriteDistance} cell. Seed-pure.</li>
 * <li>WHETHER the {@code meteoriteSpawnChance} roll passes: same seeded stream. Seed-pure.</li>
 * <li>The starting {@code y}: {@code MeteoriteSpawn.depth}, set in {@code MeteoriteWorldGen.generate}
 * from {@code Random.nextInt} on that same stream, or the literal 128. No heightmap. Seed-pure —
 * checked, not assumed.</li>
 * <li>WHETHER the ground is acceptable: {@code getBlock} against {@code validSpawn} /
 * {@code invalidSpawn}, on the LIVE world, at tick time. <b>Route-dependent.</b></li>
 * </ul>
 *
 * <p>
 * So the same coordinate can hold a landable block under one walk and something else under another,
 * and the meteorite exists in one world and not the other. Position never varies because position is
 * settled before this read; only the yes/no flips, which is exactly the measured shape.
 *
 * <h2>Why the timing is not the problem</h2>
 *
 * AE2 places meteorites from a tick callable rather than during worldgen —
 * {@code MeteoriteWorldGen.generate} only calls {@code TickHandler.addCallable}. That deferral is
 * deliberate: a crater spans many chunks, and carving it during populate would write into neighbours
 * that may not exist yet. Un-deferring would trade a determinism bug for the cascading-population
 * crash class. The deferral is fine; the live read is not.
 *
 * <h2>Scope</h2>
 *
 * All three {@code getBlock} calls in {@code spawnMeteoriteCenter} are redirected, because all three
 * are siting tests taken BEFORE anything is placed — two membership tests against {@code validSpawn}
 * and one against {@code invalidSpawn}, each with its own reject path. Nothing is written until after
 * the last of them. The crater carving that follows keeps reading and writing the live world, so the
 * meteorite is still built into the terrain that is actually there; only the decision to build
 * becomes seed-pure. Same shape as the hilltop {@code LocationIsValidSpawn} fix, the vanilla dungeon
 * scan and the GT ore pin.
 *
 * <p>
 * {@code require = 3} rather than 1 on purpose: this fix is only correct if it covers every siting
 * read. If an AE2 update drops one, the jar should fail loudly rather than silently pin two of three
 * and leave a residual that looks like a partial fix.
 *
 * <p>
 * Note {@code tryMeteorite} calls this up to 20 times per meteorite, stepping {@code y} down by 15
 * and giving up below 40 — about six samples from the default depth of 128. They all pass through
 * these same call sites, so one redirect covers the whole vertical scan.
 *
 * <h2>What this does NOT change</h2>
 *
 * It does not make meteorites avoid structures, because stock does not either. The accept path is
 * {@code hasNoSky()}, the membership tests, and {@code chunkExists} — no radius scan, no flatness
 * test, nothing structure-aware. A meteorite centred on grass one block outside a village carves
 * straight through it on stock AE2 today. The narrow goal these reads serve — do not spawn in mid-air
 * or in water — is a terrain-phase property that virgin terrain answers just as well.
 *
 * <p>
 * One real behaviour change to be aware of: the fallout style ({@code FalloutSand},
 * {@code FalloutSnow}, {@code FalloutCopy}) is chosen from the same centre block, so a site whose
 * live surface was decorated differently from virgin terrain can get different debris. Cosmetic, and
 * deterministic either way.
 */
@Mixin(targets = "appeng.worldgen.MeteoritePlacer", remap = false)
public abstract class Ae2MeteoriteSitingMixin {

    private static final boolean gtnhdet$ENABLED = !"false".equals(System.getProperty("gtnhdet.meteorsiting"));

    @Redirect(
        method = "spawnMeteoriteCenter",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/worldgen/meteorite/IMeteoriteWorld;getBlock(III)Lnet/minecraft/block/Block;"),
        require = 3)
    private Block gtnhdet$virginSiting(IMeteoriteWorld self, int x, int y, int z) {
        if (!gtnhdet$ENABLED) return self.getBlock(x, y, z);
        final World world = self.getWorld();
        // No world means no oracle; leaving stock's answer is still deterministic for that call.
        return world == null ? self.getBlock(x, y, z) : TerrainOracle.block(world, x, y, z);
    }
}
