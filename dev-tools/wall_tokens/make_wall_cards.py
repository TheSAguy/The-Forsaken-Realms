"""Round 411: build the 12 notoriety Wall token card faces (488 x 680, like the plane's other custom card pictures).

usage: python make_wall_cards.py --out <custom_card_pics folder> [--art <folder>] [--sheet <preview.png>]

--out   REQUIRED - where the <token script>.fullborder.png files go (forge-gui/res/adventure/common/custom_card_pics;
        ImageKeys finds a token's picture there since round 411).
--art   the user's art. Per kind: normal.png, reach.png, flying.png - or per size: normal_1.png .. flying_4.png, which
        win over the kind's single picture. Any size; landscape ~4:3 fits the art box best (408 x 300). Small pictures
        (pixel art) are enlarged with hard edges, big ones smoothly. Missing art falls back to a drawn placeholder.
--sheet a 6 x 2 preview of all twelve, for review before shipping.
"""
import argparse
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

W, H = 488, 680
ART_BOX = (40, 78, 448, 378)          # 408 x 300
KINDS = [("normal", "", []), ("reach", "reach", ["Reach"]), ("flying", "flying", ["Flying"])]
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


def fit_art(img):
    bw, bh = ART_BOX[2] - ART_BOX[0], ART_BOX[3] - ART_BOX[1]
    scale = max(bw / img.width, bh / img.height)
    method = Image.NEAREST if img.width <= bw // 2 else Image.LANCZOS
    big = img.convert("RGB").resize((max(bw, round(img.width * scale)), max(bh, round(img.height * scale))), method)
    left = (big.width - bw) // 2
    top = (big.height - bh) // 2
    return big.crop((left, top, left + bw, top + bh))


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


WINS_PER_LEVEL = 5   # settings.json notorietyWinsPerLevel - the 0/N wall stands from N x this many wins in a row


def why_line(toughness):
    """Round 411b (the user: "Add to the Card a text line. Saying why it's on the field"): the card's own threshold."""
    return f"Notoriety: you have won {toughness * WINS_PER_LEVEL}+ duels in a row, and word has spread."


LEAD = "Notoriety:"   # round 417 (the user: "put the Notoriety text in bold") - the card is the only notice now


def draw_why(d, text, x0, y, width):
    """The why line in italic with its LEAD word bold italic, word-wrapped across both fonts. Returns the next y."""
    italic, bold = font("palai.ttf", 18), font("palabi.ttf", 18)
    space = d.textlength(" ", font=italic)
    x = x0
    for i, word in enumerate(text.split()):
        f = bold if i == 0 and word == LEAD else italic
        w = d.textlength(word, font=f)
        if x > x0 and x + w > x0 + width:
            x, y = x0, y + 21
        d.text((x, y), word, font=f, fill=(40, 40, 46) if f is italic else (16, 16, 20))
        x += w + space
    return y + 21


def card(art, keywords, toughness):
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
    img.paste(fit_art(art), ART_BOX[:2])
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
    y = draw_why(d, why_line(toughness), 54, y, W - 41 - 14 - 54)
    # power / toughness
    d.rounded_rectangle([W - 128, 590, W - 34, 636], radius=10, fill=(214, 216, 220), outline=(70, 72, 78), width=2)
    pt = f"0/{toughness}"
    f = font("palab.ttf", 30)
    d.text((W - 81 - d.textlength(pt, font=f) / 2, 595), pt, font=f, fill=(16, 16, 18))
    d.text((36, H - 40), "The Forsaken Realms \u2022 Notoriety", font=font("pala.ttf", 13), fill=(40, 40, 44))
    return img


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True)
    ap.add_argument("--art")
    ap.add_argument("--sheet")
    args = ap.parse_args()
    out = Path(args.out)
    if not out.is_dir():
        sys.exit(f"--out is not a folder: {out}")
    art_dir = Path(args.art) if args.art else None
    made = []
    for kind, word, keywords in KINDS:
        for t in range(1, 5):
            art, source = None, "placeholder"
            if art_dir:
                for candidate in (art_dir / f"{kind}_{t}.png", art_dir / f"{kind}.png"):
                    if candidate.exists():
                        art, source = Image.open(candidate), candidate.name
                        break
            if art is None:
                art = placeholder(kind, t)
            script = f"tfr_wall_{word + '_' if word else ''}0_{t}"
            face = card(art, keywords, t)
            face.save(out / f"{script}.fullborder.png")
            made.append(face)
            print(f"{script}.fullborder.png  <- {source}")
    if args.sheet:
        sheet = Image.new("RGB", (W * 4 // 2 + 30, H * 3 // 2 + 40), (36, 36, 40))
        for i, face in enumerate(made):
            small = face.resize((W // 2, H // 2), Image.LANCZOS)
            sheet.paste(small, (10 + (i % 4) * (W // 2 + 4), 10 + (i // 4) * (H // 2 + 10)))
        sheet.save(args.sheet)
        print("sheet:", args.sheet)


if __name__ == "__main__":
    main()
