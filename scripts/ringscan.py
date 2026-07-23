#!/usr/bin/env python3
"""Offline replica of gtnhdeterminism's TcForkUtil.fork + TC eldritch ring candidacy.

Predicts, purely from the world seed, which chunks roll an eldritch ring
(obelisk) candidate under the seeded generateSurface (jar >= 0.1), and which
candidate wins the deterministic priority contest (jar >= 0.2, EldritchRingLottery).

Usage: ringscan.py [seed] [centerChunkX] [centerChunkZ] [radiusChunks]

Caveats:
- WIN/suppressed verdicts within ~32 chunks of the scan edge are unreliable
  (suppressors outside the scanned window are not considered) — scan with margin.
- A WIN only means the site may generate; Thaumcraft's stock 5-column terrain
  validity test still applies, so a winner can still produce no obelisk
  (granite/water/slope/trees at the probed columns).
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


def ring_candidate(world_seed, cx, cz):
    """Mirror of the salt-5 structure stream in ThaumcraftWorldGeneratorMixin."""
    r = fork(world_seed, cx, cz, 5)
    x16 = r.next_int(16)
    z16 = r.next_int(16)
    if r.next_int(150) == 0:
        return None  # mound branch
    if r.next_int(66) != 0:
        return None
    w = 11 + r.next_int(6) * 2
    h = 11 + r.next_int(6) * 2
    return dict(cx=cx, cz=cz, x=cx * 16 + x16, z=cz * 16 + z16, w=w, h=h)


def priority(world_seed, cx, cz):
    return fork(world_seed, cx, cz, 7).next_long()


def cells_range(c):
    """Chunk-coord rect that MazeThread would fill for candidate c."""
    col = c["cx"] - (1 + c["w"] // 2)
    row = c["cz"] - (1 + c["h"] // 2)
    return col, col + c["w"] - 1, row, row + c["h"] - 1


def conflicts(a, b):
    """True if candidate b's maze cells intersect a's mazesInRange scan rect."""
    x0, x1, z0, z1 = cells_range(b)
    return not (x1 < a["cx"] - a["w"] or x0 > a["cx"] + a["w"]
                or z1 < a["cz"] - a["h"] or z0 > a["cz"] + a["h"])


def main():
    seed = int(sys.argv[1]) if len(sys.argv) > 1 else 88888888
    ccx, ccz = (int(sys.argv[2]), int(sys.argv[3])) if len(sys.argv) > 4 else (-50, 28)
    radius = int(sys.argv[4]) if len(sys.argv) > 4 else 40

    cands = []
    for cx in range(ccx - radius, ccx + radius + 1):
        for cz in range(ccz - radius, ccz + radius + 1):
            c = ring_candidate(seed, cx, cz)
            if c:
                c["prio"] = priority(seed, cx, cz)
                cands.append(c)

    print(f"seed {seed}: {len(cands)} ring candidates within +-{radius} chunks of ({ccx},{ccz})")
    for c in sorted(cands, key=lambda c: (abs(c["cx"] - ccx) + abs(c["cz"] - ccz))):
        others = [o for o in cands if o is not c and conflicts(c, o)]
        losers = [o for o in others if o["prio"] < c["prio"]]
        wins = all(o["prio"] < c["prio"] for o in others)
        print(f"  chunk ({c['cx']:4d},{c['cz']:4d}) center block ({c['x']:6d},{c['z']:6d}) "
              f"w={c['w']} h={c['h']} conflicts={len(others)} "
              f"{'WINS (deterministic rule)' if wins else 'suppressed by higher-priority neighbor'}")


if __name__ == "__main__":
    main()
