import sys, io, gzip, zlib, struct, importlib.util, collections
from pathlib import Path
spec=importlib.util.spec_from_file_location("drb","/home/order/Dropbox/OrderedSetCode/cloned-gtnh/gtnh-determinism/scripts/diff-region-blocks.py")
drb=importlib.util.module_from_spec(spec); 
try: spec.loader.exec_module(drb)
except SystemExit: pass
def levels(d):
    for mca in sorted(Path(d,"region").glob("r.*.mca")):
        data=mca.read_bytes()
        for i in range(1024):
            off=struct.unpack(">i", b"\0"+data[i*4:i*4+3])[0]
            if off==0: continue
            s=off*4096; (ln,)=struct.unpack(">i",data[s:s+4]); comp=data[s+4]
            raw=data[s+5:s+4+ln]
            pay=zlib.decompress(raw) if comp==2 else gzip.decompress(raw)
            _,root=drb.read_nbt(io.BytesIO(pay)); yield root["Level"]
w=sys.argv[1]; x0,x1,z0,z1=map(int,sys.argv[2:6])
tes=collections.Counter(); pts=[]; chunks=set(); unpop=set()
for lv in levels(w):
    cx,cz=lv["xPos"],lv["zPos"]
    if not (x0>>4 <= cx <= x1>>4 and z0>>4 <= cz <= z1>>4): continue
    chunks.add((cx,cz))
    if not lv.get("TerrainPopulated"): unpop.add((cx,cz))
    for t in (lv.get("TileEntities") or []):
        if x0<=t.get("x",1<<30)<=x1 and z0<=t.get("z",1<<30)<=z1:
            tes[t.get("id","?")]+=1
            if t.get("id") in ("Chest","MobSpawner"): pts.append((t["id"],t["x"],t["y"],t["z"]))
print(f"{w}\n  chunks in box: {len(chunks)}  unpopulated: {len(unpop)}")
print(f"  TEs: {dict(tes.most_common(8))}")
for p in sorted(pts,key=lambda p:(p[0],p[2],p[1])): print(f"    {p[0]:10s} ({p[1]},{p[2]},{p[3]})")
