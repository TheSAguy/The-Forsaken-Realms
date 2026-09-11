"""QA contact sheet for converted atlases: per sprite the Avatar, Idle 1st, Walk 1st + middle, Attack middle, Hit,
Death last - each cell drawn with its visible Idle size at 40 px (NEAREST, as the game draws), on grass.
usage: python art_preview.py <staging dir> <manifest.json> <out prefix> [per_page]"""
import json, os, sys
from PIL import Image, ImageDraw

STAGE, MAN, OUTP = sys.argv[1], sys.argv[2], sys.argv[3]
PER = int(sys.argv[4]) if len(sys.argv) > 4 else 36
man = json.load(open(MAN))


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
            xy = [int(v) for v in s[3:].split(",")]
            regs.setdefault(cur, []).append(xy)
        elif s.startswith("size:") and cur and cur in regs and len(regs[cur][-1]) == 2:
            regs[cur][-1] += [int(v) for v in s[5:].split(",")]
    return png, regs


COLW, ROWH, COLS = 460, 74, 3
items = [(k, v) for k, v in man.items() if v.get("status") == "ok"]
for p in range(0, len(items), PER):
    page = items[p:p + PER]
    sheet = Image.new("RGB", (COLW * COLS, ROWH * ((len(page) + COLS - 1) // COLS)), (58, 110, 38))
    d = ImageDraw.Draw(sheet)
    for j, (slug, info) in enumerate(page):
        x0, y0 = (j % COLS) * COLW, (j // COLS) * ROWH
        atlas = os.path.join(STAGE, slug, slug + ".atlas")
        png, regs = regions(atlas)
        im = Image.open(os.path.join(STAGE, slug, png)).convert("RGBA")
        vis = max(info["idle_visible"])
        f = 40.0 / vis
        picks = []
        for name, idx in (("Avatar", 0), ("Idle", 0), ("Walk", 0), ("Walk", "mid"), ("Attack", "mid"), ("Hit", 0), ("Death", -1)):
            rr = regs.get(name)
            if not rr:
                continue
            r = rr[len(rr) // 2] if idx == "mid" else rr[idx]
            fr = im.crop((r[0], r[1], r[0] + r[2], r[1] + r[3]))
            if name == "Avatar":
                fr = fr.resize((40, 40), Image.NEAREST)
            else:
                fr = fr.resize((max(1, round(r[2] * f)), max(1, round(r[3] * f))), Image.NEAREST)
            picks.append(fr)
        xx = x0 + 4
        for fr in picks:
            if xx + fr.size[0] > x0 + COLW - 4:
                break
            sheet.paste(fr, (xx, y0 + 12 + max(0, 58 - fr.size[1])), fr)
            xx += fr.size[0] + 4
        c = info["counts"]
        d.text((x0 + 4, y0 + 1), "%s  I%d W%d A%d H%d D%d %s" % (slug[3:][:30], c.get("Idle", 0), c.get("Walk", 0), c.get("Attack", 0),
                                                          c.get("Hit", 0), c.get("Death", 0), " ".join(info.get("notes", []))[:24]), fill=(255, 255, 0))
    out = "%s_%02d.png" % (OUTP, p // PER + 1)
    sheet.save(out)
    print(out, len(page))
