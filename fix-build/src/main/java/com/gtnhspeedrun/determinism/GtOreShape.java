package com.gtnhspeedrun.determinism;

/**
 * Which shape of GregTech's ore worldgen is installed.
 *
 * <p>
 * GregTech rewrote {@code WorldgenGTOreLayer} twice, so the F4 family has three targets, not two. Every decision
 * that depends on which one is present is made here, once, from the installed bytecode — see {@link ClassShape}
 * for why this reads the constant pool instead of loading the class.
 *
 * <table border="1">
 * <caption>The three shapes</caption>
 * <tr>
 * <th>Shape</th>
 * <th>GT band</th>
 * <th>Reroll probe asks</th>
 * <th>{@code executeWorldgenChunkified}</th>
 * </tr>
 * <tr>
 * <td>{@link Shape#REPLACEABLE_ORE_GEN}</td>
 * <td>5.09.50.119 .. 5.09.52.30 (packs 2.7.4, 2.8.4)</td>
 * <td>{@code Block.isReplaceableOreGen}, 10 call sites</td>
 * <td>one declaration; selection writes</td>
 * </tr>
 * <tr>
 * <td>{@link Shape#FIND_STONE_TYPE}</td>
 * <td>5.09.52.31 .. 5.09.54.01 (pack 2.9.0-beta-1)</td>
 * <td>{@code StoneType.findStoneType}, 2 call sites</td>
 * <td>one declaration; accept test FUSED with the write</td>
 * </tr>
 * <tr>
 * <td>{@link Shape#VEIN_PLACEMENT}</td>
 * <td>5.09.54.02 and later (pack 2.9.0-beta-3)</td>
 * <td>{@code StoneType.findStoneType}, 2 call sites</td>
 * <td>split into resolve / test / place; selection is a pure test</td>
 * </tr>
 * </table>
 *
 * <p>
 * <b>Never replace these probes with a version-string comparison.</b> The ore rewrite was developed on a
 * {@code -pre} branch and merged late, so the shapes INTERLEAVE by tag number across 5.09.52.03-pre through
 * 5.09.52.30: {@code 5.09.52.25-pre} is {@link Shape#FIND_STONE_TYPE} while the later-numbered {@code 5.09.52.26}
 * and {@code 5.09.52.30} are {@link Shape#REPLACEABLE_ORE_GEN}. Any {@code >=} or {@code <} test over that range
 * is wrong in both directions. Probing the bytecode is not a convenience here, it is the only correct method.
 *
 * <p>
 * Each constant probes the class the corresponding mixin actually binds to, rather than a proxy class whose
 * presence merely correlates. The previous implementation probed {@code hasClass("gregtech.api.enums.StoneType")}
 * on the belief that the class did not exist before 5.09.54.x; it exists from 5.09.52.31, so packs in the middle
 * band were handed a mixin that could not bind and crashed at init.
 */
public final class GtOreShape {

    private GtOreShape() {}

    private static final String ORE_LAYER = "gregtech.common.WorldgenGTOreLayer";
    private static final String STONE_TYPE = "gregtech.api.enums.StoneType";
    private static final String WORLD_GEN_CONTAINER = "gregtech.common.GTWorldgenerator$WorldGenContainer";
    private static final String ORE_MANAGER = "gregtech.common.ores.OreManager";

    public enum Shape {
        /** GT 5.09.50.119 .. 5.09.52.30. */
        REPLACEABLE_ORE_GEN,
        /** GT 5.09.52.31 .. 5.09.54.01. */
        FIND_STONE_TYPE,
        /** GT 5.09.54.02 and later. */
        VEIN_PLACEMENT,
        /** Neither probe matched: an unrecognised GregTech. No F4 mixin is offered. */
        UNKNOWN
    }

    /** The installed shape, probed once from bytecode. */
    public static final Shape SHAPE = detect();

    /**
     * True when {@code StoneType.findStoneType(World, int, int, int)} does NOT apply the
     * {@code canGenerateInWorld} dimension gate, i.e. GT 5.09.52.31 through 5.09.53.10.
     *
     * <p>
     * Selects between {@code VirginStoneType.atUngated} and {@code VirginStoneType.at}; read that class's javadoc
     * for why answering with the wrong one silently changes which vein generates. {@code VISUAL_STONE_TYPES} is a
     * field deleted by the same commit that added the gate at 5.09.53.11 — a co-change, not a cause. It is used
     * because a constant-pool scan cannot see which method a call site lives in, and verified against every
     * tagged GregTech carrying {@code StoneType}: no version contradicts it.
     */
    public static final boolean FIND_STONE_TYPE_UNGATED = ClassShape.hasMember(STONE_TYPE, "VISUAL_STONE_TYPES");

    private static Shape detect() {
        // Most specific first: VEIN_PLACEMENT also names findStoneType, so probing for it second would
        // misclassify every 5.09.54.02+ pack as the middle shape.
        if (ClassShape.hasMember(ORE_LAYER, "resolveVeinPlacement")) return Shape.VEIN_PLACEMENT;
        if (ClassShape.hasMember(ORE_LAYER, "findStoneType")) return Shape.FIND_STONE_TYPE;
        if (ClassShape.hasMember(ORE_LAYER, "isReplaceableOreGen")) return Shape.REPLACEABLE_ORE_GEN;
        return Shape.UNKNOWN;
    }

    /**
     * True when GregTech provides its own dry run for vein selection, so F4d can pin it with {@code @ModifyArg}.
     *
     * <p>
     * Probes the class the mixin binds to, not {@code OreManager}'s mere existence: {@code OreManager} has been
     * present since 5.09.52.31, three hundred-odd versions before it grew the methods F4d targets, and gating on
     * its presence is what offered the F4d mixins to packs that could not bind them.
     */
    public static boolean hasNativeDryRun() {
        return ClassShape.hasMember(WORLD_GEN_CONTAINER, "resolveVeinPlacement")
            && ClassShape.hasMember(ORE_MANAGER, "canSetOreForWorldGenOrAlreadySet");
    }

    /** Human-readable identification of the installed GregTech, for log lines and bug reports. */
    public static String installedGregTech() {
        final String source = ClassShape.sourceOf(ORE_LAYER);
        return source == null ? "unknown (could not locate " + ORE_LAYER + ")" : source;
    }
}
