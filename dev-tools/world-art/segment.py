"""Round 303: cut a sprite sheet with a transparent background into its separate sprites and number them.
python segment.py <sheet> <prefix> [gap]
Connected components on alpha >= 24 (8-neighborhood), then boxes closer than `gap` px merged (a flower's
petals and stem, a shadow under a rock). Writes <prefix>_boxes.json and <prefix>_catalog.png (each sprite on a
gray card with its number, at 2x)."""
import json
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import paths  # noqa: E402


def components(alpha, w, h, thr=int(os.environ.get("SEG_THR", "24"))):
    seen = bytearray(w * h)
    boxes = []
    px = alpha.load()
    for y in range(h):
        for x in range(w):
            i = y * w + x
            if seen[i] or px[x, y] < thr:
                continue
            stack = [(x, y)]
            seen[i] = 1
            x0 = x1 = x
            y0 = y1 = y
            n = 0
            while stack:
                cx, cy = stack.pop()
                n += 1
                x0, x1, y0, y1 = min(x0, cx), max(x1, cx), min(y0, cy), max(y1, cy)
                for dx in (-1, 0, 1):
                    for dy in (-1, 0, 1):
                        nx, ny = cx + dx, cy + dy
                        if 0 <= nx < w and 0 <= ny < h:
                            j = ny * w + nx
                            if not seen[j] and px[nx, ny] >= thr:
                                seen[j] = 1
                                stack.append((nx, ny))
            boxes.append([x0, y0, x1 + 1, y1 + 1, n])
    return boxes


def merge(boxes, gap):
    changed = True
    while changed:
        changed = False
        out = []
        while boxes:
            a = boxes.pop()
            for b in boxes[:]:
                if a[0] - gap <= b[2] and b[0] - gap <= a[2] and a[1] - gap <= b[3] and b[1] - gap <= a[3]:
                    a = [min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]), a[4] + b[4]]
                    boxes.remove(b)
                    changed = True
            out.append(a)
        boxes = out
    return boxes


def main():
    sheet, prefix = sys.argv[1], sys.argv[2]
    gap = int(sys.argv[3]) if len(sys.argv) > 3 else 3
    im = Image.open(sheet).convert("RGBA")
    boxes = [b for b in merge(components(im.getchannel("A"), *im.size), gap) if b[4] >= 20]
    boxes.sort(key=lambda b: (b[1] // 24, b[0]))
    json.dump([b[:4] for b in boxes], open(os.path.join(HERE, "boxes", prefix + "_boxes.json"), "w"))
    card = 110
    cols = 16
    rows = (len(boxes) + cols - 1) // cols
    cat = Image.new("RGB", (cols * card, rows * card), (70, 70, 70))
    d = ImageDraw.Draw(cat)
    for k, (x0, y0, x1, y1, _) in enumerate(boxes):
        spr = im.crop((x0, y0, x1, y1))
        s = min(2.0, (card - 16) / max(spr.width, spr.height))
        spr = spr.resize((max(1, int(spr.width * s)), max(1, int(spr.height * s))), Image.NEAREST)
        bg = Image.new("RGBA", (card - 4, card - 4), (120, 150, 90, 255))
        bg.alpha_composite(spr, ((card - 4 - spr.width) // 2, (card - 4 - spr.height) // 2 + 6))
        cx, cy = (k % cols) * card, (k // cols) * card
        cat.paste(bg.convert("RGB"), (cx + 2, cy + 2))
        d.text((cx + 4, cy + 3), "%d  %dx%d" % (k, x1 - x0, y1 - y0), fill=(255, 255, 0))
    cat.save(os.path.join(paths.OUT, prefix + "_catalog.png"))
    print(prefix, len(boxes), "sprites ->", prefix + "_catalog.png", cat.size)


main()
