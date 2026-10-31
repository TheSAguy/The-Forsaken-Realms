#!/usr/bin/env python
"""
bake_avatars.py - round 897: the Realm Journal's avatar sheet.

Every enemy has its own atlas (about 2,100, 6 GB of textures), and loading one to draw a 16-px face loads all of it. This
bakes each atlas's first Avatar region onto a few 2048 pages, read by util/EnemyAvatars.java:

    <plane>/sprites/enemy_avatars/avatars_<n>.png     cells of 64 x 64
    <plane>/sprites/enemy_avatars/avatars.index       atlas path <tab> page <tab> x <tab> y <tab> w <tab> h

Usage, from the repo root:
    python dev-tools/enemy_avatars/bake_avatars.py            bake the sheet
    python dev-tools/enemy_avatars/bake_avatars.py --audit    list stale, missing and extra faces; exit 1 if any

A stale face is harmless in the game, so the audit is a check before packaging, not a guard. Run the bake after new
enemy art. An atlas with no Avatar region gets no cell; the game falls back on the enemy's own sprite.
"""
import json
import os
import sys

from PIL import Image

PLANE = "forge-gui/res/adventure/The Forsaken Realms/"
COMMON = "forge-gui/res/adventure/common/"
OUT = PLANE + "sprites/enemy_avatars/"
PAGE, CELL = 2048, 64
PER_PAGE = (PAGE // CELL) ** 2


def resolve(path):
    for root in (PLANE, COMMON):
        if os.path.exists(root + path):
            return root + path
    return None


def read_avatar(atlas_path):
    """(page file, x, y, w, h) of the lowest-index Avatar region, or None."""
    page, best, region = None, None, None
    fields = {}

    def close():
        nonlocal best
        if region == "Avatar" and "xy" in fields and "size" in fields:
            index = int(fields.get("index", "-1"))
            if best is None or index < best[0]:
                x, y = [int(v) for v in fields["xy"].split(",")]
                w, h = [int(v) for v in fields["size"].split(",")]
                best = (index, page, x, y, w, h, fields.get("rotate", "false") == "true")

    with open(atlas_path, encoding="utf-8") as f:
        lines = [line.rstrip("\r\n") for line in f]
    for i, line in enumerate(lines):
        if not line.strip():
            close()
            region, page, fields = None, None, {}
        elif ":" in line and (line[0] in " \t" or region is None):
            key, value = line.split(":", 1)
            fields[key.strip()] = value.strip()
        elif line.endswith((".png", ".PNG")):
            page = line.strip()
        else:
            close()
            region, fields = line.strip(), {}
    close()
    return best[1:] if best else None


def face(atlas_path):
    """The Avatar's pixels as an RGBA image no larger than a cell, or None."""
    found = read_avatar(atlas_path)
    if not found:
        return None
    page, x, y, w, h, rotated = found
    png = os.path.join(os.path.dirname(atlas_path), page)
    with Image.open(png) as sheet:
        im = sheet.convert("RGBA").crop((x, y, x + (h if rotated else w), y + (w if rotated else h)))
    if rotated:
        im = im.transpose(Image.Transpose.ROTATE_90)
    if im.width > CELL or im.height > CELL:
        scale = CELL / max(im.width, im.height)
        im = im.resize((max(1, round(im.width * scale)), max(1, round(im.height * scale))), Image.Resampling.NEAREST)
    return im


def sprites():
    enemies = json.load(open(PLANE + "world/enemies.json", encoding="utf-8"))
    return sorted({e["sprite"] for e in enemies if e.get("sprite")})


def read_index():
    rows = {}
    with open(OUT + "avatars.index", encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) == 6:
                rows[p[0]] = tuple(int(v) for v in p[1:])
    return rows


def audit():
    rows = read_index()
    pages = {}
    problems = 0
    wanted = sprites()
    for path in wanted:
        real = resolve(path)
        im = face(real) if real else None
        if im is None:
            if path in rows:
                print("extra (the atlas has no Avatar now):", path)
                problems += 1
            continue
        if path not in rows:
            print("missing:", path)
            problems += 1
            continue
        n, x, y, w, h = rows[path]
        page = pages.setdefault(n, Image.open(OUT + "avatars_%d.png" % n).convert("RGBA"))
        if page.crop((x, y, x + w, y + h)).tobytes() != im.tobytes():
            print("stale:", path)
            problems += 1
    for path in rows:
        if path not in wanted:
            print("extra (no enemy uses it):", path)
            problems += 1
    print("%d faces, %d problem(s)" % (len(rows), problems))
    return 1 if problems else 0


def bake():
    os.makedirs(OUT, exist_ok=True)
    for name in os.listdir(OUT):
        os.remove(OUT + name)
    rows, none = [], []
    page, count = None, 0
    pages = 0

    def flush():
        nonlocal page, pages
        if page is not None:
            page.save(OUT + "avatars_%d.png" % pages, optimize=True)
            pages += 1
            page = None

    for path in sprites():
        real = resolve(path)
        im = face(real) if real else None
        if im is None:
            none.append(path)
            continue
        if count == PER_PAGE:
            flush()
            count = 0
        if page is None:
            page = Image.new("RGBA", (PAGE, PAGE), (0, 0, 0, 0))
        per_row = PAGE // CELL
        x, y = (count % per_row) * CELL, (count // per_row) * CELL
        page.paste(im, (x, y))
        rows.append("%s\t%d\t%d\t%d\t%d\t%d" % (path, pages, x, y, im.width, im.height))
        count += 1
    flush()
    with open(OUT + "avatars.index", "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(rows) + "\n")
    print("%d faces on %d page(s); %d atlas(es) with no Avatar: %s" % (len(rows), pages, len(none), none[:10]))
    return 0


if __name__ == "__main__":
    sys.exit(audit() if "--audit" in sys.argv else bake())
