"""Round 305: a whirlpool doodad from the user's Wirlpool.png (the MV A1 whirlpool, one 48 px swirl tile), recoloured
into the in-game ocean's palette (its water sits at (21,99,151), the ocean's Base tile at (48,175,218)) and faded out
in a circle so it blends into the sea instead of showing as a square."""
import math

from PIL import Image

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from paths import ART  # noqa: E402

SRC = os.path.join(ART, "Wirlpool.png")
OCEAN = (48, 175, 218)
BG = (18, 79, 137)


def whirlpool():
    tile = Image.open(SRC).convert("RGBA").crop((288, 288, 336, 336))
    gain = [OCEAN[c] / BG[c] for c in range(3)]
    out = Image.new("RGBA", tile.size, (0, 0, 0, 0))
    src, dst = tile.load(), out.load()
    cx = cy = 23.5
    for y in range(48):
        for x in range(48):
            r, g, b, a = src[x, y]
            d = math.hypot(x - cx, y - cy)
            fade = max(0.0, min(1.0, (23.0 - d) / 7.0))
            if fade <= 0:
                continue
            rr, gg, bb = min(255, int(r * gain[0])), min(255, int(g * gain[1])), min(255, int(b * gain[2]))
            dst[x, y] = (rr, gg, bb, int(a * fade))
    return out


if __name__ == "__main__":
    wp = whirlpool()
    wp.save("whirlpool_48.png")
    # preview on the in-game ocean tile
    import sys
    sys.path.insert(0, ".")
    from atlas import read_atlas
    r = read_atlas(r"C:\TFR\repo\forge-gui\res\adventure\common\world\tilesets\terrain.atlas")["Base"][0]
    page = Image.open(r["page"]).convert("RGBA")
    tile = page.crop((r["x"] + 16, r["y"] + 32, r["x"] + 32, r["y"] + 48)).resize((32, 32), Image.NEAREST)
    sea = Image.new("RGBA", (192, 128))
    for y in range(0, 128, 32):
        for x in range(0, 192, 32):
            sea.alpha_composite(tile, (x, y))
    big = wp.resize((64, 64), Image.NEAREST)   # drawn at 2 tiles (scale 2/3 of the 48 px picture)
    sea.alpha_composite(big, (32, 32))
    sea.alpha_composite(wp.resize((48, 48), Image.NEAREST), (120, 40))
    sea.resize((576, 384), Image.NEAREST).save("whirlpool_preview.png")
    print("ok")
