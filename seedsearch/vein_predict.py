#!/usr/bin/env python3
"""Gate-less worldless prediction of GT ore-vein identity, GTNH 2.8.4 (gregtech-5.09.51.482).

Predicts the vein a given oreseed cell WOULD get if the terrain reroll gate never fired.
Pure arithmetic: no world, no terrain, no probe. This is layer 1 of a two-layer analysis:
layer 1 predicts the vein with the gate ignored, layer 2 evaluates the terrain reroll gate
itself. See docs/harness-speed.md section C.2 for layer 2.

Algorithm (forks/GT5-Unofficial = 5.09.50.119 era, which is what 2.8.4 ships):
  GTWorldgenerator.java:300-305  oreveinSeed = (seed<<16) ^ ((dim&0xff)<<56 | (osX&0xfffffff)<<28 | (osZ&0xfffffff))
  GTWorldgenerator.java:305      one nextInt(100) burned (oreveinPercentage=100 => always passes)
  GTWorldgenerator.java:336-346  per attempt: nextInt(sWeight=3568), then a LINEAR walk of the
                                 79-entry sList (OreMixes enum order) taking the first index where
                                 the running remainder goes <= 0.

DIMENSIONS. `sWeight` is global across all 79 mixes in every dimension: GT's dimension check is a
`WRONG_DIMENSION` return from `executeWorldgenChunkified`, taken AFTER the weighted draw, and each
attempt gets its own RNG stream (`new XSTR(oreveinSeed ^ mPrimaryMeta)`), so a rejected attempt burns
an `i` and nothing else. The first mix drawn that is enabled in the target dimension is therefore the
gate-less prediction, exactly as it is for the overworld.

Two different things are both called "dimension" here and must not be conflated:
  - the runtime dim id (0, -1, 1, 7) feeds ONLY the RNG hash, in bits 56-63;
  - the OreMixBuilder token ("Overworld", "Nether", "TheEnd", "Twilight Forest") drives ONLY
    eligibility, via each mix's dims[] list.
`GTWorldgen.isGenerationAllowed` reads `provider.getDimensionName()` and hard-rejects anything outside
those four names, so GT ore veins exist in exactly four dimensions however many a pack registers.

ACCURACY. Measured against a 799-seed OVERWORLD corpus (docs/HANDOFF.md): 62.1% exact overall, but it
splits by Y band — mixes with minY <= 40 run 90-96% precision while mixes with minY >= 60 run 3-9%,
because the terrain reroll gate kills 91-97% of the high band. No non-overworld dimension has been
measured. In the Twilight Forest the ground level is 30, so the high-band mixes probe air even more
often than in the overworld; expect precision at least as good for low-band mixes and recall
materially worse. Do not quote a TF number that tests/validate-vein-predict.sh has not passed.
"""
import json, os
from uo_oil import XSTR, M64   # XSTR validated bit-exact against the JVM (20k/20k)

_HERE = os.path.dirname(os.path.abspath(__file__))
# The table is PACK-SPECIFIC and is part of the algorithm, not reference data: the draw is
# nextInt(S_WEIGHT) over every mix in every dimension, so a table from another GregTech build shifts
# every result. Measured on GTNH daily-707 (gregtech-5.09.54.115) against the 2.8.4 table: 79 mixes
# and sWeight 3568 against the real 122 and 4770, and three in-game Twilight Forest checks came back
# 0/3 with two of them naming mixes that are not shard mixes at all.
#
# GTNH_OREMIXES overrides the file; regenerate with data/extract_oremixes_from_jar.py.
_MIX_FILE = os.environ.get(
    "GTNH_OREMIXES", os.path.join(_HERE, "data", "oremixes-gtnh-daily707.json"))
MIXES = json.load(open(_MIX_FILE))

# The DRAW PROTOCOL is version-specific, independently of the table. GT 5.09.51 (GTNH 2.8.4) draws
# nextInt(sWeight) over ALL mixes and rejects wrong-dimension picks after the fact; GT 5.09.54
# (daily) reseeds per attempt with FNV-1a-64 and draws over the DIMENSION-FILTERED list only
# (WorldGenContainer.run -> WorldgenQuery.inDimension(dim).findRandom). Using either protocol
# against the other version predicts confident nonsense, which is how a correct 122-mix table still
# scored 0/64 against a real daily-707 world. Sniffed from the table filename; override with
# GTNH_VEIN_ALGO=legacy|fnv.
ALGO = os.environ.get("GTNH_VEIN_ALGO") or ("legacy" if "2.8.4" in _MIX_FILE else "fnv")
MIXES.sort(key=lambda m: m["enumIndex"])
S_WEIGHT = sum(m["weight"] for m in MIXES)          # 4770 on daily-707
OREVEIN_ATTEMPTS = 64                                # GregTech.cfg I:oreveinAttempts

# The four tokens OreMixBuilder emits (OreMixBuilder.java:12-15). Note "TheEnd", not "The End" — the
# runtime provider name has a space and the probe's gtdims.json normalises it away.
OVERWORLD, NETHER, THE_END, TWILIGHT_FOREST = "Overworld", "Nether", "TheEnd", "Twilight Forest"

# Fallback dim id -> token for GTNH 2.8.4. The Twilight Forest id is a config value
# (config/TwilightForest.cfg I:dimensionID=7), so a pack can move it; prefer the gtdims.json the probe
# writes beside gtmats.json, which reports what the running instance actually had.
_FALLBACK_DIMS = {0: OVERWORLD, -1: NETHER, 1: THE_END, 7: TWILIGHT_FOREST}


def load_dims(report_dir=None, quiet=False):
    """dim id -> GT token, preferring the probe's gtdims.json over the pinned 2.8.4 table."""
    if report_dir:
        path = os.path.join(report_dir, "gtdims.json")
        if os.path.exists(path):
            with open(path) as fh:
                return {int(k): v for k, v in json.load(fh).items()}
    if not quiet:
        print(f"note: no gtdims.json in {report_dir or '<no dir given>'}; "
              f"using the pinned GTNH 2.8.4 table {_FALLBACK_DIMS}")
    return dict(_FALLBACK_DIMS)


def dim_token(dim_id, dims=None):
    """GT token for a runtime dim id. Raises rather than defaulting: a wrong token silently predicts
    the wrong dimension's veins, which reads as a broken RNG rather than a lookup miss."""
    table = dims if dims is not None else _FALLBACK_DIMS
    if dim_id not in table:
        raise KeyError(f"dim id {dim_id} is not a GT ore-vein dimension (known: {sorted(table)})")
    return table[dim_id]


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


_FNV_PRIME = 0x100000001B3
_FNV_BASIS = 0xCBF29CE484222325


def _fnv_byte(h, b):
    """One FNV-1a-64 step. gtnhlib's hashStep(long, byte) SIGN-EXTENDS the byte before the XOR
    (bytecode: i2b then i2l), so bytes >= 0x80 xor as negative longs — flipping the high 56 bits.
    Dropping that detail changes every hash that contains such a byte."""
    v = b & 0xFF
    if v >= 0x80:
        v -= 0x100
    return ((h ^ (v & M64)) * _FNV_PRIME) & M64


def _fnv_int(h, x):
    for shift in (24, 16, 8, 0):
        h = _fnv_byte(h, (x >> shift) & 0xFF)
    return h


def _fnv_long(h, x):
    for shift in (56, 48, 40, 32, 24, 16, 8, 0):
        h = _fnv_byte(h, (x >> shift) & 0xFF)
    return h


_ELIGIBLE = {}


def _eligible(token):
    """Dimension-filtered mix list in enum order, with the summed weight — WorldgenQuery's filtered
    list. Cached: it is a pure function of the token and the table."""
    got = _ELIGIBLE.get(token)
    if got is None:
        mixes = [m for m in MIXES if token in m["dims"] and m.get("enabledByDefault", True)]
        got = _ELIGIBLE[token] = (mixes, sum(m["weight"] for m in mixes))
    return got


def _attempt_hash(world_seed, oreseed_x, oreseed_z, dim, attempt):
    """FNV-1a-64 over (oreveinSeed, attemptIndex) — the per-attempt XSTR seed in GT 5.09.54."""
    h = _fnv_long(_FNV_BASIS, orevein_seed(world_seed, oreseed_x, oreseed_z, dim) & M64)
    return _fnv_int(h, attempt)


def _predict_all_fnv(world_seed, oreseed_x, oreseed_z, dim, token):
    """GT 5.09.54: every attempt draws from the dimension-filtered list with a fresh FNV-seeded XSTR.

    Unlike the legacy walk there is no wrong-dimension rejection — every attempt yields an eligible
    mix — so "attempt depth" here counts terrain-gate fall-throughs only. The chance roll
    (nextInt(100) vs oreveinPercentage=100) always passes and burns nothing from the per-attempt
    streams, which are independently seeded.
    """
    mixes, total = _eligible(token)
    if not mixes:
        return []
    out = []
    for i in range(OREVEIN_ATTEMPTS):
        rng = XSTR(_attempt_hash(world_seed, oreseed_x, oreseed_z, dim, i))
        r = rng.next_int(total)
        for m in mixes:
            r -= m["weight"]
            if r < 0:
                out.append((m, i))
                break
    return out


def predict_all(world_seed, oreseed_x, oreseed_z, dim=0, token=None):
    """-> [(mix, attempt_index), ...] for EVERY dimension-eligible draw across the 64 attempts.

    Depth 1 (the first entry) is the honest gate-less prediction. Deeper entries are what the cell
    falls through to when the terrain gate rejects the earlier picks, which in the Twilight Forest is
    the common case: 53% of layer-1 TF draws are mixes whose tMinY lands above TF's ground level of 30.
    Report the two depths separately — merging them inflates recall while hiding which is which.
    """
    if token is None:
        token = dim_token(dim)
    if ALGO == "fnv":
        return _predict_all_fnv(world_seed, oreseed_x, oreseed_z, dim, token)
    rng = XSTR(orevein_seed(world_seed, oreseed_x, oreseed_z, dim))
    rng.next_int(100)                                # oreveinPercentageRoll, always passes at 100
    out = []
    for i in range(OREVEIN_ATTEMPTS):
        m = _pick(rng.next_int(S_WEIGHT))
        if token in m["dims"]:
            out.append((m, i))
    return out


def predict(world_seed, oreseed_x, oreseed_z, dim=0, token=None):
    """-> (mix, attempt_index) for the first dimension-eligible mix drawn, or (None, None).

    Deliberately not `predict_all(...)[0]`: this is the stage-0 hot path, and a TF-eligible mix wins on
    attempt 2.4 on average, so returning early costs ~2 draws where the full walk costs 64.
    """
    if token is None:
        token = dim_token(dim)
    if ALGO == "fnv":
        hits = _predict_all_fnv(world_seed, oreseed_x, oreseed_z, dim, token)
        return hits[0] if hits else (None, None)
    rng = XSTR(orevein_seed(world_seed, oreseed_x, oreseed_z, dim))
    rng.next_int(100)
    for i in range(OREVEIN_ATTEMPTS):
        m = _pick(rng.next_int(S_WEIGHT))
        if token in m["dims"]:
            return m, i
    return None, None


def vein_geometry(mix, oreseed_x, oreseed_z, dim=0, world_seed=None, attempt=0):
    """Y band and XZ bounding box of the vein, from the per-vein RNG stream.

    GTWorldgenerator.java:355 seeds a SEPARATE stream per attempt, `new XSTR(oreveinSeed ^ mPrimaryMeta)`,
    and WorldgenGTOreLayer draws from it in a fixed order before touching the world at all:

        tMinY  = mMinY + nextInt(mMaxY - mMinY - 5)
        wXVein = aSeedX      - nextInt(mSize)
        eXVein = aSeedX + 16 + nextInt(mSize)
        nZVein = aSeedZ      - nextInt(mSize)
        sZVein = aSeedZ + 16 + nextInt(mSize)

    So the footprint and the depth to dig to are worldless too, not just the vein's identity.

    The Z pair is drawn after an X-overlap branch (bytecode offsets 322-349) rather than straight
    through, so it is reached only for a chunk the vein's X extent covers. That does not make the values
    conditional: the stream is re-seeded per chunk from (oreveinSeed ^ mPrimaryMeta), so draws 4 and 5
    are fixed for a given (oreseed, mix) whenever they happen at all, and a vein that places anywhere
    reaches them.

    Layer stack, relative to tMinY (WorldgenGTOreLayer.java:207-382, comment-confirmed): secondary on
    layers -1/0/1/2, in-between on 2/3/4/5, primary on 4/5/6/7. For the three Twilight Forest shard
    mixes (minY 5, maxY 20) that puts the secondary shard at y 4-16 and the primary at y 9-21.
    """
    if world_seed is None:
        raise TypeError("vein_geometry needs world_seed")
    if mix.get("primaryMeta") is None:
        # Newer GT registers Materials at runtime, so some carried-across tables lack metas; the
        # placement stream is seeded from the primary's id, so without it there is no honest geometry.
        return None
    if ALGO == "fnv":
        # GT 5.09.54: the placement stream is XSTR(FNV(FNV(basis, oreveinSeed), attempt) folded with
        # the primary id) — the ATTEMPT that placed the vein is part of the seed, unlike the legacy
        # (oreveinSeed ^ primaryMeta) stream, which is attempt-independent. Same draw order after.
        h = _attempt_hash(world_seed, oreseed_x, oreseed_z, dim, attempt)
        rng = XSTR(_fnv_int(h, mix["primaryMeta"]))
    else:
        rng = XSTR(_s64(orevein_seed(world_seed, oreseed_x, oreseed_z, dim) ^ mix["primaryMeta"]))
    span = mix["maxY"] - mix["minY"] - 5
    t_min_y = mix["minY"] + rng.next_int(span)
    seed_x, seed_z = oreseed_x * 16, oreseed_z * 16
    size = mix["size"]
    w_x = seed_x - rng.next_int(size)
    e_x = seed_x + 16 + rng.next_int(size)
    n_z = seed_z - rng.next_int(size)
    s_z = seed_z + 16 + rng.next_int(size)
    return {
        "tMinY": t_min_y,
        "bbox": [w_x, n_z, e_x, s_z],
        "yLow": t_min_y - 1,
        "yHigh": t_min_y + 7,
        "secondaryY": [t_min_y - 1, t_min_y + 2],
        "betweenY": [t_min_y + 2, t_min_y + 5],
        "primaryY": [t_min_y + 4, t_min_y + 7],
    }


# ---------------------------------------------------------------- oreseed grid

def is_ore_chunk(chunk_x, chunk_z):
    """GTWorldgenerator.isOreChunk for OregenPattern.EQUAL_SPACING, which is what every fresh world gets.

    `OregenPatternSavedData.loadData` defaults a new world to the last enum value (EQUAL_SPACING), and
    `onWorldLoad` only ever runs for dimensionId == 0 while `oregenPattern` is a static — so the
    overworld's pattern is also the Twilight Forest's.

    Python's % already floors, which is what Math.floorMod does and what Java's bare % does NOT
    (-2 % 3 is 1 here and -2 in Java). Do not "simplify" this into the Java spelling.
    """
    return chunk_x % 3 == 1 and chunk_z % 3 == 1


def nearest_cell(chunk_x, chunk_z):
    """The oreseed cell whose 3x3 box contains this chunk."""
    return (round((chunk_x - 1) / 3) * 3 + 1, round((chunk_z - 1) / 3) * 3 + 1)


def oreseed_cells(center_cx, center_cz, radius_chunks):
    """All oreseed cells in a square chunk window, sorted, as (cell_x, cell_z) CHUNK coordinates."""
    lo_x, hi_x = center_cx - radius_chunks, center_cx + radius_chunks
    lo_z, hi_z = center_cz - radius_chunks, center_cz + radius_chunks
    return [(x, z)
            for x in range(lo_x, hi_x + 1) if x % 3 == 1
            for z in range(lo_z, hi_z + 1) if z % 3 == 1]


def cell_origin_block(cell_x, cell_z):
    """GT's aSeedX/aSeedZ: the cell's chunk origin in blocks (GTWorldgenerator.java:361-362)."""
    return (cell_x * 16, cell_z * 16)


def cell_center_block(cell_x, cell_z):
    """Block centre of the cell's trigger chunk. The right anchor for distances: the vein box runs
    [origin - size, origin + 16 + size], so it is centred near origin+8, not on origin."""
    return (cell_x * 16 + 8, cell_z * 16 + 8)


def materials_of(mix):
    # Absent metas mean the reference table did not know that material, not that the slot is empty.
    # Returning a partial set would let a mix be identified by the subset it happens to share with
    # another, so a mix missing any meta is excluded from identification entirely (see BY_MATS).
    keys = ("primaryMeta", "secondaryMeta", "betweenMeta", "sporadicMeta")
    if any(mix.get(k) is None for k in keys):
        return None
    return {mix[k] for k in keys}


BY_DIM = {tok: [m for m in MIXES if tok in m["dims"]]
          for tok in (OVERWORLD, NETHER, THE_END, TWILIGHT_FOREST)}
WEIGHT_BY_DIM = {tok: sum(m["weight"] for m in ms) for tok, ms in BY_DIM.items()}

OW = BY_DIM[OVERWORLD]
TF = BY_DIM[TWILIGHT_FOREST]
BY_MATS = {frozenset(ms): m["name"] for m in OW for ms in [materials_of(m)] if ms is not None}

# The dims[]-membership test has to stay exactly equivalent to the old hardcoded m["overworld"] flag,
# or every stored overworld prediction silently changes meaning.
assert all((OVERWORLD in m["dims"]) == bool(m["overworld"]) for m in MIXES), \
    "oremixes data: dims[] and the overworld flag disagree"
