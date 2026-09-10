#!/usr/bin/env python3
"""What produced a world directory. Written by run-probe.sh, printed by every diff tool.

    probe-provenance.py write <world-dir> --server-dir D [--stock D] [--param k=v]... [--config-set S]...
    probe-provenance.py show  <world-dir>

The file lands at `<world-dir>/.probe-provenance.json`, INSIDE the world, so it survives the `cp -a`
that every measurement does. A sibling sidecar does not, and a sidecar nobody remembers to read is the
same as no record at all — which is how an A/B once ran with arms differing by version AND md5 AND the
change under test, and was only caught afterwards (results/2026-09-05-gt-ore-dryrun-virgin).

WHAT IT DELIBERATELY DOES NOT DO: copy `config/`, or list a hash per config file. 8,600 files of
unreadable output is the same failure as recording nothing. Instead it records the DELTA against the
shipped pack config, which on a clean server dir is 5 keys — so an arm's difference from stock is
legible at a glance rather than inferred from a hash.

The digest is SEMANTIC, not byte-level: sorted section-qualified `key=value` pairs, ignoring comments,
whitespace and key order. Forge rewrites config files in place on shutdown, so a byte digest would flap
between two identical runs and train everyone to ignore it.

Only `.cfg` files are compared; other config formats (json, properties) are counted, not diffed.
"""
import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
# The pack repo sits beside this one: cloned-gtnh/{gtnh-determinism,all-gtnh}
DEFAULT_STOCK = HERE.parent.parent / "all-gtnh" / "GT-New-Horizons-Modpack" / "config"
FILENAME = ".probe-provenance.json"
DELTA_CAP = 50

# Forge typed keys: `B:key=value`, and the section headers that scope them.
KV = re.compile(r"^\s*([BISDN]):([^=]+)=(.*)$")
# gtnhsplash is a worldgen amplifier, so a run that carries it is not a stock-density world and an
# arm that carries a different build of it is not comparable. It was absent from this tuple for the
# 2026-09-08 splash runs, whose four worlds therefore record only the probe and fix jars; their
# amplifier md5 (dfefe3ca0f03dc29cabb13c4703dfda5, identical in both arms) had to be checked by
# hand. That is exactly the check this file exists to make unnecessary.
JAR_PREFIXES = ("gtnhdeterminism", "worldgenprobe", "gtnhsplash")


def settings(path):
    """Section-qualified `key -> value` for one Forge .cfg, order- and format-independent."""
    out, sect = {}, []
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                s = line.strip()
                if not s or s.startswith("#"):
                    continue
                m = KV.match(line)
                if m:
                    out[".".join(sect + [m.group(2)])] = m.group(3).strip()
                elif s.endswith("{"):
                    sect.append(s[:-1].strip())
                elif s == "}" and sect:
                    sect.pop()
    except OSError:
        return {}
    return out


# Java's Properties.store() and IC2's ini writer stamp the current date into a comment on every boot,
# so hashing these raw makes the digest differ between two identical runs. Measured on daily-707: of
# 8,605 config files exactly 4 churn across a boot (DreamCoreMod.properties, hodgepodgeEarly.properties,
# jarjar.properties, IC2.ini) and all 4 differ only in that timestamp line. Comments are not settings,
# so they are stripped. Formats not listed here are still hashed raw — if a new one starts churning it
# shows up as a DIFFERS on a same-arm pair, which is the signal to add its extension.
LINE_CONFIG = {".properties": "#!", ".ini": ";#"}


def cfg_files(root):
    for dirpath, _dirs, files in os.walk(root):
        for fn in files:
            p = Path(dirpath) / fn
            yield p.relative_to(root).as_posix(), p


def config_digest(config_dir):
    """One md5 over the whole config tree: .cfg files by their SEMANTIC settings, everything else by
    content. The .cfg half is format-independent because Forge rewrites those files on shutdown and a
    byte digest would flap between two identical runs — an alarm that fires on nothing trains everyone
    to ignore it. The rest (json, lang, scripts) is hashed raw so the tree has no blind spot; if that
    half flaps, the header's "trees differ but settings are identical" warning names it."""
    h = hashlib.md5()
    other = 0
    for rel, p in sorted(cfg_files(config_dir)):
        h.update(rel.encode())
        if rel.endswith(".cfg"):
            for k, v in sorted(settings(p).items()):
                h.update(f"\0{k}={v}".encode())
            continue
        other += 1
        comments = LINE_CONFIG.get(p.suffix.lower())
        try:
            if comments is None:
                h.update(hashlib.md5(p.read_bytes()).digest())
            else:
                for line in sorted(_meaningful_lines(p, comments)):
                    h.update(f"\0{line}".encode())
        except OSError:
            h.update(b"\0unreadable")
    return h.hexdigest(), other


def _meaningful_lines(path, comment_chars):
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            s = line.strip()
            if s and s[0] not in comment_chars:
                yield s


def config_deltas(config_dir, stock_dir):
    """Keys whose value differs from the shipped pack config. Empty on a clean server dir."""
    if not stock_dir or not Path(stock_dir).is_dir():
        return None, 0
    deltas, only = [], 0
    for rel, p in sorted(cfg_files(config_dir)):
        if not rel.endswith(".cfg"):
            continue
        sp = Path(stock_dir) / rel
        if not sp.exists():
            only += 1
            continue
        a, b = settings(p), settings(sp)
        for k in sorted(a):
            if k in b and a[k] != b[k]:
                deltas.append(f"{rel}:{k}={a[k]} (stock: {b[k]})")
    return deltas, only


def jars(server_dir):
    mods = Path(server_dir) / "mods"
    out = []
    if not mods.is_dir():
        return out
    for p in sorted(mods.iterdir()):
        if p.is_file() and p.name.startswith(JAR_PREFIXES):
            out.append({"name": p.name, "md5": hashlib.md5(p.read_bytes()).hexdigest()})
    return out


def build(server_dir, stock_dir, params, config_sets):
    config_dir = Path(server_dir) / "config"
    rec = {"params": params, "jars": jars(server_dir), "config_set": config_sets}
    if config_dir.is_dir():
        digest, other = config_digest(config_dir)
        deltas, only = config_deltas(config_dir, stock_dir)
        rec["config_digest"] = digest
        rec["config_files_not_compared"] = other
        rec["config_files_absent_from_stock"] = only
        if deltas is None:
            rec["config_deltas"] = None
            rec["config_deltas_note"] = "stock reference unavailable"
        else:
            rec["config_deltas_count"] = len(deltas)
            rec["config_deltas"] = deltas[:DELTA_CAP]
            if len(deltas) > DELTA_CAP:
                rec["config_deltas_note"] = f"truncated to {DELTA_CAP} of {len(deltas)}"
    return rec


def load(world):
    """The record for a world dir, or None. Never raises — a missing or corrupt file is reported by
    the caller as UNKNOWN rather than aborting a diff of worlds generated before this existed."""
    try:
        with open(Path(world) / FILENAME, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def _flat(rec):
    """Comparable field -> displayable value. A list value renders one entry per line."""
    if rec is None:
        return None
    out = {f"param.{k}": v for k, v in (rec.get("params") or {}).items()}
    out["jars"] = ", ".join(f"{j['name']} ({j['md5'][:8]})" for j in rec.get("jars") or []) or "none"
    out["config_digest"] = (rec.get("config_digest") or "?")[:12]
    d = rec.get("config_deltas")
    out["config_deltas"] = ["stock reference unavailable"] if d is None else list(d)
    if rec.get("config_set"):
        out["config_set"] = list(rec["config_set"])
    return out


def _emit(key, value, width, indent=""):
    if isinstance(value, list):
        if not value:
            print(f"  {key:<{width}}  {indent}none")
            return
        print(f"  {key:<{width}}  {indent}{value[0]}")
        for v in value[1:]:
            print(f"  {'':<{width}}  {indent}{v}")
    else:
        print(f"  {key:<{width}}  {indent}{value}")


def header(world_a, world_b, label_a="A", label_b="B"):
    """Print both arms' provenance and mark every field that differs.

    Always printed, never behind a flag: an A/B should show exactly ONE differing field, and anything
    more means the arms are confounded and the number underneath is not attributable to the change.
    """
    a, b = _flat(load(world_a)), _flat(load(world_b))
    print("=" * 78)
    for lbl, rec, w in ((label_a, a, world_a), (label_b, b, world_b)):
        if rec is None:
            print(f"  !! PROVENANCE UNKNOWN for {lbl}: {w}")
            print("     (world predates provenance stamping, or the file was lost in a copy)")
    if a is None or b is None:
        # Field-by-field marking against a missing record would flag everything and mean nothing.
        known, klbl = (a, label_a) if a else (b, label_b)
        if known:
            width = max(len(k) for k in known)
            print(f"  -- provenance for {klbl} only --")
            for k in sorted(known):
                _emit(k, known[k], width)
        print("=" * 78)
        return

    keys = sorted(set(a) | set(b))
    width = max(len(k) for k in keys)
    ndiff = 0
    for k in keys:
        va, vb = a.get(k, "-"), b.get(k, "-")
        if va == vb:
            # Identical stock deltas are the normal case and say nothing; collapse to a count so the
            # header stays scannable and only expands when it is actually carrying information.
            if k == "config_deltas" and isinstance(va, list) and len(va) > 1:
                print(f"  {k:<{width}}  {len(va)} keys differ from stock, identically in both arms")
            else:
                _emit(k, va, width)
            continue
        ndiff += 1
        _emit(k, va, width, f"{label_a}: ")
        _emit("", vb, width, f"{label_b}: ")
        print(f"  {'':<{width}}  <<< DIFFERS")
    if a["config_digest"] != b["config_digest"] and a["config_deltas"] == b["config_deltas"]:
        print("  !! config trees differ but the stock-comparable settings are identical —")
        print("     the divergence is somewhere this comparison cannot see. Arms not trustworthy.")
    if ndiff > 1:
        print(f"  !! {ndiff} fields differ. An A/B isolates ONE variable; this pair does not,")
        print("     so any difference measured below is not attributable to a single cause.")
    print("=" * 78)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    w = sub.add_parser("write")
    w.add_argument("world")
    w.add_argument("--server-dir", required=True)
    w.add_argument("--stock", default=os.environ.get("PROBE_STOCK_CONFIG", str(DEFAULT_STOCK)))
    w.add_argument("--param", action="append", default=[], metavar="K=V")
    w.add_argument("--config-set", action="append", default=[], metavar="SPEC")
    s = sub.add_parser("show")
    s.add_argument("world")
    args = ap.parse_args()

    if args.cmd == "show":
        rec = load(args.world)
        print(json.dumps(rec, indent=2) if rec else "PROVENANCE UNKNOWN")
        return 0

    params = {}
    for p in args.param:
        k, _, v = p.partition("=")
        if v != "":
            params[k] = v
    rec = build(args.server_dir, args.stock, params, args.config_set)
    dest = Path(args.world) / FILENAME
    if not dest.parent.is_dir():
        print(f"probe-provenance: no world dir at {args.world}; nothing stamped", file=sys.stderr)
        return 1
    dest.write_text(json.dumps(rec, indent=2), encoding="utf-8")
    print(f"provenance stamped: {dest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
