#!/usr/bin/env python3
"""Find maps split into pieces the player cannot walk between.

User report, 2026-09-13, with a screenshot of a cave whose two halves sat either side of solid
void: "I found this cave, seems like the two sides are not connected. ... can't reach the one
side." A generated cave that hands out a chest and a gem on an island you cannot reach is a dead
loss to the player, and nothing in the pipeline was checking for it.

Resolution
----------
TILE level, not pixel level, on purpose. A cave split by a void is separated by many solid tiles,
so tile granularity finds it comfortably - and a pixel pass over 120 maps in pure Python (no numpy
in this environment) is minutes of work for no extra signal. `dev-tools/pixel_collision_qa.py` is
the precise follow-up once a suspect map is named: it walks the map with the player's real
collision box and will settle any borderline gap this flags or misses.

A tile counts as passable when its collision rectangles cover less than `--max-coverage` of it
(default 0.6). Partial-collision tiles are the norm here - see pixel_collision_qa's own notes - so
"any collision at all" would be far too strict a test.

Usage
-----
    python dev-tools/cave_connectivity_qa.py [path ...] [--min-island 12] [--max-coverage 0.6]
"""

import argparse
import base64
import os
import struct
import sys
import xml.etree.ElementTree as ET
import zlib

GID_MASK = 0x1FFFFFFF
DEFAULT_ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                            "forge-gui", "res", "adventure", "The Forsaken Realms", "maps", "map")

_TSX = {}


def tileset_area(path, tw, th):
    """{tile id: collision area in px} for one .tsx."""
    path = os.path.normpath(path)
    if path in _TSX:
        return _TSX[path]
    out = {}
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError):
        _TSX[path] = out
        return out
    for tile in root.findall("tile"):
        group = tile.find("objectgroup")
        if group is None:
            continue
        area = 0.0
        for obj in group.findall("object"):
            if any(obj.find(s) is not None for s in ("polygon", "ellipse", "polyline", "text")):
                # Not a rectangle: MapStage.loadCollision() ignores it too, but treat it as solid
                # for connectivity so a shaped blocker cannot read as open floor.
                area = float(tw * th)
                continue
            area += float(obj.get("width", 0)) * float(obj.get("height", 0))
        if area > 0:
            out[int(tile.get("id"))] = area
    _TSX[path] = out
    return out


def decode_layer(layer):
    data = layer.find("data")
    if data is None:
        return []
    encoding = data.get("encoding")
    if encoding == "csv":
        return [int(v) for v in data.text.replace("\n", "").split(",") if v.strip()]
    if encoding == "base64":
        raw = base64.b64decode(data.text.strip())
        if data.get("compression") == "zlib":
            raw = zlib.decompress(raw)
        elif data.get("compression") == "gzip":
            import gzip
            raw = gzip.decompress(raw)
        return list(struct.unpack("<%dI" % (len(raw) // 4), raw))
    return [int(t.get("gid", 0)) for t in data.findall("tile")]


def components(path, max_coverage, min_island):
    root = ET.parse(path).getroot()
    width, height = int(root.get("width")), int(root.get("height"))
    tw, th = int(root.get("tilewidth")), int(root.get("tileheight"))
    base = os.path.dirname(os.path.abspath(path))

    tilesets = []
    for ts in root.findall("tileset"):
        src = ts.get("source")
        if src:
            tilesets.append((int(ts.get("firstgid")), os.path.normpath(os.path.join(base, src))))
    tilesets.sort()

    def owner(gid):
        found = None
        for first, tsx in tilesets:
            if gid >= first:
                found = (first, tsx)
        return found

    cover = [0.0] * (width * height)
    drawn = [False] * (width * height)
    for layer in root.findall(".//layer"):
        for index, raw in enumerate(decode_layer(layer)):
            gid = raw & GID_MASK
            if not gid:
                continue
            drawn[index] = True
            own = owner(gid)
            if own:
                cover[index] += tileset_area(own[1], tw, th).get(gid - own[0], 0.0)

    limit = tw * th * max_coverage
    # A cell with nothing drawn at all is off-map void, not floor.
    passable = [drawn[i] and cover[i] < limit for i in range(width * height)]

    seen = bytearray(width * height)
    found = []
    for start in range(width * height):
        if not passable[start] or seen[start]:
            continue
        stack = [start]
        seen[start] = 1
        size = 0
        cells = []
        while stack:
            i = stack.pop()
            size += 1
            if len(cells) < 4:
                cells.append((i % width, i // width))
            x, y = i % width, i // width
            for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if 0 <= nx < width and 0 <= ny < height:
                    j = ny * width + nx
                    if passable[j] and not seen[j]:
                        seen[j] = 1
                        stack.append(j)
        if size >= min_island:
            found.append((size, cells[0]))
    found.sort(reverse=True)
    return found, width, height


def iter_maps(paths):
    for path in paths:
        if os.path.isfile(path) and path.endswith(".tmx"):
            yield path
        elif os.path.isdir(path):
            for dirpath, _, files in os.walk(path):
                for f in sorted(files):
                    if f.endswith(".tmx"):
                        yield os.path.join(dirpath, f)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="*", default=[DEFAULT_ROOT])
    ap.add_argument("--min-island", type=int, default=12,
                    help="ignore pockets smaller than this many tiles (default 12)")
    ap.add_argument("--max-coverage", type=float, default=0.6,
                    help="a tile is passable below this collision coverage (default 0.6)")
    args = ap.parse_args()

    split = 0
    scanned = 0
    for path in iter_maps(args.paths or [DEFAULT_ROOT]):
        try:
            found, w, h = components(path, args.max_coverage, args.min_island)
        except (ET.ParseError, OSError, struct.error, ValueError) as exc:
            print("  !! %s: %s" % (os.path.basename(path), exc), file=sys.stderr)
            continue
        scanned += 1
        if len(found) > 1:
            split += 1
            rel = os.path.relpath(path, DEFAULT_ROOT)
            total = sum(s for s, _ in found)
            print("%-52s %dx%d  %d separate region(s): %s"
                  % (rel, w, h, len(found),
                     ", ".join("%d tiles @(%d,%d)" % (s, c[0], c[1]) for s, c in found[:5])))
            biggest = found[0][0]
            print("%-52s   -> %d of %d walkable tiles (%.0f%%) are cut off from the largest region"
                  % ("", total - biggest, total, 100.0 * (total - biggest) / total))

    print("\nscanned %d map(s); %d have more than one walkable region" % (scanned, split))
    return 0


if __name__ == "__main__":
    sys.exit(main())
