#!/usr/bin/env python3
"""Round 186: clear the three invisible walls in cave_amphin.tmx.

The defect
----------
Three cells of the Flooded Cave (cave_amphin.tmx) stand in open water and block the player
completely, with nothing drawn to explain it:

    tile (33,21)  Ground      main.tsx#1473
    tile (27,28)  Background  main.tsx#1947
    tile (36,31)  Ground      main.tsx#1472

All three tiles are plain water art - visually indistinguishable from main.tsx#1951, the water
tile the rest of the pool is painted with - but unlike #1951 they carry a full 16x16 collision
box. Verified by rendering: dev-tools/map_collision_render.py shows open water with a full-tile
box, and dev-tools/pixel_collision_qa.py lists them as free-standing obstacles unattached to any
wall, at the resolution the player's own collision box actually moves through.

The fix
-------
The map already loads main-nocollide.tsx (firstgid 11905) alongside main.tsx (firstgid 1).
Both tilesets point at the SAME image (main.png) with the same 10112 tile ids and the same
geometry - the no-collide copy simply carries no collision objects at all. The water around
these three cells is already a mix of both variants, so the author was converting tiles over
and missed these. Re-pointing the three gids at the no-collide tileset therefore changes the
rendered map by exactly zero pixels and removes only the collision.

Not touched: tile (16,11), a 4x4 box on a tiny pebble that IS drawn. Small and arguably a
nuisance, but it is visible, so it is a design call rather than a defect.

Writes cave_amphin.tmx.bak once, then rewrites only the <data> blocks it changes.
"""

import base64
import os
import re
import shutil
import struct
import sys
import xml.etree.ElementTree as ET
import zlib

MAP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "forge-gui", "res",
                   "adventure", "The Forsaken Realms", "maps", "map", "cave", "cave_amphin.tmx")

MAIN_FIRSTGID = 1
NOCOLLIDE_FIRSTGID = 11905

# (layer, tile x, tile y, expected main.tsx tile id)
FIXES = [
    ("Ground", 33, 21, 1473),
    ("Background", 27, 28, 1947),
    ("Ground", 36, 31, 1472),
]


def main():
    path = os.path.normpath(MAP)
    text = open(path, encoding="utf-8").read()
    root = ET.fromstring(text)
    width = int(root.get("width"))

    # Sanity-check the two tilesets really are the same art before re-pointing anything.
    firsts = {}
    for ts in root.findall("tileset"):
        src = (ts.get("source") or "")
        if src.endswith("main.tsx"):
            firsts["main"] = int(ts.get("firstgid"))
        elif src.endswith("main-nocollide.tsx"):
            firsts["nocollide"] = int(ts.get("firstgid"))
    if firsts.get("main") != MAIN_FIRSTGID or firsts.get("nocollide") != NOCOLLIDE_FIRSTGID:
        print("ABORT: tileset firstgids are %r, expected main=%d nocollide=%d"
              % (firsts, MAIN_FIRSTGID, NOCOLLIDE_FIRSTGID))
        return 1

    base = os.path.dirname(path)
    a = ET.parse(os.path.join(base, "../../../../common/maps/tileset/main.tsx")).getroot()
    b = ET.parse(os.path.join(base, "../../../../common/maps/tileset/main-nocollide.tsx")).getroot()
    if (a.find("image").get("source") != b.find("image").get("source")
            or a.get("columns") != b.get("columns") or a.get("tilecount") != b.get("tilecount")):
        print("ABORT: main.tsx and main-nocollide.tsx are not the same grid over the same image")
        return 1
    if any(t.find("objectgroup") is not None for t in b.findall("tile")):
        print("ABORT: main-nocollide.tsx carries collision objects; it is not a safe target")
        return 1

    changed = 0
    for layer in root.findall("layer"):
        name = layer.get("name")
        wanted = [f for f in FIXES if f[0] == name]
        if not wanted:
            continue
        data = layer.find("data")
        if data.get("encoding") != "base64" or data.get("compression") != "zlib":
            print("ABORT: layer %s is %s/%s, expected base64/zlib"
                  % (name, data.get("encoding"), data.get("compression")))
            return 1
        old_text = data.text
        raw = zlib.decompress(base64.b64decode(old_text.strip()))
        gids = list(struct.unpack("<%dI" % (len(raw) // 4), raw))

        touched = False
        for (_, tx, ty, tile_id) in wanted:
            index = ty * width + tx
            expected = MAIN_FIRSTGID + tile_id
            if gids[index] != expected:
                print("ABORT: %s (%d,%d) holds gid %d, expected %d - map has changed"
                      % (name, tx, ty, gids[index], expected))
                return 1
            gids[index] = NOCOLLIDE_FIRSTGID + tile_id
            print("  %-11s (%2d,%2d)  gid %5d -> %5d   (tile #%d, art unchanged)"
                  % (name, tx, ty, expected, gids[index], tile_id))
            touched = True
            changed += 1
        if not touched:
            continue

        packed = struct.pack("<%dI" % len(gids), *gids)
        new_b64 = base64.b64encode(zlib.compress(packed)).decode("ascii")
        # Keep the file's own indentation around the payload: swap only the base64 text.
        indent = re.match(r"\s*", old_text).group(0)
        tail = re.search(r"\s*$", old_text).group(0)
        replacement = indent + new_b64 + tail
        if text.count(old_text) != 1:
            print("ABORT: layer %s payload is not unique in the file" % name)
            return 1
        text = text.replace(old_text, replacement)

    if not changed:
        print("nothing to do")
        return 0

    backup = path + ".bak"
    if not os.path.exists(backup):
        shutil.copy2(path, backup)
        print("backup -> %s" % os.path.basename(backup))
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write(text)
    print("patched %d cell(s) in %s" % (changed, os.path.basename(path)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
