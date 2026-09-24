"""rpgm_to_atlas.py - PROTOTYPE (round 316 survey, scratch only): one RPG Maker character -> a TFR enemy atlas.

Direction sheets (rows Down/Left/Right/Up, columns step A / neutral / step B):
    Avatar                      square head crop from the Down neutral frame
    IdleDown IdleLeft IdleRight IdleUp     --idle still: the neutral frame; --idle cycle: 0,1,2,1 (fliers keep flapping)
    WalkDown WalkLeft WalkRight WalkUp     0,1,2,1 (RPG Maker's own walk order)
Front-only variant sheets (each row a color): Avatar, Idle and Walk = that row's 0,1,2,1; the engine mirrors Right for Left.
No Attack / Hit / Death (the art has none; CharacterSprite falls back to short timed pauses, as for 71 atlases today).
The page is the avatar plus the untouched 3x4 block - the four-frame cycles reuse the neutral cell's xy, the way
drake.atlas shares cells between Idle and Walk. Uniform cells, no trimming, so feet never jitter.
Avatar: --avatar down (default) = top-center square of the Down neutral frame - the face of a walker, bat, eye or any
front-only sheet; --avatar right = the square at the HEAD end of the Right neutral frame, for fliers seen from above
whose Down view shows wings first (wyverns, serpents) - the round-272 "cut at the head end" rule; --avatar bottom =
bottom-center square of the front frame, for the behemoth (its face is under the mountain it carries).
--key-shadow: Bats_recolor.png and Ariman.png draw their ground shadow as OPAQUE gray (127,127,127) in the bottom rows of
every frame (a flattened alpha); this turns exactly that color into translucent black (0,0,0,96), which is how the
reaper sheet ships its shadow. Checked: that gray occurs nowhere else on those two sheets.
usage: python rpgm_to_atlas.py <sheet.png> <variant 1-8 | row 1-4> <out dir> <stem> [--blocks 4x2] [--variants]
       [--idle still|cycle] [--avatar down|right|bottom] [--key-shadow]"""
import os, sys
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rpgm import Sheet, DIRS

args = [a for a in sys.argv[1:] if not a.startswith("--")]
SHEET, VAR, OUT, STEM = args[0], int(args[1]), args[2], args[3]
opt = lambda k, d: sys.argv[sys.argv.index(k) + 1] if k in sys.argv else d
BLOCKS = tuple(int(v) for v in opt("--blocks", "1x1" if "$" in os.path.basename(SHEET) else "4x2").split("x"))
VARIANTS = "--variants" in sys.argv
IDLE = opt("--idle", "still")
AV = opt("--avatar", "down")

sh = Sheet(SHEET, BLOCKS, "variants" if VARIANTS else "dirs")
if "--key-shadow" in sys.argv:
    sh.img.putdata([(0, 0, 0, 96) if p == (127, 127, 127, 255) else p for p in sh.img.get_flattened_data()])
fw, fh = sh.fw, sh.fh


def avatar(fr, head_right=False, bottom=False):
    x0, y0, x1, y1 = fr.getchannel("A").getbbox()
    side = min(x1 - x0, y1 - y0)
    ax = x1 - side if head_right else x0 + (x1 - x0 - side) // 2
    ay = y1 - side if bottom else y0
    return fr.crop((ax, ay, ax + side, ay + side))


if VARIANTS:
    cells = {(0, c): sh.frame(0, VAR - 1, c) for c in range(3)}
    av = avatar(cells[(0, 1)], bottom=(AV == "bottom"))
else:
    cells = {(r, c): sh.frame(VAR - 1, r, c) for r in range(4) for c in range(3)}
    av = avatar(cells[(2, 1)], head_right=True) if AV == "right" else avatar(cells[(0, 1)])
A = av.size[0]
rows_used = 1 if VARIANTS else 4
page = Image.new("RGBA", (max(3 * fw, A), A + rows_used * fh), (0, 0, 0, 0))
page.paste(av, (0, 0))
for (r, c), fr in cells.items():
    page.paste(fr, (c * fw, A + r * fh))
xy = lambda r, c: (c * fw, A + r * fh)
lines = [STEM + ".png", "size: %d,%d" % page.size, "format: RGBA8888", "filter: Nearest,Nearest", "repeat: none",
         "Avatar", "  xy: 0, 0", "  size: %d, %d" % (A, A)]


def region(name, r, c):
    x, y = xy(r, c)
    lines.extend([name, "  xy: %d, %d" % (x, y), "  size: %d, %d" % (fw, fh)])


CYCLE = [0, 1, 2, 1]
if VARIANTS:
    for c in CYCLE:
        region("Idle", 0, c)
    for c in CYCLE:
        region("Walk", 0, c)
else:
    for r, d in enumerate(DIRS):
        for c in (CYCLE if IDLE == "cycle" else [1]):
            region("Idle" + d, r, c)
    for r, d in enumerate(DIRS):
        for c in CYCLE:
            region("Walk" + d, r, c)
os.makedirs(OUT, exist_ok=True)
page.save(os.path.join(OUT, STEM + ".png"))
open(os.path.join(OUT, STEM + ".atlas"), "w", newline="\n").write("\n".join(lines) + "\n")
print("%s: page %dx%d, cell %dx%d, avatar %d, regions %d" % (STEM, page.size[0], page.size[1], fw, fh, A,
                                                             sum(1 for l in lines if l and l[0] not in " " and ":" not in l) - 1))
