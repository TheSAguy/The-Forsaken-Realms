"""Round 303: write the approved art into the plane.
- world/structures/<color>_structures_hd.png + .atlas (96x128 XP regions at 32 px per tile), biome JSONs point at them
- world/sprites/doodads_hd.png + .atlas (one 32x32 cell per variant, the sprite centered and standing on the cell's
  bottom), map_sprites.json entries (atlas + scale 0.5), biome spriteNames
Stock/common files are never touched; JSON files get minimal text edits.
python export.py <repo root> [--dry]   (the tree to write is REQUIRED)"""
import json
import os
import re
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build  # noqa: E402
import paths  # noqa: E402
import spec  # noqa: E402

if len(sys.argv) < 2 or sys.argv[1].startswith("-"):
    raise SystemExit("usage: python export.py <repo root> [--dry]")
PLANE = build.PLANE = paths.plane(sys.argv[1])
DRY = "--dry" in sys.argv
COLORS = ["white", "blue", "black", "red", "green", "colorless", "player"]
CELL = 32
NL = "\n"


def atlas_text(png, size, regions):
    out = "%s\nsize: %d,%d\nformat: RGBA8888\nfilter: Nearest,Nearest\nrepeat: none\n" % (png, size[0], size[1])
    for name, (x, y, w, h) in regions:
        out += "%s\n  rotate: false\n  xy: %d, %d\n  size: %d, %d\n  orig: %d, %d\n  offset: 0, 0\n  index: -1\n" % (
            name, x, y, w, h, w, h)
    return out


def write(path, data, binary=False):
    if DRY:
        print("   (dry)", os.path.relpath(path, PLANE))
        return
    if binary:
        data.save(path)
    else:
        open(path, "w", encoding="utf-8", newline="\n").write(data)
    print("   wrote", os.path.relpath(path, PLANE))


def swatch(xp):
    """Round 305: the minimap reads a region's top-left 4x4 (World.createSmallPixmap()) - the XP tiles there are never
    drawn in the world, and the first HD sheets left them empty, so structures vanished from the minimap and the
    player's ground showed the red the minimap is primed with. Fill it with the art's mean colour."""
    frame = xp.crop((0, 32, 96, 128))
    px = [p for p in frame.getdata() if p[3] > 200]
    res = xp.copy()
    if not px:
        return res
    m = tuple(int(sum(p[c] for p in px) / len(px)) for c in range(3)) + (255,)
    for yy in range(4):
        for xx in range(4):
            res.putpixel((xx, yy), m)
    return res


def outline(img, skip=(0, 0, 4, 4)):
    """Round 328: a 1-px black outline (4-neighbour) on the transparent pixels next to the art. The minimap swatch
    square (see swatch()) is not art: it gets no outline and its colour is left as swatch() computed it."""
    src = img.load()
    out = img.copy()
    o = out.load()
    w, h = img.size
    sx0, sy0, sx1, sy1 = skip
    for y in range(h):
        for x in range(w):
            if src[x, y][3] != 0:
                continue
            for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                xx, yy = x + dx, y + dy
                if 0 <= xx < w and 0 <= yy < h and src[xx, yy][3] >= 128 and not (sx0 <= xx < sx1 and sy0 <= yy < sy1):
                    o[x, y] = (0, 0, 0, 255)
                    break
    return out


def structures():
    for color in COLORS:
        sheets = build.new_structures(color)
        names = list(sheets)
        cols = 4
        rows = (len(names) + cols - 1) // cols
        page = Image.new("RGBA", (cols * 96, rows * 128), (0, 0, 0, 0))
        regions = []
        for i, n in enumerate(names):
            x, y = (i % cols) * 96, (i // cols) * 128
            block = swatch(sheets[n])
            if n in spec.OUTLINED_STRUCTURES.get(color, ()):  # round 328
                block = outline(block)
            page.alpha_composite(block, (x, y))
            regions.append((n, (x, y, 96, 128)))
        base = "%s_structures_hd" % color
        write(os.path.join(PLANE, "world", "structures", base + ".png"), page, binary=True)
        write(os.path.join(PLANE, "world", "structures", base + ".atlas"), atlas_text(base + ".png", page.size, regions))


def doodads():
    entries = []
    cells = []
    for color in COLORS:
        for d in build.new_doodads(color):
            entries.append(d)
            for v in d["variants"]:
                cell = Image.new("RGBA", (CELL, CELL), (0, 0, 0, 0))
                cell.alpha_composite(v, ((CELL - v.width) // 2, CELL - v.height - 1))
                cells.append((d["name"], cell))
    import whirlpool
    big = [("Whirlpool", whirlpool.whirlpool())]
    entries.append(dict(name="Whirlpool", variants=[big[0][1]], layer=-1, start=0.0, end=1.0, res=1,
                        density=dict(spec.OCEAN_DOODADS)["Whirlpool"], on=["ocean"], scale=0.6667))
    cols = 16
    rows = (len(cells) + cols - 1) // cols
    page = Image.new("RGBA", (cols * CELL, rows * CELL + 48), (0, 0, 0, 0))
    regions = []
    for i, (name, cell) in enumerate(cells):
        x, y = (i % cols) * CELL, (i // cols) * CELL
        page.alpha_composite(cell, (x, y))
        regions.append((name, (x, y, CELL, CELL)))
    for i, (name, im) in enumerate(big):   # round 305: the whirlpool, 48 px, drawn two tiles wide
        page.alpha_composite(im, (i * 48, rows * CELL))
        regions.append((name, (i * 48, rows * CELL, 48, 48)))
        cells.append((name, im))
    write(os.path.join(PLANE, "world", "sprites", "doodads_hd.png"), page, binary=True)
    write(os.path.join(PLANE, "world", "sprites", "doodads_hd.atlas"), atlas_text("doodads_hd.png", page.size, regions))
    print("   %d doodad kinds, %d pictures" % (len(entries), len(cells)))
    return entries


def block(d):
    """One map_sprites.json entry in the file's own hand-written style."""
    lines = ['"name":"%s"' % d["name"], '"atlas":"world/sprites/doodads_hd.atlas"',
             '"startArea":%s' % d["start"], '"endArea":%s' % d["end"], '"layer":%d' % d["layer"],
             '"resolution":%s' % d["res"], '"density":%s' % d["density"], '"scale":%s' % d.get("scale", 0.5)]
    if d.get("on"):
        lines.append('"onStructures":[%s]' % ",".join('"%s"' % s for s in d["on"]))
    return "{" + NL + ("," + NL).join(lines) + NL + "}"


def catalog(entries):
    """The player's five entries re-skinned in place (so a save's placed ones change too), the new ones appended
    before the closing bracket; every other byte of the file stays as it is."""
    path = os.path.join(PLANE, "world", "sprites", "map_sprites.json")
    text = open(path, encoding="utf-8").read()
    new = []
    for d in entries:
        pat = re.compile(r'\{\s*"name":"' + re.escape(d["name"]) + r'",[^{}]*\}')
        hits = pat.findall(text)
        if len(hits) > 1:
            raise SystemExit("two entries named " + d["name"])
        if hits:
            text = pat.sub(lambda m, d=d: block(d), text)
            print("   re-skinned", d["name"])
        else:
            new.append(block(d))
    tail = text.rstrip()
    cut = tail.rfind("]")
    body = tail[:cut].rstrip()
    assert body.endswith("}"), body[-30:]
    text = body + ("," + ",".join(new) if new else "") + NL + "]" + NL + "}" + NL
    json.loads(text)  # still valid
    write(path, text)


def biomes():
    """Each structure set's atlas path, and the spriteNames array in the file's own layout."""
    for color in COLORS:
        path = os.path.join(PLANE, "world", "biomes", color + ".json")
        text = open(path, encoding="utf-8").read()
        text = text.replace('"world/structures/%s_structures.atlas"' % color,
                            '"world/structures/%s_structures_hd.atlas"' % color)
        m = re.search(r'"spriteNames":\s*\[[^\]]*\]', text)
        if not m:
            raise SystemExit("no spriteNames in " + color)
        names = [d[0] for d in spec.DOODADS[color]]
        arr = '"spriteNames":  [' + NL + ("," + NL).join(" " * 24 + '"%s"' % n for n in names) + NL + " " * 20 + "]"
        text = text[:m.start()] + arr + text[m.end():]
        b = json.loads(text)
        assert b["spriteNames"] == names
        assert all(s["structureAtlasPath"].endswith("_structures_hd.atlas") for s in b.get("structures") or [])
        write(path, text)


def ocean():
    """Round 305: the plane's own copy of common's base.json (the ocean), with its doodads - common stays untouched."""
    src = os.path.join(os.path.dirname(PLANE), "common", "world", "biomes", "base.json")
    b = json.load(open(src, encoding="utf-8"))
    b["spriteNames"] = [n for n, _ in spec.OCEAN_DOODADS]
    write(os.path.join(PLANE, "world", "biomes", "base.json"), json.dumps(b, indent=2) + NL)


if __name__ == "__main__":
    structures()
    entries = doodads()
    catalog(entries)
    biomes()
    ocean()
