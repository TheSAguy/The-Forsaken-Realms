"""Preview: a land's AREA structure (an autotile stitched from quarter tiles in the world) with and without a 1-px
black border drawn around the STITCHED shapes - the way an in-game, composition-aware outline would look.

Round 328 outlined only GRID structures (one picture per tile, see spec.OUTLINED_STRUCTURES): an area autotile's
outline cannot be baked into its block, because the world assembles each tile from four quarter tiles and a baked
outline breaks at those seams. The user, 2026-09-24, on the Wasteland's pale cone clusters ("hills" = the colorless
`rock` structure): "Let's see how the hills look with the same 1 pix border." This is that preview.

python outline_preview.py <repo root> <color> <structure> <out.png>
e.g.   python outline_preview.py C:/TFR/repo colorless rock C:/TFR/art-staging/world-art/hills_outline.png
"""
import os
import sys

if len(sys.argv) != 5 or not os.path.isdir(os.path.join(sys.argv[1], "forge-gui")):
    raise SystemExit("usage: python outline_preview.py <repo root> <color> <structure> <out.png>")
ROOT, COLOR, STRUCTURE, OUT = sys.argv[1:5]
sys.argv = [sys.argv[0], ROOT]   # build/export read the repo root from argv[1]
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from PIL import Image, ImageDraw  # noqa: E402

import build  # noqa: E402
import export  # noqa: E402

T = build.T
structs = build.new_structures(COLOR)
if STRUCTURE not in structs:
    raise SystemExit("%s has no structure %r - it has %s" % (COLOR, STRUCTURE, sorted(structs)))
base, patches = build.ground(COLOR)
W, H = 14, 8
cells_all = {(x, y) for y in range(H) for x in range(W)}
# a lone tile, a pair, a large cluster and a small one - the shapes the world makes
shapes = {(2, 1)} | {(5, 1), (6, 1)} \
    | {(x, y) for (x, y) in cells_all if ((x - 4) / 2.2) ** 2 + ((y - 5) / 1.8) ** 2 <= 1.0} \
    | {(10, 2), (11, 2), (10, 3), (11, 3), (12, 3), (11, 4)}

ground = Image.new("RGBA", (W * T, H * T), (0, 0, 0, 255))
build.draw_layer(ground, base, cells_all, W, H)
if patches:
    build.draw_layer(ground, patches[0], {(x, y) for (x, y) in cells_all
                                          if ((x - 10) / 2.2) ** 2 + ((y - 6.5) / 1.3) ** 2 <= 1}, W, H)
layer = Image.new("RGBA", (W * T, H * T), (0, 0, 0, 0))
build.draw_layer(layer, structs[STRUCTURE], shapes, W, H)   # stitched exactly as render.draw_tile() does

today = ground.copy()
today.alpha_composite(layer)
outlined = ground.copy()
outlined.alpha_composite(export.outline(layer, skip=(0, 0, 0, 0)))   # the outline AFTER stitching

sc = 2
a = today.resize((today.width * sc, today.height * sc), Image.NEAREST)
b = outlined.resize((outlined.width * sc, outlined.height * sc), Image.NEAREST)
sheet = Image.new("RGB", (a.width, a.height * 2 + 64), (30, 30, 30))
sheet.paste(a.convert("RGB"), (0, 32))
sheet.paste(b.convert("RGB"), (0, a.height + 64))
d = ImageDraw.Draw(sheet)
d.text((8, 10), "%s %s - TODAY" % (COLOR.upper(), STRUCTURE), fill=(255, 255, 0))
d.text((8, a.height + 42), "WITH A 1-PX BLACK BORDER around the stitched shapes", fill=(120, 255, 120))
sheet.save(OUT)
print(OUT, sheet.size)
