"""facing_review.py - settle "which way does this sheet face?" once, by looking, and record the answer (round 272).

`art_convert_generic.py` no longer defaults to "the art faces right". It converts a sheet self-consistently and
reports an unanswered one as UNREVIEWED; this tool is how the question gets answered. It builds one contact sheet -
each creature's first Idle frame as converted, beside its mirror - and writes the chosen answers into
`overrides_generic.json`, where they stay for every future run.

Order: unreviewed sheets first, most doubtful first, using `facing_detect.py` to rank them. The detector's guess is
printed beside each row as a starting point ONLY - it scores 81% on the 16 labelled sheets and is confidently wrong
on three of them, so there is deliberately no way to bulk-accept its guesses. The engine draws an un-suffixed
animation facing RIGHT and mirrors it for Left, so a wrong answer here is a creature walking backwards.

usage: python facing_review.py <out dir> [--out facing_review.png]        build the contact sheet
       python facing_review.py <out dir> --set 23=left,41=right           record answers (by sheet index)
       python facing_review.py <out dir> --list                           text only, no image
       python facing_review.py <out dir> --seed-known                     record what rounds 245/247 established

After recording, re-run the converter for the sheets that changed:
       python art_convert_generic.py <art dir> <art_rows.json> <out dir> --only 23,41
"""
import json
import os
import sys

from PIL import Image, ImageDraw

import facing_detect as fd

HERE = os.path.dirname(os.path.abspath(__file__))
OVR_PATH = os.path.join(HERE, "overrides_generic.json")

ROW_H = 132
HALF_W = 250


def load_manifest(out_dir):
    p = os.path.join(out_dir, "manifest_generic.json")
    if not os.path.exists(p):
        sys.exit("no manifest at %s - run art_convert_generic.py first" % p)
    return json.load(open(p))


def first_idle(out_dir, slug):
    """The converted sheet's first Idle cell, as an RGBA image, or None."""
    atlas = os.path.join(out_dir, slug, slug + ".atlas")
    png = os.path.join(out_dir, slug, slug + ".png")
    if not (os.path.exists(atlas) and os.path.exists(png)):
        return None
    _name, regions = fd.read_atlas(atlas)
    idle = [r for r in regions if r[0] == "Idle"]
    if not idle:
        return None
    im = Image.open(png).convert("RGBA")
    _n, x, y, w, h = idle[0]
    return im.crop((x, y, x + w, y + h))


def rows(out_dir):
    man = load_manifest(out_dir)
    # The overrides file is the source of truth, not the manifest: an answer recorded after the last conversion
    # is still an answer, and reading it from the manifest would show it as unreviewed until a re-run.
    ovr = json.load(open(OVR_PATH)) if os.path.exists(OVR_PATH) else {}
    out = []
    for slug, info in man.items():
        if info.get("status") != "ok":
            continue
        frame = first_idle(out_dir, slug)
        if frame is None:
            continue
        guess, conf = fd.head_end(frame)
        entry = ovr.get(str(info.get("index")), {})
        recorded = entry.get("facing", "left" if entry.get("flip") else "unreviewed")
        # head on the left means the sheet faces left
        out.append({"slug": slug, "index": info.get("index"), "recorded": recorded,
                    "guess": guess, "conf": conf, "frame": frame})
    # unreviewed first, then least confident first - the ones most likely to be wrong come to the top
    out.sort(key=lambda r: (r["recorded"] != "unreviewed", r["conf"]))
    return out


def write_answers(pairs):
    ovr = json.load(open(OVR_PATH)) if os.path.exists(OVR_PATH) else {}
    for idx, side in pairs:
        entry = ovr.setdefault(str(idx), {})
        entry["facing"] = side
        entry.pop("flip", None)  # the legacy key would say the same thing twice
        print("sheet #%s facing = %s" % (idx, side))
    json.dump(ovr, open(OVR_PATH, "w"), indent=1, sort_keys=True)
    print("wrote %s - now re-run the converter with --only %s"
          % (os.path.relpath(OVR_PATH, HERE), ",".join(str(i) for i, _ in pairs)))


def seed_known(out_dir):
    """Record the facing of the sheets rounds 245 and 247 actually settled, so a re-import keeps that knowledge.

    Round 247 mirrored nine shipped PNGs, which means the IMPORTED art faced left - without this, re-importing
    those nine would quietly reproduce the creatures walking backwards, which is the trap this round exists to
    close. The other seven were looked at and found already right-facing. The labels are by final slug, so they
    go back through roster179's (sheet slug -> creature name) table to reach a sheet index.
    """
    sys.path.insert(0, HERE)
    from roster179 import ROSTER
    import re as _re

    def final_slug(name):
        return _re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")

    final_to_sheet = {final_slug(name): sheet for sheet, name, *_rest in ROSTER}
    man = load_manifest(out_dir)
    sheet_to_index = {slug: info.get("index") for slug, info in man.items()}

    faces_left = ["tyrant_rex", "stormcrag_griffin", "bonewhite_lizardragon", "ashen_spinewyrm", "shellplate_wyrm",
                  "megamouth_wyrm", "astral_wyrm", "furhorn_wyvern", "aurelian_dragon"]
    faces_right = ["void_dragon", "ironstride_construct", "serpleg_stalker", "shellback_ankylosaur",
                   "magmaback_crawler", "mossback_dragon", "skyreef_shark"]

    pairs, unmapped = [], []
    for side, slugs in (("left", faces_left), ("right", faces_right)):
        for s in slugs:
            sheet = final_to_sheet.get(s)
            idx = sheet_to_index.get(sheet) if sheet else None
            if idx is None:
                unmapped.append(s)
                continue
            pairs.append((str(idx), side))
    if unmapped:
        print("could not map to a generic sheet (imported from a non-generic source, or renamed): %s"
              % ", ".join(unmapped))
    if pairs:
        write_answers(pairs)
    print("%d of 16 labelled sprites recorded; every other sheet stays UNREVIEWED, which is the truth - round"
          " 245's contact sheet only covered the atlases whose avatar was a body crop." % len(pairs))


def contact_sheet(data, target):
    sheet = Image.new("RGBA", (HALF_W * 2 + 190, ROW_H * len(data) + 28), (30, 30, 36, 255))
    draw = ImageDraw.Draw(sheet)
    draw.text((8, 6), "AS CONVERTED", fill=(235, 235, 235, 255))
    draw.text((8 + HALF_W, 6), "MIRRORED", fill=(235, 235, 235, 255))
    draw.text((8 + HALF_W * 2, 6), "the engine draws these facing RIGHT", fill=(170, 170, 170, 255))
    for i, r in enumerate(data):
        y0 = 28 + i * ROW_H
        for k, im in enumerate((r["frame"], r["frame"].transpose(Image.FLIP_LEFT_RIGHT))):
            bb = im.getbbox()
            fr = im.crop(bb) if bb else im
            scale = min((HALF_W - 16) / float(fr.size[0]), (ROW_H - 16) / float(fr.size[1]), 3.0)
            fr = fr.resize((max(1, int(fr.size[0] * scale)), max(1, int(fr.size[1] * scale))), Image.LANCZOS)
            sheet.alpha_composite(fr, (8 + k * HALF_W + (HALF_W - 16 - fr.size[0]) // 2, y0 + ROW_H - 8 - fr.size[1]))
        tx = 8 + HALF_W * 2
        colour = (245, 200, 120, 255) if r["recorded"] == "unreviewed" else (140, 220, 140, 255)
        draw.text((tx, y0 + 6), "#%s %s" % (r["index"], r["slug"][:22]), fill=(235, 235, 235, 255))
        draw.text((tx, y0 + 22), "recorded: %s" % r["recorded"], fill=colour)
        draw.text((tx, y0 + 38), "guess: %s (%.2f)%s" % (r["guess"], r["conf"],
                                                         "" if r["conf"] >= fd.CONFIDENT else " weak"),
                  fill=(170, 170, 170, 255))
        draw.text((tx, y0 + 58), "--set %s=left" % r["index"], fill=(150, 170, 210, 255))
        draw.text((tx, y0 + 74), "--set %s=right" % r["index"], fill=(150, 170, 210, 255))
        draw.line((0, y0 + ROW_H - 1, sheet.size[0], y0 + ROW_H - 1), fill=(60, 60, 70, 255))
    sheet.save(target)
    print("%d sheet(s) -> %s" % (len(data), target))


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    out_dir = sys.argv[1]
    if "--seed-known" in sys.argv:
        seed_known(out_dir)
        return
    if "--set" in sys.argv:
        spec = sys.argv[sys.argv.index("--set") + 1]
        pairs = []
        for part in spec.split(","):
            idx, _, side = part.partition("=")
            if side not in ("left", "right"):
                sys.exit("--set takes index=left or index=right, got %r" % part)
            pairs.append((idx.strip(), side))
        write_answers(pairs)
        return
    data = rows(out_dir)
    unreviewed = [r for r in data if r["recorded"] == "unreviewed"]
    print("%-5s %-26s %-11s %-6s %s" % ("index", "slug", "recorded", "guess", "confidence"))
    for r in data:
        print("%-5s %-26s %-11s %-6s %.2f%s" % (r["index"], r["slug"][:26], r["recorded"], r["guess"], r["conf"],
                                                "" if r["conf"] >= fd.CONFIDENT else "  weak"))
    print("\n%d sheet(s), %d unreviewed" % (len(data), len(unreviewed)))
    if "--list" not in sys.argv:
        target = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv \
            else os.path.join(HERE, "facing_review.png")
        contact_sheet(data, target)


if __name__ == "__main__":
    main()
