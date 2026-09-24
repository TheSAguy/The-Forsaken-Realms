"""Round 303: sprite and autotile sources, and the small image tools the builder uses.

Sprite refs:  "cy:<n>"            cyanide-tilemix sprite n (cy_boxes.json, SEG_THR=140 cut)
              "rdx:<n>"           rDcmulG sprite n (rd_boxes.json, SEG_THR=200 gap 0 cut)
              "rd:c0,r0,c1,r1"    rDcmulG tiles c0..c1 x r0..r1 (32 px grid, inclusive), trimmed to what is visible
Round 309, the user's other sheets (SHEETS: key -> file, grid):
              "<key>:c,r"         one grid cell           "<key>:c0,r0,c1,r1"  a block of cells (inclusive)
              "<key>:c,r#k"       the k-th separate object in that cell, left to right
              "<key>@x0,y0,x1,y1" a pixel box
              soft drop shadows (semi-transparent near-black) are dropped, then the sprite is trimmed
Autotile refs: "a2:c_r"  the MV World A2 autotile (a2/c_r.png, XP at 32 px)
               "a1:name" an MV World A1 block (a1/name.png), its baked sand keyed out
               "old:<atlas>:<name>"  a current 16 px sheet region, enlarged 2x (kept as is)
"""
import colorsys
import json
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from paths import ART  # noqa: E402
_cy = Image.open(os.path.join(ART, "cyanide-tilemix-11-nature-2.png")).convert("RGBA")
_rd = Image.open(os.path.join(ART, "rDcmulG.png")).convert("RGBA")
_cy_boxes = json.load(open(os.path.join(HERE, "boxes", "cy_boxes.json")))
_rd_boxes = json.load(open(os.path.join(HERE, "boxes", "rd_boxes.json")))


def trim(img):
    bb = img.getchannel("A").point(lambda a: 255 if a >= 24 else 0).getbbox()
    return img.crop(bb) if bb else img


SHEETS = {"ob": ("Outside_B.png", 48), "wc": ("World_C.png", 48), "fo": ("foresta 3.png", 48),
          "bc": ("BCDE_Moderno_18.png", 48), "zb": ("ZRPGBeach.png", 32), "dt": ("deserttiles2.png", 48),
          "tn": ("73nunEG.png", 48), "cb": ("CaveUploadB.png", 48)}
_sheets = {}


def sheet(key):
    if key not in _sheets:
        _sheets[key] = Image.open(os.path.join(ART, SHEETS[key][0])).convert("RGBA")
    return _sheets[key]


def unshadow(img):
    """MV objects carry soft drop shadows; at a third of their size they would harden into dark blobs."""
    out = img.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if 0 < a < 220 and max(r, g, b) < 90:
                px[x, y] = (0, 0, 0, 0)
    return out


def parts(img, gap=2, thr=60, least=12):
    """The separate objects in a crop, left to right: 8-connected alpha components, boxes closer than gap merged
    (gap None: no merging)."""
    w, h = img.size
    a = img.getchannel("A").load()
    seen = bytearray(w * h)
    boxes = []
    for y in range(h):
        for x in range(w):
            if seen[y * w + x] or a[x, y] < thr:
                continue
            stack, seen[y * w + x] = [(x, y)], 1
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
                        if 0 <= nx < w and 0 <= ny < h and not seen[ny * w + nx] and a[nx, ny] >= thr:
                            seen[ny * w + nx] = 1
                            stack.append((nx, ny))
            boxes.append([x0, y0, x1 + 1, y1 + 1, n])
    merged = gap is not None   # gap None: connected pieces only, no merging (dense sheets, where boxes overlap)
    while merged:
        merged = False
        for i in range(len(boxes)):
            for j in range(i + 1, len(boxes)):
                p, q = boxes[i], boxes[j]
                if p[0] - gap <= q[2] and q[0] - gap <= p[2] and p[1] - gap <= q[3] and q[1] - gap <= p[3]:
                    boxes[i] = [min(p[0], q[0]), min(p[1], q[1]), max(p[2], q[2]), max(p[3], q[3]), p[4] + q[4]]
                    del boxes[j]
                    merged = True
                    break
            if merged:
                break
    return sorted([b[:4] for b in boxes if b[4] >= least], key=lambda b: b[0])


def sheet_sprite(ref):
    if "@" in ref:
        key, box = ref.split("@", 1)
        x0, y0, x1, y1 = [int(t) for t in box.split(",")]
        return trim(unshadow(sheet(key).crop((x0, y0, x1, y1))))
    key, arg = ref.split(":", 1)
    part = None
    if "#" in arg:
        arg, part = arg.split("#")
    v = [int(t) for t in arg.split(",")]
    c0, r0, c1, r1 = (v + v)[:4] if len(v) == 2 else v
    g = SHEETS[key][1]
    crop = unshadow(sheet(key).crop((c0 * g, r0 * g, (c1 + 1) * g, (r1 + 1) * g)))
    if part is not None:
        found = parts(crop)
        crop = crop.crop(tuple(found[int(part)]))
    return trim(crop)


def sprite(ref):
    if ref.split("@")[0].split(":")[0] in SHEETS:
        return sheet_sprite(ref)
    kind, arg = ref.split(":", 1)
    if kind == "cy":
        x0, y0, x1, y1 = _cy_boxes[int(arg)]
        return trim(_cy.crop((x0, y0, x1, y1)))
    if kind == "rdx":
        x0, y0, x1, y1 = _rd_boxes[int(arg)]
        return trim(_rd.crop((x0, y0, x1, y1)))
    if kind == "rd":
        c0, r0, c1, r1 = [int(t) for t in arg.split(",")]
        return trim(_rd.crop((c0 * 32, r0 * 32, (c1 + 1) * 32, (r1 + 1) * 32)))
    raise ValueError(ref)


def crisp(img, thr=100):
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            px[x, y] = (r, g, b, 255) if a >= thr else (0, 0, 0, 0)
    return img


def fit(img, max_w, max_h, upscale=False):
    """Scale to fit max_w x max_h (area average in premultiplied space, then crisp alpha)."""
    s = min(max_w / img.width, max_h / img.height)
    if s >= 1 and not upscale:
        return img.copy()
    w, h = max(1, round(img.width * s)), max(1, round(img.height * s))
    if s >= 1:
        return img.resize((w, h), Image.NEAREST)
    small = img.convert("RGBa").resize((w, h), Image.BOX).convert("RGBA")
    return crisp(small)


def tint(img, hue=0.0, sat=1.0, val=1.0, toward=None, amount=0.0):
    """HSV adjust every opaque pixel: hue shift (0..1 turns), saturation and value multipliers; optionally blend
    toward an RGB color by `amount`."""
    out = img.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            h = (h + hue) % 1.0
            s = max(0.0, min(1.0, s * sat))
            v = max(0.0, min(1.0, v * val))
            rr, gg, bb = colorsys.hsv_to_rgb(h, s, v)
            rr, gg, bb = rr * 255, gg * 255, bb * 255
            if toward is not None and amount:
                rr = rr + (toward[0] - rr) * amount
                gg = gg + (toward[1] - gg) * amount
                bb = bb + (toward[2] - bb) * amount
            px[x, y] = (int(rr), int(gg), int(bb), a)
    return out


def key_sand(img):
    """The A1 blocks were ripped over sand: (212-217, 186-193, 142-144) and its close neighbors -> transparent."""
    out = img.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a and 200 <= r <= 228 and 174 <= g <= 202 and 128 <= b <= 156 and r - b > 55:
                px[x, y] = (0, 0, 0, 0)
    return out


def autotile(ref):
    kind, arg = ref.split(":", 1)
    if kind == "a2":
        return Image.open(os.path.join(HERE, "autotiles", "a2", arg + ".png")).convert("RGBA")
    if kind == "a1":
        return key_sand(Image.open(os.path.join(HERE, "autotiles", "a1", arg + ".png")).convert("RGBA"))
    raise ValueError(ref)


def clear_row0(xp):
    """XP tiles (0,0) and (1,0) are never drawn (BiomeTexture's Empty pieces) - keep them empty."""
    out = xp.copy()
    for x in range(64):
        for y in range(32):
            out.putpixel((x, y), (0, 0, 0, 0))
    return out


def grid_autotile(spr, cell=32, box=28):
    """One object per tile, the same in every tile, so any quarter-tile assembly draws whole objects."""
    s = fit(spr, box, box)
    tile = Image.new("RGBA", (cell, cell), (0, 0, 0, 0))
    tile.alpha_composite(s, ((cell - s.width) // 2, cell - s.height - (cell - box) // 2))
    xp = Image.new("RGBA", (3 * cell, 4 * cell), (0, 0, 0, 0))
    for (tx, ty) in [(2, 0)] + [(x, y) for y in range(1, 4) for x in range(3)]:
        xp.alpha_composite(tile, (tx * cell, ty * cell))
    return xp
