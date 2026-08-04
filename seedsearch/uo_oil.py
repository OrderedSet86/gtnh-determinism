#!/usr/bin/env python3
"""Worldless prediction of GregTech underground fluid ("oil") fields.

GT computes underground fluid lazily on first read, never at worldgen, and the value is a
PURE function of (worldSeed, dimensionId, chunkX>>3, chunkZ>>3, chunkX&7, chunkZ&7) plus
static config -- no terrain, no biome, no world state. So the whole overworld oil map of any
seed is computable offline with no JVM and no probe run.

Source of truth (2.7.4-era fork; current 54.x is behaviourally identical here):
  gregtech/common/UndergroundOil.java:127-144   getPristineAmount
  gregtech/api/objects/XSTR.java                 constructor is identity; next/nextInt below
  gregtech/api/objects/GTUOFluid.java:62-70      getRandomAmount
  gregtech/api/objects/GTUODimension.java:39-48  getRandomFluid
  gregtech/api/objects/GTUODimensionList.java:122-161  overworld defaults

Fields are 8x8 chunks (128x128 blocks). Fluid TYPE and veinAverage are per-field; only the
+-25% multiplier is per-chunk.

CAUTION -- fluid order is Guava HashBiMap iteration order, NOT config order. Verified
identical under the guava-14.0.1 and guava-17.0 (the version Forge ships for 1.7.10) that
the pack actually runs. Do not "fix" this to config order.
"""
import math
import struct

M64 = (1 << 64) - 1


def _f32(x):
    """Round a Python float to float32, as Java's (float) cast / float arithmetic does."""
    return struct.unpack("f", struct.pack("f", x))[0]


class XSTR:
    """gregtech.api.objects.XSTR -- 64-bit xorshift. Constructor is `this.seed = seed`."""

    __slots__ = ("seed",)

    def __init__(self, seed):
        self.seed = seed & M64

    def _step(self):
        x = self.seed
        x ^= (x << 21) & M64
        x ^= x >> 35                      # Java >>> on a long = logical shift
        x ^= (x << 4) & M64
        self.seed = x & M64
        return self.seed

    def next(self, nbits):
        return self._step() & ((1 << nbits) - 1)

    def next_int(self, bound):
        """XSTR.nextInt(bound): ONE step, `(int) last % bound`, then abs. No rejection loop."""
        v = self._step() & 0xFFFFFFFF     # (int) cast keeps the low 32 bits...
        if v >= 1 << 31:
            v -= 1 << 32                  # ...as a SIGNED int
        return abs(v) % bound             # Java % truncates toward zero, then (out<0)?-out:out

    def next_float(self):
        return self.next(24) * (2.0 ** -24)

    def next_double(self):
        return ((self.next(26) << 27) + self.next(27)) * (2.0 ** -53)


DIVIDER = 5000

# (registry name, chance, minAmount, maxAmount) in Guava HashBiMap ITERATION order.
OVERWORLD_FLUIDS = [
    ("liquid_light_oil",  20, 10, 350),
    ("liquid_medium_oil", 20,  0, 625),
    ("oil",               20,  0, 625),
    ("gas_natural_gas",   20, 10, 350),
    ("liquid_heavy_oil",  20,  0, 625),
]
_MAX_CHANCE = sum(f[1] for f in OVERWORLD_FLUIDS)


def _random_fluid(rng):
    r = rng.next_int(1000)
    for f in OVERWORLD_FLUIDS:
        chance = f[1] * 1000 // _MAX_CHANCE
        if r <= chance:
            return f
        r -= chance
    return None                            # unreachable in the overworld (buckets sum to 1000)


def _random_amount(rng, fluid):
    _, _, min_amount, max_amount = fluid
    smax = int(math.floor(math.pow(max_amount * 100.0 * DIVIDER, 0.2)))
    smin = math.pow(min_amount * 100.0 * DIVIDER, 0.2)
    samount = max(smin, rng.next_int(smax) + rng.next_double())
    return int(math.pow(samount, 5) / 100)   # Java (int) truncates toward zero


def field_seed(world_seed, chunk_x, chunk_z, dim=0):
    s = world_seed + dim * 2 + (chunk_x >> 3) + 8267 * (chunk_z >> 3)
    return s & M64


def oil_at_chunk(world_seed, chunk_x, chunk_z, dim=0):
    """-> (registry_name, vein_average, chunk_amount). Mirrors getPristineAmount exactly."""
    rng = XSTR(field_seed(world_seed, chunk_x, chunk_z, dim))
    fluid = _random_fluid(rng)
    if fluid is None:
        return None
    vein_average = _random_amount(rng, fluid)
    for _ in range((((chunk_x & 0x7) << 3) | (chunk_z & 0x7))):
        rng.next(24)
    # all float32 in Java: (float)veinAverage * (0.75f + nextFloat()/2f)
    mult = _f32(0.75 + _f32(rng.next_float() / 2.0))
    amount = int(_f32(_f32(vein_average) * mult))
    return fluid[0], vein_average, amount


def litres_per_op(amount, coefficient=1):
    """What a prospector/drill reports: floor(amount * coefficient / DIVIDER)."""
    return math.floor(amount * coefficient / DIVIDER)


def field_of(chunk_x, chunk_z):
    return chunk_x >> 3, chunk_z >> 3
