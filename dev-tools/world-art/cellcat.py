"""Round 309: a catalog of one of the user's sheets (sources.SHEETS) - every non-empty grid cell, split into its separate
objects, each on a card with the ref that picks it ("ob:3,5" for a cell holding one object, "ob:0,1#1" for the second
of several), drawn at the size a doodad would get (fit into 30x30 at 32 px per tile) and at 2x.
python cellcat.py <key> [c0,r0,c1,r1] [--whole]   -> <OUT>/cells_<key>.png
--whole: the block is split into its objects as one piece, each named by its pixel box ("dt@336,48,380,140") -
for sheets whose objects straddle the grid."""
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import paths  # noqa: E402
import sources as S  # noqa: E402

CARD = 74


def main():
    key = sys.argv[1]
    g = S.SHEETS[key][1]
    im = S.sheet(key)
    cols, rows = im.width // g, im.height // g
    c0, r0, c1, r1 = [int(t) for t in sys.argv[2].split(",")] if len(sys.argv) > 2 else (0, 0, cols - 1, rows - 1)
    cards = []
    if "--whole" in sys.argv:
        x0, y0 = c0 * g, r0 * g
        crop = S.unshadow(im.crop((x0, y0, (c1 + 1) * g, (r1 + 1) * g)))
        for b in sorted(S.parts(crop, gap=None, least=40), key=lambda b: (b[1] // g, b[0])):
            ref = "%s@%d,%d,%d,%d" % (key, x0 + b[0], y0 + b[1], x0 + b[2], y0 + b[3])
            cards.append((ref, S.trim(crop.crop(tuple(b)))))
    for r in range(r0, r1 + 1) if "--whole" not in sys.argv else ():
        for c in range(c0, c1 + 1):
            crop = S.unshadow(im.crop((c * g, r * g, (c + 1) * g, (r + 1) * g)))
            found = S.parts(crop)
            if not found:
                continue
            for k, box in enumerate(found):
                ref = "%s:%d,%d" % (key, c, r) + ("#%d" % k if len(found) > 1 else "")
                cards.append((ref, S.trim(crop.crop(tuple(box)))))
    per = 18
    out = Image.new("RGB", (per * CARD, ((len(cards) + per - 1) // per) * CARD), (40, 40, 40))
    d = ImageDraw.Draw(out)
    for i, (ref, spr) in enumerate(cards):
        small = S.fit(spr, 30, 30)
        big = small.resize((small.width * 2, small.height * 2), Image.NEAREST)
        bg = Image.new("RGBA", (CARD - 4, CARD - 16), (104, 124, 84, 255))
        bg.alpha_composite(big, ((CARD - 4 - big.width) // 2, max(0, CARD - 16 - big.height)))
        x, y = (i % per) * CARD, (i // per) * CARD
        out.paste(bg.convert("RGB"), (x + 2, y + 14))
        d.text((x + 3, y + 1), ref.split(":", 1)[1] if ":" in ref else ref.split("@")[1], fill=(255, 255, 0))
    path = os.path.join(paths.OUT, "cells_%s%s.png" % (key, "_whole" if "--whole" in sys.argv else ""))
    out.save(path)
    print(path, out.size, len(cards), "objects")


if __name__ == "__main__":
    main()
