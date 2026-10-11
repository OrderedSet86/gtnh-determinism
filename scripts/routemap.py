#!/usr/bin/env python3
"""Type a seed, get a route map. The one entry point, on Windows, macOS and Linux alike.

    routemap.py                      # pick an instance and a seed in a window
    routemap.py --seed N [--instance NAME] [--radius 60] [--dims 0,7,-1]

Run with no arguments it opens a small window; run with arguments it behaves as a CLI. Both call the
same two modules -- routemap-bootstrap.py to turn the player's Prism instance into a headless server,
then make-routemap.py to generate the bundle -- so there is no separate "shipped" path that can drift
from the one a developer uses.

The player installs nothing. Java comes from Prism's own bundled runtime, the mod jars come from
their own instance, and on Windows the interpreter ships beside this file as python.org's embeddable
build. That last part is why this is NOT frozen with PyInstaller: a one-file bootloader unpacks to
temp and executes, which is what a malware dropper does and what antivirus heuristics fire on.
Nothing here is a packed binary.

PREFLIGHT IS THE POINT. A run costs half an hour, so every condition that can kill it is checked
before the first JVM starts rather than discovered twenty minutes in.
"""
import argparse
import ctypes
import http.server
import importlib.util
import json
import platform
import shutil
import socketserver
import sys
import threading
import webbrowser
from pathlib import Path

HERE = Path(__file__).resolve().parent
PORT = 8502
# Derived from the heap the probe actually asks for, not a round number. Requiring a flat 8 GB
# *available* refused a 12 GB machine that had 7.9 GB free, and would refuse every 8 GB machine
# outright, since a running OS never leaves all of it available.
HEAP_GB = 6
NEED_RAM_GB = HEAP_GB + 1
# Measured peak, all transient except the last two: 627 MB assembled server (490 mods, 88 config,
# 30 libraries), ~538 MB scratch holding three dimensions of region captures, ~180 MB for the
# in-progress World, 31 MB bundle, 13 MB scaffolding cache. About 1.4 GB concurrent, so 2 GB leaves
# useful headroom. Steady state once a run finishes is ~44 MB.
NEED_DISK_GB = 2


def load(name, filename):
    """These modules have hyphens in their names, so they need loading by path."""
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


bootstrap = load("routemap_bootstrap", "routemap-bootstrap.py")


def available_ram_gb():
    """Available (not total) RAM, without a third-party dependency."""
    system = platform.system()
    try:
        if system == "Linux":
            for line in Path("/proc/meminfo").read_text().splitlines():
                if line.startswith("MemAvailable:"):
                    return int(line.split()[1]) / 1024 / 1024
        elif system == "Darwin":
            import subprocess

            total = int(subprocess.run(["sysctl", "-n", "hw.memsize"], capture_output=True, text=True).stdout)
            return total / 1024**3
        elif system == "Windows":

            class MemoryStatusEx(ctypes.Structure):
                _fields_ = [
                    ("dwLength", ctypes.c_ulong),
                    ("dwMemoryLoad", ctypes.c_ulong),
                    ("ullTotalPhys", ctypes.c_ulonglong),
                    ("ullAvailPhys", ctypes.c_ulonglong),
                    ("ullTotalPageFile", ctypes.c_ulonglong),
                    ("ullAvailPageFile", ctypes.c_ulonglong),
                    ("ullTotalVirtual", ctypes.c_ulonglong),
                    ("ullAvailVirtual", ctypes.c_ulonglong),
                    ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
                ]

            stat = MemoryStatusEx()
            stat.dwLength = ctypes.sizeof(MemoryStatusEx)
            ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(stat))
            return stat.ullAvailPhys / 1024**3
    except (OSError, ValueError, AttributeError):
        pass
    return None


def viewer_dir():
    """Where routemap/ lives: vendored beside this script when packaged, else the seedlib checkout."""
    for cand in (HERE, HERE.parent, HERE.parent.parent / "gtnh-seedlib"):
        if (cand / "routemap" / "index.html").is_file():
            return cand / "routemap"
    return None


def worlds_dir(instance=None, override=None):
    """Where finished bundles land.

    Inside the instance, as `<instance>/routemap/`, so they sit next to the world they describe and
    Prism's own "Open Folder" button reaches them -- a non-technical player is never going to find
    %LOCALAPPDATA%. Only the ~31 MB bundles go here; the assembled server stays in the cache, since
    it is bulky, regenerable, and would otherwise be swept into Prism's Export Instance zip.

    Two consequences worth knowing: deleting the instance deletes its maps, and this is the one place
    the tool writes into the instance at all.
    """
    if override:
        return Path(override).expanduser().resolve()
    if instance:
        return instance["dir"] / "routemap"
    return bootstrap.cache_root() / "worlds"


def make_handler(viewer, worlds):
    """Serve the viewer and the bundles from two unrelated directories under one origin.

    The page fetches `../worlds/<seed>/...` relative to `/routemap/`, so the two have to look like
    siblings. They are not: the viewer ships with the tool and the bundles live in the Prism
    instance. Routing by prefix avoids copying the viewer in next to the bundles or relying on
    symlinks, which need elevation on Windows.
    """

    class Handler(http.server.SimpleHTTPRequestHandler):
        def translate_path(self, path):
            import urllib.parse

            parts = [
                p
                for p in urllib.parse.unquote(urllib.parse.urlparse(path).path).split("/")
                if p and p not in (".", "..")
            ]
            if parts and parts[0] == "worlds":
                base, rest = worlds, parts[1:]
            elif parts and parts[0] == "routemap":
                base, rest = viewer, parts[1:]
            else:
                base, rest = viewer, parts
            full = base.joinpath(*rest) if rest else base
            return str(full / "index.html" if full.is_dir() else full)

        def log_message(self, *a):
            pass

    return Handler


def preflight(instance=None):
    """Everything that can stop a half-hour run, checked while it is still cheap to say so."""
    problems = []

    instances = bootstrap.find_instances()
    if not instances:
        roots = ", ".join(str(p) for p in bootstrap.prism_roots())
        problems.append(
            "No Prism Launcher install with a GTNH (1.7.10 Forge) instance was found.\n"
            f"    Looked in: {roots or 'nowhere -- Prism does not appear to be installed'}"
        )
        return problems, None, None

    chosen = None
    if instance:
        chosen = next((i for i in instances if i["name"] == instance), None)
        if chosen is None:
            problems.append(f"No instance named {instance!r}. Known: {', '.join(i['name'] for i in instances)}")
            return problems, None, instances
    else:
        usable = [i for i in instances if i["pack_version"]]
        if len(usable) == 1:
            chosen = usable[0]
        elif not usable:
            problems.append(
                "Every GTNH instance found is a daily or experimental build, whose pack version\n"
                "    cannot be resolved to a release manifest. Use a stable instance (2.8.4,\n"
                "    2.9.0-beta-3, ...), or pass --pack-version if you know what it should be."
            )
            return problems, None, instances

    if chosen is not None:
        if bootstrap.find_jre(chosen) is None:
            problems.append(
                f"{chosen['name']} has no usable Java runtime yet.\n"
                "    Launch it once in Prism so Prism downloads one, then run this again."
            )
        launch = next((chosen["dir"] / "libraries").glob("lwjgl3ify-*-forgePatches.jar"), None)
        if launch is None:
            problems.append(
                f"{chosen['name']} has no lwjgl3ify launch jar yet.\n"
                "    Launch it once in Prism so Prism downloads its libraries, then run this again."
            )

    for mod in ("numpy", "PIL"):
        if importlib.util.find_spec(mod) is None:
            problems.append(
                f"Python package {mod!r} is missing; the map renderer needs it.\n"
                "    Install both with:  pip install numpy pillow"
            )

    ram = available_ram_gb()
    if ram is not None and ram < NEED_RAM_GB:
        problems.append(
            f"Needs {NEED_RAM_GB} GB of free memory; only {ram:.1f} GB is available."
        )

    # Walk up to something that exists: on a machine that has never run this, neither the cache
    # directory nor its parent is there yet, and statvfs on a missing path raises rather than
    # reporting zero -- which turned the disk check into a traceback for exactly the new user it is
    # meant to help.
    probe_dir = bootstrap.cache_root()
    while not probe_dir.exists() and probe_dir != probe_dir.parent:
        probe_dir = probe_dir.parent
    try:
        free = shutil.disk_usage(str(probe_dir)).free / 1024**3
    except OSError:
        free = None
    if free is not None and free < NEED_DISK_GB:
        problems.append(f"Only {free:.1f} GB of disk is free; about {NEED_DISK_GB} GB is needed.")

    if viewer_dir() is None:
        problems.append(
            "The map viewer (routemap/) is not next to this script and no gtnh-seedlib checkout\n"
            "    was found beside the repo."
        )
    return problems, chosen, instances


def serve_and_open(viewer, worlds, seed):
    """Serve the bundle locally and open it. Bound to loopback: no firewall prompt, no exposure."""
    handler = make_handler(viewer, worlds)

    class Quiet(socketserver.TCPServer):
        allow_reuse_address = True

        def handle_error(self, request, client_address):
            pass

    for port in range(PORT, PORT + 20):
        try:
            httpd = Quiet(("127.0.0.1", port), handler)
            break
        except OSError:
            continue
    else:
        print(f"could not bind a port in {PORT}..{PORT + 19}; serve {root} yourself")
        return

    url = f"http://localhost:{port}/routemap/?world={seed}"
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    print(f"\nroute map: {url}")
    print("Leave this window open while you use the map; press Ctrl+C to stop.")
    webbrowser.open(url)
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        print("\nstopped")


def find_jars():
    """The probe and fix jars to deploy: vendored, else a developer build, else the release.

    Deliberately does NOT look in the repo's top-level jars/, which is a staging area of historical
    builds -- picking from it lexically once deployed worldgenprobe-0.7 over a jar built the same day,
    and an old probe silently produces a bundle missing everything the newer one records.
    """
    found = {}
    for stem, build in (("worldgenprobe", "probe-build"), ("gtnhdeterminism", "fix-build")):
        cands = list((HERE / "jars").glob(f"{stem}-*.jar"))
        if not cands:
            libs = HERE.parent / build / "build" / "libs"
            cands = [
                p for p in libs.glob(f"{stem}-*.jar") if not p.name.endswith(("-dev.jar", "-sources.jar"))
            ]
        if cands:
            found[stem] = max(cands, key=lambda p: p.stat().st_mtime)
    if len(found) < 2:
        found.update(bootstrap.fetch_release_jars())
    return found


def existing_bundle(bundle, want_pack):
    """A finished bundle for this seed that is still valid for the pack now installed.

    Worldgen does not survive a pack update -- mod changes move RNG consumption, structures and
    loot tables -- so a map baked against 2.8.4 describes a different world once the instance is on
    2.9.0. Reusing it silently would be worse than the half hour it saves.
    """
    meta = bundle / "meta.json"
    if not meta.is_file():
        return None
    try:
        pack = json.loads(meta.read_text()).get("pack")
    except (OSError, ValueError):
        return None
    if pack != want_pack:
        print(f"  the existing map was built for pack {pack}, you are on {want_pack}; rebuilding")
        return None
    return bundle


def generate(instance, seed, radius, dims, pack_version=None, out=None, keep_server=False,
             regenerate=False):
    """Assemble the server if needed, then build the bundle. Returns the bundle directory."""
    worlds = worlds_dir(instance, out)
    version_wanted = pack_version or instance["pack_version"]
    if not regenerate and existing_bundle(worlds / str(seed), version_wanted):
        print(f"  a map for seed {seed} is already built; opening it")
        print("  (delete it, or pass --regenerate, to build it again)")
        return worlds / str(seed)
    jars = find_jars()
    for stem, path in sorted(jars.items()):
        print(f"  using {path.name}")
    jre = bootstrap.find_jre(instance)
    version = pack_version or instance["pack_version"]
    server = bootstrap.cache_root() / "servers" / version
    bootstrap.assemble(instance, server, pack_version=pack_version)

    driver = load("make_routemap", "make-routemap.py")
    argv = [
        "--seed", str(seed),
        "--server", str(server),
        "--java", str(jre),
        "--out", str(worlds),
        "--radius", str(radius),
        "--dims", dims,
        "--pack", version,
        # Explicitly in the cache, NOT beside the bundle: the scratch holds every dimension's region
        # capture and runs to ~540 MB mid-build, and the bundle now lives inside the Prism instance.
        "--scratch", str(bootstrap.cache_root() / "scratch" / str(seed)),
    ]
    amidst, mc_jar = bootstrap.palette_sources(instance)
    if amidst:
        argv += ["--amidst", str(amidst)]
    if mc_jar:
        argv += ["--mc-jar", str(mc_jar)]
    for stem, flag in (("worldgenprobe", "--probe-jar"), ("gtnhdeterminism", "--fix-jar")):
        jar = jars.get(stem)
        if jar:
            argv += [flag, str(jar)]
    sys.argv = ["make-routemap.py"] + argv
    driver.main()

    # The server is ~550 MB of copied mod jars and exists only to generate this bundle. Keeping it
    # around would quietly cost more disk than every map the player will ever make, so it goes unless
    # asked for. The ~13 MB of downloaded scaffolding stays cached, so rebuilding it is local work.
    if not keep_server:
        shutil.rmtree(server, ignore_errors=True)
        print(f"  removed the temporary server ({server.name}); --keep-server keeps it for next time")
    return worlds / str(seed)


def _ask(question):
    """input(), but Ctrl-C and a closed stdin exit quietly instead of dumping a traceback."""
    try:
        return input(question).strip()
    except (EOFError, KeyboardInterrupt):
        print()
        raise SystemExit(130)


def prompt():
    """Ask for an instance and a seed at the console.

    This is the Windows path in practice, not a degraded fallback: python.org's embeddable
    distribution ships no tkinter, and vendoring Tcl/Tk would add ~10 MB and a pile of DLLs to dodge
    a prompt that works fine in the window the .bat already opens.
    """
    problems, chosen, instances = preflight()
    if instances is None:
        for p in problems:
            print(f"  - {p}")
        raise SystemExit(1)

    usable = [i for i in instances if i["pack_version"]]
    if not usable:
        for p in problems:
            print(f"  - {p}")
        raise SystemExit(1)
    if len(usable) == 1:
        chosen = usable[0]
        print(f"GTNH instance: {chosen['name']}  (pack {chosen['pack_version']})")
    else:
        print("GTNH instances found:")
        for n, inst in enumerate(usable, 1):
            print(f"  {n}. {inst['name']}   (pack {inst['pack_version']})")
        while True:
            raw = _ask(f"Which one? [1-{len(usable)}]: ")
            if raw.isdigit() and 1 <= int(raw) <= len(usable):
                chosen = usable[int(raw) - 1]
                break
            print("  Type one of the numbers above.")

    issues, _, _ = preflight(chosen["name"])
    if issues:
        for p in issues:
            print(f"  - {p}")
        raise SystemExit(1)

    while True:
        raw = _ask("World seed: ")
        try:
            seed = int(raw)
            break
        except ValueError:
            print("  A seed is a whole number, and may start with '-'.")

    print("\nThis takes about half an hour.\n")
    generate(chosen, seed, 60, "0,7,-1")
    serve_and_open(viewer_dir(), worlds_dir(chosen), seed)


def run_gui():
    try:
        import tkinter as tk
        from tkinter import messagebox, ttk
    except ImportError:
        return prompt()

    problems, chosen, instances = preflight()
    win = tk.Tk()
    win.title("GTNH Route Map")
    frame = ttk.Frame(win, padding=16)
    frame.grid()

    if problems and instances is None:
        messagebox.showerror("GTNH Route Map", "\n\n".join(problems))
        return

    ttk.Label(frame, text="GTNH instance").grid(column=0, row=0, sticky="w")
    names = [i["name"] for i in instances if i["pack_version"]]
    inst_var = tk.StringVar(value=(chosen or instances[0])["name"] if names else "")
    ttk.Combobox(frame, textvariable=inst_var, values=names, width=42, state="readonly").grid(column=1, row=0)

    ttk.Label(frame, text="World seed").grid(column=0, row=1, sticky="w", pady=(8, 0))
    seed_var = tk.StringVar()
    ttk.Entry(frame, textvariable=seed_var, width=44).grid(column=1, row=1, pady=(8, 0))

    status = ttk.Label(frame, text="This takes about half an hour.", wraplength=420)
    status.grid(column=0, row=3, columnspan=2, pady=(12, 0), sticky="w")

    def go():
        try:
            seed = int(seed_var.get().strip())
        except ValueError:
            messagebox.showerror("GTNH Route Map", "The seed must be a whole number.")
            return
        inst = next(i for i in instances if i["name"] == inst_var.get())
        issues, _, _ = preflight(inst["name"])
        if issues:
            messagebox.showerror("GTNH Route Map", "\n\n".join(issues))
            return
        win.destroy()
        generate(inst, seed, 60, "0,7,-1")
        serve_and_open(viewer_dir(), worlds_dir(inst), seed)
        return bundle

    ttk.Button(frame, text="Generate", command=go).grid(column=1, row=2, sticky="e", pady=(12, 0))
    win.mainloop()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seed", type=int)
    ap.add_argument("--instance")
    ap.add_argument("--pack-version")
    ap.add_argument("--radius", type=int, default=60)
    ap.add_argument("--dims", default="0,7,-1")
    ap.add_argument("--list", action="store_true", help="show the instances that were found and exit")
    ap.add_argument("--out", help="where bundles are written (default: the cache, not any git checkout)")
    ap.add_argument("--regenerate", action="store_true",
                    help="rebuild even if a map for this seed already exists")
    ap.add_argument("--keep-server", action="store_true",
                    help="keep the ~550 MB assembled server so the next run skips rebuilding it")
    ap.add_argument("--no-serve", action="store_true", help="build the bundle but do not open a browser")
    args = ap.parse_args()

    if args.list:
        bootstrap.cmd_list(args)
        return
    if args.seed is None:
        run_gui()
        return

    problems, chosen, instances = preflight(args.instance)
    if problems:
        for p in problems:
            print(f"  - {p}")
        raise SystemExit(1)
    if chosen is None:
        raise SystemExit(
            "several instances found; pass --instance:\n  "
            + "\n  ".join(i["name"] for i in instances if i["pack_version"])
        )
    generate(chosen, args.seed, args.radius, args.dims, args.pack_version, args.out,
             args.keep_server, args.regenerate)
    if not args.no_serve:
        serve_and_open(viewer_dir(), worlds_dir(chosen, args.out), args.seed)


if __name__ == "__main__":
    main()
