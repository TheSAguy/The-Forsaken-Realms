"""Round 411: build the notoriety Wall token card faces (488 x 680, like the plane's other custom card pictures).

Round 429: 24 faces - two walls (the second from 25 wins in a row) x three kinds x four levels, P/T 0/1, 1/2, 2/4, 3/6
(make_token_scripts.py names them). The second wall uses the same art; only its printed win count differs (25+..40+).

usage: python make_wall_cards.py --out <custom_card_pics folder> [--art <folder>] [--sheet <preview.png>]

--out   REQUIRED - where the <token script>.fullborder.png files go (forge-gui/res/adventure/common/custom_card_pics;
        ImageKeys finds a token's picture there since round 411).
--art   the user's art. Per kind: normal.png, reach.png, flying.png - or per size: normal_1.png .. flying_4.png, which
        win over the kind's single picture. Round 423: the user's own names work too - Regular-1.png .. Regular-4.png,
        Reach-1..4, Fly-1..4 (their 256 x 256 set lives OUTSIDE the repo, F:\\Art_to_Tweak\\WALL). Missing art falls back
        to a drawn placeholder.
--sheet a 4-wide preview of every face, for review before shipping.
--reuse-art  round 470: the bonus walls' art boxes come from their faces already in --out (the art folder was not at
        hand) - only the frame and the text are drawn again. With --bonus-only it touches only those two faces.
--preview-dir  round 470: write the faces to this folder instead of --out, to look before replacing.

Round 470: the two bonus walls (45+, 50+) print their extra enemy life after the why line (BONUS_LIFE_PERCENT).

Round 423 framing (frame_art). The art box is 408 x 300 and the user's art is square with a transparent sky, so every
picture stands on a backdrop for its kind: a banded stone-grey sky for the plain Wall, a green one with a ground band
for Reach, open sky with pixel clouds for Flying. A picture whose content runs edge to edge (a scene) covers the width
and loses the height it must from its own empty sky first, keeping a small margin over the tallest tower, then from the
bottom. A cut-out (content clear of the edges) is fitted whole with a margin - standing on the ground line for the
grounded kinds, centered in the sky for Flying. Enlarging is hard-edged at 3x and more; below that it is 2x hard-edged,
then smoothed down to size, which keeps pixel edges crisp at an uneven scale.
"""
import argparse
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
from make_token_scripts import BONUS_WALLS, LEVEL_PT, WALLS, script_name   # noqa: E402 - round 429: one naming for scripts and faces

W, H = 488, 680
ART_BOX = (40, 78, 448, 378)          # 408 x 300
KINDS = [("normal", "", []), ("reach", "reach", ["Reach"]), ("flying", "flying", ["Flying"])]
USER_NAMES = {"normal": "Regular", "reach": "Reach", "flying": "Fly"}   # round 423: the user's art file names
REMINDER = {
    "Defender": "(This creature can't attack.)",
    "Reach": "(This creature can block creatures with flying.)",
    "Flying": "(This creature can't be blocked except by creatures with flying or reach.)",
}
FONT_DIR = Path(r"C:\Windows\Fonts")


def font(name, size):
    for candidate in (name, "georgia.ttf", "arial.ttf"):
        p = FONT_DIR / candidate
        if p.exists():
            return ImageFont.truetype(str(p), size)
    return ImageFont.load_default()


# ---------------------------------------------------------------- placeholder art (pixel walls that grow with toughness)
def placeholder(kind, toughness):
    pw, ph = 68, 50                                      # drawn small, enlarged 6x with hard edges
    sky = {"normal": (92, 104, 128), "reach": (70, 98, 84), "flying": (120, 168, 214)}[kind]
    img = Image.new("RGB", (pw, ph), sky)
    d = ImageDraw.Draw(img)
    ground_y = 41 if kind != "flying" else 40
    if kind != "flying":
        d.rectangle([0, ground_y, pw, ph], fill=(70, 60, 48))
    rows = 2 + toughness * 2                             # 0/1 low, 0/4 tall
    brick_h = 3
    wall_w = 34
    x0 = (pw - wall_w) // 2
    top = ground_y - rows * brick_h
    if kind == "flying":
        top -= 2
        ground_y -= 2
    for r in range(rows):
        y = top + r * brick_h
        offset = 0 if r % 2 == 0 else 3
        for bx in range(x0 - offset, x0 + wall_w, 6):
            a = max(bx, x0)
            b = min(bx + 5, x0 + wall_w - 1)
            if a <= b:
                shade = 150 + ((r * 7 + bx) % 3) * 12
                d.rectangle([a, y, b, y + brick_h - 2], fill=(shade, shade - 6, shade - 16))
        d.line([x0, y + brick_h - 1, x0 + wall_w - 1, y + brick_h - 1], fill=(88, 84, 80))
    for cx in range(x0, x0 + wall_w, 6):                 # crenellations
        d.rectangle([cx, top - 3, cx + 3, top - 1], fill=(160, 156, 146))
    if kind == "reach":                                  # vines climbing and reaching up
        for vx in (x0 + 4, x0 + 15, x0 + 27):
            for y in range(ground_y, top - 6, -1):
                d.point((vx + (1 if (y // 3) % 2 else 0), y), fill=(60, 150, 70))
            d.point((vx - 1, top - 6), fill=(90, 190, 90))
            d.point((vx + 2, top - 7), fill=(90, 190, 90))
    if kind == "flying":                                 # wings either side, clouds below
        mid = top + (rows * brick_h) // 2
        for side in (-1, 1):
            base = x0 - 1 if side < 0 else x0 + wall_w
            for i in range(12):
                length = 12 - i
                y = mid - 6 + i
                xs = base + side * 1
                xe = base + side * length
                d.line([min(xs, xe), y, max(xs, xe), y], fill=(246, 246, 250) if i % 3 else (214, 220, 232))
        for cx, cy in ((12, 44), (30, 46), (52, 44)):
            d.ellipse([cx - 7, cy - 3, cx + 7, cy + 3], fill=(236, 240, 248))
    return img


SKY = {  # round 423 backdrops: top of the sky, horizon
    "normal": ((92, 112, 146), (190, 198, 210)),
    "reach": ((74, 104, 92), (168, 186, 150)),
    "flying": ((96, 150, 214), (206, 228, 248)),
}
GROUND = {"normal": (86, 78, 62), "reach": (58, 76, 44), "flying": None}
TOP_MARGIN = 14   # px kept over the tallest tower when a scene is cropped


def backdrop(kind, bw, bh):
    """A banded sky drawn at quarter size and enlarged hard-edged, a ground band for the grounded kinds, clouds for flying."""
    w, h = bw // 4, bh // 4
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    top, horizon = SKY[kind]
    bands = 9
    for i in range(bands):
        t = i / (bands - 1)
        d.rectangle([0, round(h * i / bands), w, round(h * (i + 1) / bands)],
                    fill=tuple(round(top[k] + (horizon[k] - top[k]) * t) for k in range(3)))
    if GROUND[kind]:
        g = GROUND[kind]
        gy = round(h * 0.80)
        d.rectangle([0, gy, w, h], fill=g)
        d.rectangle([0, gy, w, gy], fill=tuple(min(255, v + 18) for v in g))
    else:
        for cx, cy, r in ((14, 56, 9), (50, 62, 12), (88, 54, 10), (30, 18, 6), (78, 14, 7)):
            d.ellipse([cx - r, cy - r // 2, cx + r, cy + r // 2], fill=(240, 244, 250))
            d.ellipse([cx - r // 2, cy - r // 2 - 3, cx + r // 2, cy + r // 2 - 3], fill=(248, 250, 253))
    return img.resize((bw, bh), Image.NEAREST)


def enlarge(img, size):
    """Hard-edged at 3x and more; below that 2x hard-edged then smoothed down - crisp pixel edges at an uneven scale."""
    if size[0] >= img.width * 3:
        return img.resize(size, Image.NEAREST)
    if size[0] <= img.width:
        return img.resize(size, Image.LANCZOS)
    return img.resize((img.width * 2, img.height * 2), Image.NEAREST).resize(size, Image.LANCZOS)


def frame_art(img, kind):
    """Round 423: the picture on its kind's backdrop, framed for the 408 x 300 box (see the module docstring)."""
    bw, bh = ART_BOX[2] - ART_BOX[0], ART_BOX[3] - ART_BOX[1]
    img = img.convert("RGBA")
    box = img.getchannel("A").point(lambda v: 255 if v > 24 else 0).getbbox() or (0, 0, img.width, img.height)
    x0, y0, x1, y1 = box
    canvas = backdrop(kind, bw, bh).convert("RGBA")
    if x0 <= 2 and x1 >= img.width - 2:   # a scene: cover the width
        s = bw / img.width
        h = max(bh, round(img.height * s))
        need = h - bh
        top = max(0, min(round(y0 * s) - TOP_MARGIN, need))
        canvas.alpha_composite(enlarge(img, (bw, h)).crop((0, top, bw, top + bh)))
    else:                                  # a cut-out: fit it whole
        cw, ch = x1 - x0, y1 - y0
        s = min(bw * 0.92 / cw, bh * 0.90 / ch)
        art = enlarge(img.crop(box), (max(1, round(cw * s)), max(1, round(ch * s))))
        px = (bw - art.width) // 2
        py = (bh - art.height) // 2 if kind == "flying" else min(bh - art.height, round(bh * 0.93) - art.height)
        canvas.alpha_composite(art, (px, max(0, py)))
    return canvas.convert("RGB")


# ---------------------------------------------------------------- the frame
def wrap(draw, text, fnt, width):
    words, lines, line = text.split(), [], ""
    for w in words:
        trial = (line + " " + w).strip()
        if draw.textlength(trial, font=fnt) <= width:
            line = trial
        else:
            lines.append(line)
            line = w
    if line:
        lines.append(line)
    return lines


WINS_PER_LEVEL = 5   # settings.json notorietyWinsPerLevel
LEVELS_PER_WALL = 4  # settings.json notorietyLevelsPerWall
BONUS_LIFE_PERCENT = (25, 25)   # settings.json notorietyBonusWallLifePercent - each bonus wall's +life, in BONUS_WALLS order


def life_line(index):
    """Round 470 (the user: "Print the +life on the 45+ and 50+ Wall cards"): the bonus wall's extra enemy life - its own
    share, and from the second on the total so far."""
    pct = BONUS_LIFE_PERCENT[index]
    total = sum(BONUS_LIFE_PERCENT[:index + 1])
    if index == 0:
        return f"Your foe also starts with +{pct}% life."
    return f"Your foe also starts with +{pct}% more life (+{total}% in all)."


def why_line(wall, level, wins=None):
    """Round 411b (the user: "Add to the Card a text line. Saying why it's on the field"): the card's own threshold.
    Round 429: by level, not toughness (2/4 and 3/6 broke "toughness x 5"), and the second wall counts on from 25.
    Round 445: a bonus wall passes its own count (45, 50)."""
    if wins is None:
        wins = (wall * LEVELS_PER_WALL + level) * WINS_PER_LEVEL
    return f"Notoriety: you have won {wins}+ duels in a row, and word has spread."


LEAD = "Notoriety:"   # round 417 (the user: "put the Notoriety text in bold") - the card is the only notice now


PT_BOX = (W - 128, 590)   # the P/T box's left edge and top - text on a line reaching below its top stays left of it


def draw_why(d, text, x0, y, width):
    """The why line in italic with its LEAD word bold italic, word-wrapped across both fonts. Returns the next y.
    Round 470: a word starting with "+" is bold too (the bonus walls' +life), and a line that reaches the P/T box's
    height wraps short of it."""
    italic, bold = font("palai.ttf", 18), font("palabi.ttf", 18)
    space = d.textlength(" ", font=italic)
    x = x0
    for i, word in enumerate(text.split()):
        f = bold if (i == 0 and word == LEAD) or word.startswith("+") or word.startswith("(+") else italic
        w = d.textlength(word, font=f)
        limit = x0 + width if y + 21 <= PT_BOX[1] else PT_BOX[0] - 8
        if x > x0 and x + w > limit:
            x, y = x0, y + 21
        d.text((x, y), word, font=f, fill=(40, 40, 46) if f is italic else (16, 16, 20))
        x += w + space
    return y + 21


def card(art, kind, keywords, wall, level, wins=None, extra=None, framed=None):
    """extra: round 470 - a sentence after the why line (a bonus wall's +life). framed: round 470 - an art box already
    framed (408 x 300, cut from an existing face by --reuse-art), pasted as it is."""
    img = Image.new("RGB", (W, H), (16, 16, 18))
    d = ImageDraw.Draw(img)
    # silver artifact frame
    d.rounded_rectangle([14, 14, W - 15, H - 15], radius=18, fill=(150, 154, 160))
    for i in range(6):
        c = 172 - i * 6
        d.rounded_rectangle([20 + i, 20 + i, W - 21 - i, H - 21 - i], radius=14, outline=(c, c + 2, c + 6))
    # name bar
    d.rounded_rectangle([30, 30, W - 31, 70], radius=10, fill=(214, 216, 220), outline=(90, 92, 98), width=2)
    d.text((44, 36), "Wall", font=font("palab.ttf", 27), fill=(20, 20, 22))
    # art
    img.paste(framed if framed is not None else frame_art(art, kind), ART_BOX[:2])
    d.rectangle([ART_BOX[0] - 2, ART_BOX[1] - 2, ART_BOX[2] + 1, ART_BOX[3] + 1], outline=(60, 62, 66), width=2)
    # type line
    d.rounded_rectangle([30, 388, W - 31, 424], radius=9, fill=(214, 216, 220), outline=(90, 92, 98), width=2)
    d.text((44, 394), "Token Artifact Creature \u2014 Wall", font=font("palab.ttf", 21), fill=(20, 20, 22))
    # rules box
    d.rectangle([40, 432, W - 41, 612], fill=(232, 230, 224), outline=(110, 110, 112), width=2)
    y = 444
    body, italic = font("pala.ttf", 21), font("palai.ttf", 17)
    for kw in ["Defender"] + keywords:
        d.text((54, y), kw, font=body, fill=(18, 18, 18))
        x = 54 + d.textlength(kw + " ", font=body)
        lines = wrap(d, REMINDER[kw], italic, W - 41 - 14 - x)
        first = True
        for line in lines:
            d.text((x if first else 54, y + 3), line, font=italic, fill=(60, 60, 60))
            y += 22 if first else 20
            first = False
        y += 10
    # why it is on the field - a rule, then italic like flavor text, kept clear of the P/T box
    d.line([70, y, W - 71, y], fill=(150, 150, 150), width=1)
    y += 8
    y = draw_why(d, why_line(wall, level, wins) + (" " + extra if extra else ""), 54, y, W - 41 - 14 - 54)
    # power / toughness
    d.rounded_rectangle([W - 128, 590, W - 34, 636], radius=10, fill=(214, 216, 220), outline=(70, 72, 78), width=2)
    pt = "%d/%d" % LEVEL_PT[level]
    f = font("palab.ttf", 30)
    d.text((W - 81 - d.textlength(pt, font=f) / 2, 595), pt, font=f, fill=(16, 16, 18))
    d.text((36, H - 40), "The Forsaken Realms \u2022 Notoriety", font=font("pala.ttf", 13), fill=(40, 40, 44))
    return img


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True)
    ap.add_argument("--art")
    ap.add_argument("--sheet")
    ap.add_argument("--bonus-only", action="store_true", help="round 445: only the bonus walls' faces")
    ap.add_argument("--reuse-art", action="store_true",
                    help="round 470: take each bonus wall's art box from its face already in --out (when --art is not at "
                         "hand) - only the frame and the text are drawn again")
    ap.add_argument("--preview-dir", help="round 470: write the faces here instead of --out (--out is still read by "
                                          "--reuse-art)")
    args = ap.parse_args()
    out = Path(args.out)
    if not out.is_dir():
        sys.exit(f"--out is not a folder: {out}")
    dest = Path(args.preview_dir) if args.preview_dir else out
    dest.mkdir(parents=True, exist_ok=True)
    art_dir = Path(args.art) if args.art else None
    made = []
    for index, (script, wins) in enumerate(BONUS_WALLS):   # round 445: the flying 3/6 (Fly-4 art) with its own win count
        art, source, framed = None, "placeholder", None
        if art_dir:
            for candidate in (art_dir / "Fly-4.png", art_dir / "flying_4.png", art_dir / "flying.png"):
                if candidate.exists():
                    art, source = Image.open(candidate), candidate.name
                    break
        if art is None and args.reuse_art and (out / f"{script}.fullborder.png").exists():
            framed = Image.open(out / f"{script}.fullborder.png").convert("RGB").crop(ART_BOX)
            source = f"the art box of the existing {script}.fullborder.png"
        if art is None and framed is None:
            art = placeholder("flying", 4)
        face = card(art, "flying", ["Flying"], 0, 4, wins, extra=life_line(index), framed=framed)
        face.save(dest / f"{script}.fullborder.png")
        made.append(face)
        print(f"{script}.fullborder.png  <- {source}")
    for wall in range(0 if args.bonus_only else len(WALLS)):   # round 429: the second wall - same art, its own win counts
        for kind, word, keywords in KINDS:
            for level in range(1, 5):
                art, source = None, "placeholder"
                if art_dir:
                    user = USER_NAMES[kind]   # round 423: the user's own file names, e.g. Fly-3.png
                    for candidate in (art_dir / f"{user}-{level}.png", art_dir / f"{kind}_{level}.png", art_dir / f"{kind}.png"):
                        if candidate.exists():
                            art, source = Image.open(candidate), candidate.name
                            break
                if art is None:
                    art = placeholder(kind, level)
                script = script_name(wall, word, level)
                face = card(art, kind, keywords, wall, level)
                face.save(dest / f"{script}.fullborder.png")
                made.append(face)
                print(f"{script}.fullborder.png  <- {source}")
    if args.sheet:
        rows = (len(made) + 3) // 4
        sheet = Image.new("RGB", (W * 4 // 2 + 30, rows * (H // 2 + 10) + 10), (36, 36, 40))
        for i, face in enumerate(made):
            small = face.resize((W // 2, H // 2), Image.LANCZOS)
            sheet.paste(small, (10 + (i % 4) * (W // 2 + 4), 10 + (i // 4) * (H // 2 + 10)))
        sheet.save(args.sheet)
        print("sheet:", args.sheet)


if __name__ == "__main__":
    main()
