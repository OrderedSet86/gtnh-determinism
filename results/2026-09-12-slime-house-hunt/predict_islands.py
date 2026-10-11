#!/usr/bin/env python3
"""Predict TiC slime-island chunks for a seed, pure Python (Java LCG replica).

Usage: predict_islands.py <seed> <radiusChunks> [rarity=8000]

Mirrors the fixed build's draw order (SlimeIslandGenMixin routes generateIsland's field
reads to the seeded FML rand): nextInt(rarity)==0 gate, then xRange=nextInt(13)+20,
r50=nextInt(50), zRange=nextInt(13)+20. yCenter = 50 + liveHeightValue + r50 — height is
world state, so only r50 is printed here. chunkSeed reproduces GameRegistry.generateWorld
verbatim, including Forge's `>> 2 + 1L` precedence quirk (= >> 3).
"""
import sys

MASK = (1 << 48) - 1
MULT = 0x5DEECE66D
ADD = 0xB


class JavaRandom:
    def __init__(self, seed):
        self.seed = (seed ^ MULT) & MASK

    def set_seed(self, seed):
        self.seed = (seed ^ MULT) & MASK

    def _next(self, bits):
        self.seed = (self.seed * MULT + ADD) & MASK
        r = self.seed >> (48 - bits)
        return r - (1 << bits) if r >= (1 << (bits - 1)) else r

    def next_int(self, bound):
        if bound & (bound - 1) == 0:
            return (bound * self._next(31)) >> 31
        while True:
            bits = self._next(31)
            val = bits % bound
            if bits - val + (bound - 1) >= 0:
                return val

    def next_long(self):
        hi = self._next(32)
        lo = self._next(32)
        return (hi << 32) + lo  # both signed; matches Java


def to_s64(v):
    v &= (1 << 64) - 1
    return v - (1 << 64) if v >= (1 << 63) else v


def islands(world_seed, radius, rarity=8000):
    fml = JavaRandom(world_seed)
    x_seed = to_s64(fml.next_long()) >> 3  # Forge: nextLong() >> 2 + 1L parses as >> 3
    z_seed = to_s64(fml.next_long()) >> 3
    roll = JavaRandom(0)
    out = []
    for cx in range(-radius, radius + 1):
        for cz in range(-radius, radius + 1):
            roll.set_seed(to_s64(x_seed * cx + z_seed * cz) ^ world_seed)
            if roll.next_int(rarity) != 0:
                continue
            xr = roll.next_int(13) + 20
            r50 = roll.next_int(50)
            zr = roll.next_int(13) + 20
            out.append((cx, cz, xr, zr, r50))
    return out


if __name__ == "__main__":
    seed = int(sys.argv[1])
    radius = int(sys.argv[2])
    rarity = int(sys.argv[3]) if len(sys.argv) > 3 else 8000
    for cx, cz, xr, zr, r50 in islands(seed, radius, rarity):
        print(f"chunk {cx},{cz}  block {cx*16},{cz*16}  xRange={xr} zRange={zr} r50={r50}")
