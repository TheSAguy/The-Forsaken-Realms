"""Round 398: the user's dedicated Inn and Armory ruins -> two plane atlases.

The user's sheets (C:/Users/User/Pictures/Screenshots/Inn Ruins.png, Armory Ruins.png) are 256x256 pages of 32x32
cells, some empty. Each sheet is copied into the plane as-is and every non-empty cell becomes one indexed region, in
reading order - so a cell redrawn in place only needs this script run again.

    python install_ruin_art.py <plane root>      e.g. "C:/TFR/repo/forge-gui/res/adventure/The Forsaken Realms"

Writes maps/tileset/inn_broken.png/.atlas (InnBroken) and maps/tileset/armory_broken.png/.atlas (ArmoryBroken),
read by TownRestoration.getInnRuinSprite() / getArmoryRuinSprite().
"""
import os
import shutil
import sys

from PIL import Image

SRC = r"C:/Users/User/Pictures/Screenshots"
SHEETS = [("Inn Ruins.png", "inn_broken", "InnBroken"), ("Armory Ruins.png", "armory_broken", "ArmoryBroken")]
CELL = 32


def main(plane):
    out_dir = os.path.join(plane, "maps", "tileset")
    if not os.path.isdir(out_dir):
        sys.exit("not a plane root (no maps/tileset): " + plane)
    for source, base, region in SHEETS:
        im = Image.open(os.path.join(SRC, source)).convert("RGBA")
        if im.width % CELL or im.height % CELL:
            sys.exit(source + ": not a whole number of 32 px cells")
        cells = []
        for row in range(im.height // CELL):
            for col in range(im.width // CELL):
                x, y = col * CELL, row * CELL
                if im.crop((x, y, x + CELL, y + CELL)).getbbox():
                    cells.append((x, y))
        shutil.copyfile(os.path.join(SRC, source), os.path.join(out_dir, base + ".png"))
        lines = [base + ".png", "size: %d,%d" % im.size, "format: RGBA8888", "filter: Nearest,Nearest", "repeat: none"]
        for i, (x, y) in enumerate(cells):
            lines += [region, "  xy: %d, %d" % (x, y), "  size: %d, %d" % (CELL, CELL), "  index: %d" % i]
        with open(os.path.join(out_dir, base + ".atlas"), "w", newline="\n") as f:
            f.write("\n".join(lines) + "\n")
        print("%s: %d ruins -> maps/tileset/%s.atlas (%s)" % (source, len(cells), base, region))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
