#!/usr/bin/env python3
"""Which source-less jars actually touch the chunk populate RNG, and therefore need reading at all.

    triage-sourceless-jars.py <server-dir> <jarmap.json> [--enumerate JAR ...]

`map-jars-to-source.py` names the jars with no repo in `all-gtnh`. Most of them are irrelevant to
worldgen and reading any of them would be waste. This narrows that set to the ones whose bytecode
references a populate-stream hook, by searching each class file's constant pool for the marker
strings. A class that dispatches or subscribes to `PopulateChunkEvent` has that name in its pool as a
UTF8 constant, whether or not the mod is obfuscated, because Forge class names are not remapped.

This is a TRIAGE, not an audit. It answers "does this jar need source" and nothing else. Reading the
logic out of bytecode is a separate and much more expensive question, and for anything with an
upstream repo the answer is to clone the repo rather than to decompile.

False positives are expected and cheap: a class that merely imports the event type, or names it in a
`@Optional` stub, hits the same way. False negatives are the thing to worry about, and the one known
source is reflection-based subscription — a mod that registers a handler by string name would not
carry the constant. The runtime listener enumeration is what covers that gap; this scan does not.

`--enumerate` is the second half: for a jar that has NO upstream source at all, run javap over the
classes the triage flagged and pull out the same three facts `scan-populate-consumers.py` reads from
source — which terraingen event each `@SubscribeEvent` method takes, at what priority, and the weight
of each `registerWorldGenerator` call. Use it ONLY on jars the triage lists and `map-jars-to-source.py`
found no repo for. For this pack that is four: Thaumcraft, Witchery, ExtraUtilities and IC2.
"""
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

MARKERS = {
    b"terraingen/PopulateChunkEvent": "PopulateChunkEvent",
    b"terraingen/DecorateBiomeEvent": "DecorateBiomeEvent",
    b"terraingen/OreGenEvent": "OreGenEvent",
    b"terraingen/TerrainGen": "TerrainGen",
    b"common/IWorldGenerator": "IWorldGenerator",
    b"registerWorldGenerator": "registerWorldGenerator",
    # BiomeDecorator subclassing is the fourth way onto stream A and carries no event at all.
    b"biome/BiomeDecorator": "BiomeDecorator",
}


def scan(jar):
    hits = {}
    try:
        z = zipfile.ZipFile(jar)
    except (zipfile.BadZipFile, OSError) as e:
        return {"__error__": [str(e)]}
    with z:
        for name in z.namelist():
            if not name.endswith(".class"):
                continue
            try:
                blob = z.read(name)
            except (KeyError, OSError, RuntimeError):
                continue
            for marker, label in MARKERS.items():
                if marker in blob:
                    hits.setdefault(label, []).append(name[:-6].replace("/", "."))
    return hits


EVENT_PARAM_RE = re.compile(r"terraingen/(\w+)(?:\$(\w+))?;\)V")
# javap renders an annotation element as `priority=Lcpw/.../EventPriority.LOWEST`.
PRIORITY_RE = re.compile(r"EventPriority\.(\w+)")
SUBSCRIBE_MARK = "SubscribeEvent"


def javap(jar, cls, *flags):
    try:
        r = subprocess.run(
            ["javap", *flags, "-cp", str(jar), cls], capture_output=True, text=True, timeout=120
        )
    except (OSError, subprocess.TimeoutExpired) as e:
        return f"<javap failed: {e}>"
    return r.stdout or r.stderr


def enumerate_jar(jar):
    """The source-scan's three facts, read out of bytecode because there is no source to read."""
    hits = scan(jar)
    classes = sorted({c for cs in hits.values() for c in cs})
    for cls in classes:
        # -v carries the annotations and the method descriptors; -c carries the call sites. Ask for
        # both in one pass rather than javapping twice; these classes are few.
        text = javap(jar, cls, "-p", "-v", "-c")
        blocks = re.split(r"\n(?=  (?:public|private|protected|static|final|\s)*[\w.$\[\]<>]+ )", text)
        for b in blocks:
            if SUBSCRIBE_MARK in b:
                m = EVENT_PARAM_RE.search(b)
                if m:
                    ev = f"{m.group(1)}.{m.group(2)}" if m.group(2) else m.group(1)
                    pr = PRIORITY_RE.search(b)
                    # The declaration is the line carrying the parameter type, not the first line of
                    # the block — javap's leading lines are class-file metadata ("major version: 50").
                    sig = next(
                        (ln.strip() for ln in b.splitlines() if "terraingen" in ln and "(" in ln),
                        "",
                    )
                    print(f"    SUBSCRIBER  {ev:<30} {(pr.group(1) if pr else 'NORMAL'):<8} {cls}  {sig}")
        # The weight is the int pushed immediately before the invokestatic. bipush/sipush/ldc for a
        # real number, iconst_N for 0-5, and `ldc #n // int 2147483647` for Integer.MAX_VALUE.
        lines = text.splitlines()
        for i, ln in enumerate(lines):
            if "registerWorldGenerator" not in ln or "invokestatic" not in ln:
                continue
            weight = "?"
            for prev in reversed(lines[max(0, i - 4) : i]):
                w = re.search(r"(?:bipush|sipush)\s+(-?\d+)|iconst_(\d)|ldc\w*\s+#\d+\s+// int (-?\d+)", prev)
                if w:
                    weight = next(g for g in w.groups() if g is not None)
                    break
            print(f"    REGISTERS   weight={weight:<24} {cls}")


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    mods = Path(sys.argv[1]) / "mods"
    rows = json.loads(Path(sys.argv[2]).read_text())
    sourceless = [r["jar"] for r in rows if not r["repo"]]

    if "--enumerate" in sys.argv:
        wanted = sys.argv[sys.argv.index("--enumerate") + 1 :]
        for name in wanted:
            if name not in sourceless:
                sys.exit(f"{name} has source — read the repo, do not javap it")
            print(f"\n{name}")
            enumerate_jar(mods / name)
        return

    need, clean = [], []
    for jar in sourceless:
        hits = scan(mods / jar)
        (need if hits else clean).append((jar, hits))

    print(f"{len(sourceless)} source-less jars: {len(need)} touch a populate hook, {len(clean)} do not\n")
    for jar, hits in need:
        print(jar)
        for label, classes in sorted(hits.items()):
            shown = ", ".join(classes[:6]) + (f" (+{len(classes)-6})" if len(classes) > 6 else "")
            print(f"    {label:<22} {len(classes):>3}  {shown}")
        print()
    print("no populate hook, ignore:")
    print("  " + ", ".join(j for j, _ in clean))


if __name__ == "__main__":
    main()
