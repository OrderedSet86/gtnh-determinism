package com.gtnhspeedrun.determinism.worldgen;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Random;

import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Flag and mechanics for the Nether populate re-seed; see {@code ChunkProviderHellPopulateMixin} for the defect.
 *
 * <p>
 * Separate from {@link GtOrePin} on purpose. The ore pin is about which vein a region resolves to; this is about
 * whether glowstone and quartz land in the same place on a second visit. They are independent defects with
 * independent evidence, and folding them under one property would make it impossible to A/B either alone.
 *
 * <p>
 * <b>Why the fields are found by reflection rather than {@code @Shadow}.</b> {@code hellRNG} and
 * {@code worldObj} are private fields of a VANILLA class, so a shadow of them has to survive MCP-to-SRG
 * remapping. The Mixin annotation processor emits no refmap entries for shadowed fields in this build — the
 * generated refmap contains exactly two field mappings, both from {@code @Redirect} targets — and this project
 * has no other mixin shadowing a vanilla field, so there is no evidence the binding would work in production.
 * A shadow that fails to bind is a crash at best and a silently skipped fix at worst.
 *
 * <p>
 * Matching on declared TYPE avoids the question entirely, and it is sound here because {@code ChunkProviderHell}
 * declares exactly one {@code Random} and exactly one {@code World}. If a coremod ever adds a second of either —
 * ArchaicFix already mixes into this class — the lookup throws rather than guessing, on the same reasoning as
 * {@link OracleRngGuard}: a guess that happens to be wrong would move Nether decoration with no log line.
 */
public final class NetherPopulateRng {

    private static final Logger LOG = LogManager.getLogger("gtnhdeterminism");

    /**
     * Default OFF — see {@code ChunkProviderHellPopulateMixin}: the hook this flag drives never fires, so the
     * flag is inert either way. Defaulting it OFF so nothing reads "netherpop=true" in a log and concludes the
     * Nether's decoration is handled. Flip to {@code -Dgtnhdet.netherpop=true} when debugging the hook.
     */
    public static final boolean ON = Boolean.getBoolean("gtnhdet.netherpop");

    private static Field rngField;
    private static Field worldField;
    private static boolean resolved;
    private static volatile boolean everCalled;

    private NetherPopulateRng() {}

    /** The one field of the given type on this class, made accessible. Throws unless there is exactly one. */
    private static Field soleFieldOfType(Class<?> owner, Class<?> type) {
        Field hit = null;
        for (final Field f : owner.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || !type.isAssignableFrom(f.getType())) continue;
            if (hit != null) {
                throw new IllegalStateException(
                    "gtnhdet.netherpop: " + owner.getName()
                        + " declares more than one "
                        + type.getSimpleName()
                        + " field ("
                        + hit.getName()
                        + ", "
                        + f.getName()
                        + ") - cannot tell which one populate draws from; investigate before trusting any "
                        + "Nether measurement");
            }
            hit = f;
        }
        if (hit == null) {
            throw new IllegalStateException(
                "gtnhdet.netherpop: " + owner.getName() + " declares no " + type.getSimpleName() + " field");
        }
        hit.setAccessible(true);
        return hit;
    }

    /**
     * Re-seed the Nether's populate RNG from {@code (worldSeed, chunkX, chunkZ)}, exactly as
     * {@code ChunkProviderGenerate.populate} does for the overworld.
     */
    public static void reseed(Object chunkProviderHell, int chunkX, int chunkZ) {
        // Unconditional first-call probe, BEFORE the ON check and before any reflection that can throw.
        // Without it, "no re-seed line in the log" is ambiguous between "the flag was off", "the inject never
        // fired" and "the field lookup threw" — and the first diagnosis of this mixin burned a measurement
        // round on exactly that ambiguity.
        if (!everCalled) {
            everCalled = true;
            LOG.info(
                "gtnhdet.netherpop: populate hook IS firing (first call: {} chunk {},{}), ON={}",
                chunkProviderHell.getClass()
                    .getName(),
                chunkX,
                chunkZ,
                ON);
        }
        if (!ON) return;
        try {
            if (!resolved) {
                final Class<?> cls = chunkProviderHell.getClass();
                rngField = soleFieldOfType(cls, Random.class);
                worldField = soleFieldOfType(cls, World.class);
                resolved = true;
                LOG.info(
                    "gtnhdet.netherpop: re-seeding {}.{} per chunk from {}.{}",
                    cls.getSimpleName(),
                    rngField.getName(),
                    cls.getSimpleName(),
                    worldField.getName());
            }
            final Random rng = (Random) rngField.get(chunkProviderHell);
            final World world = (World) worldField.get(chunkProviderHell);
            if (rng == null || world == null) return; // pre-construction call; nothing to re-seed yet

            final long worldSeed = world.getSeed();
            rng.setSeed(worldSeed);
            final long i1 = rng.nextLong() / 2L * 2L + 1L;
            final long j1 = rng.nextLong() / 2L * 2L + 1L;
            rng.setSeed((long) chunkX * i1 + (long) chunkZ * j1 ^ worldSeed);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("gtnhdet.netherpop: cannot reach the Nether populate RNG", e);
        }
    }

    public static void logState() {
        LOG.info("Nether populate RNG re-seed: gtnhdet.netherpop={}", ON);
    }
}
