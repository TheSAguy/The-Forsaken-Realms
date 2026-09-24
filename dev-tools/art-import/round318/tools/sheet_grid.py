"""Labeled variant grid of an RPG Maker sheet: every character block (v1-v8, left->right then top->bottom) or every
row of a front-only variant sheet (row 1-4), upscaled, on a mid-gray background - to check variant numbers by eye."""
import os, sys
from PIL import Image, ImageDraw
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rpgm import Sheet

ART = r"C:\Users\User\Desktop\New Enemy Art"


def grid(rel, out, scale=2, variants=False):
    sh = Sheet(os.path.join(ART, rel), (1, 1) if "$" in os.path.basename(rel) else (4, 2), "variants" if variants else "dirs")
    fw, fh = sh.fw, sh.fh
    tiles = []
    if variants:
        for r in range(4):
            t = Image.new("RGBA", (3 * fw, fh), (0, 0, 0, 0))
            for c in range(3):
                t.paste(sh.frame(0, r, c), (c * fw, 0))
            tiles.append(("row %d" % (r + 1), t))
    else:
        bx, by = sh.blocks
        for b in range(bx * by):
            t = Image.new("RGBA", (3 * fw, 4 * fh), (0, 0, 0, 0))
            for r in range(4):
                for c in range(3):
                    t.paste(sh.frame(b, r, c), (c * fw, r * fh))
            tiles.append(("v%d" % (b + 1), t))
    cols = min(4 if not variants else 2, len(tiles))
    tw, th = tiles[0][1].size[0] * scale, tiles[0][1].size[1] * scale
    pad, lab = 8, 16
    W = cols * (tw + pad) + pad
    rows = (len(tiles) + cols - 1) // cols
    H = rows * (th + pad + lab) + pad + 18
    img = Image.new("RGB", (W, H), (128, 128, 128))
    d = ImageDraw.Draw(img)
    d.text((pad, 3), "%s  frame %dx%d" % (rel, fw, fh), fill=(255, 255, 0))
    for i, (name, t) in enumerate(tiles):
        x = pad + (i % cols) * (tw + pad)
        y = 18 + pad + (i // cols) * (th + pad + lab)
        d.text((x, y), name, fill=(255, 255, 255))
        big = t.resize((tw, th), Image.NEAREST)
        img.paste(big, (x, y + lab), big)
        d.rectangle([x - 1, y + lab - 1, x + tw, y + lab + th], outline=(90, 90, 90))
    img.save(out)
    print(out, img.size)


if __name__ == "__main__":
    grid(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 2, "--variants" in sys.argv)
