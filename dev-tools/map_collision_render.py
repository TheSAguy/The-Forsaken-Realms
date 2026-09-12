#!/usr/bin/env python3
"""Render a .tmx map to PNG with its collision geometry drawn on top.

This is the F12 overlay, offline. It reproduces what MapStage.loadCollision() builds - one
rectangle per collision object per cell, taken from the tileset definition - so a map can be
inspected without launching the game, and a screenshot from a player can be compared against
what the data actually says.

Coordinates: Tiled stores tile-collision objects top-down within the tile, and libGDX flips
BOTH the layer rows and the object's y when it loads. The two flips cancel, so a box drawn at
(cellX * tw + obj.x, cellY * th + obj.y) in top-down image space lands exactly where the player
meets it in game.

Usage
-----
    python dev-tools/map_collision_render.py <map.tmx> -o out.png [--scale 3] [--no-art]
"""

import argparse
import base64
import os
import struct
import sys
import xml.etree.ElementTree as ET
import zlib

from PIL import Image, ImageDraw

H_FLIP = 0x80000000
V_FLIP = 0x40000000
D_FLIP = 0x20000000
GID_MASK = 0x1FFFFFFF


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


class Tileset:
    """One external .tsx: its atlas image, grid, and per-tile collision rectangles."""

    def __init__(self, path):
        self.path = path
        root = ET.parse(path).getroot()
        self.tw = int(root.get("tilewidth"))
        self.th = int(root.get("tileheight"))
        self.columns = int(root.get("columns") or 0)
        self.margin = int(root.get("margin") or 0)
        self.spacing = int(root.get("spacing") or 0)
        self.image = None
        img = root.find("image")
        if img is not None:
            src = os.path.join(os.path.dirname(path), img.get("source"))
            if os.path.exists(src):
                self.image = Image.open(src).convert("RGBA")
        self.rects = {}
        for tile in root.findall("tile"):
            group = tile.find("objectgroup")
            if group is None:
                continue
            boxes = []
            for obj in group.findall("object"):
                if any(obj.find(shape) is not None
                       for shape in ("polygon", "ellipse", "polyline", "text")):
                    continue
                boxes.append((float(obj.get("x", 0)), float(obj.get("y", 0)),
                              float(obj.get("width", 0)), float(obj.get("height", 0))))
            if boxes:
                self.rects[int(tile.get("id"))] = boxes

    def tile_image(self, local_id):
        if self.image is None or not self.columns:
            return None
        col = local_id % self.columns
        row = local_id // self.columns
        x = self.margin + col * (self.tw + self.spacing)
        y = self.margin + row * (self.th + self.spacing)
        if x + self.tw > self.image.width or y + self.th > self.image.height:
            return None
        return self.image.crop((x, y, x + self.tw, y + self.th))


def render(tmx_path, out_path, scale=3, draw_art=True, draw_boxes=True, crop=None):
    """Render the map. `crop` is (x0, y0, x1, y1) in TILES, to inspect one area closely."""
    root = ET.parse(tmx_path).getroot()
    width = int(root.get("width"))
    height = int(root.get("height"))
    tw = int(root.get("tilewidth"))
    th = int(root.get("tileheight"))
    base = os.path.dirname(os.path.abspath(tmx_path))

    tilesets = []
    for ts in root.findall("tileset"):
        source = ts.get("source")
        if not source:
            continue
        path = os.path.normpath(os.path.join(base, source))
        if os.path.exists(path):
            tilesets.append((int(ts.get("firstgid")), Tileset(path)))
    tilesets.sort(key=lambda p: p[0])

    def owner(gid):
        found = None
        for first, ts in tilesets:
            if gid >= first:
                found = (first, ts)
        return found

    canvas = Image.new("RGBA", (width * tw, height * th), (20, 20, 28, 255))
    boxes = []

    for layer in root.findall(".//layer"):
        for index, raw in enumerate(decode_layer(layer)):
            if not raw:
                continue
            gid = raw & GID_MASK
            own = owner(gid)
            if not own:
                continue
            first, ts = own
            local = gid - first
            cx, cy = (index % width) * tw, (index // width) * th

            if draw_art:
                tile = ts.tile_image(local)
                if tile is not None:
                    if raw & H_FLIP:
                        tile = tile.transpose(Image.FLIP_LEFT_RIGHT)
                    if raw & V_FLIP:
                        tile = tile.transpose(Image.FLIP_TOP_BOTTOM)
                    if raw & D_FLIP:
                        tile = tile.transpose(Image.TRANSPOSE)
                    canvas.alpha_composite(tile, (cx, cy))

            for (ox, oy, ow, oh) in ts.rects.get(local, []):
                boxes.append((cx + ox, cy + oy, ow, oh))

    if crop:
        cx0, cy0, cx1, cy1 = crop
        px = (cx0 * tw, cy0 * th, cx1 * tw, cy1 * th)
        canvas = canvas.crop(px)
        boxes = [(x - px[0], y - px[1], w, h) for (x, y, w, h) in boxes
                 if px[0] <= x < px[2] and px[1] <= y < px[3]]

    if scale != 1:
        canvas = canvas.resize((canvas.width * scale, canvas.height * scale), Image.NEAREST)

    if draw_boxes:
        overlay = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        pen = ImageDraw.Draw(overlay)
        for (x, y, w, h) in boxes:
            x0, y0 = x * scale, y * scale
            x1, y1 = (x + w) * scale - 1, (y + h) * scale - 1
            if x1 < x0 or y1 < y0:
                continue
            pen.rectangle([x0, y0, x1, y1], fill=(255, 40, 40, 90), outline=(255, 60, 60, 255))
        canvas.alpha_composite(overlay)

    canvas.convert("RGB").save(out_path)
    full = sum(1 for (_, _, w, h) in boxes if w * h >= tw * th * 0.95)
    print("%s -> %s  (%dx%d tiles, %d collision boxes: %d full-tile, %d partial)"
          % (os.path.basename(tmx_path), out_path, width, height, len(boxes), full, len(boxes) - full))


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tmx")
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument("--scale", type=int, default=3)
    ap.add_argument("--no-art", action="store_true", help="collision only, on a flat background")
    ap.add_argument("--no-boxes", action="store_true", help="art only, to see what the player sees")
    ap.add_argument("--crop", help="x0,y0,x1,y1 in TILES, to inspect one area closely")
    args = ap.parse_args()
    crop = tuple(int(v) for v in args.crop.split(",")) if args.crop else None
    render(args.tmx, args.out, args.scale, not args.no_art, not args.no_boxes, crop)
    return 0


if __name__ == "__main__":
    sys.exit(main())
