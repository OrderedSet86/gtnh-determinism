#!/usr/bin/env python3
"""Install a prepared VisualProspecting ore-vein cache into a world.

usage: install-visualprospecting.py <world-dir> <cache-dir> [--dry-run]

  world-dir   the world save: the folder containing level.dat and data/visualprospecting.dat
              (server: <server>/World, single player: .minecraft/saves/<name>)
  cache-dir   the folder holding DIM0.dat / DIM7.dat to install

VisualProspecting keys its cache by a world id stored in <world>/data/visualprospecting.dat, and puts
the per-dimension files in <instance>/visualprospecting/server/<worldId>/. The id is generated per
world, so a prepared cache cannot simply be copied — the folder has to be named after the TARGET
world's id. This reads that id and does the copy.

The id is the `wId` string in that gzipped NBT. It is read by locating the tag and taking the
length-prefixed value that follows, which needs no NBT library and — unlike matching a fixed prefix —
does not assume the level's name. The id is `<levelName>_<uuid>`: a dedicated server whose level is
called "World" yields `World_<uuid>`, but a single-player save named after its seed yields
`-1636594104014467454_<uuid>`. Matching on `World_` looked right against one server sample and found
nothing on every single-player world.

Refuses rather than guessing if the file is missing or unparseable — a wrong id silently produces a
cache the mod ignores, which is indistinguishable from "the veins are not there".
"""
import argparse
import gzip
import pathlib
import shutil
import struct
import sys


def world_id(world_dir):
    dat = pathlib.Path(world_dir) / "data" / "visualprospecting.dat"
    if not dat.is_file():
        sys.exit(f"no {dat}\n"
                 f"  VisualProspecting writes it the first time the world runs with the mod installed.\n"
                 f"  Load the world once, quit, then re-run this.")
    raw = dat.read_bytes()
    try:
        blob = gzip.decompress(raw)
    except OSError:
        blob = raw
    # TAG_String payload: 2-byte big-endian length, then UTF-8. The name "wId" is itself stored the
    # same way, so the value starts right after it.
    i = blob.find(b"wId")
    if i < 0:
        sys.exit(f"{dat} contains no wId tag — refusing to guess an id")
    p = i + 3
    (n,) = struct.unpack_from(">H", blob, p)
    val = blob[p + 2:p + 2 + n]
    if len(val) != n or not val:
        sys.exit(f"{dat}: wId is truncated — refusing to guess an id")
    return val.decode("utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("world_dir")
    ap.add_argument("cache_dir")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    world = pathlib.Path(args.world_dir).expanduser().resolve()
    cache = pathlib.Path(args.cache_dir).expanduser().resolve()
    if cache.is_dir() and not any(cache.glob("DIM*.dat")):
        sub = [d for d in cache.iterdir() if d.is_dir() and any(d.glob("DIM*.dat"))]
        if len(sub) == 1:
            cache = sub[0]
    dims = sorted(cache.glob("DIM*.dat"))
    if not dims:
        sys.exit(f"no DIM*.dat under {cache}")

    wid = world_id(world)
    # The mod's storage root is the INSTANCE dir (server root / .minecraft), i.e. the world's parent
    # for a server layout, or saves/.. for single player.
    inst = world.parent.parent if world.parent.name == "saves" else world.parent
    vp = inst / "visualprospecting"

    # BOTH caches. server/<worldId>/ is authoritative, but the map renders from
    # client/<playerUuid>/<worldId>/ — populating only the server side leaves the map blank, which is
    # exactly how this first failed. The two use an identical schema (version 3, same ore keys, same
    # source/depleted encoding), verified by comparing a discovered client file against its server
    # counterpart, so the same bytes serve both.
    dests = [vp / "server" / wid]
    client_root = vp / "client"
    players = sorted(p for p in client_root.glob("*") if p.is_dir()) if client_root.is_dir() else []
    for p in players:
        dests.append(p / wid)

    print(f"world      : {world}")
    print(f"world id   : {wid}")
    print(f"installing : {', '.join(d.name for d in dims)}")
    for d in dests:
        print(f"destination: {d}")
    if not players:
        print("NOTE: no client profile directory found under visualprospecting/client/.\n"
              "      Load the world once with VisualProspecting installed so the client cache is\n"
              "      created, then re-run — otherwise nothing will be drawn on the map.")
    if args.dry_run:
        print("\n--dry-run: nothing written")
        return 0
    for dest in dests:
        dest.mkdir(parents=True, exist_ok=True)
        for d in dims:
            target = dest / d.name
            if target.exists():
                backup = target.with_suffix(".dat.bak")
                shutil.copy2(target, backup)
                print(f"  existing {target.parent.parent.name}/{d.name} backed up to {backup.name}")
            shutil.copy2(d, target)
            print(f"  wrote {target} ({target.stat().st_size} bytes)")
    print("\ndone — start the game; the veins appear as already prospected.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
