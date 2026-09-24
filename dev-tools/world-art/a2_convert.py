"""Round 303: the MV World A2 sheet (6a2a0a2dc...image8.png, 768x576 = 8x4 autotiles of 2x3 tiles at 48 px) ->
XP autotiles at 32 px per tile (96x128), the structure sheets' format for the 2x renderer.

VX/MV autotile: top-left = thumbnail, top-right = inner corners, bottom 2x2 = the outer frame.
XP: row 0 = [thumbnail][unused][inner corners]; rows 1-3 = the 3x3 frame from the 2x2 block in half-tile
minitiles, column/row c <- [0,1,2,1,2,3][c].
Downscale 48 -> 32 px: area average in premultiplied space, then alpha made crisp.
Writes a2/<col>_<row>.png (XP 96x128) and a2_catalog.png (labeled, 2x, on grass).
"""
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
import sys

sys.path.insert(0, HERE)
import paths  # noqa: E402

SRC = os.path.join(paths.ART, "6a2a0a2dc1af77454b9e51dc_image8.png")
T_SRC, T = 48, 32
M = [0, 1, 2, 1, 2, 3]
NAMES = {
    (0, 0): "grass", (1, 0): "grass soft", (2, 0): "grass dark", (3, 0): "grass deep",
    (4, 0): "forest", (5, 0): "pines", (6, 0): "hills", (7, 0): "mountains",
    (0, 1): "paving", (1, 1): "sand blots", (2, 1): "sand", (3, 1): "sand soft",
    (4, 1): "dead forest", (5, 1): "pale sand", (6, 1): "sandstone", (7, 1): "mesas",
    (0, 2): "dunes", (1, 2): "dunes 2", (2, 2): "stones", (3, 2): "lava ground",
    (4, 2): "palms", (5, 2): "bricks", (6, 2): "grey peaks", (7, 2): "volcanoes",
    (0, 3): "snow", (1, 3): "snow peaks", (2, 3): "snow soft", (3, 3): "snow drifts",
    (4, 3): "snow pines", (5, 3): "crater", (6, 3): "spires", (7, 3): "ice rocks",
}


def crisp(img):
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            px[x, y] = (r, g, b, 255) if a >= 110 else (0, 0, 0, 0)
    return img


def downscale(block):
    pm = block.convert("RGBa")
    small = pm.resize((block.width * T // T_SRC, block.height * T // T_SRC), Image.BOX)
    return crisp(small.convert("RGBA"))


def vx_to_xp(vx):
    xp = Image.new("RGBA", (3 * T, 4 * T), (0, 0, 0, 0))
    xp.paste(vx.crop((0, 0, T, T)), (0, 0))              # thumbnail
    xp.paste(vx.crop((T, 0, 2 * T, T)), (2 * T, 0))      # inner corners
    h = T // 2
    for r in range(6):
        for c in range(6):
            piece = vx.crop((M[c] * h, T + M[r] * h, M[c] * h + h, T + M[r] * h + h))
            xp.paste(piece, (c * h, T + r * h))
    return xp


def main():
    src = Image.open(SRC).convert("RGBA")
    # the rip's orange guide frames around some autotiles (253, 93, 10) would become lines along every blob edge
    px = src.load()
    for y in range(src.height):
        for x in range(src.width):
            if px[x, y] == (253, 93, 10, 255):
                px[x, y] = (0, 0, 0, 0)
    os.makedirs(os.path.join(HERE, "autotiles", "a2"), exist_ok=True)
    cell_w, cell_h = 3 * T * 2 + 10, 4 * T * 2 + 22
    cat = Image.new("RGB", (8 * cell_w, 4 * cell_h), (40, 40, 40))
    d = ImageDraw.Draw(cat)
    for (c, r), name in NAMES.items():
        block = src.crop((c * 2 * T_SRC, r * 3 * T_SRC, (c + 1) * 2 * T_SRC, (r + 1) * 3 * T_SRC))
        xp = vx_to_xp(downscale(block))
        xp.save(os.path.join(HERE, "autotiles", "a2", "%d_%d.png" % (c, r)))
        big = xp.resize((xp.width * 2, xp.height * 2), Image.NEAREST)
        bg = Image.new("RGBA", big.size, (104, 138, 72, 255))
        bg.alpha_composite(big)
        x, y = c * cell_w, r * cell_h
        cat.paste(bg.convert("RGB"), (x, y + 20))
        d.text((x + 2, y + 4), "%d_%d %s" % (c, r, name), fill=(255, 255, 0))
    cat.save(os.path.join(paths.OUT, "a2_catalog.png"))
    print("a2_catalog.png", cat.size)


if __name__ == "__main__":
    main()
