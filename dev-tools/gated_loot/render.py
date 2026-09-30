"""render.py - a verification picture per map: collision grey, gates red, WALL/other blockers purple,
reachable-with-gates-shut green, reachable-only-with-gates-open yellow, boosters blue, chests orange,
other pickups cyan, our added guards magenta (removal list = thick outline), authored enemies black.

usage: python render.py <rel map> [...]    -> renders/<map>.png
"""
import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import geom
from audit import World, origin_of

OUT = os.path.join(HERE, "renders")


def main(targets, scale=2):
    os.makedirs(OUT, exist_ok=True)
    W = World()
    rep = json.load(open(os.path.join(HERE, "audit.json"), encoding="utf-8"))
    hist = json.load(open(os.path.join(HERE, "enemy_history.json"), encoding="utf-8"))
    removal = {(x["map"], x["id"]) for x in rep["removal"]}
    edge = {(x["map"], x["id"]) for x in rep["edge"]}
    all_gates = {(r, i) for (r, i, k) in W.gate_list if k == "blocker"}
    aa = {(r, p["id"]) for r, m in W.maps.items() for p in m.portals if p["_portal_active"]}
    ap = {(r, p["id"]) for r, m in W.maps.items() for p in m.portals}
    cv, cs = W.reach(all_gates, aa)
    ov, os_ = W.reach(set(), ap)
    for r in targets:
        m = W.maps[r]
        blocked, Wd, H, tw, th = m.base()
        g = blocked.copy()
        closed_ids = {i for (rr, i) in all_gates if rr == r}
        for b in m.blockers:
            if b["cls"] == "GATE":
                geom.add_rect(g, *b["box"])
        lab_c = geom.label(geom.free_positions(g))
        lab_o = geom.label(geom.free_positions(blocked))
        img = np.full((H, Wd, 3), 255, dtype=np.uint8)
        rc = np.isin(lab_c, list(cv.get(r, set()))) if cv.get(r) else np.zeros_like(blocked)
        ro = np.isin(lab_o, list(ov.get(r, set()))) if ov.get(r) else np.zeros_like(blocked)
        img[ro & ~rc] = (255, 235, 120)
        img[rc] = (190, 235, 190)
        img[blocked] = (110, 110, 110)
        im = Image.fromarray(img).resize((Wd * scale, H * scale), Image.NEAREST)
        d = ImageDraw.Draw(im)
        S = scale
        for b in m.blockers:
            x, y, w, h = b["box"]
            col = {"GATE": (230, 0, 0), "WALL": (120, 0, 160), "FREE": (0, 160, 255), "INACTIVE": (200, 200, 200)}[b["cls"]]
            d.rectangle([x * S, y * S, (x + w) * S, (y + h) * S], outline=col, width=2)
            d.text((x * S, (y - 10) * S / 2 + y * S / 2 - 10), "%s#%d" % (b["cls"][0], b["id"]), fill=col)
        for o in m.rewards:
            yb = o["y"] if o["is_tile"] else o["y"] + o["h"]
            col = {"booster": (0, 60, 255), "chest": (255, 140, 0), "other": (0, 190, 190)}[o["_kind"]]
            d.rectangle([o["x"] * S, (yb - o["h"]) * S, (o["x"] + o["w"]) * S, yb * S], outline=col, width=2)
            d.text((o["x"] * S + 2, (yb - o["h"]) * S + 2), str(o["id"]), fill=col)
        for e in m.enemies:
            yb = e["y"] if e["is_tile"] else e["y"] + e["h"]
            who, lab, _ = origin_of(hist, r, e["id"])
            col = (220, 0, 220) if who == "OURS" else (0, 0, 0)
            wdt = 4 if (r, e["id"]) in removal else (3 if (r, e["id"]) in edge else 1)
            d.ellipse([e["x"] * S, (yb - e["h"]) * S, (e["x"] + e["w"]) * S, yb * S], outline=col, width=wdt)
            d.text((e["x"] * S, yb * S), "%d%s" % (e["id"], "*" if (r, e["id"]) in removal else ("?" if (r, e["id"]) in edge else "")), fill=col)
        for o in m.arrivals:
            yb = o["y"] if o["is_tile"] else o["y"] + o["h"]
            d.rectangle([o["x"] * S, (yb - o["h"]) * S, (o["x"] + o["w"]) * S, yb * S], outline=(0, 120, 0), width=1)
        fn = os.path.join(OUT, r.replace("/", "__").replace(".tmx", ".png"))
        im.save(fn)
        print(fn)


if __name__ == "__main__":
    main(sys.argv[1:])
