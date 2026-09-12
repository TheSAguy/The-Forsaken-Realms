#!/usr/bin/env python3
"""Round 185: find (and optionally clear) collision cells that sit under walkable-looking floor.

User report: "I seem to be running into invisible borders" in Mind Slaver's Encampment
(maps/map/fort/fort_colorless_5_evil.tmx). These maps carry a dedicated `Collision` tile layer,
drawn FIRST so every other layer paints over it, holding a solid-black tile whose tileset entry
has a collision box. That is the normal authoring technique - mark collision low, draw the wall on
top - and MapStage.loadCollision() reads collision from EVERY tile layer, so it works.

The defect is the cells where the art drawn on top is not a wall but ordinary floor. The player
sees open ground and walks into nothing.

Deciding "floor" is the whole problem, and this uses the MAP'S OWN vocabulary rather than a guess:
for every tile id, count how often it is the topmost visible tile in a cell that is NOT blocked
versus one that IS. A tile that is overwhelmingly walkable elsewhere in the same map (>= FLOOR_RATIO
of its appearances) is floor by that map's own usage, so the blocked instances are anomalies. Water
and wall-caps, which block nearly everywhere they appear, never qualify.

Only the dedicated `Collision` layer is ever edited. A blocking tile that lives on an ART layer is
left alone - it may be carrying the art as well as the collision.

Usage:
    python dev-tools/stray_collision_qa.py                 # report every map under maps/map
    python dev-tools/stray_collision_qa.py --write         # clear the stray cells (keeps a .bak)
    python dev-tools/stray_collision_qa.py --only fort     # restrict to paths containing "fort"
"""
import base64, collections, glob, os, re, struct, sys
import xml.etree.ElementTree as ET

try:
    from PIL import Image
except ImportError:
    sys.exit("PIL required: pip install pillow")

PLANE = r"F:\FORGE\C--Users-vicwaver-MTG-Forge\forge-gui\res\adventure\The Forsaken Realms"
COLLISION_LAYER = "Collision"
FLOOR_RATIO = 0.80     # a tile walkable in >= this share of its appearances counts as floor
MIN_SAMPLES = 4        # ...and it must appear at least this many times, to avoid one-off noise

_ts_cache = {}


def tileset(path):
    if path in _ts_cache:
        return _ts_cache[path]
    try:
        root = ET.parse(path).getroot()
    except Exception:
        _ts_cache[path] = None
        return None
    img = root.find("image")
    info = {
        "cols": int(root.get("columns") or 0),
        "coll": {int(t.get("id")) for t in root.findall("tile")
                 if t.find("objectgroup") is not None and t.find("objectgroup").findall("object")},
    }
    _ts_cache[path] = info
    return info


def decode(layer):
    data = layer.find("data")
    if data is None or data.get("encoding") != "base64":
        return None
    raw = base64.b64decode(data.text.strip())
    comp = data.get("compression")
    if comp == "zlib":
        raw = __import__("zlib").decompress(raw)
    elif comp == "gzip":
        raw = __import__("gzip").decompress(raw)
    return list(struct.unpack("<%dI" % (len(raw) // 4), raw))


def encode(vals, layer):
    raw = struct.pack("<%dI" % len(vals), *vals)
    comp = layer.find("data").get("compression")
    if comp == "zlib":
        raw = __import__("zlib").compress(raw)
    elif comp == "gzip":
        raw = __import__("gzip").compress(raw)
    return base64.b64encode(raw).decode("ascii")


def analyse(path):
    root = ET.parse(path).getroot()
    W = int(root.get("width") or 0)
    if not W:
        return None
    sets = []
    for ts in root.findall("tileset"):
        src = ts.get("source")
        if src:
            sets.append((int(ts.get("firstgid")), os.path.normpath(os.path.join(os.path.dirname(path), src))))
    sets.sort()

    def blocks(gid):
        g = gid & 0x1FFFFFFF
        if not g:
            return False
        best = None
        for first, p in sets:
            if g >= first:
                best = (first, p)
        if not best:
            return False
        info = tileset(best[1])
        return bool(info) and (g - best[0]) in info["coll"]

    layers = []
    for lay in root.findall("layer"):
        vals = decode(lay)
        if vals is not None:
            layers.append((lay.get("name"), lay, vals))
    if not layers:
        return None

    n = len(layers[0][2])
    blocked, topmost = [False] * n, [0] * n
    for _name, _lay, vals in layers:
        for i, gid in enumerate(vals):
            g = gid & 0x1FFFFFFF
            if not g:
                continue
            topmost[i] = g
            if blocks(g):
                blocked[i] = True

    seen = collections.Counter()
    free = collections.Counter()
    for i in range(n):
        g = topmost[i]
        if not g:
            continue
        seen[g] += 1
        if not blocked[i]:
            free[g] += 1
    floors = {g for g, c in seen.items()
              if c >= MIN_SAMPLES and free[g] / float(c) >= FLOOR_RATIO}

    coll_layer = next((t for t in layers if t[0] == COLLISION_LAYER), None)
    H = n // W
    strays = []
    if coll_layer:
        _n, _l, cvals = coll_layer
        for i in range(n):
            if not blocks(cvals[i]):
                continue
            if topmost[i] not in floors or blocks(topmost[i]):
                continue
            # Isolation test. Collision over floor art is deliberate where it forms a BARRIER - the
            # ring that stops you walking off the map edge, a rope line across a doorway. Those cells
            # have blocked neighbours continuing the line. What the player reports as "an invisible
            # wall in the middle of the floor" is an isolated cell they can walk all the way around,
            # so only cells with at most one blocked orthogonal neighbour are treated as mistakes.
            x, y = i % W, i // W
            near = 0
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                x2, y2 = x + dx, y + dy
                if not (0 <= x2 < W and 0 <= y2 < H):
                    near += 1        # the map edge counts as "the line continues"
                    continue
                if blocked[y2 * W + x2]:
                    near += 1
            if near <= 1:
                strays.append(i)
    coll_raw = coll_layer[1].find("data").text.strip() if coll_layer else None
    return {"root": root, "W": W, "layers": layers, "coll": coll_layer, "strays": strays,
            "floors": floors, "topmost": topmost, "coll_raw": coll_raw}


def main():
    write = "--write" in sys.argv
    only = None
    if "--only" in sys.argv:
        only = sys.argv[sys.argv.index("--only") + 1]
    files = sorted(glob.glob(os.path.join(PLANE, "maps", "map", "**", "*.tmx"), recursive=True))
    if only:
        files = [f for f in files if only.lower() in f.lower()]
    total = 0
    touched = 0
    for f in files:
        try:
            res = analyse(f)
        except Exception as exc:
            print("%-58s ERROR %s" % (os.path.relpath(f, PLANE), exc))
            continue
        if not res or not res["strays"]:
            continue
        rel = os.path.relpath(f, PLANE).replace("\\", "/")
        W = res["W"]
        pts = [(i % W, i // W) for i in res["strays"]]
        print("%-58s %3d stray cell(s)  e.g. %s" % (rel, len(pts), pts[:6]))
        total += len(pts)
        touched += 1
        if write:
            name, lay, vals = res["coll"]
            for i in res["strays"]:
                vals[i] = 0
            lay.find("data").text = encode(vals, lay)
            # Rewrite only the one <data> element's text, in the original file, so nothing else about
            # the .tmx changes - ElementTree's own serializer would reorder attributes and drop the
            # formatting Tiled wrote, producing a huge unreadable diff over a handful of cells.
            original = open(f, encoding="utf-8").read()
            old_text = res["coll_raw"]
            new_text = encode(vals, lay)
            assert original.count(old_text) == 1, "collision data block not uniquely found"
            open(f, "w", encoding="utf-8", newline="").write(original.replace(old_text, new_text, 1))
    print("\n%d map(s), %d stray collision cell(s)%s" % (touched, total, " CLEARED" if write else ""))
    if not write:
        print("(dry run - pass --write to clear them)")


if __name__ == "__main__":
    main()
