package com.gtnhspeedrun.tcworldgenfix.mixins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import greymerk.roguelike.util.mst.Edge;
import greymerk.roguelike.util.mst.MinimumSpanningTree;
import greymerk.roguelike.util.mst.Point;
import greymerk.roguelike.worldgen.Coord;

/**
 * Determinism fix for Roguelike Dungeons MST levels (GTNH speedrun determinism audit, 0.4).
 *
 * {@code MinimumSpanningTree.mstEdges} is a {@code HashSet<Edge>} and {@code Edge} does not override
 * {@code hashCode}, so the set iterates in identity-hash order — different every JVM launch. Both
 * {@code getEdges()} (feeding {@code LevelGeneratorMST}'s tunnel list) and {@code generate()} iterate it,
 * so on the MST level (level index 2 in the default 5-level stack, ~y30) the tunnel construction order,
 * each node's entrance array order, and the alignment of every per-block theme roll (stone brick
 * normal/mossy/cracked, wall jumbles) scrambled per launch while the edge SET — the layout — stayed fixed.
 * Ground truth: two cold launches of seed -777 differed by ~1,600 persisted blocks, all in y28-33.
 *
 * Fix: after the constructor picks the MST, rebuild the set as a {@code LinkedHashSet} in a total,
 * seed-pure order (edge length, then endpoint grid coordinates — grid positions are unique per point).
 * No RNG draws are added or removed; only iteration order is pinned, so launch A and launch B now build
 * the same dungeon. Also covers {@code DungeonTreetho} and citadels, which build their own MSTs.
 */
@Mixin(value = MinimumSpanningTree.class, remap = false)
public abstract class MinimumSpanningTreeMixin {

    @Shadow(remap = false)
    Set<Edge> mstEdges;

    @Inject(
        method = "<init>(Ljava/util/Random;IILgreymerk/roguelike/worldgen/Coord;)V",
        at = @At("RETURN"),
        remap = false)
    private void tcfix$pinEdgeOrder(Random rand, int size, int edgeLength, Coord origin, CallbackInfo ci) {
        final List<Edge> sorted = new ArrayList<>(this.mstEdges);
        Collections.sort(sorted, new Comparator<Edge>() {

            @Override
            public int compare(Edge a, Edge b) {
                final int byLength = a.compareTo(b);
                if (byLength != 0) return byLength;
                final Point[] pa = a.getPoints();
                final Point[] pb = b.getPoints();
                final int byStart = tcfix$compareCoord(pa[0].getPosition(), pb[0].getPosition());
                if (byStart != 0) return byStart;
                return tcfix$compareCoord(pa[1].getPosition(), pb[1].getPosition());
            }
        });
        this.mstEdges = new LinkedHashSet<>(sorted);
    }

    private static int tcfix$compareCoord(Coord a, Coord b) {
        if (a.getX() != b.getX()) return Integer.compare(a.getX(), b.getX());
        if (a.getY() != b.getY()) return Integer.compare(a.getY(), b.getY());
        return Integer.compare(a.getZ(), b.getZ());
    }
}
