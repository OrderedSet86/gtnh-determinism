#!/usr/bin/env python3
"""Library + CLI for probe seed-search reports (probe.search=true JSONs).

Decodes GT ore m-values, aggregates chest loot, and evaluates filter predicates.

CLI:
  searchlib.py summary <report-dir>                 # per-seed one-liners + aggregate stats
  searchlib.py chests <report-dir> [item-substr]    # chest loot histogram (optionally filtered)
  searchlib.py filter <report-dir> <expr>           # python expr over `r` (SeedReport); prints passing seeds
     e.g. 'r.water(6) > 2000 and r.has_chest_item("gt.metaitem")'
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


class SeedReport:

    def __init__(self, path, mats=None):
        self.path = Path(path)
        d = json.load(open(path))
        self.seed = d["seed"]
        self.spawn = d.get("search", {}).get("spawn", [0, 0, 0])
        self.chunks = d.get("search", {}).get("chunks", {})
        self.villages = d.get("villages", [])
        self.witchery = d.get("witchery", [])
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


def load_dir(report_dir):
    report_dir = Path(report_dir)
    mats_file = report_dir / "gtmats.json"
    mats = json.load(open(mats_file)) if mats_file.exists() else {}
    for p in sorted(report_dir.glob("seed-*.json")):
        try:
            yield SeedReport(p, mats)
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
