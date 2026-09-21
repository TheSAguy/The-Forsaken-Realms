"""art_convert_generic.py - the 73 generic (3D / comic render) sheets -> TFR enemy atlases.

These sheets follow one loose layout: a boxed portrait top-left, then bands (rows) of poses - stance, walk, attack, and
often a dying row - some facing right and some left, with watermark text ("www.thegamingpot.com") mixed in as frames.
Per sheet (bands and frame boxes from art_triage3's art_rows.json):
  1. drop text frames (mostly dark ink) and thumbnails (under 40% of the sheet's median frame height); the first
     near-opaque, roughly square frame is the PORTRAIT (the duel avatar);
  2. bands in order: Idle, Walk, Attack; a later band whose last frame lies down is Death (its first frame is Hit);
  3. every frame is made to face the SAME WAY as Idle's first frame: each other frame is flipped when its mirrored
     silhouette matches that reference better than itself. Which way that is comes from the overrides file, NOT
     from this step - see "facing" below;
  4. frames downscaled (LANCZOS) so the Idle body is BODY px, packed bottom-centered into uniform cells;
  5. the duel avatar is the sheet's painted portrait when it has one, else a square cut from the first Idle frame
     at the creature's HEAD end, which is the end it faces (round 272 - it used to be cut from the middle, which
     for anything wider than tall is its belly: the Shellback Ankylosaur showed its hind legs on a standings page).

FACING (round 272). This step used to DEFAULT to "the reference already faces right" and say nothing, so a
left-facing sheet shipped walking backwards - nine of them did, repaired after the fact in round 247, and the same
assumption put five portraits on a creature's flank (round 245). The engine draws an un-suffixed animation facing
right and mirrors it for Left, so the answer has to be right, and nothing on the sheet reliably says which end is
the head: `facing_detect.py` scores 81% against the 16 labelled sheets and is CONFIDENTLY wrong on three, so it is
used to order a review, never to decide. The answer is recorded per sheet in the overrides file as
`"facing": "right"|"left"`, and a sheet without one is converted anyway but reported as UNREVIEWED, both in the
manifest (`"facing": "unreviewed"`) and in a summary line at the end of the run. `facing_review.py` builds the
contact sheet and writes the answers.

Overrides (overrides_generic.json, by sheet index): "facing": "right"|"left" (which way the reference faces;
the legacy key `{"flip": true}` still reads as "left"), "idle"/"walk"/"attack"/"death": band numbers,
"skip": [band numbers], "portrait": false.
usage: python art_convert_generic.py <art dir> <art_rows.json> <out dir> [--only N,N]"""
import json, os, re, statistics, sys
from PIL import Image

ART, ROWS, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
ONLY = set(int(x) for x in sys.argv[sys.argv.index("--only") + 1].split(",")) if "--only" in sys.argv else None
GENERIC = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 15, 18, 19, 21, 22, 23, 25, 26, 27, 28, 29, 31, 32, 33, 34, 35, 36,
           37, 38, 39, 40, 41, 43, 44, 46, 48, 49, 50, 51, 52, 53, 54, 56, 58, 59, 60, 62, 63, 64, 67, 68, 69, 72,
           200, 201, 206, 208, 210, 211, 212, 213, 214, 215, 216, 217, 220, 222, 224, 225, 227]
BODY = 80          # Idle body box (10% trim) after downscaling, atlas px
AVATAR = 64
HERE = os.path.dirname(os.path.abspath(__file__))
OVR_PATH = os.path.join(HERE, "overrides_generic.json")
OVR = json.load(open(OVR_PATH)) if os.path.exists(OVR_PATH) else {}


def alpha_box(img):
    return img.getchannel("A").point(lambda v: 255 if v >= 24 else 0).getbbox()


def dark_ratio(img):
    n = d = 0
    for (r, g, b, a) in img.resize((max(1, img.size[0] // 4), max(1, img.size[1] // 4))).get_flattened_data():
        if a >= 128:
            n += 1
            if max(r, g, b) < 80:
                d += 1
    return d / max(1, n)


def fill_ratio(img):
    a = img.getchannel("A").resize((max(1, img.size[0] // 4), max(1, img.size[1] // 4)))
    px = list(a.get_flattened_data())
    return sum(1 for v in px if v >= 200) / max(1, len(px))


def body_side(img, trim=0.10):
    a = img.getchannel("A").point(lambda v: 1 if v >= 32 else 0)
    w, h = a.size
    px = a.load()
    cols = [sum(px[x, y] for y in range(h)) for x in range(w)]
    rows = [sum(px[x, y] for x in range(w)) for y in range(h)]
    tot = sum(cols)
    if not tot:
        return max(w, h)

    def span(v):
        c, lo, hi = 0, 0, len(v) - 1
        for i, s in enumerate(v):
            c += s
            if c > tot * trim:
                lo = i
                break
        c = 0
        for i, s in enumerate(v):
            c += s
            if c >= tot * (1 - trim):
                hi = i
                break
        return hi + 1 - lo

    return max(span(cols), span(rows))


def silhouette(img, n=24):
    """Alpha mask on an n x n canvas, scaled by the larger side and bottom-centered - pose-tolerant."""
    bb = alpha_box(img)
    im = img.crop(bb) if bb else img
    r = n / max(im.size)
    im = im.resize((max(1, int(im.size[0] * r)), max(1, int(im.size[1] * r))))
    can = Image.new("L", (n, n), 0)
    can.paste(im.getchannel("A"), ((n - im.size[0]) // 2, n - im.size[1]))
    return [1 if v >= 64 else 0 for v in can.get_flattened_data()]


def iou(a, b):
    inter = sum(1 for x, y in zip(a, b) if x and y)
    uni = sum(1 for x, y in zip(a, b) if x or y)
    return inter / max(1, uni)


def mirror(sil, n=24):
    return [sil[y * n + (n - 1 - x)] for y in range(n) for x in range(n)]


def lying(img):
    bb = alpha_box(img)
    return bb is not None and (bb[2] - bb[0]) > 1.35 * (bb[3] - bb[1])


def slug_of(name):
    s = re.sub(r"\.png$", "", name, flags=re.I).lower()
    s = re.sub(r"(characters?|sprites?|sheets?|high|quality|hd|full|comic|3d|rendered|2d|transparent|background|colerless|animated)", " ", s)
    s = re.sub(r"[^a-z0-9]+", "_", s).strip("_")
    return "gen_" + re.sub(r"_+", "_", s)


def convert(idx, name, bands_boxes):
    ov = OVR.get(str(idx), {})
    sheet = Image.open(os.path.join(ART, name)).convert("RGBA")
    portrait = None
    raw = []
    for bi, band in enumerate(bands_boxes):
        if bi in ov.get("skip", []):
            continue
        for box in band:
            fr = sheet.crop(tuple(box))
            bb = alpha_box(fr)
            if bb:
                fr = fr.crop(bb)
                raw.append((bi, fr, dark_ratio(fr), fill_ratio(fr)))
    sheet_med = statistics.median(fr.size[1] for _, fr, _, fill in raw if fill < 0.9) if raw else 1
    bands = []
    for bi in sorted(set(r[0] for r in raw)):
        frames = []
        for _, fr, dark, fill in [r for r in raw if r[0] == bi]:
            # watermark text: dark ink, and short against the sheet's poses (a dark armored pose is as tall as any)
            if (dark >= 0.75 and fr.size[1] < 0.45 * sheet_med and fr.size[1] < fr.size[0]) or dark >= 0.93:
                continue
            if portrait is None and ov.get("portrait", True) and fill >= 0.9 and 0.6 <= fr.size[0] / fr.size[1] <= 1.7:
                portrait = fr
                continue
            if fill >= 0.9:
                continue                                    # a second boxed picture
            frames.append(fr)
        if frames:
            bands.append((bi, frames))
    if not bands:
        return None, "no frames"
    med = statistics.median(f.size[1] for _, fs in bands for f in fs)
    bands = [(bi, [f for f in fs if f.size[1] >= 0.4 * med]) for bi, fs in bands]
    # a frame far wider than its band-mates is a merge (a pose fused with the thumbnail strip or a caption) - drop it
    cleaned = []
    for bi, fs in bands:
        if len(fs) >= 3:
            mw = statistics.median(f.size[0] for f in fs)
            fs = [f for f in fs if f.size[0] <= 1.8 * mw]
        cleaned.append((bi, fs))
    bands = [(bi, fs) for bi, fs in cleaned if fs]
    by_bi = dict(bands)
    order = [bi for bi, _ in bands]

    def pick(key, pos):
        if key in ov:
            return by_bi.get(ov[key])
        return by_bi[order[pos]] if pos < len(order) else None

    anim = {"Idle": pick("idle", 0), "Walk": pick("walk", 1), "Attack": pick("attack", 2)}
    death = None
    if "death" in ov:
        death = by_bi.get(ov["death"])
    elif len(order) >= 4:
        # a dying row ends lying down: far flatter than the creature's own stance (a crawling beast is wide already)
        i0 = anim["Idle"][0]
        stance = i0.size[0] / max(1, i0.size[1])
        for bi in reversed(order[3:]):
            if any(f.size[0] / max(1, f.size[1]) > 1.6 * stance for f in by_bi[bi]):
                death = by_bi[bi]
                break
    if death:
        anim["Hit"], anim["Death"] = death[:1], death[1:] or death[:1]
    anim = {k: v for k, v in anim.items() if v}
    if "Walk" not in anim:
        anim["Walk"] = anim["Idle"]
    # facing: every frame made to agree with Idle's first frame, then the whole sheet turned to face right.
    # Round 272: which way the reference faces is RECORDED, not assumed - see the module docstring. An
    # unreviewed sheet is still converted (self-consistent, and half of them are right by luck) but says so.
    ref = silhouette(anim["Idle"][0])
    recorded = ov.get("facing", "left" if ov.get("flip", False) else None)
    reviewed = recorded in ("left", "right")
    ref_flip = recorded == "left"
    flips = 0
    for k in anim:
        out = []
        for f in anim[k]:
            s = silhouette(f)
            same = iou(s, ref) >= iou(mirror(s), ref)
            flip = ref_flip if same else not ref_flip
            if flip:
                f = f.transpose(Image.FLIP_LEFT_RIGHT)
                flips += 1
            out.append(f)
        anim[k] = out
    # scale: Idle body -> BODY px, one factor for the whole sheet
    factor = BODY / max(1, body_side(anim["Idle"][0]))
    for k in anim:
        anim[k] = [f.resize((max(1, round(f.size[0] * factor)), max(1, round(f.size[1] * factor))), Image.LANCZOS) for f in anim[k]]
    base = anim["Idle"] + anim["Walk"]
    cw = max(f.size[0] for f in base) + 2
    ch = max(f.size[1] for f in base) + 2
    names = [k for k in ("Idle", "Walk", "Attack", "Hit", "Death") if k in anim]
    ncol = max(len(anim[k]) for k in names)
    if portrait is not None:
        # a painted portrait is already a framed head - its middle is the right place to cut
        side = min(portrait.size)
        px0, py0 = (portrait.size[0] - side) // 2, 0
        av = portrait.crop((px0, py0, px0 + side, py0 + side)).resize((AVATAR, AVATAR), Image.LANCZOS)
    else:
        # Round 272: no painted portrait, so the avatar is cut from the first Idle frame - at the HEAD end, not
        # the middle. Every frame above was just turned to face right, so the head is at the right end; that is
        # the same answer round 245 reached by eye for all five it repaired ("right", 5 of 5). Only a frame
        # appreciably wider than tall needs this - a humanoid's head is top-centre, where the old cut already
        # landed - so the narrow case keeps its previous framing exactly.
        f0 = anim["Idle"][0]
        bb = alpha_box(f0) or (0, 0, f0.size[0], f0.size[1])
        bw, bh = bb[2] - bb[0], bb[3] - bb[1]
        side = min(bw, bh)
        ax = max(bb[0], bb[2] - side) if bw >= 1.25 * bh else bb[0] + (bw - side) // 2
        av = f0.crop((ax, bb[1], ax + side, bb[1] + side)).resize((AVATAR, AVATAR), Image.LANCZOS)
    slug = slug_of(name)
    page = Image.new("RGBA", (max(ncol * cw, AVATAR), AVATAR + len(names) * ch), (0, 0, 0, 0))
    page.paste(av, (0, 0))
    lines = [slug + ".png", "size: %d,%d" % page.size, "format: RGBA8888", "filter: Nearest,Nearest", "repeat: none",
             "Avatar", "  xy: 0, 0", "  size: %d, %d" % (AVATAR, AVATAR)]
    for ri, k in enumerate(names):
        y = AVATAR + ri * ch
        for ci, f in enumerate(anim[k]):
            if f.size[0] > cw or f.size[1] > ch:
                r = min(cw / f.size[0], ch / f.size[1])
                f = f.resize((max(1, int(f.size[0] * r)), max(1, int(f.size[1] * r))), Image.LANCZOS)
            page.paste(f, (ci * cw + (cw - f.size[0]) // 2, y + ch - f.size[1] - 1))
            lines += [k, "  xy: %d, %d" % (ci * cw, y), "  size: %d, %d" % (cw, ch)]
    d = os.path.join(OUT, slug)
    os.makedirs(d, exist_ok=True)
    page.save(os.path.join(d, slug + ".png"))
    open(os.path.join(d, slug + ".atlas"), "w", newline="\n").write("\n".join(lines) + "\n")
    return {"slug": slug, "source": name, "index": idx, "status": "ok", "cell": [cw, ch],
            "idle_visible": list(anim["Idle"][0].size), "counts": {k: len(anim[k]) for k in names},
            "facing": recorded if reviewed else "unreviewed",
            "notes": (["portrait"] if portrait is not None else ["no portrait"]) + ["flips %d" % flips]
                     + ([] if reviewed else ["FACING UNREVIEWED"]),
            "bands": order}, ""


def main():
    cat = json.load(open(ROWS))
    by_index = {v["index"]: k for k, v in cat.items()}
    mp = os.path.join(OUT, "manifest_generic.json")
    man = json.load(open(mp)) if (ONLY and os.path.exists(mp)) else {}
    for idx in GENERIC:
        if ONLY and idx not in ONLY:
            continue
        name = by_index[idx]
        try:
            info, err = convert(idx, name, cat[name]["bands"])
        except Exception as ex:
            info, err = None, repr(ex)
        if info is None:
            man["#%d" % idx] = {"source": name, "index": idx, "status": "fail", "notes": [err]}
            print("#%-4d FAIL %s  %s" % (idx, name, err))
            continue
        man[info["slug"]] = info
        print("#%-4d %-40s bands %-18s %s %s" % (idx, info["slug"][:40], info["bands"], info["counts"], " ".join(info["notes"])))
    json.dump(man, open(mp, "w"), indent=1)
    # Round 272: the run does not end quietly on an unanswered facing question. This is the whole point of
    # recording the answer instead of defaulting to "right" - a sheet nobody looked at used to be
    # indistinguishable from one that was checked, and nine of them shipped walking backwards.
    unreviewed = sorted(v["index"] for v in man.values() if v.get("facing") == "unreviewed")
    if unreviewed:
        print("\n!! %d sheet(s) converted with an UNREVIEWED facing: %s"
              % (len(unreviewed), ",".join(str(i) for i in unreviewed)))
        print("   Each one is self-consistent but may face left, which the engine draws walking backwards, and")
        print("   its portrait is cut at whichever end that makes the head. Settle them before shipping:")
        print("       python facing_review.py %s --out facing_review.png" % OUT)
        print("   then re-run this converter for the ones it wrote a \"facing\" into (--only N,N).")
    else:
        print("\nfacing: all %d sheet(s) carry a recorded answer." % len(man))


if __name__ == "__main__":
    main()
