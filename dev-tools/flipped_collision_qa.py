#!/usr/bin/env python3
"""Find map cells whose collision box no longer matches the art, because the tile is flipped.

Why this exists
---------------
MapStage.loadCollision() (forge-gui-mobile/src/forge/adventure/stage/MapStage.java) builds a
collision rectangle straight from the TILESET's definition:

    for (MapObject collision : cell.getTile().getObjects())
        collisionRect.add(new Rectangle(layer.getTileWidth() * x + r.x, ...));

`cell.getTile()` is the UNFLIPPED tile. A TMX cell can carry horizontal, vertical and diagonal
flip bits in the top three bits of its gid, and libGDX honours them when it DRAWS the tile - but
the collision box above is taken from the unflipped original and is never mirrored to match.

For a full-tile 16x16 box that is harmless (a square is symmetric). For the partial boxes that
trace a shoreline or a wall edge - a 4x4 corner, a 16x4 strip, a 5x16 side - it means the art
shows the edge on one side and the game blocks the other. That is an invisible wall standing in
open ground, with an equally invisible gap where the art says solid.

This script reports exactly those cells: flipped, carrying a collision box, and that box not
symmetric under the flips applied.

Usage
-----
    python dev-tools/flipped_collision_qa.py [path ...]

Paths default to the whole plane's map tree. Each path may be a .tmx file or a directory.
"""

import argparse
import base64
import collections
import os
import struct
import sys
import xml.etree.ElementTree as ET
import zlib

H_FLIP = 0x80000000
V_FLIP = 0x40000000
D_FLIP = 0x20000000
GID_MASK = 0x1FFFFFFF

DEFAULT_ROOT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..",
    "forge-gui", "res", "adventure", "The Forsaken Realms", "maps", "map")

_TILESET_CACHE = {}


def tileset_rects(path):
    """{tile id: [(x, y, w, h), ...]} for every tile in a .tsx that carries collision."""
    path = os.path.normpath(path)
    if path in _TILESET_CACHE:
        return _TILESET_CACHE[path]
    out = {}
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError):
        _TILESET_CACHE[path] = out
        return out
    for tile in root.findall("tile"):
        group = tile.find("objectgroup")
        if group is None:
            continue
        boxes = []
        for obj in group.findall("object"):
            # Only rectangles: MapStage.loadCollision() ignores every other shape too.
            if obj.find("polygon") is not None or obj.find("ellipse") is not None:
                continue
            if obj.find("polyline") is not None or obj.find("text") is not None:
                continue
            boxes.append((float(obj.get("x", 0)), float(obj.get("y", 0)),
                          float(obj.get("width", 0)), float(obj.get("height", 0))))
        if boxes:
            out[int(tile.get("id"))] = boxes
    _TILESET_CACHE[path] = out
    return out


def decode_layer(layer):
    """Layer data as a flat list of raw gids, whatever encoding the map was saved with."""
    data = layer.find("data")
    if data is None:
        return []
    encoding = data.get("encoding")
    if encoding == "csv":
        return [int(v) for v in data.text.replace("\n", "").split(",") if v.strip()]
    if encoding == "base64":
        raw = base64.b64decode(data.text.strip())
        compression = data.get("compression")
        if compression == "zlib":
            raw = zlib.decompress(raw)
        elif compression == "gzip":
            import gzip
            raw = gzip.decompress(raw)
        return list(struct.unpack("<%dI" % (len(raw) // 4), raw))
    # Uncompressed XML <tile gid="..."/> children.
    return [int(t.get("gid", 0)) for t in data.findall("tile")]


def box_survives(box, tw, th, h_flip, v_flip, d_flip):
    """True when the box lands on the same pixels after the cell's flips are applied.

    If it does, the unflipped box MapStage uses is still correct and the cell is fine.
    """
    x, y, w, h = box
    if d_flip:
        # Diagonal flip transposes the tile; only a box symmetric about the diagonal survives,
        # and that needs a square tile to even be meaningful.
        if tw != th or (x, y, w, h) != (y, x, h, w):
            return False
    if h_flip and (x, w) != (tw - x - w, w):
        return False
    if v_flip and (y, h) != (th - y - h, h):
        return False
    return True


def scan_map(path):
    """(findings, cells_checked) for one .tmx file."""
    root = ET.parse(path).getroot()
    tw = int(root.get("tilewidth"))
    th = int(root.get("tileheight"))
    width = int(root.get("width"))
    base = os.path.dirname(os.path.abspath(path))

    tilesets = []
    for ts in root.findall("tileset"):
        source = ts.get("source")
        if not source:
            continue  # embedded tileset: no external .tsx to read
        tilesets.append((int(ts.get("firstgid")), os.path.normpath(os.path.join(base, source))))
    tilesets.sort()

    def owner(gid):
        found = None
        for first, tsx in tilesets:
            if gid >= first:
                found = (first, tsx)
        return found

    findings = []
    checked = 0
    for layer in root.findall(".//layer"):
        name = layer.get("name")
        for index, raw in enumerate(decode_layer(layer)):
            if not raw:
                continue
            h_flip = bool(raw & H_FLIP)
            v_flip = bool(raw & V_FLIP)
            d_flip = bool(raw & D_FLIP)
            if not (h_flip or v_flip or d_flip):
                continue
            gid = raw & GID_MASK
            own = owner(gid)
            if not own:
                continue
            boxes = tileset_rects(own[1]).get(gid - own[0])
            if not boxes:
                continue
            checked += 1
            bad = [b for b in boxes if not box_survives(b, tw, th, h_flip, v_flip, d_flip)]
            if bad:
                flips = "".join(c for c, on in (("H", h_flip), ("V", v_flip), ("D", d_flip)) if on)
                findings.append({
                    "x": index % width,
                    "y": index // width,
                    "layer": name,
                    "tsx": os.path.basename(own[1]),
                    "tile": gid - own[0],
                    "box": tuple(int(v) for v in bad[0]),
                    "flips": flips,
                })
    return findings, checked


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
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("paths", nargs="*", default=[DEFAULT_ROOT],
                        help="maps or directories to scan (default: the whole plane)")
    parser.add_argument("--details", type=int, default=8,
                        help="how many offending cells to list per map (default 8)")
    args = parser.parse_args()

    total_cells = 0
    total_checked = 0
    per_map = collections.Counter()
    per_tile = collections.Counter()

    for path in iter_maps(args.paths or [DEFAULT_ROOT]):
        try:
            findings, checked = scan_map(path)
        except (ET.ParseError, OSError, struct.error) as exc:
            print("  !! %s: %s" % (os.path.basename(path), exc), file=sys.stderr)
            continue
        total_checked += checked
        if not findings:
            continue
        rel = os.path.relpath(path, DEFAULT_ROOT)
        per_map[rel] = len(findings)
        total_cells += len(findings)
        print("%-52s %d cell(s)" % (rel, len(findings)))
        for f in findings[:args.details]:
            print("    x=%-3d y=%-3d %-12s %s#%-5d box=(%d,%d %dx%d) flip=%s"
                  % (f["x"], f["y"], f["layer"], f["tsx"], f["tile"],
                     f["box"][0], f["box"][1], f["box"][2], f["box"][3], f["flips"]))
        if len(findings) > args.details:
            print("    ... %d more" % (len(findings) - args.details))
        for f in findings:
            per_tile[(f["tsx"], f["tile"], f["box"], f["flips"])] += 1

    print()
    print("flipped cells carrying collision, checked: %d" % total_checked)
    print("of those, box does NOT match the flipped art: %d across %d map(s)"
          % (total_cells, len(per_map)))
    if per_tile:
        print()
        print("worst tiles:")
        for (tsx, tile, box, flips), n in per_tile.most_common(12):
            print("   x%-4d %s#%-5d box=(%d,%d %dx%d) flip=%s"
                  % (n, tsx, tile, box[0], box[1], box[2], box[3], flips))
    return 0


if __name__ == "__main__":
    sys.exit(main())
