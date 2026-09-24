"""build_atlases.py - round 316: cut the RPG Maker MV/MZ character sheets in roster316.ART into TFR enemy atlases.

From the survey's prototype (enemy_art/rpgm_to_atlas.py), with the cells TRIMMED:
  * one crop box per atlas - the union of every frame's opaque pixels, widened to be symmetric about the cell's
    centre (RPG Maker anchors a character at its frame's bottom-centre) - so every cell is the same size, the
    creature never jitters between frames, and its lowest pixel (feet, or the shadow of a flier) sits on the cell's
    bottom row: the actor's position is where it stands.
  * why trim at all: CharacterSprite draws the WHOLE frame and TuningData.tierSizeMultiplier() caps the drawn frame
    at enemySpriteFrameCap (1.4) x the rank's 16-px body (round 261). enemy_scale.py sizes the BODY, so a cell with a
    wide empty margin (the behemoth's 96x132 frame holds a 74x116 creature) would be capped and draw below its rank.
Regions (libGDX text atlas, Nearest filtering, no index lines - the house format):
  walk   Avatar; IdleDown/Left/Right/Up = the neutral frame; WalkDown/Left/Right/Up = 0,1,2,1 (RPG Maker's walk)
  fly    Avatar; Idle<Dir> AND Walk<Dir> = 0,1,2,1 (the wings never stop)
  front  Avatar; un-suffixed Idle and Walk = 0,1,2,1 (CharacterSprite mirrors Right for Left)
The four-frame cycles reuse the cells (drake.atlas shares cells between Idle and Walk the same way).
No Attack / Hit / Death: the art has none; CharacterSprite.setAnimation() skips a missing animation and the
duel/death pauses fall back to their timed defaults (getActionAnimationDuration), as for the plane's other
atlases without them.
Shadows: Bats_recolor.png and Ariman.png draw the ground shadow in OPAQUE gray (127,127,127) - it becomes
translucent black (0,0,0,127), the exact alpha the reaper sheet ships its own shadow with. That gray occurs nowhere
else on either sheet (checked).
usage: python build_atlases.py [out dir] [--only stem,stem]"""
import os, sys
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "tools"))
from rpgm import Sheet
from roster316 import ART, ART_ROOT

OUT = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else os.path.join(HERE, "atlases")
ONLY = set(sys.argv[sys.argv.index("--only") + 1].split(",")) if "--only" in sys.argv else None
DIRS = ["Down", "Left", "Right", "Up"]
CYCLE = [0, 1, 2, 1]
GRAY = (127, 127, 127, 255)
SHADOW = (0, 0, 0, 127)


def load_frames(sheet, pick, kind, shadow):
    """{(row, col): RGBA frame} - rows are Down/Left/Right/Up (0-3) for a direction sheet, just row 0 for front."""
    path = os.path.join(ART_ROOT, sheet)
    blocks = (1, 1) if "$" in os.path.basename(sheet) else (4, 2)
    sh = Sheet(path, blocks, "variants" if kind == "front" else "dirs")
    if shadow:
        px = sh.img.load()
        w, h = sh.img.size
        n = 0
        for y in range(h):
            for x in range(w):
                if px[x, y] == GRAY:
                    px[x, y] = SHADOW
                    n += 1
        assert n > 0, sheet
    if kind == "front":
        frames = {(0, c): sh.frame(0, pick - 1, c) for c in range(3)}
    else:
        frames = {(r, c): sh.frame(pick - 1, r, c) for r in range(4) for c in range(3)}
    return frames, (sh.fw, sh.fh)


def crop_box(frames, fw):
    boxes = [f.getchannel("A").getbbox() for f in frames.values()]
    boxes = [b for b in boxes if b]
    x0, y0 = min(b[0] for b in boxes), min(b[1] for b in boxes)
    x1, y1 = max(b[2] for b in boxes), max(b[3] for b in boxes)
    half = max(fw / 2.0 - x0, x1 - fw / 2.0)          # symmetric about the frame's centre line
    cx0, cx1 = max(0, int(fw / 2.0 - half)), min(fw, int(round(fw / 2.0 + half)))
    return (cx0, y0, cx1, y1)


def is_shadow(p):
    """A ground-shadow pixel: translucent and dark - the keyed bat/eye shadow (0,0,0,127) and the reaper sheet's own
    (0,0,0,127), (35,35,35,127), (0,0,0,191). No figure pixel on these sheets is translucent."""
    return 0 < p[3] < 255 and max(p[:3]) <= 40


def avatar_of(frame, mode):
    """A square close-up (round 286d: the user prefers the close-up to a small fitted body), measured and cut
    WITHOUT the ground shadow - a portrait does not stand on the floor."""
    fig = frame.copy()
    px = fig.load()
    for y in range(fig.size[1]):
        for x in range(fig.size[0]):
            if is_shadow(px[x, y]):
                px[x, y] = (0, 0, 0, 0)
    x0, y0, x1, y1 = fig.getchannel("A").getbbox()
    side = min(x1 - x0, y1 - y0)
    if mode == "head":                                  # the right end of a right-facing creature, vertically centred
        ax, ay = x1 - side, y0 + (y1 - y0 - side) // 2
    elif mode == "bottom":
        ax, ay = x0 + (x1 - x0 - side) // 2, y1 - side
    else:                                               # "top"
        ax, ay = x0 + (x1 - x0 - side) // 2, y0
    return fig.crop((ax, ay, ax + side, ay + side))


def build(stem, spec, out):
    sheet, pick, kind, av_mode, shadow = spec
    frames, (fw, fh) = load_frames(sheet, pick, kind, shadow)
    box = crop_box(frames, fw)
    cw, ch = box[2] - box[0], box[3] - box[1]
    cells = {k: f.crop(box) for k, f in frames.items()}
    if kind == "front":
        av_src = frames[(0, 1)]
    elif av_mode == "head":
        av_src = frames[(2, 1)]                         # Right, neutral
    else:
        av_src = frames[(0, 1)]                         # Down, neutral
    av = avatar_of(av_src, av_mode)
    A = av.size[0]
    rows = 1 if kind == "front" else 4
    page = Image.new("RGBA", (max(3 * cw, A), A + rows * ch), (0, 0, 0, 0))
    page.paste(av, (0, 0))
    for (r, c), cell in cells.items():
        page.paste(cell, (c * cw, A + r * ch))
    xy = lambda r, c: (c * cw, A + r * ch)
    lines = [stem + ".png", "size: %d,%d" % page.size, "format: RGBA8888", "filter: Nearest,Nearest", "repeat: none",
             "Avatar", "  xy: 0, 0", "  size: %d, %d" % (A, A)]

    def region(name, r, c):
        x, y = xy(r, c)
        lines.extend([name, "  xy: %d, %d" % (x, y), "  size: %d, %d" % (cw, ch)])

    if kind == "front":
        for anim in ("Idle", "Walk"):
            for c in CYCLE:
                region(anim, 0, c)
    else:
        for r, d in enumerate(DIRS):
            for c in (CYCLE if kind == "fly" else [1]):
                region("Idle" + d, r, c)
        for r, d in enumerate(DIRS):
            for c in CYCLE:
                region("Walk" + d, r, c)
    os.makedirs(out, exist_ok=True)
    page.save(os.path.join(out, stem + ".png"), optimize=True)
    with open(os.path.join(out, stem + ".atlas"), "w", encoding="utf-8", newline="\n") as fh_:
        fh_.write("\n".join(lines) + "\n")
    return dict(stem=stem, page=page.size, src_cell=(fw, fh), cell=(cw, ch), crop=box, avatar=A,
                regions=sum(1 for l in lines[5:] if not l.startswith(" ")))


def main():
    rows = []
    for stem, spec in ART.items():
        if ONLY and stem not in ONLY:
            continue
        r = build(stem, spec, OUT)
        rows.append(r)
        print("%-26s %-5s src %3dx%-3d -> cell %3dx%-3d crop %-18s avatar %2d  page %3dx%-3d  regions %d" % (
            stem, spec[2], r["src_cell"][0], r["src_cell"][1], r["cell"][0], r["cell"][1], r["crop"], r["avatar"],
            r["page"][0], r["page"][1], r["regions"]))
    print("%d atlases -> %s" % (len(rows), OUT))


if __name__ == "__main__":
    main()
