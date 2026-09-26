#!/usr/bin/env python
"""
player_road.py - the player road's overworld autotile (round 346).

The cobbles the user boxed on Road.png (an RPG Maker MV A2 sheet, block row 1 col 2) with the sand keyed out - the
same block dev-tools/tiled-tilesets/make_tilesets.py cuts for the place maps - as an XP autotile at the overworld's
HD tile size: 48 px VX (2x3) -> 48 px XP (3x4) the way a2_convert does -> 2/3 to 32 px = a 96x128 sheet, the size of
every HD terrain region (player_terrain_hd's Player is 96x128). Written to world/tilesets/player_road.png + .atlas
(region "PlayerRoad"); world.json's playerRoadTileset points at it and World draws it as the layer above the old road.

    python dev-tools/world-art/player_road.py            # writes into the plane
    python dev-tools/world-art/player_road.py --out DIR  # elsewhere (a preview)
"""
import argparse
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(REPO, "dev-tools", "tiled-tilesets"))
from make_tilesets import ROAD_BLOCK, key_out_sand, vx_to_xp, crisp  # noqa: E402

PLANE = os.path.join(REPO, "forge-gui", "res", "adventure", "The Forsaken Realms")
DEFAULT_OUT = os.path.join(PLANE, "world", "tilesets")
ART = r"C:\Users\User\Pictures\Screenshots\Terrain\Road.png"
HD = 32


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--art", default=ART)
    a = ap.parse_args()
    src = Image.open(a.art).convert("RGBA")
    row, col = ROAD_BLOCK
    block = key_out_sand(src.crop((col * 96, row * 144, col * 96 + 96, row * 144 + 144)))
    xp48 = vx_to_xp(block, 48)                                   # 144 x 192
    xp = crisp(xp48.resize((3 * HD, 4 * HD), Image.LANCZOS))     # 96 x 128, alpha made crisp like every HD sheet
    os.makedirs(a.out, exist_ok=True)
    png = os.path.join(a.out, "player_road.png")
    xp.save(png)
    with open(os.path.join(a.out, "player_road.atlas"), "w", encoding="utf-8", newline="\n") as f:
        f.write("player_road.png\nsize: %d,%d\nformat: RGBA8888\nfilter: Nearest,Nearest\nrepeat: none\n" % xp.size)
        f.write("PlayerRoad\n  rotate: false\n  xy: 0, 0\n  size: %d, %d\n  orig: %d, %d\n  offset: 0, 0\n  index: -1\n"
                % (xp.width, xp.height, xp.width, xp.height))
    print("player_road: %s %s" % (png, xp.size))


if __name__ == "__main__":
    main()
