#!/usr/bin/env python
"""
install_templates.py - the user's finished Tiled work from the Player_Cap folder back into the plane (round 343).

Reads player_town.tmx / player_capital.tmx as Tiled saved them in --src (paths relative to that folder), and
writes them into maps/map/towns/ with every reference re-pointed to where the game keeps it:
  tilesets in the plane folder      -> ../../tileset/<name>
  tilesets in common                -> ../../../../common/maps/tileset/<name>
  object templates (.tx)            -> ../../../../common/maps/obj/<name> or ../../obj/<name>
  the user's own Walls.tsx (48 px)  -> ../../tileset/walls_48.tsx, a shipping copy this script writes with
                                       collision: every piece a full 48x48 box, gate pieces two side boxes
Tilesets the map does not use are dropped. The build refuses to finish while any reference fails to resolve,
and prints the fixed land shops (Tiled object ids 55, 77-81 - TownRestoration's LAND_SHOP_RUIN_REGIONS) so a
lost object shows up here, not in play.
"""
import argparse
import os
import shutil
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tmx  # noqa: E402

REPO = os.path.dirname(os.path.dirname(HERE))
ADVENTURE = os.path.join(REPO, "forge-gui", "res", "adventure")
PLANE = os.path.join(ADVENTURE, "The Forsaken Realms")
TOWNS = os.path.join(PLANE, "maps", "map", "towns")
TILESETS = os.path.join(PLANE, "maps", "tileset")
OBJ_PLANE = os.path.join(PLANE, "maps", "obj")
COMMON_TILESETS = os.path.join(ADVENTURE, "common", "maps", "tileset")
OBJ_COMMON = os.path.join(ADVENTURE, "common", "maps", "obj")
DEFAULT_SRC = r"C:\Users\User\Pictures\Screenshots\Player_Cap"
WALLS_48_ART = r"C:\Users\User\Pictures\Screenshots\Terrain\Walls.png"

# walls_48 collision: a piece blocks its opaque extent (the vertical wall bands 160/162 are 24 px wide, the shrine 82
# sits inside its tile), except the pieces listed here - (x, y, w, h) boxes in the 48 px tile. 145 is the gate: the
# dark doorway is painted, not transparent, so its middle third stays open for the road (cell x+1 of the three).
GATE_BOXES = {145: [(0, 0, 16, 48), (32, 0, 16, 48)]}
FIXED_SHOPS = (55, 77, 78, 79, 80, 81)


def norm(p):
    return os.path.normcase(os.path.normpath(p))


def write_walls_48():
    """Walls.png as a 48 px tileset in the plane folder: walls_48.tsx (collision) and walls_48-nocollide.tsx."""
    png = os.path.join(TILESETS, "walls_48.png")
    shutil.copyfile(WALLS_48_ART, png)
    art = Image.open(png).convert("RGBA")
    for name, collide in (("walls_48", True), ("walls_48-nocollide", False)):
        lines = ['<?xml version="1.0" encoding="UTF-8"?>',
                 '<tileset version="1.10" tiledversion="1.12.2" name="%s" tilewidth="48" tileheight="48" tilecount="256" columns="16">' % name,
                 ' <image source="walls_48.png" width="768" height="768"/>']
        if collide:
            for tid in range(256):
                c, r = tid % 16, tid // 16
                alpha = art.crop((c * 48, r * 48, c * 48 + 48, r * 48 + 48)).getchannel("A")
                bbox = alpha.point(lambda v: 255 if v > 0 else 0).getbbox()
                if bbox is None:
                    continue
                boxes = GATE_BOXES.get(tid, [(bbox[0], bbox[1], bbox[2] - bbox[0], bbox[3] - bbox[1])])
                lines += [' <tile id="%d">' % tid, '  <objectgroup draworder="index" id="2">']
                for i, (x, y, w, h) in enumerate(boxes):
                    lines.append('   <object id="%d" x="%d" y="%d" width="%d" height="%d"/>' % (i + 1, x, y, w, h))
                lines += ['  </objectgroup>', ' </tile>']
        lines.append('</tileset>')
        with open(os.path.join(TILESETS, name + ".tsx"), "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(lines) + "\n")
    print("walls_48: written (%d gate pieces with side boxes)" % len(GATE_BOXES))


def write_walls_16():
    """Round 346b: Walls.png cut at 16 px - the user's "Smaller Walls" (Tiled's read of the same art on the map's own
    grid, 48 columns) - as walls_16.tsx (collision = each cell's opaque extent) and walls_16-nocollide.tsx, both over
    the walls_48.png already in the plane. Same grid, so a map made with the user's tileset keeps every tile id."""
    png = os.path.join(TILESETS, "walls_48.png")
    art = Image.open(png).convert("RGBA")
    cols, rows = art.width // 16, art.height // 16
    for name, collide in (("walls_16", True), ("walls_16-nocollide", False)):
        lines = ['<?xml version="1.0" encoding="UTF-8"?>',
                 '<tileset version="1.10" tiledversion="1.12.2" name="%s" tilewidth="16" tileheight="16" tilecount="%d" columns="%d">'
                 % (name, cols * rows, cols),
                 ' <image source="walls_48.png" width="%d" height="%d"/>' % art.size]
        boxes = 0
        if collide:
            for tid in range(cols * rows):
                c, r = tid % cols, tid // cols
                alpha = art.crop((c * 16, r * 16, c * 16 + 16, r * 16 + 16)).getchannel("A")
                bbox = alpha.point(lambda v: 255 if v > 0 else 0).getbbox()
                if bbox is None:
                    continue
                boxes += 1
                lines += [' <tile id="%d">' % tid, '  <objectgroup draworder="index" id="2">',
                          '   <object id="1" x="%d" y="%d" width="%d" height="%d"/>' % (bbox[0], bbox[1], bbox[2] - bbox[0], bbox[3] - bbox[1]),
                          '  </objectgroup>', ' </tile>']
        lines.append('</tileset>')
        with open(os.path.join(TILESETS, name + ".tsx"), "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(lines) + "\n")
        print("%s: %d tiles, %d with a box" % (name, cols * rows, boxes))


def relocate(src_dir, ref):
    """A reference as Tiled saved it (relative to src_dir) -> the same file relative to maps/map/towns/."""
    target = norm(os.path.join(src_dir, ref))
    base = os.path.basename(target)
    if target == norm(os.path.join(src_dir, "Walls.tsx")):
        return "../../tileset/walls_48.tsx"
    if target == norm(os.path.join(src_dir, "Smaller Walls.tsx")):
        return "../../tileset/walls_16.tsx"
    if target == norm(os.path.join(src_dir, "..", "Terrain", "Walls.png")):
        return "../../tileset/walls_48.png"
    for folder, rel in ((TILESETS, "../../tileset/"), (COMMON_TILESETS, "../../../../common/maps/tileset/"),
                        (OBJ_COMMON, "../../../../common/maps/obj/"), (OBJ_PLANE, "../../obj/")):
        if os.path.dirname(target) == norm(folder):
            return rel + base
    raise SystemExit("no home in the plane for the reference %s (%s)" % (ref, target))


def install(name, src_dir, renders):
    src = os.path.join(src_dir, name + ".tmx")
    m = tmx.Map(src)
    used = set()
    for layer in m.layers.values():
        for gid in layer.cells:
            if gid:
                used.add(m.tileset_for(gid).firstgid)
    kept, dropped = [], []
    for el in list(m.root.findall("tileset")):
        firstgid = int(el.get("firstgid"))
        info = [t for t in m.tilesets if t.firstgid == firstgid][0]
        if firstgid not in used:
            m.root.remove(el)
            dropped.append(info.name)
            continue
        kept.append(info.name)
        if el.get("source"):
            el.set("source", relocate(src_dir, el.get("source")))
        else:
            img = el.find("image")
            img.set("source", relocate(src_dir, img.get("source")))
    for obj in m.root.iter("object"):
        if obj.get("template"):
            obj.set("template", relocate(src_dir, obj.get("template")))
    out = os.path.join(TOWNS, name + ".tmx")
    m.write(out)
    check = tmx.Map(out)
    missing = check.missing()
    if missing:
        raise SystemExit("%s: references that do not resolve from maps/map/towns/: %s" % (name, missing))
    objects = check.root.findall("objectgroup/object")
    ids = {int(o.get("id")): o for o in objects}
    shops = "n/a (Capitol only)" if name != "player_capital" else ", ".join(
        "%d %s" % (i, "ok" if i in ids and (ids[i].get("template") or "").endswith("shop.tx") else "MISSING")
        for i in FIXED_SHOPS)
    print("%s: installed to %s\n  kept %s\n  dropped %s\n  %d objects; fixed land shops: %s\n  %d references resolve"
          % (name, out, kept, dropped, len(objects), shops, len(check.references())))
    if renders:
        check.render(2).save(os.path.join(renders, "installed_%s.png" % name))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", default=DEFAULT_SRC)
    ap.add_argument("--renders", default="")
    ap.add_argument("--only", default="town,capital")
    a = ap.parse_args()
    write_walls_48()
    if "walls16" in a.only:
        write_walls_16()
    if "town" in a.only:
        install("player_town", a.src, a.renders or None)
    if "capital" in a.only:
        install("player_capital", a.src, a.renders or None)


if __name__ == "__main__":
    main()
