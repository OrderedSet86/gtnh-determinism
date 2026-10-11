#!/usr/bin/env python3
"""Build a headless GTNH server out of the player's own Prism Launcher instance.

    routemap-bootstrap.py list
    routemap-bootstrap.py assemble <instance-name> [--dest D] [--pack-version V] [--force]

A routemap needs a *dedicated* server; a player has a *client*. Worldgen is side-identical -- that is
the premise this whole repo rests on -- so the mod jars can come straight out of the instance, and
only the launch scaffolding has to be fetched. Assembling from the instance rather than downloading
the 620 MB server pack costs about 13 MB of network, guarantees the bundle matches the world the
player will actually load, and reaches daily and beta packs, whose server zips are published only as
GitHub Actions artifacts (the zip endpoint is 401 without an `actions:read` token even though the
repo is public).

DISK. The assembled server is a real copy of the pack's mods and config, around 550 MB, because
Forge needs a single directory it can read the whole mod set from and rewrite configs into. It is
deliberately NOT hardlinked back into the instance: that would make deleting the instance fail to
free the space, and make Explorer and du both report sizes that are not true. Instead the server is
transient -- make-routemap.py deletes it after a successful run unless told to keep it -- so the
steady-state cost is the ~13 MB scaffolding cache plus the bundles themselves.

The probe jar never enters the instance. Everything is assembled into a separate cache directory, so
there is nothing to clean up afterwards and the player's saves cannot be touched.

WHAT CANNOT COME FROM THE CLIENT, and where it comes from instead:

  minecraft_server.1.7.10.jar   Mojang's version manifest (the client has only client.jar)
  forge-*-universal.jar         maven.minecraftforge.net
  libraries/ (14 jars)          resolved per REPOS below; Forge maven has 13 of them and
                                `lzma:lzma:0.0.1` only exists on libraries.minecraft.net
  java9args.txt                 generated from the launch jar's own Add-Opens manifest attribute
  JourneyMapServer, Morpheus    the two SERVER-side mods, downloaded from the asset index

The library list is the launch jar's `Class-Path`, not a hand-maintained table and not Forge's
`version.json`. Note it is only 14 jars while a real server pack ships 25: launchwrapper, ASM and
commons-lang3 are shaded INTO lwjgl3ify-forgePatches.jar, so the extra 11 are vestigial Forge layout
and fetching them would be cargo cult.

MOD PRUNING is deliberately minimal: only mods the manifest positively identifies as CLIENT are
removed, because those abort a headless boot. Everything else travels, including mods the manifest
has never heard of. The player is mapping THEIR world, so a worldgen mod they added is part of it,
and leaving it out produces a map of somewhere else; full generation records whatever actually
generates, so extra worldgen mods need no special handling. If an unrecognised mod turns out to be
client-only, Forge names it at boot, which is a better outcome than a quietly wrong map.

Matching is by mod identity, not exact filename. An instance carrying AdvancedSolarPanel 3.5.1a
against a manifest pinning 3.5.1 had that mod classed as player-added and dropped, and the server
then died with supersolarpanel demanding it.
"""
import argparse
import configparser
import hashlib
import io
import json
import os
import platform
import re
import shutil
import ssl
import subprocess
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

DAXXL = "https://raw.githubusercontent.com/GTNewHorizons/DreamAssemblerXXL/master"
ASSETS_URL = f"{DAXXL}/gtnh-assets.json"
MODPACK_URL = f"{DAXXL}/gtnh-modpack.json"
MANIFEST_URL = f"{DAXXL}/releases/manifests/{{version}}.json"
MOJANG_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"

# Ordered: first hit wins. Forge maven carries 13 of the 14; lzma 0.0.1 exists only in Mojang's
# library mirror, and Maven Central is the backstop for anything a future pack bump introduces.
REPOS = (
    "https://maven.minecraftforge.net/",
    "https://libraries.minecraft.net/",
    "https://repo1.maven.org/maven2/",
    "https://nexus.gtnewhorizons.com/repository/public/",
)

# DreamAssemblerXXL's Side enum: a modern-Java server build keeps exactly these.
# src/daxxl/defs.py -- Side.SERVER_JAVA9 -> {BOTH, SERVER, BOTH_JAVA9, SERVER_JAVA9}
KEEP_SIDES = frozenset({"BOTH", "SERVER", "BOTH_JAVA9", "SERVER_JAVA9"})

MC_VERSION = "1.7.10"
# Some sites 403 a bare urllib User-Agent.
UA = {"User-Agent": "gtnh-routemap/1.0 (+https://github.com/OrderedSet86/gtnh-determinism)"}

# The Add-Opens manifest attribute cannot express a second target module, but the shipped
# java9args.txt opens this one to java.naming as well (JNDI's DNS provider needs it). Verified by
# generating the file and diffing against a real 2.8.4 server pack: 67 of 68 lines matched exactly,
# this was the 68th. Adding a target only ever loosens access, so applying it blind is safe.
ADD_OPENS_EXTRA_TARGETS = {"jdk.naming.dns/com.sun.jndi.dns": "java.naming"}


def mod_stem(filename):
    """Mod identity from a jar name: everything before the first version-looking token.

    Exact filenames are not a reliable key. A player's instance carried
    AdvancedSolarPanel-1.7.10-3.5.1a.jar while the 2.9.0-beta-3 manifest pinned 3.5.1, so an exact
    match reported a required pack mod as "something you added yourself", dropped it, and the server
    died at boot with supersolarpanel demanding it. Comparing stems tolerates that drift.
    """
    return re.split(r"-\d|-v\d", filename)[0].lower()


def log(msg):
    print(msg, flush=True)


def die(msg):
    raise SystemExit(f"error: {msg}")


def _ssl_context():
    """A context with a CA bundle that actually exists on Windows.

    python.org's embeddable distribution ships no CA certificates, and a fresh Windows install's
    root store has not cached the intermediates either, so every HTTPS fetch dies with
    "unable to get local issuer certificate". Linux never showed this because it picked up the
    system trust store. certifi is vendored into the Windows build for exactly this; elsewhere the
    default context is already correct.
    """
    try:
        import certifi

        return ssl.create_default_context(cafile=certifi.where())
    except ImportError:
        return None


_SSL = _ssl_context()


def fetch(url, binary=True):
    req = urllib.request.Request(url, headers=UA)
    try:
        with urllib.request.urlopen(req, timeout=60, context=_SSL) as r:
            data = r.read()
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{url} -> HTTP {e.code}") from e
    except urllib.error.URLError as e:
        raise RuntimeError(f"{url} -> {e.reason}") from e
    return data if binary else data.decode("utf-8")


def fetch_json(url):
    return json.loads(fetch(url, binary=False))


# --------------------------------------------------------------------------------------- Prism


def prism_roots():
    """Every place a Prism (or MultiMC) data directory can live, newest layout first."""
    home = Path.home()
    out = []
    system = platform.system()
    if system == "Windows":
        appdata = os.environ.get("APPDATA")
        if appdata:
            out += [Path(appdata) / "PrismLauncher", Path(appdata) / "MultiMC"]
    elif system == "Darwin":
        base = home / "Library" / "Application Support"
        out += [base / "PrismLauncher", base / "MultiMC"]
    else:
        out += [
            home / ".local" / "share" / "PrismLauncher",
            # Flatpak keeps its own copy and it is NOT the same directory as the native one; on this
            # developer's machine the GTNH instances live only in the Flatpak root.
            home / ".var" / "app" / "org.prismlauncher.PrismLauncher" / "data" / "PrismLauncher",
            home / ".local" / "share" / "multimc",
        ]
    # Portable installs keep instances/ beside the executable rather than in a data dir.
    for cand in (Path.cwd(), Path(sys.argv[0]).resolve().parent):
        if (cand / "instances").is_dir():
            out.append(cand)
    return [p for p in out if (p / "instances").is_dir()]


def _read_cfg(path):
    """instance.cfg and prismlauncher.cfg are INI without a section header."""
    if not path.is_file():
        return {}
    cp = configparser.ConfigParser(interpolation=None)
    try:
        cp.read_string("[x]\n" + path.read_text(encoding="utf-8", errors="replace"))
    except configparser.Error:
        return {}
    return dict(cp["x"])


def find_instances(roots=None):
    """Every 1.7.10 Forge instance under every Prism root."""
    found = []
    for root in roots if roots is not None else prism_roots():
        for inst_dir in sorted((root / "instances").iterdir()):
            pack = inst_dir / "mmc-pack.json"
            if not pack.is_file():
                continue
            try:
                components = json.loads(pack.read_text(encoding="utf-8")).get("components", [])
            except (OSError, json.JSONDecodeError):
                continue
            uids = {c.get("uid"): c.get("version") for c in components}
            if uids.get("net.minecraft") != MC_VERSION or "net.minecraftforge" not in uids:
                continue
            # The bare `1.7.10` instance uses minecraft/; every GTNH one uses .minecraft/.
            mc = next((inst_dir / n for n in (".minecraft", "minecraft") if (inst_dir / n).is_dir()), None)
            if mc is None:
                continue
            found.append(
                {
                    "name": inst_dir.name,
                    "dir": inst_dir,
                    "minecraft": mc,
                    "root": root,
                    "forge": uids["net.minecraftforge"],
                    "pack_version": parse_pack_version(inst_dir.name),
                    "java_range": parse_java_range(inst_dir.name),
                    "cfg": _read_cfg(inst_dir / "instance.cfg"),
                }
            )
    return found


def parse_pack_version(name):
    """`GT_New_Horizons_2.9.0-beta-3_Java_17-26` -> `2.9.0-beta-3`; daily builds -> None."""
    m = re.search(r"(?:GT[_ ]New[_ ]Horizons|GTNH)[_ -]+(\d+\.\d+\.\d+(?:-(?:beta|rc)-\d+)?)", name, re.I)
    return m.group(1) if m else None


def parse_java_range(name):
    """`..._Java_17-25` -> (17, 25). Absent means no constraint we can read."""
    m = re.search(r"Java[_ ](\d+)-(\d+)", name, re.I)
    return (int(m.group(1)), int(m.group(2))) if m else None


def _java_binaries(root):
    jdir = root / "java"
    if not jdir.is_dir():
        return []
    out = []
    for d in sorted(jdir.iterdir()):
        for rel in ("bin/java", "bin/java.exe", "Contents/Home/bin/java"):
            if (d / rel).is_file():
                out.append(d / rel)
                break
    return out


def java_major(binary):
    """Major version of a JRE, or None if it will not run."""
    try:
        r = subprocess.run([str(binary), "-version"], capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.SubprocessError):
        return None
    m = re.search(r'version "(\d+)(?:\.(\d+))?', r.stderr + r.stdout)
    if not m:
        return None
    major = int(m.group(1))
    return int(m.group(2) or 0) if major == 1 else major  # 1.8.0_202 -> 8


def find_jre(instance):
    """A JRE inside the pack's supported range.

    Deliberately does NOT just trust instance.cfg's JavaPath: Prism points GTNH instances at whatever
    it downloaded most recently, which is often 25, while this harness wants something in the range
    the pack declares and prefers 21.
    """
    lo, hi = instance.get("java_range") or (17, 25)
    candidates = []

    cfg = instance["cfg"]
    if cfg.get("overridejavalocation", "").lower() == "true" and cfg.get("javapath"):
        candidates.append(Path(cfg["javapath"]))
    candidates += _java_binaries(instance["root"])
    global_cfg = _read_cfg(instance["root"] / "prismlauncher.cfg")
    if global_cfg.get("javapath"):
        candidates.append(Path(global_cfg["javapath"]))

    usable = []
    for c in candidates:
        if not c.is_file():
            continue
        major = java_major(c)
        if major is not None and lo <= major <= hi:
            usable.append((major, c))
    if not usable:
        return None
    # Prefer 21: it is the version this harness has the most hours on.
    usable.sort(key=lambda mc: (abs(mc[0] - 21), mc[0]))
    return usable[0][1]


# ------------------------------------------------------------------------------- mod resolution


def resolve_mods(pack_version):
    """(filename -> side) for every mod the pack declares at this version."""
    assets = fetch_json(ASSETS_URL)
    by_name = {m["name"]: m for m in assets["mods"]}
    try:
        manifest = fetch_json(MANIFEST_URL.format(version=pack_version))
    except RuntimeError as e:
        die(
            f"no release manifest for pack version {pack_version!r} ({e}).\n"
            "  Daily and experimental builds are not released manifests; pass --pack-version with a\n"
            "  stable release, or generate the routemap from a stable instance. A bundle built against\n"
            "  the wrong pack version does not describe the world you will load."
        )

    entries = {}
    for key in ("github_mods", "external_mods"):
        entries.update(manifest.get(key, {}))

    # filename -> mod name across EVERY known version, so a point-release mismatch between the
    # manifest and what the player actually has still identifies the mod.
    any_version = {
        v["filename"]: mod["name"]
        for mod in assets["mods"]
        for v in mod.get("versions", [])
        if v.get("filename")
    }
    sides, unresolved = {}, []
    for name, info in entries.items():
        mod = by_name.get(name)
        if mod is None:
            unresolved.append(f"{name}: not in the asset index")
            continue
        version = next((v for v in mod["versions"] if v.get("version_tag") == info["version"]), None)
        if version is None:
            unresolved.append(f"{name}: no asset entry for version {info['version']}")
            continue
        sides[version["filename"]] = {
            "side": info.get("side") or mod.get("side"),
            "url": version.get("download_url"),
            "name": name,
        }
    if unresolved:
        # Not fatal on its own -- a stale asset index costs us a jar, and the boot test will say so --
        # but it must be visible rather than silently shrinking the mod set.
        log(f"  warning: {len(unresolved)} manifest entries did not resolve to a filename:")
        for u in unresolved[:10]:
            log(f"    {u}")
    return sides, any_version


def server_file_exclusions():
    """Paths the pack itself strips from a server build (file-level, separate from the side field)."""
    try:
        mp = fetch_json(MODPACK_URL)
    except RuntimeError:
        return set()
    return set(mp.get("server_exclusions", [])) | set(mp.get("server_java8_exclusions", []))


# ----------------------------------------------------------------------------- launch scaffolding


def manifest_attr(jar, name):
    """A single MANIFEST.MF attribute, with continuation lines unfolded."""
    with zipfile.ZipFile(jar) as z:
        raw = z.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
    lines = []
    for line in raw.splitlines():
        if line.startswith(" ") and lines:
            lines[-1] += line[1:]
        else:
            lines.append(line)
    for line in lines:
        if line.startswith(name + ":"):
            return line[len(name) + 1 :].strip()
    return None


def write_java9args(launch_jar, dest):
    """Regenerate java9args.txt from the launch jar rather than shipping a copy of it."""
    add_opens = (manifest_attr(launch_jar, "Add-Opens") or "").split()
    if not add_opens:
        die(f"{launch_jar.name} has no Add-Opens manifest attribute; cannot generate java9args.txt")
    args = [
        "-Dfile.encoding=UTF-8",
        "-Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader",
    ]
    for entry in add_opens:
        targets = "ALL-UNNAMED"
        if entry in ADD_OPENS_EXTRA_TARGETS:
            targets += "," + ADD_OPENS_EXTRA_TARGETS[entry]
        args += ["--add-opens", f"{entry}={targets}"]
    dest.write_text("\n".join(args) + "\n", encoding="utf-8")
    return len(add_opens)


def download_to(url, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    data = fetch(url)
    dest.write_bytes(data)
    return len(data)


def resolve_library(rel, dest):
    """Fetch one `group/artifact/version/file.jar` from the first repo that has it."""
    if dest.is_file() and dest.stat().st_size > 0:
        return "cached", 0
    errors = []
    for repo in REPOS:
        try:
            size = download_to(repo + rel, dest)
            return repo, size
        except RuntimeError as e:
            errors.append(str(e))
    die(
        f"library {rel} resolves in none of the {len(REPOS)} configured repositories.\n"
        + "\n".join(f"    {e}" for e in errors)
        + "\n  A missing library is a boot failure with no explanation, so this stops here rather\n"
        "  than assembling a server that cannot start."
    )


def mojang_server_jar_url():
    manifest = fetch_json(MOJANG_MANIFEST)
    entry = next((v for v in manifest["versions"] if v["id"] == MC_VERSION), None)
    if entry is None:
        die(f"Mojang's version manifest has no {MC_VERSION}")
    return fetch_json(entry["url"])["downloads"]["server"]["url"]


# ------------------------------------------------------------------------------- palette sources


AMIDST_URL = (
    "https://raw.githubusercontent.com/GTNewHorizons/AmidstGTNH/master"
    "/src/main/resources/assets/forgeamidst/biome/default.json"
)


RELEASE_API = "https://api.github.com/repos/OrderedSet86/gtnh-determinism/releases/latest"
RELEASE_JARS = ("worldgenprobe", "gtnhdeterminism")


def fetch_release_jars():
    """The probe and fix jars from the latest release, cached by tag.

    Only used when neither a vendored copy nor a developer build is present. The qol jar is a
    client-side convenience mod and has no business on a measurement server, so it is skipped.
    """
    rel = fetch_json(RELEASE_API)
    tag = rel.get("tag_name") or "latest"
    out = {}
    for asset in rel.get("assets", []):
        stem = asset["name"].split("-")[0]
        if stem not in RELEASE_JARS:
            continue
        dest = cache_root() / "jars" / tag / asset["name"]
        if not dest.is_file():
            log(f"  fetching {asset['name']} ({asset['size'] / 1e3:.0f} kB) from release {tag}")
            download_to(asset["browser_download_url"], dest)
        out[stem] = dest
    missing = set(RELEASE_JARS) - set(out)
    if missing:
        die(f"release {tag} has no asset for: {', '.join(sorted(missing))}")
    return out


def palette_sources(instance):
    """The two optional inputs that decide whether the map's colours are real or synthesised.

    Neither is required to produce a bundle, which is exactly why they are easy to lose: without them
    the map renders with synthesised biome colours and untinted grass, looking merely odd rather than
    broken. The Amidst table is a small public JSON; the client jar is already in Prism's own
    launcher-wide library cache, since that is what it runs the game from.
    """
    amidst = cache_root() / "amidst-biomes.json"
    if not amidst.is_file():
        try:
            download_to(AMIDST_URL, amidst)
        except RuntimeError as e:
            log(f"  warning: could not fetch the Amidst biome table ({e}); colours will be synthesised")
            amidst = None
    mc_jar = instance["root"] / "libraries" / "com" / "mojang" / "minecraft" / MC_VERSION / f"minecraft-{MC_VERSION}-client.jar"
    if not mc_jar.is_file():
        log(f"  warning: no client jar at {mc_jar}")
        log("    grass and foliage will render grey instead of green. Launching the instance once")
        log("    in Prism downloads it.")
        mc_jar = None
    return amidst, mc_jar


# ------------------------------------------------------------------------------------- assembly


def cache_root():
    if platform.system() == "Windows":
        base = os.environ.get("LOCALAPPDATA") or str(Path.home())
        # Short on purpose: libraries/ and mods/ nest deeply and GTNH server dirs have hit MAX_PATH.
        return Path(base) / "gtnh-routemap"
    return Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "gtnh-routemap"


def long_path(p):
    """Windows caps paths at 260 chars unless they are \\\\?\\-prefixed."""
    if platform.system() != "Windows":
        return str(p)
    p = Path(p).resolve()
    s = str(p)
    return s if s.startswith("\\\\?\\") else "\\\\?\\" + s


def assemble(instance, dest, pack_version=None, force=False):
    version = pack_version or instance["pack_version"]
    if not version:
        die(
            f"cannot read a pack version from instance name {instance['name']!r}.\n"
            "  Pass --pack-version explicitly (e.g. 2.8.4). Seed reports do not transfer across pack\n"
            "  versions, so guessing here would produce a map of a different world."
        )

    mc = instance["minecraft"]
    # Fingerprint of the instance's mod set. A failed run leaves the assembled server behind, and
    # matching only on pack version then reuses it forever -- after restoring a quarantined jar the
    # instance had 242 jars while the stale server still had 215, and the next run booted the old
    # one and failed identically. Any change to the instance has to invalidate the build.
    mod_names = sorted(p.name for p in (mc / "mods").iterdir() if p.is_file())
    fingerprint = hashlib.sha256("\n".join(mod_names).encode()).hexdigest()[:16]

    stamp = dest / ".routemap-assembled.json"
    if stamp.is_file() and not force:
        prev = json.loads(stamp.read_text())
        # A stamp with no fingerprint came from an older build of this tool and says nothing about
        # whether the mods still match, so treat it as stale rather than trusting it.
        if prev.get("mods_fingerprint") != fingerprint:
            log("  the instance's mods changed since this server was built; rebuilding")
            prev = {}
        if prev.get("pack_version") == version:
            log(f"server already assembled at {dest} (pack {version}); use --force to rebuild")
            return dest
    if dest.exists() and force:
        shutil.rmtree(long_path(dest))
    dest.mkdir(parents=True, exist_ok=True)

    log(f"assembling a {version} server from {instance['name']}")
    log("  resolving the pack's mod list")
    sides, any_version = resolve_mods(version)
    excluded = server_file_exclusions()

    # ---- mods
    src_mods, dst_mods = mc / "mods", dest / "mods"
    dst_mods.mkdir(exist_ok=True)
    # Exact filename first, then mod identity. Without the stem fallback a point-release
    # difference between the manifest and the instance silently drops a required mod.
    by_stem = {mod_stem(f): i for f, i in sides.items()}
    kept, pruned, unknown = [], [], []
    copied_bytes = 0
    for entry in sorted(src_mods.iterdir()):
        if entry.is_dir():
            # Mod data directories (OpenSecurity, ic2, carpentersblocks) travel with the pack, minus
            # whatever the pack's own server_exclusions strips out of them. Always a real copy: these
            # hold loose files the game rewrites.
            shutil.copytree(
                long_path(entry),
                long_path(dst_mods / entry.name),
                dirs_exist_ok=True,
                ignore=lambda d, names: [
                    n for n in names if f"mods/{Path(d).name}/{n}" in excluded or f"mods/{entry.name}/{n}" in excluded
                ],
            )
            continue
        if not entry.name.endswith(".jar"):
            continue
        info = sides.get(entry.name) or by_stem.get(mod_stem(entry.name))
        if info is None:
            # KEEP it. Dropping unrecognised mods was backwards: the player is mapping their own
            # world, so a worldgen mod the pack does not list is part of the world they are actually
            # playing, and leaving it out produces a map of somewhere else. Full generation records
            # whatever really generates, so extra worldgen mods are handled correctly by keeping
            # them. The only mods that must go are ones positively identified as CLIENT below --
            # and if an unrecognised one turns out to be client-only, Forge names it at boot.
            unknown.append(entry.name)
            shutil.copy2(long_path(entry), long_path(dst_mods / entry.name))
            copied_bytes += entry.stat().st_size
            kept.append(entry.name)
        elif info["side"] in KEEP_SIDES:
            shutil.copy2(long_path(entry), long_path(dst_mods / entry.name))
            copied_bytes += entry.stat().st_size
            kept.append(entry.name)
        else:
            pruned.append(f"{entry.name} ({info['side']})")

    log(f"  mods: kept {len(kept)}, pruned {len(pruned)} client-side")

    # Mods the pack requires that are not in the instance at all. This is the antivirus case seen
    # from the other side: Defender quarantined gtnhextlib-1.0.4.jar straight out of the instance, so
    # it was never copied and nothing downstream noticed until the server died 29 seconds into boot
    # with a missing library. Absent-before-we-start is invisible to the vanished-after-copy check
    # below, so it needs its own.
    # Two things this must NOT treat as missing, both seen on a healthy instance:
    #   * SERVER-side mods (JourneyMapServer), which a client install never has -- they are fetched.
    #   * a different point release of the right mod. The manifest pinned AdvancedSolarPanel 3.5.1
    #     while the instance had 3.5.1a, so exact-filename matching called the same mod both
    #     "unknown, dropped" and "required, missing" at once.
    # So compare by mod identity, using every filename the asset index knows for that mod.
    present = {mod_stem(e.name) for e in src_mods.iterdir() if e.is_file() and e.name.endswith(".jar")}
    expected = {
        mod_stem(f): i["name"] for f, i in sides.items() if i["side"] in ("BOTH", "BOTH_JAVA9")
    }
    absent = sorted(name for st, name in expected.items() if st not in present)
    if absent:
        # A WARNING, not a hard stop. This heuristic has already misfired twice on a perfectly
        # healthy instance -- once on a SERVER-side mod a client never has, once on
        # AdvancedSolarPanel 3.5.1a where the instance carried a build the asset index has never
        # heard of. Refusing to run on a guess is worse than the failure it prevents, and the boot
        # failure it explains is fast and loud anyway; the point is to make the connection
        # obvious when it happens.
        log(f"  warning: {len(absent)} mod(s) the {version} pack lists are not in your instance:")
        for a in absent:
            log(f"    {a}")
        log("    If the server then fails to boot, this is why. Antivirus is the usual cause --")
        log("    GTNH mod jars bundle nested jars, which reads as a self-extracting archive, and")
        log("    Defender has been measured quarantining gtnhextlib. Check your quarantine.")

    # Every jar we just wrote gets scanned again where it landed, and Windows Defender
    # false-positives on GTNH mod jars: measured on 2.9.0-beta-3, it quarantined
    # gtnhextlib-1.0.4.jar as Trojan:Win32/Egairtigado!rfn -- a cloud heuristic firing on a jar that
    # contains nested jars, with zero Windows executables in it. A silently missing mod makes the
    # server fail to boot for reasons nobody would connect to antivirus, so check and say so.
    vanished = [name for name in kept if not (dst_mods / name).is_file()]
    if vanished:
        die(
            f"{len(vanished)} mod jar(s) disappeared right after being copied:\n"
            + "\n".join(f"    {v}" for v in vanished)
            + "\n  Antivirus almost certainly quarantined them. GTNH mod jars are a known false\n"
            "  positive -- they bundle nested jars, which reads as a self-extracting archive.\n"
            "  Restore them, and exclude this directory from real-time scanning:\n"
            f"    {dest}"
        )
    log(f"  mods: {copied_bytes / 1e6:.0f} MB copied")
    if unknown:
        log(f"  kept {len(unknown)} jar(s) the {version} manifest does not list:")
        for name in unknown:
            log(f"    {name}")
        log("    These are mods you added yourself, and they are kept so the map describes the")
        log("    world you actually play. Forge skips properly-declared client-only mods by itself,")
        log("    and if an undeclared one crashes the boot it is set aside automatically -- your")
        log("    instance is never modified either way.")

    # ---- the SERVER-side mods a client install by definition does not have
    for filename, info in sorted(sides.items()):
        if info["side"] == "SERVER" and not (dst_mods / filename).is_file():
            if not info["url"]:
                log(f"  warning: no download URL for server-side mod {filename}")
                continue
            cached = cache_root() / "scaffolding" / "server-mods" / filename
            if not cached.is_file():
                log(f"  fetching server-side mod {filename}")
                download_to(info["url"], cached)
            shutil.copy2(long_path(cached), long_path(dst_mods / filename))

    # ---- config
    log("  copying config")
    shutil.copytree(
        long_path(mc / "config"),
        long_path(dest / "config"),
        dirs_exist_ok=True,
        ignore=lambda d, names: [n for n in names if f"config/{n}/" in excluded or f"config/{n}" in excluded],
    )
    for extra in ("scripts", "serverutilities"):
        if (mc / extra).is_dir():
            shutil.copytree(long_path(mc / extra), long_path(dest / extra), dirs_exist_ok=True)
    zz = dest / "scripts" / "ZZ-Client-Only.zs"
    if zz.is_file():
        zz.unlink()

    # ---- launch jar, harvested from the instance so it matches the pack exactly
    launch_src = next((instance["dir"] / "libraries").glob("lwjgl3ify-*-forgePatches.jar"), None)
    if launch_src is None:
        die(
            f"no lwjgl3ify-*-forgePatches.jar under {instance['dir'] / 'libraries'}.\n"
            "  Launch the instance in Prism once so it downloads its libraries, then retry."
        )
    launch_jar = dest / "lwjgl3ify-forgePatches.jar"
    shutil.copy2(long_path(launch_src), long_path(launch_jar))
    log(f"  launch jar: {launch_src.name}")

    # ---- scaffolding the client cannot provide
    # Downloaded into a cache that OUTLIVES the server directory, because the server itself is
    # transient: it gets deleted after a successful run, and re-downloading 13 MB every time to
    # rebuild it would be rude on a metered connection.
    scaffold = cache_root() / "scaffolding"
    forge_version = instance["forge"]
    forge_jar = f"forge-{MC_VERSION}-{forge_version}-{MC_VERSION}-universal.jar"
    forge_rel = f"net/minecraftforge/forge/{MC_VERSION}-{forge_version}-{MC_VERSION}/{forge_jar}"
    cached_forge = scaffold / forge_rel
    if not cached_forge.is_file():
        repo, size = resolve_library(forge_rel, cached_forge)
        log(f"  {forge_jar}: {size / 1e6:.1f} MB from {repo}")
    shutil.copy2(long_path(cached_forge), long_path(dest / forge_jar))

    cached_server = scaffold / f"minecraft_server.{MC_VERSION}.jar"
    if not cached_server.is_file():
        log("  minecraft_server: resolving via Mojang's version manifest")
        size = download_to(mojang_server_jar_url(), cached_server)
        log(f"  minecraft_server.{MC_VERSION}.jar: {size / 1e6:.1f} MB")
    shutil.copy2(long_path(cached_server), long_path(dest / f"minecraft_server.{MC_VERSION}.jar"))

    # ---- libraries, from the launch jar's own Class-Path
    class_path = (manifest_attr(launch_jar, "Class-Path") or "").split()
    libs = [e for e in class_path if e.startswith("libraries/")]
    if not libs:
        die(f"{launch_jar.name} declares no libraries/ entries on its Class-Path")
    log(f"  libraries: {len(libs)} declared by the launch jar")
    prism_libs = instance["root"] / "libraries"
    harvested = fetched = 0
    for entry in libs:
        rel = entry[len("libraries/") :]
        out = dest / "libraries" / rel
        if out.is_file():
            continue
        local = prism_libs / rel
        if not local.is_file():
            local = scaffold / rel
            if not local.is_file():
                resolve_library(rel, local)
            fetched += 1
        else:
            harvested += 1
        out.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(long_path(local), long_path(out))
    log(f"  libraries: {harvested} harvested from Prism, {fetched} downloaded")

    # ---- generated files
    n = write_java9args(launch_jar, dest / "java9args.txt")
    log(f"  java9args.txt: generated from Add-Opens ({n} entries)")
    (dest / "eula.txt").write_text("eula=true\n", encoding="utf-8")

    stamp.write_text(
        json.dumps(
            {
                "pack_version": version,
                "instance": instance["name"],
                "forge": forge_version,
                "launch_jar": launch_src.name,
                "mods_fingerprint": fingerprint,
                "mods_kept": len(kept),
                "mods_pruned": len(pruned),
                "mods_dropped_unknown": unknown,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    log(f"assembled: {dest}")
    return dest


# ------------------------------------------------------------------------------------------ CLI


def cmd_list(args):
    instances = find_instances()
    if not instances:
        die(
            "no Prism Launcher installation with a 1.7.10 Forge instance found.\n"
            f"  Looked in: {', '.join(str(p) for p in prism_roots()) or '(no Prism data directory at all)'}"
        )
    for inst in instances:
        jre = find_jre(inst)
        version = inst["pack_version"] or "unknown"
        note = "" if jre else "   [no usable JRE -- launch this instance in Prism once]"
        log(f"{inst['name']}")
        log(f"    pack {version}   forge {inst['forge']}   java {java_major(jre) if jre else '-'}{note}")


def cmd_assemble(args):
    instances = {i["name"]: i for i in find_instances()}
    inst = instances.get(args.instance)
    if inst is None:
        die(f"no instance named {args.instance!r}. Known: {', '.join(sorted(instances)) or '(none)'}")
    jre = find_jre(inst)
    if jre is None:
        die(
            f"no JRE in range for {inst['name']}.\n"
            "  Launch the instance in Prism once so it downloads a runtime, then retry."
        )
    log(f"java: {jre} (major {java_major(jre)})")
    dest = Path(args.dest) if args.dest else cache_root() / "servers" / (args.pack_version or inst["pack_version"] or inst["name"])
    assemble(inst, dest, pack_version=args.pack_version, force=args.force)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list", help="show GTNH instances Prism knows about")
    a = sub.add_parser("assemble", help="build a headless server from one instance")
    a.add_argument("instance")
    a.add_argument("--dest", help="server directory (default: per-version under the cache root)")
    a.add_argument("--pack-version", help="override the version parsed from the instance name")
    a.add_argument("--force", action="store_true", help="rebuild even if already assembled")
    args = ap.parse_args()
    {"list": cmd_list, "assemble": cmd_assemble}[args.cmd](args)


if __name__ == "__main__":
    main()
