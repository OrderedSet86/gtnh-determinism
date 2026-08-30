#!/usr/bin/env python3
"""Library + CLI for probe seed-search reports (probe.search=true JSONs).

Decodes GT ore m-values, aggregates chest loot, and evaluates filter predicates.

CLI:
  searchlib.py summary <report-dir>                 # per-seed one-liners + aggregate stats
  searchlib.py chests <report-dir> [item-substr]    # chest loot histogram (optionally filtered)
  searchlib.py filter <report-dir> <expr>           # python expr over `r` (SeedReport); prints passing seeds
     e.g. 'r.water(6) > 2000 and r.has_chest_item("gt.metaitem")'
  searchlib.py biomeregions <report-dir> [biomes.json] [min-side] [radius] [humidity]
     no-rain chunk squares near spawn and their distance to a high-humidity chunk
"""
import json
import sys
from collections import Counter
from pathlib import Path

# GT m-value encoding (GT5u 1.7.10). m % 1000 is the material id; the thousands digit is the stone type:
#   0 stone, 1 netherrack, 2 endstone, 3 black granite, 4 red granite, 5 marble, 6 basalt.
# Two flags sit above that, as offsets rather than bits:
#   +16000 small-ore variant
#   +8000  natural (worldgen-placed rather than player-placed) — added by GT 5.09.54.x
# Decoding matches GTOreAdapter.getOreInfo. It reads pre-54 values correctly too: those never set the natural
# offset, so the extra modulo is a no-op on them and one decoder covers every supported pack version.
SMALL_ORE_META_OFFSET = 16000
NATURAL_ORE_META_OFFSET = 8000
STONE_NAMES = {0: "stone", 1: "netherrack", 2: "endstone", 3: "blackgranite",
               4: "redgranite", 5: "marble", 6: "basalt"}


def decode_ore(m, mats=None):
    small = m >= SMALL_ORE_META_OFFSET
    base = m % SMALL_ORE_META_OFFSET
    natural = base >= NATURAL_ORE_META_OFFSET
    v = (base % NATURAL_ORE_META_OFFSET) // 1000
    mat = m % 1000
    name = (mats or {}).get(str(mat), f"mat{mat}")
    return {"material": name, "materialId": mat, "small": small, "natural": natural,
            "stone": STONE_NAMES.get(v, f"v{v}")}


class BiomeTable:
    """Biome climate + crop humidity, loaded from the probe's biomes.json sidecar.

    Never hardcodes a classification. Which biomes do not rain, and what humidity a biome is worth,
    are both pack-dependent: humidity is CropsNH (a ramp on biome.rainfall, saturating at 0.8) on the
    2.9 daily line and CropsPP-filled IC2 BiomeDictionary weights on 2.8.4, and the two disagree. A
    report directory without the sidecar therefore gets no classification rather than a guessed one.
    """

    def __init__(self, d):
        self.source = d.get("humiditySource", "unknown")
        self.mods = d.get("mods", {})
        self.by_id = {b["id"]: b for b in d.get("biomes", [])}
        self.rivers = {int(k): set(v) for k, v in (d.get("rwgRivers") or {}).items()}
        self.reachable = set()
        for ids in (d.get("rwgBuckets") or {}).values():
            self.reachable |= set(ids)
        for ids in self.rivers.values():
            self.reachable |= ids

    def no_rain(self, biome_id):
        """True/False, or None when the flag could not be read for that biome."""
        b = self.by_id.get(biome_id)
        return None if b is None else b.get("rainEnabled") is False

    def humidity(self, biome_id):
        b = self.by_id.get(biome_id)
        return None if b is None else b.get("hum")


def _max_square(grid):
    """(side, corner_i, corner_j) of the largest all-True axis-aligned square, and the full DP table.

    Standard dp[i][j] = 1 + min(up, left, up-left). The DP table is returned as well because the
    caller needs every position that admits a square of the minimum side, not just the biggest one.
    """
    n, m = len(grid), len(grid[0])
    dp = [[0] * m for _ in range(n)]
    best, bi, bj = 0, 0, 0
    for i in range(n):
        for j in range(m):
            if not grid[i][j]:
                continue
            dp[i][j] = 1 if (i == 0 or j == 0) else 1 + min(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            if dp[i][j] > best:
                best, bi, bj = dp[i][j], i, j
    return best, bi - best + 1, bj - best + 1, dp


class SeedReport:

    def __init__(self, path, mats=None, table=None):
        self.path = Path(path)
        d = json.load(open(path))
        self.table = table
        self.seed = d["seed"]
        # "dim" arrived in report format 6. Absent means the report predates it and is an overworld walk.
        self.dim = d.get("dim", 0)
        self.center = d.get("center")
        self.spawn = d.get("search", {}).get("spawn", [0, 0, 0])
        self.chunks = d.get("search", {}).get("chunks", {})
        self.villages = d.get("villages", [])
        self.witchery = d.get("witchery", [])
        self.tffeatures = d.get("tffeatures", {})
        self.mats = mats or {}

    def _near(self, radius_chunks):
        scx, scz = self.spawn[0] >> 4, self.spawn[2] >> 4
        for key, c in self.chunks.items():
            x, z = map(int, key.split(","))
            if abs(x - scx) <= radius_chunks and abs(z - scz) <= radius_chunks:
                yield key, c

    def water(self, radius_chunks=15):
        return sum(c["water"] for _, c in self._near(radius_chunks))

    def clay(self, radius_chunks=15):
        return sum(c["clay"] for _, c in self._near(radius_chunks))

    def biomes(self, radius_chunks=15):
        return Counter(c["biome"] for _, c in self._near(radius_chunks))

    def biome_regions(self, min_side=5, radius_chunks=15, humidity=14, strict=True):
        """Largest no-rain chunk square near spawn, and its distance to a high-humidity chunk.

        Returns None when the report directory carried no biomes.json, because the classification is
        pack-dependent and guessing it is how a sweep ends up measuring the wrong thing.

        A chunk counts as no-rain only if EVERY one of its 256 columns is a no-rain biome. That needs
        the per-column "biomeCounts" census added in report format 7; on an older report only the
        chunk-centre column exists, so the answer is a proxy and is labelled `columns: "center"`
        rather than silently passing as the real thing. The distinction matters here more than it
        looks: under RWG the desert family paints its rivers with River Oasis, which rains and is
        itself high-humidity, so a single river column both breaks a square and supplies its
        neighbour. A centre sample sees 1 column in 256 and misses almost all of it.

        Chunks missing from the report are treated as not-no-rain, which keeps the square inside
        measured ground instead of extending it across a hole.

        `side` is the largest square found anywhere in the window. `best` is the square of exactly
        `min_side` that sits closest to a humid chunk, which is the routing-relevant one — the
        biggest desert is not necessarily the one next to the swamp.

        `humidSide` is the largest all-humid square, and it is the field that actually discriminates.
        Measured over 24 random daily-707 seeds, a >=5x5 no-rain square near spawn occurs in 50% of
        seeds and is nearly always within a chunk or two of SOME humid chunk, so the stated criterion
        on its own rules almost nothing out. Requiring both regions to be >=5x5 cuts it to 25%,
        because the two are anti-correlated: RWG picks the climate band from one cell-noise field, so
        a window is mostly hot band or mostly wet band. A window holding a large amount of both is
        one that straddles the band boundary, which is the rare and routing-relevant case.
        """
        if self.table is None:
            return None
        scx, scz = self.spawn[0] >> 4, self.spawn[2] >> 4
        span = 2 * radius_chunks + 1
        norain = [[False] * span for _ in range(span)]
        humid_grid = [[False] * span for _ in range(span)]
        humid = []
        columns, unset = "all", 0

        for key, c in self._near(radius_chunks):
            cx, cz = map(int, key.split(","))
            i, j = cx - (scx - radius_chunks), cz - (scz - radius_chunks)
            counts = c.get("biomeCounts")
            if counts and strict:
                ids = {int(k): v for k, v in counts.items()}
            else:
                if strict:
                    columns = "center"
                ids = {c["biomeId"]: 256}
            # 255 is Chunk.getBiomeGenForWorldCoords' "unset" sentinel, not a biome. Counted and
            # reported; a window carrying any is not a clean measurement.
            unset += ids.get(255, 0)
            flags = [self.table.no_rain(b) for b in ids if b != 255]
            norain[i][j] = bool(flags) and all(f is True for f in flags)
            hums = [self.table.humidity(b) for b in ids if b != 255]
            best_hum = max((h for h in hums if h is not None), default=None)
            if best_hum is not None and best_hum >= humidity:
                humid.append((i, j, best_hum))
                humid_grid[i][j] = True

        side, ci, cj, dp = _max_square(norain)
        humid_side, hi0, hj0, _ = _max_square(humid_grid)

        best = None
        if side >= min_side and humid:
            for i in range(span):
                for j in range(span):
                    if dp[i][j] < min_side:
                        continue
                    i0, j0 = i - min_side + 1, j - min_side + 1
                    for hi, hj, hb in humid:
                        gap = max(max(i0 - hi, 0, hi - i), max(j0 - hj, 0, hj - j))
                        if best is None or gap < best[0]:
                            best = (gap, i0, j0, hi, hj, hb)
                    if best is not None and best[0] <= 1:
                        break
                if best is not None and best[0] <= 1:
                    break

        out = {"side": side, "corner": [ci + scx - radius_chunks, cj + scz - radius_chunks],
               "humidSide": humid_side,
               "humidCorner": [hi0 + scx - radius_chunks, hj0 + scz - radius_chunks],
               "columns": columns, "unset": unset, "humidChunks": len(humid), "gap": -1}
        if best is not None:
            gap, i0, j0, hi, hj, hb = best
            out.update(gap=gap,
                       best=[i0 + scx - radius_chunks, j0 + scz - radius_chunks],
                       humid=[hi + scx - radius_chunks, hj + scz - radius_chunks],
                       hum=hb)
        return out

    def chest_items(self, radius_chunks=15):
        out = Counter()
        for _, c in self._near(radius_chunks):
            for chest in c.get("chests", []):
                for it in chest["items"]:
                    out[f'{it["id"]}:{it["d"]}'] += it["n"]
        return out

    def has_chest_item(self, substr, radius_chunks=15):
        return any(substr in k for k in self.chest_items(radius_chunks))

    def vein_materials(self, radius_chunks=15, min_count=8):
        """Materials with enough big-ore TEs nearby to plausibly be a vein (not scattered smalls)."""
        counts = Counter()
        for _, c in self._near(radius_chunks):
            for m_str, n in c.get("ores", {}).items():
                d = decode_ore(int(m_str), self.mats)
                if not d["small"]:
                    counts[d["material"]] += n
        return Counter({k: v for k, v in counts.items() if v >= min_count})

    def village_count(self):
        return len([v for v in self.villages if isinstance(v, str) and "pieces" in v])

    def vein_cells(self, min_count=8):
        """Big-ore block counts grouped by GT oreseed cell -> {(cell_x, cell_z): Counter(materialId: n)}.

        Small ores are excluded, and that exclusion is load-bearing rather than tidy:
        WorldgenGTOreSmallPieces scatters small ores independently of any vein, so counting them would
        manufacture cells that hold no vein at all. This is the largest false-positive risk in the whole
        ground-truth path.

        A vein spans [cell*16 - size, cell*16 + 16 + size], so it can spill one chunk outside its 3x3
        cell box. Those contributions go to a separate "ambiguous" bucket keyed None rather than being
        folded into a neighbour, because guessing which cell they belong to is what turns a clean
        measurement into a plausible-looking wrong one.
        """
        cells, ambiguous = {}, Counter()
        for key, c in self.chunks.items():
            cx, cz = map(int, key.split(","))
            # The cell whose 3x3 box holds this chunk; cells sit on floorMod(chunk,3)==1.
            cell = (round((cx - 1) / 3) * 3 + 1, round((cz - 1) / 3) * 3 + 1)
            inside = abs(cx - cell[0]) <= 1 and abs(cz - cell[1]) <= 1
            for m_str, n in c.get("ores", {}).items():
                d = decode_ore(int(m_str), self.mats)
                if d["small"]:
                    continue
                if inside:
                    cells.setdefault(cell, Counter())[d["materialId"]] += n
                else:
                    ambiguous[d["materialId"]] += n
        out = {k: v for k, v in cells.items() if sum(v.values()) >= min_count}
        if ambiguous:
            out[None] = ambiguous
        return out

    def has_ore_census(self):
        """False when no chunk reports any ore tile entity at all.

        On GT 5.09.54.x worldgen ores are plain blocks with no tile entities, so the census comes back
        EMPTY rather than zero and every vein tool would silently report "no veins". The probe logs a
        warning about this; the report itself carries no marker, so callers must check.
        """
        return any(c.get("ores") for c in self.chunks.values())

    def chests_by_feature(self):
        """chests grouped by the Twilight Forest feature the probe attributed their chunk to.

        Attribution is per chunk because every chest in a chunk belongs to the same feature, and it
        comes from the probe rather than being recomputed here: the TF region grid is reproducible in
        Python, but reimplementing a mod's placement maths is how self-consistent, real-divergent
        answers get produced.
        """
        out = {}
        for _, c in self.chunks.items():
            feature = c.get("tffeature")
            if not feature:
                continue
            for chest in c.get("chests", []):
                out.setdefault(feature, []).append(chest)
        return out


# GT material ids for the six Thaumcraft shards. Confirmed against
# results/2026-07-24-seedlib-2.8.4-pool-500/gtmats.json.
SHARD_MATERIAL_IDS = {540: "InfusedAir", 541: "InfusedFire", 542: "InfusedEarth",
                      543: "InfusedWater", 544: "InfusedEntropy", 545: "InfusedOrder"}

# Each Twilight Forest shard mix carries TWO shards, as its primary and secondary. Amber (514) and
# Cinnabar (826) are the between and sporadic materials of ALL THREE, so neither can ever identify a
# mix — only the InfusedX pair discriminates.
SHARD_MIX_BY_PAIR = {
    frozenset((543, 541)): "ore.mix.aquaignis",     # InfusedWater + InfusedFire
    frozenset((542, 540)): "ore.mix.terraaer",      # InfusedEarth + InfusedAir
    frozenset((544, 545)): "ore.mix.perditioordo",  # InfusedEntropy + InfusedOrder
}


def identify_mix(cell_counter):
    """-> (mix name, confidence) for a cell's big-ore material counts.

    confidence is "pair" when both shards of a mix are present, "primary-only" when just one is (a
    window-clipped or shallow-probed vein), and the mix is None with "shard-unknown" when a cell holds
    only the shared Amber/Cinnabar materials. Never guesses: a wrong attribution here would show up as
    a predictor defect rather than a measurement one.
    """
    shards = {m for m in cell_counter if m in SHARD_MATERIAL_IDS}
    if not shards:
        return None, "no-shards"
    for pair, name in SHARD_MIX_BY_PAIR.items():
        if pair <= shards:
            return name, "pair"
    if len(shards) == 1:
        only = next(iter(shards))
        for pair, name in SHARD_MIX_BY_PAIR.items():
            if only in pair:
                return name, "primary-only"
    return None, "shard-unknown"


def load_dir(report_dir, table=None):
    """SeedReports from a directory, with its gtmats.json and biomes.json sidecars attached.

    `table` overrides the directory's own biomes.json, which is what lets a corpus generated before
    the sidecar existed be classified with one taken from the pack it was generated on. Supplying a
    table from a DIFFERENT pack line is a measurement error, not a convenience — the humidity column
    means something else there.
    """
    report_dir = Path(report_dir)
    mats_file = report_dir / "gtmats.json"
    mats = json.load(open(mats_file)) if mats_file.exists() else {}
    if table is None:
        table_file = report_dir / "biomes.json"
        if table_file.exists():
            table = BiomeTable(json.load(open(table_file)))
    for p in sorted(report_dir.glob("seed-*.json")):
        try:
            yield SeedReport(p, mats, table)
        except Exception as e:
            print(f"WARN: {p.name}: {e}", file=sys.stderr)


def main():
    cmd, d = sys.argv[1], sys.argv[2]
    if cmd == "summary":
        biome_all, item_all = Counter(), Counter()
        n = 0
        for r in load_dir(d):
            n += 1
            top_biome = ", ".join(b for b, _ in r.biomes().most_common(3))
            veins = ", ".join(f"{m}:{c}" for m, c in r.vein_materials().most_common(5))
            print(f"{r.seed}: spawn {r.spawn}  water {r.water()}  clay {r.clay()}  "
                  f"villages {r.village_count()}  biomes [{top_biome}]  veins [{veins}]")
            biome_all.update(r.biomes())
            item_all.update(r.chest_items())
        print(f"\n== {n} seeds; top biomes: {biome_all.most_common(8)}")
        print(f"== top chest items: {item_all.most_common(15)}")
    elif cmd == "chests":
        sub = sys.argv[3] if len(sys.argv) > 3 else ""
        total = Counter()
        for r in load_dir(d):
            total.update({k: v for k, v in r.chest_items().items() if sub in k})
        for k, v in total.most_common(50):
            print(f"{v:6d}  {k}")
    elif cmd == "biomeregions":
        # searchlib.py biomeregions <report-dir> [biomes.json] [min-side] [radius] [humidity]
        table = BiomeTable(json.load(open(sys.argv[3]))) if len(sys.argv) > 3 else None
        min_side = int(sys.argv[4]) if len(sys.argv) > 4 else 5
        radius = int(sys.argv[5]) if len(sys.argv) > 5 else 15
        humidity = int(sys.argv[6]) if len(sys.argv) > 6 else 14
        n = qualifying = both = 0
        sides, gaps, proxy = Counter(), Counter(), 0
        for r in load_dir(d, table):
            res = r.biome_regions(min_side, radius, humidity)
            if res is None:
                sys.exit(f"{d} has no biomes.json and none was supplied; pass one as argv[3]")
            n += 1
            sides[res["side"]] += 1
            if res["columns"] == "center":
                proxy += 1
            if res["side"] >= min_side:
                qualifying += 1
                gaps[res["gap"]] += 1
                if res["humidSide"] >= min_side:
                    both += 1
                print(f'{r.seed}: side {res["side"]} at {res["corner"]}  gap {res["gap"]}  '
                      f'humidSide {res["humidSide"]} at {res["humidCorner"]}  '
                      f'humidChunks {res["humidChunks"]}  columns {res["columns"]}')
        print(f'\n== {n} seeds, {qualifying} with a >={min_side}x{min_side} no-rain square '
              f'({100.0 * qualifying / max(n, 1):.1f}%)', file=sys.stderr)
        print(f'== of those, {both} also have a >={min_side}x{min_side} HUMID square '
              f'({100.0 * both / max(n, 1):.1f}% of all seeds) — the discriminating variant',
              file=sys.stderr)
        print(f'== side histogram: {sorted(sides.items())}', file=sys.stderr)
        print(f'== gap histogram (qualifying only): {sorted(gaps.items())}', file=sys.stderr)
        if proxy:
            print(f'== WARNING: {proxy}/{n} seeds had no per-column biomeCounts; those used the '
                  f'chunk-centre column only (1 of 256) and OVERCOUNT no-rain squares', file=sys.stderr)
    elif cmd == "filter":
        expr = sys.argv[3]
        for r in load_dir(d):
            try:
                if eval(expr, {}, {"r": r}):
                    print(r.seed)
            except Exception as e:
                print(f"WARN {r.seed}: {e}", file=sys.stderr)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
