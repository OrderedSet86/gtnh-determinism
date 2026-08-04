#!/usr/bin/env python3
"""Gate-less worldless prediction of GT ore-vein identity, GTNH 2.8.4 (gregtech-5.09.51.482).

Predicts the vein a given oreseed cell WOULD get if the terrain reroll gate never fired.
Pure arithmetic: no world, no terrain, no probe. Layer 1 of the two-layer analysis in
docs/HANDOFF.md "Worldless GT vein prediction".

Algorithm (forks/GT5-Unofficial = 5.09.50.119 era, which is what 2.8.4 ships):
  GTWorldgenerator.java:300-305  oreveinSeed = (seed<<16) ^ ((dim&0xff)<<56 | (osX&0xfffffff)<<28 | (osZ&0xfffffff))
  GTWorldgenerator.java:305      one nextInt(100) burned (oreveinPercentage=100 => always passes)
  GTWorldgenerator.java:336-346  per attempt: nextInt(sWeight=3568), then a LINEAR walk of the
                                 79-entry sList (OreMixes enum order) taking the first index where
                                 the running remainder goes <= 0.
Only 21 of 79 mixes are Overworld-enabled (weight 1520/3568), so ~57% of attempts return
WRONG_DIMENSION and are burned for free -- they cost an `i` but not a placementAttempt. The first
Overworld mix drawn is therefore the gate-less prediction.
"""
import json, os
from uo_oil import XSTR, M64   # XSTR validated bit-exact against the JVM (20k/20k)

_HERE = os.path.dirname(os.path.abspath(__file__))
MIXES = json.load(open(os.path.join(_HERE, "data", "oremixes-gtnh-2.8.4.json")))
MIXES.sort(key=lambda m: m["enumIndex"])
S_WEIGHT = sum(m["weight"] for m in MIXES)          # 3568
OREVEIN_ATTEMPTS = 64                                # GregTech.cfg I:oreveinAttempts

def _s64(v):
    v &= M64
    return v - (1 << 64) if v >> 63 else v

def orevein_seed(world_seed, oreseed_x, oreseed_z, dim=0):
    return _s64(((world_seed << 16) & M64)
                ^ (((dim & 0xFF) << 56) | ((oreseed_x & 0x0FFFFFFF) << 28) | (oreseed_z & 0x0FFFFFFF)))

def _pick(r):
    """sList linear walk: first index where the running remainder goes <= 0."""
    acc = 0
    for m in MIXES:
        acc += m["weight"]
        if r <= acc:
            return m
    return MIXES[-1]

def predict(world_seed, oreseed_x, oreseed_z, dim=0):
    """-> (mix, attempt_index) for the first Overworld-enabled mix drawn, or (None, None)."""
    rng = XSTR(orevein_seed(world_seed, oreseed_x, oreseed_z, dim))
    rng.next_int(100)                                # oreveinPercentageRoll, always passes at 100
    for i in range(OREVEIN_ATTEMPTS):
        m = _pick(rng.next_int(S_WEIGHT))
        if m["overworld"]:
            return m, i
    return None, None

def materials_of(mix):
    return {mix["primaryMeta"], mix["secondaryMeta"], mix["betweenMeta"], mix["sporadicMeta"]}

OW = [m for m in MIXES if m["overworld"]]
BY_MATS = {frozenset(materials_of(m)): m["name"] for m in OW}
