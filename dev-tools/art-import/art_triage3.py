"""Art triage, pass 3: for every sheet, the first pose of each animation ROW, downscaled to game size (larger side
32 px, drawn x2), so a page shows at a glance which rows are body animations and whether they read at game size.
Writes art3_sheet_NN.png (18 sheets per page) and art_rows.json (the row/frame boxes, for the converter).
usage: python art_triage3.py <art dir> <out dir>"""
import json, os, sys
from PIL import Image, ImageDraw

ART, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)


def runs(flags, min_gap):
    out, start, gap, end = [], None, 0, 0
    for i, f in enumerate(flags):
        if f:
            if start is None:
                start = i
            gap, end = 0, i
        elif start is not None:
            gap += 1
            if gap >= min_gap:
                out.append((start, end + 1))
                start, gap = None, 0
    if start is not None:
        out.append((start, end + 1))
    return out


def analyse(path):
    im = Image.open(path)
    im.load()
    rgba = im.convert("RGBA")
    W, H = rgba.size
    f = max(1, max(W, H) // 1800)
    a = rgba.getchannel("A")
    s = a.resize((max(1, W // f), max(1, H // f)), Image.NEAREST) if f > 1 else a
    sw, sh = s.size
    px = s.load()
    rows_on = [any(px[x, y] >= 24 for x in range(0, sw, 2)) for y in range(sh)]
    bands = []
    for (y0, y1) in runs(rows_on, max(3, sh // 150)):
        bh = y1 - y0
        if bh * f < 24:
            continue
        cols = [any(px[x, y] >= 24 for y in range(y0, y1, 2)) for x in range(sw)]
        segs = [sg for sg in runs(cols, max(3, sw // 300)) if sg[1] - sg[0] > 3]
        frames = [sg for sg in segs if 0.2 <= (sg[1] - sg[0]) / max(1, bh) <= 4.0]
        if not frames:
            continue
        if len(frames) == 1 and (frames[0][1] - frames[0][0]) / max(1, bh) > 3.0:
            continue  # a text banner / watermark strip
        boxes = []
        for (x0, x1) in frames:
            crop = rgba.crop((x0 * f, y0 * f, x1 * f, y1 * f))
            bb = crop.getchannel("A").point(lambda v: 255 if v >= 24 else 0).getbbox()
            if bb:
                boxes.append([x0 * f + bb[0], y0 * f + bb[1], x0 * f + bb[2], y0 * f + bb[3]])
        if boxes:
            bands.append(boxes)
    return rgba, bands


def thumb(rgba, box, h=32, maxw=48):
    fr = rgba.crop(tuple(box))
    r = h / fr.size[1]
    w = max(1, round(fr.size[0] * r))
    if w > maxw:
        r = maxw / fr.size[0]
        w, h = maxw, max(1, round(fr.size[1] * r))
    return fr.resize((w, h), Image.LANCZOS)


names = sorted(n for n in os.listdir(ART) if n.lower().endswith(".png"))
catalog = {}
PER, COLW, ROWH = 18, 690, 92
page = []
for idx, name in enumerate(names, 1):
    try:
        rgba, bands = analyse(os.path.join(ART, name))
    except Exception as e:
        rgba, bands = None, []
    catalog[name] = {"index": idx, "bands": bands, "size": list(rgba.size) if rgba else None}
    page.append((idx, name, rgba, bands))
    if len(page) == PER or idx == len(names):
        sheet = Image.new("RGB", (COLW * 2, ROWH * ((len(page) + 1) // 2)), (58, 110, 38))
        d = ImageDraw.Draw(sheet)
        for j, (i2, n2, im2, b2) in enumerate(page):
            x0, y0 = (j % 2) * COLW, (j // 2) * ROWH
            d.text((x0 + 4, y0 + 2), "#%d %s" % (i2, n2[:58]), fill=(255, 255, 0))
            d.text((x0 + 4, y0 + 14), "rows %d: %s" % (len(b2), " ".join(str(len(r)) for r in b2)[:60]), fill=(200, 230, 255))
            xx = x0 + 4
            for r in b2[:7]:
                t = thumb(im2, r[0])
                t2 = t.resize((t.size[0] * 2, t.size[1] * 2), Image.NEAREST)
                sheet.paste(t2, (xx, y0 + 26 + (64 - t2.size[1])), t2)
                xx += t2.size[0] + 6
                if xx > x0 + COLW - 60:
                    break
        sheet.save(os.path.join(OUT, "art3_sheet_%02d.png" % ((idx - 1) // PER + 1)))
        page = []
json.dump(catalog, open(os.path.join(OUT, "art_rows.json"), "w"), indent=0)
print("sheets:", len(names), "pages:", (len(names) + PER - 1) // PER)
