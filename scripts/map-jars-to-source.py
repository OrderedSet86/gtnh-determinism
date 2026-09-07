#!/usr/bin/env python3
"""Map every jar a pack ships to the source repo that built it, and name the ones with no source.

    map-jars-to-source.py <server-dir> [--src DIR] [--json OUT]

The census of populate-RNG consumers is a SOURCE reading exercise, so the first question is which
jars can be read as source at all. Filenames do not answer it: `ProjRed-4.12.43-GTNH.jar` comes from
`ProjectRed`, `ae2fc-1.5.105-gtnh.jar` from `AE2FluidCraft-Rework`, and several repos build more than
one jar. The mod id does answer it, and both sides carry one — the jar in its `mcmod.info`, the repo
in `src/main/resources/mcmod.info` — so match on that and fall back to a normalised name only for
the jars that ship no `mcmod.info` at all.

WHAT THIS IS FOR, and what it is not for: the unmatched list is the ONLY set that justifies javap.
Everything matched must be read from `--src`. Decompiling a mod whose source is one directory away
produces worse text and invites reading obfuscated member names as if they were the real API.

VERSION IS REPORTED, NOT ENFORCED. The repo checkouts are whatever branch they sit on, which is not
necessarily the commit that built the shipped jar. This script prints both versions side by side so
the gap is visible; closing it is a `git checkout` decision per repo, made by a human, because GTNH
tagging is not uniform (some repos tag `1.2.3`, some `v1.2.3`, some only have branch heads).
"""
import argparse
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

# mcmod.info is hand-edited JSON in a lot of 1.7.10 mods and frequently not parseable: unescaped
# backslashes in Windows paths, trailing commas, raw newlines inside strings. Regex over the raw
# bytes rather than json.loads, because a strict parse silently drops the worst-formatted mods and
# those are exactly the old ones this pack is full of.
MODID_RE = re.compile(rb'"modid"\s*:\s*"([^"]+)"')
VERSION_RE = re.compile(rb'"version"\s*:\s*"([^"]+)"')

# GTNH repos template their mcmod.info — every one of them literally says `"modid": "${modId}"`,
# resolved at build time from gradle.properties. So the repo-side key is gradle.properties, and
# reading the repo's mcmod.info gets you the string `${modId}` for 150-odd repos at once. That is
# what made the first version of this script report 48 unmatched jars, most of which do have source.
GRADLE_MODID_RE = re.compile(r"^\s*modId\s*=\s*(\S+)\s*$", re.M)

# Jars whose modid matches nothing because the repo builds under a different id, or which ship no
# usable mcmod.info at all.
NAME_ALIASES = {
    "projred": "ProjectRed",
    "ae2fc": "AE2FluidCraft-Rework",
    "emt": "Electro-Magic-Tools",
    "tconstruct": "TinkersConstruct",
    "gregtech": "GT5-Unofficial",
    "gtnewhorizonscoremod": "NewHorizonsCoreMod",
    "etfuturum": "Et-Futurum-Requiem",
    "roguelike": "Roguelike-Dungeons",
    "binniemods": "Binnie",
    "forestry": "ForestryMC",
}


def norm(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())


def jar_identity(path):
    """(modids, version) from a jar's mcmod.info; empty modids if it has none."""
    try:
        with zipfile.ZipFile(path) as z:
            if "mcmod.info" not in z.namelist():
                return [], None
            raw = z.read("mcmod.info")
    except (zipfile.BadZipFile, KeyError, OSError):
        return [], None
    ids = [m.group(1).decode("utf-8", "replace") for m in MODID_RE.finditer(raw)]
    ver = VERSION_RE.search(raw)
    return ids, ver.group(1).decode("utf-8", "replace") if ver else None


def repo_index(src):
    """modid -> repo dir, and normalised repo name -> repo dir, over every repo in the source tree."""
    by_modid, by_name = {}, {}
    for repo in sorted(p for p in src.iterdir() if p.is_dir()):
        by_name[norm(repo.name)] = repo
        gp = repo / "gradle.properties"
        if gp.is_file():
            try:
                m = GRADLE_MODID_RE.search(gp.read_text(errors="replace"))
            except OSError:
                m = None
            if m:
                # First repo wins: forks and vendored copies sort after the canonical repo for every
                # case observed in this tree.
                by_modid.setdefault(m.group(1), repo)
        for info in list(repo.glob("src/main/resources/mcmod.info")) + list(
            repo.glob("*/src/main/resources/mcmod.info")
        ):
            try:
                raw = info.read_bytes()
            except OSError:
                continue
            for m in MODID_RE.finditer(raw):
                mid = m.group(1).decode("utf-8", "replace")
                if not mid.startswith("${"):
                    by_modid.setdefault(mid, repo)
    return by_modid, by_name


def git(repo, *args):
    try:
        r = subprocess.run(
            ["git", "-C", str(repo), *args], capture_output=True, text=True, timeout=20
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    return r.stdout.strip() if r.returncode == 0 else None


def version_state(repo, jar_version):
    """Where the checkout sits relative to the tag that built the shipped jar.

    GTNH tagging is not uniform — some repos tag `1.2.3`, some `v1.2.3`, some carry no tag for the
    build at all — so try both spellings and report honestly rather than guessing. `ALIGNED` means
    HEAD is that exact commit; `AVAILABLE` means the tag exists and a checkout would close the gap;
    `NO-TAG` means it does not and the jar was built from a branch head or a CI artifact.
    """
    if not (repo / ".git").exists():
        return "not-a-git-repo", None, None
    head = git(repo, "rev-parse", "--short", "HEAD")
    describe = git(repo, "describe", "--tags", "--always", "--dirty") or head
    if not jar_version:
        return "no-jar-version", describe, None
    for tag in (jar_version, "v" + jar_version):
        sha = git(repo, "rev-list", "-n", "1", tag)
        if sha:
            head_full = git(repo, "rev-parse", "HEAD")
            return ("ALIGNED" if sha == head_full else "AVAILABLE"), describe, tag
    return "NO-TAG", describe, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("server_dir")
    ap.add_argument("--src", default=str(Path(__file__).resolve().parent.parent.parent / "all-gtnh"))
    ap.add_argument("--json")
    ap.add_argument(
        "--versions",
        action="store_true",
        help="also report, per matched repo, whether the checkout matches the shipped jar's version tag",
    )
    ap.add_argument(
        "--only",
        metavar="FILE",
        help="restrict the version report to the repos named one per line in FILE "
        "(the consumer set — version-aligning 191 repos to read 40 is waste)",
    )
    args = ap.parse_args()

    mods = Path(args.server_dir) / "mods"
    src = Path(args.src)
    if not mods.is_dir():
        sys.exit(f"no mods dir at {mods}")
    if not src.is_dir():
        sys.exit(f"no source tree at {src}")

    by_modid, by_name = repo_index(src)
    rows = []
    for jar in sorted(mods.glob("*.jar")):
        ids, ver = jar_identity(jar)
        repo, how = None, None
        for mid in ids:
            if mid in by_modid:
                repo, how = by_modid[mid], f"modid:{mid}"
                break
        if repo is None:
            # Strip the version tail before normalising, or `Railcraft-9.17.31` never matches
            # `Railcraft`. The tail is everything from the first digit group that follows a dash.
            stem = re.split(r"-\d|-mc\d|\[", jar.stem)[0]
            key = norm(stem)
            if key in NAME_ALIASES:
                repo, how = by_name.get(norm(NAME_ALIASES[key])), f"alias:{key}"
            elif key in by_name:
                repo, how = by_name[key], f"name:{stem}"
        rows.append(
            {
                "jar": jar.name,
                "modids": ids,
                "jar_version": ver,
                "repo": repo.name if repo else None,
                "matched_by": how,
            }
        )

    matched = [r for r in rows if r["repo"]]
    unmatched = [r for r in rows if not r["repo"]]
    print(f"{len(rows)} jars: {len(matched)} with source in {src}, {len(unmatched)} without\n")
    print("NO SOURCE REPO — the only jars javap is justified on:")
    for r in unmatched:
        print(f"  {r['jar']:<52} modids={','.join(r['modids']) or '-'}")

    if args.versions:
        keep = None
        if args.only:
            keep = {ln.strip() for ln in Path(args.only).read_text().splitlines() if ln.strip()}
        print("\nversion alignment (repo checkout vs shipped jar):")
        for r in matched:
            if keep is not None and r["repo"] not in keep:
                continue
            state, describe, tag = version_state(src / r["repo"], r["jar_version"])
            r["version_state"], r["repo_describe"], r["repo_tag"] = state, describe, tag
            flag = "  " if state == "ALIGNED" else "! "
            print(
                f"  {flag}{r['repo']:<34} jar={str(r['jar_version']):<18} "
                f"head={str(describe):<26} {state}"
            )

    if args.json:
        Path(args.json).write_text(json.dumps(rows, indent=2))
        print(f"\nwrote {args.json}")


if __name__ == "__main__":
    main()
