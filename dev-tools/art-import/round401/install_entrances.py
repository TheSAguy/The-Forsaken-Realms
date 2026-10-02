"""Round 401: every dungeon entrance reviewed against the user's new art (Pictures\\Screenshots\\Update_2), 2026-10-02.

plan401.json (beside this file) is the user's decision on a numbered proposal page (place numbers 1-350): one entry
per place that changes - its POI name, the picture it had (atlas + region), the new art (a cell of one of the user's
PixelLab sheets, or a white-backed single keyed out), how big it may be, and what becomes of the old picture:
  category "reuse" - the user: "dungeons that we can re-use in the future, but proceed with change"
  category "low"   - the user: "change and keep those entrances as low probability to re-use"
Sizes (the user: "keep the size of the icons uniform, 32x32, unless special location or tower where it does not make
sense"): fit "32" - a 32x32 region, the art standing on its bottom edge; "tower" - 32 wide, up to 48 tall; "special"
(castles, side-boss lairs, the Vampire Castle beside its two 48 px siblings) - up to 48; "native" - the art as drawn
(the two Eldrazi places). Bigger art is scaled down smoothly with a hard alpha edge.

Each new picture gets its own region on page 1 of dungeon_entrances_2 (a strip below what is there) and the place -
by its unique POI name - points at it. An old picture that lived in this atlas and no place uses any more is renamed
Spare_<region> (reuse) or SpareLow_<region> (low); one in another atlas stays where it is. Every old picture is also
exported to Pictures\\Screenshots\\Art_to_Tweak\\Entrance_Spares\\{Reuse,Low} and listed in spares401.csv.
Page 2 of the atlas is common/maps/tileset/buildings.png and is never written.
Usage: python install_entrances.py <repo root> [--dry]"""
import csv
import json
import os
import re
import sys

import numpy as np
from PIL import Image

if len(sys.argv) < 2 or sys.argv[1].startswith("-"):
    raise SystemExit(__doc__)
ROOT = sys.argv[1].rstrip("/\\")
DRY = "--dry" in sys.argv
RES = os.path.join(ROOT, "forge-gui", "res", "adventure")
PLANE = os.path.join(RES, "The Forsaken Realms")
TILESET = os.path.join(PLANE, "maps", "tileset")
ATLAS = os.path.join(TILESET, "dungeon_entrances_2.atlas")
PNG = os.path.join(TILESET, "dungeon_entrances_2.png")
POIS = os.path.join(PLANE, "world", "points_of_interest.json")
NEW_ATLAS_REF = "../The Forsaken Realms/maps/tileset/dungeon_entrances_2.atlas"
SRC = r"C:\Users\User\Pictures\Screenshots\Update_2"
SPARES = r"C:\Users\User\Pictures\Screenshots\Art_to_Tweak\Entrance_Spares"
HERE = os.path.dirname(os.path.abspath(__file__))
PLAN = json.load(open(os.path.join(HERE, "plan401.json"), encoding="utf-8"))
FITS = {"32": (32, 32), "tower": (32, 48), "special": (48, 48), "native": (999, 999)}


def trim(img):
    bb = img.getchannel("A").point(lambda v: 255 if v >= 1 else 0).getbbox()
    return img.crop(bb) if bb else img


def key_white(img):
    """A white-backed single: the white connected to the edges becomes transparent, enclosed highlights stay."""
    img = img.convert("RGBA")
    px = img.load()
    w, h = img.size
    seen = set()
    stack = [(x, y) for x in range(w) for y in (0, h - 1)] + [(x, y) for y in range(h) for x in (0, w - 1)]
    while stack:
        x, y = stack.pop()
        if (x, y) in seen or not (0 <= x < w and 0 <= y < h):
            continue
        r, g, b, _ = px[x, y]
        if min(r, g, b) < 235:
            continue
        seen.add((x, y))
        px[x, y] = (0, 0, 0, 0)
        stack += [(x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)]
    return img


def source(entry):
    s = entry["src"]
    if "file" in s:
        img = Image.open(os.path.join(SRC, s["file"]))
        return key_white(img) if s.get("key") == "white" else img.convert("RGBA")
    x, y, w, h = s["box"]
    return Image.open(os.path.join(SRC, s["sheet"])).convert("RGBA").crop((x, y, x + w, y + h))


def icon(entry):
    art = trim(source(entry))
    W, H = FITS[entry["fit"]]
    s = min(W / art.width, H / art.height)
    if s < 1:
        art = art.convert("RGBa").resize((max(1, round(art.width * s)), max(1, round(art.height * s))),
                                         Image.LANCZOS).convert("RGBA")
        a = np.asarray(art).copy()
        a[a[..., 3] < 40] = 0
        a[a[..., 3] >= 40, 3] = 255
        art = trim(Image.fromarray(a))
    if art.width <= 32 and art.height <= 32:   # uniform: a full 32x32 region, the art standing on its bottom edge
        cell = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
        cell.alpha_composite(art, ((32 - art.width) // 2, 32 - art.height))
        return cell
    return art


# ---------------------------------------------------------------- the atlas, page by page (round 391's reader)
def read_pages(text):
    pages = []
    for block in re.split(r"\n\s*\n", text.strip("\n")):
        head, regions, cur = [], [], None
        for ln in block.split("\n"):
            if cur is None and (not regions) and (ln.endswith(".png") or ":" in ln and not ln.startswith(" ")):
                head.append(ln)
                continue
            if not ln.startswith(" "):
                cur = [ln, {}]
                regions.append(cur)
            else:
                k, v = [s.strip() for s in ln.split(":", 1)]
                cur[1][k] = v
        pages.append([head, regions])
    return pages


def write_pages(pages):
    out = []
    for head, regions in pages:
        lines = list(head)
        for name, f in regions:
            lines.append(name)
            for k in ("rotate", "xy", "size", "orig", "offset", "index"):
                if k in f:
                    lines.append("  %s: %s" % (k, f[k]))
        out.append("\n".join(lines))
    return "\n\n".join(out) + "\n"


def parse_any_atlas(path):
    """name -> (page image, x, y, w, h) of the lowest-index region, for exporting an old picture from any atlas."""
    regs, page, cur = {}, None, None
    base = os.path.dirname(path)
    for ln in open(path, encoding="utf-8").read().splitlines():
        s = ln.strip()
        if not s:
            page = cur = None
            continue
        if page is None and s.lower().endswith(".png"):
            page = os.path.normpath(os.path.join(base, s))
            continue
        if ":" not in s:
            cur = {"page": page, "index": -1}
            regs.setdefault(s, []).append(cur)
            continue
        k, v = [t.strip() for t in s.split(":", 1)]
        if cur is None:
            continue
        if k == "xy":
            cur["xy"] = [int(t) for t in v.split(",")]
        elif k == "size" and "xy" in cur:
            cur["size"] = [int(t) for t in v.split(",")]
        elif k == "bounds":
            b = [int(t) for t in v.split(",")]
            cur["xy"], cur["size"] = b[:2], b[2:]
        elif k == "index":
            cur["index"] = int(v)
    return {n: sorted(r, key=lambda e: e["index"])[0] for n, r in regs.items()}


text = open(ATLAS, encoding="utf-8").read()
pages = read_pages(text)
assert write_pages(pages) == text.strip("\n") + "\n", "the atlas does not round-trip - every field must be kept"
assert pages[0][0][0] == "dungeon_entrances_2.png", pages[0][0]
page1 = Image.open(PNG).convert("RGBA")
names = {}
for pi, (_, regions) in enumerate(pages):
    for r in regions:
        assert r[0] not in names, "two regions named " + r[0]
        names[r[0]] = (pi, r)

pois_text = open(POIS, encoding="utf-8").read()
pois = json.loads(pois_text)
by_name = {p["name"]: p for p in pois}
log, strip, spare_rows = [], [], []
atlas_cache = {}

for e in PLAN:
    p = by_name[e["name"]]
    assert (p.get("spriteAtlas") or "").endswith("/" + e["atlas"]) and p.get("sprite") == e["region"], \
        "%s no longer shows %s/%s - re-run the review" % (e["name"], e["atlas"], e["region"])
    base = "E_" + re.sub(r"[^A-Za-z0-9]+", "_", e["name"]).strip("_")
    new = base if base not in names else base + "_401"
    assert new not in names, new
    reg = [new, {"rotate": "false", "xy": "0, 0", "size": "0, 0", "orig": "0, 0", "offset": "0, 0", "index": "-1"}]
    pages[0][1].append(reg)
    names[new] = (0, reg)
    img = icon(e)
    strip.append((new, img, reg))
    e["_new"] = new
    log.append("%3d %-26s %-8s %-6s %-8s %dx%d -> %s" % (e["n"], e["display"][:26], e["art"], e["category"], e["fit"],
                                                        img.width, img.height, new))

# re-point each place by its own (unique) name
for e in PLAN:
    pat = re.compile(r'(\{\s*"name":\s*' + re.escape(json.dumps(e["name"], ensure_ascii=False)) + r',.*?\n\t\})', re.S)
    m = pat.search(pois_text)
    assert m, e["name"]
    block = m.group(1)
    nb = re.sub(r'"spriteAtlas":\s*"[^"]*"', '"spriteAtlas": "' + NEW_ATLAS_REF + '"', block, count=1)
    nb = re.sub(r'"sprite":\s*"[^"]*"', '"sprite": "' + e["_new"] + '"', nb, count=1)
    assert nb != block, e["name"]
    pois_text = pois_text.replace(block, nb, 1)
new_pois = json.loads(pois_text)

# the old pictures: exported, listed, and - in this atlas, when nothing uses them now - renamed as spares
still_used = {(os.path.basename(p.get("spriteAtlas") or ""), p.get("sprite")) for p in new_pois}
renamed = {}
for e in PLAN:
    folder = "Reuse" if e["category"] == "reuse" else "Low"
    atlas_path = None
    for dirpath in (os.path.join(PLANE, "sprites"), TILESET, os.path.join(RES, "common", "maps", "tileset"),
                    os.path.join(RES, "common", "maps", "tileset", "phyrexia")):   # the plane first, then common
        cand = os.path.join(dirpath, e["atlas"])
        if os.path.exists(cand):
            atlas_path = cand
            break
    kept_as = e["region"]
    if e["atlas"] == "dungeon_entrances_2.atlas" and (e["atlas"], e["region"]) not in still_used:
        if e["region"] in renamed:
            kept_as = renamed[e["region"]]
        else:
            prefix = "Spare_" if e["category"] == "reuse" else "SpareLow_"
            kept_as = prefix + e["region"]
            if kept_as in names:   # round 391 kept a spare of this very region already
                kept_as += "_r401"
            assert kept_as not in names, kept_as
            pi, reg = names.pop(e["region"])
            reg[0] = kept_as
            names[kept_as] = (pi, reg)
            renamed[e["region"]] = kept_as
    out_png = ""
    if atlas_path:
        regs = atlas_cache.setdefault(atlas_path, parse_any_atlas(atlas_path))
        r = regs.get(e["region"])
        if r:
            x, y = r["xy"]
            w, h = r["size"]
            old = Image.open(r["page"]).convert("RGBA").crop((x, y, x + w, y + h))
            out_png = os.path.join(SPARES, folder, "%03d_%s.png" % (e["n"], re.sub(r"[^A-Za-z0-9]+", "_", e["display"])))
            if not DRY:
                os.makedirs(os.path.dirname(out_png), exist_ok=True)
                old.save(out_png)
    spare_rows.append([e["n"], e["display"], e["category"], e["atlas"], kept_as, out_png])

# pack the strip below page 1: rows across its width, 2 px apart, tallest last so rows stay even
W = page1.width
x = y = row_h = 0
placed = []
for name, img, reg in sorted(strip, key=lambda s: (s[1].height, s[0])):
    if x + img.width > W:
        x, y, row_h = 0, y + row_h + 2, 0
    placed.append((name, img, reg, x, y))
    x += img.width + 2
    row_h = max(row_h, img.height)
H0 = page1.height
out = Image.new("RGBA", (W, H0 + y + row_h + 2), (0, 0, 0, 0))
out.paste(page1, (0, 0))
for name, img, reg, px, py in placed:
    out.alpha_composite(img, (px, H0 + 2 + py))
    reg[1]["xy"] = "%d, %d" % (px, H0 + 2 + py)
    reg[1]["size"] = reg[1]["orig"] = "%d, %d" % img.size
pages[0][0] = [ln if not ln.startswith("size:") else "size: %d,%d" % out.size for ln in pages[0][0]]

print("\n".join(log))
print("%d places re-pointed, %d old regions renamed as spares, page 1 %s -> %s"
      % (len(PLAN), len(renamed), page1.size, out.size))
if DRY:
    print("(dry run - nothing written)")
    sys.exit(0)
out.save(PNG)
open(ATLAS, "w", encoding="utf-8", newline="\n").write(write_pages(pages))
open(POIS, "w", encoding="utf-8", newline="").write(pois_text)
with open(os.path.join(HERE, "spares401.csv"), "w", encoding="utf-8", newline="") as f:
    w = csv.writer(f)
    w.writerow(["place", "name", "category", "atlas", "old picture's region now", "exported to"])
    w.writerows(spare_rows)
print("written")
