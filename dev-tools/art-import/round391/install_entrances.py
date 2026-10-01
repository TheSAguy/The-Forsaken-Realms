"""Round 391: the Front View dungeon entrances replaced (the user's review of a numbered proposal, 2026-10-01).

plan391.json (beside this file) holds the decided plan, one entry per Front View place (Pictures\\Screenshots\\
Art_to_Tweak\\Front_View\\front_view_list.csv numbering):
  kind "pixellab" - a 3/4-view cave cut from one of the user's PixelLab sheets in F:\\Art_to_Tweak\\Update (sheet +
                    pixel box); written into the place's own E_ region (32x32, centred, standing on the cell's bottom)
  kind "hmm3"     - an HMM3 adventure-map object from F:\\Art_to_Tweak\\Update\\HMM3, palette-keyed (cyan transparent,
                    magenta/pink -> a soft shadow), trimmed and scaled to at most 48 px; appended to page 1 of
                    dungeon_entrances_2 and the place re-pointed (an E_ region takes the new xy/size, any other atlas
                    gets a new E_<place> region and every POI on that atlas+region is re-pointed)
  kind "keep"     - unchanged
  "spare": true   - the original picture (if it lives in this atlas) is kept as a region "Spare_<region>" that no place
                    uses yet, so it can be given to a place later (the user liked these originals too)
Page 2 of the atlas is common/maps/tileset/buildings.png and is never written: an E_ region on it that changes moves to
page 1 under the same name.
Usage: python install_entrances.py <repo root> [--dry]"""
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
PLANE = os.path.join(ROOT, "forge-gui", "res", "adventure", "The Forsaken Realms")
TILESET = os.path.join(PLANE, "maps", "tileset")
ATLAS = os.path.join(TILESET, "dungeon_entrances_2.atlas")
PNG = os.path.join(TILESET, "dungeon_entrances_2.png")
POIS = os.path.join(PLANE, "world", "points_of_interest.json")
NEW_ATLAS_REF = "../The Forsaken Realms/maps/tileset/dungeon_entrances_2.atlas"
SRC = r"F:\Art_to_Tweak\Update"
HMM3 = os.path.join(SRC, "HMM3")
HMM3_MAX = 48
HERE = os.path.dirname(os.path.abspath(__file__))
PLAN = json.load(open(os.path.join(HERE, "plan391.json"), encoding="utf-8"))


def hmm3_key(path, shadow=0.45, edge=0.25):
    a = np.asarray(Image.open(path).convert("RGB")).astype(int)
    out = np.zeros(a.shape[:2] + (4,), np.uint8)
    out[..., :3] = a
    out[..., 3] = 255
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    clear = ((r == 0) & (g == 255) & (b == 255)) | ((r == 255) & (g == 255) & (b == 0)) \
        | ((r == 180) & (g == 0) & (b == 255)) | ((r == 0) & (g == 255) & (b == 0))
    out[clear] = (0, 0, 0, 0)
    out[(r == 255) & (g == 0) & (b == 255)] = (0, 0, 0, int(255 * shadow))
    out[(r == 255) & (g == 150) & (b == 255)] = (0, 0, 0, int(255 * edge))
    return Image.fromarray(out)


def trim(img, thr=1):
    bb = img.getchannel("A").point(lambda v: 255 if v >= thr else 0).getbbox()
    return img.crop(bb) if bb else img


def hmm3_icon(rel):
    im = trim(hmm3_key(os.path.join(HMM3, rel)))
    s = HMM3_MAX / max(im.size)
    if s < 1:
        im = im.convert("RGBa").resize((max(1, round(im.width * s)), max(1, round(im.height * s))),
                                       Image.LANCZOS).convert("RGBA")
    a = np.asarray(im).copy()
    a[a[..., 3] < 20] = 0
    return trim(Image.fromarray(a))


def pixellab_cell(entry):
    x0, y0, x1, y1 = entry["box"]
    icon = Image.open(os.path.join(SRC, entry["sheet"])).convert("RGBA").crop((x0, y0, x1, y1))
    cell = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    cell.alpha_composite(icon, ((32 - icon.width) // 2, 32 - icon.height))
    return cell


# ---------------------------------------------------------------- the atlas, page by page
def read_pages(text):
    pages = []
    for block in re.split(r"\n\s*\n", text.strip("\n")):
        lines = block.split("\n")
        head, regions, cur = [], [], None
        for ln in lines:
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

strip = []          # (name, image) appended below page 1
writes = []         # (x, y, image) into page 1 in place
repoint = []        # (atlas tail, region, new region name)
log = []

page_imgs = {0: page1}
for pi in range(1, len(pages)):
    page_imgs[pi] = Image.open(os.path.normpath(os.path.join(TILESET, pages[pi][0][0]))).convert("RGBA")
for e in PLAN:
    # The user: "the original is also okay to use" / "also okay, to use again" - an original in this atlas is kept as
    # a spare region (no place uses it), copied before anything is overwritten. Originals in other atlases stay there.
    if e.get("spare") and e["kind"] != "keep" and e["atlas"].endswith("dungeon_entrances_2.atlas"):
        pi, reg = names[e["region"]]
        x0, y0 = [int(v) for v in reg[1]["xy"].split(",")]
        w0, h0 = [int(v) for v in reg[1]["size"].split(",")]
        spare = "Spare_" + e["region"]
        sreg = [spare, {"rotate": "false", "xy": "0, 0", "size": "0, 0", "orig": "0, 0", "offset": "0, 0", "index": "-1"}]
        pages[0][1].append(sreg)
        names[spare] = (0, sreg)
        strip.append((spare, page_imgs[pi].crop((x0, y0, x0 + w0, y0 + h0)), sreg))
        log.append("%3d %-22s original kept as %s" % (e["n"], e["region"], spare))
for e in PLAN:
    if e["kind"] == "keep":
        continue
    img = pixellab_cell(e) if e["kind"] == "pixellab" else hmm3_icon(e["src"])
    own = e["atlas"].endswith("dungeon_entrances_2.atlas")
    if own:
        pi, reg = names[e["region"]]
        w, h = [int(v) for v in reg[1]["size"].split(",")]
        if pi == 0 and img.size == (w, h):
            x, y = [int(v) for v in reg[1]["xy"].split(",")]
            writes.append((x, y, img))
            log.append("%3d %-22s in place" % (e["n"], e["region"]))
        else:
            if pi != 0:
                pages[pi][1].remove(reg)
                pages[0][1].append(reg)
            strip.append((e["region"], img, reg))
            log.append("%3d %-22s moved to the new strip%s" % (e["n"], e["region"], " (off page 2)" if pi else ""))
    else:
        base = "E_" + re.sub(r"[^A-Za-z0-9]+", "_", e["region"] + "_" + e["names"]).strip("_")
        assert base not in names, base
        reg = [base, {"rotate": "false", "xy": "0, 0", "size": "0, 0", "orig": "0, 0", "offset": "0, 0", "index": "-1"}]
        pages[0][1].append(reg)
        names[base] = (0, reg)
        strip.append((base, img, reg))
        repoint.append((e["atlas"].split("/")[-1], e["region"], base, e["names"]))
        log.append("%3d %-22s -> new region %s" % (e["n"], e["region"], base))

# pack the strip: rows across page 1's width, 2 px apart
W = page1.width
x = y = 0
row_h = 0
placed = []
for name, img, reg in strip:
    if x + img.width > W:
        x, y, row_h = 0, y + row_h + 2, 0
    placed.append((name, img, reg, x, y))
    x += img.width + 2
    row_h = max(row_h, img.height)
strip_h = (y + row_h) if strip else 0
H0 = page1.height
out = Image.new("RGBA", (W, H0 + strip_h + 2), (0, 0, 0, 0))
out.paste(page1, (0, 0))
for x0, y0, img in writes:
    out.paste(Image.new("RGBA", img.size, (0, 0, 0, 0)), (x0, y0))
    out.alpha_composite(img, (x0, y0))
for name, img, reg, px, py in placed:
    out.alpha_composite(img, (px, H0 + 2 + py))
    reg[1]["xy"] = "%d, %d" % (px, H0 + 2 + py)
    reg[1]["size"] = reg[1]["orig"] = "%d, %d" % img.size
pages[0][0] = [ln if not ln.startswith("size:") else "size: %d,%d" % out.size for ln in pages[0][0]]

# re-point the POIs on a moved atlas+region
pois_text = open(POIS, encoding="utf-8").read()
pois = json.loads(pois_text)
moved = 0
for atlas_tail, region, new, label in repoint:
    hits = [p for p in pois if p.get("sprite") == region and (p.get("spriteAtlas") or "").endswith("/" + atlas_tail)]
    assert hits, (atlas_tail, region, label)
    for p in hits:
        pat = re.compile(r'(\{\s*"name":\s*' + re.escape(json.dumps(p["name"], ensure_ascii=False)) + r',.*?\n\t\})', re.S)
        m = pat.search(pois_text)
        assert m, p["name"]
        block = m.group(1)
        nb = re.sub(r'"spriteAtlas":\s*"[^"]*"', '"spriteAtlas": "' + NEW_ATLAS_REF + '"', block, count=1)
        nb = re.sub(r'"sprite":\s*"[^"]*"', '"sprite": "' + new + '"', nb, count=1)
        assert nb != block, p["name"]
        pois_text = pois_text.replace(block, nb, 1)
        moved += 1
        log.append("    POI %-24s -> %s" % (p["name"], new))
json.loads(pois_text)

print("\n".join(log))
print("in place %d, new strip %d (page 1 %s -> %s), POIs re-pointed %d" % (len(writes), len(placed), page1.size,
                                                                          out.size, moved))
if DRY:
    print("(dry run - nothing written)")
    sys.exit(0)
out.save(PNG)
open(ATLAS, "w", encoding="utf-8", newline="\n").write(write_pages(pages))
open(POIS, "w", encoding="utf-8", newline="").write(pois_text)
print("written")
