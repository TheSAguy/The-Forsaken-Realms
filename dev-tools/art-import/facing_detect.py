"""facing_detect.py - decide which way a side-view creature sprite faces, and where its head is (round 272).

`art_convert_generic.py` never decided this. It made a sheet self-consistent against its own first Idle frame and
then ASSUMED that frame faced right, which is what shipped nine creatures walking backwards (repaired after the
fact in round 247) - and the same assumption cut a wide creature's portrait from the middle of its body, which is
its belly (repaired in round 245). This module is the missing decision, so the importer can stop assuming.

`head_end(frame)` returns ("left"|"right", confidence 0..1). Confidence is the margin between the two ends, so a
caller can refuse to guess: the importer marks a sheet `facing uncertain` below CONFIDENT and leaves it for review
rather than shipping it backwards.

The cues, all measured inside the alpha bounding box, all scored as "how much more does the LEFT third carry than
the RIGHT third", and all picked because they survive the hard case - long-bodied wyrms, where a plain "thicker
end" test picks a dragon's tail and the Grim Executioner's axe:

  detail    high-frequency luminance energy in the upper half. Faces carry eyes, teeth, horns and jaw lines; a
            tail and a haunch are comparatively smooth.
  chroma    colour spread in the upper half - eyes and mouths break a body's flat palette.
  reach     how high the silhouette reaches at each end. A head end is usually raised; a tail drops.
  taper     how much of an end's bulk survives into its outermost tenth. A tail thins to a tip; a head does not.

Validated against the only ground truth there is: the nine sheets round 247 mirrored (so their shipped frames now
face RIGHT) and the seven round 245/247 judged by eye to face right already. Every sheet is scored twice, as
shipped and mirrored, so the set is balanced and a cue that merely always says "right" scores 50%.

usage: python facing_detect.py            # per-cue accuracy on the labelled set, then the ensemble
       python facing_detect.py --all      # every importer-layout atlas: verdict and confidence
"""
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
PLANE = os.path.normpath(os.path.join(HERE, "..", "..", "forge-gui", "res", "adventure", "The Forsaken Realms"))
ROOT = os.path.join(PLANE, "sprites", "enemy")

# Below this margin the importer must not decide on its own - see the module docstring.
CONFIDENT = 0.18

WEIGHTS = {"detail": 0.45, "chroma": 0.25, "reach": 0.15, "taper": 0.15}

# Ground truth, from portrait_and_facing.py's two applied rounds. Every one of these faces RIGHT as shipped.
FACES_RIGHT_NOW = [
    # round 247 mirrored these nine because they faced left
    "tyrant_rex", "stormcrag_griffin", "bonewhite_lizardragon", "ashen_spinewyrm", "shellplate_wyrm",
    "megamouth_wyrm", "astral_wyrm", "furhorn_wyvern", "aurelian_dragon",
    # round 245/247 looked at these and found them already right-facing
    "void_dragon", "ironstride_construct", "serpleg_stalker",
    # round 245 cut their portraits from the RIGHT end, i.e. that is where the head is
    "shellback_ankylosaur", "magmaback_crawler", "mossback_dragon", "skyreef_shark",
]
# Deliberately excluded: three-quarter and frontal views, where "which end is the head" has no answer.
FRONTAL = ["crimson_burrower", "brood_spawn"]


def _thirds(values):
    third = max(1, len(values) // 3)
    return sum(values[:third]), sum(values[-third:])


def _score(left, right):
    """+1 means all of it sits on the left, -1 all on the right."""
    total = left + right
    return 0.0 if total <= 0 else (left - right) / float(total)


def cues(frame):
    """Every cue's left-vs-right score for one RGBA frame. Empty dict if the frame has no pixels."""
    box = frame.getbbox()
    if not box:
        return {}
    im = frame.crop(box)
    w, h = im.size
    px = im.load()
    upper = max(1, int(h * 0.5))

    opaque = [[1 if px[x, y][3] > 40 else 0 for y in range(h)] for x in range(w)]
    lum = [[(px[x, y][0] + px[x, y][1] + px[x, y][2]) / 3.0 if px[x, y][3] > 40 else None
            for y in range(h)] for x in range(w)]

    detail, chroma, reach, thick = [], [], [], []
    for x in range(w):
        d = c = 0.0
        for y in range(upper):
            if lum[x][y] is None:
                continue
            for nx, ny in ((x + 1, y), (x, y + 1)):
                if nx < w and ny < h and lum[nx][ny] is not None:
                    d += abs(lum[x][y] - lum[nx][ny])
            r, g, b = px[x, y][:3]
            c += max(r, g, b) - min(r, g, b)
        detail.append(d)
        chroma.append(c)
        col = opaque[x]
        top = next((y for y in range(h) if col[y]), h)
        reach.append(max(0, h - top))
        thick.append(sum(col))

    out = {}
    for name, series in (("detail", detail), ("chroma", chroma), ("reach", reach)):
        out[name] = _score(*_thirds(series))
    # taper: each end's outermost tenth as a fraction of that end's third. A tail's tip is a thin sliver of its
    # third, a head's is not - so the END WITH THE HIGHER FRACTION is the head, which is the same sign convention
    # as the other cues (positive = the left end looks more like a head).
    tenth = max(1, w // 10)
    third = max(1, w // 3)
    left_frac = sum(thick[:tenth]) / float(max(1, sum(thick[:third])))
    right_frac = sum(thick[-tenth:]) / float(max(1, sum(thick[-third:])))
    out["taper"] = _score(left_frac, right_frac)
    return out


def head_end(frame):
    """-> ("left"|"right", confidence 0..1). Confidence is the weighted margin; compare against CONFIDENT."""
    c = cues(frame)
    if not c:
        return "right", 0.0
    margin = sum(WEIGHTS[k] * c[k] for k in WEIGHTS)
    return ("left" if margin > 0 else "right"), abs(margin)


# ---------------------------------------------------------------- evaluation / reporting

def read_atlas(path):
    png, regions, name = None, [], None
    for raw in open(path, encoding="utf-8", errors="ignore"):
        line = raw.rstrip("\r\n")
        if not line.strip():
            continue
        if png is None:
            png = line.strip()
        elif not line.startswith(" ") and ":" not in line:
            name = line.strip()
            regions.append([name, None, None, None, None])
        elif name and line.strip().startswith("xy:"):
            regions[-1][1:3] = [int(v) for v in line.split(":")[1].split(",")]
        elif name and line.strip().startswith("size:") and regions[-1][1] is not None:
            regions[-1][3:5] = [int(v) for v in line.split(":")[1].split(",")]
    return png, [r for r in regions if r[1] is not None and r[3] is not None]


def importer_atlases():
    """(slug, first Idle frame) for every atlas with the importer's signature."""
    for folder, _dirs, files in os.walk(ROOT):
        for f in sorted(files):
            if not f.endswith(".atlas"):
                continue
            png, regions = read_atlas(os.path.join(folder, f))
            avatar = [r for r in regions if r[0] == "Avatar"]
            idle = [r for r in regions if r[0] == "Idle"]
            if not (png and avatar and idle and tuple(avatar[0][1:5]) == (0, 0, 64, 64)):
                continue
            img_path = os.path.join(folder, png)
            if not os.path.exists(img_path):
                continue
            im = Image.open(img_path).convert("RGBA")
            _n, x, y, w, h = idle[0]
            yield f[:-6], im.crop((x, y, x + w, y + h))


def evaluate():
    frames = {slug: fr for slug, fr in importer_atlases() if slug in FACES_RIGHT_NOW}
    missing = [s for s in FACES_RIGHT_NOW if s not in frames]
    if missing:
        print("no importer-layout atlas for: %s" % missing)
    names = sorted(frames)
    per_cue = {k: 0 for k in WEIGHTS}
    ens = 0
    cases = 0
    print("%-24s %8s %8s %8s %8s   %-9s %s" % ("slug (as shipped)", "detail", "chroma", "reach", "taper",
                                               "ensemble", "confidence"))
    for slug in names:
        for mirrored in (False, True):
            fr = frames[slug].transpose(Image.FLIP_LEFT_RIGHT) if mirrored else frames[slug]
            truth = "left" if mirrored else "right"
            c = cues(fr)
            verdict, conf = head_end(fr)
            for k in WEIGHTS:
                if ("left" if c[k] > 0 else "right") == truth:
                    per_cue[k] += 1
            ok = verdict == truth
            ens += 1 if ok else 0
            cases += 1
            if not mirrored:
                print("%-24s %8.2f %8.2f %8.2f %8.2f   %-9s %.2f %s" % (
                    slug[:24], c["detail"], c["chroma"], c["reach"], c["taper"], verdict, conf,
                    "" if ok else "  <-- WRONG"))
            elif not ok:
                print("%-24s %8.2f %8.2f %8.2f %8.2f   %-9s %.2f   <-- WRONG (mirrored)" % (
                    (slug + " [mirrored]")[:24], c["detail"], c["chroma"], c["reach"], c["taper"], verdict, conf))
    print("\n%d cases (%d sheets x as-shipped + mirrored)" % (cases, len(names)))
    for k in sorted(per_cue, key=lambda k: -per_cue[k]):
        print("  cue %-8s %2d/%2d  %.0f%%" % (k, per_cue[k], cases, 100.0 * per_cue[k] / max(1, cases)))
    print("  ENSEMBLE %-6s %2d/%2d  %.0f%%" % ("", ens, cases, 100.0 * ens / max(1, cases)))
    confident = sum(1 for slug in names for m in (False, True)
                    if head_end(frames[slug].transpose(Image.FLIP_LEFT_RIGHT) if m else frames[slug])[1] >= CONFIDENT)
    right_when_confident = sum(
        1 for slug in names for m in (False, True)
        for v, c in [head_end(frames[slug].transpose(Image.FLIP_LEFT_RIGHT) if m else frames[slug])]
        if c >= CONFIDENT and v == ("left" if m else "right"))
    print("  above CONFIDENT=%.2f: %d/%d cases, %d of those correct (%.0f%%)"
          % (CONFIDENT, confident, cases, right_when_confident,
             100.0 * right_when_confident / max(1, confident)))


def report_all():
    print("%-28s %-9s %-5s %s" % ("slug", "head end", "conf", "cues (detail/chroma/reach/taper)"))
    for slug, fr in importer_atlases():
        c = cues(fr)
        if not c:
            continue
        verdict, conf = head_end(fr)
        flag = "" if conf >= CONFIDENT else "   UNCERTAIN"
        note = "  [labelled right]" if slug in FACES_RIGHT_NOW else ("  [frontal]" if slug in FRONTAL else "")
        print("%-28s %-9s %.2f  %5.2f %5.2f %5.2f %5.2f%s%s" % (
            slug[:28], verdict, conf, c["detail"], c["chroma"], c["reach"], c["taper"], flag, note))


if __name__ == "__main__":
    if "--all" in sys.argv:
        report_all()
    else:
        evaluate()
