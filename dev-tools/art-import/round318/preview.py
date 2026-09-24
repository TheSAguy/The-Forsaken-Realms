"""preview.py - preview.png: the 43 new enemies and the 4 re-skins as the game will draw them.

Each card: name, colors, rank; the Avatar (the duel portrait, boxed); then ON ONE GROUND LINE, at the game's own sizes
magnified x4 with nearest-neighbour sampling (the atlases use Nearest filtering), a 16-px hero (human_m, the stock
hero) beside the enemy's Idle frame in the facing it spawns with (CharacterSprite.load() sets Idle + Right) and its
first Walk frame (walking Down, toward the viewer; front-only sheets have one view).
Size = frame x EnemyData.scale x the rank cue, the scale being the one enemy_scale.py --write gave it in the TESTED
copy (fakeroot's enemies.json), with TuningData.tierSizeMultiplier()'s frame ceiling applied.
usage: python preview.py [enemies.json]  -> preview.png"""
import json, os, re, sys
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from roster316 import ROSTER, RESKINS, ART

EJ = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "fakeroot", "forge-gui", "res", "adventure",
                                                         "The Forsaken Realms", "world", "enemies.json")
HERO = r"C:\TFR\repo\forge-gui\res\adventure\common\sprites\heroes\human_m"
ZOOM = 4
CUE = {"Common": 13 / 16, "Uncommon": 1.0, "Rare": 20 / 16, "Mythic": 24 / 16}
RANK = {"Common": "Apprentice", "Uncommon": "Adept", "Rare": "Master", "Mythic": "Archmage"}
CAP = 1.4 * 16
try:
    FONT = ImageFont.truetype("arial.ttf", 13)
    SMALL = ImageFont.truetype("arial.ttf", 11)
except OSError:
    FONT = SMALL = ImageFont.load_default()


def regions(path):
    t = open(path, encoding="utf-8").read()
    out = {}
    for m in re.finditer(r"^(\w+)[ \t]*\n\s+xy: (\d+), (\d+)[ \t]*\n\s+size: (\d+), (\d+)", t, re.M):
        out.setdefault(m.group(1), []).append(tuple(int(m.group(i)) for i in range(2, 6)))
    return t.split("\n", 1)[0].strip(), out


def frame(atlas, name):
    page, regs = regions(atlas)
    img = Image.open(os.path.join(os.path.dirname(atlas), page)).convert("RGBA")
    x, y, w, h = regs[name][0]
    return img.crop((x, y, x + w, y + h))


def drawn(fr, scale, tier):
    """CharacterSprite.draw(): frame x scale x tierSizeMultiplier(tier, frameHeight x scale, false)."""
    pre_h = fr.size[1] * scale
    cue = CUE[tier]
    mult = cue if pre_h * cue <= cue * CAP else cue * (cue * CAP) / (pre_h * cue)
    return fr.size[0] * scale * mult, fr.size[1] * scale * mult, mult < cue * 0.999   # at the ceiling = not shrunk


def magnify(fr, w, h):
    return fr.resize((max(1, round(w * ZOOM)), max(1, round(h * ZOOM))), Image.NEAREST)


def card(e, stem, label, kind):
    atlas = os.path.join(HERE, "atlases", stem + ".atlas")
    _, regs = regions(atlas)
    idle_name = "IdleRight" if "IdleRight" in regs else "Idle"
    walk_name = "WalkDown" if "WalkDown" in regs else "Walk"
    idle, walk, av = frame(atlas, idle_name), frame(atlas, walk_name), frame(atlas, "Avatar")
    scale, tier = float(e["scale"]), e["tier"]
    iw, ih, capped = drawn(idle, scale, tier)
    ww, wh, _ = drawn(walk, scale, tier)
    hero = frame(HERO + ".atlas", "IdleRight")
    parts = [("hero 16px", magnify(hero, 16, 16)), ("%s %.0fx%.0f" % (idle_name, iw, ih), magnify(idle, iw, ih)),
             ("%s" % walk_name, magnify(walk, ww, wh))]
    tallest = max(p.size[1] for _, p in parts)
    slot = lambda cap, p: max(p.size[0], 6 * len(cap)) + 14      # room for the caption under a narrow sprite
    W = 12 + 76 + sum(slot(cap, p) for cap, p in parts) + 8
    W = max(W, 330)
    H = 38 + max(tallest + 16, 84)
    c = Image.new("RGBA", (W, H), (58, 84, 52, 255))
    d = ImageDraw.Draw(c)
    for x in range(0, W, 16 * ZOOM // 2):                       # a half-tile grid (8 game px) for scale
        d.line([(x, 34), (x, H)], fill=(64, 92, 57, 255))
    d.rectangle([0, 0, W - 1, 31], fill=(30, 30, 30, 255))
    d.text((6, 2), label[0], font=FONT, fill=(255, 235, 120, 255))
    d.text((6, 17), label[1], font=SMALL, fill=(220, 220, 220, 255))
    ground = H - 12
    d.line([(84, ground), (W - 4, ground)], fill=(30, 40, 25, 255))
    avb = av.resize((64, 64), Image.NEAREST)
    d.rectangle([8, 40, 75, 107], fill=(35, 35, 35, 255), outline=(200, 200, 200, 255))
    c.alpha_composite(avb, (10, 42))
    d.text((10, 109), "Avatar", font=SMALL, fill=(230, 230, 230, 255))
    x = 90
    for cap, p in parts:
        c.alpha_composite(p, (x, ground - p.size[1]))
        d.text((x, ground + 1), cap, font=SMALL, fill=(235, 235, 235, 255))
        x += slot(cap, p)
    if capped:
        d.text((W - 70, 17), "frame-capped", font=SMALL, fill=(255, 120, 120, 255))
    return c


def main():
    E = {e["name"]: e for e in json.load(open(EJ, encoding="utf-8"))}
    cards = []
    for slug, name, rank, colors, theme, extra in ROSTER:
        e = E[name]
        how = {"walk": "walks", "fly": "flies", "front": "front view"}[ART[slug][2]]
        sub = "%s  %s  %s   speed %s  life %s  scale %s" % (colors, RANK[e["tier"]], how, e["speed"], e["life"], e["scale"])
        cards.append(card(e, slug, (name, sub), ART[slug][2]))
    for name, stem, sheet, row in RESKINS:
        e = E[name]
        sub = "RE-SKIN  %s  %s  (coiled dragon row %d)  scale %s" % (e.get("colors"), RANK[e["tier"]], row, e["scale"])
        cards.append(card(e, stem, (name, sub), "front"))
    cols = 4
    cw = max(c.size[0] for c in cards)
    rows = [cards[i:i + cols] for i in range(0, len(cards), cols)]
    rh = [max(c.size[1] for c in r) for r in rows]
    head = 46
    sheet = Image.new("RGBA", (cols * (cw + 8) + 8, head + sum(rh) + 8 * len(rows) + 8), (20, 20, 20, 255))
    d = ImageDraw.Draw(sheet)
    d.text((10, 6), "Round 316 - 43 new enemies + 4 re-skins as the game draws them (x%d, nearest). Size = frame x scale "
           "(enemy_scale.py --write, tested copy) x rank cue (Apprentice 13/16, Adept 1, Master 20/16, Archmage 24/16), "
           "frame ceiling applied." % ZOOM, font=FONT, fill=(255, 255, 255, 255))
    d.text((10, 24), "Each card: the Avatar (duel portrait), then on one ground line the 16-px hero, the enemy's Idle "
           "frame in its spawn facing (Right) and its first Walk frame (Down). Grid = 8 game px.", font=FONT,
           fill=(200, 200, 200, 255))
    y = head
    for r, hgt in zip(rows, rh):
        for i, c in enumerate(r):
            sheet.alpha_composite(c, (8 + i * (cw + 8), y))
        y += hgt + 8
    out = os.path.join(HERE, "preview.png")
    sheet.convert("RGB").save(out)
    print(out, sheet.size)


if __name__ == "__main__":
    main()
