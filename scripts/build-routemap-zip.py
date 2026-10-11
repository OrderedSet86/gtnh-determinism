#!/usr/bin/env python3
"""Assemble the distributable route-map zip.

    build-routemap-zip.py [--platform windows|unix|all] [--out DIR] [--no-jars]

Produces `GTNH-Routemap-<platform>.zip`, which a player unzips and double-clicks. It contains no
packed binary of any kind, and that is the point rather than an accident: PyInstaller's one-file
bootloader is a self-extracting stub that unpacks to temp and executes, which is structurally what a
malware dropper does and is why unsigned frozen Python reliably trips SmartScreen and AV heuristics.
Shipping a scary dialog to a non-technical player costs more trust than it saves effort.

So the Windows build vendors python.org's official embeddable distribution instead. `python.exe` is
Authenticode-signed by the Python Software Foundation and carries years of reputation; numpy and
Pillow arrive as official PyPI wheels; everything else in the tree is plain text. macOS and Linux get
the same tree minus `python/`, because they already have python3 -- so there is nothing unsigned for
Gatekeeper to quarantine either.

Layout, which the scripts' own path resolution expects:

    GTNH-Routemap/
      GTNH Routemap.bat / routemap.sh   one line, calls app/routemap.py
      routemap/                         the viewer, copied verbatim from gtnh-seedlib
      python/                           Windows only: embeddable CPython + wheels
      app/
        routemap.py routemap-bootstrap.py make-routemap.py
        jars/          worldgenprobe + gtnhdeterminism, from the latest release
        seedsearch/    vein-csv.py fullgen-loot-csv.py loot-score.py
        seedlib/       tools/{build_world_bundle,worldrender}.py + browser/lootscore.py
        data/          value-table.csv, amidst-biomes.json

The embeddable build and the wheels must agree on the ABI tag (3.12 <-> cp312) or the wheels will
not import; both are pinned together below.
"""
import argparse
import importlib.util
import io
import json
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
SEEDLIB = REPO.parent / "gtnh-seedlib"

PYTHON_VERSION = "3.12.8"
ABI = "cp312"
PYTHON_EMBED = f"https://www.python.org/ftp/python/{PYTHON_VERSION}/python-{PYTHON_VERSION}-embed-amd64.zip"
# certifi is not optional on Windows: the embeddable Python ships no CA bundle, so every HTTPS
# fetch fails with "unable to get local issuer certificate" until this is present.
WHEELS = ("numpy", "pillow", "certifi")

UA = {"User-Agent": "gtnh-routemap-build/1.0"}

LAUNCHER_BAT = '@echo off\r\n"%~dp0python\\python.exe" "%~dp0app\\routemap.py" %*\r\npause\r\n'
LAUNCHER_SH = '#!/usr/bin/env bash\ncd "$(dirname "$0")"\nexec python3 app/routemap.py "$@"\n'

README = """GTNH Route Map
==============

Generates an interactive map of one GTNH world seed: ore veins, chest loot, villages,
dungeons and points of interest across the Overworld, Nether and Twilight Forest.

HOW TO USE

  Windows   double-click "GTNH Routemap.bat"
  macOS     open Terminal here and run ./routemap.sh
  Linux     ./routemap.sh

Pick your GTNH instance, type a seed, and wait. It takes about half an hour. When it
finishes the map opens in your browser automatically.

WHAT YOU NEED

  * Prism Launcher with a GTNH instance you have launched at least once.
    (Launching it once is what makes Prism download the Java runtime this uses.)
  * 7 GB of free memory.
  * About 2 GB of free disk.

You do NOT need to install Java, Python, or a GTNH server. Windows: everything is included.
macOS and Linux: you need python3 plus "pip install numpy pillow".

WHERE YOUR MAPS GO

  Into the instance itself, beside the world they describe:

    <your Prism instance>\\routemap\\<seed>\\

  In Prism, select the instance and click "Folder" to get there. Nothing else in the
  instance is touched -- your mods, config and saves are only ever read.

  Opening a map again later is instant: run the tool, enter the same seed, and it serves
  the map you already have instead of rebuilding it. The half hour is paid once per seed.

  If you update your pack, maps made on the old version are rebuilt automatically -- a
  pack update changes worldgen, so the old map would describe a different world.

WORKING FILES

  A headless server is built to generate the map and deleted again afterwards. While a
  run is going it needs about 1.4 GB here:

    %LOCALAPPDATA%\\gtnh-routemap

  Roughly 44 MB stays behind so the next run is quicker. Deleting that folder is safe.

IF WINDOWS WARNS YOU

  Nothing in this zip is an installer or a compiled program: it is the official Python
  interpreter from python.org plus plain text scripts you can open and read. If Windows
  or your antivirus does object, that is a false positive.

  Antivirus has been seen quarantining GTNH's own mod jars (they bundle nested jars,
  which looks like a self-extracting archive). If that happens the map generator will
  tell you which mod went missing -- restore it from quarantine and run again.
"""


def log(msg):
    print(msg, flush=True)


def die(msg):
    raise SystemExit(f"error: {msg}")


def fetch(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read()


def wheel_url(package):
    """The newest wheel for this package: the pinned ABI + win_amd64, or pure-python if that is all
    it publishes (certifi is pure python and ships only py3-none-any)."""
    data = json.loads(fetch(f"https://pypi.org/pypi/{package}/json").decode())
    version = data["info"]["version"]
    files = data["releases"][version]
    for want in (lambda f: ABI in f and "win_amd64" in f, lambda f: "py3-none-any" in f):
        for f in files:
            if f["filename"].endswith(".whl") and want(f["filename"]):
                return f["url"], f["filename"], f["size"]
    die(f"no usable wheel for {package} {version}")


def copy_tree(src, dst, names=None):
    dst.mkdir(parents=True, exist_ok=True)
    if names is None:
        shutil.copytree(src, dst, dirs_exist_ok=True)
        return
    for name in names:
        s = src / name
        if not s.exists():
            die(f"missing required file: {s}")
        shutil.copy2(s, dst / Path(name).name)


def build_app(root, include_jars=True, jars_dir=None):
    """Everything platform-independent: the scripts, their helpers, the viewer and the data."""
    app = root / "app"
    app.mkdir(parents=True, exist_ok=True)

    copy_tree(HERE, app, ["routemap.py", "routemap-bootstrap.py", "make-routemap.py"])
    copy_tree(
        REPO / "seedsearch",
        app / "seedsearch",
        ["vein-csv.py", "fullgen-loot-csv.py", "loot-score.py"],
    )

    if not (SEEDLIB / "tools" / "build_world_bundle.py").is_file():
        die(f"gtnh-seedlib not found at {SEEDLIB}; it holds the bundle builder and the viewer")
    copy_tree(SEEDLIB / "tools", app / "seedlib" / "tools", ["build_world_bundle.py", "worldrender.py"])
    # build_world_bundle.py does sys.path.insert(REPO/"browser") where REPO is tools/..; the vendored
    # layout has to preserve that relationship or the value-table parser goes missing at bake time.
    copy_tree(SEEDLIB / "browser", app / "seedlib" / "browser", ["lootscore.py"])

    data = app / "data"
    data.mkdir(exist_ok=True)
    shutil.copy2(REPO / "results" / "2026-08-30-chest-loot-radius60" / "value-table.csv", data)
    amidst = REPO.parent / "all-gtnh" / "AmidstGTNH" / "src" / "main" / "resources" / "assets" / "forgeamidst" / "biome" / "default.json"
    if amidst.is_file():
        shutil.copy2(amidst, data / "amidst-biomes.json")
        log("  vendored the Amidst biome table")
    else:
        log("  note: no Amidst table to vendor; the tool will fetch it at run time")

    # The viewer, byte-for-byte. No fork, no reimplementation -- the packaged map is the same page
    # as the hosted one, including every layer, popup and deep-link parameter.
    shutil.copytree(SEEDLIB / "routemap", root / "routemap", dirs_exist_ok=True)
    log(f"  viewer: {sum(1 for _ in (root / 'routemap').rglob('*') if _.is_file())} files")

    if include_jars:
        if jars_dir:
            jars = {}
            for stem in ("worldgenprobe", "gtnhdeterminism"):
                found = sorted(jars_dir.glob(f"{stem}-*.jar"))
                if not found:
                    die(f"no {stem}-*.jar in {jars_dir}")
                jars[stem] = found[-1]
        else:
            jars = load_bootstrap().fetch_release_jars()
        (app / "jars").mkdir(exist_ok=True)
        for stem, path in sorted(jars.items()):
            shutil.copy2(path, app / "jars" / path.name)
            log(f"  jar: {path.name}")

    (root / "README.txt").write_text(README, encoding="utf-8")
    return app


def load_bootstrap():
    spec = importlib.util.spec_from_file_location("rb", HERE / "routemap-bootstrap.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def build_python(root):
    """Vendor the embeddable interpreter plus the two wheels the renderer needs."""
    pydir = root / "python"
    pydir.mkdir(parents=True, exist_ok=True)
    log(f"  python {PYTHON_VERSION} embeddable")
    with zipfile.ZipFile(io.BytesIO(fetch(PYTHON_EMBED))) as z:
        z.extractall(pydir)

    for package in WHEELS:
        url, name, size = wheel_url(package)
        log(f"  wheel {name} ({size / 1e6:.1f} MB)")
        with zipfile.ZipFile(io.BytesIO(fetch(url))) as z:
            # Unpacked beside python.exe rather than into Lib/site-packages: the embeddable build's
            # ._pth already has "." on sys.path, so this imports with no _pth surgery and no pip.
            z.extractall(pydir)

    pth = next(pydir.glob("python*._pth"), None)
    if pth is None:
        die("the embeddable package has no ._pth file; sys.path would not include the wheels")
    text = pth.read_text()
    if "\n.\n" not in text and not text.rstrip().endswith("."):
        pth.write_text(text.rstrip() + "\n.\n")
        log("  added '.' to the interpreter's path file")


def verify(root, platform):
    """Refuse to ship a tree that is missing something the run needs."""
    required = [
        "app/routemap.py",
        "app/routemap-bootstrap.py",
        "app/make-routemap.py",
        "app/seedsearch/vein-csv.py",
        "app/seedsearch/fullgen-loot-csv.py",
        "app/seedsearch/loot-score.py",
        "app/seedlib/tools/build_world_bundle.py",
        "app/seedlib/tools/worldrender.py",
        "app/seedlib/browser/lootscore.py",
        "app/data/value-table.csv",
        "routemap/index.html",
        "routemap/map.js",
        "routemap/layers.js",
        "routemap/vendor/leaflet.js",
        "README.txt",
    ]
    if platform == "windows":
        required += [
            "python/python.exe",
            "python/numpy/__init__.py",
            "python/PIL/__init__.py",
            "python/certifi/cacert.pem",
            "GTNH Routemap.bat",
        ]
    else:
        required += ["routemap.sh"]
    missing = [r for r in required if not (root / r).exists()]
    if missing:
        die("the assembled tree is missing:\n" + "\n".join(f"    {m}" for m in missing))
    # A packed binary here would mean someone reintroduced a frozen build.
    exes = [p for p in root.rglob("*.exe") if p.name != "python.exe" and p.name != "pythonw.exe"]
    if exes:
        die("unexpected executable(s) in the tree: " + ", ".join(p.name for p in exes))
    log(f"  verified {len(required)} required paths")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--platform", choices=("windows", "unix", "all"), default="all")
    ap.add_argument("--out", type=Path, default=REPO / "dist")
    ap.add_argument("--no-jars", action="store_true", help="skip the release jars (offline builds)")
    ap.add_argument("--seedlib", type=Path, help="gtnh-seedlib checkout (default: sibling of this repo)")
    ap.add_argument(
        "--jars",
        type=Path,
        help="directory holding worldgenprobe/gtnhdeterminism jars to vendor. CI must pass this: "
        "without it the build fetches the LATEST PUBLISHED release, so a zip built during a "
        "release would ship the previous version's jars.",
    )
    args = ap.parse_args()

    global SEEDLIB
    if args.seedlib:
        SEEDLIB = args.seedlib.resolve()
    platforms = ("windows", "unix") if args.platform == "all" else (args.platform,)
    args.out.mkdir(parents=True, exist_ok=True)

    for platform in platforms:
        log(f"building {platform}")
        stage = args.out / f".stage-{platform}" / "GTNH-Routemap"
        shutil.rmtree(stage.parent, ignore_errors=True)
        build_app(stage, include_jars=not args.no_jars, jars_dir=args.jars)
        if platform == "windows":
            build_python(stage)
            (stage / "GTNH Routemap.bat").write_text(LAUNCHER_BAT, encoding="utf-8", newline="")
        else:
            sh = stage / "routemap.sh"
            sh.write_text(LAUNCHER_SH, encoding="utf-8")
            sh.chmod(0o755)
        verify(stage, platform)

        zip_path = args.out / f"GTNH-Routemap-{platform}.zip"
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for p in sorted(stage.parent.rglob("*")):
                if p.is_file():
                    z.write(p, p.relative_to(stage.parent))
        shutil.rmtree(stage.parent, ignore_errors=True)
        log(f"  {zip_path}  ({zip_path.stat().st_size / 1e6:.1f} MB)\n")


if __name__ == "__main__":
    main()
