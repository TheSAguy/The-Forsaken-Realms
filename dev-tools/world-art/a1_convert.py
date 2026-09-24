"""Round 303: water-type autotiles from the MV World sheet's A1 quadrant (frame 0 of each animated block) -> XP at
32 px, like a2_convert.py. Writes a1/<name>.png and a1_catalog.png."""
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import a2_convert as a2  # noqa: E402

import paths  # noqa: E402

SRC = os.path.join(paths.ART, "PC _ Computer - RPG Maker MV - Tilesets - World.png")
BLOCKS = {"sea": (0, 0), "poison": (384, 0), "deep sea": (0, 144), "lava": (384, 144), "lake": (0, 288),
          "pit": (384, 288), "ice water": (0, 432), "clouds": (384, 432), "sea rocks": (288, 288), "decor rocks": (288, 0)}


def main():
    sheet = Image.open(SRC).convert("RGBA").crop((2, 2, 770, 578))
    os.makedirs(os.path.join(HERE, "autotiles", "a1"), exist_ok=True)
    cat = Image.new("RGB", (len(BLOCKS) * 202, 280), (40, 40, 40))
    d = ImageDraw.Draw(cat)
    for i, (name, (x, y)) in enumerate(BLOCKS.items()):
        xp = a2.vx_to_xp(a2.downscale(sheet.crop((x, y, x + 96, y + 144))))
        xp.save(os.path.join(HERE, "autotiles", "a1", name.replace(" ", "_") + ".png"))
        big = xp.resize((192, 256), Image.NEAREST)
        bg = Image.new("RGBA", big.size, (104, 138, 72, 255))
        bg.alpha_composite(big)
        cat.paste(bg.convert("RGB"), (i * 202, 20))
        d.text((i * 202 + 2, 4), name, fill=(255, 255, 0))
    cat.save(os.path.join(paths.OUT, "a1_catalog.png"))
    print("ok")


if __name__ == "__main__":
    main()
