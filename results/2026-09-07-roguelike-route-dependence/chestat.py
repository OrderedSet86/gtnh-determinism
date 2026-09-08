"""Find chest tile entities near a block position in a saved world, and list contents."""
import sys, io, gzip, zlib, struct, importlib.util, collections
from pathlib import Path
spec=importlib.util.spec_from_file_location("drb","/home/order/Dropbox/OrderedSetCode/cloned-gtnh/gtnh-determinism/scripts/diff-region-blocks.py")
drb=importlib.util.module_from_spec(spec)
try: spec.loader.exec_module(drb)
except SystemExit: pass

def chunks_raw(dimdir, want):
    """Yield (cx,cz,level) for the requested chunk coords."""
    for mca in sorted(Path(dimdir,"region").glob("r.*.mca")):
        data=mca.read_bytes()
        for i in range(1024):
            off=struct.unpack(">i", b"\0"+data[i*4:i*4+3])[0]
            if off==0: continue
            start=off*4096
            (length,)=struct.unpack(">i", data[start:start+4])
            comp=data[start+4]
            raw=data[start+5:start+4+length]
            payload=zlib.decompress(raw) if comp==2 else gzip.decompress(raw)
            _,root=drb.read_nbt(io.BytesIO(payload))
            lv=root["Level"]
            if (lv["xPos"],lv["zPos"]) in want:
                yield lv["xPos"],lv["zPos"],lv

def main():
    dim=sys.argv[1]
    pts=[tuple(map(int,a.split(','))) for a in sys.argv[2:]]
    want={(x>>4,z>>4) for x,_,z in pts}
    got={}
    for cx,cz,lv in chunks_raw(dim,want):
        got[(cx,cz)]=lv
    for (px,py,pz) in pts:
        key=(px>>4,pz>>4)
        lv=got.get(key)
        print(f"\n=== target ({px},{py},{pz})  chunk {key} ===")
        if lv is None:
            print("  CHUNK NOT GENERATED"); continue
        tes=lv.get("TileEntities") or []
        print(f"  chunk generated; TerrainPopulated={lv.get('TerrainPopulated')}; {len(tes)} tile entities")
        hits=[t for t in tes if abs(t.get("x",1<<30)-px)<=6 and abs(t.get("z",1<<30)-pz)<=6 and abs(t.get("y",1<<30)-py)<=6]
        if not hits:
            kinds=collections.Counter(t.get("id","?") for t in tes)
            print(f"  no TE within 6 blocks. TE kinds in chunk: {dict(kinds.most_common(6))}")
            continue
        for t in hits:
            items=t.get("Items") or []
            print(f"  {t.get('id')} at ({t.get('x')},{t.get('y')},{t.get('z')})  {len(items)} stacks")
            for it in items:
                print(f"      slot {it.get('Slot'):>3}  {it.get('id')}  dmg={it.get('Damage')}  x{it.get('Count')}")
main()
