#!/usr/bin/env python3
"""Compare the PERSISTED tile entities of two saved 1.7.10 worlds (Anvil region files).

Usage: diff-region-tes.py <worldA> <worldB> [minCX maxCX minCZ maxCZ]

Reads every chunk's Level.TileEntities from region/*.mca in both worlds (optionally limited
to a chunk window), canonicalizes each TE's NBT (id/x/y/z + sorted payload), and reports
per-position presence and content differences. This is the ground truth for whether TE
jitter observed in a live probe actually reaches players via the saved world.
"""
import gzip
import io
import struct
import sys
import zlib
from pathlib import Path

TAG_NAMES = {0: "end", 1: "b", 2: "s", 3: "i", 4: "l", 5: "f", 6: "d",
             7: "ba", 8: "str", 9: "list", 10: "comp", 11: "ia"}


def read_nbt(buf):
    t = buf.read(1)[0]
    if t == 0:
        return None, None
    nlen = struct.unpack(">H", buf.read(2))[0]
    name = buf.read(nlen).decode("utf-8", "replace")
    return name, read_payload(buf, t)


def read_payload(buf, t):
    if t == 1: return struct.unpack(">b", buf.read(1))[0]
    if t == 2: return struct.unpack(">h", buf.read(2))[0]
    if t == 3: return struct.unpack(">i", buf.read(4))[0]
    if t == 4: return struct.unpack(">q", buf.read(8))[0]
    if t == 5: return struct.unpack(">f", buf.read(4))[0]
    if t == 6: return struct.unpack(">d", buf.read(8))[0]
    if t == 7:
        n = struct.unpack(">i", buf.read(4))[0]
        return buf.read(n)
    if t == 8:
        n = struct.unpack(">H", buf.read(2))[0]
        return buf.read(n).decode("utf-8", "replace")
    if t == 9:
        et = buf.read(1)[0]
        n = struct.unpack(">i", buf.read(4))[0]
        return [read_payload(buf, et) for _ in range(n)]
    if t == 10:
        out = {}
        while True:
            tt = buf.read(1)[0]
            if tt == 0:
                return out
            nlen = struct.unpack(">H", buf.read(2))[0]
            name = buf.read(nlen).decode("utf-8", "replace")
            out[name] = read_payload(buf, tt)
    if t == 11:
        n = struct.unpack(">i", buf.read(4))[0]
        return list(struct.unpack(f">{n}i", buf.read(4 * n)))
    raise ValueError(f"tag {t}")


def canon(v):
    if isinstance(v, dict):
        return "{" + ",".join(f"{k}:{canon(v[k])}" for k in sorted(v)) + "}"
    if isinstance(v, list):
        return "[" + ",".join(canon(x) for x in v) + "]"
    if isinstance(v, bytes):
        return v.hex()
    if isinstance(v, float):
        return f"{v:.6g}"
    return str(v)


def world_tes(world, window):
    tes = {}
    for mca in sorted(Path(world, "region").glob("r.*.mca")):
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
            _, root = read_nbt(io.BytesIO(payload))
            level = root["Level"]
            cx, cz = level["xPos"], level["zPos"]
            if window and not (window[0] <= cx <= window[1] and window[2] <= cz <= window[3]):
                continue
            for te in level.get("TileEntities", []):
                tes[(te.get("x"), te.get("y"), te.get("z"))] = (te.get("id"), canon(te))
    return tes


def main():
    a_dir, b_dir = sys.argv[1], sys.argv[2]
    window = tuple(map(int, sys.argv[3:7])) if len(sys.argv) >= 7 else None
    A = world_tes(a_dir, window)
    B = world_tes(b_dir, window)
    only_a = sorted(set(A) - set(B))
    only_b = sorted(set(B) - set(A))
    changed = sorted(p for p in set(A) & set(B) if A[p] != B[p])
    print(f"persisted TEs: A={len(A)} B={len(B)}  only-A={len(only_a)} only-B={len(only_b)} changed={len(changed)}")
    for p in only_a[:10]:
        print("  only-A", p, A[p][0])
    for p in only_b[:10]:
        print("  only-B", p, B[p][0])
    for p in changed[:10]:
        print("  changed", p, A[p][0])
    sys.exit(0 if not (only_a or only_b or changed) else 1)


if __name__ == "__main__":
    main()
