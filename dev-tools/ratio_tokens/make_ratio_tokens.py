"""Round 620: the win-ratio help tokens - four Clue / Treasure copies whose card says why they are on the battlefield (the
user: "make copies of these cards, so we can add text to them, so the player knows why they are on the battlefield (same
as we do for the walls)").

Writes the token scripts (forge-gui/res/tokenscripts/<name>.txt) and their faces
(forge-gui/res/adventure/common/custom_card_pics/<name>.fullborder.png, 488 x 680 - ImageKeys finds a token's picture
there, as for the notoriety Walls). The faces are the real token cards the user picked - AFR's Treasure (Dan Scott) and
OTJ's Clue (Clint Lockwood) - with two lines added under the rules text: the line the user asked for, and the reason.

usage: python make_ratio_tokens.py --treasure <AFR Treasure 488x680> --clue <OTJ Clue picture> --repo <TFR repo root>
       [--preview <png>]   (--repo is required: nothing is written without it; --preview alone only draws the sheet)
"""
import argparse
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

W, H = 488, 680
ENEMY = "\"You're a formidable opponent - I need some help.\""
PLAYER = "\"We see you need a little help.\""
TOKENS = {
    # name: (kind, line, reason)
    "tfr_ratio_enemy_clue": ("clue", ENEMY, "Your last 50 duels: 75% or more won"),
    "tfr_ratio_enemy_treasure": ("treasure", ENEMY, "Your last 50 duels: 90% or more won"),
    "tfr_ratio_player_clue": ("clue", PLAYER, "Your last 50 duels: 25% or fewer won"),
    "tfr_ratio_player_treasure": ("treasure", PLAYER, "Your last 50 duels: 50% or fewer won"),
}
RULES = {
    "clue": ("Clue Token", "Artifact Clue",
             "A:AB$ Draw | Cost$ 2 Sac<1/CARDNAME/this token> | NumCards$ 1 | SpellDescription$ Draw a card.\nSVar:SacMe:1",
             "{2}, Sacrifice this token: Draw a card."),
    "treasure": ("Treasure Token", "Artifact Treasure",
                 "A:AB$ Mana | Cost$ T Sac<1/CARDNAME/this token> | Produced$ Any | Amount$ 1 | SpellDescription$ Add one "
                 "mana of any color.",
                 "{T}, Sacrifice this token: Add one mana of any color."),
}
# the empty lower part of each face's text box (488 x 680 coordinates), under the printed rules text
TEXT_AREA = {"clue": (40, 591, 448, 629), "treasure": (40, 591, 448, 629)}
INK = (25, 22, 20)
FONT_DIR = Path("C:/Windows/Fonts")


def load_clue(path):
    """The user's OTJ Clue: a screenshot with a page margin and a white "text here" box - cropped to the card, scaled to
    488 x 680, the box painted over in the text box's own color."""
    im = Image.open(path).convert("RGB")
    diff = ImageChops.difference(im, Image.new("RGB", im.size, (255, 255, 255))).convert("L")
    card = im.crop(diff.point(lambda v: 255 if v > 40 else 0).getbbox()).resize((W, H), Image.LANCZOS)
    return card


def box_color(face, area):
    """The text box's color, from a clean strip at the area's left end."""
    x0, y0, x1, y1 = area
    strip = face.crop((x0 + 4, y0 + 4, x0 + 50, y1 - 4))
    pixels = sorted(strip.getdata(), key=sum)
    return pixels[len(pixels) // 2]


def draw_face(base, kind, line, reason):
    face = base.copy()
    area = TEXT_AREA[kind]
    d = ImageDraw.Draw(face)
    # the clean box: the clue picture's "text here" overlay goes, the treasure's empty space stays as printed
    d.rectangle(area, fill=box_color(face, area))
    italic = ImageFont.truetype(str(FONT_DIR / "georgiai.ttf"), 15)
    small = ImageFont.truetype(str(FONT_DIR / "georgia.ttf"), 12)
    x0, y0, x1, y1 = area
    d.text((x0 + 8, y0 + 2), line, font=italic, fill=INK)
    d.text((x0 + 8, y0 + 21), reason, font=small, fill=(70, 64, 58))
    return face


def script(name, kind, line, reason):
    title, types, abilities, oracle = RULES[kind]
    return (f"Name:{title}\nManaCost:no cost\nTypes:{types}\n{abilities}\n"
            f"Oracle:{oracle}\\n{line} ({reason}.)\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--treasure", required=True)
    ap.add_argument("--clue", required=True)
    ap.add_argument("--repo")
    ap.add_argument("--preview")
    a = ap.parse_args()
    bases = {"treasure": Image.open(a.treasure).convert("RGB").resize((W, H), Image.LANCZOS), "clue": load_clue(a.clue)}
    faces = {name: draw_face(bases[k], k, line, why) for name, (k, line, why) in TOKENS.items()}
    if a.preview:
        sheet = Image.new("RGB", (W * 4 + 30, H), (40, 40, 40))
        for i, face in enumerate(faces.values()):
            sheet.paste(face, (i * (W + 10), 0))
        sheet.save(a.preview)
        print("preview", a.preview)
    if a.repo:
        repo = Path(a.repo)
        pics = repo / "forge-gui/res/adventure/common/custom_card_pics"
        scripts = repo / "forge-gui/res/tokenscripts"
        assert pics.is_dir() and scripts.is_dir(), "not a TFR repo root"
        for name, (k, line, why) in TOKENS.items():
            faces[name].save(pics / f"{name}.fullborder.png")
            (scripts / f"{name}.txt").write_text(script(name, k, line, why), encoding="utf-8", newline="\n")
            print("wrote", name)


if __name__ == "__main__":
    main()
