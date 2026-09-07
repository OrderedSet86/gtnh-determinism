#!/usr/bin/env python3
"""Enumerate, from SOURCE, everything hooked to a chunk's populate RNG in a given pack.

    scan-populate-consumers.py <jarmap.json> [--src DIR] [--json OUT] [--repos OUT]

Input is `map-jars-to-source.py`'s output, so the scan covers exactly the repos whose jars the pack
ships. That restriction is the point: `all-gtnh` holds 300+ repos and most of the interesting-looking
worldgen hooks in it (Realistic-Terrain-Generation, CubicChunks1710, The-Aether-GTNH) belong to mods
this pack does not install. A grep over the whole tree reports consumers that cannot fire.

FOUR WAYS ONTO THE POPULATE STREAM, and a scan that knows only the first is the usual mistake:

  1. `@SubscribeEvent` on a terraingen event. Three busses carry these and they are NOT
     interchangeable — `MinecraftForge.EVENT_BUS` takes `PopulateChunkEvent.Pre/Post` and
     `DecorateBiomeEvent.Pre/Post`, `TERRAIN_GEN_BUS` takes `PopulateChunkEvent.Populate` and
     `DecorateBiomeEvent.Decorate`, `ORE_GEN_BUS` takes `OreGenEvent.*`.
  2. `IWorldGenerator` via `GameRegistry.registerWorldGenerator`. This is a DIFFERENT Random:
     `GameRegistry.generateWorld` calls `fmlRandom.setSeed(chunkSeed)` before every generator, so
     these are insulated from each other's draw counts. Recorded here with the registration weight,
     because equal weights tie-break in identity-hash order.
  3. `BiomeDecorator` subclasses, which decorate off the populate Random with no event at all.
  4. Posters — code that itself calls `TerrainGen.populate/decorate/generateOre` or posts to one of
     the busses. These are the dispatch nodes, and consumers post events too (Railcraft's
     QuarryPopulator re-posts from inside its own handler), so the result is a graph.

The output is an ENUMERATION, not a verdict. Whether a consumer can desync by chunk load order is a
reading question — does it draw a variable number of times behind a live world read — and this
script deliberately does not guess at it.

Regex over Java, not a parser: the patterns are distinctive enough and a real parser is not worth the
dependency. The known blind spot is a handler registered reflectively or by a name string, which
carries no `@SubscribeEvent` at a readable site; the runtime listener enumeration is what covers that.

Scala counts. MrTJPCore registers `object SimpleGenHandler extends IWorldGenerator` from a `.scala`
file, and an earlier version of this scan that globbed only `*.java` reported 34 registrations where
the running game had 30 generators including that one — a miss found by the runtime dump, not by the
scan. So `.scala` is read too, and the interface test accepts Scala's `extends`/`with` as well as
Java's `implements`.
"""
import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

EVENTS = ("PopulateChunkEvent", "DecorateBiomeEvent", "OreGenEvent")

# Which bus a given event type is posted on. Getting this wrong makes an enumeration look complete
# while missing a third of it, so it is a table rather than an assumption.
BUS = {
    "PopulateChunkEvent": "EVENT_BUS",
    "PopulateChunkEvent.Pre": "EVENT_BUS",
    "PopulateChunkEvent.Post": "EVENT_BUS",
    "PopulateChunkEvent.Populate": "TERRAIN_GEN_BUS",
    "DecorateBiomeEvent": "EVENT_BUS",
    "DecorateBiomeEvent.Pre": "EVENT_BUS",
    "DecorateBiomeEvent.Post": "EVENT_BUS",
    "DecorateBiomeEvent.Decorate": "TERRAIN_GEN_BUS",
    "OreGenEvent": "ORE_GEN_BUS",
    "OreGenEvent.Pre": "ORE_GEN_BUS",
    "OreGenEvent.Post": "ORE_GEN_BUS",
    "OreGenEvent.GenerateMinable": "ORE_GEN_BUS",
}

SUBSCRIBE_RE = re.compile(
    r"@SubscribeEvent\s*(?:\(([^)]*)\))?\s*"
    r"(?:@\w+(?:\([^)]*\))?\s*)*"          # further annotations between @SubscribeEvent and the method
    r"(?:public|private|protected|static|final|synchronized|\s)*"
    r"\w[\w.<>\[\]]*\s+(\w+)\s*\(\s*"      # return type, method name, open paren
    r"(?:final\s+)?([\w.$]+)",             # first parameter's type
    re.S,
)
PRIORITY_RE = re.compile(r"EventPriority\.(\w+)")
# Terminator is `;` in Java and a bare newline in Scala — anchoring on `;` alone silently drops every
# Scala registration, which is how MrTJPCore's SimpleGenHandler went missing.
REGISTER_RE = re.compile(r"registerWorldGenerator\s*\(\s*(.+?)\s*\)\s*(?:;|\r?\n)", re.S)
# Java `class X ... implements A, IWorldGenerator` and Scala `object X extends IWorldGenerator`.
IMPLEMENTS_RE = re.compile(
    r"\b(?:class|enum|object|trait)\s+(\w+)[^{]*?\b(?:implements|extends|with)\b([^{]*)\{", re.S
)
EXTENDS_DECORATOR_RE = re.compile(r"\b(?:class)\s+(\w+)[^{]*\bextends\s+(\w*BiomeDecorator\w*)\b")
POSTER_RE = re.compile(
    r"\bTerrainGen\.(populate|decorate|generateOre)\s*\(|"
    r"\b(EVENT_BUS|TERRAIN_GEN_BUS|ORE_GEN_BUS)\.post\s*\(\s*new\s+(\w+(?:\.\w+)*)"
)


def qualify(param, text):
    """Turn a bare parameter type into `Outer.Inner`, using the file's imports when it is unqualified."""
    p = param.split(".")[-1] if param.startswith("net.") or param.startswith("cpw.") else param
    if "." in p:
        return p
    for ev in EVENTS:
        # `void onPopulate(Pre e)` after `import ...PopulateChunkEvent.Pre;`
        if re.search(rf"import\s+[\w.]*{ev}\.{re.escape(p)}\s*;", text):
            return f"{ev}.{p}"
    return p


def java_files(repo):
    for sub in ("src/main/java", "src/main/scala", "src/mixin/java", "src/api/java"):
        d = repo / sub
        if d.is_dir():
            yield from d.rglob("*.java")
            yield from d.rglob("*.scala")
    # Some repos keep a flat `src/` or a non-standard layout; fall back only if the standard one
    # produced nothing, so the common case does not pay for a whole-repo walk.


def scan_repo(repo):
    out = {"subscribers": [], "generators": [], "registrations": [], "decorators": [], "posters": []}
    files = list(java_files(repo))
    if not files:
        files = [
            p
            for pat in ("*.java", "*.scala")
            for p in repo.rglob(pat)
            if "/build/" not in str(p) and "/test" not in str(p)
        ]
    for f in files:
        try:
            text = f.read_text(errors="replace")
        except OSError:
            continue
        if not any(
            k in text
            for k in (*EVENTS, "IWorldGenerator", "registerWorldGenerator", "BiomeDecorator", "TerrainGen")
        ):
            continue
        rel = str(f.relative_to(repo))

        for m in SUBSCRIBE_RE.finditer(text):
            ann, method, param = m.group(1) or "", m.group(2), m.group(3)
            ev = qualify(param, text)
            if not any(ev.startswith(e) or ev.split(".")[0] == e for e in EVENTS):
                continue
            pr = PRIORITY_RE.search(ann)
            out["subscribers"].append(
                {
                    "file": rel,
                    "method": method,
                    "event": ev,
                    "bus": BUS.get(ev, "?"),
                    "priority": pr.group(1) if pr else "NORMAL",
                    "line": text.count("\n", 0, m.start()) + 1,
                }
            )

        for m in IMPLEMENTS_RE.finditer(text):
            if re.search(r"\bIWorldGenerator\b", m.group(2)):
                out["generators"].append(
                    {"file": rel, "class": m.group(1), "line": text.count("\n", 0, m.start()) + 1}
                )

        for m in REGISTER_RE.finditer(text):
            args = " ".join(m.group(1).split())
            # Weight is the last top-level argument. Splitting on commas is wrong in general
            # (`new Foo(a, b), 4`), so take the tail after the final comma that is not inside
            # brackets, which is enough for every real call site in this pack.
            depth, cut = 0, None
            for i, ch in enumerate(args):
                if ch in "([<":
                    depth += 1
                elif ch in ")]>":
                    depth -= 1
                elif ch == "," and depth == 0:
                    cut = i
            out["registrations"].append(
                {
                    "file": rel,
                    "generator": args[:cut].strip() if cut else args,
                    "weight": args[cut + 1 :].strip() if cut else None,
                    "line": text.count("\n", 0, m.start()) + 1,
                }
            )

        for m in EXTENDS_DECORATOR_RE.finditer(text):
            out["decorators"].append(
                {
                    "file": rel,
                    "class": m.group(1),
                    "extends": m.group(2),
                    "line": text.count("\n", 0, m.start()) + 1,
                }
            )

        posts = set()
        for m in POSTER_RE.finditer(text):
            posts.add(m.group(1) or m.group(3))
        if posts:
            out["posters"].append({"file": rel, "posts": sorted(posts)})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jarmap")
    ap.add_argument("--src", default=str(Path(__file__).resolve().parent.parent.parent / "all-gtnh"))
    ap.add_argument("--json")
    ap.add_argument("--repos", help="write the list of repos that hit, one per line (feeds --only)")
    args = ap.parse_args()

    src = Path(args.src)
    rows = json.loads(Path(args.jarmap).read_text())
    repos = sorted({r["repo"] for r in rows if r["repo"]})
    if not repos:
        sys.exit("jarmap names no matched repos")

    results, hit_repos = {}, []
    for name in repos:
        repo = src / name
        if not repo.is_dir():
            continue
        r = scan_repo(repo)
        if any(r.values()):
            results[name] = r
            hit_repos.append(name)

    totals = defaultdict(int)
    for r in results.values():
        for k, v in r.items():
            totals[k] += len(v)
    print(f"{len(repos)} shipped repos scanned, {len(results)} hit\n")
    print(
        "  ".join(f"{k}={totals[k]}" for k in ("subscribers", "generators", "registrations", "decorators", "posters"))
    )

    print("\n--- stream A: event subscribers (share the chunk's populate Random) ---")
    for name in sorted(results):
        for s in results[name]["subscribers"]:
            print(
                f"  {name:<28} {s['bus']:<16} {s['event']:<30} {s['priority']:<8} "
                f"{s['file']}:{s['line']} {s['method']}()"
            )

    print("\n--- stream A: BiomeDecorator subclasses (no event, same Random) ---")
    for name in sorted(results):
        for d in results[name]["decorators"]:
            print(f"  {name:<28} {d['class']} extends {d['extends']}  {d['file']}:{d['line']}")

    print("\n--- stream B: registerWorldGenerator (own Random, reseeded per generator) ---")
    for name in sorted(results):
        for g in results[name]["registrations"]:
            print(f"  {name:<28} weight={str(g['weight']):<20} {g['generator'][:52]}  {g['file']}:{g['line']}")

    if args.repos:
        Path(args.repos).write_text("\n".join(hit_repos) + "\n")
        print(f"\nwrote {args.repos} ({len(hit_repos)} repos)")
    if args.json:
        Path(args.json).write_text(json.dumps(results, indent=2))
        print(f"wrote {args.json}")


if __name__ == "__main__":
    main()
