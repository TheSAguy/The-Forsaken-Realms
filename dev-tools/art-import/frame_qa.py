"""frame_qa.py - per-frame sanity check of converted enemy atlases (round 179): flags frames that hold a second large
figure (a sheet's preview render caught in a cell), frames far bigger or smaller than the sprite's own Idle/Walk median,
and near-empty frames. Pure PIL (no numpy): the alpha mask is boxed into 3-px blocks and flood-filled.
usage: python frame_qa.py <atlas dir> [--sheet out.png]   -> prints flagged frames; --sheet renders the flagged sprites"""
import os, re, statistics, sys
from PIL import Image, ImageDraw

BLOCK = 3


def regions(atlas):
    t = open(atlas, encoding="utf-8").read()
    page = t.split("\n", 1)[0].strip()
    out = []
    for m in re.finditer(r"^(\w+)\n\s+xy: (\d+), (\d+)\n\s+size: (\d+), (\d+)", t, re.M):
        out.append((m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5))))
    return page, out


def blobs(img):
    """Connected components of the alpha mask on a BLOCK-px grid: list of (area_px, x0, y0, x1, y1)."""
    a = img.getchannel("A")
    w, h = img.size
    gw, gh = (w + BLOCK - 1) // BLOCK, (h + BLOCK - 1) // BLOCK
    px = a.load()
    grid = [[0] * gw for _ in range(gh)]
    for gy in range(gh):
        for gx in range(gw):
            n = 0
            for y in range(gy * BLOCK, min(h, gy * BLOCK + BLOCK)):
                for x in range(gx * BLOCK, min(w, gx * BLOCK + BLOCK)):
                    if px[x, y] > 40:
                        n += 1
            grid[gy][gx] = n
    seen = [[False] * gw for _ in range(gh)]
    out = []
    for gy in range(gh):
        for gx in range(gw):
            if grid[gy][gx] and not seen[gy][gx]:
                stack = [(gx, gy)]
                seen[gy][gx] = True
                area, x0, y0, x1, y1 = 0, gx, gy, gx, gy
                while stack:
                    cx, cy = stack.pop()
                    area += grid[cy][cx]
                    x0, y0, x1, y1 = min(x0, cx), min(y0, cy), max(x1, cx), max(y1, cy)
                    for dx in (-1, 0, 1):
                        for dy in (-1, 0, 1):
                            nx, ny = cx + dx, cy + dy
                            if 0 <= nx < gw and 0 <= ny < gh and grid[ny][nx] and not seen[ny][nx]:
                                seen[ny][nx] = True
                                stack.append((nx, ny))
                out.append((area, x0 * BLOCK, y0 * BLOCK, (x1 + 1) * BLOCK, (y1 + 1) * BLOCK))
    out.sort(reverse=True)
    return out


def check(atlas):
    page, regs = regions(atlas)
    sheet = Image.open(os.path.join(os.path.dirname(atlas), page)).convert("RGBA")
    info = []
    for i, (name, x, y, w, h) in enumerate(regs):
        if name == "Avatar":
            continue
        fr = sheet.crop((x, y, x + w, y + h))
        bb = fr.getbbox()
        bl = blobs(fr)
        info.append((i, name, bb, bl))
    base = [(bb, bl) for i, n, bb, bl in info if n in ("Idle", "Walk") and bb]
    if not base:
        return [("no Idle/Walk", None)]
    mh = statistics.median(bb[3] - bb[1] for bb, _ in base)
    mw = statistics.median(bb[2] - bb[0] for bb, _ in base)
    ma = statistics.median(sum(b[0] for b in bl) for _, bl in base)
    flags = []
    for i, n, bb, bl in info:
        if not bb:
            flags.append(("%s#%d empty" % (n, i), i))
            continue
        tot = sum(b[0] for b in bl)
        big = [b for b in bl if b[0] >= 0.2 * bl[0][0] and b[0] >= 0.08 * tot]
        if n in ("Idle", "Walk", "Hit", "Death") and len(big) >= 2:
            a, b = big[0], big[1]
            gap_x = max(b[1] - a[3], a[1] - b[3])
            gap_y = max(b[2] - a[4], a[2] - b[4])
            if max(gap_x, gap_y) > 6:
                flags.append(("%s#%d two figures (%d/%d px, gap %d)" % (n, i, a[0], b[0], max(gap_x, gap_y)), i))
        hh, ww = bb[3] - bb[1], bb[2] - bb[0]
        if n in ("Idle", "Walk") and (hh > 1.35 * mh or ww > 1.6 * mw):
            flags.append(("%s#%d oversize %dx%d vs median %dx%d" % (n, i, ww, hh, mw, mh), i))
        if n in ("Idle", "Walk") and tot < 0.3 * ma:
            flags.append(("%s#%d near-empty (%d vs %d px)" % (n, i, tot, ma), i))
    return flags


def main():
    d = sys.argv[1]
    flagged = []
    for f in sorted(os.listdir(d)):
        if f.endswith(".atlas"):
            fl = check(os.path.join(d, f))
            if fl:
                flagged.append((f, fl))
                print("%-40s %s" % (f, "; ".join(x for x, _ in fl)))
    print("flagged %d atlases" % len(flagged))
    if "--sheet" in sys.argv:
        out = sys.argv[sys.argv.index("--sheet") + 1]
        rows = []
        for f, fl in flagged:
            page, regs = regions(os.path.join(d, f))
            sheet = Image.open(os.path.join(d, page)).convert("RGBA")
            idx = sorted(set(i for _, i in fl if i is not None))
            frames = []
            for i in [k for k, r in enumerate(regs) if r[0] in ("Idle", "Walk")][:8] + idx:
                n, x, y, w, h = regs[i]
                fr = sheet.crop((x, y, x + w, y + h))
                s = 96 / max(w, h)
                fr = fr.resize((max(1, int(w * s)), max(1, int(h * s))))
                tile = Image.new("RGBA", (100, 112), (70, 70, 70, 255) if i not in idx else (120, 50, 50, 255))
                tile.alpha_composite(fr, (2, 2))
                ImageDraw.Draw(tile).text((2, 100), "%s%d" % (n[0], i), fill=(255, 255, 255, 255))
                frames.append(tile)
            row = Image.new("RGBA", (220 + 100 * len(frames), 112), (30, 30, 30, 255))
            ImageDraw.Draw(row).text((4, 50), f[:-6][:34], fill=(255, 255, 255, 255))
            for k, t in enumerate(frames):
                row.alpha_composite(t, (220 + 100 * k, 0))
            rows.append(row)
        if rows:
            W = max(r.size[0] for r in rows)
            img = Image.new("RGBA", (W, 114 * len(rows)), (20, 20, 20, 255))
            for k, r in enumerate(rows):
                img.alpha_composite(r, (0, 114 * k))
            img.convert("RGB").save(out)
            print("sheet ->", out)


if __name__ == "__main__":
    main()
