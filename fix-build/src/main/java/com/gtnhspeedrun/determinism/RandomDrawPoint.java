package com.gtnhspeedrun.determinism;

import java.util.Collection;
import java.util.ListIterator;

import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.InsnList;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.injection.InjectionPoint;
import org.spongepowered.asm.mixin.injection.struct.InjectionPointData;

/**
 * Selects a {@code Random.nextInt(int)} draw by its STOCK ordinal, whether or not Mobs-Info has rewritten it.
 *
 * <p>
 * Mobs-Info 0.6.0 (GTNH 2.9.0-RC-1) registers {@code RandomComparisonTransformer}, which runs on every class
 * that references {@code java/util/Random}. Wherever {@code rand.nextInt(n)} is compared straight away with a
 * constant, it replaces the call with
 * {@code RandomComparison.nextInt(rand, n, constant, comparisonOp)} and leaves the comparison itself in place.
 * For an ordinary {@code Random} that helper returns {@code rand.nextInt(n)}, so the pack generates exactly
 * what it did before. Mobs-Info needs the rewrite only for its own {@code RandomSequencer}, which weights the
 * outcomes when it enumerates mob drops for NEI.
 *
 * <p>
 * The draw is unchanged, but the call site is not: a rewritten draw is no longer a {@code Random.nextInt(I)I}
 * invoke. So a plain {@code @At(value = "INVOKE", target = "Ljava/util/Random;nextInt(I)I", ordinal = n)} counts
 * one fewer site for each rewritten draw before it. It lands on a later draw, or on nothing. Both happened on
 * RC-1: vanilla dungeons and mineshaft corridors crashed at init, and two horse-spawn draws escaped a fork. See
 * results/2026-09-25-2.9.0-RC-1-compatibility.
 *
 * <p>
 * This point counts BOTH call shapes as one sequence, in instruction order. The transformer swaps each call
 * in place, one for one, so position {@code n} in that sequence is stock draw {@code n} on every pack. It then
 * returns only the draws that have the requested shape. An injector therefore comes in one of two forms:
 * <ul>
 * <li>A draw that is not a comparison in stock code can never be rewritten. It takes one
 * {@code shape=virtual} injector with {@code require = 1}. If some future rewrite ever reaches it, the
 * injector finds nothing and fails at apply time.</li>
 * <li>A draw that is a comparison in stock code has either shape, depending on whether Mobs-Info 0.6.0+ is
 * installed. It takes a {@code shape=virtual} {@code @Redirect} AND a {@code shape=comparison}
 * {@code @WrapOperation} that share one {@code @Group} with {@code min} and {@code max} set to the stock count.
 * The pair together must cover every draw exactly once. The wrap calls through to Mobs-Info's
 * {@code original} whenever the fix does not own the draw, so Mobs-Info keeps its behaviour.</li>
 * </ul>
 *
 * <p>
 * Usage: {@code @At(value = "com.gtnhspeedrun.determinism.RandomDrawPoint", args = "shape=virtual", ordinal = 3)}.
 * {@code ordinal} is the STOCK ordinal among {@code nextInt(int)} draws; leave it out to take every draw of the
 * requested shape. Only {@code nextInt(int)} is supported, because it is the only draw any mixin here selects
 * this way. Lives outside the mixin package because Mixin loads it as an ordinary class.
 */
public class RandomDrawPoint extends InjectionPoint {

    private static final String RANDOM = "java/util/Random";
    private static final String COMPARISON = "com/kuba6000/mobsinfo/api/RandomComparison";

    private final boolean wantComparison;
    private final int ordinal;

    public RandomDrawPoint(InjectionPointData data) {
        super(data);
        final String shape = data.get("shape", "");
        switch (shape) {
            case "virtual":
                this.wantComparison = false;
                break;
            case "comparison":
                this.wantComparison = true;
                break;
            default:
                // No default shape: an injector that silently took one shape would be the exact half-fix this
                // class exists to prevent.
                throw new IllegalArgumentException(
                    "RandomDrawPoint needs args = \"shape=virtual\" or \"shape=comparison\", got \"" + shape + "\"");
        }
        this.ordinal = data.getOrdinal();
    }

    @Override
    public boolean find(String desc, InsnList insns, Collection<AbstractInsnNode> nodes) {
        boolean found = false;
        int draw = 0;
        for (ListIterator<AbstractInsnNode> it = insns.iterator(); it.hasNext();) {
            final AbstractInsnNode insn = it.next();
            final boolean virtual = isVirtualDraw(insn);
            final boolean comparison = !virtual && isComparisonDraw(insn);
            if (!virtual && !comparison) continue;
            if ((this.ordinal < 0 || this.ordinal == draw) && comparison == this.wantComparison) {
                nodes.add(insn);
                found = true;
            }
            draw++;
        }
        return found;
    }

    private static boolean isVirtualDraw(AbstractInsnNode insn) {
        if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) return false;
        final MethodInsnNode call = (MethodInsnNode) insn;
        return RANDOM.equals(call.owner) && "nextInt".equals(call.name) && "(I)I".equals(call.desc);
    }

    private static boolean isComparisonDraw(AbstractInsnNode insn) {
        if (insn.getOpcode() != Opcodes.INVOKESTATIC) return false;
        final MethodInsnNode call = (MethodInsnNode) insn;
        return COMPARISON.equals(call.owner) && "nextInt".equals(call.name)
            && "(Ljava/util/Random;III)I".equals(call.desc);
    }
}
