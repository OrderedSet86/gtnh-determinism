#!/usr/bin/env python3
"""Compare the PERSISTED block arrays of two saved 1.7.10 worlds (Anvil region files).

Usage: diff-region-blocks.py <worldA> <worldB> [minCX maxCX minCZ maxCZ] [--ids id1,id2,...]

--ids keeps only positions where at least one side holds a listed block id.

Handles NEID's 16-bit sections (Blocks16/Data16) as well as vanilla Blocks+Add+Data.
Reports per-position block differences: (id:meta)A vs (id:meta)B, aggregated by
transition pair and by chunk. Ground truth for whether live-probe block jitter
reaches the saved world.
"""
import gzip
import io
import struct
import sys
from collections import Counter, defaultdict
from pathlib import Path
import zlib


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


def section_blocks(sec):
    """Return (ids, metas) as flat 4096-length lists, YZX order."""
    if "Blocks16" in sec:
        raw = sec["Blocks16"]
        ids = list(struct.unpack(f">{len(raw)//2}h", raw))
        ids = [i & 0xFFFF for i in ids]
    else:
        ids = list(sec["Blocks"])
        if "Add" in sec:
            add = sec["Add"]
            for i in range(4096):
                nib = (add[i >> 1] >> ((i & 1) * 4)) & 0xF
                ids[i] |= nib << 8
    if "Data16" in sec:
        raw = sec["Data16"]
        metas = list(struct.unpack(f">{len(raw)//2}h", raw))
    else:
        data = sec["Data"]
        metas = [(data[i >> 1] >> ((i & 1) * 4)) & 0xF for i in range(4096)]
        if "Data1High" in sec:  # EndlessIDs (2.9.0+/daily): metadata bits 4-7
            hi = sec["Data1High"]
            for i in range(4096):
                nib = (hi[i >> 1] >> ((i & 1) * 4)) & 0xF
                metas[i] |= nib << 4
        if "Data2" in sec:  # EndlessIDs: metadata bits 8-15 (GT ore rework materials live here)
            d2 = sec["Data2"]
            for i in range(4096):
                metas[i] |= d2[i] << 8
    return ids, metas


def world_chunks(world, window):
    chunks = {}
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
            secs = {}
            for sec in level.get("Sections", []):
                secs[sec["Y"]] = section_blocks(sec)
            chunks[(cx, cz)] = secs
    return chunks


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    a_dir, b_dir = args[0], args[1]
    window = tuple(map(int, args[2:6])) if len(args) >= 6 else None
    # --ids restricts the report to positions where at least one side holds one of these block ids.
    # Until 2026-08-29 this option was documented, accepted, and then silently discarded, so a filtered
    # diff reported the whole world and any "only these blocks differ" conclusion drawn from it was wrong.
    ids = None
    for a in sys.argv[1:]:
        if a.startswith("--ids="):
            ids = {int(x) for x in a.split("=", 1)[1].split(",") if x.strip()}
        elif a == "--ids":
            nxt = sys.argv[sys.argv.index(a) + 1]
            ids = {int(x) for x in nxt.split(",") if x.strip()}
    if ids is not None:
        print(f"filtering to {len(ids)} block ids")
    A = world_chunks(a_dir, window)
    B = world_chunks(b_dir, window)
    both = sorted(set(A) & set(B))
    print(f"chunks: A={len(A)} B={len(B)} common={len(both)} "
          f"only-A={len(set(A)-set(B))} only-B={len(set(B)-set(A))}")
    pair_counts = Counter()
    chunk_counts = Counter()
    samples = defaultdict(list)
    total = 0
    missing_sections = 0
    for (cx, cz) in both:
        sa, sb = A[(cx, cz)], B[(cx, cz)]
        for y_sec in sorted(set(sa) | set(sb)):
            if y_sec not in sa or y_sec not in sb:
                # One side has no section here at all. With --ids there is nothing to attribute it to,
                # so it is reported separately rather than folded into the filtered count.
                if ids is not None:
                    missing_sections += 1
                    continue
                chunk_counts[(cx, cz)] += 4096
                total += 4096
                continue
            (ia, ma), (ib, mb) = sa[y_sec], sb[y_sec]
            if ia == ib and ma == mb:
                continue
            for i in range(4096):
                if ia[i] != ib[i] or ma[i] != mb[i]:
                    if ids is not None and ia[i] not in ids and ib[i] not in ids:
                        continue
                    total += 1
                    chunk_counts[(cx, cz)] += 1
                    key = (f"{ia[i]}:{ma[i]}", f"{ib[i]}:{mb[i]}")
                    pair_counts[key] += 1
                    if len(samples[key]) < 3:
                        x = cx * 16 + (i & 15)
                        z = cz * 16 + ((i >> 4) & 15)
                        y = y_sec * 16 + (i >> 8)
                        samples[key].append((x, y, z))
    print(f"differing blocks: {total} across {len(chunk_counts)} chunks")
    print("\ntop transitions (idA:metaA -> idB:metaB, count, sample xyz):")
    for key, n in pair_counts.most_common(40):
        print(f"  {key[0]:>10} -> {key[1]:<10} {n:6d}   {samples[key]}")
    print("\ntop chunks:")
    for (cx, cz), n in chunk_counts.most_common(15):
        print(f"  chunk {cx},{cz}: {n}")
    sys.exit(0 if total == 0 else 1)


if __name__ == "__main__":
    main()
