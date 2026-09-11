"""Naming catalog: every converted sprite's first Idle frame (and first Attack frame) at a readable size with its slug,
48 per page. usage: python art_catalog.py <staging dir> <manifest.json> <out prefix>"""
import json, os, sys
from PIL import Image, ImageDraw

STAGE, MAN, OUTP = sys.argv[1], sys.argv[2], sys.argv[3]
man = json.load(open(MAN))
items = [(k, v) for k, v in sorted(man.items()) if v.get("status") == "ok" and "robot" not in k]


def regions(atlas):
    regs, cur, png = {}, None, None
    for raw in open(atlas, encoding="utf-8"):
        s = raw.strip()
        if not s:
            continue
        if png is None:
            png = s
            continue
        if ":" not in s:
            cur = s
            continue
        if s.startswith("xy:") and cur:
            regs.setdefault(cur, []).append([int(v) for v in s[3:].split(",")])
        elif s.startswith("size:") and cur and cur in regs and len(regs[cur][-1]) == 2:
            regs[cur][-1] += [int(v) for v in s[5:].split(",")]
    return png, regs


CW, CH, COLS, PER = 175, 150, 8, 48
for p in range(0, len(items), PER):
    page = items[p:p + PER]
    sheet = Image.new("RGB", (CW * COLS, CH * ((len(page) + COLS - 1) // COLS)), (74, 122, 52))
    d = ImageDraw.Draw(sheet)
    for j, (slug, info) in enumerate(page):
        x0, y0 = (j % COLS) * CW, (j // COLS) * CH
        png, regs = regions(os.path.join(STAGE, slug, slug + ".atlas"))
        im = Image.open(os.path.join(STAGE, slug, png)).convert("RGBA")
        xx = x0 + 3
        for key in ("Idle", "Attack"):
            rr = regs.get(key)
            if not rr:
                continue
            r = rr[0] if key == "Idle" else rr[len(rr) // 2]
            fr = im.crop((r[0], r[1], r[0] + r[2], r[1] + r[3]))
            bb = fr.getchannel("A").getbbox()
            if bb:
                fr = fr.crop(bb)
            f = min(100 / max(1, fr.size[1]), 82 / max(1, fr.size[0]))
            fr = fr.resize((max(1, int(fr.size[0] * f)), max(1, int(fr.size[1] * f))), Image.NEAREST if max(fr.size) < 90 else Image.LANCZOS)
            sheet.paste(fr, (xx, y0 + 120 - fr.size[1]), fr)
            xx += fr.size[0] + 4
        d.text((x0 + 3, y0 + 124), slug[:27], fill=(255, 255, 0))
        d.rectangle([x0, y0, x0 + CW - 1, y0 + CH - 1], outline=(40, 80, 30))
    out = "%s_%02d.png" % (OUTP, p // PER + 1)
    sheet.save(out)
    print(out)
