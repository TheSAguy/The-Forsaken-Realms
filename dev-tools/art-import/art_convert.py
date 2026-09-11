"""art_convert.py - turn the user's Ragnarok Online sprite sheets (The Spriters Resource layout) into TFR enemy
atlases, in a staging folder outside the repo.

The TSR layout: animation rows labeled with text at the side ("Standing", "Walking", "Attacking", "Hurt & Dying",
"Standing & Walking", "Death Parts"), each row holding the FRONT view (facing down-left) and then the BACK view; a
two-row group (bracketed label between the rows) is front row + back row. Extras sit in a boxed area.

What this does per sheet:
  1. connected components of the alpha mask (run-length union-find, pure PIL), merged when closer than 3 px;
  2. drops label text / brackets (dark-dominant components) and box borders (sparse components) and everything
     inside a box; the rest are sprite frames, clustered into rows;
  3. groups rows by their nearest label block, reads the group order as Standing/Walking/Attacking/Hurt&Dying,
     keeps the FRONT view only, flips it to face right (the game's default direction);
  4. packs Avatar / Idle / Walk / Attack / Hit / Death into one uniform-cell atlas (bottom-center aligned) and writes
     <slug>.png + <slug>.atlas + a manifest row (visible Idle size, the Adept scale 16/visible, notes).

usage: python art_convert.py <art dir> <out dir> [--only substring] [--preview]
"""
import json, os, re, statistics, sys
from PIL import Image, ImageDraw

ART, OUT = sys.argv[1], sys.argv[2]
ONLY = sys.argv[sys.argv.index("--only") + 1].lower() if "--only" in sys.argv else None
RO_PREFIX = "PC _ Computer - Ragnarok Online - "


# ------------------------------------------------------------------ components
def components(alpha, thr=24, join=2):
    W, H = alpha.size
    tbl = bytes(0 if v < thr else 1 for v in range(256))
    data = alpha.tobytes().translate(tbl)
    parent, boxes = [], []

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    pat = re.compile(b"\x01+")
    prev = []
    for y in range(H):
        row = data[y * W:(y + 1) * W]
        runs = []
        for m in pat.finditer(row):
            if runs and m.start() - runs[-1][1] <= join:
                runs[-1] = (runs[-1][0], m.end())
            else:
                runs.append((m.start(), m.end()))
        cur, k = [], 0
        for (x0, x1) in runs:
            lab = len(parent)
            parent.append(lab)
            boxes.append([x0, y, x1, y + 1, x1 - x0])
            while k < len(prev) and prev[k][1] + join < x0:
                k += 1
            kk = k
            while kk < len(prev) and prev[kk][0] - join < x1:
                ra, rb = find(prev[kk][2]), find(lab)
                if ra != rb:
                    parent[rb] = ra
                kk += 1
            cur.append((x0, x1, lab))
        prev = cur
    out = {}
    for lab, b in enumerate(boxes):
        r = find(lab)
        o = out.get(r)
        if o is None:
            out[r] = list(b)
        else:
            o[0] = min(o[0], b[0]); o[1] = min(o[1], b[1]); o[2] = max(o[2], b[2]); o[3] = max(o[3], b[3]); o[4] += b[4]
    return list(out.values())


def near(a, b, dx, dy):
    return a[0] - dx < b[2] and b[0] - dx < a[2] and a[1] - dy < b[3] and b[1] - dy < a[3]


def merge_close(cs, dx, dy, pred=lambda a, b: True):
    cs = [list(c) for c in cs]
    changed = True
    while changed:
        changed = False
        cs.sort(key=lambda c: c[0])
        out = []
        for c in cs:
            hit = None
            for o in out:
                if near(o, c, dx, dy) and pred(o, c):
                    hit = o
                    break
            if hit:
                hit[0] = min(hit[0], c[0]); hit[1] = min(hit[1], c[1]); hit[2] = max(hit[2], c[2]); hit[3] = max(hit[3], c[3])
                hit[4] += c[4]
                changed = True
            else:
                out.append(c)
        cs = out
    return cs


def dark_ratio(rgba, box):
    crop = rgba.crop(tuple(box[:4]))
    n = d = 0
    for (r, g, b, a) in crop.get_flattened_data():
        if a >= 24:
            n += 1
            if max(r, g, b) < 90:
                d += 1
    return d / max(1, n)


def col_runs(rgba, box):
    """Separate ink columns in a blob: a word's letters leave gaps, a bone or a shadow does not."""
    crop = rgba.crop(tuple(box[:4])).getchannel("A")
    w, h = crop.size
    px = crop.load()
    n, inside = 0, False
    for x in range(w):
        on = any(px[x, y] >= 24 for y in range(h))
        if on and not inside:
            n += 1
        inside = on
    return n


def ink_lines(rgba, box):
    crop = rgba.crop(tuple(box[:4])).getchannel("A")
    w, h = crop.size
    px = crop.load()
    on = [any(px[x, y] >= 24 for x in range(w)) for y in range(h)]
    lines, inside, gap = 0, False, 0
    for f in on:
        if f:
            if not inside:
                lines += 1
                inside = True
            gap = 0
        else:
            gap += 1
            if gap >= 2:
                inside = False
    return lines


# ------------------------------------------------------------------ one sheet
def key_background(rgba):
    """Some rips sit on an opaque ground: a solid sheet color (read from the corners) or black panels behind each
    animation block. Pixel art, so an exact-color key is safe; near-black (every channel <= 2) goes when it covers
    at least a tenth of the sheet - RO sprites outline in dark colors, never true black."""
    W, H = rgba.size
    keys = set()
    for p in [(0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1)]:
        px = rgba.getpixel(p)
        if px[3] >= 24:
            keys.add(px[:3])
    data = list(rgba.get_flattened_data())
    black = sum(1 for (r, g, b, a) in data if a >= 24 and r <= 2 and g <= 2 and b <= 2)
    if not keys and black < 0.1 * W * H:
        return rgba
    out = [(0, 0, 0, 0) if (a >= 24 and ((r, g, b) in keys or (black >= 0.1 * W * H and r <= 2 and g <= 2 and b <= 2)))
           else (r, g, b, a) for (r, g, b, a) in data]
    im = Image.new("RGBA", (W, H))
    im.putdata(out)
    return im



def analyse(path):
    """-> rgba, rows, labels, separators, error. Labels are text blocks [x0,y0,x1,y1,n,lines,span]; separators are
    the y of long horizontal rules (some rippers divide the animation groups with lines instead of brackets).
    Classified on the RAW components, before any merging, so a two-line label never fuses into a 'frame'."""
    rgba = key_background(Image.open(path).convert("RGBA"))
    W, H = rgba.size
    raw = [c for c in components(rgba.getchannel("A")) if (c[2] - c[0]) * (c[3] - c[1]) >= 4]
    seps, strokes, boxes, rest = [], [], [], []
    for c in raw:
        w, h = c[2] - c[0], c[3] - c[1]
        fill = c[4] / max(1, w * h)
        if h <= 3 and w >= 0.5 * W:
            seps.append((c[1] + c[3]) / 2)
        elif max(w, h) > 100 and fill < 0.04:
            boxes.append(c)                                 # a frame drawn around extras ("not used", "Dead")
        elif min(w, h) <= 3 and max(w, h) >= 10:
            strokes.append(c)                               # thin brackets and dashes beside labels
        elif 16 < h <= 60 and (fill <= 0.5 or min(w, h) <= 8) and dark_ratio(rgba, c) >= 0.85:
            strokes.append(c)                               # thick bracket corners
        else:
            rest.append(c)

    def inside_box(c):
        cx, cy = (c[0] + c[2]) / 2, (c[1] + c[3]) / 2
        return any(b[0] <= cx <= b[2] and b[1] <= cy <= b[3] for b in boxes)

    rest = [c for c in rest if not inside_box(c)]
    bigs = [c for c in rest if (c[3] - c[1]) > 16]
    pieces = [c for c in rest if (c[3] - c[1]) <= 16 and not any(near(c, b, 3, 3) for b in bigs)]
    sprite = [c for c in rest if c not in pieces]
    # text: pieces within 6 px form lines, lines within 8 px form a block; a real label has letter gaps
    lines_ = merge_close(pieces, 6, 1)
    blocks = merge_close(lines_, 30, 8)
    labels, text_pieces = [], set()
    for bl in blocks:
        if (bl[2] - bl[0]) >= 15 and col_runs(rgba, bl) >= 3:
            inner = [ln for ln in lines_ if near(ln, bl, 0, 0)]
            if all((ln[3] - ln[1]) <= 18 for ln in inner):
                l = list(bl[:5]) + [len(merge_close(inner, 1000, 1)), [bl[1], bl[3]]]
                for s in strokes:
                    if s[0] < l[2] + 12 and l[0] - 12 < s[2] and (abs(s[3] - l[1]) <= 40 or abs(s[1] - l[3]) <= 40):
                        l[6] = [min(l[6][0], s[1]), max(l[6][1], s[3])]
                labels.append(l)
                text_pieces.update(id(p) for p in pieces if near(p, bl, 0, 0))
    sprite += [p for p in pieces if id(p) not in text_pieces]       # sparks, dropped bones: sprite debris
    blobs = merge_close(sprite, 4, 4)
    if not blobs:
        return rgba, [], labels, [], "no frames"
    hs = sorted(b[3] - b[1] for b in blobs if b[4] > 60)
    typical = hs[int(len(hs) * 0.75)] if hs else 0
    frames = [b for b in blobs if (b[3] - b[1]) >= 0.3 * typical and b[4] > 40]
    frames.sort(key=lambda c: (c[1] + c[3]) / 2)
    rows = []
    for c in frames:
        cy = (c[1] + c[3]) / 2
        for r in rows:
            if r["y0"] <= cy <= r["y1"]:
                r["f"].append(c)
                r["y0"] = min(r["y0"], c[1]); r["y1"] = max(r["y1"], c[3])
                break
        else:
            rows.append({"y0": c[1], "y1": c[3], "f": [c]})
    rows.sort(key=lambda r: r["y0"])
    for r in rows:
        r["f"].sort(key=lambda c: c[0])
        r["h"] = statistics.median(c[3] - c[1] for c in r["f"])
    big = max(r["h"] for r in rows) if rows else 0
    rows = [r for r in rows if r["h"] >= 0.45 * big]       # Death Parts / debris rows
    return rgba, rows, labels, sorted(seps), ""


def group_rows(rows, labels, seps, sheet_h):
    """Returns [(label or None, [rows])] top to bottom.
    - Two or more separator rules: a group is every row between two rules.
    - Otherwise rows PAIR: a TSR group is either one row (front half + back half) or two rows with the same frame
      count (front row + back row). Two neighbors with equal counts pair unless each carries its own label text.
    A single rule low on the sheet fences off "Extra sprites (not used)": rows below it are dropped."""
    if len(seps) == 1 and seps[0] > 0.6 * sheet_h:
        rows = [r for r in rows if (r["y0"] + r["y1"]) / 2 < seps[0]]
        seps = []

    def label_for(rs):
        y0, y1 = rs[0]["y0"], rs[-1]["y1"] + 20
        best, bd = None, None
        for l in labels:
            cy = (l[1] + l[3]) / 2
            if y0 <= cy <= y1:
                d = abs(cy - (rs[0]["y0"] + rs[-1]["y1"]) / 2)
                if bd is None or d < bd:
                    best, bd = l, d
        return best

    if len(seps) >= 2:
        bounds = [-1] + list(seps) + [10 ** 9]
        groups = []
        for i in range(len(bounds) - 1):
            rs = [r for r in rows if bounds[i] < (r["y0"] + r["y1"]) / 2 < bounds[i + 1]]
            if rs:
                groups.append((label_for(rs), rs))
        return groups

    def has_text(r):
        return any(min(r["y1"], l[3]) - max(r["y0"], l[1]) > 0 for l in labels)

    groups, i = [], 0
    while i < len(rows):
        r = rows[i]
        if i + 1 < len(rows) and len(rows[i + 1]["f"]) == len(r["f"]) and not (has_text(r) and has_text(rows[i + 1])):
            rs = [r, rows[i + 1]]
            i += 2
        else:
            rs = [r]
            i += 1
        groups.append((label_for(rs), rs))
    return groups


def front(rs):
    if len(rs) >= 2:
        return rs[0]["f"]
    f = rs[0]["f"]
    return f[:max(1, (len(f) + 1) // 2)]


def wide(box):
    return (box[2] - box[0]) > 1.15 * (box[3] - box[1])


def dying(rs):
    """Hit and Death from a "Hurt & Dying" group. Death runs from the second frame to the first lying (wide) frame
    of the front view; a front view with no lying frame borrows the row's first lying frame (some rippers put both
    hurt views first and both dead views after)."""
    fr = front(rs)
    row = rs[0]["f"]
    rh = rs[0]["h"]
    lying = [f for f in row if wide(f) and (f[3] - f[1]) >= 0.35 * rh]
    in_front = [i for i, f in enumerate(fr) if f in lying]
    if in_front:
        return fr[:1], fr[1:in_front[0] + 1]
    if lying:
        return fr[:1], fr[:1] + lying[:1]
    return fr[:1], fr[1:] or fr[:1]


def interpret(groups):
    """Group order on a TSR sheet: Standing, Walking (or one "Standing & Walking" group), Attacking, then either one
    "Hurt & Dying" group or a "Hurt" group and a "Dying" group. Front view only."""
    notes = []
    if not groups:
        return None, ["no rows"]
    g = list(groups)
    anim = {}
    first_label, first_rows = g[0]
    if first_label is not None and first_label[5] >= 2 and len(first_rows) >= 2:
        walk = first_rows[0]["f"]
        anim["Idle"] = walk[:1]
        anim["Walk"] = walk
        rest = g[1:]
        notes.append("standing&walking")
    else:
        anim["Idle"] = front(first_rows)
        if len(g) >= 2:
            anim["Walk"] = front(g[1][1])
            rest = g[2:]
        else:
            anim["Walk"] = anim["Idle"]
            rest = []
            notes.append("no walk row")
    if rest:
        anim["Attack"] = front(rest[0][1])
        rest = rest[1:]
    else:
        notes.append("no attack row")
    if len(rest) >= 2 and len(front(rest[0][1])) <= 2 and not any(wide(f) for f in rest[0][1][0]["f"]):
        anim["Hit"] = front(rest[0][1])
        anim["Death"] = dying(rest[1][1])[1]
        notes.append("hurt+dying")
    elif rest:
        anim["Hit"], anim["Death"] = dying(rest[0][1])
    else:
        notes.append("no hurt/dying row")
    return anim, notes


# ------------------------------------------------------------------ atlas
def crop_frame(rgba, box, flip=True):
    fr = rgba.crop(tuple(box[:4]))
    bb = fr.getchannel("A").point(lambda v: 255 if v >= 24 else 0).getbbox()
    if bb:
        fr = fr.crop(bb)
    return fr.transpose(Image.FLIP_LEFT_RIGHT) if flip else fr


def build_atlas(rgba, anim, slug, outdir, flip=True):
    frames = {k: [crop_frame(rgba, b, flip) for b in v] for k, v in anim.items() if v}
    base = frames["Idle"] + frames.get("Walk", [])
    cw = max(f.size[0] for f in base) + 2
    ch = max(f.size[1] for f in base) + 2
    order = [k for k in ("Idle", "Walk", "Attack", "Hit", "Death") if k in frames]
    ncol = max(len(frames[k]) for k in order)
    # square avatar from the first Idle frame: the top square of the visible box, centered
    idle0 = frames["Idle"][0]
    iw, ih = idle0.size
    side = min(iw, ih)
    ax = (iw - side) // 2
    av = idle0.crop((ax, 0, ax + side, side))
    sheet = Image.new("RGBA", (max(ncol * cw, side), side + len(order) * ch), (0, 0, 0, 0))
    sheet.paste(av, (0, 0))
    lines = [slug + ".png", "size: %d,%d" % sheet.size, "format: RGBA8888", "filter: Nearest,Nearest", "repeat: none",
             "Avatar", "  xy: 0, 0", "  size: %d, %d" % (side, side)]
    for ri, k in enumerate(order):
        y = side + ri * ch
        for ci, f in enumerate(frames[k]):
            if f.size[0] > cw or f.size[1] > ch:          # attack slashes / lying bodies: shrink to the cell
                r = min(cw / f.size[0], ch / f.size[1])
                f = f.resize((max(1, int(f.size[0] * r)), max(1, int(f.size[1] * r))), Image.LANCZOS)
            x = ci * cw + (cw - f.size[0]) // 2
            sheet.paste(f, (x, y + ch - f.size[1] - 1))
            lines += [k, "  xy: %d, %d" % (ci * cw, y), "  size: %d, %d" % (cw, ch)]
    os.makedirs(outdir, exist_ok=True)
    sheet.save(os.path.join(outdir, slug + ".png"))
    with open(os.path.join(outdir, slug + ".atlas"), "w", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    vis = max(idle0.size)
    return {"cell": [cw, ch], "idle_visible": list(idle0.size), "counts": {k: len(frames[k]) for k in order},
            "adept_scale": round(16.0 / vis, 4)}


def slug_of(name):
    s = name[len(RO_PREFIX):] if name.startswith(RO_PREFIX) else name
    s = re.sub(r"\.png$", "", s, flags=re.I)
    s = s.replace("Enemies - ", "").replace("Homunculus - ", "homunculus ").replace("Effects ", "effects ")
    s = re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")
    return "ro_" + s if name.startswith(RO_PREFIX) else s


def main():
    names = sorted(n for n in os.listdir(ART) if n.lower().endswith(".png") and n.startswith(RO_PREFIX))
    if ONLY:
        names = [n for n in names if ONLY in n.lower()]
    manifest = {}
    for n in names:
        slug = slug_of(n)
        try:
            rgba, rows, labels, seps, err = analyse(os.path.join(ART, n))
            if err:
                manifest[slug] = {"source": n, "status": "fail", "notes": [err]}
                print("%-40s FAIL %s" % (slug, err))
                continue
            groups = group_rows(rows, labels, seps, rgba.size[1])
            anim, notes = interpret(groups)
            info = build_atlas(rgba, anim, slug, os.path.join(OUT, slug))
            info.update({"source": n, "status": "ok", "notes": notes,
                         "groups": [[(g[0][5] if g[0] else 0), [len(r["f"]) for r in g[1]]] for g in groups]})
            manifest[slug] = info
            print("%-34s rows %-28s L%d S%d  %s  %s" % (slug, [len(r["f"]) for r in rows], len(labels), len(seps), info["counts"], " ".join(notes)))
        except Exception as ex:
            manifest[slug] = {"source": n, "status": "fail", "notes": [repr(ex)]}
            print("%-40s ERROR %r" % (slug, ex))
    mp = os.path.join(OUT, "manifest_ro.json")
    old = json.load(open(mp)) if (ONLY and os.path.exists(mp)) else {}
    old.update(manifest)
    json.dump(old, open(mp, "w"), indent=1)
    print("converted", sum(1 for m in manifest.values() if m["status"] == "ok"), "of", len(manifest))


if __name__ == "__main__":
    main()
