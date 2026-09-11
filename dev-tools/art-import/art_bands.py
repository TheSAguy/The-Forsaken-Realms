"""Band strips for the generic (non-Ragnarok) sheets: every detected band (row) of a sheet with ALL its frames, so each
band can be labeled Idle / Walk / Attack / Hit / Death / portrait / skip and its facing read at a glance.
usage: python art_bands.py <art dir> <art_rows.json> <out prefix>"""
import json, os, sys
from PIL import Image, ImageDraw

ART, ROWS, OUTP = sys.argv[1], sys.argv[2], sys.argv[3]
GENERIC = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 15, 18, 19, 21, 22, 23, 25, 26, 27, 28, 29, 31, 32, 33, 34, 35, 36,
           37, 38, 39, 40, 41, 43, 44, 46, 48, 49, 50, 51, 52, 53, 54, 56, 58, 59, 60, 62, 63, 64, 67, 68, 69, 72,
           200, 201, 206, 208, 210, 211, 212, 213, 214, 215, 216, 217, 220, 222, 224, 225, 227]
cat = json.load(open(ROWS))
by_index = {v["index"]: k for k, v in cat.items()}
TH, COLW, PER = 40, 700, 6
pages = [GENERIC[i:i + PER] for i in range(0, len(GENERIC), PER)]
for pi, page in enumerate(pages):
    blocks = []
    for idx in page:
        name = by_index[idx]
        bands = cat[name]["bands"]
        im = Image.open(os.path.join(ART, name)).convert("RGBA")
        h = 16 + len(bands) * (TH + 6)
        blk = Image.new("RGB", (COLW, h), (58, 110, 38))
        d = ImageDraw.Draw(blk)
        d.text((4, 2), "#%d %s" % (idx, name[:70]), fill=(255, 255, 0))
        for bi, band in enumerate(bands):
            y = 16 + bi * (TH + 6)
            d.text((4, y + 12), "b%d n%d" % (bi, len(band)), fill=(200, 230, 255))
            x = 52
            for box in band:
                fr = im.crop(tuple(box))
                r = TH / max(1, fr.size[1])
                w = max(1, int(fr.size[0] * r))
                if w > 90:
                    r = 90 / fr.size[0]
                    w = 90
                t = fr.resize((w, max(1, int(fr.size[1] * r))), Image.LANCZOS)
                if x + w > COLW - 4:
                    d.text((x, y + 12), "+", fill=(255, 255, 255))
                    break
                blk.paste(t, (x, y + (TH - t.size[1])), t)
                x += w + 4
        blocks.append(blk)
    left = blocks[0::2]
    right = blocks[1::2]
    H = max(sum(b.size[1] for b in left), sum(b.size[1] for b in right)) + 4
    sheet = Image.new("RGB", (COLW * 2 + 6, H), (30, 60, 20))
    for col, bl in ((0, left), (1, right)):
        y = 0
        for b in bl:
            sheet.paste(b, (col * (COLW + 6), y))
            y += b.size[1] + 2
    out = "%s_%02d.png" % (OUTP, pi + 1)
    sheet.save(out)
    print(out, sheet.size)
