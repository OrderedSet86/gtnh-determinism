package com.gtnhspeedrun.determinism.worldgen;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.chunk.IChunkProvider;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Runs {@link TerrainOracle}'s {@code provideChunk} without disturbing the live generator's RNG fields.
 *
 * <p>
 * <b>The defect.</b> {@code TerrainOracle} asks the LIVE chunk generator for a virgin chunk. Generators keep
 * their {@code Random} in a field and re-seed it at the top of {@code provideChunk}, so an oracle call resets
 * that field and then burns hundreds of draws on terrain noise. Whether that matters depends entirely on
 * whether the generator also re-seeds at POPULATE time:
 *
 * <table>
 * <tr>
 * <td>{@code ChunkProviderGenerate}</td>
 * <td>re-seeds {@code rand} in {@code populate}</td>
 * <td>immune</td>
 * </tr>
 * <tr>
 * <td>{@code ChunkGeneratorRealistic} (RWG)</td>
 * <td>re-seeds {@code rand} in {@code populate}</td>
 * <td>immune</td>
 * </tr>
 * <tr>
 * <td>{@code ChunkProviderHell}</td>
 * <td><b>never re-seeds {@code hellRNG}</b></td>
 * <td><b>exposed</b></td>
 * </tr>
 * </table>
 *
 * <p>
 * In the Nether, {@code populate} draws the fortress, lava lakes, fire, both glowstone passes, mushrooms and
 * nether quartz from one continuous {@code hellRNG} and never calls {@code setSeed}. So an oracle call
 * permanently reroutes every later chunk's decoration, and the NUMBER of oracle calls depends on the LRU in
 * {@link TerrainOracle}, which depends on the route. RWG's {@code mapRand} is the same shape in the overworld —
 * consumed inside {@code provideChunk}, never re-seeded — it is simply exercised far more rarely.
 *
 * <p>
 * <b>The guard.</b> Swap every {@code Random}-typed field on the generator for a scratch instance of the same
 * concrete class, call {@code provideChunk}, restore. Every generator examined re-seeds its own RNG as the first
 * statement of {@code provideChunk}, so the virgin blocks are bit-identical and only the live stream is spared.
 *
 * <p>
 * Matching on {@link Field#getType()} rather than on a name is what makes this provider-agnostic: no MCP/SRG
 * name is spelled anywhere, so it covers vanilla, RWG, Twilight Forest and any modded provider without a list to
 * keep up to date.
 *
 * <p>
 * <b>Fails loud.</b> A field that cannot be swapped throws rather than being skipped. A guard that silently
 * missed one field would leave exactly the defect it exists to close, while the log said it was on — the
 * "clean-looking number for the arm you thought you were not running" failure this project keeps writing up.
 *
 * <p>
 * Only Minecraft/mod classes in the unnamed module are touched. {@code java.util.Random}'s internal
 * {@code AtomicLong seed} is deliberately NOT snapshotted: reaching it needs
 * {@code --add-opens java.base/java.util=ALL-UNNAMED} on every launcher, which is a far larger operational
 * liability than swapping a field the mod itself declares.
 *
 * <p>
 * Disable with {@code -Dgtnhdet.oracleguard=false} for an A/B; the call site branches on the flag, so both arms
 * are the same jar.
 */
public final class OracleRngGuard {

    private static final Logger LOG = LogManager.getLogger("gtnhdeterminism");

    /** Default ON: this closes an already-shipped defect, independent of which dimensions the ore pin covers. */
    public static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("gtnhdet.oracleguard", "true"));

    /** Per generator CLASS, since reflection lookup is far more expensive than the swap itself. */
    private static final Map<Class<?>, Field[]> FIELDS = new ConcurrentHashMap<>();

    private static volatile boolean logged;

    private OracleRngGuard() {}

    /**
     * @return every non-static {@code Random}-typed field on this class and its superclasses, made accessible.
     */
    private static Field[] rngFields(Class<?> type) {
        return FIELDS.computeIfAbsent(type, cls -> {
            final List<Field> found = new ArrayList<>();
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (final Field f : c.getDeclaredFields()) {
                    // Declared type, not the runtime value's type: a null field must still be swapped back
                    // correctly, and a subclass instance is caught by the declared supertype anyway.
                    if (!Random.class.isAssignableFrom(f.getType())) continue;
                    if (Modifier.isStatic(f.getModifiers())) {
                        // A static generator RNG would be shared across dimensions and is not ours to swap;
                        // it is also not something any examined provider has. Loud, because it would mean the
                        // analysis behind this guard does not describe the provider actually installed.
                        throw new IllegalStateException(
                            "gtnhdet.oracleguard: static Random field " + c.getName()
                                + "."
                                + f.getName()
                                + " on chunk generator "
                                + cls.getName()
                                + " - the guard cannot isolate a static RNG; investigate before trusting any "
                                + "worldgen measurement from this provider");
                    }
                    f.setAccessible(true); // non-static finals are settable this way; static finals are not
                    found.add(f);
                }
            }
            return found.toArray(new Field[0]);
        });
    }

    /**
     * Call {@code generator.provideChunk(cx, cz)} with the generator's {@code Random} fields temporarily
     * replaced, so the live decoration stream is untouched.
     */
    public static net.minecraft.world.chunk.Chunk provideChunkIsolated(IChunkProvider generator, int cx, int cz) {
        if (!ON) return generator.provideChunk(cx, cz);

        final Field[] fields = rngFields(generator.getClass());
        if (fields.length == 0) {
            // Not an error: a generator may hold no Random at all. Worth one line, because it also means this
            // guard is doing nothing for that provider and any claim about it rests on something else.
            if (!logged) {
                logged = true;
                LOG.info(
                    "gtnhdet.oracleguard: {} declares no Random fields; oracle calls cannot perturb it",
                    generator.getClass()
                        .getName());
            }
            return generator.provideChunk(cx, cz);
        }

        final Object[] saved = new Object[fields.length];
        int swapped = 0;
        try {
            for (; swapped < fields.length; swapped++) {
                final Field f = fields[swapped];
                final Object live = f.get(generator);
                saved[swapped] = live;
                f.set(generator, scratchLike(live, f));
            }
            return generator.provideChunk(cx, cz);
        } catch (IllegalAccessException | RuntimeException e) {
            throw new IllegalStateException(
                "gtnhdet.oracleguard: failed to isolate the RNG of " + generator.getClass()
                    .getName() + " - refusing to run the oracle against the live stream",
                e);
        } finally {
            // Restore exactly what was swapped, in reverse, even if provideChunk threw. Leaving a scratch RNG
            // installed would be worse than never having guarded at all.
            for (int i = swapped - 1; i >= 0; i--) {
                try {
                    fields[i].set(generator, saved[i]);
                } catch (IllegalAccessException restoreFailed) {
                    throw new IllegalStateException(
                        "gtnhdet.oracleguard: could not restore " + fields[i],
                        restoreFailed);
                }
            }
        }
    }

    /**
     * A throwaway RNG of the same concrete class as the one being displaced.
     *
     * <p>
     * Same class matters: {@code provideChunk} may call a subclass method (GT's {@code XSTR} overrides
     * {@code nextInt}), and handing it a plain {@code Random} would change the terrain the oracle reports rather
     * than merely isolating it. The seed is irrelevant — every generator re-seeds at the head of
     * {@code provideChunk} — so a fixed constant keeps the guard itself deterministic.
     */
    private static Random scratchLike(Object live, Field f) {
        final Class<?> cls = live != null ? live.getClass() : f.getType();
        try {
            try {
                return (Random) cls.getConstructor(long.class)
                    .newInstance(0L);
            } catch (NoSuchMethodException noLongCtor) {
                return (Random) cls.getConstructor()
                    .newInstance();
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "gtnhdet.oracleguard: cannot construct a scratch " + cls.getName() + " for field " + f,
                e);
        }
    }

    public static void logState() {
        LOG.info("TerrainOracle RNG isolation: gtnhdet.oracleguard={}", ON);
    }
}
