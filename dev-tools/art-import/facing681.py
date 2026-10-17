"""facing681.py - round 681: the sprites that walk backwards, the blurry tfr2 portraits, the Santa hat (2026-10-16).

The user found Frostcloak Marauder walking backwards. The engine draws an un-suffixed animation facing RIGHT and mirrors
it for Left (CharacterSprite), so a sheet drawn facing left walks backwards. Rounds 245/247/272 settled this for the
generic render sheets only (portrait_and_facing.py, facing_review.py); round 179's Ragnarok Online sheets were never
looked at, and RO draws a monster whichever way its sheet happens to show. Every sprite an enemy still wears that is
not a remake was laid out by Walk frames and judged by eye (facing_detect.py's guess is wrong on Frostcloak itself):

  MIRROR       every animation frame (not the Avatar) flipped inside its own atlas rectangle - the frames are
               bottom-centred in uniform cells, so the alignment holds; no .atlas change. A list of animation names
               flips only those (the Drowned Deckhand's idle faces right, his walk left).
  PLANE_COPY   a stock sprite (forge-gui/res/adventure/common) that faces left is copied into the plane at the same
               relative path, where the game looks first, and mirrored there - the upstream file is never edited.

Also:
  PORTRAITS    round 382's tfr2 sprites had their 64 px duel portrait stretched up smoothly from a small crop (the
               user: "The guys portrait is all blurry" - Tempest Djinn). Each is shrunk back to 32 px and drawn at x2
               with no smoothing: the same framing, crisp pixels. A cut from the small frames instead (the generator's
               whole-creature rule, or a head-and-shoulders square) was tried: the whole creature sat tiny in the box
               and the square missed bodies (Mackenzie's wing, the Twinskull Ogre's side). KEEP_PORTRAIT are left as
               they are: Mackenzie's is already a sharp full figure; the Broodweaver's sprite is no longer worn.
  SANTA_HAT    Frostcloak Marauder is RO's "Orc Santa": its first two Death frames wear a Santa hat the rest of the
               sprite does not. Those two regions leave the .atlas (their cells cleared); Death keeps the fall.

--apply is NOT idempotent (a second mirror flips a sheet back), so it refuses once facing681_done.json exists.
usage:  python facing681.py --preview <out.png>     before / after, nothing written
        python facing681.py --apply                 write the plane files and the ledger
Run --apply only with the game closed and no package running."""
import json
import os
import re
import shutil
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
PLANE = os.path.join(REPO, "forge-gui", "res", "adventure", "The Forsaken Realms")
COMMON = os.path.join(REPO, "forge-gui", "res", "adventure", "common")
LEDGER = os.path.join(HERE, "facing681_done.json")

MIRROR = {
    "sprites/enemy/tfr/sunplume_phoenix.atlas": None,
    "sprites/enemy/tfr/gazestalk_ooze.atlas": None,
    "sprites/enemy/tfr/anvilhorn_minotaur.atlas": None,
    "sprites/enemy/tfr/grimtusk_bowman.atlas": None,
    "sprites/enemy/tfr/frostcloak_marauder.atlas": None,
    "sprites/enemy/tfr/bonetusk_legionnaire.atlas": None,
    "sprites/enemy/tfr/grimtusk_shieldbearer.atlas": None,
    "sprites/enemy/tfr/sackbeak_peddler.atlas": None,
    "sprites/enemy/tfr/mossback_tortoise.atlas": None,
    "sprites/enemy/tfr/cinderscale_salamander.atlas": None,
    "sprites/enemy/tfr/ragged_hermit.atlas": None,
    "sprites/enemy/tfr/drowned_deckhand.atlas": ["Walk"],
    "sprites/enemy/beast/smallmammals/gecko.atlas": None,       # Gev - a stock sprite: PLANE_COPY
}
PLANE_COPY = ["sprites/enemy/beast/smallmammals/gecko.atlas"]
SANTA_HAT = ("sprites/enemy/tfr/frostcloak_marauder.atlas", "Death", (0, 1))
KEEP_PORTRAIT = {"mackenzie", "broodweaver_drider"}


def regions(atlas_text):
    """[(name, x, y, w, h, start, end)] in file order; start/end are the region block's character span."""
    out = []
    for m in re.finditer(r"^(\w+)\n\s+xy: (\d+), (\d+)\n\s+size: (\d+), (\d+)\n(?:\s+\w+: .*\n)*", atlas_text, re.M):
        out.append((m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5)), m.start(), m.end()))
    return out


def page_of(atlas_path, atlas_text):
    return os.path.join(os.path.dirname(atlas_path), atlas_text.split("\n", 1)[0].strip())


def mirrored(atlas_path, anims):
    text = open(atlas_path, encoding="utf-8").read()
    im = Image.open(page_of(atlas_path, text)).convert("RGBA")
    out = im.copy()
    n = 0
    for name, x, y, w, h, _, _ in regions(text):
        if name == "Avatar" or (anims is not None and name not in anims):
            continue
        cell = im.crop((x, y, x + w, y + h))
        out.paste(Image.new("RGBA", (w, h)), (x, y))
        out.paste(cell.transpose(Image.FLIP_LEFT_RIGHT), (x, y))
        n += 1
    return out, n


def repixel(av, k=2):
    """A smooth-stretched portrait back to pixel art: shrunk to 64/k px, alpha cut at half, drawn at xk unsmoothed."""
    n = 64 // k
    small = av.resize((n, n), Image.LANCZOS)
    small.putalpha(small.split()[3].point(lambda v: 255 if v >= 128 else 0))
    tile = Image.new("RGBA", (64, 64))
    tile.alpha_composite(small.resize((n * k, n * k), Image.NEAREST), ((64 - n * k) // 2, (64 - n * k) // 2))
    return tile


def portrait_fix(atlas_path):
    text = open(atlas_path, encoding="utf-8").read()
    av = next(r for r in regions(text) if r[0] == "Avatar")
    im = Image.open(page_of(atlas_path, text)).convert("RGBA")
    old = im.crop((av[1], av[2], av[1] + av[3], av[2] + av[4]))
    tile = repixel(old.resize((64, 64), Image.LANCZOS) if old.size != (64, 64) else old)
    out = im.copy()
    out.paste(Image.new("RGBA", (av[3], av[4])), (av[1], av[2]))
    out.paste(tile if old.size == (64, 64) else tile.resize(old.size, Image.NEAREST), (av[1], av[2]))
    return out, old, tile


def tfr2_atlases():
    d = os.path.join(PLANE, "sprites", "enemy", "tfr2")
    return sorted(os.path.join(d, f) for f in os.listdir(d) if f.endswith(".atlas") and f[:-6] not in KEEP_PORTRAIT)


def santa_fix():
    rel, anim, drop = SANTA_HAT
    p = os.path.join(PLANE, rel)
    text = open(p, encoding="utf-8").read()
    im = Image.open(page_of(p, text)).convert("RGBA")
    cut = [r for r in regions(text) if r[0] == anim]
    gone = [cut[i] for i in drop]
    for _, x, y, w, h, _, _ in gone:
        im.paste(Image.new("RGBA", (w, h)), (x, y))
    for _, _, _, _, _, s, e in sorted(gone, key=lambda r: -r[5]):
        text = text[:s] + text[e:]
    return p, text, im


def main():
    apply = "--apply" in sys.argv
    out = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    if apply and os.path.exists(LEDGER):
        raise SystemExit("facing681_done.json exists: round 681 is applied; a second --apply flips the sheets back")
    for rel in PLANE_COPY:
        src, dst = os.path.join(COMMON, rel), os.path.join(PLANE, rel)
        if apply and not os.path.exists(dst):
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(src, dst)
            text = open(src, encoding="utf-8").read()
            shutil.copyfile(page_of(src, text), page_of(dst, text))
            print("copied the stock %s into the plane" % rel)
    rows = []
    for rel, anims in MIRROR.items():
        p = os.path.join(PLANE, rel) if os.path.exists(os.path.join(PLANE, rel)) else os.path.join(COMMON, rel)
        text = open(p, encoding="utf-8").read()
        before = Image.open(page_of(p, text)).convert("RGBA")
        after, n = mirrored(p, anims)
        shown = anims[0] if anims else "Idle"
        regs = regions(text)
        first = next((r for r in regs if r[0] == shown), None) or next(r for r in regs if r[0] != "Avatar")
        box = (first[1], first[2], first[1] + first[3], first[2] + first[4])
        rows.append((rel, before.crop(box), after.crop(box), n))
        if apply:
            after.save(page_of(os.path.join(PLANE, rel), text))
            print("mirrored %d frame(s) of %s" % (n, rel))
    ports = []
    for p in tfr2_atlases():
        new_page, old, tile = portrait_fix(p)
        ports.append((os.path.basename(p)[:-6], old, tile))
        if apply:
            new_page.save(page_of(p, open(p, encoding="utf-8").read()))
    if apply:
        print("new portraits for %d tfr2 sprites" % len(ports))
        p, text, im = santa_fix()
        open(p, "w", encoding="utf-8", newline="\n").write(text)
        im.save(page_of(p, text))
        print("dropped the Santa hat's two Death frames of %s" % SANTA_HAT[0])
        json.dump({"mirrored": {k: v for k, v in MIRROR.items()}, "plane_copy": PLANE_COPY,
                   "portraits": [n for n, _, _ in ports], "santa_hat": list(SANTA_HAT[:2]) + [list(SANTA_HAT[2])]},
                  open(LEDGER, "w", encoding="utf-8", newline="\n"), indent=1)
    if out:
        Z = 3
        cw = max(max(b.width, a.width) for _, b, a, _ in rows) * Z + 20
        ch = max(max(b.height, a.height) for _, b, a, _ in rows) * Z + 22
        cols = 2
        sheet = Image.new("RGBA", (cols * 2 * cw, ((len(rows) + cols - 1) // cols) * ch + 30), (86, 120, 70, 255))
        d = ImageDraw.Draw(sheet)
        d.text((6, 6), "NOW (walks backwards)  |  MIRRORED (faces right, as the engine expects)", fill=(255, 255, 255, 255))
        for i, (rel, b, a, n) in enumerate(rows):
            ox, oy = (i % cols) * 2 * cw, 30 + (i // cols) * ch
            d.text((ox + 4, oy), "%s (%d frames)" % (os.path.basename(rel)[:-6].replace("_", " "), n), fill=(255, 240, 120, 255))
            for k, f in enumerate((b, a)):
                sheet.alpha_composite(f.resize((f.width * Z, f.height * Z), Image.NEAREST), (ox + k * cw + 6, oy + 14))
        sheet.save(out)
        cols = 10
        ps = Image.new("RGBA", (cols * 140, ((len(ports) + cols - 1) // cols) * 92), (60, 66, 80, 255))
        d = ImageDraw.Draw(ps)
        for i, (n, old, tile) in enumerate(ports):
            ox, oy = (i % cols) * 140, (i // cols) * 92
            ps.alpha_composite(old.resize((64, 64)), (ox + 4, oy + 14))
            ps.alpha_composite(tile, (ox + 72, oy + 14))
            d.text((ox + 4, oy + 2), n[:22], fill=(255, 240, 120, 255))
        ps.save(out[:-4] + "_portraits.png")
        print("preview ->", out, "and", out[:-4] + "_portraits.png")


if __name__ == "__main__":
    main()
