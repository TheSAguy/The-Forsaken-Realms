#!/usr/bin/env python
"""poi_footprints.py - where the solid art sits inside a dungeon entrance's atlas region (round 594).

The user: "The magnifying glass was very far from this dungeon" - Demon's Bargain, whose altar casts a soft shadow
over the left 14 px of its 48 px region. MapSprite drew the unvisited magnifier at the region's left edge (and the
bookmark star at its right edge), so on art with a wide shadow or empty margin the marker stood off in the grass.

This writes an optional `footprint: x, y, w, h` field (the round-578 field name; region px, y down) on the regions of
maps/tileset/dungeon_entrances_2.atlas whose solid art - alpha >= SOLID, so a soft cast shadow does not count - starts
or ends MIN_INSET px or more in from the region's sides. Config.getPOIFootprint() reads it and PointOfInterestMapSprite
puts both markers at the solid art's own edges. A region without the field draws its markers as before. Stock atlases
under adventure/common are never written.

usage (repo root):
  python dev-tools/poi_footprints.py           dry run: the regions that would get / lose / change the field
  python dev-tools/poi_footprints.py --write   write / refresh / remove the field
  python dev-tools/poi_footprints.py --check   exit 1 when the atlas needs --write (run after any entrance install)
"""
import os
import re
import sys

from PIL import Image

SOLID = 200
MIN_INSET = 4
ATLAS = os.path.join("forge-gui", "res", "adventure", "The Forsaken Realms", "maps", "tileset", "dungeon_entrances_2.atlas")


def read_pages(text):
    """[[head lines], [[name, {field: value}], ...]] per page; every field kept, in the order read."""
    pages = []
    for block in re.split(r"\n\s*\n", text.strip("\n")):
        head, regions, cur = [], [], None
        for ln in block.split("\n"):
            if cur is None and not regions and (ln.endswith(".png") or (":" in ln and not ln.startswith(" "))):
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
            for k in f:
                lines.append("  %s: %s" % (k, f[k]))
        out.append("\n".join(lines))
    return "\n\n".join(out) + "\n"


def footprint(page, f):
    x, y = [int(t) for t in f["xy"].split(",")]
    w, h = [int(t) for t in f["size"].split(",")]
    cell = page.crop((x, y, x + w, y + h))
    bb = cell.getchannel("A").point(lambda v: 255 if v >= SOLID else 0).getbbox()
    if not bb or (bb[0] < MIN_INSET and w - bb[2] < MIN_INSET):
        return None
    return "%d, %d, %d, %d" % (bb[0], bb[1], bb[2] - bb[0], bb[3] - bb[1])


def main():
    write, check = "--write" in sys.argv, "--check" in sys.argv
    text = open(ATLAS, encoding="utf-8").read()
    pages = read_pages(text)
    assert write_pages(pages) == text.replace("\r\n", "\n").strip("\n") + "\n", "the atlas does not round-trip"
    changes = []
    for head, regions in pages:
        page = Image.open(os.path.join(os.path.dirname(ATLAS), head[0].strip())).convert("RGBA")
        for name, f in regions:
            if f.get("rotate", "false") != "false":
                continue
            want = footprint(page, f)
            have = f.get("footprint")
            if want == have:
                continue
            changes.append("%-40s %s -> %s" % (name, have, want))
            if want is None:
                del f["footprint"]
            else:
                f["footprint"] = want
    print("\n".join(changes) if changes else "no region needs a change")
    print("%d region(s) %s" % (len(changes), "written" if write and changes else "would change"))
    if write and changes:
        open(ATLAS, "w", encoding="utf-8", newline="\n").write(write_pages(pages))
    if check and changes:
        sys.exit(1)


if __name__ == "__main__":
    main()
