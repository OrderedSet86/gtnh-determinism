#!/usr/bin/env python3
"""Extract the OreMixes table from javap -c output of gregtech/api/enums/OreMixes.class."""
import re, sys, json

path = sys.argv[1]
out = sys.argv[2]
lines = open(path).read().splitlines()

# isolate <clinit>
start = None
for i, l in enumerate(lines):
    if l.strip() == 'static {};':
        start = i
        break
body = lines[start:]

INT = {'iconst_m1': -1}
for k in range(6):
    INT['iconst_%d' % k] = k

mixes = []
cur = None
pending_ints = []
pending_mats = []
pending_strs = []
pending_dims = []

def num(op, arg):
    if op in INT: return INT[op]
    if op in ('bipush', 'sipush'): return int(arg)
    if op in ('ldc', 'ldc_w'): return int(arg)
    return None

for l in body:
    m = re.match(r'\s*\d+:\s+(\S+)\s*(.*)$', l)
    if not m: continue
    op, rest = m.group(1), m.group(2).strip()
    comment = ''
    if '//' in rest:
        arg, comment = rest.split('//', 1)
        arg = arg.strip(); comment = comment.strip()
    else:
        arg = rest

    # constant pushes
    if op in INT or op in ('bipush', 'sipush'):
        v = num(op, arg)
        if v is not None: pending_ints.append(v)
        continue
    if op in ('ldc', 'ldc_w', 'ldc2_w'):
        if comment.startswith('String '):
            pending_strs.append(comment[len('String '):])
        elif comment.startswith('int '):
            pending_ints.append(int(comment[4:]))
        continue
    if op == 'getstatic':
        mm = re.match(r'Field gregtech/api/enums/Materials\.(\w+):', comment)
        if mm: pending_mats.append(mm.group(1))
        dm = re.match(r'Field galacticgreg/api/enums/DimensionDef\.(\w+):', comment)
        if dm: pending_dims.append(dm.group(1))
        continue
    if op == 'invokevirtual':
        mm = re.match(r'Method gregtech/common/OreMixBuilder\.(\w+):\((.*?)\)', comment)
        if not mm:
            continue
        meth, sig = mm.group(1), mm.group(2)
        if meth == 'name':
            cur = {'enumIndex': len(mixes), 'name': pending_strs[-1] if pending_strs else None,
                   'enabledByDefault': True, 'dims': [], 'spaceDims': []}
            mixes.append(cur)
            pending_strs = []
        elif meth == 'heightRange':
            cur['minY'], cur['maxY'] = pending_ints[-2], pending_ints[-1]; pending_ints = []
        elif meth == 'weight':
            cur['weight'] = pending_ints[-1]; pending_ints = []
        elif meth == 'density':
            cur['density'] = pending_ints[-1]; pending_ints = []
        elif meth == 'size':
            cur['size'] = pending_ints[-1]; pending_ints = []
        elif meth == 'disabledByDefault':
            cur['enabledByDefault'] = False
        elif meth == 'enableInDim':
            if 'Ljava/lang/String;' in sig:
                cur['dims'] += pending_strs
            else:
                cur['spaceDims'] += pending_dims
            pending_strs = []; pending_dims = []; pending_ints = []
        elif meth in ('primary', 'secondary', 'inBetween', 'sporadic'):
            key = {'primary': 'primary', 'secondary': 'secondary',
                   'inBetween': 'between', 'sporadic': 'sporadic'}[meth]
            cur[key] = pending_mats[-1]; pending_mats = []
        elif meth == 'localize':
            cur['localize'] = list(pending_mats); pending_mats = []; pending_ints = []
        continue
    if op == 'anewarray':
        pending_ints = []  # array length push
        continue

json.dump(mixes, open(out, 'w'), indent=1)
print('mixes:', len(mixes))
for m in mixes:
    print(m['enumIndex'], m['name'], 'w=%s' % m.get('weight'), 'y=%s-%s' % (m.get('minY'), m.get('maxY')),
          'd=%s' % m.get('density'), 's=%s' % m.get('size'),
          'OW' if 'Overworld' in m['dims'] else '  ',
          m.get('primary'), m.get('secondary'), m.get('between'), m.get('sporadic'))
