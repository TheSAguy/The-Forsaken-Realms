"""facing709.py - round 709: Urabrask and Jin-Gitaxias stop walking backwards (2026-10-22).

Round 681 left four sprites unjudged: Spinecoil Isopod, and the stock Cerodon, Urabrask and Jin-Gitaxias. Each was laid
out frame by frame at 8x and judged by eye:

  Spinecoil Isopod   faces right (eyed head and feelers right, spiny tail trailing left) - unchanged
  Cerodon            faces right (frilled head right, rump left) - unchanged
  Urabrask           faces LEFT in Idle, Attack and Death (snout, claws and the fire sweep point left)
  Jin-Gitaxias       faces LEFT in Idle, Walk, Attack and Death (head, eyes and claws left, cloak trailing right)

Both left-facing sprites are stock (forge-gui/res/adventure/common). Each is copied into the plane at the same relative
path, where the game looks first, and every animation frame (not the Avatar) is flipped inside its own atlas rectangle,
as round 681 did for Gev. The .atlas is copied unchanged; the upstream files are never edited.

Gev: round 681's plane gecko is mixed (see GEV below); its left-facing frames are flipped here.

Not idempotent (a second mirror flips a sheet back), so --apply refuses once facing709_done.json exists.
usage:  python facing709.py --preview <dir>     before / after pages, nothing written in the repo
        python facing709.py --apply             write the plane copies
Run --apply only with the game closed and no package running."""
import json
import os
import re
import shutil
import sys

from PIL import Image

from facing681 import COMMON, PLANE, page_of

PLANE_COPY = [
    "sprites/enemy/aberration/phyrexian/urabrask.atlas",
    "sprites/enemy/aberration/phyrexian/jin-gitaxias.atlas",
]
# Gev's plane gecko (round 681) came out mixed: facing681.regions wants no trailing spaces, and the stock gecko.atlas
# has them, so only Walk 0-2 were flipped. Idle 0-3, Walk 3 and Death 0-2 still faced left. (animation, frame indexes)
GEV = ("sprites/enemy/beast/smallmammals/gecko.atlas", {"Idle": (0, 1, 2, 3), "Walk": (3,), "Death": (0, 1, 2)})
LEDGER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "facing709_done.json")


def regions(atlas_text):
    """[(name, x, y, w, h)]; unlike facing681.regions it allows the trailing spaces these stock atlases carry."""
    return [(m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5))) for m in
            re.finditer(r"^(\w+)[ \t]*\n\s+xy: *(\d+), *(\d+)[ \t]*\n\s+size: *(\d+), *(\d+)", atlas_text, re.M)]


def mirrored(atlas_path, only=None):
    """Every frame but the Avatar flipped in its own cell; `only` = {animation: frame indexes} limits it."""
    text = open(atlas_path, encoding="utf-8").read()
    im = Image.open(page_of(atlas_path, text)).convert("RGBA")
    out = im.copy()
    n = 0
    seen = {}
    for name, x, y, w, h in regions(text):
        i = seen[name] = seen.get(name, -1) + 1
        if name == "Avatar" or (only is not None and i not in only.get(name, ())):
            continue
        cell = im.crop((x, y, x + w, y + h))
        out.paste(Image.new("RGBA", (w, h)), (x, y))
        out.paste(cell.transpose(Image.FLIP_LEFT_RIGHT), (x, y))
        n += 1
    return out, n


def main():
    apply = "--apply" in sys.argv
    out = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    if apply and os.path.exists(LEDGER):
        raise SystemExit("facing709_done.json exists: round 709 is applied; a second --apply flips the sheets back")
    rel, only = GEV
    gev = os.path.join(PLANE, rel)
    after, n = mirrored(gev, only)
    if out:
        text = open(gev, encoding="utf-8").read()
        Image.open(page_of(gev, text)).save(os.path.join(out, "gev_before.png"))
        after.save(os.path.join(out, "gev_after.png"))
    if apply:
        after.save(page_of(gev, open(gev, encoding="utf-8").read()))
        print("Gev: %d frame(s) of the plane gecko mirrored" % n)
    for rel in PLANE_COPY:
        src, dst = os.path.join(COMMON, rel), os.path.join(PLANE, rel)
        text = open(src, encoding="utf-8").read()
        after, n = mirrored(src)
        if out:
            name = os.path.basename(rel)[:-6]
            Image.open(page_of(src, text)).save(os.path.join(out, name + "_before.png"))
            after.save(os.path.join(out, name + "_after.png"))
        if apply:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(src, dst)
            after.save(page_of(dst, text))
            print("plane copy of %s, %d frame(s) mirrored" % (rel, n))
    if apply:
        json.dump({"plane_copy": PLANE_COPY, "gev": [GEV[0], {k: list(v) for k, v in GEV[1].items()}]},
                  open(LEDGER, "w", encoding="utf-8", newline="\n"), indent=1)


if __name__ == "__main__":
    main()
