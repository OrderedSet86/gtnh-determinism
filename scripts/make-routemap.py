#!/usr/bin/env python3
"""Generate a complete routemap bundle for one seed, from a server dir to a viewable map.

    make-routemap.py --seed N --server DIR --java PATH --out WORLDS_DIR [--radius 60]
                     [--dims 0,7,-1] [--seedlib DIR] [--pack V] [--keep-scratch]

This is the single code path. The Windows/macOS/Linux launcher (routemap.py) is a front end over it,
and it is also what a developer should run instead of retyping the fifteen-argument bake command out
of a scratch PROVENANCE.md.

WHY COLD BOOTS. The probe has a warm daemon that would save three ~90 s boots, but a warm run has to
copy `World/` out from under a LIVE JVM between jobs, and on Windows Minecraft's RegionFileCache
holds RandomAccessFile handles open -- a copy that is free on Linux can fail outright there. Cold
boots let the JVM exit before anything is read, so the same code runs identically on all three
platforms. ~5 extra minutes on a ~25 minute run is worth one code path. `--warm` is available for
bulk development use on Linux; warm/cold parity is separately evidenced by scripts/matrix-driver.sh.

ORDER MATTERS. Every probe start does `rm -rf World`, so each dimension's region files must be
captured before the next boot begins. One boot per dimension, capture, repeat.

TRAPS THIS ENCODES, each of which has silently produced a wrong bundle before:

  * The run log is an INPUT, not just diagnostics. Without the gtnhdet.*trace lines that
    fullgen-loot-csv.py reads via --trace, every chest collapses to source `fullgen` and the
    Thaumcraft hilltop, barrow, vanilla-dungeon and meteorite POIs disappear.
  * vein-csv.py defaults to --spawn 0 0. Spawn is threaded from the prefilter record instead, or the
    baked per-vein distances are measured from the origin while the map draws rings at spawn.
  * --nether-portal-ratio must be the pack's real hodgepodge.cfg value or the Nether rings land ~183
    blocks off centre.
  * build_world_bundle.py is called ONCE with every dimension, so its carry-forward merge is never
    exercised and nobody has to hand-merge meta.json.
  * The prefilter's structure flags take an integer RADIUS, not a boolean; at their -1 default they
    emit zero structures and report no error. Likewise prefilter terrain must be >= 0 or spawn is
    never computed and the whole scoring window centres on the origin.
"""
import argparse
import json
import math
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent


def _resource(vendored, in_repo):
    """Prefer a copy vendored beside this script, else the developer checkout's own.

    The packaged build has no repo around it, so every helper and data file it shells out to is
    copied in next to the scripts. Checking the vendored path FIRST also means a packaged build never
    silently reaches into a checkout that happens to be nearby.
    """
    v = HERE / vendored
    return v if v.exists() else REPO / in_repo


SEEDSEARCH = _resource("seedsearch", "seedsearch")
VALUE_TABLE = _resource(
    "data/value-table.csv", "results/2026-08-30-chest-loot-radius60/value-table.csv"
)

DIM_NAMES = {0: "overworld", 7: "twilight forest", -1: "nether"}
DIM_REGION = {0: ("region",), 7: ("DIM7", "region"), -1: ("DIM-1", "region")}

# The fix jar's tracing flags. These are what turn an anonymous `fullgen` chest into one attributed to
# a hilltop circle, barrow, vanilla dungeon, mineshaft or AE2 meteorite, which is what the POI layer
# and the loot layer's `source` column are built from.
TRACE_FLAGS = (
    "chesttrace",
    "dungeontrace",
    "hilltoptrace",
    "meteortrace",
    "mineshafttrace",
    "moundtrace",
    "witchtrace",
)

# An .mca with only its 8 KiB header is an empty region file; anything at or below this generated
# nothing and must not be passed off as a rendered dimension.
EMPTY_REGION_BYTES = 8192


def log(msg):
    print(msg, flush=True)


def die(msg):
    raise SystemExit(f"error: {msg}")


def find_seedlib(explicit=None):
    """build_world_bundle.py lives in the sibling gtnh-seedlib checkout, or vendored beside us."""
    candidates = []
    if explicit:
        candidates.append(Path(explicit))
    # Vendored copy first, for the same reason _resource prefers it: a packaged build must not
    # silently bake against a checkout that happens to sit next to where it was unzipped.
    candidates += [HERE / "seedlib", REPO.parent / "gtnh-seedlib"]
    for c in candidates:
        if (c / "tools" / "build_world_bundle.py").is_file():
            return c
    die(
        "cannot find gtnh-seedlib (needs tools/build_world_bundle.py).\n"
        f"  Looked in: {', '.join(str(c) for c in candidates)}\n"
        "  Pass --seedlib, or clone it beside this repo."
    )


# The fix jar decides what worldgen does, so the map is only a map of the player's world if it is
# generated with the jar the player actually plays with. If their instance carries one, that is the
# authority and ours stays out of the way. The probe is the opposite: it is a measurement harness
# nobody plays with, so ours always wins.
PREFER_INSTANCE_JAR = {"gtnhdeterminism"}


def deploy_jars(server, jars):
    """Install the probe jar, and the fix jar only if the instance did not supply one.

    Replacing an existing copy matters because two jars with the same mod id fail in a way that
    reads like a pack problem. But blindly replacing the FIX jar was worse: a player running a
    newer build than the last release had it silently downgraded to the release, which on
    2.9.0-beta-1 swapped a working GtOreShape build for one that crashes at init.
    """
    mods = server / "mods"
    for jar in jars:
        stem = re.split(r"-\d|-v\d", jar.name)[0]
        existing = sorted(mods.glob(f"{stem}*.jar"))
        if existing and stem in PREFER_INSTANCE_JAR:
            log(f"  using your own {existing[0].name} rather than the bundled {jar.name}")
            for extra in existing[1:]:
                extra.unlink()
            continue
        for old in existing:
            old.unlink()
        shutil.copy2(jar, mods / jar.name)
        log(f"  deployed {jar.name}")


def write_server_properties(server, seed, port):
    (server / "server.properties").write_text(
        "allow-nether=true\n"
        "level-name=World\n"
        f"level-seed={seed}\n"
        "level-type=rwg\n"
        "online-mode=false\n"
        "snooper-enabled=false\n"
        "max-tick-time=-1\n"
        f"server-port={port}\n"
        # Bind to loopback: this is a measurement server, and binding the wildcard address both
        # raises a Windows firewall dialog and briefly exposes a Minecraft server on the player's LAN.
        "server-ip=127.0.0.1\n"
        "motd=routemap\n",
        encoding="utf-8",
    )
    (server / "eula.txt").write_text("eula=true\n", encoding="utf-8")


def launch_jar(server):
    for name in ("lwjgl3ify-forgePatches.jar",):
        if (server / name).is_file():
            return name
    forge = sorted(server.glob("forge-*.jar"))
    if forge:
        return forge[0].name
    die(f"no launch jar in {server}")


def free_port(preferred):
    """A port nothing else holds.

    A stale JVM from an interrupted run keeps its port for a while, and Minecraft's response to a
    bind failure is to log a warning and shut down quietly -- which surfaces as "the probe wrote no
    output" rather than "the port was busy", and sends you looking at the wrong thing entirely.
    """
    import socket

    for port in range(preferred, preferred + 20):
        with socket.socket() as s:
            try:
                s.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    die(f"no free port in {preferred}..{preferred + 19}")


# Forge naming the mod whose construction threw. Mods properly declared clientSideOnly are simply
# skipped on a server, so the ones that reach here are the ones that touch client classes without
# saying so.
MOD_CRASH = re.compile(r"Caught exception from (?P<name>.+?) \((?P<modid>[\w.\-]+)\)")
# [mixin]: Mixin apply for mod <mod> failed <config>:<mixin> from mod <mod> -> <target class>: ...
MIXIN_FAIL = re.compile(
    r"Mixin apply for mod (?P<mod>\S+) failed \S+:(?P<mixin>\S+) from mod \S+ -> (?P<target>[\w.$]+)"
)
CLIENT_ONLY = re.compile(
    r"invalid side SERVER|net[./]minecraft[./]client|NoClassDefFoundError: net/minecraft/client"
)


def jar_for_modid(mods, modid):
    """Which jar declares this mod id, read from its own mcmod.info."""
    for jar in sorted(mods.glob("*.jar")):
        try:
            with zipfile.ZipFile(jar) as z:
                if "mcmod.info" not in z.namelist():
                    continue
                raw = z.read("mcmod.info").decode("utf-8", errors="replace")
        except (zipfile.BadZipFile, OSError):
            continue
        if re.search(r'"modid"\s*:\s*"' + re.escape(modid) + r'"', raw):
            return jar
    return None


def excluded_dir(server):
    return server / "mods-excluded-client-only"


def drop_client_only_mod(server, logfile):
    """If a boot died inside one mod because it is really client-side, set that jar aside.

    Keeping unrecognised mods is right -- they are part of the world the player actually plays -- but
    an undeclared client-only one takes the server down with it. Making the player edit their
    instance to work around that is not acceptable, so handle it here and say what was done.
    Returns the jar moved, or None if the failure was something else.
    """
    text = logfile.read_text(errors="replace")
    m = MOD_CRASH.search(text)
    if not m:
        return None
    # Only act when the crash actually looks client-side; anything else is a real bug to surface.
    window = text[max(0, m.start() - 2000) : m.end() + 4000]
    if not CLIENT_ONLY.search(window):
        return None
    jar = jar_for_modid(server / "mods", m.group("modid"))
    if jar is None:
        return None
    dest = excluded_dir(server)
    dest.mkdir(exist_ok=True)
    jar.rename(dest / jar.name)
    log(f"    {jar.name} ({m.group('name')}) is client-only and crashed the server; excluded")
    return jar


def run_java(server, java, props, logfile, xmx="6G", _attempt=0):
    """One cold probe boot. Returns the log path; raises if the JVM fails."""
    cmd = [str(java)]
    if (server / "java9args.txt").is_file():
        cmd.append("@java9args.txt")
    cmd += [f"-Xmx{xmx}", f"-Xms{xmx}"]
    cmd += [f"-D{k}={v}" for k, v in props.items()]
    cmd += ["-Dfml.readTimeout=180", "-Dfml.queryResult=confirm"]
    cmd += ["-jar", launch_jar(server), "nogui"]
    t0 = time.time()
    proc = None
    try:
        with open(logfile, "wb") as out:
            proc = subprocess.Popen(
                cmd, cwd=str(server), stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT
            )
            rc = proc.wait()
    except KeyboardInterrupt:
        # Without this the JVM outlives the interrupt, keeps its port, and the next run fails to bind.
        if proc and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                proc.kill()
        raise
    text = logfile.read_text(errors="replace")

    # A mixin that cannot bind takes its target class down with it, and the crash surfaces on
    # whichever unrelated mod touches that class next -- a real report showed gtnhdeterminism's
    # ore-layer mixin failing against a GregTech it did not match, then visualprospecting dying with
    # NoClassDefFoundError. The generic "the probe wrote no output" hid all of that, so name it.
    # Searched over the whole log, not the tail: the FATAL lands during mod init, long before the end.
    mixin_fail = MIXIN_FAIL.search(text)
    if mixin_fail:
        die(
            f"a mixin from {mixin_fail.group('mod')} could not be applied, which stops the server "
            "booting:\n"
            f"    mixin  {mixin_fail.group('mixin')}\n"
            f"    target {mixin_fail.group('target')}\n"
            "  That combination of jar and pack version does not fit: the mixin expects a shape of\n"
            "  the target class this pack does not have. Update that mod, or use the jar built for\n"
            f"  this pack version. Full detail in {logfile}"
        )

    tail = text[-8000:]
    if "FAILED TO BIND TO PORT" in tail:
        die(
            f"the server could not bind its port -- another probe run is probably still going.\n"
            f"  Wait for it to finish, or pass --port. See {logfile}"
        )
    if rc != 0:
        # Before giving up, see whether one undeclared client-only mod took the boot down. Retry
        # without it rather than telling the player to go edit their instance.
        if _attempt < 3 and drop_client_only_mod(server, logfile):
            return run_java(server, java, props, logfile, xmx, _attempt + 1)
        die(f"probe JVM exited {rc}; see {logfile}")
    log(f"    ({time.time() - t0:.0f}s)")
    return logfile


def probe_dimension(server, java, seed, dim, radius, scratch, trace=True):
    """Generate one dimension, then capture everything before the next boot wipes World/."""
    # Each dimension gets its own directory because the probe writes its sidecars (biomes.json,
    # gtmats.json) next to probe.out, once per PROCESS. Sharing one directory let the Nether run --
    # the last one -- overwrite biomes.json with a table built from the Nether's chunk manager, whose
    # rwgRivers and rwgBuckets are empty. The builder then silently fell back to a hard-coded
    # 2.8.4-era file and painted this world's rivers from a different pack version.
    captured = scratch / f"dim{dim}"
    captured.mkdir(parents=True, exist_ok=True)
    out = captured / "search.json"
    logfile = captured / "probe.log"
    props = {
        "probe.order": "rows",
        "probe.radius": str(radius),
        "probe.out": str(out),
        "probe.search": "true",
        "probe.dim": str(dim),
    }
    if dim == 7:
        # Independent of probe.dim and generates nothing, but needs dim 7 to exist.
        props["probe.tffeatures"] = "4"
    if trace:
        for flag in TRACE_FLAGS:
            props[f"gtnhdet.{flag}"] = "true"

    shutil.rmtree(server / "World", ignore_errors=True)
    log(f"  dim {dim} ({DIM_NAMES.get(dim, '?')}): generating radius {radius}")
    run_java(server, java, props, logfile)

    if not out.is_file():
        die(f"dim {dim} produced no report at {out}; see {logfile}")

    src_region = server.joinpath("World", *DIM_REGION[dim])
    if not src_region.is_dir():
        die(f"dim {dim} left no region directory at {src_region}; see {logfile}")
    dst_region = captured / "region"
    shutil.rmtree(dst_region, ignore_errors=True)
    shutil.copytree(src_region, dst_region)

    real = [p for p in dst_region.glob("*.mca") if p.stat().st_size > EMPTY_REGION_BYTES]
    if not real:
        die(
            f"dim {dim} captured {len(list(dst_region.glob('*.mca')))} region files but none exceed "
            f"{EMPTY_REGION_BYTES} bytes -- the world generated nothing. See {logfile}"
        )
    log(f"    captured {len(real)} region files")

    if dim == 0:
        shutil.copy2(server / "World" / "level.dat", captured / "level.dat")
    return {"search": out, "log": logfile, "region": dst_region, "dir": captured}


def run_prefilter(server, java, seed, radius, scratch):
    """Stage-0 sweep: the POI layer, and the spawn point everything else is measured from."""
    out = scratch / "prefilter.jsonl"
    logfile = scratch / "prefilter.log"
    props = {
        "probe.prefilter": str(seed),
        "probe.prefilter.out": str(out),
        # Village cell scan radius is around the ORIGIN and must exceed the scoring radius, because
        # spawn is offset from it.
        "probe.prefilter.radius": str(radius + 10),
        "probe.prefilter.pieces": "true",
        # Must be >= 0 or Prefilter never computes spawn and silently centres everything on (0,0).
        "probe.prefilter.terrain": "4",
        "probe.prefilter.chests": "true",
        "probe.prefilter.villagechests": "true",
        # Integer radii, not booleans. At the -1 default these emit nothing and report no error.
        #
        # DO NOT narrow these to `radius` to "clip them to the map". Measured: witchery at 60 emits
        # ZERO cells against 148 at 120. These are SEARCH windows over a sparse candidate grid
        # (WitcheryPrefilter.candidates tests isCandidate per chunk and almost all fail), not display
        # filters -- shrinking the window does not trim the layer, it loses it. For reference the
        # committed bundle carries 168 witchery cells to this pipeline's 148, so 2x is if anything
        # already conservative. Veins are the opposite case and genuinely do need clipping, because
        # vein-csv filters an already-complete list by Euclidean distance.
        "probe.prefilter.dungeon": str(radius),
        "probe.prefilter.stronghold": str(radius * 2),
        "probe.prefilter.witchery": str(radius * 2),
        "probe.prefilter.chunkcache": "2048",
    }
    log("  stage-0 prefilter (POIs, spawn)")
    shutil.rmtree(server / "World", ignore_errors=True)
    run_java(server, java, props, logfile)
    if not out.is_file():
        die(f"prefilter wrote nothing to {out}; see {logfile}")

    record = None
    for line in out.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if row.get("seed") == seed:
            record = row
            break
    if record is None:
        die(f"prefilter produced no record for seed {seed} in {out}")
    if record.get("kill"):
        die(f"prefilter killed seed {seed}: {record['kill']} (a gate is set that should not be)")
    spawn = record.get("spawn")
    if not spawn:
        die("prefilter record carries no spawn; probe.prefilter.terrain must be >= 0")
    log(f"    spawn {spawn[0]}, {spawn[2]}")
    return {"jsonl": out, "spawn": (int(spawn[0]), int(spawn[2])), "record": record}


def run_py(script, *args, cwd=None):
    """Run a helper script, returning stdout and stderr together.

    build_world_bundle.py logs its whole progress -- including the per-dimension "N% of surface from
    the palette" line that is the only warning of a level.dat/region mismatch -- to STDERR. Capturing
    only stdout threw that away and made a degraded bake look like a silent success.
    """
    cmd = [sys.executable, str(script)] + [str(a) for a in args]
    r = subprocess.run(
        cmd, cwd=cwd and str(cwd), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
    )
    if r.returncode != 0:
        die(f"{Path(script).name} failed:\n{r.stdout}")
    return r.stdout


def nether_portal_ratio(server):
    """GTNH exposes the ratio in hodgepodge.cfg; vanilla 8.0 is only the default."""
    cfg = server / "config" / "hodgepodge.cfg"
    if cfg.is_file():
        m = re.search(r"^\s*[DIS]:netherPortalRatio=([\d.]+)", cfg.read_text(errors="replace"), re.M)
        if m:
            return float(m.group(1))
    return 8.0


# Below this, the block palette did not recognise a large share of the surface, which in practice
# means level.dat's FML registry does not match the pack that wrote the region files. The reference
# overworld bake reports 99.67%.
MIN_PALETTE_PCT = 95.0
PALETTE_LINE = re.compile(r"dim(-?\d+) blocks:.*?([\d.]+)% of surface from the palette")


def summarise(bundle, dims, radius, bake_output=""):
    """Report what was actually produced, and refuse to pass off a degraded bundle as a good one."""
    meta = json.loads((bundle / "meta.json").read_text())
    problems = []

    coverage = {int(d): float(p) for d, p in PALETTE_LINE.findall(bake_output)}
    for dim, pct in sorted(coverage.items()):
        log(f"  dim {dim} palette coverage: {pct}%")
        if pct < MIN_PALETTE_PCT:
            problems.append(
                f"dim {dim} resolved only {pct}% of its surface from the block palette "
                f"(expected >{MIN_PALETTE_PCT}%) -- level.dat does not match the region files"
            )
    log("")
    log(f"bundle: {bundle}")
    log(f"  pack {meta.get('pack')}  seed {meta.get('seed')}  spawn {meta.get('spawn')}")
    for dim in dims:
        entry = meta.get("dims", {}).get(str(dim))
        if not entry:
            problems.append(f"dim {dim} is missing from meta.json entirely")
            continue
        bases = ", ".join(b["key"] for b in entry.get("bases", []))
        veins = bundle / f"dim{dim}" / "veins.json"
        n_veins = len(json.loads(veins.read_text()).get("veins", [])) if veins.is_file() else 0
        log(f"  dim {dim}: bases [{bases}]  chunks {entry.get('chunks', '?')}  veins {n_veins}")
        if not bases:
            problems.append(f"dim {dim} carries no base render")
    loot = bundle / "dim0" / "loot.json"
    if loot.is_file():
        d = json.loads(loot.read_text())
        chests = d.get("chests", [])
        log(f"  loot: {len(chests)} chests, {len(d.get('items', {}))} distinct items")
        srcs = d.get("sources") or {}
        if srcs and set(srcs) <= {"fullgen"}:
            problems.append(
                "every chest has source `fullgen` -- the trace flags did not reach the run log, so "
                "hilltop/barrow/dungeon/meteorite POIs are missing"
            )
        # A handful of chunks genuinely can hold no chests, so this is only a signal at a radius
        # anyone would actually route on. The reference radius-60 bundle carries ~1700.
        if not chests and radius >= 20:
            problems.append(
                f"no chests at radius {radius} -- the loot CSV or its --trace log did not reach the "
                "builder, since a window this size is never empty"
            )
    else:
        problems.append("no dim0/loot.json was produced")
    pois = bundle / "dim0" / "pois.json"
    if pois.is_file():
        p = json.loads(pois.read_text())
        kinds = {k: len(v) for k, v in p.items() if isinstance(v, list) and v}
        log(f"  POIs: {', '.join(f'{k} {n}' for k, n in sorted(kinds.items()))}")
    else:
        problems.append("no dim0/pois.json was produced")
    return problems


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seed", type=int, required=True)
    ap.add_argument("--server", type=Path, required=True, help="assembled server dir (routemap-bootstrap.py)")
    ap.add_argument("--java", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True, help="worlds/ directory; the bundle lands in <out>/<seed>")
    ap.add_argument("--radius", type=int, default=60, help="chunk radius per dimension")
    ap.add_argument("--dims", default="0,7,-1")
    ap.add_argument("--seedlib", help="gtnh-seedlib checkout (default: sibling of this repo)")
    ap.add_argument("--pack", help="pack version recorded in meta.json (default: read from the server stamp)")
    ap.add_argument("--probe-jar", type=Path, help="worldgenprobe jar to deploy")
    ap.add_argument("--fix-jar", type=Path, help="gtnhdeterminism jar to deploy")
    ap.add_argument("--amidst", type=Path, help="AmidstGTNH biome/default.json, for real biome colours")
    ap.add_argument("--mc-jar", type=Path, help="1.7.10 client jar, for the grass/foliage colormaps")
    ap.add_argument("--scratch", type=Path, help="working directory (default: <out>/.scratch-<seed>)")
    ap.add_argument("--keep-scratch", action="store_true")
    ap.add_argument("--port", type=int, default=25599)
    args = ap.parse_args()

    server = args.server.resolve()
    if not (server / "mods").is_dir():
        die(f"{server} does not look like a server directory (no mods/)")
    seedlib = find_seedlib(args.seedlib)
    builder = seedlib / "tools" / "build_world_bundle.py"
    dims = [int(d) for d in args.dims.split(",") if d.strip()]
    if 0 not in dims:
        # level.dat, the loot CSV and the trace log all come from the overworld run; without it there
        # is no bundle to speak of, only orphan vein layers.
        die("--dims must include 0: the overworld run supplies level.dat, the loot table and the trace log")

    pack = args.pack
    stamp = server / ".routemap-assembled.json"
    if not pack and stamp.is_file():
        pack = json.loads(stamp.read_text()).get("pack_version")
    if not pack:
        die("no pack version: pass --pack, or use a server built by routemap-bootstrap.py")

    # Default beside the bundle only when nothing better was given; callers that write bundles
    # somewhere space-sensitive (the launcher writes into the Prism instance) pass --scratch.
    scratch = (args.scratch or args.out / f".scratch-{args.seed}").resolve()
    scratch.mkdir(parents=True, exist_ok=True)
    bundle = (args.out / str(args.seed)).resolve()

    log(f"seed {args.seed}  pack {pack}  radius {args.radius}  dims {dims}")
    jars = [j for j in (args.probe_jar, args.fix_jar) if j]
    if jars:
        deploy_jars(server, jars)
    port = free_port(args.port)
    if port != args.port:
        log(f"  port {args.port} is busy; using {port}")
    write_server_properties(server, args.seed, port)

    pre = run_prefilter(server, args.java, args.seed, args.radius, scratch)
    results = {d: probe_dimension(server, args.java, args.seed, d, args.radius, scratch) for d in dims}

    log("  deriving vein and loot tables")
    sx, sz = pre["spawn"]
    vein_csvs = {}
    for dim in dims:
        cache = Path(str(results[dim]["search"]) + ".veincache.json")
        if not cache.is_file():
            log(f"    dim {dim}: no veincache sidecar, skipping veins")
            continue
        prefix = scratch / f"veins-dim{dim}"
        run_py(
            SEEDSEARCH / "vein-csv.py",
            cache,
            "--dim", dim,
            "--seed", args.seed,
            "--spawn", sx, sz,
            # The probe generates a SQUARE of `radius` chunks but vein-csv filters on Euclidean
            # distance, so the radius has to reach the square's corners -- 60 would silently drop
            # the diagonals, and 3x (the first guess here) kept 506 dim-7 veins that fall outside
            # the rendered base map entirely and would draw off the edge of it.
            "--radius", math.ceil(args.radius * math.sqrt(2)),
            "--out-prefix", prefix,
        )
        csv = Path(f"{prefix}.csv")
        if csv.is_file():
            vein_csvs[dim] = csv

    loot_csv = scratch / "loot.csv"
    run_py(
        SEEDSEARCH / "fullgen-loot-csv.py",
        VALUE_TABLE,
        results[0]["search"],
        "-o", loot_csv,
        "--radius", args.radius,
        "--trace", results[0]["log"],
        "--prefilter", pre["jsonl"],
    )

    # From the OVERWORLD run specifically: the sidecar's rwgRivers and rwgBuckets come from the live
    # chunk manager, so only the RWG overworld produces a populated one. Passing it explicitly matters
    # because the builder otherwise falls back to a hard-coded sibling path and bakes a stale palette
    # without complaint.
    biomes = results[0]["dir"] / "biomes.json"
    if biomes.is_file():
        rivers = len(json.loads(biomes.read_text()).get("rwgRivers") or [])
        if not rivers:
            log("    warning: the overworld biomes.json carries no rwgRivers; river colours will be")
            log("             taken from whatever fallback table the builder can find")

    log("  baking the bundle")
    cmd = [
        "--seed", args.seed, "--pack", pack,
        "--prefilter", pre["jsonl"],
        "--trace", results[0]["log"],
        "--loot-csv", loot_csv,
        "--value-table", VALUE_TABLE,
        "--level-dat", results[0]["dir"] / "level.dat",
        "--nether-portal-ratio", nether_portal_ratio(server),
        "--out", bundle,
    ]
    if biomes.is_file():
        cmd += ["--biomes", biomes]
    # Without these two the palette still renders, but 136 of 215 biome colours fall back to
    # synthesised ones and grass/foliage lose their vanilla tints -- a map that looks wrong rather
    # than a map that fails, which is the harder kind of problem to notice.
    amidst = args.amidst or next(
        (p for p in (HERE / "data" / "amidst-biomes.json",
                     REPO.parent / "all-gtnh" / "AmidstGTNH" / "src" / "main" / "resources"
                     / "assets" / "forgeamidst" / "biome" / "default.json") if p.is_file()),
        None,
    )
    if amidst:
        cmd += ["--amidst", amidst]
    else:
        log("    note: no AmidstGTNH biome table; biome colours will be synthesised")
    if args.mc_jar and args.mc_jar.is_file():
        cmd += ["--mc-jar", args.mc_jar]
    else:
        # Without the vanilla colormaps every grass and foliage block renders untinted, which comes
        # out stone grey instead of green. The map still works and nothing errors, so this has to be
        # said out loud or it reads as "that is just how the map looks".
        log("    note: no Minecraft client jar; grass and foliage will render grey, not green")
    for dim, flag in ((0, "ow"), (7, "tf"), (-1, "nether")):
        if dim in results:
            cmd += [f"--{flag}-search", results[dim]["search"], f"--{flag}-region", results[dim]["region"]]
        if dim in vein_csvs:
            cmd += [f"--veins-{flag}", vein_csvs[dim]]
    bake_output = run_py(builder, *cmd, cwd=seedlib)
    log(bake_output.rstrip())

    problems = summarise(bundle, dims, args.radius, bake_output)
    if not args.keep_scratch:
        shutil.rmtree(scratch, ignore_errors=True)
    if problems:
        log("")
        for p in problems:
            log(f"  PROBLEM: {p}")
        die(f"{len(problems)} problem(s): the bundle exists but is degraded, see above")
    log("")
    log("done")


if __name__ == "__main__":
    main()
