"""Switch a land's ground, or every land's patch size, to another of the options kept here (round 307; the user:
"don't get rid of any of the other options. I might want to switch some out").

    python set_ground.py <repo root> <land> <letter>   white / blue / black / red / green / colorless (the wasteland)
    python set_ground.py <repo root> <land> old         back to the stock 16 px ground (world/tilesets/terrain.atlas)
    python set_ground.py <repo root> player <tone>      the player's 32 px ground toned 0..1 toward its old 16 px one
    python set_ground.py <repo root> patches <A|B|C|old>   every land's patch size (see PATCHES)

<land>.png holds that land's options, one row each, A at the top: base | patch 1 | patch 2, each a 96x128 XP autotile
at 32 px with its minimap swatch in the top-left 4x4 (round 305). The pick is written to the plane's
world/tilesets/<land>_terrain_hd.png/.atlas and the biome's tilesetAtlas points there. Nothing else is needed: on its
next load a save lays its patches out again when the bands changed, and re-bakes its map image when the ground art
changed (World.migrateGround(), round 307). Edits keep every other byte of a biome file as it was."""
import os
import re
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
LANDS = {"white": "White", "blue": "Blue", "black": "Black", "red": "Red", "green": "Green", "colorless": "Colorless"}
LETTERS = "ABCDEFGH"   # a land has as many options as its sheet has rows
# name: (resolution, patch 1's max, patch 2's min) - patch 1 covers noise [0, max], patch 2 [min, 1] (World.generateNew())
PATCHES = {"A": (4, 0.2, 0.8),      # bigger, the same amount of patch as before
           "B": (3, 0.25, 0.75),    # bigger and a bit more - round 307's pick
           "C": (2, 0.25, 0.75),    # much bigger and a bit more
           "old": None}             # before round 307: the colors 10, the wasteland and the player 5, bands 0.2 / 0.8
OLD_RES = {"colorless": 5, "player": 5}


def plane(root):
    p = os.path.join(root, "forge-gui", "res", "adventure", "The Forsaken Realms")
    if not os.path.isdir(os.path.join(p, "world", "biomes")):
        raise SystemExit("not a TFR repo root: " + root)
    return p


def read(path):
    return open(path, "rb").read().decode("utf-8")


def write(path, text):
    open(path, "wb").write(text.encode("utf-8"))
    print("wrote", path)


def atlas_text(png, names):
    out = "%s\nsize: %d,128\nformat: RGBA8888\nfilter: Nearest,Nearest\nrepeat: none\n" % (png, 96 * len(names))
    for i, n in enumerate(names):
        out += "%s\n  rotate: false\n  xy: %d, 0\n  size: 96, 128\n  orig: 96, 128\n  offset: 0, 0\n  index: -1\n" % (n, i * 96)
    return out


def point_biome(pl, land, atlas):
    path = os.path.join(pl, "world", "biomes", land + ".json")
    text = read(path)
    new = re.sub(r'("tilesetAtlas":\s*)"[^"]*"', lambda m: m.group(1) + '"%s"' % atlas, text, count=1)
    if new != text:
        write(path, new)


def set_land(pl, land, letter):
    cap = LANDS[land]
    if letter == "old":
        point_biome(pl, land, "world/tilesets/terrain.atlas")
        return
    sheet = Image.open(os.path.join(HERE, land + ".png")).convert("RGBA")
    row = LETTERS.index(letter)
    if (row + 1) * 128 > sheet.height:
        raise SystemExit("%s has no option %s" % (land, letter))
    base = "%s_terrain_hd" % land
    ts = os.path.join(pl, "world", "tilesets")
    sheet.crop((0, row * 128, 288, row * 128 + 128)).save(os.path.join(ts, base + ".png"))
    print("wrote", os.path.join(ts, base + ".png"), "(%s %s)" % (land, letter))
    write(os.path.join(ts, base + ".atlas"), atlas_text(base + ".png", [cap, cap + "_1", cap + "_2"]))
    point_biome(pl, land, "world/tilesets/%s.atlas" % base)


# ---------------------------------------------------------------- the player's tone (round 305's player_tone.py)

def _mean(im):
    px = [p for p in im.getdata() if p[3] > 200]
    return [sum(p[i] for p in px) / len(px) for i in range(3)]


def _tone(h, o, t):
    """h (96x128 XP) with its centre tile's mean moved t of the way to o's (the old 16 px region's) centre-tile mean."""
    hm, om = _mean(h.crop((32, 64, 64, 96))), _mean(o.crop((16, 32, 32, 48)))
    gain = [1 + t * ((om[c] / max(1.0, hm[c])) - 1) for c in range(3)]
    res = h.copy()
    px = res.load()
    for y in range(res.height):
        for x in range(res.width):
            r, g, b, a = px[x, y]
            if a:
                px[x, y] = (min(255, int(r * gain[0])), min(255, int(g * gain[1])), min(255, int(b * gain[2])), a)
    m = _mean(res.crop((32, 64, 64, 96)))
    for y in range(4):
        for x in range(4):
            res.putpixel((x, y), (int(m[0]), int(m[1]), int(m[2]), 255))
    return res


def set_player(pl, t):
    ts = os.path.join(pl, "world", "tilesets")
    old = Image.open(os.path.join(ts, "player_terrain.png")).convert("RGBA")
    src = Image.open(os.path.join(HERE, "player_source.png")).convert("RGBA")
    page = src.copy()
    for i in range(src.width // 96):
        page.paste(_tone(src.crop((i * 96, 0, i * 96 + 96, 128)), old.crop((i * 48, 0, i * 48 + 48, 64)), t), (i * 96, 0))
    page.save(os.path.join(ts, "player_terrain_hd.png"))
    print("wrote", os.path.join(ts, "player_terrain_hd.png"), "toned", t)


# ---------------------------------------------------------------- patch sizes

def _block(text, key):
    """The span of a biome file's `key` array."""
    m = re.search(r'"%s":\s*\[' % key, text)
    if not m:
        return None
    depth, i = 0, m.end() - 1
    while True:
        depth += {"[": 1, "]": -1}.get(text[i], 0)
        if depth == 0:
            return m.end(), i
        i += 1


def _set(obj, key, value):
    new, n = re.subn(r'("%s":\s*)[-0-9.]+' % key, lambda m: m.group(1) + ("%g" % value), obj, count=1)
    if not n and value != 0:
        raise SystemExit("no %s in %s" % (key, obj.strip()[:60]))
    return new


def set_patches(pl, pick):
    for land in list(LANDS) + ["player"]:
        path = os.path.join(pl, "world", "biomes", land + ".json")
        text = read(path)
        for key in ("terrain", "overlays"):
            span = _block(text, key)
            if not span:
                continue
            body = text[span[0]:span[1]]
            objs = list(re.finditer(r"\{[^{}]*\}", body))
            out, last = [], 0
            for k, m in enumerate(objs):
                obj = m.group(0)
                if pick == "old":
                    res, max1, min2 = OLD_RES.get(land, 10), 0.2, 0.8
                else:
                    res, max1, min2 = PATCHES[pick]
                obj = _set(obj, "resolution", res)
                if key == "terrain":
                    obj = _set(obj, "max", max1) if k == 0 else _set(obj, "min", min2)
                out.append(body[last:m.start()] + obj)
                last = m.end()
            text = text[:span[0]] + "".join(out) + body[last:] + text[span[1]:]
        write(path, text)


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    pl, what, pick = plane(sys.argv[1]), sys.argv[2], sys.argv[3]
    if what == "patches":
        if pick not in PATCHES:
            raise SystemExit("patches: A, B, C or old")
        set_patches(pl, pick)
    elif what == "player":
        set_player(pl, float(pick))
    elif what in LANDS:
        if pick not in LETTERS and pick != "old":
            raise SystemExit("%s: a letter A-H or old" % what)
        set_land(pl, what, pick)
    else:
        raise SystemExit("unknown land " + what)


if __name__ == "__main__":
    main()
