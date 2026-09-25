"""
paint.py - painting terrain sets into a Tiled map's layers from Python (round 342).

A Tiled terrain set (wangset) in one of make_tilesets.py's .tsx files maps a wang id (eight neighbours, 1 = the
kind, 2 = none) to a tile id. Given a mask of cells that belong to the kind, paint_set() puts the right piece in
every masked cell - the same choice the Terrain Brush makes, and the same 47-shape rule the overworld draws with.
Value noise (value_noise) lays the ground's patch bands the way the world does (bands of one smooth field).
"""
import math
import random
import xml.etree.ElementTree as ET

BIT = {"TL": 8, "T": 7, "TR": 6, "L": 5, "C": 4, "R": 3, "BL": 2, "B": 1, "BR": 0}
ORDER = ["T", "TR", "R", "BR", "B", "BL", "L", "TL"]
OFFS = {"TL": (-1, -1), "T": (0, -1), "TR": (1, -1), "L": (-1, 0), "R": (1, 0), "BL": (-1, 1), "B": (0, 1), "BR": (1, 1)}


def load_wangsets(tsx_path):
    """{set name: {wangid: tile id}} for a .tsx; the all-'2' entry is the set's empty tile."""
    root = ET.parse(tsx_path).getroot()
    out = {}
    ws = root.find("wangsets")
    if ws is None:
        return out
    for wangset in ws.findall("wangset"):
        table = {}
        for wt in wangset.findall("wangtile"):
            table[wt.get("wangid")] = int(wt.get("tileid"))
        out[wangset.get("name")] = table
    return out


def canonical_wangid(present):
    """present: {name: bool} for the eight neighbours. A corner counts only when both edges beside it are in."""
    p = dict(present)
    for corner, (e1, e2) in {"TL": ("T", "L"), "TR": ("T", "R"), "BL": ("B", "L"), "BR": ("B", "R")}.items():
        if not (p[e1] and p[e2]):
            p[corner] = False
    return ",".join("1" if p[k] else "2" for k in ORDER)


def paint_set(layer, width, height, inside, table, firstgid, outside_counts=True, skip=None):
    """inside(x, y) -> bool: the cells of the kind. Cells off the map count as inside when outside_counts (a road
    that runs off the edge keeps running). skip(x, y) -> bool leaves a cell as it is (something already there).
    Returns the number of cells painted."""
    n = 0
    for y in range(height):
        for x in range(width):
            if not inside(x, y) or (skip and skip(x, y)):
                continue
            present = {}
            for k, (dx, dy) in OFFS.items():
                nx, ny = x + dx, y + dy
                if 0 <= nx < width and 0 <= ny < height:
                    present[k] = inside(nx, ny)
                else:
                    present[k] = outside_counts
            tid = table[canonical_wangid(present)]
            layer.set(x, y, firstgid + tid)
            n += 1
    return n


def value_noise(width, height, cell, seed):
    """A smooth field in [0,1]: random lattice values `cell` tiles apart, cosine-interpolated."""
    rnd = random.Random(seed)
    gw, gh = width // cell + 3, height // cell + 3
    lattice = [[rnd.random() for _ in range(gw)] for _ in range(gh)]

    def smooth(t):
        return (1 - math.cos(t * math.pi)) / 2

    field = [[0.0] * width for _ in range(height)]
    for y in range(height):
        for x in range(width):
            fx, fy = x / cell + 1, y / cell + 1
            ix, iy = int(fx), int(fy)
            tx, ty = smooth(fx - ix), smooth(fy - iy)
            a = lattice[iy][ix] * (1 - tx) + lattice[iy][ix + 1] * tx
            b = lattice[iy + 1][ix] * (1 - tx) + lattice[iy + 1][ix + 1] * tx
            field[y][x] = a * (1 - ty) + b * ty
    return field
