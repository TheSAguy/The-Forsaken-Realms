r"""open_baneful_r683.py - TFR round 683: Baneful Cavern opened up.

The user, in Realm of Legends' Baneful Cavern (round 674): "I think this is one of the new caves. Is seems most of the
cave is not reachable?" - then "Open Baneful Cavern up: connect the rooms, move Axelrod and the gold to the far hall".
As Realm of Legends shipped it, the entrance room (the entry, Axelrod Gunnarson, two gold piles) was walled off from the
rest of the cave: 16% of its walkable space was reachable from the entrance.

What it does, on the plane's maps/map/evilgrove/Baneful_Cavern.tmx in place:
  - carves the cells of PASSAGES to floor on the Walls layer, working on the corners of main.tsx's "Walls" corner
    terrain (the set dev-tools/gen_caves.py paints caves with), so the edge tiles around the opening are re-picked by
    the terrain's own rules - the variant the map already uses most;
  - moves Axelrod (object 53) and the gold (49, 68) to the far top-right chamber, the longest walk from the entrance.
Re-running changes nothing: an open passage stays open and the moves set fixed cells.
Cells are tile coordinates, rows top-down. usage: python open_baneful_r683.py [repo root]"""
import sys
import base64
import collections
import re
import struct
import xml.etree.ElementTree as ET
import zlib

FLAGS = 0xE0000000

REPO = sys.argv[1] if len(sys.argv) > 1 else 'C:/TFR/repo'
MAP = REPO + '/forge-gui/res/adventure/The Forsaken Realms/maps/map/evilgrove/Baneful_Cavern.tmx'
TSX = REPO + '/forge-gui/res/adventure/common/maps/tileset/main.tsx'
PASSAGES = [(11, 12, 7, 9)]          # x0, x1, y0, y1: the entrance room's top wall up to the corridor
AXELROD = (22, 3)                    # the far top-right chamber
GOLD = [(20, 3), (25, 3)]            # objects 49 and 68, either side of him

# the "Walls" corner terrain: gid -> (tl, tr, br, bl) and back
ws = [w for w in ET.parse(TSX).getroot().find('wangsets').findall('wangset')
      if w.get('name') == 'Walls' and w.get('type') == 'corner'][0]
rev, fwd = {}, collections.defaultdict(list)
for wt in ws.findall('wangtile'):
    top, tr, right, br, bottom, bl, left, tl = [int(v) for v in wt.get('wangid').split(',')]
    if any(c not in (0, 1) for c in (tl, tr, br, bl)):
        continue
    gid = int(wt.get('tileid')) + 1
    rev[gid] = (tl, tr, br, bl)
    fwd[(tl, tr, br, bl)].append(gid)

raw = open(MAP, encoding='utf-8', newline='').read()
root = ET.fromstring(raw)
W, H = int(root.get('width')), int(root.get('height'))
m = re.search(r'(<layer id="\d+" name="Walls".*?<data encoding="base64" compression="zlib">\s*)(\S+)(\s*</data>)', raw, re.S)
cells = list(struct.unpack('<%dI' % (W * H), zlib.decompress(base64.b64decode(m.group(2)))))
gid = lambda x, y: cells[y * W + x] & ~FLAGS

# vertices: V[vy][vx] is the corner at the top-left of cell (vx, vy)
V = [[None] * (W + 1) for _ in range(H + 1)]
clash = 0
for y in range(H):
    for x in range(W):
        g = gid(x, y)
        corners = (0, 0, 0, 0) if g == 0 else rev.get(g)
        if corners is None:
            continue  # a decoration on the Walls layer: its corners come from the neighbours
        for (vx, vy), c in zip(((x, y), (x + 1, y), (x + 1, y + 1), (x, y + 1)), corners):
            if V[vy][vx] is not None and V[vy][vx] != c:
                clash += 1
            V[vy][vx] = c if V[vy][vx] is None else max(V[vy][vx], c)
print('vertex clashes in the original painting:', clash)

before = [row[:] for row in V]
carved = set()
for x0, x1, y0, y1 in PASSAGES:
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            carved.add((x, y))
            for vx, vy in ((x, y), (x + 1, y), (x + 1, y + 1), (x, y + 1)):
                V[vy][vx] = 0

usage = collections.Counter(gid(x, y) for y in range(H) for x in range(W))
changed = []
for y in range(H):
    for x in range(W):
        key = (V[y][x], V[y][x + 1], V[y + 1][x + 1], V[y + 1][x])
        old_key = (before[y][x], before[y][x + 1], before[y + 1][x + 1], before[y + 1][x])
        if key == old_key and (x, y) not in carved:
            continue
        g = gid(x, y)
        if g not in rev and g != 0 and (x, y) not in carved:
            print('  decoration at cell', (x, y), 'gid', g, 'kept (its corners changed to', key, ')')
            continue
        if None in key:
            raise SystemExit('unknown corner at cell %s: %s' % ((x, y), key))
        if key == (0, 0, 0, 0):
            new = 0
        elif key in fwd:
            new = max(fwd[key], key=lambda t: usage[t])  # the variant this map already uses most
        else:
            raise SystemExit('no Walls tile for corners %s at cell %s - widen the passage' % (key, (x, y)))
        if new != g:
            changed.append(((x, y), g, new))
            cells[y * W + x] = new
print('cells repainted:', len(changed))
for c in changed:
    print('  ', c)

data = base64.b64encode(zlib.compress(struct.pack('<%dI' % (W * H), *cells), 9)).decode('ascii')
out = raw[:m.start(2)] + data + raw[m.end(2):]


def move(obj_id, cx, cy):
    """Put a point/tile object's anchor on cell (cx, cy): x at the cell's left + 2, y at its bottom edge (tile objects
    hang up from y)."""
    global out
    pat = re.compile(r'(<object id="%d"[^>]*? x=")([-\d.]+)(" y=")([-\d.]+)(")' % obj_id)
    mm = pat.search(out)
    if not mm:
        raise SystemExit('object %d not found' % obj_id)
    out = out[:mm.start()] + mm.group(1) + '%g' % (cx * 16) + mm.group(3) + '%g' % ((cy + 1) * 16) + mm.group(5) \
        + out[mm.end():]
    print('object %d -> cell (%d,%d)' % (obj_id, cx, cy))


move(53, *AXELROD)
for oid, cell in zip((49, 68), GOLD):
    move(oid, *cell)
ET.fromstring(out)
if out == raw:
    print('already open - nothing to change')
else:
    open(MAP, 'w', encoding='utf-8', newline='').write(out)
    print('wrote', MAP)
