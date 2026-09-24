"""Round 303 builder: new structure sheets + doodad sprites from spec.py, and before/after preview scenes.
python build.py preview   -> preview_<color>.png (before | after), doodads_<color>.png (every new doodad at 3x)
"""
import json
import os
import random
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import render  # noqa: E402
import sources as S  # noqa: E402
import spec  # noqa: E402
from atlas import read_atlas  # noqa: E402

import paths  # noqa: E402

PLANE = paths.plane()   # export.py points this at the tree it writes
RES = os.path.dirname(PLANE)
T = 32


def resolve(rel):
    p = os.path.join(PLANE, rel)
    return p if os.path.exists(p) else os.path.join(os.path.dirname(PLANE), "common", rel)


def region_image(reg):
    page = Image.open(reg["page"]).convert("RGBA")
    return page.crop((reg["x"], reg["y"], reg["x"] + reg["w"], reg["y"] + reg["h"]))


def at32(xp):
    """An XP sheet at any tile size -> 96x128 (pixel-exact enlargement, like BiomeTexture.atTileSize)."""
    return xp if xp.size == (96, 128) else xp.resize((96, 128), Image.NEAREST)


def biome(color):
    return json.load(open(os.path.join(PLANE, "world", "biomes", color + ".json"), encoding="utf-8"))


# ------------------------------------------------------------------ new art

def new_structure(src, tf):
    if src.startswith("grid:"):
        xp = S.grid_autotile(S.sprite(src[5:]))
    else:
        xp = S.clear_row0(S.autotile(src))
    if tf:
        xp = S.tint(xp, **tf)
    return xp


def new_structures(color):
    return {name: new_structure(src, tf) for name, (src, tf) in spec.STRUCTURES[color].items()}


def new_doodads(color):
    out = []
    for entry in spec.DOODADS[color]:
        name, refs, tf, layer, a0, a1, res, dens, box = entry[:9]
        extra = entry[9] if len(entry) > 9 else {}
        variants = []
        for ref in refs:
            own = tf
            if isinstance(ref, tuple):   # round 309: (ref, tint) - a picture's own transform over the kind's
                ref, own = ref
            img = S.sprite(ref)
            if own:
                img = S.tint(img, **own)
            variants.append(S.fit(img, *box))
        out.append(dict(name=name, variants=variants, layer=layer, start=a0, end=a1, res=res, density=dens,
                        on=extra.get("onStructures")))
    return out


# ------------------------------------------------------------------ old art

def old_structures(color):
    b = biome(color)
    out = {}
    for s in b.get("structures") or []:
        regs = read_atlas(resolve(s["structureAtlasPath"]))
        for m in s["mappingInfo"]:
            if m["name"] in regs and m["name"] not in out:
                out[m["name"]] = at32(region_image(regs[m["name"]][0]))
    return out


_old_sprites = None


def old_doodads(color):
    global _old_sprites
    if _old_sprites is None:
        _old_sprites = read_atlas(os.path.join(PLANE, "world", "sprites", "map_sprites.atlas"))
        _old_sprites.update(read_atlas(os.path.join(PLANE, "world", "sprites", "player_doodads.atlas")))
    cat = {d["name"]: d for d in json.load(open(os.path.join(PLANE, "world", "sprites", "map_sprites.json"), encoding="utf-8"))["sprites"]}
    out = []
    for name in biome(color).get("spriteNames") or []:
        d = cat.get(name, {})
        vs = [region_image(r) for r in _old_sprites.get(name, [])]
        vs = [v.resize((v.width * 2, v.height * 2), Image.NEAREST) for v in vs]
        out.append(dict(name=name, variants=vs, layer=d.get("layer", -1), density=d.get("density", 0.01)))
    return out


# ------------------------------------------------------------------ the scene

def ground(color):
    b = biome(color)
    at = read_atlas(resolve(b["tilesetAtlas"]))
    base = at32(region_image(at[b["tilesetName"]][0]))
    patches = [at32(region_image(at[t["spriteName"]][0])) for t in (b.get("terrain") or []) if t["spriteName"] in at]
    return base, patches


def blob_masks(W, H, seed):
    rnd = random.Random(seed)
    blobs = [(5, 4, 3.2, 2.2), (17, 3, 3.5, 2.0), (6, 10, 2.8, 2.0), (18, 10, 3.0, 2.2)]
    masks = []
    for cx, cy, rx, ry in blobs:
        m = set()
        for y in range(H):
            for x in range(W):
                d = ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2
                if d <= 1.0 + rnd.uniform(-0.25, 0.25):
                    m.add((x, y))
        masks.append(m)
    return masks


def draw_layer(img, xp, cells, W, H):
    grid = [[set() for _ in range(W)] for _ in range(H)]
    for (x, y) in cells:
        grid[y][x].add("k")
    for (x, y) in cells:
        n = render.layer_mask(grid, x, y, "k")
        render.draw_tile(img, xp, T, n, x * T, y * T)


def scene(color, structures, doodads, kinds, W=24, H=14, seed=303):
    base, patches = ground(color)
    img = Image.new("RGBA", (W * T, H * T), (0, 0, 0, 255))
    all_cells = {(x, y) for y in range(H) for x in range(W)}
    draw_layer(img, base, all_cells, W, H)
    rnd = random.Random(seed)
    for i, p in enumerate(patches[:2]):
        cx, cy = (11, 7) if i == 0 else (3, 12)
        cells = {(x, y) for (x, y) in all_cells if ((x - cx) / 3.0) ** 2 + ((y - cy) / 1.8) ** 2 <= 1}
        draw_layer(img, p, cells, W, H)
    masks = blob_masks(W, H, seed)
    used = set()
    kind_cells = {}
    for kind, cells in zip(kinds, masks):
        if kind in structures:
            draw_layer(img, structures[kind], cells, W, H)
            used |= cells
            kind_cells[kind] = cells
    water_dd = [d for d in doodads if d.get("on")]
    doodads = [d for d in doodads if not d.get("on")]
    # doodads: the same points on both sides; the kind is drawn from this side's list by density
    free = sorted(all_cells - used)
    weights = [max(1e-4, d["density"]) for d in doodads] or [1]
    flat, upright = [], []
    for (x, y) in rnd.sample(free, min(len(free), 70)):
        r = rnd.random()
        if not doodads:
            continue
        d = rnd.choices(doodads, weights=weights)[0]
        if not d["variants"]:
            continue
        v = d["variants"][int(r * 1000) % len(d["variants"])]
        px = int((x + 0.5) * T - v.width / 2 + rnd.uniform(-6, 6))
        py = int((y + 1) * T - v.height - rnd.uniform(0, 8))
        (flat if d["layer"] < 0 else upright).append((py, px, v))
    wrnd = random.Random(seed + 1)
    for d in water_dd:
        for kind in d["on"]:
            for (x, y) in sorted(kind_cells.get(kind, ())):
                if wrnd.random() < d["density"] * 3 and d["variants"]:   # x3: a small scene, so they show
                    v = d["variants"][wrnd.randrange(len(d["variants"]))]
                    flat.append((int((y + 1) * T - v.height - wrnd.uniform(0, 6)),
                                 int((x + 0.5) * T - v.width / 2 + wrnd.uniform(-5, 5)), v))
    for py, px, v in sorted(flat) + sorted(upright):
        img.alpha_composite(v, (max(0, px), max(0, py)))
    return img


def preview(color):
    b = biome(color)
    names = [m["name"] for s in b.get("structures") or [] for m in s["mappingInfo"]]
    order = []
    for n in names:
        if n not in order:
            order.append(n)
    kinds = (order[1:2] + order[-2:-1] + order[:1] + order[-1:]) if len(order) > 3 else order
    before = scene(color, old_structures(color), old_doodads(color), kinds)
    after = scene(color, new_structures(color), new_doodads(color), kinds)
    W = before.width
    out = Image.new("RGB", (W * 2 + 20, before.height + 28), (30, 30, 30))
    out.paste(before.convert("RGB"), (0, 28))
    out.paste(after.convert("RGB"), (W + 20, 28))
    d = ImageDraw.Draw(out)
    d.text((6, 8), "%s - BEFORE   (structures shown: %s)" % (color.upper(), ", ".join(kinds)), fill=(255, 255, 0))
    d.text((W + 26, 8), "%s - AFTER" % color.upper(), fill=(120, 255, 120))
    out.save(os.path.join(paths.OUT, "preview_%s.png" % color))
    return out


def structure_sheet(color):
    """Every structure kind of a color, old (left) and new (right), at 1x of the 32 px sheet."""
    old, new = old_structures(color), new_structures(color)
    names = list(spec.STRUCTURES[color])
    cw = 96 * 2 + 30
    out = Image.new("RGB", (len(names) * (96 + 12), 2 * (128 + 20) + 10), (40, 40, 40))
    d = ImageDraw.Draw(out)
    for i, n in enumerate(names):
        x = i * (96 + 12)
        d.text((x + 2, 2), n, fill=(255, 255, 0))
        for row, im in enumerate((old.get(n), new.get(n))):
            if im is None:
                continue
            bg = Image.new("RGBA", im.size, (96, 120, 70, 255))
            bg.alpha_composite(im)
            out.paste(bg.convert("RGB"), (x, 16 + row * (128 + 20)))
    out.save(os.path.join(paths.OUT, "structures_%s.png" % color))


def doodad_sheet(color):
    dd = new_doodads(color)
    cell = 32 * 3 + 8
    cols = max(len(d["variants"]) for d in dd)
    out = Image.new("RGB", (160 + cols * cell, len(dd) * (cell + 4)), (40, 40, 40))
    d = ImageDraw.Draw(out)
    for r, dm in enumerate(dd):
        y = r * (cell + 4)
        d.text((4, y + 40), "%s\nlayer %d" % (dm["name"], dm["layer"]), fill=(255, 255, 0))
        for c, v in enumerate(dm["variants"]):
            big = v.resize((v.width * 3, v.height * 3), Image.NEAREST)
            bg = Image.new("RGBA", (cell - 4, cell - 4), (96, 120, 70, 255))
            bg.alpha_composite(big, ((cell - 4 - big.width) // 2, (cell - 4 - big.height) // 2))
            out.paste(bg.convert("RGB"), (160 + c * cell, y + 2))
    out.save(os.path.join(paths.OUT, "doodads_%s.png" % color))


if __name__ == "__main__":
    colors = sys.argv[2:] or ["white", "blue", "black", "red", "green", "colorless", "player"]
    for c in colors:
        preview(c)
        structure_sheet(c)
        doodad_sheet(c)
        print("ok", c)
