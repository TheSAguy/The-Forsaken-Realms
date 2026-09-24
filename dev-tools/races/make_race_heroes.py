"""Build the four new races' hero sheets, the extended avatar atlas and the plane heroes.json - into a TARGET plane
folder given on the command line (never a default: point it at a scratch copy first, then at the repo plane).

    python make_race_heroes.py <adventure res root> <target plane dir> [--recolor PRESET] [--preview out.png]

<adventure res root>  C:/TFR/repo/forge-gui/res/adventure   (sources: common/sprites/enemy/*, common/sprites/heroes/avatar.*,
                                                              common/world/heroes.json)
<target plane dir>    e.g. .../scratchpad/r316/races/out/The Forsaken Realms   or   .../adventure/The Forsaken Realms
--recolor SPEC        none (default: the enemy art as is) | A | B, optionally per hero: "A,goblin_m=B,angel_m=none"
                      (see RECOLOR below; the user picks from hero_recolor_options.png)

Writes (relative to the target plane):
  sprites/heroes/{goblin,angel,merfolk,vampire}_{m,f}.png + .atlas   64x80, rows Idle/Walk/Attack/Hit/Death, 4 frames,
                                                                  regions IdleRight..DeathRight (side view: the game
                                                                  mirrors Left and reuses Right/Left for Up/Down/diagonals)
  sprites/heroes/avatar_tfr.png + .atlas   common avatar.png (160x368) + 8 new 16px rows = 160x496; every common region
                                           copied verbatim, plus Goblin_m/_f, Angel_m/_f, Merfolk_m/_f, Vampire_m/_f
--walk-from-idle KEYS  e.g. vampire_m,vampire_f: use the Idle frames as the Walk row (the vampires' Walk row is a bat)
  world/heroes.json                        common's 16 races in the SAME order (save files store the race INDEX) + the 4
                                           new ones appended, "avatar" pointing at avatar_tfr.atlas

Source atlases are PARSED, not assumed: angel_1/angel_2 put Idle at y=0 and the Avatar at y=80, every other sheet puts
the Avatar at y=0 and Idle at y=16.
"""
import colorsys
import json
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

if len(sys.argv) < 3:
    raise SystemExit(__doc__)
ADV = sys.argv[1]
TARGET = sys.argv[2]
RECOLOR_PRESET = "none"
PREVIEW = None
if "--recolor" in sys.argv:
    RECOLOR_PRESET = sys.argv[sys.argv.index("--recolor") + 1]
if "--preview" in sys.argv:
    PREVIEW = sys.argv[sys.argv.index("--preview") + 1]
# The vampire sheets' Walk row is a BAT (the enemy flies when it moves). "--walk-from-idle vampire_m,vampire_f" gives
# those heroes their Idle frames as the Walk row instead (they glide in their cape rather than turning into a bat).
WALK_FROM_IDLE = set()
if "--walk-from-idle" in sys.argv:
    WALK_FROM_IDLE = {k.strip() for k in sys.argv[sys.argv.index("--walk-from-idle") + 1].split(",") if k.strip()}
if not os.path.isdir(os.path.join(ADV, "common", "sprites", "enemy")):
    raise SystemExit("not an adventure res root: " + ADV)
if not os.path.isfile(os.path.join(TARGET, "config.json")):
    raise SystemExit("target is not a plane folder (no config.json): " + TARGET)

E = "common/sprites/enemy/"
# race, sex, hero key, source atlas
HEROES = [
    ("Goblin", "m", "goblin_m", E + "humanoid/goblin/goblin.atlas"),
    ("Goblin", "f", "goblin_f", E + "humanoid/goblin/goblin_2.atlas"),
    ("Angel", "m", "angel_m", E + "celestial/angel_2.atlas"),
    ("Angel", "f", "angel_f", E + "celestial/angel_1.atlas"),
    ("Merfolk", "m", "merfolk_m", E + "humanoid/merfolk/merfolk_lord.atlas"),
    ("Merfolk", "f", "merfolk_f", E + "humanoid/merfolk/mermaid.atlas"),
    ("Vampire", "m", "vampire_m", E + "undead/vampire_3.atlas"),
    ("Vampire", "f", "vampire_f", E + "undead/vampire_2.atlas"),
]
ANIMS = ["Idle", "Walk", "Attack", "Hit", "Death"]

# Hue-band recolors: (hue_from_deg, hue_to_deg, min_saturation, shift_deg[, max_value]). Only pixels inside the band move, so outlines,
# greys, skin and gold trim stay. Preset A / B are two looks for the user to pick from the preview.
RECOLOR = {
    "A": {
        "goblin_m": [(80, 115, 0.3, 110)],             # green skin -> blue-grey ("frost goblin"; orange/red come out
                                                       # brown at this sheet's low value and read as goblin_4/goblin_2)
        "goblin_f": [(330, 360, 0.3, 250), (0, 20, 0.35, 250)],   # pink/red skin -> violet
        "angel_m": [(205, 240, 0.35, -90)],            # blue robe -> emerald (the eyes share the robe's blue and follow it)
        "angel_f": [(205, 240, 0.35, -165)],           # blue dress -> gold
        "merfolk_m": [(150, 185, 0.25, 45)],           # teal body -> deep blue
        "merfolk_f": [(150, 185, 0.25, 95), (330, 350, 0.1, -60)],  # cyan body -> violet, pink tail -> coral
        "vampire_m": [(330, 360, 0.5, -130, 0.5)],     # dark red cape -> midnight blue; bright red eyes stay
        "vampire_f": [(255, 300, 0.3, -130), (320, 335, 0.3, -130)],  # purple gown -> dark teal
    },
    "B": {
        "goblin_m": [(80, 115, 0.3, 176)],             # green skin -> plum
        "goblin_f": [(330, 360, 0.3, 200), (0, 20, 0.35, 200)],   # pink skin -> slate blue
        "angel_m": [(205, 240, 0.35, -165)],           # blue robe -> gold
        "angel_f": [(205, 240, 0.35, 70)],             # blue dress -> violet
        "merfolk_m": [(150, 185, 0.25, -60)],          # teal body -> green
        "merfolk_f": [(150, 185, 0.25, 140), (330, 350, 0.1, -140)],
        "vampire_m": [(330, 360, 0.5, 150, 0.5)],      # dark red cape -> emerald; bright red eyes stay
        "vampire_f": [(255, 300, 0.3, 80), (320, 335, 0.3, 80)],  # purple gown -> crimson
    },
}


def parse_atlas(path):
    regs, cur, page = [], None, None
    for raw in open(path, encoding="utf-8", errors="ignore").read().splitlines():
        s = raw.strip()
        if not s:
            cur = None
            continue
        if ":" not in s:
            if s.lower().endswith(".png"):
                page, cur = s, None
                continue
            cur = {"name": s, "page": page}
            regs.append(cur)
        elif cur is not None:
            k, v = s.split(":", 1)
            cur[k.strip()] = v.strip()
    out = {}
    for r in regs:
        x, y = [int(a) for a in r["xy"].split(",")]
        w, h = [int(a) for a in r["size"].split(",")]
        out.setdefault(r["name"], []).append((r["page"], x, y, w, h))
    return out


def recolor(img, bands):
    if not bands:
        return img
    img = img.copy()
    px = img.load()
    for yy in range(img.size[1]):
        for xx in range(img.size[0]):
            r, g, b, a = px[xx, yy]
            if a == 0:
                continue
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            hd = h * 360
            for band in bands:
                lo, hi, smin, shift = band[:4]
                vmax = band[4] if len(band) > 4 else 1.0   # optional: leave bright pixels (eyes) alone
                if lo <= hd <= hi and s >= smin and v <= vmax:
                    nh = ((hd + shift) % 360) / 360
                    nr, ng, nb = colorsys.hsv_to_rgb(nh, s, v)
                    px[xx, yy] = (round(nr * 255), round(ng * 255), round(nb * 255), a)
                    break
    return img


def atlas_text(png_name, w, h, regions):
    lines = [png_name, "size: %d,%d" % (w, h), "format: RGBA8888", "filter: Nearest,Nearest", "repeat: none"]
    for name, x, y in regions:
        lines += [name, "  xy: %d, %d" % (x, y), "  size: 16, 16"]
    return "\n".join(lines) + "\n"


out_heroes = os.path.join(TARGET, "sprites", "heroes")
os.makedirs(out_heroes, exist_ok=True)
# "A,goblin_m=B,angel_m=none" -> default preset A, two per-hero overrides
_default, _per = "none", {}
for tok in RECOLOR_PRESET.split(","):
    tok = tok.strip()
    if "=" in tok:
        k, v = tok.split("=", 1)
        _per[k.strip()] = v.strip()
    elif tok:
        _default = tok
for _p in [_default] + list(_per.values()):
    if _p != "none" and _p not in RECOLOR:
        raise SystemExit("unknown recolor preset " + _p)
bands_for = {key: RECOLOR.get(_per.get(key, _default), {}).get(key) for _r, _s, key, _rel in HEROES}
avatars = {}
built = {}
for race, sex, key, rel in HEROES:
    src = os.path.join(ADV, rel)
    anims = parse_atlas(src)
    folder = os.path.dirname(src)
    pages = {}

    def frame(entry):
        page, x, y, w, h = entry
        if (w, h) != (16, 16):
            raise SystemExit("%s: a %dx%d frame - hero sheets must be 16x16" % (rel, w, h))
        if page not in pages:
            pages[page] = Image.open(os.path.join(folder, page)).convert("RGBA")
        return pages[page].crop((x, y, x + w, y + h))

    sheet = Image.new("RGBA", (64, 80), (0, 0, 0, 0))
    regions = []
    for row, anim in enumerate(ANIMS):
        src_anim = "Idle" if (anim == "Walk" and key in WALK_FROM_IDLE) else anim
        frames = anims.get(src_anim) or anims.get(src_anim + "Right")
        if not frames or len(frames) < 4:
            raise SystemExit("%s: animation %s has %d frame(s), need 4" % (rel, anim, len(frames or [])))
        for col, entry in enumerate(frames[:4]):
            sheet.paste(frame(entry), (col * 16, row * 16))
            regions.append((anim + "Right", col * 16, row * 16))
    sheet = recolor(sheet, bands_for.get(key))
    sheet.save(os.path.join(out_heroes, key + ".png"))
    open(os.path.join(out_heroes, key + ".atlas"), "w", encoding="utf-8", newline="\n").write(
        atlas_text(key + ".png", 64, 80, regions))
    if "Avatar" not in anims:
        raise SystemExit(rel + ": no Avatar region for the portrait")
    avatars["%s_%s" % (race, sex)] = recolor(frame(anims["Avatar"][0]), bands_for.get(key))
    built[key] = sheet
    print("hero   %-10s <- %s%s" % (key, rel, "  recolor " + _per.get(key, _default) if bands_for.get(key) else ""))

# ---- avatar atlas: common's verbatim + 8 new rows
common_av = os.path.join(ADV, "common", "sprites", "heroes")
base = Image.open(os.path.join(common_av, "avatar.png")).convert("RGBA")
if base.size != (160, 368):
    print("WARNING: common avatar.png is %dx%d, expected 160x368 - rows appended below it anyway" % base.size)
order = ["Goblin_m", "Goblin_f", "Angel_m", "Angel_f", "Merfolk_m", "Merfolk_f", "Vampire_m", "Vampire_f"]
av = Image.new("RGBA", (base.size[0], base.size[1] + 16 * len(order)), (0, 0, 0, 0))
av.paste(base, (0, 0))
common_text = open(os.path.join(common_av, "avatar.atlas"), encoding="utf-8").read().replace("\r\n", "\n")
body = common_text.split("\n")
# header = first 5 lines (png, size, format, filter, repeat); keep every region line verbatim
head_end = next(i for i, ln in enumerate(body) if i > 0 and ln.strip() and ":" not in ln.strip())
region_lines = [ln.rstrip() for ln in body[head_end:] if ln.strip()]
new_lines = []
for i, name in enumerate(order):
    y = base.size[1] + 16 * i
    av.paste(avatars[name], (0, y))
    new_lines += [name, "  xy: 0, %d" % y, "  size: 16, 16"]
av.save(os.path.join(out_heroes, "avatar_tfr.png"))
header = ["avatar_tfr.png", "size: %d,%d" % av.size, "format: RGBA8888", "filter: Nearest,Nearest", "repeat: none"]
open(os.path.join(out_heroes, "avatar_tfr.atlas"), "w", encoding="utf-8", newline="\n").write(
    "\n".join(header + region_lines + new_lines) + "\n")
print("avatar sprites/heroes/avatar_tfr.png %dx%d (+%d rows)" % (av.size + (len(order),)))

# ---- heroes.json: common's list, same order, + 4 appended
common_heroes = open(os.path.join(ADV, "common", "world", "heroes.json"), encoding="utf-8").read()
data = json.loads(common_heroes)
names = [h["name"] for h in data["heroes"]]
for race in ["Goblin", "Angel", "Merfolk", "Vampire"]:
    if race in names:
        raise SystemExit("common heroes.json already has " + race)
    low = race.lower()
    data["heroes"].append({"name": race, "female": "sprites/heroes/%s_f.atlas" % low,
                           "male": "sprites/heroes/%s_m.atlas" % low,
                           "femaleAvatar": race + "_f", "maleAvatar": race + "_m"})
data["avatar"] = "sprites/heroes/avatar_tfr.atlas"
os.makedirs(os.path.join(TARGET, "world"), exist_ok=True)
with open(os.path.join(TARGET, "world", "heroes.json"), "w", encoding="utf-8", newline="\n") as fh:
    fh.write("{\n"
             "  // The Forsaken Realms' copy of common/world/heroes.json (round 316): the stock races in the SAME order,\n"
             "  // then Goblin, Angel, Merfolk, Vampire. APPEND ONLY - a save stores the race as an INDEX into this list,\n"
             "  // and config.json's raceEditions / raceShops key on \"name\".\n")
    fh.write("  \"avatar\": \"%s\",\n  \"heroes\": [\n" % data["avatar"])
    fh.write(",\n".join("    " + json.dumps(h) for h in data["heroes"]))
    fh.write("\n  ]\n}\n")
print("json   world/heroes.json: %d races (indices 0-%d unchanged, new %s)" % (
    len(data["heroes"]), len(names) - 1, ", ".join("%d=%s" % (len(names) + i, r) for i, r in enumerate(["Goblin", "Angel", "Merfolk", "Vampire"]))))

# ---- optional preview: each hero at 6x (portrait, Right idle/walk/attack, mirrored Left walk) + game-size strip
if PREVIEW:
    try:
        F = ImageFont.truetype("arial.ttf", 16)
    except OSError:
        F = ImageFont.load_default()
    S = 6
    cols = 7
    W = 170 + cols * (16 * S + 8)
    H = 30 + len(HEROES) * (16 * S + 14)
    img = Image.new("RGB", (W, H), (128, 128, 128))
    d = ImageDraw.Draw(img)
    for c, t in enumerate(["portrait", "idle", "walk 1", "walk 3", "attack 3", "death 4", "walk (Left)"]):
        d.text((170 + c * (16 * S + 8), 6), t, fill=(20, 20, 20), font=F)
    for i, (race, sex, key, rel) in enumerate(HEROES):
        y = 30 + i * (16 * S + 14)
        d.text((8, y + 30), "%s %s\n%s" % (race, "male" if sex == "m" else "female", os.path.basename(rel)), fill=(0, 0, 0), font=F)
        sh = built[key]
        cells = [avatars["%s_%s" % (race, sex)], sh.crop((0, 0, 16, 16)), sh.crop((16, 16, 32, 32)), sh.crop((48, 16, 64, 32)),
                 sh.crop((48, 32, 64, 48)), sh.crop((48, 64, 64, 80)), sh.crop((16, 16, 32, 32)).transpose(Image.FLIP_LEFT_RIGHT)]
        for c, fr in enumerate(cells):
            b = fr.resize((16 * S, 16 * S), Image.NEAREST)
            x = 170 + c * (16 * S + 8)
            d.rectangle((x - 1, y - 1, x + 16 * S, y + 16 * S), outline=(100, 100, 100))
            img.paste(b, (x, y), b)
    img.save(PREVIEW)
    print("preview", PREVIEW)
