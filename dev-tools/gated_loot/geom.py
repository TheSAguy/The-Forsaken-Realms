"""geom.py - the collision grid MapStage builds, at pixel resolution, with numpy (read-only helper).

Blocked pixels come from
  * every TOP-LEVEL tile layer (MapStage.loadMap walks map.getLayers(); a layer with noCollision=true is skipped,
    round 349), each cell contributing the RECTANGLE objects authored on its tile in the tileset
    (MapStage.loadCollision - flips ignored, ellipses/polygons ignored);
  * every `collision` object's x/y/width/height (MapStage.loadObjects case "collision");
  * extra actor boxes passed in by the caller (gates, dummies).
The player is a 10x6 box (CharacterSprite.updateBoundingRect: x+4, y, w-6, h*0.4 of a 16 px sprite).
Frame: .tmx top-down pixels, row 0 = top of the map.
"""
import base64
import gzip
import math
import os
import struct
import xml.etree.ElementTree as ET
import zlib

import numpy as np

GID_MASK = 0x1FFFFFFF
PW, PH = 10, 6

_TSX = {}


def _tile_rects_from_tileset_el(ts_el):
    out = {}
    for tile in ts_el.findall("tile"):
        group = tile.find("objectgroup")
        if group is None:
            continue
        boxes = []
        for obj in group.findall("object"):
            if any(obj.find(s) is not None for s in ("polygon", "ellipse", "polyline", "text", "point")):
                continue
            boxes.append((float(obj.get("x", 0)), float(obj.get("y", 0)),
                          float(obj.get("width", 0) or 0), float(obj.get("height", 0) or 0)))
        if boxes:
            out[int(tile.get("id"))] = boxes
    return out


def tileset_rects(path):
    path = os.path.normpath(path)
    if path not in _TSX:
        try:
            _TSX[path] = _tile_rects_from_tileset_el(ET.parse(path).getroot())
        except (OSError, ET.ParseError):
            _TSX[path] = {}
    return _TSX[path]


def decode_layer(layer):
    data = layer.find("data")
    if data is None:
        return []
    enc = data.get("encoding")
    if enc == "csv":
        return [int(v) for v in data.text.replace("\n", "").split(",") if v.strip()]
    if enc == "base64":
        raw = base64.b64decode(data.text.strip())
        if data.get("compression") == "zlib":
            raw = zlib.decompress(raw)
        elif data.get("compression") == "gzip":
            raw = gzip.decompress(raw)
        return list(struct.unpack("<%dI" % (len(raw) // 4), raw))
    return [int(t.get("gid", 0)) for t in data.findall("tile")]


def base_blocked(tmx, collision_objs):
    """(blocked bool[H,W], W, H, tw, th) from tile layers + collision objects."""
    root = ET.parse(tmx).getroot()
    width, height = int(root.get("width")), int(root.get("height"))
    tw, th = int(root.get("tilewidth")), int(root.get("tileheight"))
    base = os.path.dirname(os.path.abspath(tmx))
    tilesets = []
    for ts in root.findall("tileset"):
        first = int(ts.get("firstgid"))
        if ts.get("source"):
            tilesets.append((first, tileset_rects(os.path.join(base, ts.get("source")))))
        else:
            tilesets.append((first, _tile_rects_from_tileset_el(ts)))
    tilesets.sort(key=lambda t: t[0])
    wpx, hpx = width * tw, height * th
    blocked = np.zeros((hpx, wpx), dtype=bool)
    for layer in root.findall("layer"):  # top-level only, like map.getLayers()
        noc = False
        pe = layer.find("properties")
        if pe is not None:
            for p in pe.findall("property"):
                if p.get("name") == "noCollision" and str(p.get("value")).lower() == "true":
                    noc = True
        if noc:
            continue
        cells = decode_layer(layer)
        for index, raw in enumerate(cells):
            gid = raw & GID_MASK
            if not gid:
                continue
            own = None
            for first, rects in tilesets:
                if gid >= first:
                    own = (first, rects)
                else:
                    break
            if not own:
                continue
            boxes = own[1].get(gid - own[0])
            if not boxes:
                continue
            cx = (index % width) * tw
            cy = (index // width) * th
            for (ox, oy, ow, oh) in boxes:
                x0 = cx + int(ox)
                y0 = cy + int(oy)
                x1 = x0 + int(round(ow))
                y1 = y0 + int(round(oh))
                blocked[max(0, y0):max(0, min(hpx, y1)), max(0, x0):max(0, min(wpx, x1))] = True
    for o in collision_objs:
        add_rect(blocked, o["x"], o["y"], o["w"], o["h"])
    return blocked, wpx, hpx, tw, th


def add_rect(grid, x, y_top, w, h):
    hpx, wpx = grid.shape
    x0, y0 = int(math.floor(x)), int(math.floor(y_top))
    x1, y1 = int(math.ceil(x + w)), int(math.ceil(y_top + h))
    x0, y0 = max(0, x0), max(0, y0)
    x1, y1 = min(wpx, x1), min(hpx, y1)
    if x1 > x0 and y1 > y0:
        grid[y0:y1, x0:x1] = True


def actor_box(o, character=True):
    """(x, y_top, w, h) of an actor's collision box in the .tmx frame.

    character=True: CharacterSprite (dialog, enemy) - (x+4, y, w-6, 0.4h) with y the libGDX bottom;
    character=False: MapActor (dummy, reward) - the whole object."""
    if o["is_tile"]:
        yb = o["y"]
    else:
        yb = o["y"] + o["h"]
    if character:
        return o["x"] + 4, yb - 0.4 * o["h"], o["w"] - 6, 0.4 * o["h"]
    return o["x"], yb - o["h"], o["w"], o["h"]


def free_positions(blocked):
    """free[y, x] True where the player's PWxPH box with its top-left at (x, y) touches no blocked pixel."""
    hpx, wpx = blocked.shape
    s = np.zeros((hpx + 1, wpx + 1), dtype=np.int32)
    s[1:, 1:] = np.cumsum(np.cumsum(blocked, axis=0, dtype=np.int32), axis=1, dtype=np.int32)
    free = np.zeros((hpx, wpx), dtype=bool)
    if hpx < PH or wpx < PW:
        return free
    box = s[PH:, PW:] - s[:-PH, PW:] - s[PH:, :-PW] + s[:-PH, :-PW]
    free[:hpx - PH + 1, :wpx - PW + 1] = box == 0
    return free


def label(free):
    """4-connected component labels of the free mask (0 = not free), via row runs + union-find."""
    hpx, wpx = free.shape
    parent = [0]

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    runs_prev = []
    all_runs = []
    padded = np.zeros(wpx + 2, dtype=np.int8)
    for y in range(hpx):
        row = free[y]
        if not row.any():
            runs_prev = []
            continue
        padded[1:-1] = row
        d = np.diff(padded)
        starts = np.nonzero(d == 1)[0]
        ends = np.nonzero(d == -1)[0]
        runs = []
        j = 0
        for x0, x1 in zip(starts.tolist(), ends.tolist()):
            lab = len(parent)
            parent.append(lab)
            while j < len(runs_prev) and runs_prev[j][1] <= x0:
                j += 1
            k = j
            while k < len(runs_prev) and runs_prev[k][0] < x1:
                ra, rb = find(lab), find(runs_prev[k][2])
                if ra != rb:
                    parent[max(ra, rb)] = min(ra, rb)
                k += 1
            runs.append((x0, x1, lab))
        all_runs.append((y, runs))
        runs_prev = runs
    lab_arr = np.zeros((hpx, wpx), dtype=np.int32)
    for y, runs in all_runs:
        for x0, x1, lab in runs:
            lab_arr[y, x0:x1] = find(lab)
    return lab_arr


def labels_touching_rect(lab, x, y_top, w, h):
    """labels of player positions whose box overlaps the rect [x, x+w) x [y_top, y_top+h)."""
    hpx, wpx = lab.shape
    bx0 = int(math.floor(x)) - PW + 1
    bx1 = int(math.ceil(x + w))
    by0 = int(math.floor(y_top)) - PH + 1
    by1 = int(math.ceil(y_top + h))
    bx0, by0 = max(0, bx0), max(0, by0)
    bx1, by1 = min(wpx, bx1), min(hpx, by1)
    if bx1 <= bx0 or by1 <= by0:
        return set()
    sub = lab[by0:by1, bx0:bx1]
    return set(np.unique(sub[sub > 0]).tolist())


def labels_near(lab, x, y, radius):
    hpx, wpx = lab.shape
    x0, x1 = max(0, int(x - radius)), min(wpx, int(x + radius) + 1)
    y0, y1 = max(0, int(y - radius)), min(hpx, int(y + radius) + 1)
    if x1 <= x0 or y1 <= y0:
        return set()
    sub = lab[y0:y1, x0:x1]
    return set(np.unique(sub[sub > 0]).tolist())


def nearest_label(lab, x, y, radius=16):
    """label of the free position nearest to (x, y) within radius, or None."""
    hpx, wpx = lab.shape
    x0, x1 = max(0, int(x - radius)), min(wpx, int(x + radius) + 1)
    y0, y1 = max(0, int(y - radius)), min(hpx, int(y + radius) + 1)
    if x1 <= x0 or y1 <= y0:
        return None
    sub = lab[y0:y1, x0:x1]
    ys, xs = np.nonzero(sub)
    if len(ys) == 0:
        return None
    d = (xs + x0 - x) ** 2 + (ys + y0 - y) ** 2
    i = int(np.argmin(d))
    return int(sub[ys[i], xs[i]])
