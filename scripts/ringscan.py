#!/usr/bin/env python3
"""Offline replica of gtnhdeterminism's eldritch ring siting (jar >= 0.3, region-grid model).

One obelisk site per 25x25-chunk region: 9 seeded candidate slots in the region's
central 3x3 chunks, tried in seeded order; in-game the winner is the FIRST candidate
whose 5-column validity test passes on virgin terrain. Terrain is not evaluable
offline, so this prints each region's candidates in try-order — the obelisk stands
at the first of those that passes (flat-ish stone/sand/grass/gravel/dirt surface).

Usage: ringscan.py [seed] [centerChunkX] [centerChunkZ] [radiusRegions]
"""

import sys

M64 = (1 << 64) - 1


def s64(x):
    x &= M64
    return x - (1 << 64) if x >= (1 << 63) else x


def jdiv(a, b):
    # Java integer division truncates toward zero
    q = abs(a) // abs(b)
    return q if (a >= 0) == (b >= 0) else -q


class JavaRandom:
    MULT = 0x5DEECE66D
    ADD = 0xB
    MASK = (1 << 48) - 1

    def __init__(self, seed):
        self.set_seed(seed)

    def set_seed(self, seed):
        self.seed = (s64(seed) ^ self.MULT) & self.MASK

    def next(self, bits):
        self.seed = (self.seed * self.MULT + self.ADD) & self.MASK
        r = self.seed >> (48 - bits)
        # signed int for bits=32
        if bits == 32 and r >= (1 << 31):
            r -= 1 << 32
        return r

    def next_int(self, bound):
        if bound & (bound - 1) == 0:  # power of two
            return (bound * self.next(31)) >> 31
        while True:
            bits = self.next(31)
            val = bits % bound
            if bits - val + (bound - 1) >= 0:
                return val

    def next_long(self):
        hi = self.next(32)
        lo = self.next(32)
        return s64((hi << 32) + lo)


def fork(world_seed, cx, cz, salt):
    r = JavaRandom(s64(world_seed + s64(salt * 0x9E3779B97F4A7C15)))
    a = s64(jdiv(r.next_long(), 2) * 2 + 1)
    b = s64(jdiv(r.next_long(), 2) * 2 + 1)
    r.set_seed(s64((s64(cx * a) + s64(cz * b)) ^ world_seed) ^ salt)
    return r



REGION = 25
MARGIN = 11
REGION_SALT = 8


def region_candidates(world_seed, rm, rn):
    """Mirror of EldritchRingLottery.candidates (jar >= 0.3)."""
    r = fork(world_seed, rm, rn, REGION_SALT)
    slots = list(range(9))
    for i in range(8, 0, -1):  # Fisher-Yates, Java order
        j = r.next_int(i + 1)
        slots[i], slots[j] = slots[j], slots[i]
    out = []
    for slot in slots:
        cx = rm * REGION + MARGIN + slot % 3
        cz = rn * REGION + MARGIN + slot // 3
        x16, z16 = r.next_int(16), r.next_int(16)
        w = 11 + r.next_int(6) * 2
        h = 11 + r.next_int(6) * 2
        out.append(dict(cx=cx, cz=cz, x=cx * 16 + x16, z=cz * 16 + z16, w=w, h=h))
    return out


def main():
    seed = int(sys.argv[1]) if len(sys.argv) > 1 else 88888888
    ccx = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    ccz = int(sys.argv[3]) if len(sys.argv) > 3 else 0
    radius = int(sys.argv[4]) if len(sys.argv) > 4 else 2

    crm, crn = ccx // REGION, ccz // REGION
    for rm in range(crm - radius, crm + radius + 1):
        for rn in range(crn - radius, crn + radius + 1):
            print(f"region ({rm},{rn}) — chunks [{rm*REGION}..{rm*REGION+REGION-1}] x "
                  f"[{rn*REGION}..{rn*REGION+REGION-1}] — candidates in try-order:")
            for i, c in enumerate(region_candidates(seed, rm, rn)):
                print(f"  #{i+1}: chunk ({c['cx']},{c['cz']}) center block ({c['x']},{c['z']}) w={c['w']} h={c['h']}")


if __name__ == "__main__":
    main()
