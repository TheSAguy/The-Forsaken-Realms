"""review_sheet.py - one picture per planned map: the real map art, the plan drawn on top (round 316).

usage: python review_sheet.py <plane folder> --plan plan.json --out review/ [--dev-tools DIR] [--scale 3]

Drawn over the map (art via dev-tools/map_collision_render.Tileset, collision boxes faint red):
  enemies     white ring = standing, gray ring = already patrols, id beside it
  planned     ORANGE = patrol (route drawn thick, waypoints as dots, first walk from the post dashed-thin)
              RED X  = removed
              PURPLE = optional relocation (arrow from its post to the new one)
  loot        yellow square = chest, magenta = booster
Crops to the plan's actors plus three tiles.
"""
import argparse
import json
import math
import os
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tfrmaps as T                                              # noqa: E402
from PIL import Image, ImageDraw                                 # noqa: E402


def render_art(tmx, mcr):
    import xml.etree.ElementTree as ET
    root = ET.parse(tmx).getroot()
    W, H = int(root.get("width")), int(root.get("height"))
    tw, th = int(root.get("tilewidth")), int(root.get("tileheight"))
    base = os.path.dirname(os.path.abspath(tmx))
    tilesets = []
    for ts in root.findall("tileset"):
        if ts.get("source"):
            p = os.path.normpath(os.path.join(base, ts.get("source")))
            if os.path.exists(p):
                tilesets.append((int(ts.get("firstgid")), mcr.Tileset(p)))
    tilesets.sort(key=lambda t: t[0])
    canvas = Image.new("RGBA", (W * tw, H * th), (20, 20, 28, 255))
    boxes = []
    for layer in root.findall(".//layer"):
        if layer.get("visible") == "0":
            continue
        for idx, raw in enumerate(mcr.decode_layer(layer)):
            if not raw:
                continue
            gid = raw & mcr.GID_MASK
            own = None
            for first, ts in tilesets:
                if gid >= first:
                    own = (first, ts)
            if not own:
                continue
            first, ts = own
            cx, cy = (idx % W) * tw, (idx // W) * th
            tile = ts.tile_image(gid - first)
            if tile is not None:
                if raw & mcr.H_FLIP:
                    tile = tile.transpose(Image.FLIP_LEFT_RIGHT)
                if raw & mcr.V_FLIP:
                    tile = tile.transpose(Image.FLIP_TOP_BOTTOM)
                if raw & mcr.D_FLIP:
                    tile = tile.transpose(Image.TRANSPOSE)
                if tile.size == (tw, th):
                    canvas.alpha_composite(tile, (cx, cy))
                else:
                    canvas.alpha_composite(tile, (cx, cy + th - tile.size[1]))
            for (ox, oy, ow, oh) in ts.rects.get(gid - first, []):
                boxes.append((cx + ox, cy + oy, ow, oh))
    return canvas, boxes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("plane")
    ap.add_argument("--plan", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--dev-tools")
    ap.add_argument("--scale", type=int, default=3)
    a = ap.parse_args()
    root = T.maps_root(a.plane)
    dt = T.find_dev_tools(root, a.dev_tools)
    T.load_tools(dt)
    import map_collision_render as mcr
    plan = json.load(open(a.plan, encoding="utf-8"))
    os.makedirs(a.out, exist_ok=True)
    S = a.scale
    n = 0
    for rel, e in plan["maps"].items():
        if not (e["patrols"] or e["removals"] or e["relocations"]):
            continue
        m = T.TMap(os.path.join(root, *rel.split("/")), root)
        pts = []
        for p in e["patrols"]:
            pts.append(tuple(p["home"]))
            pts += [(w["x"], w["y"]) for w in p["waypoints"]]
            for q in p.get("partners", []):
                o = m.by_id.get(q)
                if o is not None:
                    pts.append((o.x, o.y))
            if p.get("chest") and m.by_id.get(p["chest"]) is not None:
                c = m.by_id[p["chest"]]
                pts.append((c.x, c.y))
        for r in e["removals"]:
            pts.append(tuple(r["home"]))
        for r in e["relocations"]:
            pts.append(tuple(r["from"]))
            pts.append(tuple(r["to"]))
        x0 = max(0, min(p[0] for p in pts) - 3 * 16)
        x1 = min(m.wpx, max(p[0] for p in pts) + 4 * 16)
        y0 = max(0, min(p[1] for p in pts) - 4 * 16)
        y1 = min(m.hpx, max(p[1] for p in pts) + 3 * 16)
        art, boxes = render_art(m.path, mcr)
        over = Image.new("RGBA", art.size, (0, 0, 0, 0))
        dr = ImageDraw.Draw(over)
        for (bx, by, bw, bh) in boxes:
            if bw >= 1 and bh >= 1:
                dr.rectangle([bx, by, bx + bw - 1, by + bh - 1], fill=(255, 40, 40, 45))
        art.alpha_composite(over)
        img = art.crop((int(x0), int(y0), int(x1), int(y1))).resize(
            (int(x1 - x0) * S, int(y1 - y0) * S), Image.NEAREST)
        d = ImageDraw.Draw(img)
        ctr = lambda x, y: ((x + 8 - x0) * S, (y - 8 - y0) * S)        # a tile object's center, scaled
        for o, tier in m.loot():
            if tier == "other":
                continue
            cx, cy = ctr(o.x, o.y)
            col = (255, 220, 40) if tier == "treasure" else (255, 60, 255)
            d.rectangle([cx - 4 * S, cy - 4 * S, cx + 4 * S, cy + 4 * S], outline=col, width=2)
        removed = {r["enemy_id"] for r in e["removals"]}
        patrolled = {p["enemy_id"] for p in e["patrols"]}
        moved = {r["enemy_id"]: r for r in e["relocations"]}
        for en in m.enemies():
            cx, cy = ctr(en.x, en.y)
            if not (x0 - 16 <= en.x <= x1 and y0 <= en.y <= y1 + 16):
                continue
            walks = bool((en.props.get("waypoints") or "").strip())
            col = (255, 140, 0) if en.id in patrolled else (160, 160, 160) if walks else (255, 255, 255)
            d.ellipse([cx - 6 * S, cy - 6 * S, cx + 6 * S, cy + 6 * S], outline=col, width=2)
            d.text((cx + 6 * S, cy - 7 * S), "#%d" % en.id, fill=col)
            if en.id in removed:
                d.line([cx - 6 * S, cy - 6 * S, cx + 6 * S, cy + 6 * S], fill=(255, 30, 30), width=4)
                d.line([cx - 6 * S, cy + 6 * S, cx + 6 * S, cy - 6 * S], fill=(255, 30, 30), width=4)
            if en.id in moved:
                tx, ty = ctr(*moved[en.id]["to"])
                d.line([cx, cy, tx, ty], fill=(190, 80, 255), width=3)
                d.ellipse([tx - 6 * S, ty - 6 * S, tx + 6 * S, ty + 6 * S], outline=(190, 80, 255), width=3)
        for p in e["patrols"]:
            hx, hy = ctr(*p["home"])
            wp = [ctr(w["x"], w["y"]) for w in p["waypoints"]]
            d.line([hx, hy, wp[0][0], wp[0][1]], fill=(255, 140, 0), width=1)
            d.line(wp + [wp[0]], fill=(255, 140, 0), width=4)
            for (wx, wy) in wp:
                d.ellipse([wx - 3 * S, wy - 3 * S, wx + 3 * S, wy + 3 * S], fill=(255, 140, 0))
        head = Image.new("RGB", (max(img.width, 520), 18 + 14 * (len(e["patrols"]) + len(e["removals"])
                                                                  + len(e["relocations"]))), (0, 0, 0))
        hd = ImageDraw.Draw(head)
        hd.text((4, 2), rel, fill=(255, 255, 255))
        yy = 16
        for p in e["patrols"]:
            hd.text((4, yy), "PATROL %s #%d: %s" % (p["enemy"], p["enemy_id"], p["purpose"]), fill=(255, 140, 0))
            yy += 14
        for r in e["removals"]:
            hd.text((4, yy), "REMOVE %s #%d" % (r["enemy"], r["enemy_id"]), fill=(255, 60, 60))
            yy += 14
        for r in e["relocations"]:
            hd.text((4, yy), "(optional) MOVE %s #%d beside its %s %d" % (r["enemy"], r["enemy_id"], r["loot_kind"],
                                                                          r["loot"]), fill=(190, 80, 255))
            yy += 14
        sheet = Image.new("RGB", (max(img.width, head.width), head.height + img.height), (0, 0, 0))
        sheet.paste(head, (0, 0))
        sheet.paste(img.convert("RGB"), (0, head.height))
        name = rel.replace("/", "__").replace(".tmx", ".png")
        sheet.save(os.path.join(a.out, name))
        n += 1
    print("%d review image(s) in %s" % (n, a.out))


if __name__ == "__main__":
    sys.exit(main())
