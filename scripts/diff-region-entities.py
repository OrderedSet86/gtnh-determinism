#!/usr/bin/env python3
"""Compare the PERSISTED entities of two saved 1.7.10 worlds (Anvil region files).

Usage: diff-region-entities.py <worldA> <worldB> [minCX maxCX minCZ maxCZ]

Reads every chunk's Level.Entities from region/*.mca in both worlds (optionally limited to a
chunk window) and reports presence and content differences, bucketed by entity id and by which
NBT tag moved. This is the ground truth for villager spawn determinism: villagers are created
during structure generation and land in Level.Entities, so a route or launch pair that differs
here differs for the player.

Three things make this NOT a fork of diff-region-tes.py:

  * Position is a unique key for tile entities but NOT for entities. Worldgen animal spawns
    arrive in clustered groups and villagers from adjacent pieces can share a column, so keying
    on position alone silently drops entities and makes count differences vanish. This keys on
    (id, x, y, z, ordinal) with an explicit duplicate counter.
  * TE coordinates are ints; entity Pos is a list of three doubles. The "%.6g" canonicalizer in
    diff-region-tes.py resolves to about 0.001 at |x| ~ 1000, which both masks real differences
    and manufactures false matches. This uses repr().
  * UUIDMost/UUIDLeast come from UUID.randomUUID() in the Entity constructor and would otherwise
    make every entity differ in every pair. They are dropped, and counted separately so the
    exclusion is visible rather than silent.

Nothing else is stripped. At zero probe ticks Motion, Age, Rotation, Fire, Air and HealF are
constants, so keeping them costs nothing and catches anything that moves them. "Only NBT
differs" is not a pass -- Profession and fleece Color live there.
"""
import importlib.util
import sys
from collections import Counter
from pathlib import Path

# Reuse the hand-rolled Anvil/NBT reader rather than re-implementing it. Same import idiom as
# inventory-region-diff.py, needed because the module name is not a valid Python identifier.
_spec = importlib.util.spec_from_file_location("drt", str(Path(__file__).with_name("diff-region-tes.py")))
drt = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(drt)

# Random per instance, no gameplay meaning, persisted. Dropping these is the only exclusion.
UUID_TAGS = ("UUIDMost", "UUIDLeast")


def canon_entity(v):
    """Canonicalize an entity NBT value. Full float precision, unlike drt.canon."""
    if isinstance(v, dict):
        return "{" + ",".join(f"{k}:{canon_entity(v[k])}" for k in sorted(v) if k not in UUID_TAGS) + "}"
    if isinstance(v, list):
        return "[" + ",".join(canon_entity(x) for x in v) + "]"
    if isinstance(v, bytes):
        return v.hex()
    if isinstance(v, float):
        return repr(v)
    return str(v)


def pos_of(ent):
    p = ent.get("Pos")
    if isinstance(p, list) and len(p) == 3:
        return tuple(float(x) for x in p)
    return (None, None, None)


def world_entities(world, window):
    """Map (id, x, y, z, ordinal) -> canonical NBT. Ordinal disambiguates co-located entities."""
    out = {}
    seen = Counter()
    for mca in sorted(Path(world, "region").glob("r.*.mca")):
        for level in drt_chunks(mca):
            cx, cz = level["xPos"], level["zPos"]
            if window and not (window[0] <= cx <= window[1] and window[2] <= cz <= window[3]):
                continue
            for ent in level.get("Entities", []):
                eid = ent.get("id")
                x, y, z = pos_of(ent)
                base = (eid, x, y, z)
                n = seen[base]
                seen[base] += 1
                out[base + (n,)] = canon_entity(ent)
    return out


def drt_chunks(mca):
    """Yield each chunk's Level compound from one region file, reusing drt's NBT reader."""
    import gzip
    import io
    import struct
    import zlib
    data = mca.read_bytes()
    for i in range(1024):
        off = struct.unpack(">i", b"\0" + data[i * 4:i * 4 + 3])[0]
        if off == 0:
            continue
        start = off * 4096
        (length,) = struct.unpack(">i", data[start:start + 4])
        comp = data[start + 4]
        raw = data[start + 5:start + 4 + length]
        payload = zlib.decompress(raw) if comp == 2 else gzip.decompress(raw)
        _, root = drt.read_nbt(io.BytesIO(payload))
        yield root["Level"]


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    a_dir, b_dir = sys.argv[1], sys.argv[2]
    window = tuple(map(int, sys.argv[3:7])) if len(sys.argv) >= 7 else None
    A = world_entities(a_dir, window)
    B = world_entities(b_dir, window)

    only_a = sorted(set(A) - set(B))
    only_b = sorted(set(B) - set(A))
    changed = sorted(k for k in set(A) & set(B) if A[k] != B[k])

    print(f"persisted entities: A={len(A)} B={len(B)}  "
          f"only-A={len(only_a)} only-B={len(only_b)} changed={len(changed)}")

    ca, cb = Counter(k[0] for k in A), Counter(k[0] for k in B)
    print("  by id (A/B, delta):")
    for eid in sorted(set(ca) | set(cb)):
        d = cb[eid] - ca[eid]
        flag = "" if d == 0 else "   <-- DIFFERS"
        print(f"    {eid:<28} {ca[eid]:>5}/{cb[eid]:<5} {d:+d}{flag}")

    # Villagers get their own line: profession is decided at generation time and must not move.
    def vill(d):
        return sorted(k for k in d if k[0] == "Villager")
    va, vb = vill(A), vill(B)
    if va or vb:
        print(f"  villagers: A={len(va)} B={len(vb)}")
        for k in sorted(set(va) - set(vb))[:20]:
            print("    only-A", k[1:4], A[k][:120])
        for k in sorted(set(vb) - set(va))[:20]:
            print("    only-B", k[1:4], B[k][:120])

    for k in only_a[:10]:
        print("  only-A", k[0], k[1:4])
    for k in only_b[:10]:
        print("  only-B", k[0], k[1:4])
    for k in changed[:10]:
        print("  changed", k[0], k[1:4])

    print(f"  (UUIDMost/UUIDLeast excluded from every comparison above; they are random per entity)")
    sys.exit(0 if not (only_a or only_b or changed) else 1)


if __name__ == "__main__":
    main()
