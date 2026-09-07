package com.gtnhspeedrun.determinism.worldgen;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * F4d: pin GregTech's ore-vein IDENTITY decision to a canonical trigger chunk, and answer the reads it still
 * makes from virgin terrain.
 *
 * <p>
 * The vein a region resolves to is chosen by whichever of the surrounding 5x5 chunks Forge populates first, and
 * that chunk's coordinates enter the accept/reject test three ways (the clipping window, the local density, and
 * the probe column). Measured on GT 5.09.54.115, seed -1636594104014467454, radius 60, with F4 already active:
 * the same-order noise floor is 0 of 1762 regions, but rows-vs-spiral differs on 140 of 1760 in the overworld
 * (7.95%) and 225 of 1702 in the Twilight Forest (13.2%).
 *
 * <p>
 * <b>Flag, not a rebuild.</b> Both A/B arms must be the SAME JAR. The previous attempt's arms differed by
 * version string and md5 as well as by the change, so its headline number was confounded and had to be re-run.
 * Branch inside the handlers on {@link #ON} rather than by registering different mixins, so "off" is
 * bit-identical to stock and the only difference between arms is one system property.
 *
 * <p>
 * Default ON. Disable with {@code -Dgtnhdet.orepin=false}, which restores stock bit-for-bit: the mixins always
 * load and every handler branches on this flag, so an A/B is one property on one jar rather than two builds.
 *
 * <p>
 * That claim became true only on 2026-09-07. Between F4d shipping and then, the flag and the dimension
 * whitelist governed the coordinate pin alone: {@code OreManagerVirginDryRunMixin} branched on {@link #ON} but
 * not on the dimension, and {@code WorldgenGTOreLayerStoneTypeMixin} branched on nothing at all. So "off" still
 * rewrote the stone probe from virgin terrain everywhere, and no arm of any A/B was stock. All four handlers
 * now route through {@link #appliesTo(net.minecraft.world.World)}.
 *
 * <p>
 * Shipped on measured evidence rather than on matching stock. Route stability is exact — rows vs spiral is 0 of
 * 1764 overworld and 0 of 1728 Twilight Forest, against a 0-of-1762 same-order floor — and a single-launch audit
 * finds 0 disagreeing oreseeds against 590 with the pin off. It does NOT pass +-10% equivalence against stock's
 * REALISED distribution (ore.mix.oilsand 1.48x, ore.mix.cassiterite 2.39x), because that distribution is itself a
 * route-chosen distortion: stock suppresses cassiterite to 11x and lignite to 18x below their declared table
 * weights. Measured against those declared weights the pinned arm is CLOSER to intent than stock (total-variation
 * distance 0.283 vs 0.303 over 24 seeds / 3521 regions per arm).
 *
 * <p>
 * The terrain filter is RELOCATED, not disabled — it still runs, at the vein's own oreseed instead of at whichever
 * chunk the route reached first. Cassiterite remains 4x below its declared weight, so high veins still lose on low
 * ground and the prospecting incentive survives. Block-level physicality was checked, not assumed: over ~275k ore
 * blocks, floating ore is 6 in both arms and ore above the surface is 0 in both; total ore moves -1.9%. Ore cannot
 * be written into air regardless, because OreManager rejects any coordinate whose stone type is null.
 *
 * <p>
 * Applies ONLY to the dimensions in {@link #DIMS} (default overworld and Twilight Forest) — see that field. The
 * whitelist governs the whole family: the two coordinate pins in {@code GTWorldGenContainerOrePinMixin}, the two
 * virgin reads in {@code OreManagerVirginDryRunMixin}, and F4's stone probe in
 * {@code WorldgenGTOreLayerStoneTypeMixin}.
 * The End is doubly excluded: it is the only dimension calling {@code disableOreVeinHeightChecks()}, which makes
 * {@code resolveVeinPlacement} scan live chunk terrain for {@code veinMinY} and consume a variable RNG draw,
 * contaminating vein GEOMETRY rather than only identity. Pinning there would move that scan up to three chunks
 * from the chunk being populated and could force generation stock never asked for, and the read would stay live,
 * so the pin could not make it total anyway. See results/2026-09-05-gt-ore-canonical-trigger.
 */
public final class GtOrePin {

    private static final Logger LOG = LogManager.getLogger("gtnhdeterminism");

    /** Master switch for the pin and the virgin dry-run reads. They ship and are measured as one change. */
    public static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("gtnhdet.orepin", "true"));

    /**
     * Re-run each cached vein decision and report disagreements ({@code -Dgtnhdet.orepin.audit=true}).
     *
     * <p>
     * Under the pin every chunk of the 5x5 box feeds the decision identical arguments, so any disagreement
     * between them is pure live-world residual. That makes a SINGLE walk sufficient to prove totality and to
     * name the oreseeds where it fails — much cheaper, and far more diagnostic, than inferring it from a
     * two-walk comparison that only says "some number of regions differ".
     */
    public static final boolean AUDIT = Boolean.getBoolean("gtnhdet.orepin.audit");

    /**
     * Assert that the nine-sample column probe is unreachable from the pinned dry run
     * ({@code -Dgtnhdet.orepin.assertprobe=true}). Default OFF: it adds a branch to a per-block worldgen path.
     *
     * <p>
     * Under the pin {@code chunkX == seedX}, and {@code resolveVeinPlacement} guarantees
     * {@code veinWestX <= seedX} and {@code veinEastX >= seedX + 16} because {@code mSize >= 1}. So
     * {@code limitWestX} is always {@code seedX + 2}, {@code limitEastX} always at least {@code seedX + 16}, and
     * {@code executeWorldgenChunkified}'s {@code limitWestX >= limitEastX} test never fires. The pin does not
     * virginise the column probe — it makes it dead code in the dry run, and what filters instead is
     * {@code generateWithPlacement} returning {@code NO_OVERLAP_AIR_BLOCK} when a full dry-run placement at the
     * oreseed lands zero blocks.
     *
     * <p>
     * That is load-bearing for three separate arguments — why the whitelist can safely govern F4 as well, why
     * the F4d balance shift has the shape it does, and why the Nether's near-solid netherrack column changes
     * the stock arm only — and it is proved by arithmetic over GregTech source, not by observation. If a GT bump
     * changes {@code mSize} or moves the {@code +2}/{@code +18} clipping bias, the branch becomes live again
     * with NO signal: a probe returning {@code NO_OVERLAP} still caches a vein, so the vein numbers stay clean
     * while the reasoning under them has quietly stopped holding. That is the same shape as the
     * {@code balance-report.py} trap — a passing number over a metric that stopped testing anything.
     */
    public static final boolean ASSERT_PROBE = Boolean.getBoolean("gtnhdet.orepin.assertprobe");

    /**
     * Dimensions the pin applies to — a WHITELIST, default {@code 0,7,-1} (overworld, Twilight Forest, Nether).
     *
     * <p>
     * Whitelist rather than blacklist because those are the only three dimensions with evidence: each measured at
     * zero differing regions between a rows and a spiral walk, against a zero same-order floor. This pack ships
     * dozens of others — every Galacticraft/GalaxySpace planet and moon, asteroid belts, Underdark,
     * SpectreWorld — with their own chunk providers, and {@link TerrainOracle} regenerating a virgin chunk
     * through an unfamiliar provider is exactly the kind of thing that works everywhere until it does not.
     * Silently changing worldgen in a dimension nobody measured is the failure this project keeps writing up.
     *
     * <p>
     * The Nether was added 2026-09-07 on the same evidence shape as the other two: rows vs spiral goes
     * 413/1806 to 0/1806 with zero differing geometry, against a 0/1813 same-order floor, and the totality
     * audit goes 1,287 disagreeing oreseeds to 0. Independent of {@link OracleRngGuard} — re-measured with
     * {@code -Dgtnhdet.oracleguard=false} and still 0/1809. See
     * results/2026-09-07-nether-orevein-determinism.
     *
     * <p>
     * An earlier revision shipped this as {@code dimensionId != 1}, which excluded The End and pinned everything
     * else by default. That was a blacklist wearing a whitelist's documentation.
     *
     * <p>
     * Override with {@code -Dgtnhdet.orepin.dims=0,7,-1}. Twilight Forest's id is configurable in some packs, so
     * it is a property rather than a constant. An empty value disables the pin everywhere.
     */
    public static final java.util.Set<Integer> DIMS = parseDims(System.getProperty("gtnhdet.orepin.dims", "0,7,-1"));

    private static java.util.Set<Integer> parseDims(String spec) {
        final java.util.Set<Integer> out = new java.util.HashSet<>();
        for (final String part : spec.split(",")) {
            final String t = part.trim();
            if (t.isEmpty()) continue;
            try {
                out.add(Integer.valueOf(t));
            } catch (NumberFormatException bad) {
                LOG.error("gtnhdet.orepin.dims: ignoring unparseable entry '{}'", t);
            }
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    /** True when the vein-identity pin should apply in this dimension. */
    public static boolean appliesTo(int dimensionId) {
        return ON && DIMS.contains(dimensionId);
    }

    /**
     * True when the pin AND the virgin dry-run reads should apply in this world.
     *
     * <p>
     * Every handler in the family routes through here, so the whitelist governs one behaviour rather than
     * three-quarters of one. Null-tolerant because it is called from mixin handlers on GregTech code paths
     * that have no contract about the world being fully constructed.
     */
    public static boolean appliesTo(net.minecraft.world.World world) {
        return world != null && world.provider != null && appliesTo(world.provider.dimensionId);
    }

    private GtOrePin() {}

    public static void logState() {
        // assertprobe is printed even when off so a run's log proves which arm it was, rather than leaving
        // "no ASSERT lines" to mean either "the invariant held" or "the flag never took".
        LOG.info(
            "GT ore-vein identity pin (F4d): gtnhdet.orepin={} dims={} (whitelist) gtnhdet.orepin.audit={} "
                + "gtnhdet.orepin.assertprobe={}",
            ON,
            DIMS,
            AUDIT,
            ASSERT_PROBE);
    }

    /**
     * Report a throwable from inside the dry run, once, before it is rethrown.
     *
     * <p>
     * {@code GTWorldgenerator.generateVein} wraps the dry run in {@code catch (Exception)} and leaves
     * {@code placementResult} at 0, which matches no case in its switch — so a throw silently costs one
     * candidate and selects a DIFFERENT vein, with no log line anywhere. A fault in our own handlers would
     * therefore present exactly as "fixed a few regions, broke a few others", which is indistinguishable from
     * the two partial fixes this project already reverted. Any occurrence of this line voids a measurement run.
     */
    private static volatile boolean reported;

    /**
     * Count of column-probe samples taken from a PINNED DRY RUN under {@link #ASSERT_PROBE}. Must be 0.
     *
     * <p>
     * Counted rather than thrown: a throw here would land inside {@code GTWorldgenerator}'s
     * {@code catch (Exception)} and silently change vein identity, which is precisely the failure this flag
     * exists to detect. A nonzero count is a pointer to re-derive the reachability argument, not a regression in
     * the pin.
     */
    private static final java.util.concurrent.atomic.AtomicLong PROBE_HITS = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Every column-probe sample seen while the pin applies, dry run or not — the POSITIVE CONTROL.
     *
     * <p>
     * {@link #PROBE_HITS} is expected to be 0, and a 0 that means "the injection never fired" looks exactly
     * like a 0 that means "the invariant holds". This counter distinguishes them: it must be nonzero, because
     * the real path still reaches the probe through {@code generateCachedVein}'s unpinned call.
     */
    private static final java.util.concurrent.atomic.AtomicLong PROBE_SEEN = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Called from the column-probe redirect on every sample, before the dry-run test.
     *
     * <p>
     * Logs the FIRST sample, once. Without that line, "no ASSERT failures" is indistinguishable from "the
     * instrumentation never ran", and a vacuous assertion that reads as a pass is worse than no assertion.
     * The real path reaches this probe through {@code generateCachedVein}'s unpinned call, so the line is
     * expected in any run that generates veins.
     */
    public static void noteColumnProbeSample(int x, int y, int z) {
        if (PROBE_SEEN.getAndIncrement() == 0) {
            // Deliberately does NOT contain the failure line's own marker text: a status message that quotes
            // the string people grep for makes every grep self-match and report one phantom failure.
            LOG.info(
                "F4d ASSERT armed: column-probe redirect is live (first sample at {}, {}, {}). Grep "
                    + "PINNED_DRY_RUN for failures; none means the invariant held.",
                x,
                y,
                z);
        }
    }

    /** Called from the column-probe redirect when the pin is active and the call is a dry run. */
    public static void reportColumnProbeReached(int x, int y, int z) {
        final long n = PROBE_HITS.incrementAndGet();
        // First hit is the finding; the decade marks keep a runaway from writing a line per block.
        if (n == 1 || n == 10 || n == 100 || n == 1000 || n == 10000) {
            LOG.error(
                "F4d ASSERT PINNED_DRY_RUN: the nine-sample column probe was reached at ({}, {}, {}) "
                    + "- count={}. limitWestX >= limitEastX was supposed to be unreachable under the pin. "
                    + "The reachability argument behind the whitelist scope and the balance result is stale "
                    + "and must be re-derived before either is quoted again.",
                x,
                y,
                z,
                n);
        }
    }

    /** Total pinned-dry-run column-probe samples this launch. Expected 0. */
    public static long columnProbeHits() {
        return PROBE_HITS.get();
    }

    /**
     * 
     * /** One line per disagreeing oreseed under {@link #AUDIT}: the decision was not a total function.
     */
    public static void reportAuditMismatch(long oreveinSeed, Object previous, Object recomputed) {
        LOG.error(
            "F4d AUDIT: oreveinSeed={} resolved differently on a later chunk of the same region — "
                + "the decision is NOT total. first={} later={}",
            oreveinSeed,
            previous,
            recomputed);
    }

    public static void reportOnce(String where, int x, int y, int z, Throwable t) {
        if (reported) return;
        reported = true;
        LOG.error(
            "F4d dry run threw in {} at ({}, {}, {}) — this SILENTLY changes vein identity via "
                + "GTWorldgenerator's catch(Exception); any measurement from this run is void",
            where,
            x,
            y,
            z,
            t);
    }
}
