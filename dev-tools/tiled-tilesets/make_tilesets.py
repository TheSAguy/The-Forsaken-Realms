#!/usr/bin/env python
"""
make_tilesets.py - Tiled tilesets for the place maps (round 341, the user: "I want to redo the Player capitol...
I need the player territory as Tiled... I want to use some of the stuff in Outside.png and World.png").

The place maps (maps/map/**/*.tmx) are 16 px per tile - MapStage sizes a map by width x tilewidth, and the player
sprite is 16 units, so the tiles must be 16 px. This writes 16 px tilesets into the plane's maps/tileset/ folder:

  player_land.png/.tsx     the player's overworld ground (player_terrain_hd: Player, Player_1..3) and its eight
                           structures (crater, tree, tree2, tree3, tree4, rock, mountain, hole), every one of the
                           47 blob shapes an autotile can take, with a Tiled TERRAIN SET per kind so the Terrain
                           Brush picks the right piece. Structures carry a full-tile collision box.
  road.png/.tsx            round 342: the cobble road the user picked on Road.png (an A2 sheet, block row 1 col 2)
                           with its sand keyed out, so it lies over any ground - a blob terrain set "road" of 47.
  mv_walls  (.png + .tsx + -collide.tsx)   round 342: Walls.png, castles, houses and city walls, plain tiles.
  mv_statues  (.png + .tsx + -collide.tsx) round 342b: the B sheet of the Dungeon composite the user saved as
                           Statues.png - statues, pillars, ore piles, crystals - plain tiles.
  mv_outside_a1/a2/a4/a5/b, mv_world_a2/b/c  (.png + .tsx + -collide.tsx)
                           the RPG Maker MV sheets from the user's Terrain folder, 48 -> 16 px. A2 (and A1's first
                           frames) expand to blob terrain sets; A4's roofs to blob sets and its walls to 16-piece
                           edge sets; A5, B and C are plain tiles in their sheet order. The plain .tsx has no
                           collision, the -collide.tsx gives every tile a full box - the same pairing as
                           main.tsx / main-nocollide.tsx, so a map can take either.

Autotile formats: the plane's HD sheets are XP autotiles (3x4 tiles: row 0 = thumbnail, unused, inner corners;
rows 1-3 = the 3x3 frame) at 32 px; MV A2 blocks are VX autotiles (2x3 tiles) at 48 px, converted to XP the way
dev-tools/world-art/a2_convert.py does. The blob expansion is BiomeTexture.drawPixmapOn, ported in
dev-tools/world-art/render.py (draw_tile): for every 8-neighbour mask the tile the game would draw.

Usage (from the repo root):
    python dev-tools/tiled-tilesets/make_tilesets.py [--out <folder>] [--art <folder>] [--preview]
--preview also writes <out>/preview_*.png: each terrain set painted on a small test map, to eyeball.
"""
import argparse
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(REPO, "dev-tools", "world-art"))
import render  # noqa: E402  (draw_tile, the game's autotile rule)
import atlas as atlas_reader  # noqa: E402

PLANE = os.path.join(REPO, "forge-gui", "res", "adventure", "The Forsaken Realms")
DEFAULT_OUT = os.path.join(PLANE, "maps", "tileset")
DEFAULT_ART = r"C:\Users\User\Pictures\Screenshots\Terrain"
T = 16  # the place maps' tile size

# ---- masks and Tiled wang ids -----------------------------------------------------------------------------------
# render.layer_mask packs the 3x3 neighbourhood MSB first: TL, T, TR, L, C, R, BL, B, BR (rows top to bottom).
BIT = {"TL": 8, "T": 7, "TR": 6, "L": 5, "C": 4, "R": 3, "BL": 2, "B": 1, "BR": 0}


def has(n, name):
    return (n >> BIT[name]) & 1


def canonical(n):
    """A corner only matters when both edges beside it are set - the 47-shape blob rule."""
    n |= 1 << BIT["C"]
    for corner, (e1, e2) in {"TL": ("T", "L"), "TR": ("T", "R"), "BL": ("B", "L"), "BR": ("B", "R")}.items():
        if not (has(n, e1) and has(n, e2)):
            n &= ~(1 << BIT[corner])
    return n


def wang_id(n, present=1, absent=2):
    """Tiled's mixed wang id: top, top-right, right, bottom-right, bottom, bottom-left, left, top-left."""
    order = ["T", "TR", "R", "BR", "B", "BL", "L", "TL"]
    return ",".join(str(present if has(n, k) else absent) for k in order)


def blob_masks():
    seen = []
    for n in range(256):
        # spread the 8 neighbour bits around the centre bit
        full = ((n >> 4) << 5) | (1 << 4) | (n & 0b1111)
        c = canonical(full)
        if c not in seen:
            seen.append(c)
    assert len(seen) == 47, len(seen)
    return seen


# ---- image helpers ------------------------------------------------------------------------------------------------
def crisp(img, threshold=100):
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            px[x, y] = (r, g, b, 255) if a >= threshold else (0, 0, 0, 0)
    return img


def shrink(img, factor):
    """Area-average downscale in premultiplied colour, alpha made crisp - the world-art pipeline's rule."""
    img = img.convert("RGBA")
    pre = Image.new("RGBA", img.size)
    src = img.load()
    dst = pre.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = src[x, y]
            dst[x, y] = (r * a // 255, g * a // 255, b * a // 255, a)
    small = pre.reduce(factor)
    out = Image.new("RGBA", small.size)
    sp = small.load()
    op = out.load()
    for y in range(small.height):
        for x in range(small.width):
            r, g, b, a = sp[x, y]
            if a > 0:
                op[x, y] = (min(255, r * 255 // a), min(255, g * 255 // a), min(255, b * 255 // a), a)
    return crisp(out)


def holds_art(block, min_coverage=0.05):
    """A sheet slot with (almost) nothing in it - the user's Outside A2/A4 rips fill 8 of 32 and 15 of 40 blocks."""
    a = block.getchannel("A")
    covered = sum(1 for v in a.get_flattened_data() if v > 0) if hasattr(a, "get_flattened_data") else sum(1 for v in a.getdata() if v > 0)
    return covered >= min_coverage * a.width * a.height


def vx_to_xp(block, tile):
    """A VX/MV autotile (2x3 tiles) -> an XP autotile (3x4 tiles): a2_convert.py's rule at any tile size."""
    h = tile // 2
    xp = Image.new("RGBA", (3 * tile, 4 * tile), (0, 0, 0, 0))
    xp.alpha_composite(block.crop((0, 0, tile, tile)), (0, 0))                      # thumbnail
    xp.alpha_composite(block.crop((tile, 0, 2 * tile, tile)), (2 * tile, 0))        # the four inner corners
    m = [0, 1, 2, 1, 2, 3]
    for r in range(6):
        for c in range(6):
            sx, sy = m[c] * h, tile + m[r] * h
            xp.alpha_composite(block.crop((sx, sy, sx + h, sy + h)), (c * h, tile + r * h))
    return xp


def blob_tiles(xp, tile):
    """The 47 blob tiles of an XP autotile, as (mask, image) in a fixed order."""
    out = []
    for n in blob_masks():
        img = Image.new("RGBA", (tile, tile), (0, 0, 0, 0))
        render.draw_tile(img, xp, tile, n, 0, 0)
        out.append((n, img))
    return out


def wall_tiles(block, tile):
    """A VX wall autotile (a 2x2 block) -> its 16 edge shapes. Each quadrant comes from the block tile whose
    edges match (left edge -> column 0, top edge -> row 0), same quadrant."""
    h = tile // 2
    out = []
    for e in range(16):
        top, right, bottom, left = e & 1, (e >> 1) & 1, (e >> 2) & 1, (e >> 3) & 1
        img = Image.new("RGBA", (tile, tile), (0, 0, 0, 0))
        for (qx, qy), (edge_x, edge_y) in {(0, 0): (left, top), (1, 0): (right, top),
                                           (0, 1): (left, bottom), (1, 1): (right, bottom)}.items():
            col = 0 if (edge_x and qx == 0) else (1 if (edge_x and qx == 1) else (1 if qx == 0 else 0))
            row = 0 if (edge_y and qy == 0) else (1 if (edge_y and qy == 1) else (1 if qy == 0 else 0))
            sx, sy = col * tile + qx * h, row * tile + qy * h
            img.alpha_composite(block.crop((sx, sy, sx + h, sy + h)), (qx * h, qy * h))
        # a wang id with edges only (corners follow their edges) - Tiled's mixed set still reads it
        n = (1 << BIT["C"]) | (0 if top else 1 << BIT["T"]) | (0 if right else 1 << BIT["R"]) \
            | (0 if bottom else 1 << BIT["B"]) | (0 if left else 1 << BIT["L"])
        out.append((canonical(n), img))
    return out


# ---- the tileset writer ---------------------------------------------------------------------------------------------
class Tileset:
    def __init__(self, name, columns):
        self.name = name
        self.columns = columns
        self.tiles = []       # images, row-major
        self.collide = set()  # tile ids with a full box
        self.wangsets = []    # (name, [(tileid, wangid)])
        self.empty_id = None

    def add(self, img, collide=False):
        self.tiles.append(img)
        tid = len(self.tiles) - 1
        if collide:
            self.collide.add(tid)
        return tid

    def add_set(self, name, shapes, collide):
        """shapes: (mask, image) list; one terrain set with the kind as colour 1 and 'none' as colour 2."""
        if self.empty_id is None:
            self.empty_id = self.add(Image.new("RGBA", (T, T), (0, 0, 0, 0)))
        entries = []
        for n, img in shapes:
            tid = self.add(img, collide)
            entries.append((tid, wang_id(n)))
        entries.append((self.empty_id, ",".join(["2"] * 8)))
        self.wangsets.append((name, entries))

    def write(self, out_dir, collide_variant=False):
        cols = self.columns
        rows = (len(self.tiles) + cols - 1) // cols
        sheet = Image.new("RGBA", (cols * T, rows * T), (0, 0, 0, 0))
        for i, img in enumerate(self.tiles):
            sheet.alpha_composite(img, ((i % cols) * T, (i // cols) * T))
        png = self.name + ".png"
        sheet.save(os.path.join(out_dir, png))
        self._write_tsx(out_dir, self.name, png, sheet.size, self.collide)
        if collide_variant:
            self._write_tsx(out_dir, self.name + "-collide", png, sheet.size, set(range(len(self.tiles))))
        return sheet

    def _write_tsx(self, out_dir, name, png, size, collide):
        lines = ['<?xml version="1.0" encoding="UTF-8"?>',
                 '<tileset version="1.10" tiledversion="1.11.2" name="%s" tilewidth="%d" tileheight="%d" tilecount="%d" columns="%d">'
                 % (name, T, T, len(self.tiles), self.columns),
                 ' <image source="%s" width="%d" height="%d"/>' % (png, size[0], size[1])]
        for tid in sorted(collide):
            lines += [' <tile id="%d">' % tid,
                      '  <objectgroup draworder="index" id="2">',
                      '   <object id="1" x="0" y="0" width="%d" height="%d"/>' % (T, T),
                      '  </objectgroup>', ' </tile>']
        if self.wangsets:
            lines.append(' <wangsets>')
            for sname, entries in self.wangsets:
                lines.append('  <wangset name="%s" type="mixed" tile="-1">' % sname)
                lines.append('   <wangcolor name="%s" color="#ff0000" tile="-1" probability="1"/>' % sname)
                lines.append('   <wangcolor name="none" color="#00ff00" tile="-1" probability="1"/>')
                for tid, wid in entries:
                    lines.append('   <wangtile tileid="%d" wangid="%s"/>' % (tid, wid))
                lines.append('  </wangset>')
            lines.append(' </wangsets>')
        lines.append('</tileset>')
        with open(os.path.join(out_dir, name + ".tsx"), "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(lines) + "\n")


# ---- sources ------------------------------------------------------------------------------------------------------
def xp_regions(atlas_path):
    """(name, XP image at 32 px) for every region of one of the plane's HD autotile atlases."""
    page, regions = read_regions(atlas_path)
    img = Image.open(os.path.join(os.path.dirname(atlas_path), page)).convert("RGBA")
    out = []
    for name, (x, y, w, h) in regions:
        out.append((name, img.crop((x, y, x + w, y + h))))
    return out


def read_regions(atlas_path):
    """A tiny .atlas reader: the first page and its regions in file order."""
    page = None
    regions = []
    name = None
    xy = None
    for raw in open(atlas_path, encoding="utf-8"):
        line = raw.rstrip("\n")
        if not line.strip():
            continue
        if line.endswith(".png") and not line.startswith(" "):
            page = page or line.strip()
            continue
        if not line.startswith(" ") and ":" not in line:
            name = line.strip()
            continue
        s = line.strip()
        if s.startswith("xy:"):
            xy = tuple(int(v) for v in s[3:].split(","))
        elif s.startswith("size:") and name and xy:
            w, h = (int(v) for v in s[5:].split(","))
            regions.append((name, (xy[0], xy[1], w, h)))
            name, xy = None, None
    return page, regions


def player_land(out_dir, previews):
    ts = Tileset("player_land", 48)
    ground = xp_regions(os.path.join(PLANE, "world", "tilesets", "player_terrain_hd.atlas"))
    structures = xp_regions(os.path.join(PLANE, "world", "structures", "player_structures_hd.atlas"))
    for name, xp32 in ground:
        xp = shrink(xp32, 2)
        ts.add_set("ground " + name, blob_tiles(xp, T), collide=False)
    for name, xp32 in structures:
        xp = shrink(xp32, 2)
        ts.add_set(name, blob_tiles(xp, T), collide=True)
    sheet = ts.write(out_dir)
    print("player_land: %d tiles, %d terrain sets, %s" % (len(ts.tiles), len(ts.wangsets), sheet.size))
    if previews:
        preview(out_dir, "player_land", [(n, shrink(x, 2)) for n, x in ground + structures])


def mv_a2(out_dir, art, sheet_name, ts_name, previews):
    src = Image.open(os.path.join(art, sheet_name)).convert("RGBA")
    ts = Tileset(ts_name, 48)
    kinds = []
    for row in range(src.height // 144):
        for col in range(src.width // 96):
            raw = src.crop((col * 96, row * 144, col * 96 + 96, row * 144 + 144))
            if not holds_art(raw):
                continue
            block = shrink(raw, 3)
            xp = vx_to_xp(block, T)
            kinds.append(("%s r%dc%d" % (ts_name, row, col), xp))
            ts.add_set("r%d c%d" % (row, col), blob_tiles(xp, T), collide=False)
    sheet = ts.write(out_dir, collide_variant=True)
    print("%s: %d tiles, %d terrain sets, %s" % (ts_name, len(ts.tiles), len(ts.wangsets), sheet.size))
    if previews:
        preview(out_dir, ts_name, kinds)


def mv_a1(out_dir, art, previews):
    """A1 (water and the like, animated): the first frame of each animated autotile - blocks at columns 0 and 4
    of every 2x3 row; the waterfall columns are left out."""
    src = Image.open(os.path.join(art, "Outside_A1.png")).convert("RGBA")
    ts = Tileset("mv_outside_a1", 48)
    kinds = []
    for row in range(src.height // 144):
        for col in (0, 4):
            raw = src.crop((col * 96, row * 144, col * 96 + 96, row * 144 + 144))
            if not holds_art(raw):
                continue
            block = shrink(raw, 3)
            xp = vx_to_xp(block, T)
            kinds.append(("a1 r%dc%d" % (row, col), xp))
            ts.add_set("r%d c%d" % (row, col), blob_tiles(xp, T), collide=False)
    sheet = ts.write(out_dir, collide_variant=True)
    print("mv_outside_a1: %d tiles, %d terrain sets, %s" % (len(ts.tiles), len(ts.wangsets), sheet.size))
    if previews:
        preview(out_dir, "mv_outside_a1", kinds)


def mv_a4(out_dir, art, previews):
    """A4: rows of roof autotiles (2x3, 144 px) alternating with wall blocks (2x2, 96 px)."""
    src = Image.open(os.path.join(art, "Outside_A4.png")).convert("RGBA")
    ts = Tileset("mv_outside_a4", 48)
    kinds = []
    y = 0
    band = 0
    while y < src.height:
        is_roof = band % 2 == 0
        h = 144 if is_roof else 96
        for col in range(src.width // 96):
            raw = src.crop((col * 96, y, col * 96 + 96, y + h))
            if not holds_art(raw):
                continue
            block = shrink(raw, 3)
            if is_roof:
                xp = vx_to_xp(block, T)
                kinds.append(("roof band%d c%d" % (band // 2, col), xp))
                ts.add_set("roof %d c%d" % (band // 2, col), blob_tiles(xp, T), collide=False)
            else:
                ts.add_set("wall %d c%d" % (band // 2, col), wall_tiles(block, T), collide=False)
        y += h
        band += 1
    sheet = ts.write(out_dir, collide_variant=True)
    print("mv_outside_a4: %d tiles, %d terrain sets, %s" % (len(ts.tiles), len(ts.wangsets), sheet.size))
    if previews:
        preview(out_dir, "mv_outside_a4", kinds)


def mv_plain(out_dir, art, sheet_name, ts_name, box=None):
    """A plain sheet (B/C/A5) as 16 px tiles in sheet order; box = the sheet inside a composite rip."""
    src = Image.open(os.path.join(art, sheet_name)).convert("RGBA")
    if box:
        src = src.crop(box)
    cols, rows = src.width // 48, src.height // 48
    ts = Tileset(ts_name, cols)
    for r in range(rows):
        for c in range(cols):
            ts.add(shrink(src.crop((c * 48, r * 48, c * 48 + 48, r * 48 + 48)), 3))
    sheet = ts.write(out_dir, collide_variant=True)
    print("%s: %d plain tiles, %s" % (ts_name, len(ts.tiles), sheet.size))


ROAD_BLOCK = (1, 2)  # Road.png block (row, col) - the cobbles on sand the user boxed in red
# Statues.png (round 342b) is the MV Dungeon composite rip (five sheets on teal, 2 px gaps): its B sheet, the one
# with the statues, sits at x 388, y 726 (A5 at x 2, C at x 1158).
STATUES_B = (388, 726, 1156, 1494)


def key_out_sand(block, yellow=40, fade=20):
    """The road block's sand is yellow (R - B about 78), the cobbles grey (R - B within +-20): pixels yellower
    than `yellow` go transparent, the band above it fades, so the road's edge keeps its stones and drops the sand."""
    out = block.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            d = r - b
            if d >= yellow + fade:
                px[x, y] = (0, 0, 0, 0)
            elif d > yellow:
                px[x, y] = (r, g, b, a * (yellow + fade - d) // fade)
    return out


def road(out_dir, art, previews):
    """One blob terrain set from the A2 block on Road.png the user picked, sand keyed out, 48 -> 16 px."""
    src = Image.open(os.path.join(art, "Road.png")).convert("RGBA")
    row, col = ROAD_BLOCK
    raw = key_out_sand(src.crop((col * 96, row * 144, col * 96 + 96, row * 144 + 144)))
    block = shrink(raw, 3)
    xp = vx_to_xp(block, T)
    ts = Tileset("road", 48)
    ts.add_set("road", blob_tiles(xp, T), collide=False)
    sheet = ts.write(out_dir)
    print("road: %d tiles, %d terrain set, %s" % (len(ts.tiles), len(ts.wangsets), sheet.size))
    if previews:
        preview(out_dir, "road", [("road", xp)])
    return xp


# ---- previews -----------------------------------------------------------------------------------------------------
DEMO = ["..###....",
        ".#####.#.",
        ".##.##...",
        "..###.##.",
        "....####.",
        ".##..##..",
        ".###.....",
        "..#......"]


def preview(out_dir, name, kinds):
    """Every kind painted on the DEMO shape, the way the game's autotile rule would draw it, at 4x."""
    grid = [[{"x"} if ch == "#" else set() for ch in row] for row in DEMO]
    w, h = len(DEMO[0]), len(DEMO)
    z = 4
    per = w * T * z + 8
    cols = 6
    rows = (len(kinds) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * per + 8, rows * (h * T * z + 24) + 8), (90, 90, 90, 255))
    d = ImageDraw.Draw(sheet)
    for i, (kname, xp) in enumerate(kinds):
        tile_img = Image.new("RGBA", (w * T, h * T), (0, 0, 0, 0))
        for y in range(h):
            for x in range(w):
                if "x" in grid[y][x]:
                    render.draw_tile(tile_img, xp, T, render.layer_mask(grid, x, y, "x"), x * T, y * T)
        big = tile_img.resize((w * T * z, h * T * z), Image.NEAREST)
        px, py = 8 + (i % cols) * per, 8 + (i // cols) * (h * T * z + 24)
        sheet.alpha_composite(big, (px, py))
        d.text((px, py + h * T * z + 4), kname, fill=(255, 255, 255, 255))
    sheet.save(os.path.join(out_dir, "preview_%s.png" % name))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--art", default=DEFAULT_ART)
    ap.add_argument("--preview", action="store_true")
    ap.add_argument("--only", default="", help="comma list: player,a1,a2,a4,a5,b,worlda2,worldb,worldc,walls,road,statues")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    only = set(a.only.split(",")) if a.only else None

    def want(key):
        return only is None or key in only

    if want("player"):
        player_land(a.out, a.preview)
    if want("a2"):
        mv_a2(a.out, a.art, "Outside_A2.png", "mv_outside_a2", a.preview)
    if want("worlda2"):
        mv_a2(a.out, a.art, "World_A2.png", "mv_world_a2", a.preview)
    if want("a1"):
        mv_a1(a.out, a.art, a.preview)
    if want("a4"):
        mv_a4(a.out, a.art, a.preview)
    if want("a5"):
        mv_plain(a.out, a.art, "Outside_A5.png", "mv_outside_a5")
    if want("b"):
        mv_plain(a.out, a.art, "Outside_B.png", "mv_outside_b")
    if want("worldb"):
        mv_plain(a.out, a.art, "World_B.png", "mv_world_b")
    if want("worldc"):
        mv_plain(a.out, a.art, "World_C.png", "mv_world_c")
    if want("walls"):
        mv_plain(a.out, a.art, "Walls.png", "mv_walls")
    if want("statues"):
        mv_plain(a.out, a.art, "Statues.png", "mv_statues", box=STATUES_B)
    if want("road"):
        road(a.out, a.art, a.preview)


if __name__ == "__main__":
    main()
