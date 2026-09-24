"""review.py - before/after pictures of re-routed patrols on the real map art (round 329).

usage: python review.py --before <plane> --after <plane> --plan plan.json --out DIR [--maps rel ...] [--scale 3]
       python review.py --before <plane> --plan plan.json --out DIR --maps rel   (before only, to inspect)

Each picture: BEFORE on the left, AFTER on the right, cropped to the changed patrols, their loot and the enemies
beside them, plus three tiles. Art via round 319's review_sheet.render_art (tile layers + map_collision_render).
  loot       yellow square = chest, magenta = booster
  enemies    white ring = stands at its post (id beside it), gray ring = patrols, CYAN ring = a standing enemy
             within 3 tiles of a chest/booster (a guard)
  routes     RED = the old route (before), GREEN = the new one (after): thin dashed line from the post to the first
             stop, thick loop through the stops, dots on the stops; an X on a waypoint = it stands ON loot there
"""
import argparse
import json
import math
import os
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pmodel as P                                          # noqa: E402
from PIL import Image, ImageDraw, ImageFont                 # noqa: E402

RS = None
MCR = None


def font(size):
    for f in ("arial.ttf", "DejaVuSans.ttf", "segoeui.ttf"):
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            continue
    return ImageFont.load_default()


def route_points(mi, eid):
    r = mi.routes.get(eid)
    if r is None or not r.ids:
        return None, []
    return r.post, [r.pts[i] for i in r.ids]


def dashed(d, a, b, fill, width, dash=6):
    L = math.hypot(b[0] - a[0], b[1] - a[1])
    if L < 1:
        return
    n = int(L // dash)
    for k in range(0, n, 2):
        t0, t1 = k / float(n), min(1.0, (k + 1) / float(n))
        d.line([a[0] + (b[0] - a[0]) * t0, a[1] + (b[1] - a[1]) * t0,
                a[0] + (b[0] - a[0]) * t1, a[1] + (b[1] - a[1]) * t1], fill=fill, width=width)


def panel(mi, box, ids, color, S, title, art_cache):
    m = mi.m
    x0, y0, x1, y1 = box
    key = m.path
    if key not in art_cache:
        art, boxes = RS.render_art(m.path, MCR)
        over = Image.new("RGBA", art.size, (0, 0, 0, 0))
        dr = ImageDraw.Draw(over)
        for (bx, by, bw, bh) in boxes:
            if bw >= 1 and bh >= 1:
                dr.rectangle([bx, by, bx + bw - 1, by + bh - 1], fill=(255, 40, 40, 40))
        for c in m.collision_objects():
            dr.rectangle([c.x, c.y, c.x + c.w - 1, c.y + c.h - 1], outline=(255, 40, 40, 110))
        art.alpha_composite(over)
        art_cache[key] = art
    art = art_cache[key]
    img = art.crop((int(x0), int(y0), int(x1), int(y1))).resize((int(x1 - x0) * S, int(y1 - y0) * S), Image.NEAREST)
    d = ImageDraw.Draw(img)
    f = font(max(10, 4 * S))
    ctr = lambda x, y: ((x + 8 - x0) * S, (y - 8 - y0) * S)
    lootxy = []
    for o, tier in mi.loot_all:
        if tier == "other" or not P.spawns(o, "Hard"):
            continue
        cx, cy = ctr(o.x, o.y)
        lootxy.append((o.x, o.y))
        col = (255, 220, 40) if tier == "treasure" else (255, 60, 255)
        d.rectangle([cx - 5 * S, cy - 5 * S, cx + 5 * S, cy + 5 * S], outline=col, width=max(2, S))
    standing = {e.id for e in mi.standing_on("Hard")}
    for en in mi.enemies:
        if not P.spawns(en, "Hard"):
            continue
        if not (x0 - 16 <= en.x <= x1 and y0 <= en.y <= y1 + 16):
            continue
        cx, cy = ctr(en.x, en.y)
        guard = en.id in standing and any(math.hypot(en.x - lx, en.y - ly) <= 48 for lx, ly in lootxy)
        col = (255, 255, 255) if en.id in standing else (170, 170, 170)
        if guard:
            col = (0, 230, 255)
        if en.id in ids:
            col = color
        d.ellipse([cx - 6 * S, cy - 6 * S, cx + 6 * S, cy + 6 * S], outline=col, width=max(2, S))
        d.text((cx + 6 * S, cy - 8 * S), "#%d" % en.id, fill=col, font=f)
    for eid in ids:
        post, pts = route_points(mi, eid)
        if post is None:
            cx, cy = ctr(*mi.by_id[eid].tile_xy()) if hasattr(mi.by_id[eid], "tile_xy") else ctr(mi.by_id[eid].x, mi.by_id[eid].y)
            d.text((cx - 6 * S, cy + 7 * S), "stands", fill=color, font=f)
            continue
        hp = ctr(*post)
        wp = [ctr(*p) for p in pts]
        dashed(d, hp, wp[0], color, max(1, S // 2))
        if len(wp) > 1:
            d.line(wp + [wp[0]], fill=color, width=max(3, S + 1))
        for (wx, wy), p in zip(wp, pts):
            d.ellipse([wx - 3 * S, wy - 3 * S, wx + 3 * S, wy + 3 * S], fill=color)
            if any(math.hypot(p[0] - lx, p[1] - ly) <= 10 for lx, ly in lootxy):
                d.line([wx - 4 * S, wy - 4 * S, wx + 4 * S, wy + 4 * S], fill=(255, 0, 0), width=3)
                d.line([wx - 4 * S, wy + 4 * S, wx + 4 * S, wy - 4 * S], fill=(255, 0, 0), width=3)
    head = Image.new("RGB", (img.width, 8 * S), (0, 0, 0))
    ImageDraw.Draw(head).text((4, 2), title, fill=color, font=font(max(12, 5 * S)))
    out = Image.new("RGB", (img.width, head.height + img.height), (0, 0, 0))
    out.paste(head, (0, 0))
    out.paste(img.convert("RGB"), (0, head.height))
    return out


def main():
    global RS, MCR
    ap = argparse.ArgumentParser()
    ap.add_argument("--before", required=True)
    ap.add_argument("--after")
    ap.add_argument("--plan", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--maps", nargs="*")
    ap.add_argument("--scale", type=int, default=3)
    ap.add_argument("--max-px", type=int, default=1400, help="crop width/height cap in scaled px (per panel)")
    a = ap.parse_args()
    rootb, edata, dt = P.setup(a.before)
    sys.path.insert(0, os.path.join(dt, "guard_patrols"))
    sys.path.insert(0, dt)
    import review_sheet as rs
    import map_collision_render as mcr
    RS, MCR = rs, mcr
    roota = P.T.maps_root(a.after) if a.after else None
    plan = json.load(open(a.plan, encoding="utf-8"))
    os.makedirs(a.out, exist_ok=True)
    rels = a.maps or [r for r, e in plan["maps"].items() if e["actions"]]
    cache = {}
    S = a.scale
    for rel in rels:
        e = plan["maps"].get(rel)
        if not e:
            print("not in plan:", rel)
            continue
        ids = [x["enemy_id"] for x in e["actions"] if x["fix"] != "COVERED"]
        ids_all = [x["enemy_id"] for x in e["actions"]] + [x["enemy_id"] for x in e["left"]]
        mb = P.MapInfo(os.path.join(rootb, *rel.split("/")), rootb, edata)
        ma = P.MapInfo(os.path.join(roota, *rel.split("/")), roota, edata) if roota else None
        pts = []
        for mi in [x for x in (mb, ma) if x]:
            for eid in ids_all:
                post, rp = route_points(mi, eid)
                if post:
                    pts.append(post)
                    pts.extend(rp)
                elif eid in mi.by_id:
                    pts.append((mi.by_id[eid].x, mi.by_id[eid].y))
        if not pts:
            continue
        m = mb.m
        x0 = max(0, min(p[0] for p in pts) - 3 * 16)
        x1 = min(m.wpx, max(p[0] for p in pts) + 4 * 16)
        y0 = max(0, min(p[1] for p in pts) - 4 * 16)
        y1 = min(m.hpx, max(p[1] for p in pts) + 3 * 16)
        scale = S
        while (x1 - x0) * scale > a.max_px and scale > 1:
            scale -= 1
        box = (x0, y0, x1, y1)
        left = panel(mb, box, ids_all, (255, 70, 70), scale, "BEFORE  " + rel, cache)
        panels = [left]
        if ma:
            panels.append(panel(ma, box, ids_all, (60, 230, 90), scale, "AFTER", cache))
        W = sum(p.width for p in panels) + 8 * (len(panels) - 1)
        Hh = max(p.height for p in panels)
        lines = []
        for x in e["actions"]:
            if x["fix"] == "COVERED":
                lines.append("#%d %s: fixed by the shared waypoint move above" % (x["enemy_id"], x["enemy"]))
                continue
            lines.append("#%d %s: %s - %s" % (x["enemy_id"], x["enemy"], x["fix"], x["why"]))
        for x in e["left"]:
            lines.append("#%d %s: LEFT - %s" % (x["enemy_id"], x["enemy"], x["why"][:160]))
        fh = max(12, 4 * scale)
        foot = Image.new("RGB", (W, (fh + 4) * len(lines) + 6), (0, 0, 0))
        fd = ImageDraw.Draw(foot)
        for k, ln in enumerate(lines):
            fd.text((4, 3 + k * (fh + 4)), ln, fill=(230, 230, 230), font=font(fh))
        sheet = Image.new("RGB", (W, Hh + foot.height), (0, 0, 0))
        xx = 0
        for p in panels:
            sheet.paste(p, (xx, 0))
            xx += p.width + 8
        sheet.paste(foot, (0, Hh))
        name = rel.replace("/", "__").replace(".tmx", ".png")
        sheet.save(os.path.join(a.out, name))
        print("wrote", name, sheet.size)


if __name__ == "__main__":
    sys.exit(main())
