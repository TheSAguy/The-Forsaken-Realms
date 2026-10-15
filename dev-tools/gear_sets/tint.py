"""Round 629: the gear sets' tint - an item icon recolored onto its set's color ramp, keeping the outline, the near-white
highlights and the accents (a gem, a glow) in a hue far from the icon's own (the user's "variant B", 2026-10-15).

Shared by make_gear_sets.py (the bonus cards' faces) and the set icons (round 631). Import only; no command line.
"""
import colorsys
import json
import os

from PIL import Image


def parse_atlas(path):
    """libGDX atlas -> {region name: {page, x, y, w, h}} (the first region of a name wins)."""
    regions, page, cur = {}, None, None
    for line in open(path, encoding='utf-8'):
        s = line.rstrip('\n')
        if s.endswith('.png') and ':' not in s:
            page = s.strip()
            continue
        if s and not s.startswith(' ') and ':' not in s:
            cur = s.strip()
            if cur in regions:
                cur = None  # a duplicate name: keep the first
            else:
                regions[cur] = {'page': page}
            continue
        if cur and s.strip().startswith('xy:'):
            x, y = [int(v) for v in s.split(':')[1].split(',')]
            regions[cur].update(x=x, y=y)
        if cur and s.strip().startswith('size:'):
            w, h = [int(v) for v in s.split(':')[1].split(',')]
            regions[cur].update(w=w, h=h)
    return regions


class Icons:
    """The plane's item icons, by item name."""

    def __init__(self, plane_dir):
        self.plane = plane_dir
        self.items = {i['name']: i for i in json.load(open(os.path.join(plane_dir, 'world', 'items.json'), encoding='utf-8'))}
        self.regions = parse_atlas(os.path.join(plane_dir, 'sprites', 'items.atlas'))
        self.pages = {}

    def icon(self, item_name):
        it = self.items.get(item_name)
        if not it:
            return None
        r = self.regions.get(it.get('iconName'))
        if not r or 'x' not in r:
            return None
        if r['page'] not in self.pages:
            self.pages[r['page']] = Image.open(os.path.join(self.plane, 'sprites', r['page'])).convert('RGBA')
        img = self.pages[r['page']].crop((r['x'], r['y'], r['x'] + r.get('w', 16), r['y'] + r.get('h', 16)))
        return img if img.size == (16, 16) else img.resize((16, 16), Image.LANCZOS)


def _lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def _dominant_hue(img):
    hues = {}
    for r, g, b, a in img.getdata():
        if a == 0:
            continue
        h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
        if s > 0.25 and 0.15 < l < 0.85:
            k = int(h * 12) % 12
            hues[k] = hues.get(k, 0) + 1
    return (max(hues, key=hues.get) + 0.5) / 12 if hues else None


def tint(img, ramp, keep_accents=True):
    """img recolored onto ramp = [dark, mid, light] (RGB lists): luminance kept, outline/highlights/accents kept."""
    dark, mid, light = [tuple(c) for c in ramp]
    dom = _dominant_hue(img) if keep_accents else None
    src = img.load()
    out = img.copy()
    dst = out.load()
    for y in range(img.size[1]):
        for x in range(img.size[0]):
            r, g, b, a = src[x, y]
            if a == 0:
                continue
            lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255
            sat = max(r, g, b) - min(r, g, b)
            if lum < 0.12 or (lum > 0.93 and sat < 20):
                continue
            if dom is not None:
                h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
                dist = min(abs(h - dom), 1 - abs(h - dom))
                if s > 0.45 and dist > 0.12 and 0.2 < l < 0.9:
                    continue
            t = max(0.0, min(1.0, (lum - 0.12) / 0.78))
            c = _lerp(dark, mid, t / 0.5) if t < 0.5 else _lerp(mid, light, (t - 0.5) / 0.5)
            dst[x, y] = c + (a,)
    return out
