"""qa_regions.py - a labeled picture of every region of the new atlases, in the order the engine plays them.

One block per atlas: the Avatar, then every animation (IdleDown ... WalkUp, or Idle / Walk) as its frame sequence,
each frame in a cell outline on a checkerboard so the bottom alignment and any stray pixel show. One PNG per sheet
group (qa/regions_<group>.png).
usage: python tools/qa_regions.py [atlas dir] [out dir]"""
import collections, os, re, sys
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)
from roster316 import ROSTER, ART, RESKINS

ATL = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "atlases")
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "qa")
RANK = {"A": "Apprentice", "D": "Adept", "M": "Master", "X": "Archmage"}
INFO = {r[0]: "%s  %s  %s" % (r[1], r[3], RANK[r[2]]) for r in ROSTER}
for name, stem, sheet, row in RESKINS:
    INFO[stem] = "%s (RE-SKIN)  row %d" % (name, row)


def parse(path):
    t = open(path, encoding="utf-8").read()
    page = t.split("\n", 1)[0].strip()
    regs = collections.OrderedDict()
    for m in re.finditer(r"^(\w+)\n\s+xy: (\d+), (\d+)\n\s+size: (\d+), (\d+)", t, re.M):
        regs.setdefault(m.group(1), []).append(tuple(int(m.group(i)) for i in range(2, 6)))
    return page, regs


def checker(w, h, s=4):
    im = Image.new("RGBA", (w, h), (150, 150, 150, 255))
    d = ImageDraw.Draw(im)
    for y in range(0, h, s):
        for x in range(0, w, s):
            if (x // s + y // s) % 2:
                d.rectangle([x, y, x + s - 1, y + s - 1], fill=(120, 120, 120, 255))
    return im


def block(stem):
    page, regs = parse(os.path.join(ATL, stem + ".atlas"))
    img = Image.open(os.path.join(ATL, page)).convert("RGBA")
    cw, ch = next(v for k, v in regs.items() if k != "Avatar")[0][2:]
    sc = 2 if max(cw, ch) <= 60 else 1
    fw, fh = cw * sc, ch * sc
    anims = [k for k in regs if k != "Avatar"]
    # two columns of animations: Idle* left, Walk* right (front sheets: Idle / Walk)
    left = [a for a in anims if a.startswith("Idle")]
    right = [a for a in anims if a.startswith("Walk")]
    nrow = max(len(left), len(right))
    lab = 76
    colw = lab + 4 * (fw + 4)
    ax, ay, aw, ah = regs["Avatar"][0]
    avs = max(aw * sc, 48)
    W = 8 + colw * 2 + 8
    H = 22 + max(avs + 16, 0) + nrow * (fh + 18) + 8
    out = Image.new("RGBA", (W, H), (60, 60, 60, 255))
    d = ImageDraw.Draw(out)
    d.text((6, 4), "%s   [%s]  cell %dx%d  page %dx%d" % (INFO.get(stem, stem), stem, cw, ch, img.size[0], img.size[1]),
           fill=(255, 255, 0, 255))
    av = img.crop((ax, ay, ax + aw, ay + ah)).resize((aw * sc, ah * sc), Image.NEAREST)
    bg = checker(av.size[0], av.size[1])
    bg.alpha_composite(av)
    d.text((6, 26), "Avatar %dx%d" % (aw, ah), fill=(255, 255, 255, 255))
    out.paste(bg, (lab + 8, 22))
    y0 = 22 + avs + 16
    for col, names in enumerate((left, right)):
        for i, n in enumerate(names):
            y = y0 + i * (fh + 18)
            x = 8 + col * colw
            d.text((x, y + fh // 2), "%s x%d" % (n, len(regs[n])), fill=(255, 255, 255, 255))
            for k, (rx, ry, rw, rh) in enumerate(regs[n]):
                fr = img.crop((rx, ry, rx + rw, ry + rh)).resize((fw, fh), Image.NEAREST)
                cell = checker(fw, fh)
                cell.alpha_composite(fr)
                px = x + lab + k * (fw + 4)
                out.paste(cell, (px, y))
                d.rectangle([px - 1, y - 1, px + fw, y + fh], outline=(255, 80, 80, 255))
                d.text((px + 2, y + fh + 2), "%d@%d,%d" % (k, rx, ry), fill=(200, 200, 200, 255))
    return out


def sheet_of(stem):
    return os.path.basename(ART[stem][0]).replace("$", "").replace("!", "").replace(".png", "")


def main():
    groups = collections.OrderedDict()
    for stem in ART:
        groups.setdefault(sheet_of(stem), []).append(stem)
    os.makedirs(OUT, exist_ok=True)
    for g, stems in groups.items():
        blocks = [block(s) for s in stems]
        cols = 2 if blocks[0].size[0] < 900 else 1
        rows = (len(blocks) + cols - 1) // cols
        cw = max(b.size[0] for b in blocks)
        rh = [max(b.size[1] for b in blocks[r * cols:(r + 1) * cols]) for r in range(rows)]
        sheet = Image.new("RGBA", (cols * (cw + 6), sum(rh) + 6 * rows), (25, 25, 25, 255))
        y = 0
        for r in range(rows):
            for c in range(cols):
                i = r * cols + c
                if i < len(blocks):
                    sheet.paste(blocks[i], (c * (cw + 6), y))
            y += rh[r] + 6
        p = os.path.join(OUT, "regions_%s.png" % g)
        sheet.convert("RGB").save(p)
        print(p, sheet.size)


if __name__ == "__main__":
    main()
