"""portrait_and_facing.py - two after-the-fact repairs for sheets made by art_convert_generic.py (round 245).

Both come from one assumption in the importer - "the first Idle frame faces right, and its middle is its face":

1. PORTRAITS.  When a source sheet has no painted portrait, the importer cuts the 64x64 `Avatar` cell from the
   CENTER square of the first Idle frame. For a creature much wider than tall that is its belly (user report: the
   Shellback Ankylosaur showed its hind legs on an Inn's standings page). `portraits` lists every importer-layout
   atlas whose Avatar is NOT an opaque painted portrait, lays them out as  now | first idle frame | LEFT-end square |
   RIGHT-end square , and with --apply repaints the Avatar cell of the ones named in PORTRAIT_FIX. Which end is the
   head is decided by LOOKING at the sheet: the "thicker end" guess it prints picks the Grim Executioner's axe, and a
   plain opacity test would replace two painted portraits (Talonborn Harpy, Ogre Matriarch) that have a soft edge.
   Applied in round 245 to the five in PORTRAIT_FIX.

2. FACING.  The engine draws an un-suffixed animation as facing RIGHT and mirrors it for Left (CharacterSprite), so a
   sheet whose frames face left walks backwards. `facing` shows  now | mirrored  for the slugs in FACES_LEFT and with
   --apply mirrors every animation frame inside its own atlas rectangle (frames are bottom-centered in uniform cells,
   so the alignment holds). The Avatar cell is left alone; no .atlas file changes.
   APPLIED in round 247 to the nine now listed in MIRRORED_R247 (user: "proceed with this fix"). --apply is NOT
   idempotent - a second run flips a sheet back - so FACES_LEFT is empty and `facing` refuses until a new slug is
   added to it.

usage:  python portrait_and_facing.py portraits [--apply] [--out sheet.png]
        python portrait_and_facing.py facing    [--apply] [--out sheet.png]
Run --apply only with the game closed and no package running; the plane folder ships wholesale."""
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PLANE = os.path.normpath(os.path.join(HERE, "..", "..", "forge-gui", "res", "adventure", "The Forsaken Realms"))
ROOT = os.path.join(PLANE, "sprites", "enemy")

# slug -> which end of the first Idle frame holds the head (round 245, from the contact sheet). The Ashen Spinewyrm
# was "left" when its portrait was cut in round 245; round 247 mirrored its frames, so its head is at the RIGHT end now
# (a re-run would cut the head again, as the mirror image of the round-245 portrait - never the tail).
PORTRAIT_FIX = {"shellback_ankylosaur": "right", "magmaback_crawler": "right", "mossback_dragon": "right",
                "skyreef_shark": "right", "ashen_spinewyrm": "right"}
# Sheets to mirror: head on the LEFT in the first Idle frame. EMPTY since round 247 - see MIRRORED_R247.
FACES_LEFT = []
# Mirrored in round 247 (from the round-245 contact sheet). Do NOT put them back in FACES_LEFT. Looked at again in
# round 247 and left alone: crimson_burrower, brood_spawn (three-quarter / frontal views). void_dragon,
# ironstride_construct and serpleg_stalker were checked in round 245 and face right.
MIRRORED_R247 = ["tyrant_rex", "stormcrag_griffin", "bonewhite_lizardragon", "ashen_spinewyrm", "shellplate_wyrm",
                 "megamouth_wyrm", "astral_wyrm", "furhorn_wyvern", "aurelian_dragon"]


def read_atlas(path):
    """-> (png name, [[region name, x, y, w, h], ...]) for a single-page libGDX atlas."""
    png, regions, name = None, [], None
    for raw in open(path, encoding="utf-8", errors="ignore"):
        line = raw.rstrip("\r\n")
        if not line.strip():
            continue
        if png is None:
            png = line.strip()
        elif not line.startswith(" ") and ":" not in line:
            name = line.strip()
            regions.append([name, None, None, None, None])
        elif name and line.strip().startswith("xy:"):
            regions[-1][1:3] = [int(v) for v in line.split(":")[1].split(",")]
        elif name and line.strip().startswith("size:") and regions[-1][1] is not None:
            regions[-1][3:5] = [int(v) for v in line.split(":")[1].split(",")]
    return png, [r for r in regions if r[1] is not None and r[3] is not None]


def importer_atlases():
    """Every atlas with the importer's signature: an Avatar of 64x64 at 0,0 and at least one Idle frame."""
    for folder, _dirs, files in os.walk(ROOT):
        for f in sorted(files):
            if not f.endswith(".atlas"):
                continue
            png, regions = read_atlas(os.path.join(folder, f))
            avatar = [r for r in regions if r[0] == "Avatar"]
            idle = [r for r in regions if r[0] == "Idle"]
            if png and avatar and idle and tuple(avatar[0][1:5]) == (0, 0, 64, 64) and os.path.exists(os.path.join(folder, png)):
                yield f[:-6], os.path.join(folder, png), regions, idle[0]


def thicker_end(frame, box):
    alpha = frame.split()[3].load()
    x0, y0, x1, y1 = box
    cols = [sum(1 for y in range(y0, y1) if alpha[x, y] > 40) for x in range(x0, x1)]
    third = max(1, len(cols) // 3)
    return "left" if sum(cols[:third]) > sum(cols[-third:]) else "right"


def end_square(frame, box, side_name):
    x0, y0, x1, y1 = box
    side = min(y1 - y0, x1 - x0)
    left = x0 if side_name == "left" else max(x0, x1 - side)
    return frame.crop((left, y0, left + side, y0 + side)).resize((64, 64), Image.LANCZOS)


def portraits(apply, out):
    rows = []
    for slug, img_path, _regions, i0 in importer_atlases():
        im = Image.open(img_path).convert("RGBA")
        current = im.crop((0, 0, 64, 64))
        opaque = sum(1 for a in current.split()[3].get_flattened_data() if a > 200) / 4096.0 \
            if hasattr(current.split()[3], "get_flattened_data") else sum(1 for a in current.split()[3].getdata() if a > 200) / 4096.0
        if opaque > 0.93:
            continue  # an opaque painted portrait
        frame = im.crop((i0[1], i0[2], i0[1] + i0[3], i0[2] + i0[4]))
        box = frame.getbbox()
        if box:
            rows.append((slug, img_path, current, frame, box, (box[2] - box[0]) / float(max(1, box[3] - box[1]))))
    rows.sort(key=lambda r: -r[5])
    sheet = Image.new("RGBA", (640, 100 * len(rows) + 24), (32, 32, 36, 255))
    draw = ImageDraw.Draw(sheet)
    draw.text((4, 4), "now          first idle frame                       LEFT end   RIGHT end   (green = PORTRAIT_FIX)", fill=(220, 220, 220, 255))
    for i, (slug, img_path, current, frame, box, ratio) in enumerate(rows):
        y = 24 + i * 100
        sheet.alpha_composite(current, (4, y + 4))
        thumb = frame.copy()
        thumb.thumbnail((200, 90), Image.LANCZOS)
        sheet.alpha_composite(thumb, (76, y + 4))
        for k, side_name in enumerate(("left", "right")):
            sheet.alpha_composite(end_square(frame, box, side_name), (284 + k * 72, y + 4))
            if PORTRAIT_FIX.get(slug) == side_name:
                draw.rectangle((282 + k * 72, y + 2, 349 + k * 72, y + 69), outline=(90, 220, 90, 255), width=2)
        draw.text((432, y + 8), slug[:30], fill=(235, 235, 235, 255))
        draw.text((432, y + 24), "w/h %.2f  thicker end: %s" % (ratio, thicker_end(frame, box)), fill=(170, 170, 170, 255))
        if apply and slug in PORTRAIT_FIX:
            im = Image.open(img_path).convert("RGBA")
            im.paste(Image.new("RGBA", (64, 64), (0, 0, 0, 0)), (0, 0))
            im.paste(end_square(frame, box, PORTRAIT_FIX[slug]), (0, 0))
            im.save(img_path)
            print("repainted the Avatar cell of " + os.path.relpath(img_path, PLANE))
    sheet.save(out)
    print("%d body-crop or soft-edged avatars listed -> %s" % (len(rows), out))


def facing(apply, out):
    assert FACES_LEFT, "FACES_LEFT is empty - the round-247 nine are already mirrored (MIRRORED_R247); a second --apply flips them back"
    twice = [s for s in FACES_LEFT if s in MIRRORED_R247]
    assert not twice, "already mirrored in round 247, a second --apply flips them back: %s" % twice
    found = {slug: (img_path, regions, i0) for slug, img_path, regions, i0 in importer_atlases() if slug in FACES_LEFT}
    missing = [s for s in FACES_LEFT if s not in found]
    assert not missing, "no importer-layout atlas for: %s" % missing
    sheet = Image.new("RGBA", (560, 150 * len(FACES_LEFT) + 24), (30, 30, 36, 255))
    draw = ImageDraw.Draw(sheet)
    draw.text((8, 6), "NOW (faces left - walks backwards)", fill=(235, 120, 120, 255))
    draw.text((288, 6), "MIRRORED (faces right, as the engine expects)", fill=(120, 235, 120, 255))
    for i, slug in enumerate(FACES_LEFT):
        img_path, regions, i0 = found[slug]
        im = Image.open(img_path).convert("RGBA")
        mirrored = im.copy()
        frames = 0
        for name, x, y, w, h in regions:
            if name == "Avatar":
                continue
            cell = im.crop((x, y, x + w, y + h))
            mirrored.paste(Image.new("RGBA", (w, h), (0, 0, 0, 0)), (x, y))
            mirrored.paste(cell.transpose(Image.FLIP_LEFT_RIGHT), (x, y))
            frames += 1
        y0 = 24 + i * 150
        for k, source in enumerate((im, mirrored)):
            fr = source.crop((i0[1], i0[2], i0[1] + i0[3], i0[2] + i0[4]))
            scale = min(250.0 / fr.size[0], 116.0 / fr.size[1], 3.0)
            fr = fr.resize((max(1, int(fr.size[0] * scale)), max(1, int(fr.size[1] * scale))), Image.LANCZOS)
            sheet.alpha_composite(fr, (8 + k * 280 + (250 - fr.size[0]) // 2, y0 + 4))
        draw.text((8, y0 + 128), "%s  (%d frames)" % (slug.replace("_", " ").title(), frames), fill=(235, 235, 235, 255))
        if apply:
            mirrored.save(img_path)
            print("mirrored %d frame(s) of %s" % (frames, os.path.relpath(img_path, PLANE)))
    sheet.save(out)
    print("%d sprites -> %s%s" % (len(FACES_LEFT), out, "" if apply else "  (preview only - nothing written)"))


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    do_apply = "--apply" in sys.argv
    target = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else os.path.join(HERE, mode + "_sheet.png")
    if mode == "portraits":
        portraits(do_apply, target)
    elif mode == "facing":
        facing(do_apply, target)
    else:
        print(__doc__)
