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
 * Applies ONLY to the dimensions in {@link #DIMS} (default overworld and Twilight Forest) — see that field.
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
     * Dimensions the pin applies to — a WHITELIST, default {@code 0,7} (overworld, Twilight Forest).
     *
     * <p>
     * Whitelist rather than blacklist because those are the only two dimensions with evidence: both measured at
     * zero differing regions between a rows and a spiral walk, against a zero same-order floor. This pack ships
     * dozens of others — the Nether, every Galacticraft/GalaxySpace planet and moon, asteroid belts, Underdark,
     * SpectreWorld — with their own chunk providers, and {@link TerrainOracle} regenerating a virgin chunk
     * through an unfamiliar provider is exactly the kind of thing that works everywhere until it does not.
     * Silently changing worldgen in a dimension nobody measured is the failure this project keeps writing up.
     *
     * <p>
     * An earlier revision shipped this as {@code dimensionId != 1}, which excluded The End and pinned everything
     * else by default. That was a blacklist wearing a whitelist's documentation.
     *
     * <p>
     * Override with {@code -Dgtnhdet.orepin.dims=0,7,-1}. Twilight Forest's id is configurable in some packs, so
     * it is a property rather than a constant. An empty value disables the pin everywhere.
     */
    public static final java.util.Set<Integer> DIMS = parseDims(System.getProperty("gtnhdet.orepin.dims", "0,7"));

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

    private GtOrePin() {}

    public static void logState() {
        LOG.info(
            "GT ore-vein identity pin (F4d): gtnhdet.orepin={} dims={} (whitelist) gtnhdet.orepin.audit={}",
            ON,
            DIMS,
            AUDIT);
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

    /** One line per disagreeing oreseed under {@link #AUDIT}: the decision was not a total function. */
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
