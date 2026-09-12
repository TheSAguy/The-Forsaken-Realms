#!/usr/bin/env python3
"""Find floating collision obstacles and impassable gaps, at the resolution the game uses.

Why pixel resolution
--------------------
Tile-level collision analysis is wrong for this game. MapStage.loadCollision() keeps each
tileset object's own x/y/width/height, so a tile can block a 4x4 corner or a 16x4 strip rather
than its whole square - a shoreline traces the water's edge INSIDE the tile. Treating any cell
that carries a box as "blocked" both over-reports (a nub becomes a wall) and, worse, lets a
flood fill leak straight through a rock face built from partial edge tiles.

So this rasterises every collision rectangle to pixels, then walks the map with the player's
ACTUAL collision box - CharacterSprite.updateBoundingRect() uses (x + 4, y, width - 6,
height * 0.4), about 10x6 for a 16px sprite - and reports two things:

  islands  blocked pixel regions completely surrounded by reachable free space. These are
           obstacles standing in open ground. Small ones with no art behind them are the
           "invisible collision block" the player walks into and cannot see.

  pockets  free space the player cannot reach from the entrance, i.e. sealed-off rooms, and
           gaps too narrow for the player's box to fit through.

Usage
-----
    python dev-tools/pixel_collision_qa.py <map.tmx> [...] [--max-island 256] [--player 10x6]
"""

import argparse
import base64
import collections
import os
import struct
import sys
import xml.etree.ElementTree as ET
import zlib

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


_TSX = {}


def tileset_rects(path):
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
        boxes = []
        for obj in group.findall("object"):
            if any(obj.find(s) is not None for s in ("polygon", "ellipse", "polyline", "text")):
                continue
            boxes.append((float(obj.get("x", 0)), float(obj.get("y", 0)),
                          float(obj.get("width", 0)), float(obj.get("height", 0))))
        if boxes:
            out[int(tile.get("id"))] = boxes
    _TSX[path] = out
    return out


def build_grid(tmx_path):
    """(blocked, W_px, H_px, tw, th) - blocked[y * W_px + x] is True where the player is stopped."""
    root = ET.parse(tmx_path).getroot()
    width, height = int(root.get("width")), int(root.get("height"))
    tw, th = int(root.get("tilewidth")), int(root.get("tileheight"))
    base = os.path.dirname(os.path.abspath(tmx_path))

    tilesets = []
    for ts in root.findall("tileset"):
        src = ts.get("source")
        if src:
            tilesets.append((int(ts.get("firstgid")), os.path.normpath(os.path.join(base, src))))
    tilesets.sort()

    def owner(gid):
        found = None
        for first, path in tilesets:
            if gid >= first:
                found = (first, path)
        return found

    wpx, hpx = width * tw, height * th
    blocked = bytearray(wpx * hpx)
    for layer in root.findall(".//layer"):
        for index, raw in enumerate(decode_layer(layer)):
            gid = raw & GID_MASK
            if not gid:
                continue
            own = owner(gid)
            if not own:
                continue
            for (ox, oy, ow, oh) in tileset_rects(own[1]).get(gid - own[0], []):
                cx = (index % width) * tw + int(ox)
                cy = (index // width) * th + int(oy)
                for py in range(max(0, cy), min(hpx, cy + int(round(oh)))):
                    row = py * wpx
                    for px in range(max(0, cx), min(wpx, cx + int(round(ow)))):
                        blocked[row + px] = 1
    return blocked, wpx, hpx, tw, th


def free_positions(blocked, wpx, hpx, pw, ph):
    """Configuration space: free[i] is True where the player's box fits with its corner at i.

    Built with a 2-D sliding window (summed-area style prefix sums) so a large map stays fast.
    """
    # column sums of a ph-tall window
    col = bytearray(wpx * hpx)
    for x in range(wpx):
        run = 0
        for y in range(ph):
            if y < hpx and blocked[y * wpx + x]:
                run += 1
        for y in range(hpx):
            col[y * wpx + x] = 1 if run else 0
            top = y
            bot = y + ph
            if blocked[top * wpx + x]:
                run -= 1
            if bot < hpx and blocked[bot * wpx + x]:
                run += 1
    free = bytearray(wpx * hpx)
    for y in range(hpx):
        row = y * wpx
        run = 0
        for x in range(pw):
            if x < wpx and col[row + x]:
                run += 1
        for x in range(wpx):
            free[row + x] = 0 if run else 1
            if col[row + x]:
                run -= 1
            if x + pw < wpx and col[row + x + pw]:
                run += 1
    # a box that would hang off the map edge is not a legal position
    for y in range(hpx):
        for x in range(max(0, wpx - pw + 1), wpx):
            free[y * wpx + x] = 0
    for y in range(max(0, hpx - ph + 1), hpx):
        for x in range(wpx):
            free[y * wpx + x] = 0
    return free


def flood(mask, wpx, hpx, seeds, want=1):
    seen = bytearray(wpx * hpx)
    stack = []
    for s in seeds:
        if mask[s] == want and not seen[s]:
            seen[s] = 1
            stack.append(s)
    while stack:
        i = stack.pop()
        x, y = i % wpx, i // wpx
        if x + 1 < wpx:
            j = i + 1
            if mask[j] == want and not seen[j]:
                seen[j] = 1
                stack.append(j)
        if x:
            j = i - 1
            if mask[j] == want and not seen[j]:
                seen[j] = 1
                stack.append(j)
        if y + 1 < hpx:
            j = i + wpx
            if mask[j] == want and not seen[j]:
                seen[j] = 1
                stack.append(j)
        if y:
            j = i - wpx
            if mask[j] == want and not seen[j]:
                seen[j] = 1
                stack.append(j)
    return seen


def analyse(tmx_path, player, max_island):
    blocked, wpx, hpx, tw, th = build_grid(tmx_path)
    pw, ph = player
    free = free_positions(blocked, wpx, hpx, pw, ph)

    total_free = sum(free)
    if not total_free:
        print("%s: no legal player position at all (%dx%d px)" % (os.path.basename(tmx_path), wpx, hpx))
        return
    # seed from the largest free region: walk every free pixel once, keep the biggest component
    seen = bytearray(wpx * hpx)
    best = None
    for start in range(wpx * hpx):
        if not free[start] or seen[start]:
            continue
        comp = flood(free, wpx, hpx, [start], want=1)
        size = sum(comp)
        for i in range(wpx * hpx):
            if comp[i]:
                seen[i] = 1
        if best is None or size > best[1]:
            best = (comp, size)
    reachable, reach_size = best

    pockets = total_free - reach_size

    # blocked islands: components of blocked pixels that never touch the map border
    border = [x for x in range(wpx)] + [(hpx - 1) * wpx + x for x in range(wpx)]
    border += [y * wpx for y in range(hpx)] + [y * wpx + wpx - 1 for y in range(hpx)]
    outer = flood(blocked, wpx, hpx, border, want=1)

    islands = []
    seen_b = bytearray(wpx * hpx)
    for start in range(wpx * hpx):
        if not blocked[start] or outer[start] or seen_b[start]:
            continue
        comp = flood(blocked, wpx, hpx, [start], want=1)
        cells = [i for i in range(wpx * hpx) if comp[i]]
        for i in cells:
            seen_b[i] = 1
        if len(cells) > max_island:
            continue
        xs = [i % wpx for i in cells]
        ys = [i // wpx for i in cells]
        islands.append({
            "area": len(cells),
            "x0": min(xs), "y0": min(ys), "x1": max(xs), "y1": max(ys),
            "tile": (min(xs) // tw, min(ys) // th),
        })

    name = os.path.basename(tmx_path)
    print("=== %s  %dx%d px, player box %dx%d" % (name, wpx, hpx, pw, ph))
    print("    legal player positions: %d, reachable from the main area: %d" % (total_free, reach_size))
    print("    unreachable free space (sealed rooms / gaps too narrow): %d px" % pockets)
    print("    free-standing obstacles (<= %d px, not attached to the map border): %d"
          % (max_island, len(islands)))
    for isl in sorted(islands, key=lambda d: d["area"])[:20]:
        print("       tile (%2d,%2d)  %d px  box %dx%d at px (%d,%d)"
              % (isl["tile"][0], isl["tile"][1], isl["area"],
                 isl["x1"] - isl["x0"] + 1, isl["y1"] - isl["y0"] + 1, isl["x0"], isl["y0"]))


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("maps", nargs="+")
    ap.add_argument("--player", default="10x6", help="player collision box, WxH px (default 10x6)")
    ap.add_argument("--max-island", type=int, default=256,
                    help="largest free-standing obstacle to report, in px (default 256 = one tile)")
    args = ap.parse_args()
    pw, ph = (int(v) for v in args.player.lower().split("x"))
    for m in args.maps:
        analyse(m, (pw, ph), args.max_island)
    return 0


if __name__ == "__main__":
    sys.exit(main())
